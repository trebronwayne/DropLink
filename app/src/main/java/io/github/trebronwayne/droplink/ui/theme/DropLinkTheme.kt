package io.github.trebronwayne.droplink.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

val LocalDropColors = staticCompositionLocalOf { LightDropColors }
val LocalDropTypography = staticCompositionLocalOf { DropTypography() }

/** Accessor for DropLink design tokens: DropTheme.colors, DropTheme.type. */
object DropTheme {
    val colors: DropColors
        @Composable @ReadOnlyComposable get() = LocalDropColors.current
    val type: DropTypography
        @Composable @ReadOnlyComposable get() = LocalDropTypography.current
}

@Composable
fun DropLinkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val c = if (darkTheme) DarkDropColors else LightDropColors
    // Material3 sits underneath only so stock components (ripples, text fields later) match the tokens.
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = c.accent, onPrimary = c.onBrand, background = c.background, onBackground = c.textPrimary,
            surface = c.card, onSurface = c.textPrimary, onSurfaceVariant = c.textSecondary,
            outline = c.stroke, error = c.danger
        )
    } else {
        lightColorScheme(
            primary = c.accent, onPrimary = c.onBrand, background = c.background, onBackground = c.textPrimary,
            surface = c.card, onSurface = c.textPrimary, onSurfaceVariant = c.textSecondary,
            outline = c.stroke, error = c.danger
        )
    }
    CompositionLocalProvider(
        LocalDropColors provides c,
        LocalDropTypography provides DropTypography()
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
