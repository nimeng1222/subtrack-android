#!/bin/bash
# SubTrack 本机构建入口（本机临时绕法，非项目正式入口）
#
# 正式入口是工程根目录的 ./gradlew（Gradle 8.10.2 Wrapper，含官方分发 SHA-256 校验），
# 在路径全 ASCII、JDK 17、Android SDK 齐备的机器上直接 ./gradlew :app:assembleDebug 即可。
#
# 本脚本只解决「这台 macOS 机器」的三处偏差（详见 BUILD-NOTES.md §6）：
#   1. 工程路径含中文 → Kotlin/KSP 报 source file or directory not found，先 rsync 到 ASCII 副本；
#   2. 本机 JDK 17 与 Gradle native 库 JNI 不兼容 → 加 -Dorg.gradle.native=false；
#   3. $HOME/.android 不可写 → ANDROID_USER_HOME 重定向，Kotlin 编译 in-process。
# 环境变量均可覆盖；脚本不依赖 /private/tmp 的既有 Gradle 安装（走 ./gradlew，首次会下载分发）。
set -euo pipefail

SRC_DIR="${SUBTRACK_SRC_DIR:-$(cd "$(dirname "$0")/.." && pwd)}"
BUILD_DIR="${SUBTRACK_BUILD_DIR:-/private/tmp/subtrack-build}"
JAVA_HOME="${SUBTRACK_JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
ANDROID_HOME="${SUBTRACK_ANDROID_HOME:-/private/tmp/android-sdk}"
GRADLE_USER_HOME="${SUBTRACK_GRADLE_USER_HOME:-$HOME/.gradle}"
ANDROID_USER_HOME="${SUBTRACK_ANDROID_USER_HOME:-/private/tmp/android-home}"
NATIVE_FLAG="${SUBTRACK_GRADLE_NATIVE:-false}"   # 本机默认关 native；正常机器可设 true 去掉该参数

export JAVA_HOME ANDROID_HOME ANDROID_SDK_ROOT="$ANDROID_HOME" GRADLE_USER_HOME ANDROID_USER_HOME
: "${HOME:?HOME 必须可写（Kotlin daemon / android 配置需要）}"

mkdir -p "$BUILD_DIR"
rsync -a --delete --exclude 'build/' --exclude '.gradle/' --exclude '.kotlin/' \
  --exclude '*.apk' --exclude '.DS_Store' "$SRC_DIR/" "$BUILD_DIR/"

cd "$BUILD_DIR"
./gradlew -Dorg.gradle.native="$NATIVE_FLAG" \
  -Pkotlin.compiler.execution.strategy=in-process "$@"

echo
echo "构建目录：${BUILD_DIR}（产物在 app/build/outputs/ 下）"
