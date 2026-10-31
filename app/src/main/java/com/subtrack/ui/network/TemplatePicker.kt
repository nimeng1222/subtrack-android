package com.subtrack.ui.network

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.subtrack.BuildConfig
import com.subtrack.domain.model.*
import com.subtrack.network.*
import com.subtrack.ui.theme.Type
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun TemplatePicker(config: NetworkSettings, onSelect: (SubscriptionTemplate) -> Unit) {
    key(config.templatesEnabled, config.templatesBaseUrl, config.connectionRevision) { TemplateSession(config, onSelect) }
}

@Composable private fun TemplateSession(config: NetworkSettings, onSelect: (SubscriptionTemplate) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { OnlineRepository(context, NetworkTransport(BuildConfig.DEBUG)) }
    var catalog by remember { mutableStateOf(repo.cachedCatalog(config.templatesBaseUrl)) }
    var templates by remember { mutableStateOf(catalog?.templates.orEmpty()) }
    var status by remember { mutableStateOf(if (templates.isEmpty()) "内置离线模板（未在线更新，价格未知）" else "离线缓存 · ${templates.first().dataVersion}") }
    var query by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var selected by remember { mutableStateOf<SubscriptionTemplate?>(null) }
    val lease = remember { RequestLease() }
    DisposableEffect(Unit) { onDispose { lease.close() } }
    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("tpl-open")) { Text("浏览/搜索订阅模板") }
    if (expanded) {
        Text(status, style = Type.caption)
        catalog?.let { cached ->
            val time = cached.fetchedAtUtcMillis?.let {
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it))
            } ?: "旧缓存未记录"
            Text("模板源：${cached.source}\n获取时间：$time", style = Type.caption)
        }
        ConfigField("搜索服务/地区/套餐", query) { query = it }
        Row {
            TextButton(enabled = config.templatesEnabled && job?.isActive != true, modifier = Modifier.testTag("tpl-refresh"), onClick = {
                val ticket = lease.ticket()
                job = scope.launch {
                    status = "连接中"
                    try {
                        val next = repo.templates(config.templatesBaseUrl)
                        if (!lease.valid(ticket)) return@launch
                        templates = next
                        catalog = repo.cachedCatalog(config.templatesBaseUrl)
                        status = "可用 · 在线数据版本 ${templates.first().dataVersion} · 核验时间见各项"
                    } catch (e: CancellationException) { status = "已取消，保留缓存"; throw e }
                    catch (e: Exception) { status = "失败：${e.message?.take(80)}；${if (templates.isEmpty()) "内置离线兜底" else "离线缓存"}" }
                }
                lease.attach(job!!)
            }) { Text("手动刷新") }
            if (job?.isActive == true) TextButton(onClick = { job?.cancel() }) { Text("取消") }
        }
        (templates.ifEmpty { OnlineRepository.offlineTemplates }).filter {
            listOfNotNull(it.serviceName, it.region, it.plan).joinToString(" ").contains(query, true)
        }.take(30).forEach { t ->
            TextButton(onClick = { selected = t }, modifier = Modifier.testTag("tpl-row-${t.id}")) {
                Text("${t.serviceName} · ${t.region ?: "地区未知"} · ${t.plan ?: "套餐未知"}\n" +
                    "${t.currency ?: "币种未知"} / ${t.billingPeriod ?: "周期未知"} · 价格${t.referencePriceMinor?.toBigDecimal()?.movePointLeft(2) ?: "未知，需填写"}\n" +
                    "含税：${t.taxIncluded ?: "未知"} · 核验：${t.verifiedAt ?: "未核验"}\n来源：${t.sourceUrl ?: "内置离线"}", style = Type.caption)
            }
        }
    }
    selected?.let { template ->
        TemplateDetails(template, onDismiss = { selected = null }, onUse = {
            onSelect(template); selected = null; expanded = false
        })
    }
}
