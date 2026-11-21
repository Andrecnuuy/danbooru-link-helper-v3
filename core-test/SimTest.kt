package com.dlh.helper

import kotlinx.coroutines.runBlocking
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

// Checks for the picture matching (Similarity.kt). Run with core-test/run.sh (no Android needed).

private var failures = 0

private fun <T> check(name: String, actual: T, expected: T) {
    if (actual == expected) {
        println("ok    $name")
    } else {
        failures++
        println("FAIL  $name\n      expected: $expected\n      actual:   $actual")
    }
}

/** Pixels of a w x h "picture" whose brightness is drawn by [f] (x and y run 0..1), tinted by [tint] (r, g, b factors). */
private fun pixels(w: Int, h: Int, noise: Int = 0, seed: Int = 1, tint: DoubleArray = doubleArrayOf(1.0, 1.0, 1.0), f: (Double, Double) -> Double): IntArray {
    val rnd = Random(seed)
    val px = IntArray(w * h)
    for (y in 0 until h) for (x in 0 until w) {
        var v = f((x + 0.5) / w, (y + 0.5) / h)
        if (noise > 0) v += rnd.nextInt(-noise, noise + 1)
        val r = (v * tint[0]).toInt().coerceIn(0, 255)
        val g = (v * tint[1]).toInt().coerceIn(0, 255)
        val b = (v * tint[2]).toInt().coerceIn(0, 255)
        px[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
    return px
}

private fun feat(w: Int, h: Int, noise: Int = 0, seed: Int = 1, tint: DoubleArray = doubleArrayOf(1.0, 1.0, 1.0), f: (Double, Double) -> Double): Feature =
    Similarity.featureFromArgb(pixels(w, h, noise, seed, tint, f), w, h)

/** A flat-colour "illustration": background plus coloured rectangles, which can be drawn at any size. */
private class Drawing(rnd: Random) {
    val bg = rnd.nextInt(0x1000000)
    val shapes = (0 until 7).map {
        val x0 = rnd.nextDouble(0.0, 0.8)
        val y0 = rnd.nextDouble(0.0, 0.8)
        doubleArrayOf(x0, y0, x0 + rnd.nextDouble(0.08, 0.5), y0 + rnd.nextDouble(0.08, 0.5), rnd.nextInt(0x1000000).toDouble())
    }

    fun raster(w: Int, h: Int): IntArray {
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val fx = (x + 0.5) / w
            val fy = (y + 0.5) / h
            var c = bg
            for (s in shapes) if (fx >= s[0] && fx < s[2] && fy >= s[1] && fy < s[3]) c = s[4].toInt()
            px[y * w + x] = (0xFF shl 24) or c
        }
        return px
    }

    fun feature(w: Int, h: Int): Feature = Similarity.featureFromArgb(raster(w, h), w, h)
}

private val blob = { x: Double, y: Double -> 255 * exp(-((x - 0.3) * (x - 0.3) + (y - 0.4) * (y - 0.4)) / 0.05) + 40 * y }
private val stripes = { x: Double, y: Double -> 128 + 100 * sin(12 * (x + y)) }
private val blobMoved = { x: Double, y: Double -> 255 * exp(-((x - 0.7) * (x - 0.7) + (y - 0.65) * (y - 0.65)) / 0.05) + 40 * y }
private val flat = { _: Double, _: Double -> 200.0 }

private fun img(src: Int, item: String, idx: Int, plat: Platform, w: Int, h: Int, f: Feature) =
    SimImage(src, item, idx, plat, w, h, "k$src$item$idx", f)

fun main() {
    // --- shrinking ---
    val fa = Similarity.featureFromArgb(IntArray(20 * 10) { 0xFF336699.toInt() }, 20, 10)
    check("block colours are kept", Triple(fa.color[0].toInt() and 0xFF, fa.color[1].toInt() and 0xFF, fa.color[2].toInt() and 0xFF), Triple(0x33, 0x66, 0x99))
    check("aspect from size", fa.aspect, 2.0f)
    check("gray of a solid colour", fa.gray[100].toInt() and 0xFF, (299 * 0x33 + 587 * 0x66 + 114 * 0x99) / 1000)
    val solid = Similarity.boxAverage(IntArray(30 * 20) { 90 }, 30, 20)
    check("solid shrinks to solid", solid.all { (it.toInt() and 0xFF) == 90 }, true)
    check("shrink size", solid.size, 1024)
    val tiny = Similarity.boxAverage(IntArray(4 * 4) { it * 10 }, 4, 4)
    check("tiny picture still fills 32x32", tiny.size, 1024)
    check("empty picture", Similarity.boxAverage(IntArray(0), 0, 0).all { it.toInt() == 0 }, true)
    val half = Similarity.boxAverage(IntArray(32 * 32) { if (it % 32 < 16) 0 else 200 }, 32, 32)
    check("left half dark, right half bright", Pair(half[5].toInt() and 0xFF, half[20].toInt() and 0xFF), Pair(0, 200))

    // --- same picture at two sizes, noisy copy, different pictures ---
    val big = img(0, "a", 0, Platform.TWITTER, 2000, 1500, feat(680, 510, f = blob))
    val small = img(1, "b", 0, Platform.PIXIV, 1000, 750, feat(540, 405, f = blob))
    val noisy = img(1, "c", 0, Platform.PIXIV, 1000, 750, feat(540, 405, noise = 12, seed = 7, f = blob))
    val other = img(1, "d", 0, Platform.PIXIV, 1000, 750, feat(540, 405, f = stripes))
    val moved = img(1, "e", 0, Platform.PIXIV, 1000, 750, feat(540, 405, f = blobMoved))
    val blank = img(1, "f", 0, Platform.PIXIV, 1000, 750, feat(540, 405, f = flat))
    val portrait = img(1, "g", 0, Platform.PIXIV, 750, 1000, feat(405, 540, f = blob))

    val r1 = Similarity.find(listOf(big, small, other, blank, portrait))
    check("same picture, two sizes: one pair", r1.pairs.size, 1)
    check("pair score high", (r1.pairs.firstOrNull()?.score ?: 0) >= 95, true)
    check("blank pictures are not compared", r1.compared, 4)
    check("higher resolution on the left", r1.pairs[0].left.id to r1.pairs[0].right.id, big.id to small.id)

    val r2 = Similarity.find(listOf(small, big))
    check("order of input does not matter", r2.pairs[0].left.id, big.id)

    val r3 = Similarity.find(listOf(big, noisy))
    check("noisy copy still matches", r3.pairs.size, 1)
    check("different pictures do not match", Similarity.find(listOf(big, other)).pairs.size, 0)
    val rMoved = Similarity.find(listOf(big, moved), threshold = 99)
    check("moved subject does not match at 99", rMoved.pairs.size, 0)
    check("different shape (portrait) is not compared", Similarity.find(listOf(big, portrait)).pairs.size, 0)
    check("threshold 100 needs identical", Similarity.find(listOf(big, noisy), threshold = 100).pairs.size, 0)

    val red = img(1, "r", 0, Platform.PIXIV, 1000, 750, feat(540, 405, tint = doubleArrayOf(1.0, 0.45, 0.35), f = blob))
    val blue = img(1, "u", 0, Platform.PIXIV, 1000, 750, feat(540, 405, tint = doubleArrayOf(0.35, 0.45, 1.0), f = blob))
    check("same layout, different colours: no match", Similarity.find(listOf(red, blue)).pairs.size, 0)
    check("same colours: match", Similarity.find(listOf(red, img(0, "r2", 0, Platform.TWITTER, 500, 375, feat(300, 225, tint = doubleArrayOf(1.0, 0.45, 0.35), f = blob)))).pairs.size, 1)
    val slightTint = img(0, "s", 0, Platform.TWITTER, 500, 375, feat(300, 225, tint = doubleArrayOf(1.0, 0.43, 0.36), f = blob))
    check("slightly different colour handling still matches", Similarity.find(listOf(red, slightTint)).pairs.size, 1)


    // --- a signature / watermark in a corner does not hide a copy ---
    run {
        val w = 540
        val h = 405
        val px = pixels(w, h, f = blob)
        for (y in (h * 0.93).toInt() until h) for (x in (w * 0.82).toInt() until w) px[y * w + x] = 0xFFFFFFFF.toInt()
        val stamped = img(1, "w", 0, Platform.PIXIV, 1000, 750, Similarity.featureFromArgb(px, w, h))
        check("copy with a signature in the corner still matches", Similarity.find(listOf(big, stamped)).pairs.size, 1)
        check("a big white patch lowers the score (found at a looser threshold)", Similarity.find(listOf(big, stamped), threshold = 40).pairs.size, 1)
        val banner = pixels(w, h, f = blob)
        for (y in 0 until h / 2) for (x in 0 until w) banner[y * w + x] = if ((x / 20 + y / 20) % 2 == 0) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        val covered = img(1, "v", 0, Platform.PIXIV, 1000, 750, Similarity.featureFromArgb(banner, w, h))
        check("half the picture replaced: no match", Similarity.find(listOf(big, covered)).pairs.size, 0)
    }

    // --- same post is never paired with itself ---
    val sameItemA = img(0, "p", 0, Platform.TWITTER, 100, 75, feat(100, 75, f = blob))
    val sameItemB = img(0, "p", 1, Platform.TWITTER, 100, 75, feat(100, 75, f = blob))
    check("same post, two photos", Similarity.find(listOf(sameItemA, sameItemB)).pairs.size, 0)

    // --- left / right rules ---
    val tw = img(0, "t", 0, Platform.TWITTER, 1000, 750, feat(200, 150, f = blob))
    val px = img(1, "x", 0, Platform.PIXIV, 1000, 750, feat(200, 150, f = blob))
    check("equal size: pixiv left, twitter right", Similarity.orient(tw, px, 100).let { it.left.platform to it.right.platform }, Platform.PIXIV to Platform.TWITTER)
    check("equal size, same site: earlier source left",
        Similarity.orient(img(2, "a", 0, Platform.PIXIV, 10, 10, tw.feature), img(1, "b", 0, Platform.PIXIV, 10, 10, tw.feature), 90).left.sourceId, 1)
    val smallPx = img(1, "y", 0, Platform.PIXIV, 500, 375, feat(200, 150, f = blob))
    check("size beats site: bigger twitter left of smaller pixiv", Similarity.orient(tw, smallPx, 100).left.platform, Platform.TWITTER)
    val unknown = img(1, "z", 0, Platform.PIXIV, 0, 0, feat(200, 150, f = blob))
    check("unknown size goes right", Similarity.orient(unknown, tw, 100).left.id, tw.id)

    // --- groups that would lose every copy ---
    val a = img(0, "A", 0, Platform.PIXIV, 3000, 2000, feat(300, 200, f = blob))
    val b = img(0, "B", 0, Platform.TWITTER, 2000, 1333, feat(300, 200, f = blob))
    val c = img(1, "C", 0, Platform.TWITTER, 1000, 667, feat(300, 200, f = blob))
    val trio = Similarity.find(listOf(a, b, c))
    check("three copies: three pairs", trio.pairs.size, 3)
    check("lefts are A, A, B", trio.pairs.map { it.left.itemId }.sorted(), listOf(a.itemId, a.itemId, b.itemId))
    val lefts = trio.pairs.map { it.left.itemId }.toSet()
    val rights = trio.pairs.map { it.right.itemId }.toSet()
    check("deleting all lefts keeps one copy", Similarity.groupsLosingEverything(trio.pairs, lefts), 0)
    check("deleting all rights keeps the best", Similarity.groupsLosingEverything(trio.pairs, rights), 0)
    check("deleting everything loses the group", Similarity.groupsLosingEverything(trio.pairs, lefts + rights), 1)
    val s1 = img(5, "S1", 0, Platform.TWITTER, 100, 75, feat(100, 75, f = stripes))
    val s2 = img(5, "S2", 0, Platform.PIXIV, 200, 150, feat(100, 75, f = stripes))
    val twoGroups = Similarity.find(listOf(a, b, c, s1, s2)).pairs
    check("two separate groups, everything deleted", Similarity.groupsLosingEverything(twoGroups, setOf(a.itemId, b.itemId, c.itemId, s1.itemId, s2.itemId)), 2)
    check("two separate groups, one fully deleted", Similarity.groupsLosingEverything(twoGroups, setOf(s1.itemId, s2.itemId)), 1)

    // --- cache ---
    val cache = FeatureCache()
    cache.put("tm:AAA", big.feature)
    cache.put("px:1", small.feature)
    val bytes = cache.toBytes()
    val copy = FeatureCache().also { it.loadBytes(bytes) }
    check("cache round trip gray", copy.get("tm:AAA")?.gray?.toList(), big.feature.gray.toList())
    check("cache round trip color", copy.get("tm:AAA")?.color?.toList(), big.feature.color.toList())
    check("cache round trip aspect", copy.get("tm:AAA")?.aspect, big.feature.aspect)
    check("cache miss", copy.get("tm:ZZZ"), null)
    check("cache retainOnly", FeatureCache().also { it.loadBytes(bytes); it.retainOnly(setOf("px:1", "nope")) }.let { it.size() to it.get("px:1")?.aspect }, 1 to small.feature.aspect)
    check("cache wrong file", FeatureCache().also { it.loadBytes(ByteArray(40) { 7 }) }.size(), 0)
    check("cache empty file", FeatureCache().also { it.loadBytes(ByteArray(0)) }.size(), 0)
    check("cache cut off keeps the first entries", FeatureCache().also { it.loadBytes(bytes.copyOf(bytes.size - 100)) }.size(), 1)

    // --- addresses to download ---
    check("twitter url", SimilarityUrls.twitter("AAA111"), "https://pbs.twimg.com/media/AAA111?format=jpg&name=small")
    check("pixiv from custom thumb", SimilarityUrls.pixiv("https://i.pximg.net/c/250x250_80_a2/custom-thumb/img/2020/01/02/03/04/05/300_p0_custom1200.jpg"),
        "https://i.pximg.net/c/540x540_70/img-master/img/2020/01/02/03/04/05/300_p0_master1200.jpg")
    check("pixiv from square thumb", SimilarityUrls.pixiv("https://i.pximg.net/c/250x250_80_a2/img-master/img/2019/12/31/23/59/59/12345_p0_square1200.jpg"),
        "https://i.pximg.net/c/540x540_70/img-master/img/2019/12/31/23/59/59/12345_p0_master1200.jpg")
    check("pixiv odd thumb", SimilarityUrls.pixiv("https://example.com/x.jpg"), null)
    check("pixiv no thumb", SimilarityUrls.pixiv(null), null)

    // --- speed and false matches: 4000 unrelated illustrations, five of them also present at another size ---
    val rnd = Random(3)
    val drawings = (0 until 4000).map { Drawing(rnd) }
    val many = ArrayList<SimImage>()
    for ((i, d) in drawings.withIndex()) {
        val w = if (i % 3 == 0) 80 else 64
        many.add(img(i % 3, "m$i", 0, if (i % 2 == 0) Platform.TWITTER else Platform.PIXIV, 1000, if (i % 3 == 0) 600 else 750, d.feature(w, 48)))
    }
    for (i in 0 until 5) {
        val idx = i * 7
        val w = if (idx % 3 == 0) 80 else 64
        many.add(img(9, "copy$i", 0, Platform.PIXIV, 3000, if (idx % 3 == 0) 1800 else 2250, drawings[idx].feature(w * 3, 144)))
    }
    val t0 = System.nanoTime()
    val rBig = Similarity.find(many)
    val ms = (System.nanoTime() - t0) / 1_000_000
    println("      ${many.size} pictures compared in $ms ms, ${rBig.pairs.size} pairs")
    check("not truncated", rBig.truncated, false)
    check("every planted copy found", (0 until 5).all { i -> rBig.pairs.any { p -> p.left.itemId == "9|copy$i" } }, true)
    check("few false pairs among 4000 unrelated pictures", rBig.pairs.size < 5 + 40, true)
    check("fast enough (under 5 s)", ms < 5000, true)


    // --- the hard case: smooth, soft pictures (gradients and blobs) in random tints ---
    run {
        val r2 = Random(11)
        val soft = ArrayList<SimImage>()
        for (i in 0 until 1500) {
            val gx = r2.nextDouble(-60.0, 60.0)
            val gy = r2.nextDouble(-60.0, 60.0)
            val base = r2.nextDouble(60.0, 190.0)
            val blobs = (0 until 7).map { doubleArrayOf(r2.nextDouble(), r2.nextDouble(), r2.nextDouble(0.01, 0.08), r2.nextDouble(-140.0, 140.0)) }
            val tint = doubleArrayOf(r2.nextDouble(0.5, 1.2), r2.nextDouble(0.5, 1.2), r2.nextDouble(0.5, 1.2))
            soft.add(img(i % 3, "s$i", 0, Platform.PIXIV, 1000, 750, feat(96, 72, tint = tint) { x, y ->
                var v = base + gx * x + gy * y
                for (b in blobs) v += b[3] * exp(-((x - b[0]) * (x - b[0]) + (y - b[1]) * (y - b[1])) / b[2])
                v
            }))
        }
        val rs = Similarity.find(soft)
        println("      soft pictures: ${rs.pairs.size} false pairs among ${soft.size} (${rs.truncated})")
        check("soft pictures: false pairs are rare", rs.pairs.size < 100, true)
    }


    // --- gathering pictures from sources, with a cache ---
    run {
        val xs = SourceData(
            id = 0, ref = ArtistRef(Platform.TWITTER, "a"), items = listOf(
                LinkItem("tw:1", "u1", mediaKeys = listOf("AAA", "BBB"), sizes = listOf(100, 200, 300, 400)),
                LinkItem("tw:2", "u2", kind = KIND_VIDEO),
                LinkItem("tw:3", "u3", mediaKeys = listOf("AAA"))
            )
        )
        val ps = SourceData(
            id = 1, ref = ArtistRef(Platform.PIXIV, "9"), items = listOf(
                LinkItem("px:5", "p5", thumb = "https://i.pximg.net/c/250x250_80_a2/custom-thumb/img/2020/01/02/03/04/05/5_p0_custom1200.jpg", sizes = listOf(1200, 1700)),
                LinkItem("px:6", "p6")
            )
        )
        val tl = SimilarityScan.targets(listOf(xs, ps))
        check("targets: photos of X posts and first page of Pixiv works", tl.targets.map { it.cacheKey }, listOf("tm:AAA", "tm:BBB", "tm:AAA", "px:5"))
        check("targets: work without thumbnail counted", tl.withoutPicture, 1)
        check("targets: sizes line up", tl.targets.map { it.width to it.height }, listOf(100 to 200, 300 to 400, 0 to 0, 1200 to 1700))
        check("targets: pixiv address", tl.targets[3].url, "https://i.pximg.net/c/540x540_70/img-master/img/2020/01/02/03/04/05/5_p0_master1200.jpg")
        val cache2 = FeatureCache()
        check("pending counts distinct pictures", SimilarityScan.pending(tl.targets, cache2), 3)
        var calls = 0
        val progress = ArrayList<Triple<Int, Int, Int>>()
        val f = big.feature
        val run1 = runBlocking {
            SimilarityScan.collect(tl.targets, cache2, 3, { t -> calls++; if (t.cacheKey == "tm:BBB") null else f }) { d, n, x -> synchronized(progress) { progress.add(Triple(d, n, x)) } }
        }
        check("collect: each distinct picture fetched once", calls, 3)
        check("collect: one failed", run1.failed to run1.fetched, 1 to 2)
        check("collect: images with a feature", run1.images.map { it.id }, listOf("0|tw:1|0", "0|tw:3|0", "1|px:5|0"))
        check("collect: images carry their address", run1.images.map { it.url }.first(), "https://pbs.twimg.com/media/AAA?format=jpg&name=small")
        check("collect: progress reaches the end", progress.maxOf { it.first } to progress.first().second, 3 to 3)
        calls = 0
        val run2 = runBlocking { SimilarityScan.collect(tl.targets, cache2, 3, { calls++; f }) { _, _, _ -> } }
        check("collect: only the failed one is fetched again", calls, 1)
        check("collect: now everything is there", run2.images.size, 4)
        check("pending after", SimilarityScan.pending(tl.targets, cache2), 0)
    }

    // --- stop request ---
    var calls = 0
    val stopped = Similarity.find(many, shouldStop = { calls++ > 2 })
    check("stop request ends early", stopped.pairs.size < rBig.pairs.size || rBig.pairs.isEmpty(), true)

    println(if (failures == 0) "\nALL PASSED" else "\n$failures FAILED")
    if (failures != 0) kotlin.system.exitProcess(1)
}
