package com.smartstudy.utils

import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.smartstudy.core.AppConfig
import com.smartstudy.core.Logger

/**
 * 超时管理器
 *
 * 统一的超时管理工具，支持生命周期感知，自动清理资源，防止内存泄漏。
 *
 * 功能特性：
 * - 生命周期感知，Activity销毁时自动清理
 * - 支持启动、取消、重置超时
 * - 线程安全
 *
 * 使用示例：
 * ```
 * val timeoutManager = TimeoutManager(
 *     activity = this,
 *     timeoutMinutes = 10L,
 *     onTimeout = { handleTimeout() }
 * )
 * lifecycle.addObserver(timeoutManager)
 * timeoutManager.start()
 * ```
 *
 * @param activity Activity实例
 * @param timeoutMinutes 超时时间（分钟）
 * @param onTimeout 超时回调
 */
class TimeoutManager(
    private val activity: AppCompatActivity,
    private val timeoutMinutes: Long,
    private val onTimeout: () -> Unit,
) : DefaultLifecycleObserver {
    companion object {
        private const val TAG = "TimeoutManager"
    }

    private var timeoutHandler: Handler? = Handler(Looper.getMainLooper())
    private val timeoutRunnable =
        Runnable {
            // 检查Activity是否仍然有效
            if (!activity.isFinishing && !activity.isDestroyed) {
                Logger.w(TAG, "超时（${timeoutMinutes}分钟），自动停止")
                onTimeout()
            }
        }

    private var isActive = false

    /**
     * 启动超时计时器
     */
    fun start() {
        if (!isActive) {
            isActive = true
            cancel()
            timeoutHandler?.postDelayed(timeoutRunnable, timeoutMinutes * AppConfig.MILLIS_PER_MINUTE)
            Logger.d(TAG, "已启动超时计时器（${timeoutMinutes}分钟）")
        }
    }

    /**
     * 取消超时计时器
     */
    fun cancel() {
        timeoutHandler?.removeCallbacks(timeoutRunnable)
        isActive = false
    }

    /**
     * 重置超时计时器
     */
    fun reset() {
        if (isActive) {
            cancel()
            start()
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        super.onDestroy(owner)
        cleanup()
    }

    /**
     * 清理资源
     */
    fun cleanup() {
        cancel()
        timeoutHandler?.removeCallbacksAndMessages(null)
        timeoutHandler = null
        Logger.d(TAG, "超时管理器已清理")
    }
}
