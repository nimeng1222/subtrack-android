#!/bin/bash
# CI 设备门禁的唯一入口：在**同一个 bash 进程**里起依赖服务、跑全部设备测试、收尾。
#
# 为什么必须是一个进程：reactivecircus/android-emulator-runner 会把 script 的每一行拆成独立的
# `sh -c` 执行，上一行起的后台 fixture 在该行退出后会被清理。设备端此时连到 adb reverse 上
# 表现为 "unexpected end of stream"，整套网络/同步用例集体失败。
#
# 用法：bash scripts/ci-device.sh [输出目录]
set -euo pipefail

OUT="${1:-artifacts/device-ci}"
mkdir -p "$OUT"

./scripts/verify-device-services.sh start "$OUT/services.log"
cleanup() {
  ./scripts/verify-device-services.sh stop "$OUT/services.log" || true
}
trap cleanup EXIT

adb reverse tcp:18080 tcp:18080
adb reverse tcp:18450 tcp:18450

python3 scripts/device-ci.py --output "$OUT"
