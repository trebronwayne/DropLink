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
import android.os.Process
import java.net.HttpURLConnection
import java.net.URL
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import io.github.trebronwayne.droplink.core.NotificationSound
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
import io.github.trebronwayne.droplink.core.ReceivedFile
import io.github.trebronwayne.droplink.core.ReceivedFiles
import io.github.trebronwayne.droplink.core.UploadRequest
import io.github.trebronwayne.droplink.core.Uploads
import io.github.trebronwayne.droplink.core.formatBytes
import io.github.trebronwayne.droplink.core.ServerStats
import io.github.trebronwayne.droplink.core.TlsIdentity
import io.github.trebronwayne.droplink.server.DropLinkServer
import io.github.trebronwayne.droplink.server.StaticAssets
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
    @Volatile private var wifiLockHighPerf: WifiManager.WifiLock? = null
    @Volatile private var activeBackendPort: Int = PORT

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        EventLog.init(this)
        EventLog.add("Hosting service created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) EventLog.add("Android restarted the hosting service after it had been stopped")
        if (intent?.action == ACTION_KEEPALIVE) {
            // The alarm fired: make sure we are still foreground and still hosting.
            scheduleKeepAlive()
        }
        if (intent?.action == ACTION_STOP) {
            EventLog.add("Stop requested by you")
            stopHosting()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            // Must be called promptly after startForegroundService().
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, buildNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        } catch (e: Throwable) {
            CrashLog.record("Could not start the foreground service", e)
            _errorState.value = "Android would not start the hosting service: ${e.javaClass.simpleName}: ${e.message ?: "no details"}"
            stopSelf()
            return START_NOT_STICKY
        }
        startHosting()
        scheduleKeepAlive()
        return START_STICKY
    }

    private fun startHosting() {
        if (!starting.compareAndSet(false, true)) return
        _errorState.value = null
        Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND)
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
                ReceivedFiles.init(applicationContext)
                io.github.trebronwayne.droplink.core.Presence.init(applicationContext)
                io.github.trebronwayne.droplink.core.Partials.init(applicationContext)
                var tls: TlsIdentity.Material? = null
                if (!useHttp) {
                    stage = "creating the security certificate"
                    tls = TlsIdentity.load(applicationContext)
                }
                stage = "creating the server"
                val backendPort = if (useHttp) PORT else freePort()
                activeBackendPort = backendPort
                StaticAssets.warm(applicationContext)
                val engine = DropLinkServer.create(
                    applicationContext,
                    bindHost = if (useHttp) "0.0.0.0" else "127.0.0.1",
                    port = backendPort,
                    secureCookies = !useHttp,
                    publicPort = PORT
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
                Uploads.onRequest = { request -> notifyUploadRequest(request) }
                Uploads.onBatchDone = { from, files -> notifyReceived(from, files) }
                _hostingState.value = true
                ServerStats.start()
                acquireLocks()
                registerScreenReceiver()
                startWatchdog()
                scheduleKeepAlive()
                EventLog.add("Standing with Android: ${standing()}")
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

    private fun stopHosting(keepState: Boolean = false) {
        val wasHosting = _hostingState.value
        // During an internal restart the screen keeps showing "hosting", so the buttons do not flicker.
        if (!keepState) _hostingState.value = false
        watchdog?.interrupt()
        watchdog = null
        cancelKeepAlive()
        unregisterScreenReceiver()
        if (wasHosting && !keepState) EventLog.add("Hosting stopped")
        ServerStats.stop()
        releaseLocks()
        io.github.trebronwayne.droplink.core.SiteHost.endAllSessions()
        Pairing.onRequest = null
        Pairing.clearRequests()
        Uploads.onRequest = null
        Uploads.onBatchDone = null
        Uploads.clearAll()
        io.github.trebronwayne.droplink.core.Outbox.clearAll()
        try { Discovery.stopAdvertising() } catch (_: Throwable) { }
        getSystemService(NotificationManager::class.java).apply {
            cancel(PAIRING_NOTIFICATION_ID)
            cancel(UPLOAD_NOTIFICATION_ID)
        }
        try { frontend?.stop() } catch (_: Throwable) { }
        frontend = null
        val engine = server
        server = null
        starting.set(false)
        if (engine != null) {
            Thread { try { engine.stop(300, 1000) } catch (_: Throwable) { } }.apply { name = "DropLink-ServerStop" }.start()
        }
    }

    // Keep the CPU and the Wi-Fi radio awake while hosting.
    // WIFI_MODE_FULL_LOW_LATENCY only takes effect while the app is on screen, so it alone is useless in the background.
    // The high-performance lock is what keeps the radio out of power-save while another app is in front or the screen is off.
    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        try {
            val power = getSystemService(PowerManager::class.java)
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DropLink:hosting").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Throwable) {
            CrashLog.record("Could not hold the CPU wake lock (hosting continues)", e)
        }
        try {
            val wifi = applicationContext.getSystemService(WifiManager::class.java)
            wifiLockHighPerf = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "DropLink:hosting-perf").apply {
                setReferenceCounted(false)
                acquire()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "DropLink:hosting-latency").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (e: Throwable) {
            CrashLog.record("Could not hold the Wi-Fi lock (hosting continues)", e)
        }
    }

    private fun releaseLocks() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Throwable) { }
        try { wifiLock?.let { if (it.isHeld) it.release() } } catch (_: Throwable) { }
        try { wifiLockHighPerf?.let { if (it.isHeld) it.release() } } catch (_: Throwable) { }
        wakeLock = null
        wifiLock = null
        wifiLockHighPerf = null
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
            Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND)
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
                        EventLog.add("DropLink was frozen or throttled by Android for about ${gap / 1000} s (expected ${WATCHDOG_INTERVAL_MS / 1000} s). ${standing()}")
                        failures = 0
                        continue // the server was frozen too; judge it on the next beat, not this one
                    }
                    if (loopbackAnswers()) {
                        failures = 0
                    } else {
                        failures += 1
                        EventLog.add("Server did not answer its own check ($failures)")
                        if (failures >= 3) {
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

    /**
     * Sends a real HTTP request to the engine and checks for "200", because a frozen server still accepts TCP connections.
     * A raw socket is used on purpose: Android refuses plain http:// URLs, even to 127.0.0.1, which made every check fail.
     */
    private fun loopbackAnswers(): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", activeBackendPort), 2_000)
                socket.soTimeout = 4_000
                socket.getOutputStream().apply {
                    write("GET /health HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".toByteArray())
                    flush()
                }
                val line = socket.getInputStream().bufferedReader().readLine().orEmpty()
                line.startsWith("HTTP/1.") && line.contains(" 200")
            }
        } catch (_: Exception) {
            false
        }
    }

    /** What Android currently thinks of this app; written to the log whenever it gets frozen, so the cause is visible. */
    private fun standing(): String {
        val parts = ArrayList<String>()
        try {
            val info = android.app.ActivityManager.RunningAppProcessInfo()
            android.app.ActivityManager.getMyMemoryState(info)
            parts += "process importance ${info.importance} (125 = foreground service, 400+ = cached)"
        } catch (_: Throwable) { }
        try { parts += "battery exemption ${getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)}" } catch (_: Throwable) { }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try { parts += "background restricted ${getSystemService(android.app.ActivityManager::class.java).isBackgroundRestricted}" } catch (_: Throwable) { }
            try { parts += "standby bucket ${getSystemService(android.app.usage.UsageStatsManager::class.java).appStandbyBucket} (10 active, 20 working, 30 frequent, 40 rare, 50 restricted)" } catch (_: Throwable) { }
        }
        try { parts += "Battery Saver ${if (getSystemService(PowerManager::class.java).isPowerSaveMode) "ON (Android will pause background work)" else "off"}" } catch (_: Throwable) { }
        parts += "Android ${Build.VERSION.SDK_INT}, ${Build.MANUFACTURER}"
        return parts.joinToString(", ")
    }

    /**
     * Inexact alarms are allowed to fire in Doze. Each one re-enters the service, which keeps it marked as active
     * and lets the watchdog verify the server even if the watchdog thread itself was paused.
     */
    private fun scheduleKeepAlive() {
        try {
            val alarm = getSystemService(android.app.AlarmManager::class.java)
            val pi = PendingIntent.getService(
                this, 3, Intent(this, LocalServerService::class.java).setAction(ACTION_KEEPALIVE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarm.setAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + KEEPALIVE_MS, pi)
        } catch (_: Throwable) { }
    }

    private fun cancelKeepAlive() {
        try {
            val alarm = getSystemService(android.app.AlarmManager::class.java)
            alarm.cancel(PendingIntent.getService(
                this, 3, Intent(this, LocalServerService::class.java).setAction(ACTION_KEEPALIVE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))
        } catch (_: Throwable) { }
    }

    private fun restartServer() {
        stopHosting(keepState = true)
        Thread.interrupted() // stopHosting interrupts this thread; clear that so the pause below works
        try { Thread.sleep(1_500) } catch (_: InterruptedException) { }
        startHosting()
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "DropLink hosting", NotificationManager.IMPORTANCE_LOW))
            // A channel's sound cannot be changed after it exists, so the "Notification sound" switch in Settings picks
            // between a channel that plays the DropLink sound and an otherwise identical silent one.
            manager.deleteNotificationChannel(LEGACY_PAIRING_CHANNEL_ID)
            manager.deleteNotificationChannel(LEGACY_RECEIVED_CHANNEL_ID)
            manager.createNotificationChannel(soundChannel(REQUESTS_SOUND_ID, "Requests (DropLink sound)", NotificationManager.IMPORTANCE_HIGH, true))
            manager.createNotificationChannel(soundChannel(REQUESTS_QUIET_ID, "Requests (silent)", NotificationManager.IMPORTANCE_HIGH, false))
            manager.createNotificationChannel(soundChannel(RECEIVED_SOUND_ID, "Received files (DropLink sound)", NotificationManager.IMPORTANCE_DEFAULT, true))
            manager.createNotificationChannel(soundChannel(RECEIVED_QUIET_ID, "Received files (silent)", NotificationManager.IMPORTANCE_DEFAULT, false))
        }
    }

    private fun soundChannel(id: String, name: String, importance: Int, withSound: Boolean): NotificationChannel =
        NotificationChannel(id, name, importance).apply {
            if (withSound) setSound(NotificationSound.uri(applicationContext), NotificationSound.attributes) else setSound(null, null)
        }

    private fun soundOn(): Boolean = HostingOptions.notificationSound(applicationContext)
    private fun requestsChannelId(): String = if (soundOn()) REQUESTS_SOUND_ID else REQUESTS_QUIET_ID
    private fun receivedChannelId(): String = if (soundOn()) RECEIVED_SOUND_ID else RECEIVED_QUIET_ID

    private fun notifyPairing(request: PairRequest) {
        val openApp = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, requestsChannelId())
            .setSmallIcon(R.drawable.ic_stat_droplink)
            .setContentTitle("Pairing request")
            .setContentText("${request.label} wants to connect. Code ${request.code}. Open DropLink to approve.")
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            getSystemService(NotificationManager::class.java).notify(PAIRING_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) { }
    }

    /** Someone wants to send files. Like pairing, the answer is given inside the app, never from the lock screen. */
    private fun notifyUploadRequest(request: UploadRequest) {
        val openApp = PendingIntent.getActivity(
            this, 4, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val count = request.files.size
        val notification = NotificationCompat.Builder(this, requestsChannelId())
            .setSmallIcon(R.drawable.ic_stat_droplink)
            .setContentTitle("Files incoming")
            .setContentText("${request.deviceName} wants to send $count file${if (count == 1) "" else "s"} (${formatBytes(request.totalBytes)}). Open DropLink to approve.")
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            getSystemService(NotificationManager::class.java).notify(UPLOAD_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) { }
    }

    private fun notifyReceived(from: String, files: List<ReceivedFile>) {
        val openApp = PendingIntent.getActivity(
            this, 5, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (files.size == 1) "${files[0].name} from $from. Saved in ${ReceivedFiles.LOCATION}."
        else "${files.size} files from $from. Saved in ${ReceivedFiles.LOCATION}."
        val notification = NotificationCompat.Builder(this, receivedChannelId())
            .setSmallIcon(R.drawable.ic_stat_droplink)
            .setContentTitle("Received ${if (files.size == 1) "a file" else "${files.size} files"}")
            .setContentText(text)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        try {
            getSystemService(NotificationManager::class.java).notify(RECEIVED_NOTIFICATION_ID, notification)
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
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
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
        if (_hostingState.value) scheduleKeepAlive()
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
        const val ACTION_KEEPALIVE = "io.github.trebronwayne.droplink.KEEPALIVE"
        private const val CHANNEL_ID = "droplink_hosting"
        private const val NOTIFICATION_ID = 47831
        private const val LEGACY_PAIRING_CHANNEL_ID = "droplink_pairing"
        private const val LEGACY_RECEIVED_CHANNEL_ID = "droplink_received"
        private const val REQUESTS_SOUND_ID = "droplink_requests_sound"
        private const val REQUESTS_QUIET_ID = "droplink_requests_quiet"
        private const val RECEIVED_SOUND_ID = "droplink_received_sound"
        private const val RECEIVED_QUIET_ID = "droplink_received_quiet"
        private const val PAIRING_NOTIFICATION_ID = 47832
        private const val UPLOAD_NOTIFICATION_ID = 47833
        private const val RECEIVED_NOTIFICATION_ID = 47834
        private const val WATCHDOG_INTERVAL_MS = 10_000L
        private const val KEEPALIVE_MS = 4 * 60_000L

        private val _hostingState = MutableStateFlow(false)
        private val _errorState = MutableStateFlow<String?>(null)

        /** True only while the server is actually running. */
        val hostingState: StateFlow<Boolean> = _hostingState.asStateFlow()

        /** Human-readable reason the last start attempt failed, or null. */
        val errorState: StateFlow<String?> = _errorState.asStateFlow()
    }
}
