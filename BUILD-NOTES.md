# 构建说明与工具链偏差

## v1.4 开发预览增补（2026-10-28）

当前版本 1.4.0 / 18，沿用 com.subtrack 与现有 Debug 签名，正式材料缺失时保持 unsigned Release。网络权限经用户明确授权新增 INTERNET；直接 APK 更新选择 REQUEST_INSTALL_PACKAGES，系统仍要求用户确认。Release 禁用明文，debug 仅模拟器/本机例外。Room 开启 schema 导出，当前仍 schema v1（未交付同步元数据迁移，不能谎称 1→2 已完成）。配置与实测见 NETWORK-SETUP.md 和 ../docs/FEATURE-REPORT-NETWORK-v1.4.md。历史章节“无网络权限”的描述仅代表对应历史版本。

## 1. 为什么版本和架构文档不一致

架构文档锁定的是 **AGP 9.4.0 / Kotlin 2.4.21 / compileSdk 37 / targetSdk 36**。那套版本要求 JDK 21 + Android Studio 的 AGP 9 支持，且 AGP 9 启用了内置 Kotlin，构建脚本写法与 8.x 不同。

交付开始时本机**没有 Android SDK、没有 Gradle、只有 JDK 17**，无法验证 AGP 9.4。随后安装了 Android SDK（android-35 / build-tools 35.0.0）与 Gradle 8.10.2，**已用下面这套基线真实构建通过**：

| 项 | 本文工程 | 架构文档 |
|---|---|---|
| AGP | 8.8.2 | 9.4.0 |
| Kotlin | 2.1.10 | 2.4.21 |
| compileSdk / targetSdk | 35 / 35 | 37 / 36 |
| Compose BOM | 2025.02.00 | 2026.09.00 |
| Room | 2.6.1 | 2.8.5 |
| minSdk | 26 | 26 |

**这是临时基线，不是新决策。** 升级到文档版本时按下面顺序单独做一次，不要和业务代码混在一起：

1. 先升 AGP + Gradle（9.4.0 / 9.6.0），跑通空壳；
2. 再升 compileSdk 37、Kotlin 2.4.21、Compose BOM 2026.09.00；
3. 最后升 Room 2.8.5，跑迁移测试；
4. 记 ADR-009，写清失败现象与最终版本。

## 2. 本机验证结果（2026-10-26）

```
Gradle 8.10.2 + AGP 8.8.2 + JDK 17 + android-35
:app:assembleDebug        BUILD SUCCESSFUL
:app:testDebugUnitTest    OK（45 tests, 0 failures）
合并后权限                 3 个，无 INTERNET / USE_EXACT_ALARM
```

构建期间发现并修掉的问题：**WorkManager 未被使用，但它的清单合并会带入 `WAKE_LOCK` / `ACCESS_NETWORK_STATE` / `FOREGROUND_SERVICE`**。为了让合并权限保持 3 个，v0.1 移除了 WorkManager，每日扫描改用 **AlarmManager 的一次性 `setAndAllowWhileIdle` 自续**（每次触发后重挂次日 09:00）。这是对 ADR-004 的临时偏离：功能等价、权限更干净，代价是失去 WorkManager 的批量调度与约束能力。后续若重新引入 WorkManager，必须同时核对合并清单。

## 3. 领域层验证（可独立复现）

领域层（不依赖 Android）已用 Kotlin 2.4.21 编译器 + JUnit 4.13.2 真实编译运行：

```bash
kotlinc -jvm-target 17 -cp junit.jar -d out.jar \
  app/src/main/java/com/subtrack/domain/model/Models.kt \
  app/src/main/java/com/subtrack/domain/cycle/CycleEngine.kt \
  app/src/main/java/com/subtrack/domain/stats/Annualizer.kt \
  app/src/main/java/com/subtrack/domain/state/DisplayStateResolver.kt \
  app/src/test/java/com/subtrack/domain/*.kt

java -cp out.jar:junit.jar:hamcrest.jar:kotlin-stdlib.jar \
  org.junit.runner.JUnitCore com.subtrack.domain.CycleEngineTest com.subtrack.domain.AnnualizerTest
```

结果：`OK (31 tests)`。

测试期间抓到并修掉一个真实 bug：双周/四周/自定义天数三支把“周期数”当成“天数/周数”累加，导致双周付被算成每周付。这类错误不抛异常，只会让日期整体偏早。

## 4. 构建命令

```bash
# 首次
export ANDROID_HOME=$HOME/Library/Android/sdk
./gradlew :app:assembleDebug

# 领域测试
./gradlew :app:testDebugUnitTest
```

若 `./gradlew` 不存在，用本机 Gradle 生成 wrapper：

```bash
gradle wrapper --gradle-version 8.8
```

## 5. 常见失败

| 现象 | 处理 |
|---|---|
| `Unsupported class file major version` | Gradle 跑在 JDK 21+ 上；本基线用 JDK 17 |
| `compileSdk 35 not found` | SDK Manager 装 Platforms;Android 15 (API 35) 与 Build-Tools 35.0.0 |
| Compose 编译器版本不匹配 | Kotlin 2.1.10 必须配 `org.jetbrains.kotlin.plugin.compose` 2.1.10 |
| Room 生成类缺失 | 确认 KSP 版本 `2.1.10-1.0.29`，且 `ksp(...)` 已加 |

## 6. 本机（macOS 26 / arm64）构建环境的三处坑

v0.3.0 重新构建时，`~/.gradle` 下的 Gradle 8.14.3 与 `/private/tmp/gradle/gradle-8.10.2` 都直接失败：

```
Could not initialize native services.
  > Failed to load native library 'libnative-platform.dylib' for Mac OS X aarch64.
```

用 `System.load` 单测该 dylib 会抛 `NoSuchMethodError: details`，说明本机 JDK（Temurin/Homebrew 17.0.19）与该 native 库的 JNI 入口不兼容。绕法是加 `-Dorg.gradle.native=false`，Gradle 改用纯 Java 实现（牺牲文件监听/watch，构建可用）。

坑二：**工程路径含中文**会让 Kotlin/KSP 把路径转成 `u8BA2u9605...` 这样的转义串再传给编译器，报 `source file or directory not found`。绕法是先把工程 rsync 到纯 ASCII 路径再构建。

坑三：本机 `$HOME/.android` 不可写，`validateSigningDebug` 会报 `Unable to create debug keystore`；Kotlin daemon 也会因为写 `~/Library/Application Support/kotlin/daemon` 失败而连不上。需要指定 `ANDROID_USER_HOME` 并把 Kotlin 编译改成 in-process。

综合成脚本 `.workbuddy/build.sh`（已可复现）：

```bash
#!/bin/bash
set -e
SRC=/Users/ningmeng/Desktop/订阅项目/subtrack-android
DST=/private/tmp/subtrack-build
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=/private/tmp/android-sdk
export ANDROID_SDK_ROOT=/private/tmp/android-sdk
export GRADLE_USER_HOME=/private/tmp/gh          # 从 ~/.gradle 复制出来的依赖缓存
export ANDROID_USER_HOME=/private/tmp/android-home
export HOME=/private/tmp/build-home
mkdir -p "$HOME" "$ANDROID_USER_HOME"

mkdir -p "$DST"
rsync -a --delete --exclude 'build/' --exclude '.gradle/' --exclude '.kotlin/' "$SRC"/ "$DST"/
cd "$DST"
/private/tmp/gradle/gradle-8.10.2/bin/gradle -Dorg.gradle.native=false --offline \
  -Pkotlin.compiler.execution.strategy=in-process "$@"
```

产物在 `/private/tmp/subtrack-build/app/build/outputs/apk/debug/app-debug.apk`，需要手动拷回工程目录。

## 7. 模拟器验收环境

本机原本无系统镜像，v0.3.0 装了 SDK Emulator 37.2.12 + `system-images;android-35;google_apis;arm64-v8a`，AVD 建在 `/private/tmp/avd-home`（`HOME` 不可写，必须用 `ANDROID_AVD_HOME` 重定向，否则 emulator 报 `Failed to create jwk directory`）。

```bash
ANDROID_AVD_HOME=/private/tmp/avd-home \
  /private/tmp/android-sdk/emulator/emulator -avd subtrack35 \
  -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot
```

设备 `1080×2400 / 420dpi`。截图走 `adb exec-out screencap -p`，坐标参考：Tab 三项中心点 y≈2255、x≈290/540/790；右上铃铛 x≈875 y≈190、加号 x≈985 y≈190。

## 8. 发布准备轮更新（2026-10-28，v1.3.5）

### 正式构建入口已补齐

- **Gradle Wrapper**：`gradlew` + `gradle/wrapper/`（Gradle 8.10.2 bin），`distributionSha256Sum=31c55713…` 与官方发布页逐字核对，本机实跑 `./gradlew` 下载分发并构建通过（`wrapper-verify.log`）。历史 §4/§6 的 `../.workbuddy/build.sh` 与 `/private/tmp/gradle/…` 直调路径是**本机临时绕法**，正式入口以工程根目录 `./gradlew` 为准；中文路径/JDK native 的绕法保留在 `scripts/build-local.sh`（环境变量可覆盖，默认仍关 native）。
- **Release 签名**：`app/build.gradle.kts` 从 `SUBTRACK_RELEASE_*` 环境变量或未跟踪的 `keystore.properties` 读签名材料；两者皆缺时输出 `app-release-unsigned.apk` 并打 WARN，不生成临时 key。密钥文件已进 `.gitignore`。
- **CI**：`.github/workflows/ci.yml`（JVM + Lint + Debug 构建 + 可选模拟器集成测试）。**截至交付没有远端仓库，此配置未在 GitHub Actions 实际运行过**，不能当作绿色 CI 证据。

### 本轮修复的缺陷（均有设备证据，见 docs/release-readiness-2026-10-27/）

1. **LockGate 回前台不重锁**：`remember(lockKey)` 键控重置会替换 MutableState 实例，生命周期观察者闭包捕获旧实例，ON_STOP 的解锁清除写不进当前状态。改为单一实例 + `LaunchedEffect(lockKey)` 显式重置；新增设备回归 `gate_relocks_on_same_session_foreground_return_when_settings_loaded_late`。
2. **剪贴板识别永不生效**：读取挂在 ON_RESUME，早于窗口获得焦点，Android 10+ 的 ClipboardService 以 "application is not in focus" 拒绝（冷启动与点图标启动均实测命中）。改为挂 `OnWindowFocusChangeListener(hasFocus=true)`。
3. **设置健康卡不刷新**：通知/精确闹钟/电池优化状态只在首次组合时读一次，用户在系统设置改完回来显示过时状态。以 `resumeTick`（回前台递增）为 remember key 重读。

### Glance/WorkManager 权限核查结论（2026-10-28 实测）

APK 合并权限确认 5 个（无 WAKE_LOCK/ACCESS_NETWORK_STATE/FOREGROUND_SERVICE）。运行时证据：API 35 模拟器上小组件由真实 launcher 添加、数据一致、冷启动后渲染正常；`dumpsys jobscheduler` 显示 WorkManager 只注册了一个**未来的自检 job**（SystemJobService，约束 CHARGING，最小延迟 +3648 天=任务队列实际为空），未发生 SecurityException。结论：当前"读库渲染、无 Worker"的用法在剔除这三个权限下可运行；若未来引入任何真正的 Worker，必须先恢复 WAKE_LOCK。

### 尚存偏差

- Lint 5 Warning 为有意保留（UnusedAttribute×2 / BatteryLife / UnsafeProtectedBroadcastReceiver / VectorPath），理由见发布准备报告 C6。
- 模拟器手势无法完成小组件 2x2 拖拽缩放的自动化验证（resizeMode 与 minResize 已声明，布局自适应代码存在），待真机人工确认。
- 深度 Doze 用 adb 强制（`dumpsys deviceidle force-idle`）验证送达；OEM 长期省电、过夜、物理设备项未覆盖。


## 9. 发布准备补修（2026-10-28，v1.3.6 / 17）

- 设备 CI 以 `python3 scripts/device-ci.py` 为唯一入口，使用 Wrapper 构建，严格按安装后授权再测试的顺序；完整原始协议、逐例结果、结束标记和非零失败检查替代 `connected... || adb`。失败也保存 JSON/JUnit/原始日志及已有构建报告。协议自测为 `python3 scripts/test-device-protocol.py`；本地正负向结果见新报告，远端未运行。
- 电池入口采用 `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` 的通用列表，不新增直接豁免权限。提供应用详情/系统设置降级、操作提示和异常反馈。旧 §8 的 BatteryLife 保留理由已失效；当前 Lint 余 4 Warning：UnusedAttribute×2、UnsafeProtectedBroadcastReceiver、VectorPath。
- 小组件刷新同时监听订阅与已发布的 fxRates/multiCurrencyEnabled；渲染使用同一输入快照，并在 Glance 会话中观察更新。加载失败/超时明确显示重试提示，不用默认设置或错误金额 0 掩盖读取失败。未增加定时任务或联网权限。
- 正式 Wrapper、已有 Release 签名读取边界保持；无正式密钥仍交付明确标识的 unsigned Release。所有历史 APK、报告与证据保留。物理设备认证、OEM/过夜/物理重启、2x2 实际缩放和远端 CI 不因本轮局部验证而自动通过。

## 10. T2 修复轮的构建环境偏差（2026-10-30，v1.8.5 / 56）

本轮产物在 **arm64 Linux + JDK 17 (Temurin) + Android SDK 35** 上生成，不是 §6 描述的那台 macOS。差异只有一处需要记录：

- 官方 `aapt2` 只提供 x86-64 Linux 构建。arm64 上以 `qemu-x86_64`（Ubuntu `qemu-user-static` 8.2.2 + amd64 `libc6/libstdc++6/libgcc-s1` 作为 `QEMU_LD_PREFIX`）执行，并通过
  `-Pandroid.aapt2FromMavenOverride=<sdk>/build-tools/35.0.0/aapt2` 让 AGP 使用它（2.19-11948202，AGP 自带的是 2.19-12006047，同一 2.19 版本线）。
- 因此 Debug APK 用该机新生成的 Android Debug 证书签名（与历史 Debug APK 不同），Release 仍是 unsigned；SHA 见 `../docs/FIX-REPORT-NETWORK-v1.8.5-T2-2026-10-30.md`。
- 该环境**没有**模拟器/`adb` 设备，159 项设备门禁未在本地执行；在具备模拟器的机器上按 §7 与 `scripts/verify-all.sh` 复跑即可，构建命令无需 aapt2 覆盖参数。
