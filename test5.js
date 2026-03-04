const text1 = `Bolt Готовка Розрахунок часу
23,73 zł (Чистий дохід)
Відхилення поїздки не вплине на показник
прийнятих замовлень
Mariusz - 5.0
1 хв • 1 km
Młynarska 35A, Warszawa
11 хв • 7.5 km
Obywatelska 44, Warszawa
Прийняти`;

const text2 = `Bolt 1.3x Высокий спрос
87,93 zł (Чистый доход)
Jerocel 5.0
3 мин 1.1 km
Siennicka 38B, Warszawa 04-393
41 мин 39.2 km
Bohaterów Modlina 57D, Nowy Dwór Mazowiecki
Принять`;

const text3 = `Bolt 1.2x Высокий спрос
60,68 zł (Чистый доход)
Maria 5.0
Текущее место назначения
3 мин 1.2 km
Efraima Schroegera 72, Warszawa 01-822
31 мин 30.7 km
Arrivals/Departures, Warsaw Modlin Airport (WMI)
Принять следующий заказ`;

function parse(text) {
    const priceRegex = /(\d+[\.,]?\d*)\s*(?:zł|zl|z1|z\||zt|pln|pIn|руб|uah)/i;
    const priceMatch = priceRegex.exec(text);
    const rawPrice = priceMatch ? priceMatch[1] : "0";
    let price = parseFloat(rawPrice.replace(",", "."));

    const searchText = priceMatch ? text.substring(priceMatch.index) : text;
    const legsRegex = /(\d+)[^\d]{1,25}?(\d+[\.,]\d+|\d+)\s*(?:km|км|кm|kм)/gi;
    let match;
    let totalTime = 0;
    let totalDist = 0.0;
    while ((match = legsRegex.exec(searchText)) !== null) {
        const t = parseInt(match[1]);
        const d = parseFloat(match[2].replace(',', '.'));
        if (t >= 1 && t <= 95 && d >= 0.1 && d <= 99.0) {
            totalTime += t;
            totalDist += d;
        }
    }

    // Address logic
    const lines = text.split('\n').map(l => l.trim()).filter(l => l.length > 10);
    const addrStrictRegex = /(?:ul\.|улица|ул\.|\bAl\.|Plac|Avenue|Road|Lotnisko|Stacja|Hotel|Mall|Station|Terminal|Port).*/i;
    const houseNumberRegex = /.*[A-ZА-Яa-zа-я]+\s+\d+.*/;

    const potentialAddresses = lines.filter(line => {
        return (addrStrictRegex.test(line) || houseNumberRegex.test(line)) &&
            !/TAXI FILTER PRO/i.test(line) &&
            !/zł|zl/i.test(line) &&
            !/Bolt/i.test(line) &&
            !/Uber/i.test(line) &&
            !/.*\d+\s*(?:km|км|кm|kм).*/i.test(line) &&
            line.length >= 10 && line.length <= 55 &&
            !line.includes("Відхилення") && !line.includes("поїздки"); // added for ukrainian
    });

    console.log("Price:", price, "Time:", totalTime, "Dist:", totalDist);
    console.log("Addresses:", potentialAddresses);
}

console.log("=== Text 1 ==="); parse(text1);
console.log("=== Text 2 ==="); parse(text2);
console.log("=== Text 3 ==="); parse(text3);
