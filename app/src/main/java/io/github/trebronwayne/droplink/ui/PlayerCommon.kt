package io.github.trebronwayne.droplink.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.trebronwayne.droplink.player.Cue
import io.github.trebronwayne.droplink.player.MediaEntry
import io.github.trebronwayne.droplink.player.Thumbs
import io.github.trebronwayne.droplink.ui.theme.DropRadius
import io.github.trebronwayne.droplink.ui.theme.DropTheme

/* Small pieces shared by the player screens. */

internal fun fmtTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Index of the last cue that starts at or before [pos], or -1. */
internal fun List<Cue>.indexAt(pos: Long): Int {
    var lo = 0
    var hi = size - 1
    var found = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (this[mid].startMs <= pos) { found = mid; lo = mid + 1 } else hi = mid - 1
    }
    return found
}

/** A rounded action chip: an icon and a word. [on] shows it as switched on. */
@Composable
internal fun Pill(icon: ImageVector, label: String, on: Boolean = false, onClick: () -> Unit) {
    val c = DropTheme.colors
    Row(
        Modifier.clip(RoundedCornerShape(DropRadius.pill)).background(if (on) c.subtleSelected else c.field).clickable(onClick = onClick)
            .heightIn(min = 40.dp).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, null, tint = if (on) c.accent else c.textPrimary, modifier = Modifier.size(18.dp))
        Text(label, color = c.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/** Cover art or a video frame; a quiet icon while loading or when there is none. */
@Composable
internal fun Thumb(e: MediaEntry, w: Dp, h: Dp, shape: Shape) {
    val c = DropTheme.colors
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val bmp by produceState<ImageBitmap?>(null, e.id) {
        value = Thumbs.load(ctx, e, with(density) { w.roundToPx() }.coerceAtLeast(64), with(density) { h.roundToPx() }.coerceAtLeast(64))?.asImageBitmap()
    }
    Box(Modifier.size(w, h).clip(shape).background(c.field), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b, null, contentScale = ContentScale.Crop, modifier = Modifier.size(w, h))
        else Icon(if (e.isVideo) Icons.Outlined.Movie else Icons.Outlined.MusicNote, null, tint = c.textSecondary, modifier = Modifier.size(minOf(w, h) / 2))
    }
}
