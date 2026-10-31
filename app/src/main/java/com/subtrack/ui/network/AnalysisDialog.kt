package com.subtrack.ui.network

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.subtrack.SubTrackApp
import com.subtrack.core.money.MoneyInput
import com.subtrack.domain.analysis.*
import com.subtrack.ui.theme.Type
import kotlinx.coroutines.launch

@Composable internal fun AnalysisDialog(onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var service by remember { mutableStateOf("") }; var region by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf("USD") }; var tax by remember { mutableStateOf("") }
    var offer by remember { mutableStateOf("") }; var rights by remember { mutableStateOf("") }
    var planA by remember { mutableStateOf("") }; var planB by remember { mutableStateOf("") }
    var amountA by remember { mutableStateOf("") }; var amountB by remember { mutableStateOf("") }
    var yearA by remember { mutableStateOf(false) }; var yearB by remember { mutableStateOf(true) }
    var result by remember { mutableStateOf("") }
    fun quote(plan: String, amount: String, year: Boolean): ComparableQuote {
        require(amount.trim().matches(Regex("[0-9]+(?:\\.[0-9]{1,2})?"))) { "金额最多两位小数" }
        return ComparableQuote(service, plan, region, currency, MoneyInput.price(amount) ?: error("金额无效"),
            if (year) PricePeriod.YEARLY else PricePeriod.MONTHLY, tax, offer, rights, null)
    }
    AlertDialog(onDismissRequest = onClose, title = { Text("本地比较与支出模拟") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("仅在本机计算。先确认两个方案具有相同税费、优惠及权益；不自动修改订阅。", style = Type.caption)
            ConfigField("服务", service) { service = it }; ConfigField("地区", region) { region = it }
            ConfigField("币种 ISO", currency) { currency = it.uppercase().take(3) }
            ConfigField("相同税费条件", tax) { tax = it }; ConfigField("相同优惠条件", offer) { offer = it }
            ConfigField("相同权益", rights) { rights = it }
            ConfigField("方案 A", planA) { planA = it }; ConfigField("A 原币金额", amountA) { amountA = it }
            Row { Checkbox(yearA, { yearA = it }); Text(if (yearA) "A 年付" else "A 月付") }
            ConfigField("方案 B", planB) { planB = it }; ConfigField("B 原币金额", amountB) { amountB = it }
            Row { Checkbox(yearB, { yearB = it }); Text(if (yearB) "B 年付" else "B 月付") }
            TextButton(onClick = { result = runCatching {
                val c = PriceAnalysis.compare(quote(planA, amountA, yearA), quote(planB, amountB, yearB))
                "A 年化 ${c.firstAnnualMinor} 分；B 年化 ${c.secondAnnualMinor} 分；A 减 B ${c.annualDifferenceMinor} 分（${c.currency}）。${c.note}"
            }.getOrElse { "无法比较：${it.message}" } }) { Text("计算方案差额") }
            TextButton(onClick = { result = runCatching {
                val c = PriceAnalysis.simulateCancellation(listOf(quote(planA, amountA, yearA)))
                "方案 A 假设取消后年预计少支出 ${c.estimatedAnnualAvoidedMinor} 分（${c.currency}）。${c.note}"
            }.getOrElse { "无法模拟：${it.message}" } }) { Text("模拟取消方案 A") }
            TextButton(onClick = { scope.launch {
                result = runCatching {
                    val subs = (context.applicationContext as SubTrackApp).container.repository.observeAllSnapshot()
                    val matches = PriceAnalysis.possibleDuplicates(subs.map { it.id to it.name })
                    if (matches.isEmpty()) "未发现同名条目；不代表服务没有重叠。"
                    else "可能重复：${matches.joinToString("、") { it.service }}。请自行核对权益。"
                }.getOrElse { "无法读取本地订阅" }
            } }) { Text("检查本地同名订阅") }
            if (result.isNotBlank()) Text(result, style = Type.footnote)
        }
    }, confirmButton = { TextButton(onClick = onClose) { Text("关闭") } })
}
