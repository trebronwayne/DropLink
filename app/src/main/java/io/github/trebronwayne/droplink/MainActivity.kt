package io.github.trebronwayne.droplink

import android.Manifest
import android.content.ActivityNotFoundException
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import android.app.DownloadManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import io.github.trebronwayne.droplink.core.CrashLog
import io.github.trebronwayne.droplink.core.DiscoveredNode
import io.github.trebronwayne.droplink.core.Discovery
import io.github.trebronwayne.droplink.core.EventLog
import io.github.trebronwayne.droplink.core.HostingOptions
import io.github.trebronwayne.droplink.core.NetworkAddresses
import io.github.trebronwayne.droplink.core.NodeIdentity
import io.github.trebronwayne.droplink.core.PairStatus
import io.github.trebronwayne.droplink.core.NotificationSound
import io.github.trebronwayne.droplink.core.Pairing
import io.github.trebronwayne.droplink.core.Presence
import io.github.trebronwayne.droplink.core.Outbox
import io.github.trebronwayne.droplink.core.OutFile
import io.github.trebronwayne.droplink.core.ReceivedFile
import io.github.trebronwayne.droplink.ui.PickedFile
import io.github.trebronwayne.droplink.ui.SendUi
import io.github.trebronwayne.droplink.core.ReceivedFiles
import io.github.trebronwayne.droplink.core.Uploads
import io.github.trebronwayne.droplink.core.SelfTest
import io.github.trebronwayne.droplink.core.ServerStats
import io.github.trebronwayne.droplink.core.SharedFolder
import io.github.trebronwayne.droplink.core.SharedFolders
import io.github.trebronwayne.droplink.core.SiteHost
import io.github.trebronwayne.droplink.ui.SiteUi
import io.github.trebronwayne.droplink.core.TlsIdentity
import io.github.trebronwayne.droplink.service.LocalServerService
import io.github.trebronwayne.droplink.ui.DropLinkApp
import io.github.trebronwayne.droplink.ui.FilesUi
import io.github.trebronwayne.droplink.ui.HostUi
import io.github.trebronwayne.droplink.ui.NetworkUi
import io.github.trebronwayne.droplink.ui.PairingUi
import io.github.trebronwayne.droplink.ui.theme.DropLinkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var nodeId by mutableStateOf("Creating secure device identity…")
    private var fingerprint by mutableStateOf("Generating certificate…")
    private var addresses by mutableStateOf(emptyList<io.github.trebronwayne.droplink.core.HostAddress>())
    private var folders by mutableStateOf(emptyList<SharedFolder>())
    private var useHttp by mutableStateOf(false)
    private var notificationSound by mutableStateOf(true)
    private var batterySaver by mutableStateOf(false)
    private var siteVersion by mutableStateOf(0)
    private var diagnostics by mutableStateOf("")
    private var events by mutableStateOf("")
    private var testResults by mutableStateOf(emptyList<String>())
    private var batteryRestricted by mutableStateOf(false)
    private var picked by mutableStateOf(emptyList<PickedFile>())

    private val pickFilesToSend =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNullOrEmpty()) return@registerForActivityResult
            val added = uris.mapNotNull { describe(it) }
            // Keep each file only once, newest picks last.
            picked = (picked + added).distinctBy { it.uri }.take(Outbox.MAX_FILES)
        }

    // Hosting works even if the user declines; only the visible notifications are lost.
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> startHostingService() }

    private val pickFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                try {
                    SharedFolders.add(this, uri)
                } catch (e: Throwable) {
                    CrashLog.record("Could not share that folder", e)
                }
                folders = SharedFolders.list(this)
                diagnostics = CrashLog.read(this)
            }
        }

    private val pickSiteFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                try { SiteHost.setFolder(this, uri) } catch (e: Throwable) { CrashLog.record("Could not use that folder for the page", e) }
                siteVersion++
                diagnostics = CrashLog.read(this)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        CrashLog.install(this)
        EventLog.init(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Pairing.init(this)
        ReceivedFiles.init(this)
        Presence.init(this)
        folders = SharedFolders.list(this)
        useHttp = HostingOptions.useHttp(this)
        notificationSound = HostingOptions.notificationSound(this)
        diagnostics = CrashLog.read(this)
        setContent {
            DropLinkTheme {
                val hosting by LocalServerService.hostingState.collectAsStateWithLifecycle()
                val error by LocalServerService.errorState.collectAsStateWithLifecycle()
                val stats by ServerStats.state.collectAsStateWithLifecycle()
                val requests by Pairing.requests.collectAsStateWithLifecycle()
                val trusted by Pairing.trusted.collectAsStateWithLifecycle()
                val uploads by Uploads.requests.collectAsStateWithLifecycle()
                val received by ReceivedFiles.recent.collectAsStateWithLifecycle()
                val nodes by Discovery.nodes.collectAsStateWithLifecycle()
                val presence by Presence.state.collectAsStateWithLifecycle()
                val receiving by Uploads.live.collectAsStateWithLifecycle()
                val sent by Outbox.batches.collectAsStateWithLifecycle()
                LaunchedEffect(hosting, useHttp) { refreshAddresses() }
                LaunchedEffect(error, hosting) {
                    diagnostics = withContext(Dispatchers.IO) { CrashLog.read(applicationContext) }
                }

                val host = HostUi(
                    isHosting = hosting,
                    addresses = addresses,
                    fingerprint = fingerprint,
                    error = error,
                    useHttp = useHttp,
                    batterySaver = batterySaver,
                    onOpenBatterySaver = {
                        openFirst(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS), Intent(Settings.ACTION_SETTINGS))
                    },
                    diagnostics = diagnostics,
                    events = events,
                    stats = stats,
                    testResults = testResults,
                    batteryRestricted = batteryRestricted,
                    onStart = ::requestStartHosting,
                    onStop = ::stopHostingService,
                    onRefresh = ::refreshAddresses,
                    notificationSound = notificationSound,
                    onNotificationSoundChange = { value ->
                        HostingOptions.setNotificationSound(this, value)
                        notificationSound = value
                        if (value) NotificationSound.preview(this)
                    },
                    onUseHttpChange = { value ->
                        HostingOptions.setUseHttp(this@MainActivity, value)
                        useHttp = value
                    },
                    onClearDiagnostics = {
                        CrashLog.clear(this@MainActivity)
                        diagnostics = ""
                    },
                    onRefreshEvents = { events = EventLog.read(this@MainActivity) },
                    onClearEvents = {
                        EventLog.clear(this@MainActivity)
                        events = ""
                    },
                    onTest = ::runSelfTest,
                    onOpenLocal = ::openOnThisPhone,
                    onOpenBatterySettings = ::openBatterySettings
                )
                val files = remember(folders, siteVersion, addresses) {
                    FilesUi(
                        folders = folders,
                        onAdd = { pickFolder.launch(null) },
                        onRemove = { folder ->
                            SharedFolders.remove(this@MainActivity, folder)
                            folders = SharedFolders.list(this@MainActivity)
                        },
                        site = SiteUi(
                            folderName = SiteHost.folderName(this@MainActivity),
                            published = SiteHost.enabled(this@MainActivity),
                            openToAnyone = SiteHost.openToAnyone(this@MainActivity),
                            users = SiteHost.users(this@MainActivity),
                            submissionCount = SiteHost.submissionCount(this@MainActivity),
                            submissionsJson = { SiteHost.submissionsJson(this@MainActivity) },
                            urls = addresses.map { it.url(LocalServerService.PORT, !useHttp) + "site/" },
                            onPickFolder = { pickSiteFolder.launch(null) },
                            onClearFolder = { SiteHost.clearFolder(this@MainActivity); siteVersion++ },
                            onPublishedChange = { SiteHost.setEnabled(this@MainActivity, it); siteVersion++ },
                            onOpenToAnyoneChange = { SiteHost.setOpenToAnyone(this@MainActivity, it); siteVersion++ },
                            onAddUser = { n, p -> SiteHost.addUser(this@MainActivity, n, p).also { siteVersion++ } },
                            onRemoveUser = { SiteHost.removeUser(this@MainActivity, it); siteVersion++ },
                            onClearSubmissions = { SiteHost.clearSubmissions(this@MainActivity); siteVersion++ }
                        )
                    )
                }
                val pairing = PairingUi(
                    requests = requests.filter { it.status == PairStatus.Pending },
                    trusted = trusted,
                    onApprove = Pairing::approve,
                    onDeny = Pairing::deny,
                    onRevoke = Pairing::revoke,
                    uploads = uploads,
                    received = received,
                    onUploadApprove = Uploads::approve,
                    onUploadDeny = Uploads::deny,
                    onSetUploadPolicy = Uploads::setPolicy,
                    onOpenDownloads = ::openDownloads,
                    onClearReceived = ReceivedFiles::clear,
                    presence = presence,
                    receiving = receiving,
                    onOpenReceived = ::openReceived
                )
                val send = SendUi(
                    picked = picked,
                    batches = sent,
                    onPick = { pickFilesToSend.launch(arrayOf("*/*")) },
                    onRemovePicked = { f -> picked = picked.filter { it.uri != f.uri } },
                    onSend = ::sendPicked,
                    onCancel = Outbox::cancel,
                    onRemove = Outbox::remove
                )
                val network = NetworkUi(nodes.filter { it.nodeId != nodeId }, ::openNode)
                DropLinkApp(nodeId = nodeId, host = host, files = files, pairing = pairing, network = network, send = send)
            }
        }
        lifecycleScope.launch {
            nodeId = try {
                withContext(Dispatchers.IO) { NodeIdentity.getOrCreateNodeId(applicationContext) }
            } catch (e: Throwable) {
                CrashLog.record("Device identity could not be created", e)
                "Unavailable: ${e.javaClass.simpleName}"
            }
        }
        lifecycleScope.launch {
            fingerprint = try {
                withContext(Dispatchers.IO) { TlsIdentity.load(applicationContext).fingerprint }
            } catch (e: Throwable) {
                CrashLog.record("Security certificate could not be created", e)
                "Unavailable: ${e.javaClass.simpleName}: ${e.message ?: "no details"}"
            }
            diagnostics = CrashLog.read(applicationContext)
        }
    }

    override fun onStart() {
        super.onStart()
        if (LocalServerService.hostingState.value) EventLog.add("App came to the foreground")
        try { Discovery.startBrowsing(applicationContext) } catch (e: Throwable) { CrashLog.record("Discovery could not start", e) }
    }

    override fun onStop() {
        if (LocalServerService.hostingState.value) EventLog.add("App went to the background")
        try { Discovery.stopBrowsing() } catch (_: Throwable) { }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refreshAddresses()
        folders = SharedFolders.list(this)
        diagnostics = CrashLog.read(this)
        events = EventLog.read(this)
        batteryRestricted = !getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        batterySaver = getSystemService(PowerManager::class.java).isPowerSaveMode
    }

    private fun runSelfTest() {
        lifecycleScope.launch {
            testResults = listOf("Testing…")
            testResults = withContext(Dispatchers.IO) { SelfTest.run(LocalServerService.PORT) }
        }
    }

    private fun openOnThisPhone() {
        val scheme = if (useHttp) "http" else "https"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$scheme://127.0.0.1:${LocalServerService.PORT}/")))
        } catch (_: ActivityNotFoundException) { }
    }

    /**
     * Opens the screen where DropLink can be allowed to run in the background.
     *
     * Why it used to stop working: the one-tap "Allow DropLink to run in the background" dialog does nothing visible
     * once DropLink is already allowed (and some phones never show it), and the old code only tried something else
     * when Android threw an error, not when the dialog silently closed. Now the dialog is used only while DropLink is
     * still restricted; otherwise the full battery-optimization list opens, and if a phone has no such screen, the
     * app's own settings page opens instead.
     */
    @android.annotation.SuppressLint("BatteryLife")
    private fun openBatterySettings() {
        val power = getSystemService(PowerManager::class.java)
        val exempt = power.isIgnoringBatteryOptimizations(packageName)
        val attempts = ArrayList<Intent>()
        if (!exempt) attempts += Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        attempts += Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        attempts += Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        attempts += Intent(Settings.ACTION_SETTINGS)
        if (!openFirst(*attempts.toTypedArray())) {
            Toast.makeText(this, "Could not open settings. Open Android Settings, then Apps, then DropLink, then Battery.", Toast.LENGTH_LONG).show()
        }
    }

    /** Starts the first of these screens that this phone can open. Returns false if none opened. */
    private fun openFirst(vararg intents: Intent): Boolean {
        for (intent in intents) {
            try {
                startActivity(intent)
                return true
            } catch (_: Exception) {
                // ActivityNotFoundException, SecurityException on some phones: try the next one.
            }
        }
        return false
    }

    /** Name, size and type of a document the owner picked, and keeps access to it while DropLink is hosting. */
    private fun describe(uri: Uri): PickedFile? {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) { /* access then lasts until the app is closed, which is enough for sending now */ }
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        var size = -1L
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst()) {
                    it.getString(0)?.let { n -> name = n }
                    if (!it.isNull(1)) size = it.getLong(1)
                }
            }
        } catch (_: Exception) { }
        if (size < 0) {
            size = try { contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L } catch (_: Exception) { -1L }
        }
        if (size < 0) return null
        val mime = contentResolver.getType(uri) ?: ReceivedFiles.mimeFor(name)
        return PickedFile(uri.toString(), ReceivedFiles.cleanName(name), size, mime)
    }

    /** Sends the picked files to the chosen devices, or to everyone who is online or was online recently. */
    private fun sendPicked(ids: Set<String>?) {
        if (!LocalServerService.hostingState.value) {
            Toast.makeText(this, "Start hosting first, so devices can download the files.", Toast.LENGTH_LONG).show()
            return
        }
        val now = System.currentTimeMillis()
        val reachable = Pairing.trusted.value.filter {
            Presence.status(Presence.lastSeen(it.id), now) != io.github.trebronwayne.droplink.core.PresenceStatus.Offline
        }
        val targets = if (ids == null) reachable else reachable.filter { it.id in ids }
        if (targets.isEmpty()) {
            Toast.makeText(this, "No chosen device is online right now.", Toast.LENGTH_LONG).show()
            return
        }
        val files = picked.mapIndexed { i, f -> OutFile(i, f.name, f.size, f.mime, f.uri) }
        if (Outbox.create(files, targets, everyone = ids == null) != null) picked = emptyList()
    }

    /** Opens a received file with another app (a music player for music, a viewer for photos, and so on). */
    private fun openReceived(file: ReceivedFile) {
        val uri: Uri? = try {
            when {
                file.uri.startsWith("content:") -> Uri.parse(file.uri)
                file.uri.startsWith("file:") -> FileProvider.getUriForFile(this, "$packageName.files", File(Uri.parse(file.uri).path ?: ""))
                else -> null
            }
        } catch (_: Exception) { null }
        if (uri == null) {
            Toast.makeText(this, "This file was received by an older version. Opening Downloads instead.", Toast.LENGTH_LONG).show()
            openDownloads()
            return
        }
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, ReceivedFiles.mimeFor(file.name))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(Intent.createChooser(view, "Open ${file.name} with"))
        } catch (_: Exception) {
            Toast.makeText(this, "No app on this phone can open this kind of file.", Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshAddresses() {
        lifecycleScope.launch {
            addresses = withContext(Dispatchers.IO) {
                NetworkAddresses.reachable()
            }
        }
    }

    /** Opens the system Downloads screen, where received files are kept in the DropLink folder. */
    private fun openDownloads() {
        try {
            startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
        } catch (_: Exception) {
            // No Downloads app on this phone.
        }
    }

    private fun openNode(node: DiscoveredNode) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://${node.host}:${node.port}/")))
        } catch (_: ActivityNotFoundException) { }
    }

    private fun requestStartHosting() {
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else startHostingService()
    }

    private fun startHostingService() {
        try {
            startForegroundService(Intent(this, LocalServerService::class.java).setAction(LocalServerService.ACTION_START))
            // Without this exemption Android freezes hosting as soon as another app is in front. Ask once, right away.
            val asked = getSharedPreferences("droplink_ui", MODE_PRIVATE)
            if (!getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName) && !asked.getBoolean("asked_battery", false)) {
                asked.edit().putBoolean("asked_battery", true).apply()
                openBatterySettings()
            }
        } catch (e: Throwable) {
            CrashLog.record("Could not start the hosting service", e)
            diagnostics = CrashLog.read(this)
        }
    }

    private fun stopHostingService() {
        startService(Intent(this, LocalServerService::class.java).setAction(LocalServerService.ACTION_STOP))
    }
}
