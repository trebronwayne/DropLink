package io.github.trebronwayne.droplink.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.trebronwayne.droplink.MainActivity
import io.github.trebronwayne.droplink.R
import io.github.trebronwayne.droplink.core.CrashLog
import io.github.trebronwayne.droplink.core.Discovery
import io.github.trebronwayne.droplink.core.EventLog
import io.github.trebronwayne.droplink.core.HostingOptions
import io.github.trebronwayne.droplink.core.NodeIdentity
import io.github.trebronwayne.droplink.core.PairRequest
import io.github.trebronwayne.droplink.core.Pairing
import io.github.trebronwayne.droplink.core.ServerStats
import io.github.trebronwayne.droplink.core.TlsIdentity
import io.github.trebronwayne.droplink.server.DropLinkServer
import io.github.trebronwayne.droplink.server.TlsFrontend
import io.ktor.server.engine.EmbeddedServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/** User-started foreground service that hosts the DropLink server (web client and file API). */
class LocalServerService : Service() {
    private val starting = AtomicBoolean(false)
    @Volatile private var server: EmbeddedServer<*, *>? = null
    @Volatile private var frontend: TlsFrontend? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var screenReceiver: BroadcastReceiver? = null
    @Volatile private var watchdog: Thread? = null

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        EventLog.init(this)
        EventLog.add("Hosting service created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) EventLog.add("Android restarted the hosting service after it had been stopped")
        if (intent?.action == ACTION_STOP) {
            EventLog.add("Stop requested by you")
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
                val backendPort = if (useHttp) PORT else freePort()
                val engine = DropLinkServer.create(
                    applicationContext,
                    bindHost = if (useHttp) "0.0.0.0" else "127.0.0.1",
                    port = backendPort,
                    secureCookies = !useHttp
                )
                stage = "starting the server"
                engine.start(wait = false)
                server = engine
                if (tls != null) {
                    stage = "starting the secure (HTTPS) front end"
                    val front = TlsFrontend(tls, PORT, backendPort)
                    front.start()
                    frontend = front
                }
                Pairing.onRequest = { request -> notifyPairing(request) }
                _hostingState.value = true
                ServerStats.start()
                acquireLocks()
                registerScreenReceiver()
                startWatchdog()
                EventLog.add("Hosting started on port $PORT (${if (useHttp) "HTTP testing mode" else "HTTPS"})")
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
        val wasHosting = _hostingState.value
        _hostingState.value = false
        watchdog?.interrupt()
        watchdog = null
        unregisterScreenReceiver()
        if (wasHosting) EventLog.add("Hosting stopped")
        ServerStats.stop()
        releaseLocks()
        Pairing.onRequest = null
        Pairing.clearRequests()
        try { Discovery.stopAdvertising() } catch (_: Throwable) { }
        getSystemService(NotificationManager::class.java).cancel(PAIRING_NOTIFICATION_ID)
        try { frontend?.stop() } catch (_: Throwable) { }
        frontend = null
        val engine = server
        server = null
        starting.set(false)
        if (engine != null) {
            Thread { try { engine.stop(300, 1000) } catch (_: Throwable) { } }.apply { name = "DropLink-ServerStop" }.start()
        }
    }

    // Keep the CPU and the Wi-Fi radio awake while hosting, otherwise Android may pause transfers when the screen is off.
    private fun acquireLocks() {
        try {
            val power = getSystemService(PowerManager::class.java)
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DropLink:hosting").apply {
                setReferenceCounted(false)
                acquire()
            }
            val wifi = applicationContext.getSystemService(WifiManager::class.java)
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifi.createWifiLock(mode, "DropLink:hosting").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Throwable) {
            CrashLog.record("Could not hold the wake locks (hosting continues)", e)
        }
    }

    private fun releaseLocks() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Throwable) { }
        try { wifiLock?.let { if (it.isHeld) it.release() } } catch (_: Throwable) { }
        wakeLock = null
        wifiLock = null
    }

    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> EventLog.add("Screen turned off")
                    Intent.ACTION_SCREEN_ON -> EventLog.add("Screen turned on")
                }
            }
        }
        screenReceiver = receiver
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } catch (_: Throwable) {
            screenReceiver = null
        }
    }

    private fun unregisterScreenReceiver() {
        val receiver = screenReceiver ?: return
        screenReceiver = null
        try { unregisterReceiver(receiver) } catch (_: Throwable) { }
    }

    /**
     * Watches the server from the inside: it notices when Android freezes the app (the beat arrives much later than
     * planned), and if the server stops answering on its own port it restarts it.
     */
    private fun startWatchdog() {
        watchdog?.interrupt()
        val thread = Thread {
            var lastBeat = SystemClock.elapsedRealtime()
            var failures = 0
            try {
                while (_hostingState.value) {
                    Thread.sleep(WATCHDOG_INTERVAL_MS)
                    if (!_hostingState.value) break
                    val now = SystemClock.elapsedRealtime()
                    val gap = now - lastBeat
                    lastBeat = now
                    if (gap > WATCHDOG_INTERVAL_MS * 3) {
                        EventLog.add("DropLink was frozen or throttled by Android for about ${gap / 1000} s (expected ${WATCHDOG_INTERVAL_MS / 1000} s)")
                    }
                    if (loopbackAnswers()) {
                        failures = 0
                    } else {
                        failures += 1
                        EventLog.add("Server did not answer its own check ($failures)")
                        if (failures >= 2) {
                            EventLog.add("Restarting the server")
                            restartServer()
                            return@Thread
                        }
                    }
                }
            } catch (_: InterruptedException) {
                // Hosting stopped.
            }
        }
        thread.name = "DropLink-Watchdog"
        thread.isDaemon = true
        watchdog = thread
        thread.start()
    }

    private fun loopbackAnswers(): Boolean {
        return try {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", PORT), 2_000) }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun restartServer() {
        stopHosting()
        Thread.interrupted() // stopHosting interrupts this thread; clear that so the pause below works
        try { Thread.sleep(1_500) } catch (_: InterruptedException) { }
        startHosting()
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

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
            .setSmallIcon(R.drawable.ic_stat_droplink)
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
            .setSmallIcon(R.drawable.ic_stat_droplink)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentTitle("DropLink is hosting")
            .setContentText("Sharing selected folders on port $PORT")
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
            .build()
    }

    override fun onDestroy() {
        if (_hostingState.value) {
            EventLog.add("Android stopped the hosting service (not you)")
            CrashLog.record("Hosting service was stopped by Android, not by you. Check battery and background settings.")
        }
        stopHosting()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        EventLog.add("App was swiped away from recent apps (hosting continues)")
        super.onTaskRemoved(rootIntent)
    }

    override fun onTrimMemory(level: Int) {
        EventLog.add("Android asked the app to free memory (level $level)")
        super.onTrimMemory(level)
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
        private const val WATCHDOG_INTERVAL_MS = 15_000L

        private val _hostingState = MutableStateFlow(false)
        private val _errorState = MutableStateFlow<String?>(null)

        /** True only while the server is actually running. */
        val hostingState: StateFlow<Boolean> = _hostingState.asStateFlow()

        /** Human-readable reason the last start attempt failed, or null. */
        val errorState: StateFlow<String?> = _errorState.asStateFlow()
    }
}
