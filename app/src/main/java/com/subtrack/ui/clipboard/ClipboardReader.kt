package com.subtrack.ui.clipboard

import android.content.ClipboardManager
import android.content.Context
import com.subtrack.domain.parse.PaymentTextParser

/**
 * 剪贴板读取（L3 零权限方案）。
 *
 * Android 10+ 限制"后台读剪贴板"，但我们只在前台、且用户主动点开时读，
 * 正好落在允许范围内。系统还会弹一个"已读取剪贴板"的提示——
 * 这不是负担，反而是透明度：用户知道自己被读了什么。
 *
 * 刻意**不做后台轮询**：那既要额外权限，也会让"隐私零权限"这个卖点变味。
 */
object ClipboardReader {

    /**
     * 读当前剪贴板并尝试解析成支付信息。
     *
     * @return 解析成功返回草稿；剪贴板为空、内容不是支付文案、或无权限时一律 null。
     *   **静默失败**是刻意的：用户只是复制了别的东西，不该被弹窗打扰。
     */
    fun readPayment(context: Context): PaymentTextParser.Parsed? {
        val text = runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return null
            val clip = cm.primaryClip ?: return null
            if (clip.itemCount == 0) return null
            clip.getItemAt(0).coerceToText(context)?.toString()
        }.getOrNull()

        return PaymentTextParser.parse(text)
    }
}
