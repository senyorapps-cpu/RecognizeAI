package com.example.recognizeai

import android.content.Context
import java.io.File

object LocalImageCache {

    private fun dir(context: Context): File =
        File(context.filesDir, "landmark_photos").also { it.mkdirs() }

    fun getFile(context: Context, serverId: Long): File =
        File(dir(context), "$serverId.jpg")

    fun save(context: Context, serverId: Long, bytes: ByteArray) {
        try {
            getFile(context, serverId).writeBytes(bytes)
        } catch (_: Exception) {}
    }
}
