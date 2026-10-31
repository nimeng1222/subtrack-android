# SubTrack（订阅台账）Android

[![SubTrack CI](https://github.com/nimeng1222/subtrack-android/actions/workflows/ci.yml/badge.svg)](https://github.com/nimeng1222/subtrack-android/actions/workflows/ci.yml)

> **下载最新版**：[GitHub Releases](https://github.com/nimeng1222/subtrack-android/releases/latest)（当前 v1.9.4 / 61）
>
> **应用内检查更新**：「设置 → 关于与更新 → 允许检查新版本 → 检查新版本」。它读取本仓库的最新 Release，
> 下载后校验大小、版本码与签名证书，必须与当前安装是同一张证书才会交给系统安装器，不会静默安装、不会卸载原应用。
>
> **升级保留数据**：包名固定 `com.subtrack`，同证书覆盖安装；`adb install -r` 或直接点安装包即可，不需要先卸载。
> 当前发布包是 **Debug 签名**（与历史交付的 1.9.0–1.9.2 一致），因此只能覆盖同签名的安装；换正式签名需要先卸载。
>
> 当前状态：v1.9.4 已通过 **472 条 JVM 单测 + 173 条严格设备门禁 + Lint 0 error**；台账、提醒、统计、同步、更新校验的既有语义未改动。
> 历史 v1.4.0 的说明保留在下方，仅作背景。

本地优先的订阅管理 App。零账号、零网络权限、零广告。设计走 Apple Liquid Glass 方向：环境色场 + 玻璃卡 + 浮动胶囊 Tab。

> 当前版本：**v1.3.6**。v1 全量（P0-1~P0-6）+ L1 v1.1 规划 + L2 增强 + 三轮数据完整性修复 + 发布准备轮（生物识别系统级闭环修复、调度矩阵实测、旧版升级链实测）。
> 本机用 Gradle 8.10.2（Wrapper，含官方分发 SHA-256 校验）+ AGP 8.8.2 + JDK 17 构建通过，**289 条单测 + 20 条设备测试全绿**；已在 Android 15 模拟器（1080×2400 / 420dpi）完成本轮 D1–D3 发布准备补修验证（2026-10-28，本轮见 `../docs/FIX-REPORT-READINESS-2026-10-28.md`；前轮见 `../docs/RELEASE-READINESS-REPORT-2026-10-27.md`）。

---

## 一、功能总览

上一版把视觉体系重做完了，这一版把 MVP 的功能清单（P0-1~P0-6）全部补齐。逐条对照 PRD 的 59 条验收标准实现：

### P0-1 台账 CRUD 与全字段录入
- 编辑页支持 **9 种周期全部可选**（周/双周/四周/月/季/半年/年/自定义天数/一次性），切周期即时重算下次扣费日，且允许逐日微调覆盖（AC-03）。
- 字段齐全：名称、分类、金额、周期、开始日期、下次到期日、备注（200 字）、自动续费、试用、单条提醒提前量（AC-01）。
- 就地校验、不清空已填内容（AC-05）；同名校验只提示不拦截（AC-06）。

### P0-2 状态流转与续费滚动
- **补记多期**：长期没开 App 再次确认续费时，会自动推进到第一个不早于今天的日期，并提示"已推进 N 期"（AC-12）。实现在 `RenewalEngine`，带 480 期上限防脏数据。
- 暂停后恢复**必须由用户确认新的下次扣费日**，系统不擅自猜（AC-15）。
- 待确认条目在首页**就地**给三个动作：已续费 / 取消 / 再说（AC-19）。
- 试用转正（写流水 + 转 ACTIVE + 滚动）、标记已过期（与取消等价但标签不同）（AC-48/50/51）。
- 所有状态变更走统一入口 `SubscriptionActions`，一次落库 + 重排提醒，避免三处数字不一致（AC-20）。

### P0-3 提醒双通道与兜底
- **过期补发**：过期后第 1/3/7 天各补发一次，之后只在应用内永久标记（AC-25）。
- **提醒健康度自检卡**：通知权限 / 精确闹钟 / 电池优化三项实时状态 + 一键跳转修复 + "发一条测试提醒"（AC-28/28a）。
- 提醒时刻与默认提前天数走设置（DataStore 持久化），不再硬编码 09:00（裁决 D8）。

### P0-4 统计三口径（本产品的核心卖点）
- 三个口径并列且不混用标签：本月将扣（现金流）/ 预计月均（承诺）/ 近 90 天实际月均（真实）（AC-36）。
- **每个数字都能点开看公式与参与计算的条目清单**（AC-35）——这是"数字可核对"的落地。
- 覆盖不足 90 天不展示实际月均，改显示"记录满 90 天后可核对"，绝不外推（AC-36a）。
- 两口径差值洞察：超 20% 且超 10 元才提示"可能漏记"或"可能有僵尸订阅"（AC-36b）。
- 分类占比带金额、一位小数百分比、条目数（AC-37）；单分类改占比条不画饼图（AC-38）；一次性买断单独汇总不进摊销（AC-40）。

### P0-5 首页
- 待确认**优先于金额**展示且可逐条处理（AC-42）。
- 近期到期最多 5 条 + "查看全部"（AC-43）；下拉刷新（AC-44）；空台账引导 + 金额 0.00（AC-45）。
- 读取失败显示可重试错误卡，**不伪装成"数据为空"**（AC-46）；加载中不先渲染 ¥0.00 再跳变。
- 状态筛选：全部 / 生效中 / 已暂停 / 已结束。

### P0-6 数据备份 v2
- JSON 含订阅 + 流水 + 设置，`formatVersion: 2`（AC-52）；文件名 `SubTrack-20261026.json`（AC-53）。
- 导入给出摘要"新增 X / 跳过 Y / 修正 Z"而非一句"成功"（AC-54）；同 id 默认跳过不覆盖（AC-55）。
- 版本过新拒绝导入（AC-56）；文件损坏整次拒绝、绝不半套数据（AC-57）。
- 往返一致 + 重复导入幂等（实测第二次导入：新增 0 条 / 跳过 6 条）（AC-58）。
- CSV 带 UTF-8 BOM，Excel 打开中文不乱码（AC-59）。

### v1.0.0 补齐的四项（v1 范围收口）

**① 深色模式**
- Token 双套 + CompositionLocal（`SubTrackTheme.colors.xxx`），不是"每个页面加 if (isDark)"。
- 三态：跟随系统 / 强制浅色 / 强制深色，存在 DataStore，重启保留。
- 深色下色晕收敛到 55%、玻璃卡统一不透明度（不同 alpha 会让卡片深浅不一，像"用了两种卡片"）、强调色换 `#0A84FF`。
- 旧的 70 处 `Ink/Accent/...` import 通过"主题感知 getter"平滑迁移，不用改调用点。

**② 桌面小组件 4x2**
- 左「预计月均 + 年度」，右「最近 3 笔到期」；整块点进首页，深链已验证。
- **不做快照表**：直接 `provideGlance` 查库，订阅和已加载的汇率/多币种设置变化均驱动刷新；运行中的 Glance 会话也订阅同一输入流。快照会制造第二份事实来源，一旦与库不一致就是最伤信任的 bug。
- 加 Glance 会经 `androidx.work` 带进 `WAKE_LOCK`/`ACCESS_NETWORK_STATE`/`FOREGROUND_SERVICE`，已用 `tools:node="remove"` 全部剔除，当前合并后为 5 项系统权限（另两项为应用锁所需）。

**③ Inter 字体子集**
- 从 Inter 4.1 可变字体按 `wght`(400/600/700) + `opsz 14` 实例化后子集化，只保留 ASCII + App 实际用到的符号，**三个字重共 96 KB**。
- 保留 `tnum`（等宽数字，金额对齐不跳）与 GPOS kerning。
- 缺字形为零：文档 gate 9 要求的 `0-9 . , % - ¥ × Σ — … →` 全部校验过。
- 中文仍走系统 Noto Sans SC（Inter 不含 CJK，自动回落）。

**④ CSV 导入（P1）**
- 与 JSON 的严格全量语义**刻意相反**：CSV 逐行容错，用户只错一行不该整份失败。
- 按列名匹配不按位置（Excel 里拖列、加列都不该让导入错乱）；支持中文表头与中文周期/状态名。
- 逐行错误报告带**真实物理行号**（引号内换行也占行，否则带多行备注的文件后面全部错位）。
- 金额走 `BigDecimal`，超过 2 位小数判非法而不是四舍五入——那等于替用户改账。
- 幂等：id 由「行号 + 名称 FNV-1a」确定性生成，同一文件导两次不产生重复。
- 已知限制：CSV 没有 `customCycleDays` 列，自定义周期导入时兜底 30 天；设置页文案已引导这类用户走 JSON。

### v1.1 规划项（L1，全部完成）

| 功能 | 做法 |
|---|---|
| **账单日历** | 月历热力图（按当日金额相对比例着色）+ 点日期看当日明细 + 12 个月分布条。以 `nextChargeDate` 为游标向前后展开，回溯**止步于 anchor**。 |
| **近 6 个月趋势** | 自绘柱状图（不引图表库）。用柱不用折线：月度支出是 6 个离散桶，折线会暗示不存在的中值。 |
| **搜索/筛选增强** | 金额 5 档、分类多选、4 种排序、"包含已结束"开关。按名字匹配 + 全部条件取交集。 |
| **2x2 小组件** | 单 provider 可 resize，2x2 ↔ 4x2 拖边角切换，布局自适应。 |
| **应用锁** | 生物识别解锁，回前台重新锁。不支持时给明确原因而非静默禁用。 |

### L2 增强（默认全部关闭）

| 功能 | 做法 |
|---|---|
| **预算与超支预警** | 用户自填上限，超支/接近上限（≥80%）时统计页显示进度条与提示。不"智能推荐"预算。 |
| **涨价预警** | 对比"最近一次实付金额"与"当前标价"，涨幅 ≥1% 才报。不用 `price_history` 表做检测——那个表只有用户主动勾选才有数据，流水覆盖率更高。 |
| **订阅体检** | 四条确定性规则：逾期 >2 周期 / 单条占月均 >30% / 一次性 >1 年 / 年付 60 天内续费。刻意只做**不需要权限**的版本。 |
| **多币种手动汇率** | 不联网，汇率用户自己填（1 外币 = 多少元）。**只在展示层折算**，原始数据不动，关掉立刻回原状。缺汇率时排除该条而不是当人民币硬算。 |

---

### 这一版修掉的两个真实 bug
1. **启动瞬间显示 ¥0.00**：`loadState` 为 Loading 时首页仍渲染了金额卡，看起来像"数据丢了"。改为只有 Ready 才渲染金额。
2. （上一版遗留）`Modifier.shadow + clip` 叠加在卡片内部画出亮块 —— 本版起全面改用 `nativeShadow`。

---

## 二、当前完成度

| 模块 | 状态 | 验证 |
|---|---|---|
| APK 构建 | **通过**，13.5 MB | 真实构建 |
| 周期引擎（9 周期、月末不漂移、闰日、向前回溯） | 完成 | 26 条单测 |
| 年化统计（整数分、四周付×13、误差上界 6 分） | 完成 | 12 条单测 |
| 状态派生（待确认永久保留） | 完成 | 5 条单测 |
| 提醒规划（提前 N 天 + 当天 + 过期 1/3/7 补发） | 完成 | 9 条单测 |
| 补记多期续费 | 完成 | 9 条单测 |
| 状态流转（暂停/恢复/取消/过期/试用/静音） | 完成 | 12 条单测 |
| 统计三口径 + 展开明细 + 差值洞察 + 多币种折算 | 完成 | 25 条单测 |
| 账单日历（年/月、热力、当月明细） | 完成 | 15 条单测 + 实机 |
| 搜索/筛选（金额/分类/排序） | 完成 | 13 条单测 + 实机 |
| 设置持久化 + 备份完整性 | 完成 | 6 条单测（含防漏字段守护） |
| 备份 v2（订阅+流水+**全部设置**） | 完成 | 31 条单测 |
| CSV 导入（逐行容错、真实行号、幂等） | 完成 | 18 条单测 |
| 深色模式（三态 + 持久化） | 完成 | 实机深浅对比 |
| 桌面小组件（4x2 / 2x2 可 resize） | 完成 | 实机添加 + 数据一致性 |
| Inter 字体子集（3 字重 96 KB，含 tnum） | 完成 | 字形校验 + 实机 |
| 应用锁（生物识别） | 完成 | 实机（模拟器无生物识别，降级路径已验证） |
| 预算 / 涨价 / 体检 / 多币种（默认关闭） | 完成 | 实机 + 26 条单测 |
| 云同步 / 自动识别 | **待用户决策** | 方案与实证见 `docs/L3-方案评估.md` |

**已验证**：`testDebugUnitTest` **289 条全绿**（v1.3.6，2026-10-28 实跑）；设备测试 **20 条全绿**（Round2Instrumentation，API 35 模拟器）；
权限 5 个（无 `INTERNET`）；所有主源码文件 ≤300 行。历史轮次的测试计数（207/228/259/278 条）为对应版本快照，不代表当前源码。

---

## 三、如何运行

1. 用 Android Studio 打开本目录（`subtrack-android/`），JDK 用 17。
2. 等待 Gradle Sync，运行 `app` 模块。

首次启动写入 6 条示例订阅（含 1 条已过期，用来展示"待确认"）。

**命令行构建**（正式入口是工程根目录的 `./gradlew`，Gradle 8.10.2 Wrapper 带官方分发 SHA-256 校验 `distributionSha256Sum`）：

```bash
./gradlew :app:assembleDebug          # Debug 构建
./gradlew :app:testDebugUnitTest      # 289 条 JVM 单测
./gradlew :app:lintDebug              # Lint（首次需联网解析 lint 依赖）
./gradlew :app:assembleRelease        # Release（无签名材料时输出 app-release-unsigned.apk）
```

**本机（macOS/中文路径）偏差绕法**：若工程路径含中文或本机 Gradle native 库不兼容，
用 `scripts/build-local.sh`（rsync 到 ASCII 副本 + 环境隔离后再调 `./gradlew`）。
细节见 `BUILD-NOTES.md` §6。Release 签名：设 `SUBTRACK_RELEASE_STORE_FILE/_PASSWORD/KEY_ALIAS/_PASSWORD`
环境变量或本地未跟踪的 `keystore.properties`，两者都缺时按未签名产物输出。

**设备测试 CI 唯一入口**（先启动 API 35 测试模拟器）：

```bash
python3 scripts/test-device-protocol.py
python3 scripts/device-ci.py --serial emulator-5554 --output artifacts/device-ci
```

该入口依次构建、安装 app/test APK、授予通知权限，再以 `am instrument -w -r` 执行所有已登记的测试。它核对每个测试的机器结果、完整完成记录、用例集合/计数及 failure/skip，不依赖 adb 的退出码；错误、零用例、崩溃、超时均失败。原始日志、JSON、JUnit XML 和已有构建报告在失败时也保留。远端 GitHub Actions 仍未运行；本地正向/隔离负向证据见 `../docs/FIX-REPORT-READINESS-2026-10-28.md`。

**电池优化入口**：健康卡“去设置”打开无需额外权限的系统电池优化列表。在“所有应用”里找到“订阅台账”，按需要选“不优化”；厂商页面不同会降级到应用详情或系统设置，并显示操作提示。所有入口都不可用时仍显示手动操作说明。返回应用会刷新健康状态。

应用**不声明也不请求** `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`，不使用直接豁免 action；旧报告中针对当前 APK 直接豁免权限用例的 Play 声明要求不再适用；这不等同于已完成 Play 上架审核，不承诺任何系统下的准时必达。旧报告关于保留 BatteryLife/直接豁免权限声明的理由由本轮纠正，历史文件保留供对照。

---

## 四、目录

```
app/src/main/java/com/subtrack/
├─ core/            AppClock（禁止裸用 LocalDate.now）、Money
├─ domain/
│  ├─ model/        Subscription / BillingCycleType / AppSettings
│  ├─ cycle/        CycleEngine（下一个扣费日）、RenewalEngine（补记多期）
│  ├─ stats/        Annualizer / StatsEngine（三口径+洞察+明细）/ MonthlyCashflow
│  ├─ state/        DisplayStateResolver（待确认派生）/ SubscriptionActions（状态流转唯一入口）
│  └─ repository/   SubscriptionRepository 接口 + PaymentRecord
├─ data/
│  ├─ db/           Room 实体 / DAO / Database
│  ├─ repository/   SubscriptionRepositoryImpl
│  ├─ settings/     SettingsStore（DataStore）
│  └─ backup/       BackupCodec（协议+计数）/ BackupMapping（字段映射）/ Json+JsonParser（手写 JSON）
│                   CsvImporter（逐行容错）/ CsvText（词法）/ CsvFields（字段解析）
├─ reminder/        ReminderPlanner / ReminderService / AlarmReminderScheduler / 四个 Receiver
├─ widget/          SubTrackWidget（Glance 4x2）/ SubTrackWidgetReceiver
├─ ui/
│  ├─ theme/        Type 字号阶梯 + 颜色 token
│  ├─ components/   Glass（玻璃件+原生阴影）/ Sheets（展开面板+确认框）
│  ├─ state/        LoadState / UiMessage / ListFilter
│  ├─ home/         HomeScreen（骨架）/ HomeCards（主数字卡+待确认）/ SubscriptionRows / HomeChrome（Tab）
│  ├─ stats/        StatsScreen / StatsCategories / TrendCard / EnhancementCards
│  ├─ editor/       EditorSheet / CycleAndDates / CategoryPicker / MoreSettings / FormControls / EditorFields
│  ├─ detail/DetailScreen.kt · reminders/ReminderCenterScreen.kt
│  ├─ SettingsScreen + SettingsWidgets · ScreenRouter · RootOverlays · Dialogs
│  ├─ calendar/     CalendarScreen / CalendarParts
│  ├─ lock/         AppLock / LockScreen
│  ├─ enhancement   EnhancementSettings / EnhancementWidgets / EnhancementState
│  └─ BackupController + BackupLaunchers（导出导入编排）
├─ MainActivity.kt / SubTrackApp.kt（手写容器，未接 Hilt）
app/src/test/      124 条领域测试（10 个文件）
app/src/main/res/drawable/  23 个 Phosphor 图标
app/src/main/res/font/      Inter 子集 3 字重（96 KB，含 tnum）
app/src/main/res/xml/       widget_info.xml（4x2）
```

---

## 五、已知边界

**v1 全量 + L1 + L2 已全部完成。** 以下是明确不做或需外部决策的：

| 项 | 状态 | 原因 |
|---|---|---|
| 云同步 / 多端 | **待用户决策** | 必须 `INTERNET`（安装期权限，无法运行时追加）。方案见 `docs/L3-方案评估.md`，推荐独立包名变体 |
| 自动识别（通知/短信） | **待用户决策** | 三条路径都会破坏"权限清单干净"；`READ_SMS` 在 Play 上记账类基本必拒 |
| 剪贴板粘贴识别 | 可行（实测通过） | 零权限替代方案，未实施（等决策） |
| 记账、家庭分摊、实时汇率 | 不做 | 定位漂移 / 需账号 / 需网络 |
| CSV 导入的自定义周期天数 | 会丢 | CSV 格式无该列；用 JSON 备份可完整保留 |
| 深色模式 / 小组件 / Inter / CSV 导入 | **已完成** | — |

---

## 六、设计约束（不要违背）

- 金额一律 `Long` 分，禁止 `Double/Float`；展示走 `Money.format`。
- 时间一律 `AppClock`，禁止业务代码 `LocalDate.now()`。
- 图标只走 `res/drawable/ic_*`（Phosphor），尺寸 16/20/24dp。
- **不要用 `Modifier.shadow(...)` 做卡片投影**，用 `Modifier.nativeShadow(...)`；两者叠加会在卡片内画出亮块。
- 不声明 `INTERNET`；`USE_EXACT_ALARM` 在清单里显式 `tools:node="remove"`。
- 单文件 ≤300 行；`domain` 不依赖 `android.*`。
- 颜色一律走 `SubTrackTheme.colors.xxx`（或兼容 getter），**不要在新代码里写 `Color(0x...)`**——那样深色模式会失效。
- 字体走 `Type` 对象里的样式，不要自己 new TextStyle(fontFamily = ...)，否则 tabular figures 会丢。
- 小组件**不许**引入快照表或定时刷新任务：它必须读同一个库。

---

## 七、构建产物

| 项 | 值 |
|---|---|
| 文件 | `SubTrack-v1.3.6-readiness-fixed-20261028.apk`（发布准备补修轮交付） |
| 大小 | 见交付报告（`../docs/FIX-REPORT-READINESS-2026-10-28.md`） |
| applicationId | `com.subtrack` |
| versionName / versionCode | 1.3.6 / 17 |
| minSdk / targetSdk / compileSdk | 26 / 35 / 35 |
| 合并后权限 | `POST_NOTIFICATIONS`、`SCHEDULE_EXACT_ALARM`、`RECEIVE_BOOT_COMPLETED`、`USE_BIOMETRIC`、`USE_FINGERPRINT`（5 个；Glance 带入的 `WAKE_LOCK`/`ACCESS_NETWORK_STATE`/`FOREGROUND_SERVICE` 已 `tools:node="remove"`，2026-10-28 已在 API 35 模拟器实测小组件渲染与 WorkManager 运行时无 SecurityException；**无 `INTERNET`**、无 `USE_EXACT_ALARM`） |
| SHA-256 | 见交付报告（以报告为准） |

> 生物识别两权限是应用锁所需（用户已确认接受）。若需回到 3 权限，移除应用锁功能即可。
>
> 桌面小组件：装好后在桌面长按 → 小组件 → 找「订阅台账」（按应用名排，不在 S 段）。
