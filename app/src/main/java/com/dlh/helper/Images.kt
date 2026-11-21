package com.dlh.helper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Downloads and shows pictures, as little as possible: a few at a time, only for what is on screen,
 * decoded at the size it is shown, and kept on disk (80 MB) so scrolling back never downloads again.
 */
object ImageLoader {
    private val memory = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val gate = Semaphore(4)

    @Volatile
    private var http: OkHttpClient = Net.client

    /** Sets up the disk cache. Successful answers are kept for a week whatever the site's own cache headers say. */
    fun init(ctx: Context) {
        try {
            http = Net.client.newBuilder()
                .cache(Cache(File(ctx.cacheDir, "images"), 80L * 1024 * 1024))
                .addNetworkInterceptor(Interceptor { chain ->
                    val reply = chain.proceed(chain.request())
                    if (reply.isSuccessful) {
                        reply.newBuilder()
                            .header("Cache-Control", "public, max-age=604800")
                            .removeHeader("Pragma")
                            .build()
                    } else {
                        reply
                    }
                })
                .build()
        } catch (e: Exception) {
            http = Net.client
        }
    }

    private fun keyOf(url: String, targetPx: Int): String = "$targetPx|$url"

    /** The picture when it is already in memory. */
    fun peek(url: String, targetPx: Int): Bitmap? = memory.get(keyOf(url, targetPx))

    /** The picture at [url], decoded to roughly [targetPx] pixels on its short side. Null when it could not be loaded. */
    suspend fun load(url: String, referer: String?, targetPx: Int): Bitmap? {
        val key = keyOf(url, targetPx)
        val cached = memory.get(key)
        if (cached != null) return cached
        return gate.withPermit {
            val again = memory.get(key)
            if (again != null) {
                again
            } else {
                val fresh = fetchAndDecode(url, referer, targetPx)
                if (fresh != null) memory.put(key, fresh)
                fresh
            }
        }
    }

    /** Separate from [gate] so a long similarity run does not hold up thumbnails. */
    private val featureGate = Semaphore(4)

    /**
     * Downloads the picture at [url] and boils it down to a [Feature] for the similarity check. Bypasses the picture cache
     * (each picture is looked at once and the result is kept by the caller). Null when it could not be loaded.
     */
    suspend fun loadFeature(url: String, referer: String?): Feature? {
        return featureGate.withPermit {
            val bytes = fetchBytes(url, referer)
            if (bytes == null) {
                null
            } else {
                withContext(Dispatchers.Default) {
                    val bmp = decode(bytes, 96)
                    if (bmp == null) {
                        null
                    } else {
                        try {
                            val w = bmp.width
                            val h = bmp.height
                            val px = IntArray(w * h)
                            bmp.getPixels(px, 0, w, 0, 0, w, h)
                            Similarity.featureFromArgb(px, w, h)
                        } finally {
                            bmp.recycle()
                        }
                    }
                }
            }
        }
    }

    private suspend fun fetchBytes(url: String, referer: String?): ByteArray? {
        val request = try {
            val rb = Request.Builder()
                .url(url)
                .header("User-Agent", Net.DESKTOP_UA)
                .header("Accept", "image/avif,image/webp,image/*,*/*;q=0.8")
            if (referer != null) rb.header("Referer", referer)
            rb.build()
        } catch (e: IllegalArgumentException) {
            return null
        }
        val response = try {
            Net.client.newCall(request).await()
        } catch (e: IOException) {
            return null
        }
        return try {
            withContext(Dispatchers.IO) {
                response.use { r -> if (r.isSuccessful) r.body?.bytes() else null }
            }
        } catch (e: CancellationException) {
            response.close()
            throw e
        } catch (e: IOException) {
            null
        }
    }

    private suspend fun fetchAndDecode(url: String, referer: String?, targetPx: Int): Bitmap? {
        val request = try {
            val rb = Request.Builder()
                .url(url)
                .header("User-Agent", Net.DESKTOP_UA)
                .header("Accept", "image/avif,image/webp,image/*,*/*;q=0.8")
            if (referer != null) rb.header("Referer", referer)
            rb.build()
        } catch (e: IllegalArgumentException) {
            return null
        }
        val response = try {
            http.newCall(request).await()
        } catch (e: IOException) {
            return null
        }
        return try {
            withContext(Dispatchers.IO) {
                response.use { r ->
                    if (!r.isSuccessful) {
                        null
                    } else {
                        val bytes = r.body?.bytes()
                        if (bytes == null) null else decode(bytes, targetPx)
                    }
                }
            }
        } catch (e: CancellationException) {
            response.close()
            throw e
        } catch (e: IOException) {
            null
        }
    }

    private fun decode(bytes: ByteArray, targetPx: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options()
            bounds.inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                null
            } else {
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outHeight / (sample * 2) >= targetPx) sample *= 2
                val opts = BitmapFactory.Options()
                opts.inSampleSize = sample
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            }
        } catch (e: OutOfMemoryError) {
            null
        }
    }
}

/** A picture loaded from [url] (nothing is requested while it is not on screen). Shows a spinner, then the picture or "no image". */
@Composable
fun RemoteImage(
    url: String?,
    referer: String?,
    targetPx: Int,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    var bitmap by remember(url, targetPx) {
        mutableStateOf<Bitmap?>(if (url != null) ImageLoader.peek(url, targetPx) else null)
    }
    var failed by remember(url, targetPx) { mutableStateOf(url == null) }

    LaunchedEffect(url, referer, targetPx) {
        if (url != null && bitmap == null) {
            val loaded = ImageLoader.load(url, referer, targetPx)
            if (loaded != null) bitmap = loaded else failed = true
        }
    }

    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        val shown = bitmap
        if (shown != null) {
            val image = remember(shown) { shown.asImageBitmap() }
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize()
            )
        } else if (failed) {
            Text(
                "no image",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}

/** Plays the video at [url] in a loop; a tap pauses and resumes. [onError] is called when it cannot be played. */
@Composable
fun VideoPlayer(url: String, modifier: Modifier = Modifier, onError: () -> Unit) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val view = VideoView(ctx)
            view.setVideoURI(Uri.parse(url))
            view.setOnPreparedListener { player ->
                player.isLooping = true
                view.start()
            }
            view.setOnErrorListener { _, _, _ ->
                onError()
                true
            }
            view.setOnClickListener {
                if (view.isPlaying) view.pause() else view.start()
            }
            view
        },
        onRelease = { it.stopPlayback() }
    )
}
