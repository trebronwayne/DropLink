@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package io.github.trebronwayne.droplink.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.RepeatOne
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Subject
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.trebronwayne.aubree.AubreeState
import io.github.trebronwayne.droplink.core.formatBytes
import io.github.trebronwayne.droplink.player.Cue
import io.github.trebronwayne.droplink.player.Lyrics
import io.github.trebronwayne.droplink.player.MediaEntry
import io.github.trebronwayne.droplink.player.MediaLibrary
import io.github.trebronwayne.droplink.player.PlayerHub
import io.github.trebronwayne.droplink.player.Thumbs
import io.github.trebronwayne.droplink.ui.components.AubreeEmptyState
import io.github.trebronwayne.droplink.ui.components.DropButton
import io.github.trebronwayne.droplink.ui.components.DropButtonStyle
import io.github.trebronwayne.droplink.ui.components.DropSegmented
import io.github.trebronwayne.droplink.ui.components.DropSwitch
import io.github.trebronwayne.droplink.ui.components.DropText
import io.github.trebronwayne.droplink.ui.theme.DropRadius
import io.github.trebronwayne.droplink.ui.theme.DropSpacing
import io.github.trebronwayne.droplink.ui.theme.DropTheme
import kotlinx.coroutines.delay

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
    val onRescan: () -> Unit = {}
)

private fun fmtTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Index of the last cue that starts at or before [pos], or -1. */
private fun List<Cue>.indexAt(pos: Long): Int {
    var lo = 0
    var hi = size - 1
    var found = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (this[mid].startMs <= pos) { found = mid; lo = mid + 1 } else hi = mid - 1
    }
    return found
}

/* ------------------------------------------------------------------------------------------------ */

@Composable
fun PlayerScreen(ui: PlayerUi, wide: Boolean) {
    val hub by PlayerHub.state.collectAsStateWithLifecycle()
    val library by MediaLibrary.items.collectAsStateWithLifecycle()
    val loaded by MediaLibrary.loaded.collectAsStateWithLifecycle()
    val text by PlayerHub.text.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(0) }
    var showLyrics by rememberSaveable { mutableStateOf(false) }
    val shown = remember(library, filter) {
        when (filter) { 1 -> library.filter { !it.isVideo }; 2 -> library.filter { it.isVideo }; else -> library }
    }
    val current = hub.current
    BackHandler(ui.fullscreen) { ui.onFullscreen(false) }
    // Songs with timed lyrics open with the lyrics showing; the person can switch them off.
    LaunchedEffect(current?.id, text.lyrics?.isSynced) { showLyrics = current != null && !current.isVideo && text.lyrics?.isSynced == true }

    if (ui.fullscreen && current?.isVideo == true) {
        Box(Modifier.fillMaxSize().background(Color.Black)) { VideoStage(ui, hub, true, Modifier.fillMaxSize()) }
        return
    }

    val onPlay: (MediaEntry) -> Unit = { e -> PlayerHub.play(shown, shown.indexOfFirst { it.id == e.id }.coerceAtLeast(0)) }

    val libraryList: @Composable (Modifier, Boolean) -> Unit = { modifier, withDetails ->
        LibraryList(ui, hub, shown, loaded, filter, { filter = it }, showLyrics, { showLyrics = it }, onPlay, withDetails, modifier)
    }

    if (wide) {
        Row(Modifier.fillMaxSize().padding(DropSpacing.l), horizontalArrangement = Arrangement.spacedBy(DropSpacing.l)) {
            Column(Modifier.weight(1.25f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(DropSpacing.m)) {
                Stage(ui, hub, showLyrics, onPlay = { if (library.isNotEmpty()) PlayerHub.play(library, 0) })
                Transport(hub)
                Details(ui, hub, showLyrics, { showLyrics = it })
            }
            libraryList(Modifier.weight(1f).fillMaxHeight(), false)
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Stage(ui, hub, showLyrics, onPlay = { if (library.isNotEmpty()) PlayerHub.play(library, 0) })
            if (current != null) Transport(hub, Modifier.padding(horizontal = DropSpacing.l))
            libraryList(Modifier.weight(1f).fillMaxWidth(), true)
        }
    }
}

/* ---------------------------------- the big area at the top ---------------------------------- */

@Composable
private fun Stage(ui: PlayerUi, hub: PlayerHub.State, showLyrics: Boolean, onPlay: () -> Unit) {
    val c = DropTheme.colors
    val current = hub.current
    when {
        current == null -> Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(c.card),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(DropSpacing.m)) {
                Icon(Icons.Outlined.MusicNote, null, tint = c.textSecondary, modifier = Modifier.size(44.dp))
                DropText("Nothing playing", style = DropTheme.type.subtitle)
                if (ui.hasAccess) DropButton("Play newest", onPlay, style = DropButtonStyle.Primary, icon = Icons.Filled.PlayArrow)
            }
        }
        current.isVideo -> VideoStage(ui, hub, false, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        else -> AudioStage(hub, current, showLyrics, Modifier.fillMaxWidth())
    }
}

@Composable
private fun AudioStage(hub: PlayerHub.State, entry: MediaEntry, showLyrics: Boolean, modifier: Modifier) {
    val c = DropTheme.colors
    val text by PlayerHub.text.collectAsStateWithLifecycle()
    Box(
        modifier.animateContentSize().height(if (showLyrics) 300.dp else 220.dp).background(c.card),
        contentAlignment = Alignment.Center
    ) {
        if (showLyrics) {
            LyricsPanel(text.lyrics, Modifier.fillMaxSize())
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DropSpacing.l), modifier = Modifier.fillMaxWidth().padding(DropSpacing.l)) {
                Thumb(entry, 160.dp, 160.dp, RoundedCornerShape(DropRadius.large))
                Column(Modifier.weight(1f)) {
                    DropText(entry.title, style = DropTheme.type.title, maxLines = 3)
                    if (entry.artist.isNotBlank()) DropText(entry.artist, style = DropTheme.type.body, color = c.textSecondary, maxLines = 2)
                    if (entry.album.isNotBlank()) DropText(entry.album, style = DropTheme.type.caption, color = c.textSecondary, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun VideoStage(ui: PlayerUi, hub: PlayerHub.State, fullscreen: Boolean, modifier: Modifier) {
    val controller by PlayerHub.controller.collectAsStateWithLifecycle()
    val text by PlayerHub.text.collectAsStateWithLifecycle()
    val subIndex by PlayerHub.subtitle.collectAsStateWithLifecycle()
    var overlay by remember { mutableStateOf(true) }
    LaunchedEffect(overlay, hub.playing) {
        if (overlay && hub.playing) {
            delay(3_200)
            overlay = false
        }
    }
    Box(modifier.background(Color.Black).clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { overlay = !overlay }) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    keepScreenOn = true
                }
            },
            update = { it.player = controller },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize()
        )
        // Subtitles, drawn here so they look the same everywhere and follow the track the person chose.
        val track = text.subtitles.getOrNull(subIndex)
        if (track != null) SubtitleLine(track.cues, Modifier.align(Alignment.BottomCenter).padding(bottom = if (overlay) 64.dp else 20.dp, start = 16.dp, end = 16.dp), fullscreen)
        if (hub.buffering) androidx.compose.material3.CircularProgressIndicator(Modifier.align(Alignment.Center).size(36.dp), color = Color.White, strokeWidth = 3.dp)
        if (overlay) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
            if (fullscreen) {
                Row(Modifier.align(Alignment.TopStart).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { ui.onFullscreen(false) }) { Icon(Icons.Outlined.FullscreenExit, "Exit full screen", tint = Color.White) }
                    Text(hub.current?.title.orEmpty(), color = Color.White, maxLines = 1, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
            }
            Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { PlayerHub.seekBy(-10_000) }) { Icon(Icons.Outlined.Replay10, "Back 10 seconds", tint = Color.White, modifier = Modifier.size(32.dp)) }
                Box(Modifier.size(60.dp).background(Color.White.copy(alpha = 0.18f), CircleShape).clickable { PlayerHub.toggle() }, contentAlignment = Alignment.Center) {
                    Icon(if (hub.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (hub.playing) "Pause" else "Play", tint = Color.White, modifier = Modifier.size(36.dp))
                }
                IconButton(onClick = { PlayerHub.seekBy(10_000) }) { Icon(Icons.Outlined.Forward10, "Forward 10 seconds", tint = Color.White, modifier = Modifier.size(32.dp)) }
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                SeekBar(hub.durationMs, Color.White, Color.White.copy(alpha = 0.3f), Color.White)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TimeLabel(hub.durationMs, Color.White)
                    Spacer(Modifier.weight(1f))
                    if (!fullscreen) IconButton(onClick = { ui.onFullscreen(true) }) { Icon(Icons.Outlined.Fullscreen, "Full screen", tint = Color.White) }
                }
            }
        }
    }
}

@Composable
private fun SubtitleLine(cues: List<Cue>, modifier: Modifier, big: Boolean) {
    val pos by PlayerHub.position.collectAsStateWithLifecycle()
    val i = cues.indexAt(pos)
    val cue = if (i >= 0 && pos < cues[i].endMs) cues[i] else null
    if (cue != null) {
        Text(
            cue.text, color = Color.White, textAlign = TextAlign.Center, fontSize = if (big) 20.sp else 14.sp, lineHeight = if (big) 27.sp else 19.sp,
            fontWeight = FontWeight.Medium,
            modifier = modifier.background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

/* -------------------------------------- lyrics -------------------------------------- */

@Composable
private fun LyricsPanel(lyrics: Lyrics?, modifier: Modifier) {
    val c = DropTheme.colors
    val pos by PlayerHub.position.collectAsStateWithLifecycle()
    when {
        lyrics == null || lyrics.isEmpty -> Box(modifier.padding(DropSpacing.l), contentAlignment = Alignment.Center) {
            DropText(
                "No lyrics found for this song. Put a .lrc file with the same name next to it, or add the folder that holds your lyrics in Settings.",
                style = DropTheme.type.body, color = c.textSecondary, textAlign = TextAlign.Center
            )
        }
        lyrics.isSynced -> {
            val state = rememberLazyListState()
            val active = lyrics.synced.indexAt(pos)
            LaunchedEffect(active) { if (active >= 0) state.animateScrollToItem((active - 2).coerceAtLeast(0)) }
            LazyColumn(modifier, state = state, contentPadding = PaddingValues(horizontal = DropSpacing.xl, vertical = 90.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(lyrics.synced.size) { i ->
                    val cue = lyrics.synced[i]
                    val on = i == active
                    Text(
                        cue.text.ifBlank { "\u266A" },
                        color = if (on) c.textPrimary else c.textSecondary.copy(alpha = 0.7f),
                        fontSize = if (on) 22.sp else 18.sp, lineHeight = if (on) 30.sp else 25.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.fillMaxWidth().clickable { PlayerHub.seekTo(cue.startMs) }
                    )
                }
            }
        }
        else -> Column(modifier.verticalScroll(rememberScrollState()).padding(DropSpacing.xl)) {
            Text(lyrics.plain, color = c.textPrimary, fontSize = 17.sp, lineHeight = 26.sp)
        }
    }
}

/* ----------------------------------- controls ----------------------------------- */

@Composable
private fun SeekBar(durationMs: Long, active: Color, track: Color, thumb: Color) {
    val pos by PlayerHub.position.collectAsStateWithLifecycle()
    var drag by remember { mutableStateOf<Float?>(null) }
    val max = durationMs.coerceAtLeast(1L).toFloat()
    Slider(
        value = (drag ?: pos.toFloat()).coerceIn(0f, max), onValueChange = { drag = it },
        onValueChangeFinished = { drag?.let { PlayerHub.seekTo(it.toLong()) }; drag = null },
        valueRange = 0f..max, modifier = Modifier.fillMaxWidth().height(28.dp),
        colors = SliderDefaults.colors(thumbColor = thumb, activeTrackColor = active, inactiveTrackColor = track)
    )
}

@Composable
private fun TimeLabel(durationMs: Long, color: Color) {
    val pos by PlayerHub.position.collectAsStateWithLifecycle()
    Text("${fmtTime(pos)} / ${fmtTime(durationMs)}", color = color, fontSize = 12.sp)
}

/** Previous, play or pause, next, and the seek bar. */
@Composable
private fun Transport(hub: PlayerHub.State, modifier: Modifier = Modifier) {
    val c = DropTheme.colors
    Column(modifier.fillMaxWidth()) {
        SeekBar(hub.durationMs, c.textPrimary, c.field, c.textPrimary)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TimeLabel(hub.durationMs, c.textSecondary)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { PlayerHub.previous() }) { Icon(Icons.Outlined.SkipPrevious, "Previous", tint = c.textPrimary, modifier = Modifier.size(28.dp)) }
            Box(
                Modifier.size(52.dp).background(c.brand, CircleShape).clickable { PlayerHub.toggle() },
                contentAlignment = Alignment.Center
            ) {
                Icon(if (hub.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (hub.playing) "Pause" else "Play", tint = c.onBrand, modifier = Modifier.size(30.dp))
            }
            IconButton(onClick = { PlayerHub.next() }) { Icon(Icons.Outlined.SkipNext, "Next", tint = c.textPrimary, modifier = Modifier.size(28.dp)) }
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(72.dp))
        }
    }
}

@Composable
private fun Pill(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, on: Boolean = false, onClick: () -> Unit) {
    val c = DropTheme.colors
    Row(
        Modifier.clip(RoundedCornerShape(DropRadius.pill)).background(if (on) c.subtleSelected else c.field).clickable(onClick = onClick)
            .heightIn(min = 40.dp).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, null, tint = if (on) c.accent else c.textPrimary, modifier = Modifier.size(18.dp))
        Text(label, color = c.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/** Title, then the things you can do with what is playing. */
@Composable
private fun Details(ui: PlayerUi, hub: PlayerHub.State, showLyrics: Boolean, setLyrics: (Boolean) -> Unit) {
    val c = DropTheme.colors
    val text by PlayerHub.text.collectAsStateWithLifecycle()
    val subIndex by PlayerHub.subtitle.collectAsStateWithLifecycle()
    val current = hub.current ?: return
    Column(verticalArrangement = Arrangement.spacedBy(DropSpacing.m)) {
        if (current.isVideo) {
            DropText(current.title, style = DropTheme.type.title, maxLines = 3)
            DropText("${fmtTime(current.durationMs)} \u00b7 ${formatBytes(current.size)}", style = DropTheme.type.caption, color = c.textSecondary)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(DropSpacing.s)) {
            Pill(Icons.Outlined.Shuffle, "Shuffle", hub.shuffle) { PlayerHub.setShuffle(!hub.shuffle) }
            Pill(if (hub.repeat == Player.REPEAT_MODE_ONE) Icons.Outlined.RepeatOne else Icons.Outlined.Repeat,
                when (hub.repeat) { Player.REPEAT_MODE_ONE -> "Repeat one"; Player.REPEAT_MODE_ALL -> "Repeat all"; else -> "Repeat" },
                hub.repeat != Player.REPEAT_MODE_OFF) { PlayerHub.cycleRepeat() }
            var speedMenu by remember { mutableStateOf(false) }
            Box {
                Pill(Icons.Outlined.Speed, if (hub.speed == 1f) "Speed" else "${hub.speed}x", hub.speed != 1f) { speedMenu = true }
                DropdownMenu(speedMenu, { speedMenu = false }) {
                    listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { s ->
                        DropdownMenuItem(text = { Text(if (s == 1f) "Normal" else "${s}x") }, onClick = { PlayerHub.setSpeed(s); speedMenu = false })
                    }
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
                Pill(Icons.Outlined.Subject, if (text.lyrics == null) "Add lyrics" else "Lyrics", showLyrics) {
                    if (text.lyrics == null) ui.onPickTextFile() else setLyrics(!showLyrics)
                }
            }
        }
        if (!current.isVideo && text.lyrics != null && !showLyrics) {
            DropText("Lyrics found: ${text.lyrics?.source}", style = DropTheme.type.caption, color = c.textSecondary)
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
                    ui.liveOn -> "On. Paired devices can open Live in their browser and watch or listen along."
                    else -> "Off. Turn on to let paired devices watch or listen along."
                },
                style = DropTheme.type.caption, color = c.textSecondary
            )
        }
        DropSwitch(ui.liveOn, ui.onLiveChange, enabled = ui.hosting)
    }
}

/* ------------------------------------- the list ------------------------------------- */

@Composable
private fun LibraryList(
    ui: PlayerUi, hub: PlayerHub.State, shown: List<MediaEntry>, loaded: Boolean, filter: Int, setFilter: (Int) -> Unit,
    showLyrics: Boolean, setLyrics: (Boolean) -> Unit, onPlay: (MediaEntry) -> Unit, withDetails: Boolean, modifier: Modifier
) {
    val c = DropTheme.colors
    LazyColumn(modifier, contentPadding = PaddingValues(horizontal = DropSpacing.l, vertical = DropSpacing.m), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (withDetails && hub.current != null) {
            item(key = "details") { Column(Modifier.padding(bottom = DropSpacing.m)) { Details(ui, hub, showLyrics, setLyrics) } }
        }
        item(key = "filter") {
            Row(Modifier.fillMaxWidth().padding(vertical = DropSpacing.s), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DropSpacing.s)) {
                DropSegmented(listOf(0 to "All", 1 to "Music", 2 to "Videos"), filter, setFilter, Modifier.weight(1f))
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
            loaded && shown.isEmpty() -> item(key = "empty") {
                AubreeEmptyState(
                    AubreeState.EMPTY, "No music or videos yet",
                    "Music (mp3, m4a, flac, wav, aac, ogg) and videos (mp4, mkv, webm, 3gp) appear here, newest first. Files you receive show up at the top."
                )
            }
            else -> items(shown, key = { it.id }) { e -> MediaRow(e, e.id == hub.current?.id, hub.playing && e.id == hub.current?.id) { onPlay(e) } }
        }
        item(key = "end") { Spacer(Modifier.height(DropSpacing.xl)) }
    }
}

@Composable
private fun MediaRow(e: MediaEntry, isCurrent: Boolean, playing: Boolean, onClick: () -> Unit) {
    val c = DropTheme.colors
    val recent = System.currentTimeMillis() / 1000 - e.dateAdded < 86_400
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(DropRadius.medium))
            .background(if (isCurrent) c.subtleSelected else Color.Transparent).clickable(onClick = onClick).padding(vertical = 8.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DropSpacing.m)
    ) {
        Box {
            if (e.isVideo) Thumb(e, 112.dp, 63.dp, RoundedCornerShape(10.dp)) else Thumb(e, 56.dp, 56.dp, RoundedCornerShape(10.dp))
            if (e.isVideo && e.durationMs > 0) {
                Text(
                    fmtTime(e.durationMs), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }
        Column(Modifier.weight(1f)) {
            DropText(e.title, style = DropTheme.type.bodyStrong, maxLines = 2)
            DropText(
                if (e.isVideo) "Video \u00b7 ${formatBytes(e.size)}" else listOf(e.artist, fmtTime(e.durationMs)).filter { it.isNotBlank() }.joinToString(" \u00b7 "),
                style = DropTheme.type.caption, color = c.textSecondary, maxLines = 1
            )
        }
        if (recent) {
            Text("NEW", color = c.accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.background(c.brandTint, RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 2.dp))
        }
        if (isCurrent) Icon(if (playing) Icons.Filled.GraphicEq else Icons.Filled.Pause, null, tint = c.accent, modifier = Modifier.size(20.dp))
    }
}

/** Cover art or a video frame; a quiet icon while loading or when there is none. */
@Composable
private fun Thumb(e: MediaEntry, w: androidx.compose.ui.unit.Dp, h: androidx.compose.ui.unit.Dp, shape: androidx.compose.ui.graphics.Shape) {
    val c = DropTheme.colors
    val ctx = LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, e.id) {
        value = Thumbs.load(ctx, e, with(density) { w.roundToPx() }.coerceAtLeast(64), with(density) { h.roundToPx() }.coerceAtLeast(64))?.asImageBitmap()
    }
    Box(Modifier.size(w, h).clip(shape).background(c.field), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(if (e.isVideo) Icons.Outlined.Movie else Icons.Outlined.MusicNote, null, tint = c.textSecondary, modifier = Modifier.size(minOf(w, h) / 2))
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
