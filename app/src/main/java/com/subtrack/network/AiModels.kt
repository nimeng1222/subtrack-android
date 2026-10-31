package com.subtrack.network

import com.subtrack.data.backup.JsonValue

/**
 * 模型列表（OpenAI 兼容的 `GET /models`）。
 *
 * 配置页以前只能手打模型名，拼错一个字母就只会得到一个 HTTP 400，用户还得自己去猜。
 * 这里把"从服务拉可用模型"独立出来：
 * - 地址由对话地址推导（`…/v1/chat/completions` → `…/v1/models`），不额外要用户填第二遍；
 * - 只接受 OpenAI（`data[].id`）与 Ollama（`models[].name`）两种常见形状；
 * - 解析是纯函数，便于 JVM 测试锁住形状变化。
 */
object AiModels {

    fun modelsUrl(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        require(base.startsWith("https://") || base.startsWith("http://")) {
            "服务地址需要以 http:// 或 https:// 开头"
        }
        val root = when {
            base.endsWith("/chat/completions") -> base.removeSuffix("/chat/completions")
            base.endsWith("/completions") -> base.removeSuffix("/completions")
            else -> base
        }
        return "$root/models"
    }

    fun parse(text: String): List<String> {
        val root = parseObject(text)
        val items = (root.fields["data"] as? JsonValue.Arr)?.items
            ?: (root.fields["models"] as? JsonValue.Arr)?.items
            ?: error("服务没有返回模型列表")
        val ids = items.mapNotNull { value ->
            val o = value as? JsonValue.Obj ?: return@mapNotNull null
            val id = (o.fields["id"] as? JsonValue.Str)?.value
                ?: (o.fields["name"] as? JsonValue.Str)?.value
                ?: (o.fields["model"] as? JsonValue.Str)?.value
            id?.trim()
        }.filter { it.isNotEmpty() && it.length <= 120 && ';' !in it && it.none { c -> c.isISOControl() } }
        require(ids.isNotEmpty()) { "服务返回的模型列表是空的" }
        return ids.distinct().sorted()
    }
}
