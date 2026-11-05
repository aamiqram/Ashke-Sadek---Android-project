# এশকে ছাদেক — Android songbook

A small, offline-first native Android reader for the three-volume Bengali songbook.

## Product decisions

- Native Android Java UI; no Compose, WebView, PDF engine, database framework, analytics SDK, advertising SDK, or third-party runtime dependency.
- Minimum Android version: Android 5.0 (API 21). Target SDK: 36.
- 615 songs are bundled locally and available without internet.
- Lyrics and paragraph formatting are extracted from the original Word document. The corrected Word copy is used only for index titles/order; the original source file is never modified. Leading right-aligned rhythm/style labels are kept as song metadata rather than mixed into the lyric body.
- The 5.5 MB interactive PDF is deliberately not packaged. The reader displays the original Word song text as clean, selectable Bengali text.
- Bengali search covers titles, first lines, and full lyrics. Results are added in batches of 60 to avoid loading hundreds of rows at once on older phones.
- Word paragraph alignment, indentation, and paragraph spacing are carried into the reader. Includes volume navigation, direct song-number jumps, favourites, bookmarks, recent songs, last-read song, last scroll position, previous/next navigation, and floating search / scroll-to-top controls.
- Voice search is optional and asks for microphone permission only when used. It uses Android's on-device recognizer when one is available. Voice commands include song number / volume, next song, previous song, contents, and scroll to top. When on-device recognition or Bengali support is unavailable, the app opens ordinary text search. It does not request network access or send audio to a cloud service.

## Storage budget

The app has no media downloads, thumbnails, web cache, streaming, analytics, or remote content. The complete catalogue is a single bundled JSON asset (currently about 2.35 MB uncompressed before APK compression). User data is limited to song IDs, recent IDs (maximum 12), and per-song scroll offsets. The app-managed temporary cache is disposable and capped at 1 MiB; it is cleared if Android signals memory pressure. There is no growing cache of lyrics or pages.

The intended storage envelope is below the 10–20 MB target. The bundled catalogue itself is only about 217 KB on disk (2.35 MB when expanded in memory). Saved preferences are bounded by the 615-song catalogue, and there is no per-use media or page cache. A final measured APK / installed footprint still needs to be recorded from a real Android SDK release build and device install; this workspace has no Android SDK or Gradle installation, so it could not produce or measure an APK here. Do not treat the target as a measured APK size until the build workflow has completed and the APK has been installed and checked on a device.

## Build requirements

- JDK 17 or newer (JDK 17 is the Android Gradle Plugin's supported baseline).
- Android SDK Platform 36 and Build Tools 36.0.0.
- Android Gradle Plugin 9.5.0 and Gradle 9.6.0.

Open this folder in Android Studio and let it sync the project, then run `:app:assembleDebug` to create an installable, debug-signed APK at `app/build/outputs/apk/debug/app-debug.apk`. Run `:app:assembleRelease` to create the optimized, minified release APK at `app/build/outputs/apk/release/app-release-unsigned.apk`. The release APK is unsigned until a permanent app-signing key is configured; do not distribute an unsigned build as the finished public release.

## Automated APK build

The workflow in `.github/workflows/build-apk.yml` builds both APK variants when manually run or pushed to the `main` branch. It uploads the debug-signed APK (installable for device testing) and the optimized unsigned release APK. It does not publish or deploy anything. A permanent signing key is required before distributing the optimized release build to end users. The source has passed catalogue validation and Java syntax parsing in this workspace, but a full Android compile and on-device QA remain outstanding because the Android SDK and Gradle are not installed here.

## Source integrity

- Original Word file SHA-256: `6fd0fdd333c62221b93610debd7272d9a0e39b3a492e22aa946ee4263a52f23e`
- Corrected index-copy SHA-256: `c349196b014dbedf600ac236c3362d90e215912c1e63c406a03c7430496749be`
- Catalogue counts: volume 1 = 82, volume 2 = 289, volume 3 = 244; total = 615.
- Duplicate candidates are retained. Nothing is removed merely because lyrics are similar.

## Rebuild the bundled catalogue (maintainers only)

Install Python 3 and `python-docx`, then run:

```powershell
python extract_catalog.py --original "C:\path\এশকে ছাদেক.docx" --index "C:\path\এশকে ছাদেক_সূচী সংশোধিত কপি.docx"
python tests\validate_catalog.py
```

The extractor reads the original and corrected files without modifying either. The original supplies the lyric text; the corrected copy supplies only index names and ordering.

## Compatibility and voice-search limits

- The app's reading and text search work locally on Android 5.0 (API 21) and newer.
- Android's on-device SpeechRecognizer interface is available from API 31. The app only attempts on-device recognition; actual Bengali language availability depends on the phone's installed recognition service. It falls back to text search and does not request network access.
- The build uses Android Gradle Plugin 9.5.0, Gradle 9.6.0, JDK 17, SDK Platform 36, and Build Tools 36.0.0.

## Current verification status

- Passed: 615 unique songs, volume sequence continuity, non-empty lyrics, Word paragraph formatting, XML validation, no INTERNET permission, and original/corrected Word source hashes.
- Passed: Java source syntax parsing.
- Not yet performed: Gradle/Android compilation, APK size measurement, emulator/device installation, voice-recognition testing across manufacturers, and release signing.
