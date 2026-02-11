package com.example.recognizeai

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.bumptech.glide.Glide
import com.example.recognizeai.databinding.FragmentSavedPhotosBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class SavedPhotosFragment : Fragment() {

    private var _binding: FragmentSavedPhotosBinding? = null
    private val binding get() = _binding!!
    private val allItems = mutableListOf<SavedLandmark>()
    private val filteredItems = mutableListOf<SavedLandmark>()
    private lateinit var adapter: SavedPhotosAdapter
    private var selectedCountry: String? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    data class SavedLandmark(
        val serverId: Long,
        val name: String,
        val location: String,
        val imageUrl: String,
        val yearBuilt: String,
        val status: String,
        val architect: String,
        val capacity: String,
        val narrativeP1: String,
        val narrativeQuote: String,
        val narrativeP2: String,
        val nearby1Name: String,
        val nearby1Category: String,
        val nearby2Name: String,
        val nearby2Category: String,
        val nearby3Name: String,
        val nearby3Category: String,
        val rating: Int,
        val createdAt: String,
        val isPending: Boolean = false
    ) {
        fun extractCountry(): String {
            val parts = location.split(",").map { it.trim() }
            return if (parts.size >= 2) parts.last() else location.ifEmpty { "Unknown" }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSavedPhotosBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
    }

    override fun onResume() {
        super.onResume()
        loadSavedItems()
    }

    private fun setupRecyclerView() {
        adapter = SavedPhotosAdapter(filteredItems) { item ->
            val intent = Intent(requireContext(), LandmarkDetailActivity::class.java).apply {
                putExtra("server_id", item.serverId)
                putExtra("photo_uri", item.imageUrl)
                putExtra("name", item.name)
                putExtra("location", item.location)
                putExtra("year_built", item.yearBuilt)
                putExtra("status", item.status)
                putExtra("architect", item.architect)
                putExtra("capacity", item.capacity)
                putExtra("narrative_p1", item.narrativeP1)
                putExtra("narrative_quote", item.narrativeQuote)
                putExtra("narrative_p2", item.narrativeP2)
                putExtra("nearby1_name", item.nearby1Name)
                putExtra("nearby1_category", item.nearby1Category)
                putExtra("nearby2_name", item.nearby2Name)
                putExtra("nearby2_category", item.nearby2Category)
                putExtra("nearby3_name", item.nearby3Name)
                putExtra("nearby3_category", item.nearby3Category)
                putExtra("rating", item.rating)
                putExtra("from_saved", true)
            }
            startActivity(intent)
        }

        binding.recyclerSaved.layoutManager =
            StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
        binding.recyclerSaved.adapter = adapter
    }

    private fun loadSavedItems() {
        val session = SessionManager(requireContext())
        val userId = session.userId

        if (userId <= 0) {
            // Guest user — load from local storage only
            Log.d("SavedPhotos", "Guest user (userId=$userId), loading from LOCAL storage")
            loadFromLocalStorage()
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val request = Request.Builder()
                    .url("${SessionManager.BASE_URL}/api/user/$userId/landmarks?saved=true")
                    .get()
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: "[]"

                if (!response.isSuccessful) {
                    throw Exception("Server error: ${response.code}")
                }

                val jsonArray = JSONArray(body)
                val items = mutableListOf<SavedLandmark>()

                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    items.add(
                        SavedLandmark(
                            serverId = obj.optLong("id", -1L),
                            name = obj.optString("name"),
                            location = obj.optString("location"),
                            imageUrl = "${SessionManager.BASE_URL}${obj.optString("image_url")}",
                            yearBuilt = obj.optString("year_built"),
                            status = obj.optString("status"),
                            architect = obj.optString("architect"),
                            capacity = obj.optString("capacity"),
                            narrativeP1 = obj.optString("narrative_p1"),
                            narrativeQuote = obj.optString("narrative_quote"),
                            narrativeP2 = obj.optString("narrative_p2"),
                            nearby1Name = obj.optString("nearby1_name"),
                            nearby1Category = obj.optString("nearby1_category"),
                            nearby2Name = obj.optString("nearby2_name"),
                            nearby2Category = obj.optString("nearby2_category"),
                            nearby3Name = obj.optString("nearby3_name"),
                            nearby3Category = obj.optString("nearby3_category"),
                            rating = obj.optInt("rating", 0),
                            createdAt = obj.optString("created_at", "")
                        )
                    )
                }

                withContext(Dispatchers.Main) {
                    allItems.clear()
                    allItems.addAll(items)
                    buildCountryChips()
                    applyFilter()
                }
            } catch (e: Exception) {
                Log.e("SavedPhotos", "Failed to load from server, falling back to local", e)
                withContext(Dispatchers.Main) {
                    loadFromLocalStorage()
                }
            }
        }
    }

    private fun loadFromLocalStorage() {
        val prefs = requireContext().getSharedPreferences("recognizeai_landmarks", android.content.Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString("landmarks", "[]"))
        val items = mutableListOf<SavedLandmark>()

        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (!obj.optBoolean("is_saved", false)) continue
            items.add(
                SavedLandmark(
                    serverId = obj.optLong("server_id", -1L),
                    name = obj.optString("name", ""),
                    location = obj.optString("location", ""),
                    imageUrl = obj.optString("photo_uri", ""),
                    yearBuilt = obj.optString("year_built", ""),
                    status = obj.optString("status", ""),
                    architect = obj.optString("architect", ""),
                    capacity = obj.optString("capacity", ""),
                    narrativeP1 = obj.optString("narrative_p1", ""),
                    narrativeQuote = obj.optString("narrative_quote", ""),
                    narrativeP2 = obj.optString("narrative_p2", ""),
                    nearby1Name = obj.optString("nearby1_name", ""),
                    nearby1Category = obj.optString("nearby1_category", ""),
                    nearby2Name = obj.optString("nearby2_name", ""),
                    nearby2Category = obj.optString("nearby2_category", ""),
                    nearby3Name = obj.optString("nearby3_name", ""),
                    nearby3Category = obj.optString("nearby3_category", ""),
                    rating = obj.optInt("rating", 0),
                    createdAt = obj.optString("created_at", ""),
                    isPending = obj.optString("status", "") == "pending"
                )
            )
        }

        allItems.clear()
        allItems.addAll(items)
        Log.d("SavedPhotos", "LOCAL LOAD: found ${items.size} saved items out of ${arr.length()} total")
        if (items.isEmpty()) {
            showEmpty()
        } else {
            buildCountryChips()
            applyFilter()
        }
    }

    private fun showEmpty() {
        allItems.clear()
        filteredItems.clear()
        if (_binding != null) {
            adapter.notifyDataSetChanged()
            binding.recyclerSaved.visibility = View.GONE
            binding.emptyState.visibility = View.VISIBLE
        }
    }

    private fun buildCountryChips() {
        binding.chipContainer.removeAllViews()

        val countries = allItems.map { it.extractCountry() }.distinct().sorted()
        val chipLabels = mutableListOf("All")
        chipLabels.addAll(countries)

        val density = resources.displayMetrics.density
        val boldFont = ResourcesCompat.getFont(requireContext(), R.font.plus_jakarta_sans_bold)
        val semiboldFont = ResourcesCompat.getFont(requireContext(), R.font.plus_jakarta_sans_semibold)

        for (label in chipLabels) {
            val chip = TextView(requireContext()).apply {
                text = label
                textSize = 13f
                val hPad = (20 * density).toInt()
                val vPad = (10 * density).toInt()
                setPadding(hPad, vPad, hPad, vPad)

                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.marginEnd = (8 * density).toInt()
                layoutParams = lp

                val isActive = (label == "All" && selectedCountry == null) ||
                        (label == selectedCountry)

                if (isActive) {
                    setBackgroundResource(R.drawable.bg_chip_active)
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.deep_blue))
                    typeface = boldFont
                } else {
                    setBackgroundResource(R.drawable.bg_chip_inactive)
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.deep_blue_50))
                    typeface = semiboldFont
                }

                setOnClickListener {
                    selectedCountry = if (label == "All") null else label
                    buildCountryChips()
                    applyFilter()
                }
            }
            binding.chipContainer.addView(chip)
        }
    }

    private fun applyFilter() {
        filteredItems.clear()
        if (selectedCountry == null) {
            filteredItems.addAll(allItems)
        } else {
            filteredItems.addAll(allItems.filter { it.extractCountry() == selectedCountry })
        }

        adapter.notifyDataSetChanged()

        if (allItems.isEmpty()) {
            binding.recyclerSaved.visibility = View.GONE
            binding.emptyState.visibility = View.VISIBLE
        } else {
            binding.recyclerSaved.visibility = View.VISIBLE
            binding.emptyState.visibility = View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    class SavedPhotosAdapter(
        private val items: List<SavedLandmark>,
        private val onClick: (SavedLandmark) -> Unit
    ) : RecyclerView.Adapter<SavedPhotosAdapter.ViewHolder>() {

        private val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

        class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val imgSaved: ImageView = itemView.findViewById(R.id.imgSaved)
            val tvSavedName: TextView = itemView.findViewById(R.id.tvSavedName)
            val tvSavedDate: TextView = itemView.findViewById(R.id.tvSavedDate)
            val pendingOverlay: View = itemView.findViewById(R.id.pendingOverlay)
            val tvPendingBadge: TextView = itemView.findViewById(R.id.tvPendingBadge)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_saved_photo, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]

            val heightDp = if (position % 2 == 0) 240 else 200
            val heightPx = (heightDp * holder.itemView.resources.displayMetrics.density).toInt()
            holder.imgSaved.layoutParams.height = heightPx

            Glide.with(holder.itemView.context)
                .load(item.imageUrl)
                .centerCrop()
                .into(holder.imgSaved)

            if (item.isPending) {
                holder.tvSavedName.text = holder.itemView.context.getString(R.string.pending_analysis)
                holder.tvSavedDate.text = "Waiting for internet\u2026"
                holder.pendingOverlay.visibility = View.VISIBLE
                holder.tvPendingBadge.visibility = View.VISIBLE
                holder.itemView.setOnClickListener {
                    android.widget.Toast.makeText(
                        holder.itemView.context,
                        "This photo hasn't been analyzed yet",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            } else {
                holder.tvSavedName.text = item.name
                holder.tvSavedDate.text = item.createdAt.substringBefore("T").ifEmpty { "" }
                holder.pendingOverlay.visibility = View.GONE
                holder.tvPendingBadge.visibility = View.GONE
                holder.itemView.setOnClickListener { onClick(item) }
            }
        }

        override fun getItemCount() = items.size
    }
}
