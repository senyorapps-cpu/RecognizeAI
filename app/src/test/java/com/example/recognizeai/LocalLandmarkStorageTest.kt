package com.example.recognizeai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests verifying that landmark data is correctly serialized/deserialized
 * to/from the local JSON storage format used by SharedPreferences("recognizeai_landmarks").
 *
 * Tests the full flow:
 *   1. AnalyzingActivity saves a landmark entry after server response
 *   2. LandmarkDetailActivity.saveToJournal() sets is_saved = true
 *   3. LandmarkDetailActivity.persistRating() updates the rating
 *   4. SavedPhotosFragment.loadFromLocalStorage() reads only is_saved == true items
 */
class LocalLandmarkStorageTest {

    private lateinit var storage: MutableMap<String, String>

    @Before
    fun setUp() {
        // Simulate SharedPreferences as an in-memory map
        storage = mutableMapOf()
    }

    // ─── helpers mirroring the real app code ───

    /** Simulates what AnalyzingActivity does after server response */
    private fun saveFromAnalyzing(serverJson: JSONObject, photoUri: String) {
        val existing = JSONArray(storage.getOrDefault("landmarks", "[]"))
        val entry = JSONObject().apply {
            put("server_id", serverJson.optLong("id", -1L))
            put("photo_uri", photoUri)
            put("name", serverJson.optString("name", "Unknown"))
            put("location", serverJson.optString("location", ""))
            put("year_built", serverJson.optString("year_built", ""))
            put("status", serverJson.optString("status", ""))
            put("architect", serverJson.optString("architect", ""))
            put("capacity", serverJson.optString("capacity", ""))
            put("narrative_p1", serverJson.optString("narrative_p1", ""))
            put("narrative_quote", serverJson.optString("narrative_quote", ""))
            put("narrative_p2", serverJson.optString("narrative_p2", ""))
            put("nearby1_name", serverJson.optString("nearby1_name", ""))
            put("nearby1_category", serverJson.optString("nearby1_category", ""))
            put("nearby2_name", serverJson.optString("nearby2_name", ""))
            put("nearby2_category", serverJson.optString("nearby2_category", ""))
            put("nearby3_name", serverJson.optString("nearby3_name", ""))
            put("nearby3_category", serverJson.optString("nearby3_category", ""))
            put("is_saved", false)
            put("rating", 0)
            put("created_at", "2026-02-07T12:00:00Z")
        }
        existing.put(entry)
        storage["landmarks"] = existing.toString()
    }

    /** Simulates updateLocalEntry from LandmarkDetailActivity */
    private fun updateLocalEntry(serverId: Long, photoUri: String, mutate: (JSONObject) -> Unit) {
        val arr = JSONArray(storage.getOrDefault("landmarks", "[]"))
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val matchById = serverId > 0 && obj.optLong("server_id", -1L) == serverId
            val matchByUri = photoUri.isNotEmpty() && obj.optString("photo_uri") == photoUri
            if (matchById || matchByUri) {
                mutate(obj)
                break
            }
        }
        storage["landmarks"] = arr.toString()
    }

    /** Simulates loadFromLocalStorage in SavedPhotosFragment — returns only is_saved == true */
    private fun loadSavedFromLocal(): List<JSONObject> {
        val arr = JSONArray(storage.getOrDefault("landmarks", "[]"))
        val result = mutableListOf<JSONObject>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (obj.optBoolean("is_saved", false)) {
                result.add(obj)
            }
        }
        return result
    }

    private fun loadAllFromLocal(): List<JSONObject> {
        val arr = JSONArray(storage.getOrDefault("landmarks", "[]"))
        val result = mutableListOf<JSONObject>()
        for (i in 0 until arr.length()) {
            result.add(arr.getJSONObject(i))
        }
        return result
    }

    // ─── build a fake server response ───

    private fun buildServerResponse(
        id: Long = 42,
        name: String = "Eiffel Tower",
        location: String = "Paris, France",
        yearBuilt: String = "1889",
        status: String = "Landmark",
        architect: String = "Gustave Eiffel",
        capacity: String = "N/A",
        narrativeP1: String = "The tower was built for the 1889 World's Fair.",
        narrativeQuote: String = "A symbol of French ingenuity.",
        narrativeP2: String = "It stands 330 meters tall.",
        nearby1Name: String = "Champ de Mars",
        nearby1Category: String = "Park",
        nearby2Name: String = "Trocadero",
        nearby2Category: String = "Viewpoint",
        nearby3Name: String = "Seine River",
        nearby3Category: String = "Waterway"
    ): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("location", location)
        put("year_built", yearBuilt)
        put("status", status)
        put("architect", architect)
        put("capacity", capacity)
        put("narrative_p1", narrativeP1)
        put("narrative_quote", narrativeQuote)
        put("narrative_p2", narrativeP2)
        put("nearby1_name", nearby1Name)
        put("nearby1_category", nearby1Category)
        put("nearby2_name", nearby2Name)
        put("nearby2_category", nearby2Category)
        put("nearby3_name", nearby3Name)
        put("nearby3_category", nearby3Category)
    }

    // ═══════════════════════════════════════════════
    //  TEST 1: AnalyzingActivity saves all fields
    // ═══════════════════════════════════════════════

    @Test
    fun analyzingActivity_savesAllFieldsToLocal() {
        val serverJson = buildServerResponse()
        val photoUri = "content://media/external/images/123"

        saveFromAnalyzing(serverJson, photoUri)

        val all = loadAllFromLocal()
        assertEquals("Should have exactly 1 entry", 1, all.size)

        val entry = all[0]
        assertEquals(42L, entry.getLong("server_id"))
        assertEquals(photoUri, entry.getString("photo_uri"))
        assertEquals("Eiffel Tower", entry.getString("name"))
        assertEquals("Paris, France", entry.getString("location"))
        assertEquals("1889", entry.getString("year_built"))
        assertEquals("Landmark", entry.getString("status"))
        assertEquals("Gustave Eiffel", entry.getString("architect"))
        assertEquals("N/A", entry.getString("capacity"))
        assertEquals("The tower was built for the 1889 World's Fair.", entry.getString("narrative_p1"))
        assertEquals("A symbol of French ingenuity.", entry.getString("narrative_quote"))
        assertEquals("It stands 330 meters tall.", entry.getString("narrative_p2"))
        assertEquals("Champ de Mars", entry.getString("nearby1_name"))
        assertEquals("Park", entry.getString("nearby1_category"))
        assertEquals("Trocadero", entry.getString("nearby2_name"))
        assertEquals("Viewpoint", entry.getString("nearby2_category"))
        assertEquals("Seine River", entry.getString("nearby3_name"))
        assertEquals("Waterway", entry.getString("nearby3_category"))
        assertFalse("is_saved should be false initially", entry.getBoolean("is_saved"))
        assertEquals(0, entry.getInt("rating"))
        assertEquals("2026-02-07T12:00:00Z", entry.getString("created_at"))
    }

    // ═══════════════════════════════════════════════
    //  TEST 2: New entry starts with is_saved=false
    // ═══════════════════════════════════════════════

    @Test
    fun newEntry_isSavedIsFalse_notVisibleInSavedList() {
        saveFromAnalyzing(buildServerResponse(), "content://photo/1")

        val saved = loadSavedFromLocal()
        assertEquals("Unsaved entry should NOT appear in saved list", 0, saved.size)
    }

    // ═══════════════════════════════════════════════
    //  TEST 3: saveToJournal sets is_saved = true
    // ═══════════════════════════════════════════════

    @Test
    fun saveToJournal_setsIsSavedTrue() {
        saveFromAnalyzing(buildServerResponse(id = 42), "content://photo/1")

        // Simulate saveToJournal()
        updateLocalEntry(42, "content://photo/1") { it.put("is_saved", true) }

        val all = loadAllFromLocal()
        assertEquals(1, all.size)
        assertTrue("is_saved should be true after saveToJournal", all[0].getBoolean("is_saved"))
    }

    // ═══════════════════════════════════════════════
    //  TEST 4: Saved entry appears in loadSavedFromLocal
    // ═══════════════════════════════════════════════

    @Test
    fun savedEntry_appearsInSavedList() {
        saveFromAnalyzing(buildServerResponse(id = 42), "content://photo/1")
        updateLocalEntry(42, "content://photo/1") { it.put("is_saved", true) }

        val saved = loadSavedFromLocal()
        assertEquals("Saved entry should appear", 1, saved.size)
        assertEquals("Eiffel Tower", saved[0].getString("name"))
    }

    // ═══════════════════════════════════════════════
    //  TEST 5: persistRating updates rating value
    // ═══════════════════════════════════════════════

    @Test
    fun persistRating_updatesRatingLocally() {
        saveFromAnalyzing(buildServerResponse(id = 42), "content://photo/1")

        // Simulate persistRating() with rating = 4
        updateLocalEntry(42, "content://photo/1") { it.put("rating", 4) }

        val all = loadAllFromLocal()
        assertEquals(4, all[0].getInt("rating"))
    }

    // ═══════════════════════════════════════════════
    //  TEST 6: Rating persists through save flow
    // ═══════════════════════════════════════════════

    @Test
    fun fullFlow_ratingAndSavePersistTogether() {
        saveFromAnalyzing(buildServerResponse(id = 42), "content://photo/1")

        // Rate first
        updateLocalEntry(42, "content://photo/1") { it.put("rating", 5) }
        // Then save to journal
        updateLocalEntry(42, "content://photo/1") { it.put("is_saved", true) }

        val saved = loadSavedFromLocal()
        assertEquals(1, saved.size)
        assertEquals(5, saved[0].getInt("rating"))
        assertTrue(saved[0].getBoolean("is_saved"))
    }

    // ═══════════════════════════════════════════════
    //  TEST 7: Multiple landmarks — only saved ones appear
    // ═══════════════════════════════════════════════

    @Test
    fun multipleLandmarks_onlySavedOnesAppear() {
        saveFromAnalyzing(buildServerResponse(id = 1, name = "Eiffel Tower", location = "Paris, France"), "content://photo/1")
        saveFromAnalyzing(buildServerResponse(id = 2, name = "Colosseum", location = "Rome, Italy"), "content://photo/2")
        saveFromAnalyzing(buildServerResponse(id = 3, name = "Big Ben", location = "London, UK"), "content://photo/3")

        // Only save Eiffel Tower and Big Ben
        updateLocalEntry(1, "content://photo/1") { it.put("is_saved", true) }
        updateLocalEntry(3, "content://photo/3") { it.put("is_saved", true) }

        val all = loadAllFromLocal()
        assertEquals("All 3 should be stored", 3, all.size)

        val saved = loadSavedFromLocal()
        assertEquals("Only 2 should be saved", 2, saved.size)

        val savedNames = saved.map { it.getString("name") }.toSet()
        assertTrue("Eiffel Tower should be saved", savedNames.contains("Eiffel Tower"))
        assertTrue("Big Ben should be saved", savedNames.contains("Big Ben"))
        assertFalse("Colosseum should NOT be saved", savedNames.contains("Colosseum"))
    }

    // ═══════════════════════════════════════════════
    //  TEST 8: Match by photo_uri when server_id <= 0 (guest)
    // ═══════════════════════════════════════════════

    @Test
    fun guestUser_matchesByPhotoUri() {
        // Guest user gets server_id = -1
        saveFromAnalyzing(buildServerResponse(id = -1, name = "Statue of Liberty"), "content://photo/guest1")

        // Update by photo_uri since server_id is -1
        updateLocalEntry(-1, "content://photo/guest1") { it.put("is_saved", true) }
        updateLocalEntry(-1, "content://photo/guest1") { it.put("rating", 3) }

        val saved = loadSavedFromLocal()
        assertEquals(1, saved.size)
        assertEquals("Statue of Liberty", saved[0].getString("name"))
        assertTrue(saved[0].getBoolean("is_saved"))
        assertEquals(3, saved[0].getInt("rating"))
    }

    // ═══════════════════════════════════════════════
    //  TEST 9: Empty storage returns empty list
    // ═══════════════════════════════════════════════

    @Test
    fun emptyStorage_returnsEmptyList() {
        val saved = loadSavedFromLocal()
        assertEquals(0, saved.size)

        val all = loadAllFromLocal()
        assertEquals(0, all.size)
    }

    // ═══════════════════════════════════════════════
    //  TEST 10: Rating can be cleared (set to 0)
    // ═══════════════════════════════════════════════

    @Test
    fun ratingCanBeCleared() {
        saveFromAnalyzing(buildServerResponse(id = 10), "content://photo/1")
        updateLocalEntry(10, "content://photo/1") { it.put("rating", 5) }

        // Clear rating
        updateLocalEntry(10, "content://photo/1") { it.put("rating", 0) }

        val all = loadAllFromLocal()
        assertEquals(0, all[0].getInt("rating"))
    }

    // ═══════════════════════════════════════════════
    //  TEST 11: All narrative fields preserved
    // ═══════════════════════════════════════════════

    @Test
    fun narrativeFields_fullyPreserved() {
        val serverJson = buildServerResponse(
            narrativeP1 = "First paragraph with special chars: é, ñ, ü",
            narrativeQuote = "A memorable quote about history",
            narrativeP2 = "Second paragraph continues the story."
        )
        saveFromAnalyzing(serverJson, "content://photo/1")

        val entry = loadAllFromLocal()[0]
        assertEquals("First paragraph with special chars: é, ñ, ü", entry.getString("narrative_p1"))
        assertEquals("A memorable quote about history", entry.getString("narrative_quote"))
        assertEquals("Second paragraph continues the story.", entry.getString("narrative_p2"))
    }

    // ═══════════════════════════════════════════════
    //  TEST 12: All 3 nearby places preserved
    // ═══════════════════════════════════════════════

    @Test
    fun nearbyPlaces_allThreePreserved() {
        val serverJson = buildServerResponse(
            nearby1Name = "Place A", nearby1Category = "Museum",
            nearby2Name = "Place B", nearby2Category = "Restaurant",
            nearby3Name = "Place C", nearby3Category = "Park"
        )
        saveFromAnalyzing(serverJson, "content://photo/1")

        val entry = loadAllFromLocal()[0]
        assertEquals("Place A", entry.getString("nearby1_name"))
        assertEquals("Museum", entry.getString("nearby1_category"))
        assertEquals("Place B", entry.getString("nearby2_name"))
        assertEquals("Restaurant", entry.getString("nearby2_category"))
        assertEquals("Place C", entry.getString("nearby3_name"))
        assertEquals("Park", entry.getString("nearby3_category"))
    }

    // ═══════════════════════════════════════════════
    //  TEST 13: Saved item fields match for detail view
    // ═══════════════════════════════════════════════

    @Test
    fun savedItem_hasAllFieldsForDetailView() {
        saveFromAnalyzing(buildServerResponse(id = 42), "content://photo/1")
        updateLocalEntry(42, "content://photo/1") { it.put("is_saved", true) }
        updateLocalEntry(42, "content://photo/1") { it.put("rating", 4) }

        val saved = loadSavedFromLocal()[0]

        // Verify all fields needed by LandmarkDetailActivity are present
        assertNotNull(saved.opt("server_id"))
        assertNotNull(saved.opt("photo_uri"))
        assertNotNull(saved.opt("name"))
        assertNotNull(saved.opt("location"))
        assertNotNull(saved.opt("year_built"))
        assertNotNull(saved.opt("status"))
        assertNotNull(saved.opt("architect"))
        assertNotNull(saved.opt("capacity"))
        assertNotNull(saved.opt("narrative_p1"))
        assertNotNull(saved.opt("narrative_quote"))
        assertNotNull(saved.opt("narrative_p2"))
        assertNotNull(saved.opt("nearby1_name"))
        assertNotNull(saved.opt("nearby1_category"))
        assertNotNull(saved.opt("nearby2_name"))
        assertNotNull(saved.opt("nearby2_category"))
        assertNotNull(saved.opt("nearby3_name"))
        assertNotNull(saved.opt("nearby3_category"))
        assertNotNull(saved.opt("rating"))
        assertNotNull(saved.opt("created_at"))

        assertEquals(4, saved.getInt("rating"))
        assertTrue(saved.getBoolean("is_saved"))
    }

    // ═══════════════════════════════════════════════
    //  TEST 14: JSON roundtrip — serialize then deserialize
    // ═══════════════════════════════════════════════

    @Test
    fun jsonRoundtrip_dataIntact() {
        val serverJson = buildServerResponse(
            id = 99,
            name = "Taj Mahal",
            location = "Agra, India",
            yearBuilt = "1653",
            architect = "Ustad Ahmad Lahauri"
        )
        saveFromAnalyzing(serverJson, "content://photo/taj")

        // Simulate reading back from SharedPreferences (string → JSONArray → JSONObject)
        val rawJson = storage["landmarks"]!!
        val parsed = JSONArray(rawJson)
        val entry = parsed.getJSONObject(0)

        assertEquals(99L, entry.getLong("server_id"))
        assertEquals("Taj Mahal", entry.getString("name"))
        assertEquals("Agra, India", entry.getString("location"))
        assertEquals("1653", entry.getString("year_built"))
        assertEquals("Ustad Ahmad Lahauri", entry.getString("architect"))
    }
}
