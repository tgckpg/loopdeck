# LoopDeck 0.1

A small, offline Android music player and Bluetooth remote for the FiiO M11S. Native Java Views,
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
- Optional Bluetooth server/client control; no full-library scan, artwork extraction, Internet access, account, or analytics.

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

- `MainActivity.java`: native controls, Android permission/picker dialogs and rendering.
- `PlayerController.java`: local/Bluetooth routing, common browser rows/capabilities,
  folder workers and playback commands shared by the UI and media controls.
- `PlaybackService.java`: single playback worker, AudioTrack, loop cache and media session.
- `decoder.cpp`: FFmpeg custom file-descriptor IO, decoding, resampling and seeking.
- `tak1.cpp`: native TAK 1.0 decoding, selected automatically for codec type 0.
- `jni.cpp`: small Java/native bridge.
- `scripts/build-ffmpeg.sh`: pinned FFmpeg download, build, cache and decoder checks.

App source is MIT licensed. FFmpeg and the Gradle wrapper have their own licenses;
see `THIRD_PARTY_NOTICES.md` before redistributing binaries.

## Album images and track views

Tap an album image (TAK, TTA, FLAC, APE, etc.) to show its numbered track list
when track information is available. LoopDeck checks same-folder external CUE
sheets first (preferring the image's basename), then an embedded `CUESHEET` tag,
then container chapters exposed by FFmpeg. You can also tap a `.cue` directly,
including sheets referencing several audio files in the same folder.

Choose a track to play it. Its SeekBar, elapsed time, duration, media-session
position and A–B markers are relative to that track, starting at zero. Playback
ends at its boundary and advances to the next listed track. Previous/Next use
the track list. Use the up arrow or Back to return to the folder. Images without
usable track information continue to play as one file.

External CUE `FILE` names must match audio filenames in the same folder; relative
subdirectories and absolute paths are not resolved. UTF-8 and BOM-marked UTF-16
are supported, with Windows-31J fallback for older Japanese sheets. `INDEX 01`
marks each start; `INDEX 00` pregaps stay with the preceding track. Track positions
use millisecond precision. Tracks reopen/seek the image when advancing; seamless
transitions between tracks are not guaranteed. Embedded metadata support depends
on what the format's FFmpeg demuxer exposes.

## Continuous albums / next-track buffering

LoopDeck prepares about one second of the next queued track on a separate worker.
For consecutive tracks with the same source sample rate, the prepared PCM is fed
straight into the existing AudioTrack after the current track's last samples.
The output is kept running, with no crossfade or added silence. Track titles and
the playback position switch when the audio playback head reaches the boundary.
This also applies to consecutive CUE tracks within an album image.

Only the next track is prepared. Each stereo-float prefix uses about 345 KiB at
44.1 kHz or 1.47 MiB at 192 kHz; a playing prefix and the next prefix can coexist.
Seeking, choosing another track, stopping and activating an A–B loop invalidate
pending handoffs. A sample-rate change or unavailable preload uses the normal
track-loading path. Extremely short tracks, slow storage or decoding that cannot
keep up can still underrun. Encoded silence in the files is preserved; codec
padding handling depends on FFmpeg and the files' metadata.

Host tests (JDK 17+):

```sh
python3 scripts/test-playback-handoff.py
java -m jdk.compiler/com.sun.tools.javac.Main -d /tmp/loopdeck-tests \
  app/src/main/java/net/astropenguin/loopdeck/PcmPrebuffer.java \
  app/src/main/java/net/astropenguin/loopdeck/PrefetchSlot.java \
  scripts/GaplessTest.java
java -cp /tmp/loopdeck-tests net.astropenguin.loopdeck.GaplessTest
```

## Bluetooth server / client

Install the same APK on the player and phone. Music stays on the player: Bluetooth
carries commands, folder names and playback state, with no audio streaming or file
transfer. The player still decodes locally, requests the file's sample rate and
runs the A–B loop itself.

1. Pair the devices in **Android Settings → Bluetooth**. Finish pairing there,
   then leave the Settings scan screen before connecting in LoopDeck.
2. On the FiiO, choose **Music folder**, then **Bluetooth → Server**.
3. On the Pixel, choose **Bluetooth → Client**, grant **Nearby devices** if
   prompted, and choose the paired FiiO. The phone needs no music-folder grant.
4. Browse the remote folders and album/CUE track lists using the normal list and
   up arrow. **Remote root** returns to the shared music folder. Folders are
   transferred in pages of 50; **Find / refresh** filters names in the current
   folder or reloads the root. A blank filter shows everything in the folder.
5. Play/pause, stop, previous/next, seek and all A–B controls operate on the FiiO,
   including holding Set A/B for exact milliseconds. Marker taps use the player's
   position when the command arrives; numeric editing avoids radio timing error.
6. **Local player / Bluetooth off**, or **Bluetooth off** in the notification,
   closes the link. Stopping music keeps Server running. Disconnecting the phone
   leaves the FiiO playing and waiting for another connection. To reconnect, pick
   **Client** and the player again. Switching the phone into Client pauses its
   local playback; it does not resume automatically when leaving Client.

The active mode lives in the foreground playback service, so leaving/rotating the
screen keeps the connection. It is intentionally not auto-started after process
or device restart. Both ends expose media-notification controls. Screen-off and
battery-management behavior must be checked on the actual firmware.

This uses one secure, bonded Bluetooth Classic RFCOMM peer, a dedicated service
UUID and a versioned, length-prefixed JSON protocol. No discovery, BLE advertising,
location access, Wi-Fi, TCP listener or new app dependencies are used. Pairing
provides trust: any paired device running the matching client can control an
enabled Server. Only the selected folder is exposed, using server-issued item
indices rather than accepting arbitrary paths/URIs. The phone caches only the
current page in memory. Messages are capped at 64 KiB and the outbound queue at
32 messages; playback state is sent at most twice per second, only when changed.
Audio decoding and Bluetooth/file IO run on separate workers.

Host integration test (JDK 17+, test-only `org.json` jar):

```sh
curl -fL -o /tmp/loopdeck-json.jar \
  https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar
python3 scripts/test-bluetooth.py /tmp/loopdeck-json.jar
python3 scripts/test-playback-handoff.py
```

The Bluetooth test runs the real protocol and shared folder/CUE code over fake
RFCOMM backed by loopback TCP. It does not test Android permission prompts,
Bluetooth hardware, radio coexistence or vendor power management.

### Controller boundary

UI and media commands go through `PlayerController`: `perform(Action)`, `seek`,
`markers`, `open`, `page`, `up`, `filter`, `refresh` and `chooseFolder`. The controller
owns local/remote selection and publishes one immutable `Browser` model for either
source. `PlaybackService` methods always operate on local audio; the Bluetooth
server invokes those local methods for incoming commands. The activity never
sends protocol messages or decides how to route playback/browsing commands.

The paired-device dialog says **Select a paired device** when there are devices
to choose from. An empty list shows the original Server/pairing setup message.
