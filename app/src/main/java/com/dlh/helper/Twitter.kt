package com.dlh.helper

import kotlinx.coroutines.delay
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** A problem with a readable message (not a network fault). */
class XException(message: String) : IOException(message)

/** The account being scanned was already scanned under another link (e.g. by handle and by numeric id). */
class DuplicateSourceException(val userId: String) : IOException("Same X account (id $userId) is already in the list.")

/**
 * One tweet from the media timeline, reduced to what the app needs.
 * [sizes] is width, height, width, height... of the photos, in the order of [mediaKeys] (0, 0 when X did not say).
 * [screenName] is the author's handle when the tweet says it.
 */
class TweetMedia(
    val id: String,
    val photos: Int,
    val videos: Int,
    val mediaKeys: List<String>,
    val thumb: String?,
    val full: String?,
    val firstIsVideo: Boolean,
    val sizes: List<Int> = emptyList(),
    val screenName: String? = null
)

class MediaPage(val tweets: List<TweetMedia>, val tweetResults: Int, val nextCursor: String?)

class UserLookup(val id: String?, val problem: String?)

/** Reading X's responses. Pure functions, no network. */
object TwitterParse {
    private val mediaKeyRe = Regex("""pbs\.twimg\.com/media/([A-Za-z0-9_-]+)""")
    private val featuresRe = Regex("""cannot be null:\s*([A-Za-z0-9_,\s]+)""")

    /** Finds the query id of GraphQL operation [op] in a script file or a JSON list of operations. */
    fun queryIdFrom(text: String, op: String): String? {
        val o = Regex.escape(op)
        val id = "([A-Za-z0-9_-]{16,40})"
        val patterns = listOf(
            // queryId first, then operationName (X's script files, and JSON)
            Regex("""["']?queryId["']?\s*[:=]\s*["']$id["']\s*,\s*["']?operationName["']?\s*[:=]\s*["']$o["']"""),
            // operationName first, then queryId
            Regex("""["']?operationName["']?\s*[:=]\s*["']$o["']\s*,\s*["']?queryId["']?\s*[:=]\s*["']$id["']"""),
            // JSON lists keyed by operation: "UserMedia": { ... "queryId": "..." }
            Regex(""""$o"\s*:\s*\{[^{}]{0,300}?"queryId"\s*:\s*"$id"""")
        )
        for (p in patterns) {
            val m = p.find(text)
            if (m != null) return m.groupValues[1]
        }
        return null
    }

    /** X answers 400 "The following features cannot be null: a, b" when a feature flag is missing. */
    fun missingFeatures(body: String): List<String> {
        val m = featuresRe.find(body) ?: return emptyList()
        return m.groupValues[1].split(',').map { it.trim() }.filter { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c == '_' } }
    }

    fun parseUser(root: JSONObject): UserLookup {
        val result = root.optJSONObject("data")?.optJSONObject("user")?.optJSONObject("result")
            ?: return UserLookup(null, "X found no account with that name.")
        val id = result.optString("rest_id")
        if (id.isNotEmpty()) return UserLookup(id, null)
        val reason = result.optString("reason")
        val why = if (result.optString("__typename") == "UserUnavailable") {
            "That X account is unavailable" + (if (reason.isNotEmpty()) " ($reason)." else ".")
        } else {
            "X returned no user id for that account."
        }
        return UserLookup(null, why)
    }

    /** Picks an mp4 near 800 kbps (small enough to preview quickly); falls back to the biggest one. */
    fun pickVideoUrl(variants: JSONArray?): String? {
        if (variants == null) return null
        val list = ArrayList<Pair<Long, String>>()
        for (i in 0 until variants.length()) {
            val v = variants.optJSONObject(i) ?: continue
            if (v.optString("content_type") != "video/mp4") continue
            val url = v.optString("url")
            if (url.isNotEmpty()) list.add(v.optLong("bitrate", 0L) to url)
        }
        if (list.isEmpty()) return null
        val sorted = list.sortedBy { it.first }
        return (sorted.firstOrNull { it.first >= 500_000L } ?: sorted.last()).second
    }

    /** Large-image URL for a pbs.twimg.com/media/ URL; anything else is returned unchanged. */
    fun photoFull(url: String): String {
        if (!url.contains("/media/")) return url
        val base = url.substringBeforeLast('.')
        val ext = url.substringAfterLast('.', "jpg")
        return "$base?format=$ext&name=large"
    }

    /** Reads one page of the user's media timeline. Tweets are found wherever the response nests them. */
    fun parsePage(root: JSONObject, userId: String): MediaPage {
        val results = ArrayList<JSONObject>()
        val cursor = arrayOfNulls<String>(1)
        collect(root.opt("data"), results, cursor)
        val out = ArrayList<TweetMedia>()
        for (r in results) {
            val t = tweetMedia(r, userId)
            if (t != null) out.add(t)
        }
        return MediaPage(out, results.size, cursor[0])
    }

    private fun collect(node: Any?, tweets: MutableList<JSONObject>, cursor: Array<String?>) {
        if (node is JSONObject) {
            if (node.optString("cursorType") == "Bottom") {
                val v = node.optString("value")
                if (v.isNotEmpty()) cursor[0] = v
            }
            val tr = node.optJSONObject("tweet_results")
            if (tr != null) {
                // Do not look inside: quoted and retweeted tweets nest here and belong to other accounts.
                val r = tr.optJSONObject("result")
                if (r != null) tweets.add(r)
                return
            }
            val keys = node.keys()
            while (keys.hasNext()) collect(node.opt(keys.next()), tweets, cursor)
        } else if (node is JSONArray) {
            for (i in 0 until node.length()) collect(node.opt(i), tweets, cursor)
        }
    }

    private fun tweetMedia(result: JSONObject, userId: String): TweetMedia? {
        val tweet = result.optJSONObject("tweet") ?: result
        val legacy = tweet.optJSONObject("legacy") ?: return null
        if (legacy.has("retweeted_status_result")) return null
        val owner = legacy.optString("user_id_str")
        if (owner.isNotEmpty() && userId.isNotEmpty() && owner != userId) return null
        var id = tweet.optString("rest_id")
        if (id.isEmpty()) id = legacy.optString("id_str")
        if (id.isEmpty() || !id.all { it.isDigit() }) return null

        val media = legacy.optJSONObject("extended_entities")?.optJSONArray("media")
            ?: legacy.optJSONObject("entities")?.optJSONArray("media")
        var photos = 0
        var videos = 0
        val keys = ArrayList<String>()
        val sizes = ArrayList<Int>()
        var thumb: String? = null
        var full: String? = null
        var firstVideo = false
        if (media != null) {
            for (i in 0 until media.length()) {
                val m = media.optJSONObject(i) ?: continue
                val type = m.optString("type")
                val url = m.optString("media_url_https")
                val isVideo = type == "video" || type == "animated_gif"
                if (isVideo) videos++ else photos++
                if (!isVideo) {
                    val km = mediaKeyRe.find(url)
                    if (km != null) {
                        keys.add(km.groupValues[1])
                        val info = m.optJSONObject("original_info")
                        val large = m.optJSONObject("sizes")?.optJSONObject("large")
                        sizes.add(info?.optInt("width", 0) ?: large?.optInt("w", 0) ?: 0)
                        sizes.add(info?.optInt("height", 0) ?: large?.optInt("h", 0) ?: 0)
                    }
                }
                if (thumb == null && url.isNotEmpty()) {
                    thumb = url
                    firstVideo = isVideo
                    full = if (isVideo) pickVideoUrl(m.optJSONObject("video_info")?.optJSONArray("variants")) else photoFull(url)
                }
            }
        }
        return TweetMedia(id, photos, videos, keys, thumb, full, firstVideo, sizes, screenNameOf(tweet))
    }

    /** The author's handle, from where X has put it over time (core.user_results...legacy.screen_name or core.screen_name). */
    private fun screenNameOf(tweet: JSONObject): String? {
        val core = tweet.optJSONObject("core") ?: return null
        val user = core.optJSONObject("user_results")?.optJSONObject("result")
        val name = user?.optJSONObject("legacy")?.optString("screen_name").orEmpty()
            .ifEmpty { user?.optJSONObject("core")?.optString("screen_name").orEmpty() }
            .ifEmpty { core.optString("screen_name") }
        return name.takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c == '_' } }
    }
}

/**
 * Lists a user's media posts (photos, videos and GIFs) through the same internal GraphQL calls the X website uses,
 * logged in with the imported cookies (auth_token and ct0).
 *
 * X changes these calls often, so nothing is hard-coded: the query ids are looked up in X's own script files
 * (and cached by the caller), missing feature flags are added when X reports them, and every failure says
 * which step failed. Not handled: the x-client-transaction-id header X asks for on some endpoints.
 */
class TwitterClient(
    private val cookieHeader: String,
    private val csrf: String,
    private val delayMs: Long,
    private val log: (String) -> Unit,
    var userQueryId: String,
    var mediaQueryId: String,
    private val lockUser: Boolean,
    private val lockMedia: Boolean
) {
    companion object {
        /** The public bearer token of X's own web app (the same for every visitor). */
        const val BEARER = "AAAAAAAAAAAAAAAAAAAAANRILgAAAAAAnNwIzUejRCOuH5E6I8xnZz4puTs%3D1Zv7ttfk8LF81IUq16cHjhLTvJu4FA33AGWWjCpTnA"
        const val MAX_PAGES = 300
        const val MAX_BUNDLES = 25
        val OPENAPI_URLS = listOf(
            "https://raw.githubusercontent.com/fa0311/twitter-openapi/main/src/config/placeholder.json"
        )
    }

    private var rediscovered = false

    // What the current scan has collected so far, so a stopped or failed scan can still hand over its results.
    private val seen = LinkedHashMap<String, TweetMedia>()
    private var scanHandle = ""
    private var scanFilter = MediaFilter.BOTH
    private var scanHost = "x.com"

    /** The numeric id of the account of the current (or last) scan; empty before it is known. */
    var resolvedUserId: String = ""
        private set

    /** The account's handle: the one that was scanned, or else the one the tweets named. Empty when unknown. */
    val screenName: String
        get() = scanHandle.ifEmpty { seen.values.firstNotNullOfOrNull { it.screenName } ?: "" }

    private val userFeatures = linkedMapOf(
        "hidden_profile_subscriptions_enabled" to true,
        "rweb_tipjar_consumption_enabled" to true,
        "responsive_web_graphql_exclude_directive_enabled" to true,
        "verified_phone_label_enabled" to false,
        "subscriptions_verification_info_is_identity_verified_enabled" to true,
        "subscriptions_verification_info_verified_since_enabled" to true,
        "highlights_tweets_tab_ui_enabled" to true,
        "responsive_web_twitter_article_notes_tab_enabled" to true,
        "subscriptions_feature_can_gift_premium" to true,
        "creator_subscriptions_tweet_preview_api_enabled" to true,
        "responsive_web_graphql_skip_user_profile_image_extensions_enabled" to false,
        "responsive_web_graphql_timeline_navigation_enabled" to true
    )

    private val mediaFeatures = linkedMapOf(
        "rweb_video_screen_enabled" to false,
        "profile_label_improvements_pcf_label_in_post_enabled" to true,
        "rweb_tipjar_consumption_enabled" to true,
        "verified_phone_label_enabled" to false,
        "creator_subscriptions_tweet_preview_api_enabled" to true,
        "responsive_web_graphql_timeline_navigation_enabled" to true,
        "responsive_web_graphql_skip_user_profile_image_extensions_enabled" to false,
        "premium_content_api_read_enabled" to false,
        "communities_web_enable_tweet_community_results_fetch" to true,
        "c9s_tweet_anatomy_moderator_badge_enabled" to true,
        "responsive_web_grok_analyze_button_fetch_trends_enabled" to false,
        "responsive_web_grok_analyze_post_followups_enabled" to true,
        "responsive_web_jetfuel_frame" to true,
        "responsive_web_grok_share_attachment_enabled" to true,
        "articles_preview_enabled" to true,
        "responsive_web_edit_tweet_api_enabled" to true,
        "graphql_is_translatable_rweb_tweet_is_translatable_enabled" to true,
        "view_counts_everywhere_api_enabled" to true,
        "longform_notetweets_consumption_enabled" to true,
        "responsive_web_twitter_article_tweet_consumption_enabled" to true,
        "tweet_awards_web_tipping_enabled" to false,
        "responsive_web_grok_show_grok_translated_post" to false,
        "responsive_web_grok_analysis_button_from_backend" to true,
        "creator_subscriptions_quote_tweet_preview_enabled" to false,
        "freedom_of_speech_not_reach_fetch_enabled" to true,
        "standardized_nudges_misinfo" to true,
        "tweet_with_visibility_results_prefer_gql_limited_actions_policy_enabled" to true,
        "longform_notetweets_rich_text_read_enabled" to true,
        "longform_notetweets_inline_media_enabled" to true,
        "responsive_web_grok_image_annotation_enabled" to true,
        "responsive_web_grok_imagine_annotation_enabled" to true,
        "responsive_web_enhance_cards_enabled" to false
    )

    private fun request(url: HttpUrl): Request = Request.Builder()
        .url(url)
        .header("User-Agent", Net.DESKTOP_UA)
        .header("Authorization", "Bearer $BEARER")
        .header("x-csrf-token", csrf)
        .header("x-twitter-auth-type", "OAuth2Session")
        .header("x-twitter-active-user", "yes")
        .header("x-twitter-client-language", "en")
        .header("Referer", "https://x.com/")
        .header("Accept", "*/*")
        .header("Cookie", cookieHeader)
        .build()

    // ------------------------------------------------------------------ query id discovery

    private suspend fun getText(url: String, withCookies: Boolean): String? {
        val rb = Request.Builder().url(url).header("User-Agent", Net.DESKTOP_UA).header("Accept", "*/*")
        if (withCookies && cookieHeader.isNotEmpty()) rb.header("Cookie", cookieHeader)
        return try {
            val r = Net.client.fetch(rb.build())
            if (r.code in 200..299) r.body else null
        } catch (e: IOException) {
            null
        }
    }

    /** Looks the two query ids up in X's own script files (then in a public list) and keeps what it finds. */
    private suspend fun discover() {
        log("Looking up X's current query IDs…")
        var user = ""
        var media = ""
        fun scanText(text: String?) {
            if (text == null) return
            if (user.isEmpty() && !lockUser) user = TwitterParse.queryIdFrom(text, "UserByScreenName").orEmpty()
            if (media.isEmpty() && !lockMedia) media = TwitterParse.queryIdFrom(text, "UserMedia").orEmpty()
        }
        fun done(): Boolean = (user.isNotEmpty() || lockUser) && (media.isNotEmpty() || lockMedia)

        val home = getText("https://x.com/", true)
        val sw = getText("https://x.com/sw.js", false)
        scanText(home)
        scanText(sw)
        if (!done()) {
            val bundleRe = Regex("""(?:https://abs\.twimg\.com)?(/responsive-web/client-web[\w-]*/[\w.~-]+\.js)""")
            val all = home.orEmpty() + "\n" + sw.orEmpty()
            val urls = bundleRe.findAll(all).map { "https://abs.twimg.com" + it.groupValues[1] }.distinct().toList()
            val ordered = urls.sortedBy { u ->
                when {
                    u.contains("/main.") -> 0
                    u.contains("/api.") -> 1
                    else -> 2
                }
            }.take(MAX_BUNDLES)
            log("X script files found: ${urls.size}; checking up to ${ordered.size}")
            for (u in ordered) {
                if (done()) break
                scanText(getText(u, false))
            }
        }
        if (!done()) {
            for (u in OPENAPI_URLS) {
                if (done()) break
                scanText(getText(u, false))
            }
        }
        if (user.isNotEmpty()) userQueryId = user
        if (media.isNotEmpty()) mediaQueryId = media
        log("Query IDs now: UserByScreenName=${userQueryId.ifEmpty { "?" }} UserMedia=${mediaQueryId.ifEmpty { "?" }}")
    }

    // ------------------------------------------------------------------ requests

    /** One GraphQL GET. Waits out rate limits and adds feature flags that X says are missing. */
    private suspend fun graphql(
        op: String,
        queryId: String,
        variables: JSONObject,
        features: MutableMap<String, Boolean>,
        toggles: JSONObject
    ): JSONObject {
        var attempt = 0
        var rateWaits = 0
        while (true) {
            attempt++
            val featuresJson = JSONObject()
            for ((k, v) in features) featuresJson.put(k, v)
            val url = "https://x.com/i/api/graphql/$queryId/$op".toHttpUrl().newBuilder()
                .addQueryParameter("variables", variables.toString())
                .addQueryParameter("features", featuresJson.toString())
                .addQueryParameter("fieldToggles", toggles.toString())
                .build()
            val reply = Net.client.fetch(request(url))
            when {
                reply.code in 200..299 -> {
                    if (delayMs > 0) delay(delayMs)
                    val root = try {
                        JSONObject(reply.body)
                    } catch (e: Exception) {
                        throw XException("X sent a reply the app could not read ($op): ${reply.body.take(120)}")
                    }
                    if (root.optJSONObject("data") == null) {
                        val msg = root.optJSONArray("errors")?.optJSONObject(0)?.optString("message").orEmpty()
                        throw XException("X returned an error for $op: " + msg.ifEmpty { reply.body.take(160) })
                    }
                    return root
                }
                reply.code == 429 -> {
                    rateWaits++
                    if (rateWaits > 3) throw HttpStatusException(429, reply.body, op)
                    val now = System.currentTimeMillis() / 1000
                    val reset = reply.rateResetSec
                    val waitSec = (if (reset != null && reset > now) reset - now + 2 else reply.retryAfterSec ?: 60L).coerceIn(5L, 900L)
                    log("X rate limit reached; waiting ${waitSec}s")
                    delay(waitSec * 1000)
                }
                reply.code == 400 && attempt <= 4 -> {
                    val missing = TwitterParse.missingFeatures(reply.body).filter { it !in features }
                    if (missing.isEmpty()) throw HttpStatusException(400, reply.body, op)
                    log("X wants more feature flags for $op: ${missing.joinToString()}")
                    for (m in missing) features[m] = false
                }
                else -> throw HttpStatusException(reply.code, reply.body, op)
            }
        }
    }

    /** Runs [block]; when X answers 404 (usually a changed query id) looks the ids up again and retries once. */
    private suspend fun <T> withIdRetry(block: suspend () -> T): T {
        try {
            return block()
        } catch (e: HttpStatusException) {
            if (e.code == 404 && !rediscovered && !(lockUser && lockMedia)) {
                rediscovered = true
                log("HTTP 404 from X; the query IDs may have changed")
                discover()
                return block()
            }
            throw e
        }
    }

    private fun explain(e: HttpStatusException): XException {
        log("X ${e.where}: HTTP ${e.code} ${e.body.take(300)}")
        val msg = when (e.code) {
            401, 403 -> "X refused the request (HTTP ${e.code}). Your cookies may be expired or incomplete: import a fresh cookies.txt from a browser that is logged in to X (it needs auth_token and ct0)."
            404 -> "X answered 404 for ${e.where}. Either X changed its internal query ID (set the right one in Settings) or X now demands its anti-bot header (x-client-transaction-id), which this app does not generate yet. The Stats log has the details."
            429 -> "X is rate limiting this account. Wait a few minutes and scan again."
            else -> "X answered HTTP ${e.code} for ${e.where}. ${e.body.take(120)}"
        }
        return XException(msg)
    }

    // ------------------------------------------------------------------ scan

    /**
     * All media posts of [handle], newest first, filtered by [filter]. [onProgress] gets (pages read, posts found).
     * With [knownUserId] the handle lookup is skipped ([handle] may then be empty: links use x.com/i/status/<id> until a
     * tweet names the author). [acceptUser] sees the numeric user id before any posts are read and can refuse it by
     * returning false, which ends the scan with a [DuplicateSourceException].
     */
    suspend fun scan(
        handle: String,
        filter: MediaFilter,
        host: String,
        onProgress: (Int, Int) -> Unit,
        knownUserId: String? = null,
        acceptUser: (String) -> Boolean = { true }
    ): List<LinkItem> {
        seen.clear()
        resolvedUserId = ""
        scanHandle = handle
        scanFilter = filter
        scanHost = host
        if (cookieHeader.isEmpty() || csrf.isEmpty()) {
            throw XException("X needs your login. Import a cookies.txt that contains auth_token and ct0 (Danbooru API card).")
        }
        if (userQueryId.isBlank() || mediaQueryId.isBlank()) {
            rediscovered = true
            discover()
        }
        if (userQueryId.isBlank() || mediaQueryId.isBlank()) {
            throw XException("Could not find X's current query IDs. Paste them into Settings (UserByScreenName and UserMedia), or try again later.")
        }

        val userId: String = if (!knownUserId.isNullOrEmpty()) knownUserId else try {
            val vars = JSONObject().put("screen_name", handle).put("withSafetyModeUserFields", true)
            val lookupToggles = JSONObject().put("withAuxiliaryUserLabels", false)
            val root = withIdRetry { graphql("UserByScreenName", userQueryId, vars, userFeatures, lookupToggles) }
            val found = TwitterParse.parseUser(root)
            val foundId = found.id
            if (foundId == null) throw XException(found.problem ?: "X found no such account.")
            foundId
        } catch (e: HttpStatusException) {
            throw explain(e)
        }
        log(if (handle.isEmpty()) "X user $userId" else "X @$handle is user $userId")
        resolvedUserId = userId
        if (!acceptUser(userId)) throw DuplicateSourceException(userId)

        var cursor: String? = null
        var pages = 0
        var emptyStreak = 0
        val toggles = JSONObject().put("withArticlePlainText", false)
        try {
            while (pages < MAX_PAGES) {
                val vars = JSONObject()
                    .put("userId", userId)
                    .put("count", 100)
                    .put("includePromotedContent", false)
                    .put("withClientEventToken", false)
                    .put("withBirdwatchNotes", false)
                    .put("withVoice", true)
                    .put("withV2Timeline", true)
                if (cursor != null) vars.put("cursor", cursor)
                val root = withIdRetry { graphql("UserMedia", mediaQueryId, vars, mediaFeatures, toggles) }
                val page = TwitterParse.parsePage(root, userId)
                pages++
                var fresh = 0
                for (t in page.tweets) {
                    if (!seen.containsKey(t.id)) {
                        seen[t.id] = t
                        fresh++
                    }
                }
                if (pages == 1 && page.tweetResults > 0 && page.tweets.isEmpty()) {
                    log("X returned ${page.tweetResults} tweets but none had readable media data; X may have changed its format")
                }
                onProgress(pages, seen.size)
                val next = page.nextCursor
                if (fresh == 0) emptyStreak++ else emptyStreak = 0
                if (next == null || next == cursor || emptyStreak >= 2 || page.tweetResults == 0) break
                cursor = next
            }
        } catch (e: HttpStatusException) {
            throw explain(e)
        }
        log("X media timeline: ${seen.size} posts in $pages pages")

        return snapshot()
    }

    /** The posts found so far by the current (or last) scan, already filtered. Empty before a scan has found anything. */
    fun snapshot(): List<LinkItem> {
        val out = ArrayList<LinkItem>()
        val linkHandle = screenName.ifEmpty { "i" }
        for (t in seen.values) {
            if (!scanFilter.keeps(t.photos, t.videos)) continue
            val kind = when {
                t.firstIsVideo -> KIND_VIDEO
                t.videos > 0 -> KIND_MIXED
                else -> KIND_IMAGE
            }
            out.add(
                LinkItem(
                    key = "tw:${t.id}",
                    url = UrlParsers.twitterLink(scanHost, linkHandle, t.id),
                    mediaKeys = t.mediaKeys,
                    thumb = t.thumb,
                    full = t.full,
                    kind = kind,
                    count = maxOf(1, t.photos + t.videos),
                    timeMs = UrlParsers.twitterTimeMs(t.id),
                    sizes = t.sizes
                )
            )
        }
        return out
    }
}
