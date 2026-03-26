package com.example.recognizeai

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import org.json.JSONObject

object GeofenceManager {

    private const val TAG = "GeofenceManager"
    const val RADIUS_METERS = 200f

    data class GeofenceLandmark(
        val id: Long,
        val name: String,
        val lat: Double,
        val lng: Double,
        val location: String = "",
        val imageUrl: String = "",
        val yearBuilt: String = "",
        val status: String = "",
        val architect: String = "",
        val capacity: String = "",
        val narrativeP1: String = "",
        val narrativeQuote: String = "",
        val narrativeP2: String = "",
        val nearby1Name: String = "",
        val nearby1Category: String = "",
        val nearby2Name: String = "",
        val nearby2Category: String = "",
        val nearby3Name: String = "",
        val nearby3Category: String = "",
        val rating: Int = 0,
        val language: String = "en",
        val isFavorited: Int = 0
    )

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java)
        return PendingIntent.getBroadcast(
            context.applicationContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    fun register(context: Context, landmarks: List<GeofenceLandmark>) {
        if (!hasPermission(context)) {
            Log.d(TAG, "Missing background location permission — skipping")
            return
        }

        // Filter out landmarks without coordinates, cap at 99 (Google limit is 100)
        val valid = landmarks.filter { it.lat != 0.0 && it.lng != 0.0 }.take(99)
        if (valid.isEmpty()) {
            Log.d(TAG, "No landmarks with coordinates")
            return
        }

        val client = LocationServices.getGeofencingClient(context.applicationContext)

        val geofences = valid.map { lm ->
            Geofence.Builder()
                .setRequestId("${lm.id}|${lm.name}")
                .setCircularRegion(lm.lat, lm.lng, RADIUS_METERS)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
                .build()
        }

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(0) // don't fire immediately if already inside
            .addGeofences(geofences)
            .build()

        // Store full landmark data so the receiver can open the detail screen
        saveLandmarkData(context, valid)

        val pi = pendingIntent(context)
        client.removeGeofences(pi).addOnCompleteListener {
            client.addGeofences(request, pi)
                .addOnSuccessListener {
                    Log.d(TAG, "Registered ${valid.size} geofences at ${RADIUS_METERS}m")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to register geofences: ${e.message}")
                }
        }
    }

    private fun saveLandmarkData(context: Context, landmarks: List<GeofenceLandmark>) {
        val prefs = context.getSharedPreferences("geofence_data", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        for (lm in landmarks) {
            val json = JSONObject().apply {
                put("id", lm.id)
                put("name", lm.name)
                put("location", lm.location)
                put("imageUrl", lm.imageUrl)
                put("yearBuilt", lm.yearBuilt)
                put("status", lm.status)
                put("architect", lm.architect)
                put("capacity", lm.capacity)
                put("narrativeP1", lm.narrativeP1)
                put("narrativeQuote", lm.narrativeQuote)
                put("narrativeP2", lm.narrativeP2)
                put("nearby1Name", lm.nearby1Name)
                put("nearby1Category", lm.nearby1Category)
                put("nearby2Name", lm.nearby2Name)
                put("nearby2Category", lm.nearby2Category)
                put("nearby3Name", lm.nearby3Name)
                put("nearby3Category", lm.nearby3Category)
                put("rating", lm.rating)
                put("language", lm.language)
                put("isFavorited", lm.isFavorited)
            }
            editor.putString("lm_${lm.id}", json.toString())
        }
        editor.apply()
    }

    fun getLandmarkData(context: Context, id: Long): JSONObject? {
        val prefs = context.getSharedPreferences("geofence_data", Context.MODE_PRIVATE)
        val json = prefs.getString("lm_$id", null) ?: return null
        return runCatching { JSONObject(json) }.getOrNull()
    }

    fun hasPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val background = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else true

        return fine && background
    }
}
