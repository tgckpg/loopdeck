# LoopDeck 0.1

A small, offline Android music player for the FiiO M11S. Native Java Views,
FFmpeg file parsing and decoding, a native TAK 1.0 decoder, AudioTrack output,
and A–B repeat.

## First version

- Browse a selected folder on internal storage or microSD; permission survives app restarts.
- Play/pause, stop, seek, previous/next, and automatically advance within a folder.
- Set A and B while listening; a valid pair immediately enables repeat.
- Toggle repeat without losing markers. Clear removes both markers.
- Hold **Set A** or **Set B** to enter exact marker times in milliseconds.
- Background playback, media notification, lock-screen controls and standard media keys.
- Pause when headphones are disconnected; handle Android audio focus.
- No full-library scan, artwork extraction, network access, account, or analytics.

The browser shows every file so unusual extensions can still be selected.
Automatic next/previous includes recognized audio filenames and audio MIME types.
Selecting a non-audio file reports an error rather than silently skipping it.
Ordering is by filename, case insensitive; this is a folder player, not a tag library.

## Formats

The pinned FFmpeg build explicitly includes **TTA, TAK, APE, WavPack (.wv),
WMA / WMA Pro / WMA Lossless**, FLAC, ALAC, MP3, AAC, Vorbis, Opus, PCM,
Musepack, Shorten and additional legacy audio decoders. The build script checks
that the specifically requested decoders were enabled. It uses FFmpeg for both
container parsing and audio decoding, not Android's codec list.

TAK codec type 0 (TAK 1.0) uses LoopDeck's native C++ decoder. Codec types 2 and
4 use FFmpeg. Type 0 does not need conversion, a Windows DLL, or emulation on
the device. The format line shows **TAK 1.0 (native)** when this path is active.
Mono/stereo at 8, 16 and 24 bits have been compared against the original decoder;
see `TAK1.md` for scope and reference tests. Experimental TAK types 1 and 3 are
not supported.

Format support does not imply that damaged, encrypted, DRM-protected or every
historical variant of a file will play. Failure details appear below the controls.
Separate CUE-sheet navigation, playlists, metadata editing and saved per-track
loop markers are not implemented in this first version. Whole-album audio files
can still be played and looped directly.

## CLI prerequisites (macOS)

Use OpenJDK 27 installation with the Android command-line SDK. The app
still compiles to Java 17 bytecode for Android 10; JDK 27 runs the build tools.
Install the SDK command-line tools, NDK and CMake if needed:

```bash
brew install --cask android-commandlinetools

export JAVA_HOME="$(/usr/libexec/java_home -v 27)"
export PATH="$JAVA_HOME/bin:$PATH"
java -version
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

sdkmanager --sdk_root="$ANDROID_HOME" --licenses
sdkmanager --sdk_root="$ANDROID_HOME" \
  "platform-tools" \
  "platforms;android-35" \
  "build-tools;36.0.0" \
  "ndk;27.2.12479018" \
  "cmake;3.22.1"

export PATH="$ANDROID_HOME/platform-tools:$PATH"
```

If `/usr/libexec/java_home` cannot find your JDK, set `JAVA_HOME` to its
installation directory directly. Check that `java -version` prints version 27
before running `./build.sh`.

On Linux use JDK 27, make, curl, tar/xz and the Android command-line tools;
install the same SDK packages. FFmpeg's assembler comes from the NDK; no separate
NASM is needed for ARM64. On Apple Silicon the NDK r27 host toolchain may require
Rosetta (`softwareupdate --install-rosetta`) if it isn't already available.

## Build and install

```bash
chmod +x build.sh gradlew scripts/build-ffmpeg.sh
./build.sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The first build downloads checksum-pinned FFmpeg 7.1.5, compiles its ARM64 shared
libraries, downloads the pinned Gradle distribution and resolves the Android
Gradle plugin. Later builds reuse the FFmpeg output. `JOBS=4 ./build.sh` can limit
native build parallelism. To clean the app, run `./gradlew clean`; to force a
FFmpeg rebuild remove `third_party/ffmpeg/arm64-v8a/.build-config`.

`./build.sh assembleDebug` is equivalent to the default. Run the FFmpeg build
script first if invoking Gradle directly. The app has no Java library dependencies.

If updating from a source copy that failed with `manifest 'build.ninja' still
dirty after 100 tries`, remove its generated native build directory once, then
retry. This keeps the already compiled FFmpeg libraries:

```bash
rm -rf app/.cxx
./build.sh
```

Gradle runs CMake configuration when needed. This project disables Ninja's own
CMake regeneration rule to avoid a timestamp loop on some mounted filesystems.
When changing `CMakeLists.txt`, build via Gradle so configuration is refreshed.

This version builds **arm64-v8a only** and requires Android 8/API 26 or newer;
it targets Android 10/API 29 for private sideloading on the M11S. It is not
configured for Play Store publication. The build runs on OpenJDK 27 using
AGP 9.4.0 and Gradle 9.8.1. Java source and target compatibility remain at 17
for Android 10. The wrapper JAR is included.

## Using A–B repeat

1. Choose **Music folder**, grant access, then select a track.
2. Tap **Set A** at the start of the passage.
3. Tap **Set B** after A. Playback returns to A and repeat starts.
4. Hold either marker button for numerical adjustment. **Loop on/off** retains
   the pair; **Clear** removes it. Seeking outside the loop turns repeat off.

Short loops cache decoded stereo float PCM during their first pass, up to
32 MiB. Once cached, repeats do not seek or decode again. The limit is roughly
95 seconds at 44.1 kHz, 87 seconds at 48 kHz, or 22 seconds at 192 kHz.
Longer loops use bounded-memory seek/decode repeat and can have a gap at B.
The status line identifies buffering, cached repeat, and seek mode. Seek and
first-pass behavior depend on the file's index, decoder and storage speed.
A cut at a nonzero waveform value can produce a click; no crossfade is applied.

## Audio output

FFmpeg converts decoded audio to stereo 32-bit float PCM, preserving the source
sample rate when Android accepts it. Mono is converted to stereo and multichannel
audio is downmixed. If AudioTrack rejects the source rate, the app tries 48 kHz.
The screen shows the source description and **requested** output rate.

This is not a claim of verified bit-perfect output, native DSD, MQA unfolding or
the physical DAC's actual rate. DSD files, when decoded, take the PCM path. FiiO's
firmware and audio settings determine the final hardware path. Check those on the
actual M11S. Bluetooth encoding is managed by Android.

For device problems:

```bash
adb logcat -c
adb logcat AndroidRuntime:E AudioTrack:W '*:S'
```

Copy the on-screen failure message and report the extension, codec if known,
source sample rate, and whether normal playback or seeking failed. Avoid clearing
app data unless you intend to re-grant folder permission.

## Source layout

- `MainActivity.java`: folder listing and native controls; file queries use a worker.
- `PlaybackService.java`: single playback worker, AudioTrack, loop cache and media session.
- `decoder.cpp`: FFmpeg custom file-descriptor IO, decoding, resampling and seeking.
- `tak1.cpp`: native TAK 1.0 decoding, selected automatically for codec type 0.
- `jni.cpp`: small Java/native bridge.
- `scripts/build-ffmpeg.sh`: pinned FFmpeg download, build, cache and decoder checks.

App source is MIT licensed. FFmpeg and the Gradle wrapper have their own licenses;
see `THIRD_PARTY_NOTICES.md` before redistributing binaries.
