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
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Science
import io.github.trebronwayne.droplink.ui.components.DropChevron
import io.github.trebronwayne.droplink.ui.components.DropCodeBox
import io.github.trebronwayne.droplink.ui.components.DropExpandableRow
import io.github.trebronwayne.droplink.ui.components.DropLinkRow
import io.github.trebronwayne.droplink.ui.components.DropRowDivider
import io.github.trebronwayne.droplink.ui.components.DropSegmented
import io.github.trebronwayne.droplink.ui.components.DropSettingRow
import io.github.trebronwayne.droplink.ui.components.DropSettingsGroup
import io.github.trebronwayne.droplink.ui.components.DropSwitchRow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import io.github.trebronwayne.droplink.core.CameraNode
import io.github.trebronwayne.droplink.core.CameraPolicy
import io.github.trebronwayne.droplink.core.CameraRequest
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

enum class Destination(val label: String, val icon: ImageVector, val selectedIcon: ImageVector, val inNav: Boolean = true) {
    Home("Home", Icons.Outlined.Home, Icons.Filled.Home),
    Player("Player", Icons.Outlined.PlayCircle, Icons.Filled.PlayCircle),
    Devices("Devices", Icons.Outlined.Devices, Icons.Filled.Devices),
    Servers("Servers", Icons.Outlined.Dns, Icons.Filled.Dns, inNav = false),
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
    val onOpenBatterySettings: () -> Unit,
    /** Play the DropLink sound for notifications. */
    val notificationSound: Boolean = true,
    val onNotificationSoundChange: (Boolean) -> Unit = {}
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
    /** When each paired device was last seen (device id to time). */
    val presence: Map<String, Long> = emptyMap(),
    /** Progress of files being received, keyed "requestId:index". */
    val receiving: Map<String, io.github.trebronwayne.droplink.core.ReceiveProgress> = emptyMap(),
    /** Open a received file with another app. */
    val onOpenReceived: (ReceivedFile) -> Unit = {},
    val onUploadApprove: (String, Boolean) -> Unit = { _, _ -> },
    val onUploadDeny: (String) -> Unit = {},
    val onSetUploadPolicy: (String, UploadPolicy) -> Unit = { _, _ -> },
    val onOpenDownloads: () -> Unit = {},
    val onClearReceived: () -> Unit = {},
    /** Devices waiting to be allowed to act as a Camera Node. */
    val cameraRequests: List<CameraRequest> = emptyList(),
    /** Camera Nodes the phone knows about (use isOnline to see who is sharing now). */
    val cameraNodes: List<CameraNode> = emptyList(),
    /** Answer a Camera Node request: (request id, always allow this device). */
    val onCameraApprove: (String, Boolean) -> Unit = { _, _ -> },
    val onCameraDeny: (String) -> Unit = {},
    val onSetCameraPolicy: (String, CameraPolicy) -> Unit = { _, _ -> }
)

/** A file the owner picked to send. */
data class PickedFile(val uri: String, val name: String, val size: Long, val mime: String)

/** Sending files from this phone to paired devices. */
data class SendUi(
    val picked: List<PickedFile> = emptyList(),
    val batches: List<io.github.trebronwayne.droplink.core.OutBatch> = emptyList(),
    val onPick: () -> Unit = {},
    val onRemovePicked: (PickedFile) -> Unit = {},
    /** Send the picked files. A null set means everyone who is online or recently online. */
    val onSend: (Set<String>?) -> Unit = {},
    val onCancel: (String) -> Unit = {},
    val onRemove: (String) -> Unit = {},
    /** Other DropLink phones this phone is paired with, and which of them are on the network now. */
    val peers: List<io.github.trebronwayne.droplink.core.Peer> = emptyList(),
    val nearby: Set<String> = emptySet(),
    val peerStatus: Map<String, io.github.trebronwayne.droplink.core.PeerStatus> = emptyMap(),
    val peerTransfers: List<io.github.trebronwayne.droplink.core.PeerTransfer> = emptyList(),
    /** Send the picked files to other phones. [clearPicked] is true when no browser device is also being sent to. */
    val onSendPeers: (Set<String>, Boolean) -> Unit = { _, _ -> },
    val onPeerCancel: (String) -> Unit = {},
    val onPeerRetry: (String) -> Unit = {},
    val onPeerRemove: (String) -> Unit = {}
)

data class NetworkUi(
    val nodes: List<DiscoveredNode>,
    val onOpen: (DiscoveredNode) -> Unit,
    val peers: List<io.github.trebronwayne.droplink.core.Peer> = emptyList(),
    val peerStatus: Map<String, io.github.trebronwayne.droplink.core.PeerStatus> = emptyMap(),
    val pairing: io.github.trebronwayne.droplink.core.PeerPairing? = null,
    val onPair: (DiscoveredNode) -> Unit = {},
    val onCancelPair: () -> Unit = {},
    val onDismissPair: () -> Unit = {},
    val onUnpair: (String) -> Unit = {},
    val onSendTo: (String) -> Unit = {}
)

data class FilesUi(
    val folders: List<SharedFolder>,
    val onAdd: () -> Unit,
    val onRemove: (SharedFolder) -> Unit,
    val site: SiteUi
)

@Composable
fun DropLinkApp(
    nodeId: String, host: HostUi, files: FilesUi, pairing: PairingUi, network: NetworkUi, send: SendUi = SendUi(),
    player: PlayerUi = PlayerUi(), openRequest: Destination? = null, onOpenHandled: () -> Unit = {}
) {
    var current by rememberSaveable { mutableStateOf(Destination.Home) }
    LaunchedEffect(openRequest) {
        if (openRequest != null) {
            current = openRequest
            onOpenHandled()
        }
    }
    val immersive = current == Destination.Player && player.fullscreen
    var preselect by remember { mutableStateOf<String?>(null) }
    val toasts = remember { AubreeToasts() }
    AubreeMoments(toasts, host, files, pairing)
    Surface(modifier = Modifier.fillMaxSize(), color = DropTheme.colors.background) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            val wide = maxWidth >= 600.dp
            val screen: @Composable () -> Unit = {
                ScreenHost(current, wide, nodeId, host, files, pairing, network, send, player, preselect, { preselect = it }) { current = it }
            }
            if (immersive) {
                Box(Modifier.fillMaxSize()) { screen() }
            } else if (wide) {
                Row(Modifier.fillMaxSize()) {
                    NavigationPane(current) { current = it }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Box(Modifier.weight(1f).fillMaxWidth()) { screen() }
                        if (current != Destination.Player) MiniPlayer({ current = Destination.Player })
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) { screen() }
                    if (current != Destination.Player) MiniPlayer({ current = Destination.Player })
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
        fresh.firstOrNull()?.let { toasts.show(AubreeState.WELCOME, "${it.label} wants to connect") }
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
    send: SendUi,
    player: PlayerUi,
    preselect: String?,
    onPreselect: (String?) -> Unit,
    onNavigate: (Destination) -> Unit
) {
    when (destination) {
        Destination.Home -> ScreenScaffold("Home", "Your phone. Your server. Your network.", wide, showLogo = true) {
            if (host.diagnostics.isNotBlank()) DiagnosticsCard(host.diagnostics, host.onClearDiagnostics)
            pairing.requests.forEach { PairingRequestCard(it, pairing.onApprove, pairing.onDeny) }
            pairing.uploads.filter { it.status == io.github.trebronwayne.droplink.core.UploadStatus.Pending }
                .forEach { UploadRequestCard(it, pairing.onUploadApprove, pairing.onUploadDeny) }
            pairing.cameraRequests.filter { it.status == io.github.trebronwayne.droplink.core.CameraRequestStatus.Pending }
                .forEach { CameraRequestCard(it, pairing.onCameraApprove, pairing.onCameraDeny) }
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
            if (pairing.requests.isEmpty() && pairing.cameraRequests.none { it.status == io.github.trebronwayne.droplink.core.CameraRequestStatus.Pending } && pairing.uploads.none { it.status == io.github.trebronwayne.droplink.core.UploadStatus.Pending }) HostingBuddy(host, pairing)
            DropDeviceCard(nodeId, host.isHosting, LocalServerService.PORT, host.fingerprint, host.addresses, host.onStart, host.onStop, host.onRefresh, host.error, host.stats, host.testResults, host.onTest, host.onOpenLocal, !host.useHttp)
            DropSectionTitle("Network workspace")
            val now = rememberNow()
            val onlineCount = pairing.trusted.count { io.github.trebronwayne.droplink.core.Presence.status(pairing.presence[it.id], now) == io.github.trebronwayne.droplink.core.PresenceStatus.Online }
            Row(horizontalArrangement = Arrangement.spacedBy(DropSpacing.m)) {
                DropInfoCard(Icons.Filled.Devices, "Devices", "$onlineCount online \u00b7 ${pairing.trusted.size} paired", Modifier.weight(1f)) { onNavigate(Destination.Devices) }
                DropInfoCard(Icons.Filled.Folder, "Files", "${files.folders.size} shared folder(s)", Modifier.weight(1f)) { onNavigate(Destination.Files) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(DropSpacing.m)) {
                DropInfoCard(Icons.Filled.SwapVert, "Transfers", "${send.batches.size} sent \u00b7 ${pairing.received.size} received", Modifier.weight(1f)) { onNavigate(Destination.Transfers) }
                DropInfoCard(Icons.Filled.Dns, "Servers", "${network.nodes.size} found nearby", Modifier.weight(1f)) { onNavigate(Destination.Servers) }
            }
        }
        Destination.Player -> PlayerScreen(player.copy(onOpenSettings = { onNavigate(Destination.Settings) }), wide)
        Destination.Devices -> ScreenScaffold("Devices", "Who is paired, and who is around", wide) {
            DevicesContent(pairing) { id -> onPreselect(id); onNavigate(Destination.Transfers) }
        }
        Destination.Servers -> ScreenScaffold("Servers", "DropLink nodes on your network", wide) {
            ServersContent(network) { id -> onPreselect("peer:$id"); onNavigate(Destination.Transfers) }
        }
        Destination.Files -> ScreenScaffold("Files", "Folders you share from this phone", wide) {
            FilesContent(files)
        }
        Destination.Transfers -> ScreenScaffold("Transfers", "Send files and see what arrives", wide) {
            TransfersContent(pairing, send, preselect) { onPreselect(null) }
        }
        Destination.Settings -> ScreenScaffold("Settings", "Your phone, hosting and support", wide) {
            SettingsContent(nodeId, host, pairing, player, onNavigate)
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
                DropText("${request.label} wants to browse your shared folders.", style = type.body)
                val detail = listOf(request.model, request.deviceName).filter { it.isNotBlank() && it != request.label }.joinToString(" \u00b7 ")
                if (detail.isNotEmpty()) DropText(detail, style = type.caption, color = secondary)
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
private fun CameraRequestCard(request: CameraRequest, onApprove: (String, Boolean) -> Unit, onDeny: (String) -> Unit) {
    val type = DropTheme.type
    val secondary = DropTheme.colors.textSecondary
    DropCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DropSpacing.m)) {
            Aubree(state = AubreeState.WELCOME, size = 64.dp, contentDescription = "")
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(DropSpacing.xs)) {
                DropText("Camera Node request", style = type.subtitle)
                DropText("${request.deviceName} wants to share its camera and microphone with your other devices.", style = type.body)
            }
        }
        DropText(
            "The video and sound go straight to whoever watches. They do not pass through this phone. Allow only a device you trust.",
            style = type.caption, color = secondary
        )
        Row(horizontalArrangement = Arrangement.spacedBy(DropSpacing.s)) {
            DropButton("Deny", { onDeny(request.id) }, Modifier.weight(1f), DropButtonStyle.Secondary)
            DropButton("Allow this time", { onApprove(request.id, false) }, Modifier.weight(1f), DropButtonStyle.Primary)
        }
        DropButton("Always allow ${request.deviceName}", { onApprove(request.id, true) }, Modifier.fillMaxWidth(), DropButtonStyle.Subtle)
    }
}

@Composable
private fun ServersContent(network: NetworkUi, onSendTo: (String) -> Unit) {
    val type = DropTheme.type
    val secondary = DropTheme.colors.textSecondary
    val c = DropTheme.colors
    network.pairing?.let { p ->
        DropCard {
            val failed = p.phase == io.github.trebronwayne.droplink.core.PeerPairing.Phase.Failed
            val done = p.phase == io.github.trebronwayne.droplink.core.PeerPairing.Phase.Done
            DropText(
                when {
                    done -> "Paired with ${p.name}"
                    failed -> "Could not pair with ${p.name}"
                    else -> "Pairing with ${p.name}"
                },
                style = type.subtitle
            )
            if (p.code.isNotEmpty() && !failed && !done) {
                DropText(p.code, style = type.title, fontFamily = FontFamily.Monospace)
                DropText("The owner of ${p.name} must see this same code on their phone and approve it.", style = type.caption, color = secondary)
            }
            DropText(p.message, style = type.body, color = if (failed) c.danger else secondary)
            if (done || failed) {
                DropButton("Done", network.onDismissPair, Modifier.fillMaxWidth(), DropButtonStyle.Secondary)
            } else {
                DropButton("Cancel", network.onCancelPair, Modifier.fillMaxWidth(), DropButtonStyle.Secondary)
            }
        }
    }
    if (network.nodes.isEmpty()) {
        AubreeEmptyState(
            AubreeState.SEARCHING, "Looking for other DropLink nodes",
            "Make sure the other phone is hosting and both are on the same Wi-Fi or hotspot. Some phones cannot see devices from the hotspot side; you can still type the address shown on the other phone."
        )
    } else {
        network.nodes.forEach { node ->
            val peer = network.peers.firstOrNull { it.nodeId == node.nodeId }
            val st = network.peerStatus[node.nodeId]
            DropCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DropIconTile(Icons.Filled.Dns)
                    Column(Modifier.weight(1f).padding(start = DropSpacing.m)) {
                        DropText(node.name, style = type.bodyStrong)
                        DropText("${node.host}:${node.port}", style = type.caption, color = secondary, fontFamily = FontFamily.Monospace)
                    }
                    if (peer != null) DropChip("Paired", c.success)
                }
                if (node.fingerprint.length >= 16) {
                    DropText(
                        "Fingerprint " + node.fingerprint.chunked(2).take(8).joinToString(":") + "…",
                        style = type.caption, color = secondary, fontFamily = FontFamily.Monospace
                    )
                }
                if (peer != null && st != null) {
                    val parts = buildList {
                        if (st.cameras > 0) add("${st.cameras} camera${if (st.cameras == 1) "" else "s"} live")
                        if (st.freeBytes >= 0) add("${io.github.trebronwayne.droplink.core.formatBytes(st.freeBytes)} free")
                        add(
                            when (st.uploadPolicy) {
                                "always" -> "accepts your files automatically"
                                "blocked" -> "blocks files from this phone"
                                else -> "asks its owner before accepting"
                            }
                        )
                    }
                    DropText(parts.joinToString(" \u00b7 "), style = type.caption, color = secondary)
                }
                if (peer != null) {
                    DropButton("Send files", { onSendTo(node.nodeId) }, Modifier.fillMaxWidth(), DropButtonStyle.Primary, Icons.Outlined.SwapVert)
                    DropButton("Unpair", { network.onUnpair(node.nodeId) }, Modifier.fillMaxWidth(), DropButtonStyle.Subtle, Icons.Outlined.Delete)
                } else {
                    DropButton("Pair with this phone", { network.onPair(node) }, Modifier.fillMaxWidth(), DropButtonStyle.Primary, Icons.Outlined.SwapVert)
                }
                DropButton("Open in browser", { network.onOpen(node) }, Modifier.fillMaxWidth(), DropButtonStyle.Secondary, Icons.Outlined.OpenInBrowser)
            }
        }
        DropText(
            "Pair two DropLink phones to send files straight between them. Each phone's owner approves the pairing once. To send both ways, pair from both phones.",
            style = type.caption, color = secondary
        )
    }
    val away = network.peers.filter { p -> network.nodes.none { it.nodeId == p.nodeId } }
    if (away.isNotEmpty()) {
        DropSectionTitle("Paired, not nearby")
        away.forEach { p ->
            DropCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DropIconTile(Icons.Filled.Dns)
                    DropText(p.name, style = type.bodyStrong, modifier = Modifier.weight(1f).padding(start = DropSpacing.m))
                    DropButton("Unpair", { network.onUnpair(p.nodeId) }, style = DropButtonStyle.Subtle, icon = Icons.Outlined.Delete)
                }
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
private fun SettingsContent(nodeId: String, host: HostUi, pairing: PairingUi, player: PlayerUi, onNavigate: (Destination) -> Unit) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val type = DropTheme.type
    val secondary = DropTheme.colors.textSecondary
    val groupGap = DropSpacing.s

    // 1. This phone: who it is
    DropSettingsGroup(
        "This phone",
        footer = "The ID comes from a public key. Its private key stays inside Android Keystore and is never exported."
    ) {
        DropSettingRow(
            "Device ID", subtitle = nodeId, icon = Icons.Outlined.Badge,
            trailing = {
                DropButton(
                    "Copy", { clipboard.setText(AnnotatedString(nodeId)) },
                    style = DropButtonStyle.Subtle, enabled = nodeId.startsWith("dl-")
                )
            }
        )
        DropRowDivider()
        DropExpandableRow("Certificate fingerprint", Icons.Outlined.Fingerprint, subtitle = "SHA-256") {
            DropCodeBox(host.fingerprint)
            DropText(
                "Compare this with the certificate details your browser shows before trusting this phone.",
                style = type.caption, color = secondary
            )
        }
    }

    Spacer(Modifier.height(groupGap))

    // 2. Hosting: how the server behaves
    DropSettingsGroup(
        "Hosting",
        footer = "Keep Battery Saver and Data Saver off while hosting. They make Android pause background apps, so other devices lose the connection when you leave DropLink."
    ) {
        DropLinkRow(
            "Paired devices", Icons.Outlined.Devices, { onNavigate(Destination.Devices) },
            subtitle = if (pairing.trusted.isEmpty()) "None yet" else "${pairing.trusted.size} paired. Manage them in Devices."
        )
        DropRowDivider()
        DropSettingRow(
            "Battery optimization",
            subtitle = if (host.batteryRestricted) "On. Android may pause hosting in the background." else "Off. Hosting can keep running.",
            icon = Icons.Outlined.BatteryChargingFull,
            onClick = host.onOpenBatterySettings,
            trailing = { DropChevron() }
        )
        DropRowDivider()
        DropSwitchRow(
            "Notification sound",
            checked = host.notificationSound, onCheckedChange = host.onNotificationSoundChange,
            subtitle = "Plays for pairing requests and files. Follows your notification volume and Do Not Disturb.",
            icon = Icons.Outlined.NotificationsNone
        )
        DropRowDivider()
        DropSwitchRow(
            "Skip the browser warning (plain HTTP)",
            checked = host.useHttp, onCheckedChange = host.onUseHttpChange, enabled = !host.isHosting,
            subtitle = if (host.isHosting) "Stop hosting to change this." else "Turns encryption off so browsers stop warning. Files still transfer, but anyone on the Wi-Fi could read them, and camera sharing and installing the web app stop working. Use only on a network you trust.",
            icon = Icons.Outlined.Science
        )
    }

    Spacer(Modifier.height(groupGap))

    // Player: behaviour, lyrics and where lyrics and subtitles are looked for
    val textFolders by io.github.trebronwayne.droplink.player.Sidecars.folders.collectAsStateWithLifecycle()
    val resumeVideos by io.github.trebronwayne.droplink.player.PlayerPrefs.resumeVideos.collectAsStateWithLifecycle()
    val keepOn by io.github.trebronwayne.droplink.player.PlayerPrefs.keepScreenOn.collectAsStateWithLifecycle()
    val lyricsPreview by io.github.trebronwayne.droplink.player.PlayerPrefs.lyricsPreview.collectAsStateWithLifecycle()
    val lyricsSize by io.github.trebronwayne.droplink.player.PlayerPrefs.lyricsSize.collectAsStateWithLifecycle()
    val minSeconds by io.github.trebronwayne.droplink.player.PlayerPrefs.minMusicSeconds.collectAsStateWithLifecycle()
    DropSettingsGroup("Player") {
        DropSwitchRow(
            "Continue videos where I stopped", resumeVideos, io.github.trebronwayne.droplink.player.PlayerPrefs::setResumeVideos,
            subtitle = "A video you left unfinished starts from that point, and a Continue button appears while you browse.", icon = Icons.Outlined.PlayCircle
        )
        DropRowDivider()
        DropSwitchRow(
            "Keep the screen on for video", keepOn, io.github.trebronwayne.droplink.player.PlayerPrefs::setKeepScreenOn,
            subtitle = "The screen stays awake while a video plays.", icon = Icons.Outlined.Info
        )
        DropRowDivider()
        DropSwitchRow(
            "Lyrics next to the cover", lyricsPreview, io.github.trebronwayne.droplink.player.PlayerPrefs::setLyricsPreview,
            subtitle = "Shows the line being sung beside the cover. Tap it to read all the lyrics.", icon = Icons.Outlined.Info
        )
        DropRowDivider()
        Column(Modifier.padding(DropSpacing.l), verticalArrangement = Arrangement.spacedBy(DropSpacing.s)) {
            DropText("Lyrics text size", style = type.bodyStrong)
            DropSegmented(listOf(0 to "Small", 1 to "Medium", 2 to "Large"), lyricsSize, io.github.trebronwayne.droplink.player.PlayerPrefs::setLyricsSize)
        }
        DropRowDivider(inset = false)
        Column(Modifier.padding(DropSpacing.l), verticalArrangement = Arrangement.spacedBy(DropSpacing.s)) {
            DropText("Leave out music shorter than", style = type.bodyStrong)
            DropSegmented(
                listOf(10 to "10 s", 30 to "30 s", 60 to "1 min"), minSeconds,
                { io.github.trebronwayne.droplink.player.PlayerPrefs.setMinMusicSeconds(it); player.onRescan() }
            )
        }
        DropRowDivider(inset = false)
        DropSettingRow(
            "Clear recent", icon = Icons.Outlined.History, subtitle = "Empties the Recent list. Favorites stay.",
            trailing = { DropButton("Clear", io.github.trebronwayne.droplink.player.PlayerStore::clearRecent, style = DropButtonStyle.Subtle) }
        )
        DropRowDivider()
        DropSettingRow(
            "Forget where videos stopped", icon = Icons.Outlined.History, subtitle = "Videos start from the beginning again.",
            trailing = { DropButton("Forget", io.github.trebronwayne.droplink.player.PlayerStore::clearPositions, style = DropButtonStyle.Subtle) }
        )
    }

    Spacer(Modifier.height(groupGap))

    DropSettingsGroup(
        "Lyrics and subtitles",
        footer = "DropLink looks for a lyrics or subtitle file with the same name as the song or video (Song.mp3 and Song.lrc, Movie.mkv and Movie.en.srt), and also reads lyrics stored inside mp3 and flac files. Android only lets apps read these small files in folders you open to them, so add the folder where yours are."
    ) {
        textFolders.forEach { folder ->
            DropSettingRow(
                io.github.trebronwayne.droplink.player.Sidecars.label(folder), icon = Icons.Outlined.Folder,
                subtitle = "Searched for lyrics and subtitles",
                trailing = {
                    DropButton("Remove", {
                        io.github.trebronwayne.droplink.player.Sidecars.removeFolder(context, folder)
                        io.github.trebronwayne.droplink.player.PlayerHub.reloadText()
                    }, style = DropButtonStyle.Subtle)
                }
            )
            DropRowDivider()
        }
        DropLinkRow(
            "Add a lyrics and subtitles folder", Icons.Outlined.Folder, player.onAddTextFolder,
            subtitle = if (textFolders.isEmpty()) "Pick the folder that holds your .lrc and .srt files" else "Add another folder"
        )
    }

    Spacer(Modifier.height(groupGap))

    // 4. Activity: the log, folded away until needed
    DropSettingsGroup("Diagnostics") {
        DropExpandableRow("Hosting activity", Icons.Outlined.History, subtitle = "Pauses, restarts and background changes") {
            DropText(
                "If hosting stops unexpectedly, email this log to ${Support.EMAIL}.",
                style = type.caption, color = secondary
            )
            DropCodeBox(
                if (host.events.isBlank()) "Nothing recorded yet."
                else host.events.lines().filter { it.isNotBlank() }.takeLast(14).joinToString("\n")
            )
            Row(horizontalArrangement = Arrangement.spacedBy(DropSpacing.s)) {
                DropButton(
                    "Email log", { Support.report(context, "DropLink hosting log", host.events) },
                    Modifier.weight(1f), DropButtonStyle.Primary, Icons.Outlined.Email, enabled = host.events.isNotBlank()
                )
                DropButton(
                    "Copy", { clipboard.setText(AnnotatedString(host.events)) },
                    Modifier.weight(1f), DropButtonStyle.Secondary, Icons.Outlined.ContentCopy, enabled = host.events.isNotBlank()
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(DropSpacing.s)) {
                DropButton("Refresh", host.onRefreshEvents, Modifier.weight(1f), DropButtonStyle.Secondary, Icons.Outlined.Refresh)
                DropButton("Clear", host.onClearEvents, Modifier.weight(1f), DropButtonStyle.Secondary, Icons.Outlined.Delete)
            }
        }
    }

    Spacer(Modifier.height(groupGap))

    // 5. What is next
    DropSettingsGroup("Roadmap") {
        DropExpandableRow("Coming soon", Icons.Outlined.AutoAwesome, subtitle = "What is planned for DropLink") {
            listOf(
                "More camera tools: motion alerts and scheduled recording for Camera Nodes",
                "Remote presentation: control slides on a big screen from the phone",
                "People: see who is connected, send messages, files and attachments",
                "Feedback: visitors write to you and you reply"
            ).forEach { DropText("\u2022  $it", style = type.caption, color = secondary) }
        }
    }

    Spacer(Modifier.height(groupGap))

    // 6. About and support
    DropSettingsGroup(
        "About",
        footer = "DropLink is free and open source. Your phone. Your server. Your network."
    ) {
        DropSettingRow(
            "DropLink", icon = Icons.Outlined.Info,
            trailing = { DropText("v${BuildConfig.VERSION_NAME}", style = type.caption, color = secondary) }
        )
        DropRowDivider()
        DropLinkRow("Support on Ko-fi", Icons.Outlined.FavoriteBorder, { Support.openUrl(context, Support.KOFI) }, subtitle = "Help keep DropLink growing")
        DropRowDivider()
        DropLinkRow("Send feedback", Icons.Outlined.Email, { Support.email(context, "DropLink feedback", "") })
        DropRowDivider()
        DropLinkRow("Report an issue", Icons.Outlined.BugReport, { Support.openUrl(context, Support.ISSUES) }, subtitle = "Opens GitHub")
        DropRowDivider()
        DropLinkRow("Privacy Policy", Icons.Outlined.PrivacyTip, { Support.openUrl(context, Support.PRIVACY) })
        DropRowDivider()
        DropLinkRow("Source code and license", Icons.Outlined.Code, { Support.openUrl(context, Support.REPO) })
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
            verticalArrangement = Arrangement.spacedBy(DropSpacing.m + 2.dp)
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

/** Navigation pane for tablets and large screens: soft rounded selection, no hard edges. */
@Composable
private fun NavigationPane(current: Destination, onSelect: (Destination) -> Unit) {
    val c = DropTheme.colors
    Column(
        modifier = Modifier.width(248.dp).fillMaxHeight().padding(DropSpacing.m),
        verticalArrangement = Arrangement.spacedBy(DropSpacing.xs)
    ) {
        Row(Modifier.padding(horizontal = DropSpacing.m, vertical = DropSpacing.m), verticalAlignment = Alignment.CenterVertically) {
            DropLogo(Modifier.size(32.dp))
            DropText("DropLink", style = DropTheme.type.subtitle, modifier = Modifier.padding(start = DropSpacing.m))
        }
        Spacer(Modifier.height(DropSpacing.s))
        Destination.entries.filter { it.inNav }.forEach { item ->
            val selected = item == current
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(DropRadius.medium))
                    .background(if (selected) c.subtleSelected else androidx.compose.ui.graphics.Color.Transparent)
                    .selectable(selected = selected, onClick = { onSelect(item) }, role = Role.Tab)
                    .padding(horizontal = DropSpacing.m),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (selected) item.selectedIcon else item.icon, contentDescription = null,
                    tint = if (selected) c.textPrimary else c.textSecondary,
                    modifier = Modifier.padding(end = DropSpacing.m).size(21.dp)
                )
                DropText(
                    item.label, style = if (selected) DropTheme.type.bodyStrong else DropTheme.type.body,
                    color = if (selected) c.textPrimary else c.textSecondary
                )
            }
        }
    }
}

/** Bottom bar on phones: a soft pill sits behind the active icon. */
@Composable
private fun BottomNavigation(current: Destination, onSelect: (Destination) -> Unit) {
    val c = DropTheme.colors
    Column(Modifier.background(c.card)) {
        HorizontalDivider(color = c.stroke)
        Row(Modifier.fillMaxWidth().padding(horizontal = DropSpacing.xs)) {
            Destination.entries.filter { it.inNav }.forEach { item ->
                val selected = item == current
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 64.dp)
                        .selectable(selected = selected, onClick = { onSelect(item) }, role = Role.Tab)
                        .padding(vertical = DropSpacing.s),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        Modifier
                            .width(60.dp).height(32.dp)
                            .background(
                                if (selected) c.subtleSelected else androidx.compose.ui.graphics.Color.Transparent,
                                RoundedCornerShape(DropRadius.pill)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (selected) item.selectedIcon else item.icon, contentDescription = null,
                            tint = if (selected) c.textPrimary else c.textSecondary, modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    DropText(
                        item.label, style = DropTheme.type.caption,
                        color = if (selected) c.textPrimary else c.textSecondary
                    )
                }
            }
        }
    }
}
