require("dotenv").config({ path: require("path").join(__dirname, ".env") });
const express = require("express");
const multer = require("multer");
const cors = require("cors");
const helmet = require("helmet");
const rateLimit = require("express-rate-limit");
const { GoogleGenerativeAI } = require("@google/generative-ai");
const { google } = require("googleapis");
const fs = require("fs");
const path = require("path");
const os = require("os");
const crypto = require("crypto");
const { Pool } = require("pg");

const app = express();
const PORT = 3001;

// Trust nginx proxy (fixes express-rate-limit X-Forwarded-For validation error)
app.set("trust proxy", 1);

// Security headers
app.use(helmet({
  contentSecurityPolicy: false,  // Disabled for share page HTML
  crossOriginEmbedderPolicy: false,
}));

// CORS — restrict to known origins
app.use(cors({
  origin: function (origin, callback) {
    // Allow requests with no origin (mobile apps, curl)
    if (!origin) return callback(null, true);
    callback(null, true); // TODO: restrict to specific domains when frontend deployed
  },
  methods: ["GET", "POST", "PUT", "DELETE", "OPTIONS"],
  allowedHeaders: ["Content-Type", "Authorization"],
}));

// Body size limits
app.use(express.json({ limit: "1mb" }));
app.use(express.urlencoded({ limit: "1mb", extended: true }));

// Serve ACME challenge for SSL cert renewal
app.use("/.well-known/acme-challenge", express.static("/var/www/letsencrypt/.well-known/acme-challenge"));

// Serve website for sightai.mnaks.online
app.use((req, res, next) => {
  if (req.hostname === "sightai.mnaks.online") {
    return express.static(path.join(__dirname, "website"), { extensions: ["html"] })(req, res, next);
  }
  next();
});

// Global rate limiter — 100 requests per 15 min per IP
const globalLimiter = rateLimit({
  windowMs: 15 * 60 * 1000,
  max: 200,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: "Too many requests, please try again later" },
});
app.use("/api/", globalLimiter);

// Strict rate limiter for Gemini text-only endpoints (no image required — easy to abuse)
const geminiTextLimiter = rateLimit({
  windowMs: 15 * 60 * 1000,
  max: 20,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: "Too many requests, please try again later" },
});

// Strict rate limiter for auth endpoints
const authLimiter = rateLimit({
  windowMs: 15 * 60 * 1000,
  max: 10,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: "Too many login attempts, please try again later" },
});

// Strict rate limiter for analyze endpoint (AI calls are expensive)
const analyzeLimiter = rateLimit({
  windowMs: 60 * 1000,
  max: 5,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: "Too many analysis requests, please try again later" },
});

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

const ALLOWED_MIMES = ["image/jpeg", "image/png", "image/webp", "image/gif"];
const storage = multer.diskStorage({
  destination: (req, file, cb) => cb(null, uploadDir),
  filename: (req, file, cb) => {
    const ext = path.extname(file.originalname).toLowerCase() || ".jpg";
    cb(null, Date.now() + "-" + crypto.randomBytes(8).toString("hex") + ext);
  },
});
const upload = multer({
  storage,
  limits: { fileSize: 10 * 1024 * 1024 },  // 10MB max
  fileFilter: (req, file, cb) => {
    if (ALLOWED_MIMES.includes(file.mimetype)) {
      cb(null, true);
    } else {
      cb(new Error("Only image files (JPEG, PNG, WebP, GIF) are allowed"));
    }
  },
});

// Gemini AI setup
const genAI = new GoogleGenerativeAI(process.env.GEMINI_API_KEY);

// PostgreSQL database
const pool = new Pool({
  host: process.env.DB_HOST || "localhost",
  port: process.env.DB_PORT || 5432,
  database: process.env.DB_NAME || "tripai",
  user: process.env.DB_USER || "tripai_user",
  password: process.env.DB_PASSWORD,
  max: 20,
  connectionTimeoutMillis: 5000,
  idleTimeoutMillis: 30000,
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

    // Add token usage columns to landmarks table
    await client.query(`ALTER TABLE landmarks ADD COLUMN IF NOT EXISTS tokens_input INTEGER DEFAULT 0`);
    await client.query(`ALTER TABLE landmarks ADD COLUMN IF NOT EXISTS tokens_output INTEGER DEFAULT 0`);
    await client.query(`ALTER TABLE landmarks ADD COLUMN IF NOT EXISTS tokens_total INTEGER DEFAULT 0`);

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

    // Upsert privacy policies on every start so content stays up to date
    await seedPrivacyPolicies(client);

    // Add is_favorited column
    await client.query(`ALTER TABLE landmarks ADD COLUMN IF NOT EXISTS is_favorited INTEGER DEFAULT 0`);

    // Add landmark geographic coordinates (where the landmark IS, not where the user is)
    await client.query(`ALTER TABLE landmarks ADD COLUMN IF NOT EXISTS landmark_lat DOUBLE PRECISION`);
    await client.query(`ALTER TABLE landmarks ADD COLUMN IF NOT EXISTS landmark_lng DOUBLE PRECISION`);

    // Add is_ar column (1 = saved from AR Live Scan)
    await client.query(`ALTER TABLE landmarks ADD COLUMN IF NOT EXISTS is_ar INTEGER DEFAULT 0`);

    // Backfill: existing AR saves always have a Wikipedia URL as image_filename
    await client.query(`UPDATE landmarks SET is_ar = 1 WHERE image_filename LIKE 'http%' AND is_ar = 0`);

    // Add subscription plan columns
    await client.query(`ALTER TABLE users ADD COLUMN IF NOT EXISTS plan VARCHAR(20) DEFAULT 'free'`);
    await client.query(`ALTER TABLE users ADD COLUMN IF NOT EXISTS daily_scans INTEGER DEFAULT 0`);
    await client.query(`ALTER TABLE users ADD COLUMN IF NOT EXISTS scan_date DATE`);
    await client.query(`ALTER TABLE users ADD COLUMN IF NOT EXISTS subscription_expires_at TIMESTAMPTZ`);
    await client.query(`ALTER TABLE users ADD COLUMN IF NOT EXISTS purchase_token TEXT`);

    // Plan limits table
    await client.query(`
      CREATE TABLE IF NOT EXISTS plan_limits (
        plan VARCHAR(20) PRIMARY KEY,
        scans_per_day INTEGER NOT NULL DEFAULT 5,
        max_journal INTEGER NOT NULL DEFAULT 20,
        max_queue INTEGER NOT NULL DEFAULT 3,
        max_pins INTEGER NOT NULL DEFAULT 20,
        audio_enabled BOOLEAN NOT NULL DEFAULT FALSE,
        share_enabled BOOLEAN NOT NULL DEFAULT FALSE
      )
    `);
    // Seed default limits if empty
    await client.query(`
      INSERT INTO plan_limits (plan, scans_per_day, max_journal, max_queue, max_pins, audio_enabled, share_enabled) VALUES
        ('free', 5,   20, 3,   20, FALSE, FALSE),
        ('plus', 50,  -1, 20,  -1, TRUE,  TRUE),
        ('pro',  200, -1, -1,  -1, TRUE,  TRUE)
      ON CONFLICT (plan) DO NOTHING
    `);

    // Badges table (definitions)
    await client.query(`
      CREATE TABLE IF NOT EXISTS badges (
        id VARCHAR(50) PRIMARY KEY,
        emoji VARCHAR(10) NOT NULL,
        title VARCHAR(100) NOT NULL,
        description VARCHAR(255) NOT NULL,
        sort_order INTEGER NOT NULL DEFAULT 0
      )
    `);

    // User earned badges
    await client.query(`
      CREATE TABLE IF NOT EXISTS user_badges (
        id SERIAL PRIMARY KEY,
        user_id INTEGER,
        device_id VARCHAR(255),
        badge_id VARCHAR(50) NOT NULL REFERENCES badges(id),
        earned_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
      )
    `);
    await client.query(`CREATE UNIQUE INDEX IF NOT EXISTS idx_user_badges_user ON user_badges(user_id, badge_id) WHERE user_id IS NOT NULL`);
    await client.query(`CREATE UNIQUE INDEX IF NOT EXISTS idx_user_badges_device ON user_badges(device_id, badge_id) WHERE device_id IS NOT NULL AND user_id IS NULL`);

    // Seed badge definitions
    await client.query(`
      INSERT INTO badges (id, emoji, title, description, sort_order) VALUES
        ('first_scan',     '🏛️', 'First Discovery',  'Scanned your first landmark',       1),
        ('explorer_5',     '🗺️', 'Explorer',         'Scanned 5 landmarks',                2),
        ('adventurer_10',  '⛺', 'Adventurer',        'Scanned 10 landmarks',               3),
        ('traveler_25',    '✈️', 'World Traveler',    'Scanned 25 landmarks',               4),
        ('legend_50',      '🏆', 'Legend',            'Scanned 50 landmarks',               5),
        ('first_ar',       '📡', 'AR Pioneer',        'Used AR Live Scan for the first time', 6),
        ('ar_explorer',    '🔭', 'AR Explorer',       'Completed 5 AR Live Scans',          7),
        ('globetrotter',   '🌍', 'Globetrotter',      'Visited landmarks in 3 countries',   8),
        ('world_explorer', '🌐', 'World Explorer',    'Visited landmarks in 5 countries',   9),
        ('critic',         '⭐', 'Critic',            'Gave your first star rating',        10),
        ('journalist',     '📖', 'Journalist',        'Saved your first landmark to journal', 11)
      ON CONFLICT (id) DO NOTHING
    `);

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
- Location Data: With your permission, we collect your device's location to enhance landmark identification accuracy and show nearby places. SightAI also uses background location to detect when you are near a landmark you have previously saved, and to send you a local proximity notification. Location data is processed on-device and is never sent to our servers.
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
  res.json({ status: "ok", service: "sightai-api", db: "postgresql" });
});

// ── Device language lookup (for restoring language after reinstall) ──
app.get("/api/device/:deviceId/language", async (req, res) => {
  try {
    const deviceId = req.params.deviceId;
    if (!deviceId) {
      return res.status(400).json({ error: "device_id is required" });
    }
    const result = await pool.query(
      "SELECT language FROM users WHERE device_id = $1 ORDER BY last_login_at DESC LIMIT 1",
      [deviceId]
    );
    if (result.rows.length > 0 && result.rows[0].language) {
      res.json({ language: result.rows[0].language });
    } else {
      res.json({ language: "en" });
    }
  } catch (error) {
    console.error("Device language lookup error:", error);
    res.json({ language: "en" });
  }
});

// Save language by device ID (for pre-login language changes)
app.put("/api/device/:deviceId/language", async (req, res) => {
  try {
    const deviceId = req.params.deviceId;
    const { language } = req.body;
    const LANGS = ["en", "ru", "es", "fr", "de", "pt"];
    if (!deviceId || !language || !LANGS.includes(language)) {
      return res.status(400).json({ error: "Invalid device_id or language" });
    }
    // Update language for the most recent user with this device_id
    const result = await pool.query(
      "UPDATE users SET language = $1 WHERE device_id = $2",
      [language, deviceId]
    );
    if (result.rowCount > 0) {
      res.json({ success: true });
    } else {
      // No user found with this device_id yet — that's OK, language will be set on login
      res.json({ success: true, note: "no_user_yet" });
    }
  } catch (error) {
    console.error("Device language save error:", error);
    res.status(500).json({ error: "Server error" });
  }
});

// ── Auth endpoints ──────────────────────────────────────────────

// Guest login — creates or finds guest user by device_id
app.post("/api/auth/guest", authLimiter, async (req, res) => {
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
    res.status(500).json({ error: "Authentication failed" });
  }
});

// Google login — creates or finds user by google_id
app.post("/api/auth/google", authLimiter, async (req, res) => {
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
    res.status(500).json({ error: "Authentication failed" });
  }
});

// Update user language
const SUPPORTED_LANGUAGES = ["en", "ru", "es", "fr", "de", "pt"];
app.put("/api/users/:id/language", async (req, res) => {
  try {
    const { language } = req.body;
    if (!language || !SUPPORTED_LANGUAGES.includes(language)) {
      return res.status(400).json({ error: "Unsupported language" });
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
    res.status(500).json({ error: "Failed to update language" });
  }
});

// Travel Passport — user's badges (must be before /api/user/:id to avoid param conflict)
app.get("/api/user/badges", async (req, res) => {
  try {
    const userId = req.query.user_id ? parseInt(req.query.user_id) : null;
    const deviceId = req.query.device_id || null;

    if (!userId && !deviceId) {
      return res.status(400).json({ error: "user_id or device_id required" });
    }

    const allBadges = await pool.query("SELECT * FROM badges ORDER BY sort_order");

    const earnedQuery = userId
      ? `SELECT badge_id, earned_at FROM user_badges WHERE user_id = $1`
      : `SELECT badge_id, earned_at FROM user_badges WHERE device_id = $1 AND user_id IS NULL`;
    const earned = await pool.query(earnedQuery, [userId || deviceId]);
    const earnedMap = {};
    for (const row of earned.rows) earnedMap[row.badge_id] = row.earned_at;

    const result = allBadges.rows.map(b => ({
      ...b,
      earned: !!earnedMap[b.id],
      earned_at: earnedMap[b.id] || null,
    }));

    res.json(result);
  } catch (error) {
    console.error("Badges error:", error);
    res.status(500).json({ error: "Failed to fetch badges" });
  }
});

// Get user profile
app.get("/api/user/:id", async (req, res) => {
  try {
    const deviceId = (req.query.device_id || "").trim();
    const userId = parseInt(req.params.id);
    if (!deviceId) return res.status(400).json({ error: "device_id is required" });
    const result = await pool.query(
      "SELECT * FROM users WHERE id = $1 AND device_id = $2",
      [userId, deviceId]
    );
    if (result.rows.length === 0) {
      return res.status(404).json({ error: "User not found" });
    }
    res.json(result.rows[0]);
  } catch (error) {
    res.status(500).json({ error: "Failed to fetch user" });
  }
});


// ── Verify Purchase endpoint ──────────────────────────────────────

const PLAN_MAP = {
  "plan_traveler_monthly":     "plus",
  "plan_globetrotter_monthly": "pro"
};

// ── Plan limits cache (loaded from DB) ────────────────────────
let planLimitsCache = {
  free: { scans_per_day: 5,   max_journal: 20, max_queue: 3,  max_pins: 20, audio_enabled: false, share_enabled: false },
  plus: { scans_per_day: 50,  max_journal: -1, max_queue: 20, max_pins: -1, audio_enabled: true,  share_enabled: true  },
  pro:  { scans_per_day: 200, max_journal: -1, max_queue: -1, max_pins: -1, audio_enabled: true,  share_enabled: true  },
};

async function loadPlanLimits() {
  try {
    const result = await pool.query("SELECT * FROM plan_limits");
    for (const row of result.rows) {
      planLimitsCache[row.plan] = {
        scans_per_day: row.scans_per_day,
        max_journal:   row.max_journal,
        max_queue:     row.max_queue,
        max_pins:      row.max_pins,
        audio_enabled: row.audio_enabled,
        share_enabled: row.share_enabled,
      };
    }
    console.log("[PlanLimits] Loaded from DB:", JSON.stringify(planLimitsCache));
  } catch (e) {
    console.error("[PlanLimits] Failed to load, using defaults:", e.message);
  }
}
// called after DB init

app.post("/api/verify-purchase", async (req, res) => {
  const { userId, deviceId, purchaseToken, productId } = req.body;
  if (!purchaseToken || !productId) {
    return res.status(400).json({ error: "Missing purchaseToken or productId" });
  }

  const newPlan = PLAN_MAP[productId];
  if (!newPlan) {
    return res.status(400).json({ error: "Unknown productId" });
  }

  try {
    // ── 1. Verify with Google Play API ──────────────────────────
    const keyPath = path.join(__dirname, "google-play-key.json");
    const auth = new google.auth.GoogleAuth({
      keyFile: keyPath,
      scopes: ["https://www.googleapis.com/auth/androidpublisher"]
    });

    const androidPublisher = google.androidpublisher({ version: "v3", auth });
    const packageName = "online.mnaks.sightai";

    let subscription;
    try {
      const verifyResponse = await androidPublisher.purchases.subscriptions.get({
        packageName,
        subscriptionId: productId,
        token: purchaseToken
      });
      subscription = verifyResponse.data;
    } catch (googleError) {
      // Google API call failed — never fall back, always reject
      console.error("[Purchase] Google API error:", googleError.message);
      return res.status(402).json({
        error: "purchase_not_verified",
        message: "Could not verify purchase with Google. Please try again."
      });
    }

    // ── 2. Check payment was actually received ───────────────────
    // paymentState: 0=pending, 1=received, 2=free trial, 3=deferred
    const paymentState = subscription.paymentState;
    if (paymentState !== 1 && paymentState !== 2) {
      console.warn(`[Purchase] Payment not received. paymentState=${paymentState}, token=${purchaseToken}`);
      return res.status(402).json({
        error: "payment_not_received",
        message: "Payment is pending or was not completed."
      });
    }

    // ── 3. Check subscription has not expired ────────────────────
    const expiryMs = parseInt(subscription.expiryTimeMillis || "0");
    if (expiryMs < Date.now()) {
      console.warn(`[Purchase] Subscription expired. expiry=${new Date(expiryMs)}, token=${purchaseToken}`);
      return res.status(402).json({ error: "subscription_expired", message: "Subscription has expired." });
    }

    // ── 4. Prevent token reuse across different accounts ─────────
    const tokenCheck = await pool.query(
      "SELECT id, device_id FROM users WHERE purchase_token = $1 LIMIT 1",
      [purchaseToken]
    );
    if (tokenCheck.rows.length > 0) {
      const existing = tokenCheck.rows[0];
      const requestingId = userId ? parseInt(userId) : null;
      const sameUser = (requestingId && existing.id === requestingId) ||
                       (deviceId && existing.device_id === deviceId);
      if (!sameUser) {
        console.error(`[Purchase] Token reuse attempt! token=${purchaseToken}, existing_user=${existing.id}, requester=${userId || deviceId}`);
        return res.status(403).json({ error: "token_already_used", message: "Purchase token already associated with another account." });
      }
    }

    // ── 5. Grant plan ────────────────────────────────────────────
    const expiryDate = new Date(expiryMs);
    if (userId && parseInt(userId) > 0) {
      await pool.query(
        "UPDATE users SET plan = $1, subscription_expires_at = $2, purchase_token = $3 WHERE id = $4",
        [newPlan, expiryDate, purchaseToken, parseInt(userId)]
      );
    } else if (deviceId) {
      await pool.query(
        "UPDATE users SET plan = $1, subscription_expires_at = $2, purchase_token = $3 WHERE device_id = $4",
        [newPlan, expiryDate, purchaseToken, deviceId]
      );
    } else {
      return res.status(400).json({ error: "No userId or deviceId provided" });
    }

    console.log(`[Purchase] ✓ Verified: productId=${productId}, plan=${newPlan}, paymentState=${paymentState}, userId=${userId}, expires=${expiryDate}`);
    res.json({ success: true, plan: newPlan, expires_at: expiryDate });

  } catch (error) {
    console.error("[Purchase] Unexpected error:", error.message);
    res.status(500).json({ error: "Verification failed" });
  }
});

// ── Subscription downgrade (called when no active purchase found) ──
app.post("/api/subscription/downgrade", async (req, res) => {
  const { userId, deviceId } = req.body;
  if (!userId && !deviceId) {
    return res.status(400).json({ error: "userId or deviceId required" });
  }
  try {
    if (userId && parseInt(userId) > 0) {
      await pool.query(
        "UPDATE users SET plan = 'free', subscription_expires_at = NULL, purchase_token = NULL WHERE id = $1 AND (subscription_expires_at IS NULL OR subscription_expires_at < NOW())",
        [parseInt(userId)]
      );
    } else {
      await pool.query(
        "UPDATE users SET plan = 'free', subscription_expires_at = NULL, purchase_token = NULL WHERE device_id = $1 AND (subscription_expires_at IS NULL OR subscription_expires_at < NOW())",
        [deviceId]
      );
    }
    console.log(`[Downgrade] Downgraded to free: userId=${userId}, deviceId=${deviceId}`);
    res.json({ success: true, plan: "free" });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// ── Public plan limits endpoint ───────────────────────────────────
app.get("/api/plan-limits", (req, res) => {
  res.json(planLimitsCache);
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

// ── AR Identify endpoint (fast, no DB save, no scan-limit deduction) ──────────
const arLimiter = rateLimit({
  windowMs: 60 * 1000,
  max: 60,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: "Too many AR requests, please try again later" },
});

app.post("/api/ar-identify", arLimiter, upload.single("image"), async (req, res) => {
  const device_id = req.body?.device_id || "unknown";
  console.log(`[AR] Request from device=${device_id}, hasFile=${!!req.file}`);
  try {
    if (!req.file) {
      console.log("[AR] No file — returning not_a_landmark");
      return res.json({ not_a_landmark: true });
    }

    const filePath = req.file.path;
    const [imageData] = await Promise.all([
      fs.promises.readFile(filePath),
    ]);
    const base64Image = imageData.toString("base64");
    const mimeType = req.file.mimetype || "image/jpeg";

    // Clean up temp file async (don't wait)
    fs.unlink(filePath, () => {});

    const model = genAI.getGenerativeModel({ model: "gemini-2.0-flash" });

    const prompt = `Is this a recognizable landmark or building? Reply with JSON only, no markdown.
Not a landmark: {"not_a_landmark":true}
Is a landmark: {"not_a_landmark":false,"name":"...","location":"City, Country","year_built":"..."}`;


    const result = await model.generateContent([
      { text: prompt },
      {
        inlineData: {
          mimeType: mimeType,
          data: base64Image,
        },
      },
    ]);

    const raw = result.response.text().trim();
    // Strip markdown fences if present
    const cleaned = raw.replace(/^```(?:json)?\s*/i, "").replace(/\s*```$/, "").trim();
    const json = JSON.parse(cleaned);
    // Treat vague responses as not_a_landmark
    const vaguePatterns = /discernible|cannot determine|unidentifiable|not identifiable|unknown location|cannot be identified|indeterminate|not recognizable/i;
    if (!json.not_a_landmark && (!json.name || vaguePatterns.test(json.name) || vaguePatterns.test(json.location || ""))) {
      json.not_a_landmark = true;
    }
    console.log(`[AR] Result: not_a_landmark=${json.not_a_landmark}, name=${json.name || "-"}`);
    return res.json(json);
  } catch (e) {
    console.error("[AR Identify] Error:", e.message);
    return res.json({ not_a_landmark: true });
  }
});
// ── Wikipedia image helper (exact title → full-text search fallback) ─────────
async function fetchWikipediaImage(name, location = "") {
  const headers = { "User-Agent": "SightAI/1.0 (sightai@mnaks.online)" };
  try {
    // Step 1: exact title (with redirects)
    const r1 = await fetch(
      `https://en.wikipedia.org/w/api.php?action=query&titles=${encodeURIComponent(name)}&prop=pageimages&format=json&pithumbsize=1200&redirects=1`,
      { headers }
    );
    if (r1.ok) {
      const d1 = await r1.json();
      const page1 = Object.values(d1.query?.pages || {})[0];
      if (page1?.thumbnail?.source) return page1.thumbnail.source;
    }
    // Step 2: text search with name + first city word for disambiguation
    const city = location ? location.split(",")[0].trim() : "";
    const query = city ? `${name} ${city}` : name;
    const r2 = await fetch(
      `https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=${encodeURIComponent(query)}&srlimit=1&format=json`,
      { headers }
    );
    if (r2.ok) {
      const d2 = await r2.json();
      const title = d2.query?.search?.[0]?.title;
      if (title) {
        const r3 = await fetch(
          `https://en.wikipedia.org/w/api.php?action=query&titles=${encodeURIComponent(title)}&prop=pageimages&format=json&pithumbsize=1200`,
          { headers }
        );
        if (r3.ok) {
          const d3 = await r3.json();
          const page3 = Object.values(d3.query?.pages || {})[0];
          if (page3?.thumbnail?.source) return page3.thumbnail.source;
        }
      }
    }
  } catch (_) {}
  return null;
}
// ── Landmark Info endpoint (text-only, for AR "View Full Details") ────────────
app.get("/api/landmark-info", geminiTextLimiter, async (req, res) => {
  const name = (req.query.name || "").trim();
  const lang = (req.query.lang || "en").trim();
  const deviceId = (req.query.device_id || "").trim();
  if (!name) return res.status(400).json({ error: "name is required" });
  if (!deviceId) return res.status(400).json({ error: "device_id is required" });
  const deviceCheck = await pool.query("SELECT id FROM users WHERE device_id = $1 LIMIT 1", [deviceId]);
  if (deviceCheck.rows.length === 0) return res.status(403).json({ error: "Unauthorized" });

  const langName = LANG_NAMES[lang] || "English";

  try {
    const model = genAI.getGenerativeModel({ model: "gemini-2.0-flash" });
    const prompt = `Provide detailed information about the landmark: "${name}".
Reply ONLY with a JSON object, no markdown, no code fences. ALL text values must be in ${langName}:
{
  "name": "Official name of the landmark",
  "location": "Full address or City, Country",
  "year_built": "Year or era built",
  "status": "UNESCO Site / National Monument / Historic Landmark / etc.",
  "architect": "Architect or dynasty/civilization that built it",
  "capacity": "Visitor capacity or notable size metric",
  "narrative_p1": "A 2-3 sentence paragraph about the landmark history and significance.",
  "narrative_quote": "A memorable quote or fact about the landmark.",
  "narrative_p2": "A 2-3 sentence paragraph about an interesting architectural or cultural detail.",
  "nearby1_name": "Name of a nearby attraction",
  "nearby1_category": "Category like Museum, Park, Monument, etc.",
  "nearby2_name": "Name of another nearby attraction",
  "nearby2_category": "Category",
  "nearby3_name": "Name of a third nearby attraction",
  "nearby3_category": "Category"
}`;

    const result = await model.generateContent(prompt);
    const raw = result.response.text().trim();
    const cleaned = raw.replace(/^```(?:json)?\s*/i, "").replace(/\s*```$/, "").trim();
    const json = JSON.parse(cleaned);

    // Fetch Wikipedia photo with name + location fallback search
    const photoUrl = await fetchWikipediaImage(name, json.location || "");
    if (photoUrl) json.photo_url = photoUrl;

    return res.json(json);
  } catch (e) {
    console.error("[Landmark Info] Error:", e.message);
    return res.status(500).json({ error: "Failed to fetch landmark info" });
  }
});
// ── Save AR landmark to journal (fetches Wikipedia image) ────────────────────
app.post("/api/landmarks/save-from-ar", async (req, res) => {
  const {
    device_id, user_id, name, location, year_built, status, architect, capacity,
    narrative_p1, narrative_quote, narrative_p2,
    nearby1_name, nearby1_category, nearby2_name, nearby2_category,
    nearby3_name, nearby3_category, language, rating
  } = req.body;

  if (!name) return res.status(400).json({ error: "name is required" });

  // Fetch Wikipedia thumbnail with name + location fallback search
  const wikiImageUrl = await fetchWikipediaImage(name, location || "");

  const client = await pool.connect();
  try {
    const result = await client.query(
      `INSERT INTO landmarks (
        user_id, device_id, name, location, year_built, status, architect, capacity,
        narrative_p1, narrative_quote, narrative_p2,
        nearby1_name, nearby1_category, nearby2_name, nearby2_category,
        nearby3_name, nearby3_category, language, image_filename, is_saved, is_ar, rating
      ) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18,$19,1,1,$20)
      RETURNING id`,
      [
        user_id || null, device_id || null, name, location || "", year_built || "",
        status || "", architect || "", capacity || "",
        narrative_p1 || "", narrative_quote || "", narrative_p2 || "",
        nearby1_name || "", nearby1_category || "", nearby2_name || "", nearby2_category || "",
        nearby3_name || "", nearby3_category || "", language || "en",
        wikiImageUrl,
        (Number.isInteger(rating) && rating >= 0 && rating <= 5) ? rating : 0
      ]
    );
    const newBadges = await checkAndAwardBadges(user_id ? parseInt(user_id) : null, device_id || null);
    res.json({ success: true, id: result.rows[0].id, new_badges: newBadges });
  } catch (e) {
    console.error("[Save AR] Error:", e.message);
    res.status(500).json({ error: "Failed to save landmark" });
  } finally {
    client.release();
  }
});
// ──────────────────────────────────────────────────────────────────────────────

app.post("/api/analyze", analyzeLimiter, upload.single("image"), async (req, res) => {
  try {
    if (!req.file) {
      return res.status(400).json({ error: "No image file provided" });
    }

    const filePath = req.file.path;
    const imageFilename = req.file.filename;
    const mimeType = req.file.mimetype || "image/jpeg";
    const language = req.body?.language || "en";
    const langName = LANG_NAMES[language] || "English";

    console.log(`[Analyze] Language requested: ${language} (${langName})`);

    // ── Read file + scan limit check in parallel ────────────────
    const PLAN_LIMITS = {
      free: planLimitsCache.free?.scans_per_day ?? 5,
      plus: planLimitsCache.plus?.scans_per_day ?? 50,
      pro:  planLimitsCache.pro?.scans_per_day  ?? 200,
    };
    const rawUserId = req.body?.user_id || null;
    const rawDeviceId = req.body?.device_id || null;
    const limitUserId = rawUserId ? parseInt(rawUserId) : null;

    const userQuery = limitUserId
      ? pool.query("SELECT id, plan, daily_scans, scan_date FROM users WHERE id = $1", [limitUserId])
      : rawDeviceId
        ? pool.query("SELECT id, plan, daily_scans, scan_date FROM users WHERE device_id = $1 ORDER BY id DESC LIMIT 1", [rawDeviceId])
        : Promise.resolve({ rows: [] });

    const [imageData, uRes] = await Promise.all([
      fs.promises.readFile(filePath),
      userQuery,
    ]);
    const base64Image = imageData.toString("base64");

    let userPlan = "free";
    let scansToday = 0;
    let scanUserId = null;
    let scanIsNewDay = false;

    if (uRes.rows.length > 0) {
      const u = uRes.rows[0];
      userPlan = u.plan || "free";
      const today = new Date().toISOString().split("T")[0];
      const savedDate = u.scan_date ? u.scan_date.toISOString().split("T")[0] : null;
      scansToday = savedDate === today ? u.daily_scans : 0;
      const limit = PLAN_LIMITS[userPlan] || PLAN_LIMITS.free;
      if (scansToday >= limit) {
        fs.unlink(filePath, () => {});
        return res.status(429).json({
          error: "scan_limit_reached",
          message: `Daily limit of ${limit} scans reached for your ${userPlan} plan.`,
          plan: userPlan, scans_today: scansToday, scan_limit: limit,
        });
      }
      scanUserId = u.id;
      scanIsNewDay = savedDate !== today;
    }
    // ────────────────────────────────────────────────────────────

    const model = genAI.getGenerativeModel({
      model: "gemini-2.0-flash",
    });

    const prompt = `Analyze this image. First, determine if it shows a real-world landmark, building, monument, historical site, or recognizable place of interest.

If the image does NOT show a real-world landmark or place (e.g. it is a drawing, painting, cartoon, screenshot, random object, person, animal, food, or an unidentifiable scene), respond ONLY with:
{"not_a_landmark": true}

If it DOES show a real-world landmark or place, respond ONLY with a JSON object in this exact format, no markdown, no code fences. ALL text values must be in ${langName}:
{
  "not_a_landmark": false,
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
  "nearby3_category": "Category (in ${langName})",
  "landmark_lat": <exact decimal latitude of the landmark itself, e.g. 41.8902>,
  "landmark_lng": <exact decimal longitude of the landmark itself, e.g. 12.4922>
}`;

    // Single retry with short wait for rate limits
    let result;
    try {
      result = await model.generateContent([
        prompt,
        {
          inlineData: {
            mimeType: mimeType,
            data: base64Image,
          },
        },
      ]);
    } catch (apiError) {
      if (apiError.status === 429) {
        // Check if daily quota is exhausted (limit: 0 means no retrying will help)
        const isDailyExhausted = apiError.message && apiError.message.includes("limit: 0");
        if (isDailyExhausted) {
          console.error("[Analyze] Daily API quota exhausted (limit: 0)");
          return res.status(429).json({
            error: "API quota exhausted",
            message: "Daily API limit reached. Please try again tomorrow.",
            retryable: false
          });
        }
        // Per-minute rate limit — return immediately so the app can show feedback
        console.log("[Analyze] Rate limited (429) — returning retryable error immediately");
        fs.unlink(filePath, () => {});
        return res.status(429).json({
          error: "API rate limited",
          message: "Server is busy. Please try again in a moment.",
          retryable: true
        });
      } else {
        throw apiError;
      }
    }

    const responseText = result.response.text();
    const usage = result.response.usageMetadata;
    const tokensInput = usage?.promptTokenCount || 0;
    const tokensOutput = usage?.candidatesTokenCount || 0;
    const tokensTotal = usage?.totalTokenCount || 0;
    console.log(`[Analyze] Tokens — input: ${tokensInput}, output: ${tokensOutput}, total: ${tokensTotal}`);
    console.log("Gemini raw response:", responseText);

    // Clean markdown fences if present
    const cleanJson = responseText
      .replace(/```json\n?/g, "")
      .replace(/```\n?/g, "")
      .trim();

    const parsed = JSON.parse(cleanJson);

    // Reject non-landmark images — do NOT count this against daily limit
    if (parsed.not_a_landmark === true) {
      console.log("[Analyze] Not a landmark — rejecting without counting scan");
      return res.status(422).json({
        error: "not_a_landmark",
        message: "This doesn't appear to be a landmark or place of interest. Please try again with a photo of a real-world landmark, building, or monument."
      });
    }

    // Reject vague Gemini responses where location/name couldn't be identified
    const vaguePatterns = /discernible|cannot determine|unidentifiable|not identifiable|unknown location|cannot be identified|indeterminate|not recognizable/i;
    if (!parsed.name || vaguePatterns.test(parsed.name) || vaguePatterns.test(parsed.location || "")) {
      console.log("[Analyze] Vague response from Gemini — rejecting:", parsed.name, parsed.location);
      return res.status(422).json({
        error: "not_a_landmark",
        message: "This doesn't appear to be a landmark or place of interest. Please try again with a photo of a real-world landmark, building, or monument."
      });
    }

    // Increment scan count only for successfully identified landmarks
    if (scanUserId) {
      if (scanIsNewDay) {
        await pool.query("UPDATE users SET daily_scans = 1, scan_date = CURRENT_DATE WHERE id = $1", [scanUserId]);
      } else {
        await pool.query("UPDATE users SET daily_scans = daily_scans + 1 WHERE id = $1", [scanUserId]);
      }
      scansToday++;
    }

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

    const landmarkLat = (typeof parsed.landmark_lat === "number" && isFinite(parsed.landmark_lat)) ? parsed.landmark_lat : null;
    const landmarkLng = (typeof parsed.landmark_lng === "number" && isFinite(parsed.landmark_lng)) ? parsed.landmark_lng : null;

    const insertResult = await pool.query(
      `INSERT INTO landmarks (
        user_id, device_id, name, location, year_built, status, architect, capacity,
        narrative_p1, narrative_quote, narrative_p2,
        nearby1_name, nearby1_category, nearby2_name, nearby2_category,
        nearby3_name, nearby3_category, image_filename, latitude, longitude, language,
        tokens_input, tokens_output, tokens_total, landmark_lat, landmark_lng
      ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16, $17, $18, $19, $20, $21, $22, $23, $24, $25, $26) RETURNING id`,
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
        tokensInput,
        tokensOutput,
        tokensTotal,
        landmarkLat,
        landmarkLng,
      ]
    );

    const newBadges = await checkAndAwardBadges(userId ? parseInt(userId) : null, deviceId);

    res.json({
      id: insertResult.rows[0].id, latitude, longitude, language, ...parsed,
      plan: userPlan,
      scans_today: scansToday,
      scan_limit: PLAN_LIMITS[userPlan] || PLAN_LIMITS.free,
      new_badges: newBadges,
    });
  } catch (error) {
    console.error("Analysis error:", error);
    res.status(500).json({ error: "Analysis failed" });
  }
});

// Backfill landmark_lat/landmark_lng for existing rows using Google Geocoding
async function backfillLandmarkCoordinates() {
  try {
    const rows = await pool.query(
      `SELECT id, name, location FROM landmarks
       WHERE landmark_lat IS NULL AND location != ''
       LIMIT 100`
    );
    if (rows.rows.length === 0) return;
    console.log(`[Backfill] Geocoding ${rows.rows.length} landmarks...`);
    for (const row of rows.rows) {
      try {
        const query = encodeURIComponent(`${row.name} ${row.location}`);
        const url = `https://maps.googleapis.com/maps/api/geocode/json?address=${query}&key=${process.env.GOOGLE_PLACES_KEY}`;
        const res = await fetch(url);
        const data = await res.json();
        if (data.status === "OK" && data.results.length > 0) {
          const loc = data.results[0].geometry.location;
          await pool.query(
            "UPDATE landmarks SET landmark_lat = $1, landmark_lng = $2 WHERE id = $3",
            [loc.lat, loc.lng, row.id]
          );
        }
        await new Promise(r => setTimeout(r, 50)); // avoid rate limit
      } catch (e) {
        console.error(`[Backfill] Failed for id=${row.id}:`, e.message);
      }
    }
    console.log("[Backfill] Done");
  } catch (e) {
    console.error("[Backfill] Error:", e.message);
  }
}

// Run backfill once on startup (non-blocking)
backfillLandmarkCoordinates();

// Check and award badges after a landmark save
async function checkAndAwardBadges(userId, deviceId) {
  try {
    const idParam = userId ? [userId] : [deviceId];
    const whereClause = userId ? "user_id = $1" : "device_id = $1";

    // Get counts
    const countResult = await pool.query(
      `SELECT
        COUNT(*) AS total_scans,
        COUNT(*) FILTER (WHERE is_ar = 1) AS ar_scans,
        COUNT(*) FILTER (WHERE rating > 0) AS ratings_given,
        COUNT(*) FILTER (WHERE is_saved = 1) AS saved_count
       FROM landmarks WHERE ${whereClause}`,
      idParam
    );
    const stats = countResult.rows[0];
    const totalScans = parseInt(stats.total_scans) || 0;
    const arScans = parseInt(stats.ar_scans) || 0;
    const ratingsGiven = parseInt(stats.ratings_given) || 0;
    const savedCount = parseInt(stats.saved_count) || 0;

    // Count distinct countries from location strings
    const locResult = await pool.query(
      `SELECT DISTINCT location FROM landmarks WHERE ${whereClause} AND location != ''`,
      idParam
    );
    const countrySet = new Set(locResult.rows.map(r => {
      const parts = r.location.split(",").map(p => p.trim());
      return parts.length >= 2 ? parts[parts.length - 1] : "";
    }).filter(Boolean));
    const countries = countrySet.size;

    const badgeConditions = [
      { id: "first_scan",     met: totalScans >= 1 },
      { id: "explorer_5",     met: totalScans >= 5 },
      { id: "adventurer_10",  met: totalScans >= 10 },
      { id: "traveler_25",    met: totalScans >= 25 },
      { id: "legend_50",      met: totalScans >= 50 },
      { id: "first_ar",       met: arScans >= 1 },
      { id: "ar_explorer",    met: arScans >= 5 },
      { id: "globetrotter",   met: countries >= 3 },
      { id: "world_explorer", met: countries >= 5 },
      { id: "critic",         met: ratingsGiven >= 1 },
      { id: "journalist",     met: savedCount >= 1 },
    ];

    // Get already earned badges
    const earnedQuery = userId
      ? `SELECT badge_id FROM user_badges WHERE user_id = $1`
      : `SELECT badge_id FROM user_badges WHERE device_id = $1 AND user_id IS NULL`;
    const earnedResult = await pool.query(earnedQuery, idParam);
    const earnedSet = new Set(earnedResult.rows.map(r => r.badge_id));

    // Award new badges
    const newBadges = [];
    for (const { id, met } of badgeConditions) {
      if (met && !earnedSet.has(id)) {
        try {
          if (userId) {
            await pool.query(
              "INSERT INTO user_badges (user_id, device_id, badge_id) VALUES ($1, $2, $3) ON CONFLICT DO NOTHING",
              [userId, deviceId || null, id]
            );
          } else {
            await pool.query(
              "INSERT INTO user_badges (user_id, device_id, badge_id) VALUES (NULL, $1, $2) ON CONFLICT DO NOTHING",
              [deviceId, id]
            );
          }
          const bRes = await pool.query("SELECT * FROM badges WHERE id = $1", [id]);
          if (bRes.rows.length > 0) newBadges.push(bRes.rows[0]);
        } catch (e) {
          console.error(`[Badges] Failed to award ${id}:`, e.message);
        }
      }
    }

    return newBadges;
  } catch (e) {
    console.error("[Badges] checkAndAwardBadges error:", e.message);
    return [];
  }
}

// Verify landmark ownership helper
async function verifyLandmarkOwner(landmarkId, userId, deviceId) {
  const result = await pool.query("SELECT user_id, device_id FROM landmarks WHERE id = $1", [landmarkId]);
  if (result.rows.length === 0) return { found: false };
  const row = result.rows[0];
  const owned = (userId && row.user_id === parseInt(userId)) || (deviceId && row.device_id === deviceId);
  return { found: true, owned };
}

// Update landmark rating
app.put("/api/landmarks/:id/rating", async (req, res) => {
  try {
    const { rating, user_id, device_id } = req.body;
    if (rating === undefined || !Number.isInteger(rating) || rating < 0 || rating > 5) {
      return res.status(400).json({ error: "rating must be integer 0-5" });
    }
    const ownership = await verifyLandmarkOwner(req.params.id, user_id, device_id);
    if (!ownership.found) return res.status(404).json({ error: "Landmark not found" });
    if (!ownership.owned) return res.status(403).json({ error: "Forbidden" });

    await pool.query("UPDATE landmarks SET rating = $1 WHERE id = $2", [rating, req.params.id]);
    res.json({ success: true, id: parseInt(req.params.id), rating });
  } catch (error) {
    console.error("Rating error:", error);
    res.status(500).json({ error: "Failed to update rating" });
  }
});

// Save landmark to journal (is_saved = 1)
app.put("/api/landmarks/:id/save", async (req, res) => {
  try {
    const { user_id, device_id } = req.body;
    const ownership = await verifyLandmarkOwner(req.params.id, user_id, device_id);
    if (!ownership.found) return res.status(404).json({ error: "Landmark not found" });
    if (!ownership.owned) return res.status(403).json({ error: "Forbidden" });

    await pool.query("UPDATE landmarks SET is_saved = 1 WHERE id = $1", [req.params.id]);
    res.json({ success: true, id: parseInt(req.params.id), is_saved: 1 });
  } catch (error) {
    console.error("Save error:", error);
    res.status(500).json({ error: "Failed to save landmark" });
  }
});

// Toggle favorite (heart) for a landmark
app.put("/api/landmarks/:id/favorite", async (req, res) => {
  try {
    const { user_id, device_id } = req.body;
    const ownership = await verifyLandmarkOwner(req.params.id, user_id, device_id);
    if (!ownership.found) return res.status(404).json({ error: "Landmark not found" });
    if (!ownership.owned) return res.status(403).json({ error: "Forbidden" });

    // Toggle: flip 0→1 or 1→0
    const result = await pool.query(
      "UPDATE landmarks SET is_favorited = CASE WHEN is_favorited = 1 THEN 0 ELSE 1 END WHERE id = $1 RETURNING is_favorited",
      [req.params.id]
    );
    const newValue = result.rows[0]?.is_favorited ?? 0;
    res.json({ success: true, id: parseInt(req.params.id), is_favorited: newValue });
  } catch (error) {
    console.error("Favorite toggle error:", error);
    res.status(500).json({ error: "Failed to toggle favorite" });
  }
});

// Search landmark by name — ask Gemini to generate data for a given query
app.get("/api/landmarks/search", geminiTextLimiter, async (req, res) => {
  try {
    const q = (req.query.q || "").trim();
    const language = req.query.language || "en";
    const langName = LANG_NAMES[language] || "English";
    const deviceId = (req.query.device_id || "").trim();
    if (!q) return res.status(400).json({ error: "Missing query" });
    if (!deviceId) return res.status(400).json({ error: "device_id is required" });
    const deviceCheck = await pool.query("SELECT id FROM users WHERE device_id = $1 LIMIT 1", [deviceId]);
    if (deviceCheck.rows.length === 0) return res.status(403).json({ error: "Unauthorized" });

    const model = genAI.getGenerativeModel({ model: "gemini-2.0-flash" });
    const prompt = `Generate detailed information about the following landmark or place: "${q}".
Respond ONLY with a JSON object in this exact format, no markdown, no code fences. ALL text values must be in ${langName}:
{
  "name": "Official name of the landmark (in ${langName})",
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
  "nearby3_category": "Category (in ${langName})",
  "landmark_lat": <exact decimal latitude of the landmark itself>,
  "landmark_lng": <exact decimal longitude of the landmark itself>
}
If the query does not match any known real-world landmark, respond with: {"not_found": true}`;

    const result = await model.generateContent(prompt);
    const raw = result.response.text()
      .replace(/```json\n?/g, "").replace(/```\n?/g, "").trim();
    const parsed = JSON.parse(raw);
    if (parsed.not_found) return res.status(404).json({ error: "Landmark not found" });
    res.json(parsed);
  } catch (e) {
    console.error("[LandmarkSearch]", e.message);
    res.status(500).json({ error: "Search failed" });
  }
});

// Correct a saved landmark with new AI-generated data
app.patch("/api/landmarks/:id/correct", async (req, res) => {
  try {
    const id = parseInt(req.params.id);
    const { user_id, device_id, name, location, year_built, status, architect, capacity,
            narrative_p1, narrative_quote, narrative_p2,
            nearby1_name, nearby1_category, nearby2_name, nearby2_category,
            nearby3_name, nearby3_category, landmark_lat, landmark_lng } = req.body;

    // Verify ownership
    const check = await pool.query(
      "SELECT id FROM landmarks WHERE id = $1 AND (user_id = $2 OR device_id = $3)",
      [id, user_id || null, device_id || null]
    );
    if (check.rows.length === 0) return res.status(403).json({ error: "Not authorized" });

    await pool.query(
      `UPDATE landmarks SET
        name=$1, location=$2, year_built=$3, status=$4, architect=$5, capacity=$6,
        narrative_p1=$7, narrative_quote=$8, narrative_p2=$9,
        nearby1_name=$10, nearby1_category=$11, nearby2_name=$12, nearby2_category=$13,
        nearby3_name=$14, nearby3_category=$15, landmark_lat=$16, landmark_lng=$17
       WHERE id=$18`,
      [name, location, year_built, status, architect, capacity,
       narrative_p1, narrative_quote, narrative_p2,
       nearby1_name, nearby1_category, nearby2_name, nearby2_category,
       nearby3_name, nearby3_category, landmark_lat, landmark_lng, id]
    );
    res.json({ success: true });
  } catch (e) {
    console.error("[CorrectLandmark]", e.message);
    res.status(500).json({ error: "Update failed" });
  }
});

// Heatmap — anonymized scan coordinates for all users
app.get("/api/landmarks/heatmap", async (req, res) => {
  try {
    const result = await pool.query(
      `SELECT
         ROUND(landmark_lat::numeric, 3) AS lat,
         ROUND(landmark_lng::numeric, 3) AS lng,
         COUNT(*) AS weight
       FROM landmarks
       WHERE landmark_lat IS NOT NULL AND landmark_lng IS NOT NULL
       GROUP BY ROUND(landmark_lat::numeric, 3), ROUND(landmark_lng::numeric, 3)
       LIMIT 2000`
    );
    const points = result.rows.map(r => ({
      lat: parseFloat(r.lat),
      lng: parseFloat(r.lng),
      weight: parseInt(r.weight),
    }));
    res.json(points);
  } catch (error) {
    console.error("Heatmap error:", error);
    res.status(500).json({ error: "Failed to fetch heatmap data" });
  }
});

// Trending Spots — all users' favorited AR landmarks, shuffled
app.get("/api/landmarks/favorites", async (req, res) => {
  try {
    const sql = "SELECT * FROM landmarks WHERE is_favorited = 1 AND is_ar = 1 ORDER BY RANDOM() LIMIT 20";
    const params = [];

    const result = await pool.query(sql, params);
    const rows = result.rows.map((row) => {
      row.image_url = row.image_filename
        ? (row.image_filename.startsWith("http") ? row.image_filename : `/api/uploads/${row.image_filename}`)
        : "";
      return row;
    });
    res.json(rows);
  } catch (error) {
    console.error("Favorites error:", error);
    res.status(500).json({ error: "Failed to fetch favorites" });
  }
});

// Delete a landmark
app.delete("/api/landmarks/:id", async (req, res) => {
  try {
    const id = req.params.id;
    const userId = req.query.user_id || req.body?.user_id;
    const deviceId = req.query.device_id || req.body?.device_id;

    const ownership = await verifyLandmarkOwner(id, userId, deviceId);
    if (!ownership.found) return res.status(404).json({ error: "Landmark not found" });
    if (!ownership.owned) return res.status(403).json({ error: "Forbidden" });

    // Get image filename before deleting
    const landmark = await pool.query("SELECT image_filename FROM landmarks WHERE id = $1", [id]);
    if (landmark.rows.length > 0 && landmark.rows[0].image_filename) {
      const imgPath = path.join(uploadDir, landmark.rows[0].image_filename);
      try { fs.unlinkSync(imgPath); } catch (e) { /* file may not exist */ }
    }
    await pool.query("DELETE FROM landmarks WHERE id = $1", [id]);
    res.json({ success: true, id: parseInt(id) });
  } catch (error) {
    console.error("Delete error:", error);
    res.status(500).json({ error: "Failed to delete landmark" });
  }
});

// Get all landmarks for a user (by user_id OR device_id)
app.get("/api/user/:id/landmarks", async (req, res) => {
  try {
    console.log(`[Landmarks] Request: user_id=${req.params.id}, query=`, req.query);
    const savedOnly = req.query.saved === "true";
    const deviceId = req.query.device_id || null;
    const userId = parseInt(req.params.id);

    // Verify the requesting device owns this user account
    if (!deviceId) return res.status(400).json({ error: "device_id is required" });
    const ownerCheck = await pool.query(
      "SELECT id FROM users WHERE id = $1 AND device_id = $2 LIMIT 1",
      [userId, deviceId]
    );
    if (ownerCheck.rows.length === 0) return res.status(403).json({ error: "Unauthorized" });

    let sql;
    let params;

    // Match landmarks by user_id OR device_id (covers guest + google on same device)
    sql = "SELECT DISTINCT ON (id) * FROM landmarks WHERE (user_id = $1 OR device_id = $2)";
    params = [userId, deviceId];

    if (savedOnly) sql += " AND is_saved = 1";
    sql += " ORDER BY id DESC";

    console.log(`[Landmarks] SQL: ${sql}, params:`, params);
    const result = await pool.query(sql, params);
    console.log(`[Landmarks] Returned ${result.rows.length} rows`);
    const rows = result.rows.map((row) => {
      row.image_url = row.image_filename
        ? (row.image_filename.startsWith("http") ? row.image_filename : `/api/uploads/${row.image_filename}`)
        : "";
      return row;
    });
    res.json(rows);
  } catch (error) {
    console.error(`[Landmarks] Error:`, error.message);
    res.status(500).json({ error: "Failed to fetch landmarks" });
  }
});

// Memory notifications — landmarks scanned exactly 1 year ago today
app.get("/api/landmarks/memories", async (req, res) => {
  try {
    const userId = req.query.user_id ? parseInt(req.query.user_id) : null;
    const deviceId = req.query.device_id;
    if (!userId && !deviceId) return res.status(400).json({ error: "user_id or device_id required" });

    let result;
    if (userId && userId > 0) {
      result = await pool.query(
        "SELECT * FROM landmarks WHERE user_id = $1 AND is_saved = 1 AND DATE(created_at) = CURRENT_DATE - INTERVAL '1 year' ORDER BY id DESC",
        [userId]
      );
    } else {
      result = await pool.query(
        "SELECT * FROM landmarks WHERE device_id = $1 AND is_saved = 1 AND DATE(created_at) = CURRENT_DATE - INTERVAL '1 year' ORDER BY id DESC",
        [deviceId]
      );
    }

    const rows = result.rows.map(row => ({
      ...row,
      image_url: row.image_filename
        ? (row.image_filename.startsWith("http") ? row.image_filename : `/api/uploads/${row.image_filename}`)
        : ""
    }));
    res.json(rows);
  } catch (error) {
    console.error("[Memories]", error.message);
    res.status(500).json({ error: "Failed to fetch memories" });
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

    const savedOnly = req.query.saved === "true";
    let sql = "SELECT * FROM landmarks WHERE device_id = $1";
    if (savedOnly) sql += " AND is_saved = 1";
    sql += " ORDER BY id DESC";
    const result = await pool.query(sql, [deviceId]);
    console.log(`[LandmarksByDevice] Returned ${result.rows.length} rows`);
    const rows = result.rows.map((row) => {
      row.image_url = row.image_filename
        ? (row.image_filename.startsWith("http") ? row.image_filename : `/api/uploads/${row.image_filename}`)
        : "";
      return row;
    });
    res.json(rows);
  } catch (error) {
    console.error(`[LandmarksByDevice] Error:`, error.message);
    res.status(500).json({ error: "Failed to fetch landmarks" });
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
      row.image_url = row.image_filename
        ? (row.image_filename.startsWith("http") ? row.image_filename : `/api/uploads/${row.image_filename}`)
        : "";
      return row;
    });
    res.json(rows);
  } catch (error) {
    console.error("Nearby landmarks error:", error);
    res.status(500).json({ error: "Failed to fetch nearby landmarks" });
  }
});

// History endpoint — returns landmarks filtered by user
app.get("/api/history", async (req, res) => {
  try {
    const userId = req.query.user_id ? parseInt(req.query.user_id) : null;
    const deviceId = req.query.device_id || null;

    if (!userId && !deviceId) {
      return res.status(400).json({ error: "user_id or device_id is required" });
    }

    let sql, params;
    if (userId && deviceId) {
      sql = "SELECT * FROM landmarks WHERE (user_id = $1 OR device_id = $2) ORDER BY id DESC LIMIT 200";
      params = [userId, deviceId];
    } else if (userId) {
      sql = "SELECT * FROM landmarks WHERE user_id = $1 ORDER BY id DESC LIMIT 200";
      params = [userId];
    } else {
      sql = "SELECT * FROM landmarks WHERE device_id = $1 ORDER BY id DESC LIMIT 200";
      params = [deviceId];
    }

    const result = await pool.query(sql, params);
    const rows = result.rows.map((row) => {
      row.image_url = row.image_filename
        ? (row.image_filename.startsWith("http") ? row.image_filename : `/api/uploads/${row.image_filename}`)
        : "";
      return row;
    });
    res.json(rows);
  } catch (error) {
    console.error("History error:", error);
    res.status(500).json({ error: "Failed to fetch history" });
  }
});

// ── Google Places API proxy ──────────────────────────────────────

const GOOGLE_PLACES_KEY = process.env.GOOGLE_PLACES_KEY;
if (!GOOGLE_PLACES_KEY) {
  console.warn("WARNING: GOOGLE_PLACES_KEY not set — /api/places/* endpoints will fail");
}

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
    res.status(500).json({ error: "Failed to fetch nearby places" });
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

    const response = await fetch(url);

    if (!response.ok) {
      return res.status(response.status).json({ error: "Failed to fetch photo" });
    }

    res.set("Content-Type", response.headers.get("content-type"));
    res.set("Cache-Control", "public, max-age=86400");
    const arrayBuf = await response.arrayBuffer();
    res.send(Buffer.from(arrayBuf));
  } catch (error) {
    console.error("Places photo error:", error);
    res.status(500).json({ error: "Failed to fetch place photo" });
  }
});

// ── Backfill missing Wikipedia images for existing NULL records ───────────────
app.post("/api/admin/backfill-images", async (req, res) => {
  try {
    const result = await pool.query(
      "SELECT id, name, location FROM landmarks WHERE image_filename IS NULL OR image_filename = ''"
    );
    const rows = result.rows;
    let updated = 0;
    for (const row of rows) {
      const url = await fetchWikipediaImage(row.name, row.location || "");
      if (url) {
        await pool.query("UPDATE landmarks SET image_filename = $1 WHERE id = $2", [url, row.id]);
        updated++;
        console.log(`[Backfill] id=${row.id} "${row.name}" → ${url}`);
      }
    }
    res.json({ total: rows.length, updated });
  } catch (e) {
    console.error("[Backfill] Error:", e.message);
    res.status(500).json({ error: e.message });
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
    res.status(500).json({ error: "Failed to send message" });
  }
});

// ── Share page (OG meta for social previews) ───────────────────

app.get("/share/:id", async (req, res) => {
  try {
    const result = await pool.query(
      "SELECT id, name, location, rating, image_filename, narrative_p1, narrative_quote, narrative_p2, year_built, status, architect, capacity, nearby1_name, nearby1_category, nearby2_name, nearby2_category, nearby3_name, nearby3_category FROM landmarks WHERE id = $1",
      [req.params.id]
    );
    if (result.rows.length === 0) {
      return res.status(404).send("Landmark not found");
    }

    const lm = result.rows[0];
    const name = escapeHtml(lm.name || "Discovery");
    const location = escapeHtml(lm.location || "");
    const p1 = escapeHtml(lm.narrative_p1 || "");
    const quote = escapeHtml(lm.narrative_quote || "");
    const p2 = escapeHtml(lm.narrative_p2 || "");
    const yearBuilt = escapeHtml(lm.year_built || "");
    const status = escapeHtml(lm.status || "");
    const architect = escapeHtml(lm.architect || "");
    const capacity = escapeHtml(lm.capacity || "");
    const nearby = [
      { name: escapeHtml(lm.nearby1_name || ""), cat: escapeHtml(lm.nearby1_category || "") },
      { name: escapeHtml(lm.nearby2_name || ""), cat: escapeHtml(lm.nearby2_category || "") },
      { name: escapeHtml(lm.nearby3_name || ""), cat: escapeHtml(lm.nearby3_category || "") },
    ].filter(n => n.name);
    const rating = Math.max(0, Math.min(5, parseInt(lm.rating) || 0));
    const baseUrl = process.env.BASE_URL || `http://${req.headers.host}`;
    const imageUrl = lm.image_filename
      ? `${baseUrl}/api/uploads/${lm.image_filename}`
      : "";
    const shareUrl = `${baseUrl}/share/${lm.id}`;
    const description = quote || location || "Discovered with TravelAI";

    // Star HTML
    const starsHtml = Array.from({ length: 5 }, (_, i) =>
      i < rating
        ? '<svg class="star" viewBox="0 0 20 20"><path d="M9.049 2.927c.3-.921 1.603-.921 1.902 0l1.07 3.292a1 1 0 00.95.69h3.462c.969 0 1.371 1.24.588 1.81l-2.8 2.034a1 1 0 00-.364 1.118l1.07 3.292c.3.921-.755 1.688-1.54 1.118l-2.8-2.034a1 1 0 00-1.175 0l-2.8 2.034c-.784.57-1.838-.197-1.539-1.118l1.07-3.292a1 1 0 00-.364-1.118L2.98 8.72c-.783-.57-.38-1.81.588-1.81h3.461a1 1 0 00.951-.69l1.07-3.292z"/></svg>'
        : '<svg class="star empty" viewBox="0 0 20 20"><path d="M9.049 2.927c.3-.921 1.603-.921 1.902 0l1.07 3.292a1 1 0 00.95.69h3.462c.969 0 1.371 1.24.588 1.81l-2.8 2.034a1 1 0 00-.364 1.118l1.07 3.292c.3.921-.755 1.688-1.54 1.118l-2.8-2.034a1 1 0 00-1.175 0l-2.8 2.034c-.784.57-1.838-.197-1.539-1.118l1.07-3.292a1 1 0 00-.364-1.118L2.98 8.72c-.783-.57-.38-1.81.588-1.81h3.461a1 1 0 00.951-.69l1.07-3.292z"/></svg>'
    ).join("");

    res.send(`<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>${name} — TravelAI</title>
<meta property="og:title" content="${name}">
<meta property="og:description" content="${description}">
<meta property="og:image" content="${imageUrl}">
<meta property="og:url" content="${shareUrl}">
<meta property="og:type" content="website">
<meta property="og:site_name" content="TravelAI">
<meta name="twitter:card" content="summary_large_image">
<meta name="twitter:title" content="${name}">
<meta name="twitter:description" content="${description}">
<meta name="twitter:image" content="${imageUrl}">
<link href="https://fonts.googleapis.com/css2?family=Plus+Jakarta+Sans:wght@400;500;600;700;800&display=swap" rel="stylesheet">
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{min-height:100vh;font-family:'Plus Jakarta Sans',sans-serif;background:#f3f4f6;display:flex;align-items:center;justify-content:center;padding:16px}
.card{max-width:375px;width:100%;background:#fff;border-radius:12px;overflow:hidden;box-shadow:0 20px 25px -5px rgba(0,0,0,0.1),0 10px 10px -5px rgba(0,0,0,0.04);display:flex;flex-direction:column}
.top{position:relative;width:100%;aspect-ratio:9/12;overflow:hidden}
.top img{width:100%;height:100%;object-fit:cover;display:block}
.badge{position:absolute;top:20px;left:20px;background:rgba(255,255,255,0.9);backdrop-filter:blur(12px);padding:6px 12px;border-radius:999px;display:flex;align-items:center;gap:8px;border:1px solid rgba(255,255,255,0.2);box-shadow:0 1px 3px rgba(0,0,0,0.08)}
.badge-dot{width:8px;height:8px;background:#25aff4;border-radius:50%;animation:pulse 2s infinite}
.badge-text{font-size:10px;font-weight:700;text-transform:uppercase;letter-spacing:0.1em;color:#1e293b}
.logo{position:absolute;top:20px;right:20px;background:#25aff4;padding:8px;border-radius:12px;box-shadow:0 4px 12px rgba(37,175,244,0.3)}
.logo svg{display:block}
.bottom{padding:20px 24px;display:flex;flex-direction:column;gap:8px}
h1{font-size:22px;font-weight:800;color:#0f172a;letter-spacing:-0.02em;line-height:1.2}
.stars-row{display:flex;align-items:center;gap:2px}
.star{width:16px;height:16px;fill:#facc15}
.star.empty{fill:#e2e8f0}
.rating-num{font-size:12px;font-weight:600;color:#94a3b8;margin-left:4px}
.location{color:#64748b;font-size:13px;font-weight:500;display:flex;align-items:center;gap:4px}
.info-grid{display:grid;grid-template-columns:1fr 1fr;gap:8px 16px;margin-top:4px}
.info-item .label{font-size:9px;font-weight:700;color:#94a3b8;text-transform:uppercase;letter-spacing:0.08em}
.info-item .value{font-size:13px;font-weight:600;color:#0f172a;margin-top:1px}
.divider{border-top:1px solid #f1f5f9;margin:8px 0}
.narrative{color:#004d4d;font-size:13px;font-weight:400;line-height:1.6}
.quote-block{border-left:3px solid #DFC623;padding-left:10px;margin:6px 0}
.quote-text{color:#92700A;font-size:12px;font-style:italic;font-weight:500;line-height:1.5}
.nearby-label{font-size:9px;font-weight:700;color:#94a3b8;text-transform:uppercase;letter-spacing:0.08em;margin-top:6px}
.nearby-list{margin-top:4px;display:flex;flex-direction:column;gap:2px}
.nearby-item{font-size:12px;color:#0f172a;font-weight:500}
.nearby-item span{color:#94a3b8;font-weight:400;font-size:11px;margin-left:4px}
.footer{display:flex;align-items:center;justify-content:space-between;padding:12px 24px;border-top:1px solid #f1f5f9}
.footer-brand{display:flex;align-items:center;gap:8px}
.footer-label{font-size:10px;font-weight:700;color:#94a3b8;text-transform:uppercase;letter-spacing:0.1em}
.footer-icon{width:24px;height:24px;border-radius:6px;background:#f1f5f9;display:flex;align-items:center;justify-content:center}
.footer-icon svg{width:12px;height:12px}
.no-img{width:100%;aspect-ratio:9/12;background:#e2e8f0;display:flex;align-items:center;justify-content:center;color:#94a3b8;font-size:48px}
@keyframes pulse{0%,100%{opacity:1}50%{opacity:.5}}
</style>
</head>
<body>
<div class="card">
  <div class="top">
    ${imageUrl ? `<img src="${imageUrl}" alt="${name}">` : '<div class="no-img">&#127963;</div>'}
    <div class="badge"><div class="badge-dot"></div><span class="badge-text">AI Discovery</span></div>
    <div class="logo"><svg width="20" height="20" fill="none" stroke="white" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" viewBox="0 0 24 24"><path d="M17.8 19.2 16 11l3.5-3.5C21 6 21.5 4 21 3c-1-.5-3 0-4.5 1.5L13 8 4.8 6.2c-.5-.1-.9.1-1.1.5l-.3.5c-.2.5-.1 1 .3 1.3L9 12l-2 3H4l-1 1 3 2 2 3 1-1v-3l3-2 3.5 5.3c.3.4.8.5 1.3.3l.5-.2c.4-.3.6-.7.5-1.2z"/></svg></div>
  </div>
  <div class="bottom">
    <h1>${name}</h1>
    ${location ? `<div class="location">&#128205; ${location}</div>` : ""}
    ${rating > 0 ? `<div class="stars-row">${starsHtml}<span class="rating-num">${rating}.0</span></div>` : ""}
    ${(yearBuilt || status || architect || capacity) ? `<div class="info-grid">${yearBuilt ? `<div class="info-item"><div class="label">Year Built</div><div class="value">${yearBuilt}</div></div>` : ""}${status ? `<div class="info-item"><div class="label">Status</div><div class="value">${status}</div></div>` : ""}${architect ? `<div class="info-item"><div class="label">Architect</div><div class="value">${architect}</div></div>` : ""}${capacity ? `<div class="info-item"><div class="label">Capacity</div><div class="value">${capacity}</div></div>` : ""}</div>` : ""}
    <div class="divider"></div>
    ${p1 ? `<p class="narrative">${p1}</p>` : ""}
    ${quote ? `<div class="quote-block"><div class="quote-text">&ldquo;${quote}&rdquo;</div></div>` : ""}
    ${p2 ? `<p class="narrative">${p2}</p>` : ""}
    ${nearby.length ? `<div class="nearby-label">Nearby</div><div class="nearby-list">${nearby.map(n => `<div class="nearby-item">&#9656; ${n.name}${n.cat ? ` <span>${n.cat}</span>` : ""}</div>`).join("")}</div>` : ""}
  </div>
  <div class="footer">
    <div></div>
    <div class="footer-brand">
      <span class="footer-label">SightAI App</span>
      <div class="footer-icon"><svg fill="none" stroke="#25aff4" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" viewBox="0 0 24 24"><path d="M17.8 19.2 16 11l3.5-3.5C21 6 21.5 4 21 3c-1-.5-3 0-4.5 1.5L13 8 4.8 6.2c-.5-.1-.9.1-1.1.5l-.3.5c-.2.5-.1 1 .3 1.3L9 12l-2 3H4l-1 1 3 2 2 3 1-1v-3l3-2 3.5 5.3c.3.4.8.5 1.3.3l.5-.2c.4-.3.6-.7.5-1.2z"/></svg></div>
    </div>
  </div>
</div>
</body>
</html>`);
  } catch (error) {
    console.error("Share page error:", error);
    res.status(500).send("Something went wrong");
  }
});

function escapeHtml(str) {
  return str
    .replace(/&/g, "&amp;")
    .replace(/"/g, "&quot;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;");
}

// ── Admin Dashboard ─────────────────────────────────────────────

// ── Admin: Plan Limits ───────────────────────────────────────────
app.get("/api/admin/plan-limits", adminAuth, async (req, res) => {
  try {
    const result = await pool.query("SELECT * FROM plan_limits ORDER BY plan");
    res.json(result.rows);
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// ── Admin: Subscription Audit ────────────────────────────────────
app.get("/api/admin/subscription-audit", adminAuth, async (req, res) => {
  try {
    const keyPath = path.join(__dirname, "google-play-key.json");
    const auth = new google.auth.GoogleAuth({
      keyFile: keyPath,
      scopes: ["https://www.googleapis.com/auth/androidpublisher"]
    });
    const androidPublisher = google.androidpublisher({ version: "v3", auth });
    const packageName = "online.mnaks.sightai";

    // Reverse PLAN_MAP: plan → productId
    const PLAN_TO_PRODUCT = {};
    for (const [productId, plan] of Object.entries(PLAN_MAP)) {
      PLAN_TO_PRODUCT[plan] = productId;
    }

    // All paid users with a purchase token
    const dbResult = await pool.query(`
      SELECT id, email, display_name, plan, purchase_token, subscription_expires_at
      FROM users
      WHERE plan != 'free' AND purchase_token IS NOT NULL
      ORDER BY id
    `);

    const rows = dbResult.rows;
    const results = [];

    for (const user of rows) {
      const productId = PLAN_TO_PRODUCT[user.plan];
      let googleStatus = null;
      let googleError = null;

      if (productId) {
        try {
          const r = await androidPublisher.purchases.subscriptions.get({
            packageName,
            subscriptionId: productId,
            token: user.purchase_token
          });
          const sub = r.data;
          const expiryMs = parseInt(sub.expiryTimeMillis || "0");
          const paymentState = sub.paymentState;
          googleStatus = {
            active: expiryMs > Date.now() && (paymentState === 1 || paymentState === 2),
            paymentState,
            expiryMs,
            expiryDate: new Date(expiryMs).toISOString(),
            autoRenewing: sub.autoRenewing
          };
        } catch (e) {
          googleError = e.message || "Google API error";
        }
      } else {
        googleError = "Unknown productId for plan: " + user.plan;
      }

      const dbExpiryMs = user.subscription_expires_at ? new Date(user.subscription_expires_at).getTime() : 0;
      const dbActive = dbExpiryMs > Date.now();
      const googleActive = googleStatus ? googleStatus.active : null;
      const match = googleActive === null ? null : (dbActive === googleActive);

      results.push({
        id: user.id,
        email: user.email || "",
        name: user.display_name || "",
        plan: user.plan,
        db_active: dbActive,
        db_expiry: user.subscription_expires_at,
        google_active: googleActive,
        google_payment_state: googleStatus ? googleStatus.paymentState : null,
        google_expiry: googleStatus ? googleStatus.expiryDate : null,
        google_auto_renew: googleStatus ? googleStatus.autoRenewing : null,
        match,
        error: googleError
      });
    }

    const total = results.length;
    const matched = results.filter(r => r.match === true).length;
    const mismatched = results.filter(r => r.match === false).length;
    const errors = results.filter(r => r.error !== null).length;

    res.json({ total, matched, mismatched, errors, rows: results });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

app.put("/api/admin/plan-limits/:plan", adminAuth, async (req, res) => {
  const { plan } = req.params;
  const { scans_per_day, max_journal, max_queue, max_pins, audio_enabled, share_enabled } = req.body;
  if (!["free", "plus", "pro"].includes(plan)) {
    return res.status(400).json({ error: "Invalid plan" });
  }
  try {
    await pool.query(`
      UPDATE plan_limits SET
        scans_per_day = $1, max_journal = $2, max_queue = $3,
        max_pins = $4, audio_enabled = $5, share_enabled = $6
      WHERE plan = $7
    `, [scans_per_day, max_journal, max_queue, max_pins, audio_enabled, share_enabled, plan]);
    await loadPlanLimits(); // refresh cache
    res.json({ success: true, limits: planLimitsCache[plan] });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

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

// Admin login (rate-limited + timing-safe comparison)
app.post("/api/admin/login", authLimiter, (req, res) => {
  const { password } = req.body;
  if (!password) {
    return res.status(400).json({ error: "Password required" });
  }
  const expected = process.env.ADMIN_PASSWORD || "";
  // Timing-safe comparison to prevent timing attacks
  const a = Buffer.from(password.padEnd(256, "\0"));
  const b = Buffer.from(expected.padEnd(256, "\0"));
  if (a.length !== b.length || !crypto.timingSafeEqual(a, b)) {
    console.warn(`[Security] Failed admin login attempt from IP`);
    return res.status(401).json({ error: "Invalid password" });
  }
  const token = crypto.randomUUID();
  adminTokens.add(token);
  // Token expires after 4 hours
  setTimeout(() => adminTokens.delete(token), 4 * 60 * 60 * 1000);
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
    const { search, auth_type, page = 1, limit: rawLimit = 50 } = req.query;
    const limit = Math.min(Math.max(1, parseInt(rawLimit) || 50), 100);
    const offset = (Math.max(1, parseInt(page) || 1) - 1) * limit;
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

    params.push(limit);
    params.push(offset);
    const result = await pool.query(
      `SELECT u.*, (SELECT COUNT(*) FROM landmarks l WHERE l.user_id = u.id) AS landmarks_count
       FROM users u ${whereClause}
       ORDER BY u.id DESC LIMIT $${idx} OFFSET $${idx + 1}`,
      params
    );

    res.json({ users: result.rows, total, page: Math.max(1, parseInt(page) || 1), limit });
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
    const { search, user_id, language, saved, page = 1, limit: rawLimit = 50 } = req.query;
    const limit = Math.min(Math.max(1, parseInt(rawLimit) || 50), 100);
    const offset = (Math.max(1, parseInt(page) || 1) - 1) * limit;
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

    params.push(limit);
    params.push(offset);
    const result = await pool.query(
      `SELECT l.*, u.display_name AS user_name
       FROM landmarks l LEFT JOIN users u ON l.user_id = u.id
       ${whereClause}
       ORDER BY l.id DESC LIMIT $${idx} OFFSET $${idx + 1}`,
      params
    );

    // Attach image file size for each landmark
    const landmarks = result.rows.map((row) => {
      if (row.image_filename) {
        try {
          const imgPath = path.join(uploadDir, row.image_filename);
          if (fs.existsSync(imgPath)) {
            row.image_size = fs.statSync(imgPath).size;
          } else {
            row.image_size = null;
          }
        } catch (e) {
          row.image_size = null;
        }
      } else {
        row.image_size = null;
      }
      return row;
    });

    res.json({ landmarks, total, page: Math.max(1, parseInt(page) || 1), limit });
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
    const landmark = result.rows[0];
    if (landmark.image_filename) {
      try {
        const imgPath = path.join(uploadDir, landmark.image_filename);
        if (fs.existsSync(imgPath)) {
          landmark.image_size = fs.statSync(imgPath).size;
        }
      } catch (e) { /* ignore */ }
    }
    res.json(landmark);
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
// ── Subscription expiry downgrade job ─────────────────────────────
async function downgradeExpiredSubscriptions() {
  try {
    const result = await pool.query(
      "UPDATE users SET plan = 'free', subscription_expires_at = NULL, purchase_token = NULL WHERE plan != 'free' AND subscription_expires_at IS NOT NULL AND subscription_expires_at < NOW() RETURNING id, email, plan"
    );
    if (result.rowCount > 0) {
      console.log(`[Subscription] Downgraded ${result.rowCount} expired subscriptions`);
    }
  } catch (e) {
    console.error("[Subscription] Expiry check error:", e.message);
  }
}

// ── Downgrade endpoint (called by app when no active purchases found) ─
app.post("/api/subscription/downgrade", async (req, res) => {
  const { userId, deviceId } = req.body;
  try {
    if (userId && parseInt(userId) > 0) {
      await pool.query(
        "UPDATE users SET plan = 'free', subscription_expires_at = NULL, purchase_token = NULL WHERE id = $1 AND subscription_expires_at IS NOT NULL AND subscription_expires_at < NOW()",
        [userId]
      );
    } else if (deviceId) {
      await pool.query(
        "UPDATE users SET plan = 'free', subscription_expires_at = NULL, purchase_token = NULL WHERE device_id = $1 AND subscription_expires_at IS NOT NULL AND subscription_expires_at < NOW()",
        [deviceId]
      );
    }
    res.json({ success: true });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

initDb()
  .then(async () => {
    await loadPlanLimits();
    // Run expiry check on startup and every 24 hours
    downgradeExpiredSubscriptions();
    setInterval(downgradeExpiredSubscriptions, 24 * 60 * 60 * 1000);
    app.listen(PORT, () => {
      console.log("SightAI API server running on port " + PORT);
    });
  })
  .catch((err) => {
    console.error("Failed to initialize database:", err);
    process.exit(1);
  });
