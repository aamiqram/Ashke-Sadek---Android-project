# এশকে ছাদেক — Android songbook

Native, offline-first Android reader for the complete three-volume Bengali songbook.

- Native Android Java UI; no PDF viewer or WebView. The PDF is a reference for source rhythm/alignment, not an app-screen template.
- Minimum Android 5.0 (API 21), target SDK 36. All 615 songs are bundled locally.
- Lyrics and paragraph formatting come from the original Word file; the corrected copy supplies index title/order only. Neither source document is modified.
- Search covers titles, first lines and lyrics; result lists load in batches of 60. Includes volume navigation, direct song-number jump, favourites, bookmarks, recent songs, last-read position, previous/next, and voice/text search.
- Reader uses a centered, capped measure with proportional Word indents and paragraph spacing. It keeps a stable, readable type size; exceptionally long paragraphs can wrap naturally rather than making every line of a song tiny.
- Voice search uses a bundled Bengali streaming ASR model and sherpa-onnx runtime. Audio is captured and recognized locally. The app does not depend on Android's system/vendor SpeechRecognizer or an installed Bengali speech pack, and does not send audio to a cloud service. Microphone permission is requested only when voice search is first used.
- No INTERNET permission is declared in the installed app.

## Offline voice size and compatibility

The bundled model is `sherpa-onnx-streaming-zipformer-bn-vosk-2026-02-09`; runtime version 2.0.1. The upstream model archive is about 87 MB compressed and its extracted directory about 203 MB; the Android runtime AAR is about 49 MB. This substantially increases APK/installed size. That cost is the price of app-owned offline Bengali speech so the user does not need a separate model/language-pack download. The final package size must be measured after CI build.

Audio capture tries 16 kHz, 48 kHz and 44.1 kHz and resamples locally to the model's 16 kHz input. CPU, memory, microphone quality and noise still affect latency and accuracy; physical-device testing is required. [Model card](https://huggingface.co/alphacep/vosk-model-small-streaming-bn) · [sherpa-onnx docs](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-transducer/zipformer-transducer-models.html) · [runtime release](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v2.0.1).

Gradle fetches SHA-256-pinned artifacts during the developer/CI build and packages the speech model as generated app assets. This is not a runtime download. The model metadata declares Apache 2.0; see THIRD_PARTY_NOTICES.md.

## Data integrity

- 615 songs: volume counts 82 + 289 + 244.
- All 6,246 source Word lyric paragraphs are preserved.
- Catalogue asset: about 217 KB compressed / 2.35 MB expanded.
- Original Word SHA-256: `6fd0fdd333c62221b93610debd7272d9a0e39b3a492e22aa946ee4263a52f23e`.
- Corrected index-copy SHA-256: `c349196b014dbedf600ac236c3362d90e215912c1e63c406a03c7430496749be`.
- Duplicate candidates are retained.

## Build

- JDK 21, Android SDK Platform 36, Build Tools 36.0.0.
- Android Gradle Plugin 9.5.0, Gradle 9.6.0.
- `tar` on PATH for unpacking the model archive; Windows 10/11 includes `tar.exe`.
- The initial developer/CI build needs internet access to fetch the pinned runtime/model. The installed app's reader and speech recognition are designed for offline use.

Run `:app:assembleDebug` for an installable debug APK or `:app:assembleRelease` for the optimized unsigned APK. A permanent signing key is required before release distribution. GitHub Actions builds both on push to `main` and manual dispatch; artifacts are uploaded, not published.

## Rebuild catalogue

Install Python 3 and `python-docx`, then run:

```powershell
python extract_catalog.py --original "C:\path\এশকে ছাদেক.docx" --index "C:\path\এশকে ছাদেক_সূচী সংশোধিত কপি.docx"
python tests\validate_catalog.py
```
