#!/bin/bash
# 一键可复现验收（独立验收者用）。全部失败即非零退出；不公开部署、不付费调用、不生成假签名。
#   bash scripts/verify-all.sh                 # 构建 + JVM + Lint + 服务端 + CI 协议 + E4 host（不含设备）
#   RUN_DEVICE=1 ANDROID_SERIAL=emulator-5554 bash scripts/verify-all.sh   # 追加全量严格设备门禁（当前 75 项，由 device-ci.py 从 androidTest 自动发现）
# 需：JDK17、Android SDK(35)、python3；设备门禁还需模拟器 + network-fixture + adb reverse（脚本自动起 fixture）。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="${SUBTRACK_JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export ANDROID_HOME="${SUBTRACK_ANDROID_HOME:-/private/tmp/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME="${SUBTRACK_GRADLE_USER_HOME:-/private/tmp/gh}"
export SUBTRACK_GRADLE_USER_HOME="$GRADLE_USER_HOME"   # build-local.sh reads this name
export SUBTRACK_JAVA_HOME="${SUBTRACK_JAVA_HOME:-$JAVA_HOME}"
export SUBTRACK_ANDROID_HOME="$ANDROID_HOME"
BUILD="${SUBTRACK_BUILD_DIR:-/private/tmp/subtrack-verify-all}"
ADB="$ANDROID_HOME/platform-tools/adb"

echo "==================== 1/5 Gradle 全量门禁 ===================="
if [ "${SKIP_BUILD:-0}" != "1" ]; then
  SUBTRACK_BUILD_DIR="$BUILD" bash "$ROOT/scripts/build-local.sh" --offline \
    :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug :app:assembleDebugAndroidTest
fi

echo "==================== 2/5 服务端（10） ===================="
( cd "$ROOT/server" && python3 -m unittest -v test_server.py )

echo "==================== 3/5 CI 协议（9） ===================="
python3 "$ROOT/scripts/test-device-protocol.py"

echo "==================== 4/5 E4 隔离 HTTPS 更新契约（4，宿主级） ===================="
python3 "$ROOT/scripts/test-update-https-fixture.py"

if [ "${RUN_DEVICE:-0}" = "1" ]; then
  echo "==================== 5/5 设备严格门禁 ===================="
  SERIAL="${ANDROID_SERIAL:-emulator-5554}"
  python3 "$ROOT/scripts/network-fixture.py" & FIXPID=$!
  # Dedicated local sync server for the N4 end-to-end test (fixed TEST setup token; disposable data file).
  ( cd "$ROOT/server" && SUBTRACK_DATA=/private/tmp/subtrack-e2e-sync.sqlite SUBTRACK_BIND=127.0.0.1 PORT=18450 \
      SUBTRACK_SETUP_TOKEN="subtrack-e2e-test-setup-token-0123456789" SUBTRACK_DEBUG_HTTP=1 python3 app.py ) & SYNCPID=$!
  trap 'kill "$FIXPID" "$SYNCPID" 2>/dev/null || true' EXIT
  sleep 2
  "$ADB" -s "$SERIAL" reverse tcp:18080 tcp:18080
  "$ADB" -s "$SERIAL" reverse tcp:18450 tcp:18450
  "$ADB" -s "$SERIAL" install -r "$BUILD/app/build/outputs/apk/debug/app-debug.apk"
  "$ADB" -s "$SERIAL" install -r "$BUILD/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
  "$ADB" -s "$SERIAL" shell pm grant com.subtrack android.permission.POST_NOTIFICATIONS || true
  "$ADB" -s "$SERIAL" shell am instrument -w -r com.subtrack.test/com.subtrack.Round2Instrumentation > "$BUILD/device.log" 2>&1 || true
  ( cd "$BUILD" && python3 scripts/device-ci.py --validate-log "$BUILD/device.log" --output "$ROOT/artifacts/device-ci" )
else
  echo "（设备门禁跳过：设 RUN_DEVICE=1 且提供 ANDROID_SERIAL 运行严格设备门禁）"
fi

echo "ALL GREEN ✅"
