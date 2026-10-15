package com.leagueradio

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/** gameWins are -1 until a result exists. */
data class Match(
    val id: String, val start: Instant, val stage: String, val bestOf: Int, val state: String,
    val a: String, val b: String, val aw: Int, val bw: Int,
) {
    val done get() = state == "completed"
}

object Schedule {
    private const val API = "https://esports-api.lolesports.com/persisted/gw/getSchedule?hl=en-US" +
        "&leagueId=98767975604431411"
    private const val KEY = "0TvQnueqKa5mxJntVWt0w4LpLfEkrV1Ta8rQBb9Z" // public key used by lolesports.com
    private val RANK = mapOf("unstarted" to 0, "inProgress" to 1, "completed" to 2)

    /** Bundled snapshot merged with everything saved from earlier runs, so it works offline. */
    fun stored(ctx: Context): List<Match> {
        val snap = ctx.assets.open("schedule_snapshot.json").bufferedReader().use { it.readText() }
        val saved = ctx.getSharedPreferences("sched", Context.MODE_PRIVATE).getString("history", null)
        return merge(parse(snap), if (saved != null) parse(saved) else emptyList())
    }

    /**
     * Merges by match id. Results are never lost: a stored result is only replaced by an equal or
     * more-final one, and matches are never deleted.
     */
    fun merge(old: List<Match>, new: List<Match>): List<Match> {
        val byId = LinkedHashMap<String, Match>()
        old.forEach { byId[it.id] = it }
        for (m in new) {
            val o = byId[m.id]
            if (o == null || (RANK[m.state] ?: 0) >= (RANK[o.state] ?: 0)) byId[m.id] = m
            else byId[m.id] = o.copy(start = m.start)
        }
        return byId.values.sortedBy { it.start }
    }

    /** Fetches every year the feed offers, merges into permanent history; null on failure. */
    fun refresh(ctx: Context): List<Match>? = try {
        fun page(token: String?): JSONObject {
            val u = if (token == null) API else "$API&pageToken=$token"
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = 10000
            c.readTimeout = 15000
            c.setRequestProperty("x-api-key", KEY)
            val s = c.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(s).getJSONObject("data").getJSONObject("schedule")
        }
        val pages = ArrayList<JSONObject>()
        val first = page(null)
        pages.add(first)
        for (key in listOf("newer", "older")) {
            var tok = first.getJSONObject("pages").optString(key)
            var k = 0
            while (tok.isNotEmpty() && tok != "null" && k++ < 12) {
                val p = page(tok)
                pages.add(p)
                tok = p.getJSONObject("pages").optString(key)
            }
        }
        val out = JSONArray()
        for (p in pages) {
            val ev = p.getJSONArray("events")
            for (i in 0 until ev.length()) {
                val e = ev.getJSONObject(i)
                val m = e.optJSONObject("match") ?: continue
                val t = m.getJSONArray("teams")
                fun wins(j: Int) = t.getJSONObject(j).optJSONObject("result")?.optInt("gameWins", -1) ?: -1
                out.put(JSONObject().put("id", m.getString("id")).put("t", e.getString("startTime"))
                    .put("stage", e.getString("blockName")).put("bo", m.getJSONObject("strategy").getInt("count"))
                    .put("state", e.getString("state"))
                    .put("a", t.getJSONObject(0).getString("code")).put("b", t.getJSONObject(1).getString("code"))
                    .put("aw", wins(0)).put("bw", wins(1)))
            }
        }
        if (out.length() == 0) null else {
            val merged = merge(stored(ctx), parse(out.toString()))
            ctx.getSharedPreferences("sched", Context.MODE_PRIVATE).edit()
                .putString("history", toJson(merged)).apply()
            merged
        }
    } catch (e: Exception) {
        null
    }

    private fun toJson(list: List<Match>): String {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("t", it.start.toString()).put("stage", it.stage)
                .put("bo", it.bestOf).put("state", it.state).put("a", it.a).put("b", it.b)
                .put("aw", it.aw).put("bw", it.bw))
        }
        return arr.toString()
    }

    private fun parse(json: String): List<Match> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Match(o.getString("id"), Instant.parse(o.getString("t")), o.getString("stage"), o.getInt("bo"),
                o.optString("state", "unstarted"), o.getString("a"), o.getString("b"),
                o.optInt("aw", -1), o.optInt("bw", -1))
        }.sortedBy { it.start }
    }
}
