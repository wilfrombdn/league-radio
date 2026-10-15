package com.leagueradio

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val bg = Color.parseColor("#000000")
    private val gold = Color.parseColor("#FFFFFF")
    private val dim = Color.parseColor("#8C8C8C")
    private val panel = Color.parseColor("#111111")
    private val panel2 = Color.parseColor("#262626")
    private val teal = Color.parseColor("#FFFFFF")
    private lateinit var tabRadio: TextView
    private lateinit var tabSched: TextView
    private lateinit var dot: View

    private var controller: MediaController? = null
    private var playing = false
    private lateinit var channelBox: EditText
    private lateinit var playBtn: TextView
    private lateinit var statusTv: TextView
    private lateinit var nextTv: TextView
    private lateinit var radioPane: LinearLayout
    private lateinit var schedPane: ScrollView
    private lateinit var schedList: LinearLayout
    private var matches: List<Match> = emptyList()
    private var view = 0 // 0 upcoming, 1 results, 2 all
    private var year = java.time.Year.now().value.toString()
    private val io = Executors.newSingleThreadExecutor()

    private val tzReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = renderSchedule()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        buildUi()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        connect()
        matches = Schedule.stored(this)
        renderSchedule()
        io.execute {
            Schedule.refresh(this)?.let { matches = it; runOnUiThread { renderSchedule() } }
        }
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(tzReceiver, IntentFilter(Intent.ACTION_TIMEZONE_CHANGED))
    }

    override fun onResume() {
        super.onResume()
        renderSchedule()
    }

    override fun onStop() {
        unregisterReceiver(tzReceiver)
        super.onStop()
    }

    override fun onDestroy() {
        controller?.release()
        io.shutdownNow()
        super.onDestroy()
    }

    // ---- playback ----
    private fun connect() {
        val token = SessionToken(this, ComponentName(this, RadioService::class.java))
        val f = MediaController.Builder(this, token)
            .setListener(object : MediaController.Listener {
                override fun onExtrasChanged(c: MediaController, extras: Bundle) {
                    extras.getString("status")?.let { statusTv.text = it; updatePlayBtn() }
                }
            }).buildAsync()
        f.addListener({
            val c = f.get()
            controller = c
            c.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) = updatePlayBtn()
            })
            c.sessionExtras.getString("status")?.let { statusTv.text = it }
            updatePlayBtn()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun updatePlayBtn() {
        val st = statusTv.text.toString()
        playing = controller?.isPlaying == true || st.startsWith("Connecting") || st.contains("retrying")
        playBtn.text = if (playing) "\u25A0" else "\u25B6"
        setDot(if (controller?.isPlaying == true) teal else if (playing) gold else dim)
    }

    private fun togglePlay() {
        val c = controller ?: return
        if (playing) {
            c.sendCustomCommand(SessionCommand(RadioService.CMD_STOP, Bundle.EMPTY), Bundle.EMPTY)
            playBtn.text = "\u25B6"
            playing = false
            setDot(dim)
        } else {
            val ch = channelBox.text.toString().trim().lowercase(Locale.ROOT)
                .removePrefix("twitch.tv/").trim('/').ifEmpty { "lolesports" }
            getSharedPreferences("prefs", MODE_PRIVATE).edit().putString("channel", ch).apply()
            c.sendCustomCommand(SessionCommand(RadioService.CMD_PLAY, Bundle.EMPTY),
                Bundle().apply { putString("channel", ch) })
            playBtn.text = "\u25A0"
            playing = true
            setDot(gold)
        }
    }

    /** Plays whichever similar channel is live right now, to test the same audio_only pipeline. */
    private fun testStream() {
        statusTv.text = "Looking for a live test stream..."
        io.execute {
            val pool = Executors.newFixedThreadPool(TEST_CHANNELS.size)
            val flags = pool.invokeAll(TEST_CHANNELS.map { ch ->
                java.util.concurrent.Callable { try { Twitch.audioUrl(ch); true } catch (e: Exception) { false } }
            }).map { it.get() }
            pool.shutdown()
            val ch = TEST_CHANNELS.zip(flags).firstOrNull { it.second }?.first
            runOnUiThread {
                if (ch == null) { statusTv.text = "No test channel is live right now"; return@runOnUiThread }
                channelBox.setText(ch)
                if (playing) togglePlay()
                togglePlay()
            }
        }
    }

    private fun setDot(color: Int) {
        if (::dot.isInitialized) dot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
    }

    // ---- UI ----
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun shape(fill: Int, radius: Int, stroke: Int = 0, strokeW: Int = 0) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radius).toFloat(); if (strokeW > 0) setStroke(dp(strokeW), stroke)
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun showTab(radio: Boolean) {
        radioPane.visibility = if (radio) View.VISIBLE else View.GONE
        schedPane.visibility = if (radio) View.GONE else View.VISIBLE
        tabRadio.setTextColor(if (radio) gold else dim)
        tabSched.setTextColor(if (radio) dim else gold)
        tabRadio.background = if (radio) underline() else null
        tabSched.background = if (radio) null else underline()
    }

    private fun underline() = android.graphics.drawable.LayerDrawable(arrayOf(
        android.graphics.drawable.ColorDrawable(Color.TRANSPARENT),
        android.graphics.drawable.ColorDrawable(gold))).apply {
        setLayerGravity(1, Gravity.BOTTOM)
        setLayerHeight(1, dp(2))
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            fitsSystemWindows = true
        }
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun tab(label: String) = text(label, 13f, dim, true).apply {
            gravity = Gravity.CENTER; letterSpacing = 0.12f; setPadding(0, dp(16), 0, dp(12))
        }
        tabRadio = tab("RADIO")
        tabSched = tab("SCHEDULE")
        tabs.addView(tabRadio, LinearLayout.LayoutParams(0, -2, 1f))
        tabs.addView(tabSched, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(tabs)

        // Radio pane
        radioPane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(20), dp(24), dp(16))
        }
        radioPane.addView(text("LEAGUE RADIO", 26f, gold, true).apply { letterSpacing = 0.08f })
        radioPane.addView(text("Audio-only  •  low data", 13f, dim).apply { setPadding(0, dp(4), 0, dp(20)) })
        playBtn = text("▶", 52f, gold).apply {
            gravity = Gravity.CENTER
            background = shape(panel, 100, gold, 3)
            setOnClickListener { togglePlay() }
        }
        radioPane.addView(playBtn, LinearLayout.LayoutParams(dp(150), dp(150)))
        val srow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(16), 0, 0) }
        dot = View(this)
        srow.addView(dot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(8) })
        statusTv = text("Stopped", 15f, Color.parseColor("#EDEDED"))
        srow.addView(statusTv)
        radioPane.addView(srow)
        setDot(dim)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = shape(panel, 14, panel2, 1)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        nextTag = text("NEXT MATCH", 11f, gold, true).apply { letterSpacing = 0.12f }
        nextTv = text("", 22f, Color.parseColor("#EDEDED"), true)
        nextTime = text("", 13f, dim)
        card.addView(nextTag)
        card.addView(nextTv)
        card.addView(nextTime)
        radioPane.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })

        radioPane.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        val crow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        channelBox = EditText(this).apply {
            setText(getSharedPreferences("prefs", MODE_PRIVATE).getString("channel", "lolesports"))
            setTextColor(Color.WHITE)
            setHintTextColor(dim)
            hint = "twitch channel"
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine()
            background = shape(panel, 10, panel2, 1)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        crow.addView(text("twitch.tv/", 13f, dim).apply { setPadding(0, 0, dp(8), 0) })
        crow.addView(channelBox, LinearLayout.LayoutParams(0, -2, 1f))
        radioPane.addView(crow, LinearLayout.LayoutParams(-1, -2))
        val testBtn = text("TEST WITH A LIVE STREAM", 12f, gold, true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
            background = shape(Color.TRANSPARENT, 10, gold, 1)
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener { testStream() }
        }
        radioPane.addView(testBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        root.addView(radioPane, LinearLayout.LayoutParams(-1, 0, 1f))

        // Schedule pane
        schedList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(24))
        }
        schedPane = ScrollView(this).apply { addView(schedList); visibility = View.GONE }
        root.addView(schedPane, LinearLayout.LayoutParams(-1, 0, 1f))

        tabRadio.setOnClickListener { showTab(true) }
        tabSched.setOnClickListener { showTab(false) }
        setContentView(root)
        showTab(true)
    }

    private lateinit var nextTag: TextView
    private lateinit var nextTime: TextView

    private fun chip(label: String, on: Boolean, click: () -> Unit) = text(label, 12f, if (on) bg else gold, true).apply {
        gravity = Gravity.CENTER
        background = shape(if (on) gold else Color.TRANSPARENT, 16, gold, 1)
        setPadding(dp(14), dp(7), dp(14), dp(7))
        setOnClickListener { click() }
    }

    /** Times are converted using the phone time zone and refreshed when it changes. */
    private fun renderSchedule() {
        if (!::schedList.isInitialized) return
        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        val dayFmt = DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.getDefault())
        val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
        schedList.removeAllViews()
        val tzName = ZonedDateTime.now(zone).format(DateTimeFormatter.ofPattern("zzz", Locale.getDefault()))
        schedList.addView(text("Your local time \u2022 ${zone.id} ($tzName)", 12f, dim).apply { setPadding(0, 0, 0, dp(8)) })

        // filters: view + year
        val years = (matches.map { it.start.atZone(zone).year.toString() } + java.time.Year.now().value.toString())
            .distinct().sorted()
        val bar = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val barRow = LinearLayout(this)
        fun add(v: View) = barRow.addView(v, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
        listOf("Upcoming", "Results", "All").forEachIndexed { i, l -> add(chip(l, view == i) { view = i; renderSchedule() }) }
        years.forEach { y -> add(chip(y, year == y) { year = y; renderSchedule() }) }
        bar.addView(barRow)
        schedList.addView(bar)

        val inYear = matches.filter { it.start.atZone(zone).year.toString() == year }
        schedList.addView(text("${inYear.count { it.done }} of ${inYear.size} matches played", 12f, dim)
            .apply { setPadding(dp(4), dp(10), 0, 0) })
        var rows = inYear.filter { view == 2 || (view == 1) == it.done }
        if (view == 1) rows = rows.reversed()

        var lastDay: String? = null
        var nextShown = false
        for (m in rows) {
            val z = m.start.atZone(zone)
            val live = !m.done && !m.start.isAfter(now) && m.start.isAfter(now.minusSeconds(3 * 3600))
            val isNext = !nextShown && !m.done && m.start.isAfter(now.minusSeconds(3 * 3600))
            if (isNext) nextShown = true
            val day = z.format(dayFmt)
            if (day != lastDay) {
                lastDay = day
                schedList.addView(text(day.uppercase(), 12f, gold, true).apply {
                    letterSpacing = 0.1f
                    setPadding(dp(4), dp(18), 0, dp(6))
                })
            }
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = shape(if (isNext) panel2 else panel, 12, if (live || isNext) gold else panel2, if (live || isNext) 2 else 1)
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            row.addView(text(z.format(timeFmt), 13f, dim, false), LinearLayout.LayoutParams(dp(76), -2))
            val mid = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            if (m.done && m.aw >= 0) {
                val aWon = m.aw > m.bw
                mid.addView(text(m.a, 16f, if (aWon) Color.WHITE else dim, aWon))
                mid.addView(text("  ${m.aw} - ${m.bw}  ", 16f, Color.WHITE, true))
                mid.addView(text(m.b, 16f, if (aWon) dim else Color.WHITE, !aWon))
            } else {
                mid.addView(text("${m.a}  vs  ${m.b}", 16f, Color.WHITE, true))
            }
            row.addView(mid, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(text(if (live) "\u25CF LIVE" else "Bo${m.bestOf} \u00B7 ${m.stage}", 11f,
                if (live) Color.WHITE else dim, live))
            schedList.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        }
        if (rows.isEmpty()) schedList.addView(text("Nothing to show yet.", 14f, dim).apply { setPadding(dp(4), dp(24), 0, 0) })

        // "next match" card: first unfinished match overall
        val n = matches.firstOrNull { !it.done && it.start.isAfter(now.minusSeconds(3 * 3600)) }
        if (n != null) {
            val live = !n.start.isAfter(now)
            nextTag.text = if (live) "\u25CF LIVE NOW" else "NEXT MATCH"
            nextTv.text = "${n.a}  vs  ${n.b}"
            val mins = java.time.Duration.between(now, n.start).toMinutes()
            val inTxt = when {
                live -> ""
                mins >= 1440 -> "  \u00B7  in ${mins / 1440}d ${mins % 1440 / 60}h"
                mins >= 60 -> "  \u00B7  in ${mins / 60}h ${mins % 60}m"
                else -> "  \u00B7  in ${mins}m"
            }
            nextTime.text = n.start.atZone(zone)
                .format(DateTimeFormatter.ofPattern("EEE MMM d, h:mm a", Locale.getDefault())) + inTxt
        } else {
            nextTag.text = "NEXT MATCH"
            nextTv.text = "\u2014"
            nextTime.text = "No upcoming matches listed"
        }
    }

    companion object {
        val TEST_CHANNELS = listOf("lolesports", "riotgames", "lck", "lec", "caedrel", "tarik", "ibai", "gaules",
            "xqc", "kaicenat", "jynxzi", "monstercat", "lofi_girl", "shroud")
    }
}
