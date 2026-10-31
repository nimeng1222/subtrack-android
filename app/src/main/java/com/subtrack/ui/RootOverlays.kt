package com.subtrack.ui

import androidx.compose.runtime.Composable
import com.subtrack.domain.model.AppSettings
import com.subtrack.domain.model.Subscription
import com.subtrack.domain.stats.StatsEngine
import com.subtrack.ui.components.BreakdownSheet
import com.subtrack.ui.components.ConfirmDialog
import com.subtrack.ui.editor.EditorSheet
import com.subtrack.ui.state.UiMessage
import java.time.LocalDate

/**
 * 根层的所有浮层：编辑 Sheet、统计展开、提示、撤销条、删除确认、恢复确认。
 *
 * 集中一处的好处是"同时只会有一个主浮层"这件事变得可读——
 * 之前散在 SubTrackRoot 里，加一个浮层就要重读 60 行导航代码。
 */
@Composable
fun RootOverlays(
    showEditor: Boolean,
    editing: Subscription?,
    /** 剪贴板识别出的预填草稿：新建时传入编辑页，关闭/保存后由调用方清空。 */
    prefill: com.subtrack.domain.parse.PaymentTextParser.Parsed? = null,
    editorSaveError: String? = null,
    editorSaving: Boolean = false,
    settings: AppSettings,
    today: LocalDate,
    breakdown: StatsEngine.Breakdown?,
    message: UiMessage?,
    undo: Subscription?,
    confirmDelete: Subscription?,
    resumeFor: Subscription?,
    onDismissEditor: () -> Unit,
    onSave: (Subscription) -> Unit,
    onCloseBreakdown: () -> Unit,
    onDismissMessage: () -> Unit,
    onUndoDelete: (Subscription) -> Unit,
    onConfirmDelete: (Subscription) -> Unit,
    onDismissDelete: () -> Unit,
    onResume: (Subscription, LocalDate) -> Unit,
    onDismissResume: () -> Unit,
) {
    if (showEditor) {
        EditorSheet(
            today = today,
            initial = editing,
            prefill = prefill,
            defaultSettings = settings,
            onDismiss = onDismissEditor,
            onSave = onSave,
            saveError = editorSaveError,
            saving = editorSaving,
        )
    }

    breakdown?.let { BreakdownSheet(breakdown = it, onDismiss = onCloseBreakdown) }

    message?.let { MessageOverlay(it, onDismissMessage) }

    undo?.let { removed -> UndoBar(removed.name) { onUndoDelete(removed) } }

    confirmDelete?.let { target ->
        ConfirmDialog(
            title = "删除「${target.name}」？",
            message = "删除后该条的历史扣费记录不再计入统计。5 秒内可以撤销。",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = { onConfirmDelete(target) },
            onDismiss = onDismissDelete,
        )
    }

    resumeFor?.let { target ->
        ResumeDialog(
            sub = target,
            today = today,
            onConfirm = { newDate -> onResume(target, newDate) },
            onDismiss = onDismissResume,
        )
    }
}
