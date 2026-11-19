package io.github.trebronwayne.droplink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.trebronwayne.droplink.player.PlayerHub
import io.github.trebronwayne.droplink.ui.components.DropButton
import io.github.trebronwayne.droplink.ui.components.DropButtonStyle
import io.github.trebronwayne.droplink.ui.components.DropText
import io.github.trebronwayne.droplink.ui.theme.DropRadius
import io.github.trebronwayne.droplink.ui.theme.DropSpacing
import io.github.trebronwayne.droplink.ui.theme.DropTheme

/**
 * The queue: what is playing and what comes next. While live streaming is on this is the live queue, the only list
 * paired devices can see and the only order that plays. Items after the current one can be moved or removed.
 */
@Composable
internal fun QueueDialog(live: Boolean, hub: PlayerHub.State, onClose: () -> Unit) {
    val c = DropTheme.colors
    Dialog(onDismissRequest = onClose) {
        Surface(shape = RoundedCornerShape(DropRadius.large), color = c.card, contentColor = c.textPrimary) {
            Column(Modifier.fillMaxWidth().padding(DropSpacing.l), verticalArrangement = Arrangement.spacedBy(DropSpacing.m)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        DropText(if (live) "Live queue" else "Queue", style = DropTheme.type.title)
                        DropText(
                            if (live) "Paired devices see only this list, and only these items play, in this order. Add more with the menu on a song or video."
                            else "What is playing and what comes next. Add more with the menu on a song or video.",
                            style = DropTheme.type.caption, color = c.textSecondary
                        )
                    }
                    IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Close", tint = c.textPrimary) }
                }
                if (hub.queue.isEmpty()) {
                    DropText("The queue is empty. Play something, or use the menu on a song or video to add it.", style = DropTheme.type.body, color = c.textSecondary)
                } else {
                    LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        itemsIndexed(hub.queue, key = { i, e -> "$i:${e.id}" }) { i, e ->
                            val isCurrent = i == hub.index
                            val past = i < hub.index
                            Row(
                                Modifier.fillMaxWidth().background(if (isCurrent) c.subtleSelected else Color.Transparent, RoundedCornerShape(DropRadius.small))
                                    .clickable { PlayerHub.playIndex(i) }.padding(start = 10.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isCurrent) Icon(Icons.Filled.GraphicEq, null, tint = c.accent, modifier = Modifier.size(18.dp))
                                else DropText("${i + 1}", style = DropTheme.type.caption, color = c.textSecondary, modifier = Modifier.size(18.dp))
                                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                    DropText(e.title, style = DropTheme.type.bodyStrong, color = if (past) c.textSecondary else c.textPrimary, maxLines = 1)
                                    DropText(e.artist.ifBlank { if (e.isVideo) "Video" else "Music" }, style = DropTheme.type.caption, color = c.textSecondary, maxLines = 1)
                                }
                                if (i > hub.index) {
                                    if (i - 1 > hub.index) IconButton(onClick = { PlayerHub.moveInQueue(i, i - 1) }, modifier = Modifier.size(34.dp)) { Icon(Icons.Filled.KeyboardArrowUp, "Move up", tint = c.textSecondary) }
                                    if (i < hub.queue.size - 1) IconButton(onClick = { PlayerHub.moveInQueue(i, i + 1) }, modifier = Modifier.size(34.dp)) { Icon(Icons.Filled.KeyboardArrowDown, "Move down", tint = c.textSecondary) }
                                }
                                if (!isCurrent) IconButton(onClick = { PlayerHub.removeFromQueue(i) }, modifier = Modifier.size(34.dp)) { Icon(Icons.Outlined.Close, "Remove from queue", tint = c.textSecondary, modifier = Modifier.size(18.dp)) }
                            }
                        }
                    }
                    if (hub.index + 1 < hub.queue.size) {
                        DropButton("Clear what comes next", { PlayerHub.clearUpcoming() }, Modifier.fillMaxWidth(), DropButtonStyle.Secondary)
                    }
                }
            }
        }
    }
}
