package com.dlh.helper

import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * What Pixiv says about one work: [type] is 0 illustration, 1 manga, 2 ugoira (animated).
 * [width]/[height] are the pixel size of the first page (0 when Pixiv did not say), [userName] the artist's display name.
 */
class PixivWork(
    val id: String,
    val type: Int,
    val thumb: String?,
    val pages: Int,
    val timeMs: Long,
    val width: Int = 0,
    val height: Int = 0,
    val userName: String? = null
)

/**
 * Lists every illustration and manga id of a Pixiv user through the same public JSON endpoint the
 * website uses. Pixiv only includes R-18 works when the request carries a logged-in session
 * (and the logged-in account has R-18 display turned on in its Pixiv settings).
 */
object PixivScanner {

    suspend fun scan(userId: String, cookie: String?): List<LinkItem> {
        val reply = Net.client.fetch(request("https://www.pixiv.net/ajax/user/$userId/profile/all?lang=en", cookie))
        if (reply.code !in 200..299) throw HttpStatusException(reply.code, reply.body, "pixiv profile/all")
        return parseProfileAll(reply.body)
    }

    private fun request(url: String, cookie: String?): Request {
        val rb = Request.Builder()
            .url(url)
            .header("User-Agent", Net.DESKTOP_UA)
            .header("Referer", "https://www.pixiv.net/")
            .header("Accept", "application/json")
        if (!cookie.isNullOrBlank()) rb.header("Cookie", cookie)
        return rb.build()
    }

    /** Reads the response of /ajax/user/<id>/profile/all into one link per illustration or manga work. */
    fun parseProfileAll(text: String): List<LinkItem> {
        val root = JSONObject(text)
        if (root.optBoolean("error", false)) {
            throw IOException("Pixiv said: " + (root.optString("message").ifEmpty { "unknown error" }))
        }
        val body = root.optJSONObject("body") ?: throw IOException("Pixiv returned an unexpected response")

        val ids = LinkedHashSet<String>()
        for (field in listOf("illusts", "manga")) {
            // Pixiv sends [] instead of {} when a section is empty, so optJSONObject handles both.
            val section = body.optJSONObject(field) ?: continue
            val keys = section.keys()
            while (keys.hasNext()) ids.add(keys.next())
        }
        return ids.map { LinkItem("px:$it", UrlParsers.pixivLink(it)) }
    }

    /**
     * Asks Pixiv for the type, thumbnail and date of many works at once (48 per request).
     * Used when the scan is limited to images or videos, because the works list alone carries no types.
     */
    suspend fun fetchDetails(
        userId: String,
        ids: List<String>,
        cookie: String?,
        onProgress: (Int) -> Unit
    ): Map<String, PixivWork> {
        val out = LinkedHashMap<String, PixivWork>()
        var done = 0
        for (chunk in ids.chunked(48)) {
            val b = "https://www.pixiv.net/ajax/user/$userId/profile/illusts".toHttpUrl().newBuilder()
            for (id in chunk) b.addQueryParameter("ids[]", id)
            b.addQueryParameter("work_category", "illustManga")
            b.addQueryParameter("is_first_page", "0")
            b.addQueryParameter("lang", "en")
            val reply = Net.client.fetch(request(b.build().toString(), cookie))
            if (reply.code !in 200..299) throw HttpStatusException(reply.code, reply.body, "pixiv profile/illusts")
            out.putAll(parseWorks(reply.body))
            done += chunk.size
            onProgress(done)
            delay(300)
        }
        return out
    }

    /**
     * Fills the items with what [details] says (thumbnail, type, page count, date) and drops the ones that
     * [filter] does not keep. Items Pixiv gave no details for count as plain images and stay as they are.
     * Animated works (type 2, ugoira) are the "videos" of Pixiv.
     */
    fun applyDetails(items: List<LinkItem>, details: Map<String, PixivWork>, filter: MediaFilter): List<LinkItem> {
        val out = ArrayList<LinkItem>(items.size)
        for (item in items) {
            val work = details[item.key.substringAfter(':')]
            val animated = work != null && work.type == 2
            if (!filter.keeps(if (animated) 0 else 1, if (animated) 1 else 0)) continue
            out.add(
                if (work == null) {
                    item
                } else {
                    item.copy(
                        thumb = work.thumb ?: item.thumb,
                        kind = if (animated) KIND_VIDEO else KIND_IMAGE,
                        count = work.pages,
                        timeMs = if (work.timeMs != 0L) work.timeMs else item.timeMs,
                        sizes = if (work.width > 0 && work.height > 0) listOf(work.width, work.height) else item.sizes
                    )
                }
            )
        }
        return out
    }

    /** Reads the response of /ajax/user/<id>/profile/illusts: a map of work id to details. */
    fun parseWorks(text: String): Map<String, PixivWork> {
        val root = JSONObject(text)
        if (root.optBoolean("error", false)) {
            throw IOException("Pixiv said: " + (root.optString("message").ifEmpty { "unknown error" }))
        }
        val body = root.optJSONObject("body") ?: throw IOException("Pixiv returned an unexpected response")
        val works = body.optJSONObject("works") ?: body
        val out = LinkedHashMap<String, PixivWork>()
        val keys = works.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            if (id.isEmpty() || !id.all { it.isDigit() }) continue
            val o = works.optJSONObject(id) ?: continue
            out[id] = PixivWork(
                id = id,
                type = o.optInt("illustType", 0),
                thumb = o.optString("url").ifEmpty { null },
                pages = maxOf(1, o.optInt("pageCount", 1)),
                timeMs = parseDate(o.optString("createDate")),
                width = o.optInt("width", 0),
                height = o.optInt("height", 0),
                userName = o.optString("userName").ifEmpty { null }
            )
        }
        return out
    }

    /** The display name of a Pixiv user (/ajax/user/<id>), or null when it cannot be read. */
    suspend fun userName(userId: String, cookie: String?): String? {
        return try {
            val reply = Net.client.fetch(request("https://www.pixiv.net/ajax/user/$userId?full=0&lang=en", cookie))
            if (reply.code !in 200..299) null
            else JSONObject(reply.body).optJSONObject("body")?.optString("name")?.ifEmpty { null }
        } catch (e: IOException) {
            null
        } catch (e: org.json.JSONException) {
            null
        }
    }

    /**
     * Best effort: the numeric user id behind a "pixiv.net/stacc/<name>" link. Pixiv used to redirect these to the
     * profile page; if it still does, the final address (or the page's canonical link) carries the id. Null when not found.
     */
    suspend fun resolveStacc(name: String, cookie: String?): String? {
        return try {
            val rb = Request.Builder()
                .url("https://www.pixiv.net/stacc/$name")
                .header("User-Agent", Net.DESKTOP_UA)
                .header("Accept", "text/html,application/xhtml+xml")
            if (!cookie.isNullOrBlank()) rb.header("Cookie", cookie)
            val reply = Net.client.fetch(rb.build())
            if (reply.code !in 200..299) null else userIdFromPage(reply.url, reply.body)
        } catch (e: IOException) {
            null
        }
    }

    private val usersInUrl = Regex("""pixiv\.net/(?:[a-z]{2}(?:-[a-z]{2})?/)?users/(\d+)""", RegexOption.IGNORE_CASE)
    private val canonical = Regex(
        """<(?:link[^>]*rel=["']canonical["'][^>]*href|meta[^>]*property=["']og:url["'][^>]*content)=["']([^"']+)["']""",
        RegexOption.IGNORE_CASE
    )

    /** Finds a Pixiv user id in where a request ended up, or in the page's canonical / og:url link. */
    fun userIdFromPage(finalUrl: String, body: String): String? {
        usersInUrl.find(finalUrl)?.let { return it.groupValues[1] }
        for (m in canonical.findAll(body)) {
            usersInUrl.find(m.groupValues[1])?.let { return it.groupValues[1] }
        }
        return null
    }

    /** "2020-01-02T03:04:05+09:00" to epoch milliseconds; 0 when missing or unreadable. */
    fun parseDate(text: String): Long {
        if (text.isEmpty()) return 0L
        return try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(text)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
}
