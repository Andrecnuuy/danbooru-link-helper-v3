package com.dlh.helper

import org.json.JSONArray
import org.json.JSONObject

// Pure Kotlin (org.json only) so it can be tested on a plain JVM.

/**
 * Reads an artist's URL list out of the different shapes Danbooru has used for it:
 *  - an artist object with "urls": [ {url, normalized_url, is_active}, ... ] or a list of strings ("-" = inactive)
 *  - an artist object with "url_string": one URL per line, "-" in front of inactive ones
 *  - a list of artist_urls objects: [ {artist_id, url, normalized_url, is_active}, ... ]
 * Returns an empty list when the text holds none of these.
 */
object ArtistUrlParse {

    fun parse(text: String): List<ArtistUrl> {
        val t = text.trim()
        if (t.isEmpty()) return emptyList()
        return try {
            when {
                t.startsWith("[") -> fromArray(JSONArray(t))
                t.startsWith("{") -> fromObject(JSONObject(t))
                else -> emptyList()
            }
        } catch (e: org.json.JSONException) {
            emptyList()
        }
    }

    private fun fromObject(o: JSONObject): List<ArtistUrl> {
        val urls = o.optJSONArray("urls")
        if (urls != null && urls.length() > 0) return fromArray(urls)
        val s = o.optString("url_string")
        if (s.isNotEmpty()) return UrlParsers.parseUrlList(s)
        return emptyList()
    }

    private fun fromArray(arr: JSONArray): List<ArtistUrl> {
        val out = ArrayList<ArtistUrl>(arr.length())
        for (i in 0 until arr.length()) {
            val e = arr.opt(i)
            when (e) {
                is JSONObject -> {
                    val url = e.str("url") ?: e.str("normalized_url") ?: continue
                    // A missing flag counts as active; Danbooru only sends false for the dead ones.
                    val active = if (e.has("is_active") && !e.isNull("is_active")) e.optBoolean("is_active", true) else true
                    val line = UrlParsers.parseUrlLine(url) ?: continue
                    // A "-" in front of the text itself also marks it inactive.
                    out.add(ArtistUrl(line.url, active && line.active))
                }
                is String -> UrlParsers.parseUrlLine(e)?.let { out.add(it) }
            }
        }
        return out
    }
}
