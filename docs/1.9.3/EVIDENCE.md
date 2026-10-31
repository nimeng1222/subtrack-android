# 1.9.3 验证记录（可复现）

环境：macOS arm64、JDK 17、Gradle 8.10.2、AGP 8.8.2、compileSdk 35；
模拟器 `zpilot_test`（Android 16 / API 36 google_apis_playstore arm64）。
工程路径含中文，构建在 `/private/tmp/subtrack-build`（纯 ASCII 副本）里执行，参数与 `BUILD-NOTES.md` 一致。

## 1. JVM

```
./gradlew -Dorg.gradle.native=false --offline -Pkotlin.compiler.execution.strategy=in-process :app:testDebugUnitTest
```

结果：**465 tests, 0 failures**（`app/build/test-results/testDebugUnitTest/*.xml` 汇总）。

## 2. Lint

```
./gradlew :app:lintDebug
```

结果：`0 errors, 33 warnings`（本轮改动的文件没有新增警告）。

## 3. 设备严格门禁

前置：`scripts/verify-device-services.sh start`（18080 fixture + 18450 同步服务端），
`adb reverse tcp:18080` / `tcp:18450`，**先 `pm clear com.subtrack`**（累积的已绑定账本会指向旧服务端，
不属于产品行为）。

```
adb shell am instrument -w -r com.subtrack.test/com.subtrack.Round2Instrumentation > device.log
python3 scripts/device-ci.py --validate-log device.log --output artifacts/device-ci
```

结果：`{"ok": true, "completed_tests": 172, "errors": []}`。
（设备结果协议见 `scripts/device-ci.py`：它按源码里所有 `@Test` 生成期望集合，漏跑/多跑都判失败。）

## 4. 实机操作（截图在本目录 `screenshots/`）

| 截图 | 验证点 |
| --- | --- |
| `01-editor-date-aligned.png` | 开始日期/下次扣费的 −、日期框、+ 三列上下对齐 |
| `02-detail-paid-total.png` | 新建 ¥68 后详情显示「¥68.00（1 笔）」 |
| `03-currency-online-rates.png` | 在线参考 6.6529 / 0.0423 / 7.4327 / 0.8480 / 8.9142，来源与日期可见 |
| `04-model-picker.png` | 模型列表 3 个、全选/全不选、已选计数 |
| `05-ai-model-chips.png` | AI 页可切换已选模型 |
| `06-ai-cta-lands-on-settings.png` | AI 未配置点按钮后落在「联网与 AI」 |
| `07-stats-no-placeholder.png` | 统计页不再出现「实际口径待启用」 |
| `08-update-found.png` | 1.9.2(59) 检查到 1.9.3(60) 与发布说明 |
| `09-update-verified.png` | 下载后「大小、哈希、版本与当前证书一致」 |
| `10-update-needs-permission.png` | 未允许安装未知应用时的说明与跳转按钮 |
| `11-install-unknown-apps.png` | 跳转到的系统「Install unknown apps」页 |
| `12-update-up-to-date.png` | 最终 1.9.3(60) 检查显示「已是最新」 |

升级链：1.9.2(59) → 1.9.3(60) 通过 `adb install -r` 覆盖，`firstInstallTime` 保持 `11:55:13` 不变。

## 5. 更新资产一致性

```
sha256(SubTrack-1.9.3-60.apk) = 5fc3535c…b257be48
GitHub Release asset digest   = sha256:5fc3535c…b257be48
```

两者一致，说明应用下载到的就是本目录发布的这一份。
