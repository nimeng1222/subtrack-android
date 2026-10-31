package com.subtrack.network

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import com.subtrack.domain.model.UpdateManifest
import java.io.File
import java.security.MessageDigest

class UpdateInstaller(private val context: Context, private val transport: NetworkTransport) {
    suspend fun download(manifest: UpdateManifest): File {
        // GitHub 资产会 302 到 objects.githubusercontent.com；只允许 GitHub 自家的跳转主机。
        val bytes = transport.request(
            manifest.downloadUrl,
            maxBytes = UpdateVerifier.MAX_APK.toInt(),
            userAgent = "SubTrack-Android/${com.subtrack.BuildConfig.VERSION_NAME}",
            redirectHosts = GithubRelease.DOWNLOAD_HOSTS,
        )
        require(bytes.size.toLong() == manifest.sizeBytes) { "下载大小与签名清单不一致" }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        // 自签清单一定带哈希；GitHub 只有在资产有 digest 时才带，没有就跳过这一步——
        // 但大小、包名、版本码与**证书必须与当前安装一致**仍然一条不少。
        require(manifest.sha256.isBlank() || hash == manifest.sha256) { "APK SHA-256 校验失败" }
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val file = File(dir, "candidate.apk")
        file.writeBytes(bytes)
        try { verifyPackage(file, manifest) }
        catch (e: Exception) { file.delete(); throw e }
        return file
    }

    @Suppress("DEPRECATION")
    private fun verifyPackage(file: File, manifest: UpdateManifest) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: error("无法读取 APK")
        val installed = pm.getPackageInfo(context.packageName, flags)
        require(archive.packageName == context.packageName && archive.packageName == manifest.packageName) { "APK 包名不匹配" }
        val code = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        val current = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        require(code == manifest.versionCode && code > current) { "APK 版本码不匹配或不是升级" }
        require(archive.applicationInfo?.minSdkVersion?.let { it <= Build.VERSION.SDK_INT } == true)
        // Strict same-signer policy. Legal rotation is not enabled without explicit release policy.
        val old = if (Build.VERSION.SDK_INT >= 28) installed.signingInfo?.apkContentsSigners else installed.signatures
        val new = if (Build.VERSION.SDK_INT >= 28) archive.signingInfo?.apkContentsSigners else archive.signatures
        require(sameSignerSet(old, new)) {
            "APK 证书与当前安装不兼容（Debug 与正式签名不能覆盖）；保留原应用与数据"
        }
    }

    companion object {
        /** Strict same-certificate policy: both signer sets must be non-empty and contain identical certificates. */
        fun sameSignerSet(
            old: Array<android.content.pm.Signature>?,
            new: Array<android.content.pm.Signature>?,
        ): Boolean = !old.isNullOrEmpty() && !new.isNullOrEmpty() && old.toSet() == new.toSet()
    }

    fun install(file: File) {
        context.startActivity(installIntent(file))
    }

    /** Guard + explicit user-confirmed system-installer intent. Never a silent install: ACTION_VIEW + package-archive
     *  MIME routed to the system installer; the candidate must live inside our own cache/updates directory. */
    internal fun installIntent(file: File): Intent {
        require(file.parentFile?.canonicalPath == File(context.cacheDir, "updates").canonicalPath && file.exists()) {
            "仅允许安装缓存目录内的已校验候选包"
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
