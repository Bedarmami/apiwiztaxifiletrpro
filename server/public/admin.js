function showTab(tabId) {
    document.querySelectorAll('.stats-panel').forEach(s => s.style.display = 'none');
    document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));

    document.getElementById(tabId + '-section').style.display = 'block';
    if (event) event.currentTarget.classList.add('active');

    updateData();
}

async function updateData() {
    try {
        await Promise.all([
            loadKeys(),
            loadOrders(),
            loadDrivers(),
            loadIntel()
        ]);
    } catch (e) {
        console.error("Ошибка загрузки данных админки", e);
    }
}

async function loadKeys() {
    const res = await fetch('/api/admin/keys');
    const data = await res.json();
    document.getElementById('keys-list').innerHTML = data.map(k => `
        <tr>
            <td style="font-family: monospace; color: #00d2ff;">${k.key}</td>
            <td>${k.days}</td>
            <td><span class="status-badge ${k.used ? 'status-used' : 'status-unused'}">${k.used ? 'ИСПОЛЬЗОВАН' : 'АКТИВЕН'}</span></td>
        </tr>
    `).join('');
}

async function loadOrders() {
    const res = await fetch('/api/admin/orders');
    const data = await res.json();
    document.getElementById('orders-list').innerHTML = data.map(o => `
        <tr id="order-${o.id}" class="${o.is_verified ? 'verified-row' : ''}">
            <td style="color: #00ff7f; font-weight: bold; position: relative;">
                ${o.price} zł
                ${o.screenshot ? `
                    <div style="margin-top: 5px;">
                        <a href="/uploads/${o.screenshot}" target="_blank" 
                           style="display: inline-block; padding: 4px 10px; background: #00d2ff; color: #000; border-radius: 4px; font-size: 0.7rem; text-decoration: none; font-weight: bold; box-shadow: 0 0 10px rgba(0,210,255,0.5);">
                           📸 СКРИНШОТ
                        </a>
                    </div>` : ''}
            </td>
            <td style="font-size: 0.8rem;">${o.km} км</td>
            <td style="font-size: 0.7rem;">
                <div style="color: #fff; cursor: pointer; border-bottom: 1px dashed #555; margin-bottom: 3px;" onclick="editOrder(${o.id}, 'pickup', '${o.pickup}')">A: ${o.pickup || '---'}</div>
                <div style="color: #aaa; cursor: pointer; border-bottom: 1px dashed #333;" onclick="editOrder(${o.id}, 'destination', '${o.destination}')">B: ${o.destination}</div>
                ${!o.is_verified ? `<button onclick="verifyOrder(${o.id})" style="font-size: 0.7rem; background: #00ff7f; color: #000; border: none; padding: 3px 10px; margin-top: 8px; border-radius: 4px; font-weight: bold; cursor: pointer;">ОБУЧИТЬ</button>` : '✅'}
            </td>
            <td>
                <span class="status-badge" style="background: ${o.app === 'BOLT' ? '#00cd00' : '#222'}; color: white; padding: 2px 5px; border-radius: 4px; font-size: 0.7rem;">${o.app || '???'}</span>
            </td>
            <td style="font-size: 0.7rem; color: #00d2ff;">
                ${o.lat ? `${o.lat.toFixed(4)}, ${o.lon.toFixed(4)}` : 'нет GPS'}
            </td>
            <td style="font-size: 0.7rem; color: ${o.status === 'TAKEN' ? '#00ff7f' : (o.status === 'IGNORED' ? '#ff4b2b' : '#888')}">
                ${o.status || 'NEW'}
            </td>
        </tr>
    `).join('');
}

async function loadDrivers() {
    const res = await fetch('/api/nearby');
    const data = await res.json();
    document.getElementById('drivers-list').innerHTML = data.map(d => `
        <tr>
            <td style="font-family: monospace;">${d.device_id}</td>
            <td>${d.name}</td>
            <td>${new Date(parseInt(d.last_update)).toLocaleString()}</td>
        </tr>
    `).join('');
}

async function loadIntel() {
    const res = await fetch('/api/admin/intel-all');
    const data = await res.json();
    document.getElementById('intel-list').innerHTML = data.map(i => `
        <tr>
            <td style="color: #00d2ff;">${i.keyword}</td>
            <td>${i.type}</td>
            <td><button onclick="deleteIntel('${i.keyword}')" style="background:none; border: 1px solid red; color: red; cursor:pointer; font-size: 0.6rem; padding: 2px 5px;">УДАЛИТЬ</button></td>
        </tr>
    `).join('');
}

async function deleteIntel(keyword) {
    if (confirm(`Удалить "${keyword}" из базы знаний?`)) {
        await fetch(`/api/admin/intel/${encodeURIComponent(keyword)}`, { method: 'DELETE' });
        updateData();
    }
}

async function generateKey() {
    const days = prompt("На сколько дней создать ключ?", "7");
    if (days) {
        await fetch('/api/admin/generate-key', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ days: parseInt(days) })
        });
        updateData();
    }
}

async function adjustTime() {
    const deviceId = document.getElementById('adj-device-id').value;
    const days = document.getElementById('adj-days').value;
    if (!deviceId || !days) return alert("Заполни все поля");

    const res = await fetch('/api/admin/adjust-time', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ deviceId, days: parseInt(days) })
    });
    alert("Время обновлено!");
    updateData();
}

async function saveSettings() {
    const version = document.getElementById('version-input').value;
    const apk = document.getElementById('apk-input').value;

    if (version) {
        await fetch('/api/admin/set-version', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ version })
        });
    }
    if (apk) {
        await fetch('/api/admin/set-apk', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ link: apk })
        });
    }
    alert("Настройки сохранены!");
    updateData();
}

async function editOrder(id, type, currentVal) {
    const newVal = prompt(`Исправить адрес ${type}:`, currentVal);
    if (newVal !== null) {
        const row = document.getElementById(`order-${id}`);
        const pickup = type === 'pickup' ? newVal : row.querySelector('[onclick*="pickup"]').innerText.replace('A: ', '');
        const dest = type === 'destination' ? newVal : row.querySelector('[onclick*="destination"]').innerText.replace('B: ', '');

        await fetch(`/api/admin/orders/${id}/correct`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ pickup, destination: dest })
        });
        updateData();
    }
}

async function verifyOrder(id) {
    const row = document.getElementById(`order-${id}`);
    const pickup = row.querySelector('[onclick*="pickup"]').innerText.replace('A: ', '');
    const dest = row.querySelector('[onclick*="destination"]').innerText.replace('B: ', '');

    await fetch(`/api/admin/orders/${id}/correct`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ pickup, destination: dest, isVerified: true })
    });
    updateData();
}

updateData();
setInterval(updateData, 10000);
