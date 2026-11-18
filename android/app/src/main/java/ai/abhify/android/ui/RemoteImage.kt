package ai.abhify.android.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 4.6.2: article images without an image library. https only, downscaled while decoding (max ~800 px wide), a small
 * memory cache (8 MB), 3 MB download cap. While loading or on failure the [placeholder] is shown, never another picture.
 */
object ImageCache {
    private val mem = object : LruCache<String, Bitmap>(8 * 1024 * 1024) { override fun sizeOf(key: String, value: Bitmap) = value.byteCount }
    fun get(url: String): Bitmap? = mem.get(url)
    suspend fun load(url: String, maxWidth: Int = 800): Bitmap? = mem.get(url) ?: withContext(Dispatchers.IO) {
        if (!url.startsWith("https://")) return@withContext null
        runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 8_000; c.readTimeout = 10_000; c.setRequestProperty("User-Agent", "Abhify-Android (news reader)")
                if (c.responseCode != 200 || (c.contentLengthLong > 3_000_000)) return@runCatching null
                val bytes = c.inputStream.use { it.readNBytesCompat(3_000_000) }
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                var s = 1; while (o.outWidth / s > maxWidth * 2) s *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
            } finally { c.disconnect() }
        }.getOrNull()?.also { mem.put(url, it) }
    }
    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(16_384); var total = 0
        while (true) { val n = read(buf); if (n < 0 || total + n > max) break; out.write(buf, 0, n); total += n }
        return out.toByteArray()
    }
}

/**
 * 5.6 root-cause fix (news refresh showed the previous first story's image): the old version used Compose produceState with (initial, url),
 * whose state is remembered WITHOUT a key, so a card reused for a new article kept the old bitmap, and its
 * `if (value == null)` guard then skipped loading the new URL. Now the image state belongs to one URL ([ImageSlot], created per
 * URL): a new URL starts empty (placeholder), and a late load for an old URL can only write into that old, discarded slot.
 */
class ImageSlot<B>(val url: String?, initial: B?) {
    var value: B? = initial; private set
    var failed = false; private set
    /** Loads once; the result is stored only in this slot (its own URL). */
    suspend fun resolve(load: suspend (String) -> B?) {
        if (value != null || url == null) return
        val b = load(url); if (b == null) failed = true else value = b
    }
    companion object {
        /** The slot to use for [url]: the same one while the URL is unchanged, a fresh one when it changes. */
        fun <B> forUrl(previous: ImageSlot<B>?, url: String?, cached: (String) -> B?): ImageSlot<B> =
            if (previous != null && previous.url == url) previous else ImageSlot(url, url?.let(cached))
    }
}

@Composable
fun RemoteImage(url: String?, description: String?, modifier: Modifier = Modifier, placeholder: @Composable () -> Unit) {
    val slot = androidx.compose.runtime.remember(url) { ImageSlot(url, url?.let { ImageCache.get(it) }) }
    var b by androidx.compose.runtime.remember(url) { androidx.compose.runtime.mutableStateOf(slot.value) }
    androidx.compose.runtime.LaunchedEffect(url) { slot.resolve { ImageCache.load(it) }; b = slot.value }
    val bmp = b
    if (bmp != null) Image(bmp.asImageBitmap(), description, modifier, contentScale = ContentScale.Crop) else Box(modifier) { placeholder() }
}
