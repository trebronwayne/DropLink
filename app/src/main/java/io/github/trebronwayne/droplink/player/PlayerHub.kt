@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package io.github.trebronwayne.droplink.player

import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The one place the screen, the notification and the live stream talk to the player.
 * It connects to [MediaPlaybackService] and publishes what is playing as flows.
 * Call everything from the main thread, except reading [snapshot] and [queueSnapshot], which any thread may do.
 */
object PlayerHub {
    data class State(
        val current: MediaEntry? = null,
        val queue: List<MediaEntry> = emptyList(),
        val index: Int = -1,
        val playing: Boolean = false,
        val buffering: Boolean = false,
        val durationMs: Long = 0L,
        val shuffle: Boolean = false,
        val repeat: Int = Player.REPEAT_MODE_OFF,
        val speed: Float = 1f
    )

    /** What the web page needs to follow along: which item, whether it plays, and where it was at time [at]. */
    data class Snapshot(val entryId: String? = null, val playing: Boolean = false, val positionMs: Long = 0L, val durationMs: Long = 0L, val at: Long = 0L, val speed: Float = 1f)

    private val _state = MutableStateFlow(State())
    private val _position = MutableStateFlow(0L)
    private val _controller = MutableStateFlow<Player?>(null)
    private val _text = MutableStateFlow(MediaText.NONE)
    private val _subtitle = MutableStateFlow(-1)

    val state: StateFlow<State> = _state.asStateFlow()
    /** Playback position in ms. Separate from [state] because it changes several times a second. */
    val position: StateFlow<Long> = _position.asStateFlow()
    /** The connected player, for the video surface. Null until the service is up. */
    val controller: StateFlow<Player?> = _controller.asStateFlow()
    /** Lyrics and subtitles found for the current item. */
    val text: StateFlow<MediaText> = _text.asStateFlow()
    /** Which subtitle track is shown, or -1 for none. */
    val subtitle: StateFlow<Int> = _subtitle.asStateFlow()

    @Volatile var snapshot: Snapshot = Snapshot()
        private set
    @Volatile var queueSnapshot: List<MediaEntry> = emptyList()
        private set

    private var app: Context? = null
    private var future: ListenableFuture<MediaController>? = null
    private val pending = ArrayList<(Player) -> Unit>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ticker: Job? = null
    private var textJob: Job? = null
    private var textFor: String? = null
    private var queue: List<MediaEntry> = emptyList()

    /** Starts the connection to the playback service. Safe to call again. */
    fun connect(context: Context) {
        val ctx = context.applicationContext
        app = ctx
        Sidecars.init(ctx)
        if (future != null || _controller.value != null) return
        val token = SessionToken(ctx, ComponentName(ctx, MediaPlaybackService::class.java))
        val f = MediaController.Builder(ctx, token)
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) {
                    // The service ended (nothing was playing and the app was swiped away). The next play reconnects.
                    _controller.value = null
                    future = null
                    publish()
                }
            })
            .buildAsync()
        future = f
        f.addListener({
            try { attach(f.get()) } catch (_: Exception) { future = null }
        }, ContextCompat.getMainExecutor(ctx))
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publish()
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) || events.contains(Player.EVENT_TIMELINE_CHANGED)) {
                val entry = currentEntry(player)
                if (entry?.id != textFor) loadText(entry)
            }
        }
    }

    private fun attach(c: MediaController) {
        _controller.value = c
        c.addListener(listener)
        val queued = pending.toList()
        pending.clear()
        queued.forEach { it(c) }
        publish()
        if (ticker == null) {
            ticker = scope.launch {
                while (isActive) {
                    delay(400)
                    tick()
                }
            }
        }
    }

    private fun currentEntry(p: Player): MediaEntry? {
        val id = p.currentMediaItem?.mediaId ?: return null
        return queue.firstOrNull { it.id == id }
    }

    private fun tick() {
        val c = _controller.value ?: return
        val pos = c.currentPosition.coerceAtLeast(0L)
        _position.value = pos
        val entry = currentEntry(c)
        snapshot = Snapshot(entry?.id, c.isPlaying, pos, durationOf(c, entry), System.currentTimeMillis(), c.playbackParameters.speed)
    }

    private fun durationOf(c: Player, entry: MediaEntry?): Long {
        val d = c.duration
        return if (d > 0) d else entry?.durationMs ?: 0L
    }

    private fun publish() {
        val c = _controller.value
        if (c == null) {
            _state.value = State()
            snapshot = Snapshot()
            return
        }
        val entry = currentEntry(c)
        _state.value = State(
            current = entry, queue = queue, index = c.currentMediaItemIndex,
            playing = c.isPlaying || (c.playWhenReady && c.playbackState == Player.STATE_BUFFERING),
            buffering = c.playbackState == Player.STATE_BUFFERING,
            durationMs = durationOf(c, entry), shuffle = c.shuffleModeEnabled, repeat = c.repeatMode, speed = c.playbackParameters.speed
        )
        queueSnapshot = queue
        tick()
    }

    private fun loadText(entry: MediaEntry?) {
        textJob?.cancel()
        textFor = entry?.id
        _text.value = MediaText.NONE
        _subtitle.value = -1
        val ctx = app ?: return
        if (entry == null) return
        textJob = scope.launch {
            val t = MediaTextRepo.load(ctx, entry)
            _text.value = t
            _subtitle.value = if (t.subtitles.isNotEmpty()) 0 else -1
        }
    }

    /** Looks for lyrics and subtitles again, for example after a file was picked by hand or a folder was added. */
    fun reloadText() {
        val entry = _state.value.current ?: return
        MediaTextRepo.forget(entry.id)
        loadText(entry)
    }

    private fun whenReady(action: (Player) -> Unit) {
        val c = _controller.value
        if (c != null) action(c) else {
            pending += action
            app?.let { connect(it) }
        }
    }

    private fun item(e: MediaEntry): MediaItem {
        val art = if (!e.isVideo && e.albumId > 0) ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), e.albumId) else null
        return MediaItem.Builder()
            .setMediaId(e.id)
            .setUri(Uri.parse(e.uri))
            .setMimeType(e.mime.takeIf { !it.endsWith("/*") })
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle(e.title).setArtist(e.artist.ifBlank { null }).setAlbumTitle(e.album.ifBlank { null })
                    .setArtworkUri(art).build()
            )
            .build()
    }

    /** Plays [list] starting at [index]. What follows is the rest of the list, newest to oldest. */
    fun play(list: List<MediaEntry>, index: Int) {
        if (list.isEmpty()) return
        val start = index.coerceIn(0, list.size - 1)
        // A very long list is trimmed around the chosen item, so the player stays light.
        val from = (start - 150).coerceAtLeast(0)
        val to = (start + 650).coerceAtMost(list.size)
        val part = list.subList(from, to).toList()
        val startInPart = start - from
        whenReady { c ->
            queue = part
            c.setMediaItems(part.map { item(it) }, startInPart, 0L)
            c.prepare()
            c.play()
            publish()
            loadText(part[startInPart])
        }
    }

    fun toggle() = whenReady { c -> if (c.isPlaying || c.playWhenReady) c.pause() else { if (c.playbackState == Player.STATE_ENDED) c.seekTo(0); c.play() } }
    fun next() = whenReady { it.seekToNextMediaItem() }
    fun previous() = whenReady { c -> if (c.currentPosition > 3_000) c.seekTo(0) else c.seekToPreviousMediaItem() }
    fun seekTo(ms: Long) = whenReady { c -> c.seekTo(ms.coerceAtLeast(0)); tick() }
    fun seekBy(deltaMs: Long) = whenReady { c -> c.seekTo((c.currentPosition + deltaMs).coerceIn(0, maxOf(0L, c.duration))); tick() }
    fun setShuffle(on: Boolean) = whenReady { it.shuffleModeEnabled = on }
    fun cycleRepeat() = whenReady {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }
    fun setSpeed(speed: Float) = whenReady { it.playbackParameters = PlaybackParameters(speed) }
    fun playIndex(i: Int) = whenReady { c -> if (i in 0 until c.mediaItemCount) { c.seekTo(i, 0L); c.play() } }
    fun selectSubtitle(i: Int) { _subtitle.value = i }

    /** Stops playback and clears the queue, which also removes the notification. */
    fun stop() = whenReady { c -> c.stop(); c.clearMediaItems(); queue = emptyList(); publish() }

    /** Videos pause when the app goes to the background; music carries on. */
    fun onAppBackground() {
        val c = _controller.value ?: return
        if (currentEntry(c)?.isVideo == true && c.isPlaying) c.pause()
    }
}
