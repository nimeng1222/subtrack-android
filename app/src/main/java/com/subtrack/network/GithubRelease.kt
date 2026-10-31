package com.subtrack.network

import com.subtrack.data.backup.JsonValue
import com.subtrack.domain.model.UpdateManifest

/**
 * GitHub Releases 更新通道。
 *
 * 之前"检查更新"只认一份自签清单：需要把公钥编进 APK、把清单签名、自己托管，
 * 结果 Debug 包（未固定公钥）里这个功能永远是"无可信发布配置"，等于没有。
 * 这里加一条同包名可覆盖的公开通道：
 *
 * - 只访问 `https://api.github.com/repos/{owner}/{repo}/releases/latest`（仓库由构建注入）；
 * - 版本号取 release tag（`v1.9.3`），版本码取资产名里的尾数字（`SubTrack-1.9.3-60.apk`），
 *   两者不一致直接拒绝——绝不靠猜；
 * - 下载地址必须是 github.com 的 https 资产地址；跳转只允许 GitHub 自家的几个域名；
 * - 下载后仍走既有硬校验：大小、版本码必须更大、包名、**证书必须与当前安装是同一张**，
 *   最后交给系统安装器确认。没有放宽任何一步，也没有静默安装。
 *
 * 自签清单通道（带公钥验签）仍然保留：填了清单地址就优先走它。
 */
data class GithubReleaseInfo(
    val versionName: String,
    val versionCode: Long,
    val assetName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val sha256: String?,
    val releaseNotes: String?,
    val publishedAt: String?,
) {
    fun toManifest(): UpdateManifest = UpdateManifest(
        packageName = "com.subtrack",
        channel = GithubRelease.CHANNEL,
        versionName = versionName,
        versionCode = versionCode,
        minSdk = 26,
        downloadUrl = downloadUrl,
        sizeBytes = sizeBytes,
        // GitHub 没给 digest 时留空：下载后仍校验大小、版本与签名证书，只是没有远端哈希可比。
        sha256 = sha256.orEmpty(),
        releaseNotes = releaseNotes,
        publishedAt = publishedAt,
    )
}

object GithubRelease {
    const val CHANNEL = "github"
    const val API_HOST = "api.github.com"

    /** 资产下载与它的跳转只允许这些主机。 */
    val DOWNLOAD_HOSTS = setOf(
        "github.com",
        "www.github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
        "api.github.com",
    )

    fun latestUrl(repo: String): String {
        val trimmed = repo.trim()
        require(trimmed.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})/[A-Za-z0-9._-]{1,100}"))) {
            "GitHub 仓库名应为 owner/repo"
        }
        return "https://$API_HOST/repos/$trimmed/releases/latest"
    }

    fun decode(text: String): GithubReleaseInfo {
        val root = parseObject(text)
        val tag = root.text("tag_name") ?: error("发布缺少 tag")
        val versionName = Regex("^v?(\\d+(?:\\.\\d+){1,3})$").find(tag)?.groupValues?.get(1)
            ?: error("发布 tag 不是版本号：$tag")
        val assets = (root.fields["assets"] as? JsonValue.Arr)?.items.orEmpty().mapNotNull { it as? JsonValue.Obj }
        val candidates = assets.mapNotNull { asset ->
            val name = (asset.fields["name"] as? JsonValue.Str)?.value ?: return@mapNotNull null
            val match = Regex("(?i)^subtrack[-_].*-(\\d+)\\.apk$").find(name) ?: return@mapNotNull null
            val code = match.groupValues[1].toLongOrNull()?.takeIf { it > 0L } ?: return@mapNotNull null
            if (!name.contains(versionName)) return@mapNotNull null
            val url = (asset.fields["browser_download_url"] as? JsonValue.Str)?.value ?: return@mapNotNull null
            val size = (asset.fields["size"] as? JsonValue.Num)?.raw?.toLongOrNull() ?: return@mapNotNull null
            ReleaseAsset(name, code, url, size, (asset.fields["digest"] as? JsonValue.Str)?.value)
        }
        require(candidates.isNotEmpty()) { "这个发布里没有「SubTrack-版本-版本码.apk」资产" }
        val picked = candidates.maxBy { it.versionCode }
        val url = NetworkUrls.validate(picked.downloadUrl)
        require(url.host in DOWNLOAD_HOSTS) { "下载地址不是 GitHub 资产域名" }
        require(picked.sizeBytes in 1..UpdateVerifier.MAX_APK) { "安装包大小超出上限" }
        val digest = picked.digest?.trim()?.takeIf { it.startsWith("sha256:") }
            ?.removePrefix("sha256:")?.lowercase()?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }
        return GithubReleaseInfo(
            versionName = versionName,
            versionCode = picked.versionCode,
            assetName = picked.name,
            downloadUrl = picked.downloadUrl,
            sizeBytes = picked.sizeBytes,
            sha256 = digest,
            releaseNotes = root.text("body")?.take(2000),
            publishedAt = root.text("published_at"),
        )
    }

    private data class ReleaseAsset(
        val name: String,
        val versionCode: Long,
        val downloadUrl: String,
        val sizeBytes: Long,
        val digest: String?,
    )
}
