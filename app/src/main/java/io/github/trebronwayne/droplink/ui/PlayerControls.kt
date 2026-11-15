package io.github.trebronwayne.droplink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.trebronwayne.droplink.player.PlayerHub
import io.github.trebronwayne.droplink.ui.theme.DropTheme

/** A seek bar that follows the player and jumps when released. Position is read here, so only this bar redraws. */
@Composable
internal fun SeekBar(durationMs: Long, active: Color, track: Color, thumb: Color, modifier: Modifier = Modifier) {
    val pos by PlayerHub.position.collectAsStateWithLifecycle()
    var drag by remember { mutableStateOf<Float?>(null) }
    val max = durationMs.coerceAtLeast(1L).toFloat()
    Slider(
        value = (drag ?: pos.toFloat()).coerceIn(0f, max), onValueChange = { drag = it },
        onValueChangeFinished = { drag?.let { PlayerHub.seekTo(it.toLong()) }; drag = null },
        valueRange = 0f..max, modifier = modifier.fillMaxWidth().height(28.dp),
        colors = SliderDefaults.colors(thumbColor = thumb, activeTrackColor = active, inactiveTrackColor = track)
    )
}

@Composable
internal fun TimeLabel(durationMs: Long, color: Color) {
    val pos by PlayerHub.position.collectAsStateWithLifecycle()
    Text("${fmtTime(pos)} / ${fmtTime(durationMs)}", color = color, fontSize = 12.sp)
}

/** The seek bar and previous, play or pause, next. */
@Composable
internal fun Transport(durationMs: Long, playing: Boolean, modifier: Modifier = Modifier) {
    val c = DropTheme.colors
    Column(modifier.fillMaxWidth()) {
        SeekBar(durationMs, c.textPrimary, c.field, c.textPrimary)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TimeLabel(durationMs, c.textSecondary)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { PlayerHub.previous() }) { Icon(Icons.Outlined.SkipPrevious, "Previous", tint = c.textPrimary, modifier = Modifier.size(28.dp)) }
            Box(Modifier.size(52.dp).background(c.brand, CircleShape).clickable { PlayerHub.toggle() }, contentAlignment = Alignment.Center) {
                Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pause" else "Play", tint = c.onBrand, modifier = Modifier.size(30.dp))
            }
            IconButton(onClick = { PlayerHub.next() }) { Icon(Icons.Outlined.SkipNext, "Next", tint = c.textPrimary, modifier = Modifier.size(28.dp)) }
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(72.dp))
        }
    }
}
