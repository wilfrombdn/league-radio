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
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Year
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class MainActivity : Activity() {
    // plain black and white
    private val bg = Color.parseColor("#000000")
    private val white = Color.parseColor("#FFFFFF")
    private val text0 = Color.parseColor("#EDEDED")
    private val dim = Color.parseColor("#8C8C8C")
    private val faint = Color.parseColor("#5C5C5C")
    private val panel = Color.parseColor("#101010")
    private val panel2 = Color.parseColor("#202020")
    private val line = Color.parseColor("#2E2E2E")

    private var controller: MediaController? = null
    private var playing = false
    private var busy = false

    private lateinit var tabCells: List<TextView>
    private lateinit var tabLines: List<View>
    private lateinit var radioPane: View
    private lateinit var schedPane: View
    private lateinit var playBtn: PlayButton
    private lateinit var dot: View
    private lateinit var statusTv: TextView
    private lateinit var channelBox: EditText
    private lateinit var nextTag: TextView
    private lateinit var nextTv: TextView
    private lateinit var nextTime: TextView
    private lateinit var segHolder: LinearLayout
    private lateinit var yearHolder: LinearLayout
    private lateinit var countTv: TextView
    private lateinit var tzTv: TextView
    private lateinit var schedList: LinearLayout

    private var matches: List<Match> = emptyList()
    private var view = 0 // 0 upcoming, 1 results, 2 all
    private var year = Year.now().value.toString()
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
                    extras.getString("status")?.let { statusTv.text = it; syncState() }
                }
            }).buildAsync()
        f.addListener({
            val c = f.get()
            controller = c
            c.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) = syncState()
            })
            c.sessionExtras.getString("status")?.let { statusTv.text = it }
            syncState()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun syncState() {
        val st = statusTv.text.toString()
        val isPlaying = controller?.isPlaying == true
        busy = !isPlaying && (st.startsWith("Connecting") || st.contains("retrying"))
        playing = isPlaying || busy
        playBtn.playing = playing
        setDot(if (isPlaying) white else if (busy) dim else faint, hollow = st.contains("retrying"))
    }

    private fun togglePlay() {
        val c = controller ?: return
        if (playing) {
            c.sendCustomCommand(SessionCommand(RadioService.CMD_STOP, Bundle.EMPTY), Bundle.EMPTY)
            playing = false
            playBtn.playing = false
            statusTv.text = "Stopped"
            setDot(faint)
        } else {
            val ch = channelBox.text.toString().trim().lowercase(Locale.ROOT)
                .removePrefix("twitch.tv/").trim('/').ifEmpty { "lolesports" }
            getSharedPreferences("prefs", MODE_PRIVATE).edit().putString("channel", ch).apply()
            c.sendCustomCommand(SessionCommand(RadioService.CMD_PLAY, Bundle.EMPTY),
                Bundle().apply { putString("channel", ch) })
            playing = true
            playBtn.playing = true
            statusTv.text = "Connecting..."
            setDot(dim)
        }
    }

    /** Plays whichever similar channel is live right now, to test the same audio_only pipeline. */
    private fun testStream() {
        statusTv.text = "Looking for a live test stream..."
        io.execute {
            val pool = Executors.newFixedThreadPool(TEST_CHANNELS.size)
            val flags = pool.invokeAll(TEST_CHANNELS.map { ch ->
                Callable { try { Twitch.audioUrl(ch); true } catch (e: Exception) { false } }
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

    private fun setDot(color: Int, hollow: Boolean = false) {
        dot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (hollow) { setColor(Color.TRANSPARENT); setStroke(dp(2), color) } else setColor(color)
        }
    }

    // ---- view helpers ----
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun shape(fill: Int, radius: Int, stroke: Int = 0, strokeW: Int = 0) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        if (strokeW > 0) setStroke(dp(strokeW), stroke)
    }

    private fun tv(s: String, size: Float, color: Int, bold: Boolean = false, spacing: Float = 0f) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        letterSpacing = spacing
        includeFontPadding = false
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    /** Segmented pill control. Cells keep their size; selecting only changes colours. */
    private fun segmented(options: List<String>, selected: Int, onPick: (Int) -> Unit): LinearLayout {
        val box = LinearLayout(this).apply {
            background = shape(line, 9)
            setPadding(dp(1), dp(1), dp(1), dp(1))
        }
        options.forEachIndexed { i, label ->
            val on = i == selected
            box.addView(tv(label, 12f, if (on) bg else dim, true).apply {
                gravity = Gravity.CENTER
                background = shape(if (on) white else panel, 8)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                setOnClickListener { onPick(i) }
            })
        }
        return box
    }

    private fun showTab(i: Int) {
        radioPane.visibility = if (i == 0) View.VISIBLE else View.GONE
        schedPane.visibility = if (i == 1) View.VISIBLE else View.GONE
        tabCells.forEachIndexed { k, t -> t.setTextColor(if (k == i) white else dim) }
        tabLines.forEachIndexed { k, l -> l.setBackgroundColor(if (k == i) white else line) }
    }

    // ---- UI ----
    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            fitsSystemWindows = true
        }

        // tab bar: two equal cells side by side; underline colour marks the active one, nothing moves
        val tabs = LinearLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0) }
        val cells = ArrayList<TextView>()
        val lines = ArrayList<View>()
        listOf("RADIO", "SCHEDULE").forEachIndexed { i, name ->
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val t = tv(name, 12f, dim, true, 0.15f).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(14), 0, dp(12))
                setOnClickListener { showTab(i) }
            }
            val u = View(this).apply { setBackgroundColor(line) }
            col.addView(t, lp(-1, -2))
            col.addView(u, lp(-1, dp(2)))
            tabs.addView(col, lp(0, -2, 1f))
            cells.add(t)
            lines.add(u)
        }
        tabCells = cells
        tabLines = lines
        root.addView(tabs, lp(-1, -2))

        radioPane = buildRadio()
        schedPane = buildSchedule()
        root.addView(radioPane, lp(-1, 0, 1f))
        root.addView(schedPane, lp(-1, 0, 1f))
        setContentView(root)
        showTab(0)
    }

    private fun buildRadio(): View {
        val p = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(28), dp(24), dp(20))
        }
        p.addView(tv("LEAGUE RADIO", 26f, white, true, 0.06f))
        p.addView(tv("Audio-only  ·  low data", 13f, dim).apply { setPadding(0, dp(6), 0, 0) })

        playBtn = PlayButton(this).apply { setOnClickListener { togglePlay() } }
        p.addView(playBtn, lp(dp(132), dp(132)).apply { topMargin = dp(28) })

        val srow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        dot = View(this)
        srow.addView(dot, lp(dp(10), dp(10)).apply { rightMargin = dp(10) })
        statusTv = tv("Stopped", 14f, text0).apply {
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
        }
        srow.addView(statusTv, lp(-2, -2))
        p.addView(srow, lp(-2, dp(24)).apply { topMargin = dp(18) })
        setDot(faint)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = shape(panel, 16, line, 1)
            setPadding(dp(16), dp(18), dp(16), dp(18))
        }
        nextTag = tv("NEXT MATCH", 11f, dim, true, 0.15f)
        nextTv = tv("", 24f, white, true).apply { setPadding(0, dp(10), 0, dp(8)) }
        nextTime = tv("", 13f, dim)
        card.addView(nextTag)
        card.addView(nextTv)
        card.addView(nextTime)
        p.addView(card, lp(-1, -2).apply { topMargin = dp(28) })

        p.addView(View(this), lp(-1, 0, 1f))

        val crow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        crow.addView(tv("twitch.tv/", 13f, dim).apply { setPadding(0, 0, dp(10), 0) })
        channelBox = EditText(this).apply {
            setText(getSharedPreferences("prefs", MODE_PRIVATE).getString("channel", "lolesports"))
            setTextColor(white)
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine()
            background = shape(panel, 10, line, 1)
            setPadding(dp(14), dp(11), dp(14), dp(11))
        }
        crow.addView(channelBox, lp(0, -2, 1f))
        p.addView(crow, lp(-1, -2))

        val test = tv("TEST WITH A LIVE STREAM", 12f, white, true, 0.1f).apply {
            gravity = Gravity.CENTER
            background = shape(Color.TRANSPARENT, 10, white, 1)
            setPadding(0, dp(14), 0, dp(14))
            setOnClickListener { testStream() }
        }
        p.addView(test, lp(-1, -2).apply { topMargin = dp(12) })
        return p
    }

    private fun buildSchedule(): View {
        val p = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // fixed header: filters stay put while the list scrolls
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }
        val r1 = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        segHolder = LinearLayout(this)
        countTv = tv("", 12f, dim).apply { gravity = Gravity.END }
        r1.addView(segHolder, lp(-2, -2))
        r1.addView(countTv, lp(0, -2, 1f))
        head.addView(r1, lp(-1, -2))

        val r2 = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val ys = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        yearHolder = LinearLayout(this)
        ys.addView(yearHolder)
        tzTv = tv("", 11f, faint).apply { gravity = Gravity.END }
        r2.addView(ys, lp(-2, -2))
        r2.addView(tzTv, lp(0, -2, 1f))
        head.addView(r2, lp(-1, -2).apply { topMargin = dp(10) })
        p.addView(head, lp(-1, -2))

        schedList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), dp(24))
        }
        p.addView(ScrollView(this).apply { addView(schedList); isVerticalScrollBarEnabled = false }, lp(-1, 0, 1f))
        return p
    }

    /** Times use the phone time zone and are re-rendered whenever it changes. */
    private fun renderSchedule() {
        if (!::schedList.isInitialized) return
        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        val dayFmt = DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.getDefault())
        val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
        val tzName = ZonedDateTime.now(zone).format(DateTimeFormatter.ofPattern("zzz", Locale.getDefault()))

        val years = (matches.map { it.start.atZone(zone).year.toString() } + Year.now().value.toString())
            .distinct().sorted()
        segHolder.removeAllViews()
        segHolder.addView(segmented(listOf("Upcoming", "Results", "All"), view) { view = it; renderSchedule() })
        yearHolder.removeAllViews()
        yearHolder.addView(segmented(years, years.indexOf(year).coerceAtLeast(0)) { year = years[it]; renderSchedule() })
        tzTv.text = "Local time  $tzName"

        val inYear = matches.filter { it.start.atZone(zone).year.toString() == year }
        countTv.text = "${inYear.count { it.done }} of ${inYear.size} played"
        var rows = inYear.filter { view == 2 || (view == 1) == it.done }
        if (view == 1) rows = rows.reversed()
        val stages = LinkedHashMap<LocalDate, MutableList<String>>()
        rows.forEach { m ->
            val l = stages.getOrPut(m.start.atZone(zone).toLocalDate()) { ArrayList() }
            if (m.stage !in l) l.add(m.stage)
        }

        schedList.removeAllViews()
        var lastDay: LocalDate? = null
        var nextShown = false
        for (m in rows) {
            val z = m.start.atZone(zone)
            val day = z.toLocalDate()
            val live = !m.done && !m.start.isAfter(now) && m.start.isAfter(now.minusSeconds(3 * 3600))
            val isNext = !nextShown && !m.done && m.start.isAfter(now.minusSeconds(3 * 3600))
            if (isNext) nextShown = true
            if (day != lastDay) {
                lastDay = day
                val h = LinearLayout(this).apply {
                    gravity = Gravity.BOTTOM
                    setPadding(dp(12), dp(22), dp(12), dp(6))
                }
                h.addView(tv(z.format(dayFmt), 14f, white, true))
                h.addView(tv("   " + stages[day]!!.joinToString(" / "), 11f, dim))
                schedList.addView(h)
            }
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                if (isNext) background = shape(panel2, 10)
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }
            row.addView(tv(z.format(timeFmt), 12f, dim), lp(dp(66), -2))
            val scored = m.done && m.aw >= 0
            val aWon = m.aw > m.bw
            row.addView(tv(m.a, 15f, if (!scored || aWon) white else dim, !scored || aWon).apply {
                gravity = Gravity.END
            }, lp(0, -2, 1f))
            row.addView(tv(if (scored) "${m.aw} – ${m.bw}" else "vs", if (scored) 15f else 12f,
                if (scored) white else faint, scored).apply { gravity = Gravity.CENTER }, lp(dp(58), -2))
            row.addView(tv(m.b, 15f, if (!scored || !aWon) white else dim, !scored || !aWon), lp(0, -2, 1f))
            row.addView(tv(if (live) "LIVE" else "Bo${m.bestOf}", 11f, if (live) white else faint, live).apply {
                gravity = Gravity.END
            }, lp(dp(38), -2))
            schedList.addView(row, lp(-1, -2))
        }
        if (rows.isEmpty()) schedList.addView(tv("Nothing to show yet.", 14f, dim).apply {
            setPadding(dp(12), dp(28), 0, 0)
        })

        // "next match" card on the radio tab: first unfinished match overall
        val n = matches.firstOrNull { !it.done && it.start.isAfter(now.minusSeconds(3 * 3600)) }
        if (n != null) {
            val live = !n.start.isAfter(now)
            nextTag.text = if (live) "● LIVE NOW" else "NEXT MATCH"
            nextTag.setTextColor(if (live) white else dim)
            nextTv.text = "${n.a}   vs   ${n.b}"
            val mins = Duration.between(now, n.start).toMinutes()
            val inTxt = when {
                live -> ""
                mins >= 1440 -> "   ·   in ${mins / 1440}d ${mins % 1440 / 60}h"
                mins >= 60 -> "   ·   in ${mins / 60}h ${mins % 60}m"
                else -> "   ·   in ${mins}m"
            }
            nextTime.text = n.start.atZone(zone)
                .format(DateTimeFormatter.ofPattern("EEE MMM d, h:mm a", Locale.getDefault())) + inTxt
        } else {
            nextTag.text = "NEXT MATCH"
            nextTv.text = "—"
            nextTime.text = "No upcoming matches listed"
        }
    }

    companion object {
        val TEST_CHANNELS = listOf("lolesports", "riotgames", "lck", "lec", "caedrel", "tarik", "ibai", "gaules",
            "xqc", "kaicenat", "jynxzi", "monstercat", "lofi_girl", "shroud")
    }
}
