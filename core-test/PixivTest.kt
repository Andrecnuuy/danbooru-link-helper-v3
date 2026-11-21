package com.dlh.helper

// Checks for the Pixiv parsers, preview data and session saving. Run with core-test/run.sh (no Android needed).

private var failures = 0

private fun <T> check(name: String, actual: T, expected: T) {
    if (actual == expected) {
        println("ok    $name")
    } else {
        failures++
        println("FAIL  $name\n      expected: $expected\n      actual:   $actual")
    }
}

private fun fails(name: String, block: () -> Unit) {
    val threw = try { block(); false } catch (e: Exception) { true }
    check(name, threw, true)
}

fun main() {
    // --- works list ---
    val all = """{"error":false,"body":{"illusts":{"300":null,"200":null},"manga":{"250":null},"novels":[]}}"""
    check("profile/all ids", PixivScanner.parseProfileAll(all).map { it.key }, listOf("px:300", "px:200", "px:250"))
    check("profile/all link", PixivScanner.parseProfileAll(all).first().url, "https://www.pixiv.net/artworks/300")
    check("profile/all empty sections", PixivScanner.parseProfileAll("""{"error":false,"body":{"illusts":[],"manga":[]}}""").size, 0)
    fails("profile/all error flag") { PixivScanner.parseProfileAll("""{"error":true,"message":"nope","body":[]}""") }

    // --- work details ---
    val works = """{"error":false,"body":{"works":{
      "300":{"id":"300","illustType":0,"pageCount":3,"width":1200,"height":1700,"userName":"Some Artist","url":"https://i.pximg.net/c/250x250_80_a2/custom-thumb/img/2020/01/02/03/04/05/300_p0_custom1200.jpg","createDate":"2020-01-02T03:04:05+09:00"},
      "200":{"id":"200","illustType":2,"pageCount":1,"url":"https://i.pximg.net/c/250x250_80_a2/custom-thumb/img/200_p0_custom1200.jpg","createDate":"2019-05-06T07:08:09+09:00"},
      "bad":{"illustType":0}}}}"""
    val parsed = PixivScanner.parseWorks(works)
    check("works ids", parsed.keys.toList(), listOf("300", "200"))
    check("works type", parsed["200"]?.type, 2)
    check("works pages", parsed["300"]?.pages, 3)
    check("works thumb", parsed["300"]?.thumb?.startsWith("https://i.pximg.net/"), true)
    // 2020-01-02T03:04:05+09:00 = 2020-01-01T18:04:05Z
    check("works date", parsed["300"]?.timeMs, 1577901845000L)
    check("date empty", PixivScanner.parseDate(""), 0L)
    check("date junk", PixivScanner.parseDate("yesterday"), 0L)

    // --- details applied to the scanned links ---
    val scanned = listOf("300", "200", "999").map { LinkItem("px:$it", "https://www.pixiv.net/artworks/$it") }
    val enriched = PixivScanner.applyDetails(scanned, parsed, MediaFilter.BOTH)
    check("details keep all", enriched.map { it.key }, listOf("px:300", "px:200", "px:999"))
    check("details fill in", enriched[0].let { Triple(it.count, it.kind, it.timeMs) }, Triple(3, KIND_IMAGE, 1577901845000L))
    check("details ugoira kind", enriched[1].kind, KIND_VIDEO)
    check("details unknown work unchanged", enriched[2], scanned[2])
    check("filter videos keeps ugoira only", PixivScanner.applyDetails(scanned, parsed, MediaFilter.VIDEOS).map { it.key }, listOf("px:200"))
    check("filter images drops ugoira", PixivScanner.applyDetails(scanned, parsed, MediaFilter.IMAGES).map { it.key }, listOf("px:300", "px:999"))


    // --- sizes, artist name, stacc ---
    check("works size", parsed["300"]?.let { it.width to it.height }, 1200 to 1700)
    check("works user name", parsed["300"]?.userName, "Some Artist")
    check("works without size", parsed["200"]?.let { it.width to it.height }, 0 to 0)
    check("details carry the size", enriched[0].sizes, listOf(1200, 1700))
    check("details without size keep none", enriched[1].sizes, emptyList<Int>())
    check("stacc id from final url", PixivScanner.userIdFromPage("https://www.pixiv.net/en/users/4242", ""), "4242")
    check("stacc id from canonical", PixivScanner.userIdFromPage("https://www.pixiv.net/stacc/x", """<head><link rel="canonical" href="https://www.pixiv.net/users/99"></head>"""), "99")
    check("stacc id from og:url", PixivScanner.userIdFromPage("https://www.pixiv.net/stacc/x", """<meta property="og:url" content="https://www.pixiv.net/users/17">"""), "17")
    check("stacc unresolved", PixivScanner.userIdFromPage("https://www.pixiv.net/stacc/x", "<html>no id here, see /users/5 in text</html>"), null)

    // --- artist URL list shapes ---
    check("urls from artist object", ArtistUrlParse.parse("""{"id":1,"name":"a","urls":[{"url":"https://x.com/abc","normalized_url":"http://twitter.com/abc/","is_active":true},{"url":"https://x.com/old","is_active":false}]}"""),
        listOf(ArtistUrl("https://x.com/abc", true), ArtistUrl("https://x.com/old", false)))
    check("urls from artist_urls list", ArtistUrlParse.parse("""[{"artist_id":1,"normalized_url":"http://twitter.com/abc/","is_active":true},{"artist_id":1,"url":"https://www.pixiv.net/users/5"}]"""),
        listOf(ArtistUrl("http://twitter.com/abc/", true), ArtistUrl("https://www.pixiv.net/users/5", true)))
    check("urls from url_string", ArtistUrlParse.parse("""{"id":1,"url_string":"https://x.com/abc\n-https://x.com/dead\n"}"""),
        listOf(ArtistUrl("https://x.com/abc", true), ArtistUrl("https://x.com/dead", false)))
    check("urls from string list", ArtistUrlParse.parse("""{"urls":["https://x.com/abc","-https://x.com/dead"]}"""),
        listOf(ArtistUrl("https://x.com/abc", true), ArtistUrl("https://x.com/dead", false)))
    check("urls none", ArtistUrlParse.parse("""{"id":1,"name":"a"}"""), emptyList<ArtistUrl>())
    check("urls junk", ArtistUrlParse.parse("<html>"), emptyList<ArtistUrl>())
    check("urls empty list", ArtistUrlParse.parse("[]"), emptyList<ArtistUrl>())

    // --- single work (preview) ---
    val one = """{"error":false,"body":{"illustType":2,"pageCount":1,"createDate":"2020-01-02T03:04:05+09:00",
      "urls":{"mini":"m","thumb":"t","small":"s","regular":"r","original":"o"}}}"""
    val info = Previews.parsePixivInfo(one)
    check("illust thumb/low/full", Triple(info?.thumb, info?.thumbLow, info?.full), Triple("s", "t", "r"))
    check("illust ugoira badge", info?.badge, true)
    check("illust not a playable video", info?.video, false)
    check("illust referer", info?.referer, Previews.PIXIV_REFERER)
    check("illust no urls", Previews.parsePixivInfo("""{"body":{}}"""), null)
    check("illust junk", Previews.parsePixivInfo("not json"), null)

    // --- preview data from stored items ---
    check("twimg small", Previews.twimgSized("https://pbs.twimg.com/media/AAA111.jpg", "small"),
        "https://pbs.twimg.com/media/AAA111?format=jpg&name=small")
    check("twimg thumb png", Previews.twimgSized("https://pbs.twimg.com/media/BBB.png", "thumb"),
        "https://pbs.twimg.com/media/BBB?format=png&name=thumb")
    check("twimg no extension", Previews.twimgSized("https://pbs.twimg.com/media/BBB", "small"), "https://pbs.twimg.com/media/BBB")
    check("twimg video thumb untouched", Previews.twimgSized("https://pbs.twimg.com/ext_tw_video_thumb/9/pu/img/V.jpg", "small"),
        "https://pbs.twimg.com/ext_tw_video_thumb/9/pu/img/V.jpg")
    check("no thumb, no info", Previews.fromItem(LinkItem("tw:1", "u")), null)
    val xv = Previews.fromItem(LinkItem("tw:1", "u", thumb = "https://pbs.twimg.com/media/A.jpg", full = "https://video.twimg.com/x.mp4", kind = KIND_VIDEO, count = 1, timeMs = 5L))
    check("x video info", Triple(xv?.video, xv?.badge, xv?.full), Triple(true, true, "https://video.twimg.com/x.mp4"))
    val xi = Previews.fromItem(LinkItem("tw:2", "u", thumb = "https://pbs.twimg.com/media/A.jpg", full = "https://pbs.twimg.com/media/A.jpg", count = 2))
    check("x image info", Triple(xi?.video, xi?.badge, xi?.count), Triple(false, false, 2))
    check("x image preview is medium", xi?.full, "https://pbs.twimg.com/media/A?format=jpg&name=medium")
    check("x image sizes", Pair(xi?.thumb, xi?.thumbLow),
        Pair("https://pbs.twimg.com/media/A?format=jpg&name=small", "https://pbs.twimg.com/media/A?format=jpg&name=thumb"))
    val pxi = Previews.fromItem(LinkItem("px:9", "u", thumb = "https://i.pximg.net/thumb.jpg"))
    check("pixiv stored: thumb only", Triple(pxi?.thumb, pxi?.full, pxi?.referer), Triple("https://i.pximg.net/thumb.jpg", null, Previews.PIXIV_REFERER))

    // --- session round trip (several sources) ---
    val s0 = SourceData(
        id = 0, ref = ArtistRef(Platform.TWITTER, "someone"), name = "someone", userId = "42", original = 10,
        removedDanbooru = 2, removedAssets = 3, removedManually = 4,
        items = listOf(
            LinkItem("tw:5", "https://x.com/someone/status/5", listOf("AAA", "BBB"), "https://pbs.twimg.com/media/AAA.jpg", "https://video.twimg.com/v.mp4", KIND_VIDEO, 2, 1700000000000L, listOf(100, 200, 300, 400)),
            LinkItem("tw:6", "https://x.com/someone/status/6")
        )
    )
    val s1 = SourceData(
        id = 3, ref = ArtistRef(Platform.PIXIV, "7"), name = "Some Artist", original = 1,
        items = listOf(LinkItem("px:7", "https://www.pixiv.net/artworks/7", thumb = "https://i.pximg.net/x.jpg", sizes = listOf(1200, 1700)))
    )
    val s2 = SourceData(id = 4, ref = ArtistRef(Platform.TWITTER, "206922466", numeric = true), original = 0)
    val data = SessionData(
        input = "https://x.com/someone", tag = "someone", sources = listOf(s0, s1, s2), active = 3,
        danbooruKeys = setOf("tw:1", "px:2"), danbooruPosts = 9, candidates = listOf("https://x.com/i/user/5")
    )
    val back = SessionStore.fromJson(SessionStore.toJson(data))
    check("session round trip", back, data)
    check("session keeps the shown source", back.active, 3)
    check("session keeps item sizes", back.sources[0].items[0].sizes, listOf(100, 200, 300, 400))
    check("session keeps numeric X ref", back.sources[2].ref, ArtistRef(Platform.TWITTER, "206922466", true))
    check("session title", back.sources.map { it.title }, listOf("X @someone", "Pixiv Some Artist", "X #206922466"))
    val transient = s0.copy(busy = true, status = "scanning", assetChecked = true, lazyThumbs = true)
    check("transient state is not saved", SessionStore.fromJson(SessionStore.toJson(data.copy(sources = listOf(transient)))).sources[0], s0)
    val gone = SessionStore.fromJson(SessionStore.toJson(data.copy(active = 99)))
    check("unknown active source falls back to the first", gone.active, 0)

    // --- sessions saved by the single-source versions ---
    val old = """{"input":"https://x.com/olduser","tag":"t","original":5,"rd":1,"ra":2,"rm":3,"dp":0,"items":[{"k":"tw:9","u":"u","m":["X"]}],"dk":[]}"""
    val loaded = SessionStore.fromJson(old)
    check("older session becomes one source", loaded.sources.size, 1)
    check("older session source", loaded.sources[0].let { Triple(it.ref, it.items, it.original) }, Triple(ArtistRef(Platform.TWITTER, "olduser"), listOf(LinkItem("tw:9", "u", listOf("X"))), 5))
    check("older session counters", loaded.sources[0].let { Triple(it.removedDanbooru, it.removedAssets, it.removedManually) }, Triple(1, 2, 3))
    check("older session active", loaded.active, 0)
    check("older session with no usable link", SessionStore.fromJson("""{"input":"hello","items":[{"k":"tw:9","u":"u"}]}""").sources.size, 0)
    check("older session without links", SessionStore.fromJson("""{"input":"https://x.com/a","items":[]}""").sources.size, 0)
    check("empty session", SessionStore.fromJson("{}").let { it.sources.size to it.active }, 0 to -1)

    println(if (failures == 0) "\nALL PASSED" else "\n$failures FAILED")
    if (failures != 0) kotlin.system.exitProcess(1)
}
