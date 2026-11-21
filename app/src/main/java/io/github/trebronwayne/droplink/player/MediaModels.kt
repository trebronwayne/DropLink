package io.github.trebronwayne.droplink.player

/**
 * One playable file. [id] is stable for the life of the file: "a:123" for music, "v:456" for video (the MediaStore
 * row), or "u:..." for a file opened from somewhere else. Dates are in seconds, like MediaStore stores them.
 */
data class MediaEntry(
    val id: String,
    val uri: String,
    val displayName: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val size: Long,
    val mime: String,
    val isVideo: Boolean,
    val dateAdded: Long,
    val dateModified: Long,
    val albumId: Long = 0L,
    /** Folder on the phone, for example "Music/Albums/", when Android tells us. Used to look for lyrics next to the file. */
    val folder: String = "",
    /** Real path when Android shares it (older phones, or files the app owns). May be empty. */
    val path: String = ""
) {
    /** The file name without its extension, which lyrics and subtitle files are matched against. */
    val baseName: String get() = displayName.substringBeforeLast('.', displayName)
}

/** One line of lyrics or one subtitle: shown from [startMs] until [endMs]. */
data class Cue(val startMs: Long, val endMs: Long, val text: String)

/** Lyrics for a song. [synced] lines have times; otherwise only [plain] text is available. */
data class Lyrics(val synced: List<Cue>, val plain: String, val source: String) {
    val isSynced: Boolean get() = synced.isNotEmpty()
    val isEmpty: Boolean get() = synced.isEmpty() && plain.isBlank()
}

/** A subtitle file for a video. [name] says which one it is, for example "English" or "movie.srt". */
data class SubtitleTrack(val name: String, val source: String, val cues: List<Cue>)

/** Everything found next to or inside a file. */
data class MediaText(val lyrics: Lyrics?, val subtitles: List<SubtitleTrack>) {
    companion object { val NONE = MediaText(null, emptyList()) }
}
