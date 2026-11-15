package io.github.trebronwayne.droplink.player

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Settings of the player. Each one is a flow so the screen follows changes at once. */
object PlayerPrefs {
    private const val PREFS = "droplink_player_settings"
    @Volatile private var prefs: android.content.SharedPreferences? = null

    private val _resumeVideos = MutableStateFlow(true)
    private val _keepScreenOn = MutableStateFlow(true)
    private val _lyricsPreview = MutableStateFlow(true)
    private val _lyricsSize = MutableStateFlow(1)
    private val _minMusicSeconds = MutableStateFlow(10)

    /** Start a video where it was left off. */
    val resumeVideos: StateFlow<Boolean> = _resumeVideos.asStateFlow()
    /** Keep the screen awake while a video plays. */
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn.asStateFlow()
    /** Show the current lyric line next to the cover. */
    val lyricsPreview: StateFlow<Boolean> = _lyricsPreview.asStateFlow()
    /** 0 small, 1 medium, 2 large. */
    val lyricsSize: StateFlow<Int> = _lyricsSize.asStateFlow()
    /** Music shorter than this is left out of the list (sound effects, clips). */
    val minMusicSeconds: StateFlow<Int> = _minMusicSeconds.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _resumeVideos.value = p.getBoolean("resume_videos", true)
        _keepScreenOn.value = p.getBoolean("keep_screen_on", true)
        _lyricsPreview.value = p.getBoolean("lyrics_preview", true)
        _lyricsSize.value = p.getInt("lyrics_size", 1).coerceIn(0, 2)
        _minMusicSeconds.value = p.getInt("min_music_seconds", 10)
    }

    fun setResumeVideos(on: Boolean) { _resumeVideos.value = on; prefs?.edit()?.putBoolean("resume_videos", on)?.apply() }
    fun setKeepScreenOn(on: Boolean) { _keepScreenOn.value = on; prefs?.edit()?.putBoolean("keep_screen_on", on)?.apply() }
    fun setLyricsPreview(on: Boolean) { _lyricsPreview.value = on; prefs?.edit()?.putBoolean("lyrics_preview", on)?.apply() }
    fun setLyricsSize(size: Int) { _lyricsSize.value = size.coerceIn(0, 2); prefs?.edit()?.putInt("lyrics_size", size.coerceIn(0, 2))?.apply() }
    fun setMinMusicSeconds(seconds: Int) { _minMusicSeconds.value = seconds; prefs?.edit()?.putInt("min_music_seconds", seconds)?.apply() }
}
