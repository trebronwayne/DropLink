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
import io.github.trebronwayne.droplink.core.CrashLog
import io.github.trebronwayne.droplink.core.Discovery
import io.github.trebronwayne.droplink.core.HostingOptions
import io.github.trebronwayne.droplink.core.NodeIdentity
import io.github.trebronwayne.droplink.core.PairRequest
import io.github.trebronwayne.droplink.core.Pairing
import io.github.trebronwayne.droplink.core.ServerStats
import io.github.trebronwayne.droplink.core.TlsIdentity
import io.github.trebronwayne.droplink.server.DropLinkServer
import io.ktor.server.engine.EmbeddedServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean

/** User-started foreground service that hosts the DropLink server (web client and file API). */
class LocalServerService : Service() {
    private val starting = AtomicBoolean(false)
    @Volatile private var server: EmbeddedServer<*, *>? = null

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopHosting()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            // Must be called promptly after startForegroundService().
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (e: Throwable) {
            CrashLog.record("Could not start the foreground service", e)
            _errorState.value = "Android would not start the hosting service: ${e.javaClass.simpleName}: ${e.message ?: "no details"}"
            stopSelf()
            return START_NOT_STICKY
        }
        startHosting()
        return START_STICKY
    }

    private fun startHosting() {
        if (!starting.compareAndSet(false, true)) return
        _errorState.value = null
        Thread {
            var stage = "checking the port"
            try {
                val useHttp = HostingOptions.useHttp(applicationContext)
                // Probe first so a busy port fails visibly instead of silently.
                ServerSocket().use { probe ->
                    probe.reuseAddress = true
                    probe.bind(InetSocketAddress(PORT))
                }
                stage = "loading pairing data"
                Pairing.init(applicationContext)
                var tls: TlsIdentity.Material? = null
                if (!useHttp) {
                    stage = "creating the security certificate"
                    tls = TlsIdentity.load(applicationContext)
                }
                stage = "creating the server"
                val engine = DropLinkServer.create(applicationContext, PORT, tls)
                stage = "starting the server"
                engine.start(wait = false)
                server = engine
                Pairing.onRequest = { request -> notifyPairing(request) }
                _hostingState.value = true
                ServerStats.start()
                try {
                    Discovery.advertise(
                        applicationContext, NodeIdentity.getOrCreateNodeId(applicationContext),
                        tls?.fingerprint.orEmpty(), Discovery.displayName(), PORT
                    )
                } catch (e: Throwable) {
                    CrashLog.record("Discovery could not start (hosting continues)", e)
                }
            } catch (e: Throwable) {
                CrashLog.record("Server failed while $stage", e)
                _errorState.value = "Could not start while $stage: ${e.javaClass.simpleName}: ${e.message ?: "no details"}"
                stopHosting()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }.apply { name = "DropLink-ServerStart" }.start()
    }

    private fun stopHosting() {
        _hostingState.value = false
        ServerStats.stop()
        Pairing.onRequest = null
        Pairing.clearRequests()
        try { Discovery.stopAdvertising() } catch (_: Throwable) { }
        getSystemService(NotificationManager::class.java).cancel(PAIRING_NOTIFICATION_ID)
        val engine = server
        server = null
        starting.set(false)
        if (engine != null) {
            Thread { try { engine.stop(300, 1000) } catch (_: Throwable) { } }.apply { name = "DropLink-ServerStop" }.start()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "DropLink hosting", NotificationManager.IMPORTANCE_LOW))
            manager.createNotificationChannel(NotificationChannel(PAIRING_CHANNEL_ID, "Pairing requests", NotificationManager.IMPORTANCE_HIGH))
        }
    }

    private fun notifyPairing(request: PairRequest) {
        val openApp = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, PAIRING_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Pairing request")
            .setContentText("${request.deviceName} wants to connect. Code ${request.code}. Open DropLink to approve.")
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            getSystemService(NotificationManager::class.java).notify(PAIRING_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) { }
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
        private const val PAIRING_CHANNEL_ID = "droplink_pairing"
        private const val PAIRING_NOTIFICATION_ID = 47832

        private val _hostingState = MutableStateFlow(false)
        private val _errorState = MutableStateFlow<String?>(null)

        /** True only while the server is actually running. */
        val hostingState: StateFlow<Boolean> = _hostingState.asStateFlow()

        /** Human-readable reason the last start attempt failed, or null. */
        val errorState: StateFlow<String?> = _errorState.asStateFlow()
    }
}
