package com.example.recognizeai

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.PopupWindow
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
import com.example.recognizeai.databinding.DialogShareBottomSheetBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit

class LandmarkDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLandmarkDetailBinding
    private var currentRating = 0
    private lateinit var stars: List<ImageView>
    private var retakePhotoUri: Uri? = null
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var isSpeaking = false

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
        setupTextToSpeech()

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

    private fun setupTextToSpeech() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isTtsReady = true
                // Set language based on the language the text was analyzed in
                val langCode = intent.getStringExtra("language") ?: LocaleHelper.getCurrentLanguageCode()
                val locale = when (langCode) {
                    "ru" -> Locale("ru")
                    "es" -> Locale("es")
                    "fr" -> Locale.FRENCH
                    "de" -> Locale.GERMAN
                    "pt" -> Locale("pt")
                    else -> Locale.US
                }
                tts?.language = locale
            }
        }

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                runOnUiThread {
                    isSpeaking = false
                    binding.imgPlayIcon.setImageResource(R.drawable.ic_play)
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                runOnUiThread {
                    isSpeaking = false
                    binding.imgPlayIcon.setImageResource(R.drawable.ic_play)
                }
            }
        })

        binding.btnPlayAudio.setOnClickListener {
            if (!isTtsReady) {
                Toast.makeText(this, "Audio not ready yet", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (isSpeaking) {
                tts?.stop()
                isSpeaking = false
                binding.imgPlayIcon.setImageResource(R.drawable.ic_play)
            } else {
                val name = intent.getStringExtra("name") ?: ""
                val p1 = intent.getStringExtra("narrative_p1") ?: ""
                val quote = intent.getStringExtra("narrative_quote") ?: ""
                val p2 = intent.getStringExtra("narrative_p2") ?: ""
                val fullText = "$name. $p1 $quote $p2"

                if (fullText.isBlank()) return@setOnClickListener

                val params = Bundle()
                params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "landmark_tts")
                tts?.speak(fullText, TextToSpeech.QUEUE_FLUSH, params, "landmark_tts")
                isSpeaking = true
                binding.imgPlayIcon.setImageResource(R.drawable.ic_pause)
            }
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
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

        binding.btnShare.setOnClickListener {
            showShareSheet()
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

    private fun loadPhotoBitmap(): Bitmap? {
        val photoUriStr = intent.getStringExtra("photo_uri") ?: return null
        return try {
            if (photoUriStr.startsWith("http")) {
                val request = Request.Builder().url(photoUriStr).build()
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        response.body?.byteStream()?.use { BitmapFactory.decodeStream(it) }
                    } else null
                }
            } else {
                val uri = Uri.parse(photoUriStr)
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            }
        } catch (e: Exception) {
            Log.e("LandmarkDetail", "Failed to load photo bitmap", e)
            null
        }
    }

    private fun createShareCard(photo: Bitmap): Bitmap {
        val cardW = 1080
        val cardH = 1920
        val card = Bitmap.createBitmap(cardW, cardH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(card)

        // Design colors
        val primaryBlue = Color.parseColor("#25AFF4")
        val deepTeal = Color.parseColor("#004D4D")
        val slate900 = Color.parseColor("#0F172A")
        val slate500 = Color.parseColor("#64748B")
        val slate400 = Color.parseColor("#94A3B8")
        val yellow400 = Color.parseColor("#FACC15")
        val slate100 = Color.parseColor("#F1F5F9")
        val goldText = Color.parseColor("#92700A")
        val goldBorder = Color.parseColor("#DFC623")

        // Fonts
        val fontBold = try {
            Typeface.createFromAsset(assets, "res/font/inter_bold.ttf")
        } catch (_: Exception) { Typeface.create("sans-serif", Typeface.BOLD) }
        val fontSemibold = try {
            Typeface.createFromAsset(assets, "res/font/inter_semibold.ttf")
        } catch (_: Exception) { Typeface.create("sans-serif-medium", Typeface.BOLD) }
        val fontMedium = try {
            Typeface.createFromAsset(assets, "res/font/inter_medium.ttf")
        } catch (_: Exception) { Typeface.create("sans-serif", Typeface.NORMAL) }
        val fontRegular = try {
            Typeface.createFromAsset(assets, "res/font/inter.ttf")
        } catch (_: Exception) { Typeface.create("sans-serif", Typeface.NORMAL) }

        canvas.drawColor(Color.WHITE)

        // ── Top: Photo (45%) ──
        val imageH = (cardH * 0.45f).toInt()
        val imgScale = maxOf(cardW.toFloat() / photo.width, imageH.toFloat() / photo.height)
        val sW = (photo.width * imgScale).toInt()
        val sH = (photo.height * imgScale).toInt()
        val scaledBmp = Bitmap.createScaledBitmap(photo, sW, sH, true)
        val cropX = ((sW - cardW) / 2).coerceAtLeast(0)
        val cropY = ((sH - imageH) / 2).coerceAtLeast(0)
        val croppedBmp = Bitmap.createBitmap(scaledBmp, cropX, cropY,
            cardW.coerceAtMost(scaledBmp.width - cropX),
            imageH.coerceAtMost(scaledBmp.height - cropY))
        canvas.drawBitmap(croppedBmp, null, RectF(0f, 0f, cardW.toFloat(), imageH.toFloat()), null)


        // ── Bottom info section ──
        val pad = 44f
        val innerW = cardW - pad * 2
        var y = imageH.toFloat() + 48f

        // Name
        val name = intent.getStringExtra("name") ?: "Discovery"
        val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = slate900; textSize = 58f; typeface = fontBold
        }
        for (line in wrapText(name, namePaint, innerW).take(2)) {
            y += 66f
            canvas.drawText(line, pad, y, namePaint)
        }
        y += 12f

        // Location
        val location = intent.getStringExtra("location") ?: ""
        if (location.isNotEmpty()) {
            y += 34f
            val locPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = slate500; textSize = 32f; typeface = fontMedium
            }
            var locStr = "\uD83D\uDCCD $location"
            while (locPaint.measureText(locStr) > innerW && locStr.length > 20) {
                locStr = locStr.dropLast(2) + "\u2026"
            }
            canvas.drawText(locStr, pad, y, locPaint)
        }
        y += 12f

        // Stars
        y += 40f
        val rating = currentRating.coerceIn(0, 5)
        val filledPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = yellow400; textSize = 42f }
        val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E2E8F0"); textSize = 42f }
        var sx = pad
        for (i in 0 until 5) {
            canvas.drawText("\u2605", sx, y, if (i < rating) filledPaint else emptyPaint)
            sx += 46f
        }
        if (rating > 0) {
            canvas.drawText("$rating.0", sx + 8f, y - 2f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = slate400; textSize = 30f; typeface = fontSemibold })
        }

        // ── Info grid (2x2) ──
        val yearBuilt = intent.getStringExtra("year_built") ?: ""
        val status = intent.getStringExtra("status") ?: ""
        val architect = intent.getStringExtra("architect") ?: ""
        val capacity = intent.getStringExtra("capacity") ?: ""

        val gridItems = listOf(
            "YEAR BUILT" to yearBuilt,
            "STATUS" to status,
            "ARCHITECT" to architect,
            "CAPACITY" to capacity
        ).filter { it.second.isNotEmpty() }

        if (gridItems.isNotEmpty()) {
            y += 36f
            val colW = innerW / 2
            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = slate400; textSize = 24f; typeface = fontBold; letterSpacing = 0.15f
            }
            val valPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = slate900; textSize = 32f; typeface = fontSemibold
            }
            val maxValW = colW - 12f

            for (i in gridItems.indices step 2) {
                // Label row
                canvas.drawText(gridItems[i].first, pad, y, labelPaint)
                if (i + 1 < gridItems.size) {
                    canvas.drawText(gridItems[i + 1].first, pad + colW, y, labelPaint)
                }
                y += 34f
                // Value row
                var val1 = gridItems[i].second
                while (valPaint.measureText(val1) > maxValW && val1.length > 8) val1 = val1.dropLast(2) + "\u2026"
                canvas.drawText(val1, pad, y, valPaint)
                if (i + 1 < gridItems.size) {
                    var val2 = gridItems[i + 1].second
                    while (valPaint.measureText(val2) > maxValW && val2.length > 8) val2 = val2.dropLast(2) + "\u2026"
                    canvas.drawText(val2, pad + colW, y, valPaint)
                }
                y += 44f
            }
        }

        // ── Divider ──
        y += 16f
        canvas.drawLine(pad, y, cardW - pad, y,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = slate100; strokeWidth = 2f })
        y += 28f

        // ── Narrative P1 ──
        val p1 = intent.getStringExtra("narrative_p1") ?: ""
        if (p1.isNotEmpty()) {
            val p1Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = deepTeal; textSize = 33f; typeface = fontRegular
            }
            for (line in wrapText(p1, p1Paint, innerW).take(3)) {
                canvas.drawText(line, pad, y, p1Paint)
                y += 44f
            }
            y += 12f
        }

        // ── Quote ──
        val quote = intent.getStringExtra("narrative_quote") ?: ""
        if (quote.isNotEmpty()) {
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = goldBorder; strokeWidth = 4f; strokeCap = Paint.Cap.ROUND
            }
            val quotePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = goldText; textSize = 30f
                typeface = Typeface.create(fontMedium, Typeface.ITALIC)
            }
            val quoteLines = wrapText("\u201C$quote\u201D", quotePaint, innerW - 28f)
            val quoteStartY = y
            for (line in quoteLines.take(2)) {
                canvas.drawText(line, pad + 24f, y, quotePaint)
                y += 40f
            }
            canvas.drawLine(pad + 4f, quoteStartY - 12f, pad + 4f, y - 10f, borderPaint)
            y += 14f
        }

        // ── Narrative P2 ──
        val p2 = intent.getStringExtra("narrative_p2") ?: ""
        if (p2.isNotEmpty()) {
            val p2Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = deepTeal; textSize = 33f; typeface = fontRegular
            }
            for (line in wrapText(p2, p2Paint, innerW).take(2)) {
                canvas.drawText(line, pad, y, p2Paint)
                y += 44f
            }
            y += 4f
        }

        // ── Footer ──
        val footerDivY = cardH - 90f
        canvas.drawLine(pad, footerDivY, cardW - pad, footerDivY,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = slate100; strokeWidth = 2f })
        val ftY = footerDivY + 40f
        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = slate400; textSize = 24f; typeface = fontBold; letterSpacing = 0.2f
        }
        val ftLabel = "TRAVELAI APP"
        val ftW = footerPaint.measureText(ftLabel)
        canvas.drawText(ftLabel, cardW - pad - ftW, ftY, footerPaint)
        val ibS = 38f
        val ibX = cardW - pad - ftW - ibS - 14f; val ibY = ftY - 24f
        canvas.drawRoundRect(RectF(ibX, ibY, ibX + ibS, ibY + ibS), 8f, 8f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = slate100 })
        val ticx = ibX + ibS / 2; val ticy = ibY + ibS / 2
        canvas.drawPath(android.graphics.Path().apply {
            moveTo(ticx - 6f, ticy - 5f); lineTo(ticx + 8f, ticy)
            lineTo(ticx - 6f, ticy + 5f); lineTo(ticx - 2f, ticy); close()
        }, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = primaryBlue; style = Paint.Style.FILL })

        if (scaledBmp !== photo) scaledBmp.recycle()
        croppedBmp.recycle()
        return card
    }

    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var current = ""
        for (word in words) {
            val test = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(test) <= maxWidth) {
                current = test
            } else {
                if (current.isNotEmpty()) lines.add(current)
                current = word
            }
        }
        if (current.isNotEmpty()) lines.add(current)
        return lines
    }

    private fun showShareSheet() {
        val dialog = BottomSheetDialog(this, R.style.Theme_TransparentBottomSheet)
        val sheetBinding = DialogShareBottomSheetBinding.inflate(layoutInflater)
        dialog.setContentView(sheetBinding.root)

        val name = intent.getStringExtra("name") ?: "Discovery"
        val location = intent.getStringExtra("location") ?: ""

        sheetBinding.tvShareTitle.text = "Share $name"

        // Load thumbnail
        val photoUri = intent.getStringExtra("photo_uri")
        if (photoUri != null) {
            Glide.with(this).load(Uri.parse(photoUri)).centerCrop().into(sheetBinding.imgShareThumb)
        }

        // Social share intents (image only — all info is on the share card)
        sheetBinding.btnShareInstagram.setOnClickListener {
            shareToApp("com.instagram.android")
            dialog.dismiss()
        }
        sheetBinding.btnShareWhatsApp.setOnClickListener {
            shareToApp("com.whatsapp")
            dialog.dismiss()
        }
        sheetBinding.btnShareTelegram.setOnClickListener {
            shareToApp("org.telegram.messenger")
            dialog.dismiss()
        }
        sheetBinding.btnShareX.setOnClickListener {
            shareToApp("com.twitter.android")
            dialog.dismiss()
        }

        // More — opens native Android share sheet
        sheetBinding.btnShareMore.setOnClickListener {
            dialog.dismiss()
            shareToApp(null)
        }

        // Copy Link
        sheetBinding.btnCopyLink.setOnClickListener {
            val serverId = intent.getLongExtra("server_id", -1L)
            val copyText = if (serverId > 0) {
                "${SessionManager.BASE_URL}/share/$serverId"
            } else {
                "\uD83C\uDFDB\uFE0F $name" + if (location.isNotEmpty()) "\n\uD83D\uDCCD $location" else ""
            }
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("landmark", copyText))
            dialog.dismiss()
            showCustomToast("Copied to clipboard!")
        }

        // Save to Gallery
        sheetBinding.btnSaveGallery.setOnClickListener {
            dialog.dismiss()
            saveImageToGallery()
        }

        dialog.show()
    }

    private fun shareToApp(targetPackage: String?) {
        CoroutineScope(Dispatchers.IO).launch {
            // Generate share card image and save to MediaStore (works with all apps including Facebook)
            val photoBitmap = loadPhotoBitmap()
            var shareUri: Uri? = null

            if (photoBitmap != null) {
                try {
                    val card = createShareCard(photoBitmap)
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "TravelAI_share_${System.currentTimeMillis()}.jpg")
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/TravelAI")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        contentResolver.openOutputStream(uri)?.use { out ->
                            card.compress(Bitmap.CompressFormat.JPEG, 92, out)
                        }
                        values.clear()
                        values.put(MediaStore.Images.Media.IS_PENDING, 0)
                        contentResolver.update(uri, values, null, null)
                        shareUri = uri
                    }
                    card.recycle()
                    photoBitmap.recycle()
                } catch (e: Exception) {
                    Log.e("LandmarkDetail", "Failed to create share card", e)
                }
            }

            withContext(Dispatchers.Main) {
                if (shareUri != null) {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "image/jpeg"
                        putExtra(Intent.EXTRA_STREAM, shareUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        if (targetPackage != null) setPackage(targetPackage)
                    }
                    try {
                        if (targetPackage != null) {
                            startActivity(shareIntent)
                        } else {
                            startActivity(Intent.createChooser(shareIntent, "Share via"))
                        }
                    } catch (e: Exception) {
                        shareIntent.setPackage(null)
                        startActivity(Intent.createChooser(shareIntent, "Share via"))
                    }
                } else {
                    Toast.makeText(this@LandmarkDetailActivity, "Failed to create share image", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun saveImageToGallery() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val photoUriStr = intent.getStringExtra("photo_uri") ?: throw Exception("No photo")
                val photoUri = Uri.parse(photoUriStr)
                val landmarkName = intent.getStringExtra("name") ?: "landmark"
                val fileName = "RecognizeAI_${landmarkName.replace(" ", "_")}_${System.currentTimeMillis()}.jpg"

                // Read the image bytes
                val imageBytes = if (photoUriStr.startsWith("http")) {
                    val request = Request.Builder().url(photoUriStr).build()
                    httpClient.newCall(request).execute().use { response ->
                        response.body?.bytes() ?: throw Exception("Download failed")
                    }
                } else {
                    contentResolver.openInputStream(photoUri)?.use { it.readBytes() }
                        ?: throw Exception("Cannot read image")
                }

                // Save using MediaStore
                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/RecognizeAI")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }

                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                    ?: throw Exception("Failed to create media entry")

                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(imageBytes)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                    contentResolver.update(uri, contentValues, null, null)
                }

                withContext(Dispatchers.Main) {
                    showCustomToast("Saved to Photo Gallery!")
                }
            } catch (e: Exception) {
                Log.e("LandmarkDetail", "Failed to save to gallery", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@LandmarkDetailActivity, "Failed to save image", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showCustomToast(message: String) {
        val toastView = LayoutInflater.from(this).inflate(R.layout.toast_custom, null)
        toastView.findViewById<android.widget.TextView>(R.id.tvToastMessage).text = message

        // Measure the view
        toastView.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )

        val popup = PopupWindow(
            toastView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            false
        )
        popup.elevation = 16f

        // Show at top center of screen
        val rootView = window.decorView.findViewById<View>(android.R.id.content)
        popup.showAtLocation(rootView, Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, (56 * resources.displayMetrics.density).toInt())

        // Animate in
        toastView.alpha = 0f
        toastView.translationY = -20f
        toastView.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(300)
            .start()

        // Auto dismiss after 2 seconds
        handler.postDelayed({
            toastView.animate()
                .alpha(0f)
                .translationY(-20f)
                .setDuration(300)
                .withEndAction { popup.dismiss() }
                .start()
        }, 2000)
    }
}
