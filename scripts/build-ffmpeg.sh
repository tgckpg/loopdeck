#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"
VERSION=7.1.5
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
NDK="${ANDROID_NDK_HOME:-$SDK/ndk/27.2.12479018}"
[[ -d "$NDK/toolchains/llvm/prebuilt" ]] || { echo "Missing NDK: $NDK (see README.md)" >&2; exit 1; }
case "$(uname -s)" in
    Darwin) HOST_TAG=darwin-x86_64 ;;
    Linux) HOST_TAG=linux-x86_64 ;;
    *) echo 'Build on macOS or Linux (or WSL2).' >&2; exit 1 ;;
esac
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG"
PREFIX="$ROOT/third_party/ffmpeg/arm64-v8a"
STAMP="$PREFIX/.build-config"
FINGERPRINT="$VERSION|$NDK|$(cat "$0" | cksum)"
if [[ -f "$STAMP" && "$(cat "$STAMP")" == "$FINGERPRINT" && -f "$PREFIX/lib/libavcodec.so" ]]; then
    echo 'Using cached FFmpeg build.'; exit 0
fi
mkdir -p .deps
ARCHIVE="$ROOT/.deps/ffmpeg-$VERSION.tar.xz"
if [[ ! -f "$ARCHIVE" ]]; then
    curl --fail --location --retry 3 --connect-timeout 20 "https://ffmpeg.org/releases/ffmpeg-$VERSION.tar.xz" -o "$ARCHIVE.part"
    mv "$ARCHIVE.part" "$ARCHIVE"
fi
EXPECTED=$(awk '{print $1}' scripts/ffmpeg.sha256)
if command -v sha256sum >/dev/null; then ACTUAL=$(sha256sum "$ARCHIVE" | awk '{print $1}'); else ACTUAL=$(shasum -a 256 "$ARCHIVE" | awk '{print $1}'); fi
[[ "$EXPECTED" == "$ACTUAL" ]] || { echo 'FFmpeg archive checksum mismatch; remove .deps archive and retry.' >&2; exit 1; }
[[ -d ".deps/ffmpeg-$VERSION" ]] || tar -xf "$ARCHIVE" -C .deps
mkdir -p .deps/ffmpeg-build-arm64
cd .deps/ffmpeg-build-arm64
DECODERS='aac,aac_fixed,aac_latm,ac3,alac,als,amrnb,amrwb,ape,atrac1,atrac3,atrac3p,atrac3pal,atrac3al,cook,dca,dsd_lsbf,dsd_msbf,dsd_lsbf_planar,dsd_msbf_planar,eac3,flac,gsm,mp1,mp1float,mp2,mp2float,mp3,mp3float,mp3adu,mp3adufloat,mp3on4,mp3on4float,mpc7,mpc8,nellymoser,opus,pcm_*,adpcm_*,qcelp,qdm2,qdmc,ra_144,ra_288,ralf,shorten,sipr,speex,tak,truehd,truespeech,tta,twinvq,vorbis,wavpack,wmalossless,wmapro,wmav1,wmav2,wmavoice'
"$ROOT/.deps/ffmpeg-$VERSION/configure" \
    --prefix="$PREFIX" --target-os=android --arch=aarch64 --cpu=armv8-a \
    --enable-cross-compile --sysroot="$TOOLCHAIN/sysroot" \
    --cc="$TOOLCHAIN/bin/aarch64-linux-android26-clang" \
    --cxx="$TOOLCHAIN/bin/aarch64-linux-android26-clang++" \
    --ar="$TOOLCHAIN/bin/llvm-ar" --ranlib="$TOOLCHAIN/bin/llvm-ranlib" \
    --strip="$TOOLCHAIN/bin/llvm-strip" --nm="$TOOLCHAIN/bin/llvm-nm" \
    --disable-autodetect --disable-programs --disable-doc --disable-debug \
    --disable-network --disable-avdevice --disable-avfilter --disable-swscale \
    --disable-encoders --disable-muxers --disable-decoders --enable-decoder="$DECODERS" \
    --disable-protocols --enable-protocol=file \
    --enable-shared --disable-static --enable-pic \
    --extra-ldflags='-Wl,-z,max-page-size=16384'
for CODEC in APE TAK TTA WAVPACK WMAV1 WMAV2 WMAPRO WMALOSSLESS; do
    grep -q "#define CONFIG_${CODEC}_DECODER 1" config_components.h || { echo "Missing required decoder: $CODEC" >&2; exit 1; }
done
JOBS="${JOBS:-$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)}"
make -j"$JOBS"
make install
printf '%s' "$FINGERPRINT" > "$STAMP"
