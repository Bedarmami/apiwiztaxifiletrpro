// --- Инициализация карты ---
const map = L.map('map').setView([52.2297, 21.0122], 12); // Центр Варшавы

// Тёмный стиль карты (CartoDB Dark Matter)
L.tileLayer('https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png', {
    attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors &copy; <a href="https://carto.com/attributions">CARTO</a>',
    subdomains: 'abcd',
    maxZoom: 20
}).addTo(map);

const driverMarkers = {};

// Иконка для водителя
const driverIcon = L.divIcon({
    className: 'custom-driver-icon',
    html: '<div style="background-color: #00ff7f; width: 12px; height: 12px; border-radius: 50%; border: 2px solid white; box-shadow: 0 0 10px #00ff7f;"></div>',
    iconSize: [12, 12],
    iconAnchor: [6, 6]
});

// --- Функции обновления данных ---

async function updateDashboard() {
    try {
        // 1. Статистика
        const statsRes = await fetch('/api/stats');
        const stats = await statsRes.json();

        document.getElementById('active-drivers').innerText = stats.activeDrivers;
        document.getElementById('total-orders').innerText = stats.totalOrders;
        document.getElementById('intel-size').innerText = stats.intelSize;

        // 2. Водители на карте
        const driversRes = await fetch('/api/nearby');
        const drivers = await driversRes.json();

        // Убираем старых, если они пропали
        const currentIds = drivers.map(d => d.lat + "_" + d.lon); // У нас нет id в nearby, используем координаты для теста
        // В реальном API лучше использовать deviceId

        drivers.forEach(driver => {
            const id = driver.name; // Пока используем имя как ключ
            if (driverMarkers[id]) {
                driverMarkers[id].setLatLng([driver.lat, driver.lon]);
            } else {
                driverMarkers[id] = L.marker([driver.lat, driver.lon], { icon: driverIcon })
                    .addTo(map)
                    .bindPopup(`<b>${driver.name}</b><br>Активен: ${new Date(driver.lastUpdate).toLocaleTimeString()}`);
            }
        });

        // 3. Умные адреса
        const intelRes = await fetch('/api/intel');
        const intel = await intelRes.json();

        const whiteList = document.getElementById('whitelist-list');
        whiteList.innerHTML = intel.whitelist.map(item => `<li>${item}</li>`).join('');

        const blackList = document.getElementById('blacklist-list');
        blackList.innerHTML = intel.blacklist.map(item => `<li>${item}</li>`).join('');

    } catch (error) {
        console.error("Ошибка обновления дашборда:", error);
    }
}

// Запуск цикла обновления
updateDashboard();
setInterval(updateDashboard, 5000); // Обновляем каждые 5 секунд
