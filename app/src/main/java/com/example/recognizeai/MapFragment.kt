package com.example.recognizeai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MapFragment : Fragment(), OnMapReadyCallback {

    private var googleMap: GoogleMap? = null
    private var progressBar: ProgressBar? = null
    private var cardDetail: LinearLayout? = null
    private var tvCardName: TextView? = null
    private var tvCardLocation: TextView? = null
    private var tvCardCategory: TextView? = null
    private var tvCardDistance: TextView? = null
    private var tvCardDescription: TextView? = null
    private var imgCardThumbnail: ImageView? = null
    private var btnExploreAI: TextView? = null
    private var userLocation: Location? = null

    private val markerDataMap = mutableMapOf<String, JSONObject>()
    private var selectedPlace: JSONObject? = null

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
        cardDetail = view.findViewById(R.id.cardLandmarkDetail)
        tvCardName = view.findViewById(R.id.tvCardName)
        tvCardLocation = view.findViewById(R.id.tvCardLocation)
        tvCardCategory = view.findViewById(R.id.tvCardCategory)
        tvCardDistance = view.findViewById(R.id.tvCardDistance)
        tvCardDescription = view.findViewById(R.id.tvCardDescription)
        imgCardThumbnail = view.findViewById(R.id.imgCardThumbnail)
        btnExploreAI = view.findViewById(R.id.btnExploreAI)

        view.findViewById<FrameLayout>(R.id.btnZoomIn)?.setOnClickListener {
            googleMap?.animateCamera(CameraUpdateFactory.zoomIn())
        }
        view.findViewById<FrameLayout>(R.id.btnZoomOut)?.setOnClickListener {
            googleMap?.animateCamera(CameraUpdateFactory.zoomOut())
        }
        view.findViewById<FrameLayout>(R.id.btnMyLocation)?.setOnClickListener {
            moveToMyLocation()
        }

        btnExploreAI?.setOnClickListener {
            val data = selectedPlace ?: return@setOnClickListener
            val source = data.optString("_source", "")

            if (source == "google_places") {
                // Open in Google Maps for navigation
                val lat = data.optDouble("latitude")
                val lng = data.optDouble("longitude")
                val name = data.optString("name", "")
                val uri = Uri.parse("geo:$lat,$lng?q=$lat,$lng(${Uri.encode(name)})")
                val mapIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage("com.google.android.apps.maps")
                }
                if (mapIntent.resolveActivity(requireContext().packageManager) != null) {
                    startActivity(mapIntent)
                } else {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                }
            } else {
                // Our landmark — open detail page
                val imageUrl = "${SessionManager.BASE_URL}${data.optString("image_url", "")}"
                val intent = Intent(requireContext(), LandmarkDetailActivity::class.java).apply {
                    putExtra("photo_uri", imageUrl)
                    putExtra("server_id", data.optLong("id", -1L))
                    putExtra("name", data.optString("name", ""))
                    putExtra("location", data.optString("location", ""))
                    putExtra("year_built", data.optString("year_built", ""))
                    putExtra("status", data.optString("status", ""))
                    putExtra("architect", data.optString("architect", ""))
                    putExtra("capacity", data.optString("capacity", ""))
                    putExtra("narrative_p1", data.optString("narrative_p1", ""))
                    putExtra("narrative_quote", data.optString("narrative_quote", ""))
                    putExtra("narrative_p2", data.optString("narrative_p2", ""))
                    putExtra("nearby1_name", data.optString("nearby1_name", ""))
                    putExtra("nearby1_category", data.optString("nearby1_category", ""))
                    putExtra("nearby2_name", data.optString("nearby2_name", ""))
                    putExtra("nearby2_category", data.optString("nearby2_category", ""))
                    putExtra("nearby3_name", data.optString("nearby3_name", ""))
                    putExtra("nearby3_category", data.optString("nearby3_category", ""))
                    putExtra("from_home", true)
                    putExtra("rating", data.optInt("rating", 0))
                    putExtra("language", data.optString("language", "en"))
                }
                startActivity(intent)
            }
        }

        val mapFragment = childFragmentManager.findFragmentById(R.id.googleMap) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map

        map.uiSettings.isZoomControlsEnabled = false
        map.uiSettings.isMyLocationButtonEnabled = false

        map.setOnMarkerClickListener { marker ->
            val data = markerDataMap[marker.id]
            if (data != null) {
                showPlaceCard(data)
            }
            true
        }

        map.setOnMapClickListener {
            cardDetail?.visibility = View.GONE
            selectedPlace = null
        }

        if (hasLocationPermission()) {
            enableMyLocation()
            fetchUserLocationAndLoadNearby()
        } else {
            requestLocationPermission.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    private fun localizeCategory(englishCategory: String): String {
        return when (englishCategory) {
            "Landmark" -> getString(R.string.map_category_landmark)
            "Museum" -> getString(R.string.map_category_museum)
            "Religious Site" -> getString(R.string.map_category_religious)
            "Viewpoint" -> getString(R.string.map_category_viewpoint)
            "Park & Garden" -> getString(R.string.map_category_park)
            "Local Food" -> getString(R.string.map_category_food)
            "Market" -> getString(R.string.map_category_market)
            "Tourist Attraction" -> getString(R.string.map_category_tourist)
            else -> englishCategory
        }
    }

    private fun showPlaceCard(data: JSONObject) {
        selectedPlace = data
        val source = data.optString("_source", "")

        tvCardName?.text = data.optString("name", "Place")
        tvCardLocation?.text = data.optString("location", "")

        if (source == "google_places") {
            val category = data.optString("category", "Tourist Attraction")
            val localizedCategory = localizeCategory(category)
            tvCardCategory?.text = localizedCategory
            btnExploreAI?.text = getString(R.string.map_navigate)

            // Show rating as part of category if available
            val rating = data.optDouble("rating", 0.0)
            if (rating > 0) {
                tvCardCategory?.text = "$localizedCategory \u2022 \u2605 ${String.format("%.1f", rating)}"
            }

            // Load photo if available
            val photoRef = data.optString("photo_reference", "")
            if (photoRef.isNotEmpty()) {
                val photoUrl = "${SessionManager.BASE_URL}/api/places/photo?ref=$photoRef"
                val cornerRadius = (8 * resources.displayMetrics.density).toInt()
                Glide.with(this)
                    .load(photoUrl)
                    .transform(CenterCrop(), RoundedCorners(cornerRadius))
                    .into(imgCardThumbnail!!)
                imgCardThumbnail?.visibility = View.VISIBLE
            } else {
                imgCardThumbnail?.visibility = View.GONE
            }

            // Fetch description from Place Details API
            val placeId = data.optString("place_id", "")
            if (placeId.isNotEmpty()) {
                tvCardDescription?.text = "\u2026"
                tvCardDescription?.visibility = View.VISIBLE
                fetchPlaceDescription(placeId)
            } else {
                tvCardDescription?.visibility = View.GONE
            }
        } else {
            tvCardCategory?.text = getString(R.string.map_landmark)
            btnExploreAI?.text = getString(R.string.map_explore_ai)
            tvCardDescription?.visibility = View.GONE

            val imageUrl = data.optString("image_url", "")
            if (imageUrl.isNotEmpty()) {
                val fullUrl = "${SessionManager.BASE_URL}$imageUrl"
                val cornerRadius = (8 * resources.displayMetrics.density).toInt()
                Glide.with(this)
                    .load(fullUrl)
                    .transform(CenterCrop(), RoundedCorners(cornerRadius))
                    .into(imgCardThumbnail!!)
                imgCardThumbnail?.visibility = View.VISIBLE
            } else {
                imgCardThumbnail?.visibility = View.GONE
            }
        }

        // Calculate distance
        val lat = data.optDouble("latitude", Double.NaN)
        val lng = data.optDouble("longitude", Double.NaN)
        if (!lat.isNaN() && !lng.isNaN() && userLocation != null) {
            val results = FloatArray(1)
            Location.distanceBetween(userLocation!!.latitude, userLocation!!.longitude, lat, lng, results)
            val distKm = results[0] / 1000f
            tvCardDistance?.text = String.format("%.1f km", distKm)
            tvCardDistance?.visibility = View.VISIBLE
        } else {
            tvCardDistance?.visibility = View.GONE
        }

        cardDetail?.visibility = View.VISIBLE
    }

    private fun fetchPlaceDescription(placeId: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val lang = LocaleHelper.getCurrentLanguageCode()
                val url = "${SessionManager.BASE_URL}/api/places/details?place_id=$placeId&language=$lang"
                val request = Request.Builder().url(url).get().build()
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: "{}"

                if (response.isSuccessful) {
                    val obj = JSONObject(body)
                    val description = obj.optString("description", "")
                    withContext(Dispatchers.Main) {
                        if (description.isNotEmpty()) {
                            tvCardDescription?.text = description
                            tvCardDescription?.visibility = View.VISIBLE
                        } else {
                            tvCardDescription?.visibility = View.GONE
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MapFragment", "Failed to fetch place description", e)
                withContext(Dispatchers.Main) {
                    tvCardDescription?.visibility = View.GONE
                }
            }
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

    private fun moveToMyLocation() {
        if (!hasLocationPermission()) return
        try {
            val fusedClient = LocationServices.getFusedLocationProviderClient(requireActivity())
            fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { location ->
                    if (location != null) {
                        userLocation = location
                        val latLng = LatLng(location.latitude, location.longitude)
                        googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
                    } else {
                        fusedClient.lastLocation.addOnSuccessListener { last ->
                            if (last != null) {
                                val latLng = LatLng(last.latitude, last.longitude)
                                googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
                            }
                        }
                    }
                }
        } catch (_: SecurityException) {}
    }

    private fun fetchUserLocationAndLoadNearby() {
        if (!hasLocationPermission()) return
        progressBar?.visibility = View.VISIBLE
        try {
            val fusedClient = LocationServices.getFusedLocationProviderClient(requireActivity())
            fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { location ->
                    if (location != null) {
                        userLocation = location
                        val latLng = LatLng(location.latitude, location.longitude)
                        googleMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 14f))
                        loadNearbyLandmarks(location.latitude, location.longitude)
                        loadNearbyTouristPlaces(location.latitude, location.longitude)
                    } else {
                        fusedClient.lastLocation.addOnSuccessListener { last ->
                            if (last != null) {
                                userLocation = last
                                val latLng = LatLng(last.latitude, last.longitude)
                                googleMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 14f))
                                loadNearbyLandmarks(last.latitude, last.longitude)
                                loadNearbyTouristPlaces(last.latitude, last.longitude)
                            } else {
                                Log.d("MapFragment", "No location available")
                                progressBar?.visibility = View.GONE
                            }
                        }
                    }
                }
                .addOnFailureListener { e ->
                    Log.e("MapFragment", "getCurrentLocation failed", e)
                    progressBar?.visibility = View.GONE
                }
        } catch (_: SecurityException) {
            progressBar?.visibility = View.GONE
        }
    }

    private fun createMarkerIcon(color: Int): Bitmap {
        val size = (36 * resources.displayMetrics.density).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = color
        paint.style = Paint.Style.FILL

        val cx = size / 2f
        val circleRadius = size * 0.3f
        val circleY = size * 0.35f
        canvas.drawCircle(cx, circleY, circleRadius, paint)

        val path = android.graphics.Path()
        path.moveTo(cx - circleRadius * 0.6f, circleY + circleRadius * 0.5f)
        path.lineTo(cx + circleRadius * 0.6f, circleY + circleRadius * 0.5f)
        path.lineTo(cx, size.toFloat() * 0.9f)
        path.close()
        canvas.drawPath(path, paint)

        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(cx, circleY, circleRadius * 0.45f, paint)

        return bitmap
    }

    private fun createCategoryMarkerIcon(color: Int, symbol: String): Bitmap {
        val size = (42 * resources.displayMetrics.density).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = color
        paint.style = Paint.Style.FILL

        val cx = size / 2f
        val circleRadius = size * 0.32f
        val circleY = size * 0.35f
        canvas.drawCircle(cx, circleY, circleRadius, paint)

        // Pin tail
        val path = android.graphics.Path()
        path.moveTo(cx - circleRadius * 0.55f, circleY + circleRadius * 0.55f)
        path.lineTo(cx + circleRadius * 0.55f, circleY + circleRadius * 0.55f)
        path.lineTo(cx, size.toFloat() * 0.92f)
        path.close()
        canvas.drawPath(path, paint)

        // White inner circle
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(cx, circleY, circleRadius * 0.7f, paint)

        // Draw symbol text
        paint.color = color
        paint.textSize = circleRadius * 0.9f
        paint.textAlign = Paint.Align.CENTER
        val textBounds = android.graphics.Rect()
        paint.getTextBounds(symbol, 0, symbol.length, textBounds)
        canvas.drawText(symbol, cx, circleY + textBounds.height() / 2f, paint)

        return bitmap
    }

    private fun getCategoryMarkerIcon(category: String): Bitmap {
        return when (category) {
            "Landmark" -> createCategoryMarkerIcon(0xFF1565C0.toInt(), "\uD83C\uDFDB") // 🏛
            "Museum" -> createCategoryMarkerIcon(0xFF7B1FA2.toInt(), "\uD83C\uDFAD")   // 🎭
            "Religious Site" -> createCategoryMarkerIcon(0xFF00838F.toInt(), "\u26EA")   // ⛪
            "Viewpoint" -> createCategoryMarkerIcon(0xFFE65100.toInt(), "\uD83D\uDC41") // 👁
            "Park" -> createCategoryMarkerIcon(0xFF2E7D32.toInt(), "\uD83C\uDF33")      // 🌳
            "Local Food" -> createCategoryMarkerIcon(0xFFC62828.toInt(), "\uD83C\uDF7D") // 🍽
            "Market" -> createCategoryMarkerIcon(0xFFEF6C00.toInt(), "\uD83D\uDED2")    // 🛒
            else -> createCategoryMarkerIcon(0xFF1C5B9E.toInt(), "\u2B50")              // ⭐
        }
    }

    private fun loadNearbyLandmarks(lat: Double, lng: Double) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val session = SessionManager(requireContext())
                val userId = session.userId
                val deviceId = session.deviceId
                val url = "${SessionManager.BASE_URL}/api/landmarks/nearby?lat=$lat&lng=$lng&radius=5&user_id=$userId&device_id=$deviceId"
                val request = Request.Builder().url(url).get().build()
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: "[]"

                if (response.isSuccessful) {
                    val arr = JSONArray(body)
                    withContext(Dispatchers.Main) {
                        val goldIcon = BitmapDescriptorFactory.fromBitmap(createMarkerIcon(0xFFDFC623.toInt()))
                        for (i in 0 until arr.length()) {
                            val obj = arr.getJSONObject(i)
                            obj.put("_source", "our_landmark")
                            val markerLat = obj.optDouble("latitude", Double.NaN)
                            val markerLng = obj.optDouble("longitude", Double.NaN)
                            val name = obj.optString("name", "Landmark")

                            if (!markerLat.isNaN() && !markerLng.isNaN()) {
                                val marker = googleMap?.addMarker(
                                    MarkerOptions()
                                        .position(LatLng(markerLat, markerLng))
                                        .title(name)
                                        .icon(goldIcon)
                                )
                                if (marker != null) {
                                    markerDataMap[marker.id] = obj
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MapFragment", "Failed to load nearby landmarks", e)
            }
        }
    }

    private fun loadNearbyTouristPlaces(lat: Double, lng: Double) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val lang = LocaleHelper.getCurrentLanguageCode()
                val url = "${SessionManager.BASE_URL}/api/places/nearby?lat=$lat&lng=$lng&radius=5000&language=$lang"
                val request = Request.Builder().url(url).get().build()
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: "[]"

                if (response.isSuccessful) {
                    val arr = JSONArray(body)
                    withContext(Dispatchers.Main) {
                        // Cache category icons to avoid recreating per marker
                        val iconCache = mutableMapOf<String, com.google.android.gms.maps.model.BitmapDescriptor>()
                        for (i in 0 until arr.length()) {
                            val obj = arr.getJSONObject(i)
                            val markerLat = obj.optDouble("latitude", Double.NaN)
                            val markerLng = obj.optDouble("longitude", Double.NaN)
                            val name = obj.optString("name", "Place")
                            val category = obj.optString("category", "Landmark")

                            val icon = iconCache.getOrPut(category) {
                                BitmapDescriptorFactory.fromBitmap(getCategoryMarkerIcon(category))
                            }

                            if (!markerLat.isNaN() && !markerLng.isNaN()) {
                                val marker = googleMap?.addMarker(
                                    MarkerOptions()
                                        .position(LatLng(markerLat, markerLng))
                                        .title(name)
                                        .icon(icon)
                                )
                                if (marker != null) {
                                    markerDataMap[marker.id] = obj
                                }
                            }
                        }
                        progressBar?.visibility = View.GONE
                    }
                } else {
                    Log.e("MapFragment", "Places API error: $body")
                    withContext(Dispatchers.Main) { progressBar?.visibility = View.GONE }
                }
            } catch (e: Exception) {
                Log.e("MapFragment", "Failed to load tourist places", e)
                withContext(Dispatchers.Main) { progressBar?.visibility = View.GONE }
            }
        }
    }
}
