require("dotenv").config({ path: require("path").join(__dirname, ".env") });
const express = require("express");
const multer = require("multer");
const cors = require("cors");
const { GoogleGenerativeAI } = require("@google/generative-ai");
const fs = require("fs");
const path = require("path");
const { Pool } = require("pg");

const app = express();
const PORT = 3001;

app.use(cors());
app.use(express.json());

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

app.get("/api/health", (req, res) => {
  res.json({ status: "ok", service: "tripai-api", db: "postgresql" });
});

// ── Auth endpoints ──────────────────────────────────────────────

// Guest login — creates or finds guest user by device_id
app.post("/api/auth/guest", async (req, res) => {
  try {
    const { device_id } = req.body;
    if (!device_id) {
      return res.status(400).json({ error: "device_id is required" });
    }

    const existing = await pool.query(
      "SELECT * FROM users WHERE auth_type = 'guest' AND device_id = $1",
      [device_id]
    );

    if (existing.rows.length > 0) {
      await pool.query(
        "UPDATE users SET last_login_at = CURRENT_TIMESTAMP WHERE id = $1",
        [existing.rows[0].id]
      );
      return res.json(existing.rows[0]);
    }

    const result = await pool.query(
      "INSERT INTO users (auth_type, display_name, device_id) VALUES ('guest', 'Guest', $1) RETURNING *",
      [device_id]
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
    const { google_id, email, display_name, photo_url, device_id } = req.body;
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
        `UPDATE users SET email = $1, display_name = $2, photo_url = $3, device_id = $4, last_login_at = CURRENT_TIMESTAMP WHERE id = $5 RETURNING *`,
        [
          email || user.email,
          display_name || user.display_name,
          photo_url || user.photo_url,
          device_id || user.device_id,
          user.id,
        ]
      );
      return res.json(updated.rows[0]);
    }

    const result = await pool.query(
      "INSERT INTO users (auth_type, google_id, email, display_name, photo_url, device_id) VALUES ('google', $1, $2, $3, $4, $5) RETURNING *",
      [google_id, email || "", display_name || "User", photo_url || "", device_id || ""]
    );
    res.json(result.rows[0]);
  } catch (error) {
    console.error("Google auth error:", error);
    res.status(500).json({ error: "Authentication failed", message: error.message });
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

// ── Landmark endpoints ──────────────────────────────────────────

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

    const model = genAI.getGenerativeModel({
      model: "gemini-2.5-flash",
      generationConfig: {
        thinkingConfig: { thinkingBudget: 0 }
      }
    });

    const prompt = `Analyze this image and identify the landmark, building, or place of interest shown.
Respond ONLY with a JSON object in this exact format, no markdown, no code fences:
{
  "name": "Name of the landmark",
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
}
If you cannot identify a specific landmark, make your best guess based on the architectural style and features visible.`;

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
    const userId = req.body?.user_id || null;
    const deviceId = req.body?.device_id || null;
    const latitude = req.body?.latitude ? parseFloat(req.body.latitude) : null;
    const longitude = req.body?.longitude ? parseFloat(req.body.longitude) : null;
    console.log("Received fields - user_id:", userId, "device_id:", deviceId, "latitude:", req.body?.latitude, "longitude:", req.body?.longitude, "parsed lat:", latitude, "parsed lng:", longitude);

    const insertResult = await pool.query(
      `INSERT INTO landmarks (
        user_id, device_id, name, location, year_built, status, architect, capacity,
        narrative_p1, narrative_quote, narrative_p2,
        nearby1_name, nearby1_category, nearby2_name, nearby2_category,
        nearby3_name, nearby3_category, image_filename, latitude, longitude
      ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16, $17, $18, $19, $20) RETURNING id`,
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
      ]
    );

    res.json({ id: insertResult.rows[0].id, latitude, longitude, ...parsed });
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

// Get all landmarks for a user (saved to journal)
app.get("/api/user/:id/landmarks", async (req, res) => {
  try {
    const savedOnly = req.query.saved === "true";
    let sql = "SELECT * FROM landmarks WHERE user_id = $1";
    if (savedOnly) sql += " AND is_saved = 1";
    sql += " ORDER BY id DESC";

    const result = await pool.query(sql, [req.params.id]);
    const rows = result.rows.map((row) => {
      row.image_url = `/api/uploads/${row.image_filename}`;
      return row;
    });
    res.json(rows);
  } catch (error) {
    res.status(500).json({ error: "Failed to fetch landmarks", message: error.message });
  }
});

// Nearby landmarks — Haversine distance filter
app.get("/api/landmarks/nearby", async (req, res) => {
  try {
    const lat = parseFloat(req.query.lat);
    const lng = parseFloat(req.query.lng);
    const radius = parseFloat(req.query.radius) || 50; // km

    if (isNaN(lat) || isNaN(lng)) {
      return res.status(400).json({ error: "lat and lng are required" });
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
        WHERE latitude IS NOT NULL AND longitude IS NOT NULL
      ) sub
      WHERE distance_km <= $3
      ORDER BY distance_km ASC
      LIMIT 100`,
      [lat, lng, radius]
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
