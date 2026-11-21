package com.dlh.helper

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Everything needed to show one post: a small [thumb] (and an even smaller [thumbLow] for low-data mode),
 * the [full] picture or video, whether [full] is a [video], whether to show a play [badge], and where it
 * has to be fetched from ([referer], Pixiv's image server refuses requests without it).
 */
class MediaInfo(
    val thumb: String?,
    val thumbLow: String?,
    val full: String?,
    val video: Boolean,
    val badge: Boolean,
    val count: Int,
    val timeMs: Long,
    val referer: String?
)

object Previews {
    const val PIXIV_REFERER = "https://www.pixiv.net/"

    private val gate = Semaphore(3)
    private val cache = ConcurrentHashMap<String, MediaInfo>()

    /** Another size of a pbs.twimg.com/media/ picture (thumb 150px, small 680px, large 2048px). Other URLs are unchanged. */
    fun twimgSized(url: String, name: String): String {
        if (!url.contains("/media/")) return url
        val clean = url.substringBefore('?')
        val slash = clean.lastIndexOf('/')
        val dot = clean.lastIndexOf('.')
        if (dot <= slash) return url
        return clean.substring(0, dot) + "?format=" + clean.substring(dot + 1) + "&name=" + name
    }

    /**
     * The preview data stored on the item itself (no network): X posts always have it, Pixiv works only when the
     * scan fetched their details. [MediaInfo.full] is null when only a thumbnail is known. Null when there is no thumbnail at all.
     */
    fun fromItem(item: LinkItem): MediaInfo? {
        val t = item.thumb ?: return null
        val isX = item.key.startsWith("tw:")
        val clip = item.full
        val video = isX && item.kind == KIND_VIDEO && clip != null && clip.contains(".mp4")
        return MediaInfo(
            thumb = if (isX) twimgSized(t, "small") else t,
            thumbLow = if (isX) twimgSized(t, "thumb") else t,
            // X pictures are previewed at medium size (1200 px) rather than the 2048 px the link list keeps.
            full = if (!isX) clip else if (video) clip else twimgSized(t, "medium"),
            video = video,
            badge = item.kind != KIND_IMAGE,
            count = item.count,
            timeMs = item.timeMs,
            referer = if (isX) null else PIXIV_REFERER
        )
    }

    /** Preview data for [item] including the big picture; Pixiv works are looked up once and remembered. */
    suspend fun resolve(item: LinkItem, cookie: String?): MediaInfo? {
        val stored = fromItem(item)
        if (stored != null && stored.full != null) return stored
        val known = cache[item.key]
        if (known != null) return known
        if (!item.key.startsWith("px:")) return stored
        val found = gate.withPermit { pixivInfo(item.key.substringAfter(':'), cookie) }
        if (found != null) {
            cache[item.key] = found
            return found
        }
        return stored
    }

    /** One work from /ajax/illust/<id>: picture URLs, type, page count and date. */
    suspend fun pixivInfo(id: String, cookie: String?): MediaInfo? {
        val rb = Request.Builder()
            .url("https://www.pixiv.net/ajax/illust/$id?lang=en")
            .header("User-Agent", Net.DESKTOP_UA)
            .header("Referer", PIXIV_REFERER)
            .header("Accept", "application/json")
        if (!cookie.isNullOrBlank()) rb.header("Cookie", cookie)
        val reply = try {
            Net.client.fetch(rb.build())
        } catch (e: IOException) {
            return null
        }
        if (reply.code !in 200..299) return null
        return parsePixivInfo(reply.body)
    }

    fun parsePixivInfo(text: String): MediaInfo? {
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            return null
        }
        val body = root.optJSONObject("body") ?: return null
        val urls = body.optJSONObject("urls") ?: return null
        val thumb = urls.optString("small").ifEmpty { urls.optString("thumb") }.ifEmpty { null }
        val low = urls.optString("thumb").ifEmpty { thumb.orEmpty() }.ifEmpty { null }
        val full = urls.optString("regular").ifEmpty { urls.optString("original") }.ifEmpty { thumb.orEmpty() }.ifEmpty { null }
        return MediaInfo(
            thumb = thumb,
            thumbLow = low,
            full = full,
            video = false,
            badge = body.optInt("illustType", 0) == 2,
            count = maxOf(1, body.optInt("pageCount", 1)),
            timeMs = PixivScanner.parseDate(body.optString("createDate")),
            referer = PIXIV_REFERER
        )
    }
}
