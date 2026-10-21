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
import android.graphics.drawable.LayerDrawable
import android.widget.ArrayAdapter
import android.view.ViewGroup
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
    // near-black with one teal accent
    private val bg = Color.parseColor("#0B0D10")
    private val white = Color.parseColor("#FFFFFF")
    private val text0 = Color.parseColor("#E8EAED")
    private val dim = Color.parseColor("#8B919A")
    private val faint = Color.parseColor("#5A606A")
    private val panel = Color.parseColor("#14171C")
    private val panel2 = Color.parseColor("#1C2027")
    private val line = Color.parseColor("#262B33")
    private val accent = Color.parseColor("#2DD4BF")

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
    private lateinit var countTv: TextView
    private lateinit var tzTv: TextView
    private lateinit var dataTv: TextView
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private val dataTick = object : Runnable {
        override fun run() { showData(); ui.postDelayed(this, 2000) }
    }
    private lateinit var schedList: LinearLayout
    private lateinit var liveDot: View
    private val green = Color.parseColor("#22C55E")
    private val red = Color.parseColor("#FF4D4D")
    private val redDim = Color.parseColor("#B05050")
    private var liveNow: Set<String> = emptySet()
    private val liveTick = object : Runnable {
        override fun run() { refreshLive(); ui.postDelayed(this, 60_000) }
    }

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
        Reminders.rescheduleAll(this, matches)
        renderSchedule()
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(tzReceiver, IntentFilter(Intent.ACTION_TIMEZONE_CHANGED))
    }

    override fun onResume() {
        super.onResume()
        renderSchedule()
        maybeRefresh()
        ui.post(dataTick)
        ui.post(liveTick)
    }

    override fun onPause() {
        ui.removeCallbacksAndMessages(null)
        super.onPause()
    }

    /** Refresh at most every 10 min on Wi-Fi, every 60 min on a metered (mobile) connection. */
    private fun maybeRefresh() {
        val p = getSharedPreferences("sched", MODE_PRIVATE)
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val gap = if (cm.isActiveNetworkMetered) 60L else 10L
        val last = p.getLong("lastRefresh", 0)
        if (System.currentTimeMillis() - last < gap * 60_000) return
        p.edit().putLong("lastRefresh", System.currentTimeMillis()).apply()
        io.execute { Schedule.refresh(this)?.let { matches = it; Reminders.rescheduleAll(this, it); runOnUiThread { renderSchedule() } } }
    }

    /** Checks which official channels are streaming (one small request) and updates the green dot. */
    private fun refreshLive() {
        io.execute {
            Channels.liveSet(Channels.all(this).map { it.login })?.let { liveNow = it }
            runOnUiThread { showLiveDot() }
        }
    }

    /** Glowing green dot beside the channel box: the chosen channel is live (for Auto: any official one). */
    private fun showLiveDot() {
        if (!::liveDot.isInitialized) return
        val ch = channelBox.text.toString().trim().lowercase(Locale.ROOT).removePrefix("twitch.tv/").trim('/')
        val on = if (ch.isEmpty() || ch == "auto") liveNow.isNotEmpty() else ch in liveNow
        liveDot.visibility = if (on) View.VISIBLE else View.INVISIBLE
    }

    private fun glowDot(): LayerDrawable {
        val halo = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = dp(11).toFloat()
            colors = intArrayOf(Color.argb(190, 34, 197, 94), Color.argb(0, 34, 197, 94))
        }
        val core = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#4ADE80")) }
        return LayerDrawable(arrayOf(halo, core)).apply { setLayerInset(1, dp(6), dp(6), dp(6), dp(6)) }
    }

    private fun fmtBytes(b: Long): String = when {
        b >= 1_000_000_000 -> String.format(Locale.US, "%.2f GB", b / 1e9)
        else -> String.format(Locale.US, "%.1f MB", b / 1e6)
    }

    private fun showData() {
        val p = getSharedPreferences("data", MODE_PRIVATE)
        val today = if (p.getString("day", "") == java.time.LocalDate.now().toString()) p.getLong("today", 0) else 0L
        dataTv.text = "Data used  " + fmtBytes(p.getLong("session", 0)) + " this session  \u00B7  " +
            fmtBytes(today) + " today"
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
        setDot(if (isPlaying) accent else if (busy) dim else faint, hollow = st.contains("retrying"))
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
                .removePrefix("twitch.tv/").trim('/').ifEmpty { "auto" }
            getSharedPreferences("prefs", MODE_PRIVATE).edit().putString("channel", ch).apply()
            c.sendCustomCommand(SessionCommand(RadioService.CMD_PLAY, Bundle.EMPTY),
                Bundle().apply { putString("channel", ch) })
            playing = true
            playBtn.playing = true
            statusTv.text = "Connecting..."
            setDot(dim)
        }
    }

    /** Drop-down of official channels in many languages; typing any channel name is still allowed. */
    private fun pickChannel() {
        io.execute {
            Channels.discover(this) // remembers streams Riot lists as live, for next time
            Channels.liveSet(Channels.all(this).map { it.login })?.let { liveNow = it }
            runOnUiThread { showChannelDialog() }
        }
    }

    private fun showChannelDialog() {
        val list = Channels.all(this)
        val labels = listOf("Auto \u00B7 first live official stream") + list.map { "${it.label}  (${it.login})" }
        val isLive = listOf(liveNow.isNotEmpty()) + list.map { it.login in liveNow }
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, labels) {
            override fun getView(pos: Int, convert: View?, parent: ViewGroup): View {
                val row = LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                }
                row.addView(View(context).apply {
                    if (isLive[pos]) background = glowDot()
                }, lp(dp(22), dp(22)).apply { rightMargin = dp(10) })
                row.addView(tv(labels[pos], 15f, if (isLive[pos]) green else text0, isLive[pos]))
                return row
            }
        }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Choose a stream")
            .setAdapter(adapter) { _, i -> channelBox.setText(if (i == 0) "auto" else list[i - 1].login) }
            .show()
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
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
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

    private var tab = 0

    /** Back from Schedule returns to Radio; back from Radio leaves the app (audio keeps playing in the service). */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (tab != 0) showTab(0) else super.onBackPressed()
    }

    private fun showTab(i: Int) {
        tab = i
        radioPane.visibility = if (i == 0) View.VISIBLE else View.GONE
        schedPane.visibility = if (i == 1) View.VISIBLE else View.GONE
        tabCells.forEachIndexed { k, t -> t.setTextColor(if (k == i) white else dim) }
        tabLines.forEachIndexed { k, l -> l.setBackgroundColor(if (k == i) accent else line) }
    }

    // ---- UI ----
    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            fitsSystemWindows = true
        }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), 0)
        }
        head.addView(tv("League Radio", 22f, white, true))
        head.addView(tv("Audio only  \u00B7  low data", 13f, dim).apply { setPadding(0, dp(6), 0, 0) })
        root.addView(head, lp(-1, -2))

        // tab bar: two equal cells side by side; underline colour marks the active one, nothing moves
        val tabs = LinearLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0) }
        val cells = ArrayList<TextView>()
        val lines = ArrayList<View>()
        listOf("RADIO", "SCHEDULE").forEachIndexed { i, name ->
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val t = tv(name, 12f, dim, true, 0.12f).apply {
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
            setPadding(dp(24), dp(12), dp(24), dp(20))
        }
        p.addView(View(this), lp(-1, 0, 1f))

        playBtn = PlayButton(this).apply { setOnClickListener { togglePlay() } }
        p.addView(playBtn, lp(dp(144), dp(144)))

        val srow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        dot = View(this)
        srow.addView(dot, lp(dp(8), dp(8)).apply { rightMargin = dp(10) })
        statusTv = tv("Stopped", 16f, text0, true).apply {
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
        }
        srow.addView(statusTv, lp(-2, -2))
        p.addView(srow, lp(-2, dp(24)).apply { topMargin = dp(24) })
        setDot(faint)
        dataTv = tv("", 12f, faint).apply { gravity = Gravity.CENTER }
        p.addView(dataTv, lp(-2, -2).apply { topMargin = dp(6) })

        p.addView(View(this), lp(-1, 0, 1f))

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = shape(panel, 16, line, 1)
            setPadding(dp(16), dp(18), dp(16), dp(20))
        }
        nextTag = tv("NEXT MATCH", 11f, dim, true, 0.12f).apply { gravity = Gravity.CENTER }
        nextTv = tv("", 22f, white, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(8)) }
        nextTime = tv("", 13f, dim).apply { gravity = Gravity.CENTER }
        card.addView(nextTag)
        card.addView(nextTv)
        card.addView(nextTime)
        p.addView(card, lp(-1, -2))

        // stream picker: one rounded field - live dot, twitch.tv/ prefix, editable name, chevron opens the list
        val field = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = shape(panel, 16, line, 1)
            setPadding(dp(8), 0, 0, 0)
        }
        liveDot = View(this).apply { background = glowDot(); visibility = View.INVISIBLE }
        field.addView(liveDot, lp(dp(22), dp(22)))
        field.addView(tv("twitch.tv/", 14f, faint).apply { setPadding(dp(4), 0, 0, 0) })
        channelBox = EditText(this).apply {
            setText(getSharedPreferences("prefs", MODE_PRIVATE).getString("channel", "auto").let { if (it == "lolesports") "auto" else it })
            setTextColor(white)
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine()
            background = null
            setPadding(dp(2), dp(16), dp(8), dp(16))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) = showLiveDot()
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        field.addView(channelBox, lp(0, -2, 1f))
        field.addView(View(this).apply { setBackgroundColor(line) }, lp(dp(1), dp(28)))
        field.addView(Chevron(this, text0).apply { setOnClickListener { pickChannel() } }, lp(dp(52), dp(52)))
        p.addView(field, lp(-1, -2).apply { topMargin = dp(12) })

        val test = tv("Test with any live stream", 13f, dim).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(14), dp(16), dp(4))
            setOnClickListener { testStream() }
        }
        p.addView(test, lp(-2, -2))
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
        tzTv = tv("", 11f, faint).apply { gravity = Gravity.END }
        r2.addView(tv("$year Schedule", 15f, white, true), lp(-2, -2))
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

        segHolder.removeAllViews()
        segHolder.addView(segmented(listOf("Upcoming", "Results", "All"), view) { view = it; renderSchedule() })
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
            val status = m.liveStatus(now)
            val live = status != 0
            val isNext = !nextShown && !m.done && (m.start.isAfter(now) || live)
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
            val canRemind = !m.done && !(m.a == "TBD" && m.b == "TBD")
            if (canRemind) {
                val on = Reminders.isSelected(this, m.id)
                row.addView(tv(if (on) "\uD83D\uDD14" else "\uD83D\uDD15", 15f, if (on) white else faint), lp(dp(30), -2))
                row.setOnClickListener {
                    val now = Reminders.toggle(this, m)
                    android.widget.Toast.makeText(this,
                        if (now) "Reminders on: 1 hour and 5 minutes before ${m.a} vs ${m.b}" else "Reminders off",
                        android.widget.Toast.LENGTH_SHORT).show()
                    renderSchedule()
                }
            } else row.addView(View(this), lp(dp(30), 1))
            row.addView(tv(z.format(timeFmt), 12f, dim), lp(dp(66), -2))
            val tbd = m.a == "TBD" && m.b == "TBD"
            val scored = m.done && m.aw >= 0
            val aWon = m.aw > m.bw
            row.addView(tv(m.a, 15f, if (tbd) faint else if (!scored || aWon) white else dim, !tbd && (!scored || aWon)).apply {
                gravity = Gravity.END
            }, lp(0, -2, 1f))
            row.addView(tv(if (scored) "${m.aw} – ${m.bw}" else "vs", if (scored) 15f else 12f,
                if (scored) white else faint, scored).apply { gravity = Gravity.CENTER }, lp(dp(58), -2))
            row.addView(tv(m.b, 15f, if (tbd) faint else if (!scored || !aWon) white else dim, !tbd && (!scored || !aWon)), lp(0, -2, 1f))
            row.addView(tv(if (!live) "Bo${m.bestOf}" else if (status == 1) "LIVE" else "LIVE?", 11f,
                if (!live) faint else if (status == 1) red else redDim, live).apply {
                gravity = Gravity.END
            }, lp(dp(44), -2))
            schedList.addView(row, lp(-1, -2))
        }
        if (rows.any { it.a == "TBD" && it.b == "TBD" }) schedList.addView(
            tv("TBD = teams not decided yet. Swiss pairings are set after each round and knockout teams after " +
                "Swiss ends. Names fill in automatically.", 11f, faint).apply { setPadding(dp(12), dp(20), dp(12), 0) })
        if (rows.isEmpty()) schedList.addView(tv("Nothing to show yet.", 14f, dim).apply {
            setPadding(dp(12), dp(28), 0, 0)
        })

        // "next match" card on the radio tab: first unfinished match overall
        val n = matches.firstOrNull { !it.done && (it.start.isAfter(now) || it.liveStatus(now) != 0) }
        if (n != null) {
            val st = n.liveStatus(now)
            val live = st != 0
            nextTag.text = if (st == 1) "● LIVE NOW" else if (st == 2) "● LIVE NOW?" else "NEXT MATCH"
            nextTag.setTextColor(if (st == 1) red else if (st == 2) redDim else dim)
            nextTv.text = android.text.SpannableString("${n.a}   vs   ${n.b}").apply {
                val v = n.a.length + 3
                setSpan(android.text.style.ForegroundColorSpan(faint), v, v + 2, 0)
                setSpan(android.text.style.RelativeSizeSpan(0.65f), v, v + 2, 0)
            }
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
