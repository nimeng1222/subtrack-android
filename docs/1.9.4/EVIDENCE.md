# 1.9.4 验证记录

环境同 1.9.3：macOS arm64、JDK 17、Gradle 8.10.2、AGP 8.8.2、compileSdk 35；
模拟器 `zpilot_test`（API 36 google_apis_playstore arm64）。构建在 `/private/tmp/subtrack-build`。

## 自动化

| 项 | 结果 |
| --- | --- |
| JVM `:app:testDebugUnitTest` | **472 / 472**（新增 7 条：地址归一化 5、降级判断 1、错误响应压缩 1，另扩了请求体断言） |
| 设备严格门禁 | **173 / 173**（新增 `ai_falls_back_to_plain_chat_when_tools_are_rejected`） |
| Lint | **0 error / 33 warning** |
| `device-ci.py --validate-log` | `{"ok": true, "completed_tests": 173, "errors": []}` |

## 实机（截图在本目录）

1. 服务地址填 `http://127.0.0.1:18082/v1`：设置页显示「发送时会自动补成 …/v1/chat/completions」；
2. 「测试对话」返回「对话接口可用」；
3. 正常工具链路：AI 打开本地 mock 的官网定价页并回答 `$17.99`；
4. 端点 `/tool-blocked`（带 tools 回 403）：自动降级为普通对话，正常显示回答，无错误气泡；
5. 端点 `/forbidden`（403 + JSON 原因）：气泡显示「HTTP 403：这个 Key 没有该模型的对话权限」，旁边有「设置」。

## 发布

- Release：<https://github.com/nimeng1222/subtrack-android/releases/tag/v1.9.4>
- `SubTrack-1.9.4-61.apk` 15,114,912 字节，SHA-256 `c2421ae2…cfff386`，与 Release 资产 `digest` 一致。
- 1.9.3(60) 应用内检查更新 → 发现 1.9.4(61)（截图 `06-update-finds-194.png`）；`adb install -r` 覆盖后 `firstInstallTime` 未变。

## CI

- `main` 上以同一份代码运行的两次设备 job 均 173/173 通过（run 41197479140、41197568589）。
- 其中一次（run 41197396980）有 3 条编辑器用例因模拟器无障碍树读取超时失败
  （Round3 / EStage 的 `text()` 拿不到 Compose 文本，同一份代码下一次即全绿），属于测试读取时序问题，
  与产品行为无关；本轮未改动这些用例的断言。
