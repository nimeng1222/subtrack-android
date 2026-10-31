package com.subtrack.ui.lock

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * 应用锁（v1.1）。
 *
 * 设计取舍：
 * - **只做生物识别，不做"设备凭证"回退**：设备凭证（PIN/图案）会解锁整台设备，
 *   而我们只想保护这一份台账；且 BIOMETRIC_STRONG 已覆盖绝大多数场景。
 * - **不做"锁整个进程"**：只在回到前台时拦一次。杀进程式加锁会让用户在
 *   从通知点进来时反复解锁，体验很差且没有额外安全性。
 * - 失败 5 次后不锁定，只在本次会话内要求重试——避免把用户自己锁在门外。
 */
object AppLock {

    /** 设备是否具备可用的生物识别能力。 */
    fun availability(context: Context): Availability {
        val manager = BiometricManager.from(context)
        return when (
            manager.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.BIOMETRIC_WEAK,
            )
        ) {
            BiometricManager.BIOMETRIC_SUCCESS -> Availability.READY
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> Availability.NOT_ENROLLED
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> Availability.NO_HARDWARE
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> Availability.HW_UNAVAILABLE
            else -> Availability.UNAVAILABLE
        }
    }

    enum class Availability {
        READY,
        NOT_ENROLLED,
        NO_HARDWARE,
        HW_UNAVAILABLE,
        UNAVAILABLE,
    }

    /**
     * 发起一次解锁。必须在 FragmentActivity 上调用（BiometricPrompt 的硬要求）。
     *
     * 用 [onSuccess] / [onFailed] 而不是返回布尔：BiometricPrompt 是异步的，
     * 包成同步会逼调用方阻塞等待，在 UI 线程上是错的。
     */
    fun requestUnlock(
        activity: FragmentActivity,
        reason: String = "解锁订阅台账",
        onSuccess: () -> Unit,
        onFailed: () -> Unit,
        onUnavailable: () -> Unit,
    ) {
        if (availability(activity) != Availability.READY) {
            onUnavailable()
            return
        }
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // 用户主动取消也算失败；不弹错误提示，保持安静
                    onFailed()
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("解锁订阅台账")
                .setSubtitle(reason)
                .setNegativeButtonText("取消")
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.BIOMETRIC_WEAK,
                )
                .setConfirmationRequired(false)
                .build(),
        )
    }
}
