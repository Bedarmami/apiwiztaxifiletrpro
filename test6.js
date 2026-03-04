const text = `Priorytet
1.8x wysoki popyt
Bolt
36,08 zł (netto)
Daniel • 4.9 *
6 min • 3.1 km
Widok 9, Warszawa 00-023
18 min • 10.1 km
Ulica Mieczysława Wolfkego 12B, Warszawa 01-494
Akceptuję`;

function parse(text) {
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
            !line.includes("Відхилення") && !line.includes("поїздки");
    });

    console.log("Lines:");
    console.log(lines);
    console.log("Addresses:");
    console.log(potentialAddresses);
}

parse(text);
