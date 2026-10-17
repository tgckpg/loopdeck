# Third-party components

## Native TAK 1.0 support

`tak1.cpp` is a new portable C++ implementation of TAK codec type 0, written
from bitstream behavior and analysis of a user-provided TAK decoding library.
It is included under this project's MIT license. It calls no Windows APIs and
uses no binary loader or CPU emulator at runtime. Original TAK DLLs are not bundled.
The optional host reference scripts require separately installed Python
packages `pefile` and `unicorn`, with their respective upstream licenses;
neither package is part of the Android application.

## FFmpeg 7.1.5

Source: https://ffmpeg.org/releases/ffmpeg-7.1.5.tar.xz

This project builds FFmpeg as separate shared libraries, without enabling GPL
or nonfree components or external codec libraries. The FFmpeg build is LGPL
2.1-or-later. Copyright belongs to the FFmpeg contributors. Its license is
included in `third_party/licenses/FFmpeg-LGPL-2.1.txt`.

The archive checksum is in `scripts/ffmpeg.sha256`. The exact configuration and
build steps are in `scripts/build-ffmpeg.sh`. Upstream FFmpeg source is unmodified.
The JNI glue and app source are supplied in this project. Do not treat the MIT
app license as a license for FFmpeg.

If distributing an APK, supply the corresponding FFmpeg source, these build
instructions, license notices and the materials required for users to modify
and relink the LGPL component. Retain the downloaded source archive rather than
assuming a download link alone satisfies distribution requirements.

## Gradle wrapper 9.8.1

Source: https://github.com/gradle/gradle/tree/v9.8.1

The included `gradle/wrapper/gradle-wrapper.jar` is from the official Gradle
repository. Gradle is licensed under Apache License 2.0, included in
`third_party/licenses/Gradle-Apache-2.0.txt`.

Wrapper JAR SHA-256:
`238e777fcddd7e34f9708186085def2abd6e08e658505b38718d79d74c21abd5`.

Android SDK/NDK tools and the Android Gradle plugin are downloaded separately
under their respective licenses and are not included in this source archive.
