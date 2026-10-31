# 本轮改动：五个问题修复 + 模型列表 + GitHub 发布与更新（2026-10-31 / v1.9.3 / 60）

## 0. 结论先写

| 用户问题 | 结论 | 证据 |
| --- | --- | --- |
| 详情页「已付总额」是 0 | 新建订阅时补记首期付款；详情页显示「¥68.00（1 笔）」 | 设备截图 `02-detail-paid-total.png`；JVM `RepositoryRoundtripTest` 两条；设备门禁 `EStageRegressionTest` 两条 |
| 编辑订阅日期框没对齐 | 两行日期统一固定宽度右对齐，「按周期重算」移到下方 | 截图 `01-editor-date-aligned.png` |
| 统计「实际口径待启用」没用 | 整块删除（无数据时不再出现空卡） | 截图 `07-stats-no-placeholder.png` |
| AI 未配置点「去设置里配置」没反应 | 跳转同时切到设置 Tab，直接落在「联网与 AI」 | 截图 `06-ai-cta-lands-on-settings.png`；设备用例 `ai_page_config_button_opens_network_settings` |
| 「在线 xxxx」看不懂 / 不是准确汇率 | 在线值改为「x.xxxx 元」；汇率改为多源真实接口，取当天数据 | 截图 `03-currency-online-rates.png` |
| 需要模型列表 | 「从服务获取模型列表」+ 多选/全选/全不选；AI 页可切换 | 截图 `04-model-picker.png`、`05-ai-model-chips.png` |
| 检查更新没弄好 | 接入 GitHub Releases，可真实检查/下载/校验/交给系统安装 | 截图 `08-update-found.png`、`09-update-verified.png` |

## 1. 已付总额：新建时补记首期付款

**原因**：流水只在「标记已续费 / 试用转正 / 通知确认 / 导入」时写入。新建订阅只保存订阅本身，所以
详情页的「已付总额」永远是 0——用户填了金额，却看不到自己付过的那一期。

**现在**（`domain/state/EditorSave.kt`）：
- 新建保存成功时，在**同一个 Room 事务**里按开始日期记一笔流水（金额＝订阅价，币种＝订阅币种）；
- 编辑保存不补记，避免改价/改日期被误当成新付款；
- 0 元订阅不写流水（0 元不是一次付款，写进去只会污染笔数）；
- 详情页在流水为 0 时写「还没有付款记录」，有流水时写「¥xx（N 笔）」，不再用 ¥0.00 误导。

同步语义不变：这笔流水走同一个 `SyncWriteGateway.payment`，在事务内入队；设备门的同步用例仍全绿。

## 2. 编辑订阅：日期行重新排版

**原因**：「下次扣费」一行右侧塞了「按周期重算」，把 `− / 日期 / +` 整体往左挤，两行错位。

**现在**：`Stepper` 固定 208dp 宽（± 各 48dp 命中区），两行右边界严格对齐；
「按周期重算」移到日期区下方的说明行，并说明「改日期不会重复记付款」。

## 3. 统计：删掉「实际口径待启用」

该卡片只在没有可比较数据时出现，不能点、没有信息，还让人以为功能坏了。
现在 `insight == null` 时整块不渲染；有数据时仍显示两口径差异洞察（AC-36b 逻辑未改）。

## 4. AI 未配置时的设置入口

**原因**：点击只改了 `settingsRoute`，没有切底部 Tab，界面仍停在 AI 页 —— 看起来「点了没反应」。

**现在**：`onNavigateSettings` 同时 `tab = 3`，落在「设置 → 联网与 AI」。

## 5. 汇率：真实数据 + 正确显示

两个问题：
1. **显示**：在线汇率是 1e9 定点，却被当成分单位格式化，7.1234 元被印成 `71234567.89`。
   现在 `SettingsSummaries.preciseRateText` 按 4 位小数显示，行文案是「手动 7.20 / 在线参考 7.1234 / 未设置」。
2. **来源**：原来只有 Frankfurter 一家（欧洲央行工作日参考价），且只有一个地址。
   现在按顺序回退，任何一家成功就用当天数据：
   - Frankfurter `api.frankfurter.dev`（CNY 基准）
   - ExchangeRate-API v6 `open.er-api.com`
   - ExchangeRate-API v4 `api.exchangerate-api.com`
   - Fawaz Ahmed 汇率表（jsDelivr CDN）
   都免注册、免 Key；失败保留上次数据并标记过期，不清空。自动刷新走同一条链路。
   自定义地址仍然优先，且只使用自定义那一个。

`FxCodec.decode` 现在兼容四种响应形状（`base/date/rates`、`base_code/time_last_update_unix/rates`、
小写嵌套 `{"cny":{...}}`），JVM 测试逐一锁住。

## 6. 模型列表

- 新增 `network/AiModels.kt`：由对话地址推导 `…/models`，解析 OpenAI（`data[].id`）与 Ollama（`models[].name`）。
- 设置页：「从服务获取模型列表」→ 弹层勾选，支持全选 / 全不选 / 逐个；保存后写 `aiModels`。
- AI 页：勾选超过 1 个时显示模型 chip，可随时切换当前模型；换服务地址会清空旧列表。
- 只使用已保存的地址与密钥，草稿不参与请求（沿用既有规则）。

## 7. 检查更新：GitHub Releases

- `network/GithubRelease.kt`：读取 `https://api.github.com/repos/nimeng1222/subtrack-android/releases/latest`；
  - 版本名必须来自 tag `v1.9.3`；
  - 版本码必须来自资产名 `SubTrack-<版本>-<版本码>.apk`，且资产名必须包含 tag 版本，**对不上直接拒绝**；
  - 下载地址必须是 GitHub 资产域名；跳转只允许 `github.com / objects.githubusercontent.com / release-assets.githubusercontent.com`，最多 3 跳，不携带 Authorization。
- `NetworkTransport`：默认仍不跟随任何重定向；只有更新下载显式传入白名单。
- `UpdateInstaller`：校验大小、包名、版本码必须更大、**证书与当前安装完全一致**；
  Release 带 `digest` 时额外校验 SHA-256（本次发布的资产带 `sha256:`，已实测通过）。
- 自签清单通道保留：填了清单地址就只走它，公钥没固定会明确报错，不会偷偷回落。
- 安装前若系统未允许「安装未知应用」，页面直接说明并给一键跳转系统设置，回来再确认安装。
- 全程不会静默安装，也不会卸载原应用。

## 8. 迁移与兼容

| 项 | 变化 | 老数据 |
| --- | --- | --- |
| Room schema | 无变化（仍为 v6） | 直接升级 |
| 设置 DataStore | 新增 `network_ai_models_v1`，缺省为空列表 | 缺失按空处理 |
| 已付总额 | 只影响**新创建**的订阅；编辑不补记 | 历史订阅保持原流水，不会凭空多一笔 |
| 在线汇率 | 旧缓存字段与精度不变，只是显示修好；下次刷新取当天数据 | 旧缓存仍可用，过期会提示 |
| 更新通道 | 新增 GitHub 通道；自签清单地址为空时启用 | 已填清单地址的用户不受影响 |
| 包名/签名 | `com.subtrack` / Debug `df3cced0…` | 可覆盖 1.9.0–1.9.2，保留数据 |

## 9. 交付物

| 项 | 值 |
| --- | --- |
| 仓库 | https://github.com/nimeng1222/subtrack-android （公开，`main`） |
| 发布 | https://github.com/nimeng1222/subtrack-android/releases/tag/v1.9.3 |
| APK | `SubTrack-1.9.3-60.apk`，15,195,205 字节 |
| SHA-256 | `5fc3535caa064f6a90be4a2c171b1a051405185e6833fbad61a13212b257be48`（与 Release 资产 `digest` 一致） |
| 版本/签名 | 1.9.3 / 60；`com.subtrack`；Debug 证书 `df3cced0…` |
| 覆盖升级 | 1.9.2(59) → 1.9.3(60)：`firstInstallTime` 未变（11:55:13） |

## 10. 验证

- JVM：**465 / 465**（新增 15 条：首期付款 2、汇率显示 2、四源解析 2、模型列表 3、Release 解析 6）。
- 设备严格门禁：**172 / 172**（清除应用数据后运行；`scripts/device-ci.py --validate-log` 校验 `ok: true`）。
  说明：中途两次非最终运行出现过时序抖动（累积数据导致已绑定账本对不上新起的服务端；一次 AI 慢响应用例超时），
  清除应用数据重跑后全绿；最终交付的这一次就是全绿结果。
- Lint：**0 error / 33 warning**（无新增于本轮改动文件的警告）。
- 实机：
  - 新建 ¥68 订阅 → 详情「¥68.00（1 笔）」；
  - 编辑页日期两行对齐；
  - 统计页无「实际口径待启用」；
  - AI 未配置 → 点按钮落在「联网与 AI」；
  - 在线汇率显示 USD 6.6529 / JPY 0.0423 / EUR 7.4327，来源 Frankfurter、日期 2026-10-30；
  - 模型列表取到 3 个，全不选→0、全选→3、保存后 AI 页出现 3 个 chip；
  - 1.9.2(59) 安装包点检查更新 → 发现 1.9.3(60) → 下载并校验通过（大小/哈希/版本/证书）→ 系统安装器。

## 11. 已知限制（如实写）

- 发布包是 **Debug 签名**：任何用同一张证书签名的安装包才能覆盖升级；换正式签名必须先卸载。
- GitHub 未登录 API 限流 60 次/小时；只在用户点「检查新版本」时请求一次，正常使用不会触发。
- 若某个 Release 的资产没有 `digest` 字段，下载后仍校验大小、版本码与签名证书，但没有远端哈希可比（本次资产带 digest）。
- 需要 JS 渲染/登录的网页仍抓不到，AI 会说明并换来源。
- 在带 Google Play 的机型上，侧载安装还会经过 Play Protect 的扫描/提示，这是系统行为，应用无法也不应该绕过；
  应用侧只负责把包校验到"大小、版本、证书、哈希都一致"再交给系统安装器。
