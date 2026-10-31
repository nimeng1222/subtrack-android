package com.subtrack.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.subtrack.BuildConfig
import com.subtrack.SubTrackApp
import com.subtrack.domain.model.AppSettings
import com.subtrack.domain.stats.CurrencyConverter
import com.subtrack.network.FxFetch
import com.subtrack.network.FxSources
import com.subtrack.network.NetworkTransport
import com.subtrack.ui.theme.Ink
import com.subtrack.ui.theme.InkSecondary
import com.subtrack.ui.theme.Type
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 币种与汇率。
 *
 * 改版前"手动汇率"藏在「增强功能」里、"在线汇率"藏在「联网服务」里，用户要开两种
 * 币种得在两个分组之间来回找，还看不出哪个优先。现在两条路径在同一页：
 * 先说明手动覆盖优先，再给手动编辑，最后才是在线参考与其数据日期。
 *
 * 语义保持不变：折算开关（multiCurrencyEnabled）与在线下载开关（network.fxEnabled）
 * 仍是两个独立开关——关掉在线下载不会影响已经保存的手动汇率。
 */
@Composable
internal fun CurrencySettingsScreen(
    settings: AppSettings,
    onUpdate: (AppSettings) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { (context.applicationContext as SubTrackApp).container.settingsStore }
    val transport = remember { NetworkTransport(BuildConfig.DEBUG) }
    val n = settings.network

    var editing by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var refreshStatus by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(Unit) { onDispose { job?.cancel() } }

    fun refreshOnline() {
        if (job?.isActive == true) return
        val source = n.fxBaseUrl
        val revision = n.connectionRevision
        refreshing = true
        refreshStatus = if (source.isBlank()) {
            "正在获取在线参考汇率（多个公共汇率源依次尝试）…"
        } else {
            "正在从自定义地址获取在线参考汇率…"
        }
        job = scope.launch {
            try {
                // 自定义地址优先；没填就按 FxSources 的顺序回退，任何一家成功即采用当天数据。
                val (quote, usedUrl) = FxFetch.fetch(transport, source)
                val container = (context.applicationContext as SubTrackApp).container
                container.settingsStore.updateNetwork { current ->
                    // 只有地址与连接代次都没变才落库：中途切换来源不能把旧响应写进来。
                    if (current.fxEnabled && current.fxBaseUrl == source && current.connectionRevision == revision) {
                        current.copy(
                            autoFxRates = quote.rates,
                            autoFxPreciseRates = quote.preciseRates,
                            fxBase = quote.base,
                            fxSource = usedUrl,
                            fxDate = quote.date,
                            fxFetchedAt = container.clock.nowMillis(),
                            fxAutoError = false,
                        )
                    } else {
                        current
                    }
                }
                refreshStatus = "已更新：${quote.date} 的数据 · ${FxSources.labelOf(usedUrl)}；手动汇率仍优先"
            } catch (e: CancellationException) {
                refreshStatus = "已取消，保留上次有效汇率"
                throw e
            } catch (e: Exception) {
                // 失败保留旧数据：宁可显示旧参考价 + 过期标记，也不清空成"未设置"。
                refreshStatus = "刷新失败：${e.message?.take(80) ?: "网络不可用"}；保留上次有效汇率"
            } finally {
                refreshing = false
            }
        }
    }

    val nowMillis = System.currentTimeMillis()
    val fetchedAt = n.fxFetchedAt
    val stale = SettingsSummaries.fxStale(fetchedAt, nowMillis)
    val onlineAvailable = n.fxEnabled && fetchedAt > 0L
    val manualCount = settings.fxRates.size

    SettingsPage(title = "币种与汇率", onBack = onBack, listTag = "currency-settings-list") {
        Column(Modifier.fillMaxWidth()) {
            SettingsStatusCard(
                tone = when {
                    !settings.multiCurrencyEnabled -> Tone.Muted
                    onlineAvailable && stale -> Tone.Warning
                    onlineAvailable -> Tone.Success
                    manualCount > 0 -> Tone.Neutral
                    else -> Tone.Warning
                },
                title = when {
                    !settings.multiCurrencyEnabled -> "多币种折算未开启"
                    onlineAvailable && stale -> "在线参考汇率已过期"
                    onlineAvailable -> "汇率可用"
                    manualCount > 0 -> "只用手动汇率"
                    else -> "还没有任何汇率"
                },
                body = when {
                    !settings.multiCurrencyEnabled -> "开启后，外币订阅会按汇率折算成人民币展示；原币金额与流水不会被改动。"
                    onlineAvailable -> "数据日期 ${n.fxDate.ifBlank { "未知" }} · ${SettingsSummaries.relativeDayLabel(fetchedAt, nowMillis)} · 来源 ${FxSources.labelOf(n.fxSource)}" +
                        (if (stale) "。参考价超过 48 小时，可能已变化。" else "。手动汇率优先于在线参考。")
                    manualCount > 0 -> "已维护 $manualCount 种手动汇率；没有在线参考数据，缺少汇率的币种不会参与折算。"
                    else -> "先编辑手动汇率，或打开下方在线参考后刷新一次。"
                },
            )
            Spacer(Modifier.height(14.dp))
        }

        Column(Modifier.fillMaxWidth()) {
            SettingsGroup(title = "折算") {
                SettingsToggleRow(
                    title = "多币种折算",
                    subtitle = "按汇率把外币金额折算成人民币展示；不改动原币与流水",
                    checked = settings.multiCurrencyEnabled,
                    tag = "toggle-multi-currency",
                    onToggle = { onUpdate(settings.copy(multiCurrencyEnabled = it)) },
                )
            }
        }

        Column(Modifier.fillMaxWidth()) {
            SettingsGroup(
                title = "手动汇率",
                footer = "点任意一行可以自己填一个固定汇率（优先于在线参考）；" +
                    "没有手动值时显示的是在线参考的当天价，保留 4 位小数。例：美元 7.20 表示 1 美元 ≈ 7.20 元。",
            ) {
                CurrencyConverter.SUPPORTED.filter { it.first != "CNY" }.forEachIndexed { index, (code, name) ->
                    if (index > 0) SettingsDivider(withIcon = false)
                    val manual = settings.fxRates[code]
                    // 在线值优先用 1e9 定点（更精确）；没有定点时才回落到按分保存的兼容值。
                    val online = if (n.fxEnabled) {
                        n.autoFxPreciseRates[code]?.takeIf { it > 0L }
                            ?: n.autoFxRates[code]?.let { it * 10_000_000L }
                    } else null
                    SettingsValueRow(
                        title = "$name $code",
                        value = SettingsSummaries.rateRowValue(manual, online),
                        tag = "fx-row-$code",
                        valueColor = if (manual != null) Ink else InkSecondary,
                        onClick = { editing = code },
                    )
                }
            }
        }

        Column(Modifier.fillMaxWidth()) {
            SettingsGroup(title = "在线参考汇率") {
                SettingsToggleRow(
                    title = "启用在线参考",
                    subtitle = "只发送币种代码，不发送台账",
                    checked = n.fxEnabled,
                    tag = "toggle-fx-online",
                    onToggle = { value -> scope.launch { store.updateNetwork { it.copy(fxEnabled = value) } } },
                )
                SettingsDivider(withIcon = false)
                SettingsToggleRow(
                    title = "自动刷新",
                    subtitle = "应用运行期间每日至多一次；失败保留上次数据",
                    checked = n.fxAutoEnabled,
                    enabled = n.fxEnabled,
                    tag = "toggle-fx-auto",
                    onToggle = { value -> scope.launch { store.updateNetwork { it.copy(fxAutoEnabled = value) } } },
                )
                SettingsDivider(withIcon = false)
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(
                        if (fetchedAt > 0L) {
                            "数据日期 ${n.fxDate.ifBlank { "未知" }} · ${SettingsSummaries.relativeDayLabel(fetchedAt, nowMillis)} · 来源 ${FxSources.labelOf(n.fxSource)}"
                        } else {
                            "还没有获取过在线参考汇率"
                        },
                        style = Type.footnote,
                        color = InkSecondary,
                        modifier = Modifier.testTag("fx-data-date"),
                    )
                    if (n.fxAutoError) {
                        Spacer(Modifier.height(4.dp))
                        Text("上次自动刷新失败，已保留旧数据。", style = Type.caption, color = com.subtrack.ui.theme.Warning)
                    }
                    Spacer(Modifier.height(10.dp))
                    SettingsButton(
                        text = if (refreshing) "正在刷新…" else "刷新在线汇率",
                        onClick = { refreshOnline() },
                        enabled = n.fxEnabled && !refreshing,
                        tag = "fx-refresh",
                    )
                    if (job?.isActive == true) {
                        Spacer(Modifier.height(8.dp))
                        SettingsButton("取消刷新", onClick = { job?.cancel() }, style = ActionStyle.Secondary, tag = "fx-refresh-cancel")
                    }
                    refreshStatus?.let { message ->
                        Spacer(Modifier.height(8.dp))
                        Text(message, style = Type.caption, color = InkSecondary, modifier = Modifier.testTag("fx-refresh-status"))
                    }
                }
            }
        }

        Column(Modifier.fillMaxWidth()) {
            SettingsHelp("高级：自定义汇率来源") {
                SettingsParagraph("默认依次尝试 Frankfurter、ExchangeRate-API、Fawaz Ahmed 等公共免费源：都无需注册；能拿到当天数据就用当天，拿不到就保留上一次并标记过期。这些是参考价，不是银行卡实际成交价。")
                SettingsParagraph("本次使用：${n.fxSource.ifBlank { "尚未获取" }}（${if (n.fxSource.isBlank()) "—" else FxSources.labelOf(n.fxSource)}）")
                SettingsParagraph("汇率精度：在线数据按 1e9 定点保存；手动汇率以「分」为整数保存，不引入浮点误差。")
                SettingsParagraph("统计口径：缺少汇率的币种不会参与折算汇总，统计页会单独提示，而不是按 1:1 相加。小组件沿用同一折算口径。")
                var urlDraft by remember(n.fxBaseUrl) { mutableStateOf(n.fxBaseUrl) }
                val dirty = urlDraft.trim() != n.fxBaseUrl
                SettingsTextField(
                    value = urlDraft,
                    onValueChange = { urlDraft = it },
                    label = "汇率接口地址（可选）",
                    placeholder = "留空使用默认公共源",
                    helper = if (dirty) "有未保存的修改" else "保存后只影响之后的刷新",
                    tag = "fx-base-url",
                )
                SettingsButton(
                    text = "保存地址",
                    onClick = {
                        val next = urlDraft.trim()
                        scope.launch {
                            store.updateNetwork {
                                it.copy(
                                    fxBaseUrl = next,
                                    fxSource = "",
                                    fxFetchedAt = 0L,
                                    fxDate = "",
                                    connectionRevision = it.connectionRevision + 1,
                                )
                            }
                        }
                    },
                    style = ActionStyle.Secondary,
                    enabled = dirty,
                    tag = "fx-base-url-save",
                )
                Spacer(Modifier.height(6.dp))
                SettingsButton(
                    text = "取消修改",
                    onClick = { urlDraft = n.fxBaseUrl },
                    style = ActionStyle.Secondary,
                    enabled = dirty,
                )
            }
        }
    }

    editing?.let { code ->
        FxRateSheet(
            code = code,
            currentMinor = settings.fxRates[code],
            onDismiss = { editing = null },
            onCommit = { raw ->
                val error = commitRate(code, raw, settings, onUpdate)
                if (error == null) editing = null
                error
            },
        )
    }
}

@Composable
private fun FxRateSheet(
    code: String,
    currentMinor: Long?,
    onDismiss: () -> Unit,
    onCommit: (String) -> String?,
) {
    var input by remember(code) { mutableStateOf(currentMinor?.let { yuanText(it) } ?: "") }
    var error by remember(code) { mutableStateOf<String?>(null) }
    val name = CurrencyConverter.nameOf(code)
    SettingsSheet(title = "手动汇率 · $name（$code）", onDismiss = onDismiss, actions = {
        SettingsButton(text = "保存", onClick = { error = onCommit(input) }, tag = "fx-save")
        Spacer(Modifier.height(8.dp))
        SettingsButton("取消", onClick = onDismiss, style = ActionStyle.Secondary)
        if (currentMinor != null) {
            Spacer(Modifier.height(8.dp))
            SettingsButton(
                text = "清除这一项",
                onClick = { error = onCommit("") },
                style = ActionStyle.Secondary,
                tag = "fx-clear",
            )
        }
    }) {
        SettingsTextField(
            value = input,
            onValueChange = { raw -> input = raw.filter { it.isDigit() || it == '.' }; error = null },
            label = "1 $code 等于多少元",
            placeholder = "例如 7.20",
            helper = "手动汇率优先于在线参考；留空表示不使用手动汇率。",
            error = error,
            keyboardType = KeyboardType.Decimal,
            tag = "fx-input",
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "当前：${currentMinor?.let { yuanText(it) } ?: "未设置"}",
            style = Type.caption,
            color = Ink,
        )
    }
}
