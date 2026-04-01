package com.example.recognizeai

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.example.recognizeai.databinding.ActivityArCameraBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.bumptech.glide.Glide
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class ARCameraActivity : BaseActivity() {

    private lateinit var binding: ActivityArCameraBinding

    private var imageCapture: ImageCapture? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isAnalyzing = false
    private var scanLineAnimator: ObjectAnimator? = null

    // Last detected landmark data for "View Full Details"
    private var lastDetectedName = ""
    private var lastDetectedLocation = ""
    private var lastDetectedYear = ""

    // Cache last result to skip redundant Gemini calls
    private var cachedResultName = ""
    private var cacheTimestamp = 0L
    private val CACHE_TTL_MS = 8000L

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .build()

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission is required for AR Mode", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val session = SessionManager(this)

        binding = ActivityArCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Draw behind system bars
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val dp16 = (16 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // Top bar: replace hardcoded 48dp with real status bar height
            binding.topBar.setPadding(dp16, systemBars.top + dp16, dp16, dp16)
            // Bottom card: add navigation bar height to existing 16dp padding
            binding.overlayCard.setPadding(dp16, dp16, dp16, dp16 + systemBars.bottom)
            insets
        }

        binding.tvArPlan.text = session.planDisplayName

        setupClickListeners()

        if (hasCameraPermission()) {
            startCamera()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.cameraPreview.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetResolution(android.util.Size(640, 480))
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
                startScanLineAnimation()
                scheduleNextScan()
            } catch (e: Exception) {
                Log.e("ARCamera", "Camera binding failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private val scanRunnable = object : Runnable {
        override fun run() {
            if (!isFinishing && !isAnalyzing) {
                captureAndAnalyze()
            }
            handler.postDelayed(this, 1500)
        }
    }

    private fun scheduleNextScan() {
        handler.post(scanRunnable)
    }

    private fun captureAndAnalyze() {
        val capture = imageCapture ?: return
        // If we detected something recently, skip the Gemini call and reuse cached result
        if (cachedResultName.isNotEmpty() && System.currentTimeMillis() - cacheTimestamp < CACHE_TTL_MS) {
            return
        }
        isAnalyzing = true
        setHintState(analyzing = true)

        capture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bytes = imageProxyToBytes(image)
                    image.close()
                    sendToServer(bytes)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("ARCamera", "Capture failed", exception)
                    isAnalyzing = false
                    setHintState(analyzing = false)
                }
            }
        )
    }

    private fun setHintState(analyzing: Boolean) {
        if (analyzing) {
            binding.tvArStatus.text = getString(R.string.ar_scanning)
            binding.tvScanHint.text = getString(R.string.ar_hold_still)
            // Speed up sweep line while analyzing
            scanLineAnimator?.duration = 700
        } else {
            binding.tvArStatus.text = getString(R.string.ar_scanning)
            binding.tvScanHint.text = getString(R.string.ar_point_at_landmark)
            // Slow back to idle speed
            scanLineAnimator?.duration = 1800
        }
    }

    private fun imageProxyToBytes(image: ImageProxy): ByteArray {
        val bitmap = image.toBitmap()
        val rotated = rotateBitmap(bitmap, image.imageInfo.rotationDegrees.toFloat())
        val output = ByteArrayOutputStream()
        rotated.compress(Bitmap.CompressFormat.JPEG, 60, output)
        if (rotated !== bitmap) rotated.recycle()
        bitmap.recycle()
        return output.toByteArray()
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        if (degrees == 0f) return bitmap
        val matrix = android.graphics.Matrix()
        matrix.postRotate(degrees)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun sendToServer(imageBytes: ByteArray) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val session = SessionManager(this@ARCameraActivity)
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "image", "frame.jpg",
                        imageBytes.toRequestBody("image/jpeg".toMediaType())
                    )
                    .addFormDataPart("device_id", session.deviceId)
                    .build()

                val request = Request.Builder()
                    .url("${SessionManager.BASE_URL}/api/ar-identify")
                    .post(body)
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: "{}"
                val json = JSONObject(responseBody)

                withContext(Dispatchers.Main) {
                    if (json.optBoolean("not_a_landmark", true)) {
                        hideOverlay()
                        showNoLandmarkHint()
                    } else {
                        val name = json.optString("name", "")
                        val location = json.optString("location", "")
                        val yearBuilt = json.optString("year_built", "")
                        showOverlay(name, location, yearBuilt)
                    }
                    isAnalyzing = false
                    setHintState(analyzing = false)
                }
            } catch (e: Exception) {
                Log.e("ARCamera", "Analysis error", e)
                withContext(Dispatchers.Main) {
                    isAnalyzing = false
                    setHintState(analyzing = false)
                }
            }
        }
    }

    private fun loadThumbnail(name: String, location: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val query = name + if (location.isNotEmpty()) " ${location.split(",")[0].trim()}" else ""
                val searchUrl = "https://en.wikipedia.org/w/api.php?action=query&titles=${
                    java.net.URLEncoder.encode(name, "UTF-8")
                }&prop=pageimages&format=json&pithumbsize=160&redirects=1"
                val req = Request.Builder().url(searchUrl)
                    .header("User-Agent", "SightAI/1.0")
                    .get().build()
                val resp = client.newCall(req).execute()
                val body = resp.body?.string() ?: return@launch
                val pages = JSONObject(body).optJSONObject("query")?.optJSONObject("pages") ?: return@launch
                val page = pages.keys().asSequence().firstOrNull()?.let { pages.optJSONObject(it) } ?: return@launch
                val imageUrl = page.optJSONObject("thumbnail")?.optString("source") ?: return@launch
                withContext(Dispatchers.Main) {
                    if (!isFinishing && binding.overlayCard.visibility == android.view.View.VISIBLE) {
                        Glide.with(this@ARCameraActivity)
                            .load(imageUrl)
                            .centerCrop()
                            .into(binding.imgArThumbnail)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun showOverlay(name: String, location: String, yearBuilt: String) {
        lastDetectedName = name
        lastDetectedLocation = location
        lastDetectedYear = yearBuilt
        cachedResultName = name
        cacheTimestamp = System.currentTimeMillis()

        binding.imgArThumbnail.setImageDrawable(null)
        loadThumbnail(name, location)
        binding.tvArName.text = name
        binding.tvArLocation.text = if (yearBuilt.isNotEmpty()) "$location · $yearBuilt" else location
        binding.tvArStatus.text = getString(R.string.ar_landmark_detected)
        binding.tvScanHint.text = getString(R.string.ar_landmark_detected)

        if (binding.overlayCard.visibility != View.VISIBLE) {
            binding.overlayCard.translationY = binding.overlayCard.height.toFloat().coerceAtLeast(200f)
            binding.overlayCard.alpha = 0f
            binding.overlayCard.visibility = View.VISIBLE
            binding.overlayCard.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(300)
                .start()
        }

        handler.postDelayed({
            if (!isFinishing) setHintState(analyzing = false)
        }, 2000)
    }

    private fun hideOverlay() {
        cachedResultName = ""
        if (binding.overlayCard.visibility == View.VISIBLE) {
            binding.overlayCard.animate()
                .translationY(200f)
                .alpha(0f)
                .setDuration(250)
                .withEndAction { binding.overlayCard.visibility = View.GONE }
                .start()
        }
    }

    private fun showNoLandmarkHint() {
        binding.tvScanHint.text = getString(R.string.ar_no_landmark)
        handler.postDelayed({
            if (!isFinishing) binding.tvScanHint.text = getString(R.string.ar_point_at_landmark)
        }, 1500)
    }

    private fun startScanLineAnimation() {
        // Animate scan line from top to bottom of the 220dp frame
        val frameHeightPx = (220 * resources.displayMetrics.density)
        scanLineAnimator = ObjectAnimator.ofFloat(binding.scanLine, View.TRANSLATION_Y, 0f, frameHeightPx).apply {
            duration = 1800
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun setupClickListeners() {
        binding.btnClose.setOnClickListener { finish() }

        binding.btnViewDetails.setOnClickListener {
            if (lastDetectedName.isNotEmpty()) {
                val intent = Intent(this, LandmarkDetailActivity::class.java).apply {
                    putExtra("name", lastDetectedName)
                    putExtra("location", lastDetectedLocation)
                    putExtra("year_built", lastDetectedYear)
                    putExtra("photo_uri", "")
                    putExtra("server_id", -1L)
                    putExtra("status", "")
                    putExtra("architect", "")
                    putExtra("capacity", "")
                    putExtra("narrative_p1", "")
                    putExtra("narrative_quote", "")
                    putExtra("narrative_p2", "")
                    putExtra("from_ar", true)
                }
                startActivity(intent)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        scanLineAnimator?.cancel()
    }
}
