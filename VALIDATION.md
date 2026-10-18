# Validation for 0.1.0

No APK was built. Android Gradle plugin project evaluation, the Android resource
linker, the NDK cross-build, packaging and actual M11S playback were not executed.
This is a source delivery for the owner to build and test on the device.

## Checks completed

- All Java app sources type-checked with Eclipse ECJ against the official Android
  API 35 `android.jar`, using Java 8 language/bytecode mode. The project runs
  Gradle on JDK 27 while targeting Java 17 for app compilation; its app source
  is also compatible with Java 8 syntax.
- The same sources compiled with OpenJDK 27 `javac --release 17` against Android
  API 35 stubs. Seven Android API deprecation warnings appeared; there were no errors.
- JNI C++ source passed the host C++17 compiler's syntax/type checks using official
  OpenJDK JNI headers and FFmpeg 7.1.5 headers.
- The actual `decoder.cpp` compiled and linked against a host build of unmodified
  FFmpeg 7.1.5. This checks its real FFmpeg API usage, not a mock decoder.
- Sequential decoding, repeated EOF reads and seeks at 0, 1/3 second, 1 second,
  1 second + 137 frames, and 3 seconds + 137 frames passed for all fixtures below.
  Each was tested at 44,100 Hz and resampled to 48,000 Hz.
- Frame arithmetic tests passed for A/B boundaries, repeated wrapping, positions
  beyond a 32-bit frame counter, marker validation, sample/time conversion and
  the 32 MiB loop-cache limit.
- Shell scripts passed `bash -n`. Manifest, theme and vector XML parsed successfully.
- For the Ninja regeneration failure on a mounted macOS volume, the top-level
  CMake project sets `CMAKE_SUPPRESS_REGENERATION=ON`; CMake documents that this
  omits Ninja's build-system regeneration rule. The changed native build has not
  been run on the owner's machine. `README.md` includes the one-time `.cxx`
  cleanup needed to replace an existing generated manifest.
- The Gradle wrapper downloaded the checksum-pinned Gradle 9.8.1 archive from a
  local test path and successfully ran `--version` on OpenJDK 27. The project
  retains the official remote distribution URL. The Android app was not built.

| Fixture | Result |
| --- | --- |
| Generated 24-bit stereo WAV | Decode / EOF / seeks passed at both rates |
| FLAC encoded from the WAV | Passed |
| TTA encoded from the WAV | Passed |
| WavPack encoded from the WAV | Passed |
| WMA v2 encoded from the WAV | Passed |
| MP3 encoded from the WAV | Passed |
| FFmpeg sample `monkeyaudio/sh3.ape` | Passed |
| FFmpeg sample `A-codecs/tak/lostchord-tak_2_2_0.tak` | Passed |

### Native TAK 1.0 / codec type 0

The user-supplied 8 MiB TAK prefix declares codec type 0, which the bundled
FFmpeg cannot decode. LoopDeck now selects a separate native C++ decoder for it.

- The native output matched the supplied TAK decoder DLL exactly for all 573
  complete frames: 3,158,376 stereo PCM frames / 12,633,504 bytes at 44,100 Hz,
  about 71.6 seconds. Both PCM outputs have SHA-256
  `ff2b6067cecf25d2957d2ff7a454bb533d7cdcd49420deaa36cd93961e2b7b2c`.
- The upload cuts frame 574 short. That incomplete frame is excluded from the
  comparison and is rejected by the native decoder; it is not counted as a pass.
- 68 generated fixtures matched the original DLL: mono/stereo, 8/16/24-bit PCM,
  source rates from 8 to 192 kHz, all six stereo modes, all 16 predictor orders
  (4 through 256), short final frames, prefilters and channel partitions.
- Through the actual `decoder.cpp`, sequential decode, EOF and seek comparisons
  passed for the complete uploaded frames at 44,100 and 48,000 Hz. Generated
  8-bit and 24-bit mono tracks passed at 8,000 and 48,000 Hz.
- AddressSanitizer and UndefinedBehaviorSanitizer found no errors across 3,000
  truncated or modified packet inputs, including modifications with recalculated
  payload checksums. LeakSanitizer was disabled because the host runtime cannot
  inspect its process threads.
- The JNI bridge type-checks with JDK 27 JNI headers after the native integration.

The Windows DLL was executed only by a host reference test harness. It and the
user's music are not included in this source archive or required by the app.
The rest of the original track, other real codec-0 albums, ARM64 performance,
Android integration and actual device playback remain unverified. No APK was built.

The seek test compares 1,024 output frames with sequential decoding. For resampled
output it excludes the first 64 frames from the comparison to allow filter warm-up.
The maximum allowed absolute sample difference is 0.003 on the float PCM scale.
ASF/WMA's demuxer prints seek-probing warnings for the generated WMA fixture,
but its compared output passes.

One additional legacy FFmpeg sample, `A-codecs/lossless/luckynight.ape`, failed
decoding in both this decoder and the system FFmpeg CLI. It was not counted as a
pass or silently ignored. Another APE sample passed. No audio fixtures are included
in this archive, and these checks do not cover all versions of every format.

## First device checks

1. Build with `./build.sh`, install, grant the actual microSD music folder.
2. Open examples from the real collection: TTA, TAK, APE, WV and WMA.
3. Seek well into a long album; test A/B both while playing and while paused.
4. Confirm cached short repeats and seek-mode long repeats, pause/resume and clearing.
5. Lock the screen, test physical buttons and headphone disconnect.
6. Confirm the source rate / requested output rate and check FiiO's audio settings.

AudioTrack timing, final DAC format, actual gaplessness and responsiveness require
the device. They are not established by host decoder tests.

## Album-image track view patch

Host checks for this change:

- `CueSheetTest`: INDEX 01 boundaries, INDEX 00 pregaps, UTF-8 Japanese/emoji
  titles, missing titles, titles after INDEX, multi-file sheets, long images,
  invalid timestamps, duplicate indices, reversed starts and non-audio entries.
- Existing `LoopMathTest` passed.
- All application Java files passed the JDK parser; `git diff --check` passed.

## Next-track buffering patch

- `GaplessTest` passed: exact stereo sample order across prefix/live reads,
  irregular read sizes, short and empty tracks, explicit CUE limits, cancelled
  preparation, resource ownership and 200 cancellation/publication races.
- `test-playback-handoff.py` compiles the actual PlaybackService against fake
  Android/native IO and simulates playback. Three tracks concatenate sample for
  sample through one AudioTrack, with no intermediate flush/recreation. Metadata
  follows the audible boundary. Seeking while the next track is queued discards
  that handoff and restores the outgoing decoder; all decoder handles close.
- Existing CUE parsing and loop arithmetic tests passed; application Java syntax
  and `git diff --check` passed.

These are host checks. No APK was built and no real AudioTrack/codec/storage
playback was tested for this change. On the M11S, check a continuous lossless album
with the screen off, seek/mark A–B close to a file boundary, pause/resume during a
handoff, skip tracks, stop, and try a sample-rate change. Confirm the final samples
of the last track play and no stale preloaded audio appears after a seek.

## Output warmup patch

- Playback service host simulation passed with exactly 44,100 stereo silent frames
  before the first music sample, followed by exact three-track concatenation
  through one AudioTrack with no extra silence at continuous boundaries.
- Track position stays at zero during warmup; audible metadata boundaries and
  the final track position remain correct. Seeking during warmup discards its
  pending silence, and replay after EOF adds a fresh silent lead-in.
- Existing queued-transition seek cancellation and decoder cleanup checks pass.

No APK was built. The silent lead-in must still be checked on the device to
confirm that its output route wakes within one second. Ordinary pause/resume
uses its existing queued audio without an added lead-in.

## Native sample-rate output (base 3d1cfda)

- Removed the rate toggle, persisted-setting reads/writes and forced 44.1 kHz
  mode. Playback always requests the source rate, retaining the 48 kHz fallback
  if Android rejects it. Existing saved toggle values are ignored.
- Host playback simulation passed for native 96 kHz output and rejection of
  96 kHz with fallback to 48 kHz, including rate-correct one-second warmup.
- Existing silent startup, EOF replay, seek cancellation, gapless sample order,
  audible metadata boundaries and decoder cleanup checks passed.
- MainActivity and PlaybackService passed Java syntax parsing; diff checks passed.

No APK was built. Android/vendor processing may still change the rate downstream.

## Bluetooth server/client (base 4cbe5c5)

- All application Java sources compiled with JDK 17 against the official Android
  API 35 `android.jar`. Deprecation warnings only. Manifest XML parsed and
  `git diff --check` passed.
- `test-bluetooth.py` passed using the actual `BluetoothLink`, `RemoteWire`,
  `MusicLibrary` and CUE parser with fake Android adapters/storage and sockets
  backed by loopback TCP. Covered Unicode/framing boundaries, invalid/truncated
  input, protocol negotiation, 50-entry pages, folder-name filtering, full-folder
  queues across pages, CUE selection, up/root navigation, rejection of stale
  selections, all playback/marker commands, invalid times, disconnect/reconnect,
  and rejection/recovery of an incompatible peer.
- The actual PlaybackService host simulation passed. Added checks that all client
  controls forward over Bluetooth without adding local playback work; Stop keeps
  Server active and disabling Bluetooth after Stop lets the service stop.
  Existing 96 kHz/fallback, startup silence, gapless sample order, audible metadata
  and decoder cleanup checks still pass.

No APK was built. On the FiiO/Pixel, verify initial Bluetooth pairing, permission
prompts, folder grant, screen-off controls, media notifications, CUE browsing,
A–B editing and native-rate output. Disconnect/reconnect, disable Bluetooth in
Settings and switch modes while playing. Server playback should continue when the
phone disconnects, and Client must never play the remote file locally. Hardware
Bluetooth throughput, permissions and vendor background behavior remain unverified.
