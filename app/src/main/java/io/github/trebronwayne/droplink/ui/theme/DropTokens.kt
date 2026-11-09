package io.github.trebronwayne.droplink.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Fluent 2 inspired color tokens. Feature screens must read colors from here, never hard-code them. */
@Immutable
data class DropColors(
    val brand: Color,
    val onBrand: Color,
    val brandTint: Color,
    val background: Color,
    val card: Color,
    val stroke: Color,
    val strokeStrong: Color,
    val subtleSelected: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val success: Color,
    val danger: Color,
    val offline: Color,
    val isDark: Boolean
)

val LightDropColors = DropColors(
    brand = Color(0xFF0F6CBD),
    onBrand = Color(0xFFFFFFFF),
    brandTint = Color(0xFFDCEBF9),
    background = Color(0xFFF3F3F3),
    card = Color(0xFFFFFFFF),
    stroke = Color(0xFFE0E0E0),
    strokeStrong = Color(0xFFD1D1D1),
    subtleSelected = Color(0x0F000000),
    textPrimary = Color(0xFF242424),
    textSecondary = Color(0xFF616161),
    success = Color(0xFF107C10),
    danger = Color(0xFFC50F1F),
    offline = Color(0xFF8A8886),
    isDark = false
)

val DarkDropColors = DropColors(
    brand = Color(0xFF2886DE),
    onBrand = Color(0xFFFFFFFF),
    brandTint = Color(0xFF16314D),
    background = Color(0xFF202020),
    card = Color(0xFF2B2B2B),
    stroke = Color(0xFF424242),
    strokeStrong = Color(0xFF5C5C5C),
    subtleSelected = Color(0x1AFFFFFF),
    textPrimary = Color(0xFFF5F5F5),
    textSecondary = Color(0xFFADADAD),
    success = Color(0xFF6CCB5F),
    danger = Color(0xFFFF99A4),
    offline = Color(0xFF8A8886),
    isDark = true
)

object DropRadius {
    val small = 4.dp
    val medium = 8.dp
    val large = 12.dp
}

object DropSpacing {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

/** Fluent type ramp (system font is used; Segoe UI is not available on Android). */
@Immutable
data class DropTypography(
    val caption: TextStyle = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal),
    val body: TextStyle = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
    val bodyStrong: TextStyle = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    val subtitle: TextStyle = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    val title: TextStyle = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    val largeTitle: TextStyle = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold)
)
