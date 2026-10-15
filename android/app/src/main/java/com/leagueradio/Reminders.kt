package com.leagueradio

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Matches the user picked to watch; an alert fires 60 and 5 minutes before the scheduled start. */
object Reminders {
    private val OFFSETS = listOf(60, 5)
    private const val CHANNEL = "match_reminders"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("reminders", Context.MODE_PRIVATE)

    fun selected(ctx: Context): Set<String> = prefs(ctx).getStringSet("ids", emptySet()) ?: emptySet()

    fun isSelected(ctx: Context, id: String) = id in selected(ctx)

    fun toggle(ctx: Context, m: Match): Boolean {
        val ids = selected(ctx).toMutableSet()
        val on = ids.add(m.id)
        if (!on) ids.remove(m.id)
        prefs(ctx).edit().putStringSet("ids", ids).apply()
        if (on) schedule(ctx, m) else cancel(ctx, m.id)
        return on
    }

    private fun pending(ctx: Context, m: Match?, id: String, offset: Int): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java).setAction("com.leagueradio.REMIND")
            .setData(Uri.parse("match://$id/$offset"))
        if (m != null) i.putExtra("a", m.a).putExtra("b", m.b).putExtra("start", m.start.toEpochMilli())
            .putExtra("offset", offset).putExtra("id", id)
        return PendingIntent.getBroadcast(ctx, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun cancel(ctx: Context, id: String) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        OFFSETS.forEach { am.cancel(pending(ctx, null, id, it)) }
    }

    fun schedule(ctx: Context, m: Match) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val now = System.currentTimeMillis()
        for (o in OFFSETS) {
            val at = m.start.toEpochMilli() - o * 60_000L
            if (at <= now) continue
            val pi = pending(ctx, m, m.id, o)
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } catch (e: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        }
    }

    /** Re-arms every selected match (after a schedule refresh, reboot or app update) and drops finished ones. */
    fun rescheduleAll(ctx: Context, matches: List<Match>) {
        val ids = selected(ctx)
        if (ids.isEmpty()) return
        val byId = matches.associateBy { it.id }
        val keep = ids.filter { byId[it]?.done != true }.toSet()
        for (id in ids) {
            val m = byId[id]
            if (m == null || id !in keep) cancel(ctx, id) else schedule(ctx, m)
        }
        if (keep != ids) prefs(ctx).edit().putStringSet("ids", keep).apply()
    }

    fun notify(ctx: Context, i: Intent) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Match reminders", NotificationManager.IMPORTANCE_HIGH))
        val start = Instant.ofEpochMilli(i.getLongExtra("start", 0))
        val time = start.atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))
        val offset = i.getIntExtra("offset", 60)
        val whenTxt = if (offset >= 60) "1 hour" else "$offset minutes"
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = android.app.Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("${i.getStringExtra("a")} vs ${i.getStringExtra("b")} in $whenTxt")
            .setContentText("Starts at $time your time")
            .setContentIntent(open).setAutoCancel(true).build()
        nm.notify(((i.getStringExtra("id") ?: "") + offset).hashCode(), n)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        when (i.action) {
            "com.leagueradio.REMIND" -> Reminders.notify(ctx, i)
            else -> Reminders.rescheduleAll(ctx, Schedule.stored(ctx)) // boot / app update
        }
    }
}
