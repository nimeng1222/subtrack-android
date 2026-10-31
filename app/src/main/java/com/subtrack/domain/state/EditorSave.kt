package com.subtrack.domain.state

import com.subtrack.domain.model.Subscription
import com.subtrack.domain.repository.PaymentRecord
import com.subtrack.domain.repository.SubscriptionRepository

sealed interface EditorSaveResult {
    data object Saved : EditorSaveResult
    data class Conflict(val current: Subscription?) : EditorSaveResult
}

/**
 * Compare the editor's opening snapshot inside the same Room transaction as its write.
 * A conflict never merges dates/status implicitly and never writes or deletes a payment record.
 *
 * **新建时补记首期付款**：以前只有"标记已续费 / 试用转正 / 导入"会写流水，于是新建一条订阅后
 * 详情页的「已付总额」永远是 0——用户明明填了金额，却看不到自己付过的那一期。现在新建成功
 * 会在**同一个事务**里按开始日期记一笔（金额＝订阅价）；编辑不补记，避免把改价、改日期
 * 误当成一笔新付款。
 *
 * [recordFirstPayment] 关闭时保持旧行为（仅用于水位确认/恢复类调用方）。
 */
suspend fun SubscriptionRepository.saveEditorDraft(
    original: Subscription?,
    draft: Subscription,
    recordFirstPayment: Boolean = true,
): EditorSaveResult =
    runInTransaction {
        require(original == null || original.id == draft.id) { "编辑条目不匹配" }
        val current = get(draft.id)
        if (current != original || current?.isDeleted == true || draft.isDeleted) {
            EditorSaveResult.Conflict(current)
        } else {
            upsert(draft)
            if (original == null && recordFirstPayment && draft.priceMinor > 0L) {
                recordPayments(
                    listOf(
                        PaymentRecord(
                            id = java.util.UUID.randomUUID().toString(),
                            subscriptionId = draft.id,
                            paidAt = draft.anchorDate,
                            amountMinor = draft.priceMinor,
                            currency = draft.currency,
                        ),
                    ),
                )
            }
            EditorSaveResult.Saved
        }
    }

const val EDITOR_CONFLICT_MESSAGE = "条目已在后台变更，未覆盖最新记录。当前草稿已保留，请核对后重新打开编辑。"
