package com.example.recognizeai

import android.Manifest
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
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

    private val nearbyPlaces = mutableListOf<JSONObject>()

    private val requestLocationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            loadTopPlaces()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadTopPlaces()
        setupClickListeners()
        loadLastCapture()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) {
            loadLastCapture()
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
                        val last = jsonArray.getJSONObject(jsonArray.length() - 1)
                        val imageUrl = "${SessionManager.BASE_URL}${last.optString("image_url", "")}"
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

    private fun loadTopPlaces() {
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
                        fetchNearbyPlaces(location.latitude, location.longitude)
                    } else {
                        fusedClient.lastLocation.addOnSuccessListener { last ->
                            if (last != null) {
                                fetchNearbyPlaces(last.latitude, last.longitude)
                            }
                        }
                    }
                }
        } catch (_: SecurityException) {}
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
               ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun fetchNearbyPlaces(lat: Double, lng: Double) {
        val lang = LocaleHelper.getCurrentLanguageCode()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = "${SessionManager.BASE_URL}/api/places/nearby?lat=$lat&lng=$lng&radius=5000&language=$lang"
                val request = Request.Builder().url(url).get().build()
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: "[]"

                if (response.isSuccessful) {
                    val arr = JSONArray(body)
                    val places = mutableListOf<JSONObject>()
                    for (i in 0 until minOf(arr.length(), 5)) {
                        places.add(arr.getJSONObject(i))
                    }

                    withContext(Dispatchers.Main) {
                        if (_binding == null) return@withContext
                        nearbyPlaces.clear()
                        nearbyPlaces.addAll(places)
                        displayTopPlaces()
                    }
                }
            } catch (e: Exception) {
                Log.e("HomeFragment", "Failed to load nearby places", e)
            }
        }
    }

    private fun displayTopPlaces() {
        if (_binding == null) return
        val cornerRadius = (20 * resources.displayMetrics.density).toInt()
        val inflater = LayoutInflater.from(requireContext())
        binding.topPlacesContainer.removeAllViews()

        for (place in nearbyPlaces) {
            val itemView = inflater.inflate(R.layout.item_top_place, binding.topPlacesContainer, false)

            val imgPlace = itemView.findViewById<ImageView>(R.id.imgPlace)
            val txtName = itemView.findViewById<TextView>(R.id.txtPlaceName)
            val txtLocation = itemView.findViewById<TextView>(R.id.txtPlaceLocation)
            val ratingBadge = itemView.findViewById<LinearLayout>(R.id.ratingBadge)
            val txtRating = itemView.findViewById<TextView>(R.id.txtPlaceRating)

            txtName.text = place.optString("name", "Place")
            txtLocation.text = place.optString("location", "")

            // Show rating badge
            val rating = place.optDouble("rating", 0.0)
            if (rating > 0) {
                txtRating.text = String.format("%.1f", rating)
                ratingBadge.visibility = View.VISIBLE
            }

            // Load photo
            val photoRef = place.optString("photo_reference", "")
            if (photoRef.isNotEmpty()) {
                val photoUrl = "${SessionManager.BASE_URL}/api/places/photo?ref=$photoRef"
                Glide.with(this)
                    .load(photoUrl)
                    .transform(CenterCrop(), RoundedCorners(cornerRadius))
                    .into(imgPlace)
            }

            // On tap — show place detail dialog
            itemView.setOnClickListener { showPlaceDetailDialog(place) }

            binding.topPlacesContainer.addView(itemView)
        }
    }

    private fun showPlaceDetailDialog(place: JSONObject) {
        val dialog = Dialog(requireContext())
        dialog.setContentView(R.layout.dialog_place_detail)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val imgPlace = dialog.findViewById<ImageView>(R.id.imgPlaceDetail)
        val tvName = dialog.findViewById<TextView>(R.id.tvPlaceName)
        val tvLocation = dialog.findViewById<TextView>(R.id.tvPlaceLocation)
        val tvRating = dialog.findViewById<TextView>(R.id.tvPlaceRating)
        val tvCategory = dialog.findViewById<TextView>(R.id.tvPlaceCategory)
        val tvDescription = dialog.findViewById<TextView>(R.id.tvPlaceDescription)
        val btnNavigate = dialog.findViewById<TextView>(R.id.btnNavigatePlace)

        tvName.text = place.optString("name", "")
        tvLocation.text = place.optString("location", "")

        val rating = place.optDouble("rating", 0.0)
        tvRating.text = if (rating > 0) "\u2605 ${String.format("%.1f", rating)}" else ""

        val category = place.optString("category", "Tourist Attraction")
        tvCategory.text = localizeCategory(category)

        // Load photo
        val photoRef = place.optString("photo_reference", "")
        if (photoRef.isNotEmpty()) {
            val photoUrl = "${SessionManager.BASE_URL}/api/places/photo?ref=$photoRef"
            val cornerRadius = (16 * resources.displayMetrics.density).toInt()
            Glide.with(this)
                .load(photoUrl)
                .transform(CenterCrop(), RoundedCorners(cornerRadius))
                .into(imgPlace)
        }

        // Fetch description
        tvDescription.text = "\u2026"
        val placeId = place.optString("place_id", "")
        if (placeId.isNotEmpty()) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val lang = LocaleHelper.getCurrentLanguageCode()
                    val url = "${SessionManager.BASE_URL}/api/places/details?place_id=$placeId&language=$lang"
                    val request = Request.Builder().url(url).get().build()
                    val response = client.newCall(request).execute()
                    val body = response.body?.string() ?: "{}"
                    if (response.isSuccessful) {
                        val obj = JSONObject(body)
                        val desc = obj.optString("description", "")
                        withContext(Dispatchers.Main) {
                            if (desc.isNotEmpty()) {
                                tvDescription.text = desc
                            } else {
                                tvDescription.visibility = View.GONE
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("HomeFragment", "Failed to fetch place details", e)
                    withContext(Dispatchers.Main) { tvDescription.visibility = View.GONE }
                }
            }
        } else {
            tvDescription.visibility = View.GONE
        }

        // Navigate button
        btnNavigate.setOnClickListener {
            dialog.dismiss()
            val lat = place.optDouble("latitude")
            val lng = place.optDouble("longitude")
            val name = place.optString("name", "")
            val uri = Uri.parse("geo:$lat,$lng?q=$lat,$lng(${Uri.encode(name)})")
            val mapIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.google.android.apps.maps")
            }
            if (mapIntent.resolveActivity(requireContext().packageManager) != null) {
                startActivity(mapIntent)
            } else {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            }
        }

        dialog.show()
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

    private fun setupClickListeners() {
        binding.cardLastCapture.setOnClickListener { openLastCaptureDetail() }
        binding.btnViewDetails.setOnClickListener { openLastCaptureDetail() }

        binding.btnSeeHistory.setOnClickListener {
            // Navigate to Saved tab in MainActivity
            (activity as? MainActivity)?.navigateToSaved()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
