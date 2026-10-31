package com.subtrack.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型列表与 GitHub 更新清单的解析。
 *
 * 这两处都是"解析错也不抛异常"的地方：模型名取错只是请求失败，更新版本码取错会
 * 让用户反复被提示升级（或永远升不了）。所以逐个形状锁住。
 */
class GithubReleaseAndModelsTest {

    @Test fun models_url_is_derived_from_chat_endpoint() {
        assertEquals(
            "https://api.example.com/v1/models",
            AiModels.modelsUrl("https://api.example.com/v1/chat/completions"),
        )
        assertEquals("https://api.example.com/v1/models", AiModels.modelsUrl("https://api.example.com/v1/"))
        assertEquals("https://api.example.com/models", AiModels.modelsUrl("https://api.example.com"))
        // 只有 http/https 才接受；否则拼出来的地址会被网络层拒绝，不如这里先说清楚。
        assertTrue(runCatching { AiModels.modelsUrl("ftp://example.com/v1") }.isFailure)
    }

    @Test fun openai_and_ollama_model_lists_both_parse() {
        val openai = """{"object":"list","data":[{"id":"gpt-4o-mini"},{"id":"gpt-4o"},{"id":"gpt-4o"}]}"""
        assertEquals(listOf("gpt-4o", "gpt-4o-mini"), AiModels.parse(openai))
        val ollama = """{"models":[{"name":"qwen2.5:7b"},{"name":"llama3.2:3b"}]}"""
        assertEquals(listOf("llama3.2:3b", "qwen2.5:7b"), AiModels.parse(ollama))
    }

    @Test fun empty_or_shapeless_model_list_is_an_error() {
        assertTrue(runCatching { AiModels.parse("""{"data":[]}""") }.isFailure)
        assertTrue(runCatching { AiModels.parse("""{"object":"list"}""") }.isFailure)
        // 只有非法 id 时也必须报错，不能返回空列表让用户以为"服务没模型"。
        assertTrue(runCatching { AiModels.parse("""{"data":[{"id":""},{"id":"a;b"}]}""") }.isFailure)
    }

    private val release = """
        {"tag_name":"v1.9.3","body":"修好已付总额等 5 项","published_at":"2026-10-31T10:00:00Z",
         "assets":[
           {"name":"notes.txt","size":10,"browser_download_url":"https://github.com/o/r/releases/download/v1.9.3/notes.txt"},
           {"name":"SubTrack-1.9.3-60.apk","size":14999111,
            "browser_download_url":"https://github.com/o/r/releases/download/v1.9.3/SubTrack-1.9.3-60.apk",
            "digest":"sha256:${"a".repeat(64)}"}
         ]}
    """.trimIndent()

    @Test fun github_release_picks_the_apk_and_reads_version_code_from_name() {
        val info = GithubRelease.decode(release)
        assertEquals("1.9.3", info.versionName)
        assertEquals(60L, info.versionCode)
        assertEquals("SubTrack-1.9.3-60.apk", info.assetName)
        assertEquals(14_999_111L, info.sizeBytes)
        assertEquals("a".repeat(64), info.sha256)
        val manifest = info.toManifest()
        assertEquals("com.subtrack", manifest.packageName)
        assertEquals(GithubRelease.CHANNEL, manifest.channel)
        assertEquals(60L, manifest.versionCode)
    }

    @Test fun release_without_digest_still_decodes_but_without_hash() {
        val noDigest = release.replace(Regex(""",\s*"digest":"sha256:[a-f0-9]+""""), "")
        assertNull(GithubRelease.decode(noDigest).sha256)
    }

    @Test fun tag_that_is_not_a_version_is_refused() {
        assertTrue(runCatching { GithubRelease.decode(release.replace("\"v1.9.3\"", "\"nightly\"")) }.isFailure)
    }

    @Test fun asset_version_must_match_the_tag() {
        // 发错包（tag 1.9.3 但资产还是 1.9.2）必须拒绝，而不是把旧包当新版推给用户。
        assertTrue(
            runCatching { GithubRelease.decode(release.replace("SubTrack-1.9.3-60", "SubTrack-1.9.2-58")) }.isFailure,
        )
    }

    @Test fun download_url_must_be_github() {
        assertTrue(
            runCatching {
                GithubRelease.decode(
                    release.replace(
                        "https://github.com/o/r/releases/download/v1.9.3/SubTrack-1.9.3-60.apk",
                        "https://evil.example/SubTrack-1.9.3-60.apk",
                    ),
                )
            }.isFailure,
        )
    }

    @Test fun repository_argument_is_validated() {
        assertTrue(GithubRelease.latestUrl("owner/repo").endsWith("/repos/owner/repo/releases/latest"))
        assertTrue(runCatching { GithubRelease.latestUrl("not a repo") }.isFailure)
    }
}
