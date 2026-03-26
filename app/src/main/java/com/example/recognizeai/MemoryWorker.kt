package com.example.recognizeai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.Locale
import java.util.concurrent.TimeUnit

class MemoryWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    override fun doWork(): Result {
        val session = SessionManager(applicationContext)
        val userId = session.userId
        val deviceId = session.deviceId

        val url = if (userId > 0)
            "${SessionManager.BASE_URL}/api/landmarks/memories?user_id=$userId&device_id=$deviceId"
        else
            "${SessionManager.BASE_URL}/api/landmarks/memories?device_id=$deviceId"

        return try {
            val response = client.newCall(Request.Builder().url(url).get().build()).execute()
            val body = response.body?.string() ?: return Result.success()
            val arr = JSONArray(body)
            if (arr.length() == 0) return Result.success()

            // Pick the first memory
            val lm = arr.getJSONObject(0)
            val name = lm.optString("name", "")
            val location = lm.optString("location", "")
            val imageUrl = lm.optString("image_url", "").let {
                if (it.startsWith("http")) it else "${SessionManager.BASE_URL}$it"
            }
            val serverId = lm.optLong("id", -1L)

            if (name.isEmpty()) return Result.success()

            // Download thumbnail for big picture style notification
            val bitmap = try {
                if (imageUrl.isNotEmpty()) {
                    val imgResp = client.newCall(Request.Builder().url(imageUrl).get().build()).execute()
                    imgResp.body?.byteStream()?.use { BitmapFactory.decodeStream(it) }
                } else null
            } catch (_: Exception) { null }

            showNotification(session, name, location, bitmap, serverId, lm)
            Result.success()
        } catch (e: Exception) {
            Result.success() // Don't retry — next day will try again
        }
    }

    private fun showNotification(
        session: SessionManager,
        name: String,
        location: String,
        bitmap: Bitmap?,
        serverId: Long,
        lm: org.json.JSONObject
    ) {
        val channelId = "memory_notifications"
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(
                channelId, "Memory Notifications", NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Yearly memories of your scanned landmarks" })
        }

        // Localized context
        val langCode = session.language
        val config = applicationContext.resources.configuration
        config.setLocale(Locale(langCode))
        val ctx = applicationContext.createConfigurationContext(config)

        val tapIntent = Intent(applicationContext, LandmarkDetailActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("server_id", serverId)
            putExtra("name", name)
            putExtra("location", location)
            putExtra("photo_uri", lm.optString("image_url", "").let {
                if (it.startsWith("http")) it else "${SessionManager.BASE_URL}$it"
            })
            putExtra("year_built", lm.optString("year_built", ""))
            putExtra("status", lm.optString("status", ""))
            putExtra("architect", lm.optString("architect", ""))
            putExtra("capacity", lm.optString("capacity", ""))
            putExtra("narrative_p1", lm.optString("narrative_p1", ""))
            putExtra("narrative_quote", lm.optString("narrative_quote", ""))
            putExtra("narrative_p2", lm.optString("narrative_p2", ""))
            putExtra("from_saved", true)
        }

        val pendingIntent = PendingIntent.getActivity(
            applicationContext, serverId.toInt(), tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(R.drawable.ic_location_on)
            .setContentTitle("📸 ${ctx.getString(R.string.memory_title)}")
            .setContentText(ctx.getString(R.string.memory_body, name, location))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        if (bitmap != null) {
            builder.setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(bitmap)
                    .setSummaryText(location)
            )
        }

        nm.notify(NOTIFICATION_ID, builder.build())
    }

    companion object {
        private const val NOTIFICATION_ID = 7001
        private const val WORK_NAME = "memory_worker"

        fun schedule(context: Context) {
            val now = java.util.Calendar.getInstance()
            val target = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 9)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                if (before(now)) add(java.util.Calendar.DAY_OF_YEAR, 1)
            }
            val delayMs = target.timeInMillis - now.timeInMillis

            val request = OneTimeWorkRequestBuilder<MemoryWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .build()

            // Chain: after firing, reschedule for next day via periodic
            val periodic = PeriodicWorkRequestBuilder<MemoryWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, periodic)
        }
    }
}
