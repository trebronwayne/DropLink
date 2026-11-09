package io.github.trebronwayne.droplink.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.outlined.Share
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import io.github.trebronwayne.droplink.core.HostAddress
import io.github.trebronwayne.droplink.core.Qr
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.trebronwayne.droplink.core.StatsSnapshot
import io.github.trebronwayne.droplink.ui.theme.DropRadius
import io.github.trebronwayne.droplink.ui.theme.DropSpacing
import io.github.trebronwayne.droplink.ui.theme.DropTheme

@Composable
fun DropText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = DropTheme.type.body,
    color: Color = DropTheme.colors.textPrimary,
    fontFamily: FontFamily? = null,
    textAlign: TextAlign? = null
) {
    Text(text = text, modifier = modifier, color = color, style = style, fontFamily = fontFamily, textAlign = textAlign)
}

/** Fluent card: flat surface, 1dp stroke, 8dp corners, no heavy shadow. */
@Composable
fun DropCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = DropTheme.colors
    val shape = RoundedCornerShape(DropRadius.medium)
    val border = BorderStroke(1.dp, colors.stroke)
    val inner: @Composable () -> Unit = {
        Column(
            modifier = Modifier.fillMaxWidth().padding(DropSpacing.l),
            verticalArrangement = Arrangement.spacedBy(DropSpacing.m),
            content = content
        )
    }
    if (onClick != null) {
        Surface(
            onClick = onClick, modifier = modifier, shape = shape, color = colors.card,
            contentColor = colors.textPrimary, border = border, content = inner
        )
    } else {
        Surface(
            modifier = modifier, shape = shape, color = colors.card,
            contentColor = colors.textPrimary, border = border, content = inner
        )
    }
}

enum class DropButtonStyle { Primary, Secondary, Subtle }

@Composable
fun DropButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: DropButtonStyle = DropButtonStyle.Primary,
    icon: ImageVector? = null,
    enabled: Boolean = true
) {
    val c = DropTheme.colors
    val shape = RoundedCornerShape(DropRadius.small)
    val padding = PaddingValues(horizontal = DropSpacing.l)
    val sized = modifier.heightIn(min = 44.dp)
    val label: @Composable RowScope.() -> Unit = {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(DropSpacing.s))
        }
        Text(text, style = DropTheme.type.bodyStrong)
    }
    when (style) {
        DropButtonStyle.Primary -> Button(
            onClick = onClick, modifier = sized, enabled = enabled, shape = shape,
            colors = ButtonDefaults.buttonColors(containerColor = c.brand, contentColor = c.onBrand),
            contentPadding = padding, content = label
        )
        DropButtonStyle.Secondary -> OutlinedButton(
            onClick = onClick, modifier = sized, enabled = enabled, shape = shape,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = c.textPrimary),
            border = BorderStroke(1.dp, c.strokeStrong),
            contentPadding = padding, content = label
        )
        DropButtonStyle.Subtle -> TextButton(
            onClick = onClick, modifier = sized, enabled = enabled, shape = shape,
            colors = ButtonDefaults.textButtonColors(contentColor = c.brand),
            contentPadding = padding, content = label
        )
    }
}

@Composable
fun DropIconTile(icon: ImageVector, modifier: Modifier = Modifier) {
    val c = DropTheme.colors
    Box(
        modifier = modifier.size(36.dp).background(c.brandTint, RoundedCornerShape(DropRadius.small)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = c.brand, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun DropStatusIndicator(active: Boolean, activeLabel: String = "Hosting", inactiveLabel: String = "Offline") {
    val c = DropTheme.colors
    val color = if (active) c.success else c.offline
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(DropSpacing.s))
        DropText(if (active) activeLabel else inactiveLabel, style = DropTheme.type.caption, color = color)
    }
}

@Composable
fun DropInfoCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    DropCard(modifier = modifier, onClick = onClick) {
        DropIconTile(icon)
        DropText(title, style = DropTheme.type.bodyStrong)
        DropText(subtitle, style = DropTheme.type.caption, color = DropTheme.colors.textSecondary)
    }
}

@Composable
fun DropSectionTitle(text: String) {
    DropText(text, style = DropTheme.type.subtitle, modifier = Modifier.padding(top = DropSpacing.s))
}

@Composable
fun DropEmptyState(icon: ImageVector, title: String, body: String) {
    DropCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = DropSpacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(DropSpacing.s)
        ) {
            DropIconTile(icon)
            DropText(title, style = DropTheme.type.subtitle, textAlign = TextAlign.Center)
            DropText(body, style = DropTheme.type.body, color = DropTheme.colors.textSecondary, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun DropDeviceCard(
    nodeId: String,
    isHosting: Boolean,
    port: Int,
    fingerprint: String,
    addresses: List<HostAddress>,
    onStartHosting: () -> Unit,
    onStopHosting: () -> Unit,
    onRefreshAddresses: () -> Unit,
    error: String? = null,
    stats: StatsSnapshot? = null,
    testResults: List<String> = emptyList(),
    onTest: () -> Unit = {},
    onOpenLocal: () -> Unit = {},
    https: Boolean = true
) {
    val context = LocalContext.current
    val urls = addresses.map { it.url(port, https) }
    val type = DropTheme.type
    val secondary = DropTheme.colors.textSecondary
    val clipboard = LocalClipboardManager.current
    DropCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DropIconTile(Icons.Outlined.PhoneAndroid)
            Column(Modifier.weight(1f).padding(start = DropSpacing.m)) {
                DropText("This device", style = type.subtitle)
                DropText("DropLink node", style = type.caption, color = secondary)
            }
            DropStatusIndicator(active = isHosting)
        }
        Column(verticalArrangement = Arrangement.spacedBy(DropSpacing.xs)) {
            DropText("Device identity", style = type.caption, color = secondary)
            DropText(nodeId, style = type.bodyStrong, fontFamily = FontFamily.Monospace)
        }
        if (isHosting) {
            Column(verticalArrangement = Arrangement.spacedBy(DropSpacing.xs)) {
                DropText("Open in a browser on another device", style = type.caption, color = secondary)
                if (addresses.isEmpty()) {
                    DropText("No network address yet. Connect to Wi-Fi or turn on your hotspot, then tap Refresh.", style = type.body)
                } else {
                    DropText("Scan the code with the other device's camera. It opens DropLink with nothing to type.", style = type.caption, color = secondary)
                    addresses.forEach { a ->
                        val url = a.url(port, https)
                        Column(Modifier.fillMaxWidth().padding(top = DropSpacing.s), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(DropSpacing.xs)) {
                            DropText(a.kind.label, style = type.caption, color = secondary)
                            QrImage(url)
                            DropText(url, style = type.bodyStrong, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(DropSpacing.xs)) {
                DropText("Certificate fingerprint", style = type.caption, color = secondary)
                DropText(fingerprint.take(23) + "…", style = type.bodyStrong, fontFamily = FontFamily.Monospace)
                DropText(
                    "Browsers warn that this certificate is self-signed. That is expected. Before continuing, compare this fingerprint with the one in the browser's certificate details.",
                    style = type.caption, color = secondary
                )
            }
            if (stats != null) {
                DropText(
                    "Active transfers: ${stats.activeTransfers}  •  ${"%.1f".format(stats.bytesPerSecond / 1_048_576.0)} MB/s  •  Served ${formatBytes(stats.totalBytes)}",
                    style = type.caption, color = secondary
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(DropSpacing.s)) {
                DropButton(
                    "Copy", { urls.firstOrNull()?.let { clipboard.setText(AnnotatedString(it)) } },
                    Modifier.weight(1f), DropButtonStyle.Secondary, Icons.Outlined.ContentCopy, enabled = urls.isNotEmpty()
                )
                DropButton(
                    "Share", {
                        urls.firstOrNull()?.let { link ->
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
                            context.startActivity(Intent.createChooser(send, "Share DropLink address").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    },
                    Modifier.weight(1f), DropButtonStyle.Secondary, Icons.Outlined.Share, enabled = urls.isNotEmpty()
                )
                DropButton("Refresh", onRefreshAddresses, style = DropButtonStyle.Subtle, icon = Icons.Outlined.Refresh)
            }
            if (testResults.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(DropSpacing.xs)) {
                    testResults.forEach { DropText(it, style = type.caption, fontFamily = FontFamily.Monospace) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(DropSpacing.s)) {
                DropButton("Open on this phone", onOpenLocal, Modifier.weight(1f), DropButtonStyle.Secondary, Icons.Outlined.OpenInBrowser)
                DropButton("Test connection", onTest, Modifier.weight(1f), DropButtonStyle.Secondary, Icons.Outlined.NetworkCheck)
            }
            DropButton("Stop hosting", onStopHosting, Modifier.fillMaxWidth(), DropButtonStyle.Secondary, Icons.Filled.Stop)
        } else {
            DropText("Make this phone available as a local DropLink node. Only the folders you share are reachable.", style = type.body)
            if (error != null) DropText(error, style = type.caption, color = DropTheme.colors.danger)
            DropButton("Start hosting", onStartHosting, Modifier.fillMaxWidth(), DropButtonStyle.Primary, Icons.Filled.PlayArrow)
        }
    }
}

private fun formatBytes(bytes: Long): String =
    if (bytes >= 1_073_741_824L) "%.2f GB".format(bytes / 1_073_741_824.0) else "%.0f MB".format(bytes / 1_048_576.0)


/** A QR code drawn natively; always black on white with a quiet zone, so any phone camera can read it. */
@Composable
fun QrImage(text: String, modifier: Modifier = Modifier.size(190.dp)) {
    val grid = remember(text) { try { Qr.modules(text) } catch (_: Throwable) { null } } ?: return
    Canvas(modifier.background(Color.White, RoundedCornerShape(14.dp)).padding(12.dp)) {
        val n = grid.size
        val cell = size.minDimension / n
        for (y in 0 until n) for (x in 0 until n) {
            if (grid[y][x]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.6f, cell + 0.6f))
        }
    }
}
