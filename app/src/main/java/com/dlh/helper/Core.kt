package com.dlh.helper

// Pure Kotlin (no Android imports) so the parsing and matching rules stay easy to test.

enum class Platform { TWITTER, PIXIV }

/**
 * A profile on one of the two sites. [id] is the Twitter handle (case kept) or the numeric Pixiv user id.
 * [numeric] is only for Twitter: true when [id] is X's numeric user id (links like x.com/i/user/123) instead of a handle.
 */
data class ArtistRef(val platform: Platform, val id: String, val numeric: Boolean = false) {
    /** Identifies the same profile regardless of spelling (handles are case-insensitive). */
    val key: String
        get() = when {
            platform == Platform.PIXIV -> "px:$id"
            numeric -> "xid:$id"
            else -> "x:${id.lowercase()}"
        }

    /** Short name for buttons and logs. */
    val label: String
        get() = when {
            platform == Platform.PIXIV -> "Pixiv $id"
            numeric -> "X #$id"
            else -> "X @$id"
        }
}

/** One entry of an artist's URL list on Danbooru. [active] is false for the "-" entries (deleted / banned / dead accounts). */
data class ArtistUrl(val url: String, val active: Boolean)

/**
 * An other-platform profile found in the artist's URL list. Either [ref] is known, or it is a Pixiv "stacc" link
 * ([stacc] holds the name) whose Pixiv user id still has to be looked up. [url] is the link as Danbooru lists it.
 */
data class SourceCandidate(val ref: ArtistRef?, val stacc: String?, val url: String) {
    val key: String get() = ref?.key ?: "stacc:${stacc?.lowercase()}"
    val label: String get() = ref?.label ?: "Pixiv ${stacc ?: "?"}"
}

const val KIND_IMAGE = 0
const val KIND_VIDEO = 1
const val KIND_MIXED = 2

/** Which kinds of posts a scan keeps. For Pixiv "video" means ugoira (animated works). */
enum class MediaFilter {
    BOTH, IMAGES, VIDEOS;

    fun keeps(photos: Int, videos: Int): Boolean = when (this) {
        BOTH -> true
        IMAGES -> photos > 0
        VIDEOS -> videos > 0
    }
}

/**
 * One link in the result list.
 * [key] is "tw:<status id>" or "px:<artwork id>". [mediaKeys] are Twitter image file keys
 * (pbs.twimg.com/media/<key>) used as a second way to recognise an image that Danbooru
 * stored under an image URL instead of the tweet URL.
 * The rest is preview data: [thumb] a thumbnail image, [full] the larger image or (for X videos) an mp4,
 * [kind] one of KIND_*, [count] media items in the post, [timeMs] post time (0 = unknown).
 * [sizes] is the pixel size of the pictures as width, height, width, height... (same order as [mediaKeys] for X,
 * the first page for Pixiv); empty when unknown.
 */
data class LinkItem(
    val key: String,
    val url: String,
    val mediaKeys: List<String> = emptyList(),
    val thumb: String? = null,
    val full: String? = null,
    val kind: Int = KIND_IMAGE,
    val count: Int = 1,
    val timeMs: Long = 0L,
    val sizes: List<Int> = emptyList()
)

/**
 * One account in the Links view, with everything the user did to its links (deletions, counters).
 * [name] is the handle (X) or display name (Pixiv) once known; [userId] the numeric X user id once resolved (to spot the same
 * account added twice). The last four fields are not saved: they describe what is happening right now.
 */
data class SourceData(
    val id: Int,
    val ref: ArtistRef,
    val name: String = "",
    val userId: String = "",
    val items: List<LinkItem> = emptyList(),
    val original: Int = 0,
    val removedDanbooru: Int = 0,
    val removedAssets: Int = 0,
    val removedManually: Int = 0,
    val assetChecked: Boolean = false,
    val assetMatches: List<LinkItem> = emptyList(),
    val lazyThumbs: Boolean = false,
    val busy: Boolean = false,
    val status: String = ""
) {
    /** "X @handle", "X #123", "Pixiv Name" - for chips and messages. */
    val title: String
        get() = when {
            ref.platform == Platform.PIXIV -> "Pixiv " + name.ifEmpty { ref.id }
            name.isNotEmpty() -> "X @$name"
            ref.numeric -> "X #${ref.id}"
            else -> "X @${ref.id}"
        }
}

/** One source link stored from the user's Danbooru media assets / uploads. */
data class AssetEntry(val uploadId: Long, val url: String)

object UrlParsers {

    private val twitterReserved = setOf(
        "i", "intent", "home", "explore", "search", "settings", "share", "hashtag",
        "messages", "notifications", "compose", "login", "signup", "tos", "privacy", "about"
    )

    private val twitterUserId = Regex(
        """(?i)(?:^|[/.])(?:twitter|x)\.com/(?:i/user/|intent/user\?(?:[^#\s]*&)?user_id=)(\d+)"""
    )
    private val twitterHost = Regex("""(?:^|[/.])(?:twitter|x)\.com/""")
    private val pixivStacc = Regex("""(?i)pixiv\.net/(?:[a-z]{2}/)?stacc/([A-Za-z0-9_.-]+)""")

    private val twitterProfile = Regex(
        """^(?:https?://)?(?:www\.|mobile\.)?(?:twitter|x)\.com/@?([A-Za-z0-9_]{1,15})(?:[/?#].*)?$""",
        RegexOption.IGNORE_CASE
    )
    private val pixivUserNew = Regex(
        """pixiv\.net/(?:[a-z]{2}(?:-[a-z]{2})?/)?users/(\d+)""",
        RegexOption.IGNORE_CASE
    )
    private val pixivUserOld = Regex(
        """pixiv\.net/(?:[a-z]{2}/)?member(?:_illust)?\.php\?(?:[^#\s]*&)?id=(\d+)""",
        RegexOption.IGNORE_CASE
    )

    private val twStatus = Regex(
        """(?i)(?<![A-Za-z0-9-])(?:twitter|x|fxtwitter|vxtwitter|fixupx)\.com/(?:[^/\s?#]+/){0,2}status(?:es)?/(\d+)"""
    )
    private val twMedia = Regex("""(?i)pbs\.twimg\.com/media/([A-Za-z0-9_-]+)""")
    private val pxArtwork = Regex("""(?i)pixiv\.net/(?:[a-z]{2}(?:-[a-z]{2})?/)?(?:artworks|i)/(\d+)""")
    private val pxIllustId = Regex("""(?i)pixiv\.net/[^\s#]*[?&]illust_id=(\d+)""")
    private val pxImage = Regex("""(?i)pximg\.net/[^\s?#]*?/(\d+)_(?:p\d+|ugoira)""")

    /** Reads the pasted profile link. Returns null when it is not a Twitter/X or Pixiv profile. */
    fun parseArtistLink(input: String): ArtistRef? {
        val s = input.trim()
        if (s.isEmpty()) return null
        pixivUserNew.find(s)?.let { return ArtistRef(Platform.PIXIV, it.groupValues[1]) }
        pixivUserOld.find(s)?.let { return ArtistRef(Platform.PIXIV, it.groupValues[1]) }
        twitterUserId.find(s)?.let { return ArtistRef(Platform.TWITTER, it.groupValues[1], numeric = true) }
        twitterProfile.find(s)?.let { m ->
            val handle = m.groupValues[1]
            if (handle.lowercase() !in twitterReserved) return ArtistRef(Platform.TWITTER, handle)
        }
        return null
    }

    /** The name in a Pixiv "stacc" profile link (pixiv.net/stacc/<name>), or null for any other link. */
    fun pixivStaccName(url: String): String? = pixivStacc.find(url)?.groupValues?.get(1)

    /**
     * One line of a Danbooru URL list ("-" in front means inactive) as an [ArtistUrl], or null for a blank line.
     */
    fun parseUrlLine(line: String): ArtistUrl? {
        var s = line.trim()
        if (s.isEmpty()) return null
        var active = true
        if (s.startsWith("-")) {
            active = false
            s = s.substring(1).trim()
        }
        return if (s.isEmpty()) null else ArtistUrl(s, active)
    }

    /** A whole URL list, one per line, as Danbooru shows it in the artist's edit box. */
    fun parseUrlList(text: String): List<ArtistUrl> = text.lineSequence().mapNotNull { parseUrlLine(it) }.toList()

    /**
     * Picks the Twitter/X and Pixiv profiles out of an artist's URL list: inactive entries, other sites (Fanbox...),
     * repeats and the profile the user already pasted ([primary]) are left out. A Pixiv "stacc" link comes back as a
     * candidate without [ArtistRef]; X profiles given by id and by handle can still be the same account, which is only
     * known after looking them up.
     */
    fun sourceCandidates(urls: List<ArtistUrl>, primary: ArtistRef?): List<SourceCandidate> {
        val out = LinkedHashMap<String, SourceCandidate>()
        for (u in urls) {
            if (!u.active) continue
            val host = u.url.lowercase()
            val cand: SourceCandidate? = when {
                !host.contains("pixiv.net") && !twitterHost.containsMatchIn(host) -> null
                host.contains("pixiv.net/fanbox") || host.contains("fanbox.cc") -> null
                else -> {
                    val ref = parseArtistLink(u.url)
                    val stacc = if (ref == null) pixivStaccName(u.url) else null
                    when {
                        ref != null -> SourceCandidate(ref, null, u.url)
                        stacc != null -> SourceCandidate(null, stacc, u.url)
                        else -> null
                    }
                }
            }
            if (cand == null) continue
            if (primary != null && cand.ref?.key == primary.key) continue
            out.putIfAbsent(cand.key, cand)
        }
        return out.values.toList()
    }

    /** URL spellings to try when searching Danbooru artists by URL, best guess first. */
    fun artistUrlVariants(a: ArtistRef): List<String> = when {
        a.platform == Platform.TWITTER && a.numeric -> listOf(
            "https://twitter.com/i/user/${a.id}",
            "https://x.com/i/user/${a.id}",
            "*twitter.com/i/user/${a.id}",
            "*://x.com/i/user/${a.id}"
        )
        else -> variantsByHandle(a)
    }

    private fun variantsByHandle(a: ArtistRef): List<String> = when (a.platform) {
        Platform.TWITTER -> listOf(
            "https://twitter.com/${a.id}",
            "https://x.com/${a.id}",
            "*twitter.com/${a.id}",
            "*://x.com/${a.id}"
        )
        Platform.PIXIV -> listOf(
            "https://www.pixiv.net/users/${a.id}",
            "https://www.pixiv.net/member.php?id=${a.id}",
            "*pixiv.net/users/${a.id}",
            "*pixiv.net/member.php?id=${a.id}"
        )
    }

    /**
     * Turns any source string (a Danbooru post source, an upload source, a referer...) into match keys:
     * "tw:<status id>", "tm:<twitter image key>" and "px:<artwork id>".
     */
    fun keysFromSource(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val out = LinkedHashSet<String>()
        twStatus.findAll(raw).forEach { out.add("tw:" + it.groupValues[1]) }
        twMedia.findAll(raw).forEach { out.add("tm:" + it.groupValues[1]) }
        pxArtwork.findAll(raw).forEach { out.add("px:" + it.groupValues[1]) }
        pxIllustId.findAll(raw).forEach { out.add("px:" + it.groupValues[1]) }
        pxImage.findAll(raw).forEach { out.add("px:" + it.groupValues[1]) }
        return out.toList()
    }

    fun twitterLink(host: String, handle: String, id: String): String = "https://$host/$handle/status/$id"

    /** Tweet ids are snowflakes: the post time (epoch ms) is in the top bits. 0 when the id is not a number. */
    fun twitterTimeMs(id: String): Long {
        val n = id.toLongOrNull() ?: return 0L
        return (n shr 22) + 1288834974657L
    }

    fun pixivLink(id: String): String = "https://www.pixiv.net/artworks/$id"
}

object Matcher {
    /** True when the item (by post id or by any of its image keys) is in the [known] key set. */
    fun isKnown(item: LinkItem, known: Set<String>): Boolean {
        if (known.isEmpty()) return false
        if (item.key in known) return true
        for (m in item.mediaKeys) if (("tm:$m") in known) return true
        return false
    }

    private fun idOf(item: LinkItem): Long = item.key.substringAfter(':').toLongOrNull() ?: 0L

    /** Keys of every item from the first to the last of [selected] (in list order), both ends included. */
    fun keysBetween(items: List<LinkItem>, selected: Set<String>): Set<String> {
        var first = -1
        var last = -1
        for (i in items.indices) {
            if (items[i].key in selected) {
                if (first < 0) first = i
                last = i
            }
        }
        if (first < 0) return emptySet()
        val out = LinkedHashSet<String>()
        for (i in first..last) out.add(items[i].key)
        return out
    }

    /** Ids are time-ordered on both sites, so sorting by id sorts by post date. */
    fun sortItems(items: List<LinkItem>, newestFirst: Boolean): List<LinkItem> =
        if (newestFirst) items.sortedByDescending { idOf(it) } else items.sortedBy { idOf(it) }
}
