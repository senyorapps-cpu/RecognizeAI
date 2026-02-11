package com.example.recognizeai

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.bumptech.glide.Glide
import com.example.recognizeai.databinding.ActivityLandmarkDetailBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class LandmarkDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLandmarkDetailBinding
    private var currentRating = 0
    private lateinit var stars: List<ImageView>
    private var retakePhotoUri: Uri? = null

    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && retakePhotoUri != null) {
            val intent = Intent(this, AnalyzingActivity::class.java)
            intent.putExtra("photo_uri", retakePhotoUri.toString())
            startActivity(intent)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityLandmarkDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.topBar) { view, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val extraPadding = (12 * resources.displayMetrics.density).toInt()
            view.setPadding(view.paddingLeft, statusBarHeight + extraPadding, view.paddingRight, view.paddingBottom)
            insets
        }

        loadHeroImage()
        populateFromIntent()
        setupClickListeners()
        setupStarRating()

        // Hide bottom action bar when opened from saved page or home last capture
        val fromSaved = intent.getBooleanExtra("from_saved", false)
        val fromHome = intent.getBooleanExtra("from_home", false)
        if (fromSaved || fromHome) {
            binding.bottomActionBar.visibility = View.GONE
        }
    }

    private fun populateFromIntent() {
        val name = intent.getStringExtra("name") ?: return
        val location = intent.getStringExtra("location") ?: ""
        val yearBuilt = intent.getStringExtra("year_built") ?: ""
        val status = intent.getStringExtra("status") ?: ""
        val architect = intent.getStringExtra("architect") ?: ""
        val capacity = intent.getStringExtra("capacity") ?: ""
        val narrativeP1 = intent.getStringExtra("narrative_p1") ?: ""
        val narrativeQuote = intent.getStringExtra("narrative_quote") ?: ""
        val narrativeP2 = intent.getStringExtra("narrative_p2") ?: ""

        binding.tvTitle.text = name
        binding.tvLocation.text = location
        binding.tvYearBuiltValue.text = yearBuilt
        binding.tvStatusValue.text = status
        binding.tvArchitectValue.text = architect
        binding.tvCapacityValue.text = capacity

        binding.tvNarrativeP1.text = narrativeP1
        binding.tvNarrativeQuote.text = "\u201C$narrativeQuote\u201D"
        binding.tvNarrativeP2.text = narrativeP2

    }

    private fun loadHeroImage() {
        val photoUri = intent.getStringExtra("photo_uri")
        if (photoUri != null) {
            Glide.with(this)
                .load(Uri.parse(photoUri))
                .centerCrop()
                .into(binding.imgHero)
        } else {
            Glide.with(this)
                .load("https://lh3.googleusercontent.com/aida-public/AB6AXuBPPjdUmoidMGut4ZivsU1EYUt5gdB0hP6coo4ySRWZEUnav0srJhwFZGS_7LOZSAzViJFaYJ5htcB5Z5tQb2FIY1maAW_SXZ_wMJ8yyPJUn-A2Gsqb8md5y6YFS3QnjEUEXPYntKtgrautJn5dWmaSnQPGy_-11yZ02xiDhTz8hByrzLXHgSsexR1K9h154R1Do06Tc9y0qwHWPZscgqDUpxDjWYcnvoe22pHGud4iBmsRdcR588sSvdbbA5RZOLAHCO6zrAMIon4")
                .centerCrop()
                .into(binding.imgHero)
        }
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private fun saveToJournal() {
        val serverId = intent.getLongExtra("server_id", -1L)

        // Update local storage
        updateLocalEntry(serverId) { it.put("is_saved", true) }
        Log.d("LandmarkDetail", "LOCAL SAVE: is_saved=true for server_id=$serverId")

        // Save rating + is_saved to server DB
        if (serverId > 0) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    // Mark as saved
                    val saveReq = Request.Builder()
                        .url("${SessionManager.BASE_URL}/api/landmarks/$serverId/save")
                        .put("{}".toRequestBody("application/json".toMediaType()))
                        .build()
                    httpClient.newCall(saveReq).execute().close()

                    // Also sync rating
                    if (currentRating > 0) {
                        val ratingJson = JSONObject().apply { put("rating", currentRating) }
                        val ratingReq = Request.Builder()
                            .url("${SessionManager.BASE_URL}/api/landmarks/$serverId/rating")
                            .put(ratingJson.toString().toRequestBody("application/json".toMediaType()))
                            .build()
                        httpClient.newCall(ratingReq).execute().close()
                    }
                } catch (e: Exception) {
                    Log.e("LandmarkDetail", "Failed to sync save to server", e)
                }
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var hintRunnable: Runnable? = null

    private fun setupStarRating() {
        stars = listOf(binding.star1, binding.star2, binding.star3, binding.star4, binding.star5)

        // Restore saved rating if opening from saved
        val savedRating = intent.getIntExtra("rating", 0)

        if (savedRating > 0) {
            // Opened from saved — show rating immediately, no entrance animation
            currentRating = savedRating
            updateStars(animated = false)
            binding.tvTapToRate.alpha = 0f
        } else {
            // Fresh result — hide stars for entrance animation
            for (star in stars) {
                star.alpha = 0f
                star.scaleX = 0f
                star.scaleY = 0f
                star.translationY = 40f
            }
        }

        for (i in stars.indices) {
            stars[i].setOnClickListener {
                val newRating = i + 1
                currentRating = if (currentRating == newRating) 0 else newRating
                updateStars(animated = true)
                // Hide prompt and stop hint once user rates
                if (currentRating > 0) {
                    cancelHintLoop()
                    binding.tvTapToRate.animate().alpha(0f).setDuration(200).start()
                } else {
                    // If cleared, show prompt again and restart hints
                    binding.tvTapToRate.animate().alpha(1f).setDuration(300).start()
                    scheduleHintWave(3000)
                }
                // Persist rating update to saved landmarks
                persistRating()
            }
        }

        // Only play entrance animation for fresh results (not from saved)
        if (savedRating == 0) {
            startStarEntranceAnimation()
        }
    }

    /** Sync rating to server DB and local storage */
    private fun persistRating() {
        val serverId = intent.getLongExtra("server_id", -1L)

        // Always update local storage (works for guest users too)
        updateLocalEntry(serverId) { it.put("rating", currentRating) }
        Log.d("LandmarkDetail", "LOCAL SAVE: rating=$currentRating for server_id=$serverId")

        if (serverId <= 0) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val json = JSONObject().apply { put("rating", currentRating) }
                val request = Request.Builder()
                    .url("${SessionManager.BASE_URL}/api/landmarks/$serverId/rating")
                    .put(json.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                httpClient.newCall(request).execute().close()
            } catch (e: Exception) {
                Log.e("LandmarkDetail", "Failed to sync rating", e)
            }
        }
    }

    /** Update a local landmark entry in SharedPreferences by server_id or photo_uri */
    private fun updateLocalEntry(serverId: Long, mutate: (JSONObject) -> Unit) {
        val prefs = getSharedPreferences("recognizeai_landmarks", MODE_PRIVATE)
        val arr = JSONArray(prefs.getString("landmarks", "[]"))
        val photoUri = intent.getStringExtra("photo_uri") ?: ""

        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val matchById = serverId > 0 && obj.optLong("server_id", -1L) == serverId
            val matchByUri = photoUri.isNotEmpty() && obj.optString("photo_uri") == photoUri
            if (matchById || matchByUri) {
                mutate(obj)
                break
            }
        }
        prefs.edit().putString("landmarks", arr.toString()).apply()
    }

    /** Phase 1: Stars fly in one-by-one from below with bounce */
    private fun startStarEntranceAnimation() {
        handler.postDelayed({
            if (isFinishing) return@postDelayed

            for (i in stars.indices) {
                val star = stars[i]
                val delay = i * 100L

                // Fly in: scale 0→1, translateY 40→0, alpha 0→1
                val scaleX = ObjectAnimator.ofFloat(star, View.SCALE_X, 0f, 1f)
                val scaleY = ObjectAnimator.ofFloat(star, View.SCALE_Y, 0f, 1f)
                val transY = ObjectAnimator.ofFloat(star, View.TRANSLATION_Y, 40f, 0f)
                val alpha = ObjectAnimator.ofFloat(star, View.ALPHA, 0f, 1f)

                AnimatorSet().apply {
                    playTogether(scaleX, scaleY, transY, alpha)
                    duration = 500
                    startDelay = delay
                    interpolator = OvershootInterpolator(3f)
                    start()
                }
            }

            // Phase 2: After stars land, do the shimmer wave
            handler.postDelayed({
                if (isFinishing || currentRating > 0) return@postDelayed
                startShimmerWave()
            }, 800)
        }, 600)
    }

    /** Phase 2: Gold shimmer wave — each star briefly fills gold then fades back */
    private fun startShimmerWave() {
        for (i in stars.indices) {
            val star = stars[i]
            val delay = i * 150L

            handler.postDelayed({
                if (isFinishing || currentRating > 0) return@postDelayed

                // Temporarily show filled star
                star.setImageResource(R.drawable.ic_star_filled)

                // Scale up slightly
                val scaleUp = AnimatorSet().apply {
                    playTogether(
                        ObjectAnimator.ofFloat(star, View.SCALE_X, 1f, 1.3f),
                        ObjectAnimator.ofFloat(star, View.SCALE_Y, 1f, 1.3f)
                    )
                    duration = 200
                    interpolator = DecelerateInterpolator()
                }

                // Scale back down + revert to outline
                val scaleDown = AnimatorSet().apply {
                    playTogether(
                        ObjectAnimator.ofFloat(star, View.SCALE_X, 1.3f, 1f),
                        ObjectAnimator.ofFloat(star, View.SCALE_Y, 1.3f, 1f)
                    )
                    duration = 300
                    interpolator = AccelerateDecelerateInterpolator()
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            if (currentRating == 0) {
                                star.setImageResource(R.drawable.ic_star_outline)
                            }
                        }
                    })
                }

                AnimatorSet().apply {
                    playSequentially(scaleUp, scaleDown)
                    start()
                }
            }, delay)
        }

        // Phase 3: After shimmer, fade in the "Tap to rate" prompt
        handler.postDelayed({
            if (isFinishing || currentRating > 0) return@postDelayed
            showTapToRatePrompt()
        }, 5 * 150L + 600L)
    }

    /** Phase 3: Animate in the "Tap to rate this place" text */
    private fun showTapToRatePrompt() {
        binding.tvTapToRate.translationY = 10f
        binding.tvTapToRate.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(400)
            .setInterpolator(DecelerateInterpolator())
            .start()

        // Schedule repeating subtle hint wave
        scheduleHintWave(3500)
    }

    /** Repeating gentle pulse wave if user hasn't rated yet */
    private fun scheduleHintWave(delayMs: Long) {
        cancelHintLoop()
        hintRunnable = object : Runnable {
            override fun run() {
                if (isFinishing || currentRating > 0) return
                // Gentle wave: scale 1→1.2→1 with alpha flicker
                for (i in stars.indices) {
                    val star = stars[i]
                    handler.postDelayed({
                        if (isFinishing || currentRating > 0) return@postDelayed
                        val scaleX = ObjectAnimator.ofFloat(star, View.SCALE_X, 1f, 1.25f, 1f)
                        val scaleY = ObjectAnimator.ofFloat(star, View.SCALE_Y, 1f, 1.25f, 1f)
                        AnimatorSet().apply {
                            playTogether(scaleX, scaleY)
                            duration = 350
                            interpolator = OvershootInterpolator(2f)
                            start()
                        }
                    }, i * 100L)
                }
                // Repeat every 4 seconds
                handler.postDelayed(this, 4000)
            }
        }
        handler.postDelayed(hintRunnable!!, delayMs)
    }

    private fun cancelHintLoop() {
        hintRunnable?.let { handler.removeCallbacks(it) }
        hintRunnable = null
    }

    private val ratingLabels = arrayOf("", "Poor", "Fair", "Good", "Great", "Amazing")

    private fun updateStars(animated: Boolean = false) {
        for (i in stars.indices) {
            val star = stars[i]
            if (i < currentRating) {
                star.setImageResource(R.drawable.ic_star_filled)
                if (animated) {
                    // Bounce-in effect for each filled star with stagger
                    star.scaleX = 0.5f
                    star.scaleY = 0.5f
                    star.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(300)
                        .setStartDelay(i * 50L)
                        .setInterpolator(OvershootInterpolator(3f))
                        .start()
                }
            } else {
                star.setImageResource(R.drawable.ic_star_outline)
                if (animated) {
                    star.scaleX = 1f
                    star.scaleY = 1f
                }
            }
        }
        if (currentRating > 0) {
            binding.tvRatingLabel.text = ratingLabels[currentRating]
            binding.tvRatingLabel.visibility = View.VISIBLE
            if (animated) {
                binding.tvRatingLabel.alpha = 0f
                binding.tvRatingLabel.translationY = 10f
                binding.tvRatingLabel.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(250)
                    .setStartDelay(currentRating * 50L)
                    .start()
            }
        } else {
            binding.tvRatingLabel.visibility = View.GONE
        }
    }

    override fun onDestroy() {
        cancelHintLoop()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun setupClickListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnRetake.setOnClickListener {
            val imageDir = File(cacheDir, "images")
            imageDir.mkdirs()
            val imageFile = File(imageDir, "photo_${System.currentTimeMillis()}.jpg")
            retakePhotoUri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", imageFile)
            takePictureLauncher.launch(retakePhotoUri!!)
        }

        binding.btnSaveJournal.setOnClickListener {
            saveToJournal()
            Toast.makeText(this, "Saved to Journal", Toast.LENGTH_SHORT).show()
            val intent = Intent(this, MainActivity::class.java)
            intent.putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_SAVED)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
            finish()
        }

    }
}
