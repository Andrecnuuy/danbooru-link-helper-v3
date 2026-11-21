package com.dlh.helper

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// Pure Kotlin (no Android imports) so saving and loading can be tested on a plain JVM.

/**
 * What survives an app restart for the current artist check: every source with its links and counters
 * (not the transient scan state), which source is shown, and the artist's Danbooru data.
 * [candidates] are the URLs of the other platforms found in the artist's Danbooru URL list.
 */
data class SessionData(
    val input: String,
    val tag: String,
    val sources: List<SourceData>,
    val active: Int,
    val danbooruKeys: Set<String>,
    val danbooruPosts: Int,
    val candidates: List<String> = emptyList()
)

object SessionStore {

    private fun itemJson(item: LinkItem): JSONObject {
        val o = JSONObject().put("k", item.key).put("u", item.url)
        if (item.mediaKeys.isNotEmpty()) o.put("m", JSONArray(item.mediaKeys))
        if (item.thumb != null) o.put("t", item.thumb)
        if (item.full != null) o.put("f", item.full)
        if (item.kind != KIND_IMAGE) o.put("d", item.kind)
        if (item.count != 1) o.put("n", item.count)
        if (item.timeMs != 0L) o.put("s", item.timeMs)
        if (item.sizes.isNotEmpty()) o.put("z", JSONArray(item.sizes))
        return o
    }

    private fun itemsFrom(arr: JSONArray?): List<LinkItem> {
        if (arr == null) return emptyList()
        val items = ArrayList<LinkItem>(arr.length())
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            val m = e.optJSONArray("m")
            val mk = ArrayList<String>()
            if (m != null) for (j in 0 until m.length()) mk.add(m.getString(j))
            val z = e.optJSONArray("z")
            val sizes = ArrayList<Int>()
            if (z != null) for (j in 0 until z.length()) sizes.add(z.getInt(j))
            items.add(
                LinkItem(
                    key = e.getString("k"),
                    url = e.getString("u"),
                    mediaKeys = mk,
                    thumb = e.optString("t").ifEmpty { null },
                    full = e.optString("f").ifEmpty { null },
                    kind = e.optInt("d", KIND_IMAGE),
                    count = e.optInt("n", 1),
                    timeMs = e.optLong("s", 0L),
                    sizes = sizes
                )
            )
        }
        return items
    }

    fun toJson(d: SessionData): String {
        val sources = JSONArray()
        for (s in d.sources) {
            val items = JSONArray()
            for (item in s.items) items.put(itemJson(item))
            sources.put(
                JSONObject()
                    .put("id", s.id)
                    .put("plat", if (s.ref.platform == Platform.PIXIV) "P" else "X")
                    .put("ref", s.ref.id)
                    .put("num", s.ref.numeric)
                    .put("name", s.name)
                    .put("uid", s.userId)
                    .put("original", s.original)
                    .put("rd", s.removedDanbooru)
                    .put("ra", s.removedAssets)
                    .put("rm", s.removedManually)
                    .put("items", items)
            )
        }
        return JSONObject()
            .put("v", 2)
            .put("input", d.input)
            .put("tag", d.tag)
            .put("dp", d.danbooruPosts)
            .put("active", d.active)
            .put("sources", sources)
            .put("dk", JSONArray(d.danbooruKeys.toList()))
            .put("cand", JSONArray(d.candidates))
            .toString()
    }

    /**
     * Reads what [toJson] wrote. A session saved by the single-source versions of the app (links, counters and the pasted
     * link at the top level) becomes one source, taken from the pasted link; a link that cannot be read drops its links.
     */
    fun fromJson(text: String): SessionData {
        val o = JSONObject(text)
        val input = o.optString("input")
        val sources = ArrayList<SourceData>()
        val arr = o.optJSONArray("sources")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                val platform = if (e.optString("plat") == "P") Platform.PIXIV else Platform.TWITTER
                val ref = ArtistRef(platform, e.optString("ref"), e.optBoolean("num", false))
                if (ref.id.isEmpty()) continue
                sources.add(
                    SourceData(
                        id = e.optInt("id", i),
                        ref = ref,
                        name = e.optString("name"),
                        userId = e.optString("uid"),
                        items = itemsFrom(e.optJSONArray("items")),
                        original = e.optInt("original"),
                        removedDanbooru = e.optInt("rd"),
                        removedAssets = e.optInt("ra"),
                        removedManually = e.optInt("rm")
                    )
                )
            }
        } else {
            val ref = UrlParsers.parseArtistLink(input)
            val items = itemsFrom(o.optJSONArray("items"))
            if (ref != null && items.isNotEmpty()) {
                sources.add(
                    SourceData(
                        id = 0,
                        ref = ref,
                        items = items,
                        original = o.optInt("original"),
                        removedDanbooru = o.optInt("rd"),
                        removedAssets = o.optInt("ra"),
                        removedManually = o.optInt("rm")
                    )
                )
            }
        }
        val dk = o.optJSONArray("dk") ?: JSONArray()
        val keys = HashSet<String>(dk.length() * 2)
        for (i in 0 until dk.length()) keys.add(dk.getString(i))
        val cand = o.optJSONArray("cand") ?: JSONArray()
        val cands = ArrayList<String>(cand.length())
        for (i in 0 until cand.length()) cands.add(cand.getString(i))
        val active = o.optInt("active", sources.firstOrNull()?.id ?: -1)
        return SessionData(
            input = input,
            tag = o.optString("tag"),
            sources = sources,
            active = if (sources.any { it.id == active }) active else (sources.firstOrNull()?.id ?: -1),
            danbooruKeys = keys,
            danbooruPosts = o.optInt("dp"),
            candidates = cands
        )
    }

    fun save(file: File, d: SessionData) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(toJson(d))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    fun load(file: File): SessionData? {
        if (!file.exists()) return null
        return fromJson(file.readText())
    }
}
