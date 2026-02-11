package com.example.recognizeai

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.example.recognizeai.databinding.ActivityCameraBinding
import java.io.File

class CameraActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraBinding
    private var photoUri: Uri? = null

    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && photoUri != null) {
            val intent = Intent(this, AnalyzingActivity::class.java)
            intent.putExtra("photo_uri", photoUri.toString())
            startActivity(intent)
            finish()
        }
    }

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val intent = Intent(this, AnalyzingActivity::class.java)
            intent.putExtra("photo_uri", uri.toString())
            startActivity(intent)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadImages()
        setupClickListeners()
    }

    private fun loadImages() {
        Glide.with(this)
            .load("https://lh3.googleusercontent.com/aida-public/AB6AXuBPPjdUmoidMGut4ZivsU1EYUt5gdB0hP6coo4ySRWZEUnav0srJhwFZGS_7LOZSAzViJFaYJ5htcB5Z5tQb2FIY1maAW_SXZ_wMJ8yyPJUn-A2Gsqb8md5y6YFS3QnjEUEXPYntKtgrautJn5dWmaSnQPGy_-11yZ02xiDhTz8hByrzLXHgSsexR1K9h154R1Do06Tc9y0qwHWPZscgqDUpxDjWYcnvoe22pHGud4iBmsRdcR588sSvdbbA5RZOLAHCO6zrAMIon4")
            .centerCrop()
            .into(binding.imgCameraPreview)

        val cornerRadius = (12 * resources.displayMetrics.density).toInt()
        Glide.with(this)
            .load("https://lh3.googleusercontent.com/aida-public/AB6AXuAk4qdrNvqi1HUyPnrZijZC3DPjzjwyJSD0N6FvOSyNmW6pDlJI7TMKrj8uxgX3aNZOYc3GT9UIXst6rRYwXfQkau6Wm-dEC-ereaVuS666-sFs2Lx1ovbXITMrUyUa-wKYXLzNhDDqqa6EtmASNCVmx0Oy6iZFoSNqrFtcr0yq7gNyexRYjhiEEdO7M-56j-IKv0G4qj1MOnbJoacELVCs_P4C6Fzx_5fiRpJjHZYImIrjkkpsKEb6WUEpHBppuHy7qQJ3DGw5cN8")
            .transform(CenterCrop(), RoundedCorners(cornerRadius))
            .into(binding.imgGalleryPreview)
    }

    private fun createPhotoUri(): Uri {
        val imageDir = File(cacheDir, "images")
        imageDir.mkdirs()
        val imageFile = File(imageDir, "photo_${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(this, "${packageName}.fileprovider", imageFile)
    }

    private fun setupClickListeners() {
        binding.btnClose.setOnClickListener {
            finish()
        }

        binding.btnCapture.setOnClickListener {
            photoUri = createPhotoUri()
            takePictureLauncher.launch(photoUri!!)
        }

        binding.btnGallery.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }
    }
}
