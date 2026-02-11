package com.example.recognizeai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.example.recognizeai.databinding.FragmentHomeBinding
import org.json.JSONArray
import org.json.JSONObject

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private var lastCaptureEntry: JSONObject? = null

    private data class TopPlace(
        val name: String,
        val location: String,
        val imageUrl: String
    )

    private val topPlaces = listOf(
        TopPlace("Eiffel Tower", "Paris, France",
            "https://lh3.googleusercontent.com/aida-public/AB6AXuA4lvi2KwB33CRmJFwjY-Ae0wK56AAyyrjno0Ngpu1tkNjFCC8slul18FTkwL34TmmTokinOFQp_IduKP60L05cbht7YH9WfJrUoT-PukOM996hjuQxGg1N04ru66ZACGKlWbGyTt_Vm-MzRC4oJpzWgM6bwUaqCYdsnGT5ptUoJheRNSwpJf-w0LUbhWeMBh_wlqXwtGFe57sztHytwEluy_LnEHKQJxTc2eI5yrydJQJGaySIE9x5SI60b6b-SyXtpdZmtFMb9QY"),
        TopPlace("Colosseum", "Rome, Italy",
            "https://lh3.googleusercontent.com/aida-public/AB6AXuBPPjdUmoidMGut4ZivsU1EYUt5gdB0hP6coo4ySRWZEUnav0srJhwFZGS_7LOZSAzViJFaYJ5htcB5Z5tQb2FIY1maAW_SXZ_wMJ8yyPJUn-A2Gsqb8md5y6YFS3QnjEUEXPYntKtgrautJn5dWmaSnQPGy_-11yZ02xiDhTz8hByrzLXHgSsexR1K9h154R1Do06Tc9y0qwHWPZscgqDUpxDjWYcnvoe22pHGud4iBmsRdcR588sSvdbbA5RZOLAHCO6zrAMIon4"),
        TopPlace("Grand Canal", "Venice, Italy",
            "https://lh3.googleusercontent.com/aida-public/AB6AXuC_cGImP-6Kw2nqm9rUxPVid8GSqGUZhGjW6rDtKlASrDQpUC2PlXTbSswUScyn5CYGGvhw6Azp2Xf6UH3t4s8aOckTF-8sDjHVgyRAByqefXC72CqRKVQMiYR4vcJpZN5Js5uuJjKxMoFkVmBtP4C9gxmYkvZnSx6pQooUZ4raikNqTq-dNV3F-BVEGsnID-5g4pmOJ1iqQXIijF9FW2a9RLY5f0gZoicYvpxAb4zYjm304h6SEBfNl5AyjQca7SKFRA2IutXfl6M"),
        TopPlace("Big Ben", "London, UK",
            "https://lh3.googleusercontent.com/aida-public/AB6AXuDYPbG8yJ0lw3-w4Zgoo8BAQaHNPR0YUYk3AIfhc4XBIj3_C0A8pbsrf19NTk6bqFftX_5tjCikQe7BC9GdMDsYrhVd4-NA-UXU1fiIL-Z91D8m7cqPVhhi1GsJS3RclKsubGgxEFXEDhxjphhhR7BDwNlKcLZqnMYCMRDnJhbEfzdvQUwLqGeTT7eHePFzF81fX_Rj29BXYvUgdeSfy_NvxbKx0cbKvTsTNxbRRCXXQp9F8JPN-qgO9XJzDQ2hBVq4F8N4ZmnSOdQ"),
        TopPlace("Santorini", "Greece",
            "https://lh3.googleusercontent.com/aida-public/AB6AXuARuqCvlgoE_TGjoCzGU_3WxKgBvLslhvcXDphjI0krM22T-hdx013GKUI45LM5t9vda2pDHmIgmRwlYZTy1mF2V50dPlFSacl9UcAbR2uNJjZ3ucW0R8MQuFQBJ6ZiG5K-19J5HPiDFi9afHmEO2Tk_aOKo3mDoDOJQw0aTc5uJnjK_adyTy4GrFbMl1z-QEIPoPP1jqVDEmVi00NBt_F7-qPnmL1-6KeygLpDKUucfxEdyL11XJUaSnMtiiF9fN9s8dbNkKfSBa8"),
        TopPlace("Roman Forum", "Rome, Italy",
            "https://lh3.googleusercontent.com/aida-public/AB6AXuAk4qdrNvqi1HUyPnrZijZC3DPjzjwyJSD0N6FvOSyNmW6pDlJI7TMKrj8uxgX3aNZOYc3GT9UIXst6rRYwXfQkau6Wm-dEC-ereaVuS666-sFs2Lx1ovbXITMrUyUa-wKYXLzNhDDqqa6EtmASNCVmx0Oy6iZFoSNqrFtcr0yq7gNyexRYjhiEEdO7M-56j-IKv0G4qj1MOnbJoacELVCs_P4C6Fzx_5fiRpJjHZYImIrjkkpsKEb6WUEpHBppuHy7qQJ3DGw5cN8"),
        TopPlace("Kinkaku-ji", "Kyoto, Japan",
            "https://lh3.googleusercontent.com/aida-public/AB6AXuBVdcd0_s5xSTzsqqcAOdaffkO1IjkYYVPK0vIDp-pObErlxhk5Gty4sSKa1KpN7b_kBjUyEkLBMKsuhGTB2aQdKFw6W5h81JhPAcck_7h3BJAUNOTvbXya-MxxWfnDXD5RUr0YvP0JKcpf2uMZgrxFgnd4mP0fC-wdy7g-IRrBAobPg-Mh5nfsiBxvlC20JWKR8pkBdTZG8eHODIsgJRlndrzfIFAAPtO0a5DOi8vGlx92AWNecbTEze2OqoqExoUAjBpB1dkxIuo"),
        TopPlace("Palatine Hill", "Rome, Italy",
            "https://lh3.googleusercontent.com/aida-public/AB6AXuDS1U77zQ_7hHRT8ljWCwCmpyz6DlN-LMwV2Kd4wRJt9z27i8_GkAd5yX3qNKWfZuDoB7FO-hHFg1xM8TS2jiJHLpn45Cs59ewwBY7aFKiw51tVKWjXdhXWw23dskxzhw5mqf0VXJwQr9r06MtvHeAc4ovpQXxJKonMnJMG_0DS1_nL_rVpoyMKZFeOaimcb544iOwz_5pCVur_k3LptDIr4eyQUQdmMcEbXfF2Peipzoi4DkaeR7sh7gPmS3PGuW_a38p4E3fLNSg")
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadTopPlaces()
        setupClickListeners()
    }

    override fun onResume() {
        super.onResume()
        loadLastCapture()
    }

    private fun loadLastCapture() {
        if (_binding == null) return
        val prefs = requireContext().getSharedPreferences("recognizeai_landmarks", Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString("landmarks", "[]"))

        val cornerRadius = (20 * resources.displayMetrics.density).toInt()

        if (arr.length() > 0) {
            val last = arr.getJSONObject(arr.length() - 1)
            lastCaptureEntry = last
            binding.txtLastCaptureName.text = last.optString("name", "Unknown")

            val photoUri = last.optString("photo_uri", "")
            if (photoUri.isNotEmpty()) {
                Glide.with(this)
                    .load(Uri.parse(photoUri))
                    .transform(CenterCrop(), RoundedCorners(cornerRadius))
                    .into(binding.imgLastCapture)
            }
        } else {
            lastCaptureEntry = null
            val randomPlace = topPlaces.random()
            binding.txtLastCaptureName.text = randomPlace.name
            Glide.with(this)
                .load(randomPlace.imageUrl)
                .transform(CenterCrop(), RoundedCorners(cornerRadius))
                .into(binding.imgLastCapture)
        }
    }

    private fun loadTopPlaces() {
        val cornerRadius = (20 * resources.displayMetrics.density).toInt()
        val inflater = LayoutInflater.from(requireContext())

        for (place in topPlaces) {
            val itemView = inflater.inflate(R.layout.item_top_place, binding.topPlacesContainer, false)

            val imgPlace = itemView.findViewById<ImageView>(R.id.imgPlace)
            val txtName = itemView.findViewById<TextView>(R.id.txtPlaceName)
            val txtLocation = itemView.findViewById<TextView>(R.id.txtPlaceLocation)

            txtName.text = place.name
            txtLocation.text = place.location

            Glide.with(this)
                .load(place.imageUrl)
                .transform(CenterCrop(), RoundedCorners(cornerRadius))
                .into(imgPlace)

            binding.topPlacesContainer.addView(itemView)
        }
    }

    private fun setupClickListeners() {
        binding.cardLastCapture.setOnClickListener {
            val entry = lastCaptureEntry
            if (entry != null) {
                val intent = Intent(requireContext(), LandmarkDetailActivity::class.java).apply {
                    putExtra("photo_uri", entry.optString("photo_uri", ""))
                    putExtra("server_id", entry.optLong("server_id", -1L))
                    putExtra("name", entry.optString("name", ""))
                    putExtra("location", entry.optString("location", ""))
                    putExtra("year_built", entry.optString("year_built", ""))
                    putExtra("status", entry.optString("status", ""))
                    putExtra("architect", entry.optString("architect", ""))
                    putExtra("capacity", entry.optString("capacity", ""))
                    putExtra("narrative_p1", entry.optString("narrative_p1", ""))
                    putExtra("narrative_quote", entry.optString("narrative_quote", ""))
                    putExtra("narrative_p2", entry.optString("narrative_p2", ""))
                    putExtra("nearby1_name", entry.optString("nearby1_name", ""))
                    putExtra("nearby1_category", entry.optString("nearby1_category", ""))
                    putExtra("nearby2_name", entry.optString("nearby2_name", ""))
                    putExtra("nearby2_category", entry.optString("nearby2_category", ""))
                    putExtra("nearby3_name", entry.optString("nearby3_name", ""))
                    putExtra("nearby3_category", entry.optString("nearby3_category", ""))
                    putExtra("from_home", true)
                    putExtra("rating", entry.optInt("rating", 0))
                }
                startActivity(intent)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
