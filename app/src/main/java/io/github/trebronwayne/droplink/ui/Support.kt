package io.github.trebronwayne.droplink.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import io.github.trebronwayne.droplink.BuildConfig

/** Where people go for help, feedback and legal information. */
object Support {
    const val EMAIL = "trebronwayne@gmail.com"
    const val REPO = "https://github.com/trebronwayne/DropLink"
    const val ISSUES = "$REPO/issues"
    const val PRIVACY = "$REPO/blob/main/PRIVACY.md"
    const val HOST_PAGE_GUIDE = "$REPO/blob/main/docs/HOST_PAGE.md"

    fun openUrl(context: Context, url: String) {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: ActivityNotFoundException) { }
    }

    /** Opens the user's mail app with a ready-to-send message. Nothing is sent unless the user presses send. */
    fun email(context: Context, subject: String, body: String) {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
            .putExtra(Intent.EXTRA_EMAIL, arrayOf(EMAIL))
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_TEXT, body)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { context.startActivity(intent) } catch (_: ActivityNotFoundException) { }
    }

    /** A report email: the app and phone model for context, then the end of the log (mail apps cannot take very long text). */
    fun report(context: Context, subject: String, log: String) {
        val body = buildString {
            append("Describe what happened (optional):\n\n\n")
            append("---\n")
            append("DropLink ${BuildConfig.VERSION_NAME}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}\n\n")
            append(log.takeLast(9000))
        }
        email(context, subject, body)
    }
}
