package com.subtrack.ui.network

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.subtrack.BuildConfig
import com.subtrack.domain.analysis.*
import com.subtrack.domain.model.AiPriceReference
import com.subtrack.domain.model.AppSettings
import com.subtrack.network.NetworkTransport
import com.subtrack.network.search.*
import com.subtrack.ui.theme.Type
import java.math.BigDecimal
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private fun money(minor: Long, currency: String): String =
    "$currency ${BigDecimal.valueOf(minor).movePointLeft(2).setScale(2).toPlainString()}"

/**
 * N5 可追溯价格检索与分析（核心 UI）。
 *
 * 流程：用户主动填条件 → 选检索方式（夹具 / 用户材料 / 在线 HTTP 默认关闭）
 * → 检索 → 逐报价展示来源、页面日期、实际检索时间、核验状态与证据原文
 * → 本地确定性复算比较/节省模拟 → 逐项“采用到编辑草稿”（只是建议，需再次确认，绝不自动保存/扣费）。
 */
@Composable
internal fun PriceRetrievalDialog(settings: AppSettings, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var service by remember { mutableStateOf("") }
    var region by remember { mutableStateOf("") }
    var plan by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf("USD") }
    var yearly by remember { mutableStateOf(false) }
    var taxTerms by remember { mutableStateOf("before tax") }
    var discountTerms by remember { mutableStateOf("no promotion") }
    var benefitTerms by remember { mutableStateOf("same benefits") }
    var newUserOnly by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf("fixture") }
    var url by remember { mutableStateOf("") }
    var material by remember { mutableStateOf("") }
    var httpEndpoint by remember { mutableStateOf("") }
    var httpKey by remember { mutableStateOf("") }
    var httpEnabled by remember { mutableStateOf(false) }
    // F5: model extraction is a separate BYOK endpoint; its key is only ever sent to this endpoint.
    var modelEndpoint by remember { mutableStateOf(settings.network.aiBaseUrl) }
    var modelName by remember { mutableStateOf(settings.network.aiModel) }
    var modelKey by remember { mutableStateOf("") }
    var useModel by remember { mutableStateOf(false) }

    var consent by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("未检索") }
    var report by remember { mutableStateOf<PriceAnalysisReport?>(null) }
    var comparisonText by remember { mutableStateOf("") }
    var savingsText by remember { mutableStateOf("") }
    var adopt by remember { mutableStateOf<StructuredPriceQuote?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(Unit) { onDispose { job?.cancel() } }
    // O7: EVERY input that shapes the request (query, conditions, sources/material, consent, retrieval/model config)
    // belongs to the request version. Any change cancels the in-flight request and invalidates its pending result.
    var requestVersion by remember { mutableStateOf(0) }
    LaunchedEffect(
        service, region, plan, currency, yearly, taxTerms, discountTerms, benefitTerms, newUserOnly,
        mode, url, material, consent, httpEndpoint, httpKey, httpEnabled, modelEndpoint, modelName, modelKey, useModel,
    ) {
        requestVersion++
        if (job?.isActive == true) job?.cancel()
        job = null
        status = ""
        report = null
        comparisonText = ""
        savingsText = ""
    }

    fun request(): PriceQueryRequest = PriceQueryRequest(
        service = service.trim(), region = region.trim(), plan = plan.trim(),
        currency = currency.trim().uppercase(), period = if (yearly) PricePeriod.YEARLY else PricePeriod.MONTHLY,
        taxTerms = taxTerms.trim(), discountTerms = discountTerms.trim(), benefitTerms = benefitTerms.trim(),
        newUserOnly = newUserOnly,
    )

    AlertDialog(onDismissRequest = { job?.cancel(); onClose() }, title = { Text("可追溯价格检索与分析") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("只发送完成查询所需字段；不上传台账、备注或支付记录。夹具仅供测试，绝非真实现价。", style = Type.caption)
            ConfigField("服务", service, "n5-service") { service = it; report = null }
            ConfigField("地区", region, "n5-region") { region = it; report = null }
            ConfigField("套餐", plan, "n5-plan") { plan = it; report = null }
            ConfigField("币种 ISO", currency, "n5-currency") { currency = it.uppercase().take(3); report = null }
            Row { Checkbox(yearly, { yearly = it; report = null }, modifier = Modifier.testTag("n5-yearly")); Text(if (yearly) "年付" else "月付") }
            ConfigField("税费条件", taxTerms, "n5-tax") { taxTerms = it; report = null }
            ConfigField("优惠条件", discountTerms, "n5-discount") { discountTerms = it; report = null }
            ConfigField("权益条件", benefitTerms, "n5-benefit") { benefitTerms = it; report = null }
            Row { Checkbox(newUserOnly, { newUserOnly = it; report = null }, modifier = Modifier.testTag("n5-newuser")); Text("仅新用户优惠") }

            Row {
                TextButton(modifier = Modifier.testTag("n5-mode-fixture"), onClick = { mode = "fixture"; report = null }) { Text(if (mode == "fixture") "● 夹具" else "夹具") }
                TextButton(modifier = Modifier.testTag("n5-mode-user"), onClick = { mode = "user"; report = null }) { Text(if (mode == "user") "● 用户材料" else "用户材料") }
                TextButton(modifier = Modifier.testTag("n5-mode-http"), onClick = { mode = "http"; report = null }) { Text(if (mode == "http") "● 在线HTTP" else "在线HTTP") }
            }
            Text(
                when (mode) {
                    "fixture" -> "夹具模式：确定性测试数据，不是真实/当前价格。"
                    "user" -> "用户材料模式：只分析你粘贴的 URL/文本，未进行在线核验，不抓取网页。"
                    else -> "在线 HTTP 模式：默认关闭；未配置检索 Key 不发起任何真实请求（会直接失败）。"
                },
                style = Type.caption,
            )
            if (mode != "fixture") {
                ConfigField("来源 HTTPS URL（仅标识，不自动抓取）", url, "n5-url") { url = it }
                OutlinedTextField(material, { if (it.length <= 12000) material = it },
                    modifier = Modifier.testTag("n5-material"), label = { Text("粘贴来源文本") })
                Row { Checkbox(consent, { consent = it }, modifier = Modifier.testTag("n5-approve")); Text("确认仅发送以上字段，不含台账") }
            }
            if (mode == "http") {
                ConfigField("检索服务 HTTPS 接口（默认关闭）", httpEndpoint, "n5-http-endpoint") { httpEndpoint = it }
                ConfigField("检索服务 Key（默认为空，不发起请求）", httpKey, "n5-http-key", secret = true) { httpKey = it }
                Row { Checkbox(httpEnabled, { httpEnabled = it }, modifier = Modifier.testTag("n5-http-enabled")); Text("启用真实在线检索（会产生第三方费用）") }
                Text("模型抽取（可选，OpenAI-compatible /v1/chat/completions）", style = Type.caption)
                ConfigField("模型接口 HTTPS 地址", modelEndpoint, "n5-model-endpoint") { modelEndpoint = it }
                ConfigField("模型名", modelName, "n5-model-name") { modelName = it }
                ConfigField("模型 Key（只发给模型接口）", modelKey, "n5-model-key", secret = true) { modelKey = it }
                Row { Checkbox(useModel, { useModel = it }, modifier = Modifier.testTag("n5-use-model")); Text("使用模型抽取检索摘要") }
                Text("模型只看到本次检索摘要与查询条件；金额/周期/来源仍由本地校验器裁决。", style = Type.caption)
            }
            TextButton(modifier = Modifier.testTag("n5-run"), enabled = job?.isActive != true && (mode == "fixture" || consent),
                onClick = {
                    val query = request()
                    val adapter: PriceRetrievalAdapter = when (mode) {
                        "fixture" -> FixturePriceRetrievalAdapter()
                        "user" -> UserProvidedSourceAdapter(url.trim(), material, System.currentTimeMillis())
                        else -> HttpSearchRetrievalAdapter(
                            HttpSearchConfig(
                                endpoint = httpEndpoint.trim(), apiKey = httpKey, enabled = httpEnabled,
                                allowLocalDebug = BuildConfig.DEBUG,
                            ),
                            RestrictedFetcher(debug = BuildConfig.DEBUG),
                        )
                    }
                    val modelConfig = ModelExtractionConfig(
                        endpoint = modelEndpoint.trim(), model = modelName.trim(), apiKey = modelKey,
                        enabled = useModel && modelKey.isNotBlank(), allowLocalDebug = BuildConfig.DEBUG,
                    )
                    val extractor: QuoteExtractor = if (modelConfig.configured) {
                        CompositeQuoteExtractor(
                            listOf(DefaultQuoteExtractor, ModelQuoteExtractor(modelConfig, NetworkTransport(BuildConfig.DEBUG))),
                        )
                    } else {
                        DefaultQuoteExtractor
                    }
                    val version = requestVersion
                    job = scope.launch {
                        status = "检索中"; report = null; comparisonText = ""; savingsText = ""
                        try {
                            val next = PriceAnalysisPipeline(extractor).run(adapter, query, System.currentTimeMillis())
                            // O7: a result produced for an older request version must never be published or adopted.
                            if (version != requestVersion) return@launch
                            report = next
                            status = next.statusMessage
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            status = "失败：${e.message?.take(160)}"
                        }
                    }
                }) { Text("检索并分析") }
            Text(status, style = Type.footnote, modifier = Modifier.testTag("n5-status"))

            report?.let { current ->
                Text("来源文档 ${current.documents.size} 个；${current.verifiedLabel}", style = Type.caption)
                if (current.isFixture) Text("⚠ 夹具数据，仅供测试，不代表真实或当前官方价格。", style = Type.caption)
                current.accepted.forEach { quote ->
                    Text(
                        buildString {
                            append("${quote.service} / ${quote.plan} / ${quote.region}\n")
                            append("${money(quote.amountMinor, quote.currency)} / ${if (quote.period == PricePeriod.MONTHLY) "月付" else "年付"}\n")
                            append("税费：${quote.taxTerms}；优惠：${quote.discountTerms}；权益：${quote.benefitTerms}")
                            if (quote.newUserOnly) append("；仅新用户")
                            append("\n来源类型：${quote.sourceType}；${quote.sourceUrl}")
                            append("\n页面日期：${quote.pageDate ?: "未知"}；实际检索：${Instant.ofEpochMilli(quote.retrievedAtMillis)}")
                            append("\n核验状态：${quote.verification.zhLabel()}")
                            append("\n证据：${quote.evidenceText}")
                            if (quote.notes.isNotEmpty()) append("\n注：${quote.notes.joinToString("；")}")
                        },
                        style = Type.footnote, modifier = Modifier.testTag("n5-quote"),
                    )
                    TextButton(modifier = Modifier.testTag("n5-adopt"), onClick = { adopt = quote }) { Text("采用到编辑草稿（建议，需再次确认）") }
                }
                if (current.rejected.isNotEmpty() || current.issues.isNotEmpty()) {
                    Text(
                        "问题与拒绝：\n" + (current.rejected + current.issues).joinToString("\n") { "${it.type}：${it.detail}" },
                        style = Type.caption, modifier = Modifier.testTag("n5-issues"),
                    )
                }
                if (current.accepted.size >= 2) {
                    TextButton(modifier = Modifier.testTag("n5-compare"), onClick = {
                        val c = DeterministicRecompute.compare(current.accepted[0], current.accepted[1])
                        comparisonText = if (c.comparable) {
                            "前两项年化：${money(c.firstAnnualMinor!!, c.currency)} vs ${money(c.secondAnnualMinor!!, c.currency)}；差额 ${money(c.annualDifferenceMinor!!, c.currency)}。${c.note}"
                        } else "不可比：${c.reason}"
                    }) { Text("本地复算前两项年化差额") }
                }
                if (current.accepted.isNotEmpty()) {
                    TextButton(modifier = Modifier.testTag("n5-savings"), onClick = {
                        val s = DeterministicRecompute.savings(current.accepted)
                        savingsText = if (s.comparable) {
                            "取消 ${s.selectedCount} 项预计年少支出 ${money(s.estimatedAnnualAvoidedMinor!!, s.currency)}。${s.note}"
                        } else "无法模拟：${s.reason}"
                    }) { Text("本地复算取消节省模拟") }
                }
                if (comparisonText.isNotBlank()) Text(comparisonText, style = Type.footnote, modifier = Modifier.testTag("n5-comparison"))
                if (savingsText.isNotBlank()) Text(savingsText, style = Type.footnote, modifier = Modifier.testTag("n5-savings-result"))
            }
        }
    }, confirmButton = { TextButton(onClick = { job?.cancel(); onClose() }) { Text("关闭") } })

    adopt?.let { quote ->
        val reference = AiPriceReference(
            service = quote.service, plan = quote.plan, region = quote.region, currency = quote.currency,
            amountMinor = quote.amountMinor, billingPeriod = quote.period.name, taxTerms = quote.taxTerms,
            sourceUrl = quote.sourceUrl, sourceType = quote.sourceType.name, pageDate = quote.pageDate,
            retrievedAtUtcMillis = quote.retrievedAtMillis, verification = quote.verification.zhLabel(),
            evidence = quote.evidenceText,
            // 夹具绝不预填价格；用户材料/在线第三方可预填但仍需在编辑页确认，且不自动保存。
            priceAdoptionAllowed = quote.verification != VerificationStatus.FIXTURE_ONLY,
        )
        AiDraftEditor(reference, settings, onClose = { adopt = null })
    }
}
