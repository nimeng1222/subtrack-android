package com.subtrack.domain.model

import java.time.LocalTime

/**
 * 全局设置。全部有默认值，因此"设置项还没写入过"与"设置成默认值"行为一致。
 *
 * [defaultReminderOffsets] 是 AC-21 的全局默认提前天数，允许 0/1/2/3/5/7/14/30，默认 3 天。
 * [reminderOnDueDay] 控制是否在到期当天再提醒一次（AC-24）。
 * [reminderTime] 对齐裁决 D8：提醒时刻由设置注入，禁止在 Planner 里硬编码 09:00。
 */
/** 外观模式：跟随系统 / 强制浅色 / 强制深色。 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val defaultReminderOffsets: List<Int> = listOf(3, 0),
    val reminderTime: LocalTime = LocalTime.of(9, 0),
    val notificationsBannerDismissed: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** 应用锁：默认关闭，用户主动开启才生效。 */
    val appLockEnabled: Boolean = false,

    // ── L2 可选增强（全部默认关闭，用户主动开启才生效）──
    //
    // 为什么全部默认关闭：这四项都是"需要一定数据密度才有意义"的功能
    // （预算要用户知道自己月均、涨价要至少两笔流水、体检要足够多的分类）。
    // 默认打开会让新用户看到一堆空卡片，反而降低信任。

    /** 月度预算上限（分）。null 表示未设置，不做超支预警。 */
    val monthlyBudgetMinor: Long? = null,

    /** 涨价预警：编辑改价时是否记录历史，并在流水里检测涨幅。 */
    val priceWatchEnabled: Boolean = false,

    /** 订阅体检：长期未确认/低使用价值的提醒。 */
    val healthCheckEnabled: Boolean = false,

    /** 多币种：展示时是否按手动汇率折算成人民币。 */
    val multiCurrencyEnabled: Boolean = false,

    /**
     * 手动汇率表：币种代码 → 折算成 1 单位该币种需要多少分人民币。
     * 例如 {"USD": 720_00} 表示 1 美元 ≈ 7.20 元。
     * 用整数分存储，避免浮点误差进到金额计算里。
     */
    val fxRates: Map<String, Long> = emptyMap(),

    /** Independent network feature switches; disabled by default and never included in backups. */
    val network: NetworkSettings = NetworkSettings(),
) {
    val effectiveFxRates: Map<String, Long> get() = com.subtrack.domain.stats.FxRateTable(
        fxRates, if (network.fxEnabled) network.autoFxPreciseRates.filterKeys { it !in fxRates } else emptyMap(),
        if (network.fxEnabled) network.autoFxRates else emptyMap())

    companion object {
        val ALLOWED_OFFSETS = listOf(0, 1, 2, 3, 5, 7, 14, 30)

        /** 过滤到合法集合、去重、降序；下标 0 永远是最大提前量。 */
        fun sanitizeOffsets(raw: List<Int>): List<Int> =
            raw.filter { it in ALLOWED_OFFSETS }.distinct().sortedDescending()
    }
}
