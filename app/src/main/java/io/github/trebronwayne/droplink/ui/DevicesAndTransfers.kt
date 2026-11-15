package io.github.trebronwayne.droplink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.trebronwayne.aubree.AubreeState
import io.github.trebronwayne.droplink.core.DeliveryState
import io.github.trebronwayne.droplink.core.OutBatch
import io.github.trebronwayne.droplink.core.Presence
import io.github.trebronwayne.droplink.core.PresenceStatus
import io.github.trebronwayne.droplink.core.ReceiveState
import io.github.trebronwayne.droplink.core.ReceivedFiles
import io.github.trebronwayne.droplink.core.TrustedDevice
import io.github.trebronwayne.droplink.core.UploadPolicy
import io.github.trebronwayne.droplink.core.UploadStatus
import io.github.trebronwayne.droplink.core.formatBytes
import io.github.trebronwayne.droplink.ui.components.AubreeEmptyState
import io.github.trebronwayne.droplink.ui.components.DropButton
import io.github.trebronwayne.droplink.ui.components.DropButtonStyle
import io.github.trebronwayne.droplink.ui.components.DropCard
import io.github.trebronwayne.droplink.ui.components.DropChevron
import io.github.trebronwayne.droplink.ui.components.DropRowDivider
import io.github.trebronwayne.droplink.ui.components.DropSectionTitle
import io.github.trebronwayne.droplink.ui.components.DropSegmented
import io.github.trebronwayne.droplink.ui.components.DropSettingRow
import io.github.trebronwayne.droplink.ui.components.DropSettingsGroup
import io.github.trebronwayne.droplink.ui.components.DropText
import io.github.trebronwayne.droplink.ui.theme.DropRadius
import io.github.trebronwayne.droplink.ui.theme.DropSpacing
import io.github.trebronwayne.droplink.ui.theme.DropTheme
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/** The current time, refreshed every few seconds so "online" and "seen 3 min ago" stay true while the screen is open. */
@Composable
fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(3_000)
            now = System.currentTimeMillis()
        }
    }
    return now
}

private fun ago(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        s < 86_400 -> "${s / 3600} h ago"
        else -> "${s / 86_400} d ago"
    }
}

/** "Online", "Seen 3 min ago" or "Offline, seen 2 h ago". */
fun presenceText(status: PresenceStatus, lastSeen: Long?, now: Long): String = when (status) {
    PresenceStatus.Online -> "Online"
    PresenceStatus.Recent -> "Seen ${ago(now - (lastSeen ?: now))}"
    PresenceStatus.Offline -> if (lastSeen == null) "Not seen yet" else "Offline, seen ${ago(now - lastSeen)}"
}

@Composable
private fun DropChip(text: String, color: Color) {
    Box(
        Modifier.background(color.copy(alpha = 0.14f), RoundedCornerShape(DropRadius.pill)).padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        DropText(text, style = DropTheme.type.caption, color = color)
    }
}

@Composable
private fun presenceColor(status: PresenceStatus): Color = when (status) {
    PresenceStatus.Online -> DropTheme.colors.success
    PresenceStatus.Recent -> DropTheme.colors.accent
    PresenceStatus.Offline -> DropTheme.colors.offline
}

@Composable
fun DropProgressBar(fraction: Float, modifier: Modifier = Modifier, done: Boolean = false) {
    val c = DropTheme.colors
    Box(modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(DropRadius.pill)).background(c.field)) {
        Box(
            Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight()
                .background(if (done) c.success else c.accent, RoundedCornerShape(DropRadius.pill))
        )
    }
}

private fun fractionOf(bytes: Long, size: Long): Float = if (size <= 0) 1f else (bytes.toFloat() / size.toFloat()).coerceIn(0f, 1f)

private fun percent(bytes: Long, size: Long): Int = (fractionOf(bytes, size) * 100).toInt()

/* ------------------------------------------------------------------------------------------------ */
/* Devices                                                                                          */
/* ------------------------------------------------------------------------------------------------ */

@Composable
fun DevicesContent(pairing: PairingUi, onSendTo: (String) -> Unit) {
    val type = DropTheme.type
    val c = DropTheme.colors
    val now = rememberNow()
    if (pairing.trusted.isEmpty()) {
        AubreeEmptyState(
            AubreeState.WAIT, "No devices paired yet",
            "Start hosting, then open the address or scan the QR code from Home on another device. When it asks to connect, approve it and it appears here."
        )
        return
    }
    val online = pairing.trusted.count { Presence.status(pairing.presence[it.id], now) == PresenceStatus.Online }
    DropText(
        "$online online \u00b7 ${pairing.trusted.size} paired", style = type.caption, color = c.textSecondary,
        modifier = Modifier.padding(horizontal = DropSpacing.xs)
    )
    // Online devices first, then the most recently seen.
    val sorted = pairing.trusted.sortedWith(
        compareBy<TrustedDevice> { Presence.status(pairing.presence[it.id], now).ordinal }.thenByDescending { pairing.presence[it.id] ?: 0L }
    )
    sorted.forEach { device ->
        val seen = pairing.presence[device.id]
        val status = Presence.status(seen, now)
        DropCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DropSpacing.m)) {
                Box(
                    Modifier.size(44.dp).background(c.brandTint, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    DropText(
                        device.label.trim().take(1).uppercase().ifEmpty { "?" },
                        style = type.title, color = c.accent
                    )
                }
                Column(Modifier.weight(1f)) {
                    DropText(device.label, style = type.subtitle)
                    val line = listOf(device.model, device.platform).filter { it.isNotBlank() }.joinToString(" \u00b7 ")
                    DropText(
                        if (line.isNotEmpty()) line else if (device.person.isNotBlank()) device.name else "Model not shared",
                        style = type.caption, color = c.textSecondary
                    )
                }
                DropChip(
                    when (status) {
                        PresenceStatus.Online -> "Online"
                        PresenceStatus.Recent -> "Recently"
                        PresenceStatus.Offline -> "Offline"
                    },
                    presenceColor(status)
                )
            }
            DropText(
                presenceText(status, seen, now) + "  \u00b7  Paired ${DateFormat.getDateInstance().format(Date(device.createdAt))}",
                style = type.caption, color = c.textSecondary
            )
            DropText("Files this device sends to your phone", style = type.caption, color = c.textSecondary)
            DropSegmented(
                listOf(UploadPolicy.Ask to "Ask me", UploadPolicy.Always to "Always", UploadPolicy.Blocked to "Block"),
                selected = device.uploads,
                onSelect = { pairing.onSetUploadPolicy(device.id, it) }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(DropSpacing.s)) {
                DropButton(
                    "Send files", { onSendTo(device.id) }, Modifier.weight(1f), DropButtonStyle.Primary, Icons.Outlined.FileUpload,
                    enabled = status != PresenceStatus.Offline
                )
                DropButton(
                    "Revoke", { pairing.onRevoke(device.id) }, Modifier.weight(1f), DropButtonStyle.Secondary, Icons.Outlined.Delete
                )
            }
            if (status == PresenceStatus.Offline) {
                DropText(
                    "Files can only be sent to devices that are online or were online in the last few minutes.",
                    style = type.caption, color = c.textSecondary
                )
            }
        }
    }
}

/* ------------------------------------------------------------------------------------------------ */
/* Transfers: Receive / Send                                                                        */
/* ------------------------------------------------------------------------------------------------ */

@Composable
fun TransfersContent(pairing: PairingUi, send: SendUi, preselect: String?, onPreselectUsed: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(0) }
    LaunchedEffect(preselect) { if (preselect != null) tab = 1 }
    DropSegmented(listOf(0 to "Receive", 1 to "Send"), tab, { tab = it })
    if (tab == 0) ReceiveContent(pairing) else SendContent(pairing, send, preselect, onPreselectUsed)
}

@Composable
private fun ReceiveContent(pairing: PairingUi) {
    val type = DropTheme.type
    val c = DropTheme.colors
    val active = pairing.uploads.filter { it.status == UploadStatus.Approved || it.status == UploadStatus.Done }
    if (active.isEmpty() && pairing.received.isEmpty()) {
        AubreeEmptyState(
            AubreeState.EMPTY, "No files received yet",
            "When a paired device sends you files and you approve them, they are saved in ${ReceivedFiles.LOCATION} and listed here. Tap a file to open it with another app."
        )
        return
    }
    active.forEach { request ->
        DropCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    DropText("Receiving from ${request.deviceName}", style = type.subtitle)
                    DropText(
                        "${request.files.size} file${if (request.files.size == 1) "" else "s"} \u00b7 ${formatBytes(request.totalBytes)}",
                        style = type.caption, color = c.textSecondary
                    )
                }
            }
            request.files.forEachIndexed { i, file ->
                val p = pairing.receiving["${request.id}:$i"]
                val bytes = p?.bytes ?: 0L
                val state = p?.state ?: ReceiveState.Waiting
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DropSpacing.s)) {
                        DropText(file.name, style = type.bodyStrong, modifier = Modifier.weight(1f), maxLines = 1)
                        DropText(
                            when (state) {
                                ReceiveState.Waiting -> "Waiting"
                                ReceiveState.Receiving -> "${percent(bytes, file.size)}%"
                                ReceiveState.Paused -> "Paused"
                                ReceiveState.Done -> "Saved"
                            },
                            style = type.caption,
                            color = when (state) {
                                ReceiveState.Done -> c.success
                                ReceiveState.Paused -> c.danger
                                else -> c.textSecondary
                            }
                        )
                    }
                    DropProgressBar(if (state == ReceiveState.Done) 1f else fractionOf(bytes, file.size), done = state == ReceiveState.Done)
                    DropText(
                        when (state) {
                            ReceiveState.Paused -> "Stopped at ${percent(bytes, file.size)}%. It continues from there when the device reconnects."
                            ReceiveState.Done -> formatBytes(file.size)
                            else -> "${formatBytes(bytes)} of ${formatBytes(file.size)}"
                        },
                        style = type.caption, color = c.textSecondary
                    )
                }
            }
        }
    }
    if (pairing.received.isNotEmpty()) {
        DropSettingsGroup("Received", footer = "Saved in ${ReceivedFiles.LOCATION}. Tap a file to open it with another app.") {
            val dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            pairing.received.take(20).forEachIndexed { i, file ->
                if (i > 0) DropRowDivider()
                DropSettingRow(
                    file.name, icon = Icons.Outlined.InsertDriveFile,
                    subtitle = "${formatBytes(file.size)} \u00b7 from ${file.from} \u00b7 ${dateFormat.format(Date(file.at))}",
                    onClick = { pairing.onOpenReceived(file) },
                    trailing = { DropChevron() }
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(DropSpacing.s)) {
            DropButton("Open Downloads", pairing.onOpenDownloads, Modifier.weight(1f), DropButtonStyle.Secondary, Icons.Outlined.Folder)
            DropButton("Clear list", pairing.onClearReceived, Modifier.weight(1f), DropButtonStyle.Secondary)
        }
    }
}

@Composable
private fun SendContent(pairing: PairingUi, send: SendUi, preselect: String?, onPreselectUsed: () -> Unit) {
    val type = DropTheme.type
    val c = DropTheme.colors
    val now = rememberNow()
    var everyone by rememberSaveable { mutableStateOf(true) }
    var chosen by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(preselect) {
        if (preselect != null) {
            everyone = false
            chosen = setOf(preselect)
            onPreselectUsed()
        }
    }
    fun reachable(d: TrustedDevice) = Presence.status(pairing.presence[d.id], now) != PresenceStatus.Offline
    val eligible = pairing.trusted.filter(::reachable)
    val recipients = if (everyone) eligible else eligible.filter { it.id in chosen }

    DropCard {
        DropText("Send files", style = type.subtitle)
        DropText(
            "Choose files from this phone and send them to paired devices. They show up in the device's web page, and a download that stops continues where it left off.",
            style = type.caption, color = c.textSecondary
        )
        DropButton(
            if (send.picked.isEmpty()) "Choose files" else "Add more files", send.onPick, Modifier.fillMaxWidth(),
            if (send.picked.isEmpty()) DropButtonStyle.Primary else DropButtonStyle.Secondary, Icons.Outlined.AttachFile
        )
        if (send.picked.isNotEmpty()) {
            Column {
                send.picked.forEach { file ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            DropText(file.name, style = type.bodyStrong, maxLines = 1)
                            DropText(formatBytes(file.size), style = type.caption, color = c.textSecondary)
                        }
                        IconButton(onClick = { send.onRemovePicked(file) }) {
                            Icon(Icons.Outlined.Close, contentDescription = "Remove ${file.name}", tint = c.textSecondary, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
            DropText(
                "${send.picked.size} file${if (send.picked.size == 1) "" else "s"} \u00b7 ${formatBytes(send.picked.sumOf { it.size })}",
                style = type.caption, color = c.textSecondary
            )
        }
    }

    DropCard {
        DropText("Send to", style = type.subtitle)
        DropSegmented(listOf(true to "All online", false to "Choose devices"), everyone, { everyone = it })
        if (pairing.trusted.isEmpty()) {
            DropText("No devices are paired yet.", style = type.body, color = c.textSecondary)
        } else if (everyone) {
            DropText(
                if (eligible.isEmpty()) "No device is online right now. Devices appear here while their page is open."
                else "${eligible.size} device${if (eligible.size == 1) "" else "s"} online or recently online: " +
                    eligible.joinToString(", ") { it.label } + ".",
                style = type.body, color = c.textSecondary
            )
        } else {
            Column {
                pairing.trusted.forEachIndexed { i, d ->
                    val seen = pairing.presence[d.id]
                    val status = Presence.status(seen, now)
                    val ok = status != PresenceStatus.Offline
                    if (i > 0) DropRowDivider(inset = false)
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .clickable(enabled = ok) { chosen = if (d.id in chosen) chosen - d.id else chosen + d.id },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = ok && d.id in chosen, onCheckedChange = null, enabled = ok,
                            colors = CheckboxDefaults.colors(checkedColor = c.accent, checkmarkColor = Color.White, uncheckedColor = c.strokeStrong)
                        )
                        Column(Modifier.weight(1f).padding(start = DropSpacing.s)) {
                            DropText(d.label, style = type.bodyStrong, color = if (ok) c.textPrimary else c.textSecondary)
                            DropText(
                                listOf(d.model, presenceText(status, seen, now)).filter { it.isNotBlank() }.joinToString(" \u00b7 "),
                                style = type.caption, color = c.textSecondary
                            )
                        }
                        DropChip(
                            when (status) {
                                PresenceStatus.Online -> "Online"
                                PresenceStatus.Recent -> "Recently"
                                PresenceStatus.Offline -> "Offline"
                            },
                            presenceColor(status)
                        )
                    }
                }
            }
            if (eligible.size < pairing.trusted.size) {
                DropText(
                    "Offline devices cannot be chosen. They need to be online, or have been online in the last few minutes.",
                    style = type.caption, color = c.textSecondary
                )
            }
        }
        DropButton(
            when {
                recipients.isEmpty() -> "Send"
                recipients.size == 1 -> "Send to ${recipients[0].label}"
                else -> "Send to ${recipients.size} devices"
            },
            { send.onSend(if (everyone) null else recipients.map { it.id }.toSet()) },
            Modifier.fillMaxWidth(), DropButtonStyle.Primary, Icons.Outlined.FileUpload,
            enabled = send.picked.isNotEmpty() && recipients.isNotEmpty()
        )
    }

    DropSectionTitle("Sent from this phone")
    if (send.batches.isEmpty()) {
        DropText("Nothing sent yet. Files you send appear here with their progress.", style = type.caption, color = c.textSecondary, modifier = Modifier.padding(horizontal = DropSpacing.xs))
    }
    send.batches.forEach { batch -> BatchCard(batch, send.onCancel, send.onRemove) }
}

@Composable
private fun BatchCard(batch: OutBatch, onCancel: (String) -> Unit, onRemove: (String) -> Unit) {
    val type = DropTheme.type
    val c = DropTheme.colors
    DropCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DropSpacing.s)) {
            Column(Modifier.weight(1f)) {
                DropText(
                    "${batch.files.size} file${if (batch.files.size == 1) "" else "s"} \u00b7 ${formatBytes(batch.files.sumOf { it.size })}",
                    style = type.subtitle
                )
                DropText(
                    (if (batch.everyone) "To everyone online" else "To ${batch.targets.size} device${if (batch.targets.size == 1) "" else "s"}") +
                        " \u00b7 " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(batch.createdAt)),
                    style = type.caption, color = c.textSecondary
                )
            }
            when {
                batch.cancelled -> DropChip("Stopped", c.offline)
                batch.finished -> DropChip("Delivered", c.success)
                else -> DropChip("In progress", c.accent)
            }
        }
        batch.targets.forEach { target ->
            val mine = batch.deliveries.filter { it.deviceId == target.deviceId }
            val doneCount = mine.count { it.state == DeliveryState.Done }
            Column(verticalArrangement = Arrangement.spacedBy(DropSpacing.s)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DropText(target.name, style = type.bodyStrong, modifier = Modifier.weight(1f), maxLines = 1)
                    DropText("$doneCount of ${mine.size} received", style = type.caption, color = if (doneCount == mine.size && mine.isNotEmpty()) c.success else c.textSecondary)
                }
                batch.files.forEach { file ->
                    val d = batch.delivery(target.deviceId, file.index)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DropSpacing.s)) {
                            DropText(file.name, style = type.caption, modifier = Modifier.weight(1f), maxLines = 1)
                            DropText(
                                when (d.state) {
                                    DeliveryState.Waiting -> "Waiting for device"
                                    DeliveryState.Downloading -> "${percent(d.bytes, file.size)}%"
                                    DeliveryState.Done -> "Done"
                                    DeliveryState.Interrupted -> "Stopped at ${percent(d.bytes, file.size)}%"
                                },
                                style = type.caption,
                                color = when (d.state) {
                                    DeliveryState.Done -> c.success
                                    DeliveryState.Interrupted -> c.danger
                                    else -> c.textSecondary
                                }
                            )
                        }
                        DropProgressBar(if (d.state == DeliveryState.Done) 1f else fractionOf(d.bytes, file.size), done = d.state == DeliveryState.Done)
                        if (d.state == DeliveryState.Interrupted && !batch.cancelled) {
                            DropText("Continues from the same place when the device downloads it again.", style = type.caption, color = c.textSecondary)
                        }
                    }
                }
            }
        }
        if (batch.cancelled || batch.finished) {
            DropButton("Remove from list", { onRemove(batch.id) }, Modifier.fillMaxWidth(), DropButtonStyle.Secondary, Icons.Outlined.Delete)
        } else {
            DropButton("Stop sharing these files", { onCancel(batch.id) }, Modifier.fillMaxWidth(), DropButtonStyle.Secondary, Icons.Outlined.Close)
        }
    }
}
