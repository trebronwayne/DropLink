package io.github.trebronwayne.droplink.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.github.trebronwayne.droplink.MainActivity
import io.github.trebronwayne.droplink.server.DropLinkServer
import io.github.trebronwayne.droplink.server.ServerAccess
import io.ktor.server.engine.EmbeddedServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean

/** User-started foreground service that hosts the DropLink HTTP server (web client, file API). */
class LocalServerService : Service() {
    private val starting = AtomicBoolean(false)
    @Volatile private var server: EmbeddedServer<*, *>? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopHosting()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // Must be called promptly after startForegroundService(); retracted if startup fails.
                startForeground(NOTIFICATION_ID, buildNotification())
                startHosting()
            }
        }
        return START_STICKY
    }

    private fun startHosting() {
        if (!starting.compareAndSet(false, true)) return
        Thread {
            try {
                // Probe first so a busy port fails visibly instead of silently.
                ServerSocket().use { probe ->
                    probe.reuseAddress = true
                    probe.bind(InetSocketAddress(PORT))
                }
                val access = ServerAccess(ServerAccess.newPin())
                val engine = DropLinkServer.create(applicationContext, PORT, access)
                engine.start(wait = false)
                server = engine
                sessionAccess = access
                _pinState.value = access.pin
                _hostingState.value = true
            } catch (_: Throwable) {
                stopHosting()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }.apply { name = "DropLink-ServerStart" }.start()
    }

    private fun stopHosting() {
        _hostingState.value = false
        _pinState.value = null
        sessionAccess?.sessions?.clear()
        sessionAccess = null
        val engine = server
        server = null
        starting.set(false)
        if (engine != null) {
            Thread { try { engine.stop(300, 1000) } catch (_: Exception) { } }.apply { name = "DropLink-ServerStop" }.start()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "DropLink hosting", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, LocalServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("DropLink is hosting")
            .setContentText("Sharing selected folders on port $PORT")
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
            .build()
    }

    override fun onDestroy() {
        stopHosting()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val PORT = 47831
        const val ACTION_START = "io.github.trebronwayne.droplink.START_HOSTING"
        const val ACTION_STOP = "io.github.trebronwayne.droplink.STOP_HOSTING"
        private const val CHANNEL_ID = "droplink_hosting"
        private const val NOTIFICATION_ID = 47831

        @Volatile private var sessionAccess: ServerAccess? = null
        private val _hostingState = MutableStateFlow(false)
        private val _pinState = MutableStateFlow<String?>(null)

        /** True only while the server is actually running. */
        val hostingState: StateFlow<Boolean> = _hostingState.asStateFlow()

        /** The access code clients must enter; null when not hosting. Changes every time hosting starts. */
        val pinState: StateFlow<String?> = _pinState.asStateFlow()
    }
}
