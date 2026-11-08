# Offline Whisper speech recognition

The system-audio caption pipeline can use multilingual Whisper locally through the existing sherpa-onnx Android runtime.

## Runtime flow

1. Android `MediaProjection` + `AudioPlaybackCapture` captures internal media audio at 16 kHz, mono PCM.
2. The app buffers 8-second windows and sends them to the local sherpa-onnx Whisper recognizer.
3. Whisper transcribes the selected source language on-device.
4. The existing translation pipeline translates the transcript and renders the caption overlay.

No Whisper API key or remote ASR server is used by this backend.

## First-run model download

The app downloads the multilingual Whisper base int8 ONNX model into private app storage:

- `base-encoder.int8.onnx`
- `base-decoder.int8.onnx`
- `base-tokens.txt`

The download is approximately 160 MB. It is downloaded once; after installation, speech recognition works offline. The model files are hosted by the sherpa-onnx project on Hugging Face.

## Languages

The current app configuration supports Japanese (`ja`), Chinese (`zh`), Korean (`ko`) and English (`en`). Japanese and Chinese are the intended default use cases.

## Device notes

- Requires Android 10 or newer and permission to capture system audio.
- The source app must allow Android playback capture; some protected apps may block it.
- Whisper is a non-streaming recognizer in this integration. Captions are emitted after each audio window, so latency is higher than a streaming recognizer.
- First-run model download requires internet access and sufficient free storage. Inference itself is on-device.
- Offline transcription and offline translation are separate model workloads. PLaMo must also be installed for fully offline Japanese/Chinese-to-Thai captions.
