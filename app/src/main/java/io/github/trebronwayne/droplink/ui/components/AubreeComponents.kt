package io.github.trebronwayne.droplink.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.trebronwayne.aubree.AubreeState
import com.trebronwayne.aubree.compose.Aubree
import io.github.trebronwayne.droplink.ui.theme.DropRadius
import io.github.trebronwayne.droplink.ui.theme.DropSpacing
import io.github.trebronwayne.droplink.ui.theme.DropTheme
import kotlinx.coroutines.delay

/** One small message from Aubree, shown for a few seconds at the top of the screen. */
data class AubreeToast(val id: Long, val state: AubreeState, val message: String)

/** Holds the toast on screen. A new toast replaces the one showing, so messages never pile up. */
@Stable
class AubreeToasts {
    var current by mutableStateOf<AubreeToast?>(null)
        private set
    private var counter = 0L

    fun show(state: AubreeState, message: String) {
        counter += 1
        current = AubreeToast(counter, state, message)
    }

    fun dismiss(id: Long) {
        if (current?.id == id) current = null
    }
}

/** The small banner: Aubree at 40 dp beside one line of text. Put it at the top of a Box. */
@Composable
fun AubreeToastHost(toasts: AubreeToasts, modifier: Modifier = Modifier) {
    val toast = toasts.current
    // Remember the last toast so the text stays readable while the banner slides away.
    var last by remember { mutableStateOf<AubreeToast?>(null) }
    LaunchedEffect(toast) { if (toast != null) last = toast }
    LaunchedEffect(toast?.id) {
        val showing = toast ?: return@LaunchedEffect
        delay(4000)
        toasts.dismiss(showing.id)
    }
    AnimatedVisibility(
        visible = toast != null,
        modifier = modifier,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut()
    ) {
        val shown = toast ?: last
        if (shown != null) AubreeBanner(shown)
    }
}

@Composable
private fun AubreeBanner(toast: AubreeToast) {
    val c = DropTheme.colors
    Surface(
        modifier = Modifier
            .padding(horizontal = DropSpacing.l, vertical = DropSpacing.s)
            .widthIn(max = 420.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(DropRadius.large),
        color = c.card,
        contentColor = c.textPrimary,
        border = BorderStroke(1.dp, c.stroke),
        shadowElevation = 6.dp
    ) {
        Row(
            modifier = Modifier.padding(start = DropSpacing.s, end = DropSpacing.l, top = DropSpacing.xs, bottom = DropSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DropSpacing.s)
        ) {
            Aubree(state = toast.state, size = 40.dp, contentDescription = "")
            DropText(toast.message, style = DropTheme.type.bodyStrong, modifier = Modifier.padding(vertical = DropSpacing.s))
        }
    }
}

/** An empty or waiting screen with Aubree in the middle instead of an icon. */
@Composable
fun AubreeEmptyState(state: AubreeState, title: String, body: String) {
    DropCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = DropSpacing.l),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(DropSpacing.s)
        ) {
            Aubree(state = state, size = 132.dp, contentDescription = "")
            DropText(title, style = DropTheme.type.subtitle, textAlign = TextAlign.Center)
            DropText(body, style = DropTheme.type.body, color = DropTheme.colors.textSecondary, textAlign = TextAlign.Center)
        }
    }
}

/** A compact card for the Home screen: Aubree on the left, what is happening on the right. */
@Composable
fun AubreeStatusCard(state: AubreeState, title: String, body: String) {
    DropCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DropSpacing.m)) {
            Aubree(state = state, size = 72.dp, contentDescription = "")
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(DropSpacing.xs)) {
                DropText(title, style = DropTheme.type.subtitle)
                DropText(body, style = DropTheme.type.caption, color = DropTheme.colors.textSecondary)
            }
        }
    }
}

/** Shown once, the first time DropLink opens. */
@Composable
fun MeetAubreeDialog(onDone: () -> Unit) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
    ) {
        DropCard {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(DropSpacing.s)
            ) {
                Aubree(state = AubreeState.WELCOME, size = 200.dp, contentDescription = "Aubree waving hello")
                DropText("Meet Aubree \uD83E\uDD79", style = DropTheme.type.title, textAlign = TextAlign.Center)
                DropText(
                    "Aubree is the DropLink mascot. She tells you when someone wants to connect, when something is done, and when something is not going well.",
                    style = DropTheme.type.body,
                    color = DropTheme.colors.textSecondary,
                    textAlign = TextAlign.Center
                )
                DropButton("Say hi", onDone, Modifier.fillMaxWidth())
            }
        }
    }
}
