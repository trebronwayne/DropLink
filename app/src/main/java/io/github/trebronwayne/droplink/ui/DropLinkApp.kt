package io.github.trebronwayne.droplink.ui

import android.content.Context
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.outlined.Email
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.trebronwayne.aubree.AubreeState
import com.trebronwayne.aubree.compose.Aubree
import io.github.trebronwayne.droplink.BuildConfig
import io.github.trebronwayne.droplink.R
import io.github.trebronwayne.droplink.core.DiscoveredNode
import io.github.trebronwayne.droplink.core.PairRequest
import io.github.trebronwayne.droplink.core.ReceivedFile
import io.github.trebronwayne.droplink.core.ReceivedFiles
import io.github.trebronwayne.droplink.core.UploadPolicy
import io.github.trebronwayne.droplink.core.UploadRequest
import io.github.trebronwayne.droplink.core.formatBytes
import io.github.trebronwayne.droplink.core.SharedFolder
import io.github.trebronwayne.droplink.core.StatsSnapshot
import io.github.trebronwayne.droplink.core.TrustedDevice
import io.github.trebronwayne.droplink.service.LocalServerService
import io.github.trebronwayne.droplink.ui.components.AubreeEmptyState
import io.github.trebronwayne.droplink.ui.components.AubreeStatusCard
import io.github.trebronwayne.droplink.ui.components.AubreeToastHost
import io.github.trebronwayne.droplink.ui.components.AubreeToasts
import io.github.trebronwayne.droplink.ui.components.MeetAubreeDialog
import io.github.trebronwayne.droplink.ui.components.DropButton
import io.github.trebronwayne.droplink.ui.components.DropButtonStyle
import io.github.trebronwayne.droplink.ui.components.DropCard
import io.github.trebronwayne.droplink.ui.components.DropDeviceCard
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
    val addresses: List<io.github.trebronwayne.droplink.core.HostAddress>,
    val fingerprint: String,
    val error: String?,
    val useHttp: Boolean,
    val diagnostics: String,
    val events: String,
    val stats: StatsSnapshot,
    val testResults: List<String>,
    val batteryRestricted: Boolean,
    val batterySaver: Boolean,
    val onOpenBatterySaver: () -> Unit,
    val onStart: () -> Unit,
    val onStop: () -> Unit,
    val onRefresh: () -> Unit,
    val onUseHttpChange: (Boolean) -> Unit,
    val onClearDiagnostics: () -> Unit,
    val onRefreshEvents: () -> Unit,
    val onClearEvents: () -> Unit,
    val onTest: () -> Unit,
    val onOpenLocal: () -> Unit,
    val onOpenBatterySettings: () -> Unit
)

data class PairingUi(
    val requests: List<PairRequest>,
    val trusted: List<TrustedDevice>,
    val onApprove: (String) -> Unit,
    val onDeny: (String) -> Unit,
    val onRevoke: (String) -> Unit,
    /** Files other devices want to send, waiting for an answer. */
    val uploads: List<UploadRequest> = emptyList(),
    /** Files already received, newest first. */
    val received: List<ReceivedFile> = emptyList(),
    /** Answer an upload request: (request id, always approve this device). */
    val onUploadApprove: (String, Boolean) -> Unit = { _, _ -> },
    val onUploadDeny: (String) -> Unit = {},
    val onSetUploadPolicy: (String, UploadPolicy) -> Unit = { _, _ -> },
    val onOpenDownloads: () -> Unit = {},
    val onClearReceived: () -> Unit = {}
)

data class NetworkUi(val nodes: List<DiscoveredNode>, val onOpen: (DiscoveredNode) -> Unit)

data class FilesUi(
    val folders: List<SharedFolder>,
    val onAdd: () -> Unit,
    val onRemove: (SharedFolder) -> Unit,
    val site: SiteUi
)

@Composable
fun DropLinkApp(nodeId: String, host: HostUi, files: FilesUi, pairing: PairingUi, network: NetworkUi) {
    var current by rememberSaveable { mutableStateOf(Destination.Home) }
    val toasts = remember { AubreeToasts() }
    AubreeMoments(toasts, host, files, pairing)
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
            AubreeToastHost(toasts, Modifier.align(Alignment.TopCenter))
        }
    }
    FirstMeeting(toasts)
}

/** Aubree reacts to what happens: hosting on or off, someone knocking, a device joining, trouble, low power. */
@Composable
private fun AubreeMoments(toasts: AubreeToasts, host: HostUi, files: FilesUi, pairing: PairingUi) {
    var wasHosting by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(host.isHosting) {
        val before = wasHosting
        wasHosting = host.isHosting
        if (before != null && before != host.isHosting) {
            if (host.isHosting) toasts.show(AubreeState.HAPPY, "Hosting is on. Others can connect.")
            else toasts.show(AubreeState.SLEEPY, "Hosting stopped.")
        }
    }

    var seenRequests by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(pairing.requests) {
        val fresh = pairing.requests.filter { it.id !in seenRequests }
        seenRequests = seenRequests + pairing.requests.map { it.id }
        fresh.firstOrNull()?.let { toasts.show(AubreeState.WELCOME, "${it.deviceName} wants to connect") }
    }

    var seenUploads by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(pairing.uploads) {
        val fresh = pairing.uploads.filter { it.id !in seenUploads }
        seenUploads = seenUploads + pairing.uploads.map { it.id }
        fresh.firstOrNull()?.let { toasts.show(AubreeState.WELCOME, "${it.deviceName} wants to send you files") }
    }

    var newestReceived by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(pairing.received.firstOrNull()?.at) {
        val latest = pairing.received.firstOrNull()
        val before = newestReceived
        newestReceived = latest?.at ?: 0L
        if (before != null && latest != null && latest.at > before) toasts.show(AubreeState.HAPPY, "Received ${latest.name}")
    }

    var trustedCount by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(pairing.trusted.size) {
        val before = trustedCount
        trustedCount = pairing.trusted.size
        val newest = pairing.trusted.maxByOrNull { it.createdAt }
        if (before != null && pairing.trusted.size > before && newest != null) {
            toasts.show(AubreeState.CELEBRATE, "${newest.name} is connected")
        }
    }

    var folderCount by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(files.folders.size) {
        val before = folderCount
        folderCount = files.folders.size
        if (before != null && files.folders.size > before) toasts.show(AubreeState.HAPPY, "Folder shared.")
    }

    LaunchedEffect(host.error) {
        if (host.error != null) toasts.show(AubreeState.SAD, "Something isn\u2019t going well. Check Home for details.")
    }

    LaunchedEffect(host.isHosting, host.batterySaver) {
        if (host.isHosting && host.batterySaver) {
            toasts.show(AubreeState.LOW_BATTERY, "Battery Saver is on. Hosting may pause in the background.")
        }
    }

    LaunchedEffect(host.testResults) {
        val lines = host.testResults
        val first = lines.firstOrNull() ?: return@LaunchedEffect
        when {
            first.startsWith("Testing") -> toasts.show(AubreeState.WAIT, "Testing the connection\u2026")
            lines.any { it.startsWith("No Wi-Fi") } -> toasts.show(AubreeState.OFFLINE, "No Wi-Fi or hotspot found.")
            lines.any { it.startsWith("FAIL") } -> toasts.show(AubreeState.SAD, "Some connection checks failed. Details are on Home.")
            lines.any { it.startsWith("OK") } -> toasts.show(AubreeState.HAPPY, "All connection checks passed.")
        }
    }
}

/** The first time DropLink opens, Aubree introduces herself. */
@Composable
private fun FirstMeeting(toasts: AubreeToasts) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("droplink_ui", Context.MODE_PRIVATE) }
    var met by remember { mutableStateOf(prefs.getBoolean("met_aubree", false)) }
    if (!met) {
        MeetAubreeDialog {
            prefs.edit().putBoolean("met_aubree", true).apply()
            met = true
            toasts.show(AubreeState.HAPPY, "Nice to meet you!")
        }
    }
}

/** Home card that says what the server is doing right now. Hidden while a pairing request is waiting for an answer. */
@Composable
private fun HostingBuddy(host: HostUi, pairing: PairingUi) {
    val paired = pairing.trusted.size
    when {
        host.error != null -> AubreeStatusCard(AubreeState.SAD, "Something isn\u2019t going well", host.error)
        !host.isHosting -> AubreeStatusCard(AubreeState.SLEEPY, "Not hosting yet", "Start hosting and others on your network can connect.")
        paired == 0 -> AubreeStatusCard(AubreeState.WAIT, "Waiting for someone to connect", "Scan the QR code below, or open the address, on another device.")
        else -> AubreeStatusCard(
            AubreeState.HAPPY, "Hosting is on",
            "$paired device${if (paired == 1) "" else "s"} paired. Others can still connect with the address below."
        )
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
        Destination.Home -> ScreenScaffold("Home", "Your phone. Your server. Your network.", wide, showLogo = true) {
            if (host.diagnostics.isNotBlank()) DiagnosticsCard(host.diagnostics, host.onClearDiagnostics)
            pairing.requests.forEach { PairingRequestCard(it, pairing.onApprove, pairing.onDeny) }
            pairing.uploads.filter { it.status == io.github.trebronwayne.droplink.core.UploadStatus.Pending }
                .forEach { UploadRequestCard(it, pairing.onUploadApprove, pairing.onUploadDeny) }
            if (host.isHosting && host.batterySaver) {
                DropCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DropSpacing.m)) {
                        Aubree(state = AubreeState.LOW_BATTERY, size = 56.dp, contentDescription = "")
                        DropText("Battery Saver is on", style = DropTheme.type.subtitle)
                    }
                    DropText(
                        "Android pauses background apps while Battery Saver is on, so other devices lose the connection when you leave DropLink. Turn it off while hosting.",
                        style = DropTheme.type.body
                    )
                    DropButton("Open Battery Saver settings", host.onOpenBatterySaver, style = DropButtonStyle.Secondary, icon = Icons.Outlined.Settings)
                }
            }
            if (pairing.requests.isEmpty() && pairing.uploads.none { it.status == io.github.trebronwayne.droplink.core.UploadStatus.Pending }) HostingBuddy(host, pairing)
            DropDeviceCard(nodeId, host.isHosting, LocalServerService.PORT, host.fingerprint, host.addresses, host.onStart, host.onStop, host.onRefresh, host.error, host.stats, host.testResults, host.onTest, host.onOpenLocal, !host.useHttp)
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
        Destination.Transfers -> ScreenScaffold("Transfers", "Files sent to this phone", wide) {
            ReceivedContent(pairing)
        }
        Destination.Settings -> ScreenScaffold("Settings", "Identity, security and about", wide) {
            SettingsContent(nodeId, host, pairing)
        }
    }
}

@Composable
private fun DropLogo(modifier: Modifier = Modifier) {
    Image(painter = painterResource(R.drawable.droplink_logo), contentDescription = "DropLink logo", modifier = modifier)
}

@Composable
private fun DiagnosticsCard(text: String, onClear: () -> Unit) {
    val type = DropTheme.type
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    DropCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DropSpacing.m)) {
            Aubree(state = AubreeState.SAD, size = 56.dp, contentDescription = "")
            DropText("Something isn\u2019t going well", style = type.subtitle)
        }
        DropText(
            "Please email this problem report to ${Support.EMAIL} so it can be fixed. Nothing is sent unless you press send in your mail app.",
            style = type.caption, color = DropTheme.colors.textSecondary
        )
        DropText(text.takeLast(1200), style = type.caption, fontFamily = FontFamily.Monospace)
        Row(horizontalArrangement = Arrangement.spacedBy(DropSpacing.s)) {
            DropButton("Email report", { Support.report(context, "DropLink problem report", text) }, Modifier.weight(1f), DropButtonStyle.Primary, Icons.Outlined.Email)
            DropButton("Copy", { clipboard.setText(AnnotatedString(text)) }, style = DropButtonStyle.Subtle, icon = Icons.Outlined.ContentCopy)
            DropButton("Clear", onClear, style = DropButtonStyle.Subtle, icon = Icons.Outlined.Delete)
        }
    }
}

@Composable
private fun PairingRequestCard(request: PairRequest, onApprove: (String) -> Unit, onDeny: (String) -> Unit) {
    val type = DropTheme.type
    val secondary = DropTheme.colors.textSecondary
    DropCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DropSpacing.m)) {
            Aubree(state = AubreeState.WELCOME, size = 64.dp, contentDescription = "")
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(DropSpacing.xs)) {
                DropText("Someone wants to connect", style = type.subtitle)
                DropText("${request.deviceName} wants to browse your shared folders.", style = type.body)
            }
        }
        DropText(request.code, style = type.title, fontFamily = FontFamily.Monospace)
        DropText("Approve only if this code matches the one shown on that device.", style = type.caption, color = secondary)
        Row(horizontalArrangement = Arrangement.spacedBy(DropSpacing.s)) {
            DropButton("Deny", { onDeny(request.id) }, Modifier.weight(1f), DropButtonStyle.Secondary)
            DropButton("Approve", { onApprove(request.id) }, Modifier.weight(1f), DropButtonStyle.Primary)
        }
    }
}

@Composable
private fun UploadRequestCard(request: UploadRequest, onApprove: (String, Boolean) -> Unit, onDeny: (String) -> Unit) {
    val type = DropTheme.type
    val secondary = DropTheme.colors.textSecondary
    val count = request.files.size
    DropCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DropSpacing.m)) {
            Aubree(state = AubreeState.WELCOME, size = 64.dp, contentDescription = "")
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(DropSpacing.xs)) {
                DropText("Files incoming", style = type.subtitle)
                DropText(
                    "${request.deviceName} wants to send you $count file${if (count == 1) "" else "s"} (${formatBytes(request.totalBytes)}).",
                    style = type.body
                )
            }
        }
        request.files.take(4).forEach { DropText("\u2022  ${it.name}  (${formatBytes(it.size)})", style = type.caption, color = secondary) }
        if (count > 4) DropText("and ${count - 4} more", style = type.caption, color = secondary)
        DropText("They will be saved in ${ReceivedFiles.LOCATION}. Approve only if you expect them.", style = type.caption, color = secondary)
        Row(horizontalArrangement = Arrangement.spacedBy(DropSpacing.s)) {
            DropButton("Deny", { onDeny(request.id) }, Modifier.weight(1f), DropButtonStyle.Secondary)
            DropButton("Approve once", { onApprove(request.id, false) }, Modifier.weight(1f), DropButtonStyle.Primary)
        }
        DropButton("Always approve from ${request.deviceName}", { onApprove(request.id, true) }, Modifier.fillMaxWidth(), DropButtonStyle.Subtle)
    }
}

@Composable
private fun ReceivedContent(pairing: PairingUi) {
    val type = DropTheme.type
    val secondary = DropTheme.colors.textSecondary
    if (pairing.received.isEmpty()) {
        AubreeEmptyState(
            AubreeState.EMPTY, "No files received yet",
            "When a paired device sends you files and you approve them, they are saved in ${ReceivedFiles.LOCATION} and listed here."
        )
        return
    }
    DropCard {
        DropText("Received files", style = type.subtitle)
        DropText("Saved in ${ReceivedFiles.LOCATION}", style = type.caption, color = secondary)
        pairing.received.take(20).forEach { file ->
            Column(verticalArrangement = Arrangement.spacedBy(DropSpacing.xs)) {
                DropText(file.name, style = type.bodyStrong)
                DropText(
                    "${formatBytes(file.size)}  \u00b7  from ${file.from}  \u00b7  ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(file.at))}",
                    style = type.caption, color = secondary
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(DropSpacing.s)) {
            DropButton("Open Downloads", pairing.onOpenDownloads, Modifier.weight(1f), DropButtonStyle.Secondary, Icons.Outlined.Folder)
            DropButton("Clear list", pairing.onClearReceived, Modifier.weight(1f), DropButtonStyle.Subtle)
        }
    }
}

@Composable
private fun ServersContent(network: NetworkUi) {
    val type = DropTheme.type
    val secondary = DropTheme.colors.textSecondary
    if (network.nodes.isEmpty()) {
        AubreeEmptyState(
            AubreeState.SEARCHING, "Looking for other DropLink nodes",
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
        AubreeEmptyState(AubreeState.EMPTY, "Nothing shared yet", "Add a folder to make its files available to web browsers on your network.")
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
    SiteCard(files.site)
}

@Composable
private fun SettingsContent(nodeId: String, host: HostUi, pairing: PairingUi) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
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
        val clipboardForEvents = LocalClipboardManager.current
        DropText("Hosting activity", style = type.subtitle)
        DropText(
            "A record of what the app and Android did while hosting: going to the background, screen off, pauses and restarts. If hosting stops unexpectedly, email it to ${Support.EMAIL}.",
            style = type.caption, color = secondary
        )
        DropText(
            if (host.events.isBlank()) "Nothing recorded yet." else host.events.lines().filter { it.isNotBlank() }.takeLast(14).joinToString("\n"),
            style = type.caption, fontFamily = FontFamily.Monospace
        )
        Row(horizontalArrangement = Arrangement.spacedBy(DropSpacing.s)) {
            DropButton(
                "Email log", { Support.report(context, "DropLink hosting log", host.events) },
                Modifier.weight(1f), DropButtonStyle.Secondary, Icons.Outlined.Email, enabled = host.events.isNotBlank()
            )
            DropButton("Copy", { clipboardForEvents.setText(AnnotatedString(host.events)) }, style = DropButtonStyle.Subtle, icon = Icons.Outlined.ContentCopy, enabled = host.events.isNotBlank())
            DropButton("Refresh", host.onRefreshEvents, style = DropButtonStyle.Subtle, icon = Icons.Outlined.Refresh)
            DropButton("Clear", host.onClearEvents, style = DropButtonStyle.Subtle, icon = Icons.Outlined.Delete)
        }
    }
    DropCard {
        DropText("Keep hosting reliable", style = type.subtitle)
        DropText(
            if (host.batteryRestricted) {
                "Battery optimization is on for DropLink, so Android may pause hosting when the screen is off or the app is in the background."
            } else {
                "Battery optimization is off for DropLink. Good."
            },
            style = type.body
        )
        DropText(
            "Keep Battery Saver and Data Saver off while hosting. They make Android pause background apps, so other devices lose the connection when you leave DropLink.",
            style = type.caption, color = secondary
        )
        DropButton("Open battery settings", host.onOpenBatterySettings, style = DropButtonStyle.Secondary, icon = Icons.Outlined.Settings)
    }
    DropCard {
        DropText("Connection mode", style = type.subtitle)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                DropText("Testing mode (HTTP)", style = type.bodyStrong)
                DropText("Turns encryption off. Use only to find problems on a network you control.", style = type.caption, color = secondary)
            }
            Switch(checked = host.useHttp, onCheckedChange = host.onUseHttpChange, enabled = !host.isHosting)
        }
        if (host.isHosting) DropText("Stop hosting to change this.", style = type.caption, color = secondary)
    }
    DropCard {
        DropText("Trusted devices", style = type.subtitle)
        if (pairing.trusted.isEmpty()) {
            DropText("No devices are paired yet. When a browser asks to connect, approve it from the Home screen.", style = type.caption, color = secondary)
        }
        pairing.trusted.forEach { device ->
            Column(verticalArrangement = Arrangement.spacedBy(DropSpacing.s)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        DropText(device.name, style = type.bodyStrong)
                        DropText("Paired ${DateFormat.getDateInstance().format(Date(device.createdAt))}", style = type.caption, color = secondary)
                    }
                    DropButton("Revoke", { pairing.onRevoke(device.id) }, style = DropButtonStyle.Subtle, icon = Icons.Outlined.Delete)
                }
                DropText("Files this device sends to your phone", style = type.caption, color = secondary)
                Row(horizontalArrangement = Arrangement.spacedBy(DropSpacing.s)) {
                    listOf(UploadPolicy.Ask to "Ask me", UploadPolicy.Always to "Always", UploadPolicy.Blocked to "Block").forEach { (policy, label) ->
                        DropButton(
                            label, { pairing.onSetUploadPolicy(device.id, policy) }, Modifier.weight(1f),
                            if (device.uploads == policy) DropButtonStyle.Primary else DropButtonStyle.Secondary
                        )
                    }
                }
                HorizontalDivider(color = DropTheme.colors.stroke)
            }
        }
    }
    DropCard {
        DropText("Certificate fingerprint (SHA-256)", style = type.subtitle)
        DropText(host.fingerprint, style = type.caption, fontFamily = FontFamily.Monospace)
        DropText("Compare this with the certificate details your browser shows before trusting this phone.", style = type.caption, color = secondary)
    }
    DropCard {
        DropText("Coming soon", style = type.subtitle)
        listOf(
            "Live streaming: watch a video file as the phone plays it",
            "Live camera: use the phone as a CCTV camera",
            "Remote presentation: control slides on a big screen from the phone",
            "Send and receive files: send to one, two or everyone connected; people accept, or turn on auto-accept",
            "People: see who is connected, send messages, files and attachments",
            "Feedback: visitors write to you and you reply",
            "A richer device list with more details about each device"
        ).forEach { DropText("•  $it", style = type.caption, color = secondary) }
    }
    DropCard {
        DropText("About", style = type.subtitle)
        DropText("DropLink ${BuildConfig.VERSION_NAME}", style = type.body)
        DropText("Your phone. Your server. Your network.", style = type.caption, color = secondary)
        DropButton("Send feedback", { Support.email(context, "DropLink feedback", "") }, Modifier.fillMaxWidth(), DropButtonStyle.Secondary, Icons.Outlined.Email)
        DropButton("Report an issue on GitHub", { Support.openUrl(context, Support.ISSUES) }, Modifier.fillMaxWidth(), DropButtonStyle.Subtle)
        DropButton("Privacy Policy", { Support.openUrl(context, Support.PRIVACY) }, Modifier.fillMaxWidth(), DropButtonStyle.Subtle)
        DropButton("Source code and license", { Support.openUrl(context, Support.REPO) }, Modifier.fillMaxWidth(), DropButtonStyle.Subtle)
    }
}

@Composable
private fun ScreenScaffold(
    title: String,
    subtitle: String,
    wide: Boolean,
    showLogo: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val edge = if (wide) DropSpacing.xxl else DropSpacing.l
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier.widthIn(max = 880.dp).fillMaxWidth().padding(horizontal = edge, vertical = DropSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(DropSpacing.l)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showLogo) {
                    DropLogo(Modifier.size(48.dp))
                    Spacer(Modifier.width(DropSpacing.m))
                }
                Column {
                    DropText(title, style = DropTheme.type.largeTitle)
                    DropText(subtitle, style = DropTheme.type.body, color = DropTheme.colors.textSecondary)
                }
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
            DropLogo(Modifier.size(32.dp))
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
