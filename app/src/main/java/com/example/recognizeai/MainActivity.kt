package com.example.recognizeai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import com.example.recognizeai.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var photoUri: Uri? = null
    private var currentTab = -1

    companion object {
        const val TAB_HOME = 0
        const val TAB_MAP = 1
        const val TAB_SAVED = 2
        const val TAB_PROFILE = 3
        const val EXTRA_TAB = "extra_tab"
    }

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
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupNavigation()

        val targetTab = intent.getIntExtra(EXTRA_TAB, TAB_HOME)
        if (savedInstanceState == null) {
            switchTab(targetTab)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val targetTab = intent.getIntExtra(EXTRA_TAB, -1)
        if (targetTab >= 0) {
            switchTab(targetTab)
        }
    }

    private var syncDialogShowing = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onResume() {
        super.onResume()
        checkAndShowSyncDialog()
    }

    override fun onStart() {
        super.onStart()
        // Register network callback to detect when internet comes back while app is open
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread { checkAndShowSyncDialog() }
            }
        }
        cm.registerNetworkCallback(request, networkCallback!!)
    }

    override fun onStop() {
        super.onStop()
        networkCallback?.let {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(it)
        }
        networkCallback = null
    }

    private fun checkAndShowSyncDialog() {
        val pendingManager = PendingAnalysisManager(this)
        if (pendingManager.hasPendingItems() && NetworkUtils.isOnline(this) && !syncDialogShowing) {
            val count = pendingManager.getPendingCount()
            syncDialogShowing = true
            MaterialAlertDialogBuilder(this)
                .setTitle("Unanalyzed Photos")
                .setMessage("You have $count unanalyzed photo(s). Analyze now?")
                .setPositiveButton("Analyze Now") { _, _ ->
                    syncDialogShowing = false
                    Toast.makeText(this, "Analyzing pending photos\u2026", Toast.LENGTH_SHORT).show()
                    CoroutineScope(Dispatchers.Main).launch {
                        pendingManager.syncAll(
                            onComplete = { success, fail ->
                                val msg = if (fail == 0) {
                                    "$success photo(s) analyzed successfully!"
                                } else {
                                    "$success analyzed, $fail failed — will retry later"
                                }
                                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                                // Refresh the current fragment if it's the Saved tab
                                if (currentTab == TAB_SAVED) {
                                    switchTab(-1) // reset to force refresh
                                    switchTab(TAB_SAVED)
                                }
                            }
                        )
                    }
                }
                .setNegativeButton("Later") { _, _ ->
                    syncDialogShowing = false
                }
                .setOnCancelListener {
                    syncDialogShowing = false
                }
                .show()
        }
    }

    private fun setupNavigation() {
        binding.navHome.setOnClickListener { switchTab(TAB_HOME) }
        binding.navMap.setOnClickListener { switchTab(TAB_MAP) }
        binding.navSaved.setOnClickListener { switchTab(TAB_SAVED) }
        binding.navProfile.setOnClickListener { switchTab(TAB_PROFILE) }
        binding.navCamera.setOnClickListener { openCamera() }
    }

    private fun switchTab(tab: Int) {
        if (tab == currentTab) return
        currentTab = tab

        val fragment: Fragment = when (tab) {
            TAB_MAP -> MapFragment()
            TAB_SAVED -> SavedPhotosFragment()
            TAB_PROFILE -> ProfileFragment()
            else -> HomeFragment()
        }

        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()

        updateNavHighlight(tab)
    }

    private fun updateNavHighlight(tab: Int) {
        // Reset all to inactive (alpha 0.4)
        binding.navHomeIcon.alpha = 0.4f
        binding.navHomeText.alpha = 0.4f
        binding.navMapIcon.alpha = 0.4f
        binding.navMapText.alpha = 0.4f
        binding.navSavedIcon.alpha = 0.4f
        binding.navSavedText.alpha = 0.4f
        binding.navProfileIcon.alpha = 0.4f
        binding.navProfileText.alpha = 0.4f

        // Reset icons to outline
        binding.navHomeIcon.setImageResource(R.drawable.ic_home_outline)
        binding.navMapIcon.setImageResource(R.drawable.ic_map_outline)
        binding.navSavedIcon.setImageResource(R.drawable.ic_bookmark_outline)

        // Set active tab to full opacity + filled icon
        when (tab) {
            TAB_HOME -> {
                binding.navHomeIcon.alpha = 1f
                binding.navHomeText.alpha = 1f
                binding.navHomeIcon.setImageResource(R.drawable.ic_home_filled)
            }
            TAB_MAP -> {
                binding.navMapIcon.alpha = 1f
                binding.navMapText.alpha = 1f
                binding.navMapIcon.setImageResource(R.drawable.ic_map_filled)
            }
            TAB_SAVED -> {
                binding.navSavedIcon.alpha = 1f
                binding.navSavedText.alpha = 1f
                binding.navSavedIcon.setImageResource(R.drawable.ic_bookmark_filled)
            }
            TAB_PROFILE -> {
                binding.navProfileIcon.alpha = 1f
                binding.navProfileText.alpha = 1f
            }
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
