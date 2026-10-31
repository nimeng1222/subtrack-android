package com.subtrack.ui.settings

import com.subtrack.core.money.Money
import com.subtrack.domain.model.NetworkSettings
import com.subtrack.domain.model.ThemeMode
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * 设置首页每行的状态摘要。
 *
 * 这里刻意只做纯函数：首页的每一段文字都必须来自真实设置，不能填演示值；
 * 抽出来单独可测，才能锁住"未开启 / 未配置 / 连接失败 / 有待处理冲突"这几种
 * 状态的区分（它们是四个不同的事实，混成一个"未启用"会让用户读不出该做什么）。
 */
object SettingsSummaries {

    /** 提醒与通知：默认时刻 + 通知权限是否可用。 */
    fun reminderLine(time: LocalTime, offsets: List<Int>, notificationsEnabled: Boolean): String {
        val clock = "%02d:%02d".format(time.hour, time.minute)
        val base = if (offsets.isEmpty()) "提醒已关闭 · $clock" else "每天 $clock"
        return if (notificationsEnabled) base else "$base · 通知未开启"
    }

    /** 预算与统计：未设置与"设成 0"必须区分开。 */
    fun budgetLine(budgetMinor: Long?): String =
        if (budgetMinor == null) "月度预算未设置" else "月度预算 ${Money.format(budgetMinor)}"

    /**
     * 币种与汇率：多币种关闭、开启但没有任何汇率、有在线参考、只有手动汇率，是四种不同状态。
     * [nowMillis] 只用于把时间戳变成"今日/昨日/ N 天前"。
     */
    fun currencyLine(
        multiEnabled: Boolean,
        manualCount: Int,
        onlineEnabled: Boolean,
        fetchedAtMillis: Long,
        nowMillis: Long,
    ): String {
        if (!multiEnabled) return "多币种未开启"
        if (fetchedAtMillis > 0L) return "多币种已开启 · ${relativeDayLabel(fetchedAtMillis, nowMillis)}"
        if (manualCount > 0) return "多币种已开启 · 手动汇率 $manualCount 种"
        if (onlineEnabled) return "多币种已开启 · 汇率未获取"
        return "多币种已开启 · 汇率未设置"
    }

    /** 显示与外观。 */
    fun themeLine(mode: ThemeMode): String = when (mode) {
        ThemeMode.SYSTEM -> "跟随系统"
        ThemeMode.LIGHT -> "浅色"
        ThemeMode.DARK -> "深色"
    }

    /**
     * 数据与同步。active=false 是"本机可用但没开同步"，与"连上了但失败"不同：
     * [initError] 非空时必须是连接失败，而不是悄悄回落到"仅本机"。
     */
    fun syncLine(active: Boolean, pendingUploads: Int, conflicts: Int, initError: String?, configured: Boolean): String {
        if (initError != null) return "连接失败 · ${initError.take(24)}"
        if (!active) return if (configured) "已停用 · 仅本机" else "仅本机 · 未配置"
        if (conflicts > 0) return "已连接 · 有待处理冲突 $conflicts"
        if (pendingUploads > 0) return "已连接 · 待上传 $pendingUploads"
        return "已连接"
    }

    /** 联网与 AI：现在只剩 AI 模型连接（在线模板已移除）。 */
    fun networkLine(network: NetworkSettings): String = when {
        !network.aiEnabled -> "未配置"
        network.aiBaseUrl.isBlank() -> "AI 未填写服务地址"
        else -> "AI 已启用"
    }

    /** 安全与隐私。 */
    fun securityLine(appLockEnabled: Boolean): String =
        if (appLockEnabled) "应用锁已开启" else "应用锁未开启"

    /** 关于与更新：永远显示真实版本。 */
    fun aboutLine(versionName: String): String = "v$versionName"

    /** 相对日期：今日 / 昨日 / N 天前。 */
    fun relativeDayLabel(atMillis: Long, nowMillis: Long): String {
        if (atMillis <= 0L) return "未获取"
        val zone = ZoneId.systemDefault()
        val then = Instant.ofEpochMilli(atMillis).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val days = java.time.temporal.ChronoUnit.DAYS.between(then, today)
        return when {
            days <= 0L -> "今日更新"
            days == 1L -> "昨日更新"
            days < 30L -> "${days} 天前更新"
            else -> "${days / 30} 个月前更新"
        }
    }

    /** 在线汇率超过 48 小时即视为过期（与统计页/小组件口径一致）。 */
    fun fxStale(fetchedAtMillis: Long, nowMillis: Long): Boolean =
        fetchedAtMillis > 0L && nowMillis - fetchedAtMillis > 172_800_000L

    /**
     * 在线汇率（1e9 定点）→ "7.1234"。
     *
     * 这里曾经直接套手动汇率的分单位格式化，于是 1 USD = 7.1234 元被显示成
     * "在线 71234567.89" —— 用户看到的是一串天文数字，以为汇率算错了。
     */
    fun preciseRateText(nanos: Long): String {
        if (nanos <= 0L) return "—"
        return java.math.BigDecimal.valueOf(nanos).movePointLeft(9)
            .setScale(4, java.math.RoundingMode.HALF_UP).toPlainString()
    }

    /**
     * 手动汇率行的值：手动优先；否则给在线参考并带"在线参考"前缀；
     * 两者都没有才写"未设置"（与"设成 0"区分开）。
     */
    fun rateRowValue(manualMinor: Long?, onlineNanos: Long?): String = when {
        manualMinor != null -> "手动 ${yuanText(manualMinor)}"
        onlineNanos != null && onlineNanos > 0L -> "在线参考 ${preciseRateText(onlineNanos)}"
        else -> "未设置"
    }
}

/**
 * 提前天数的可读标签。列表语义：0 = 到期当天，其余为提前天数；
 * 规划器只按"最大提前量 + 当天"两档排程，所以这里也只展示这两档。
 */
internal fun offsetsLabel(offsets: List<Int>): String {
    val onDay = offsets.contains(0)
    val ahead = offsets.filter { it > 0 }.maxOrNull()
    return when {
        ahead == null && onDay -> "到期当天"
        onDay && ahead != null -> "提前 $ahead 天 + 当天"
        ahead != null -> "提前 $ahead 天"
        else -> "已关闭"
    }
}

/** 分 → "7.20"：回填输入框用，保留两位小数。 */
internal fun yuanText(minor: Long): String {
    val yuan = minor / 100
    val cents = (minor % 100).toString().padStart(2, '0')
    return "$yuan.$cents"
}

/**
 * 提交一条手动汇率。
 *
 * 单独抽出来是因为"元 → 分"的量纲最容易写错，而且错误输入必须**保留原汇率**
 * （不能因为一次打错就把已有汇率清空）。返回 null 表示已提交，非空是给用户看的错误。
 */
internal fun commitRate(
    code: String,
    raw: String,
    settings: com.subtrack.domain.model.AppSettings,
    onUpdate: (com.subtrack.domain.model.AppSettings) -> Unit,
): String? {
    val minor = com.subtrack.core.money.MoneyInput.rate(raw)
    if (raw.isNotBlank() && minor == null) return "汇率需大于 0 且不超过 10000 元，原汇率已保留"
    val next = if (raw.isBlank()) settings.fxRates - code else settings.fxRates + (code to minor!!)
    if (next != settings.fxRates) onUpdate(settings.copy(fxRates = next))
    return null
}
