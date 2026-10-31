package com.subtrack.ui.network

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.subtrack.network.search.SearchHit
import com.subtrack.network.search.WikipediaSearch
import com.subtrack.ui.theme.Type
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** This provider gives third-party summaries, never a verified price. */
@Composable internal fun SearchDialog(onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var contact by remember { mutableStateOf("") }
    var consent by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("未请求") }
    var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var job by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(Unit) { onDispose { job?.cancel() } }
    AlertDialog(onDismissRequest = { job?.cancel(); onClose() }, title = { Text("搜索公开资料") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("提供方：英文维基百科。仅发送搜索词与联系地址，不发送台账或模型 Key。结果是第三方摘要，不能核验现价。", style = Type.caption)
            ConfigField("搜索词", query) { query = it; consent = false; hits = emptyList(); job?.cancel() }
            ConfigField("联系地址（Wikimedia User-Agent 要求）", contact) { contact = it; consent = false; job?.cancel() }
            androidx.compose.foundation.layout.Row { Checkbox(consent, { consent = it }); Text("确认发送上述字段") }
            Text(status, style = Type.caption)
            hits.forEach { hit ->
                Text("${hit.title}\n${hit.snippet}\n页面更新：${hit.pageUpdatedAt ?: "未知"}；获取：${java.time.Instant.ofEpochMilli(hit.retrievedAtMillis)}\n第三方摘要 · 未核验价格", style = Type.caption)
                TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(hit.url)).addCategory(Intent.CATEGORY_BROWSABLE)) }
                    .onFailure { status = "无法打开浏览器" } }) { Text("打开来源") }
            }
        }
    }, confirmButton = { TextButton(enabled = consent && job?.isActive != true, onClick = {
        val q = query; val c = contact
        job = scope.launch {
            status = "连接中"; hits = emptyList()
            try { val next = WikipediaSearch.search(q, c, System.currentTimeMillis()); hits = next; status = "返回 ${next.size} 条第三方摘要，未核验价格" }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { status = "检索失败：${e.message?.take(120)}" }
        }
    }) { Text("主动检索") } }, dismissButton = { TextButton(onClick = { job?.cancel(); onClose() }) { Text("取消/关闭") } })
}
