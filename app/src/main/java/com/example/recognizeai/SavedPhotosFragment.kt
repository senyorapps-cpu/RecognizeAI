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
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.bumptech.glide.Glide
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
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
        val language: String = "en",
        val isPending: Boolean = false,
        val isFavorited: Int = 0,
        val landmarkLat: Double = 0.0,
        val landmarkLng: Double = 0.0
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
        loadSavedItems()

        binding.btnExportPdf.setOnClickListener {
            if (SessionManager(requireContext()).isPlus) {
                exportPdf()
            } else {
                startActivity(Intent(requireContext(), SubscriptionActivity::class.java))
            }
        }
    }

    private fun exportPdf() {
        val ctx = context ?: return
        if (allItems.isEmpty()) {
            Toast.makeText(ctx, getString(R.string.export_no_items), Toast.LENGTH_SHORT).show()
            return
        }

        // Pre-capture everything needed by async callbacks to avoid fragment-detach crashes
        val msgGenerating = getString(R.string.export_generating)
        val msgFailed = getString(R.string.export_failed)
        val shareTitle = getString(R.string.export_share_title)
        val authority = "${ctx.packageName}.fileprovider"
        val userName = SessionManager(ctx).displayName

        Toast.makeText(ctx, msgGenerating, Toast.LENGTH_SHORT).show()

        var tvProgress: TextView? = null
        val dialog: Dialog? = try {
            Dialog(ctx).also { d ->
                d.setContentView(R.layout.dialog_export_progress)
                d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                d.window?.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                d.setCancelable(false)
                d.show()
                tvProgress = d.findViewById(R.id.tvExportProgress)
            }
        } catch (e: Exception) {
            android.util.Log.e("ExportPdf", "Dialog inflate failed: ${e.javaClass.simpleName}: ${e.message}", e)
            null
        }

        JournalPdfExporter(ctx).export(
            items = allItems.toList(),
            userName = userName,
            onProgress = { current, total ->
                tvProgress?.text = if (current == 0) msgGenerating else "$current / $total"
            },
            onDone = { file ->
                if (dialog?.isShowing == true) dialog.dismiss()
                if (file != null) {
                    try {
                        val uri = FileProvider.getUriForFile(ctx, authority, file)
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/pdf"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(shareIntent, shareTitle))
                    } catch (e: Exception) {
                        android.util.Log.e("SavedPhotos", "Share failed", e)
                        Toast.makeText(ctx, msgFailed, Toast.LENGTH_LONG).show()
                    }
                } else {
                    Toast.makeText(ctx, msgFailed, Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    override fun onResume() {
        super.onResume()
        loadSavedItems()
    }

    fun refreshData() {
        loadSavedItems()
    }

    private fun setupRecyclerView() {
        adapter = SavedPhotosAdapter(
            items = filteredItems,
            onClick = { item ->
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
                    putExtra("language", item.language)
                    putExtra("is_favorited", item.isFavorited)
                    putExtra("from_saved", true)
                }
                startActivity(intent)
            },
            onDelete = { item -> confirmDelete(item) }
        )

        binding.recyclerSaved.layoutManager =
            StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
        binding.recyclerSaved.adapter = adapter
    }

    private fun confirmDelete(item: SavedLandmark) {
        val dialog = Dialog(requireContext())
        dialog.setContentView(R.layout.dialog_delete_confirm)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        dialog.findViewById<TextView>(R.id.btnDialogCancel).setOnClickListener {
            dialog.dismiss()
        }
        dialog.findViewById<TextView>(R.id.btnDialogDelete).setOnClickListener {
            dialog.dismiss()
            deleteItem(item)
        }
        dialog.show()
    }

    private fun deleteItem(item: SavedLandmark) {
        // Delete from server
        if (item.serverId > 0) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val sm = SessionManager(requireContext())
                    val request = Request.Builder()
                        .url("${SessionManager.BASE_URL}/api/landmarks/${item.serverId}?user_id=${sm.userId}&device_id=${sm.deviceId}")
                        .delete()
                        .build()
                    client.newCall(request).execute().close()
                    Log.d("SavedPhotos", "Deleted from server: ${item.serverId}")
                } catch (e: Exception) {
                    Log.e("SavedPhotos", "Failed to delete from server", e)
                }
            }
        }

        // Delete from local storage
        val prefs = requireContext().getSharedPreferences("recognizeai_landmarks", android.content.Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString("landmarks", "[]"))
        val newArr = JSONArray()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val matchById = item.serverId > 0 && obj.optLong("server_id", -1L) == item.serverId
            val matchByUri = item.imageUrl.isNotEmpty() && obj.optString("photo_uri") == item.imageUrl
            if (!matchById && !matchByUri) {
                newArr.put(obj)
            }
        }
        prefs.edit().putString("landmarks", newArr.toString()).apply()

        // Remove from lists, reset stale country filter, refresh UI
        allItems.remove(item)
        val deletedCountry = item.extractCountry()
        if (selectedCountry == deletedCountry && allItems.none { it.extractCountry() == deletedCountry }) {
            selectedCountry = null
        }
        buildCountryChips()
        applyFilter()
    }

    private fun loadSavedItems() {
        val session = SessionManager(requireContext())
        val userId = session.userId
        val deviceId = session.deviceId

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = if (userId > 0) {
                    "${SessionManager.BASE_URL}/api/user/$userId/landmarks?device_id=$deviceId"
                } else {
                    "${SessionManager.BASE_URL}/api/landmarks/by-device?device_id=$deviceId"
                }
                Log.d("SavedPhotos", "Loading from server: $url")
                val request = Request.Builder()
                    .url(url)
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
                            imageUrl = buildImageUrl(obj.optString("image_url", "")),
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
                            createdAt = obj.optString("created_at", ""),
                            language = obj.optString("language", "en"),
                            isFavorited = obj.optInt("is_favorited", 0),
                            landmarkLat = obj.optDouble("landmark_lat", 0.0),
                            landmarkLng = obj.optDouble("landmark_lng", 0.0)
                        )
                    )
                }

                withContext(Dispatchers.Main) {
                    allItems.clear()
                    allItems.addAll(items)
                    buildCountryChips()
                    applyFilter()
                    // Register geofences for all landmarks that have coordinates
                    val ctx = context ?: return@withContext
                    GeofenceManager.register(ctx, items.map {
                        GeofenceManager.GeofenceLandmark(
                            id = it.serverId,
                            name = it.name,
                            lat = it.landmarkLat,
                            lng = it.landmarkLng,
                            location = it.location,
                            imageUrl = it.imageUrl,
                            yearBuilt = it.yearBuilt,
                            status = it.status,
                            architect = it.architect,
                            capacity = it.capacity,
                            narrativeP1 = it.narrativeP1,
                            narrativeQuote = it.narrativeQuote,
                            narrativeP2 = it.narrativeP2,
                            nearby1Name = it.nearby1Name,
                            nearby1Category = it.nearby1Category,
                            nearby2Name = it.nearby2Name,
                            nearby2Category = it.nearby2Category,
                            nearby3Name = it.nearby3Name,
                            nearby3Category = it.nearby3Category,
                            rating = it.rating,
                            language = it.language,
                            isFavorited = it.isFavorited
                        )
                    })
                }
            } catch (e: Exception) {
                Log.e("SavedPhotos", "Failed to load from server, falling back to local", e)
                withContext(Dispatchers.Main) {
                    loadFromLocalStorage()
                }
            }
        }
    }

    private fun buildImageUrl(raw: String): String = when {
        raw.startsWith("http") -> raw
        raw.isNotEmpty() -> "${SessionManager.BASE_URL}$raw"
        else -> ""
    }

    private fun loadFromLocalStorage() {
        val prefs = requireContext().getSharedPreferences("recognizeai_landmarks", android.content.Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString("landmarks", "[]"))
        val items = mutableListOf<SavedLandmark>()

        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
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
                    language = obj.optString("language", "en"),
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
        private val onClick: (SavedLandmark) -> Unit,
        private val onDelete: (SavedLandmark) -> Unit
    ) : RecyclerView.Adapter<SavedPhotosAdapter.ViewHolder>() {

        private val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

        class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val imgSaved: ImageView = itemView.findViewById(R.id.imgSaved)
            val tvSavedName: TextView = itemView.findViewById(R.id.tvSavedName)
            val tvSavedDate: TextView = itemView.findViewById(R.id.tvSavedDate)
            val pendingOverlay: View = itemView.findViewById(R.id.pendingOverlay)
            val pendingBadgeContainer: View = itemView.findViewById(R.id.pendingBadgeContainer)
            val btnDeleteItem: ImageView = itemView.findViewById(R.id.btnDeleteItem)
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

            holder.btnDeleteItem.setOnClickListener { onDelete(item) }

            if (item.isPending) {
                holder.tvSavedName.text = holder.itemView.context.getString(R.string.saved_offline)
                holder.tvSavedDate.text = item.createdAt.substringBefore("T").ifEmpty { "" }
                holder.pendingOverlay.visibility = View.VISIBLE
                holder.pendingBadgeContainer.visibility = View.VISIBLE
                holder.itemView.setOnClickListener { /* pending — no action */ }
            } else {
                holder.tvSavedName.text = item.name
                holder.tvSavedDate.text = item.createdAt.substringBefore("T").ifEmpty { "" }
                holder.pendingOverlay.visibility = View.GONE
                holder.pendingBadgeContainer.visibility = View.GONE
                holder.itemView.setOnClickListener { onClick(item) }
            }
        }

        override fun getItemCount() = items.size
    }
}
