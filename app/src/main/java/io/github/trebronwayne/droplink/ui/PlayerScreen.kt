package io.github.trebronwayne.droplink.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.RepeatOne
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Subject
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import io.github.trebronwayne.droplink.core.formatBytes
import io.github.trebronwayne.droplink.player.LiveStream
import io.github.trebronwayne.droplink.player.MediaEntry
import io.github.trebronwayne.droplink.player.MediaLibrary
import io.github.trebronwayne.droplink.player.PlayerHub
import io.github.trebronwayne.droplink.player.PlayerStore
import io.github.trebronwayne.droplink.ui.components.DropSwitch
import io.github.trebronwayne.droplink.ui.components.DropText
import io.github.trebronwayne.droplink.ui.theme.DropRadius
import io.github.trebronwayne.droplink.ui.theme.DropSpacing
import io.github.trebronwayne.droplink.ui.theme.DropTheme

/** What the player screen needs from the activity: permission, full screen, live streaming and lyrics files. */
data class PlayerUi(
    val hasAccess: Boolean = false,
    val onGrantAccess: () -> Unit = {},
    val fullscreen: Boolean = false,
    val onFullscreen: (Boolean) -> Unit = {},
    val hosting: Boolean = false,
    val liveOn: Boolean = false,
    val onLiveChange: (Boolean) -> Unit = {},
    val onAddTextFolder: () -> Unit = {},
    val onPickTextFile: () -> Unit = {},
    val onRescan: () -> Unit = {},
    /** Opens the app's Settings, where the player's preferences live. */
    val onOpenSettings: () -> Unit = {}
)

/**
 * The player screen. Layout: the stage (video or cover) and the seek bar stay pinned on top; everything else scrolls
 * under them as one list. On a wide screen the stage and details sit on the left and the list on the right.
 * Behaviour lives in [PlayerHub] (playing and queue) and [PlayerStore] (favorites, recent, where videos were left off).
 */
@Composable
fun PlayerScreen(ui: PlayerUi, wide: Boolean) {
    val hub by PlayerHub.state.collectAsStateWithLifecycle()
    val library by MediaLibrary.items.collectAsStateWithLifecycle()
    val loaded by MediaLibrary.loaded.collectAsStateWithLifecycle()
    val text by PlayerHub.text.collectAsStateWithLifecycle()
    val recent by PlayerStore.recent.collectAsStateWithLifecycle()
    val favorites by PlayerStore.favorites.collectAsStateWithLifecycle()
    var tabIndex by rememberSaveable { mutableStateOf(if (PlayerStore.recent.value.isEmpty()) PlayerTab.Music.ordinal else PlayerTab.Recent.ordinal) }
    var lyricsOpen by rememberSaveable { mutableStateOf(false) }
    var queueOpen by rememberSaveable { mutableStateOf(false) }
    val tab = PlayerTab.entries[tabIndex]
    val current = hub.current

    // Each tab is its own list: music and video are never mixed, except in Recent and Favorites, which follow you.
    val shown = remember(library, tab, recent, favorites) {
        when (tab) {
            PlayerTab.Recent -> PlayerStore.resolve(recent, { it.ref }, library)
            PlayerTab.Music -> library.filter { !it.isVideo }
            PlayerTab.Videos -> library.filter { it.isVideo }
            PlayerTab.Favorites -> PlayerStore.resolve(favorites, { it.ref }, library)
        }
    }

    BackHandler(ui.fullscreen) { ui.onFullscreen(false) }

    if (ui.fullscreen && current?.isVideo == true) {
        Box(Modifier.fillMaxSize().background(Color.Black)) { VideoStage(ui, hub, true, Modifier.fillMaxSize()) }
        return
    }

    if (lyricsOpen && current != null && !current.isVideo) {
        LyricsDialog(current, text.lyrics, hub.playing, hub.durationMs) { lyricsOpen = false }
    }
    if (queueOpen) QueueDialog(LiveStream.enabled.collectAsStateWithLifecycle().value, hub) { queueOpen = false }

    val onPlay: (MediaEntry) -> Unit = { e -> PlayerHub.play(shown, shown.indexOfFirst { it.id == e.id }.coerceAtLeast(0)) }
    // Carrying on a video plays it from where it stopped, with the other videos after it.
    val onContinue: (MediaEntry) -> Unit = { e ->
        val videos = library.filter { it.isVideo }
        PlayerHub.play(videos, videos.indexOfFirst { it.id == e.id }.coerceAtLeast(0), resume = true)
    }
    val playNewestMusic = { library.filter { !it.isVideo }.let { if (it.isNotEmpty()) PlayerHub.play(it, 0) } }
    val details: @Composable () -> Unit = { Details(ui, hub, { queueOpen = true }, { lyricsOpen = true }) }

    if (wide) {
        Row(Modifier.fillMaxSize().padding(DropSpacing.l), horizontalArrangement = Arrangement.spacedBy(DropSpacing.l)) {
            Column(Modifier.weight(1.25f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(DropSpacing.m)) {
                Stage(ui, hub, true, playNewestMusic) { lyricsOpen = true }
                if (current != null) {
                    Transport(hub.durationMs, hub.playing)
                    details()
                }
            }
            LibraryList(ui, hub, tab, { tabIndex = it.ordinal }, shown, library, loaded, onPlay, onContinue, null, Modifier.weight(1f).fillMaxHeight())
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Stage(ui, hub, false, playNewestMusic) { lyricsOpen = true }
            if (current != null) Transport(hub.durationMs, hub.playing, Modifier.padding(horizontal = DropSpacing.l))
            LibraryList(
                ui, hub, tab, { tabIndex = it.ordinal }, shown, library, loaded, onPlay, onContinue,
                if (current != null) details else null, Modifier.weight(1f).fillMaxWidth()
            )
        }
    }
}

/** Actions for what is playing, and the live streaming switch. */
@Composable
private fun Details(ui: PlayerUi, hub: PlayerHub.State, openQueue: () -> Unit, openLyrics: () -> Unit) {
    val c = DropTheme.colors
    val text by PlayerHub.text.collectAsStateWithLifecycle()
    val subIndex by PlayerHub.subtitle.collectAsStateWithLifecycle()
    val sleep by PlayerHub.sleep.collectAsStateWithLifecycle()
    val favorites by PlayerStore.favorites.collectAsStateWithLifecycle()
    val live by LiveStream.enabled.collectAsStateWithLifecycle()
    val now = rememberNow()
    val current = hub.current ?: return
    val isFavorite = favorites.any { it.ref.matches(current) }
    Column(verticalArrangement = Arrangement.spacedBy(DropSpacing.m)) {
        if (current.isVideo) {
            DropText(current.title, style = DropTheme.type.title, maxLines = 3)
            DropText("${fmtTime(current.durationMs)} \u00b7 ${formatBytes(current.size)}", style = DropTheme.type.caption, color = c.textSecondary)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(DropSpacing.s)) {
            Pill(if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, if (isFavorite) "Favorite" else "Favorite", isFavorite) { PlayerStore.toggleFavorite(current) }
            Pill(Icons.Outlined.QueueMusic, (if (live) "Live queue" else "Queue") + " (${hub.queue.size})", live) { openQueue() }
            Pill(Icons.Outlined.Shuffle, "Shuffle", hub.shuffle) { PlayerHub.setShuffle(!hub.shuffle) }
            Pill(
                if (hub.repeat == Player.REPEAT_MODE_ONE) Icons.Outlined.RepeatOne else Icons.Outlined.Repeat,
                when (hub.repeat) { Player.REPEAT_MODE_ONE -> "Repeat one"; Player.REPEAT_MODE_ALL -> "Repeat all"; else -> "Repeat" },
                hub.repeat != Player.REPEAT_MODE_OFF
            ) { PlayerHub.cycleRepeat() }
            var speedMenu by remember { mutableStateOf(false) }
            Box {
                Pill(Icons.Outlined.Speed, if (hub.speed == 1f) "Speed" else "${hub.speed}x", hub.speed != 1f) { speedMenu = true }
                DropdownMenu(speedMenu, { speedMenu = false }) {
                    listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { s ->
                        DropdownMenuItem(text = { Text(if (s == 1f) "Normal" else "${s}x") }, onClick = { PlayerHub.setSpeed(s); speedMenu = false })
                    }
                }
            }
            var sleepMenu by remember { mutableStateOf(false) }
            Box {
                val label = when {
                    sleep == null -> "Sleep"
                    sleep?.atEndOfItem == true -> "Sleep: end"
                    else -> "Sleep ${(((sleep?.endsAt ?: now) - now) / 60_000 + 1).coerceAtLeast(1)} min"
                }
                Pill(Icons.Outlined.Bedtime, label, sleep != null) { sleepMenu = true }
                DropdownMenu(sleepMenu, { sleepMenu = false }) {
                    listOf(15, 30, 45, 60).forEach { m -> DropdownMenuItem(text = { Text("In $m minutes") }, onClick = { PlayerHub.sleepIn(m); sleepMenu = false }) }
                    DropdownMenuItem(text = { Text("When this ${if (current.isVideo) "video" else "song"} ends") }, onClick = { PlayerHub.sleepAtEndOfItem(); sleepMenu = false })
                    if (sleep != null) DropdownMenuItem(text = { Text("Turn off") }, onClick = { PlayerHub.cancelSleep(); sleepMenu = false })
                }
            }
            if (current.isVideo) {
                var subMenu by remember { mutableStateOf(false) }
                Box {
                    Pill(Icons.Outlined.ClosedCaption, if (subIndex >= 0) text.subtitles.getOrNull(subIndex)?.name ?: "Subtitles" else "Subtitles", subIndex >= 0) {
                        if (text.subtitles.isEmpty()) ui.onPickTextFile() else subMenu = true
                    }
                    DropdownMenu(subMenu, { subMenu = false }) {
                        DropdownMenuItem(text = { Text("Off") }, onClick = { PlayerHub.selectSubtitle(-1); subMenu = false })
                        text.subtitles.forEachIndexed { i, t -> DropdownMenuItem(text = { Text(t.name) }, onClick = { PlayerHub.selectSubtitle(i); subMenu = false }) }
                        DropdownMenuItem(text = { Text("Add a subtitle file\u2026") }, onClick = { subMenu = false; ui.onPickTextFile() })
                    }
                }
                Pill(Icons.Outlined.Fullscreen, "Full screen") { ui.onFullscreen(true) }
            } else {
                Pill(Icons.Outlined.Subject, if (text.lyrics == null) "Add lyrics" else "Lyrics") {
                    if (text.lyrics == null) ui.onPickTextFile() else openLyrics()
                }
            }
            Pill(Icons.Outlined.Settings, "Player settings") { ui.onOpenSettings() }
        }
        if (!current.isVideo && text.lyrics != null) {
            DropText("Lyrics: ${text.lyrics?.source}", style = DropTheme.type.caption, color = c.textSecondary)
        }
        LiveRow(ui)
    }
}

@Composable
private fun LiveRow(ui: PlayerUi) {
    val c = DropTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(DropRadius.medium)).background(c.card).padding(horizontal = DropSpacing.l, vertical = DropSpacing.m),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DropSpacing.m)
    ) {
        Icon(Icons.Outlined.Cast, null, tint = if (ui.liveOn) c.accent else c.textSecondary)
        Column(Modifier.weight(1f)) {
            DropText("Live streaming", style = DropTheme.type.bodyStrong)
            DropText(
                when {
                    !ui.hosting -> "Start hosting to share this player with connected devices."
                    ui.liveOn -> "On. Only the live queue plays, and paired devices see only that list. Add items with \u22EE on a song or video."
                    else -> "Off. Turn on to let paired devices watch or listen along. The song or video playing now becomes the first in the live queue."
                },
                style = DropTheme.type.caption, color = c.textSecondary
            )
        }
        DropSwitch(ui.liveOn, ui.onLiveChange, enabled = ui.hosting)
    }
}

/* ------------------------------------- mini player ------------------------------------- */

/** The small bar shown on other screens while something is playing. Tap it to open the player. */
@Composable
fun MiniPlayer(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val hub by PlayerHub.state.collectAsStateWithLifecycle()
    val pos by PlayerHub.position.collectAsStateWithLifecycle()
    val current = hub.current ?: return
    val c = DropTheme.colors
    Column(modifier.fillMaxWidth().background(c.card).clickable(onClick = onOpen)) {
        val fraction = if (hub.durationMs > 0) (pos.toFloat() / hub.durationMs).coerceIn(0f, 1f) else 0f
        Box(Modifier.fillMaxWidth().height(2.dp).background(c.field)) { Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(c.accent)) }
        Row(Modifier.fillMaxWidth().padding(horizontal = DropSpacing.m, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DropSpacing.m)) {
            Thumb(current, 44.dp, 44.dp, RoundedCornerShape(8.dp))
            Column(Modifier.weight(1f)) {
                DropText(current.title, style = DropTheme.type.bodyStrong, maxLines = 1)
                DropText(current.artist.ifBlank { if (current.isVideo) "Video" else "Music" }, style = DropTheme.type.caption, color = c.textSecondary, maxLines = 1)
            }
            IconButton(onClick = { PlayerHub.toggle() }) { Icon(if (hub.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (hub.playing) "Pause" else "Play", tint = c.textPrimary) }
            IconButton(onClick = { PlayerHub.stop() }) { Icon(Icons.Outlined.Close, "Stop", tint = c.textSecondary) }
        }
    }
}
