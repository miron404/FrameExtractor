# Performance notes

Why frame stepping and video playback used to feel slow, and what changed.

## What was wrong

1. **A permanent 16 ms polling loop ran a full seek every time it woke up.**
   `LaunchedEffect(videoUri)` looped `while (true) { if (target != decoded) getFrameAtTime(...); delay(16) }`.
   Every frame therefore cost a synchronous `MediaMetadataRetriever` seek, and the loop kept the
   CPU busy even when nobody was touching the app.

2. **Frames were decoded at source resolution.** A 4K clip produced an 8 MB `ARGB_8888` bitmap per
   step: allocated, colour converted, uploaded to the GPU and drawn into a viewport that is at most
   a couple of megapixels. Every step paid for ~4x to ~8x more pixels than the screen could show.

3. **"Playback" was scrubbing.** Play mode incremented the position and re-decoded with
   `OPTION_CLOSEST` for every displayed frame. There is no way `MediaMetadataRetriever` can decode
   30 fps that way; the UI could only ever show a handful of frames per second, and each decode
   blocked the previous one.

4. **Stale work was not cancelled.** Requests were serialised behind one retriever, so a quick drag
   over the timeline queued up decodes for positions the user had already left.

5. **The main thread did the metadata read.** `setDataSource` + six `extractMetadata` calls ran
   inside the file-picker callback, on the UI thread, for a file that can be hundreds of megabytes.

6. **The saved frame was the preview bitmap**, so the "lossless extraction" feature saved whatever
   resolution the preview happened to be.

## What changed

| Area | Before | After |
| --- | --- | --- |
| Still frames | `while(true) + delay(16)` polling | `snapshotFlow { SeekRequest(...) }.distinctUntilChanged().collectLatest { }` - decode only on real change, newest request wins |
| Decode size | source resolution | into the viewer's pixel box (`Timeline.previewDimension`), at source resolution only when saving. A clip that already fits the view still goes through the exact `getFrameAtTime` path; the scaled accessor is used only when the frame is genuinely larger than the box |
| Playback | re-seek per frame | `ExoPlayer` (media3) on a `SurfaceView`, hardware decoded, audio disabled, speed = `displayFps / videoFps` |
| Position updates | one recomposition per decoded frame | player polled every 80 ms while playing |
| Repeated frames | always re-decoded | `FrameCache` (48 MB LRU) keyed by frame index + preview size |
| Metadata | main thread, inside the picker callback | `VideoFrameDecoder.open()` on `Dispatchers.IO` |
| Thread safety | shared retriever, no serialisation | one retriever behind a coroutine `Mutex`; in-flight decodes finish before `close()` releases it |
| Save | reused the preview bitmap, MediaStore entry with no path on API <= 28 | re-decodes at native resolution; MediaStore + `IS_PENDING` on API 29+, public Pictures file + media scan below |
| Overview | - | `VideoInfo` reads resolution, rotation-corrected dimensions, fps and frame count once |

Decoding is also skipped entirely while the player is on screen, so playback and frame extraction no
longer fight over the same hardware decoder.

## How it is verified

* `TimelineTest`, `LruCacheTest` - JVM unit tests for the frame/time arithmetic, the playback speed
  mapping, the preview size bounds and the cache eviction policy.
* `VideoFrameDecoderTest` (instrumented) - against a generated clip whose every frame carries its own
  frame index in a black/white barcode, so a seek can be checked for returning the *right* frame. It
  also asserts that previews are smaller than the source, that full decodes are not, and that a
  preview decode stays inside a latency budget.
* `FrameExtractorScreenTest` (instrumented) - drives the real screen: loading, frame stepping,
  play/pause with the position actually advancing, and the speed controls.

Run everything the way CI does:

```
./gradlew assembleDebug testDebugUnitTest          # build + unit tests
./gradlew connectedDebugAndroidTest                # needs a device or emulator
```

The test clip can be regenerated with `python3 tools/generate_test_video.py` (needs ffmpeg + Pillow).

## Known trade-offs

* Zoom/pan applies to the paused still frame only. It is reset when playback starts, because the
  player renders through a `SurfaceView`, which cannot be transformed as cheaply as a texture.
* Frames are still decoded through `MediaMetadataRetriever`, which seeks exactly
  (`OPTION_CLOSEST`) but is not incremental. A `MediaCodec` + `ImageReader` pipeline that decodes
  forward frame by frame could go faster for single-step scrubbing; it is a much larger change and
  was left out.
* For a source larger than the view, the preview uses the scaled accessor, which some platform
  extractors round onto the neighbouring frame (up to ~33 ms at 30 fps). Saving always re-decodes
  with the exact accessor, so extracted files are unaffected;
  `scaledPreviewStaysWithinOneFrameOfTheExactFrame` bounds the drift.
* Playback speed below ~0.1x is dominated by the player's frame scheduler rather than by decode
  throughput.
