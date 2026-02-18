package com.example.recognizeai

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

class ContactUsActivity : AppCompatActivity() {

    private var selectedTopic: String? = null
    private var attachedUri: Uri? = null
    private val topicViews = mutableListOf<TextView>()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            attachedUri = uri
            val imgAttached = findViewById<ImageView>(R.id.imgAttached)
            val cornerRadius = (8 * resources.displayMetrics.density).toInt()
            Glide.with(this)
                .load(uri)
                .transform(CenterCrop(), RoundedCorners(cornerRadius))
                .into(imgAttached)
            imgAttached.visibility = View.VISIBLE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contact_us)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }

        val topicBilling = findViewById<TextView>(R.id.topicBilling)
        val topicAnalysis = findViewById<TextView>(R.id.topicAnalysis)
        val topicBug = findViewById<TextView>(R.id.topicBug)
        val topicGeneral = findViewById<TextView>(R.id.topicGeneral)
        topicViews.addAll(listOf(topicBilling, topicAnalysis, topicBug, topicGeneral))

        topicBilling.setOnClickListener { selectTopic("billing", topicBilling) }
        topicAnalysis.setOnClickListener { selectTopic("analysis", topicAnalysis) }
        topicBug.setOnClickListener { selectTopic("bug", topicBug) }
        topicGeneral.setOnClickListener { selectTopic("general", topicGeneral) }

        findViewById<LinearLayout>(R.id.btnAttach).setOnClickListener {
            pickImage.launch("image/*")
        }

        findViewById<TextView>(R.id.btnSend).setOnClickListener {
            sendMessage()
        }
    }

    private fun selectTopic(topic: String, view: TextView) {
        selectedTopic = topic
        for (tv in topicViews) {
            tv.isSelected = false
        }
        view.isSelected = true
    }

    private fun sendMessage() {
        val etMessage = findViewById<EditText>(R.id.etMessage)
        val message = etMessage.text.toString().trim()

        if (selectedTopic == null) {
            Toast.makeText(this, getString(R.string.contact_error_topic), Toast.LENGTH_SHORT).show()
            return
        }
        if (message.isEmpty()) {
            Toast.makeText(this, getString(R.string.contact_error_message), Toast.LENGTH_SHORT).show()
            return
        }

        val btnSend = findViewById<TextView>(R.id.btnSend)
        btnSend.isEnabled = false
        btnSend.alpha = 0.5f

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val builder = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("topic", selectedTopic!!)
                    .addFormDataPart("message", message)

                // Attach screenshot if selected
                if (attachedUri != null) {
                    val tempFile = File(cacheDir, "screenshot_${System.currentTimeMillis()}.jpg")
                    contentResolver.openInputStream(attachedUri!!)?.use { input ->
                        tempFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    builder.addFormDataPart(
                        "screenshot",
                        tempFile.name,
                        tempFile.asRequestBody("image/jpeg".toMediaType())
                    )
                }

                val request = Request.Builder()
                    .url("${SessionManager.BASE_URL}/api/contact")
                    .post(builder.build())
                    .build()
                val response = client.newCall(request).execute()
                response.body?.string()
                response.close()

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful) {
                        Toast.makeText(this@ContactUsActivity, getString(R.string.contact_success), Toast.LENGTH_LONG).show()
                        finish()
                    } else {
                        Toast.makeText(this@ContactUsActivity, "Failed to send message", Toast.LENGTH_SHORT).show()
                        btnSend.isEnabled = true
                        btnSend.alpha = 1f
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ContactUsActivity, "Network error. Please try again.", Toast.LENGTH_SHORT).show()
                    btnSend.isEnabled = true
                    btnSend.alpha = 1f
                }
            }
        }
    }
}
