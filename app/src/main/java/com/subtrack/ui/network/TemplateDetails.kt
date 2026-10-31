package com.subtrack.ui.network

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import com.subtrack.domain.model.SubscriptionTemplate
import com.subtrack.network.NetworkUrls
import com.subtrack.ui.theme.Type

@Composable
internal fun TemplateDetails(template: SubscriptionTemplate, onDismiss: () -> Unit, onUse: () -> Unit) {
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    fun open(url: String) {
        if (!NetworkUrls.external(url)) { error = "链接不受支持"; return }
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }.onFailure { error = "未找到可用浏览器" }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(template.serviceName) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            val tax = when (template.taxIncluded) { true -> "含税"; false -> "不含税"; null -> "未知" }
            val price = template.referencePriceMinor?.toBigDecimal()?.movePointLeft(2)?.toPlainString() ?: "未知，需填写实付"
            Text("地区：${template.region ?: "未知"}\n套餐：${template.plan ?: "未知"}\n币种：${template.currency ?: "未知"}\n" +
                "周期：${template.billingPeriod ?: "未知"}\n参考价格：$price\n税费：$tax\n" +
                "试用条件：${template.trialTerms ?: "未知"}\n核验日期：${template.verifiedAt ?: "未核验"}\n" +
                "数据版本：${template.dataVersion ?: "内置离线"}\n模板 ID：${template.id}", style = Type.footnote)
            template.websiteUrl?.let { url -> TextButton(onClick = { open(url) }) { Text("官网：$url") } }
            template.cancellationUrl?.let { url -> TextButton(onClick = { open(url) }) { Text("取消入口：$url") } }
            template.sourceUrl?.let { url -> TextButton(onClick = { open(url) }) { Text("来源：$url") } }
            error?.let { Text(it, style = Type.caption) }
        }
    }, confirmButton = { TextButton(onClick = onUse, modifier = Modifier.testTag("tpl-use")) { Text("采用到编辑草稿") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}
