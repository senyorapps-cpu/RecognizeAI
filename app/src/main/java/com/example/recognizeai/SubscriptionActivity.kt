package com.example.recognizeai

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.android.billingclient.api.*
import com.example.recognizeai.databinding.ActivitySubscriptionBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class SubscriptionActivity : BaseActivity(), PurchasesUpdatedListener {

    private lateinit var binding: ActivitySubscriptionBinding
    private lateinit var session: SessionManager
    private lateinit var billingClient: BillingClient

    private var plusProductDetails: ProductDetails? = null
    private var proProductDetails: ProductDetails? = null
    private var isSilentCheck = false

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    companion object {
        const val PRODUCT_TRAVELER = "plan_traveler_monthly"
        const val PRODUCT_GLOBETROTTER = "plan_globetrotter_monthly"
        const val EXTRA_RETURN_TO = "return_to"
        const val RETURN_AR = "ar"
        const val RETURN_SHARE = "share"
        const val RETURN_HEATMAP = "heatmap"
        const val RETURN_PDF = "pdf"
        private const val TAG = "SubscriptionActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySubscriptionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        session = SessionManager(this)

        updateCurrentPlanIndicators()
        setupClickListeners()
        setupBillingClient()
    }

    private fun updateCurrentPlanIndicators() {
        binding.tvFreeCurrent.visibility = View.GONE
        binding.tvPlusCurrent.visibility = View.GONE
        binding.tvProCurrent.visibility = View.GONE
        when (session.plan) {
            "plus" -> binding.tvPlusCurrent.visibility = View.VISIBLE
            "pro"  -> binding.tvProCurrent.visibility = View.VISIBLE
            else   -> binding.tvFreeCurrent.visibility = View.VISIBLE
        }
    }

    private fun setupClickListeners() {
        binding.btnBack.setOnClickListener { finish() }

        binding.btnUpgradePlus.setOnClickListener {
            when (session.plan) {
                "plus" -> Toast.makeText(this, "You are already on the Traveler plan.", Toast.LENGTH_SHORT).show()
                "pro"  -> Toast.makeText(this, "Downgrade not supported. Cancel from Play Store.", Toast.LENGTH_LONG).show()
                else   -> launchPurchase(plusProductDetails, PRODUCT_TRAVELER)
            }
        }

        binding.btnUpgradePro.setOnClickListener {
            when (session.plan) {
                "pro" -> Toast.makeText(this, "You are already on the Globetrotter plan.", Toast.LENGTH_SHORT).show()
                else  -> launchPurchase(proProductDetails, PRODUCT_GLOBETROTTER)
            }
        }
    }

    // ── Billing Setup ─────────────────────────────────────────────

    private fun setupBillingClient() {
        billingClient = BillingClient.newBuilder(this)
            .setListener(this)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .build()

        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.d(TAG, "Billing connected")
                    queryProducts()
                    checkExistingPurchases()
                } else {
                    Log.e(TAG, "Billing setup failed: ${result.debugMessage}")
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.w(TAG, "Billing disconnected")
            }
        })
    }

    private fun queryProducts() {
        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRODUCT_TRAVELER)
                .setProductType(BillingClient.ProductType.SUBS)
                .build(),
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRODUCT_GLOBETROTTER)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        )

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        billingClient.queryProductDetailsAsync(params) { result, productDetailsList ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                for (product in productDetailsList) {
                    when (product.productId) {
                        PRODUCT_TRAVELER     -> plusProductDetails = product
                        PRODUCT_GLOBETROTTER -> proProductDetails = product
                    }
                }
                Log.d(TAG, "Products loaded: ${productDetailsList.size}")
            } else {
                Log.e(TAG, "Failed to query products: ${result.debugMessage}")
            }
        }
    }

    private fun checkExistingPurchases() {
        isSilentCheck = true
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        ) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                val activePurchases = purchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                if (activePurchases.isEmpty() && session.plan != "free") {
                    // No active subscriptions but user has paid plan — check server for expiry
                    notifyServerNoActivePurchases()
                } else {
                    for (purchase in activePurchases) {
                        // Only process unacknowledged purchases to avoid duplicate receipts
                        if (!purchase.isAcknowledged) {
                            handlePurchase(purchase, silent = true)
                        }
                    }
                }
            }
            isSilentCheck = false
        }
    }

    private fun notifyServerNoActivePurchases() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val json = org.json.JSONObject().apply {
                    put("userId", session.userId)
                    put("deviceId", session.deviceId)
                }
                val body = json.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("${SessionManager.BASE_URL}/api/subscription/downgrade")
                    .post(body)
                    .build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val newPlan = "free"
                    if (session.plan != "free") {
                        session.plan = newPlan
                        withContext(Dispatchers.Main) { updateCurrentPlanIndicators() }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Downgrade check error", e)
            }
        }
    }

    // ── Purchase Flow ─────────────────────────────────────────────

    private fun launchPurchase(productDetails: ProductDetails?, productId: String) {
        if (productDetails == null) {
            Toast.makeText(this, "Loading plans, please try again.", Toast.LENGTH_SHORT).show()
            return
        }

        val offerToken = productDetails.subscriptionOfferDetails?.firstOrNull()?.offerToken ?: run {
            Toast.makeText(this, "No offer available.", Toast.LENGTH_SHORT).show()
            return
        }

        val productDetailsParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(productDetails)
            .setOfferToken(offerToken)
            .build()

        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productDetailsParams))
            .build()

        billingClient.launchBillingFlow(this, billingFlowParams)
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases?.forEach { purchase ->
                    if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                        handlePurchase(purchase, silent = false)
                    }
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                Log.d(TAG, "User cancelled purchase")
            }
            else -> {
                Log.e(TAG, "Purchase error: ${result.debugMessage}")
                Toast.makeText(this, "Purchase failed: ${result.debugMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ── Verify Purchase on Server ──────────────────────────────────

    private fun handlePurchase(purchase: Purchase, silent: Boolean = false) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val productId = purchase.products.firstOrNull() ?: return@launch
                val json = JSONObject().apply {
                    put("userId", session.userId)
                    put("deviceId", session.deviceId)
                    put("purchaseToken", purchase.purchaseToken)
                    put("productId", productId)
                }

                val body = json.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("${SessionManager.BASE_URL}/api/verify-purchase")
                    .post(body)
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: "{}"

                if (response.isSuccessful) {
                    val responseJson = JSONObject(responseBody)
                    val newPlan = responseJson.optString("plan", session.plan)
                    session.plan = newPlan

                    // Acknowledge the purchase
                    if (!purchase.isAcknowledged) {
                        val ackParams = AcknowledgePurchaseParams.newBuilder()
                            .setPurchaseToken(purchase.purchaseToken)
                            .build()
                        billingClient.acknowledgePurchase(ackParams) { ackResult ->
                            Log.d(TAG, "Ack result: ${ackResult.responseCode}")
                        }
                    }

                    withContext(Dispatchers.Main) {
                        updateCurrentPlanIndicators()
                        if (!silent) showSuccessDialog()
                    }
                } else {
                    Log.e(TAG, "Server verification failed: $responseBody")
                    if (!silent) withContext(Dispatchers.Main) {
                        Toast.makeText(this@SubscriptionActivity, "Verification failed. Contact support.", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Purchase verification error", e)
                if (!silent) withContext(Dispatchers.Main) {
                    Toast.makeText(this@SubscriptionActivity, "Network error. Please try again.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showSuccessDialog() {
        val returnTo = intent.getStringExtra(EXTRA_RETURN_TO)
        val view = layoutInflater.inflate(R.layout.dialog_subscription_success, null)
        val dialog = android.app.Dialog(this)
        dialog.setContentView(view)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.85).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.setCancelable(false)
        view.findViewById<android.widget.TextView>(R.id.btnContinue).setOnClickListener {
            dialog.dismiss()
            when (returnTo) {
                RETURN_AR -> {
                    startActivity(android.content.Intent(this@SubscriptionActivity, ARCameraActivity::class.java))
                    finish()
                }
                else -> {
                    setResult(RESULT_OK)
                    finish()
                }
            }
        }
        dialog.show()
    }

    override fun onDestroy() {
        super.onDestroy()
        billingClient.endConnection()
    }
}
