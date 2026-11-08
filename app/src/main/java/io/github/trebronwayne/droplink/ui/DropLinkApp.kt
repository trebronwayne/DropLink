package io.github.trebronwayne.droplink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.trebronwayne.droplink.BuildConfig
import io.github.trebronwayne.droplink.core.DiscoveredNode
import io.github.trebronwayne.droplink.core.PairRequest
import io.github.trebronwayne.droplink.core.SharedFolder
import io.github.trebronwayne.droplink.core.TrustedDevice
import io.github.trebronwayne.droplink.service.LocalServerService
import io.github.trebronwayne.droplink.ui.components.DropButton
import io.github.trebronwayne.droplink.ui.components.DropButtonStyle
import io.github.trebronwayne.droplink.ui.components.DropCard
import io.github.trebronwayne.droplink.ui.components.DropDeviceCard
import io.github.trebronwayne.droplink.ui.components.DropEmptyState
import io.github.trebronwayne.droplink.ui.components.DropIconTile
import io.github.trebronwayne.droplink.ui.components.DropInfoCard
import io.github.trebronwayne.droplink.ui.components.DropSectionTitle
import io.github.trebronwayne.droplink.ui.components.DropText
import io.github.trebronwayne.droplink.ui.theme.DropRadius
import io.github.trebronwayne.droplink.ui.theme.DropSpacing
import io.github.trebronwayne.droplink.ui.theme.DropTheme
import java.text.DateFormat
import java.util.Date

enum class Destination(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    Home("Home", Icons.Outlined.Home, Icons.Filled.Home),
    Servers("Servers", Icons.Outlined.Dns, Icons.Filled.Dns),
    Files("Files", Icons.Outlined.Folder, Icons.Filled.Folder),
    Transfers("Transfers", Icons.Outlined.SwapVert, Icons.Filled.SwapVert),
    Settings("Settings", Icons.Outlined.Settings, Icons.Filled.Settings)
}

data class HostUi(
    val isHosting: Boolean,
    val addresses: List<String>,
    val fingerprint: String,
    val onStart: () -> Unit,
    val onStop: () -> Unit,
    val onRefresh: () -> Unit
)

data class PairingUi(
    val requests: List<PairRequest>,
    val trusted: List<TrustedDevice>,
    val onApprove: (String) -> Unit,
    val onDeny: (String) -> Unit,
    val onRevoke: (String) -> Unit
)

data class NetworkUi(val nodes: List<DiscoveredNode>, val onOpen: (DiscoveredNode) -> Unit)

data class FilesUi(
    val folders: List<SharedFolder>,
    val onAdd: () -> Unit,
    val onRemove: (SharedFolder) -> Unit
)

@Composable
fun DropLinkApp(nodeId: String, host: HostUi, files: FilesUi, pairing: PairingUi, network: NetworkUi) {
    var current by rememberSaveable { mutableStateOf(Destination.Home) }
    Surface(modifier = Modifier.fillMaxSize(), color = DropTheme.colors.background) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            val wide = maxWidth >= 600.dp
            val screen: @Composable () -> Unit = {
                ScreenHost(current, wide, nodeId, host, files, pairing, network) { current = it }
            }
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    NavigationPane(current) { current = it }
                    Box(Modifier.weight(1f).fillMaxHeight()) { screen() }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) { screen() }
                    BottomNavigation(current) { current = it }
                }
            }
        }
    }
}

@Composable
private fun ScreenHost(
    destination: Destination,
    wide: Boolean,
    nodeId: String,
    host: HostUi,
    files: FilesUi,
    pairing: PairingUi,
    network: NetworkUi,
    onNavigate: (Destination) -> Unit
) {
    when (destination) {
        Destination.Home -> ScreenScaffold("Home", "Your phone. Your server. Your network.", wide) {
            pairing.requests.forEach { PairingRequestCard(it, pairing.onApprove, pairing.onDeny) }
            DropDeviceCard(nodeId, host.isHosting, LocalServerService.PORT, host.fingerprint, host.addresses, host.onStart, host.onStop, host.onRefresh)
            DropSectionTitle("Network workspace")
            Row(horizontalArrangement = Arrangement.spacedBy(DropSpacing.m)) {
                DropInfoCard(Icons.Filled.Dns, "Servers", "${network.nodes.size} found nearby", Modifier.weight(1f)) { onNavigate(Destination.Servers) }
                DropInfoCard(Icons.Filled.Folder, "Files", "${files.folders.size} shared folder(s)", Modifier.weight(1f)) { onNavigate(Destination.Files) }
            }
        }
        Destination.Servers -> ScreenScaffold("Servers", "DropLink nodes on your network", wide) {
            ServersContent(network)
        }
        Destination.Files -> ScreenScaffold("Files", "Folders you share from this phone", wide) {
            FilesContent(files)
        }
        Destination.Transfers -> ScreenScaffold("Transfers", "Active, queued and completed", wide) {
            DropEmptyState(Icons.Filled.SwapVert, "No transfers", "Resumable file transfers will appear here.")
        }
        Destination.Settings -> ScreenScaffold("Settings", "Identity, security and about", wide) {
            SettingsContent(nodeId, host.fingerprint, pairing)
        }
    }
}

@Composable
private fun PairingRequestCard(request: PairRequest, onApprove: (String) -> Unit, onDeny: (String) -> Unit) {
    val type = DropTheme.type
    val secondary = DropTheme.colors.textSecondary
    DropCard {
        DropText("Pairing request", style = type.subtitle)
        DropText("${request.deviceName} wants to browse your shared folders.", style = type.body)
        DropText(request.code, style = type.title, fontFamily = FontFamily.Monospace)
        DropText("Approve only if this code matches the one shown on that device.", style = type.caption, color = secondary)
        Row(horizontalArrangement = Arrangement.spacedBy(DropSpacing.s)) {
            DropButton("Deny", { onDeny(request.id) }, Modifier.weight(1f), DropButtonStyle.Secondary)
            DropButton("Approve", { onApprove(request.id) }, Modifier.weight(1f), DropButtonStyle.Primary)
        }
    }
}

@Composable
private fun ServersContent(network: NetworkUi) {
    val type = DropTheme.type
    val secondary = DropTheme.colors.textSecondary
    if (network.nodes.isEmpty()) {
        DropEmptyState(
            Icons.Filled.Dns, "No other DropLink nodes found",
            "Make sure the other phone is hosting and both are on the same Wi-Fi or hotspot. Some phones cannot see devices from the hotspot side; you can still type the address shown on the other phone."
        )
    } else {
        network.nodes.forEach { node ->
            DropCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DropIconTile(Icons.Filled.Dns)
                    Column(Modifier.weight(1f).padding(start = DropSpacing.m)) {
                        DropText(node.name, style = type.bodyStrong)
                        DropText("${node.host}:${node.port}", style = type.caption, color = secondary, fontFamily = FontFamily.Monospace)
                    }
                }
                if (node.fingerprint.length >= 16) {
                    DropText(
                        "Fingerprint " + node.fingerprint.chunked(2).take(8).joinToString(":") + "…",
                        style = type.caption, color = secondary, fontFamily = FontFamily.Monospace
                    )
                }
                DropButton("Open in browser", { network.onOpen(node) }, Modifier.fillMaxWidth(), DropButtonStyle.Secondary, Icons.Outlined.OpenInBrowser)
            }
        }
    }
}

@Composable
private fun FilesContent(files: FilesUi) {
    val type = DropTheme.type
    DropCard {
        DropText("Shared folders", style = type.subtitle)
        DropText(
            "Anyone who enters your access code can view and download everything inside a shared folder. Share only what you want others on your network to see. Access is read-only.",
            style = type.caption, color = DropTheme.colors.textSecondary
        )
        DropButton("Add shared folder", files.onAdd, Modifier.fillMaxWidth(), icon = Icons.Outlined.CreateNewFolder)
    }
    if (files.folders.isEmpty()) {
        DropEmptyState(Icons.Filled.Folder, "Nothing shared yet", "Add a folder to make its files available to web browsers on your network.")
    } else {
        files.folders.forEach { folder ->
            DropCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DropIconTile(Icons.Filled.Folder)
                    DropText(folder.name, style = type.bodyStrong, modifier = Modifier.weight(1f).padding(start = DropSpacing.m))
                    DropButton("Remove", { files.onRemove(folder) }, style = DropButtonStyle.Subtle, icon = Icons.Outlined.Delete)
                }
            }
        }
    }
}

@Composable
private fun SettingsContent(nodeId: String, fingerprint: String, pairing: PairingUi) {
    val clipboard = LocalClipboardManager.current
    val type = DropTheme.type
    val secondary = DropTheme.colors.textSecondary
    DropCard {
        DropText("Device identity", style = type.subtitle)
        DropText(nodeId, style = type.bodyStrong, fontFamily = FontFamily.Monospace)
        DropText(
            "This ID is derived from a public key. Its matching private key stays inside Android Keystore and is not exported.",
            style = type.caption, color = secondary
        )
        DropButton(
            "Copy node ID", { clipboard.setText(AnnotatedString(nodeId)) },
            style = DropButtonStyle.Secondary, icon = Icons.Outlined.ContentCopy,
            enabled = nodeId.startsWith("dl-")
        )
    }
    DropCard {
        DropText("Trusted devices", style = type.subtitle)
        if (pairing.trusted.isEmpty()) {
            DropText("No devices are paired yet. When a browser asks to connect, approve it from the Home screen.", style = type.caption, color = secondary)
        }
        pairing.trusted.forEach { device ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    DropText(device.name, style = type.bodyStrong)
                    DropText("Paired ${DateFormat.getDateInstance().format(Date(device.createdAt))}", style = type.caption, color = secondary)
                }
                DropButton("Revoke", { pairing.onRevoke(device.id) }, style = DropButtonStyle.Subtle, icon = Icons.Outlined.Delete)
            }
        }
    }
    DropCard {
        DropText("Certificate fingerprint (SHA-256)", style = type.subtitle)
        DropText(fingerprint, style = type.caption, fontFamily = FontFamily.Monospace)
        DropText("Compare this with the certificate details your browser shows before trusting this phone.", style = type.caption, color = secondary)
    }
    DropCard {
        DropText("About", style = type.subtitle)
        DropText("DropLink ${BuildConfig.VERSION_NAME}", style = type.body)
        DropText("Node Foundation build", style = type.caption, color = secondary)
    }
}

@Composable
private fun ScreenScaffold(
    title: String,
    subtitle: String,
    wide: Boolean,
    content: @Composable ColumnScope.() -> Unit
) {
    val edge = if (wide) DropSpacing.xxl else DropSpacing.l
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier.widthIn(max = 880.dp).fillMaxWidth().padding(horizontal = edge, vertical = DropSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(DropSpacing.l)
        ) {
            Column {
                DropText(title, style = DropTheme.type.largeTitle)
                DropText(subtitle, style = DropTheme.type.body, color = DropTheme.colors.textSecondary)
            }
            content()
        }
    }
}

/** Windows 11 style navigation pane for tablets and large screens. */
@Composable
private fun NavigationPane(current: Destination, onSelect: (Destination) -> Unit) {
    val c = DropTheme.colors
    Column(
        modifier = Modifier.width(240.dp).fillMaxHeight().padding(DropSpacing.m),
        verticalArrangement = Arrangement.spacedBy(DropSpacing.xs)
    ) {
        Row(Modifier.padding(DropSpacing.s), verticalAlignment = Alignment.CenterVertically) {
            DropIconTile(Icons.Filled.Hub)
            DropText("DropLink", style = DropTheme.type.subtitle, modifier = Modifier.padding(start = DropSpacing.m))
        }
        Spacer(Modifier.height(DropSpacing.s))
        Destination.entries.forEach { item ->
            val selected = item == current
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(DropRadius.small))
                    .background(if (selected) c.subtleSelected else androidx.compose.ui.graphics.Color.Transparent)
                    .selectable(selected = selected, onClick = { onSelect(item) }, role = Role.Tab)
                    .padding(horizontal = DropSpacing.s),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.width(3.dp).height(16.dp)
                        .background(if (selected) c.brand else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(2.dp))
                )
                Icon(
                    if (selected) item.selectedIcon else item.icon, contentDescription = null,
                    tint = if (selected) c.brand else c.textSecondary,
                    modifier = Modifier.padding(horizontal = DropSpacing.m).size(20.dp)
                )
                DropText(item.label, style = if (selected) DropTheme.type.bodyStrong else DropTheme.type.body)
            }
        }
    }
}

@Composable
private fun BottomNavigation(current: Destination, onSelect: (Destination) -> Unit) {
    val c = DropTheme.colors
    Column(Modifier.background(c.background)) {
        HorizontalDivider(color = c.stroke)
        Row(Modifier.fillMaxWidth()) {
            Destination.entries.forEach { item ->
                val selected = item == current
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 56.dp)
                        .selectable(selected = selected, onClick = { onSelect(item) }, role = Role.Tab)
                        .padding(vertical = DropSpacing.s),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        if (selected) item.selectedIcon else item.icon, contentDescription = null,
                        tint = if (selected) c.brand else c.textSecondary, modifier = Modifier.size(24.dp)
                    )
                    DropText(
                        item.label, style = DropTheme.type.caption,
                        color = if (selected) c.brand else c.textSecondary
                    )
                }
            }
        }
    }
}
