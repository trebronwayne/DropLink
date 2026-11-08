package io.github.trebronwayne.droplink

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import io.github.trebronwayne.droplink.core.NetworkAddresses
import io.github.trebronwayne.droplink.core.NodeIdentity
import io.github.trebronwayne.droplink.core.SharedFolders
import io.github.trebronwayne.droplink.service.LocalServerService
import io.github.trebronwayne.droplink.ui.DropLinkApp
import io.github.trebronwayne.droplink.ui.FilesUi
import io.github.trebronwayne.droplink.ui.HostUi
import io.github.trebronwayne.droplink.ui.theme.DropLinkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var nodeId by mutableStateOf("Creating secure device identity…")
    private var addresses by mutableStateOf(emptyList<String>())
    private var folders by mutableStateOf(emptyList<io.github.trebronwayne.droplink.core.SharedFolder>())

    // Hosting works even if the user declines; only the visible notification is lost.
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
        folders = SharedFolders.list(this)
        setContent {
            DropLinkTheme {
                val hosting by LocalServerService.hostingState.collectAsStateWithLifecycle()
                val pin by LocalServerService.pinState.collectAsStateWithLifecycle()
                LaunchedEffect(hosting) { refreshAddresses() }
                val host = HostUi(hosting, pin, addresses, ::requestStartHosting, ::stopHostingService, ::refreshAddresses)
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
                DropLinkApp(nodeId = nodeId, host = host, files = files)
            }
        }
        lifecycleScope.launch {
            nodeId = withContext(Dispatchers.IO) { NodeIdentity.getOrCreateNodeId(applicationContext) }
        }
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
