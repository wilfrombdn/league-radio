package com.leagueradio

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

data class Chan(val login: String, val label: String)

/** Official Riot / partner broadcast channels on Twitch, in several languages. */
object Channels {
    // Each of these exists on Twitch and is run by Riot or an official regional partner.
    val OFFICIAL = listOf(
        Chan("riotgames", "English · Riot Games"),
        Chan("lolesports", "English · LoL Esports"),
        Chan("lck", "Korean / English · LCK"),
        Chan("lec", "English · LEC"),
        Chan("lcs", "English · LCS"),
        Chan("lplenglish", "English · LPL English"),
        Chan("lpl", "Chinese · LPL"),
        Chan("lolesportstw", "Chinese (Taiwan) · LoL Esports TW"),
        Chan("cblol", "Portuguese · CBLOL"),
        Chan("riotgamesbrasil", "Portuguese · Riot Games Brasil"),
        Chan("lvpes", "Spanish · LVP"),
        Chan("riotgameslatam", "Spanish · Riot Games LATAM"),
        Chan("lolesportsla", "Spanish · LoL Esports LA"),
        Chan("otplol_", "French · OTP"),
        Chan("riotgamesru", "Russian · Riot Games RU"),
        Chan("riotgamesvn", "Vietnamese · Riot Games VN"),
        Chan("lolesports_ar", "Arabic · LoL Esports AR"),
    )

    private const val LIVE = "https://esports-api.lolesports.com/persisted/gw/getLive?hl=en-US"
    private const val KEY = "0TvQnueqKa5mxJntVWt0w4LpLfEkrV1Ta8rQBb9Z" // public key used by lolesports.com

    /** Which of these Twitch channels are streaming right now (one small request, no video); null on failure. */
    fun liveSet(logins: List<String>): Set<String>? = try {
        val q = "query{users(logins:[" + logins.joinToString(",") { "\"$it\"" } + "]){login stream{id}}}"
        val c = URL("https://gql.twitch.tv/gql").openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Client-ID", "kimne78kx3ncx6brgo4mv6wki5h1ko") // Twitch web player public id
        c.outputStream.use { it.write(JSONObject().put("query", q).toString().toByteArray()) }
        val users = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            .getJSONObject("data").getJSONArray("users")
        (0 until users.length()).mapNotNull { users.optJSONObject(it) }
            .filter { !it.isNull("stream") }.map { it.getString("login").lowercase() }.toSet()
    } catch (e: Exception) { null }

    /** Official channels found by discovery first, then the built-in list. */
    fun all(ctx: Context): List<Chan> {
        val found = ArrayList<Chan>()
        try {
            val arr = JSONArray(ctx.getSharedPreferences("chans", Context.MODE_PRIVATE).getString("found", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                found.add(Chan(o.getString("login"), o.getString("label")))
            }
        } catch (e: Exception) { }
        return (found + OFFICIAL).distinctBy { it.login }
    }

    /** Asks Riot which streams the live Worlds broadcast is using right now and remembers them. */
    fun discover(ctx: Context) {
        try {
            val c = URL(LIVE).openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.setRequestProperty("x-api-key", KEY)
            val root = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val events = root.getJSONObject("data").getJSONObject("schedule").getJSONArray("events")
            val out = JSONArray()
            for (i in 0 until events.length()) {
                val e = events.getJSONObject(i)
                if (e.optJSONObject("league")?.optString("slug") != "worlds") continue
                val streams = e.optJSONArray("streams") ?: continue
                for (j in 0 until streams.length()) {
                    val s = streams.getJSONObject(j)
                    if (s.optString("provider") != "twitch") continue
                    val login = s.optString("parameter").lowercase()
                    if (login.isEmpty()) continue
                    val lang = s.optJSONObject("mediaLocale")?.optString("englishName") ?: s.optString("locale")
                    out.put(JSONObject().put("login", login).put("label", "$lang · official live"))
                }
            }
            if (out.length() > 0) {
                ctx.getSharedPreferences("chans", Context.MODE_PRIVATE).edit().putString("found", out.toString()).apply()
            }
        } catch (e: Exception) { }
    }

    /**
     * Returns the first live stream. Streams Riot lists for the live Worlds broadcast are tried first;
     * if none of those are live, every official channel is probed in parallel in priority order.
     */
    fun firstLive(ctx: Context): Pair<String, String>? {
        discover(ctx)
        val everything = all(ctx).map { it.login }
        val found = try {
            val arr = JSONArray(ctx.getSharedPreferences("chans", Context.MODE_PRIVATE).getString("found", "[]"))
            (0 until arr.length()).map { arr.getJSONObject(it).getString("login") }
        } catch (e: Exception) { emptyList() }
        val groups = if (found.isNotEmpty()) listOf(found, everything) else listOf(everything)
        for (logins in groups) {
            val pool = Executors.newFixedThreadPool(logins.size.coerceAtLeast(1))
            try {
                val results = pool.invokeAll(logins.map { l ->
                    Callable { try { l to Twitch.audioUrl(l) } catch (e: Exception) { null } }
                }).map { it.get() }
                results.firstOrNull { it != null }?.let { return it }
            } finally {
                pool.shutdown()
            }
        }
        return null
    }
}
