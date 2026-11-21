package com.dlh.helper

import org.json.JSONObject

// Checks for the X response parser and the other pure helpers added for the media features.
// Run with core-test/run.sh (no Android needed).

private var failures = 0

private fun <T> check(name: String, actual: T, expected: T) {
    if (actual == expected) {
        println("ok    $name")
    } else {
        failures++
        println("FAIL  $name\n      expected: $expected\n      actual:   $actual")
    }
}

private fun tweet(id: String, owner: String, mediaJson: String, extra: String = ""): String =
    """{"entryId":"profile-grid-0-tweet-$id","item":{"itemContent":{"itemType":"TimelineTweet","tweet_results":{"result":{
        "__typename":"Tweet","rest_id":"$id","legacy":{"user_id_str":"$owner",$extra"extended_entities":{"media":[$mediaJson]}}}}}}}"""

private const val PHOTO_A = """{"type":"photo","media_url_https":"https://pbs.twimg.com/media/AAA111.jpg"}"""
private const val PHOTO_B = """{"type":"photo","media_url_https":"https://pbs.twimg.com/media/BBB222.png"}"""
private const val VIDEO = """{"type":"video","media_url_https":"https://pbs.twimg.com/ext_tw_video_thumb/9/pu/img/VID.jpg",
    "video_info":{"variants":[
      {"content_type":"application/x-mpegURL","url":"https://video.twimg.com/a.m3u8"},
      {"bitrate":256000,"content_type":"video/mp4","url":"https://video.twimg.com/low.mp4"},
      {"bitrate":2176000,"content_type":"video/mp4","url":"https://video.twimg.com/high.mp4"},
      {"bitrate":832000,"content_type":"video/mp4","url":"https://video.twimg.com/mid.mp4"}]}}"""
private const val GIF = """{"type":"animated_gif","media_url_https":"https://pbs.twimg.com/tweet_video_thumb/GIF.jpg",
    "video_info":{"variants":[{"bitrate":0,"content_type":"video/mp4","url":"https://video.twimg.com/gif.mp4"}]}}"""

fun main() {
    // --- one media timeline page ---
    val page = """{"data":{"user":{"result":{"timeline":{"timeline":{"instructions":[
      {"type":"TimelineClearCache"},
      {"type":"TimelineAddEntries","entries":[
        {"entryId":"profile-grid-0","content":{"entryType":"TimelineTimelineModule","items":[
          ${tweet("111", "42", "$PHOTO_A,$PHOTO_B")},
          ${tweet("222", "42", VIDEO)},
          ${tweet("333", "42", GIF)},
          ${tweet("444", "42", PHOTO_A, "\"retweeted_status_result\":{\"result\":{}},")},
          ${tweet("555", "99", PHOTO_A)}
        ]}},
        {"entryId":"profile-grid-1","content":{"entryType":"TimelineTimelineModule","items":[
          {"entryId":"profile-grid-1-tweet-666","item":{"itemContent":{"tweet_results":{"result":{
            "__typename":"TweetWithVisibilityResults","tweet":{"rest_id":"666","legacy":{"user_id_str":"42",
            "extended_entities":{"media":[$PHOTO_B,$VIDEO]}}}}}}}}
        ]}},
        {"entryId":"cursor-top-1","content":{"entryType":"TimelineTimelineCursor","value":"TOPCURSOR","cursorType":"Top"}},
        {"entryId":"cursor-bottom-1","content":{"entryType":"TimelineTimelineCursor","value":"NEXTCURSOR","cursorType":"Bottom"}}
      ]}]}}}}}}"""
    val parsed = TwitterParse.parsePage(JSONObject(page), "42")
    check("page tweet ids", parsed.tweets.map { it.id }, listOf("111", "222", "333", "666"))
    check("page tweet results counted", parsed.tweetResults, 6)
    check("page bottom cursor", parsed.nextCursor, "NEXTCURSOR")
    val t111 = parsed.tweets[0]
    check("photos counted", t111.photos to t111.videos, 2 to 0)
    check("photo keys", t111.mediaKeys, listOf("AAA111", "BBB222"))
    check("photo thumb", t111.thumb, "https://pbs.twimg.com/media/AAA111.jpg")
    check("photo full", t111.full, "https://pbs.twimg.com/media/AAA111?format=jpg&name=large")
    val t222 = parsed.tweets[1]
    check("video counted", t222.photos to t222.videos, 0 to 1)
    check("video first", t222.firstIsVideo, true)
    check("video picks mid bitrate", t222.full, "https://video.twimg.com/mid.mp4")
    check("video thumb", t222.thumb, "https://pbs.twimg.com/ext_tw_video_thumb/9/pu/img/VID.jpg")
    check("video has no photo keys", t222.mediaKeys, emptyList<String>())
    check("gif counts as video", parsed.tweets[2].videos, 1)
    check("gif url", parsed.tweets[2].full, "https://video.twimg.com/gif.mp4")
    val t666 = parsed.tweets[3]
    check("wrapped tweet mixed", t666.photos to t666.videos, 1 to 1)
    check("wrapped tweet thumb is first media", t666.firstIsVideo, false)

    // --- end of timeline: only cursors ---
    val last = """{"data":{"user":{"result":{"timeline":{"timeline":{"instructions":[{"type":"TimelineAddEntries","entries":[
      {"entryId":"cursor-bottom-9","content":{"value":"END","cursorType":"Bottom"}}]}]}}}}}}"""
    val end = TwitterParse.parsePage(JSONObject(last), "42")
    check("last page has no tweets", end.tweetResults, 0)

    // --- user lookup ---
    check("user ok", TwitterParse.parseUser(JSONObject("""{"data":{"user":{"result":{"__typename":"User","rest_id":"42"}}}}""")).id, "42")
    val gone = TwitterParse.parseUser(JSONObject("""{"data":{"user":{"result":{"__typename":"UserUnavailable","reason":"Suspended"}}}}"""))
    check("user unavailable", gone.id to gone.problem, null to "That X account is unavailable (Suspended).")
    check("user missing", TwitterParse.parseUser(JSONObject("""{"data":{}}""")).id, null)

    // --- query id lookup ---
    check("qid js", TwitterParse.queryIdFrom("e.exports={queryId:\"MOLbHrtk8Ovu7DUNOLcXiA\",operationName:\"UserMedia\",operationType:\"query\"}", "UserMedia"), "MOLbHrtk8Ovu7DUNOLcXiA")
    check("qid json", TwitterParse.queryIdFrom("""{"queryId":"xmU6X_CKVnQ5lSrCbAmJsg","operationName":"UserByScreenName"}""", "UserByScreenName"), "xmU6X_CKVnQ5lSrCbAmJsg")
    check("qid reversed", TwitterParse.queryIdFrom("""{operationName:"UserMedia",queryId:"AbCdEfGhIjKlMnOpQr"}""", "UserMedia"), "AbCdEfGhIjKlMnOpQr")
    check("qid placeholder", TwitterParse.queryIdFrom("""{"UserMedia":{"method":"GET","queryId":"PlaceHolder1234567890"},"Other":{"queryId":"ZZZZZZZZZZZZZZZZZZZZ"}}""", "UserMedia"), "PlaceHolder1234567890")
    check("qid wrong op", TwitterParse.queryIdFrom("e.exports={queryId:\"MOLbHrtk8Ovu7DUNOLcXiA\",operationName:\"UserTweets\"}", "UserMedia"), null)
    check("qid none", TwitterParse.queryIdFrom("nothing here", "UserMedia"), null)

    // --- feature flags X says are missing ---
    check("missing features", TwitterParse.missingFeatures("""{"errors":[{"message":"The following features cannot be null: feat_a, feat_b_2"}]}"""), listOf("feat_a", "feat_b_2"))
    check("no missing features", TwitterParse.missingFeatures("""{"errors":[{"message":"Something else"}]}"""), emptyList<String>())

    // --- video choice ---
    check("only low bitrate", TwitterParse.pickVideoUrl(org.json.JSONArray("""[{"bitrate":256000,"content_type":"video/mp4","url":"u1"}]""")), "u1")
    check("no variants", TwitterParse.pickVideoUrl(null), null)

    // --- time from tweet id, filter, range selection ---
    check("snowflake time", UrlParsers.twitterTimeMs((1000L shl 22).toString()), 1288834974657L + 1000L)
    check("snowflake bad id", UrlParsers.twitterTimeMs("abc"), 0L)
    check("filter both", MediaFilter.BOTH.keeps(0, 0), true)
    check("filter images", listOf(MediaFilter.IMAGES.keeps(1, 0), MediaFilter.IMAGES.keeps(0, 1), MediaFilter.IMAGES.keeps(1, 1)), listOf(true, false, true))
    check("filter videos", listOf(MediaFilter.VIDEOS.keeps(1, 0), MediaFilter.VIDEOS.keeps(0, 1), MediaFilter.VIDEOS.keeps(1, 1)), listOf(false, true, true))
    val list = listOf("a", "b", "c", "d", "e").map { LinkItem("tw:$it", it) }
    check("between two", Matcher.keysBetween(list, setOf("tw:b", "tw:d")), setOf("tw:b", "tw:c", "tw:d"))
    check("between order independent", Matcher.keysBetween(list, setOf("tw:e", "tw:a")).size, 5)
    check("between one", Matcher.keysBetween(list, setOf("tw:c")), setOf("tw:c"))
    check("between none", Matcher.keysBetween(list, emptySet()), emptySet<String>())
    check("link item defaults", LinkItem("k", "u").let { Triple(it.thumb, it.kind, it.count) }, Triple(null, KIND_IMAGE, 1))


    // --- photo sizes and author handle (for the multi-source view) ---
    val sized = """{"entryId":"profile-grid-0-tweet-777","item":{"itemContent":{"tweet_results":{"result":{
        "__typename":"Tweet","rest_id":"777",
        "core":{"user_results":{"result":{"legacy":{"screen_name":"Some_Artist"}}}},
        "legacy":{"user_id_str":"42","extended_entities":{"media":[
          {"type":"photo","media_url_https":"https://pbs.twimg.com/media/SZ1.jpg","original_info":{"width":2048,"height":1365}},
          {"type":"photo","media_url_https":"https://pbs.twimg.com/media/SZ2.jpg","sizes":{"large":{"w":1024,"h":768}}},
          {"type":"photo","media_url_https":"https://pbs.twimg.com/media/SZ3.jpg"},
          $VIDEO]}}}}}}}"""
    val sizedPage = """{"data":{"user":{"result":{"timeline":{"timeline":{"instructions":[{"type":"TimelineAddEntries","entries":[
      {"entryId":"profile-grid-0","content":{"entryType":"TimelineTimelineModule","items":[$sized]}}]}]}}}}}}"""
    val sp = TwitterParse.parsePage(JSONObject(sizedPage), "42").tweets.single()
    check("photo sizes (original_info, sizes.large, unknown)", sp.sizes, listOf(2048, 1365, 1024, 768, 0, 0))
    check("sizes line up with keys", sp.sizes.size, sp.mediaKeys.size * 2)
    check("author handle", sp.screenName, "Some_Artist")
    check("no handle when absent", parsed.tweets[0].screenName, null)
    check("link item sizes default", LinkItem("k", "u").sizes, emptyList<Int>())

    println(if (failures == 0) "\nALL PASSED" else "\n$failures FAILED")
    if (failures != 0) kotlin.system.exitProcess(1)
}
