@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package io.github.trebronwayne.droplink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import io.github.trebronwayne.droplink.player.Cue
import io.github.trebronwayne.droplink.player.MediaEntry
import io.github.trebronwayne.droplink.player.PlayerHub
import io.github.trebronwayne.droplink.player.PlayerPrefs
import io.github.trebronwayne.droplink.player.PlayerStore
import io.github.trebronwayne.droplink.ui.components.DropButton
import io.github.trebronwayne.droplink.ui.components.DropButtonStyle
import io.github.trebronwayne.droplink.ui.components.DropText
import io.github.trebronwayne.droplink.ui.theme.DropRadius
import io.github.trebronwayne.droplink.ui.theme.DropSpacing
import io.github.trebronwayne.droplink.ui.theme.DropTheme
import kotlinx.coroutines.delay

/** The big area at the top of the player: a video, or the cover of a song with its lyrics line. */
@Composable
internal fun Stage(ui: PlayerUi, hub: PlayerHub.State, wide: Boolean, onPlayNewest: () -> Unit, onOpenLyrics: () -> Unit) {
    val c = DropTheme.colors
    val current = hub.current
    when {
        current == null -> Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(c.card), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(DropSpacing.m)) {
                Icon(Icons.Outlined.MusicNote, null, tint = c.textSecondary, modifier = Modifier.size(44.dp))
                DropText("Nothing playing", style = DropTheme.type.subtitle)
                if (ui.hasAccess) DropButton("Play newest music", onPlayNewest, style = DropButtonStyle.Primary, icon = Icons.Filled.PlayArrow)
            }
        }
        current.isVideo -> VideoStage(ui, hub, false, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        else -> AudioStage(current, wide, onOpenLyrics, Modifier.fillMaxWidth())
    }
}

@Composable
private fun ErrorLine(modifier: Modifier = Modifier) {
    val error by PlayerHub.error.collectAsStateWithLifecycle()
    val message = error ?: return
    Text(
        message, color = Color.White, fontSize = 12.sp, textAlign = TextAlign.Center,
        modifier = modifier.background(DropTheme.colors.danger.copy(alpha = 0.9f), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

/** A song: the cover stays in view, with title, the heart, and the line of lyrics being sung. */
@Composable
private fun AudioStage(entry: MediaEntry, wide: Boolean, onOpenLyrics: () -> Unit, modifier: Modifier) {
    val c = DropTheme.colors
    val text by PlayerHub.text.collectAsStateWithLifecycle()
    val preview by PlayerPrefs.lyricsPreview.collectAsStateWithLifecycle()
    val favorites by PlayerStore.favorites.collectAsStateWithLifecycle()
    val isFavorite = favorites.any { it.ref.matches(entry) }
    val cover = if (wide) 200.dp else 128.dp
    Box(modifier.background(c.card)) {
        Row(Modifier.fillMaxWidth().padding(DropSpacing.l), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DropSpacing.l)) {
            Thumb(entry, cover, cover, RoundedCornerShape(DropRadius.large))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(DropSpacing.s)) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        DropText(entry.title, style = DropTheme.type.subtitle, maxLines = 2)
                        if (entry.artist.isNotBlank()) DropText(entry.artist, style = DropTheme.type.body, color = c.textSecondary, maxLines = 1)
                    }
                    IconButton(onClick = { PlayerStore.toggleFavorite(entry) }) {
                        Icon(if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, if (isFavorite) "Remove from favorites" else "Add to favorites", tint = if (isFavorite) c.danger else c.textSecondary)
                    }
                }
                val lyrics = text.lyrics
                if (preview && lyrics != null && !lyrics.isEmpty) LyricsPreview(lyrics, onOpenLyrics, Modifier.fillMaxWidth())
            }
        }
        ErrorLine(Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp))
    }
}

/** A video. Tap to show or hide controls, double-tap the left or right half to jump 10 seconds. */
@Composable
internal fun VideoStage(ui: PlayerUi, hub: PlayerHub.State, fullscreen: Boolean, modifier: Modifier) {
    val controller by PlayerHub.controller.collectAsStateWithLifecycle()
    val text by PlayerHub.text.collectAsStateWithLifecycle()
    val subIndex by PlayerHub.subtitle.collectAsStateWithLifecycle()
    val resumedFrom by PlayerHub.resumedFrom.collectAsStateWithLifecycle()
    val keepOn by PlayerPrefs.keepScreenOn.collectAsStateWithLifecycle()
    var overlay by remember { mutableStateOf(true) }
    var flash by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(overlay, hub.playing) {
        if (overlay && hub.playing) {
            delay(3_200)
            overlay = false
        }
    }
    LaunchedEffect(flash) {
        if (flash != null) {
            delay(650)
            flash = null
        }
    }
    Box(
        modifier.background(Color.Black).pointerInput(Unit) {
            detectTapGestures(
                onTap = { overlay = !overlay },
                onDoubleTap = { offset ->
                    if (offset.x < size.width / 2f) { PlayerHub.seekBy(-10_000); flash = "\u221210 s" } else { PlayerHub.seekBy(10_000); flash = "+10 s" }
                }
            )
        }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { view ->
                view.player = controller
                view.keepScreenOn = keepOn
            },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize()
        )
        // Subtitles are drawn here so they look the same everywhere and follow the track the person chose.
        val track = text.subtitles.getOrNull(subIndex)
        if (track != null) SubtitleLine(track.cues, Modifier.align(Alignment.BottomCenter).padding(bottom = if (overlay && fullscreen) 76.dp else 18.dp, start = 16.dp, end = 16.dp), fullscreen)
        if (hub.buffering) CircularProgressIndicator(Modifier.align(Alignment.Center).size(36.dp), color = Color.White, strokeWidth = 3.dp)
        flash?.let {
            Text(it, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.55f), CircleShape).padding(horizontal = 18.dp, vertical = 10.dp))
        }
        resumedFrom?.let { from ->
            Row(
                Modifier.align(Alignment.TopStart).padding(10.dp).background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(50)).padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Resumed at ${fmtTime(from)}", color = Color.White, fontSize = 12.sp)
                androidx.compose.material3.TextButton(onClick = { PlayerHub.startOver() }) { Text("Start over", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            }
        }
        ErrorLine(Modifier.align(Alignment.TopCenter).padding(top = 10.dp))
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
            if (fullscreen) {
                // Full screen has no bar underneath, so the seek bar lives on the video.
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    SeekBar(hub.durationMs, Color.White, Color.White.copy(alpha = 0.3f), Color.White)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TimeLabel(hub.durationMs, Color.White)
                        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                        FullscreenMenus(text.subtitles.map { it.name }, subIndex, hub.speed)
                    }
                }
            } else {
                // In the page the seek bar is under the video, so only the full screen button is added here.
                IconButton(onClick = { ui.onFullscreen(true) }, modifier = Modifier.align(Alignment.BottomEnd)) {
                    Icon(Icons.Outlined.Fullscreen, "Full screen", tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun FullscreenMenus(subtitleNames: List<String>, subIndex: Int, speed: Float) {
    var subMenu by remember { mutableStateOf(false) }
    var speedMenu by remember { mutableStateOf(false) }
    if (subtitleNames.isNotEmpty()) {
        Box {
            IconButton(onClick = { subMenu = true }) { Icon(Icons.Outlined.ClosedCaption, "Subtitles", tint = if (subIndex >= 0) Color.White else Color.White.copy(alpha = 0.6f)) }
            DropdownMenu(subMenu, { subMenu = false }) {
                DropdownMenuItem(text = { Text("Off") }, onClick = { PlayerHub.selectSubtitle(-1); subMenu = false })
                subtitleNames.forEachIndexed { i, n -> DropdownMenuItem(text = { Text(n) }, onClick = { PlayerHub.selectSubtitle(i); subMenu = false }) }
            }
        }
    }
    Box {
        IconButton(onClick = { speedMenu = true }) { Icon(Icons.Outlined.Speed, "Speed", tint = if (speed == 1f) Color.White.copy(alpha = 0.6f) else Color.White) }
        DropdownMenu(speedMenu, { speedMenu = false }) {
            listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { s ->
                DropdownMenuItem(text = { Text(if (s == 1f) "Normal" else "${s}x") }, onClick = { PlayerHub.setSpeed(s); speedMenu = false })
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
