package com.subtrack.domain.stats

import com.subtrack.domain.model.Subscription
import kotlin.math.abs

/**
 * 涨价预警（L2，默认关闭）。
 *
 * 文档 §5.4 与 §11.3 的既定方案：**不依赖 price_history 表**做检测，
 * 而是"最近一条 payment_record 的金额"与"当前 priceMinor"比较。
 * 理由：price_history 只在用户主动勾选"记录本次调价"时才有数据，
 * 而流水是每次确认续费都会写的，覆盖率天然更高。
 * price_history 保留作为"用户想留档"的补充，不作为检测依据。
 */
object PriceWatch {

    data class Change(
        val subscription: Subscription,
        /** 上次实际支付的金额（分）。 */
        val previousMinor: Long,
        /** 当前标价（分）。 */
        val currentMinor: Long,
    ) {
        val deltaMinor: Long get() = currentMinor - previousMinor

        /** 涨幅百分比，向下取整；用于文案"涨了 12%"。 */
        val percent: Int
            get() = if (previousMinor <= 0L) 0
            else (abs(deltaMinor) * 100.0 / previousMinor).toInt()

        val isIncrease: Boolean get() = deltaMinor > 0
    }

    /**
     * 检测涨价。
     *
     * @param lastPaid 订阅 id → 该条最近一次流水金额（分）。没有流水的条目不参与检测。
     * @return 只返回"涨幅超过阈值"的条目。阈值默认 1%：
     *   低于 1% 的变化多半是汇率/分账尾差，报出来是噪音。
     */
    fun detect(
        subs: List<Subscription>,
        lastPaid: Map<String, Long>,
        thresholdPercent: Int = 1,
    ): List<Change> = subs
        .filter { it.isForecastIncluded }
        .mapNotNull { sub ->
            val previous = lastPaid[sub.id] ?: return@mapNotNull null
            if (previous <= 0L) return@mapNotNull null
            val change = Change(sub, previous, sub.priceMinor)
            if (change.deltaMinor == 0L) return@mapNotNull null
            if (change.percent < thresholdPercent) return@mapNotNull null
            change
        }
        // 涨得最多的排前面：用户最需要知道的是"哪条突然变贵了"
        .sortedWith(compareByDescending<Change> { it.isIncrease }.thenByDescending { abs(it.deltaMinor) })
}
