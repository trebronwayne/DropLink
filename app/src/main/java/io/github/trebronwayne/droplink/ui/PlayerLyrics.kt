package io.github.trebronwayne.droplink.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.trebronwayne.droplink.player.Lyrics
import io.github.trebronwayne.droplink.player.MediaEntry
import io.github.trebronwayne.droplink.player.PlayerHub
import io.github.trebronwayne.droplink.player.PlayerPrefs
import io.github.trebronwayne.droplink.ui.components.DropText
import io.github.trebronwayne.droplink.ui.theme.DropRadius
import io.github.trebronwayne.droplink.ui.theme.DropSpacing
import io.github.trebronwayne.droplink.ui.theme.DropTheme

/**
 * Lyrics come in two shapes here. Next to the cover only the line being sung shows, with the lines around it faded
 * (the cover stays visible). Tapping that box opens all the lyrics, which scroll with the song.
 */

@Composable
internal fun LyricsPreview(lyrics: Lyrics, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val c = DropTheme.colors
    val pos by PlayerHub.position.collectAsStateWithLifecycle()
    Box(
        modifier.clip(RoundedCornerShape(DropRadius.medium)).background(c.field).clickable(onClick = onOpen)
            .heightIn(min = 92.dp).padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        if (lyrics.isSynced) {
            val cues = lyrics.synced
            AnimatedContent(
                targetState = cues.indexAt(pos),
                transitionSpec = { (slideInVertically { it / 2 } + fadeIn()) togetherWith (slideOutVertically { -it / 2 } + fadeOut()) },
                label = "lyric line"
            ) { i ->
                Column(Modifier.fillMaxWidth().padding(end = 18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(cues.getOrNull(i - 1)?.text.orEmpty(), color = c.textSecondary.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (i >= 0) cues[i].text.ifBlank { "\u266A" } else "\u266A", color = c.textPrimary, fontSize = 17.sp, lineHeight = 22.sp,
                        fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                    Text(cues.getOrNull(i + 1)?.text.orEmpty(), color = c.textSecondary.copy(alpha = 0.7f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        } else {
            Column(Modifier.padding(end = 18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(lyrics.plain, color = c.textPrimary, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text("Tap to read all", color = c.textSecondary, fontSize = 12.sp)
            }
        }
        Icon(Icons.Outlined.OpenInFull, "Open all lyrics", tint = c.textSecondary, modifier = Modifier.align(Alignment.TopEnd).size(14.dp))
    }
}

/** All the lyrics. Timed ones highlight the line being sung and scroll along; tap a line to jump there. */
@Composable
internal fun LyricsPanel(lyrics: Lyrics?, modifier: Modifier = Modifier) {
    val c = DropTheme.colors
    val pos by PlayerHub.position.collectAsStateWithLifecycle()
    val size by PlayerPrefs.lyricsSize.collectAsStateWithLifecycle()
    val base = listOf(16, 19, 23)[size]
    when {
        lyrics == null || lyrics.isEmpty -> Box(modifier.padding(DropSpacing.xl), contentAlignment = Alignment.Center) {
            DropText(
                "No lyrics found for this song. Put a .lrc file with the same name next to it, or add the folder that holds your lyrics in Settings.",
                style = DropTheme.type.body, color = c.textSecondary, textAlign = TextAlign.Center
            )
        }
        lyrics.isSynced -> {
            val state = rememberLazyListState()
            val active = lyrics.synced.indexAt(pos)
            LaunchedEffect(active) { if (active >= 0) state.animateScrollToItem((active - 2).coerceAtLeast(0)) }
            LazyColumn(modifier, state = state, contentPadding = PaddingValues(horizontal = DropSpacing.xl, vertical = 120.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(lyrics.synced.size) { i ->
                    val cue = lyrics.synced[i]
                    val on = i == active
                    Text(
                        cue.text.ifBlank { "\u266A" },
                        color = if (on) c.textPrimary else c.textSecondary.copy(alpha = 0.65f),
                        fontSize = (if (on) base + 4 else base).sp, lineHeight = (if (on) base + 12 else base + 8).sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.fillMaxWidth().clickable { PlayerHub.seekTo(cue.startMs) }
                    )
                }
            }
        }
        else -> Column(modifier.verticalScroll(rememberScrollState()).padding(DropSpacing.xl)) {
            Text(lyrics.plain, color = c.textPrimary, fontSize = (base + 1).sp, lineHeight = (base + 10).sp)
        }
    }
}

/** The lyrics on their own screen, with the cover and play controls kept small at the top and bottom. */
@Composable
internal fun LyricsDialog(entry: MediaEntry, lyrics: Lyrics?, playing: Boolean, durationMs: Long, onClose: () -> Unit) {
    val c = DropTheme.colors
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = c.background, contentColor = c.textPrimary) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = DropSpacing.m, vertical = DropSpacing.s), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DropSpacing.m)) {
                    Thumb(entry, 48.dp, 48.dp, RoundedCornerShape(10.dp))
                    Column(Modifier.weight(1f)) {
                        DropText(entry.title, style = DropTheme.type.bodyStrong, maxLines = 1)
                        DropText(entry.artist.ifBlank { "Lyrics" }, style = DropTheme.type.caption, color = c.textSecondary, maxLines = 1)
                    }
                    // Quick text size, without leaving the lyrics. The same setting lives in Settings.
                    val size by PlayerPrefs.lyricsSize.collectAsStateWithLifecycle()
                    IconButton(onClick = { PlayerPrefs.setLyricsSize((size + 1) % 3) }) { Icon(Icons.Outlined.TextFields, "Change lyrics text size", tint = c.textPrimary) }
                    IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Close lyrics", tint = c.textPrimary) }
                }
                LyricsPanel(lyrics, Modifier.weight(1f).fillMaxWidth())
                Column(Modifier.fillMaxWidth().padding(horizontal = DropSpacing.l, vertical = DropSpacing.s)) {
                    SeekBar(durationMs, c.textPrimary, c.field, c.textPrimary)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TimeLabel(durationMs, c.textSecondary)
                        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                        IconButton(onClick = { PlayerHub.toggle() }) {
                            Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pause" else "Play", tint = c.textPrimary, modifier = Modifier.size(32.dp))
                        }
                    }
                }
            }
        }
    }
}
