package com.dlh.helper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A non-2xx answer. [code] is the HTTP status, [body] the start of the response text. */
class HttpStatusException(val code: Int, val body: String, val where: String) :
    IOException("HTTP $code at $where: ${body.take(200)}")

/** [url] is the address the answer came from after any redirects. */
class Reply(
    val code: Int,
    val body: String,
    val retryAfterSec: Long?,
    val rateResetSec: Long? = null,
    val url: String = ""
)

object Net {
    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

/** Runs an OkHttp call as a cancellable suspend function. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }
    })
    cont.invokeOnCancellation { cancel() }
}

/** Sends the request and reads the whole body on the IO dispatcher (reading a body on the main thread crashes on Android). */
suspend fun OkHttpClient.fetch(req: Request): Reply {
    val response = newCall(req).await()
    return withContext(Dispatchers.IO) {
        response.use { r ->
            Reply(
                r.code,
                r.body?.string().orEmpty(),
                r.header("Retry-After")?.toLongOrNull(),
                r.header("x-rate-limit-reset")?.toLongOrNull(),
                r.request.url.toString()
            )
        }
    }
}

/** String value of a JSON field, or null when it is missing, null or empty. */
fun JSONObject.str(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }
