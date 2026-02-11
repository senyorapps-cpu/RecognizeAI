package com.example.recognizeai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class PendingAnalysisManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("recognizeai_landmarks", Context.MODE_PRIVATE)

    private val client = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    fun addToPendingQueue(photoUri: String, lat: Double?, lng: Double?, createdAt: String) {
        // 1. Add to pending_analyses queue
        val pendingArr = JSONArray(prefs.getString("pending_analyses", "[]"))
        val pendingEntry = JSONObject().apply {
            put("photo_uri", photoUri)
            lat?.let { put("latitude", it) }
            lng?.let { put("longitude", it) }
            put("created_at", createdAt)
        }
        pendingArr.put(pendingEntry)

        // 2. Add placeholder landmark to landmarks array so it shows in SavedPhotosFragment
        val landmarksArr = JSONArray(prefs.getString("landmarks", "[]"))
        val placeholder = JSONObject().apply {
            put("server_id", -1L)
            put("photo_uri", photoUri)
            put("name", "Pending Analysis")
            put("location", "")
            put("year_built", "")
            put("status", "pending")
            put("architect", "")
            put("capacity", "")
            put("narrative_p1", "")
            put("narrative_quote", "")
            put("narrative_p2", "")
            put("nearby1_name", "")
            put("nearby1_category", "")
            put("nearby2_name", "")
            put("nearby2_category", "")
            put("nearby3_name", "")
            put("nearby3_category", "")
            put("is_saved", true)
            put("rating", 0)
            put("created_at", createdAt)
            lat?.let { put("latitude", it) }
            lng?.let { put("longitude", it) }
        }
        landmarksArr.put(placeholder)

        prefs.edit()
            .putString("pending_analyses", pendingArr.toString())
            .putString("landmarks", landmarksArr.toString())
            .apply()

        Log.d("PendingManager", "Added to pending queue. Total pending: ${pendingArr.length()}")
    }

    fun getPendingCount(): Int {
        val arr = JSONArray(prefs.getString("pending_analyses", "[]"))
        return arr.length()
    }

    fun hasPendingItems(): Boolean = getPendingCount() > 0

    suspend fun syncAll(
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
        onComplete: (successCount: Int, failCount: Int) -> Unit = { _, _ -> }
    ) {
        withContext(Dispatchers.IO) {
            val pendingArr = JSONArray(prefs.getString("pending_analyses", "[]"))
            val total = pendingArr.length()
            if (total == 0) {
                withContext(Dispatchers.Main) { onComplete(0, 0) }
                return@withContext
            }

            val session = SessionManager(context)
            var successCount = 0
            var failCount = 0
            val remainingPending = JSONArray()

            for (i in 0 until total) {
                val entry = pendingArr.getJSONObject(i)
                val photoUriStr = entry.optString("photo_uri", "")
                val lat = if (entry.has("latitude")) entry.optDouble("latitude") else null
                val lng = if (entry.has("longitude")) entry.optDouble("longitude") else null

                withContext(Dispatchers.Main) { onProgress(i + 1, total) }

                try {
                    val photoUri = Uri.parse(photoUriStr)
                    val compressed = compressImage(photoUri)
                    val imageBytes: ByteArray

                    if (compressed != null) {
                        imageBytes = compressed.first
                    } else {
                        val raw = context.contentResolver.openInputStream(photoUri)?.use { it.readBytes() }
                        if (raw == null) {
                            failCount++
                            remainingPending.put(entry)
                            continue
                        }
                        imageBytes = raw
                    }

                    val builder = MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart(
                            "image", "photo.jpg",
                            imageBytes.toRequestBody("image/jpeg".toMediaType())
                        )
                        .addFormDataPart("user_id", session.userId.toString())
                        .addFormDataPart("device_id", session.deviceId)

                    lat?.let { builder.addFormDataPart("latitude", it.toString()) }
                    lng?.let { builder.addFormDataPart("longitude", it.toString()) }

                    val request = Request.Builder()
                        .url("${SessionManager.BASE_URL}/api/analyze")
                        .post(builder.build())
                        .build()

                    val response = client.newCall(request).execute()
                    val responseBody = response.body?.string() ?: ""

                    if (!response.isSuccessful) {
                        throw Exception("Server error: ${response.code}")
                    }

                    val json = JSONObject(responseBody)

                    // Update the placeholder landmark in local storage with real data
                    updatePlaceholder(photoUriStr, json, compressed?.second?.toString())

                    successCount++
                    Log.d("PendingManager", "Synced: ${json.optString("name")}")

                } catch (e: Exception) {
                    Log.e("PendingManager", "Failed to sync item $i", e)
                    failCount++
                    remainingPending.put(entry)
                }
            }

            // Save remaining failed items back to queue
            prefs.edit()
                .putString("pending_analyses", remainingPending.toString())
                .apply()

            withContext(Dispatchers.Main) { onComplete(successCount, failCount) }
        }
    }

    private fun updatePlaceholder(originalPhotoUri: String, json: JSONObject, compressedUri: String?) {
        val landmarksArr = JSONArray(prefs.getString("landmarks", "[]"))
        val updatedArr = JSONArray()

        for (i in 0 until landmarksArr.length()) {
            val obj = landmarksArr.getJSONObject(i)
            if (obj.optString("photo_uri") == originalPhotoUri && obj.optString("status") == "pending") {
                // Replace placeholder with real data
                val updated = JSONObject().apply {
                    put("server_id", json.optLong("id", -1L))
                    put("photo_uri", compressedUri ?: originalPhotoUri)
                    put("name", json.optString("name", "Unknown"))
                    put("location", json.optString("location", ""))
                    put("year_built", json.optString("year_built", ""))
                    put("status", json.optString("status", ""))
                    put("architect", json.optString("architect", ""))
                    put("capacity", json.optString("capacity", ""))
                    put("narrative_p1", json.optString("narrative_p1", ""))
                    put("narrative_quote", json.optString("narrative_quote", ""))
                    put("narrative_p2", json.optString("narrative_p2", ""))
                    put("nearby1_name", json.optString("nearby1_name", ""))
                    put("nearby1_category", json.optString("nearby1_category", ""))
                    put("nearby2_name", json.optString("nearby2_name", ""))
                    put("nearby2_category", json.optString("nearby2_category", ""))
                    put("nearby3_name", json.optString("nearby3_name", ""))
                    put("nearby3_category", json.optString("nearby3_category", ""))
                    put("is_saved", true)
                    put("rating", obj.optInt("rating", 0))
                    put("created_at", obj.optString("created_at", ""))
                    if (obj.has("latitude")) put("latitude", obj.optDouble("latitude"))
                    if (obj.has("longitude")) put("longitude", obj.optDouble("longitude"))
                }
                updatedArr.put(updated)
            } else {
                updatedArr.put(obj)
            }
        }

        prefs.edit().putString("landmarks", updatedArr.toString()).apply()
    }

    private fun compressImage(photoUri: Uri): Pair<ByteArray, Uri>? {
        val inputStream = context.contentResolver.openInputStream(photoUri) ?: return null
        val original = BitmapFactory.decodeStream(inputStream)
        inputStream.close()
        if (original == null) return null

        val maxEdge = 1200
        val scale = if (original.width > original.height) {
            maxEdge.toFloat() / original.width
        } else {
            maxEdge.toFloat() / original.height
        }

        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                original,
                (original.width * scale).toInt(),
                (original.height * scale).toInt(),
                true
            )
        } else {
            original
        }

        val dir = File(context.cacheDir, "compressed_images")
        dir.mkdirs()
        val file = File(dir, "compressed_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 65, out)
        }
        if (bitmap !== original) bitmap.recycle()
        original.recycle()

        val compressedUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Pair(file.readBytes(), compressedUri)
    }
}
