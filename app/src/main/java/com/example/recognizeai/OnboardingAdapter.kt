package com.example.recognizeai

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
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
            }
            1 -> {
                view.findViewById<View>(R.id.btnNext)?.setOnClickListener {
                    activity.goToNextPage()
                }

                // Load images with Glide
                val cornerRadius = (12 * view.context.resources.displayMetrics.density).toInt()

                view.findViewById<ImageView>(R.id.imgCard1)?.let {
                    Glide.with(activity)
                        .load("https://lh3.googleusercontent.com/aida-public/AB6AXuARuqCvlgoE_TGjoCzGU_3WxKgBvLslhvcXDphjI0krM22T-hdx013GKUI45LM5t9vda2pDHmIgmRwlYZTy1mF2V50dPlFSacl9UcAbR2uNJjZ3ucW0R8MQuFQBJ6ZiG5K-19J5HPiDFi9afHmEO2Tk_aOKo3mDoDOJQw0aTc5uJnjK_adyTy4GrFbMl1z-QEIPoPP1jqVDEmVi00NBt_F7-qPnmL1-6KeygLpDKUucfxEdyL11XJUaSnMtiiF9fN9s8dbNkKfSBa8")
                        .transform(CenterCrop(), RoundedCorners(cornerRadius))
                        .into(it)
                }

                view.findViewById<ImageView>(R.id.imgCard2)?.let {
                    Glide.with(activity)
                        .load("https://lh3.googleusercontent.com/aida-public/AB6AXuAHEBZpmKx4x2VjhQ71oYm6IKFe59z361Egzuoq1rMhwLPsntvmwKMr_N1JazMtcxfivWHs8Sy-xiUIMMaxw2qvzY3L9zzyM39-ygAEoK-it3NDpX6NKA0u88yawppmoHHeMiuFLHGCq67DstdZPe7SahnpWSptEhs3MFbXsJEFIjBdVvx7A8mt76fgBhG-r7ZljzM5V7TW1c73PgDSWFHMt2zP4jdrRFtXbo71I-hC5j8gHSJcb9DmaAuXktXh5ybla2gQkfYbxKA")
                        .transform(CenterCrop(), RoundedCorners(cornerRadius))
                        .into(it)
                }

                view.findViewById<ImageView>(R.id.imgCard3)?.let {
                    Glide.with(activity)
                        .load("https://lh3.googleusercontent.com/aida-public/AB6AXuBVdcd0_s5xSTzsqqcAOdaffkO1IjkYYVPK0vIDp-pObErlxhk5Gty4sSKa1KpN7b_kBjUyEkLBMKsuhGTB2aQdKFw6W5h81JhPAcck_7h3BJAUNOTvbXya-MxxWfnDXD5RUr0YvP0JKcpf2uMZgrxFgnd4mP0fC-wdy7g-IRrBAobPg-Mh5nfsiBxvlC20JWKR8pkBdTZG8eHODIsgJRlndrzfIFAAPtO0a5DOi8vGlx92AWNecbTEze2OqoqExoUAjBpB1dkxIuo")
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

    override fun getItemCount(): Int = layouts.size
}
