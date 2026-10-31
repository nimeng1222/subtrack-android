package com.subtrack.ui.network

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.subtrack.SubTrackApp
import com.subtrack.domain.model.*
import com.subtrack.domain.state.*
import com.subtrack.ui.editor.EditorSheet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun AiDraftEditor(result: AiPriceReference, settings: AppSettings, onClose: () -> Unit,
    save: (suspend (Subscription) -> EditorSaveResult)? = null,
    replan: (suspend (Subscription) -> Unit)? = null) {
    val c = (LocalContext.current.applicationContext as SubTrackApp).container
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var committed by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var savedMessage by remember { mutableStateOf<String?>(null) }
    if (!committed) EditorSheet(today = c.clock.today(),
        template = SubscriptionTemplate("ai-draft", result.service, result.region, result.plan, result.currency,
            result.billingPeriod, if (result.priceAdoptionAllowed) result.amountMinor else null, sourceUrl = result.sourceUrl),
        defaultSettings = settings, saving = saving, saveError = error,
        onDismiss = { if (!saving) onClose() }, onSave = submit@{ sub ->
            if (saving || committed) return@submit
            saving = true; error = null
            scope.launch {
                try {
                    when (if (save != null) save(sub) else c.repository.saveEditorDraft(null, sub)) {
                        is EditorSaveResult.Conflict -> error = EDITOR_CONFLICT_MESSAGE
                        EditorSaveResult.Saved -> {
                            committed = true
                            try { if (replan != null) replan(sub) else c.reminderService.replan(sub); onClose() }
                            catch (e: Exception) {
                                if (e is CancellationException) throw e
                                savedMessage = "订阅与首期付款已保存；提醒重排失败，请从提醒健康度重试。不要重复创建。"
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    error = "保存失败，草稿已保留，请重试"
                } finally { saving = false }
            }
        })
    savedMessage?.let { message -> androidx.compose.material3.AlertDialog(onDismissRequest = onClose,
        title = { Text("已保存") }, text = { Text(message) }, confirmButton = {
            androidx.compose.material3.TextButton(onClick = onClose) { Text("知道了") }
        }) }
}
