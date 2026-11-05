# এশকে ছাদেক — Android songbook

Native, offline-first Android reader for the complete three-volume Bengali songbook.

- Native Android Java UI; no PDF viewer or WebView. The PDF is a reference for source rhythm/alignment, not an app-screen template.
- Minimum Android 5.0 (API 21), target SDK 36. All 615 songs are bundled locally.
- Lyrics and paragraph formatting come from the original Word file; the corrected copy supplies index title/order only. Neither source document is modified.
- Search covers titles, first lines and lyrics; result lists load in batches of 60. Includes volume navigation, direct song-number jump, favourites, bookmarks, recent songs, last-read position, previous/next, and voice/text search.
- Reader uses a centered, capped measure with proportional Word indents and paragraph spacing. It keeps a stable, readable type size; exceptionally long paragraphs can wrap naturally rather than making every line of a song tiny.
- Voice search uses a bundled int8 Bengali streaming ASR model and sherpa-onnx runtime. Audio is captured and recognized locally; it is never uploaded. The recognizer runs in a private, short-lived Android process separate from the reader UI.
- The recognizer is loaded only for a voice-search session, uses one inference thread, is explicitly released, and its worker process exits after the session. The idle reader never retains the speech model in its process.
- The catalogue keeps each song's lyric text once. Paragraphs store source offsets and formatting instead of a second copy of every paragraph string. Normalized and phonetic search forms are generated transiently for each song during a query rather than retained for all songs.
- Internet access is used only for release update checks/downloads; speech recognition and song reading remain offline. APK installation still requires Android's system confirmation.

## Offline voice size and compatibility

The bundled model is the int8-quantized `bbbipul/vosk-small-streaming-bn-int8` derivative of the Alpha Cephei Bengali Vosk streaming Zipformer2 model; runtime version 2.0.1. The encoder is 26.2 MB, the decoder 2.1 MB, the joiner 0.26 MB and tokens 6 KB. Its model card reports FLEURS Bengali WER 27.06% versus 26.97% for the full-precision model. This benchmark is not a measure of noisy live singing or song-title accuracy. The APK is built for Android ARM phones; x86 emulator libraries are excluded.

## Memory policy and measurement

The ASR process is started only when voice search is explicitly opened. It is isolated as `:voice`, limited to one inference thread, and terminated after its session so the native model's working set does not remain resident in the reader process. If Android kills this worker under memory pressure, the reader remains separate and the UI reports the interrupted session. No service is kept running while the user reads.

The app logs main-process and voice-process PSS, Java heap and native heap measurements with the `AshkeMemory` log tag. Measure on physical devices with `adb shell dumpsys meminfo org.ashkeshadek.reader` and `adb logcat -s AshkeMemory`; record idle home, open reader, active ASR peak, and post-ASR idle. A build passing CI does not prove RAM performance on every device. The practical goal is a small idle reader footprint and a voice-only temporary peak, including tests on 2–3 GB devices with aggressive vendor process management.

Audio capture tries 16 kHz, 48 kHz and 44.1 kHz and resamples locally to the model's 16 kHz input. CPU, memory, microphone quality and noise still affect latency and accuracy; physical-device testing is required. [Model card](https://huggingface.co/alphacep/vosk-model-small-streaming-bn) · [sherpa-onnx docs](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-transducer/zipformer-transducer-models.html) · [runtime release](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v2.0.1).

Gradle fetches SHA-256-pinned artifacts during the developer/CI build and packages the speech model as generated app assets. This is not a runtime download. The model metadata declares Apache 2.0; see THIRD_PARTY_NOTICES.md.

## Data integrity

- 615 songs: volume counts 82 + 289 + 244.
- All 6,246 source Word lyric paragraphs are preserved.
- Catalogue asset: about 217 KB compressed / 2.35 MB expanded.
- Original Word SHA-256: `6fd0fdd333c62221b93610debd7272d9a0e39b3a492e22aa946ee4263a52f23e`.
- Corrected index-copy SHA-256: `c349196b014dbedf600ac236c3362d90e215912c1e63c406a03c7430496749be`.
- Duplicate candidates are retained.

## Memory-conscious runtime

The reader keeps a single canonical copy of each song's lyric text. Paragraphs use offsets into that string; search does not retain normalized or phonetic duplicates for all 615 songs. Voice recognition runs in a separate `:voice` process, starts only for an active voice session, uses one CPU inference thread, releases the model, and exits after the session. Main and voice PSS/native-heap telemetry is written to logcat under `AshkeMemory`.

A practical memory test should record idle home, reading a long song, search while typing, voice-model load, active capture, and post-voice idle on physical 2 GB and 3 GB devices. Use `adb shell dumpsys meminfo org.ashkeshadek.reader` and `adb logcat -s AshkeMemory`. CI can verify the architecture and compile, but it cannot certify memory usage on every OEM ROM.

## Release publishing safety

The app checks the public GitHub releases feed at most once per day and has a manual update entry on the home screen. It downloads only APKs described by the repository's `update.json` manifest, verifies SHA-256, package ID, version code and signing certificate, then uses Android's Package Installer. Android displays the final install confirmation; the app cannot silently install an APK.

Every published release must use the same permanent signing key and a higher version code. CI release publishing is gated by `ASHKE_STABLE_RELEASE_READY=true` and requires four repository Actions secrets. The earlier beta used regenerated debug signing, so the first permanent-key update needs a one-time manual migration. This version includes a JSON backup/restore tool for favourites, bookmarks, recent songs, last-read song and per-song scroll positions; use it before uninstalling a build that already includes this tool. The older beta distributed before this backup UI was added has no in-app export screen, so its saved state cannot be exported from that older app using this feature. After migration, same-key releases update in place and preserve local data.

Generate and back up the permanent signing key locally (never commit it):

```powershell
keytool -genkeypair -v -keystore ashke-shadek-release.jks -alias ashke-shadek -keyalg RSA -keysize 4096 -validity 10000
[Convert]::ToBase64String([IO.File]::ReadAllBytes(".\\ashke-shadek-release.jks")) | Set-Clipboard
```

In GitHub repository **Settings → Secrets and variables → Actions**, add:
- `ASHKE_KEYSTORE_BASE64` — copied Base64 keystore content
- `ASHKE_KEYSTORE_PASSWORD` — keystore password
- `ASHKE_KEY_ALIAS` — `ashke-shadek`
- `ASHKE_KEY_PASSWORD` — key password

After checking that a signed test APK uses the same certificate for all future builds, set repository Actions variable `ASHKE_STABLE_RELEASE_READY` to `true`. Keep the keystore backup offline and never share its passwords.

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
