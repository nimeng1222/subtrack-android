package com.subtrack.ui.network

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import com.subtrack.SubTrackApp
import com.subtrack.domain.model.*
import com.subtrack.network.*
import com.subtrack.ui.editor.EditorSheet
import com.subtrack.ui.theme.Type
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
internal fun AiPriceDialog(settings: AppSettings, transport: NetworkTransport, secrets: SecretStore, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var service by remember { mutableStateOf("") }; var region by remember { mutableStateOf("") }
    var plan by remember { mutableStateOf("") }; var currency by remember { mutableStateOf("USD") }
    var period by remember { mutableStateOf("MONTHLY") }; var conditions by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }; var material by remember { mutableStateOf("") }
    var approved by remember { mutableStateOf(false) }; var status by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<AiPriceReference?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }; var draft by remember { mutableStateOf(false) }
    val lease = remember { RequestLease() }
    DisposableEffect(Unit) { onDispose { lease.close() } }
    // O7: material/consent/endpoint changes invalidate the in-flight analysis and any pending result.
    var requestVersion by remember { mutableStateOf(0) }
    LaunchedEffect(material, approved, settings.network.aiBaseUrl) {
        requestVersion++
        job?.cancel()
        job = null
        result = null
        status = "未发送"
    }
    fun changed() { lease.invalidate(); approved = false; result = null; draft = false }
    AlertDialog(onDismissRequest = { if (!draft) { job?.cancel(); onDismiss() } }, title = { Text("材料分析 · 未进行在线核验") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("将发送以下全部字段至 ${settings.network.aiBaseUrl}；不发送备注、台账或付款。可删减或取消。不提供网页抓取/在线搜索。", style = Type.caption)
            ConfigField("服务", service, "ai-query-service") { service = it; changed() }; ConfigField("地区", region, "ai-query-region") { region = it; changed() }
            ConfigField("套餐", plan, "ai-query-plan") { plan = it; changed() }; ConfigField("币种", currency, "ai-query-currency") { currency = it; changed() }
            ConfigField("周期 MONTHLY / YEARLY", period, "ai-query-period") { period = it; changed() }
            ConfigField("税费、优惠、新老用户条件", conditions, "ai-query-conditions") { conditions = it; changed() }
            ConfigField("来源 HTTPS URL（仅标识，不自动抓取）", url, "ai-query-url") { url = it; changed() }
            OutlinedTextField(material, { if (it.length <= 12000) { material = it; changed() } }, modifier = Modifier.testTag("ai-query-material"), label = { Text("粘贴来源材料") })
            Row { Checkbox(approved, { approved = it }, modifier = Modifier.testTag("ai-query-approve")); Text("确认条件与发送范围；同意可能的提供方费用") }
            Text(status, style = Type.footnote)
            result?.let { r ->
                Text("${r.service} / ${r.plan} / ${r.region}\n${r.currency} ${r.amountMinor?.toBigDecimal()?.movePointLeft(2) ?: "未知"} / ${r.billingPeriod}\n${r.taxTerms}\n来源类型：用户材料\n${r.sourceUrl}\n证据：${r.evidence}\n检索时间：无（未检索）；分析时间：${r.retrievedAtUtcMillis}\n${r.verification}", modifier = Modifier.testTag("ai-result"), style = Type.footnote)
                Text(if (r.priceAdoptionAllowed) "逐报价字段已绑定（仍未在线核验）" else "非结构化材料无法可靠绑定套餐/地区/条件，价格不会预填，请手动确认", style = Type.caption)
                TextButton(onClick = { draft = true }) { Text("采用到编辑草稿（需再次确认价格/日期）") }
            }
        }
    }, confirmButton = { TextButton(modifier = Modifier.testTag("ai-query-send"), enabled = approved && job?.isActive != true, onClick = {
        val ticket = lease.ticket()
        val version = requestVersion
        job = scope.launch {
            status = "连接中"; result = null
            try {
                val q = PriceQuestion(service, region, plan, currency, period, conditions, url, material)
                val body = AiProtocol.request(settings.network.aiModel, q)
                val response = transport.request(settings.network.aiBaseUrl, body, secrets.getBound(settings.network.aiBaseUrl), 65536)
                val decoded = AiProtocol.decode(response.toString(Charsets.UTF_8), q,
                    (context.applicationContext as SubTrackApp).container.clock.nowMillis())
                if (version == requestVersion && lease.valid(ticket)) { result = decoded; status = "协议可用；建议未核验，不会自动改账" }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (lease.valid(ticket)) status = "失败：${e.message?.take(160)}" }
        }
        lease.attach(job!!)
    }) { Text("确认发送") } }, dismissButton = { TextButton(enabled = !draft, onClick = { job?.cancel(); onDismiss() }) { Text("取消/关闭") } })
    if (draft) result?.let { AiDraftEditor(it, settings, onClose = { draft = false; onDismiss() }) }
}
