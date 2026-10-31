package com.subtrack

import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.subtrack.domain.model.*
import com.subtrack.domain.state.*
import com.subtrack.network.SecretStore
import com.subtrack.ui.network.AiDraftEditor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class EStageRegressionTest {
    private val inst get() = Round2Instrumentation.instance
    private val context get() = inst.targetContext
    private fun nodes(): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo) { out += n; for (i in 0 until n.childCount) n.getChild(i)?.let { visit(it) } }
        inst.uiAutomation.windows.forEach { it.root?.let { root -> visit(root) } }
        return out
    }
    private fun idle() { inst.waitForIdleSync(); Thread.sleep(150) }
    private fun text(value: String, scroll: Boolean = false): AccessibilityNodeInfo {
        repeat(if (scroll) 60 else 30) {
            nodes().firstOrNull { it.text?.toString() == value && it.isVisibleToUser }?.let { return it }
            if (scroll) nodes().filter { it.isScrollable }.forEach { it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }
            idle()
        }
        error("No text: $value; ${nodes().map { it.text }}")
    }
    private fun click(value: String, scroll: Boolean = false) {
        var n: AccessibilityNodeInfo? = text(value, scroll)
        while (n != null && !n.isClickable) n = n.parent
        assertNotNull(n)
        n!!.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id); idle()
        n = text(value, scroll)
        while (n != null && !n.isClickable) n = n.parent
        assertNotNull(n)
        val bounds = android.graphics.Rect(); n!!.getBoundsInScreen(bounds)
        if (!n!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            val now = android.os.SystemClock.uptimeMillis()
            inst.sendPointerSync(android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN, bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0))
            inst.sendPointerSync(android.view.MotionEvent.obtain(now, now + 40, android.view.MotionEvent.ACTION_UP, bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0))
        }
        idle()
    }
    private fun activity(): MainActivity {
        inst.uiAutomation.serviceInfo = inst.uiAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        return inst.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    }
    private fun result() = AiPriceReference("E ACCEPTANCE PROBE", "Pro", "US", "USD", 2000,
        "MONTHLY", "before tax", "https://example.com", "USER_PROVIDED", null, 0, "unverified", "USD20/month", true)

    @Test fun e2_delayed_double_click_commits_once_and_reminder_failure_is_distinct() = runBlocking {
        val activity = activity()
        val c = (context.applicationContext as SubTrackApp).container
        val settings = c.awaitSettings()
        val calls = AtomicInteger(); val closed = AtomicInteger()
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var saved: Subscription? = null
        try {
            inst.runOnMainSync { activity.setContent {
                AiDraftEditor(result(), settings, onClose = { closed.incrementAndGet() }, save = { sub ->
                    calls.incrementAndGet(); started.complete(Unit); release.await()
                    saved = sub; c.repository.saveEditorDraft(null, sub)
                }, replan = { error("intentional post-commit reminder failure") })
            } }
            idle(); click("保存")
            withTimeout(5000) { started.await() }
            // Accessibility can send actions even while disabled; callback guards must also hold.
            val top = text("保存")
            var action: AccessibilityNodeInfo? = top
            while (action != null && !action.isClickable) action = action.parent
            action?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            val footer = text("保存订阅", true)
            var footerAction: AccessibilityNodeInfo? = footer
            while (footerAction != null && !footerAction.isClickable) footerAction = footerAction.parent
            footerAction?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            idle(); assertEquals(1, calls.get()); assertEquals(0, closed.get())
            release.complete(Unit)
            text("订阅与首期付款已保存；提醒重排失败，请从提醒健康度重试。不要重复创建。")
            assertEquals(1, calls.get())
            // 新建保存会补记一笔首期付款；双击只提交一次，所以流水也必须只有一笔。
            assertNotNull(c.repository.get(saved!!.id)); assertEquals(1, c.repository.paymentsOf(saved!!.id).size)
            click("知道了"); assertEquals(1, closed.get())
        } finally {
            release.complete(Unit)
            inst.runOnMainSync { activity.finish() }
            saved?.let { c.repository.archive(it.id); c.reminderService.cancelFor(it.id) }
        }
    }

    @Test fun e2_storage_failure_keeps_draft_and_retry_uses_same_identity() = runBlocking {
        val activity = activity()
        val c = (context.applicationContext as SubTrackApp).container
        val settings = c.awaitSettings()
        val identities = mutableListOf<String>()
        val closed = AtomicInteger()
        try {
            inst.runOnMainSync { activity.setContent {
                AiDraftEditor(result(), settings, onClose = { closed.incrementAndGet() }, save = { sub ->
                    identities += sub.id
                    if (identities.size == 1) error("intentional storage failure")
                    c.repository.saveEditorDraft(null, sub)
                }, replan = {})
            } }
            idle(); Thread.sleep(800); click("保存")
            assertEquals("save callback entered", 1, identities.size)
            idle(); Thread.sleep(300)
            // Fixed artifact path: app-private cache instead of /sdcard/Download. The old path failed with EACCES whenever
            // a previous install (different app uid) had left a same-named file behind — an environment artifact, not a
            // product behaviour; the screenshot's purpose is unchanged (pull with `run-as com.subtrack`).
            java.io.File(context.cacheDir, "subtrack-independent-storage-failure.png").outputStream().use { out ->
                inst.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }
            
            assertEquals(0, closed.get()); assertEquals(1, identities.size)
            assertNull(c.repository.get(identities.single()))
            repeat(15) { nodes().filter { it.isScrollable }.forEach { it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) }; idle() }
            click("保存", true)
            repeat(20) { if (closed.get() == 0) idle() }
            assertEquals(1, closed.get()); assertEquals(2, identities.size)
            assertEquals(identities.first(), identities.last())
            assertNotNull(c.repository.get(identities.last()))
            // 重试用的是同一个订阅 id；成功那一次只补记一笔首期付款，不会因为重试写两笔。
            assertEquals(1, c.repository.paymentsOf(identities.last()).size)
        } finally {
            inst.runOnMainSync { activity.finish() }
            identities.distinct().forEach { c.repository.archive(it); c.reminderService.cancelFor(it) }
        }
    }

    @Test fun e3_concurrent_network_patch_and_ordinary_settings_do_not_overwrite_each_other() = runBlocking {
        val c = (context.applicationContext as SubTrackApp).container
        val store = c.settingsStore
        val original = store.observe().first()
        try {
            coroutineScope {
                launch { store.updateNetwork { it.copy(aiBaseUrl = "https://two.example/v1", aiEnabled = true) } }
                launch { store.update(original.copy(monthlyBudgetMinor = 12345)) }
            }
            val after = store.observe().first()
            assertEquals("https://two.example/v1", after.network.aiBaseUrl)
            assertEquals(12345L, after.monthlyBudgetMinor)
        } finally { store.update(original); store.updateNetwork { original.network } }
    }

}
