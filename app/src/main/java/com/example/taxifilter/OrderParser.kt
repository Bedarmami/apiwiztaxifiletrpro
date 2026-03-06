package com.example.taxifilter

import android.util.Log

data class OrderInfo(
    val price: Double,
    val timeToClient: Int,
    val estimatedDistance: Double,
    val pickupAddress: String? = null,
    val destinationAddress: String? = null,
    val pickupTime: Int? = null,
    val pickupDist: Double? = null,
    val destinationTime: Int? = null,
    val destinationDist: Double? = null,
    val rawText: String,
    val appName: String? = null,
    val confidence: Int = 100,
    val surgeMultiplier: Double = 1.0,
    val passengerRating: Double? = null,
    val netProfit: Double = 0.0,
    val hourlyRate: Int = 0,
    val isProfitable: Boolean = false,
    val isHighlyProfitable: Boolean = false
)

object OrderParser {
    /**
     * ВАЖНО: normalizeNumbers применяется ТОЛЬКО к строке с ценой, НЕ к адресам!
     * Иначе все буквы в адресах превратятся в цифры.
     */
    private fun normalizeNumbers(text: String): String {
        return text.lowercase()
            .replace("l", "1")
            .replace("i", "1")
            .replace("|", "1")
            .replace("!", "1")
            .replace("b", "6")
            .replace("s", "5")
            .replace("o", "0")
            .replace("z", "2")
            .replace(" ", "")
    }

    // Фильтр мусорных строк OCR - расширен по данным реального лога
    private val GARBAGE_LINES = setOf(
        "5g", "4g", "3g", "lte", "h+", "wi-fi", "wifi", "wlan",
        "vo", "во", "бар", "bar", "nfc", "gps", "sync", "online",
        "zł/ч", "/ч", "profit", "ai:"
    )


    private val MAP_ARTIFACTS = setOf(
        "archive", "institute", "museum", "google", "soogle", "certes", "ostrobremal",
        "distribution", "odolany", "witolin", "ostrobrem", "al institute",
        "m archive", "al. jero", "apuo city", "adluo", "sp. z", "sp.z.o.o",
        "kowalstwo", "ślusarstwo", "park okęcie", "gil andrzej",
        // Районы на карте
        "nowodwory", "sadyba", "mokotów", "mokotow", "praga capital",
        "dąbrówka", "choszczówka", "wawer", "radawiec",
        // Кнопки интерфейса (A4scrypted по логу)
        "lpuhatb", "lpuhatm", "lpuhath", "ipuähatm", "pryjmij", "prynaty",
        "3amobneHb", "3aMoBneHb", "3akoblth", "3akpblth",
        // Объяснение из popup нашего приложения
        "pacxon", "nacTpouKax", "kapMaHe", "nohatho", "noHätho",
        "bepaиKT", "aMopTM", "sMopTM"
    )
    // Фильтр: строка в ALL_CAPS без пробелов = название района с карты
    private val allCapsPattern = Regex("""^[A-Z0-9А-ЯІЇЄ\-]{4,}$""")
    private fun isGarbageLine(line: String): Boolean {
        val l = line.trim().lowercase()
        if (l.length <= 3) return true
        if (GARBAGE_LINES.any { l == it || l.contains(it) }) return true
        if (MAP_ARTIFACTS.any { l.contains(it) }) return true
        // Строка целиком из заглавных = название района с карты (NOWODWORY, SADYBA)
        if (allCapsPattern.matches(line.trim())) return true
        return false
    }

    // Паттерн: имя пассажира + рейтинг ("строки по типу Jerocel 5.0 * не являются адресом")
    private val nameRatingPattern = Regex("""^[A-ZА-Я][a-zа-я]+.{0,20}\d[.,]\d\s*[*★t]""")
    private fun isPassengerName(line: String): Boolean = nameRatingPattern.containsMatchIn(line)

    // Паттерн: кнопки действия (принять, отказать)
    private val buttonTextPattern = Regex(
        """(?:pryjmij|akceptuj|принять|отказать|odrzuc|lpuhatb|lpuhatm|ipuatm|lpuhath|reject|decline|3akp|nohatho|nohатно)""",
        RegexOption.IGNORE_CASE
    )
    private fun isButtonText(line: String): Boolean = buttonTextPattern.containsMatchIn(line)

    fun parse(text: String, commissionPct: Float = 25f, fuelCost: Float = 0.40f): OrderInfo {
        // === ЦЕНА ===
        // Сначала ищем Bolt-формат: "Цена PLN/zł (чистый доход)"
        // Надёжнее, чем простое findFirst(цена) — особенно при попадании СТАРЫХ данных в OCR
        val boltNetPriceRegex = Regex(
            """(\d+(?:[.,\s]\d+)?)\s*(?:zł|pln|PLN|z1|zt)\s*\((?:4ucT|YucT|HucT|UncT|HMcT|YMcT|4ncT|uncT|4MCT|unCT)""",
            RegexOption.IGNORE_CASE
        )
        val priceRegex = Regex("""(\d+(?:[\s.,]\d+)?)\s*(?:zł|zl|z1|z\||zt|pln|pIn|uah|руб|грн)""", RegexOption.IGNORE_CASE)

        // Сначала пробуем Bolt-специфичный паттерн (цена + "чистый доход")
        val boltNetMatch = boltNetPriceRegex.find(text)
        val priceMatch = boltNetMatch ?: priceRegex.find(text)
        var price = 0.0

        priceMatch?.let { m ->
            val rawValue = m.groupValues[1]
            val cleanValue = normalizeNumbers(rawValue).replace(",", ".")
            price = cleanValue.toDoubleOrNull() ?: 0.0
        }
        
        // Fallback: Если основная регулярка не сработала, ищем просто любую крупную цифру в начале или на отдельной строке
        if (price == 0.0) {
            val fallbackRegex = Regex("""(?:\n|^)\s*(\d{1,3}[.,]\d{2})\s*(?:\n|$)""")
            fallbackRegex.find(text)?.let { m ->
                val rawValue = m.groupValues[1]
                price = rawValue.replace(",", ".").toDoubleOrNull() ?: 0.0
            }
        }

        if (price > 500.0) price /= 100.0

        // === КОЭФФИЦИЕНТ (SURGE) ===
        val surgeRegex = Regex("""(x\s?)([1-4](?:[.,]\d)?)|([1-4](?:[.,]\d)?)\s?x|(?:спрос|surge|коэффиц|demand|mnoznik).{0,5}([1-4](?:[.,]\d)?)""", RegexOption.IGNORE_CASE)
        val surgeMatch = surgeRegex.find(text)
        var surgeMultiplier = 1.0
        surgeMatch?.let { m ->
            val raw = listOf(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4]).firstOrNull { it.isNotEmpty() } ?: ""
            surgeMultiplier = raw.replace(",", ".").toDoubleOrNull() ?: 1.0
        }

        // === РЕЙТИНГ ПАССАЖИРА ===
        val altRatingRegex = Regex("""(?:rating|рейтинг|ocena).{0,5}([3-5][.,]\d{1,2})|([3-5][.,]\d{1,2})\s?[★*]|[★*]\s?([3-5][.,]\d{1,2})""", RegexOption.IGNORE_CASE)
        val ratingMatchData = altRatingRegex.find(text)
        val passengerRating = ratingMatchData?.let { m ->
            val raw = m.groupValues[1].ifEmpty { m.groupValues[2].ifEmpty { m.groupValues[3] } }
            raw.replace(",", ".").toDoubleOrNull()
        }

        val searchText = if (priceMatch != null) text.substring(priceMatch.range.first) else text

        // === ДЕДУПЛИКАЦИЯ OCR перед парсингом ===
        // 🔴 ИСПРАВЛЕНО: без этого "4 MMH •1.4 km" попадало 2 раза → 198 мин вместо 97 мин
        val dedupText = text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString("\n")
        val dedupSearchText = if (priceMatch != null) {
            val priceOffset = dedupText.indexOf(priceMatch.value.take(6))
            if (priceOffset >= 0) dedupText.substring(priceOffset) else dedupText
        } else dedupText

        // === ВРЕМЯ + ДИСТАНЦИЯ ===
        val legRegex = Regex(
            // 🔴 ИСПРАВЛЕНО: \D{0,60} (было {1,60}) — чтобы получало "18 MMH6.1 km" без пробела
            """(\d+)\s*(?:мин|min|m|м|хв|хв\.|minuty|minut|MMH|mmh|х|x)\D{0,60}?(?:(\()? \s?(\d+(?:[.,]\d+)?)\s?(?:km|км|кm|kм|kn)(\)?)?)""",
            RegexOption.IGNORE_CASE
        )
        val legs = legRegex.findAll(dedupSearchText)
            .filter { m ->
                val t = m.groupValues[1].toIntOrNull() ?: 0
                val d = (m.groupValues.getOrNull(3) ?: "0").replace(",", ".").toDoubleOrNull() ?: 0.0
                t in 1..350 && d in 0.1..500.0
            }
            .toList()

        var totalTime = 0
        var totalDist = 0.0
        var pTime: Int? = null
        var pDist: Double? = null
        var dTime: Int? = null
        var dDist: Double? = null

        if (legs.isNotEmpty()) {
            legs.forEachIndexed { index, m ->
                val t = m.groupValues[1].toIntOrNull() ?: 0
                val distStr = m.groupValues.getOrNull(3) ?: m.groupValues.getOrNull(2) ?: ""
                val d = distStr.replace(",", ".").toDoubleOrNull() ?: 0.0
                
                totalTime += t
                totalDist += d
                
                if (index == 0) {
                    pTime = t
                    pDist = d
                } else if (index == 1) {
                    dTime = t
                    dDist = d
                }
            }
        }
        
        if (totalDist == 0.0) {
            val distRegex = Regex("""\(?\s?(\d+[.,]?\d+)\s*(?:km|км|кm|kм)\)?""", RegexOption.IGNORE_CASE)
            val dMatch = distRegex.find(dedupSearchText)
            totalDist = dMatch?.groupValues?.get(1)?.replace(",", ".")?.toDoubleOrNull() ?: 0.0
            totalTime = 10
            pDist = totalDist
            pTime = totalTime
        }

        // === АДРЕСА ===
        // --- 1H PROFIT STYLE: Leg-Anchored Parsing ---
        // Bolt/Uber layout: [Leg 1] [Address A] [Leg 2] [Address B]
        // Ищем адреса относительно меток времени/расстояния
        
        var pickupAddress: String? = null
        var destinationAddress: String? = null

        val addrStrictRegex = Regex("""(?:ul\.|улица|ул\.|\bAl\.|Plac|Avenue|Road|Lotnisko|Stacja|Hotel|Mall|Station|Terminal|Port|ulica|uIica|u1ica|aleja|ul\s|Aleje|Bulwar|Рум).*""", RegexOption.IGNORE_CASE)
        val cityHintRegex = Regex("""(?i)(?:Warszawa|Варшава|Kraków|Краков|Wrocław|Вроцлав|Poznań|Познань|Gdańsk|Гданьск|Łódź|Лодзь)""")
        val houseNumberRegex = Regex(""".*\s+\d+[a-zA-Z]?.*""") 

        // Адреса ищем по dedupText (без дубликатов) чтобы не путаться
        val legsList = legRegex.findAll(dedupText).toList().sortedBy { it.range.first }

        if (legsList.size >= 2) {
            val leg1 = legsList[0]
            val leg2 = legsList[1]
            
            // Текст между первой и второй легой - это точка А
            val textBetween = dedupText.substring(leg1.range.last + 1, leg2.range.first).trim()
            val linesBetween = textBetween.lines().map { it.trim() }.filter {
                it.length > 5 && !it.contains(Regex("""\d+[.,]\d{2}""")) && !it.contains("★") &&
                !isGarbageLine(it) && !SmartLearningManager.isLikelyGarbage(it)
            }
            // Выбираем лучшую строку для адреса А
            pickupAddress = cleanAddress(
                linesBetween.find { it.contains(addrStrictRegex) || it.contains(houseNumberRegex) }
                    ?: linesBetween.firstOrNull()
            )

            // 🔴 ИСПРАВЛЕНО: если адрес А не нашли между легами, смотрим ДО leg1
            // (Bolt иногда показывает улицу ДО первой леги время/дистанции)
            if (pickupAddress == null) {
                val textBeforeLeg1 = dedupText.substring(0, leg1.range.first).trim()
                val linesBefore = textBeforeLeg1.lines().map { it.trim() }.filter {
                    it.length > 5 && !it.contains(Regex("""|\d+[.,]\d{2}""")) && !it.contains("★") &&
                    !isGarbageLine(it) && !SmartLearningManager.isLikelyGarbage(it) &&
                    !isPassengerName(it) && !isButtonText(it)
                }
                // 🔴 БЕЗ lastOrNull() фоллбэка! Только строгий матч
                // (иначе возьмём имя пассажира как "Jerocel" вместо адреса)
                pickupAddress = cleanAddress(
                    linesBefore.findLast { it.contains(addrStrictRegex) || it.contains(houseNumberRegex) }
                    // НЕТ lastOrNull() здесь!
                )
            }

            // Текст после второй леги - это точка Б
            val textAfter = dedupText.substring(leg2.range.last + 1).trim()
            val linesAfter = textAfter.lines().map { it.trim() }.filter {
                it.length > 5 && !it.contains(Regex("""\d+[.,]\d{2}""")) && !it.contains("★") &&
                !isGarbageLine(it) && !isButtonText(it) && !isPassengerName(it) &&
                !SmartLearningManager.isLikelyGarbage(it)
            }
            // Выбираем лучшую строку для адреса Б
            destinationAddress = cleanAddress(
                linesAfter.find { it.contains(addrStrictRegex) || it.contains(houseNumberRegex) }
                    ?: linesAfter.firstOrNull()
            )

            // Альтернативный Layout Bolt: оба адреса после leg2
            // если А не нашли между легами и до leg1, но есть два адреса после leg2 — берём первый=A, последний=B
            if (pickupAddress == null && linesAfter.size >= 2) {
                val strictLines = linesAfter.filter { it.contains(addrStrictRegex) || it.contains(houseNumberRegex) }
                if (strictLines.size >= 2) {
                    pickupAddress = cleanAddress(strictLines.first())
                    destinationAddress = cleanAddress(strictLines.last())
                } else if (linesAfter.size >= 2 && pickupAddress == null) {
                    pickupAddress = cleanAddress(linesAfter.first())
                    // destinationAddress already set above
                }
            }

        }  // end if (legsList.size >= 2)

        if (pickupAddress == null || destinationAddress == null) {
            val lines = text.lines().map { it.trim() }.filter { it.length > 5 }

            val potentialAddresses = lines.filter { line ->
                val lower = line.lowercase()
                val isDistanceLine = lower.contains(Regex("""\d+[.,]?\d*\s*(?:km|км|min|мин)"""))
                val hasAddrMarker = line.contains(addrStrictRegex) || (line.contains(houseNumberRegex) && !isDistanceLine) || line.contains(cityHintRegex)
                
                val isUIElement = lower.contains("uber") || lower.contains("bolt") || 
                                  lower.contains("filter") || lower.contains("дохід") || lower.contains("доход") ||
                                  lower.contains("прийняти") || lower.contains("akceptuj") || lower.contains("zysk") ||
                                  lower.contains("netto") || lower.contains("income") || lower.contains("zł") || lower.contains("pln") ||
                                  lower.contains("высокий") || lower.contains("спрос") || lower.contains("wysoki") ||
                                  lower.contains(Regex("""\d+[.,]\d{2}"""))

                (!isUIElement && !SmartLearningManager.isLikelyGarbage(line) && !isGarbageLine(line) && (hasAddrMarker || line.length in 8..80))
            }
            
            val finalAddresses = potentialAddresses.distinct()
                .filter { !it.contains("★") && it.length > 5 && !it.matches(Regex("""^\d+[.,]?\d*$""")) }
            
            if (pickupAddress == null) pickupAddress = cleanAddress(finalAddresses.firstOrNull())
            if (destinationAddress == null) {
                if (finalAddresses.size >= 2) {
                    val last = finalAddresses.last()
                    val secondLast = finalAddresses[finalAddresses.size - 2]
                    destinationAddress = if (last.length < 8) cleanAddress("$secondLast, $last") else cleanAddress(last)
                } else if (finalAddresses.size == 1 && pickupAddress != null) {
                    destinationAddress = "Unknown"
                }
            }
        }

        // --- 1H PROFIT: City Normalization ---
        pickupAddress = verifyAndReplaceCity(pickupAddress)
        destinationAddress = verifyAndReplaceCity(destinationAddress)

        // --- 1H PROFIT: Geocoding Validation (Optional but recommended) ---
        // if (pickupAddress != null) {
        //    val geo = AddressVerifier.verify(pickupAddress)
        //    if (geo != null) pickupAddress = geo.placeName ?: pickupAddress
        // }

        // Детектируем приложение с устойчивостью к OCR-ошибкам
        val textLower = text.lowercase()
        val appName = when {
            // Bolt: прямое имя + типичные OCR-замены + кнопка «Akceptuj»
            textLower.contains("bolt") || textLower.contains("b0lt") || textLower.contains("bo1t") ||
            textLower.contains("akceptuj") || textLower.contains("odrzuc") -> "BOLT"
            // Uber: прямое имя + OCR-замены + типичные фразы
            textLower.contains("uber") || textLower.contains("u6er") || textLower.contains("u8er") ||
            textLower.contains("comfort") || textLower.contains("accept trip") -> "UBER"
            // FreeNow
            textLower.contains("freenow") || textLower.contains("free now") || textLower.contains("mytaxi") -> "FREENOW"
            else -> "App"
        }

        val confidenceA = SmartLearningManager.getConfidence(pickupAddress)
        val confidenceB = SmartLearningManager.getConfidence(destinationAddress)
        val avgConfidence = (confidenceA + confidenceB) / 2

        // === РАСЧЕТ ПРИБЫЛИ (как в accesreed: включаем waitTime 2 мин) ===
        val commission = commissionPct / 100.0
        val fuelCostPerKm = fuelCost.toDouble()
        val waitTimeMin = 2.0 // среднее ожидание у клиента (как в accesreed OrderData)
        val serviceTimePerOrder = 2.0
        
        val totalActiveTime = (if (totalTime > 0) totalTime else 15) + serviceTimePerOrder + waitTimeMin
        val netRevenue = price * (1 - commission)
        val fuelExpense = totalDist * fuelCostPerKm
        val netProfit = netRevenue - fuelExpense
        val hourlyRate = if (totalActiveTime > 0) (netProfit / (totalActiveTime / 60.0)).toInt() else 0

        Log.d("OrderParser", "Parsed: Price=$price, Dist=$totalDist, App=$appName, Pickup=$pickupAddress")

        return OrderInfo(
            price, totalTime, totalDist, 
            pickupAddress, destinationAddress, 
            pickupTime = pTime, pickupDist = pDist,
            destinationTime = dTime, destinationDist = dDist,
            rawText = text, appName = appName, confidence = avgConfidence,
            surgeMultiplier = surgeMultiplier, passengerRating = passengerRating,
            netProfit = netProfit,
            hourlyRate = hourlyRate,
            isProfitable = hourlyRate >= 60,
            isHighlyProfitable = hourlyRate >= 70
        )
    }

    private fun cleanAddress(addr: String?): String? {
        if (addr == null) return null
        // Удаляем "хвостики" цен, рейтинги и другие артефакты OCR
        val trashRegex = Regex("""(?i)(?:\d+[.,]\d{2}.*|★.*|5\.0.*|4\.\d.*|zł/ч|/ч|доход.*|\d+\s*km|\d+\s*км|min|мин|хв|minuty|minut|чистый|netto|zł|pln).*""")
        var res = addr.replace(trashRegex, "").replace("➲", "").replace(Regex("""[()]"""), "").replace("•", "").trim()
        
        // Удаляем карту и случайные слова из заведений рядом
        val mapArtifacts = listOf("WITOLIN", "Certes", "Flagowa", "Poprzecz", "Mieczysława", "Dino", "Regulska", "Agatki", "Spiżarka")
        mapArtifacts.forEach { res = res.replace(it, "", ignoreCase = true).trim() }
        
        res = res.trim(',', ' ', '.', '-')
        return if (res.length < 5) null else res
    }

    fun containsTaxiKeywords(text: String): Boolean {
        val lower = text.lowercase()
        // Ищем символы валюты: zł, pln, грн, руб, €, $
        val hasPrice = lower.contains(Regex("""\d+.*(?:zł|pln|грн|руб|€|\$)""", RegexOption.IGNORE_CASE))
        // Ищем расстояние: км, km, m, м
        val hasDist = lower.contains(Regex("""\d+.*(?:km|км|м|m)""", RegexOption.IGNORE_CASE))
        // Ищем ключевые слова брендов и кнопок действий (АКЦЕПТ, ПРИНЯТЬ)
        val hasKeywords = lower.contains("bolt") || lower.contains("uber") || lower.contains("taxi") || 
                         lower.contains("freenow") || lower.contains("kurs") || lower.contains("zlecenie") ||
                         lower.contains("принять") || lower.contains("akceptuj") || lower.contains("подтвердить")

        // Прямо как в 1 Hour: Показываем плашку если есть хоть что-то похожее на заказ
        return hasKeywords || (hasPrice && hasDist) || (hasPrice && lower.length < 500)
    }

    private fun verifyAndReplaceCity(addr: String?): String? {
        if (addr == null) return null
        val cityHintRegex = Regex("""(?i)(Warszawa|Kraków|Wrocław|Poznań|Gdańsk|Łódź|Warsaw|Białystok|Katowice|Lublin|Szczecin|Bydgoszcz)""")
        val match = cityHintRegex.find(addr)
        if (match != null) {
            val rawCity = match.value
            val verified = SmartLearningManager.verifyCity(rawCity)
            if (verified != null && verified != rawCity) {
                return addr.replace(rawCity, verified, ignoreCase = true)
            }
        }
        return addr
    }

    fun isTripActive(text: String): Boolean {
        val normalized = text.lowercase().replace(" ", "")
        return normalized.contains("наместе") || normalized.contains("впути") || normalized.contains("namiejscu")
    }
}
