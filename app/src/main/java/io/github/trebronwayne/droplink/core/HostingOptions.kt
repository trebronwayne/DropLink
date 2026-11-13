package io.github.trebronwayne.droplink.core

import android.content.Context

/** User-changeable hosting options. */
object HostingOptions {
    private const val PREFS = "droplink_options"
    private const val KEY_HTTP = "use_http"
    private const val KEY_SOUND = "notification_sound"

    /** Testing mode: serve plain HTTP instead of HTTPS. Off by default; only for diagnosing problems. */
    fun useHttp(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_HTTP, false)

    fun setUseHttp(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_HTTP, value).apply()
    }

    /** Whether notifications (pairing and upload requests, received files) play the DropLink sound. On by default. */
    fun notificationSound(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SOUND, true)

    fun setNotificationSound(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SOUND, value).apply()
    }
}
