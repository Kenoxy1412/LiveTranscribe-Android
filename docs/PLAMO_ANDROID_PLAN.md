# PLaMo 3 Translate Android integration

## Goal

Add an optional on-device translation fallback for Japanese/Chinese to Thai. The existing Cloudflare Worker remains the primary translator. Audio is never sent to the translation service; only recognized text is sent to the Worker. The PLaMo model runs locally after it has been downloaded.

## Model

- Repository: https://huggingface.co/pfnet/plamo-3-translate-0.8b-GGUF
- Model: `plamo-3-translate-0.8b-Q4_K_M.gguf` (about 0.57 GB)
- Runtime: llama.cpp, pinned to v0.9.0 for the first Android integration
- Target ABI: `arm64-v8a`, Android API 28+
- Download the model on demand into app-private storage. Do not package the 570 MB model inside the APK.
- Keep the model and its runtime separate: the app may be installed without the model, and offline fallback is unavailable until the download is complete and verified.

Q4_K_M is the initial default because the model card calls it the default quality/size balance. Q4_0 is listed as a smartphone-oriented option, but the published multilingual benchmark shows a larger quality drop for Q4_0 than Q4_K_M on the Japanese↔multilingual aggregate. Re-evaluate Q4_0 on the actual target handset before switching defaults.

## Prompt format

Use PFML3 directly (do not rely on an embedded legacy chat template):

```text
<|plamo:tag|>dataset<|plamo:msg|>translation<|plamo:tag|>input<|plamo:msg|>{SOURCE_TEXT}<|plamo:tag|>output<|plamo:key|>lang<|plamo:val|>th<|plamo:msg|>
```

The generated output should stop at `<|plamo:tag|>` or `<|plamo:bos|>`. Use a conservative context size on mobile and cap output tokens based on source length. Source language can be omitted for auto-detection; when known, include `<|plamo:key|>lang<|plamo:val|>{SOURCE_CODE}` on the input element.

## Translation routing

1. Try OpenAI Worker first.
2. If Worker returns an empty result or fails, try PLaMo only when the model is installed and loaded.
3. If PLaMo is unavailable or fails, return a failure/empty translation to the existing caller; never silently replace a failed translation with the source text.
4. Serialize local inference requests initially (one model context is shared).
5. Never upload the GGUF model or audio to a remote inference endpoint.

## Model download and integrity

- Download from the official Hugging Face repository using the resolve URL for the pinned file.
- Support HTTP Range resume and write to a temporary file before atomic rename.
- Verify the expected file length and SHA-256 from the Hugging Face LFS metadata before marking the model installed.
- Show download progress, pause/resume, retry, and delete controls.
- Keep at least the required free disk space before download; do not start if storage is insufficient.

## Licensing and attribution

PLaMo 3 Translate is under the PLaMo Community License, not a standard permissive OSS license. Personal/non-commercial use is free without registration. Commercial use requires prior registration when the user/affiliates' latest fiscal-year revenue or sales is at most JPY 1 billion; above that, a separate agreement is required. The license also imposes conditions when redistributing model materials, including passing on the license and showing "Built with PLaMo". Confirm the app publisher's licensing position before shipping a public commercial build. This development branch must not bundle model weights in the APK.

Official license and model card:
- https://huggingface.co/pfnet/plamo-3-translate-0.8b-GGUF
- https://plamo.preferredai.jp/

## Device validation checklist

- Build and load the model on the target ARM64 handset.
- Measure cold model-load time, warm translation latency, peak memory, and thermal behavior.
- Test Japanese→Thai and Chinese Simplified/Traditional→Thai with representative speech fragments.
- Test airplane mode after model download.
- Test Worker failure and fallback behavior.
- Test low-storage, interrupted download, corrupt model, and app process death.
- Do not label offline translation as available until the model is installed and native inference succeeds.
