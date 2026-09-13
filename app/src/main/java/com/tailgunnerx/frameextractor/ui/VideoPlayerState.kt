package com.tailgunnerx.frameextractor.ui

import android.content.Context
import android.net.Uri
import android.os.Handler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import com.tailgunnerx.frameextractor.media.VideoFrameDecoder
import com.tailgunnerx.frameextractor.media.VideoInfo
import com.tailgunnerx.frameextractor.util.Timeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * The one place that knows where we are in the clip.
 *
 * The screen used to run two independent pipelines: ExoPlayer drew playback onto a surface, while a
 * `MediaMetadataRetriever` separately decoded a still bitmap for the paused view. Nothing kept them
 * in step, which is where three separate symptoms came from - pausing showed the *previous* still
 * until a fresh seek finished, pressing play jumped to the nearest keyframe instead of the frame on
 * screen, and the two had different ideas about which frame "frame N" was.
 *
 * There is now a single pipeline: the player's hardware decoder renders every frame the user ever
 * sees, paused or playing, and [frameIndex] is the only source of truth for position. Pausing costs
 * nothing because the correct frame is already on the surface, and stepping is a frame-exact seek on
 * a decoder that is already warm.
 */
@Stable
class VideoPlayerState(val player: ExoPlayer) {

    var info by mutableStateOf<VideoInfo?>(null)
        private set

    var isLoading by mutableStateOf(false)
        private set

    var isPlaying by mutableStateOf(false)
        private set

    var isBuffering by mutableStateOf(false)
        private set

    /** Width/height the video should be *drawn* at, 0 until the player reports it. */
    var displayAspectRatio by mutableFloatStateOf(0f)
        private set

    /** Set when the player gives up on the clip. Nothing else on screen can report this: with the
     *  player being the only thing that decodes, a failed playback would otherwise just sit there
     *  showing the "loading" placeholder forever. */
    var playbackError by mutableStateOf<String?>(null)
        private set

    /** The frame the user is looking at. Everything else about position is derived from this. */
    var frameIndex by mutableLongStateOf(0L)
        private set

    var decoder: VideoFrameDecoder? = null
        private set

    /** Compose does not guarantee whether the view or the effect that owns the player is torn down
     *  first, so every entry point that touches the player has to tolerate being called after it. */
    private var released = false

    /** Set when a pause is waiting for the player to actually come to a stop. */
    private var settleOnStop = false

    /**
     * Presentation time of the frame the renderer last put on the surface, i.e. the frame actually
     * being looked at.
     *
     * `currentPosition` is the media clock, which can already be past the last rendered frame's
     * timestamp - reading it on pause reported the *next* frame, and correcting the player onto that
     * frame then dragged the picture forward by one. Asking which frame was rendered removes the
     * guess, and with it the correcting seek and the buffering flash it caused.
     *
     * Written from the playback thread.
     */
    @Volatile
    var lastRenderedUs: Long = C.TIME_UNSET
        private set

    private val frameMetadataListener = VideoFrameMetadataListener { presentationTimeUs, _, _, _ ->
        lastRenderedUs = presentationTimeUs
    }

    /**
     * The player is confined to the thread it was built on and throws if touched from anywhere else.
     * Click handlers are already on it; effects are not - a `LaunchedEffect` inherits whatever
     * dispatcher the composition runs on, which under `createComposeRule` is a worker thread. So
     * every entry point an effect can reach hops here rather than trusting its caller.
     */
    private val playerThread = Handler(player.applicationLooper).asCoroutineDispatcher()

    val fps: Float get() = info?.fps ?: Timeline.DEFAULT_FPS
    val lastFrameIndex: Long get() = info?.lastFrameIndex ?: 0L

    /** Timestamp of the frame on screen - derived, never stored, so it cannot drift out of step. */
    val positionMs: Long get() = Timeline.frameStartMs(frameIndex, fps)

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            isBuffering = playbackState == Player.STATE_BUFFERING
            if (playbackState == Player.STATE_ENDED) {
                isPlaying = false
                player.pause()
                frameIndex = lastFrameIndex
            }
        }

        override fun onIsPlayingChanged(playing: Boolean) {
            // pause() returns before the player stops: it still renders the frame it had queued, so
            // the position read there can be a frame short of what ends up on screen. Saving that
            // would extract a different frame from the one being looked at. Adopt what the player
            // settled on, then snap exactly onto it.
            if (playing || !settleOnStop || player.playWhenReady || info == null) return
            settleOnStop = false
            // Only re-read which frame is on the surface. Deliberately no seek: correcting the
            // player here is what made pausing flash and step a frame forward.
            syncFrameFromPlayer()
        }

        override fun onPlayerError(error: PlaybackException) {
            isPlaying = false
            playbackError = error.errorCodeName
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            displayAspectRatio = Timeline.displayAspectRatio(
                videoSize.width,
                videoSize.height,
                videoSize.pixelWidthHeightRatio,
            )
        }
    }

    init {
        player.volume = 0f
        // Audio is not just muted but deselected: at these playback speeds it would only cost CPU.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .build()
        // The whole point of the app is landing on a specific frame. CLOSEST_SYNC (what this used to
        // ask for, to make Play feel snappy) silently drops you on the nearest keyframe, which can be
        // seconds away from the frame being looked at.
        player.setSeekParameters(SeekParameters.EXACT)
        player.setVideoFrameMetadataListener(frameMetadataListener)
        player.addListener(listener)
    }

    suspend fun load(context: Context, uri: Uri): Throwable? {
        withContext(playerThread) {
            isPlaying = false
            isLoading = true
            playbackError = null
            settleOnStop = false
            lastRenderedUs = C.TIME_UNSET
            frameIndex = 0L
            displayAspectRatio = 0f
            info = null
            closeDecoder()
        }
        val opened = try {
            Result.success(VideoFrameDecoder.open(context, uri))
        } catch (cancellation: CancellationException) {
            withContext(playerThread) { isLoading = false }
            throw cancellation
        } catch (t: Throwable) {
            Result.failure(t)
        }
        withContext(playerThread) {
            isLoading = false
            opened.onSuccess { loaded ->
                decoder = loaded
                info = loaded.info
                player.setMediaItem(MediaItem.fromUri(uri))
                player.prepare()
                player.playWhenReady = false
                player.seekTo(Timeline.seekTargetMs(0L, loaded.info.fps))
            }
        }
        return opened.exceptionOrNull()
    }

    fun unload() {
        isPlaying = false
        settleOnStop = false
        lastRenderedUs = C.TIME_UNSET
        playbackError = null
        info = null
        frameIndex = 0L
        displayAspectRatio = 0f
        player.stop()
        player.clearMediaItems()
        closeDecoder()
    }

    /** Moves [delta] frames and seeks the player there. Used by the step buttons. */
    fun stepFrames(delta: Long) = seekToFrame(frameIndex + delta)

    /** Jumps to an absolute frame. Used by the scrubber. */
    fun seekToFrame(index: Long) {
        val loaded = info ?: return
        // settle = false: the user has named the frame, so a late "the player has stopped" callback
        // must not overwrite it with wherever playback happened to end up.
        stopPlayback(settle = false)
        val target = index.coerceIn(0L, loaded.lastFrameIndex)
        frameIndex = target
        player.seekTo(Timeline.seekTargetMs(target, fps))
    }

    fun play() {
        if (info == null || isPlaying) return
        settleOnStop = false
        // Resume from the frame on screen rather than wherever the player drifted to.
        val target = Timeline.seekTargetMs(frameIndex, fps)
        if (abs(player.currentPosition - target) > Timeline.frameDurationMs(fps)) {
            player.seekTo(target)
        }
        isPlaying = true
        player.play()
    }

    fun pause() = stopPlayback(settle = true)

    private fun stopPlayback(settle: Boolean) {
        settleOnStop = settle
        if (!isPlaying) return
        isPlaying = false
        player.pause()
        // Provisional: refined by onIsPlayingChanged once the player has really stopped. Still no
        // re-decode - re-seeking to a freshly decoded still is what used to make pausing stutter.
        if (settle) syncFrameFromPlayer()
    }

    fun togglePlay() = if (isPlaying) pause() else play()

    /** Called by the position poll while playing, and once more when a pause settles. Never seeks. */
    fun syncFrameFromPlayer() {
        if (info == null) return
        renderedFrameIndex?.let { frameIndex = it; return }

        // Nothing has reached the surface since the seek that put us here. The clock is all that is
        // left, and on its own it is not trustworthy: a seek aims a quarter frame *below* the target
        // frame's timestamp, so reading the clock there names the frame before it and the counter
        // would tick backwards the moment playback started. It exists only to keep the counter
        // moving during playback, where the clock only ever runs forward - so let it do only that.
        val fromClock = Timeline.frameIndexAt(player.currentPosition.coerceAtLeast(0L), fps)
            .coerceIn(0L, lastFrameIndex)
        if (fromClock > frameIndex) frameIndex = fromClock
    }

    /**
     * The frame on the surface, or null before anything has been rendered.
     *
     * Falls back to the clock if the two disagree by more than a second: a container whose frame
     * timestamps are offset from the period would otherwise put the readout somewhere else entirely,
     * and being a frame out beats being a minute out.
     */
    val renderedFrameIndex: Long?
        get() {
            val renderedUs = lastRenderedUs
            if (renderedUs == C.TIME_UNSET || info == null) return null
            if (abs(renderedUs / 1000L - player.currentPosition) > OFFSET_SANITY_MS) return null
            return Timeline.frameIndexOfTimestampUs(renderedUs, fps).coerceIn(0L, lastFrameIndex)
        }

    /** Driven by an effect, so it cannot assume it is already on the player's thread. */
    suspend fun applyPlaybackFps(displayFps: Float) = withContext(playerThread) {
        player.setPlaybackSpeed(Timeline.playbackSpeed(displayFps, fps))
    }

    /** Driven by the position poll, which has the same problem. */
    suspend fun pollPosition() = withContext(playerThread) { syncFrameFromPlayer() }

    fun release() {
        if (released) return
        released = true
        player.removeListener(listener)
        player.clearVideoFrameMetadataListener(frameMetadataListener)
        closeDecoder()
        player.release()
    }

    /** Detaches the output view, unless the player is already gone. */
    fun detachOutput(detach: (ExoPlayer) -> Unit) {
        if (!released) detach(player)
    }

    private fun closeDecoder() {
        decoder?.close()
        decoder = null
    }
}

/** How far the rendered frame's timestamp may sit from the clock before it is not believed. */
private const val OFFSET_SANITY_MS = 1_000L

@Composable
fun rememberVideoPlayerState(): VideoPlayerState {
    val context = LocalContext.current
    return remember { VideoPlayerState(ExoPlayer.Builder(context.applicationContext).build()) }
}
