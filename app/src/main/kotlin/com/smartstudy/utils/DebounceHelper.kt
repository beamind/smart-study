package com.smartstudy.utils

import android.os.Handler
import android.os.Looper
import com.smartstudy.core.Logger

/**
 * 防抖工具类
 * 用于防止用户快速重复点击或操作
 *
 * 功能特性：
 * - 支持防抖延迟配置
 * - 自动清理，防止内存泄漏
 * - 线程安全
 *
 * 使用示例：
 * ```
 * val debounceHelper = DebounceHelper(delayMillis = 500L)
 * debounceHelper.debounce {
 *     // 执行操作
 *     performAction()
 * }
 * ```
 *
 * @param delayMillis 防抖延迟时间（毫秒），默认 500ms
 */
class DebounceHelper(
    private val delayMillis: Long = 500L,
) {
    companion object {
        private const val TAG = "DebounceHelper"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var runnable: Runnable? = null

    /**
     * 执行防抖操作
     * 如果在上一次调用后的 delayMillis 时间内再次调用，会取消上一次的调用
     *
     * @param action 要执行的操作
     */
    fun debounce(action: () -> Unit) {
        // 取消之前的操作
        runnable?.let { handler.removeCallbacks(it) }

        // 创建新的操作
        val newRunnable =
            Runnable {
                try {
                    action()
                } catch (e: IllegalStateException) {
                    Logger.e(TAG, "状态错误，防抖操作执行失败", e)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // 协程取消是正常情况，重新抛出
                    throw e
                } finally {
                    runnable = null
                }
            }
        runnable = newRunnable

        // 延迟执行
        handler.postDelayed(newRunnable, delayMillis)
    }

    /**
     * 取消待执行的防抖操作
     */
    fun cancel() {
        runnable?.let { handler.removeCallbacks(it) }
        runnable = null
    }

    /**
     * 清理资源
     * 应在不再使用时调用，防止内存泄漏
     */
    fun cleanup() {
        cancel()
        handler.removeCallbacksAndMessages(null)
    }
}
