package io.github.trebronwayne.droplink.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Soft, calm color tokens. The main action color ("brand") is a clean neutral that flips with the theme:
 * near-black ink on light, soft white on dark. A single teal [accent] picked from the logo is used sparingly
 * (icons, toggles, focus). Feature screens must read colors from here, never hard-code them.
 */
@Immutable
data class DropColors(
    /** Main action color: ink in light theme, soft white in dark theme. */
    val brand: Color,
    val onBrand: Color,
    /** Soft tint behind accent icons. Kept under its old name so older code keeps working. */
    val brandTint: Color,
    val background: Color,
    val card: Color,
    /** Quiet fill for inset areas inside a card: secondary buttons, text fields, segmented controls. */
    val field: Color,
    val stroke: Color,
    val strokeStrong: Color,
    val subtleSelected: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    /** Small touch of the logo's teal. */
    val accent: Color,
    /** Off-state track of switches. */
    val track: Color,
    val success: Color,
    val danger: Color,
    val offline: Color,
    val isDark: Boolean
)

val LightDropColors = DropColors(
    brand = Color(0xFF17181B),
    onBrand = Color(0xFFFFFFFF),
    brandTint = Color(0xFFE4F3F8),
    background = Color(0xFFF3F4F6),
    card = Color(0xFFFFFFFF),
    field = Color(0xFFF1F2F4),
    stroke = Color(0x0F000000),
    strokeStrong = Color(0x1F000000),
    subtleSelected = Color(0x14000000),
    textPrimary = Color(0xFF17181B),
    textSecondary = Color(0xFF6B6E76),
    accent = Color(0xFF0E86B4),
    track = Color(0xFFCDD0D6),
    success = Color(0xFF178A4C),
    danger = Color(0xFFD13438),
    offline = Color(0xFF8E9199),
    isDark = false
)

val DarkDropColors = DropColors(
    brand = Color(0xFFF4F4F6),
    onBrand = Color(0xFF111113),
    brandTint = Color(0x264FC3DC),
    background = Color(0xFF111113),
    card = Color(0xFF1B1B1F),
    field = Color(0xFF26262B),
    stroke = Color(0x14FFFFFF),
    strokeStrong = Color(0x26FFFFFF),
    subtleSelected = Color(0x1FFFFFFF),
    textPrimary = Color(0xFFF4F4F6),
    textSecondary = Color(0xFF9B9CA5),
    accent = Color(0xFF55C6DE),
    track = Color(0xFF3B3B42),
    success = Color(0xFF5FD38D),
    danger = Color(0xFFFF8A94),
    offline = Color(0xFF8E9199),
    isDark = true
)

object DropRadius {
    val small = 10.dp
    val medium = 14.dp
    val large = 22.dp
    val pill = 100.dp
}

object DropSpacing {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

/** Type ramp (system font). Slightly roomier line heights than before for a calmer feel. */
@Immutable
data class DropTypography(
    val caption: TextStyle = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Normal),
    /** Small section label shown above a group of settings. Use with uppercase text. */
    val label: TextStyle = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp),
    val body: TextStyle = TextStyle(fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Normal),
    val bodyStrong: TextStyle = TextStyle(fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold),
    val subtitle: TextStyle = TextStyle(fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
    val title: TextStyle = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    val largeTitle: TextStyle = TextStyle(fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp)
)
