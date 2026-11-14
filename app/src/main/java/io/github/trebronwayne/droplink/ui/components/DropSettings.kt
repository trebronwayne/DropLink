package io.github.trebronwayne.droplink.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.trebronwayne.droplink.ui.theme.DropRadius
import io.github.trebronwayne.droplink.ui.theme.DropSpacing
import io.github.trebronwayne.droplink.ui.theme.DropTheme

/**
 * Building blocks for calm, well-organised settings: a small label, then one soft card holding a list of rows
 * separated by hairlines. Put one idea in each group.
 */
@Composable
fun DropSettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val c = DropTheme.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DropSpacing.s)) {
        DropText(
            title.uppercase(), style = DropTheme.type.label, color = c.textSecondary,
            modifier = Modifier.padding(start = DropSpacing.l, top = DropSpacing.s)
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(DropRadius.large),
            color = c.card,
            contentColor = c.textPrimary,
            border = BorderStroke(1.dp, c.stroke)
        ) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
        if (footer != null) {
            DropText(
                footer, style = DropTheme.type.caption, color = c.textSecondary,
                modifier = Modifier.padding(horizontal = DropSpacing.l)
            )
        }
    }
}

/** Hairline between two rows of a group. [inset] lines it up with the text when rows have icons. */
@Composable
fun DropRowDivider(inset: Boolean = true) {
    HorizontalDivider(
        modifier = Modifier.padding(start = if (inset) 64.dp else DropSpacing.l),
        thickness = 1.dp,
        color = DropTheme.colors.stroke
    )
}

/** A small rounded square holding a quiet icon. */
@Composable
fun DropRowIcon(icon: ImageVector) {
    val c = DropTheme.colors
    Box(
        Modifier.size(36.dp).background(c.field, RoundedCornerShape(DropRadius.small)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = c.textPrimary, modifier = Modifier.size(19.dp))
    }
}

@Composable
fun DropChevron() {
    Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = DropTheme.colors.textSecondary, modifier = Modifier.size(20.dp))
}

/** One row: optional icon, a title with an optional line below, and anything you like on the right. */
@Composable
fun DropSettingRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val type = DropTheme.type
    val c = DropTheme.colors
    val clickable = if (onClick != null && enabled) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(clickable)
            .heightIn(min = 60.dp)
            .padding(horizontal = DropSpacing.l, vertical = DropSpacing.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DropSpacing.m)
    ) {
        if (icon != null) DropRowIcon(icon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            DropText(title, style = type.bodyStrong, color = if (enabled) c.textPrimary else c.textSecondary)
            if (subtitle != null) DropText(subtitle, style = type.caption, color = c.textSecondary)
        }
        if (trailing != null) trailing()
    }
}

/** A row that opens or does something, with a chevron. */
@Composable
fun DropLinkRow(title: String, icon: ImageVector, onClick: () -> Unit, subtitle: String? = null) {
    DropSettingRow(title, subtitle = subtitle, icon = icon, onClick = onClick, trailing = { DropChevron() })
}

/** A row with an on/off switch. The whole row is tappable. */
@Composable
fun DropSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true
) {
    DropSettingRow(
        title = title, subtitle = subtitle, icon = icon, enabled = enabled,
        modifier = modifier,
        onClick = { onCheckedChange(!checked) },
        trailing = { DropSwitch(checked, onCheckedChange, enabled = enabled) }
    )
}

/** Soft pill switch: a touch of teal when on, a quiet grey track when off. */
@Composable
fun DropSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = DropTheme.colors
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = c.accent,
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = if (c.isDark) Color(0xFFD7D7DD) else Color.White,
            uncheckedTrackColor = c.track,
            uncheckedBorderColor = Color.Transparent,
            disabledCheckedThumbColor = Color.White.copy(alpha = 0.8f),
            disabledCheckedTrackColor = c.accent.copy(alpha = 0.4f),
            disabledCheckedBorderColor = Color.Transparent,
            disabledUncheckedThumbColor = Color.White.copy(alpha = 0.8f),
            disabledUncheckedTrackColor = c.track.copy(alpha = 0.5f),
            disabledUncheckedBorderColor = Color.Transparent
        )
    )
}

/** A row that folds away its details. Closed by default so long content (logs, lists) does not crowd the page. */
@Composable
fun DropExpandableRow(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    startOpen: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    var open by rememberSaveable(title) { mutableStateOf(startOpen) }
    Column(modifier.fillMaxWidth()) {
        DropSettingRow(
            title, subtitle = subtitle, icon = icon, onClick = { open = !open },
            trailing = {
                Icon(
                    if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (open) "Collapse" else "Expand",
                    tint = DropTheme.colors.textSecondary, modifier = Modifier.size(22.dp)
                )
            }
        )
        AnimatedVisibility(visible = open) {
            Column(
                Modifier.fillMaxWidth().padding(start = DropSpacing.l, end = DropSpacing.l, bottom = DropSpacing.l),
                verticalArrangement = Arrangement.spacedBy(DropSpacing.m),
                content = content
            )
        }
    }
}

/** Pill-shaped choice between a few options, for example Ask / Always / Block. */
@Composable
fun <T> DropSegmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val c = DropTheme.colors
    Row(
        modifier = modifier.fillMaxWidth().background(c.field, RoundedCornerShape(DropRadius.pill)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        options.forEach { (value, label) ->
            val on = value == selected
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .clip(RoundedCornerShape(DropRadius.pill))
                    .background(if (on) (if (c.isDark) c.track else c.card) else Color.Transparent)
                    .selectable(selected = on, onClick = { onSelect(value) }, role = Role.RadioButton),
                contentAlignment = Alignment.Center
            ) {
                DropText(
                    label,
                    style = if (on) DropTheme.type.bodyStrong else DropTheme.type.body,
                    color = if (on) c.textPrimary else c.textSecondary
                )
            }
        }
    }
}

/** Rounded text field that matches the rest of the app. */
@Composable
fun DropTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    password: Boolean = false
) {
    val c = DropTheme.colors
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        singleLine = true,
        label = { Text(label) },
        shape = RoundedCornerShape(DropRadius.medium),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = c.textPrimary,
            unfocusedTextColor = c.textPrimary,
            focusedContainerColor = c.field,
            unfocusedContainerColor = c.field,
            focusedBorderColor = c.accent,
            unfocusedBorderColor = Color.Transparent,
            focusedLabelColor = c.accent,
            unfocusedLabelColor = c.textSecondary,
            cursorColor = c.accent
        )
    )
}

/** A value shown in a quiet inset box, for IDs and fingerprints. */
@Composable
fun DropCodeBox(text: String, modifier: Modifier = Modifier) {
    val c = DropTheme.colors
    Box(modifier.fillMaxWidth().background(c.field, RoundedCornerShape(DropRadius.medium)).padding(DropSpacing.m)) {
        DropText(text, style = DropTheme.type.caption, fontFamily = FontFamily.Monospace)
    }
}
