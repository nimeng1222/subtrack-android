package com.subtrack.ui.network

import android.os.Build
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.subtrack.BuildConfig
import com.subtrack.domain.model.NetworkSettings
import com.subtrack.domain.model.UpdateManifest
import com.subtrack.network.*
import com.subtrack.ui.theme.InkSecondary
import com.subtrack.ui.theme.Type
import com.subtrack.ui.theme.Warning
import com.subtrack.ui.settings.ActionStyle
import com.subtrack.ui.settings.SettingsButton
import com.subtrack.ui.settings.SettingsPaddedContent
import com.subtrack.ui.settings.SettingsTextAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

@Composable internal fun UpdatePanel(config: NetworkSettings, transport: NetworkTransport, authorization: RequestLease) {
    key(config.updateEnabled, config.updateManifestUrl, config.connectionRevision) {
        UpdateSession(config, transport, authorization)
    }
}

/**
 * 检查更新的动作区。
 *
 * 保护链一步没少：HTTPS 清单 → 内置公钥验签 → 版本号必须更大 → 下载后校验哈希/版本/证书 →
 * 交给系统安装器由用户确认。这里只统一了外观与状态呈现，没有放宽任何校验。
 */
@Composable private fun UpdateSession(config: NetworkSettings, transport: NetworkTransport, authorization: RequestLease) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val signedSource = config.updateManifestUrl.trim()
    val githubRepo = BuildConfig.UPDATE_GITHUB_REPO.trim()
    val usingGithub = signedSource.isBlank() && githubRepo.isNotBlank()
    var message by remember {
        mutableStateOf(
            "当前 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）。" +
                when {
                    signedSource.isNotBlank() -> "更新源：自签清单（需要内置公钥验签）。"
                    githubRepo.isNotBlank() -> "更新源：GitHub Releases（$githubRepo）。"
                    else -> "这个构建没有配置更新源。"
                },
        )
    }
    var failed by remember { mutableStateOf(false) }
    var manifest by remember { mutableStateOf<UpdateManifest?>(null) }
    var downloaded by remember { mutableStateOf<File?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val lease = remember { RequestLease() }
    val sessionAuthorization = remember { authorization.ticket() }
    DisposableEffect(Unit) { onDispose { lease.close(); downloaded?.delete() } }

    // 用户去系统设置里允许"安装未知应用"再回来时，页面必须自己刷新这个状态；
    // 否则按钮仍写着"去开启"，点一下又被送回同一个设置页。
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var canInstall by remember { mutableStateOf(context.packageManager.canRequestPackageInstalls()) }
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                canInstall = context.packageManager.canRequestPackageInstalls()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SettingsPaddedContent(top = 12.dp, bottom = 2.dp) {
        Text(
            message,
            modifier = Modifier.testTag("update-status"),
            style = Type.footnote,
            color = if (failed) Warning else InkSecondary,
        )
    }
    SettingsPaddedContent(top = 8.dp, bottom = 10.dp) {
        SettingsButton(
            text = "检查新版本",
            onClick = {
                val ticket = lease.ticket()
                val authorizationTicket = authorization.ticket()
                job = scope.launch {
                    manifest = null; downloaded = null; failed = false
                    message = "正在检查更新…"
                    try {
                        val m: UpdateManifest = when {
                            // 自签清单优先：填了地址就必须验签，公钥没固定就明确报错，不偷偷换成 GitHub。
                            signedSource.isNotBlank() -> {
                                require(BuildConfig.UPDATE_PUBLIC_KEY.isNotBlank()) {
                                    "填了自签清单地址，但这个构建没有内置可信公钥"
                                }
                                val text = transport.request(signedSource).toString(Charsets.UTF_8)
                                UpdateVerifier.decode(
                                    text, BuildConfig.UPDATE_PUBLIC_KEY, BuildConfig.VERSION_CODE.toLong(),
                                    Build.VERSION.SDK_INT, BuildConfig.UPDATE_CHANNEL,
                                )
                            }
                            githubRepo.isNotBlank() -> {
                                val text = transport.request(
                                    GithubRelease.latestUrl(githubRepo),
                                    accept = "application/vnd.github+json",
                                    userAgent = "SubTrack-Android/${BuildConfig.VERSION_NAME}",
                                    throttle = false,
                                ).toString(Charsets.UTF_8)
                                GithubRelease.decode(text).toManifest()
                            }
                            else -> error("这个构建没有配置更新源")
                        }
                        if (!lease.valid(ticket) || !authorization.valid(authorizationTicket) || !config.updateEnabled) return@launch
                        if (m.versionCode <= BuildConfig.VERSION_CODE) {
                            message = "已是最新：${m.versionName} / ${m.versionCode}" +
                                if (usingGithub) "（GitHub 最新发布）" else ""
                        } else {
                            manifest = m
                            message = "发现新版本 ${m.versionName}（${m.versionCode}），${m.sizeBytes} 字节\n" +
                                "发布：${m.publishedAt ?: "未知"}\n${m.releaseNotes.orEmpty()}"
                        }
                    } catch (e: CancellationException) {
                        message = "已取消检查"; throw e
                    } catch (e: Exception) {
                        message = "检查失败：${e.message?.take(150)}"; failed = true
                    }
                }
                lease.attach(job!!)
                authorization.attach(job!!)
            },
            style = ActionStyle.Secondary,
            enabled = config.updateEnabled && job?.isActive != true,
            tag = "update-check",
        )
        manifest?.takeIf { config.updateEnabled }?.let { m ->
            Spacer(Modifier.height(8.dp))
            SettingsButton(
                text = "下载并校验（${m.sizeBytes} 字节）",
                onClick = {
                    job = scope.launch {
                        message = "正在下载并校验…"; failed = false
                        val ticket = lease.ticket()
                        val authorizationTicket = authorization.ticket()
                        try {
                            val file = UpdateInstaller(context, transport).download(m)
                            if (lease.valid(ticket) && authorization.valid(authorizationTicket) && config.updateEnabled) {
                                downloaded = file
                                message = "大小、哈希、版本与当前证书一致；等待你确认安装"
                            } else file.delete()
                        } catch (e: CancellationException) {
                            message = "已取消下载"; throw e
                        } catch (e: Exception) {
                            message = "下载/校验失败：${e.message?.take(150)}"; failed = true
                        }
                    }
                    lease.attach(job!!)
                    authorization.attach(job!!)
                },
                style = ActionStyle.Secondary,
                enabled = job?.isActive != true,
                tag = "update-download",
            )
        }
        if (job?.isActive == true) {
            Spacer(Modifier.height(6.dp))
            SettingsTextAction("取消更新请求", onClick = { job?.cancel() })
        }
        downloaded?.takeIf { config.updateEnabled }?.let { f ->
            Spacer(Modifier.height(8.dp))
            // 系统级开关：没允许"安装未知应用"时，直接拉起安装器只会弹一个没有解释的系统对话框。
            // 这里先说明并给一键跳转，回到应用后再点安装，已校验的候选包还在。
            if (!canInstall) {
                Text(
                    "系统还没允许本应用安装应用。先点下面按钮去开启，回到这里再确认安装。",
                    style = Type.footnote,
                    color = Warning,
                    modifier = Modifier.testTag("update-permission-hint"),
                )
                Spacer(Modifier.height(8.dp))
            }
            SettingsButton(
                text = if (canInstall) "交给系统安装器（需你确认）" else "去开启「安装未知应用」",
                onClick = {
                    if (authorization.valid(sessionAuthorization)) {
                        if (!canInstall) {
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(
                                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                        android.net.Uri.parse("package:${context.packageName}"),
                                    ),
                                )
                            }.onFailure {
                                message = "打不开系统设置：请在系统「安装未知应用」里手动允许本应用"
                                failed = true
                            }
                            message = "已打开系统设置：允许「安装未知应用」后返回，再点一次安装"
                        } else {
                            runCatching { UpdateInstaller(context, transport).install(f) }
                                .onFailure {
                                    message = "安装器不可用：请检查是否允许本应用安装应用；原应用不会被删除"
                                    failed = true
                                }
                        }
                    }
                },
                tag = "update-install",
            )
        }
    }
}
