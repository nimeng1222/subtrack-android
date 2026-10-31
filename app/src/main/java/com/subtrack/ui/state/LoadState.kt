package com.subtrack.ui.state

import com.subtrack.domain.model.Subscription

/**
 * 数据加载状态。
 *
 * AC-46 明确要求：读取失败要显示可重试的错误，**不得呈现为"数据为空"**。
 * 所以这里把 Loading / Failed / Ready 三态分开，UI 不能靠 `list.isEmpty()` 猜。
 */
sealed interface LoadState {
    data object Loading : LoadState
    data class Ready(val data: List<Subscription>) : LoadState
    data class Failed(val reason: String) : LoadState
}
