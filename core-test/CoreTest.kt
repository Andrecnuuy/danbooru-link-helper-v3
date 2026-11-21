package com.dlh.helper

// Plain JVM check of Core.kt. Run with core-test/run.sh (no Android needed).

private var failures = 0

private fun <T> check(name: String, actual: T, expected: T) {
    if (actual == expected) {
        println("ok    $name")
    } else {
        failures++
        println("FAIL  $name\n      expected: $expected\n      actual:   $actual")
    }
}

fun main() {
    val p = UrlParsers

    // --- artist links ---
    check("x handle", p.parseArtistLink("https://x.com/some_artist"), ArtistRef(Platform.TWITTER, "some_artist"))
    check("twitter handle + tail", p.parseArtistLink("https://twitter.com/Some_Artist/media?lang=en"), ArtistRef(Platform.TWITTER, "Some_Artist"))
    check("no scheme", p.parseArtistLink("x.com/abc123"), ArtistRef(Platform.TWITTER, "abc123"))
    check("@ handle", p.parseArtistLink("https://x.com/@abc"), ArtistRef(Platform.TWITTER, "abc"))
    check("reserved path", p.parseArtistLink("https://x.com/home"), null)
    check("tweet link falls back to its author", p.parseArtistLink("https://x.com/abc/status/123"), ArtistRef(Platform.TWITTER, "abc"))
    check("pixiv artwork link has no user id", p.parseArtistLink("https://www.pixiv.net/artworks/123"), null)
    check("pixiv new", p.parseArtistLink("https://www.pixiv.net/users/12345"), ArtistRef(Platform.PIXIV, "12345"))
    check("pixiv lang + tab", p.parseArtistLink("https://www.pixiv.net/en/users/12345/artworks"), ArtistRef(Platform.PIXIV, "12345"))
    check("pixiv old", p.parseArtistLink("https://www.pixiv.net/member.php?id=777"), ArtistRef(Platform.PIXIV, "777"))
    check("pixiv old illust list", p.parseArtistLink("https://www.pixiv.net/member_illust.php?id=888&type=all"), ArtistRef(Platform.PIXIV, "888"))
    check("garbage", p.parseArtistLink("hello"), null)
    check("blank", p.parseArtistLink("   "), null)

    // --- source keys ---
    check("tweet url", p.keysFromSource("https://twitter.com/abc/status/1234567890123456789"), listOf("tw:1234567890123456789"))
    check("x url + query", p.keysFromSource("https://x.com/abc/status/42?s=20"), listOf("tw:42"))
    check("i/web/status", p.keysFromSource("https://twitter.com/i/web/status/99"), listOf("tw:99"))
    check("fxtwitter", p.keysFromSource("https://fxtwitter.com/abc/status/5"), listOf("tw:5"))
    check("x.com false friend", p.keysFromSource("https://box.com/abc/status/5"), emptyList())
    check("twimg media", p.keysFromSource("https://pbs.twimg.com/media/F3x9aBcDeFg?format=jpg&name=orig"), listOf("tm:F3x9aBcDeFg"))
    check("twimg media ext", p.keysFromSource("https://pbs.twimg.com/media/F3x9aBcDeFg.jpg:orig"), listOf("tm:F3x9aBcDeFg"))
    check("pixiv artworks", p.keysFromSource("https://www.pixiv.net/artworks/12345678"), listOf("px:12345678"))
    check("pixiv en artworks", p.keysFromSource("https://www.pixiv.net/en/artworks/12345678"), listOf("px:12345678"))
    check("pixiv old", p.keysFromSource("https://www.pixiv.net/member_illust.php?mode=medium&illust_id=555"), listOf("px:555"))
    check("pixiv short", p.keysFromSource("https://www.pixiv.net/i/321"), listOf("px:321"))
    check("pximg original", p.keysFromSource("https://i.pximg.net/img-original/img/2020/01/02/03/04/05/12345678_p0.png"), listOf("px:12345678"))
    check("pximg master", p.keysFromSource("https://i.pximg.net/img-master/img/2020/01/02/03/04/05/12345678_p3_master1200.jpg"), listOf("px:12345678"))
    check("pximg ugoira", p.keysFromSource("https://i.pximg.net/img-zip-ugoira/img/2020/01/02/03/04/05/999_ugoira1920x1080.zip"), listOf("px:999"))
    check("unrelated", p.keysFromSource("https://example.com/post/1"), emptyList())
    check("null", p.keysFromSource(null), emptyList())

    // --- matching ---
    val known = setOf("tw:1", "tm:KEYA", "px:9")
    check("match by id", Matcher.isKnown(LinkItem("tw:1", "u"), known), true)
    check("match by media key", Matcher.isKnown(LinkItem("tw:2", "u", listOf("zzz", "KEYA")), known), true)
    check("no match", Matcher.isKnown(LinkItem("tw:3", "u", listOf("zzz")), known), false)
    check("pixiv match", Matcher.isKnown(LinkItem("px:9", "u"), known), true)
    check("empty known", Matcher.isKnown(LinkItem("tw:1", "u"), emptySet()), false)

    // --- sorting ---
    val items = listOf(LinkItem("tw:5", "a"), LinkItem("tw:100", "b"), LinkItem("tw:20", "c"))
    check("newest first", Matcher.sortItems(items, true).map { it.key }, listOf("tw:100", "tw:20", "tw:5"))
    check("oldest first", Matcher.sortItems(items, false).map { it.key }, listOf("tw:5", "tw:20", "tw:100"))

    // --- link builders / variants ---
    check("tw link", p.twitterLink("x.com", "abc", "42"), "https://x.com/abc/status/42")
    check("px link", p.pixivLink("42"), "https://www.pixiv.net/artworks/42")
    check("variants first", p.artistUrlVariants(ArtistRef(Platform.PIXIV, "7")).first(), "https://www.pixiv.net/users/7")

    // --- cookies.txt ---
    val t = "\t"
    val now = 1_700_000_000L
    val sample = listOf(
        "# Netscape HTTP Cookie File",
        "",
        ".x.com${t}TRUE${t}/${t}TRUE${t}1893456000${t}auth_token${t}abc123",
        "#HttpOnly_.x.com${t}TRUE${t}/${t}TRUE${t}1893456000${t}ct0${t}csrfvalue",
        ".x.com${t}TRUE${t}/${t}TRUE${t}1000${t}old${t}expired",
        ".pixiv.net${t}TRUE${t}/${t}FALSE${t}0${t}PHPSESSID${t}12345_secret",
        ".example.com${t}TRUE${t}/${t}FALSE${t}0${t}other${t}zzz",
        "garbage line"
    ).joinToString("\n")
    val parsed = CookieFile.parse(sample, now)
    check("cookie count", parsed.cookies.size, 4)
    check("cookie expired skipped", parsed.expiredSkipped, 1)
    check("cookie bad lines", parsed.badLines, 1)
    check("cookie httponly prefix", parsed.cookies.first { it.name == "ct0" }.httpOnly, true)
    val rel = CookieFile.filterRelevant(parsed.cookies)
    check("cookie relevant only", rel.map { it.name }, listOf("auth_token", "ct0", "PHPSESSID"))
    check("cookie header x", CookieFile.headerFor(rel, "x.com", now), "auth_token=abc123; ct0=csrfvalue")
    check("cookie header pixiv", CookieFile.headerFor(rel, "www.pixiv.net", now), "PHPSESSID=12345_secret")
    check("cookie header none", CookieFile.headerFor(rel, "danbooru.donmai.us", now), "")
    check("cookie names pixiv", CookieFile.namesFor(rel, "www.pixiv.net", now), listOf("PHPSESSID"))
    check("cookie names x", CookieFile.namesFor(rel, "x.com", now), listOf("auth_token", "ct0"))
    check("cookie round trip", CookieFile.parse(CookieFile.serialize(rel), now).cookies, rel)
    val sum = CookieFile.summarize(rel)
    check("summary x", Triple(sum.xAuthToken, sum.xCsrf, sum.xExpiresSec), Triple(true, true, 1893456000L))
    check("summary pixiv", Pair(sum.pixivSession, sum.pixivExpiresSec), Pair(true, 0L))
    val ms = CookieFile.parse(".x.com${t}TRUE${t}/${t}TRUE${t}1893456000000${t}auth_token${t}v", now)
    check("cookie ms expiry", ms.cookies.first().expiresSec, 1893456000L)
    val spaces = CookieFile.parse(".x.com TRUE / TRUE 0 auth_token abc", now)
    check("cookie space separated", spaces.cookies.map { it.name to it.value }, listOf("auth_token" to "abc"))
    check("domain dot match", CookieFile.domainMatches(".x.com", "x.com"), true)
    check("domain sub match", CookieFile.domainMatches(".x.com", "www.x.com"), true)
    check("domain false friend", CookieFile.domainMatches("x.com", "box.com"), false)
    check("empty input", CookieFile.parse("", now).cookies.isEmpty(), true)


    // --- numeric X profiles, URL lists, other-platform candidates ---
    check("x i/user id", p.parseArtistLink("https://x.com/i/user/206922466"), ArtistRef(Platform.TWITTER, "206922466", numeric = true))
    check("twitter intent user id", p.parseArtistLink("https://twitter.com/intent/user?user_id=55"), ArtistRef(Platform.TWITTER, "55", numeric = true))
    check("ref keys", listOf(ArtistRef(Platform.TWITTER, "AbC").key, ArtistRef(Platform.TWITTER, "9", true).key, ArtistRef(Platform.PIXIV, "9").key), listOf("x:abc", "xid:9", "px:9"))
    check("ref label", ArtistRef(Platform.TWITTER, "AbC").label, "X @AbC")
    check("numeric variants", p.artistUrlVariants(ArtistRef(Platform.TWITTER, "77", true)).first(), "https://twitter.com/i/user/77")
    check("stacc name", p.pixivStaccName("https://www.pixiv.net/stacc/user_nzze5344"), "user_nzze5344")
    check("stacc lang", p.pixivStaccName("https://www.pixiv.net/en/stacc/xeae_exp"), "xeae_exp")
    check("stacc none", p.pixivStaccName("https://www.pixiv.net/users/5"), null)
    check("url line inactive", p.parseUrlLine("-https://x.com/AIO_6666"), ArtistUrl("https://x.com/AIO_6666", false))
    check("url line active", p.parseUrlLine("  https://x.com/abc "), ArtistUrl("https://x.com/abc", true))
    check("url line blank", p.parseUrlLine("  "), null)

    val list = p.parseUrlList(
        """https://www.pixiv.net/users/101962418
https://www.pixiv.net/users/117275702
https://www.pixiv.net/stacc/user_nzze5344
https://www.pixiv.net/stacc/xeae_exp
https://x.com/Longlastingwish
https://x.com/NT_511511
https://x.com/QT_561561
https://x.com/i/user/206922466
https://x.com/i/user/1069808403730628608
https://theflatfacadeds.fanbox.cc
https://www.pixiv.net/fanbox/creator/101962418
-https://x.com/AIO_6666
-https://x.com/i/user/2041638248326299648
https://x.com/longlastingwish
https://example.com/artist"""
    )
    check("url list size", list.size, 15)
    val c = p.sourceCandidates(list, ArtistRef(Platform.PIXIV, "101962418"))
    check(
        "candidates (primary pixiv, inactive/fanbox/other dropped, repeat dropped)",
        c.map { it.label },
        listOf("Pixiv 117275702", "Pixiv user_nzze5344", "Pixiv xeae_exp", "X @Longlastingwish", "X @NT_511511", "X @QT_561561", "X #206922466", "X #1069808403730628608")
    )
    check("candidate stacc has no ref", c[1].ref, null)
    val c2 = p.sourceCandidates(list, ArtistRef(Platform.TWITTER, "LONGLASTINGWISH"))
    check("candidates (primary X, case-insensitive)", c2.any { it.key == "x:longlastingwish" }, false)
    check("candidates keep pixiv primary out only when pasted", c2.count { it.ref?.platform == Platform.PIXIV }, 2)
    check("no urls", p.sourceCandidates(emptyList(), null), emptyList())

    println(if (failures == 0) "\nALL PASSED" else "\n$failures FAILED")
    if (failures != 0) kotlin.system.exitProcess(1)
}
