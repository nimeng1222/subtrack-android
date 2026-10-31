package com.subtrack.ui.editor
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subtrack.domain.cycle.CycleEngine
import com.subtrack.domain.model.AppSettings
import com.subtrack.domain.model.BillingCycleType
import com.subtrack.domain.model.Subscription
import com.subtrack.domain.model.SubscriptionStatus
import com.subtrack.ui.Categories
import com.subtrack.ui.components.CategoryBadge
import com.subtrack.ui.components.SectionHeader
import java.time.LocalDate
import java.util.UUID
import com.subtrack.ui.theme.Accent
import com.subtrack.ui.theme.Danger
import com.subtrack.ui.theme.Ink
import com.subtrack.ui.theme.InkTertiary
import com.subtrack.ui.theme.SheetBg
import com.subtrack.ui.theme.SurfaceSecondary
/** 9 种周期，顺序即展示顺序。 */
private val cycleOptions = listOf(
    BillingCycleType.MONTHLY to "月付",
    BillingCycleType.QUARTERLY to "季付",
    BillingCycleType.SEMIANNUAL to "半年付",
    BillingCycleType.YEARLY to "年付",
    BillingCycleType.WEEKLY to "周付",
    BillingCycleType.BIWEEKLY to "双周付",
    BillingCycleType.FOUR_WEEKLY to "四周付",
    BillingCycleType.CUSTOM_DAYS to "自定义",
    BillingCycleType.ONE_TIME to "一次性",
)
/**
 * 新建 / 编辑订阅。
 *
 * AC-01 要求的字段全部在这里：名称、分类、金额、周期、开始日期、下次到期日、备注、状态、试用。
 * AC-03 要求选周期后立即推算下次到期日，但仍允许手动覆盖——所以日期是"可点按微调"的。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorSheet(
    today: LocalDate,
    initial: Subscription? = null,
    /** 从剪贴板识别出的草稿，用于预填（用户仍可改）。 */
    prefill: com.subtrack.domain.parse.PaymentTextParser.Parsed? = null,
    defaultSettings: AppSettings = AppSettings(),
    template: com.subtrack.domain.model.SubscriptionTemplate? = null,
    saveError: String? = null,
    saving: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (Subscription) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val submissionId = remember(initial?.id) { initial?.id ?: UUID.randomUUID().toString() }
    var name by remember(initial, prefill) {
        mutableStateOf(
            initial?.name ?: template?.serviceName ?: prefill?.merchant?.takeUnless { it == com.subtrack.domain.parse.PaymentTextParser.UNKNOWN_MERCHANT } ?: "",
        )
    }
    var amount by remember(initial, prefill) {
        mutableStateOf(
            initial?.let { "${it.priceMinor / 100}.${(it.priceMinor % 100).toString().padStart(2, '0')}" }
                ?: prefill?.let { "${it.amountMinor / 100}.${(it.amountMinor % 100).toString().padStart(2, '0')}" }
                ?: template?.referencePriceMinor?.toBigDecimal()?.movePointLeft(2)?.toPlainString() ?: "",
        )
    }
    var currency by remember(initial, template) { mutableStateOf(initial?.currency ?: template?.currency ?: "CNY") }
    var cycle by remember(initial, prefill) {
        mutableStateOf(initial?.cycle ?: prefill?.suggestedCycle ?: template?.billingPeriod?.let { BillingCycleType.valueOf(it) } ?: BillingCycleType.MONTHLY)
    }
    var customDays by remember(initial) { mutableStateOf(initial?.customCycleDays?.toString() ?: "30") }
    var categoryId by remember(initial) { mutableStateOf(initial?.categoryId ?: "video") }
    var autoRenew by remember(initial) { mutableStateOf(initial?.autoRenew ?: true) }
    var anchor by remember(initial) { mutableStateOf(initial?.anchorDate ?: today) }
    var nextCharge by remember(initial) {
        mutableStateOf(
            initial?.nextChargeDate
                ?: CycleEngine.occurrenceAtOrAfter(today, BillingCycleType.MONTHLY, null, today)
                ?: today,
        )
    }
    var note by remember(initial) { mutableStateOf(initial?.note ?: "") }
    var isTrial by remember(initial) { mutableStateOf(initial?.status == SubscriptionStatus.TRIAL) }
    var trialEnd by remember(initial) { mutableStateOf(initial?.trialEndDate ?: today.plusDays(7)) }
    var offsets by remember(initial) {
        mutableStateOf(initial?.reminderOffsets ?: defaultSettings.defaultReminderOffsets)
    }
    var error by remember { mutableStateOf<String?>(null) }
    var showMore by remember { mutableStateOf(false) }
    val minor = remember(amount) {
        com.subtrack.core.money.MoneyInput.price(amount)
    }
    val days = customDays.trim().toIntOrNull()
    /**
     * 顶部"保存"与底部"保存订阅"共用同一套校验（v1.3.2 里只有底部看 valid，
     * 顶部直接 submit，自定义天数清空/填 0 也能写库并让重算崩溃）。
     * 自定义周期的合法范围与 CycleEngine / 导入校验同源（[Limits]）。
     */
    val validationError = validate(
        name = name,
        minor = minor,
        anchor = anchor,
        today = today,
        cycle = cycle,
        customDays = days,
    )
    val valid = validationError == null
    val recomputeNext: (BillingCycleType) -> Unit = { newCycle ->
        cycle = newCycle
        val decision = recalculateEditorDate(anchor, newCycle, days, today, nextCharge)
        nextCharge = decision.date
        error = decision.error
    }
    val submit: () -> Unit = submit@{
        if (saving) return@submit
        val err = validate(name, minor, anchor, today, cycle, days)
        if (!currency.matches(Regex("[A-Z]{3}"))) {
            error = "币种需为三字母代码"
        } else if (err != null) {
            error = err
        } else {
            onSave(
                Subscription(
                    id = submissionId,
                    name = name.trim(),
                    categoryId = categoryId,
                    paymentMethodId = initial?.paymentMethodId,
                    cycle = cycle,
                    customCycleDays = if (cycle == BillingCycleType.CUSTOM_DAYS) days else null,
                    anchorDate = anchor,
                    nextChargeDate = nextCharge,
                    priceMinor = minor ?: 0L,
                    currency = currency,
                    status = when {
                        isTrial -> SubscriptionStatus.TRIAL
                        initial != null -> initial.status
                        else -> SubscriptionStatus.ACTIVE
                    },
                    autoRenew = autoRenew,
                    trialEndDate = if (isTrial) trialEnd else null,
                    canceledAt = initial?.canceledAt,
                    reminderOffsets = offsets,
                    mutedUntil = initial?.mutedUntil,
                    note = note.trim().ifBlank { null },
                    deletedAtUtcMillis = initial?.deletedAtUtcMillis,
                ),
            )
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SheetBg,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = null,
        scrimColor = Color.Black.copy(alpha = 0.34f),
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.94f).navigationBarsPadding().imePadding().padding(horizontal = 20.dp)) {
        Column(
            Modifier
                .fillMaxWidth().weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(36.dp, 5.dp).clip(RoundedCornerShape(3.dp)).background(InkTertiary))
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("取消", Modifier.clickable(onClick = onDismiss), style = TextStyle(fontSize = 17.sp, color = Accent))
                Spacer(Modifier.weight(1f))
                Text(
                    if (initial == null) "新建订阅" else "编辑订阅",
                    style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Ink),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "保存",
                    Modifier.testTag("editor-save-top").clickable(enabled = !saving) { submit() },
                    style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Accent),
                )
            }
            saveError?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = TextStyle(fontSize = 13.sp, color = Danger))
            }
            Spacer(Modifier.height(16.dp))
            if (initial == null) {
                com.subtrack.ui.network.TemplatePicker(defaultSettings.network) { selected ->
                    name = selected.serviceName
                    amount = selected.referencePriceMinor?.toBigDecimal()?.movePointLeft(2)?.toPlainString() ?: ""
                    currency = selected.currency ?: "CNY"
                    selected.billingPeriod?.let { recomputeNext(BillingCycleType.valueOf(it)) }
                    error = "模板仅作参考：请确认实付金额、币种与扣费日期"
                }
            }
            if (initial == null) {
                com.subtrack.ui.network.ConfigField("币种（ISO 三字母）", currency) { currency = it.uppercase().take(3) }
                Spacer(Modifier.height(12.dp))
            }
            AmountField(amount, currency) { amount = it; error = null }
            Spacer(Modifier.height(14.dp))
            NameField(name) { name = it; error = null }
            Spacer(Modifier.height(20.dp))
            CycleAndDates(
                cycle = cycle,
                onCycle = { recomputeNext(it) },
                customDays = customDays,
                onCustomDays = { customDays = it.filter { c -> c.isDigit() }.take(8) },
                anchor = anchor,
                onAnchor = { anchor = it },
                nextCharge = nextCharge,
                onNextCharge = { nextCharge = it },
                onRecalc = {
                    recomputeNext(cycle)
                },
                today = today,
                isNew = initial == null,
            )
            CategoryPicker(categoryId) { categoryId = it }
            Spacer(Modifier.height(20.dp))
            Row(
                Modifier.fillMaxWidth().clickable { showMore = !showMore },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionHeader("更多设置")
                Spacer(Modifier.weight(1f))
                Text(
                    if (showMore) "收起" else "展开",
                    style = TextStyle(fontSize = 12.sp, color = Accent),
                )
            }
            if (showMore) {
                Spacer(Modifier.height(12.dp))
                MoreSettings(
                    autoRenew = autoRenew,
                    onAutoRenew = { autoRenew = it },
                    isTrial = isTrial,
                    onTrial = { isTrial = it },
                    trialEnd = trialEnd,
                    onTrialEnd = { trialEnd = it },
                    offsets = offsets,
                    onOffsets = { offsets = it },
                    note = note,
                    onNote = { note = it },
                    today = today,
                )
            }
            Spacer(Modifier.height(10.dp))
            if (error != null) {
                Spacer(Modifier.height(10.dp))
                Text(error!!, style = TextStyle(fontSize = 13.sp, color = Danger))
            }
            Spacer(Modifier.height(22.dp))
        }
            Box(
                Modifier
                    .testTag("editor-save-bottom")
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (valid) Accent else Accent.copy(alpha = 0.28f))
                    .clickable(enabled = valid && !saving) { submit() },
                contentAlignment = Alignment.Center,
            ) {
                Text("保存订阅", style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
