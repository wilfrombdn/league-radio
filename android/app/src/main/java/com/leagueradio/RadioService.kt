package com.leagueradio

import android.content.Intent
import android.net.TrafficStats
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executors

/** Plays the audio-only stream in the background with a media notification. */
class RadioService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var channel: String? = null
    private var gen = 0
    private var lastRx = -1L
    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            val rx = TrafficStats.getUidRxBytes(Process.myUid())
            if (rx >= 0) {
                if (channel != null && lastRx in 0..rx) addUsage(rx - lastRx)
                lastRx = rx
            }
            ticker.postDelayed(this, 3000)
        }
    }

    /** Counts the bytes this app downloads while a channel is active (session + today). */
    private fun addUsage(delta: Long) {
        val p = getSharedPreferences("data", MODE_PRIVATE)
        val day = java.time.LocalDate.now().toString()
        val today = if (p.getString("day", "") == day) p.getLong("today", 0) else 0L
        p.edit().putString("day", day).putLong("today", today + delta)
            .putLong("session", p.getLong("session", 0) + delta).apply()
    }

    override fun onCreate() {
        super.onCreate()
        ticker.post(tick)
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = retryLater("Stream error")
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) retryLater("Stream ended")
            }
        })
        session = MediaSession.Builder(this, player).setCallback(object : MediaSession.Callback {
            override fun onConnect(s: MediaSession, c: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                val cmds = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                    .add(SessionCommand(CMD_PLAY, Bundle.EMPTY)).add(SessionCommand(CMD_STOP, Bundle.EMPTY)).build()
                return MediaSession.ConnectionResult.accept(cmds, MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
            }

            override fun onCustomCommand(
                s: MediaSession, c: MediaSession.ControllerInfo, cmd: SessionCommand, args: Bundle
            ): ListenableFuture<SessionResult> {
                when (cmd.customAction) {
                    CMD_PLAY -> start(args.getString("channel") ?: "lolesports")
                    CMD_STOP -> stop()
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
        }).build()
    }

    private fun status(text: String) {
        session?.setSessionExtras(Bundle().apply { putString("status", text) })
    }

    private fun start(ch: String) {
        channel = ch
        getSharedPreferences("data", MODE_PRIVATE).edit().putLong("session", 0).apply()
        gen++
        main.removeCallbacksAndMessages(null)
        resolve()
    }

    private fun stop() {
        channel = null
        gen++
        main.removeCallbacksAndMessages(null)
        player.stop()
        player.clearMediaItems()
        status("Stopped")
    }

    private fun resolve() {
        val ch = channel ?: return
        val g = gen
        status("Connecting...")
        io.execute {
            try {
                val url = Twitch.audioUrl(ch)
                main.post {
                    if (g != gen) return@post
                    player.setMediaItem(MediaItem.Builder().setUri(url).setMediaMetadata(
                        MediaMetadata.Builder().setTitle("League Radio").setArtist("twitch.tv/$ch").build()).build())
                    player.prepare()
                    player.play()
                    status("Playing audio: $ch")
                }
            } catch (e: Exception) {
                main.post { if (g == gen) retryLater(if (e is OfflineException) "Offline" else "Network error") }
            }
        }
    }

    private fun retryLater(msg: String) {
        if (channel == null) return
        val g = gen
        status("$msg - retrying in 45s")
        main.postDelayed({ if (g == gen) resolve() }, 45_000)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady) stopSelf()
    }

    override fun onDestroy() {
        session?.release()
        player.release()
        io.shutdownNow()
        ticker.removeCallbacksAndMessages(null)
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        const val CMD_PLAY = "lr.play"
        const val CMD_STOP = "lr.stop"
    }
}
