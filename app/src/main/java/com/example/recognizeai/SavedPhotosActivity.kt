package com.example.recognizeai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.bumptech.glide.Glide
import com.example.recognizeai.databinding.ActivitySavedPhotosBinding
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class SavedPhotosActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySavedPhotosBinding
    private val savedItems = mutableListOf<SavedLandmark>()
    private lateinit var adapter: SavedPhotosAdapter
    private var photoUri: Uri? = null

    data class SavedLandmark(
        val name: String,
        val location: String,
        val photoUri: String,
        val yearBuilt: String,
        val status: String,
        val architect: String,
        val capacity: String,
        val narrativeP1: String,
        val narrativeQuote: String,
        val narrativeP2: String,
        val nearby1Name: String,
        val nearby1Category: String,
        val nearby2Name: String,
        val nearby2Category: String,
        val nearby3Name: String,
        val nearby3Category: String
    )

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && photoUri != null) {
            val intent = Intent(this, AnalyzingActivity::class.java)
            intent.putExtra("photo_uri", photoUri.toString())
            startActivity(intent)
        }
    }

    private val requestCameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            launchCamera()
        } else {
            Toast.makeText(this, "Camera permission is required", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySavedPhotosBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupClickListeners()
    }

    override fun onResume() {
        super.onResume()
        loadSavedItems()
    }

    private fun setupRecyclerView() {
        adapter = SavedPhotosAdapter(savedItems) { item ->
            val intent = Intent(this, LandmarkDetailActivity::class.java).apply {
                putExtra("photo_uri", item.photoUri)
                putExtra("name", item.name)
                putExtra("location", item.location)
                putExtra("year_built", item.yearBuilt)
                putExtra("status", item.status)
                putExtra("architect", item.architect)
                putExtra("capacity", item.capacity)
                putExtra("narrative_p1", item.narrativeP1)
                putExtra("narrative_quote", item.narrativeQuote)
                putExtra("narrative_p2", item.narrativeP2)
                putExtra("nearby1_name", item.nearby1Name)
                putExtra("nearby1_category", item.nearby1Category)
                putExtra("nearby2_name", item.nearby2Name)
                putExtra("nearby2_category", item.nearby2Category)
                putExtra("nearby3_name", item.nearby3Name)
                putExtra("nearby3_category", item.nearby3Category)
            }
            startActivity(intent)
        }

        binding.recyclerSaved.layoutManager =
            StaggeredGridLayoutManager(3, StaggeredGridLayoutManager.VERTICAL)
        binding.recyclerSaved.adapter = adapter
    }

    private fun loadSavedItems() {
        savedItems.clear()
        val prefs = getSharedPreferences("tripai_saved", MODE_PRIVATE)
        val jsonStr = prefs.getString("saved_landmarks", "[]") ?: "[]"
        val jsonArray = JSONArray(jsonStr)

        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            savedItems.add(
                SavedLandmark(
                    name = obj.optString("name"),
                    location = obj.optString("location"),
                    photoUri = obj.optString("photo_uri"),
                    yearBuilt = obj.optString("year_built"),
                    status = obj.optString("status"),
                    architect = obj.optString("architect"),
                    capacity = obj.optString("capacity"),
                    narrativeP1 = obj.optString("narrative_p1"),
                    narrativeQuote = obj.optString("narrative_quote"),
                    narrativeP2 = obj.optString("narrative_p2"),
                    nearby1Name = obj.optString("nearby1_name"),
                    nearby1Category = obj.optString("nearby1_category"),
                    nearby2Name = obj.optString("nearby2_name"),
                    nearby2Category = obj.optString("nearby2_category"),
                    nearby3Name = obj.optString("nearby3_name"),
                    nearby3Category = obj.optString("nearby3_category")
                )
            )
        }

        adapter.notifyDataSetChanged()

        if (savedItems.isEmpty()) {
            binding.recyclerSaved.visibility = View.GONE
            binding.emptyState.visibility = View.VISIBLE
        } else {
            binding.recyclerSaved.visibility = View.VISIBLE
            binding.emptyState.visibility = View.GONE
        }
    }

    private fun setupClickListeners() {
        binding.navHome.setOnClickListener {
            startActivity(Intent(this, HomeActivity::class.java))
            finish()
        }

        binding.navCamera.setOnClickListener {
            openCamera()
        }
    }

    private fun openCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchCamera()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchCamera() {
        val imageDir = File(cacheDir, "images")
        imageDir.mkdirs()
        val imageFile = File(imageDir, "capture_${System.currentTimeMillis()}.jpg")
        photoUri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", imageFile)
        takePicture.launch(photoUri!!)
    }

    inner class SavedPhotosAdapter(
        private val items: List<SavedLandmark>,
        private val onClick: (SavedLandmark) -> Unit
    ) : RecyclerView.Adapter<SavedPhotosAdapter.ViewHolder>() {

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val imgSaved: ImageView = itemView.findViewById(R.id.imgSaved)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_saved_photo, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]

            // Alternate heights for masonry effect
            val heightDp = if (position % 3 == 0) 200 else if (position % 3 == 1) 150 else 180
            val heightPx = (heightDp * holder.itemView.resources.displayMetrics.density).toInt()
            holder.imgSaved.layoutParams.height = heightPx

            Glide.with(holder.itemView.context)
                .load(Uri.parse(item.photoUri))
                .centerCrop()
                .into(holder.imgSaved)

            holder.itemView.setOnClickListener { onClick(item) }
        }

        override fun getItemCount() = items.size
    }
}
