package com.dlh.helper

import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything that decides WHICH Danbooru records count as "my media assets", and which fields
 * hold their source links, lives here, so it can be lined up with the DAO script in one place.
 *
 * Current assumption: the account's upload queue = /uploads.json filtered to the account's own uploads
 * that were never turned into a post (search[is_posted]=false), newest first.
 * Source links are read from each upload's `source` and `referer_url`, plus every
 * upload_media_asset's `page_url` and `source_url` when the server accepts the nested only= syntax.
 */
object AssetApi {
    const val PATH = "/uploads.json"
    const val PAGE_LIMIT = 200
    const val ONLY_PLAIN = "id,status,source,referer_url"
    const val ONLY_NESTED = "id,status,source,referer_url,upload_media_assets[page_url,source_url]"
}

class FetchResult(
    val entries: List<AssetEntry>,
    val newestId: Long,
    val uploadsSeen: Int,
    val reachedMarker: Boolean
)

class MediaAssetFetcher(private val api: DanbooruClient, private val log: (String) -> Unit) {

    /**
     * Pages newest-to-oldest and stops as soon as it reaches an upload id at or below [marker]
     * (the highest id from the previous fetch), so only new uploads are collected.
     * [onProgress] gets (uploads scanned, links collected).
     */
    suspend fun fetchNew(
        userId: Long,
        marker: Long,
        onlyUnposted: Boolean,
        onProgress: (Int, Int) -> Unit
    ): FetchResult {
        var nested = true
        var before: Long? = null
        var newest = marker
        // Uploads still pending/processing have no media assets yet. The fetch point must stay below
        // the lowest of them, otherwise the next fetch would skip them for good.
        var lowestUnfinished = Long.MAX_VALUE
        var seen = 0
        var reached = false
        val out = LinkedHashSet<AssetEntry>()

        paging@ while (true) {
            val params = mutableListOf(
                "search[uploader_id]" to userId.toString(),
                "search[order]" to "id_desc",
                "limit" to AssetApi.PAGE_LIMIT.toString(),
                "only" to (if (nested) AssetApi.ONLY_NESTED else AssetApi.ONLY_PLAIN)
            )
            if (onlyUnposted) params.add("search[is_posted]" to "false")
            if (before != null) params.add("page" to "b$before")

            val text = try {
                api.getText(AssetApi.PATH, params)
            } catch (e: HttpStatusException) {
                val clientError = e.code in 400..499 && e.code != 401 && e.code != 403 && e.code != 429
                if (nested && clientError) {
                    log("Nested only= was rejected (HTTP ${e.code}); retrying with plain fields")
                    nested = false
                    continue@paging
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
                if (id > newest) newest = id
                if (id <= marker) {
                    reached = true
                    continue
                }
                seen++
                val status = o.optString("status")
                if (status == "pending" || status == "processing") {
                    if (id < lowestUnfinished) lowestUnfinished = id
                }
                if (status == "error") continue
                collectUrls(o, id, out)
            }
            onProgress(seen, out.size)
            if (reached || arr.length() < AssetApi.PAGE_LIMIT) break
            before = minId
        }
        if (lowestUnfinished != Long.MAX_VALUE) newest = maxOf(marker, minOf(newest, lowestUnfinished - 1))
        return FetchResult(out.toList(), newest, seen, reached)
    }

    private fun collectUrls(o: JSONObject, id: Long, out: MutableSet<AssetEntry>) {
        fun add(s: String?) {
            if (s != null && s.startsWith("http", ignoreCase = true)) out.add(AssetEntry(id, s))
        }
        add(o.str("source"))
        add(o.str("referer_url"))
        val umas = o.optJSONArray("upload_media_assets")
        if (umas != null) {
            for (i in 0 until umas.length()) {
                val u = umas.optJSONObject(i) ?: continue
                add(u.str("page_url"))
                add(u.str("source_url"))
            }
        }
    }
}
