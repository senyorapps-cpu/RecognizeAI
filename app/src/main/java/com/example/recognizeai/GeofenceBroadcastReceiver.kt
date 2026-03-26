package com.example.recognizeai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.os.ConfigurationCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import java.util.Locale

class GeofenceBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return
        if (event.geofenceTransition != Geofence.GEOFENCE_TRANSITION_ENTER) return

        val triggered = event.triggeringGeofences ?: return
        for (geofence in triggered) {
            // requestId format: "{id}|{name}"
            val name = geofence.requestId.substringAfter("|", geofence.requestId)
            showNotification(context, name, geofence.requestId.substringBefore("|").toLongOrNull() ?: 0L)
        }
    }

    private fun showNotification(context: Context, landmarkName: String, landmarkId: Long) {
        val channelId = "nearby_landmarks"
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Nearby Landmarks",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Alerts when you approach a saved landmark"
            }
            nm.createNotificationChannel(channel)
        }

        // Try to build a deep-link intent to the landmark detail screen
        val lm = GeofenceManager.getLandmarkData(context, landmarkId)
        val tapIntent = if (lm != null) {
            Intent(context, LandmarkDetailActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("server_id", landmarkId)
                putExtra("name", lm.optString("name"))
                putExtra("location", lm.optString("location"))
                putExtra("photo_uri", lm.optString("imageUrl"))
                putExtra("year_built", lm.optString("yearBuilt"))
                putExtra("status", lm.optString("status"))
                putExtra("architect", lm.optString("architect"))
                putExtra("capacity", lm.optString("capacity"))
                putExtra("narrative_p1", lm.optString("narrativeP1"))
                putExtra("narrative_quote", lm.optString("narrativeQuote"))
                putExtra("narrative_p2", lm.optString("narrativeP2"))
                putExtra("nearby1_name", lm.optString("nearby1Name"))
                putExtra("nearby1_category", lm.optString("nearby1Category"))
                putExtra("nearby2_name", lm.optString("nearby2Name"))
                putExtra("nearby2_category", lm.optString("nearby2Category"))
                putExtra("nearby3_name", lm.optString("nearby3Name"))
                putExtra("nearby3_category", lm.optString("nearby3Category"))
                putExtra("rating", lm.optInt("rating"))
                putExtra("language", lm.optString("language", "en"))
                putExtra("is_favorited", lm.optInt("isFavorited"))
                putExtra("from_saved", true)
            }
        } else {
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_SAVED)
            }
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            landmarkId.toInt(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Apply user's language to get localized strings
        val langCode = SessionManager(context).language
        val locale = Locale(langCode)
        val config = context.resources.configuration
        config.setLocale(locale)
        val localizedCtx = context.createConfigurationContext(config)

        val title = localizedCtx.getString(R.string.notif_nearby_title, landmarkName)
        val body = localizedCtx.getString(R.string.notif_nearby_body)

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_location_on)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        nm.notify(landmarkId.toInt(), notification)
    }
}
