package io.github.trebronwayne.droplink

import android.Manifest
import android.content.ActivityNotFoundException
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
import io.github.trebronwayne.droplink.core.Pairing
import io.github.trebronwayne.droplink.core.SelfTest
import io.github.trebronwayne.droplink.core.ServerStats
import io.github.trebronwayne.droplink.core.SharedFolder
import io.github.trebronwayne.droplink.core.SharedFolders
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
    private var addresses by mutableStateOf(emptyList<String>())
    private var folders by mutableStateOf(emptyList<SharedFolder>())
    private var useHttp by mutableStateOf(false)
    private var diagnostics by mutableStateOf("")
    private var events by mutableStateOf("")
    private var testResults by mutableStateOf(emptyList<String>())
    private var batteryRestricted by mutableStateOf(false)

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

    override fun onCreate(savedInstanceState: Bundle?) {
        CrashLog.install(this)
        EventLog.init(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Pairing.init(this)
        folders = SharedFolders.list(this)
        useHttp = HostingOptions.useHttp(this)
        diagnostics = CrashLog.read(this)
        setContent {
            DropLinkTheme {
                val hosting by LocalServerService.hostingState.collectAsStateWithLifecycle()
                val error by LocalServerService.errorState.collectAsStateWithLifecycle()
                val stats by ServerStats.state.collectAsStateWithLifecycle()
                val requests by Pairing.requests.collectAsStateWithLifecycle()
                val trusted by Pairing.trusted.collectAsStateWithLifecycle()
                val nodes by Discovery.nodes.collectAsStateWithLifecycle()
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
                    diagnostics = diagnostics,
                    events = events,
                    stats = stats,
                    testResults = testResults,
                    batteryRestricted = batteryRestricted,
                    onStart = ::requestStartHosting,
                    onStop = ::stopHostingService,
                    onRefresh = ::refreshAddresses,
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
                val files = remember(folders) {
                    FilesUi(
                        folders = folders,
                        onAdd = { pickFolder.launch(null) },
                        onRemove = { folder ->
                            SharedFolders.remove(this@MainActivity, folder)
                            folders = SharedFolders.list(this@MainActivity)
                        }
                    )
                }
                val pairing = PairingUi(
                    requests = requests.filter { it.status == PairStatus.Pending },
                    trusted = trusted,
                    onApprove = Pairing::approve,
                    onDeny = Pairing::deny,
                    onRevoke = Pairing::revoke
                )
                val network = NetworkUi(nodes.filter { it.nodeId != nodeId }, ::openNode)
                DropLinkApp(nodeId = nodeId, host = host, files = files, pairing = pairing, network = network)
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

    private fun openBatterySettings() {
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (_: ActivityNotFoundException) { }
    }

    private fun refreshAddresses() {
        lifecycleScope.launch {
            addresses = withContext(Dispatchers.IO) {
                NetworkAddresses.baseUrls(LocalServerService.PORT, https = !useHttp)
            }
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
        } catch (e: Throwable) {
            CrashLog.record("Could not start the hosting service", e)
            diagnostics = CrashLog.read(this)
        }
    }

    private fun stopHostingService() {
        startService(Intent(this, LocalServerService::class.java).setAction(LocalServerService.ACTION_STOP))
    }
}
