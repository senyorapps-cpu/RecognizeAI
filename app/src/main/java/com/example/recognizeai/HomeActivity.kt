package com.example.recognizeai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.example.recognizeai.databinding.ActivityHomeBinding
import java.io.File

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private var photoUri: Uri? = null

    private data class TopPlace(
        val name: String,
        val location: String,
        val imageUrl: String
    )

    private val topPlaces = listOf(
        TopPlace("Eiffel Tower", "Paris, France",
            "https://lh3.googleusercontent.com/aida-public/AB6AXuA4lvi2KwB33CRmJFwjY-Ae0wK56AAyyrjno0Ngpu1tkNjFCC8slul18FTkwL34TmmTokinOFQp_IduKP60L05cbht7YH9WfJrUoT-PukOM996hjuQxGg1N04ru66ZACGKlWbGyTt_Vm-MzRC4oJpzWgM6bwUaqCYdsnGT5ptUoJheRNSwpJf-w0LUbhWeMBh_wlqXwtGFe57sztHytwEluy_LnEHKQJxTc2eI5yrydJQJGaySIE9x5SI60b6b-SyXtpdZmtFMb9QY"),
        TopPlace("Colosseum", "Rome, Italy",
            "https://lh3.googleusercontent.com/aida-public/AB6AXuBPPjdUmoidMGut4ZivsU1EYUt5gdB0hP6coo4ySRWZEUnav0srJhwFZGS_7LOZSAzViJFaYJ5htcB5Z5tQb2FIY1maAW_SXZ_wMJ8yyPJUn-A2Gsqb8md5y6YFS3QnjEUEXPYntKtgrautJn5dWmaSnQPGy_-11yZ02xiDhTz8hByrzLXHgSsexR1K9h154R1Do06Tc9y0qwHWPZscgqDUpxDjWYcnvoe22pHGud4iBmsRdcR588sSvdbbA5RZOLAHCO6zrAMIon4"),
        TopPlace("Grand Canal", "Venice, Italy",
            "https://lh3.googleusercontent.com/aida-public/AB6AXuC_cGImP-6Kw2nqm9rUxPVid8GSqGUZhGjW6rDtKlASrDQpUC2PlXTbSswUScyn5CYGGvhw6Azp2Xf6UH3t4s8aOckTF-8sDjHVgyRAByqefXC72CqRKVQMiYR4vcJpZN5Js5uuJjKxMoFkVmBtP4C9gxmYkvZnSx6pQooUZ4raikNqTq-dNV3F-BVEGsnID-5g4pmOJ1iqQXIijF9FW2a9RLY5f0gZoicYvpxAb4zYjm304h6SEBfNl5AyjQca7SKFRA2IutXfl6M"),
        TopPlace("Big Ben", "London, UK",
            "https://lh3.googleusercontent.com/aida-public/AB6AXuDYPbG8yJ0lw3-w4Zgoo8BAQaHNPR0YUYk3AIfhc4XBIj3_C0A8pbsrf19NTk6bqFftX_5tjCikQe7BC9GdMDsYrhVd4-NA-UXU1fiIL-Z91D8m7cqPVhhi1GsJS3RclKsubGgxEFXEDhxjphhhR7BDwNlKcLZqnMYCMRDnJhbEfzdvQUwLqGeTT7eHePFzF81fX_Rj29BXYvUgdeSfy_NvxbKx0cbKvTsTNxbRRCXXQp9F8JPN-qgO9XJzDQ2hBVq4F8N4ZmnSOdQ"),
        TopPlace("Santorini", "Greece",
            "https://lh3.googleusercontent.com/aida-public/AB6AXuARuqCvlgoE_TGjoCzGU_3WxKgBvLslhvcXDphjI0krM22T-hdx013GKUI45LM5t9vda2pDHmIgmRwlYZTy1mF2V50dPlFSacl9UcAbR2uNJjZ3ucW0R8MQuFQBJ6ZiG5K-19J5HPiDFi9afHmEO2Tk_aOKo3mDoDOJQw0aTc5uJnjK_adyTy4GrFbMl1z-QEIPoPP1jqVDEmVi00NBt_F7-qPnmL1-6KeygLpDKUucfxEdyL11XJUaSnMtiiF9fN9s8dbNkKfSBa8"),
        TopPlace("Roman Forum", "Rome, Italy",
            "https://lh3.googleusercontent.com/aida-public/AB6AXuAk4qdrNvqi1HUyPnrZijZC3DPjzjwyJSD0N6FvOSyNmW6pDlJI7TMKrj8uxgX3aNZOYc3GT9UIXst6rRYwXfQkau6Wm-dEC-ereaVuS666-sFs2Lx1ovbXITMrUyUa-wKYXLzNhDDqqa6EtmASNCVmx0Oy6iZFoSNqrFtcr0yq7gNyexRYjhiEEdO7M-56j-IKv0G4qj1MOnbJoacELVCs_P4C6Fzx_5fiRpJjHZYImIrjkkpsKEb6WUEpHBppuHy7qQJ3DGw5cN8"),
        TopPlace("Kinkaku-ji", "Kyoto, Japan",
            "https://lh3.googleusercontent.com/aida-public/AB6AXuBVdcd0_s5xSTzsqqcAOdaffkO1IjkYYVPK0vIDp-pObErlxhk5Gty4sSKa1KpN7b_kBjUyEkLBMKsuhGTB2aQdKFw6W5h81JhPAcck_7h3BJAUNOTvbXya-MxxWfnDXD5RUr0YvP0JKcpf2uMZgrxFgnd4mP0fC-wdy7g-IRrBAobPg-Mh5nfsiBxvlC20JWKR8pkBdTZG8eHODIsgJRlndrzfIFAAPtO0a5DOi8vGlx92AWNecbTEze2OqoqExoUAjBpB1dkxIuo"),
        TopPlace("Palatine Hill", "Rome, Italy",
            "https://lh3.googleusercontent.com/aida-public/AB6AXuDS1U77zQ_7hHRT8ljWCwCmpyz6DlN-LMwV2Kd4wRJt9z27i8_GkAd5yX3qNKWfZuDoB7FO-hHFg1xM8TS2jiJHLpn45Cs59ewwBY7aFKiw51tVKWjXdhXWw23dskxzhw5mqf0VXJwQr9r06MtvHeAc4ovpQXxJKonMnJMG_0DS1_nL_rVpoyMKZFeOaimcb544iOwz_5pCVur_k3LptDIr4eyQUQdmMcEbXfF2Peipzoi4DkaeR7sh7gPmS3PGuW_a38p4E3fLNSg")
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
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadLastCapture()
        loadTopPlaces()
        setupClickListeners()
    }

    private fun loadLastCapture() {
        val cornerRadius = (20 * resources.displayMetrics.density).toInt()
        Glide.with(this)
            .load("https://lh3.googleusercontent.com/aida-public/AB6AXuBPPjdUmoidMGut4ZivsU1EYUt5gdB0hP6coo4ySRWZEUnav0srJhwFZGS_7LOZSAzViJFaYJ5htcB5Z5tQb2FIY1maAW_SXZ_wMJ8yyPJUn-A2Gsqb8md5y6YFS3QnjEUEXPYntKtgrautJn5dWmaSnQPGy_-11yZ02xiDhTz8hByrzLXHgSsexR1K9h154R1Do06Tc9y0qwHWPZscgqDUpxDjWYcnvoe22pHGud4iBmsRdcR588sSvdbbA5RZOLAHCO6zrAMIon4")
            .transform(CenterCrop(), RoundedCorners(cornerRadius))
            .into(binding.imgLastCapture)
    }

    private fun loadTopPlaces() {
        val cornerRadius = (20 * resources.displayMetrics.density).toInt()
        val inflater = LayoutInflater.from(this)

        for (place in topPlaces) {
            val itemView = inflater.inflate(R.layout.item_top_place, binding.topPlacesContainer, false)

            val imgPlace = itemView.findViewById<ImageView>(R.id.imgPlace)
            val txtName = itemView.findViewById<TextView>(R.id.txtPlaceName)
            val txtLocation = itemView.findViewById<TextView>(R.id.txtPlaceLocation)

            txtName.text = place.name
            txtLocation.text = place.location

            Glide.with(this)
                .load(place.imageUrl)
                .transform(CenterCrop(), RoundedCorners(cornerRadius))
                .into(imgPlace)

            binding.topPlacesContainer.addView(itemView)
        }
    }

    private fun setupClickListeners() {
        binding.navCamera.setOnClickListener {
            openCamera()
        }

        binding.navSaved.setOnClickListener {
            startActivity(Intent(this, SavedPhotosActivity::class.java))
        }

        binding.cardLastCapture.setOnClickListener {
            startActivity(Intent(this, LandmarkDetailActivity::class.java))
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
}
