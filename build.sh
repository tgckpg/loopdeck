#!/bin/bash

brew list --versions openjdk

export JAVA_HOME=$(brew --prefix)/opt/openjdk@27
export ANDROID_HOME="$HOME/.rbash/lib/Android"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

# ./scripts/build-ffmpeg.sh

./gradlew assembleDebug

adb install -r ./app/build/outputs/apk/debug/app-debug.apk
