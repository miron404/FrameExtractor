package com.tailgunnerx.frameextractor.ui

import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 5f

/**
 * Shows whatever the player's decoder has rendered - which is every frame the user ever sees, both
 * while playing and while paused on a single frame.
 *
 * Two things here are deliberate:
 *
 * * The video is drawn into a box with the *video's* aspect ratio rather than stretched over the
 *   whole viewer. A bare `SurfaceView`/`TextureView` does no letterboxing of its own; that is what
 *   `AspectRatioFrameLayout` does inside media3's `PlayerView`, and without it a 16:9 clip is
 *   squashed into whatever shape the viewer happens to be.
 * * It is a [TextureView], not a `SurfaceView`. A `SurfaceView` cannot be transformed, so zoom had
 *   to be thrown away whenever playback started, and hiding one destroys its surface - which meant
 *   every play/pause cycle tore down and rebuilt the codec's output. A `TextureView` composites like
 *   any other view, so it can stay on screen permanently and be scaled and panned.
 */
@Composable
fun FrameViewer(
    state: VideoPlayerState,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    var zoom by remember { mutableFloatStateOf(MIN_ZOOM) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var viewerSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = modifier
            .background(Color.Black)
            .clipToBounds()
            .onSizeChanged { viewerSize = it }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoomChange, _ ->
                    val updatedZoom = (zoom * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
                    val maxX = (updatedZoom - 1f) * size.width / 2f
                    val maxY = (updatedZoom - 1f) * size.height / 2f
                    zoom = updatedZoom
                    offset = Offset(
                        (offset.x + pan.x).coerceIn(-maxX, maxX),
                        (offset.y + pan.y).coerceIn(-maxY, maxY),
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val aspect = state.displayAspectRatio
        // Until the player reports a video size nothing is being rendered, so filling the (black)
        // viewer is both harmless and keeps the TextureView - and therefore the output surface -
        // from being torn down and recreated between clips.
        val fit = when {
            aspect <= 0f || viewerSize.width <= 0 || viewerSize.height <= 0 -> Modifier.fillMaxSize()
            aspect > viewerSize.width.toFloat() / viewerSize.height.toFloat() ->
                Modifier.fillMaxWidth().aspectRatio(aspect)
            else -> Modifier.fillMaxHeight().aspectRatio(aspect)
        }

        AndroidView(
            factory = { viewContext ->
                TextureView(viewContext).also(state.player::setVideoTextureView)
            },
            onRelease = { view -> state.detachOutput { it.clearVideoTextureView(view as TextureView) } },
            modifier = fit.graphicsLayer {
                scaleX = zoom
                scaleY = zoom
                translationX = offset.x
                translationY = offset.y
            },
        )

        val error = state.playbackError
        if (error != null) {
            Text(text = "Cannot play this video ($error)", color = Color.Gray)
        } else if (state.info == null) {
            Text(text = placeholder, color = Color.Gray)
        }

        if (state.isBuffering) {
            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(48.dp))
        }
    }
}
