package io.github.trebronwayne.droplink.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import io.github.trebronwayne.droplink.ui.components.DropButton
import io.github.trebronwayne.droplink.ui.components.DropButtonStyle
import io.github.trebronwayne.droplink.ui.components.DropCard
import io.github.trebronwayne.droplink.ui.components.DropText
import io.github.trebronwayne.droplink.ui.components.DropSwitch
import io.github.trebronwayne.droplink.ui.components.DropTextField
import io.github.trebronwayne.droplink.ui.theme.DropSpacing
import io.github.trebronwayne.droplink.ui.theme.DropTheme

data class SiteUi(
    val folderName: String?,
    val published: Boolean,
    val openToAnyone: Boolean,
    val users: List<String>,
    val submissionCount: Int,
    val submissionsJson: () -> String,
    val urls: List<String>,
    val onPickFolder: () -> Unit,
    val onClearFolder: () -> Unit,
    val onPublishedChange: (Boolean) -> Unit,
    val onOpenToAnyoneChange: (Boolean) -> Unit,
    val onAddUser: (String, String) -> Boolean,
    val onRemoveUser: (String) -> Unit,
    val onClearSubmissions: () -> Unit
)

/** "Host a page": publish a website from a folder, with sign-in accounts and a way for pages to send information back. */
@Composable
fun SiteCard(site: SiteUi) {
    val type = DropTheme.type
    val secondary = DropTheme.colors.textSecondary
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    DropCard {
        DropText("Host a page", style = type.subtitle)
        DropText(
            "Publish a website from a folder on this phone. Visitors open it in their browser, and its pages can sign people in and send information back to you. Everything stays on this phone.",
            style = type.caption, color = secondary
        )
        if (site.folderName == null) {
            DropButton("Choose website folder", site.onPickFolder, Modifier.fillMaxWidth(), icon = Icons.Outlined.CreateNewFolder)
            DropButton("How to build a page", { Support.openUrl(context, Support.HOST_PAGE_GUIDE) }, Modifier.fillMaxWidth(), DropButtonStyle.Subtle)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    DropText(site.folderName, style = type.bodyStrong)
                    DropText("Website folder (needs an index.html)", style = type.caption, color = secondary)
                }
                DropButton("Change", site.onPickFolder, style = DropButtonStyle.Subtle)
                DropButton("Remove", site.onClearFolder, style = DropButtonStyle.Subtle, icon = Icons.Outlined.Delete)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    DropText("Publish the page", style = type.bodyStrong)
                    DropText("Visitors can reach it while hosting is running.", style = type.caption, color = secondary)
                }
                DropSwitch(checked = site.published, onCheckedChange = site.onPublishedChange)
            }
            if (site.published) {
                if (site.urls.isEmpty()) DropText("Start hosting to get the page address.", style = type.caption, color = secondary)
                site.urls.forEach { DropText(it, style = type.bodyStrong, fontFamily = FontFamily.Monospace) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    DropText("Anyone on this network can open it", style = type.bodyStrong)
                    DropText("Turn off to allow only devices you have approved.", style = type.caption, color = secondary)
                }
                DropSwitch(checked = site.openToAnyone, onCheckedChange = site.onOpenToAnyoneChange)
            }
        }
    }
    if (site.folderName == null) return

    DropCard {
        var name by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var note by remember { mutableStateOf("") }
        DropText("Sign-in accounts", style = type.subtitle)
        DropText(
            "Visitors can sign in from your page with these accounts. Files inside a folder named \"private\" are shown only to signed-in visitors. Passwords are stored only as salted hashes.",
            style = type.caption, color = secondary
        )
        site.users.forEach { user ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                DropText(user, style = type.bodyStrong, modifier = Modifier.weight(1f))
                DropButton("Remove", { site.onRemoveUser(user) }, style = DropButtonStyle.Subtle, icon = Icons.Outlined.Delete)
            }
        }
        DropTextField(name, { name = it }, "New account name")
        DropTextField(password, { password = it }, "Password (6 or more characters)", password = true)
        if (note.isNotEmpty()) DropText(note, style = type.caption, color = secondary)
        DropButton(
            "Add account", {
                if (site.onAddUser(name, password)) { name = ""; password = ""; note = "Account added." }
                else note = "Could not add it. Use a new name and a password of 6 or more characters."
            },
            Modifier.fillMaxWidth(), DropButtonStyle.Secondary, enabled = name.isNotBlank() && password.length >= 6
        )
    }

    DropCard {
        DropText("Information sent to you", style = type.subtitle)
        DropText(
            if (site.submissionCount == 0) "Nothing received yet. Pages send information with POST /site-api/submit/<form name>."
            else "${site.submissionCount} item(s) received from your page.",
            style = type.caption, color = secondary
        )
        Row(horizontalArrangement = Arrangement.spacedBy(DropSpacing.s)) {
            DropButton(
                "Copy as JSON", { clipboard.setText(AnnotatedString(site.submissionsJson())) },
                Modifier.weight(1f), DropButtonStyle.Secondary, Icons.Outlined.ContentCopy, enabled = site.submissionCount > 0
            )
            DropButton("Clear", site.onClearSubmissions, style = DropButtonStyle.Subtle, icon = Icons.Outlined.Delete, enabled = site.submissionCount > 0)
        }
    }
}
