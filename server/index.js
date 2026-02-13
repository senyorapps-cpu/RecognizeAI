require("dotenv").config({ path: require("path").join(__dirname, ".env") });
const express = require("express");
const multer = require("multer");
const cors = require("cors");
const { GoogleGenerativeAI } = require("@google/generative-ai");
const fs = require("fs");
const path = require("path");
const os = require("os");
const crypto = require("crypto");
const { Pool } = require("pg");

const app = express();
const PORT = 3001;

app.use(cors());
app.use(express.json());

// ── Server start time & Admin auth ─────────────────────────────
const SERVER_START_TIME = Date.now();
const adminTokens = new Set();

// ── Request logging ring buffer (last 500) ─────────────────────
const REQUEST_LOG_MAX = 500;
const requestLogs = [];

app.use((req, res, next) => {
  // Skip logging for admin endpoints and static files to reduce noise
  if (req.path === "/admin" || req.path.startsWith("/api/uploads/")) return next();
  const start = Date.now();
  const entry = {
    timestamp: new Date().toISOString(),
    method: req.method,
    url: req.originalUrl,
    status: null,
    responseTime: null,
    error: null,
    level: "info",
  };
  res.on("finish", () => {
    entry.status = res.statusCode;
    entry.responseTime = Date.now() - start;
    if (res.statusCode >= 400) {
      entry.level = "error";
    }
    requestLogs.push(entry);
    if (requestLogs.length > REQUEST_LOG_MAX) requestLogs.shift();
  });
  next();
});

// Configure multer for image uploads
const uploadDir = path.join(__dirname, "uploads");
if (!fs.existsSync(uploadDir)) fs.mkdirSync(uploadDir);

const storage = multer.diskStorage({
  destination: (req, file, cb) => cb(null, uploadDir),
  filename: (req, file, cb) => cb(null, Date.now() + "-" + file.originalname),
});
const upload = multer({ storage, limits: { fileSize: 20 * 1024 * 1024 } });

// Gemini AI setup
const genAI = new GoogleGenerativeAI(process.env.GEMINI_API_KEY);

// PostgreSQL database
const pool = new Pool({
  host: process.env.DB_HOST || "localhost",
  port: process.env.DB_PORT || 5432,
  database: process.env.DB_NAME || "tripai",
  user: process.env.DB_USER || "tripai_user",
  password: process.env.DB_PASSWORD,
});

pool.on("connect", () => {
  console.log("Connected to PostgreSQL database");
});

pool.on("error", (err) => {
  console.error("PostgreSQL pool error:", err);
});

async function initDb() {
  const client = await pool.connect();
  try {
    await client.query(`
      CREATE TABLE IF NOT EXISTS users (
        id SERIAL PRIMARY KEY,
        auth_type VARCHAR(20) NOT NULL,
        google_id VARCHAR(255) UNIQUE,
        email VARCHAR(255),
        display_name VARCHAR(255) NOT NULL,
        photo_url TEXT,
        device_id VARCHAR(255),
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        last_login_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
      )
    `);

    await client.query(`
      CREATE TABLE IF NOT EXISTS landmarks (
        id SERIAL PRIMARY KEY,
        user_id INTEGER REFERENCES users(id),
        device_id VARCHAR(255),
        name VARCHAR(500),
        location VARCHAR(500),
        year_built VARCHAR(100),
        status VARCHAR(255),
        architect VARCHAR(500),
        capacity VARCHAR(255),
        narrative_p1 TEXT,
        narrative_quote TEXT,
        narrative_p2 TEXT,
        nearby1_name VARCHAR(255),
        nearby1_category VARCHAR(100),
        nearby2_name VARCHAR(255),
        nearby2_category VARCHAR(100),
        nearby3_name VARCHAR(255),
        nearby3_category VARCHAR(100),
        image_filename VARCHAR(500),
        rating INTEGER DEFAULT 0,
        is_saved INTEGER DEFAULT 0,
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
      )
    `);

    // Add latitude/longitude columns if they don't exist
    await client.query(`ALTER TABLE landmarks ADD COLUMN IF NOT EXISTS latitude DOUBLE PRECISION`);
    await client.query(`ALTER TABLE landmarks ADD COLUMN IF NOT EXISTS longitude DOUBLE PRECISION`);

    // Add language column to users table
    await client.query(`ALTER TABLE users ADD COLUMN IF NOT EXISTS language VARCHAR(10) DEFAULT 'en'`);

    // Add language column to landmarks table
    await client.query(`ALTER TABLE landmarks ADD COLUMN IF NOT EXISTS language VARCHAR(10) DEFAULT 'en'`);

    // Create privacy_policies table
    await client.query(`
      CREATE TABLE IF NOT EXISTS privacy_policies (
        id SERIAL PRIMARY KEY,
        lang VARCHAR(10) NOT NULL UNIQUE,
        content TEXT NOT NULL,
        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
      )
    `);

    // Create contact_messages table
    await client.query(`
      CREATE TABLE IF NOT EXISTS contact_messages (
        id SERIAL PRIMARY KEY,
        topic VARCHAR(255),
        message TEXT,
        screenshot_filename VARCHAR(500),
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
      )
    `);

    // Seed privacy policies if table is empty
    const policyCount = await client.query("SELECT COUNT(*) FROM privacy_policies");
    if (parseInt(policyCount.rows[0].count) === 0) {
      await seedPrivacyPolicies(client);
    }

    // Create indexes
    await client.query(`CREATE INDEX IF NOT EXISTS idx_landmarks_user_id ON landmarks(user_id)`);
    await client.query(`CREATE INDEX IF NOT EXISTS idx_users_device_id ON users(device_id)`);
    await client.query(`CREATE INDEX IF NOT EXISTS idx_users_google_id ON users(google_id)`);

    console.log("Database tables initialized successfully");
  } catch (error) {
    console.error("Error initializing database:", error);
    throw error;
  } finally {
    client.release();
  }
}

async function seedPrivacyPolicies(client) {
  const policies = {
    en: `Privacy Policy

Last updated: February 2026

TripAI ("we", "our", or "us") operates the TripAI mobile application. This Privacy Policy explains how we collect, use, and protect your information when you use our app.

1. Information We Collect

- Photos & Camera: We access your camera to capture photos of landmarks. Photos are uploaded to our servers for AI analysis and stored to provide you with landmark information.
- Location Data: With your permission, we collect your device's location to enhance landmark identification accuracy and show nearby places.
- Device Information: We collect your device identifier to associate your data with your account and enable guest access.
- Account Information: If you sign in with Google, we receive your name, email address, and profile photo from Google.
- Language Preference: We store your selected language preference to provide the app in your chosen language.

2. How We Use Your Information

- To analyze photos and identify landmarks using AI
- To provide historical and cultural information about landmarks
- To save your travel journal and photo history
- To personalize your experience based on language and location
- To improve our AI recognition accuracy

3. Data Storage & Security

Your data is stored on our secure servers. Photos and landmark data are associated with your device or Google account. We implement appropriate security measures to protect your personal information.

4. Data Sharing

We do not sell your personal information. We may share anonymized, aggregated data for analytics purposes. Your photos are processed by our AI systems and are not shared with third parties.

5. Your Rights

You can:
- Delete your account and all associated data by contacting us
- Change your language preference at any time
- Use the app as a guest without providing personal information

6. Children's Privacy

Our app is not directed at children under 13. We do not knowingly collect personal information from children.

7. Changes to This Policy

We may update this policy from time to time. We will notify you of significant changes through the app.

8. Contact Us

If you have questions about this Privacy Policy, please contact us at senyor.apps@gmail.com`,

    ru: `\u041f\u043e\u043b\u0438\u0442\u0438\u043a\u0430 \u043a\u043e\u043d\u0444\u0438\u0434\u0435\u043d\u0446\u0438\u0430\u043b\u044c\u043d\u043e\u0441\u0442\u0438

\u041f\u043e\u0441\u043b\u0435\u0434\u043d\u0435\u0435 \u043e\u0431\u043d\u043e\u0432\u043b\u0435\u043d\u0438\u0435: \u0444\u0435\u0432\u0440\u0430\u043b\u044c 2026

TripAI (\u00ab\u043c\u044b\u00bb, \u00ab\u043d\u0430\u0448\u00bb \u0438\u043b\u0438 \u00ab\u043d\u0430\u0441\u00bb) \u0443\u043f\u0440\u0430\u0432\u043b\u044f\u0435\u0442 \u043c\u043e\u0431\u0438\u043b\u044c\u043d\u044b\u043c \u043f\u0440\u0438\u043b\u043e\u0436\u0435\u043d\u0438\u0435\u043c TripAI. \u041d\u0430\u0441\u0442\u043e\u044f\u0449\u0430\u044f \u041f\u043e\u043b\u0438\u0442\u0438\u043a\u0430 \u043a\u043e\u043d\u0444\u0438\u0434\u0435\u043d\u0446\u0438\u0430\u043b\u044c\u043d\u043e\u0441\u0442\u0438 \u043e\u0431\u044a\u044f\u0441\u043d\u044f\u0435\u0442, \u043a\u0430\u043a \u043c\u044b \u0441\u043e\u0431\u0438\u0440\u0430\u0435\u043c, \u0438\u0441\u043f\u043e\u043b\u044c\u0437\u0443\u0435\u043c \u0438 \u0437\u0430\u0449\u0438\u0449\u0430\u0435\u043c \u0432\u0430\u0448\u0443 \u0438\u043d\u0444\u043e\u0440\u043c\u0430\u0446\u0438\u044e \u043f\u0440\u0438 \u0438\u0441\u043f\u043e\u043b\u044c\u0437\u043e\u0432\u0430\u043d\u0438\u0438 \u043d\u0430\u0448\u0435\u0433\u043e \u043f\u0440\u0438\u043b\u043e\u0436\u0435\u043d\u0438\u044f.

1. \u0418\u043d\u0444\u043e\u0440\u043c\u0430\u0446\u0438\u044f, \u043a\u043e\u0442\u043e\u0440\u0443\u044e \u043c\u044b \u0441\u043e\u0431\u0438\u0440\u0430\u0435\u043c

- \u0424\u043e\u0442\u043e\u0433\u0440\u0430\u0444\u0438\u0438 \u0438 \u043a\u0430\u043c\u0435\u0440\u0430: \u041c\u044b \u0438\u0441\u043f\u043e\u043b\u044c\u0437\u0443\u0435\u043c \u0432\u0430\u0448\u0443 \u043a\u0430\u043c\u0435\u0440\u0443 \u0434\u043b\u044f \u0441\u044a\u0451\u043c\u043a\u0438 \u0434\u043e\u0441\u0442\u043e\u043f\u0440\u0438\u043c\u0435\u0447\u0430\u0442\u0435\u043b\u044c\u043d\u043e\u0441\u0442\u0435\u0439. \u0424\u043e\u0442\u043e\u0433\u0440\u0430\u0444\u0438\u0438 \u0437\u0430\u0433\u0440\u0443\u0436\u0430\u044e\u0442\u0441\u044f \u043d\u0430 \u043d\u0430\u0448\u0438 \u0441\u0435\u0440\u0432\u0435\u0440\u0430 \u0434\u043b\u044f AI-\u0430\u043d\u0430\u043b\u0438\u0437\u0430.
- \u0414\u0430\u043d\u043d\u044b\u0435 \u043e \u043c\u0435\u0441\u0442\u043e\u043f\u043e\u043b\u043e\u0436\u0435\u043d\u0438\u0438: \u0421 \u0432\u0430\u0448\u0435\u0433\u043e \u0440\u0430\u0437\u0440\u0435\u0448\u0435\u043d\u0438\u044f \u043c\u044b \u0441\u043e\u0431\u0438\u0440\u0430\u0435\u043c \u0434\u0430\u043d\u043d\u044b\u0435 \u043e \u043c\u0435\u0441\u0442\u043e\u043f\u043e\u043b\u043e\u0436\u0435\u043d\u0438\u0438 \u0432\u0430\u0448\u0435\u0433\u043e \u0443\u0441\u0442\u0440\u043e\u0439\u0441\u0442\u0432\u0430.
- \u0418\u043d\u0444\u043e\u0440\u043c\u0430\u0446\u0438\u044f \u043e\u0431 \u0443\u0441\u0442\u0440\u043e\u0439\u0441\u0442\u0432\u0435: \u041c\u044b \u0441\u043e\u0431\u0438\u0440\u0430\u0435\u043c \u0438\u0434\u0435\u043d\u0442\u0438\u0444\u0438\u043a\u0430\u0442\u043e\u0440 \u0432\u0430\u0448\u0435\u0433\u043e \u0443\u0441\u0442\u0440\u043e\u0439\u0441\u0442\u0432\u0430.
- \u0418\u043d\u0444\u043e\u0440\u043c\u0430\u0446\u0438\u044f \u043e\u0431 \u0430\u043a\u043a\u0430\u0443\u043d\u0442\u0435: \u041f\u0440\u0438 \u0432\u0445\u043e\u0434\u0435 \u0447\u0435\u0440\u0435\u0437 Google \u043c\u044b \u043f\u043e\u043b\u0443\u0447\u0430\u0435\u043c \u0432\u0430\u0448\u0435 \u0438\u043c\u044f, \u0430\u0434\u0440\u0435\u0441 \u044d\u043b\u0435\u043a\u0442\u0440\u043e\u043d\u043d\u043e\u0439 \u043f\u043e\u0447\u0442\u044b \u0438 \u0444\u043e\u0442\u043e \u043f\u0440\u043e\u0444\u0438\u043b\u044f.
- \u042f\u0437\u044b\u043a\u043e\u0432\u044b\u0435 \u043f\u0440\u0435\u0434\u043f\u043e\u0447\u0442\u0435\u043d\u0438\u044f: \u041c\u044b \u0441\u043e\u0445\u0440\u0430\u043d\u044f\u0435\u043c \u0432\u044b\u0431\u0440\u0430\u043d\u043d\u044b\u0439 \u0432\u0430\u043c\u0438 \u044f\u0437\u044b\u043a.

2. \u041a\u0430\u043a \u043c\u044b \u0438\u0441\u043f\u043e\u043b\u044c\u0437\u0443\u0435\u043c \u0432\u0430\u0448\u0443 \u0438\u043d\u0444\u043e\u0440\u043c\u0430\u0446\u0438\u044e

- \u0414\u043b\u044f \u0430\u043d\u0430\u043b\u0438\u0437\u0430 \u0444\u043e\u0442\u043e\u0433\u0440\u0430\u0444\u0438\u0439 \u0438 \u043e\u043f\u0440\u0435\u0434\u0435\u043b\u0435\u043d\u0438\u044f \u0434\u043e\u0441\u0442\u043e\u043f\u0440\u0438\u043c\u0435\u0447\u0430\u0442\u0435\u043b\u044c\u043d\u043e\u0441\u0442\u0435\u0439 \u0441 \u043f\u043e\u043c\u043e\u0449\u044c\u044e AI
- \u0414\u043b\u044f \u043f\u0440\u0435\u0434\u043e\u0441\u0442\u0430\u0432\u043b\u0435\u043d\u0438\u044f \u0438\u0441\u0442\u043e\u0440\u0438\u0447\u0435\u0441\u043a\u043e\u0439 \u0438 \u043a\u0443\u043b\u044c\u0442\u0443\u0440\u043d\u043e\u0439 \u0438\u043d\u0444\u043e\u0440\u043c\u0430\u0446\u0438\u0438
- \u0414\u043b\u044f \u0441\u043e\u0445\u0440\u0430\u043d\u0435\u043d\u0438\u044f \u0432\u0430\u0448\u0435\u0433\u043e \u0434\u043d\u0435\u0432\u043d\u0438\u043a\u0430 \u043f\u0443\u0442\u0435\u0448\u0435\u0441\u0442\u0432\u0438\u0439
- \u0414\u043b\u044f \u043f\u0435\u0440\u0441\u043e\u043d\u0430\u043b\u0438\u0437\u0430\u0446\u0438\u0438 \u043e\u043f\u044b\u0442\u0430
- \u0414\u043b\u044f \u0443\u043b\u0443\u0447\u0448\u0435\u043d\u0438\u044f \u0442\u043e\u0447\u043d\u043e\u0441\u0442\u0438 AI-\u0440\u0430\u0441\u043f\u043e\u0437\u043d\u0430\u0432\u0430\u043d\u0438\u044f

3. \u0425\u0440\u0430\u043d\u0435\u043d\u0438\u0435 \u0438 \u0431\u0435\u0437\u043e\u043f\u0430\u0441\u043d\u043e\u0441\u0442\u044c \u0434\u0430\u043d\u043d\u044b\u0445

\u0412\u0430\u0448\u0438 \u0434\u0430\u043d\u043d\u044b\u0435 \u0445\u0440\u0430\u043d\u044f\u0442\u0441\u044f \u043d\u0430 \u043d\u0430\u0448\u0438\u0445 \u0437\u0430\u0449\u0438\u0449\u0451\u043d\u043d\u044b\u0445 \u0441\u0435\u0440\u0432\u0435\u0440\u0430\u0445. \u041c\u044b \u043f\u0440\u0438\u043c\u0435\u043d\u044f\u0435\u043c \u0441\u043e\u043e\u0442\u0432\u0435\u0442\u0441\u0442\u0432\u0443\u044e\u0449\u0438\u0435 \u043c\u0435\u0440\u044b \u0431\u0435\u0437\u043e\u043f\u0430\u0441\u043d\u043e\u0441\u0442\u0438.

4. \u041f\u0435\u0440\u0435\u0434\u0430\u0447\u0430 \u0434\u0430\u043d\u043d\u044b\u0445

\u041c\u044b \u043d\u0435 \u043f\u0440\u043e\u0434\u0430\u0451\u043c \u0432\u0430\u0448\u0443 \u043b\u0438\u0447\u043d\u0443\u044e \u0438\u043d\u0444\u043e\u0440\u043c\u0430\u0446\u0438\u044e. \u0412\u0430\u0448\u0438 \u0444\u043e\u0442\u043e\u0433\u0440\u0430\u0444\u0438\u0438 \u043e\u0431\u0440\u0430\u0431\u0430\u0442\u044b\u0432\u0430\u044e\u0442\u0441\u044f \u043d\u0430\u0448\u0438\u043c\u0438 AI-\u0441\u0438\u0441\u0442\u0435\u043c\u0430\u043c\u0438 \u0438 \u043d\u0435 \u043f\u0435\u0440\u0435\u0434\u0430\u044e\u0442\u0441\u044f \u0442\u0440\u0435\u0442\u044c\u0438\u043c \u043b\u0438\u0446\u0430\u043c.

5. \u0412\u0430\u0448\u0438 \u043f\u0440\u0430\u0432\u0430

\u0412\u044b \u043c\u043e\u0436\u0435\u0442\u0435:
- \u0423\u0434\u0430\u043b\u0438\u0442\u044c \u0441\u0432\u043e\u0439 \u0430\u043a\u043a\u0430\u0443\u043d\u0442 \u0438 \u0432\u0441\u0435 \u0441\u0432\u044f\u0437\u0430\u043d\u043d\u044b\u0435 \u0434\u0430\u043d\u043d\u044b\u0435
- \u0418\u0437\u043c\u0435\u043d\u0438\u0442\u044c \u044f\u0437\u044b\u043a\u043e\u0432\u044b\u0435 \u043f\u0440\u0435\u0434\u043f\u043e\u0447\u0442\u0435\u043d\u0438\u044f \u0432 \u043b\u044e\u0431\u043e\u0435 \u0432\u0440\u0435\u043c\u044f
- \u0418\u0441\u043f\u043e\u043b\u044c\u0437\u043e\u0432\u0430\u0442\u044c \u043f\u0440\u0438\u043b\u043e\u0436\u0435\u043d\u0438\u0435 \u043a\u0430\u043a \u0433\u043e\u0441\u0442\u044c

6. \u041a\u043e\u043d\u0444\u0438\u0434\u0435\u043d\u0446\u0438\u0430\u043b\u044c\u043d\u043e\u0441\u0442\u044c \u0434\u0435\u0442\u0435\u0439

\u041d\u0430\u0448\u0435 \u043f\u0440\u0438\u043b\u043e\u0436\u0435\u043d\u0438\u0435 \u043d\u0435 \u043f\u0440\u0435\u0434\u043d\u0430\u0437\u043d\u0430\u0447\u0435\u043d\u043e \u0434\u043b\u044f \u0434\u0435\u0442\u0435\u0439 \u043c\u043b\u0430\u0434\u0448\u0435 13 \u043b\u0435\u0442.

7. \u0418\u0437\u043c\u0435\u043d\u0435\u043d\u0438\u044f \u0432 \u043f\u043e\u043b\u0438\u0442\u0438\u043a\u0435

\u041c\u044b \u043c\u043e\u0436\u0435\u043c \u0432\u0440\u0435\u043c\u044f \u043e\u0442 \u0432\u0440\u0435\u043c\u0435\u043d\u0438 \u043e\u0431\u043d\u043e\u0432\u043b\u044f\u0442\u044c \u044d\u0442\u0443 \u043f\u043e\u043b\u0438\u0442\u0438\u043a\u0443.

8. \u0421\u0432\u044f\u0436\u0438\u0442\u0435\u0441\u044c \u0441 \u043d\u0430\u043c\u0438

senyor.apps@gmail.com`,

    es: `Pol\u00edtica de Privacidad

\u00daltima actualizaci\u00f3n: febrero 2026

TripAI ("nosotros", "nuestro") opera la aplicaci\u00f3n m\u00f3vil TripAI. Esta Pol\u00edtica de Privacidad explica c\u00f3mo recopilamos, usamos y protegemos su informaci\u00f3n.

1. Informaci\u00f3n que recopilamos

- Fotos y c\u00e1mara: Accedemos a su c\u00e1mara para capturar fotos de monumentos.
- Datos de ubicaci\u00f3n: Con su permiso, recopilamos la ubicaci\u00f3n de su dispositivo.
- Informaci\u00f3n del dispositivo: Recopilamos el identificador de su dispositivo.
- Informaci\u00f3n de la cuenta: Si inicia sesi\u00f3n con Google, recibimos su nombre, correo y foto.
- Preferencia de idioma: Almacenamos su preferencia de idioma seleccionada.

2. C\u00f3mo usamos su informaci\u00f3n

- Para analizar fotos e identificar monumentos usando IA
- Para proporcionar informaci\u00f3n hist\u00f3rica y cultural
- Para guardar su diario de viaje
- Para personalizar su experiencia
- Para mejorar la precisi\u00f3n de nuestro reconocimiento

3. Almacenamiento y seguridad de datos

Sus datos se almacenan en nuestros servidores seguros. Implementamos medidas de seguridad apropiadas.

4. Compartir datos

No vendemos su informaci\u00f3n personal. Sus fotos son procesadas por nuestros sistemas de IA y no se comparten con terceros.

5. Sus derechos

Usted puede:
- Eliminar su cuenta y todos los datos asociados
- Cambiar su preferencia de idioma en cualquier momento
- Usar la aplicaci\u00f3n como invitado

6. Privacidad de los ni\u00f1os

Nuestra aplicaci\u00f3n no est\u00e1 dirigida a ni\u00f1os menores de 13 a\u00f1os.

7. Cambios en esta pol\u00edtica

Podemos actualizar esta pol\u00edtica de vez en cuando.

8. Cont\u00e1ctenos

senyor.apps@gmail.com`,

    fr: `Politique de Confidentialit\u00e9

Derni\u00e8re mise \u00e0 jour : f\u00e9vrier 2026

TripAI (\u00ab nous \u00bb, \u00ab notre \u00bb) exploite l\u2019application mobile TripAI. Cette Politique de Confidentialit\u00e9 explique comment nous collectons, utilisons et prot\u00e9geons vos informations.

1. Informations que nous collectons

- Photos et cam\u00e9ra : Nous acc\u00e9dons \u00e0 votre cam\u00e9ra pour capturer des photos de monuments.
- Donn\u00e9es de localisation : Avec votre permission, nous collectons la localisation de votre appareil.
- Informations sur l\u2019appareil : Nous collectons l\u2019identifiant de votre appareil.
- Informations du compte : Si vous vous connectez avec Google, nous recevons votre nom, e-mail et photo.
- Pr\u00e9f\u00e9rence linguistique : Nous stockons votre pr\u00e9f\u00e9rence de langue.

2. Comment nous utilisons vos informations

- Pour analyser les photos et identifier les monuments
- Pour fournir des informations historiques et culturelles
- Pour sauvegarder votre journal de voyage
- Pour personnaliser votre exp\u00e9rience
- Pour am\u00e9liorer notre reconnaissance par IA

3. Stockage et s\u00e9curit\u00e9 des donn\u00e9es

Vos donn\u00e9es sont stock\u00e9es sur nos serveurs s\u00e9curis\u00e9s. Nous mettons en \u0153uvre des mesures de s\u00e9curit\u00e9 appropri\u00e9es.

4. Partage des donn\u00e9es

Nous ne vendons pas vos informations personnelles. Vos photos ne sont pas partag\u00e9es avec des tiers.

5. Vos droits

Vous pouvez :
- Supprimer votre compte et toutes les donn\u00e9es associ\u00e9es
- Modifier votre pr\u00e9f\u00e9rence linguistique \u00e0 tout moment
- Utiliser l\u2019application en tant qu\u2019invit\u00e9

6. Vie priv\u00e9e des enfants

Notre application n\u2019est pas destin\u00e9e aux enfants de moins de 13 ans.

7. Modifications de cette politique

Nous pouvons mettre \u00e0 jour cette politique de temps en temps.

8. Contactez-nous

senyor.apps@gmail.com`,

    de: `Datenschutzerkl\u00e4rung

Letzte Aktualisierung: Februar 2026

TripAI (\u201ewir\u201c, \u201eunser\u201c) betreibt die mobile Anwendung TripAI. Diese Datenschutzerkl\u00e4rung erl\u00e4utert, wie wir Ihre Informationen erfassen, verwenden und sch\u00fctzen.

1. Informationen, die wir erfassen

- Fotos und Kamera: Wir greifen auf Ihre Kamera zu, um Fotos von Sehensw\u00fcrdigkeiten aufzunehmen.
- Standortdaten: Mit Ihrer Erlaubnis erfassen wir den Standort Ihres Ger\u00e4ts.
- Ger\u00e4teinformationen: Wir erfassen die Kennung Ihres Ger\u00e4ts.
- Kontoinformationen: Bei Google-Anmeldung erhalten wir Ihren Namen, E-Mail und Profilfoto.
- Sprachpr\u00e4ferenz: Wir speichern Ihre gew\u00e4hlte Sprachpr\u00e4ferenz.

2. Wie wir Ihre Informationen verwenden

- Zur Analyse von Fotos und Identifizierung von Sehensw\u00fcrdigkeiten
- Zur Bereitstellung historischer und kultureller Informationen
- Zum Speichern Ihres Reisetagebuchs
- Zur Personalisierung Ihrer Erfahrung
- Zur Verbesserung unserer KI-Erkennung

3. Datenspeicherung und Sicherheit

Ihre Daten werden auf unseren sicheren Servern gespeichert. Wir implementieren angemessene Sicherheitsma\u00dfnahmen.

4. Datenweitergabe

Wir verkaufen Ihre pers\u00f6nlichen Daten nicht. Ihre Fotos werden nicht an Dritte weitergegeben.

5. Ihre Rechte

Sie k\u00f6nnen:
- Ihr Konto und alle Daten l\u00f6schen
- Ihre Sprachpr\u00e4ferenz jederzeit \u00e4ndern
- Die App als Gast nutzen

6. Datenschutz f\u00fcr Kinder

Unsere App richtet sich nicht an Kinder unter 13 Jahren.

7. \u00c4nderungen dieser Richtlinie

Wir k\u00f6nnen diese Richtlinie von Zeit zu Zeit aktualisieren.

8. Kontaktieren Sie uns

senyor.apps@gmail.com`,

    pt: `Pol\u00edtica de Privacidade

\u00daltima atualiza\u00e7\u00e3o: fevereiro 2026

TripAI ("n\u00f3s", "nosso") opera o aplicativo m\u00f3vel TripAI. Esta Pol\u00edtica de Privacidade explica como coletamos, usamos e protegemos suas informa\u00e7\u00f5es.

1. Informa\u00e7\u00f5es que coletamos

- Fotos e c\u00e2mera: Acessamos sua c\u00e2mera para capturar fotos de pontos tur\u00edsticos.
- Dados de localiza\u00e7\u00e3o: Com sua permiss\u00e3o, coletamos a localiza\u00e7\u00e3o do seu dispositivo.
- Informa\u00e7\u00f5es do dispositivo: Coletamos o identificador do seu dispositivo.
- Informa\u00e7\u00f5es da conta: Se fizer login com Google, recebemos seu nome, e-mail e foto.
- Prefer\u00eancia de idioma: Armazenamos sua prefer\u00eancia de idioma selecionada.

2. Como usamos suas informa\u00e7\u00f5es

- Para analisar fotos e identificar pontos tur\u00edsticos usando IA
- Para fornecer informa\u00e7\u00f5es hist\u00f3ricas e culturais
- Para salvar seu di\u00e1rio de viagem
- Para personalizar sua experi\u00eancia
- Para melhorar a precis\u00e3o do reconhecimento

3. Armazenamento e seguran\u00e7a de dados

Seus dados s\u00e3o armazenados em nossos servidores seguros. Implementamos medidas de seguran\u00e7a apropriadas.

4. Compartilhamento de dados

N\u00e3o vendemos suas informa\u00e7\u00f5es pessoais. Suas fotos n\u00e3o s\u00e3o compartilhadas com terceiros.

5. Seus direitos

Voc\u00ea pode:
- Excluir sua conta e todos os dados associados
- Alterar sua prefer\u00eancia de idioma a qualquer momento
- Usar o aplicativo como convidado

6. Privacidade das crian\u00e7as

Nosso aplicativo n\u00e3o \u00e9 direcionado a crian\u00e7as menores de 13 anos.

7. Altera\u00e7\u00f5es nesta pol\u00edtica

Podemos atualizar esta pol\u00edtica de tempos em tempos.

8. Fale conosco

senyor.apps@gmail.com`,
  };

  for (const [lang, content] of Object.entries(policies)) {
    await client.query(
      "INSERT INTO privacy_policies (lang, content) VALUES ($1, $2) ON CONFLICT (lang) DO UPDATE SET content = $2, updated_at = CURRENT_TIMESTAMP",
      [lang, content]
    );
  }
  console.log("Privacy policies seeded for 6 languages");
}

app.get("/api/health", (req, res) => {
  res.json({ status: "ok", service: "tripai-api", db: "postgresql" });
});

// ── Auth endpoints ──────────────────────────────────────────────

// Guest login — creates or finds guest user by device_id
app.post("/api/auth/guest", async (req, res) => {
  try {
    const { device_id, language } = req.body;
    console.log(`[GuestAuth] Login attempt with device_id: ${device_id}, language: ${language}`);
    if (!device_id) {
      return res.status(400).json({ error: "device_id is required" });
    }

    const existing = await pool.query(
      "SELECT * FROM users WHERE auth_type = 'guest' AND device_id = $1",
      [device_id]
    );

    if (existing.rows.length > 0) {
      console.log(`[GuestAuth] Found existing user id=${existing.rows[0].id}`);
      const updates = ["last_login_at = CURRENT_TIMESTAMP"];
      const params = [existing.rows[0].id];
      if (language) {
        updates.push(`language = $${params.length + 1}`);
        params.push(language);
      }
      await pool.query(`UPDATE users SET ${updates.join(", ")} WHERE id = $1`, params);
      // Re-fetch to return updated data
      const updated = await pool.query("SELECT * FROM users WHERE id = $1", [existing.rows[0].id]);
      return res.json(updated.rows[0]);
    }

    const result = await pool.query(
      "INSERT INTO users (auth_type, display_name, device_id, language) VALUES ('guest', 'Guest', $1, $2) RETURNING *",
      [device_id, language || "en"]
    );
    res.json(result.rows[0]);
  } catch (error) {
    console.error("Guest auth error:", error);
    res.status(500).json({ error: "Authentication failed", message: error.message });
  }
});

// Google login — creates or finds user by google_id
app.post("/api/auth/google", async (req, res) => {
  try {
    const { google_id, email, display_name, photo_url, device_id, language } = req.body;
    if (!google_id) {
      return res.status(400).json({ error: "google_id is required" });
    }

    const existing = await pool.query(
      "SELECT * FROM users WHERE auth_type = 'google' AND google_id = $1",
      [google_id]
    );

    if (existing.rows.length > 0) {
      const user = existing.rows[0];
      const updated = await pool.query(
        `UPDATE users SET email = $1, display_name = $2, photo_url = $3, device_id = $4, language = $5, last_login_at = CURRENT_TIMESTAMP WHERE id = $6 RETURNING *`,
        [
          email || user.email,
          display_name || user.display_name,
          photo_url || user.photo_url,
          device_id || user.device_id,
          language || user.language || "en",
          user.id,
        ]
      );
      return res.json(updated.rows[0]);
    }

    const result = await pool.query(
      "INSERT INTO users (auth_type, google_id, email, display_name, photo_url, device_id, language) VALUES ('google', $1, $2, $3, $4, $5, $6) RETURNING *",
      [google_id, email || "", display_name || "User", photo_url || "", device_id || "", language || "en"]
    );
    res.json(result.rows[0]);
  } catch (error) {
    console.error("Google auth error:", error);
    res.status(500).json({ error: "Authentication failed", message: error.message });
  }
});

// Update user language
app.put("/api/users/:id/language", async (req, res) => {
  try {
    const { language } = req.body;
    if (!language) {
      return res.status(400).json({ error: "language is required" });
    }
    const result = await pool.query(
      "UPDATE users SET language = $1 WHERE id = $2 RETURNING *",
      [language, req.params.id]
    );
    if (result.rows.length === 0) {
      return res.status(404).json({ error: "User not found" });
    }
    console.log(`[Language] Updated user ${req.params.id} language to ${language}`);
    res.json(result.rows[0]);
  } catch (error) {
    res.status(500).json({ error: "Failed to update language", message: error.message });
  }
});

// Get user profile
app.get("/api/user/:id", async (req, res) => {
  try {
    const result = await pool.query("SELECT * FROM users WHERE id = $1", [req.params.id]);
    if (result.rows.length === 0) {
      return res.status(404).json({ error: "User not found" });
    }
    res.json(result.rows[0]);
  } catch (error) {
    res.status(500).json({ error: "Failed to fetch user", message: error.message });
  }
});

// ── Privacy Policy endpoint ──────────────────────────────────────

app.get("/api/privacy-policy", async (req, res) => {
  try {
    const result = await pool.query("SELECT lang, content FROM privacy_policies");
    const policies = {};
    for (const row of result.rows) {
      policies[row.lang] = row.content;
    }
    res.json(policies);
  } catch (error) {
    console.error("Privacy policy error:", error);
    res.status(500).json({ error: "Failed to fetch privacy policy", message: error.message });
  }
});

// ── Landmark endpoints ──────────────────────────────────────────

// Language name map for Gemini prompt
const LANG_NAMES = {
  en: "English",
  ru: "Russian",
  es: "Spanish",
  fr: "French",
  de: "German",
  pt: "Portuguese",
};

app.post("/api/analyze", upload.single("image"), async (req, res) => {
  try {
    if (!req.file) {
      return res.status(400).json({ error: "No image file provided" });
    }

    const filePath = req.file.path;
    const imageFilename = req.file.filename;
    const imageData = fs.readFileSync(filePath);
    const base64Image = imageData.toString("base64");
    const mimeType = req.file.mimetype || "image/jpeg";
    const language = req.body?.language || "en";
    const langName = LANG_NAMES[language] || "English";

    console.log(`[Analyze] Language requested: ${language} (${langName})`);

    const model = genAI.getGenerativeModel({
      model: "gemini-2.5-flash",
      generationConfig: {
        thinkingConfig: { thinkingBudget: 0 }
      }
    });

    const prompt = `Analyze this image and identify the landmark, building, or place of interest shown.
IMPORTANT: All text values in your response MUST be written in ${langName} language.
Respond ONLY with a JSON object in this exact format, no markdown, no code fences:
{
  "name": "Name of the landmark (in ${langName})",
  "location": "Full address or City, Country (in ${langName})",
  "year_built": "Year or era built (in ${langName})",
  "status": "UNESCO Site / National Monument / Historic Landmark / etc. (in ${langName})",
  "architect": "Architect or dynasty/civilization that built it (in ${langName})",
  "capacity": "Visitor capacity or notable size metric (in ${langName})",
  "narrative_p1": "A 2-3 sentence paragraph about the landmark history and significance. (in ${langName})",
  "narrative_quote": "A memorable quote or fact about the landmark. (in ${langName})",
  "narrative_p2": "A 2-3 sentence paragraph about an interesting architectural or cultural detail. (in ${langName})",
  "nearby1_name": "Name of a nearby attraction (in ${langName})",
  "nearby1_category": "Category like Museum, Park, Monument, etc. (in ${langName})",
  "nearby2_name": "Name of another nearby attraction (in ${langName})",
  "nearby2_category": "Category (in ${langName})",
  "nearby3_name": "Name of a third nearby attraction (in ${langName})",
  "nearby3_category": "Category (in ${langName})"
}
If you cannot identify a specific landmark, make your best guess based on the architectural style and features visible. Remember: ALL values must be in ${langName}.`;

    const result = await model.generateContent([
      prompt,
      {
        inlineData: {
          mimeType: mimeType,
          data: base64Image,
        },
      },
    ]);

    const responseText = result.response.text();
    console.log("Gemini raw response:", responseText);

    // Clean markdown fences if present
    const cleanJson = responseText
      .replace(/```json\n?/g, "")
      .replace(/```\n?/g, "")
      .trim();

    const parsed = JSON.parse(cleanJson);

    // Save to database with user_id, device_id, and optional location
    let userId = req.body?.user_id || null;
    const deviceId = req.body?.device_id || null;

    // Validate user_id exists; if not, try to find/create by device_id
    if (userId) {
      const userCheck = await pool.query("SELECT id FROM users WHERE id = $1", [userId]);
      if (userCheck.rows.length === 0) {
        console.log("user_id", userId, "not found, looking up by device_id:", deviceId);
        if (deviceId) {
          const byDevice = await pool.query(
            "SELECT id FROM users WHERE device_id = $1 ORDER BY id DESC LIMIT 1",
            [deviceId]
          );
          if (byDevice.rows.length > 0) {
            userId = byDevice.rows[0].id;
            console.log("Found user by device_id:", userId);
          } else {
            // Create guest user for this device
            const newUser = await pool.query(
              "INSERT INTO users (auth_type, display_name, device_id) VALUES ('guest', 'Guest', $1) RETURNING id",
              [deviceId]
            );
            userId = newUser.rows[0].id;
            console.log("Created new guest user:", userId);
          }
        } else {
          userId = null;
        }
      }
    }
    const latitude = req.body?.latitude ? parseFloat(req.body.latitude) : null;
    const longitude = req.body?.longitude ? parseFloat(req.body.longitude) : null;
    console.log("Received fields - user_id:", userId, "device_id:", deviceId, "language:", language, "latitude:", req.body?.latitude, "longitude:", req.body?.longitude);

    const insertResult = await pool.query(
      `INSERT INTO landmarks (
        user_id, device_id, name, location, year_built, status, architect, capacity,
        narrative_p1, narrative_quote, narrative_p2,
        nearby1_name, nearby1_category, nearby2_name, nearby2_category,
        nearby3_name, nearby3_category, image_filename, latitude, longitude, language
      ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16, $17, $18, $19, $20, $21) RETURNING id`,
      [
        userId,
        deviceId,
        parsed.name || "",
        parsed.location || "",
        parsed.year_built || "",
        parsed.status || "",
        parsed.architect || "",
        parsed.capacity || "",
        parsed.narrative_p1 || "",
        parsed.narrative_quote || "",
        parsed.narrative_p2 || "",
        parsed.nearby1_name || "",
        parsed.nearby1_category || "",
        parsed.nearby2_name || "",
        parsed.nearby2_category || "",
        parsed.nearby3_name || "",
        parsed.nearby3_category || "",
        imageFilename,
        latitude,
        longitude,
        language,
      ]
    );

    res.json({ id: insertResult.rows[0].id, latitude, longitude, language, ...parsed });
  } catch (error) {
    console.error("Analysis error:", error);
    res.status(500).json({ error: "Analysis failed", message: error.message });
  }
});

// Update landmark rating
app.put("/api/landmarks/:id/rating", async (req, res) => {
  try {
    const { rating } = req.body;
    if (rating === undefined || rating < 0 || rating > 5) {
      return res.status(400).json({ error: "rating must be 0-5" });
    }
    await pool.query("UPDATE landmarks SET rating = $1 WHERE id = $2", [rating, req.params.id]);
    res.json({ success: true, id: parseInt(req.params.id), rating });
  } catch (error) {
    res.status(500).json({ error: "Failed to update rating", message: error.message });
  }
});

// Save landmark to journal (is_saved = 1)
app.put("/api/landmarks/:id/save", async (req, res) => {
  try {
    await pool.query("UPDATE landmarks SET is_saved = 1 WHERE id = $1", [req.params.id]);
    res.json({ success: true, id: parseInt(req.params.id), is_saved: 1 });
  } catch (error) {
    res.status(500).json({ error: "Failed to save landmark", message: error.message });
  }
});

// Delete a landmark
app.delete("/api/landmarks/:id", async (req, res) => {
  try {
    const id = req.params.id;
    // Get image filename before deleting
    const landmark = await pool.query("SELECT image_filename FROM landmarks WHERE id = $1", [id]);
    if (landmark.rows.length > 0 && landmark.rows[0].image_filename) {
      const imgPath = path.join(uploadDir, landmark.rows[0].image_filename);
      if (fs.existsSync(imgPath)) {
        fs.unlinkSync(imgPath);
      }
    }
    await pool.query("DELETE FROM landmarks WHERE id = $1", [id]);
    res.json({ success: true, id: parseInt(id) });
  } catch (error) {
    res.status(500).json({ error: "Failed to delete landmark", message: error.message });
  }
});

// Get all landmarks for a user (by user_id OR device_id)
app.get("/api/user/:id/landmarks", async (req, res) => {
  try {
    console.log(`[Landmarks] Request: user_id=${req.params.id}, query=`, req.query);
    const savedOnly = req.query.saved === "true";
    const deviceId = req.query.device_id || null;

    let sql;
    let params;

    if (deviceId) {
      // Match landmarks by user_id OR device_id (covers guest + google on same device)
      sql = "SELECT DISTINCT ON (id) * FROM landmarks WHERE user_id = $1 OR device_id = $2";
      params = [req.params.id, deviceId];
    } else {
      sql = "SELECT * FROM landmarks WHERE user_id = $1";
      params = [req.params.id];
    }

    if (savedOnly) sql += " AND is_saved = 1";
    sql += " ORDER BY id DESC";

    console.log(`[Landmarks] SQL: ${sql}, params:`, params);
    const result = await pool.query(sql, params);
    console.log(`[Landmarks] Returned ${result.rows.length} rows`);
    const rows = result.rows.map((row) => {
      row.image_url = `/api/uploads/${row.image_filename}`;
      return row;
    });
    res.json(rows);
  } catch (error) {
    console.error(`[Landmarks] Error:`, error.message);
    res.status(500).json({ error: "Failed to fetch landmarks", message: error.message });
  }
});

// Get landmarks by device_id (for guest users with userId=-1)
app.get("/api/landmarks/by-device", async (req, res) => {
  try {
    const deviceId = req.query.device_id;
    console.log(`[LandmarksByDevice] device_id=${deviceId}`);
    if (!deviceId) {
      return res.status(400).json({ error: "device_id is required" });
    }

    const result = await pool.query(
      "SELECT * FROM landmarks WHERE device_id = $1 ORDER BY id DESC",
      [deviceId]
    );
    console.log(`[LandmarksByDevice] Returned ${result.rows.length} rows`);
    const rows = result.rows.map((row) => {
      row.image_url = `/api/uploads/${row.image_filename}`;
      return row;
    });
    res.json(rows);
  } catch (error) {
    console.error(`[LandmarksByDevice] Error:`, error.message);
    res.status(500).json({ error: "Failed to fetch landmarks", message: error.message });
  }
});

// Nearby landmarks — Haversine distance filter (filtered by user)
app.get("/api/landmarks/nearby", async (req, res) => {
  try {
    const lat = parseFloat(req.query.lat);
    const lng = parseFloat(req.query.lng);
    const radius = parseFloat(req.query.radius) || 50; // km
    const userId = req.query.user_id ? parseInt(req.query.user_id) : null;
    const deviceId = req.query.device_id || null;

    if (isNaN(lat) || isNaN(lng)) {
      return res.status(400).json({ error: "lat and lng are required" });
    }

    // Build user filter
    let userFilter = "";
    const params = [lat, lng, radius];
    if (userId && userId > 0 && deviceId) {
      userFilter = `AND (user_id = $4 OR device_id = $5)`;
      params.push(userId, deviceId);
    } else if (deviceId) {
      userFilter = `AND device_id = $4`;
      params.push(deviceId);
    }

    const result = await pool.query(
      `SELECT * FROM (
        SELECT *, (
          6371 * acos(
            LEAST(1, cos(radians($1)) * cos(radians(latitude))
            * cos(radians(longitude) - radians($2))
            + sin(radians($1)) * sin(radians(latitude)))
          )
        ) AS distance_km
        FROM landmarks
        WHERE latitude IS NOT NULL AND longitude IS NOT NULL ${userFilter}
      ) sub
      WHERE distance_km <= $3
      ORDER BY distance_km ASC
      LIMIT 100`,
      params
    );

    const rows = result.rows.map((row) => {
      row.image_url = `/api/uploads/${row.image_filename}`;
      return row;
    });
    res.json(rows);
  } catch (error) {
    console.error("Nearby landmarks error:", error);
    res.status(500).json({ error: "Failed to fetch nearby landmarks", message: error.message });
  }
});

// History endpoint — returns all saved landmarks, newest first
app.get("/api/history", async (req, res) => {
  try {
    const result = await pool.query("SELECT * FROM landmarks ORDER BY id DESC");
    const rows = result.rows.map((row) => {
      row.image_url = `/api/uploads/${row.image_filename}`;
      return row;
    });
    res.json(rows);
  } catch (error) {
    res.status(500).json({ error: "Failed to fetch history", message: error.message });
  }
});

// ── Google Places API proxy ──────────────────────────────────────

const GOOGLE_PLACES_KEY = process.env.GOOGLE_PLACES_KEY || "AIzaSyDW_5xgz6Bf83iPIkxUoNZZtgQ0MgO6GYw";

// Nearby tourist places — proxy Google Places Nearby Search (multiple types)
app.get("/api/places/nearby", async (req, res) => {
  try {
    const lat = req.query.lat;
    const lng = req.query.lng;
    const radius = req.query.radius || 5000;
    const language = req.query.language || "en";

    if (!lat || !lng) {
      return res.status(400).json({ error: "lat and lng are required" });
    }

    const fetch = (await import("node-fetch")).default;

    console.log(`[Places] Fetching nearby: lat=${lat}, lng=${lng}, radius=${radius}, language=${language}`);

    // 3 optimized searches to cover all 7 categories
    const searches = [
      { type: "tourist_attraction" },  // covers landmarks, museums, viewpoints, religious sites
      { type: "restaurant" },          // covers local food, cafes
      { type: "shopping_mall" },       // covers markets, shops
    ];

    const requests = searches.map(async (s) => {
      const url = `https://maps.googleapis.com/maps/api/place/nearbysearch/json?location=${lat},${lng}&radius=${radius}&type=${s.type}&language=${language}&key=${GOOGLE_PLACES_KEY}`;
      const response = await fetch(url);
      const data = await response.json();
      if (data.status === "OK") return data.results || [];
      return [];
    });

    const allResults = await Promise.all(requests);
    const flatResults = allResults.flat();

    // Deduplicate by place_id
    const seen = new Set();
    const unique = flatResults.filter((place) => {
      if (seen.has(place.place_id)) return false;
      seen.add(place.place_id);
      return true;
    });

    // Assign category based on types
    const places = unique.map((place) => {
      const types = place.types || [];
      let category = "Landmark";

      if (types.includes("museum") || types.includes("art_gallery")) category = "Museum";
      else if (types.includes("church") || types.includes("mosque") || types.includes("hindu_temple") || types.includes("synagogue") || types.includes("place_of_worship")) category = "Religious Site";
      else if (types.includes("park") || types.includes("natural_feature")) category = "Park";
      else if (types.includes("restaurant") || types.includes("cafe") || types.includes("bakery") || types.includes("meal_takeaway")) category = "Local Food";
      else if (types.includes("shopping_mall") || types.includes("store") || types.includes("clothing_store") || types.includes("jewelry_store")) category = "Market";
      else if (types.includes("tourist_attraction")) category = "Landmark";

      return {
        _source: "google_places",
        name: place.name || "",
        location: place.vicinity || "",
        latitude: place.geometry?.location?.lat,
        longitude: place.geometry?.location?.lng,
        rating: place.rating || 0,
        user_ratings_total: place.user_ratings_total || 0,
        category,
        photo_reference: place.photos?.[0]?.photo_reference || "",
        place_id: place.place_id || "",
      };
    });

    // Pick top places per category to ensure variety, then fill to 20
    const categoryLabels = ["Landmark", "Museum", "Religious Site", "Park", "Local Food", "Market", "Viewpoint"];
    const byCategory = {};
    for (const label of categoryLabels) byCategory[label] = [];
    for (const p of places) {
      if (byCategory[p.category]) byCategory[p.category].push(p);
    }
    // Sort each category by rating * user_ratings_total (popularity)
    for (const label of categoryLabels) {
      byCategory[label].sort((a, b) => (b.rating * Math.log(b.user_ratings_total + 1)) - (a.rating * Math.log(a.user_ratings_total + 1)));
    }

    // Pick top 3 from each category first, then fill remaining
    const picked = new Set();
    const top = [];
    for (const label of categoryLabels) {
      const items = byCategory[label];
      for (let i = 0; i < Math.min(3, items.length) && top.length < 20; i++) {
        if (!picked.has(items[i].place_id)) {
          picked.add(items[i].place_id);
          top.push(items[i]);
        }
      }
    }
    // Fill remaining slots with best rated across all categories
    if (top.length < 20) {
      const remaining = places
        .filter((p) => !picked.has(p.place_id))
        .sort((a, b) => (b.rating * Math.log(b.user_ratings_total + 1)) - (a.rating * Math.log(a.user_ratings_total + 1)));
      for (const p of remaining) {
        if (top.length >= 20) break;
        top.push(p);
      }
    }

    console.log(`[Places] Returned ${top.length} top places from ${unique.length} unique`);
    res.json(top);
  } catch (error) {
    console.error("Places nearby error:", error);
    res.status(500).json({ error: "Failed to fetch nearby places", message: error.message });
  }
});

// Google Place Details — returns editorial summary (description)
app.get("/api/places/details", async (req, res) => {
  try {
    const placeId = req.query.place_id;
    const language = req.query.language || "en";
    if (!placeId) {
      return res.status(400).json({ error: "place_id is required" });
    }

    const fetch = (await import("node-fetch")).default;
    const url = `https://maps.googleapis.com/maps/api/place/details/json?place_id=${placeId}&fields=editorial_summary,formatted_address&language=${language}&key=${GOOGLE_PLACES_KEY}`;
    const response = await fetch(url);
    const data = await response.json();

    if (data.status === "OK" && data.result) {
      const description = data.result.editorial_summary?.overview || "";
      const address = data.result.formatted_address || "";
      res.json({ description, address });
    } else {
      res.json({ description: "", address: "" });
    }
  } catch (error) {
    console.error("Place details error:", error);
    res.status(500).json({ error: "Failed to fetch place details" });
  }
});

// Google Places photo proxy
app.get("/api/places/photo", async (req, res) => {
  try {
    const photoRef = req.query.ref;
    if (!photoRef) {
      return res.status(400).json({ error: "photo reference is required" });
    }

    const url = `https://maps.googleapis.com/maps/api/place/photo?maxwidth=400&photo_reference=${photoRef}&key=${GOOGLE_PLACES_KEY}`;
    const fetch = (await import("node-fetch")).default;
    const response = await fetch(url);

    if (!response.ok) {
      return res.status(response.status).json({ error: "Failed to fetch photo" });
    }

    res.set("Content-Type", response.headers.get("content-type"));
    res.set("Cache-Control", "public, max-age=86400");
    const buffer = await response.buffer();
    res.send(buffer);
  } catch (error) {
    console.error("Places photo error:", error);
    res.status(500).json({ error: "Failed to fetch place photo" });
  }
});

// Serve saved images
app.get("/api/uploads/:filename", (req, res) => {
  const filename = path.basename(req.params.filename);
  const filePath = path.join(uploadDir, filename);
  if (fs.existsSync(filePath)) {
    res.sendFile(filePath);
  } else {
    res.status(404).json({ error: "Image not found" });
  }
});

// ── Contact endpoint ────────────────────────────────────────────

app.post("/api/contact", upload.single("screenshot"), async (req, res) => {
  try {
    const { topic, message } = req.body;
    if (!topic || !message) {
      return res.status(400).json({ error: "topic and message are required" });
    }
    const screenshotFilename = req.file ? req.file.filename : null;

    await pool.query(
      "INSERT INTO contact_messages (topic, message, screenshot_filename) VALUES ($1, $2, $3)",
      [topic, message, screenshotFilename]
    );

    console.log(`[Contact] New message: topic="${topic}"`);
    res.json({ success: true });
  } catch (error) {
    console.error("Contact error:", error);
    res.status(500).json({ error: "Failed to send message", message: error.message });
  }
});

// ── Admin Dashboard ─────────────────────────────────────────────

// Serve admin HTML page
app.get("/admin", (req, res) => {
  res.sendFile(path.join(__dirname, "admin.html"));
});

// Admin auth middleware
function adminAuth(req, res, next) {
  const auth = req.headers.authorization;
  if (!auth || !auth.startsWith("Bearer ")) {
    return res.status(401).json({ error: "Unauthorized" });
  }
  const token = auth.split(" ")[1];
  if (!adminTokens.has(token)) {
    return res.status(401).json({ error: "Invalid token" });
  }
  next();
}

// Admin login
app.post("/api/admin/login", (req, res) => {
  const { password } = req.body;
  if (password !== process.env.ADMIN_PASSWORD) {
    return res.status(401).json({ error: "Invalid password" });
  }
  const token = crypto.randomUUID();
  adminTokens.add(token);
  res.json({ token });
});

// Admin overview
app.get("/api/admin/overview", adminAuth, async (req, res) => {
  try {
    const [users, landmarks, saved, avgRating, todayUsers, todayLandmarks, guestCount, googleCount] = await Promise.all([
      pool.query("SELECT COUNT(*) FROM users"),
      pool.query("SELECT COUNT(*) FROM landmarks"),
      pool.query("SELECT COUNT(*) FROM landmarks WHERE is_saved = 1"),
      pool.query("SELECT COALESCE(AVG(rating), 0) AS avg FROM landmarks WHERE rating > 0"),
      pool.query("SELECT COUNT(*) FROM users WHERE created_at >= CURRENT_DATE"),
      pool.query("SELECT COUNT(*) FROM landmarks WHERE created_at >= CURRENT_DATE"),
      pool.query("SELECT COUNT(*) FROM users WHERE auth_type = 'guest'"),
      pool.query("SELECT COUNT(*) FROM users WHERE auth_type = 'google'"),
    ]);

    const mem = process.memoryUsage();
    res.json({
      totalUsers: parseInt(users.rows[0].count),
      guestUsers: parseInt(guestCount.rows[0].count),
      googleUsers: parseInt(googleCount.rows[0].count),
      totalLandmarks: parseInt(landmarks.rows[0].count),
      savedLandmarks: parseInt(saved.rows[0].count),
      avgRating: parseFloat(avgRating.rows[0].avg).toFixed(1),
      todayUsers: parseInt(todayUsers.rows[0].count),
      todayLandmarks: parseInt(todayLandmarks.rows[0].count),
      uptimeSeconds: Math.floor((Date.now() - SERVER_START_TIME) / 1000),
      memoryMB: Math.round(mem.heapUsed / 1024 / 1024),
    });
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

// Admin server monitor
app.get("/api/admin/server", adminAuth, async (req, res) => {
  try {
    const mem = process.memoryUsage();
    const totalMem = os.totalmem();
    const freeMem = os.freemem();
    const cpus = os.cpus();
    const cpuCount = cpus.length;

    // Calculate CPU usage from os.cpus()
    let totalIdle = 0, totalTick = 0;
    for (const cpu of cpus) {
      for (const type in cpu.times) totalTick += cpu.times[type];
      totalIdle += cpu.times.idle;
    }
    const cpuUsage = ((1 - totalIdle / totalTick) * 100).toFixed(1);

    // Upload folder stats
    let uploadSize = 0, uploadCount = 0;
    try {
      const files = fs.readdirSync(uploadDir);
      uploadCount = files.length;
      for (const f of files) {
        const stat = fs.statSync(path.join(uploadDir, f));
        uploadSize += stat.size;
      }
    } catch (e) { /* ignore */ }

    // Pool status
    const poolStatus = {
      total: pool.totalCount,
      idle: pool.idleCount,
      waiting: pool.waitingCount,
    };

    res.json({
      cpu: { usage: parseFloat(cpuUsage), cores: cpuCount },
      ram: {
        total: totalMem,
        used: totalMem - freeMem,
        free: freeMem,
      },
      nodeMemory: {
        heapUsed: mem.heapUsed,
        heapTotal: mem.heapTotal,
        rss: mem.rss,
        external: mem.external,
      },
      uptime: {
        server: Math.floor((Date.now() - SERVER_START_TIME) / 1000),
        system: Math.floor(os.uptime()),
      },
      uploads: {
        size: uploadSize,
        count: uploadCount,
      },
      pool: poolStatus,
      platform: os.platform(),
      hostname: os.hostname(),
      nodeVersion: process.version,
    });
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

// Admin users list
app.get("/api/admin/users", adminAuth, async (req, res) => {
  try {
    const { search, auth_type, page = 1, limit = 50 } = req.query;
    const offset = (parseInt(page) - 1) * parseInt(limit);
    let where = [];
    let params = [];
    let idx = 1;

    if (search) {
      where.push(`(display_name ILIKE $${idx} OR email ILIKE $${idx})`);
      params.push(`%${search}%`);
      idx++;
    }
    if (auth_type) {
      where.push(`auth_type = $${idx}`);
      params.push(auth_type);
      idx++;
    }

    const whereClause = where.length ? "WHERE " + where.join(" AND ") : "";
    const countResult = await pool.query(`SELECT COUNT(*) FROM users ${whereClause}`, params);
    const total = parseInt(countResult.rows[0].count);

    params.push(parseInt(limit));
    params.push(offset);
    const result = await pool.query(
      `SELECT u.*, (SELECT COUNT(*) FROM landmarks l WHERE l.user_id = u.id) AS landmarks_count
       FROM users u ${whereClause}
       ORDER BY u.id DESC LIMIT $${idx} OFFSET $${idx + 1}`,
      params
    );

    res.json({ users: result.rows, total, page: parseInt(page), limit: parseInt(limit) });
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

// Admin user's landmarks
app.get("/api/admin/users/:id/landmarks", adminAuth, async (req, res) => {
  try {
    const result = await pool.query(
      "SELECT id, name, location, language, rating, is_saved, image_filename, created_at FROM landmarks WHERE user_id = $1 ORDER BY id DESC",
      [req.params.id]
    );
    res.json(result.rows);
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

// Admin delete user
app.delete("/api/admin/users/:id", adminAuth, async (req, res) => {
  try {
    const userId = req.params.id;
    // Delete user's landmark images
    const landmarks = await pool.query("SELECT image_filename FROM landmarks WHERE user_id = $1", [userId]);
    for (const row of landmarks.rows) {
      if (row.image_filename) {
        const imgPath = path.join(uploadDir, row.image_filename);
        if (fs.existsSync(imgPath)) fs.unlinkSync(imgPath);
      }
    }
    await pool.query("DELETE FROM landmarks WHERE user_id = $1", [userId]);
    await pool.query("DELETE FROM users WHERE id = $1", [userId]);
    res.json({ success: true });
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

// Admin landmarks list
app.get("/api/admin/landmarks", adminAuth, async (req, res) => {
  try {
    const { search, user_id, language, saved, page = 1, limit = 50 } = req.query;
    const offset = (parseInt(page) - 1) * parseInt(limit);
    let where = [];
    let params = [];
    let idx = 1;

    if (search) {
      where.push(`l.name ILIKE $${idx}`);
      params.push(`%${search}%`);
      idx++;
    }
    if (user_id) {
      where.push(`l.user_id = $${idx}`);
      params.push(parseInt(user_id));
      idx++;
    }
    if (language) {
      where.push(`l.language = $${idx}`);
      params.push(language);
      idx++;
    }
    if (saved !== undefined && saved !== "") {
      where.push(`l.is_saved = $${idx}`);
      params.push(parseInt(saved));
      idx++;
    }

    const whereClause = where.length ? "WHERE " + where.join(" AND ") : "";
    const countResult = await pool.query(`SELECT COUNT(*) FROM landmarks l ${whereClause}`, params);
    const total = parseInt(countResult.rows[0].count);

    params.push(parseInt(limit));
    params.push(offset);
    const result = await pool.query(
      `SELECT l.*, u.display_name AS user_name
       FROM landmarks l LEFT JOIN users u ON l.user_id = u.id
       ${whereClause}
       ORDER BY l.id DESC LIMIT $${idx} OFFSET $${idx + 1}`,
      params
    );

    res.json({ landmarks: result.rows, total, page: parseInt(page), limit: parseInt(limit) });
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

// Admin landmark detail
app.get("/api/admin/landmarks/:id", adminAuth, async (req, res) => {
  try {
    const result = await pool.query(
      `SELECT l.*, u.display_name AS user_name
       FROM landmarks l LEFT JOIN users u ON l.user_id = u.id
       WHERE l.id = $1`,
      [req.params.id]
    );
    if (result.rows.length === 0) return res.status(404).json({ error: "Not found" });
    res.json(result.rows[0]);
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

// Admin delete landmark
app.delete("/api/admin/landmarks/:id", adminAuth, async (req, res) => {
  try {
    const landmark = await pool.query("SELECT image_filename FROM landmarks WHERE id = $1", [req.params.id]);
    if (landmark.rows.length > 0 && landmark.rows[0].image_filename) {
      const imgPath = path.join(uploadDir, landmark.rows[0].image_filename);
      if (fs.existsSync(imgPath)) fs.unlinkSync(imgPath);
    }
    await pool.query("DELETE FROM landmarks WHERE id = $1", [req.params.id]);
    res.json({ success: true });
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

// Admin analytics
app.get("/api/admin/analytics", adminAuth, async (req, res) => {
  try {
    const [regDaily, landmarksDaily, langDist, authDist, topRated, mostActive] = await Promise.all([
      pool.query(`SELECT DATE(created_at) AS date, COUNT(*) AS count FROM users
        WHERE created_at >= CURRENT_DATE - INTERVAL '30 days' GROUP BY DATE(created_at) ORDER BY date`),
      pool.query(`SELECT DATE(created_at) AS date, COUNT(*) AS count FROM landmarks
        WHERE created_at >= CURRENT_DATE - INTERVAL '30 days' GROUP BY DATE(created_at) ORDER BY date`),
      pool.query(`SELECT COALESCE(language, 'en') AS language, COUNT(*) AS count FROM landmarks GROUP BY language ORDER BY count DESC`),
      pool.query(`SELECT auth_type, COUNT(*) AS count FROM users GROUP BY auth_type ORDER BY count DESC`),
      pool.query(`SELECT id, name, rating, location FROM landmarks WHERE rating > 0 ORDER BY rating DESC, id DESC LIMIT 10`),
      pool.query(`SELECT u.id, u.display_name, u.auth_type, COUNT(l.id) AS landmarks_count
        FROM users u LEFT JOIN landmarks l ON u.id = l.user_id
        GROUP BY u.id, u.display_name, u.auth_type ORDER BY landmarks_count DESC LIMIT 10`),
    ]);

    res.json({
      registrationsDaily: regDaily.rows,
      landmarksDaily: landmarksDaily.rows,
      languageDistribution: langDist.rows,
      authDistribution: authDist.rows,
      topRated: topRated.rows,
      mostActive: mostActive.rows,
    });
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

// Admin logs
app.get("/api/admin/logs", adminAuth, (req, res) => {
  const { level, endpoint } = req.query;
  let logs = [...requestLogs].reverse();
  if (level) logs = logs.filter((l) => l.level === level);
  if (endpoint) logs = logs.filter((l) => l.url.includes(endpoint));
  res.json(logs);
});

// Admin contacts
app.get("/api/admin/contacts", adminAuth, async (req, res) => {
  try {
    const { topic } = req.query;
    let sql = "SELECT * FROM contact_messages";
    const params = [];
    if (topic) {
      sql += " WHERE topic = $1";
      params.push(topic);
    }
    sql += " ORDER BY id DESC";
    const result = await pool.query(sql, params);
    res.json(result.rows);
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

// Admin database stats
app.get("/api/admin/database", adminAuth, async (req, res) => {
  try {
    const [users, landmarks, policies, contacts] = await Promise.all([
      pool.query("SELECT COUNT(*) FROM users"),
      pool.query("SELECT COUNT(*) FROM landmarks"),
      pool.query("SELECT COUNT(*) FROM privacy_policies"),
      pool.query("SELECT COUNT(*) FROM contact_messages"),
    ]);

    let uploadSize = 0, uploadCount = 0;
    try {
      const files = fs.readdirSync(uploadDir);
      uploadCount = files.length;
      for (const f of files) {
        const stat = fs.statSync(path.join(uploadDir, f));
        uploadSize += stat.size;
      }
    } catch (e) { /* ignore */ }

    res.json({
      tables: {
        users: parseInt(users.rows[0].count),
        landmarks: parseInt(landmarks.rows[0].count),
        privacy_policies: parseInt(policies.rows[0].count),
        contact_messages: parseInt(contacts.rows[0].count),
      },
      pool: {
        total: pool.totalCount,
        idle: pool.idleCount,
        waiting: pool.waitingCount,
      },
      uploads: {
        size: uploadSize,
        count: uploadCount,
      },
    });
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

// Initialize DB then start server
initDb()
  .then(() => {
    app.listen(PORT, () => {
      console.log("TripAI API server running on port " + PORT);
    });
  })
  .catch((err) => {
    console.error("Failed to initialize database:", err);
    process.exit(1);
  });
