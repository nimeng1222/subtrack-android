package com.subtrack.domain.stats

import com.subtrack.domain.model.Subscription
import com.subtrack.domain.model.SubscriptionStatus

/**
 * 搜索与筛选的纯函数实现（v1.1）。
 *
 * 单独抽出来而不是写在 Composable 里，理由和 StatsEngine 一样：
 * "筛选后到底剩几条"是能被测试断言的事实，不该埋在 UI 状态里。
 */
object SubscriptionQuery {

    enum class SortBy(val label: String) {
        NEXT_CHARGE("按到期日"),
        PRICE_DESC("按金额从高到低"),
        PRICE_ASC("按金额从低到高"),
        NAME("按名称"),
    }

    data class Criteria(
        val keyword: String = "",
        /** 金额区间（分）。null 表示不限。 */
        val minMinor: Long? = null,
        val maxMinor: Long? = null,
        /** 分类 id 多选；空集表示不限。 */
        val categoryIds: Set<String> = emptySet(),
        val sortBy: SortBy = SortBy.NEXT_CHARGE,
        val includeEnded: Boolean = true,
    ) {
        /** 是否动用了关键词之外的筛选条件（决定 UI 是否高亮"筛选"按钮）。 */
        val hasAdvancedFilter: Boolean
            get() = minMinor != null || maxMinor != null || categoryIds.isNotEmpty()
    }

    /**
     * 应用筛选与排序。
     *
     * 排序细节：
     * - 按到期日：没有下次扣费日的（一次性）排最后，而不是当 0 排最前；
     * - 按金额：同额时按名称，保证顺序稳定（否则 Compose 里会出现条目跳动）。
     */
    fun apply(subs: List<Subscription>, criteria: Criteria): List<Subscription> {
        val keyword = criteria.keyword.trim()

        val filtered = subs.filter { sub ->
            if (!criteria.includeEnded &&
                (sub.status == SubscriptionStatus.CANCELED || sub.status == SubscriptionStatus.EXPIRED)
            ) return@filter false

            if (keyword.isNotEmpty() && !sub.name.contains(keyword, ignoreCase = true)) {
                return@filter false
            }
            criteria.minMinor?.let { if (sub.priceMinor < it) return@filter false }
            criteria.maxMinor?.let { if (sub.priceMinor > it) return@filter false }
            if (criteria.categoryIds.isNotEmpty() && sub.categoryId !in criteria.categoryIds) {
                return@filter false
            }
            true
        }

        return when (criteria.sortBy) {
            SortBy.NEXT_CHARGE -> filtered.sortedWith(
                compareBy({ it.nextChargeDate == null }, { it.nextChargeDate }, { it.name }),
            )
            SortBy.PRICE_DESC -> filtered.sortedWith(
                compareByDescending<Subscription> { it.priceMinor }.thenBy { it.name },
            )
            SortBy.PRICE_ASC -> filtered.sortedWith(
                compareBy<Subscription> { it.priceMinor }.thenBy { it.name },
            )
            SortBy.NAME -> filtered.sortedBy { it.name }
        }
    }

    /** 可选的金额档位，用于 UI 的快捷分段选择（比让用户手输两个数字更快）。 */
    val PRICE_PRESETS: List<Triple<String, Long?, Long?>> = listOf(
        Triple("不限", null, null),
        Triple("< 30", null, 30_00L),
        Triple("30-100", 30_00L, 100_00L),
        Triple("100-300", 100_00L, 300_00L),
        Triple("> 300", 300_00L, null),
    )
}
