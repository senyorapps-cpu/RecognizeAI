package com.example.recognizeai

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.bumptech.glide.Glide
import com.example.recognizeai.databinding.FragmentHomeBinding
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private var lastCaptureEntry: JSONObject? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()


    private val requestLocationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            fetchLocationAndWeather()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupGreeting()
        fetchLocationAndWeather()
        setupClickListeners()
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            loadLastCapture()
            loadLikedPlace()
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) {
            loadLastCapture()
            loadLikedPlace()
        }
    }

    private fun setupGreeting() {
        val session = SessionManager(requireContext())
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val timeGreeting = when {
            hour < 12 -> "Good Morning"
            hour < 17 -> "Good Afternoon"
            else      -> "Good Evening"
        }
        val name = if (session.isGoogle) session.displayName.split(" ").firstOrNull() ?: session.displayName else "Explorer"
        binding.tvGreeting.text = "$timeGreeting, $name!"
        binding.tvLocationWeather.text = "Detecting location…"

        if (!hasLocationPermission()) {
            binding.tvLocationWeather.text = ""
        }
        // Weather will be loaded by fetchLocationAndWeather() once location is obtained
    }

    private fun fetchWeatherForLocation(lat: Double, lon: Double) {
        val ctx = context ?: return  // capture context before coroutine
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Reverse geocode city name — isolated try/catch so weather still shows if this fails
                var city = ""
                var country = ""
                try {
                    val geocoder = android.location.Geocoder(ctx, java.util.Locale.US)
                    val addresses = geocoder.getFromLocation(lat, lon, 1)
                    city = addresses?.firstOrNull()?.let { addr ->
                        addr.locality ?: addr.subAdminArea ?: addr.adminArea
                    } ?: ""
                    country = addresses?.firstOrNull()?.countryName ?: ""
                } catch (geoEx: Exception) {
                    Log.w("HomeFragment", "Geocoder failed, showing weather without city", geoEx)
                }

                // Fetch weather from Open-Meteo (free, no API key)
                val weatherUrl = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current_weather=true"
                val weatherRequest = okhttp3.Request.Builder().url(weatherUrl).get().build()
                val weatherResponse = client.newCall(weatherRequest).execute()
                val weatherBody = weatherResponse.body?.string() ?: return@launch
                val weatherJson = org.json.JSONObject(weatherBody)
                val current = weatherJson.optJSONObject("current_weather") ?: return@launch
                val tempC = current.optDouble("temperature", Double.NaN)
                if (tempC.isNaN()) return@launch

                val weatherCode = current.optInt("weathercode", 0)
                val weatherIcon = when (weatherCode) {
                    0 -> "☀️"
                    1, 2 -> "⛅"
                    3 -> "☁️"
                    45, 48 -> "🌫️"
                    51, 53, 55, 61, 63, 65, 80, 81, 82 -> "🌧️"
                    71, 73, 75, 77, 85, 86 -> "❄️"
                    95, 96, 99 -> "⛈️"
                    else -> "🌤️"
                }

                val locationPart = if (city.isNotEmpty() && country.isNotEmpty()) "📍 $city, $country • " else ""
                val locationText = "$locationPart$weatherIcon ${tempC.toInt()}°C"

                withContext(Dispatchers.Main) {
                    if (_binding != null) binding.tvLocationWeather.text = locationText
                }
            } catch (e: Exception) {
                Log.e("HomeFragment", "Failed to load weather", e)
                withContext(Dispatchers.Main) {
                    if (_binding != null) binding.tvLocationWeather.text = ""
                }
            }
        }
    }

    private fun loadLastCapture() {
        if (_binding == null) return
        val session = SessionManager(requireContext())
        val userId = session.userId
        val deviceId = session.deviceId

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = if (userId > 0) {
                    "${SessionManager.BASE_URL}/api/user/$userId/landmarks?device_id=$deviceId"
                } else {
                    "${SessionManager.BASE_URL}/api/landmarks/by-device?device_id=$deviceId"
                }
                val request = Request.Builder().url(url).get().build()
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: "[]"
                if (!response.isSuccessful) throw Exception("Server error: ${response.code}")

                val jsonArray = JSONArray(body)
                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext
                    if (jsonArray.length() > 0) {
                        val last = jsonArray.getJSONObject(0)
                        val rawUrl = last.optString("image_url", "")
                        val imageUrl = if (rawUrl.startsWith("http")) rawUrl else "${SessionManager.BASE_URL}$rawUrl"
                        last.put("photo_uri", imageUrl)
                        last.put("server_id", last.optLong("id", -1L))
                        lastCaptureEntry = last
                        binding.txtLastCaptureName.text = last.optString("name", "Unknown")
                        binding.txtLastCaptureLocation.text = last.optString("location", "")

                        Glide.with(this@HomeFragment)
                            .load(imageUrl)
                            .centerCrop()
                            .into(binding.imgLastCapture)
                    } else {
                        loadLastCaptureFromLocal()
                    }
                }
            } catch (e: Exception) {
                Log.e("HomeFragment", "Failed to load last capture from server", e)
                withContext(Dispatchers.Main) { loadLastCaptureFromLocal() }
            }
        }
    }

    private fun loadLastCaptureFromLocal() {
        if (_binding == null) return
        val prefs = requireContext().getSharedPreferences("recognizeai_landmarks", Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString("landmarks", "[]"))

        // Find last non-pending (analyzed) item
        var last: JSONObject? = null
        for (i in arr.length() - 1 downTo 0) {
            val obj = arr.getJSONObject(i)
            if (obj.optString("status", "") != "pending") {
                last = obj
                break
            }
        }

        if (last != null) {
            lastCaptureEntry = last
            binding.txtLastCaptureName.text = last.optString("name", "Unknown")
            binding.txtLastCaptureLocation.text = last.optString("location", "")

            val photoUri = last.optString("photo_uri", "")
            if (photoUri.isNotEmpty()) {
                Glide.with(this)
                    .load(Uri.parse(photoUri))
                    .centerCrop()
                    .into(binding.imgLastCapture)
            }
        } else {
            lastCaptureEntry = null
            binding.txtLastCaptureName.text = getString(R.string.no_captures_yet)
            binding.txtLastCaptureLocation.text = getString(R.string.take_first_photo)
        }
    }

    private fun fetchLocationAndWeather() {
        if (!hasLocationPermission()) {
            requestLocationPermission.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
            return
        }

        try {
            val fusedClient = LocationServices.getFusedLocationProviderClient(requireActivity())
            fusedClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                .addOnSuccessListener { location ->
                    if (location != null) {
                        fetchWeatherForLocation(location.latitude, location.longitude)
                    } else {
                        fusedClient.lastLocation.addOnSuccessListener { last ->
                            if (last != null) {
                                fetchWeatherForLocation(last.latitude, last.longitude)
                            } else {
                                if (_binding != null) binding.tvLocationWeather.text = ""
                            }
                        }
                    }
                }
                .addOnFailureListener {
                    if (_binding != null) binding.tvLocationWeather.text = ""
                }
        } catch (_: SecurityException) {
            if (_binding != null) binding.tvLocationWeather.text = ""
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
               ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun openLastCaptureDetail() {
        val entry = lastCaptureEntry ?: return
        val intent = Intent(requireContext(), LandmarkDetailActivity::class.java).apply {
            putExtra("photo_uri", entry.optString("photo_uri", ""))
            putExtra("server_id", entry.optLong("server_id", entry.optLong("id", -1L)))
            putExtra("name", entry.optString("name", ""))
            putExtra("location", entry.optString("location", ""))
            putExtra("year_built", entry.optString("year_built", ""))
            putExtra("status", entry.optString("status", ""))
            putExtra("architect", entry.optString("architect", ""))
            putExtra("capacity", entry.optString("capacity", ""))
            putExtra("narrative_p1", entry.optString("narrative_p1", ""))
            putExtra("narrative_quote", entry.optString("narrative_quote", ""))
            putExtra("narrative_p2", entry.optString("narrative_p2", ""))
            putExtra("nearby1_name", entry.optString("nearby1_name", ""))
            putExtra("nearby1_category", entry.optString("nearby1_category", ""))
            putExtra("nearby2_name", entry.optString("nearby2_name", ""))
            putExtra("nearby2_category", entry.optString("nearby2_category", ""))
            putExtra("nearby3_name", entry.optString("nearby3_name", ""))
            putExtra("nearby3_category", entry.optString("nearby3_category", ""))
            putExtra("from_home", true)
            putExtra("rating", entry.optInt("rating", 0))
            putExtra("language", entry.optString("language", "en"))
        }
        startActivity(intent)
    }

    private fun loadLikedPlace() {
        if (_binding == null) return
        val session = SessionManager(requireContext())
        val userId = session.userId
        val deviceId = session.deviceId

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = "${SessionManager.BASE_URL}/api/landmarks/favorites?device_id=$deviceId" +
                    if (userId > 0) "&user_id=$userId" else ""
                val request = Request.Builder().url(url).get().build()
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: "[]"
                if (!response.isSuccessful) throw Exception("Server error")

                val arr = JSONArray(body)

                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext
                    if (arr.length() == 0) {
                        binding.sectionLikedPlaces.visibility = View.GONE
                        return@withContext
                    }

                    binding.likedPlacesRow.removeAllViews()
                    val dp = resources.displayMetrics.density
                    val cardW = (144 * dp).toInt()
                    val cardH = (240 * dp).toInt()
                    val marginEnd = (12 * dp).toInt()

                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val rawUrl = obj.optString("image_url", "")
                        val imageUrl = if (rawUrl.startsWith("http")) rawUrl else "${SessionManager.BASE_URL}$rawUrl"
                        obj.put("photo_uri", imageUrl)
                        obj.put("server_id", obj.optLong("id", -1L))

                        // Build card: FrameLayout with image + name overlay
                        val card = android.widget.FrameLayout(requireContext()).apply {
                            layoutParams = android.widget.LinearLayout.LayoutParams(cardW, cardH).apply {
                                setMargins(0, 0, marginEnd, 0)
                            }
                            background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_rounded_24)
                            clipToOutline = true
                            elevation = 6 * dp
                            isClickable = true
                            isFocusable = true
                        }

                        val img = android.widget.ImageView(requireContext()).apply {
                            layoutParams = android.widget.FrameLayout.LayoutParams(
                                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                            )
                            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                        }
                        card.addView(img)

                        // Dark gradient at bottom
                        val gradient = android.view.View(requireContext()).apply {
                            layoutParams = android.widget.FrameLayout.LayoutParams(
                                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                                (80 * dp).toInt(),
                                android.view.Gravity.BOTTOM
                            )
                            setBackgroundResource(R.drawable.gradient_top_dark)
                            rotation = 180f
                        }
                        card.addView(gradient)

                        // Name label
                        val nameLabel = android.widget.TextView(requireContext()).apply {
                            layoutParams = android.widget.FrameLayout.LayoutParams(
                                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                                android.view.Gravity.BOTTOM
                            ).apply { setMargins((10*dp).toInt(), 0, (10*dp).toInt(), (8*dp).toInt()) }
                            text = obj.optString("name", "")
                            setTextColor(android.graphics.Color.WHITE)
                            textSize = 12f
                            maxLines = 2
                            ellipsize = android.text.TextUtils.TruncateAt.END
                            typeface = android.graphics.Typeface.DEFAULT_BOLD
                        }
                        card.addView(nameLabel)

                        // Heart badge top-right
                        val heart = android.widget.ImageView(requireContext()).apply {
                            layoutParams = android.widget.FrameLayout.LayoutParams(
                                (22 * dp).toInt(), (22 * dp).toInt(), android.view.Gravity.TOP or android.view.Gravity.END
                            ).apply { setMargins(0, (8*dp).toInt(), (8*dp).toInt(), 0) }
                            setImageResource(R.drawable.ic_favorite_filled)
                        }
                        card.addView(heart)

                        val entry = obj
                        card.setOnClickListener { openLikedPlaceDetail(entry) }

                        if (imageUrl.isNotEmpty()) {
                            Glide.with(this@HomeFragment).load(imageUrl).centerCrop().into(img)
                        }

                        binding.likedPlacesRow.addView(card)
                    }

                    binding.sectionLikedPlaces.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                Log.e("HomeFragment", "Failed to load liked places", e)
                withContext(Dispatchers.Main) {
                    if (_binding != null) binding.sectionLikedPlaces.visibility = View.GONE
                }
            }
        }
    }

    private fun openLikedPlaceDetail(entry: JSONObject) {
        val intent = Intent(requireContext(), LandmarkDetailActivity::class.java).apply {
            putExtra("photo_uri", entry.optString("photo_uri", ""))
            putExtra("server_id", entry.optLong("server_id", -1L))
            putExtra("name", entry.optString("name", ""))
            putExtra("location", entry.optString("location", ""))
            putExtra("year_built", entry.optString("year_built", ""))
            putExtra("status", entry.optString("status", ""))
            putExtra("architect", entry.optString("architect", ""))
            putExtra("capacity", entry.optString("capacity", ""))
            putExtra("narrative_p1", entry.optString("narrative_p1", ""))
            putExtra("narrative_quote", entry.optString("narrative_quote", ""))
            putExtra("narrative_p2", entry.optString("narrative_p2", ""))
            putExtra("nearby1_name", entry.optString("nearby1_name", ""))
            putExtra("nearby1_category", entry.optString("nearby1_category", ""))
            putExtra("nearby2_name", entry.optString("nearby2_name", ""))
            putExtra("nearby2_category", entry.optString("nearby2_category", ""))
            putExtra("nearby3_name", entry.optString("nearby3_name", ""))
            putExtra("nearby3_category", entry.optString("nearby3_category", ""))
            putExtra("rating", entry.optInt("rating", 0))
            putExtra("language", entry.optString("language", "en"))
            putExtra("is_favorited", entry.optInt("is_favorited", 1))
            putExtra("from_home", true)
        }
        startActivity(intent)
    }

    private fun setupClickListeners() {
        binding.cardLastCapture.setOnClickListener { openLastCaptureDetail() }
        binding.btnViewDetails.setOnClickListener { openLastCaptureDetail() }

        binding.btnSeeHistory.setOnClickListener {
            // Navigate to Saved tab in MainActivity
            (activity as? MainActivity)?.navigateToSaved()
        }

        binding.cardArMode.setOnClickListener {
            if (SessionManager(requireContext()).isPro) {
                startActivity(Intent(requireContext(), ARCameraActivity::class.java))
            } else {
                startActivity(Intent(requireContext(), SubscriptionActivity::class.java))
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
