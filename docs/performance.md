# Performance notes

Why frame stepping and playback felt slow, and what the pipeline looks like now.

## The shape of the problem

The screen used to run **two independent pipelines over the same clip**:

* `ExoPlayer` decoded playback onto a `SurfaceView`.
* A `MediaMetadataRetriever` separately decoded a still `Bitmap` whenever the app was paused.

Nothing kept the two in step, and most of the symptoms were downstream of that:

1. **Pausing stuttered.** On pause the surface was hidden immediately and the *previous* still was
   shown over it, while a fresh `MediaMetadataRetriever` seek ran; the correct frame only appeared
   a few hundred milliseconds later.
2. **Play jumped backwards.** The player was configured with `SeekParameters.CLOSEST_SYNC`, so
   pressing play left the frame on screen and resumed from the nearest keyframe instead - up to a
   whole GOP earlier.
3. **The video was stretched.** A bare `SurfaceView` at `fillMaxSize()` does no letterboxing; that
   is `AspectRatioFrameLayout`'s job inside media3's `PlayerView`. Stills were drawn with
   `ContentScale.Fit`, so the picture also changed shape on every play/pause.
4. **Stepping stayed slow no matter what.** `MediaMetadataRetriever.getFrameAtTime` seeks by
   decoding forward from the preceding keyframe *every single call*, so a one frame step costs a
   whole GOP. Decoding into a smaller box and caching the result cut the cost of the pixels, which
   was never where the time went.
5. **The timeline drifted off the frames.** Position was stepped by an integer "milliseconds per
   frame", which truncates 33.33 to 33. That loses a frame every thirty: on a ten minute 30 fps clip
   the counter ended up 180 frames short and the last six seconds could not be reached at all.

## What it is now

**One pipeline.** The player's hardware decoder renders every frame the user ever sees, paused or
playing, and the frame *index* is the only stored position - milliseconds are always derived from it.

| Area | Before | After |
| --- | --- | --- |
| Paused frame | separate `MediaMetadataRetriever` decode into a `Bitmap` | already on the player's surface; pausing neither decodes nor seeks |
| Which frame is on screen | inferred from `currentPosition`, the media clock | reported by the renderer via `setVideoFrameMetadataListener` |
| Stepping | `getFrameAtTime(OPTION_CLOSEST)`, a full GOP per step | `seekTo` on a warm, already-configured decoder |
| Seek accuracy | `CLOSEST_SYNC` (nearest keyframe) | `SeekParameters.EXACT`, aimed at the middle of the target frame |
| Position | `positionMs`, stepped by a truncated `msPerFrame` | `frameIndex`, with `Timeline.frameMidpointMs` / `frameStartMs` derived from the rational frame rate |
| Scrubber | milliseconds | frame indices, so the thumb can only land on a real frame |
| Output view | `SurfaceView`, hidden on pause (which destroys its surface and rebuilds the codec's output every play/pause) | `TextureView`, permanently on screen |
| Aspect ratio | stretched to the viewer | drawn into a box with the video's own display aspect, `pixelWidthHeightRatio` included |
| Zoom/pan | reset whenever playback started | kept; a `TextureView` composites like any other view |
| State | ~12 `mutableStateOf`s in one 609 line composable | `VideoPlayerState` owns position and playback; the composables only draw |
| `MediaMetadataRetriever` | on the interactive path | metadata at open, and one full resolution decode per saved frame |

`FrameCache` and the `LruCache` behind it are gone: there is nothing left to cache, because no
bitmap is decoded for display any more.

## How it is verified

* `TimelineTest` - JVM unit tests for the frame/time arithmetic. Includes the regressions above:
  that a frame index survives the round trip through its seek timestamp at 23.976/29.97/59.94 fps,
  and that the last frame of a ten minute clip is reachable.
* `VideoFrameDecoderTest` (instrumented) - extraction, against a generated clip whose every frame
  carries its own frame index as a black/white barcode. `everyFrameIndexExtractsThatExactFrame`
  walks all 60 frames **through `Timeline.frameMidpointMs` and the fps the app read from the
  container**, rather than computing timestamps locally the way the previous version of this test
  did - which is why that test could not see the app drifting a frame every thirty.
* `VideoPlayerStateTest` (instrumented) - the interactive path. Starts playback from frame 45, which
  the test clip places deep inside a GOP, and asserts the position never moves backwards; asserts
  that seeking to a frame puts *that* frame on the surface; asserts that after a pause the reported
  frame is the one on screen and that nothing further is rendered.
* `FrameExtractorScreenTest` (instrumented) - drives the real screen end to end.

```
./gradlew assembleDebug testDebugUnitTest          # build + unit tests
./gradlew connectedDebugAndroidTest                # needs a device or emulator
```

The test clip can be regenerated with `python3 tools/generate_test_video.py` (needs ffmpeg + Pillow).

## Not measured

The previous version of this file quoted per-seek timings from the CI emulator. They described the
`MediaMetadataRetriever` preview path, which no longer exists, and they were taken on a 320x240 clip
decoded by a host-side software codec - where per-seek overhead dominates and resolution barely
registers, so they said very little about a phone either way.

Stepping latency is now a property of the player's seek on real hardware. **It has not been measured
on a device**, and a CI emulator is the wrong place to try. If stepping still feels slow on real
footage, the thing to look at first is media3's scrubbing mode (`setScrubbingModeEnabled`, added in
media3 1.6.0), which keeps the decoder hot across a run of seeks; this project is pinned to 1.4.1,
and moving up is likely to require raising `compileSdk`.

## Which frame is on screen

`currentPosition` is the media clock, not a statement about the surface: when playback stops it can
already sit past the last rendered frame's timestamp. Reading it on pause therefore named the *next*
frame, and correcting the player onto that frame dragged the picture forward by one and flashed the
buffering spinner while the codec flushed - a pause that visibly stepped and stuttered.

`ExoPlayer.setVideoFrameMetadataListener` reports the presentation time of each frame as it is
rendered, so the app can read which frame is on the surface instead of inferring it. Pausing now only
re-reads that value: no correcting seek, and nothing reaches the surface after the player stops. The
clock is still the fallback, used when the two disagree by more than a second, which would mean a
container whose frame timestamps are offset from the period.

The spinner is also on a delay now. Every seek buffers briefly, so showing it the moment buffering
starts made each frame step flash.

## Known trade-offs

* A source whose rotation the codec does not apply itself (`VideoSize.unappliedRotationDegrees`)
  is not rotated by the viewer. This was never handled, and correcting it means applying a rotate +
  scale transform to the `TextureView` that cannot be verified without a device with such a clip.
* A `TextureView` costs one more composite per frame than a `SurfaceView`. For a tool whose whole
  job is sitting on a single frame and zooming into it, that is the right side of the trade.
* Playback speed below ~0.1x is dominated by the player's frame scheduler rather than by decode
  throughput.
