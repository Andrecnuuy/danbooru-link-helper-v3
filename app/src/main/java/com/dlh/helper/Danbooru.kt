package com.dlh.helper

import kotlinx.coroutines.delay
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

data class Me(val id: Long, val name: String, val level: String)

data class ArtistHit(val id: Long, val name: String, val isDeleted: Boolean, val isBanned: Boolean)

class TagSources(val sources: List<String>, val posts: Int)

/**
 * Thin Danbooru API client. Auth is the account name + API key sent as HTTP Basic,
 * so everything the account is allowed to see (hidden/banned-artist posts, own uploads) is visible.
 */
class DanbooruClient(
    baseUrl: String,
    private val login: String,
    private val apiKey: String,
    private val delayMs: Long,
    private val log: (String) -> Unit
) {
    private val base: String = normalizeBase(baseUrl)

    val hasAuth: Boolean get() = login.isNotBlank() && apiKey.isNotBlank()

    companion object {
        const val UA = "DanbooruLinkHelper/1.0 (personal link tool)"

        fun normalizeBase(raw: String): String {
            var s = raw.trim().trimEnd('/')
            if (s.isEmpty()) s = "https://danbooru.donmai.us"
            if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "https://$s"
            return s
        }
    }

    private fun buildRequest(path: String, params: List<Pair<String, String>>): Request {
        val b = (base + path).toHttpUrl().newBuilder()
        for ((k, v) in params) b.addQueryParameter(k, v)
        val rb = Request.Builder()
            .url(b.build())
            .header("User-Agent", UA)
            .header("Accept", "application/json")
        if (hasAuth) rb.header("Authorization", Credentials.basic(login.trim(), apiKey.trim()))
        return rb.build()
    }

    /** GET with polite pacing, retry on 429/5xx/network errors, and a clear exception otherwise. */
    suspend fun getText(path: String, params: List<Pair<String, String>> = emptyList()): String {
        val req = buildRequest(path, params)
        var attempt = 0
        while (true) {
            attempt++
            val reply = try {
                Net.client.fetch(req)
            } catch (e: IOException) {
                if (attempt >= 4) throw e
                log("Network error (${e.message}); retry $attempt")
                delay(1500L * attempt)
                continue
            }
            when {
                reply.code in 200..299 -> {
                    if (delayMs > 0) delay(delayMs)
                    return reply.body
                }
                (reply.code == 429 || reply.code >= 500) && attempt < 5 -> {
                    val waitSec = reply.retryAfterSec ?: (2L * attempt)
                    log("HTTP ${reply.code} from $path; waiting ${waitSec}s then retrying")
                    delay(waitSec * 1000)
                }
                else -> throw HttpStatusException(reply.code, reply.body, path)
            }
        }
    }

    /** Validates the API key and returns the account it belongs to. */
    suspend fun profile(): Me {
        val o = JSONObject(getText("/profile.json"))
        return Me(o.getLong("id"), o.optString("name"), o.optString("level_string"))
    }

    /** Looks up artists by profile URL, trying each spelling until one matches. */
    suspend fun findArtists(variants: List<String>): List<ArtistHit> {
        for (v in variants) {
            log("Artist search: url_matches=$v")
            val arr = JSONArray(getText("/artists.json", listOf("search[url_matches]" to v, "limit" to "10")))
            if (arr.length() == 0) continue
            val hits = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ArtistHit(o.getLong("id"), o.optString("name"), o.optBoolean("is_deleted"), o.optBoolean("is_banned"))
            }
            return hits.sortedWith(compareBy<ArtistHit>({ it.isDeleted }, { it.isBanned }))
        }
        return emptyList()
    }

    /** The artist with exactly this tag name, or null. Used when the artist tag was typed in by hand. */
    suspend fun findArtistByName(name: String): ArtistHit? {
        val n = name.trim().lowercase().replace(' ', '_')
        if (n.isEmpty()) return null
        for (param in listOf("search[name]", "search[any_name_matches]")) {
            log("Artist search: $param=$n")
            val arr = JSONArray(getText("/artists.json", listOf(param to n, "limit" to "5")))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optString("name") == n) {
                    return ArtistHit(o.getLong("id"), n, o.optBoolean("is_deleted"), o.optBoolean("is_banned"))
                }
            }
        }
        return null
    }

    /**
     * Reads the URL list of an artist (the "-" ones come back with active = false). Danbooru's response shape for this is
     * not something I could check from here, so several endpoints are tried in turn and the first one that
     * yields any URLs wins; [log] says which one worked.
     */
    suspend fun artistUrls(artistId: Long, artistName: String): List<ArtistUrl> {
        val attempts = listOf<Pair<String, List<Pair<String, String>>>>(
            "/artists/$artistId.json" to listOf("only" to "id,name,urls"),
            "/artist_urls.json" to listOf("search[artist_id]" to artistId.toString(), "limit" to "1000"),
            "/artist_urls.json" to listOf("search[artist][name]" to artistName, "limit" to "1000"),
            "/artists/$artistId.json" to emptyList()
        )
        for ((path, params) in attempts) {
            val urls = try {
                ArtistUrlParse.parse(getText(path, params))
            } catch (e: HttpStatusException) {
                log("Artist URLs via $path: HTTP ${e.code}")
                continue
            }
            if (urls.isNotEmpty()) {
                log("Artist URLs via $path${if (params.isEmpty()) "" else params.joinToString("&", "?") { "${it.first}=${it.second}" }}: ${urls.size} entries")
                return urls
            }
            log("Artist URLs via $path: nothing usable in the answer")
        }
        return emptyList()
    }

    /**
     * Walks every post under [tag] (newest to oldest, 200 per page) and returns each post's source string.
     * With [includeDeleted] the search adds status:any so deleted posts count too.
     */
    suspend fun fetchTagSources(tag: String, includeDeleted: Boolean, onProgress: (Int) -> Unit): TagSources {
        var withDeleted = includeDeleted
        val sources = ArrayList<String>()
        var total = 0
        var before: Long? = null
        while (true) {
            val tags = if (withDeleted) "$tag status:any" else tag
            val params = mutableListOf(
                "tags" to tags,
                "limit" to "200",
                "only" to "id,source"
            )
            if (before != null) params.add("page" to "b$before")
            val text = try {
                getText("/posts.json", params)
            } catch (e: HttpStatusException) {
                if (withDeleted && before == null && e.code == 422) {
                    log("Tag limit hit with status:any; continuing without deleted posts")
                    withDeleted = false
                    continue
                }
                throw e
            }
            val arr = JSONArray(text)
            if (arr.length() == 0) break
            var minId = Long.MAX_VALUE
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.getLong("id")
                if (id < minId) minId = id
                o.str("source")?.let { sources.add(it) }
            }
            total += arr.length()
            onProgress(total)
            if (arr.length() < 200) break
            before = minId
        }
        return TagSources(sources, total)
    }
}
