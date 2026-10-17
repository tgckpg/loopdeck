# TAK 1.0 native decoder

LoopDeck handles legacy TAK codec type 0 with `tak1.cpp`. FFmpeg still parses
the TAK container and frame boundaries, supplies timestamps, and performs
seeking. Types 2 and 4 continue to use FFmpeg's decoder. Type 0 is selected
from STREAMINFO, without attempting to decode it through FFmpeg first.

The new decoder reads each frame into planar signed PCM. The playback adapter
converts it to left-aligned 32-bit PCM for the existing resampler and AudioTrack
path. It preserves mono/stereo layout, source rate, seeking and A–B repeat.
Frames are independent; a seek resets the stream information and resampler.

The implementation includes the old residual codebooks and partition tables,
channel predictors, prefilters, differential integration, and six stereo modes.
It deliberately uses unsigned arithmetic for 32-bit wrapping and implements
rounding toward zero where required by the reference. Header and payload CRC-24
are verified before reconstruction. Bit reads, frame sizes, predictor orders,
partition indices and coefficient widths are bounded.

## Reference validation

The supplied reference was `tak_deco_lib.dll` 2.3.0 (32-bit Windows x86), SHA-256:

`6b48b47a291aab210d4003caba93b31dfa39a1ca759615d3d042327d65d539de`

`scripts/tak1_reference.py` runs that library's unmodified decoder instructions
with Unicorn on the development computer, providing only the small Win32 IO
adapter it needs. This is a test oracle, not part of Android playback. No DLL
is included, downloaded, or loaded by LoopDeck.

The uploaded example matched exactly for 573 complete frames. Its partial final
frame is rejected. A separate generated corpus matched 68 cases, covering mono,
stereo, 8/16/24-bit PCM, 8–192 kHz, all stereo modes and predictor orders, channel
prefilters, channel partitions and short final frames. See `VALIDATION.md`.
These checks cannot establish that every historical encoder variant is handled.

## Optional host checks

These commands build host test executables, not an APK. Install a host FFmpeg
development package with pkg-config metadata, Python 3, `pefile` and `unicorn`.

```bash
python3 -m pip install pefile unicorn
c++ -std=c++17 -O2 -Iapp/src/main/cpp \
  app/src/main/cpp/tak1.cpp scripts/test-tak1.cpp \
  $(pkg-config --cflags --libs libavformat libavcodec libavutil) \
  -o /tmp/test-tak1

python3 scripts/tak1_reference.py \
  --dll /path/to/tak_deco_lib.dll \
  --input /path/to/legacy-track.tak \
  --native /tmp/test-tak1

python3 scripts/test-tak1-cases.py \
  --dll /path/to/tak_deco_lib.dll --native /tmp/test-tak1
```

For the specific 8 MiB upload, use `--packets 573` to omit the cut final frame.
For a complete original file, omit that option. The reference runner is scoped
to the supplied DLL and is not a general Windows compatibility layer.

To check the actual playback adapter and seeks, compile `decoder.cpp`, `tak1.cpp`
and `scripts/test-decoder.cpp` with the same include flags, adding
`$(pkg-config --libs libswresample)` to the libraries, then pass audio fixtures.

## Building the app update

Replace the sources in the existing checkout, clear `app/.cxx` once, and run
`./build.sh` with JDK 27 as before. The compiled FFmpeg libraries can be reused.
The screen should show **TAK 1.0 (native)** for a codec-0 file.
