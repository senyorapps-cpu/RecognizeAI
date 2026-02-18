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
import com.google.android.material.snackbar.Snackbar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import com.example.recognizeai.databinding.ActivityMainBinding
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var photoUri: Uri? = null
    private var currentTab = -1

    private var homeFragment: Fragment? = null
    private var mapFragment: Fragment? = null
    private var savedFragment: Fragment? = null
    private var profileFragment: Fragment? = null
    private var activeFragment: Fragment? = null

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
        LocaleHelper.initFromSession(this)
        // Apply dark mode preference (only if not already matching to avoid recreation loop)
        val session = SessionManager(this)
        val targetMode = if (session.isDarkMode) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        if (AppCompatDelegate.getDefaultNightMode() != targetMode) {
            AppCompatDelegate.setDefaultNightMode(targetMode)
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupNavigation()

        if (savedInstanceState != null) {
            // Activity was recreated (e.g. language change) — remove stale fragments
            supportFragmentManager.fragments.forEach {
                supportFragmentManager.beginTransaction().remove(it).commitNowAllowingStateLoss()
            }
            // Restore the tab we were on before recreation
            val restoredTab = savedInstanceState.getInt("current_tab", TAB_HOME)
            switchTab(restoredTab)
        } else {
            val targetTab = intent.getIntExtra(EXTRA_TAB, TAB_HOME)
            switchTab(targetTab)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("current_tab", currentTab)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val targetTab = intent.getIntExtra(EXTRA_TAB, -1)
        if (targetTab >= 0) {
            val wasAlreadyOnTab = targetTab == currentTab
            if (!wasAlreadyOnTab) {
                switchTab(targetTab)
            }
            // Only refresh if fragment is already attached;
            // new fragments load data in their own onViewCreated()
            if (targetTab == TAB_SAVED) {
                val saved = savedFragment as? SavedPhotosFragment
                if (saved != null && saved.isAdded) {
                    saved.refreshData()
                }
            }
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

            val dialog = Dialog(this)
            dialog.setContentView(R.layout.dialog_back_online)
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            dialog.window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )

            val photoWord = if (count == 1) getString(R.string.back_online_photo_singular) else getString(R.string.back_online_photo_plural)
            dialog.findViewById<TextView>(R.id.tvSyncMessage).text =
                getString(R.string.back_online_message, count, photoWord)

            dialog.findViewById<TextView>(R.id.btnAnalyzeNow).setOnClickListener {
                dialog.dismiss()
                syncDialogShowing = false
                val snackbar = Snackbar.make(binding.root, getString(R.string.analyzing_pending), Snackbar.LENGTH_INDEFINITE)
                snackbar.setBackgroundTint(getColor(R.color.deep_blue))
                snackbar.setTextColor(getColor(R.color.white))
                snackbar.view.translationY = -(90 * resources.displayMetrics.density)
                snackbar.show()
                CoroutineScope(Dispatchers.Main).launch {
                    pendingManager.syncAll(
                        onComplete = { success, fail ->
                            snackbar.dismiss()
                            val msg = if (fail == 0) {
                                "$success ${getString(R.string.photos_analyzed_success)}"
                            } else {
                                "$success ${getString(R.string.analyzed_some_failed, fail)}"
                            }
                            Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).apply {
                                setBackgroundTint(getColor(R.color.deep_blue))
                                setTextColor(getColor(R.color.white))
                                view.translationY = -(90 * resources.displayMetrics.density)
                                show()
                            }
                            (savedFragment as? SavedPhotosFragment)?.refreshData()
                        }
                    )
                }
            }

            dialog.findViewById<TextView>(R.id.btnLater).setOnClickListener {
                dialog.dismiss()
                syncDialogShowing = false
            }

            dialog.setOnCancelListener {
                syncDialogShowing = false
            }

            dialog.show()
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

        val fm = supportFragmentManager
        val transaction = fm.beginTransaction()

        activeFragment?.let { transaction.hide(it) }

        val target = when (tab) {
            TAB_MAP -> mapFragment ?: MapFragment().also { mapFragment = it; transaction.add(R.id.fragmentContainer, it) }
            TAB_SAVED -> savedFragment ?: SavedPhotosFragment().also { savedFragment = it; transaction.add(R.id.fragmentContainer, it) }
            TAB_PROFILE -> profileFragment ?: ProfileFragment().also { profileFragment = it; transaction.add(R.id.fragmentContainer, it) }
            else -> homeFragment ?: HomeFragment().also { homeFragment = it; transaction.add(R.id.fragmentContainer, it) }
        }

        transaction.show(target)
        activeFragment = target
        transaction.commit()

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

    fun navigateToSaved() {
        switchTab(TAB_SAVED)
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
