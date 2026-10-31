# 网络配置与维护（v1.5.8）

本轮是可审阅开发预览；本轮验收修复与剩余项见 ../docs/FIX-REPORT-NETWORK-v158-ACCEPTANCE-2026-10-29.md，历史报告保留。无付费调用、无公开部署、无远端推送、无 APK 发布。版本 1.8.4 / 55，com.subtrack，Room schema 4。

## 构建

沿用 README 的 JDK17 / Gradle Wrapper / SDK35。macOS 中文路径：

```sh
HOME=/private/tmp/build-home SUBTRACK_GRADLE_USER_HOME=/private/tmp/gh \
SUBTRACK_BUILD_DIR=/private/tmp/subtrack-network-v14-work \
bash scripts/build-local.sh --offline :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug :app:assembleDebugAndroidTest
```

缓存不存在时去掉 --offline 并安装 SDK/JDK，不假定 /tmp 目录永久存在。产物在脚本输出的 ASCII 构建目录。无正式签名材料时仅 unsigned Release，不生成假正式证书。Debug 沿用本机已有证书，与已验收 v1.3.6 同证书。

## 按功能配置

- 设置→联网服务：所有开关默认关闭，配置地址必须完整 URL。Release 仅 HTTPS，平台验证系统证书。debug 额外仅允许 10.0.2.2、localhost、127.0.0.1 的 HTTP，不允许任意 LAN 明文或 trust-all。
- 模板：启用后填 https://your-host/v1/templates，或 HTTPS 静态 templates.json 地址。server/README.md 提供服务端和静态部署数据。新建页浏览/搜索、刷新、采用到可编辑草稿；金额未知保持空，必须手填才可保存。离线内置与缓存明确区分，不改已有记录，不生成付款。
- 汇率：启用并手动刷新，留空地址使用 https://api.frankfurter.dev/v1/latest?base=EUR。公共源无 Key，无日/月配额但有防滥用限流，无 SLA；缓存鼓励，商业/再分发需核对 https://frankfurter.dev/license/ 及底层提供方条件。v1 ECB 参考价非成交价。**在线汇率已用 1e-9 定点高精度（`FxPrecision`/`FxRateTable.preciseRates`）表示，普通 Map 旧视图不参与折算，最终显示才舍入**；真实免费源 JPY10,000→CNY 422.02 已留证。数据日期/获取时间持久保存；手动汇率优先于自动。自动刷新为独立可选开关，**仅在 App 进程存活期间最多每日一次**，不是后台每日任务，关闭/改源会取消；失败保留最后有效值，无有效汇率明确缺失，不当作 1 或 0。统计、明细、预算与 launcher 小组件共用同一合并输入。**真实 launcher AppWidget 渲染已实测**：桌面绑定后显示预计月均/年度/近期到期，数值与 App 主界面一致；追加台账后自动更新（¥419.25→¥499.25、¥5,031→¥5,991），见 `../docs/feature-report-network-resumed-20261029/n2-widget-render/`。
- 更新：开关与签名清单地址；构建前设置 SUBTRACK_UPDATE_PUBLIC_KEY 为归属明确的 RSA 公钥 X509 DER base64，默认空则明确无可信发布配置。签名使用 SHA256withRSA，信封 payload 是原始 JSON 字节 base64，signature 是签名字节 base64。字段见 UpdateVerifier。生产 root 不可从同一响应中下载，不可用临时测试根冒充。仅直接 APK 分发声明 REQUEST_INSTALL_PACKAGES：用户主动下载、校验后点系统安装器，系统要求“允许此来源”及确认。Play 分发应改商店路径并移除此权限。本轮相同 signer 集合才允许，尚不支持合法轮换证书链。**真实设备矩阵已跑**：隔离 debug 构建 + 一次性测试 CA/清单密钥 + 真实 loopback HTTPS（127.0.0.1:18443）下 79/79 通过（清单验签、版本码、下载哈希、同证书策略、错误证书拒绝、慢链路取消，见 `../docs/feature-report-network-resumed-20261029/e4-isolated-matrix/`）；并实测同证书覆盖升级 `36→38` 后订阅/流水/墓碑逐字段保留、错误签名包被系统以 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` 拒绝（`e4-installer-upgrade/`）。**系统 PackageInstaller 人机确认已实测**：生产更新界面检查/下载/校验后交付系统安装器，弹真实“Do you want to update this app? [Cancel] [Update]”框，点 Update → `InstallSuccess` → `versionCode 37→38` 且台账逐字段保留（`e4-installer-interactive/`）。**“未知来源安装”系统授权也已实测**：未授权时系统弹限制提示 → 进入系统“Install unknown apps”页手动打开“Allow from this source”（`appops default→allow`）→ 重跑后确认安装成功（`e4-installer-unknown-sources/`）。生产设置页四种撤销/取消（清除连接、关开关、改地址、离开页面）单跑通过（`e4-isolated-matrix/device-update-ui.log`）。**仍无发布级证据**：正式清单私钥与分发渠道未提供（禁止造假正式签名），合法证书轮换链产品未支持，公网发布不在授权内；隔离测试 CA/密钥只存在于 `/private/tmp`，Release 不信任测试根。
- AI：完整 OpenAI-compatible /v1/chat/completions 地址、模型与 BYOK。Key 使用 Android Keystore AES-GCM + noBackupFilesDir，修改服务地址立即清除旧 Key，防止误发至新地址。连接测试与分析是一次用户主动请求，无自动付费调用。参考协议 https://platform.openai.com/docs/api-reference/chat/create。**N5 新增可追溯价格检索/分析核心**（`network/search/` + `domain/analysis/`）：可配置检索适配器（确定性夹具、用户材料、真实 HTTP 三种，HTTP 适配器默认关闭且无 Key 直接拒绝、零联网）、SSRF 安全抓取（仅 https、禁重定向、阻断环回/私网/链路本地/云元数据、响应大小与超时上限）、逐报价证据绑定（服务/地区/套餐/币种/周期/税费/优惠/权益/来源类型/页面日期/实际检索时间/核验状态）、本地确定性金额复算与冲突/过期/不可比显式呈现、比较/节省模拟与"采用到编辑草稿"。未配置在线检索 Key，真实 HTTP 适配器未连付费检索服务；**已在设备上用生产 `RestrictedFetcher` 实抓真实免费公开 API（Steam Store appdetails，无 Key）**验证真实网络链路与“不可比即拒绝/反伪造”语义（游戏买断价，不是订阅官方价，见 `../docs/feature-report-network-resumed-20261029/n5-real-source/`）；实时模型抽取未接入该流水线（现有 AiProtocol/AiPriceDialog 材料分析路径保留）。夹具仅测试数据，绝非真实或当前官方价格。
- 同步：**产品入口为“实验，默认收起且不上传”**（未创建/加入账本前仓库写入仍是直通；进入该区需用户显式操作）。已具备 AES-GCM/AAD、KeyStore 包装、vault/epoch 隔离、Room outbox/接收/checkpoint 事务原语、因果向量钟纯算法；并有加密业务操作 payload/schema（`sync/BusinessOperation.kt`）、每 vault 串行协调器（`sync/SyncCoordinator.kt`：有界上传重试、响应丢失幂等、分页下载、限流协作）、**持久化因果 heads（Room v3 `sync_entity_head` + `MIGRATION_2_3`）**、**仓库写入经 `SyncWriteGateway` 在同一 Room 事务写 ledger+outbox+head**（`SubscriptionRepositoryImpl` 的 upsert/archive/recordPayment 已接，默认直通不影响同步关闭），`SyncEngine.enqueueBusiness/coordinator` 生产绑定；设备用例 `SyncWriteDeviceTest` 证明原子提交/墓碑/失败回滚。**远端 apply 分支 `sync/SyncBusinessApplier.kt`**：按因果序应用解密业务操作（更新/墓碑），并发只落冲突 head 不覆盖本地值，直接写 DAO 不回传。**续费/通知确认/导入批量写入**（`recordPayments`/`insertPayments`）逐笔经 gateway 在同一事务写 ledger+outbox（`SyncBatchWriteDeviceTest`）。**同步状态读模型** `sync/SyncStatusRepository.kt`（设备ID/密钥代次/最近同步修订/待上传数/未解决冲突数）。**冲突解决核心**（Room v4 `sync_conflict` 持久化未应用载荷）：`resolveKeepRemote`/`resolveKeepLocal` 单事务完成 ledger+heads+冲突簿记；**v1.5.8 修复真实缺陷**——付款冲突“采用远端”此前因 `payment_record` 唯一索引 `(subscriptionId, paidAtEpochDay)` + `@Insert(IGNORE)` 被静默丢弃（返回成功但账本仍是本机金额），现改为先 `deleteByPeriod` 再插入远端行，并有设备回归用例。**跨设备续费幂等**（同期同额不重复）与**重复付款冲突**（同期异额显式记录冲突）已实现（`SyncPaymentIdempotencyDeviceTest`）。**提醒设备角色门控**（`SyncReminderRole` + `ReminderService.shouldSchedule`，非提醒设备撤销待发计划）与**密钥轮换原语**（新 epoch 数据密钥入 KeyStore、保留旧 epoch 可解旧密文，`SyncKeyRotationDeviceTest`）已具备。`ui/network/SyncConflictPanel.kt`（保留本机/采用远端）与 `ui/network/SyncSettingsPanel.kt` 保留为组件级视图；**生产入口**为 `ui/network/SyncProductionSection.kt`（网络设置页内）。**真实服务端双客户端端到端**：`SyncE2EDeviceTest`（单模拟器上两个独立 Room DB + 两个设备凭据 + 真实 SyncEngine/服务端）跑通上传→下载应用→回传→收敛→墓碑传播→撤销后拒绝，并含多 op 排队与批量上传/多页下载。**两台独立 Android 实例矩阵**（新建 AVD + 原模拟器并行，各自独立库/凭据，真实 `/v1/pair` 配对）已跑通 9 阶段并两端收敛一致，见 `../docs/feature-report-network-resumed-20261029/n4-two-device/`；仍非两台实体手机，恢复材料由宿主带外搬运（产品化分发 UI 未做）。**产品接线（本轮 F8）**：AppContainer 现在通过 `SwitchableSyncWriteGateway` 把仓库写入接到真实 outbox 路径（默认直通，创建/加入账本后启用）；网络设置页新增“多设备同步（实验，默认不上传）”区，可创建账本/用配对凭据+恢复材料加入/初次本地数据合并预览与显式上传/立即同步/状态/冲突解决（决定会作为新操作发布）/设备列表与撤销/密钥轮换（显示新恢复材料）/停用。入口默认收起且不自动上传；两台独立模拟器实例已按此产品路径跑通矩阵（见修复报告）。

## 隐私与故障

模板/汇率/更新请求不携带台账；AI 只携带预览字段。网络配置、凭据、自动汇率缓存不进入旧 JSON/CSV 备份，恢复备份保留当前网络配置。清除连接会删除本机密钥/配置，但不等于撤销远端访问。模板缓存不含私账，手工删应用缓存/文件可清理；默认 Android 系统备份不包含 noBackupFilesDir 凭据。

单请求 8 秒连接、15 秒读取、25 秒总时限，默认 1 MiB 响应（模型 64 KiB、APK 最大64 MiB），不跟重定向，不自动重试；同地址至少间隔2秒，用户可取消。失败保留旧缓存与明确错误。当前没有自动刷新/后台网络 Worker，不恢复 WAKE_LOCK。

## 本地验证

```sh
cd server && python3 -m unittest -v test_server.py    # 10 项
cd ..
bash scripts/verify-all.sh                            # 一键：构建+JVM 406+Lint+服务端10+协议9+E4 host4
RUN_DEVICE=1 ANDROID_SERIAL=emulator-5554 bash scripts/verify-all.sh   # 追加全量设备门禁（当前 75 项）
```

分步（设备门禁需 fixture + 本机同步服务 + `adb reverse`）：

```sh
python3 scripts/network-fixture.py &                  # 127.0.0.1:18080
( cd server && SUBTRACK_DATA=/private/tmp/subtrack-e2e-sync.sqlite SUBTRACK_BIND=127.0.0.1 PORT=18450 \
    SUBTRACK_SETUP_TOKEN=<测试用固定 token> SUBTRACK_DEBUG_HTTP=1 python3 app.py ) &   # N4 端到端用例用
adb reverse tcp:18080 tcp:18080
adb reverse tcp:18450 tcp:18450
python3 scripts/test-device-protocol.py               # 9 项
python3 scripts/device-ci.py --serial emulator-5554 --output artifacts/device-ci   # 校验设备日志 75/75
```

F6：`RestrictedFetcher` 现在只用校验过的地址集合拨号（域名仅用于 TLS SNI/主机名校验），DNS 重绑定无法把连接改到内网；取消/超时会关闭 socket。fixture 新增 `/search/web`（Brave 兼容，校验 `X-Subscription-Token`）与 `/model/chat`（OpenAI 兼容，校验 `Authorization: Bearer`）两个受控协议端点；fixture 只是受控协议/故障端点，不是模型、搜索或官方价格源。测试会在专用目标安装/写测试数据，禁止指向用户日常设备；原 CI 严格检查每例结果和完整结束，不靠 adb 退出码。GitHub Actions 配置已加 fixture 和 server tests，但未在远端运行。
