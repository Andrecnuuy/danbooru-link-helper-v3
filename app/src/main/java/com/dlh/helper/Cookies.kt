package com.dlh.helper

// Pure Kotlin (no Android imports): reads the Netscape cookies.txt format used by yt-dlp and gallery-dl.

data class CookieEntry(
    val domain: String,
    val includeSubdomains: Boolean,
    val path: String,
    val secure: Boolean,
    /** Expiry in epoch seconds; 0 = session cookie. */
    val expiresSec: Long,
    val name: String,
    val value: String,
    val httpOnly: Boolean
)

class CookieParseResult(val cookies: List<CookieEntry>, val expiredSkipped: Int, val badLines: Int)

/** What the loaded cookies cover. Expiry values: null = cookie missing, 0 = session cookie. */
class CookieSummary(
    val total: Int,
    val xAuthToken: Boolean,
    val xCsrf: Boolean,
    val xExpiresSec: Long?,
    val pixivSession: Boolean,
    val pixivExpiresSec: Long?
)

object CookieFile {

    /** Only cookies for these sites are kept; everything else in an imported file is discarded. */
    private val SITES = listOf("x.com", "twitter.com", "pixiv.net")

    /** True when a cookie stored for [cookieDomain] is sent to [host] (".x.com" matches "x.com" and "www.x.com"). */
    fun domainMatches(cookieDomain: String, host: String): Boolean {
        val d = cookieDomain.trim().trimStart('.').lowercase()
        val h = host.trim().lowercase()
        return d.isNotEmpty() && (h == d || h.endsWith(".$d"))
    }

    /** True when the cookie's domain is [site] or one of its subdomains. */
    fun belongsTo(cookieDomain: String, site: String): Boolean {
        val d = cookieDomain.trim().trimStart('.').lowercase()
        return d == site || d.endsWith(".$site")
    }

    fun parse(text: String, nowSec: Long): CookieParseResult {
        val out = ArrayList<CookieEntry>()
        var expired = 0
        var bad = 0
        for (raw in text.removePrefix("﻿").lineSequence()) {
            var line = raw.trimEnd()
            if (line.isBlank()) continue
            var httpOnly = false
            if (line.startsWith("#HttpOnly_")) {
                httpOnly = true
                line = line.removePrefix("#HttpOnly_")
            } else if (line.trimStart().startsWith("#")) {
                continue
            }
            var f = line.split('\t')
            if (f.size < 7) f = line.trim().split(Regex("\\s+"), 7)
            if (f.size < 7) {
                bad++
                continue
            }
            val name = f[5].trim()
            if (name.isEmpty()) {
                bad++
                continue
            }
            var exp = f[4].trim().toLongOrNull() ?: 0L
            if (exp > 100_000_000_000L) exp /= 1000 // some exporters write milliseconds
            if (exp > 0 && exp <= nowSec) {
                expired++
                continue
            }
            out.add(
                CookieEntry(
                    domain = f[0].trim(),
                    includeSubdomains = f[1].trim().equals("TRUE", ignoreCase = true),
                    path = f[2].trim().ifEmpty { "/" },
                    secure = f[3].trim().equals("TRUE", ignoreCase = true),
                    expiresSec = exp,
                    name = name,
                    value = f[6].trim(),
                    httpOnly = httpOnly
                )
            )
        }
        return CookieParseResult(out, expired, bad)
    }

    fun filterRelevant(cookies: List<CookieEntry>): List<CookieEntry> =
        cookies.filter { c -> SITES.any { belongsTo(c.domain, it) } }

    fun serialize(cookies: List<CookieEntry>): String = buildString {
        append("# Netscape HTTP Cookie File\n")
        for (c in cookies) {
            if (c.httpOnly) append("#HttpOnly_")
            append(c.domain).append('\t')
            append(if (c.includeSubdomains) "TRUE" else "FALSE").append('\t')
            append(c.path).append('\t')
            append(if (c.secure) "TRUE" else "FALSE").append('\t')
            append(c.expiresSec).append('\t')
            append(c.name).append('\t')
            append(c.value).append('\n')
        }
    }

    /** Value for a Cookie request header to [host] ("name=value; name=value"), skipping expired cookies. */
    fun headerFor(cookies: List<CookieEntry>, host: String, nowSec: Long): String {
        val byName = LinkedHashMap<String, String>()
        for (c in cookies) {
            if (c.expiresSec > 0 && c.expiresSec <= nowSec) continue
            if (domainMatches(c.domain, host)) byName[c.name] = c.value
        }
        return byName.entries.joinToString("; ") { it.key + "=" + it.value }
    }

    /** Value of the cookie [name] that would be sent to the first of [hosts] that has one, or "". */
    fun valueFor(cookies: List<CookieEntry>, name: String, hosts: List<String>, nowSec: Long): String {
        for (h in hosts) {
            for (c in cookies) {
                if (c.name != name) continue
                if (c.expiresSec > 0 && c.expiresSec <= nowSec) continue
                if (domainMatches(c.domain, h)) return c.value
            }
        }
        return ""
    }

    /** Names (never values) of the cookies that would be sent to [host]. Used for diagnostics. */
    fun namesFor(cookies: List<CookieEntry>, host: String, nowSec: Long): List<String> {
        val names = LinkedHashSet<String>()
        for (c in cookies) {
            if (c.expiresSec > 0 && c.expiresSec <= nowSec) continue
            if (domainMatches(c.domain, host)) names.add(c.name)
        }
        return names.toList()
    }

    fun summarize(cookies: List<CookieEntry>): CookieSummary {
        fun isX(c: CookieEntry) = belongsTo(c.domain, "x.com") || belongsTo(c.domain, "twitter.com")
        val auth = cookies.firstOrNull { isX(it) && it.name == "auth_token" }
        val csrf = cookies.firstOrNull { isX(it) && it.name == "ct0" }
        val pixiv = cookies.firstOrNull { belongsTo(it.domain, "pixiv.net") && it.name == "PHPSESSID" }
        return CookieSummary(
            total = cookies.size,
            xAuthToken = auth != null,
            xCsrf = csrf != null,
            xExpiresSec = auth?.expiresSec,
            pixivSession = pixiv != null,
            pixivExpiresSec = pixiv?.expiresSec
        )
    }
}
