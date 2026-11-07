# এশকে ছাদেক — Android songbook

An offline-first Bengali songbook reader for the complete three-volume collection. Read songs, search by title or lyric, and use on-device Bengali speech recognition to find likely matches.

[![Build Android APK](https://github.com/aamiqram/Ashke-Sadek---Android-project/actions/workflows/build-apk.yml/badge.svg?branch=main)](https://github.com/aamiqram/Ashke-Sadek---Android-project/actions/workflows/build-apk.yml)

**615 songs · 3 volumes · Android 5.0+ · Bengali interface**

Minimum Android version is 5.0 (API 21); the app targets API 36 and is packaged for ARM phones. x86 emulator libraries are excluded.

## Contents

- [Features](#features)
- [Voice search and its limits](#voice-search-and-its-limits)
- [Privacy and network use](#privacy-and-network-use)
- [Catalogue and reading layout](#catalogue-and-reading-layout)
- [Performance and memory](#performance-and-memory)
- [Build and QA](#build-and-qa)
- [Updates and release process](#updates-and-release-process)
- [Rebuild the catalogue](#rebuild-the-catalogue)
- [Third-party notices](#third-party-notices)

## Features

| Area | What readers can do |
| --- | --- |
| Browse | Open the first, second or third volume; browse the song index; jump to a song by number; move to the previous or next song. |
| Search | Get live typed results from song titles, first lines and lyrics. Search understands older/newer Bengali spellings, phonetic forms, partial words and limited typing errors. Long result lists load in batches. |
| Voice | Tap the microphone once. The app begins listening automatically when its offline model is ready and shows likely songs while recognition continues. Suggestions include the volume, song number, title and a lyric excerpt. |
| Read | Read the original Bengali text with paragraph alignment, indentation, emphasis and spacing preserved. Text wraps to the phone width, and the reader has small/big text controls. |
| Find another song | Open search from inside a song, without returning to the home screen. |
| Personal library | Save favourites and bookmarks, see recent songs, and return to the last song and its saved scroll position. |
| Backup and restore | Export or restore favourites, bookmarks, recent songs, last-read song and per-song reading positions as JSON. |
| Updates | Check for a release manually or through the daily update check. Downloaded APKs are verified before Android's installer opens. |
| Feedback | Write a problem report or suggestion and save it on the phone. Reports can be reviewed, deleted or shared manually. |

The home screen shows the three volumes and quick access to favourites, bookmarks, recent reading and updates. The catalogue, typed search and voice recognition work without an internet connection.

## Voice search and its limits

Voice search uses a bundled Bengali streaming speech-recognition model and the sherpa-onnx Android runtime. Recognition and matching happen on the device. Live suggestions require multiple words or a multiword lyric phrase, and matching checks lyric word order to reduce results from ordinary conversation. A candidate stays visible while recognition continues, so a later transcript fragment does not immediately erase it.

The speech model is loaded for each voice-search session and released afterward to keep its native memory out of the reader process while the user reads. Model startup has taken about 15–20 seconds on some tested phones; latency varies with the device and memory conditions.

The int8 model package contains an encoder of about 26.2 MB, a 2.1 MB decoder, a 0.26 MB joiner and roughly 6 KB of tokens. Its published FLEURS Bengali word-error rate is not a measure of accuracy on noisy singing or song identification.

**Voice search is speech recognition, not music identification.** It can match a Bengali lyric someone says or sings into the microphone. It does not identify a song from audio playing nearby, and it does not save or upload microphone recordings.

## Privacy and network use

- Song text, typed queries and speech recognition stay on the phone. There is no analytics or advertising service.
- The microphone is used only after the reader taps voice search and grants Android's permission.
- Feedback reports are stored locally, up to 20 at a time. There is no receiving server and no automatic upload. App/device details and recent performance or crash diagnostics are optional; microphone audio, personal files and reading history are not attached. A report leaves the phone only if the reader chooses an app from Android's share sheet.
- A successful automatic update check is remembered for 24 hours. A failed check does not count as success; the app stays usable, offers a manual retry and tries again on a later launch.
- Internet access is used for GitHub release checks and APK downloads. If a check fails because the phone is offline or cannot reach GitHub, the app stays usable.

## Catalogue and reading layout

The bundled catalogue contains **615 songs**: 82 in the first volume, 289 in the second and 244 in the third. It preserves all 6,246 source Word lyric paragraphs. Lyrics and paragraph formatting are read from the original Word document; the corrected copy supplies song titles and index order. The catalogue-generation script reads both documents and never edits either one. Duplicate candidates remain available in the catalogue data.

The reader maps Word alignment, indents and paragraph spacing to the available screen width. The source document mostly uses the SutonnyOMJ font, which is not bundled in the app; Android uses its Bengali system font instead. As a result, line breaks can differ from Word or PDF even when paragraph formatting is preserved. Readers can adjust text size inside the app.

Integrity reference values:

- Original Word file SHA-256: `6fd0fdd333c62221b93610debd7272d9a0e39b3a492e22aa946ee4263a52f23e`
- Corrected index copy SHA-256: `c349196b014dbedf600ac236c3362d90e215912c1e63c406a03c7430496749be`
- Compressed catalogue: about 217 KB; expanded catalogue: about 2.35 MB

## Performance and memory

The catalogue and search index are prepared once during startup. Search updates reuse that index rather than rebuilding normalized lyrics on every keystroke. To keep repeated matching fast, the app retains derived normalized, spelling-variant, phonetic and word-set search forms in addition to the single canonical lyric text for each song.

Voice recognition runs in a private `:voice` process with one inference thread. It is started only for a voice-search session and stopped afterward. Android can still terminate that process under memory pressure; the reader remains separate and reports an interrupted voice session.

The app writes local performance and memory events to the `AshkeMemory` log tag. On a connected test phone, inspect memory with:

```sh
adb shell dumpsys meminfo org.ashkeshadek.reader
adb logcat -s AshkeMemory
```

Check idle home, an open reader, search, voice-model startup, active recognition and post-voice idle. CI builds cannot certify memory use or lifecycle behavior on every device or vendor version of Android.

## Build and QA

### Requirements

- JDK 21
- Gradle 9.6.0 (the repository does not include a Gradle wrapper)
- Android SDK Platform 36 and Build Tools 36.0.0
- Internet access for the initial build to fetch the pinned speech runtime and model

Set `ANDROID_HOME` or configure `local.properties` with `sdk.dir`, then run from the repository root:

```sh
gradle --no-daemon :app:assembleDebug
```

The installable debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. To assemble the optimized release variant:

```sh
gradle --no-daemon :app:assembleRelease
```

Without the permanent release signing key, the release APK is unsigned and is for QA only. The build fetches pinned dependencies and checks the runtime and ONNX model-weight SHA-256 values before packaging them as app assets; model files are not downloaded by the installed app.

GitHub Actions runs catalogue validation and assembles debug and release APK variants for pull requests, pushes to `main` and manual dispatches. The installable debug artifact is named `ashke-shadek-installable-debug-apk` and is retained for 14 days.

## Updates and release process

After a successful result, the updater waits 24 hours before its next automatic check; a manual check is also available on the home screen. A failed network check is not cached as success. The updater only accepts APK information from the repository's `update.json` manifest. Before installation, the app verifies the APK checksum, package name, version code and signing certificate. Android displays the final installation confirmation; the app cannot install silently.

Normal pushes to `main` build QA artifacts but do not publish a release. Publishing requires a successful manual dispatch of the build workflow on `main`, the variable `ASHKE_STABLE_RELEASE_READY=true`, and the permanent signing secrets below.

Every release must use the same permanent signing key and a higher version code. Generate and back up the key securely; never commit it or share its passwords:

```powershell
keytool -genkeypair -v -keystore ashke-shadek-release.jks -alias ashke-shadek -keyalg RSA -keysize 4096 -validity 10000
[Convert]::ToBase64String([IO.File]::ReadAllBytes(".\ashke-shadek-release.jks")) | Set-Clipboard
```

In **GitHub → Settings → Secrets and variables → Actions**, configure:

| Name | Purpose |
| --- | --- |
| `ASHKE_KEYSTORE_BASE64` | Base64-encoded keystore |
| `ASHKE_KEYSTORE_PASSWORD` | Keystore password |
| `ASHKE_KEY_ALIAS` | Signing key alias |
| `ASHKE_KEY_PASSWORD` | Key password |
| `ASHKE_STABLE_RELEASE_READY` | Actions variable; set to `true` only after validating the permanent key and signed update path |

Use the in-app JSON backup before changing signing keys or moving between installations that cannot update in place. A same-certificate update preserves app data.

## Rebuild the catalogue

Install Python 3 and `python-docx`, then run the extractor with the original Word file and corrected index copy. The source documents are read only; the generated catalogue and emphasis assets are written into the repository.

```powershell
python -m pip install python-docx
python extract_catalog.py --original "C:\path\এশকে ছাদেক.docx" --index "C:\path\এশকে ছাদেক (ডুপ্লিকেট বাদ).docx"
python tests\validate_catalog.py
```

## Third-party notices

The offline speech runtime and Bengali model have separate third-party terms. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for attribution and licensing details. The runtime is sherpa-onnx 2.0.1; the bundled model is an int8-quantized Bengali streaming Zipformer2 derivative. Its published FLEURS Bengali word-error rate is not a measure of accuracy on noisy singing or song identification. See the [model card](https://huggingface.co/alphacep/vosk-model-small-streaming-bn), [sherpa-onnx documentation](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-transducer/zipformer-transducer-models.html) and [runtime release](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v2.0.1).
