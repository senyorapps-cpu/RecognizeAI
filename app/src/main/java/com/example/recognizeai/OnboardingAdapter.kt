package com.example.recognizeai

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners

class OnboardingAdapter(
    private val activity: OnboardingActivity
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val layouts = intArrayOf(
        R.layout.item_onboarding_1,
        R.layout.item_onboarding_2,
        R.layout.item_onboarding_3
    )

    private val handler = Handler(Looper.getMainLooper())
    private val animatedPositions = mutableSetOf<Int>()

    override fun getItemViewType(position: Int): Int = position

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(layouts[viewType], parent, false)
        return object : RecyclerView.ViewHolder(view) {}
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val view = holder.itemView

        when (position) {
            0 -> {
                view.findViewById<View>(R.id.btnNext)?.setOnClickListener {
                    activity.goToNextPage()
                }

                // Load Eiffel Tower image
                val imgLandmark = view.findViewById<ImageView>(R.id.imgOnboardingLandmark)
                if (imgLandmark != null) {
                    Glide.with(activity)
                        .load("https://lh3.googleusercontent.com/aida-public/AB6AXuDKxvyDcgxM2iTFD7VGkW87xoW_yPZ_I_AkJeiqhCDKnu17AFiREt7XwskwsjIwzClpBcYccKAdrFboVBVn2RQ8PH1UBQ-ndH14WTUaMf7PUFxUJ5deTqpMFXUK6LQYY1td_1kKmMsH0mJ6DaGARe--gBiwBYjaX296PI7dYIMaTQoDsr9dP3JOORXcY_W8HSUUzrmfYrv1KFGvnzibUyQz0YD9UDhQ8eDZZX0cF2pvcoIWHxgAGyNBX7Zk3qN49Tjm1hOQfH0eBM8")
                        .centerCrop()
                        .into(imgLandmark)
                }

                // Start animations only once
                if (!animatedPositions.contains(0)) {
                    animatedPositions.add(0)
                    startScanningAnimation(view)
                }
            }
            1 -> {
                view.findViewById<View>(R.id.btnNext)?.setOnClickListener {
                    activity.goToNextPage()
                }

                // Load images with Glide
                val cornerRadius = (12 * view.context.resources.displayMetrics.density).toInt()

                // Left card: Colosseum, Rome
                view.findViewById<ImageView>(R.id.imgCard1)?.let {
                    Glide.with(activity)
                        .load("https://images.unsplash.com/photo-1552832230-c0197dd311b5?w=400&q=80")
                        .transform(CenterCrop(), RoundedCorners(cornerRadius))
                        .into(it)
                }

                // Right card: Venice canals
                view.findViewById<ImageView>(R.id.imgCard2)?.let {
                    Glide.with(activity)
                        .load("https://images.unsplash.com/photo-1523906834658-6e24ef2386f9?w=400&q=80")
                        .transform(CenterCrop(), RoundedCorners(cornerRadius))
                        .into(it)
                }

                // Center card (front): Eiffel Tower, Paris
                view.findViewById<ImageView>(R.id.imgCard3)?.let {
                    Glide.with(activity)
                        .load("https://images.unsplash.com/photo-1502602898657-3e91760cbb34?w=400&q=80")
                        .transform(CenterCrop(), RoundedCorners(cornerRadius))
                        .into(it)
                }
            }
            2 -> {
                view.findViewById<View>(R.id.btnGetStarted)?.setOnClickListener {
                    activity.navigateToLogin()
                }
            }
        }
    }

    private fun startScanningAnimation(view: View) {
        val scanBeam = view.findViewById<View>(R.id.scanBeamOnboarding) ?: return
        val scanningCard = view.findViewById<View>(R.id.scanningCard) ?: return
        val scanningLabel = view.findViewById<TextView>(R.id.tvScanningLabel) ?: return
        val scanningName = view.findViewById<TextView>(R.id.tvScanningName) ?: return

        // 1. Scan beam sweeps up and down
        scanBeam.post {
            val parent = scanBeam.parent as? View ?: return@post
            val beamAnimator = ObjectAnimator.ofFloat(scanBeam, "translationY", 0f, parent.height.toFloat())
            beamAnimator.duration = 2500
            beamAnimator.repeatCount = ValueAnimator.INFINITE
            beamAnimator.repeatMode = ValueAnimator.REVERSE
            beamAnimator.interpolator = LinearInterpolator()
            beamAnimator.start()
        }

        // 2. After 800ms — slide up the scanning card
        handler.postDelayed({
            val slideUp = ObjectAnimator.ofFloat(scanningCard, View.TRANSLATION_Y, 40f, 0f)
            val fadeIn = ObjectAnimator.ofFloat(scanningCard, View.ALPHA, 0f, 1f)
            AnimatorSet().apply {
                playTogether(slideUp, fadeIn)
                duration = 500
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
        }, 800)

        // 3. Pulse the "Scanning" label
        handler.postDelayed({
            val pulse = ObjectAnimator.ofFloat(scanningLabel, View.ALPHA, 1f, 0.3f)
            pulse.duration = 600
            pulse.repeatCount = ValueAnimator.INFINITE
            pulse.repeatMode = ValueAnimator.REVERSE
            pulse.start()
        }, 1000)

        // 4. After 1.5s — fade in the landmark name
        handler.postDelayed({
            ObjectAnimator.ofFloat(scanningName, View.ALPHA, 0f, 1f).apply {
                duration = 400
                start()
            }
        }, 1500)
    }

    override fun getItemCount(): Int = layouts.size
}
