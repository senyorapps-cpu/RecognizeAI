package com.example.recognizeai

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

class MapFragment : Fragment(), OnMapReadyCallback {

    private var googleMap: GoogleMap? = null
    private var progressBar: ProgressBar? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val requestLocationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            enableMyLocation()
            fetchUserLocationAndLoadNearby()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_map, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        progressBar = view.findViewById(R.id.progressBar)
        val mapFragment = childFragmentManager.findFragmentById(R.id.googleMap) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map

        if (hasLocationPermission()) {
            enableMyLocation()
            fetchUserLocationAndLoadNearby()
        } else {
            requestLocationPermission.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
               ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun enableMyLocation() {
        try {
            googleMap?.isMyLocationEnabled = true
        } catch (_: SecurityException) {}
    }

    private fun fetchUserLocationAndLoadNearby() {
        if (!hasLocationPermission()) return
        try {
            val fusedClient = LocationServices.getFusedLocationProviderClient(requireActivity())
            fusedClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    val latLng = LatLng(location.latitude, location.longitude)
                    googleMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 12f))
                    loadNearbyLandmarks(location.latitude, location.longitude)
                } else {
                    // Default to a central position if location is null
                    val defaultPos = LatLng(41.9028, 12.4964) // Rome
                    googleMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(defaultPos, 5f))
                }
            }
        } catch (_: SecurityException) {}
    }

    private fun loadNearbyLandmarks(lat: Double, lng: Double) {
        progressBar?.visibility = View.VISIBLE

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = "${SessionManager.BASE_URL}/api/landmarks/nearby?lat=$lat&lng=$lng&radius=50"
                val request = Request.Builder().url(url).get().build()
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: "[]"

                if (response.isSuccessful) {
                    val arr = JSONArray(body)
                    withContext(Dispatchers.Main) {
                        for (i in 0 until arr.length()) {
                            val obj = arr.getJSONObject(i)
                            val markerLat = obj.optDouble("latitude", Double.NaN)
                            val markerLng = obj.optDouble("longitude", Double.NaN)
                            val name = obj.optString("name", "Landmark")

                            if (!markerLat.isNaN() && !markerLng.isNaN()) {
                                googleMap?.addMarker(
                                    MarkerOptions()
                                        .position(LatLng(markerLat, markerLng))
                                        .title(name)
                                )
                            }
                        }
                        progressBar?.visibility = View.GONE
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        progressBar?.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                Log.e("MapFragment", "Failed to load nearby landmarks", e)
                withContext(Dispatchers.Main) {
                    progressBar?.visibility = View.GONE
                }
            }
        }
    }
}
