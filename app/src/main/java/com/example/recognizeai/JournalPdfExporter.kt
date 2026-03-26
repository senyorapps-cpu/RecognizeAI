package com.example.recognizeai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.StaticLayout
import android.text.TextPaint
import com.bumptech.glide.Glide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

class JournalPdfExporter(private val context: Context) {

    private val W = 595
    private val H = 842
    private val M = 40f
    private val CW = (W - 2 * M).toInt()

    private val colorDeepBlue  = Color.parseColor("#1C5B9E")
    private val colorGold      = Color.parseColor("#D4A843")
    private val colorText      = Color.parseColor("#1A1A2E")
    private val colorGray      = Color.parseColor("#888888")
    private val colorDivider   = Color.parseColor("#E0E0E0")
    private val colorCoverBg   = Color.parseColor("#1a1a2e")
    private val colorQuoteBg   = Color.parseColor("#F5EBC8")

    // Cache typefaces — repeated Typeface.create() calls exhaust native resources across pages
    private val tfNormal = Typeface.create("sans-serif", Typeface.NORMAL)
    private val tfBold   = Typeface.create("sans-serif", Typeface.BOLD)
    private val tfItalic = Typeface.create("sans-serif", Typeface.ITALIC)

    fun export(
        items: List<SavedPhotosFragment.SavedLandmark>,
        userName: String,
        onProgress: (current: Int, total: Int) -> Unit,
        onDone: (File?) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            var document: PdfDocument? = null
            try {
                document = PdfDocument()
                var pageNum = 1

                drawCoverPage(document, items, userName, pageNum++)
                withContext(Dispatchers.Main) { runCatching { onProgress(0, items.size) } }

                for ((index, item) in items.withIndex()) {
                    System.gc()
                    val photo = loadBitmap(item.imageUrl)
                    drawLandmarkPage(document, item, photo, pageNum++)
                    photo?.recycle()
                    withContext(Dispatchers.Main) { runCatching { onProgress(index + 1, items.size) } }
                }

                val dir = File(context.cacheDir, "exports").also { it.mkdirs() }
                val file = File(dir, "SightAI_Journal.pdf")
                file.outputStream().use { document.writeTo(it) }
                document.close()

                withContext(Dispatchers.Main) { runCatching { onDone(file) } }
            } catch (t: Throwable) {
                android.util.Log.e("JournalPdf", "Export failed: ${t.javaClass.name}: ${t.message}", t)
                document?.close()
                withContext(Dispatchers.Main) { runCatching { onDone(null) } }
            }
        }
    }

    // ── Cover page ────────────────────────────────────────────────────────────

    private fun drawCoverPage(
        document: PdfDocument,
        items: List<SavedPhotosFragment.SavedLandmark>,
        userName: String,
        pageNum: Int
    ) {
        val page = document.startPage(PdfDocument.PageInfo.Builder(W, H, pageNum).create())
        val c = page.canvas

        // Background
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), paint { color = colorCoverBg })

        // Top gold bar
        c.drawRect(0f, 0f, W.toFloat(), 6f, paint { color = colorGold })

        // Brand
        c.drawText("SIGHTAI", M, 80f, textPaint(colorGold, 18f, Typeface.BOLD).apply {
            letterSpacing = 0.15f
        })

        // Separator
        c.drawLine(M, 94f, W - M, 94f, paint { color = colorGold; alpha = 70; strokeWidth = 1f })

        // Title
        c.drawText("My Travel", M, 200f, textPaint(Color.WHITE, 46f, Typeface.NORMAL))
        c.drawText("Journal", M, 254f, textPaint(Color.WHITE, 46f, Typeface.BOLD))
        c.drawRect(M, 266f, M + 76f, 270f, paint { color = colorGold })

        // User name
        if (userName.isNotEmpty()) {
            c.drawText(safe(userName), M, 316f, textPaint(Color.WHITE, 16f).apply { alpha = 180 })
        }

        // Stats
        val statY = 420f
        val labelPaint = textPaint(Color.WHITE, 11f).apply {
            alpha = 160; letterSpacing = 0.12f
        }
        val valuePaint = textPaint(colorGold, 34f, Typeface.BOLD)

        c.drawText("${items.size}", M, statY, valuePaint)
        c.drawText("LANDMARKS", M, statY + 20f, labelPaint)

        val countries = items.map { it.extractCountry() }.distinct().size
        c.drawText("$countries", M + 160f, statY, valuePaint)
        c.drawText("COUNTRIES", M + 160f, statY + 20f, labelPaint)

        // Date range
        val dates = items.mapNotNull {
            it.createdAt.substringBefore("T").takeIf { d -> d.isNotEmpty() }
        }.sorted()
        if (dates.isNotEmpty()) {
            val rangeStr = if (dates.size == 1) dates.first()
                          else "${dates.first()}  –  ${dates.last()}"
            c.drawText(rangeStr, M, statY + 72f, textPaint(Color.WHITE, 13f).apply { alpha = 160 })
        }

        // Bottom gold bar
        c.drawRect(0f, H - 6f, W.toFloat(), H.toFloat(), paint { color = colorGold })

        document.finishPage(page)
    }

    // ── Landmark page ─────────────────────────────────────────────────────────

    private fun drawLandmarkPage(
        document: PdfDocument,
        item: SavedPhotosFragment.SavedLandmark,
        photo: Bitmap?,
        pageNum: Int
    ) {
        val page = document.startPage(PdfDocument.PageInfo.Builder(W, H, pageNum).create())
        val c = page.canvas

        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), paint { color = Color.WHITE })

        val photoH = 256f
        drawPhoto(c, photo, photoH)
        c.drawRect(0f, photoH, W.toFloat(), photoH + 4f, paint { color = colorGold })

        var y = photoH + 28f
        y = drawText(c, safe(item.name), M, y, textPaint(colorDeepBlue, 22f, Typeface.BOLD)) + 4f
        if (item.location.isNotEmpty()) {
            y = drawText(c, "\u25B8  ${safe(item.location)}", M, y, textPaint(colorGray, 12f)) + 2f
        }
        val date = item.createdAt.substringBefore("T").ifEmpty { "" }
        if (date.isNotEmpty()) { c.drawText(safe(date), M, y, textPaint(colorGray, 11f)) }
        val safeRating = item.rating.coerceIn(0, 5)
        if (safeRating > 0) {
            val stars = "★".repeat(safeRating) + "☆".repeat(5 - safeRating)
            val sp = textPaint(colorGold, 13f)
            c.drawText(stars, W - M - sp.measureText(stars), y, sp)
        }
        y += 22f
        c.drawLine(M, y, W - M, y, paint { color = colorDivider; strokeWidth = 1f })
        y += 16f

        if (item.narrativeP1.isNotEmpty()) {
            y = drawWrapped(c, item.narrativeP1, M, y, textPaint(colorText, 11.5f), CW) + 6f
        }

        if (item.narrativeQuote.isNotEmpty() && y < H - 100f) {
            y += 6f
            val quoteText = "\u201C${item.narrativeQuote.trim()
                .trimStart('\u201C').trimEnd('\u201D')}\u201D"
            val qPaint = textPaint(colorDeepBlue, 10.5f, Typeface.ITALIC)
            val sl = buildStaticLayout(quoteText, qPaint, CW - 32)
            val blockH = sl.height + 24f
            if (y + blockH < H - 60f) {
                c.drawRoundRect(RectF(M, y, W - M, y + blockH), 8f, 8f, paint { color = colorQuoteBg })
                c.drawRect(M, y, M + 4f, y + blockH, paint { color = colorGold })
                c.save(); c.translate(M + 16f, y + 12f); sl.draw(c); c.restore()
                y += blockH + 10f
            }
        }

        if (item.narrativeP2.isNotEmpty() && y < H - 80f) {
            y += 6f
            drawWrapped(c, item.narrativeP2, M, y, textPaint(colorText, 11.5f), CW)
        }

        // Footer
        val footerY = H - 20f
        c.drawLine(M, footerY - 10f, W - M, footerY - 10f,
            paint { color = colorDivider; strokeWidth = 0.5f })
        c.drawText("SightAI Travel Journal", M, footerY, textPaint(colorGray, 9f))
        val pageStr = "Page ${pageNum - 1}"
        val pgPaint = textPaint(colorGray, 9f)
        c.drawText(pageStr, W - M - pgPaint.measureText(pageStr), footerY, pgPaint)

        document.finishPage(page)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun drawPhoto(canvas: Canvas, photo: Bitmap?, photoH: Float) {
        if (photo != null) {
            val scale = maxOf(W.toFloat() / photo.width, photoH / photo.height)
            val scaledW = photo.width * scale
            val scaledH = photo.height * scale
            val matrix = Matrix().apply {
                postScale(scale, scale)
                postTranslate((W - scaledW) / 2f, (photoH - scaledH) / 2f)
            }
            canvas.save()
            canvas.clipRect(0f, 0f, W.toFloat(), photoH)
            canvas.drawBitmap(photo, matrix, null)
            canvas.restore()
        } else {
            val shader = LinearGradient(0f, 0f, W.toFloat(), photoH,
                colorDeepBlue, Color.parseColor("#0f3460"), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, W.toFloat(), photoH, paint { this.shader = shader })
        }
    }

    /** Draw single-line text, return new Y after line */
    private fun drawText(canvas: Canvas, text: String, x: Float, y: Float, p: TextPaint): Float {
        canvas.drawText(text, x, y, p)
        return y + (p.textSize * 1.4f)
    }

    /** Draw multi-line wrapped text, return new Y after block */
    private fun drawWrapped(canvas: Canvas, text: String, x: Float, y: Float,
                            p: TextPaint, maxWidth: Int): Float {
        if (text.isEmpty()) return y
        val sl = buildStaticLayout(text, p, maxWidth)
        canvas.save(); canvas.translate(x, y); sl.draw(canvas); canvas.restore()
        return y + sl.height
    }

    /** Remove supplementary-plane characters (emoji, etc.) that crash Skia's PDF canvas */
    private fun safe(text: String): String = buildString {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (cp <= 0xFFFF) append(cp.toChar())
            i += Character.charCount(cp)
        }
    }

    private fun buildStaticLayout(text: String, paint: TextPaint, width: Int): StaticLayout {
        val safeText = safe(text)
        return StaticLayout.Builder.obtain(safeText, 0, safeText.length, paint, width)
            .setLineSpacing(3f, 1f)
            .build()
    }

    private fun paint(block: Paint.() -> Unit) = Paint(Paint.ANTI_ALIAS_FLAG).apply(block)

    private fun textPaint(color: Int, size: Float, style: Int = Typeface.NORMAL) =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            typeface = when (style) {
                Typeface.BOLD   -> tfBold
                Typeface.ITALIC -> tfItalic
                else            -> tfNormal
            }
        }

    private fun loadBitmap(url: String): Bitmap? {
        if (url.isEmpty()) return null
        return try {
            // Load at half resolution to reduce PDF canvas memory usage
            val bmp = Glide.with(context.applicationContext).asBitmap().load(url)
                .submit(W / 2, 128).get(15, TimeUnit.SECONDS)
            // Ensure RGB_565 (half the memory of ARGB_8888)
            if (bmp.config == Bitmap.Config.ARGB_8888) {
                val small = bmp.copy(Bitmap.Config.RGB_565, false)
                bmp.recycle()
                small
            } else bmp
        } catch (e: Exception) {
            android.util.Log.w("JournalPdf", "Image load failed: $url")
            null
        }
    }
}
