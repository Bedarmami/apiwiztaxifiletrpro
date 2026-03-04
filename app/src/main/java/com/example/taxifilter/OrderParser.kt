package com.example.taxifilter

data class OrderInfo(
    val price: Double,
    val timeToClient: Int,
    val estimatedDistance: Double,
    val pickupAddress: String? = null,
    val destinationAddress: String? = null,
    val rawText: String,
    val appName: String? = null,
    val confidence: Int = 100,
    val surgeMultiplier: Double = 1.0,
    val passengerRating: Double? = null
)

object OrderParser {
    fun parse(text: String): OrderInfo {
        // === ЦЕНА ===
        // Ищем число с возможным пробелом-разделителем и валюту (PLN, zł, zl, руб...)
        val priceRegex = Regex("""(\d+(?:\s?[\.,]\s?\d+)?)\s*(?:zł|zl|z1|z\||zt|pln|z┼В|pIn|uah|руб|uah|грн)""", RegexOption.IGNORE_CASE)
        val ratingRegex = Regex(""".*\d[\.,]\d\s?[★\*].*""")
        
        val priceMatch = priceRegex.find(text)
        val rawPrice = priceMatch?.groupValues?.get(1) ?: "0"
        var price = rawPrice.replace(" ", "").replace(",", ".").toDoubleOrNull() ?: 0.0
        
        // Коррекция для копеек (если OCR приклеил их без разделителя 4450 -> 44.50)
        if (price > 450.0 && !rawPrice.contains(",") && !rawPrice.contains(".") && !rawPrice.contains(" ")) {
            price /= 100.0
        }

        // === КОЭФФИЦИЕНТ (SURGE) ===
        // Ищем: x2.1, 1.5x, "2x Высокий спрос"
        val surgeRegex = Regex("""(?:x\s?)([1-4](?:[\.,]\d)?)|([1-4](?:[\.,]\d)?)\s?x|(?:спрос|surge|коэффиц|demand|mnoznik).{0,5}([1-4](?:[\.,]\d)?)""", RegexOption.IGNORE_CASE)
        val surgeMatch = surgeRegex.find(text)
        var surgeMultiplier = 1.0
        surgeMatch?.let { m ->
            val raw = listOf(m.groupValues[1], m.groupValues[2], m.groupValues[3]).firstOrNull { it.isNotEmpty() } ?: ""
            surgeMultiplier = raw.replace(",", ".").toDoubleOrNull() ?: 1.0
        }

        // === РЕЙТИНГ ПАССАЖИРА ===
        // Ищем: 4.8 ★, ★ 5.0, "Rating: 4.9"
        val altRatingRegex = Regex("""(?:rating|рейтинг|ocena).{0,5}([3-5][\.,]\d{1,2})|([3-5][\.,]\d{1,2})\s?[★\*]|(?:[★\*])\s?([3-5][\.,]\d{1,2})""", RegexOption.IGNORE_CASE)
        val ratingMatchData = altRatingRegex.find(text)
        val passengerRating = ratingMatchData?.let { m ->
            val raw = m.groupValues[1].ifEmpty { m.groupValues[2].ifEmpty { m.groupValues[3] } }
            raw.replace(",", ".").toDoubleOrNull()
        }

        // Ищем после цены
        val searchText = if (priceMatch != null) {
            text.substring(priceMatch.range.first)
        } else {
            text
        }

        // === ВРЕМЯ + ДИСТАНЦИЯ ===
        // Поддерживаем: "4 min", "2 хв", "31 хв.", "MMH" (ошибка OCR)
        // Разделитель между временем и дистанцией: пробелы, точки, тире, буллиты, скобки
        val legRegex = Regex(
            """(\d+)\s*(?:мин|min|m|м|хв|хв\.|minuty|minut|MMH|mmh|х|x)[^\d]{1,30}?(?:(?:\()?\s?(\d+(?:[\.,]\d+)?)\s?(?:km|км|кm|kм|kn)(?:\)?)?)""",
            RegexOption.IGNORE_CASE
        )
        val legs = legRegex.findAll(searchText)
            .filter { m ->
                val t = m.groupValues[1].toIntOrNull() ?: 0
                val d = m.groupValues[2].replace(",", ".").toDoubleOrNull() ?: 0.0
                t in 1..350 && d in 0.1..500.0
            }
            .toList()

        var totalTime = 0
        var totalDist = 0.0

        if (legs.isNotEmpty()) {
            // Суммируем все найденные участки пути (подача + поездка)
            legs.forEach { m ->
                totalTime += m.groupValues[1].toIntOrNull() ?: 0
                totalDist += m.groupValues[2].replace(",", ".").toDoubleOrNull() ?: 0.0
            }
        }
        
        // Если ничего не нашли через Regex, пробуем фолбэк по чистой дистанции
        if (totalDist == 0.0) {
            val distRegex = Regex("""(?:\()?\s?(\d+[\.,]?\d+)\s*(?:km|км|кm|kм)(?:\)?)?""", RegexOption.IGNORE_CASE)
            val dMatch = distRegex.find(searchText)
            totalDist = dMatch?.groupValues?.get(1)?.replace(",", ".")?.toDoubleOrNull() ?: 0.0
            totalTime = 10 
        }

        // === АДРЕСА ===
        val lines = text.lines().map { it.trim() }.filter { it.length > 5 }
        
        val addrStrictRegex = Regex("""(?:ul\.|улица|ул\.|\bAl\.|Plac|Avenue|Road|Lotnisko|Stacja|Hotel|Mall|Station|Terminal|Port|ulica|uIica|u1ica|aleja|ul\s|Aleje|Bulwar|Рум).*""", RegexOption.IGNORE_CASE)
        val cityHintRegex = Regex("""(?i)(?:Warszawa|Варшава|Kraków|Краков|Wrocław|Вроцлав|Poznań|Познань|Gdańsk|Гданьск|Łódź|Лодзь)""")
        val houseNumberRegex = Regex(""".*\s+\d+[a-zA-Z]?.*""") 

        val potentialAddresses = lines.filter { line ->
            val lower = line.lowercase()
            // Если в строке только км/мин и цифры - это точно не адрес (loesaka = Поездка в OCR)
            val isHeader = (lower.contains("min") || lower.contains("мин") || lower.contains("хв") || lower.contains("mmh")) && 
                           (lower.contains("km") || lower.contains("км") || lower.contains("поездка") || lower.contains("loesaka") || lower.contains("от вас"))
            
            // Адрес должен иметь маркер или заканчиваться на номер дома (пробел + цифра, но не км/мин)
            val isDistanceLine = lower.contains(Regex("""\d+[\.,]?\d*\s*(?:km|км|min|мин)"""))
            val hasAddrMarker = line.contains(addrStrictRegex) || (line.contains(houseNumberRegex) && !isDistanceLine) || line.contains(cityHintRegex)
            
            val isGarbage = lower.contains("zł") || lower.contains("pln") || isHeader ||
                           lower.contains("★") ||
                           lower.contains("uber") || lower.contains("bolt") ||
                           lower.contains("filter") || lower.contains("дохід") || lower.contains("доход") ||
                           lower.contains("відхилення") || lower.contains("не повлияет") || lower.contains("не повлияє") ||
                           lower.contains("прийнятых") || lower.contains("замовлень") || lower.contains("принятия") ||
                           lower.contains("готівка") || lower.contains("оплата") || 
                           lower.contains("радіусу") || lower.contains("радиуса") ||
                           lower.contains("безопасности") || lower.contains("доступ") || 
                           lower.contains("lte") || lower.contains("vzw") || 
                           lower.contains("signal") || line.contains("%") ||
                           line.contains(ratingRegex) ||
                           lower.contains("stats") || lower.contains("активн") || 
                           lower.contains("включено") || lower.contains("dashboard") ||
                           lower.contains("элемент") || lower.contains("фон") ||
                           lower.contains("уведомл") || lower.contains("система") ||
                           lower.contains("сегодня") || lower.contains("макс") || 
                           lower.contains("прибыль") || lower.contains("галерея") ||
                           lower.contains("дополнительно") || lower.contains("кнопка") ||
                           lower.contains("текущ") || lower.contains("место") || lower.contains("назнач") || 
                           lower.contains("откуда") || lower.contains("куда") || lower.contains("пункт") ||
                           lower.contains("tekuu") || lower.contains("mecto") || lower.contains("h3haye") ||
                           lower.contains("destin") || lower.contains("pick") || lower.contains("arrival") ||
                           lower.contains("premium") || lower.contains("edition") || lower.contains("varsavia") ||
                           lower.contains("samsung") || lower.contains("telegram") || lower.contains("youtube") ||
                           lower.contains("music") || lower.contains("статистика") || lower.contains("папка") ||
                           lower.contains("доход") || lower.contains("дохід") || lower.contains("чистый") || 
                           lower.contains("следующий") || lower.contains("текущее") || lower.contains("место") ||
                           lower.contains("закрыть") || lower.contains("все") || 
                           lower.contains("отказ") || lower.contains("akceptuj") || lower.contains("принять")
            
            (!isGarbage && !SmartLearningManager.isLikelyGarbage(line) && line.length in 5..100)
        }
        
        // Удаляем дубликаты и пустые
        val finalAddresses = potentialAddresses.distinct()

        // Улучшенная логика: берем первый и ПОСЛЕДНИЙ валидный адрес 
        // (в Bolt/Uber город часто идет второй строкой, а реальная цель - в самом низу)
        val pickupAddress = cleanAddress(finalAddresses.firstOrNull())
        var destinationAddress: String? = null
        
        if (finalAddresses.size >= 2) {
            val last = finalAddresses.last()
            val secondLast = finalAddresses[finalAddresses.size - 2]
            
            // Если последняя строка короткая (похожа на город), склеиваем её с предпоследней (улицей)
            if (last.length < 15 && finalAddresses.size >= 2) {
                destinationAddress = cleanAddress("$secondLast, $last")
            } else {
                destinationAddress = cleanAddress(last)
            }
            
            // Если вдруг первая и последняя строка совпали (дубль OCR)
            if (pickupAddress == destinationAddress && finalAddresses.size > 2) {
                destinationAddress = cleanAddress(finalAddresses[finalAddresses.size - 2])
            }
        } else if (finalAddresses.size == 1) {
            destinationAddress = "Unknown"
        }

        val appName = when {
            text.contains("bolt", ignoreCase = true) || 
            text.contains("прийняти", ignoreCase = true) || 
            text.contains("akceptuj", ignoreCase = true) -> "BOLT"
            
            text.contains("uber", ignoreCase = true) || 
            text.contains("comfort", ignoreCase = true) || 
            text.contains("priority", ignoreCase = true) ||
            text.contains("share", ignoreCase = true) ||
            text.contains("совпадение", ignoreCase = true) ||
            text.contains("поездка:", ignoreCase = true) -> "UBER"
            else -> null
        }

        val confidenceA = SmartLearningManager.getConfidence(pickupAddress)
        val confidenceB = SmartLearningManager.getConfidence(destinationAddress)
        val avgConfidence = (confidenceA + confidenceB) / 2

        return OrderInfo(
            price, totalTime, totalDist, 
            pickupAddress, destinationAddress, 
            text, appName, avgConfidence,
            surgeMultiplier, passengerRating
        )
    }

    private fun cleanAddress(addr: String?): String? {
        if (addr == null) return null
        return addr.replace(Regex("""\(|\)"""), "")
            .replace(Regex("""(?i)\d+\s*(?:min|мин|хв|minuty|minut)"""), "")
            .replace(Regex("""(?i)\d+[\.,]?\d*\s*(?:km|км)"""), "")
            .replace("•", "")
            .trim()
    }

    fun containsTaxiKeywords(text: String): Boolean {
        val lower = text.lowercase()
        // Признак приложения или кнопки
        val hasApp = listOf("bolt", "uber", "freenow", "itaxi", "yandex").any { lower.contains(it) }
        val hasAction = listOf("принять", "akceptuj", "прийняти", "accept", "заказ", "zamówienie").any { lower.contains(it) }
        
        // Признак "Цена + Дистанция" (самый надежный для OCR)
        val hasPrice = lower.contains(Regex("""\d+.*(?:zł|pln|руб)"""))
        val hasDist = lower.contains(Regex("""\d+.*(?:km|км)"""))
        
        return hasApp || hasAction || (hasPrice && hasDist)
    }

    fun isTripActive(text: String): Boolean {
        // Очищаем текст от лишних пробелов для защиты от плохой OCR
        val normalized = text.lowercase().replace(" ", "")
        
        // Явные индикаторы кнопок снизу во время поездки (RU, PL, EN, UK)
        val activeKeywords = listOf(
            "наместе", "впути", "начатьпоездку", "завершить", // RU
            "namiejscu", "rozpocznij", "wdrodze", "zakończ", "przesuń", "gotowe", // PL
            "arrived", "starttrip", "endtrip", "enroute", "swipe", // EN
            "намісці", "удорозі", "почати", "завершити", "проведіть" // UK
        )
        val hasActiveIndicator = activeKeywords.any { normalized.contains(it) }
        
        // Кнопки принятия заказа
        val acceptKeywords = listOf("принять", "akceptuj", "прийняти", "accept")
        val hasAcceptButton = acceptKeywords.any { normalized.contains(it) }
        
        return hasActiveIndicator || (!hasAcceptButton && !normalized.contains("zł") && normalized.length > 25)
    }
}
