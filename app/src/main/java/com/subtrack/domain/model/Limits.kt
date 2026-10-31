package com.subtrack.domain.model

/**
 * 业务边界的唯一事实来源。导入校验（JSON/CSV）、编辑页校验、统计运算共用这一份，
 * 任何一处单独放宽都会让"编辑页能存、引擎会崩"这类口径分裂回来。
 *
 * - [MAX_CUSTOM_CYCLE_DAYS] 与 [CycleEngine] 的 `require(n in 1..3650)` 对齐；
 * - [MAX_PRICE_MINOR] 保证 Annualizer 最大系数 365 相乘不溢出 Long（1e12 × 365 ≈ 3.65e14）；
 * - [MAX_FX_RATE] 保证折算运算 `minor × rate / 100` 不溢出。
 */
object Limits {
    /** 自定义周期的合法天数范围，与 CycleEngine 一致。 */
    const val CUSTOM_CYCLE_DAYS_MIN = 1
    const val CUSTOM_CYCLE_DAYS_MAX = 3650

    /** 订阅价格上限（分）= 100 亿元。下限 0：免费/0 元订阅是合法数据（统计按 0 摊平，不会崩）。 */
    const val MAX_PRICE_MINOR = 1_000_000_000_000L

    /** 单笔流水金额上限（分）= 100 亿元。 */
    const val MAX_PAYMENT_MINOR = 1_000_000_000_000L

    /** 汇率上限：1 外币单位最多折 1 万元人民币（分）。与最大价格相乘 (1e12×1e6=1e18) 不溢出 Long。 */
    const val MAX_FX_RATE = 1_000_000L

    fun isValidCustomCycleDays(days: Int?): Boolean =
        days != null && days in CUSTOM_CYCLE_DAYS_MIN..CUSTOM_CYCLE_DAYS_MAX

    /** 价格允许 0（免费订阅），只挡溢出风险的上界；编辑页的"金额必须大于 0"由 UI 校验负责。 */
    fun isValidFxRate(rate: Long?): Boolean = rate != null && rate in 1..MAX_FX_RATE

    fun isValidPriceMinor(minor: Long): Boolean = minor in 0..MAX_PRICE_MINOR

    fun isValidPaymentMinor(minor: Long): Boolean = minor in 0..MAX_PAYMENT_MINOR
}
