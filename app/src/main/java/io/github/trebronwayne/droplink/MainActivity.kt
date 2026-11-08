package io.github.trebronwayne.droplink

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import io.github.trebronwayne.droplink.core.Discovery
import io.github.trebronwayne.droplink.core.DiscoveredNode
import io.github.trebronwayne.droplink.core.NetworkAddresses
import io.github.trebronwayne.droplink.core.NodeIdentity
import io.github.trebronwayne.droplink.core.PairStatus
import io.github.trebronwayne.droplink.core.Pairing
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

    // Hosting works even if the user declines; only the visible notifications are lost.
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> startHostingService() }

    private val pickFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                SharedFolders.add(this, uri)
                folders = SharedFolders.list(this)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Pairing.init(this)
        folders = SharedFolders.list(this)
        setContent {
            DropLinkTheme {
                val hosting by LocalServerService.hostingState.collectAsStateWithLifecycle()
                val requests by Pairing.requests.collectAsStateWithLifecycle()
                val trusted by Pairing.trusted.collectAsStateWithLifecycle()
                val nodes by Discovery.nodes.collectAsStateWithLifecycle()
                LaunchedEffect(hosting) { refreshAddresses() }

                val host = HostUi(hosting, addresses, fingerprint, ::requestStartHosting, ::stopHostingService, ::refreshAddresses)
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
            nodeId = withContext(Dispatchers.IO) { NodeIdentity.getOrCreateNodeId(applicationContext) }
        }
        lifecycleScope.launch {
            fingerprint = withContext(Dispatchers.IO) { TlsIdentity.load(applicationContext).fingerprint }
        }
    }

    override fun onStart() {
        super.onStart()
        Discovery.startBrowsing(applicationContext)
    }

    override fun onStop() {
        Discovery.stopBrowsing()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refreshAddresses()
        folders = SharedFolders.list(this)
    }

    private fun refreshAddresses() {
        lifecycleScope.launch {
            addresses = withContext(Dispatchers.IO) { NetworkAddresses.baseUrls(LocalServerService.PORT) }
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
        startForegroundService(Intent(this, LocalServerService::class.java).setAction(LocalServerService.ACTION_START))
    }

    private fun stopHostingService() {
        startService(Intent(this, LocalServerService::class.java).setAction(LocalServerService.ACTION_STOP))
    }
}
