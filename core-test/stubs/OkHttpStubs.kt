package okhttp3

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit

class HttpUrl {
    class Builder {
        fun addQueryParameter(name: String, value: String?): Builder = this
        fun build(): HttpUrl = HttpUrl()
    }
    fun newBuilder(): Builder = Builder()
    companion object {
        fun String.toHttpUrl(): HttpUrl = HttpUrl()
    }
}

class Request {
    val url: HttpUrl = HttpUrl()
    class Builder {
        fun url(url: HttpUrl): Builder = this
        fun url(url: String): Builder = this
        fun header(name: String, value: String): Builder = this
        fun build(): Request = Request()
    }
}

interface Callback {
    fun onFailure(call: Call, e: IOException)
    fun onResponse(call: Call, response: Response)
}

interface Call {
    fun enqueue(responseCallback: Callback)
    fun cancel()
}

abstract class ResponseBody { abstract fun string(): String }

abstract class Response : Closeable {
    abstract val code: Int
    abstract val body: ResponseBody?
    abstract val request: Request
    abstract fun header(name: String): String?
}

class OkHttpClient {
    fun newCall(request: Request): Call = throw NotImplementedError()
    class Builder {
        fun connectTimeout(t: Long, u: TimeUnit): Builder = this
        fun readTimeout(t: Long, u: TimeUnit): Builder = this
        fun writeTimeout(t: Long, u: TimeUnit): Builder = this
        fun build(): OkHttpClient = OkHttpClient()
    }
}

object Credentials { fun basic(username: String, password: String): String = "" }
