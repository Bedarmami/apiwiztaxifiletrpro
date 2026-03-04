const text = `Bolt
22,75 zł (Чистый доход)
Anna • 5.0 ★
0 мин • 0.1 km
Ulica Urszuli 33, Warszawa 02-419
14 мин • 6.1 km
Dino, Regulska 2D, Michałowice
Принять`;

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
        line.length >= 10 && line.length <= 55;
});

function cleanAddress(addr) {
    if (!addr) return null;
    return addr.replace(/\(|\)/g, "")
        .replace(/\d+\s*(?:min|мин|хв|minuty|minut|MMH|mmh|\w{1,3}H)/gi, "")
        .replace(/\d+[\.,]?\d*\s*(?:km|км)/gi, "")
        .replace(/•/g, "")
        .replace(/^[0-9\sA-Za-z]\s*n\s*$/, "") // clean junk like "0 MMH n"
        .trim();
}

console.log(potentialAddresses.map(cleanAddress));
