@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package io.github.trebronwayne.droplink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trebronwayne.aubree.AubreeState
import io.github.trebronwayne.droplink.core.formatBytes
import io.github.trebronwayne.droplink.player.LiveStream
import io.github.trebronwayne.droplink.player.MediaEntry
import io.github.trebronwayne.droplink.player.PlayerHub
import io.github.trebronwayne.droplink.player.PlayerStore
import io.github.trebronwayne.droplink.ui.components.AubreeEmptyState
import io.github.trebronwayne.droplink.ui.components.DropButton
import io.github.trebronwayne.droplink.ui.components.DropButtonStyle
import io.github.trebronwayne.droplink.ui.components.DropText
import io.github.trebronwayne.droplink.ui.theme.DropRadius
import io.github.trebronwayne.droplink.ui.theme.DropSpacing
import io.github.trebronwayne.droplink.ui.theme.DropTheme

/** The tabs of the library. Music and videos are kept apart; "Recent" is what was played, whichever kind. */
internal enum class PlayerTab(val label: String) { Recent("Recent"), Music("Music"), Videos("Videos"), Favorites("Favorites") }

@Composable
private fun TabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = DropTheme.colors
    Text(
        label, color = if (selected) c.onBrand else c.textPrimary, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(DropRadius.pill)).background(if (selected) c.brand else c.field).clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp)
    )
}

/**
 * The list under the player. [shown] is already the right list for [tab]; tapping an item plays it and the rest
 * of that list follows. [header] (title and actions on a phone) scrolls away with the list.
 */
@Composable
internal fun LibraryList(
    ui: PlayerUi, hub: PlayerHub.State, tab: PlayerTab, setTab: (PlayerTab) -> Unit, shown: List<MediaEntry>, library: List<MediaEntry>,
    loaded: Boolean, onPlay: (MediaEntry) -> Unit, onContinue: (MediaEntry) -> Unit, header: (@Composable () -> Unit)?, modifier: Modifier
) {
    val c = DropTheme.colors
    val live by LiveStream.enabled.collectAsStateWithLifecycle()
    val favorites by PlayerStore.favorites.collectAsStateWithLifecycle()
    val resumes by PlayerStore.resume.collectAsStateWithLifecycle()
    var dismissed by rememberSaveable { mutableStateOf<String?>(null) }

    // The unfinished video most recently watched, if it is not the one playing now: offered as a floating button.
    val candidate = remember(resumes, library, hub.current?.id) {
        resumes.asSequence().filter { it.fraction < 0.95f }.mapNotNull { r -> library.firstOrNull { r.ref.matches(it) && it.id != hub.current?.id }?.let { it to r } }.firstOrNull()
    }

    Box(modifier) {
        LazyColumn(Modifier.fillMaxHeight().fillMaxWidth(), contentPadding = PaddingValues(start = DropSpacing.l, end = DropSpacing.l, top = DropSpacing.m, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (header != null) item(key = "header") { Column(Modifier.padding(bottom = DropSpacing.m)) { header() } }
            item(key = "tabs") {
                Row(Modifier.fillMaxWidth().padding(vertical = DropSpacing.s), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(DropSpacing.s)) {
                        PlayerTab.entries.forEach { TabChip(it.label, it == tab) { setTab(it) } }
                    }
                    IconButton(onClick = ui.onRescan) { Icon(Icons.Outlined.Refresh, "Refresh the list", tint = c.textSecondary) }
                }
            }
            when {
                !ui.hasAccess -> item(key = "access") {
                    Column(Modifier.fillMaxWidth().padding(vertical = DropSpacing.xl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(DropSpacing.m)) {
                        DropText("Allow DropLink to see your music and videos so it can list them here, newest first.", style = DropTheme.type.body, color = c.textSecondary, textAlign = TextAlign.Center)
                        DropButton("Allow access", ui.onGrantAccess, style = DropButtonStyle.Primary)
                    }
                }
                shown.isEmpty() -> item(key = "empty") { EmptyTab(tab, loaded) }
                else -> items(shown, key = { "${tab.ordinal}:${it.id}" }) { e ->
                    val resume = resumes.firstOrNull { it.ref.matches(e) }
                    MediaRow(
                        e, e.id == hub.current?.id, hub.playing && e.id == hub.current?.id, favorites.any { it.ref.matches(e) },
                        if (e.isVideo) resume?.fraction else null, live, tab == PlayerTab.Recent
                    ) { onPlay(e) }
                }
            }
        }
        val pick = candidate
        if (pick != null && pick.first.id != dismissed && ui.hasAccess) {
            ContinueButton(pick.first, pick.second, { onContinue(pick.first) }, { dismissed = pick.first.id }, Modifier.align(Alignment.BottomCenter).padding(bottom = DropSpacing.l, start = DropSpacing.l, end = DropSpacing.l))
        }
    }
}

@Composable
private fun EmptyTab(tab: PlayerTab, loaded: Boolean) {
    val c = DropTheme.colors
    when (tab) {
        PlayerTab.Recent -> Hint("Nothing played yet", "Music and videos you play show up here, most recent first.")
        PlayerTab.Favorites -> Hint("No favorites yet", "Tap the heart on a song or video, or choose Add to favorites in its menu, to keep it here.")
        PlayerTab.Music -> if (loaded) AubreeEmptyState(
            AubreeState.EMPTY, "No music yet",
            "Music (mp3, m4a, flac, wav, aac, ogg) appears here, newest first. Files you receive show up at the top."
        ) else DropText("Looking for music\u2026", style = DropTheme.type.body, color = c.textSecondary, modifier = Modifier.padding(vertical = DropSpacing.xl))
        PlayerTab.Videos -> if (loaded) AubreeEmptyState(
            AubreeState.EMPTY, "No videos yet",
            "Videos (mp4, mkv, webm, 3gp) appear here, newest first. Files you receive show up at the top."
        ) else DropText("Looking for videos\u2026", style = DropTheme.type.body, color = c.textSecondary, modifier = Modifier.padding(vertical = DropSpacing.xl))
    }
}

@Composable
private fun Hint(title: String, body: String) {
    val c = DropTheme.colors
    Column(Modifier.fillMaxWidth().padding(vertical = DropSpacing.xl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(DropSpacing.s)) {
        DropText(title, style = DropTheme.type.subtitle)
        DropText(body, style = DropTheme.type.body, color = c.textSecondary, textAlign = TextAlign.Center)
    }
}

/** One song or video. Tap to play; the menu (or a long press) has queue and favorite actions. */
@Composable
private fun MediaRow(e: MediaEntry, isCurrent: Boolean, playing: Boolean, favorite: Boolean, resume: Float?, live: Boolean, inRecent: Boolean, onClick: () -> Unit) {
    val c = DropTheme.colors
    val recent = System.currentTimeMillis() / 1000 - e.dateAdded < 86_400
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(DropRadius.medium))
                .background(if (isCurrent) c.subtleSelected else Color.Transparent)
                .combinedClickable(onClick = onClick, onLongClick = { menu = true })
                .padding(start = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DropSpacing.m)
        ) {
            Box {
                if (e.isVideo) Thumb(e, 112.dp, 63.dp, RoundedCornerShape(10.dp)) else Thumb(e, 56.dp, 56.dp, RoundedCornerShape(10.dp))
                if (e.isVideo && e.durationMs > 0) {
                    Text(
                        fmtTime(e.durationMs), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 6.dp, end = 4.dp).background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
                if (resume != null) {
                    // How much of the video has been watched.
                    Box(Modifier.align(Alignment.BottomStart).padding(horizontal = 4.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.35f))) {
                        Box(Modifier.fillMaxWidth(resume).fillMaxHeight().background(c.accent))
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                DropText(e.title, style = DropTheme.type.bodyStrong, maxLines = 2)
                DropText(
                    if (e.isVideo) "Video \u00b7 ${formatBytes(e.size)}" else listOf(e.artist, fmtTime(e.durationMs)).filter { it.isNotBlank() }.joinToString(" \u00b7 "),
                    style = DropTheme.type.caption, color = c.textSecondary, maxLines = 1
                )
            }
            if (recent) Text("NEW", color = c.accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.background(c.brandTint, RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 2.dp))
            if (favorite) Icon(Icons.Filled.Favorite, "Favorite", tint = c.danger, modifier = Modifier.size(15.dp))
            if (isCurrent) Icon(if (playing) Icons.Filled.GraphicEq else Icons.Filled.Pause, null, tint = c.accent, modifier = Modifier.size(20.dp))
            IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) { Icon(Icons.Outlined.MoreVert, "More for ${e.title}", tint = c.textSecondary) }
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text("Play next") }, onClick = { menu = false; PlayerHub.playNext(e) })
            DropdownMenuItem(text = { Text(if (live) "Add to live queue" else "Add to queue") }, onClick = { menu = false; PlayerHub.addToQueue(e) })
            DropdownMenuItem(text = { Text(if (favorite) "Remove from favorites" else "Add to favorites") }, onClick = { menu = false; PlayerStore.toggleFavorite(e) })
            if (resume != null) DropdownMenuItem(text = { Text("Forget where I stopped") }, onClick = { menu = false; PlayerStore.forgetPosition(e) })
            if (inRecent) DropdownMenuItem(text = { Text("Remove from recent") }, onClick = { menu = false; PlayerStore.removeRecent(e) })
        }
    }
}

/** A small floating button that offers to carry on with the video that was left unfinished. */
@Composable
private fun ContinueButton(entry: MediaEntry, resume: PlayerStore.Resume, onContinue: () -> Unit, onDismiss: () -> Unit, modifier: Modifier) {
    val c = DropTheme.colors
    Surface(
        modifier = modifier.clickable(onClick = onContinue), shape = RoundedCornerShape(DropRadius.pill), color = c.brand, contentColor = c.onBrand,
        shadowElevation = 10.dp
    ) {
        Row(Modifier.padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box {
                Thumb(entry, 44.dp, 44.dp, RoundedCornerShape(50))
                Box(Modifier.align(Alignment.Center).size(24.dp).background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
            Column(Modifier.weight(1f, fill = false)) {
                Text("Continue watching", color = c.onBrand, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text("${entry.title} \u00b7 ${fmtTime(resume.positionMs)} of ${fmtTime(resume.durationMs)}", color = c.onBrand.copy(alpha = 0.75f), fontSize = 11.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(34.dp)) { Icon(Icons.Outlined.Close, "Hide", tint = c.onBrand.copy(alpha = 0.8f), modifier = Modifier.size(18.dp)) }
        }
    }
}
