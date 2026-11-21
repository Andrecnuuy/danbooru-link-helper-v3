package com.dlh.helper

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

// Pure Kotlin (no Android imports) so the matching can be tested on a plain JVM.
// The Android side only downloads and decodes the pictures into a [Feature] (see SimilarityScan).

/**
 * What a picture boils down to for comparing: [gray] is 32x32 gray levels and [color] the average red, green, blue of each
 * of 4x4 blocks (all 0..255 as unsigned bytes), [aspect] is width / height.
 */
class Feature(val gray: ByteArray, val color: ByteArray, val aspect: Float)

/** One picture of one post of one source, ready to be compared. [width]/[height] are the real pixel size (0 = unknown). */
class SimImage(
    val sourceId: Int,
    val itemKey: String,
    val index: Int,
    val platform: Platform,
    val width: Int,
    val height: Int,
    val cacheKey: String,
    val feature: Feature,
    /** Where the small copy used for comparing can be downloaded (also used to show the picture). */
    val url: String = ""
) {
    val id: String = "$sourceId|$itemKey|$index"

    /** The post this picture belongs to. Deleting a picture deletes this whole post. */
    val itemId: String = "$sourceId|$itemKey"
    val pixels: Long get() = width.toLong() * height.toLong()
}

/** Two similar pictures, [left] being the one to keep by the "higher resolution on the left" rule. [score] is 0..100. */
class SimPair(val left: SimImage, val right: SimImage, val score: Int) {
    val key: String get() = left.id + "~" + right.id
}

class SimResult(val pairs: List<SimPair>, val compared: Int, val truncated: Boolean)

object Similarity {
    const val GRID = 32
    const val CELLS = GRID * GRID
    const val COLOR_GRID = 4
    const val COLOR_VALUES = COLOR_GRID * COLOR_GRID * 3
    const val DEFAULT_THRESHOLD = 80
    const val MAX_PAIRS = 5000

    /** Largest difference of ln(width/height) for two pictures to be compared at all (about 8 %). */
    private const val ASPECT_TOL = 0.08

    /** Of 64 coarse brightness bits, at most this many may differ before the exact comparison is even tried. */
    private const val HASH_MAX_DIFF = 20

    /** A picture whose gray levels hardly vary (blank, solid colour) has no structure to compare. */
    private const val MIN_SPREAD = 1.5

    /** The colours of the 4x4 blocks must agree on average within this many levels (0..255). */
    private const val COLOR_MEAN_DIFF = 20

    /** Each quarter of the picture may correlate this much lower than the whole picture has to. */
    private const val QUADRANT_MARGIN = 0.20f

    /** A quarter whose gray levels hardly vary in either picture (a plain corner) is not checked: a single signature would ruin it. */
    private const val QUADRANT_MIN_SPREAD = 6.0

    /** One quarter may disagree completely (a signature, a logo, a caption in one corner) as long as the others agree. */
    private const val QUADRANT_FAILS_ALLOWED = 1

    /** Shrinks [v] (w*h values 0..255, row by row) to grid x grid by averaging every box of pixels. */
    fun boxAverage(v: IntArray, w: Int, h: Int, grid: Int = GRID): ByteArray {
        val out = ByteArray(grid * grid)
        if (w <= 0 || h <= 0 || v.size < w * h) return out
        for (cy in 0 until grid) {
            val y0 = cy * h / grid
            val y1 = minOf(h, maxOf(y0 + 1, (cy + 1) * h / grid))
            for (cx in 0 until grid) {
                val x0 = cx * w / grid
                val x1 = minOf(w, maxOf(x0 + 1, (cx + 1) * w / grid))
                var sum = 0L
                var n = 0
                for (y in y0 until y1) {
                    val row = y * w
                    for (x in x0 until x1) {
                        sum += v[row + x]
                        n++
                    }
                }
                out[cy * grid + cx] = (sum / n).toInt().coerceIn(0, 255).toByte()
            }
        }
        return out
    }

    /**
     * Builds the [Feature] of a decoded picture from its [argb] pixels (Android's Bitmap.getPixels order, [w] x [h]).
     * Transparent pixels are treated as their colour; pictures from X and Pixiv come as opaque JPEG anyway.
     */
    fun featureFromArgb(argb: IntArray, w: Int, h: Int): Feature {
        val n = w * h
        val luma = IntArray(n)
        val r = IntArray(n)
        val g = IntArray(n)
        val b = IntArray(n)
        for (i in 0 until n) {
            val p = argb[i]
            val pr = (p shr 16) and 0xFF
            val pg = (p shr 8) and 0xFF
            val pb = p and 0xFF
            r[i] = pr
            g[i] = pg
            b[i] = pb
            luma[i] = (299 * pr + 587 * pg + 114 * pb) / 1000
        }
        val rc = boxAverage(r, w, h, COLOR_GRID)
        val gc = boxAverage(g, w, h, COLOR_GRID)
        val bc = boxAverage(b, w, h, COLOR_GRID)
        val color = ByteArray(COLOR_VALUES)
        for (i in 0 until COLOR_GRID * COLOR_GRID) {
            color[i * 3] = rc[i]
            color[i * 3 + 1] = gc[i]
            color[i * 3 + 2] = bc[i]
        }
        return Feature(boxAverage(luma, w, h), color, if (h > 0) w.toFloat() / h else 0f)
    }

    /** Mean-centred, unit-length copy of the gray levels, so the dot product of two of them is their correlation. Null for flat pictures. */
    private class Prep(val img: SimImage, val z: FloatArray, val hash: Long, val logAspect: Double)

    private fun prep(img: SimImage): Prep? {
        val g = img.feature.gray
        if (g.size != CELLS || img.feature.color.size != COLOR_VALUES || img.feature.aspect <= 0f) return null
        var sum = 0.0
        for (b in g) sum += (b.toInt() and 0xFF)
        val mean = sum / CELLS
        var sq = 0.0
        for (b in g) {
            val d = (b.toInt() and 0xFF) - mean
            sq += d * d
        }
        if (sqrt(sq / CELLS) < MIN_SPREAD) return null
        val norm = sqrt(sq)
        val z = FloatArray(CELLS) { (((g[it].toInt() and 0xFF) - mean) / norm).toFloat() }

        // 8x8 coarse brightness hash: every cell is the average of 4x4 cells, the bit says "brighter than the whole picture".
        val step = GRID / 8
        val coarse = DoubleArray(64)
        for (cy in 0 until 8) for (cx in 0 until 8) {
            var s = 0
            for (dy in 0 until step) for (dx in 0 until step) s += g[(cy * step + dy) * GRID + cx * step + dx].toInt() and 0xFF
            coarse[cy * 8 + cx] = s.toDouble() / (step * step)
        }
        var hash = 0L
        for (i in 0 until 64) if (coarse[i] > mean) hash = hash or (1L shl i)
        return Prep(img, z, hash, ln(img.feature.aspect.toDouble()))
    }

    /** Puts the picture with more pixels on the left; on equal size Pixiv goes left of Twitter, then the earlier source. */
    fun orient(a: SimImage, b: SimImage, score: Int): SimPair {
        val aLeft = when {
            a.pixels != b.pixels -> a.pixels > b.pixels
            a.platform != b.platform -> a.platform == Platform.PIXIV
            a.sourceId != b.sourceId -> a.sourceId < b.sourceId
            else -> a.id <= b.id
        }
        return if (aLeft) SimPair(a, b, score) else SimPair(b, a, score)
    }

    /**
     * Local check: the quarters of the picture must correlate on their own too (a bit more loosely than the whole), so two
     * pictures that merely share a similar overall light/dark layout are not mistaken for copies. One quarter may fail,
     * because a signature or logo in a corner can ruin it. A quarter without any structure counts as agreeing.
     */
    private fun quadrantsAgree(a: ByteArray, b: ByteArray, minR: Float): Boolean {
        val half = GRID / 2
        var failed = 0
        for (q in 0 until 4) {
            val ox = (q % 2) * half
            val oy = (q / 2) * half
            var sa = 0.0
            var sb = 0.0
            for (y in 0 until half) for (x in 0 until half) {
                val i = (oy + y) * GRID + ox + x
                sa += a[i].toInt() and 0xFF
                sb += b[i].toInt() and 0xFF
            }
            val n = half * half
            val ma = sa / n
            val mb = sb / n
            var saa = 0.0
            var sbb = 0.0
            var sab = 0.0
            for (y in 0 until half) for (x in 0 until half) {
                val i = (oy + y) * GRID + ox + x
                val da = (a[i].toInt() and 0xFF) - ma
                val db = (b[i].toInt() and 0xFF) - mb
                saa += da * da
                sbb += db * db
                sab += da * db
            }
            if (saa / n < QUADRANT_MIN_SPREAD * QUADRANT_MIN_SPREAD || sbb / n < QUADRANT_MIN_SPREAD * QUADRANT_MIN_SPREAD) continue
            if (sab / sqrt(saa * sbb) < minR && ++failed > QUADRANT_FAILS_ALLOWED) return false
        }
        return true
    }

    private fun colorsAgree(a: ByteArray, b: ByteArray): Boolean {
        var sum = 0
        for (i in 0 until COLOR_VALUES) {
            sum += abs((a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF))
        }
        return sum <= COLOR_MEAN_DIFF * COLOR_VALUES
    }

    /**
     * Finds every pair of pictures that look alike: matching shape (aspect ratio), then a coarse brightness check, then the
     * colours of 4x4 blocks must agree, and finally the correlation of the 32x32 gray levels must reach [threshold] percent. Two pictures of the same post are never paired.
     * Highest score first. [shouldStop] is polled so a long run can be cancelled; [onProgress] gets (done, total).
     */
    fun find(
        images: List<SimImage>,
        threshold: Int = DEFAULT_THRESHOLD,
        shouldStop: () -> Boolean = { false },
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): SimResult {
        val preps = images.mapNotNull { prep(it) }.sortedBy { it.logAspect }
        val minR = threshold.coerceIn(1, 100) / 100f
        val found = ArrayList<SimPair>()
        var truncated = false
        outer@ for (i in preps.indices) {
            if ((i and 63) == 0) {
                if (shouldStop()) break
                onProgress(i, preps.size)
            }
            val a = preps[i]
            var j = i + 1
            while (j < preps.size && preps[j].logAspect - a.logAspect <= ASPECT_TOL) {
                val b = preps[j]
                j++
                if (a.img.itemId == b.img.itemId) continue
                if (java.lang.Long.bitCount(a.hash xor b.hash) > HASH_MAX_DIFF) continue
                if (!colorsAgree(a.img.feature.color, b.img.feature.color)) continue
                var dot = 0f
                val za = a.z
                val zb = b.z
                for (k in 0 until CELLS) dot += za[k] * zb[k]
                if (dot >= minR && quadrantsAgree(a.img.feature.gray, b.img.feature.gray, minR - QUADRANT_MARGIN)) {
                    found.add(orient(a.img, b.img, (dot * 100f).roundToInt().coerceIn(0, 100)))
                    if (found.size >= MAX_PAIRS) {
                        truncated = true
                        break@outer
                    }
                }
            }
        }
        onProgress(preps.size, preps.size)
        found.sortWith(compareByDescending<SimPair> { it.score }.thenBy { it.key })
        return SimResult(found, preps.size, truncated)
    }

    /**
     * How many groups of look-alike posts would be left with no post at all if the posts in [deleteItems] (SimImage.itemId)
     * were deleted. A group is every set of posts connected through [pairs].
     */
    fun groupsLosingEverything(pairs: List<SimPair>, deleteItems: Set<String>): Int {
        val parent = HashMap<String, String>()
        fun rootOf(x: String): String {
            var r = x
            while (true) {
                val p = parent[r] ?: break
                if (p == r) break
                r = p
            }
            var c = x
            while (c != r) {
                val n = parent[c] ?: r
                parent[c] = r
                c = n
            }
            return r
        }
        for (p in pairs) {
            val a = p.left.itemId
            val b = p.right.itemId
            parent.putIfAbsent(a, a)
            parent.putIfAbsent(b, b)
            val ra = rootOf(a)
            val rb = rootOf(b)
            if (ra != rb) parent[ra] = rb
        }
        val allGone = HashMap<String, Boolean>()
        for (item in parent.keys) {
            val root = rootOf(item)
            allGone[root] = (allGone[root] ?: true) && item in deleteItems
        }
        return allGone.values.count { it }
    }
}


/** One picture to look at: whose it is, how big it really is, and where to download a small copy. */
class SimTarget(
    val sourceId: Int,
    val itemKey: String,
    val index: Int,
    val platform: Platform,
    val width: Int,
    val height: Int,
    val cacheKey: String,
    val url: String
)

class TargetList(val targets: List<SimTarget>, val withoutPicture: Int)

class FeatureRun(val images: List<SimImage>, val fetched: Int, val failed: Int)

/** Gathers the pictures of all sources and turns them into [SimImage]s, downloading only what the cache does not hold. */
object SimilarityScan {

    /** Average size of the small copies, for the data estimate shown before a run. */
    const val BYTES_PER_PICTURE = 60 * 1024

    /**
     * Every picture of every source: all photos of an X post, the first page of a Pixiv work. Videos are left out.
     * [TargetList.withoutPicture] counts works that could not be turned into a download address (Pixiv works without a
     * thumbnail yet).
     */
    fun targets(sources: List<SourceData>): TargetList {
        val out = ArrayList<SimTarget>()
        var missing = 0
        for (src in sources) {
            for (item in src.items) {
                when (src.ref.platform) {
                    Platform.TWITTER -> {
                        for ((i, key) in item.mediaKeys.withIndex()) {
                            out.add(
                                SimTarget(
                                    src.id, item.key, i, Platform.TWITTER,
                                    item.sizes.getOrElse(i * 2) { 0 }, item.sizes.getOrElse(i * 2 + 1) { 0 },
                                    "tm:$key", SimilarityUrls.twitter(key)
                                )
                            )
                        }
                    }
                    Platform.PIXIV -> {
                        val url = SimilarityUrls.pixiv(item.thumb)
                        if (url == null) {
                            missing++
                        } else {
                            out.add(
                                SimTarget(
                                    src.id, item.key, 0, Platform.PIXIV,
                                    item.sizes.getOrElse(0) { 0 }, item.sizes.getOrElse(1) { 0 },
                                    item.key, url
                                )
                            )
                        }
                    }
                }
            }
        }
        return TargetList(out, missing)
    }

    /** How many different pictures still have to be downloaded. */
    fun pending(targets: List<SimTarget>, cache: FeatureCache): Int =
        targets.asSequence().filter { cache.get(it.cacheKey) == null }.map { it.cacheKey }.distinct().count()

    /**
     * Downloads what is missing ([fetch] does one picture, null when it failed), [parallel] at a time, keeps every result
     * in [cache], and returns all pictures that have a [Feature]. [onProgress] gets (done, total, failed).
     */
    suspend fun collect(
        targets: List<SimTarget>,
        cache: FeatureCache,
        parallel: Int,
        fetch: suspend (SimTarget) -> Feature?,
        onProgress: (Int, Int, Int) -> Unit
    ): FeatureRun {
        val need = targets.filter { cache.get(it.cacheKey) == null }.distinctBy { it.cacheKey }
        val done = AtomicInteger(0)
        val failed = AtomicInteger(0)
        if (need.isNotEmpty()) {
            val gate = Semaphore(parallel.coerceAtLeast(1))
            coroutineScope {
                need.map { t ->
                    async {
                        gate.withPermit {
                            val f = fetch(t)
                            if (f != null) cache.put(t.cacheKey, f) else failed.incrementAndGet()
                            onProgress(done.incrementAndGet(), need.size, failed.get())
                        }
                    }
                }.awaitAll()
            }
        }
        val images = ArrayList<SimImage>(targets.size)
        for (t in targets) {
            val f = cache.get(t.cacheKey) ?: continue
            images.add(SimImage(t.sourceId, t.itemKey, t.index, t.platform, t.width, t.height, t.cacheKey, f, t.url))
        }
        return FeatureRun(images, need.size - failed.get(), failed.get())
    }
}

/** Where to download a small, uncropped version of a picture for comparing. */
object SimilarityUrls {
    private val pixivThumb = Regex("""/img/(\d{4}/\d{2}/\d{2}/\d{2}/\d{2}/\d{2})/(\d+)_(?:p|ugoira)0""")

    /** About 40 KB, whole picture (not the square crop), longest side up to 680 px. */
    fun twitter(mediaKey: String): String = "https://pbs.twimg.com/media/$mediaKey?format=jpg&name=small"

    /**
     * The 540 px whole-picture version of a Pixiv work, built from the path of the 250 px thumbnail the profile list gives
     * (those thumbnails are square crops, so they cannot be compared with the whole pictures from X). Null when the thumbnail
     * address has an unexpected shape. Needs the Pixiv Referer header to download.
     */
    fun pixiv(thumbUrl: String?): String? {
        val m = pixivThumb.find(thumbUrl ?: return null) ?: return null
        return "https://i.pximg.net/c/540x540_70/img-master/img/${m.groupValues[1]}/${m.groupValues[2]}_p0_master1200.jpg"
    }
}

/**
 * Remembers the [Feature] of pictures already downloaded, so a second run only fetches new ones.
 * Keys are stable ids ("tm:<twitter media key>", "px:<artwork id>"), not URLs.
 */
class FeatureCache {
    private val map = HashMap<String, Feature>()

    @Synchronized
    fun get(key: String): Feature? = map[key]

    @Synchronized
    fun put(key: String, f: Feature) {
        map[key] = f
    }

    @Synchronized
    fun size(): Int = map.size

    @Synchronized
    fun clear() = map.clear()

    /** Forgets everything except the pictures in [keys], so the file does not keep growing with every artist looked at. */
    @Synchronized
    fun retainOnly(keys: Set<String>) {
        map.keys.retainAll(keys)
    }

    @Synchronized
    fun toBytes(): ByteArray {
        val bos = ByteArrayOutputStream(map.size * 1100 + 16)
        DataOutputStream(bos).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(map.size)
            for ((k, f) in map) {
                out.writeUTF(k)
                out.writeFloat(f.aspect)
                out.write(f.gray)
                out.write(f.color)
            }
        }
        return bos.toByteArray()
    }

    /** Adds what [data] holds (as written by [toBytes]). Anything unreadable, or from another layout, is ignored. */
    @Synchronized
    fun loadBytes(data: ByteArray) {
        try {
            DataInputStream(ByteArrayInputStream(data)).use { inp ->
                if (inp.readInt() != MAGIC) return
                val n = inp.readInt()
                for (i in 0 until n) {
                    val k = inp.readUTF()
                    val aspect = inp.readFloat()
                    val gray = ByteArray(Similarity.CELLS)
                    val color = ByteArray(Similarity.COLOR_VALUES)
                    inp.readFully(gray)
                    inp.readFully(color)
                    if (aspect > 0f) map[k] = Feature(gray, color, aspect)
                }
            }
        } catch (e: EOFException) {
            // A cut-off file keeps what was read before the cut.
        } catch (e: java.io.IOException) {
            // A damaged cache only costs a re-download.
        }
    }

    fun save(file: File) {
        val tmp = File(file.path + ".tmp")
        tmp.writeBytes(toBytes())
        if (!tmp.renameTo(file)) {
            file.writeBytes(tmp.readBytes())
            tmp.delete()
        }
    }

    fun load(file: File) {
        if (file.exists()) loadBytes(file.readBytes())
    }

    private companion object {
        /** Changes whenever the layout of a [Feature] changes, so an old cache is not misread. */
        const val MAGIC = 0x44464331
    }
}
