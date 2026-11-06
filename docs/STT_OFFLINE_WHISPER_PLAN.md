# LiveCaptionN STT Upgrade Plan (Offline-first)

## User requirements

- Primary use: Japanese audio from MX Player, translated to Thai.
- Test playback at 1x, 2x, and 3x.
- Keep recurring cost at zero: no paid STT API calls in the default path.
- Keep Vosk as an automatic offline fallback if the new engine cannot initialize or fails.
- Preserve the existing translation pipeline and overlay unless a separate change is needed.

## Recommended architecture

1. Keep Android `AudioPlaybackCapture` as the audio input path. First add capture diagnostics (PCM byte count, average/RMS level, silence ratio, read errors, capture source) so "no audio captured" can be distinguished from "model could not recognize speech".
2. Add an offline multilingual Whisper engine using `whisper.cpp` Android JNI/native integration. Use a downloadable quantized multilingual model rather than bundling a large model in the APK.
3. Keep `StreamingSttEngine` / Vosk available as the fallback. The selector should support:
   - Offline Whisper (preferred)
   - Offline Vosk (fallback / manual selection)
4. Do not call OpenAI hosted transcription APIs in this mode. Whisper model weights run locally on the phone; downloading the model requires internet once, but inference does not.
5. Continue sending recognized Japanese text to the existing translation backend. Translation may still require internet depending on the user's selected translator; STT itself will be free/offline.

## Model rollout

- Start with a multilingual Whisper **base** quantized model as the initial candidate. It is a compromise for mobile memory and speed; compare it against tiny and small using the same Japanese test clips.
- Keep model files outside the APK in app-private storage and provide download progress, size, checksum, delete, and installed-state UI.
- Do not promise 3x real-time operation until measured on the target phone. At 3x playback, a 5-second captured window contains about 15 seconds of source speech, so the recognizer must process faster than the incoming audio rate to avoid a growing queue.
- If Whisper cannot keep up, use shorter VAD-based segments with a small overlap, deduplicate text at segment boundaries, and expose a "fast / balanced" model setting. Avoid blindly reducing chunk size: Whisper is not inherently streaming and very short chunks can harm context and recognition accuracy.

## Automatic Vosk fallback policy

- Fall back to Vosk if the Whisper model is absent, corrupt, unsupported, or fails to load.
- If native inference repeatedly errors or the processing backlog exceeds a defined limit, stop Whisper cleanly and restart Vosk using the same audio source.
- Do not switch to Vosk merely because the PCM level is zero: that is an audio-capture/source issue, not a model issue. Show a clear "no system audio captured" diagnostic instead.
- Tell the user which engine is active and why fallback occurred.

## Implementation stages

### Stage 0 — Baseline and diagnostics
- Add structured audio capture telemetry and a diagnostic status in the overlay/settings.
- Verify MX Player capture at 1x, 2x, and 3x before changing the recognizer.
- Record CPU, memory, dropped chunks, processing time, and end-to-end caption delay.

### Stage 1 — Native Whisper prototype
- Integrate and pin a known `whisper.cpp` revision and Android NDK build.
- Add a JNI wrapper that accepts 16 kHz mono PCM and returns Japanese text with segment timestamps.
- Test model load/unload, cancellation, and background-thread execution.
- Keep the current Vosk path unchanged while the prototype is tested.

### Stage 2 — Model manager and engine routing
- Add a model download/install manager for the selected quantized multilingual model.
- Add a new STT backend option and persist the selected model.
- Implement automatic Vosk fallback for initialization/runtime failures and expose fallback reason.

### Stage 3 — Audio segmentation and performance
- Add voice activity detection / silence-aware segmentation, a short overlap, and duplicate suppression.
- Use a bounded queue; drop or merge stale partial work rather than allowing unbounded latency.
- Tune chunk length and thread count from measurements, not assumptions.

### Stage 4 — Acceptance tests
- Same Japanese clips at 1x, 2x, and 3x in MX Player.
- Include dialogue, music/background noise, silence, and fast speech.
- Measure Japanese transcription error, missing segments, caption latency, CPU, peak RAM, battery drain, and thermal throttling.
- Acceptance: no silent capture at any speed when MX Player actually exposes capturable audio; no unbounded queue growth; automatic Vosk fallback works; 1x quality must not regress.

## Important technical boundary

Changing the STT model cannot fix Android playback capture if MX Player does not expose audio to `AudioPlaybackCapture`. Stage 0 is therefore mandatory. Whisper is offline and has no per-minute API charge, but its runtime cost is CPU/RAM/battery and its model download consumes storage/data.

## References

- whisper.cpp Android example: https://github.com/ggml-org/whisper.cpp/tree/master/examples/whisper.android
- whisper.cpp project and quantization: https://github.com/ggml-org/whisper.cpp
- Android playback capture API: https://developer.android.com/media/platform/av-capture
