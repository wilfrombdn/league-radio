package com.leagueradio

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

class OfflineException : IOException("offline")

/** Resolves a Twitch channel to its audio-only HLS media playlist (no video data is fetched). */
object Twitch {
    private const val CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko" // Twitch web player public id

    private const val TOKEN_HASH = "ed230aa1e33e07eebb8928504583da78a5173989fadfb1ac94be06a04f3cdbe9"

    // playerType "site" gets a 1080p video preroll ad stitched into the audio_only playlist;
    // the player locks onto that video track and goes silent when the ad ends. "embed" gets no preroll.
    fun audioUrl(channel: String): String {
        val body = JSONArray().put(JSONObject()
            .put("operationName", "PlaybackAccessToken")
            .put("extensions", JSONObject().put("persistedQuery", JSONObject()
                .put("version", 1).put("sha256Hash", TOKEN_HASH)))
            .put("variables", JSONObject().put("isLive", true).put("login", channel).put("isVod", false)
                .put("vodID", "").put("playerType", "embed").put("platform", "web")))
        val gql = http("https://gql.twitch.tv/gql", body.toString())
        val tok = JSONArray(gql).getJSONObject(0).optJSONObject("data")
            ?.optJSONObject("streamPlaybackAccessToken") ?: throw OfflineException()
        val usher = "https://usher.ttvnw.net/api/channel/hls/$channel.m3u8" +
            "?sig=${enc(tok.getString("signature"))}&token=${enc(tok.getString("value"))}" +
            "&allow_audio_only=true&allow_source=true&fast_bread=true&player=twitchweb" +
            "&supported_codecs=avc1&type=any&p=${(100000..999999).random()}"
        val master = try {
            http(usher, null)
        } catch (e: IOException) {
            throw OfflineException() // usher answers 404 when the channel is not live
        }
        return pickAudio(master) ?: throw OfflineException()
    }

    /** Picks the audio_only variant, else the lowest-bandwidth one. */
    internal fun pickAudio(master: String): String? {
        val lines = master.lines()
        var best: String? = null
        var bestBw = Long.MAX_VALUE
        for (i in lines.indices) {
            if (!lines[i].startsWith("#EXT-X-STREAM-INF")) continue
            val url = lines.getOrNull(i + 1)?.trim().orEmpty()
            if (!url.startsWith("http")) continue
            if (lines[i].contains("\"audio_only\"")) return url
            val bw = Regex("BANDWIDTH=(\\d+)").find(lines[i])?.groupValues?.get(1)?.toLong() ?: Long.MAX_VALUE
            if (bw < bestBw) { bestBw = bw; best = url }
        }
        return best
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun http(url: String, post: String?): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 15000
        c.setRequestProperty("Client-ID", CLIENT_ID)
        if (post != null) {
            c.requestMethod = "POST"
            c.doOutput = true
            c.outputStream.use { it.write(post.toByteArray()) }
        }
        return c.inputStream.bufferedReader().use { it.readText() }
    }
}
