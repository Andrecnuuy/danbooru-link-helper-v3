package com.dlh.helper

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What a similarity run would do: how many pictures there are and how many still have to be downloaded. */
class SimPlan(val pictures: Int, val toDownload: Int, val needDetails: Int)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val appCtx: Application = app
    val settings = AppSettings(app)
    private val store = AssetStore(File(app.filesDir, "assets.json"))
    private val sessionFile = File(app.filesDir, "session.json")
    private val cookieFile = File(app.filesDir, "cookies.txt")
    private val featureFile = File(app.filesDir, "features.bin")
    private val featureCache = FeatureCache()

    // ---- 1. artist link / scan ----
    var artistInput by mutableStateOf("")
    var scanStatus by mutableStateOf("")
    var scanBusy by mutableStateOf(false)

    /** Which kinds of posts the next scan keeps. */
    var mediaFilter by mutableStateOf(settings.mediaFilter)

    // ---- sources: every account whose links are in the Links view ----
    var sources by mutableStateOf<List<SourceData>>(emptyList())

    /** The source shown in the Links view (-1 when there is none). */
    var activeId by mutableIntStateOf(-1)
    private var nextSourceId = 0

    /** Copy / save the links of every source instead of only the shown one. */
    var linksScopeAll by mutableStateOf(false)

    val activeSource: SourceData? get() = sources.firstOrNull { it.id == activeId }

    // The numbers and links of the shown source; the artist-wide actions below work on all sources.
    val items: List<LinkItem> get() = activeSource?.items ?: emptyList()
    val originalCount: Int get() = activeSource?.original ?: 0
    val removedByDanbooru: Int get() = activeSource?.removedDanbooru ?: 0
    val removedByAssets: Int get() = activeSource?.removedAssets ?: 0
    val removedManually: Int get() = activeSource?.removedManually ?: 0
    val assetChecked: Boolean get() = sources.any { it.assetChecked }
    val assetMatches: List<LinkItem> get() = activeSource?.assetMatches ?: emptyList()

    /** True when the bulk thumbnail request failed (or missed works), so grid cells look their thumbnail up one by one. */
    val lazyPixivThumbs: Boolean get() = activeSource?.lazyThumbs ?: false
    val totalLinks: Int get() = sources.sumOf { it.items.size }
    val totalAssetMatches: Int get() = sources.sumOf { it.assetMatches.size }

    // ---- 2. Danbooru API ----
    var apiLogin by mutableStateOf(settings.login)
    var apiKey by mutableStateOf(settings.apiKey)
    var apiStatus by mutableStateOf("")
    var apiBusy by mutableStateOf(false)

    /** Imported cookies (only x.com, twitter.com and pixiv.net ones are kept). */
    var cookieList by mutableStateOf<List<CookieEntry>>(emptyList())
    var cookieStatus by mutableStateOf("")

    // ---- 3. artist check ----
    var artistTag by mutableStateOf("")
    var candidates by mutableStateOf<List<ArtistHit>>(emptyList())
    var artistStatus by mutableStateOf("")
    var artistBusy by mutableStateOf(false)
    var danbooruKeys by mutableStateOf<Set<String>>(emptySet())
    var danbooruPosts by mutableIntStateOf(0)
    private var tagFor: ArtistRef? = null

    /** Other X / Pixiv accounts found in the artist's Danbooru URL list. */
    var otherPlatforms by mutableStateOf<List<SourceCandidate>>(emptyList())
    var otherStatus by mutableStateOf("")
    var otherBusy by mutableStateOf(false)

    // ---- 4. media assets ----
    var assetStatus by mutableStateOf("")
    var assetBusy by mutableStateOf(false)
    var assetStats by mutableStateOf(AssetStats.EMPTY)

    // ---- 5. links ----
    var linksNote by mutableStateOf("")

    /** Show the links as a grid of thumbnails instead of a list. */
    var gridView by mutableStateOf(settings.gridView)

    /** Load the smallest thumbnails in the grid. */
    var lowData by mutableStateOf(settings.lowData)

    /** Keys of the links (of the shown source) that are ticked; selection mode is on while this is not empty. */
    var selected by mutableStateOf<Set<String>>(emptySet())

    /** The link whose menu (preview / open / delete) is open. */
    var actionItem by mutableStateOf<LinkItem?>(null)

    /** The link whose preview is open. */
    var previewItem by mutableStateOf<LinkItem?>(null)

    /** True while the last hand-deletion can still be taken back. */
    var canUndo by mutableStateOf(false)

    /** What the last hand-deletion removed, per source id. */
    private var undoMap: Map<Int, List<LinkItem>>? = null

    // ---- 6. similar images ----
    var simBusy by mutableStateOf(false)
    var simStatus by mutableStateOf("")
    var simRan by mutableStateOf(false)
    var simThreshold by mutableIntStateOf(settings.simThreshold)

    /** Every pair found by the last run (the ones whose posts were since deleted are filtered out by [visiblePairs]). */
    var simPairs by mutableStateOf<List<SimPair>>(emptyList())

    /** Ids (SimImage.id) of the pictures marked for deletion. */
    var simMarked by mutableStateOf<Set<String>>(emptySet())

    /** More than 0 while the "this would delete every copy" warning is open: the number of groups affected. */
    var simWarning by mutableIntStateOf(0)

    val log = mutableStateListOf<String>()

    private var scanJob: Job? = null
    private var artistJob: Job? = null
    private var assetJob: Job? = null
    private var thumbJob: Job? = null
    private var simJob: Job? = null
    private val queue = ArrayDeque<Int>()

    /** Bumped whenever the results are replaced or cleared, so a scan that was stopped never writes into newer results. */
    private var scanGen = 0

    // ------------------------------------------------------------------ helpers

    fun addLog(msg: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        log.add("$t  $msg")
        while (log.size > 200) log.removeAt(0)
    }

    private fun newClient() =
        DanbooruClient(settings.baseUrl, apiLogin, apiKey, settings.delayMs) { addLog(it) }

    private fun errorText(e: Throwable): String = when (e) {
        is XException -> e.message ?: "The X scan failed."
        is HttpStatusException -> when {
            e.where.contains("pixiv") ->
                "Pixiv answered HTTP ${e.code}. It may be blocking the request; import your cookies.txt (Danbooru API card) and try again."
            e.code == 401 || e.code == 403 ->
                "HTTP ${e.code}: Danbooru refused the request. Check the username and API key."
            e.code == 422 -> "HTTP 422: Danbooru rejected the search (${e.body.take(120)})."
            else -> "HTTP ${e.code}. ${e.body.take(120)}"
        }
        is IOException -> "Network problem: ${e.message}"
        else -> "${e.javaClass.simpleName}: ${e.message}"
    }

    private fun persist() {
        val data = SessionData(
            input = artistInput,
            tag = artistTag,
            sources = sources,
            active = activeId,
            danbooruKeys = danbooruKeys,
            danbooruPosts = danbooruPosts,
            candidates = otherPlatforms.map { it.url }
        )
        viewModelScope.launch(Dispatchers.IO) {
            try {
                synchronized(sessionFile) { SessionStore.save(sessionFile, data) }
            } catch (e: Exception) {
                // Session saving is best effort.
            }
        }
    }

    private fun clearUndo() {
        undoMap = null
        canUndo = false
    }

    private fun updateSource(id: Int, change: (SourceData) -> SourceData) {
        sources = sources.map { if (it.id == id) change(it) else it }
    }

    private fun sourceById(id: Int): SourceData? = sources.firstOrNull { it.id == id }

    /** "Pixiv Name: " in front of messages when there is more than one source. */
    private fun prefix(id: Int): String =
        if (sources.size > 1) (sourceById(id)?.title ?: "") + ": " else ""

    /** Puts a fresh scan result in place of what the source held before (its deletions and counters start over). */
    private fun replaceItems(id: Int, list: List<LinkItem>) {
        val unique = LinkedHashMap<String, LinkItem>()
        for (i in list) if (!unique.containsKey(i.key)) unique[i.key] = i
        val sorted = Matcher.sortItems(unique.values.toList(), settings.newestFirst)
        updateSource(id) {
            it.copy(
                items = sorted,
                original = sorted.size,
                removedDanbooru = 0,
                removedAssets = 0,
                removedManually = 0,
                assetChecked = false,
                assetMatches = emptyList(),
                lazyThumbs = false
            )
        }
        if (id == activeId) {
            selected = emptySet()
            actionItem = null
            previewItem = null
        }
        val u = undoMap
        if (u != null && u.containsKey(id)) {
            val rest = u - id
            undoMap = rest.ifEmpty { null }
            canUndo = rest.isNotEmpty()
        }
        persist()
    }

    /** Keeps only the ticked links that are still in the shown source. */
    private fun pruneSelection() {
        if (selected.isEmpty()) return
        val present = HashSet<String>(items.size * 2)
        for (i in items) present.add(i.key)
        selected = selected.filter { it in present }.toSet()
    }

    private fun refreshAssetMatches() {
        if (!assetChecked) return
        val idx = store.index
        sources = sources.map { s ->
            if (s.assetChecked) s.copy(assetMatches = s.items.filter { Matcher.isKnown(it, idx) }) else s
        }
    }

    private fun refreshStats() {
        assetStats = store.stats
    }

    /** The links to copy or save: the shown source's, or every source's (each link once) when [linksScopeAll] is on. */
    private fun scopeItems(): List<LinkItem> {
        if (!linksScopeAll) return items
        val seen = HashSet<String>()
        val out = ArrayList<LinkItem>()
        for (s in sources) for (i in s.items) if (seen.add(i.url)) out.add(i)
        return out
    }

    fun linksInScope(): Int = scopeItems().size

    fun allLinksText(): String = scopeItems().joinToString("\n") { it.url }

    // ------------------------------------------------------------------ 2. API

    fun setLogin(v: String) {
        apiLogin = v
        settings.login = v
    }

    fun updateApiKey(v: String) {
        apiKey = v
        settings.apiKey = v
    }

    fun testApi() {
        if (apiBusy) return
        if (apiLogin.isBlank() || apiKey.isBlank()) {
            apiStatus = "Enter your Danbooru username and API key first."
            return
        }
        viewModelScope.launch {
            apiBusy = true
            apiStatus = "Checking…"
            try {
                val me = newClient().profile()
                val level = if (me.level.isNotEmpty()) ", ${me.level}" else ""
                apiStatus = "Connected as ${me.name} (id ${me.id}$level)."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                apiStatus = errorText(e)
                addLog("API test: ${errorText(e)}")
            } finally {
                apiBusy = false
            }
        }
    }

    // ------------------------------------------------------------------ 1. scan

    fun chooseFilter(f: MediaFilter) {
        mediaFilter = f
        settings.mediaFilter = f
    }

    /** Scans the pasted link as the first source. Everything the previous artist left behind is dropped. */
    fun startScan() {
        if (scanBusy) return
        val ref = UrlParsers.parseArtistLink(artistInput)
        if (ref == null) {
            scanStatus = "Couldn't read that link. Use something like https://x.com/handle or https://www.pixiv.net/users/12345"
            return
        }
        // X is read with the imported login cookies; without them there is nothing to try, so keep the old results.
        if (ref.platform == Platform.TWITTER) {
            val problem = xCookieProblem()
            if (problem != null) {
                scanStatus = problem
                return
            }
        }

        // New scan: drop the old sources, and the artist data if it belonged to a different artist.
        scanGen++
        scanJob?.cancel()
        thumbJob?.cancel()
        queue.clear()
        nextSourceId = 0
        sources = emptyList()
        activeId = -1
        danbooruKeys = emptySet()
        danbooruPosts = 0
        selected = emptySet()
        actionItem = null
        previewItem = null
        clearUndo()
        linksNote = ""
        otherPlatforms = emptyList()
        otherStatus = ""
        clearSimilar()
        if (tagFor != ref) {
            artistTag = ""
            candidates = emptyList()
            tagFor = null
            artistStatus = ""
        }
        val id = nextSourceId++
        sources = listOf(newSource(id, ref))
        activeId = id
        enqueue(id)
    }

    private fun newSource(id: Int, ref: ArtistRef) = SourceData(
        id = id,
        ref = ref,
        name = if (ref.platform == Platform.TWITTER && !ref.numeric) ref.id else "",
        busy = true,
        status = "Waiting…"
    )

    /** Null when X can be scanned, else what is missing. */
    private fun xCookieProblem(): String? {
        val x = xCookies()
        if (x.first.isEmpty()) {
            return "X needs your login. Import a cookies.txt from a browser that is logged in to X " +
                "(it must contain auth_token and ct0) in the Danbooru API card."
        }
        if (x.second.isEmpty()) {
            return "Your X cookies have no ct0 cookie. Export the cookies.txt again while logged in to X and import it."
        }
        return null
    }

    /** Puts a source in line to be scanned; sources are scanned one after the other. */
    private fun enqueue(id: Int) {
        queue.addLast(id)
        if (scanJob?.isActive == true) return
        val gen = scanGen
        scanJob = viewModelScope.launch {
            scanBusy = true
            try {
                while (gen == scanGen) {
                    val next = queue.removeFirstOrNull() ?: break
                    runSource(next, gen)
                }
            } finally {
                if (gen == scanGen) scanBusy = false
            }
        }
    }

    private suspend fun runSource(id: Int, gen: Int) {
        val s = sourceById(id) ?: return
        updateSource(id) { it.copy(busy = true, status = "Scanning…") }
        try {
            when (s.ref.platform) {
                Platform.TWITTER -> scanTwitterInto(id, gen)
                Platform.PIXIV -> scanPixivInto(id, gen)
            }
        } finally {
            if (gen == scanGen) updateSource(id) { it.copy(busy = false) }
        }
    }

    private fun finishSource(id: Int, gen: Int, status: String) {
        if (gen != scanGen) return
        updateSource(id) { it.copy(busy = false, status = status) }
        scanStatus = prefix(id) + status
    }

    private suspend fun scanTwitterInto(id: Int, gen: Int) {
        val ref = sourceById(id)?.ref ?: return
        val problem = xCookieProblem()
        if (problem != null) {
            finishSource(id, gen, problem)
            return
        }
        val x = xCookies()
        val filter = mediaFilter
        val userOverride = settings.twUserQid
        val mediaOverride = settings.twMediaQid
        val client = TwitterClient(
            cookieHeader = x.first,
            csrf = x.second,
            delayMs = settings.delayMs,
            log = { addLog(it) },
            userQueryId = userOverride.ifEmpty { settings.twUserQidFound },
            mediaQueryId = mediaOverride.ifEmpty { settings.twMediaQidFound },
            lockUser = userOverride.isNotEmpty(),
            lockMedia = mediaOverride.isNotEmpty()
        )
        val who = if (ref.numeric) "user ${ref.id}" else "@${ref.id}"
        scanStatus = prefix(id) + "Connecting to X…"
        addLog("X scan $who; keeping ${filter.name.lowercase()}; cookies sent: " +
            CookieFile.namesFor(cookieList, "x.com", nowSec()).joinToString())
        try {
            val list = client.scan(
                handle = if (ref.numeric) "" else ref.id,
                filter = filter,
                host = settings.twitterHost,
                onProgress = { pages, found ->
                    if (gen == scanGen) scanStatus = prefix(id) + "Reading $who's media timeline… page $pages, $found posts"
                },
                knownUserId = if (ref.numeric) ref.id else null,
                acceptUser = { uid ->
                    val other = sources.firstOrNull { it.id != id && it.userId == uid }
                    if (other == null) updateSource(id) { it.copy(userId = uid) }
                    other == null
                }
            )
            if (gen != scanGen) return
            if (userOverride.isEmpty() && client.userQueryId.isNotBlank()) settings.twUserQidFound = client.userQueryId
            if (mediaOverride.isEmpty() && client.mediaQueryId.isNotBlank()) settings.twMediaQidFound = client.mediaQueryId
            replaceItems(id, list)
            updateSource(id) { s ->
                s.copy(name = client.screenName.ifEmpty { s.name }, userId = client.resolvedUserId.ifEmpty { s.userId })
            }
            val videos = list.count { it.kind != KIND_IMAGE }
            val status = if (list.isEmpty()) {
                "No ${filterWords(filter)} found for $who. The account may have none, may be protected " +
                    "(your logged-in account has to follow it), or the filter hid everything."
            } else {
                "Found ${list.size} posts: ${list.size - videos} with images, $videos with video."
            }
            finishSource(id, gen, status)
            addLog("X $who: ${list.size} posts")
            persist()
        } catch (e: DuplicateSourceException) {
            if (gen == scanGen) {
                val other = sources.firstOrNull { it.id != id && it.userId == e.userId }
                dropSource(id)
                val msg = "Skipped $who: it is the same X account as ${other?.title ?: "a source already added"}."
                otherStatus = msg
                scanStatus = msg
                addLog(msg)
            }
        } catch (e: CancellationException) {
            if (gen == scanGen) {
                val partial = client.snapshot()
                if (partial.isNotEmpty()) {
                    replaceItems(id, partial)
                    finishSource(id, gen, "Stopped early with ${partial.size} posts.")
                }
            }
            throw e
        } catch (e: Exception) {
            if (gen == scanGen) {
                val partial = client.snapshot()
                val why = errorText(e)
                if (partial.isNotEmpty()) {
                    replaceItems(id, partial)
                    finishSource(id, gen, "$why Kept the ${partial.size} posts found before that.")
                } else {
                    finishSource(id, gen, why)
                }
                addLog("X scan: $why")
            }
        }
    }

    private suspend fun scanPixivInto(id: Int, gen: Int) {
        val ref = sourceById(id)?.ref ?: return
        val filter = mediaFilter
        val cookie = pixivCookie()
        val names = CookieFile.namesFor(cookieList, "www.pixiv.net", nowSec())
        scanStatus = prefix(id) + "Fetching the Pixiv works list…"
        addLog("Pixiv scan ${ref.id}; cookies sent: " + if (names.isEmpty()) "none" else names.joinToString())
        try {
            val all = PixivScanner.scan(ref.id, cookie)
            var list = all
            var note = ""
            var userName: String? = null
            var lazy = false
            if (all.isNotEmpty()) {
                // The works list has no types, sizes or thumbnails; the details (48 works per request) tell animations from
                // pictures and give the size the similarity check needs.
                try {
                    val details = PixivScanner.fetchDetails(ref.id, all.map { it.key.substringAfter(':') }, cookie) { n ->
                        if (gen == scanGen) scanStatus = prefix(id) + "Reading work details… $n of ${all.size}"
                    }
                    userName = details.values.firstNotNullOfOrNull { it.userName }
                    if (details.size * 2 < all.size) {
                        if (filter != MediaFilter.BOTH) {
                            note = " Pixiv gave details for only ${details.size} of ${all.size} works, so the filter was not applied."
                        }
                        addLog("Pixiv details incomplete: ${details.size} of ${all.size}")
                        list = PixivScanner.applyDetails(all, details, MediaFilter.BOTH)
                    } else {
                        list = PixivScanner.applyDetails(all, details, filter)
                    }
                    lazy = details.size < all.size
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    note = " Could not read the work details (${errorText(e)})" +
                        if (filter != MediaFilter.BOTH) ", so the filter was not applied." else "."
                    addLog("Pixiv details: ${errorText(e)}")
                    lazy = true
                }
            }
            if (gen != scanGen) return
            replaceItems(id, list)
            val shownName = userName ?: PixivScanner.userName(ref.id, cookie)
            updateSource(id) { it.copy(name = shownName ?: it.name, lazyThumbs = lazy) }
            val cookieNote = if (names.isEmpty()) " No Pixiv cookies are loaded, so R-18 works are hidden." else ""
            val status = if (list.isEmpty()) {
                "No works found for that Pixiv user. They may be private, or R-18 works need your cookies.txt (Danbooru API card).$cookieNote$note"
            } else {
                "Found ${list.size} works.$cookieNote$note"
            }
            finishSource(id, gen, status)
            addLog("Pixiv ${ref.id}: ${list.size} works")
            persist()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (gen == scanGen) {
                finishSource(id, gen, errorText(e))
                addLog("Pixiv scan: ${errorText(e)}")
            }
        }
    }

    private fun filterWords(f: MediaFilter): String = when (f) {
        MediaFilter.BOTH -> "posts"
        MediaFilter.IMAGES -> "image posts"
        MediaFilter.VIDEOS -> "video posts"
    }

    fun stopScan() {
        scanJob?.cancel()
        queue.clear()
        scanBusy = false
        scanStatus = "Stopped."
        sources = sources.map { if (it.busy) it.copy(busy = false, status = "Stopped.") else it }
    }

    /**
     * Pixiv grid thumbnails of the source [id] (default: the shown one): one request per 48 works instead of one per picture.
     * Does nothing for X (its posts carry their thumbnails already) or when every work already has one.
     */
    fun loadPixivThumbs(id: Int = activeId) {
        val src = sourceById(id) ?: return
        if (src.ref.platform != Platform.PIXIV) return
        if (thumbJob?.isActive == true) return
        val missing = src.items.filter { it.key.startsWith("px:") && it.thumb == null }.map { it.key.substringAfter(':') }
        if (missing.isEmpty()) return
        val gen = scanGen
        thumbJob = viewModelScope.launch {
            linksNote = "Loading Pixiv thumbnails…"
            try {
                val details = PixivScanner.fetchDetails(src.ref.id, missing, pixivCookie()) { n ->
                    if (gen == scanGen) linksNote = "Loading Pixiv thumbnails… $n of ${missing.size}"
                }
                if (gen != scanGen) return@launch
                updateSource(id) {
                    it.copy(
                        items = PixivScanner.applyDetails(it.items, details, MediaFilter.BOTH),
                        lazyThumbs = it.lazyThumbs || details.size < missing.size
                    )
                }
                linksNote = "Pixiv thumbnails loaded."
                persist()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen == scanGen) {
                    updateSource(id) { it.copy(lazyThumbs = true) }
                    linksNote = "Could not load the thumbnails in bulk (${errorText(e)}); they load one by one instead."
                    addLog("Pixiv thumbnails: ${errorText(e)}")
                }
            }
        }
    }

    // ------------------------------------------------------------------ sources

    /** Shows another source in the Links view. */
    fun selectSource(id: Int) {
        if (id == activeId || sourceById(id) == null) return
        activeId = id
        selected = emptySet()
        actionItem = null
        previewItem = null
        linksNote = ""
        if (gridView) loadPixivThumbs(id)
        persist()
    }

    private fun dropSource(id: Int) {
        val rest = sources.filter { it.id != id }
        sources = rest
        if (activeId == id) {
            activeId = rest.firstOrNull()?.id ?: -1
            selected = emptySet()
            actionItem = null
            previewItem = null
        }
        val u = undoMap
        if (u != null && u.containsKey(id)) {
            val left = u - id
            undoMap = left.ifEmpty { null }
            canUndo = left.isNotEmpty()
        }
        persist()
    }

    /** Takes a source out of the view (its links are gone from the list; add it again from the artist check to rescan). */
    fun removeSource(id: Int) {
        if (scanBusy) {
            linksNote = "Stop the scan first."
            return
        }
        val s = sourceById(id) ?: return
        dropSource(id)
        linksNote = "Removed ${s.title} from the view."
    }

    fun chooseScope(all: Boolean) {
        linksScopeAll = all
    }

    /**
     * True when this account is already one of the sources. A source whose scan failed or was stopped before it found
     * anything does not count, so pressing Add again scans it again.
     */
    fun isAdded(c: SourceCandidate): Boolean {
        val r = c.ref
        return sources.any { s ->
            (s.busy || s.items.isNotEmpty() || s.original > 0) &&
                (s.ref.key == c.key || (r != null && r.platform == Platform.TWITTER && r.numeric && s.userId == r.id))
        }
    }

    fun addCandidate(c: SourceCandidate) = addCandidates(listOf(c), showFirst = true)

    fun addAllCandidates() = addCandidates(otherPlatforms.filter { !isAdded(it) }, showFirst = false)

    /** Adds the accounts as sources and scans them (one after the other). Pixiv "stacc" links are turned into user ids first. */
    private fun addCandidates(list: List<SourceCandidate>, showFirst: Boolean) {
        if (list.isEmpty()) return
        viewModelScope.launch {
            val gen = scanGen
            var shown = false
            for (c in list) {
                if (gen != scanGen) return@launch
                var ref: ArtistRef? = c.ref
                if (ref == null) {
                    otherStatus = "Looking up the Pixiv user behind ${c.url}…"
                    val found = try {
                        PixivScanner.resolveStacc(c.stacc.orEmpty(), pixivCookie())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                    if (found == null) {
                        otherStatus = "Couldn't find the Pixiv user id behind ${c.url}. Open it in a browser and paste its " +
                            "pixiv.net/users/<number> link above instead."
                        addLog("Pixiv stacc not resolved: ${c.url}")
                        continue
                    }
                    val resolved = ArtistRef(Platform.PIXIV, found)
                    ref = resolved
                    otherPlatforms = otherPlatforms.map {
                        if (it.key == c.key) SourceCandidate(resolved, null, "https://www.pixiv.net/users/$found") else it
                    }
                }
                val existing = sources.firstOrNull { it.ref.key == ref.key }
                if (existing != null && (existing.busy || existing.items.isNotEmpty() || existing.original > 0)) continue
                if (ref.platform == Platform.TWITTER) {
                    val problem = xCookieProblem()
                    if (problem != null) {
                        otherStatus = problem
                        scanStatus = problem
                        return@launch
                    }
                }
                // A source that came up empty (failed or stopped) is scanned again under its old entry.
                val id = existing?.id ?: nextSourceId++
                sources = if (existing != null) {
                    sources.map { if (it.id == id) newSource(id, ref).copy(userId = it.userId) else it }
                } else {
                    sources + newSource(id, ref)
                }
                if (activeId < 0 || (showFirst && !shown)) {
                    activeId = id
                    selected = emptySet()
                    actionItem = null
                    previewItem = null
                }
                shown = true
                otherStatus = "Added ${ref.label}."
                enqueue(id)
            }
            persist()
        }
    }

    // ------------------------------------------------------------------ 5. selecting, deleting, viewing

    fun chooseGrid(on: Boolean) {
        gridView = on
        settings.gridView = on
        if (on) loadPixivThumbs()
    }

    fun chooseLowData(on: Boolean) {
        lowData = on
        settings.lowData = on
    }

    fun toggleSelect(key: String) {
        selected = if (key in selected) selected - key else selected + key
    }

    fun clearSelection() {
        selected = emptySet()
    }

    fun selectAll() {
        val all = LinkedHashSet<String>()
        for (i in items) all.add(i.key)
        selected = all
    }

    fun invertSelection() {
        val rest = LinkedHashSet<String>()
        for (i in items) if (i.key !in selected) rest.add(i.key)
        selected = rest
    }

    /** Ticks every link from the first ticked one to the last ticked one. */
    fun selectBetween() {
        if (selected.size < 2) return
        selected = Matcher.keysBetween(items, selected)
    }

    fun deleteSelected() {
        deleteKeys(selected)
    }

    fun deleteKeys(keys: Set<String>) {
        if (keys.isEmpty()) return
        val id = activeId
        val n = deleteFromSources(mapOf(id to keys))
        selected = emptySet()
        actionItem = null
        previewItem = null
        if (n > 0) linksNote = "Removed $n links by hand."
    }

    /**
     * Removes links by hand from any number of sources ([keys] per source id) and remembers them for [undoDelete].
     * Returns how many links went.
     */
    private fun deleteFromSources(keys: Map<Int, Set<String>>): Int {
        val removed = LinkedHashMap<Int, List<LinkItem>>()
        sources = sources.map { s ->
            val k = keys[s.id]
            if (k.isNullOrEmpty()) {
                s
            } else {
                val gone = s.items.filter { it.key in k }
                if (gone.isEmpty()) {
                    s
                } else {
                    removed[s.id] = gone
                    s.copy(items = s.items.filter { it.key !in k }, removedManually = s.removedManually + gone.size)
                }
            }
        }
        val total = removed.values.sumOf { it.size }
        if (total == 0) return 0
        undoMap = removed
        canUndo = true
        refreshAssetMatches()
        persist()
        return total
    }

    fun undoDelete() {
        val back = undoMap ?: return
        var n = 0
        sources = sources.map { s ->
            val list = back[s.id]
            if (list == null) {
                s
            } else {
                n += list.size
                s.copy(
                    items = Matcher.sortItems(s.items + list, settings.newestFirst),
                    removedManually = maxOf(0, s.removedManually - list.size)
                )
            }
        }
        linksNote = "Brought back $n links."
        clearUndo()
        refreshAssetMatches()
        persist()
    }

    // ------------------------------------------------------------------ cookies (cookies.txt)

    private fun nowSec(): Long = System.currentTimeMillis() / 1000

    /** The Cookie header for Pixiv requests, or null when no Pixiv cookies are loaded. */
    fun pixivCookie(): String? {
        val header = CookieFile.headerFor(cookieList, "www.pixiv.net", nowSec())
        return if (header.isEmpty()) null else header
    }

    /** The Cookie header and the ct0 value for X requests (twitter.com cookies from older exports work too). Empty strings when there is no login. */
    private fun xCookies(): Pair<String, String> {
        val now = nowSec()
        for (host in listOf("x.com", "twitter.com")) {
            if (CookieFile.valueFor(cookieList, "auth_token", listOf(host), now).isEmpty()) continue
            val header = CookieFile.headerFor(cookieList, host, now)
            val csrf = CookieFile.valueFor(cookieList, "ct0", listOf(host), now)
            return Pair(header, csrf)
        }
        return Pair("", "")
    }

    private fun expiryNote(sec: Long?): String = when {
        sec == null -> ""
        sec == 0L -> " (session cookie)"
        else -> " (expires " + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(sec * 1000)) + ")"
    }

    private fun updateCookieStatus(list: List<CookieEntry>, expiredSkipped: Int) {
        if (list.isEmpty()) {
            cookieStatus = "No cookies loaded. X and Pixiv hide login-only posts without them."
            return
        }
        val s = CookieFile.summarize(list)
        val x = if (s.xAuthToken) {
            "logged in" + expiryNote(s.xExpiresSec) + if (s.xCsrf) "" else ", ct0 missing"
        } else {
            "no auth_token (not logged in)"
        }
        val px = if (s.pixivSession) "logged in" + expiryNote(s.pixivExpiresSec) else "no PHPSESSID (not logged in)"
        val skipped = if (expiredSkipped > 0) " $expiredSkipped expired cookies were skipped." else ""
        cookieStatus = "Loaded ${list.size} cookies. X: $x. Pixiv: $px.$skipped"
    }

    fun importCookies(uri: Uri) {
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    val input = appCtx.contentResolver.openInputStream(uri)
                        ?: throw IOException("Could not read the file")
                    input.bufferedReader(Charsets.UTF_8).use { it.readText() }
                }
                importCookiesText(text)
            } catch (e: Exception) {
                cookieStatus = "Import failed: ${e.message}"
                addLog("Cookie import failed: ${e.message}")
            }
        }
    }

    /** Replaces the stored cookies with the ones in [text] (Netscape cookies.txt format). */
    fun importCookiesText(text: String) {
        viewModelScope.launch {
            try {
                val parsed = withContext(Dispatchers.Default) { CookieFile.parse(text, nowSec()) }
                if (parsed.cookies.isEmpty()) {
                    val why = if (parsed.expiredSkipped > 0) " All ${parsed.expiredSkipped} cookies in it had expired." else ""
                    cookieStatus = "Couldn't read any usable cookies. Expected the Netscape cookies.txt format " +
                        "(7 tab-separated columns).$why Nothing was replaced."
                    return@launch
                }
                val relevant = CookieFile.filterRelevant(parsed.cookies)
                if (relevant.isEmpty()) {
                    cookieStatus = "That file has no x.com, twitter.com or pixiv.net cookies. Nothing was replaced."
                    return@launch
                }
                withContext(Dispatchers.IO) { cookieFile.writeText(CookieFile.serialize(relevant)) }
                cookieList = relevant
                updateCookieStatus(relevant, parsed.expiredSkipped)
                addLog("Cookies replaced: ${relevant.size} kept of ${parsed.cookies.size} in the file")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                cookieStatus = "Import failed: ${e.message}"
                addLog("Cookie import failed: ${e.message}")
            }
        }
    }

    fun clearCookies() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { cookieFile.delete() }
            cookieList = emptyList()
            cookieStatus = "Cookies cleared."
        }
    }

    // ------------------------------------------------------------------ 3. artist check

    fun onTagEdited(v: String) {
        artistTag = v
        candidates = emptyList()
        tagFor = UrlParsers.parseArtistLink(artistInput)
    }

    /**
     * Finds the artist on Danbooru (from the pasted link, or the typed tag), reads the artist's other X / Pixiv accounts
     * from the artist's URL list, and fetches the artist's posts.
     */
    fun fetchArtistPosts(forceTag: String? = null) {
        if (artistBusy) return
        val ref = UrlParsers.parseArtistLink(artistInput)
        val typedTag = (forceTag ?: artistTag).trim()
        if (ref == null && typedTag.isEmpty()) {
            artistStatus = "Paste a Twitter/X or Pixiv profile link first (or type an artist tag below)."
            return
        }
        artistJob = viewModelScope.launch {
            artistBusy = true
            try {
                val client = newClient()
                var tag = typedTag
                val needFind = tag.isEmpty() ||
                    (forceTag == null && ref != null && tagFor != null && ref != tagFor)
                if (needFind) {
                    val r = ref ?: run {
                        artistStatus = "Paste a profile link first."
                        return@launch
                    }
                    artistStatus = "Searching Danbooru for this artist…"
                    val hits = client.findArtists(UrlParsers.artistUrlVariants(r))
                    candidates = hits
                    if (hits.isEmpty()) {
                        artistTag = ""
                        artistStatus = "No Danbooru artist matches that link. If the artist exists under another tag, type the tag in the box and fetch again."
                        addLog("No artist found for ${r.platform} ${r.id}")
                        return@launch
                    }
                    tag = hits.first().name
                    tagFor = r
                    addLog("Artist tag: $tag (${hits.size} candidate(s))")
                }
                artistTag = tag

                // The artist's other accounts (a few quick requests); a failure here must not stop the posts.
                try {
                    loadOtherPlatforms(client, tag, candidates.firstOrNull { it.name == tag })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    otherStatus = "Couldn't read the artist's links: ${errorText(e)}"
                    addLog("Artist links: ${errorText(e)}")
                }

                artistStatus = "Fetching posts for $tag…"
                val res = client.fetchTagSources(tag, settings.includeDeleted) { n ->
                    artistStatus = "Fetching posts for $tag… $n"
                }
                val keys = withContext(Dispatchers.Default) {
                    val s = HashSet<String>()
                    for (src in res.sources) s.addAll(UrlParsers.keysFromSource(src))
                    s
                }
                danbooruKeys = keys
                danbooruPosts = res.posts
                val total = totalLinks
                val overlap = sources.sumOf { s -> s.items.count { Matcher.isKnown(it, keys) } }
                val tail = if (total == 0) {
                    "Scan the artist's links, then purge."
                } else {
                    "$overlap of $total scanned links are already on Danbooru."
                }
                artistStatus = "$tag: ${res.posts} posts, ${keys.size} source keys. $tail"
                addLog("Artist posts $tag: ${res.posts} posts, ${keys.size} keys")
                refreshAssetMatches()
                persist()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                artistStatus = errorText(e)
                addLog("Artist posts: ${errorText(e)}")
            } finally {
                artistBusy = false
            }
        }
    }

    /** Reads the artist's URL list again without fetching the posts. */
    fun findOtherPlatforms() {
        if (otherBusy || artistBusy) return
        val tag = artistTag.trim()
        val ref = UrlParsers.parseArtistLink(artistInput)
        if (tag.isEmpty() && ref == null) {
            otherStatus = "Paste a profile link first (or type an artist tag)."
            return
        }
        viewModelScope.launch {
            otherBusy = true
            try {
                val client = newClient()
                var name = tag
                var hit: ArtistHit? = candidates.firstOrNull { it.name == tag }
                if (name.isEmpty() && ref != null) {
                    otherStatus = "Searching Danbooru for this artist…"
                    val hits = client.findArtists(UrlParsers.artistUrlVariants(ref))
                    candidates = hits
                    hit = hits.firstOrNull()
                    if (hit == null) {
                        otherStatus = "No Danbooru artist matches that link. Type the artist tag and try again."
                        return@launch
                    }
                    name = hit.name
                    artistTag = name
                    tagFor = ref
                }
                loadOtherPlatforms(client, name, hit)
                persist()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                otherStatus = "Couldn't read the artist's links: ${errorText(e)}"
                addLog("Artist links: ${errorText(e)}")
            } finally {
                otherBusy = false
            }
        }
    }

    private suspend fun loadOtherPlatforms(client: DanbooruClient, tag: String, known: ArtistHit?) {
        otherStatus = "Reading the artist's links on Danbooru…"
        val hit = known ?: client.findArtistByName(tag)
        if (hit == null) {
            otherPlatforms = emptyList()
            otherStatus = "Couldn't find a Danbooru artist named $tag, so its links can't be read."
            return
        }
        val urls = client.artistUrls(hit.id, hit.name)
        if (urls.isEmpty()) {
            otherStatus = "Danbooru gave no URL list for ${hit.name} (the log says which requests were tried)."
            return
        }
        val primary = UrlParsers.parseArtistLink(artistInput)
        val found = UrlParsers.sourceCandidates(urls, primary)
        otherPlatforms = found
        val inactive = urls.count { !it.active }
        otherStatus = if (found.isEmpty()) {
            "No other X or Pixiv accounts are listed for ${hit.name}. ($inactive inactive links ignored.)"
        } else {
            "${found.size} other account${if (found.size == 1) "" else "s"} listed for ${hit.name}. ($inactive inactive links ignored.)"
        }
        addLog("Artist links ${hit.name}: ${urls.size} urls, ${found.size} other accounts, $inactive inactive")
    }

    fun stopArtist() {
        artistJob?.cancel()
        artistBusy = false
        artistStatus = "Stopped."
    }

    /** Removes the links already on Danbooru from every source. */
    fun purgeDanbooru() {
        if (danbooruKeys.isEmpty()) {
            artistStatus = "Fetch the artist's Danbooru posts first."
            return
        }
        var dropped = 0
        var left = 0
        sources = sources.map { s ->
            val keep = s.items.filter { !Matcher.isKnown(it, danbooruKeys) }
            val gone = s.items.size - keep.size
            dropped += gone
            left += keep.size
            s.copy(items = keep, removedDanbooru = s.removedDanbooru + gone)
        }
        pruneSelection()
        val where = if (sources.size > 1) " across ${sources.size} sources" else ""
        artistStatus = "Removed $dropped links already on Danbooru$where. $left left."
        refreshAssetMatches()
        persist()
    }

    // ------------------------------------------------------------------ 4. media assets

    fun fetchAssets() {
        if (assetBusy) return
        if (apiLogin.isBlank() || apiKey.isBlank()) {
            assetStatus = "Enter your Danbooru username and API key first."
            return
        }
        assetJob = viewModelScope.launch {
            assetBusy = true
            try {
                val client = newClient()
                assetStatus = "Checking the account…"
                val me = client.profile()
                val marker = store.lastSeenId
                addLog("Asset fetch for ${me.name} (id ${me.id}); stopping at upload #$marker")
                val fetcher = MediaAssetFetcher(client) { addLog(it) }
                val res = fetcher.fetchNew(me.id, marker, settings.onlyUnposted) { seen, links ->
                    assetStatus = "Scanned $seen new uploads, $links links…"
                }
                val added = withContext(Dispatchers.IO) { store.addAndSave(res.entries, res.newestId) }
                refreshStats()
                refreshAssetMatches()
                val tail = if (res.reachedMarker) " Stopped at the previous fetch point." else ""
                assetStatus = "Done: $added new links from ${res.uploadsSeen} new uploads.$tail"
                addLog("Asset fetch: +$added links, ${res.uploadsSeen} uploads, newest #${res.newestId}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                assetStatus = errorText(e)
                addLog("Asset fetch: ${errorText(e)}")
            } finally {
                assetBusy = false
            }
        }
    }

    fun stopAssets() {
        assetJob?.cancel()
        assetBusy = false
        assetStatus = "Stopped. Nothing from this run was saved."
    }

    /** Looks which links of every source are already in the media assets. */
    fun checkAssets() {
        if (totalLinks == 0) {
            assetStatus = "No scanned links yet. Scan an artist first."
            return
        }
        if (store.stats.links == 0) {
            assetStatus = "The stored link database is empty. Fetch your media assets (or import a backup) first."
            return
        }
        val idx = store.index
        sources = sources.map { s -> s.copy(assetChecked = true, assetMatches = s.items.filter { Matcher.isKnown(it, idx) }) }
        assetStatus = "${totalAssetMatches} of $totalLinks links are already in your media assets."
    }

    fun purgeAssets() {
        if (!assetChecked) {
            checkAssets()
            if (!assetChecked) return
        }
        val idx = store.index
        var dropped = 0
        var left = 0
        sources = sources.map { s ->
            val keep = s.items.filter { !Matcher.isKnown(it, idx) }
            val gone = s.items.size - keep.size
            dropped += gone
            left += keep.size
            s.copy(items = keep, removedAssets = s.removedAssets + gone, assetMatches = emptyList())
        }
        pruneSelection()
        val where = if (sources.size > 1) " across ${sources.size} sources" else ""
        assetStatus = "Removed $dropped links already in your media assets$where. $left left."
        persist()
    }

    fun exportAssets(uri: Uri) {
        viewModelScope.launch {
            try {
                val n = withContext(Dispatchers.IO) {
                    val text = store.exportJson()
                    val out = appCtx.contentResolver.openOutputStream(uri, "wt")
                        ?: throw IOException("Could not open the file for writing")
                    out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    store.stats.links
                }
                assetStatus = "Exported $n links."
            } catch (e: Exception) {
                assetStatus = "Export failed: ${e.message}"
                addLog("Export failed: ${e.message}")
            }
        }
    }

    fun importAssets(uri: Uri) {
        viewModelScope.launch {
            try {
                val res = withContext(Dispatchers.IO) {
                    val input = appCtx.contentResolver.openInputStream(uri)
                        ?: throw IOException("Could not read the file")
                    val text = input.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    store.importText(text)
                }
                refreshStats()
                refreshAssetMatches()
                assetStatus = "Imported ${res.added} new links (${res.total} stored in total)."
            } catch (e: Exception) {
                assetStatus = "Import failed: ${e.message}"
                addLog("Import failed: ${e.message}")
            }
        }
    }

    fun resetAssetMarker() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.resetMarker() }
            refreshStats()
            assetStatus = "Fetch point reset. The next fetch scans everything again (stored links are kept)."
        }
    }

    fun clearStoredAssets() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.clear() }
            refreshStats()
            refreshAssetMatches()
            assetStatus = "Stored links cleared."
        }
    }

    // ------------------------------------------------------------------ 5. links

    fun saveLinks(uri: Uri) {
        val text = allLinksText()
        val count = linksInScope()
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val out = appCtx.contentResolver.openOutputStream(uri, "wt")
                        ?: throw IOException("Could not open the file for writing")
                    out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                }
                linksNote = "Saved $count links."
            } catch (e: Exception) {
                linksNote = "Save failed: ${e.message}"
            }
        }
    }

    fun clearAll() {
        scanGen++
        scanJob?.cancel()
        artistJob?.cancel()
        thumbJob?.cancel()
        queue.clear()
        artistInput = ""
        artistTag = ""
        candidates = emptyList()
        tagFor = null
        sources = emptyList()
        activeId = -1
        nextSourceId = 0
        danbooruKeys = emptySet()
        danbooruPosts = 0
        otherPlatforms = emptyList()
        otherStatus = ""
        selected = emptySet()
        actionItem = null
        previewItem = null
        clearUndo()
        clearSimilar()
        scanStatus = ""
        artistStatus = ""
        linksNote = ""
        scanBusy = false
        artistBusy = false
        otherBusy = false
        persist()
    }

    // ------------------------------------------------------------------ 6. similar images

    private fun clearSimilar() {
        simJob?.cancel()
        simBusy = false
        simPairs = emptyList()
        simMarked = emptySet()
        simWarning = 0
        simRan = false
        simStatus = ""
    }

    fun chooseSimThreshold(v: Int) {
        simThreshold = v
        settings.simThreshold = v
    }

    /** How many pictures a run would look at and how many it still has to download (the rest is remembered from before). */
    fun simPlan(): SimPlan {
        val tl = SimilarityScan.targets(sources)
        val needDetails = sources.filter { it.ref.platform == Platform.PIXIV }
            .sumOf { s -> s.items.count { SimilarityUrls.pixiv(it.thumb) == null } }
        return SimPlan(tl.targets.size, SimilarityScan.pending(tl.targets, featureCache), needDetails)
    }

    fun stopSimilar() {
        simJob?.cancel()
        simBusy = false
        simStatus = "Stopped. Pictures already downloaded are remembered."
    }

    /** Downloads small copies of every picture (only the ones not seen before), and lists the pairs that look alike. */
    fun runSimilarity() {
        if (simBusy) return
        if (totalLinks == 0) {
            simStatus = "No links yet. Scan an artist first."
            return
        }
        simJob = viewModelScope.launch {
            simBusy = true
            simMarked = emptySet()
            simWarning = 0
            try {
                // Pixiv works need their thumbnail address and size first (48 per request).
                for (s in sources.filter { it.ref.platform == Platform.PIXIV }) {
                    val missing = s.items.filter { SimilarityUrls.pixiv(it.thumb) == null || it.sizes.isEmpty() }
                        .map { it.key.substringAfter(':') }
                    if (missing.isEmpty()) continue
                    simStatus = "Reading Pixiv work details for ${s.title}…"
                    try {
                        val details = PixivScanner.fetchDetails(s.ref.id, missing, pixivCookie()) { n ->
                            simStatus = "Reading Pixiv work details for ${s.title}… $n of ${missing.size}"
                        }
                        updateSource(s.id) { it.copy(items = PixivScanner.applyDetails(it.items, details, MediaFilter.BOTH)) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        addLog("Pixiv details for similarity: ${errorText(e)}")
                    }
                }

                val tl = SimilarityScan.targets(sources)
                if (tl.targets.isEmpty()) {
                    simStatus = "Nothing to compare: no pictures in the links."
                    return@launch
                }
                val pending = SimilarityScan.pending(tl.targets, featureCache)
                addLog("Similarity: ${tl.targets.size} pictures, $pending to download")
                val run = SimilarityScan.collect(
                    targets = tl.targets,
                    cache = featureCache,
                    parallel = 4,
                    fetch = { t -> ImageLoader.loadFeature(t.url, if (t.platform == Platform.PIXIV) Previews.PIXIV_REFERER else null) }
                ) { done, total, failed ->
                    val bad = if (failed > 0) " ($failed failed)" else ""
                    simStatus = "Downloading pictures… $done of $total$bad"
                }
                // Keep only what the current links need, so the file does not grow with every artist.
                val wanted = tl.targets.map { it.cacheKey }.toHashSet()
                withContext(Dispatchers.IO + NonCancellable) {
                    featureCache.retainOnly(wanted)
                    try {
                        featureCache.save(featureFile)
                    } catch (e: IOException) {
                        // The cache is a convenience.
                    }
                }
                simStatus = "Comparing ${run.images.size} pictures…"
                val threshold = simThreshold
                val res = withContext(Dispatchers.Default) {
                    Similarity.find(run.images, threshold, shouldStop = { !isActive })
                }
                simPairs = res.pairs
                simRan = true
                val notes = ArrayList<String>()
                if (run.failed > 0) notes.add("${run.failed} pictures could not be downloaded")
                if (tl.withoutPicture > 0) notes.add("${tl.withoutPicture} Pixiv works had no thumbnail address")
                if (res.truncated) notes.add("stopped at ${Similarity.MAX_PAIRS} pairs; raise the match level")
                simStatus = "Compared ${res.compared} pictures: ${res.pairs.size} pairs at $threshold% or more." +
                    if (notes.isEmpty()) "" else " (" + notes.joinToString("; ") + ")"
                addLog("Similarity: ${res.pairs.size} pairs among ${res.compared} pictures")
                persist()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                simStatus = errorText(e)
                addLog("Similarity: ${errorText(e)}")
            } finally {
                simBusy = false
            }
        }
    }

    // What the Similar tab shows: the pairs whose two posts are both still in their sources.
    private var visSources: List<SourceData>? = null
    private var visPairs: List<SimPair>? = null
    private var visResult: List<SimPair> = emptyList()
    private var visUrls: Map<String, String> = emptyMap()

    private fun refreshVisible() {
        val s = sources
        val p = simPairs
        if (s === visSources && p === visPairs) return
        val urls = HashMap<String, String>()
        for (src in s) for (item in src.items) urls["${src.id}|${item.key}"] = item.url
        visResult = p.filter { it.left.itemId in urls && it.right.itemId in urls }
        visUrls = urls
        visSources = s
        visPairs = p
    }

    fun visiblePairs(): List<SimPair> {
        refreshVisible()
        return visResult
    }

    /** The link of the post a picture belongs to, or null when it is gone. */
    fun postUrl(img: SimImage): String? {
        refreshVisible()
        return visUrls[img.itemId]
    }

    fun sourceTitle(id: Int): String = sourceById(id)?.title ?: "?"

    fun simToggle(imageId: String) {
        simMarked = if (imageId in simMarked) simMarked - imageId else simMarked + imageId
    }

    /** Marks the left (or right) picture of every listed pair. */
    fun simMarkSide(left: Boolean) {
        val m = LinkedHashSet<String>()
        for (p in visiblePairs()) m.add(if (left) p.left.id else p.right.id)
        simMarked = m
    }

    fun simClearMarks() {
        simMarked = emptySet()
    }

    /** How many listed posts the marked pictures belong to. */
    fun simMarkedPosts(): Int {
        val doomed = HashSet<String>()
        for (p in visiblePairs()) {
            if (p.left.id in simMarked) doomed.add(p.left.itemId)
            if (p.right.id in simMarked) doomed.add(p.right.itemId)
        }
        return doomed.size
    }

    /** Deletes the posts of the marked pictures, unless that would leave some group of look-alikes with no copy at all. */
    fun requestDeleteMarked() {
        val vis = visiblePairs()
        val doomed = HashSet<String>()
        for (p in vis) {
            if (p.left.id in simMarked) doomed.add(p.left.itemId)
            if (p.right.id in simMarked) doomed.add(p.right.itemId)
        }
        if (doomed.isEmpty()) {
            simStatus = "Nothing is marked. Tap a picture to mark it, or use the buttons for a whole side."
            return
        }
        val loses = Similarity.groupsLosingEverything(vis, doomed)
        if (loses > 0) {
            simWarning = loses
            return
        }
        confirmDeleteMarked()
    }

    fun cancelSimWarning() {
        simWarning = 0
    }

    fun confirmDeleteMarked() {
        simWarning = 0
        val keys = HashMap<Int, MutableSet<String>>()
        for (p in visiblePairs()) {
            for (img in listOf(p.left, p.right)) {
                if (img.id in simMarked) keys.getOrPut(img.sourceId) { HashSet() }.add(img.itemKey)
            }
        }
        val n = deleteFromSources(keys)
        simMarked = emptySet()
        selected = emptySet()
        actionItem = null
        previewItem = null
        simStatus = "Removed $n posts from the links. Undo is in the Links tab."
        linksNote = "Removed $n links by hand (from the Similar images tab)."
    }

    // ------------------------------------------------------------------ settings

    fun saveSettings(
        base: String,
        delayMs: Long,
        twitterHost: String,
        includeDeleted: Boolean,
        onlyUnposted: Boolean,
        newestFirst: Boolean,
        userQueryId: String,
        mediaQueryId: String
    ) {
        val hostChanged = twitterHost != settings.twitterHost
        val orderChanged = newestFirst != settings.newestFirst
        settings.baseUrl = base
        settings.delayMs = delayMs
        settings.twitterHost = twitterHost
        settings.includeDeleted = includeDeleted
        settings.onlyUnposted = onlyUnposted
        settings.newestFirst = newestFirst
        settings.twUserQid = userQueryId
        settings.twMediaQid = mediaQueryId
        if (hostChanged || orderChanged) {
            sources = sources.map { s ->
                var list = s.items
                if (hostChanged) {
                    list = list.map {
                        if (it.key.startsWith("tw:")) {
                            it.copy(url = it.url.replaceFirst(Regex("^https://[^/]+/"), "https://$twitterHost/"))
                        } else {
                            it
                        }
                    }
                }
                s.copy(items = Matcher.sortItems(list, newestFirst))
            }
            persist()
        }
    }

    // ------------------------------------------------------------------ startup (keep last in the class)

    init {
        ImageLoader.init(appCtx)
        viewModelScope.launch {
            val storeError = withContext(Dispatchers.IO) {
                try {
                    store.load()
                    null
                } catch (e: Exception) {
                    e.message ?: "unknown error"
                }
            }
            if (storeError != null) addLog("Could not read the stored links: $storeError")
            refreshStats()

            withContext(Dispatchers.IO) { featureCache.load(featureFile) }

            val storedCookies = withContext(Dispatchers.IO) {
                try {
                    if (cookieFile.exists()) CookieFile.parse(cookieFile.readText(), nowSec()) else null
                } catch (e: Exception) {
                    null
                }
            }
            if (storedCookies != null && storedCookies.cookies.isNotEmpty()) {
                cookieList = storedCookies.cookies
                updateCookieStatus(storedCookies.cookies, storedCookies.expiredSkipped)
            }

            val s = withContext(Dispatchers.IO) {
                try {
                    SessionStore.load(sessionFile)
                } catch (e: Exception) {
                    null
                }
            }
            if (s != null) {
                artistInput = s.input
                artistTag = s.tag
                sources = s.sources
                activeId = s.active
                nextSourceId = (s.sources.maxOfOrNull { it.id } ?: -1) + 1
                danbooruKeys = s.danbooruKeys
                danbooruPosts = s.danbooruPosts
                otherPlatforms = UrlParsers.sourceCandidates(s.candidates.map { ArtistUrl(it, true) }, null)
                tagFor = if (s.tag.isNotBlank()) UrlParsers.parseArtistLink(s.input) else null
                val total = s.sources.sumOf { it.items.size }
                if (total > 0) {
                    scanStatus = "Restored your last session ($total links" +
                        if (s.sources.size > 1) " in ${s.sources.size} sources)." else ")."
                }
                if (gridView) loadPixivThumbs()
            }
        }
    }
}
