package com.vynex.gumshoos

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Contact thumbnails, decoded on demand and kept in a small cache.
 *
 * Deliberately lazy, unlike app icons. There can be thousands of contacts against a few hundred
 * apps, and only a handful of rows are ever on screen, so decoding the lot at warm time would cost
 * far more memory and time than it could ever save.
 */
object ContactPhotos {

    private val cache = mutableStateMapOf<Long, ImageBitmap?>()

    /** Null means either not loaded yet or no photo — callers fall back to the initial. */
    fun cached(id: Long): ImageBitmap? = cache[id]

    fun isLoaded(id: Long): Boolean = cache.containsKey(id)

    suspend fun load(context: Context, entry: ContactEntry) {
        if (cache.containsKey(entry.id)) return
        val uri = entry.photoUri
        if (uri == null) {
            cache[entry.id] = null
            return
        }
        val bitmap = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use {
                    BitmapFactory.decodeStream(it)?.asImageBitmap()
                }
            }.getOrNull()
        }
        cache[entry.id] = bitmap
    }

    /**
     * Stable colour for the letter fallback, derived from the name so a contact keeps the same
     * colour between launches.
     */
    fun fallbackColour(name: String): Long {
        val palette = listOf(
            0xFFD32F2FL, 0xFFC2185BL, 0xFF7B1FA2L, 0xFF512DA8L, 0xFF303F9FL,
            0xFF1976D2L, 0xFF0288D1L, 0xFF00796BL, 0xFF388E3CL, 0xFFF57C00L,
            0xFFE64A19L, 0xFF5D4037L,
        )
        val hash = name.fold(0) { acc, ch -> acc * 31 + ch.code }
        return palette[Math.floorMod(hash, palette.size)]
    }

    fun initial(name: String): String =
        name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
}
