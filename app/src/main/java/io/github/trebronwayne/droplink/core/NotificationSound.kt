package io.github.trebronwayne.droplink.core

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import io.github.trebronwayne.droplink.R

/** The DropLink notification sound (res/raw/droplink_notification.ogg, about half a second). */
object NotificationSound {
    /** The sound as a URI that notification channels can play. */
    fun uri(context: Context): Uri =
        Uri.parse("android.resource://${context.packageName}/${R.raw.droplink_notification}")

    val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /** Plays the sound once, so someone switching it on hears what they chose. Follows the notification volume. */
    fun preview(context: Context) {
        try {
            val player = MediaPlayer.create(context.applicationContext, R.raw.droplink_notification, attributes, AudioManager.AUDIO_SESSION_ID_GENERATE)
                ?: return
            player.setOnCompletionListener { it.release() }
            player.setOnErrorListener { mp, _, _ -> mp.release(); true }
            player.start()
        } catch (_: Exception) {
            // No audio output available: nothing to preview.
        }
    }
}
