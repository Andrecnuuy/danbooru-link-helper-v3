package com.dlh.helper

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** User settings, kept in the app's private storage. */
class AppSettings(ctx: Context) {
    private val p = ctx.getSharedPreferences("dlh_settings", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = p.getString("base", "https://danbooru.donmai.us") ?: "https://danbooru.donmai.us"
        set(v) { p.edit().putString("base", v.trim()).apply() }

    var login: String
        get() = p.getString("login", "") ?: ""
        set(v) { p.edit().putString("login", v.trim()).apply() }

    var apiKey: String
        get() = p.getString("api_key", "") ?: ""
        set(v) { p.edit().putString("api_key", v.trim()).apply() }

    var includeDeleted: Boolean
        get() = p.getBoolean("include_deleted", true)
        set(v) { p.edit().putBoolean("include_deleted", v).apply() }

    var onlyUnposted: Boolean
        get() = p.getBoolean("only_unposted", true)
        set(v) { p.edit().putBoolean("only_unposted", v).apply() }

    var delayMs: Long
        get() = p.getLong("delay_ms", 300L)
        set(v) { p.edit().putLong("delay_ms", v.coerceIn(0L, 10_000L)).apply() }

    var twitterHost: String
        get() = p.getString("tw_host", "x.com") ?: "x.com"
        set(v) { p.edit().putString("tw_host", v).apply() }

    var newestFirst: Boolean
        get() = p.getBoolean("newest_first", true)
        set(v) { p.edit().putBoolean("newest_first", v).apply() }

    /** Which kinds of posts a scan keeps (MediaFilter name). */
    var mediaFilter: MediaFilter
        get() = try {
            MediaFilter.valueOf(p.getString("media_filter", "BOTH") ?: "BOTH")
        } catch (e: IllegalArgumentException) {
            MediaFilter.BOTH
        }
        set(v) { p.edit().putString("media_filter", v.name).apply() }

    /** Show the result as a grid of thumbnails instead of a list of links. */
    var gridView: Boolean
        get() = p.getBoolean("grid_view", false)
        set(v) { p.edit().putBoolean("grid_view", v).apply() }

    /** Load the smallest thumbnails in the grid to save mobile data. */
    var lowData: Boolean
        get() = p.getBoolean("low_data", true)
        set(v) { p.edit().putBoolean("low_data", v).apply() }

    /** How alike two pictures must look to be listed in the Similar images tab (percent). */
    var simThreshold: Int
        get() = p.getInt("sim_threshold", Similarity.DEFAULT_THRESHOLD).coerceIn(50, 100)
        set(v) { p.edit().putInt("sim_threshold", v.coerceIn(50, 100)).apply() }

    /** X's GraphQL query ids typed in by hand. Empty means: look them up automatically. */
    var twUserQid: String
        get() = p.getString("tw_user_qid", "") ?: ""
        set(v) { p.edit().putString("tw_user_qid", v.trim()).apply() }

    var twMediaQid: String
        get() = p.getString("tw_media_qid", "") ?: ""
        set(v) { p.edit().putString("tw_media_qid", v.trim()).apply() }

    /** The query ids found automatically last time; tried first, and looked up again when X says 404. */
    var twUserQidFound: String
        get() = p.getString("tw_user_qid_found", "") ?: ""
        set(v) { p.edit().putString("tw_user_qid_found", v).apply() }

    var twMediaQidFound: String
        get() = p.getString("tw_media_qid_found", "") ?: ""
        set(v) { p.edit().putString("tw_media_qid_found", v).apply() }
}

data class AssetStats(
    val links: Int,
    val uploads: Int,
    val twitter: Int,
    val pixiv: Int,
    val other: Int,
    val lastSeenId: Long,
    val lastFetchAt: Long
) {
    companion object {
        val EMPTY = AssetStats(0, 0, 0, 0, 0, 0L, 0L)
    }
}

data class ImportResult(val added: Int, val total: Int)

/**
 * The database of source links collected from the user's Danbooru media assets.
 * Stored as one JSON file. [lastSeenId] is the highest upload id seen so far: the next fetch stops there.
 */
class AssetStore(private val file: File) {
    private val lock = Any()
    private var entries = LinkedHashSet<AssetEntry>()

    var lastSeenId: Long = 0L
        private set
    var lastFetchAt: Long = 0L
        private set

    /** Match keys (tw:/tm:/px:) of every stored link. Replaced as a whole, so safe to read from any thread. */
    @Volatile
    var index: Set<String> = emptySet()
        private set

    @Volatile
    var stats: AssetStats = AssetStats.EMPTY
        private set

    fun load() {
        synchronized(lock) {
            if (!file.exists()) return
            val o = JSONObject(file.readText())
            lastSeenId = o.optLong("lastSeenId", 0L)
            lastFetchAt = o.optLong("lastFetchAt", 0L)
            val arr = o.optJSONArray("entries") ?: JSONArray()
            val set = LinkedHashSet<AssetEntry>(arr.length() * 2)
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                val u = e.optString("u")
                if (u.isNotBlank()) set.add(AssetEntry(e.optLong("i", 0L), u))
            }
            entries = set
            rebuild()
        }
    }

    /** Adds new links, moves the fetch marker forward, saves. Returns how many links were actually new. */
    fun addAndSave(newEntries: List<AssetEntry>, newestId: Long): Int {
        return synchronized(lock) {
            var added = 0
            val keys = HashSet<String>(index)
            for (e in newEntries) {
                if (entries.add(e)) {
                    added++
                    keys.addAll(UrlParsers.keysFromSource(e.url))
                }
            }
            index = keys
            if (newestId > lastSeenId) lastSeenId = newestId
            lastFetchAt = System.currentTimeMillis()
            recomputeStats()
            save()
            added
        }
    }

    fun importText(text: String): ImportResult {
        return synchronized(lock) {
            val t = text.trim()
            val incoming = ArrayList<AssetEntry>()
            var marker = 0L
            if (t.startsWith("{")) {
                val o = JSONObject(t)
                marker = o.optLong("lastSeenId", 0L)
                val arr = o.optJSONArray("entries") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val e = arr.getJSONObject(i)
                    val u = e.optString("u")
                    if (u.isNotBlank()) incoming.add(AssetEntry(e.optLong("i", 0L), u))
                }
            } else {
                // Plain list: one link per line.
                for (line in t.lineSequence()) {
                    val s = line.trim()
                    if (s.startsWith("http", ignoreCase = true)) incoming.add(AssetEntry(0L, s))
                }
            }
            var added = 0
            for (e in incoming) if (entries.add(e)) added++
            if (marker > lastSeenId) lastSeenId = marker
            if (added > 0 || marker > 0) lastFetchAt = maxOf(lastFetchAt, System.currentTimeMillis())
            rebuild()
            save()
            ImportResult(added, entries.size)
        }
    }

    fun exportJson(): String {
        return synchronized(lock) {
            val arr = JSONArray()
            for (e in entries) arr.put(JSONObject().put("i", e.uploadId).put("u", e.url))
            JSONObject()
                .put("version", 1)
                .put("lastSeenId", lastSeenId)
                .put("lastFetchAt", lastFetchAt)
                .put("entries", arr)
                .toString()
        }
    }

    fun resetMarker() {
        synchronized(lock) {
            lastSeenId = 0L
            recomputeStats()
            save()
        }
    }

    fun clear() {
        synchronized(lock) {
            entries = LinkedHashSet()
            lastSeenId = 0L
            lastFetchAt = 0L
            rebuild()
            save()
        }
    }

    private fun rebuild() {
        val keys = HashSet<String>(entries.size * 2)
        for (e in entries) keys.addAll(UrlParsers.keysFromSource(e.url))
        index = keys
        recomputeStats()
    }

    private fun recomputeStats() {
        var tw = 0
        var px = 0
        var other = 0
        val uploads = HashSet<Long>()
        for (e in entries) {
            if (e.uploadId != 0L) uploads.add(e.uploadId)
            val ks = UrlParsers.keysFromSource(e.url)
            when {
                ks.any { it.startsWith("tw:") || it.startsWith("tm:") } -> tw++
                ks.any { it.startsWith("px:") } -> px++
                else -> other++
            }
        }
        stats = AssetStats(entries.size, uploads.size, tw, px, other, lastSeenId, lastFetchAt)
    }

    private fun save() {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(exportJson())
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }
}
