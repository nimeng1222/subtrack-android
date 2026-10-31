package com.subtrack.ai

/**
 * 对话入口地址的归一化。
 *
 * 用户经常只填到 `https://host/v1`（甚至 `…/v1beta/openai`）——模型列表能取到
 * （`/models` 就是在这个前缀下），但对话请求会 POST 到前缀本身，服务端往往回 403/404，
 * 于是出现"能列出模型、一发消息就报错"。这里只在**发送时**补全，不改用户已保存的值，
 * 也不影响密钥与服务地址的绑定关系。
 */
object AiEndpoint {

    fun chatUrl(saved: String): String {
        val url = saved.trim().trimEnd('/')
        if (url.isEmpty()) return url
        return when {
            url.endsWith("/chat/completions") -> url
            // /v1、/v2、/v4、/v1beta 这类版本前缀，以及 Gemini 的 OpenAI 兼容根路径。
            Regex(".*/v\\d+[a-zA-Z0-9]*$").matches(url) -> "$url/chat/completions"
            url.endsWith("/openai") -> "$url/chat/completions"
            else -> url
        }
    }

    /** 这个地址会不会被自动补全（设置页用它给出说明）。 */
    fun autoCompleted(saved: String): Boolean {
        val url = saved.trim()
        return url.isNotEmpty() && chatUrl(url) != url.trimEnd('/')
    }
}

/**
 * 服务拒绝 tool calling 时的降级判断。
 *
 * 有的服务对"这个模型不支持工具"不是回 400 说明原因，而是直接 403；也有服务确实会回
 * "tools is not supported" 的 400。两种都值得**去掉 tools 再试一次**——普通对话仍然可用，
 * 总比把一句 HTTP 403 丢给用户强。其余 401/404/429 等不在降级范围。
 */
object AiChatFallback {
    fun shouldRetryWithoutTools(code: Int, detail: String): Boolean =
        code == 403 || (
            code == 400 && (detail.contains("tool", ignoreCase = true) || detail.contains("function", ignoreCase = true))
            )
}
