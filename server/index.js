require('dotenv').config();
const express = require('express');
const bodyParser = require('body-parser');
const cors = require('cors');
const path = require('path');
const { Pool } = require('pg');
const { Telegraf } = require('telegraf');
const { GoogleGenerativeAI } = require("@google/generative-ai");

const app = express();
const PORT = process.env.PORT || 3000;
const GEMINI_KEY = process.env.GEMINI_KEY;

if (!GEMINI_KEY) {
    console.error("❌ GEMINI_KEY is not defined in .env file!");
}

// Инициализация Gemini
const genAI = new GoogleGenerativeAI(GEMINI_KEY || "dummy_key");
const genAI_v1 = new GoogleGenerativeAI(GEMINI_KEY || "dummy_key");

async function listModels() {
    if (!GEMINI_KEY || GEMINI_KEY === "dummy_key") return;
    try {
        const lastFour = GEMINI_KEY.slice(-4);
        console.log(`🔑 Проверка ключа (заканчивается на: ...${lastFour})`);
        const fetch = (...args) => import('node-fetch').then(({ default: fetch }) => fetch(...args));
        const response = await fetch(`https://generativelanguage.googleapis.com/v1/models?key=${GEMINI_KEY}`);
        const data = await response.json();
        console.log("📋 Доступные модели Gemini:");
        if (data.models) {
            data.models.forEach(m => console.log(` - ${m.name}`));
        } else {
            console.log("⚠️ Список моделей пуст или ошибка:", JSON.stringify(data));
        }
    } catch (e) {
        console.error("❌ Не удалось получить список моделей:", e.message);
    }
}
listModels();

async function analyzeWithVision(base64Image) {
    if (!GEMINI_KEY || GEMINI_KEY === "dummy_key") return null;
    const modelsToTry = [
        "gemini-2.5-flash", "gemini-2.5-flash-lite",
        "gemini-2.5-pro", "gemini-2.0-flash-exp"
    ];
    for (const modelName of modelsToTry) {
        try {
            console.log(`🤖 Пробую модель: ${modelName}`);
            const model = genAI.getGenerativeModel({ model: modelName });
            const prompt = "Проанализируй скриншот заказа такси. Найди адрес ПОДАЧИ (A) и адрес НАЗНАЧЕНИЯ (B). ВАЖНО: Адрес назначения (B) обычно самый нижний в списке. Если видишь город и улицу на разных строках, объедини их. Также найди мусорные слова (названия приложений). Ответ СТРОГО в JSON: { \"pickup\": \"Улица, Город\", \"destination\": \"Улица, Город\", \"junk\": [] }";
            const result = await model.generateContent([
                { text: prompt },
                { inlineData: { data: base64Image, mimeType: "image/jpeg" } }
            ]);
            const response = await result.response;
            const text = response.text();
            console.log(`✅ [AI SUCCESS] Ответила модель ${modelName}:`, text);
            const jsonMatch = text.match(/\{.*\}/s);
            return jsonMatch ? JSON.parse(jsonMatch[0]) : null;
        } catch (e) {
            console.error(`⚠️ Ошибка ${modelName}:`, e.message);
            if (e.message.includes("API_KEY_INVALID") || e.message.includes("expired")) {
                console.error("❌ КРИТИЧЕСКАЯ ОШИБКА: Ключ не принят Google.");
                return null;
            }
        }
    }
    return null;
}

// ПОДКЛЮЧЕНИЕ К БАЗЕ ДАННЫХ
const pool = new Pool({
    connectionString: process.env.DATABASE_URL,
    ssl: { rejectUnauthorized: false }
});

// ИНИЦИАЛИЗАЦИЯ БАЗЫ
async function initDb() {
    const client = await pool.connect();
    try {
        await client.query(`
            CREATE TABLE IF NOT EXISTS drivers (device_id TEXT PRIMARY KEY, name TEXT, lat DOUBLE PRECISION, lon DOUBLE PRECISION, last_update BIGINT);
            CREATE TABLE IF NOT EXISTS intel (keyword TEXT PRIMARY KEY, type TEXT);
            CREATE TABLE IF NOT EXISTS orders (
                id SERIAL PRIMARY KEY, 
                price REAL, 
                km REAL, 
                destination TEXT, 
                pickup TEXT,
                lat DOUBLE PRECISION,
                lon DOUBLE PRECISION,
                app TEXT,
                status TEXT,
                device_id TEXT, 
                timestamp BIGINT
            );
            CREATE TABLE IF NOT EXISTS keys (key TEXT PRIMARY KEY, days INTEGER, used BOOLEAN DEFAULT FALSE);
            CREATE TABLE IF NOT EXISTS subs (device_id TEXT PRIMARY KEY, expiry BIGINT);
            CREATE TABLE IF NOT EXISTS config (key TEXT PRIMARY KEY, value TEXT);
            
            -- 🆕 Таблица баг-репортов
            CREATE TABLE IF NOT EXISTS reports (
                id SERIAL PRIMARY KEY,
                device_id TEXT,
                device_model TEXT,
                is_good BOOLEAN DEFAULT FALSE,
                log_text TEXT,
                timestamp BIGINT,
                reviewed BOOLEAN DEFAULT FALSE
            );
            
            INSERT INTO config (key, value) VALUES ('app_version', '1.2.5') ON CONFLICT DO NOTHING;
            INSERT INTO config (key, value) VALUES ('bot_link', 'https://t.me/your_bot_name') ON CONFLICT DO NOTHING;
            INSERT INTO config (key, value) VALUES ('apk_link', 'https://your-download-link.com/app.apk') ON CONFLICT DO NOTHING;
            
            ALTER TABLE orders ADD COLUMN IF NOT EXISTS raw_text TEXT;
            ALTER TABLE orders ADD COLUMN IF NOT EXISTS screenshot TEXT;
            ALTER TABLE orders ADD COLUMN IF NOT EXISTS is_verified BOOLEAN DEFAULT FALSE;
            ALTER TABLE orders ADD COLUMN IF NOT EXISTS hourly_rate REAL DEFAULT 0;
            ALTER TABLE orders ADD COLUMN IF NOT EXISTS time_min INTEGER DEFAULT 0;
        `);
        console.log("✅ Database tables ready");
    } finally {
        client.release();
    }
}
initDb().catch(console.error);

// ТЕЛЕГРАМ БОТ
const bot = new Telegraf(process.env.BOT_TOKEN || 'YOUR_BOT_TOKEN_HERE');

bot.start(async (ctx) => {
    ctx.reply(`🚕 Добро пожаловать!\n\n1. Введи ID устройства (из приложения), чтобы получить 24 часа БЕСПЛАТНОГО теста.\n2. Используй /apk чтобы скачать актуальную версию.\n3. Для продления введи купленный ключ.`);
});

bot.command('apk', async (ctx) => {
    const res = await pool.query('SELECT value FROM config WHERE key = $1', ['apk_link']);
    const link = res.rows[0]?.value || "Ссылка не настроена";
    ctx.reply(`📦 Актуальная версия:\n${link}`);
});

bot.on('text', async (ctx) => {
    const msg = ctx.message.text.trim();
    if (msg.length > 10 && !msg.startsWith('/')) {
        const deviceId = msg;
        const check = await pool.query('SELECT * FROM subs WHERE device_id = $1', [deviceId]);
        if (check.rows.length === 0) {
            const expiry = Date.now() + (24 * 60 * 60 * 1000);
            await pool.query('INSERT INTO subs (device_id, expiry) VALUES ($1, $2)', [deviceId, expiry]);
            ctx.reply(`✅ Тест-драйв активирован на 24 часа! Перезапусти приложение.`);
        } else {
            ctx.reply(`❌ Это устройство уже было зарегистрировано.`);
        }
    }
});

bot.command('gen', async (ctx) => {
    const days = parseInt(ctx.message.text.split(' ')[1]) || 7;
    const key = `TAXI-${days}-${Math.random().toString(36).substring(2, 7).toUpperCase()}`;
    await pool.query('INSERT INTO keys (key, days) VALUES ($1, $2)', [key, days]);
    ctx.reply(`Ключ на ${days} дн.: \`${key}\``, { parse_mode: 'Markdown' });
});

bot.launch()
    .then(() => console.log("Telegram Bot started"))
    .catch((err) => {
        if (err.response && err.response.error_code === 409) {
            console.warn("⚠️ Telegram Bot: Конфликт 409. Пропускаю...");
        } else {
            console.error("❌ Telegram Bot Error:", err);
        }
    });

app.use(cors());
app.use(bodyParser.json({ limit: '50mb' }));
app.use(bodyParser.urlencoded({ limit: '50mb', extended: true }));
app.use(express.static(path.join(__dirname, 'public')));

// --- ЭНДПОИНТЫ API ---

// 1. Обновление локации
app.post('/api/location', async (req, res) => {
    const { deviceId, lat, lon, name } = req.body;
    if (!deviceId) return res.status(400).send("No deviceId");
    await pool.query(
        'INSERT INTO drivers (device_id, name, lat, lon, last_update) VALUES ($1, $2, $3, $4, $5) ON CONFLICT (device_id) DO UPDATE SET lat=$3, lon=$4, last_update=$5',
        [deviceId, name || "Водитель", lat, lon, Date.now()]
    );
    const count = await pool.query('SELECT COUNT(*) FROM drivers WHERE last_update > $1', [Date.now() - 300000]);
    res.json({ status: "ok", nearby: parseInt(count.rows[0].count) - 1 });
});

// 2. Список водителей
app.get('/api/nearby', async (req, res) => {
    const result = await pool.query('SELECT * FROM drivers WHERE last_update > $1', [Date.now() - 300000]);
    res.json(result.rows);
});

// 3. Статус и подписка
app.get('/api/status', async (req, res) => {
    const { deviceId, version } = req.query;
    const configRes = await pool.query('SELECT value FROM config WHERE key = $1', ['app_version']);
    const serverVersion = configRes.rows[0]?.value || "1.2.5";
    const updateRequired = version !== serverVersion;
    const botRes = await pool.query('SELECT value FROM config WHERE key = $1', ['bot_link']);
    const botLink = botRes.rows[0]?.value || "";
    const apkRes = await pool.query('SELECT value FROM config WHERE key = $1', ['apk_link']);
    const apkDownloadLink = apkRes.rows[0]?.value || "";
    const sub = await pool.query('SELECT expiry FROM subs WHERE device_id = $1', [deviceId]);
    const expiry = sub.rows[0]?.expiry || 0;
    const isActive = expiry > Date.now();
    res.json({
        isActive, expiry, updateRequired, serverVersion, botLink, apkDownloadLink,
        message: updateRequired ? `Обновитесь до ${serverVersion}!` : (isActive ? "Ок" : "Нужен ключ")
    });
});

// 4. Активация
app.post('/api/activate', async (req, res) => {
    const { deviceId, key } = req.body;
    const keyCheck = await pool.query('SELECT days FROM keys WHERE key = $1 AND used = FALSE', [key]);
    if (keyCheck.rows.length > 0) {
        const days = keyCheck.rows[0].days;
        const duration = days * 24 * 60 * 60 * 1000;
        const sub = await pool.query('SELECT expiry FROM subs WHERE device_id = $1', [deviceId]);
        const currentExpiry = Math.max(sub.rows[0]?.expiry || 0, Date.now());
        const newExpiry = currentExpiry + duration;
        await pool.query('INSERT INTO subs (device_id, expiry) VALUES ($1, $2) ON CONFLICT (device_id) DO UPDATE SET expiry = $2', [deviceId, newExpiry]);
        await pool.query('UPDATE keys SET used = TRUE WHERE key = $1', [key]);
        res.json({ status: "success", expiry: newExpiry });
    } else {
        res.status(400).json({ status: "error", message: "Ключ не подходит" });
    }
});

// 5. Умная авто-коррекция адресов
async function smartAutoCorrect(order, screenshotBase64) {
    const garbage = [
        "отказ", "не повлияет", "процент", "принятия", "заказов", "akceptuj", "принять", "min", "km", "мин",
        "premium", "edition", "varsavia", "samsung", "telegram", "youtube", "music", "статистика", "папка"
    ];
    let pickup = order.pickup || "";
    let dest = order.destination || "";
    let isAutoVerified = false;

    if (screenshotBase64) {
        const aiResult = await analyzeWithVision(screenshotBase64);
        if (aiResult && aiResult.destination) {
            pickup = aiResult.pickup || pickup;
            dest = aiResult.destination;
            isAutoVerified = true;
            const allText = `${pickup} ${dest}`;
            const words = allText.split(/[\s,.-]+/).filter(w => w.length > 3);
            for (const w of words) {
                const kw = w.toLowerCase();
                await pool.query("INSERT INTO intel (keyword, type) VALUES ($1, 'whitelist') ON CONFLICT (keyword) DO UPDATE SET type = 'whitelist'", [kw]);
            }
            if (aiResult.junk && Array.isArray(aiResult.junk)) {
                for (const j of aiResult.junk) {
                    if (j.length > 3) {
                        const kw = j.toLowerCase();
                        await pool.query("INSERT INTO intel (keyword, type) VALUES ($1, 'garbage') ON CONFLICT (keyword) DO UPDATE SET type = 'garbage'", [kw]);
                    }
                }
            }
        }
    }

    garbage.forEach(word => {
        if (pickup.toLowerCase().includes(word)) pickup = "";
        if (dest.toLowerCase().includes(word)) dest = "";
    });

    if (!isAutoVerified) {
        const { rows } = await pool.query("SELECT keyword FROM intel WHERE type='whitelist'");
        const whitelist = rows.map(r => r.keyword.toLowerCase());
        for (const word of whitelist) {
            if (dest && dest.toLowerCase().includes(word) && word.length > 3) {
                isAutoVerified = true;
                break;
            }
        }
    }

    if (!pickup && order.raw_text) {
        const lines = order.raw_text.split('\n').filter(l => l.length > 10);
        if (lines.length > 0) pickup = lines[0].trim();
    }

    return { pickup, destination: dest, isVerified: isAutoVerified };
}

// 6. Получение базы знаний
app.get('/api/intel', async (req, res) => {
    try {
        const white = await pool.query("SELECT keyword FROM intel WHERE type = 'whitelist'");
        const black = await pool.query("SELECT keyword FROM intel WHERE type = 'blacklist'");
        const garbage = await pool.query("SELECT keyword FROM intel WHERE type = 'garbage'");
        res.json({
            whitelist: white.rows.map(r => r.keyword),
            blacklist: black.rows.map(r => r.keyword),
            garbage: garbage.rows.map(r => r.keyword)
        });
    } catch (e) {
        res.status(500).json({ whitelist: [], blacklist: [], garbage: [] });
    }
});

// 7. Сохранение заказа
app.post('/api/orders', async (req, res) => {
    const { price, km, destination, pickup, lat, lon, app, status, device_id, raw_text, screenshot, hourly_rate, time_min } = req.body;
    let screenshotName = null;
    if (screenshot) {
        const fs = require('fs');
        const uploadDir = path.join(__dirname, 'public', 'uploads');
        if (!fs.existsSync(uploadDir)) fs.mkdirSync(uploadDir, { recursive: true });
        screenshotName = `snap_${Date.now()}_${(device_id || 'unkn').slice(-4)}.jpg`;
        const buffer = Buffer.from(screenshot, 'base64');
        fs.writeFileSync(path.join(uploadDir, screenshotName), buffer);
    }
    const corrected = await smartAutoCorrect({ pickup, destination, raw_text }, screenshot);
    await pool.query(
        'INSERT INTO orders (price, km, destination, pickup, lat, lon, app, status, device_id, timestamp, raw_text, screenshot, is_verified, hourly_rate, time_min) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15)',
        [price, km, corrected.destination, corrected.pickup, lat, lon, app, status, device_id, Date.now(), raw_text, screenshotName, corrected.isVerified, hourly_rate || 0, time_min || 0]
    );
    if (price > 100) {
        const kw = destination?.split(' ')[0]?.toLowerCase() || "";
        if (kw.length > 3) {
            await pool.query("INSERT INTO intel (keyword, type) VALUES ($1, 'whitelist') ON CONFLICT DO NOTHING", [kw]);
        }
    }
    res.json({ status: "saved", screenshot: screenshotName });
});

// ============================================================
// 🆕 БАГРАПОРТЫ — Endpoint для приёма отчётов с телефона
// ============================================================
app.post('/api/taxifilter/report', async (req, res) => {
    try {
        const { device_id, device_model, is_good, log_text } = req.body;

        await pool.query(
            'INSERT INTO reports (device_id, device_model, is_good, log_text, timestamp) VALUES ($1, $2, $3, $4, $5)',
            [device_id || 'unknown', device_model || 'unknown', is_good || false, log_text || '', Date.now()]
        );

        console.log(`📋 Новый bug report от ${device_model} (${device_id}): ${is_good ? '✅' : '🐞'}`);
        res.json({ status: 'saved' });
    } catch (e) {
        console.error('Report save error:', e);
        res.status(500).json({ status: 'error' });
    }
});

// Список репортов для веб-дашборда
app.get('/api/taxifilter/reports', async (req, res) => {
    try {
        const result = await pool.query(
            'SELECT id, device_id, device_model, is_good, timestamp, reviewed, LEFT(log_text, 200) as preview FROM reports ORDER BY timestamp DESC LIMIT 100'
        );
        res.json(result.rows);
    } catch (e) {
        res.status(500).json([]);
    }
});

// Полный текст одного репорта
app.get('/api/taxifilter/reports/:id', async (req, res) => {
    try {
        const result = await pool.query('SELECT * FROM reports WHERE id = $1', [req.params.id]);
        res.json(result.rows[0] || null);
    } catch (e) {
        res.status(500).json(null);
    }
});

// Отметить репорт как просмотренный
app.post('/api/taxifilter/reports/:id/review', async (req, res) => {
    await pool.query('UPDATE reports SET reviewed = TRUE WHERE id = $1', [req.params.id]);
    res.json({ status: 'ok' });
});

// ============================================================
// 🆕 СТАТИСТИКА ПОЕЗДОК — для дашборда
// ============================================================
app.get('/api/taxifilter/mystats', async (req, res) => {
    try {
        const { device_id } = req.query;
        const todayStart = new Date();
        todayStart.setHours(0, 0, 0, 0);

        // Сегодняшние заказы
        const todayRes = await pool.query(
            `SELECT COUNT(*) as count, COALESCE(SUM(price),0) as total, COALESCE(AVG(hourly_rate),0) as avg_rate
             FROM orders WHERE device_id = $1 AND timestamp > $2`,
            [device_id, todayStart.getTime()]
        );

        // За всё время
        const allTimeRes = await pool.query(
            `SELECT COUNT(*) as count, COALESCE(SUM(price),0) as total FROM orders WHERE device_id = $1`,
            [device_id]
        );

        // Топ-5 направлений
        const topDestRes = await pool.query(
            `SELECT destination, COUNT(*) as cnt FROM orders 
             WHERE device_id = $1 AND destination IS NOT NULL AND destination != ''
             GROUP BY destination ORDER BY cnt DESC LIMIT 5`,
            [device_id]
        );

        // Лучший час дня
        const byHourRes = await pool.query(
            `SELECT EXTRACT(HOUR FROM to_timestamp(timestamp/1000)) as hour, 
                    COALESCE(AVG(hourly_rate),0) as avg_rate, COUNT(*) as cnt
             FROM orders WHERE device_id = $1 AND timestamp > $2
             GROUP BY hour ORDER BY avg_rate DESC LIMIT 5`,
            [device_id, Date.now() - 7 * 24 * 60 * 60 * 1000]
        );

        res.json({
            today: todayRes.rows[0],
            allTime: allTimeRes.rows[0],
            topDestinations: topDestRes.rows,
            bestHours: byHourRes.rows
        });
    } catch (e) {
        console.error('Stats error:', e);
        res.status(500).json({});
    }
});

// Коррекция и одобрение заказа
app.post('/api/admin/orders/:id/correct', async (req, res) => {
    const { id } = req.params;
    const { pickup, destination, isVerified } = req.body;
    await pool.query(
        'UPDATE orders SET pickup = $1, destination = $2, is_verified = TRUE WHERE id = $3',
        [pickup, destination, id]
    );
    if (isVerified !== false) {
        const allText = `${pickup || ""} ${destination || ""}`;
        const words = allText.split(/[\s,.-]+/).filter(w => w.length > 3);
        for (const w of words) {
            const kw = w.toLowerCase();
            await pool.query("INSERT INTO intel (keyword, type) VALUES ($1, 'whitelist') ON CONFLICT DO NOTHING", [kw]);
        }
    }
    res.json({ status: "corrected" });
});

app.post('/api/admin/orders/:id/reject', async (req, res) => {
    const { id } = req.params;
    await pool.query('UPDATE orders SET is_verified = TRUE WHERE id = $1', [id]);
    res.json({ status: "rejected" });
});

app.get('/api/stats', async (req, res) => {
    const ors = await pool.query('SELECT COUNT(*) FROM orders');
    const drs = await pool.query('SELECT COUNT(*) FROM drivers WHERE last_update > $1', [Date.now() - 300000]);
    const int = await pool.query('SELECT COUNT(*) FROM intel');
    const rpts = await pool.query('SELECT COUNT(*) FROM reports WHERE reviewed = FALSE');
    res.json({
        totalOrders: parseInt(ors.rows[0].count),
        activeDrivers: parseInt(drs.rows[0].count),
        intelSize: parseInt(int.rows[0].count),
        unreviewedReports: parseInt(rpts.rows[0].count)
    });
});

// --- АДМИН ЭНДПОИНТЫ ---
app.get('/api/admin/keys', async (req, res) => {
    const result = await pool.query('SELECT * FROM keys ORDER BY used ASC');
    res.json(result.rows);
});

app.get('/api/admin/orders', async (req, res) => {
    const result = await pool.query('SELECT * FROM orders ORDER BY timestamp DESC LIMIT 50');
    res.json(result.rows);
});

app.post('/api/admin/generate-key', async (req, res) => {
    const { days } = req.body;
    const key = `WEB-${days}-${Math.random().toString(36).substring(2, 7).toUpperCase()}`;
    await pool.query('INSERT INTO keys (key, days) VALUES ($1, $2)', [key, days]);
    res.json({ status: "ok", key });
});

app.post('/api/admin/adjust-time', async (req, res) => {
    const { deviceId, days } = req.body;
    const sub = await pool.query('SELECT expiry FROM subs WHERE device_id = $1', [deviceId]);
    const currentExpiry = Math.max(sub.rows[0]?.expiry || Date.now(), Date.now());
    const newExpiry = currentExpiry + (days * 24 * 60 * 60 * 1000);
    await pool.query('INSERT INTO subs (device_id, expiry) VALUES ($1, $2) ON CONFLICT (device_id) DO UPDATE SET expiry = $2', [deviceId, newExpiry]);
    res.json({ status: "ok", newExpiry });
});

app.post('/api/admin/set-version', async (req, res) => {
    const { version } = req.body;
    await pool.query("UPDATE config SET value = $1 WHERE key = 'app_version'", [version]);
    res.json({ status: "ok" });
});

app.get('/api/admin/intel-all', async (req, res) => {
    const result = await pool.query('SELECT * FROM intel ORDER BY keyword ASC');
    res.json(result.rows);
});

app.delete('/api/admin/intel/:keyword', async (req, res) => {
    const { keyword } = req.params;
    await pool.query('DELETE FROM intel WHERE keyword = $1', [keyword]);
    res.json({ status: "deleted" });
});

app.get('/', (req, res) => res.sendFile(path.join(__dirname, 'public', 'dashboard.html')));
app.listen(PORT, () => console.log(`Server running on ${PORT}`));

