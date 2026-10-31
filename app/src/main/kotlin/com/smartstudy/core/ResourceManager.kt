package com.smartstudy.core

/**
 * 统一的资源管理接口
 * 用于在Activity/Fragment销毁时统一释放资源，防止资源泄漏
 *
 * 使用示例：
 * ```
 * class MyActivity : AppCompatActivity(), ResourceManager {
 *     override fun releaseResources() {
 *         // 释放资源
 *     }
 * }
 *
 * override fun onDestroy() {
 *     super.onDestroy()
 *     releaseResources()
 * }
 * ```
 */
interface ResourceManager {
    /**
     * 释放所有资源
     * 应在onDestroy中调用
     */
    fun releaseResources()
}

/**
 * 资源管理工具类
 * 提供统一的资源释放辅助方法，确保资源释放过程的安全性
 *
 * 功能特性：
 * - 安全释放：捕获异常，防止释放过程中的错误影响其他资源释放
 * - 批量释放：支持一次性释放多个资源管理器
 *
 * 使用示例：
 * ```
 * // 安全释放单个资源
 * ResourceManagerHelper.safeRelease {
 *     bitmap.recycle()
 * }
 *
 * // 批量释放资源
 * ResourceManagerHelper.releaseAll(manager1, manager2, manager3)
 * ```
 */
object ResourceManagerHelper {
    @PublishedApi
    internal const val TAG = "ResourceManager"

    /**
     * 安全释放资源（捕获异常，防止释放过程中的错误影响其他资源释放）
     *
     * 这是一个内联函数，用于安全地执行资源释放操作。如果释放过程中发生异常，
     * 会记录日志但不会抛出异常，确保其他资源的释放不受影响。
     *
     * @param block 资源释放代码块
     * @return 代码块的返回值，如果发生异常则返回null
     */
    inline fun <T> safeRelease(block: () -> T): T? {
        return try {
            block()
        } catch (e: Exception) {
            Logger.e(TAG, "释放资源时发生异常", e)
            null
        }
    }

    /**
     * 批量释放资源
     *
     * 遍历所有提供的资源管理器，逐个安全释放。即使某个资源释放失败，
     * 也会继续释放其他资源。
     *
     * @param managers 要释放的资源管理器列表（可以为null，会被自动跳过）
     */
    fun releaseAll(vararg managers: ResourceManager?) {
        managers.forEach { manager ->
            manager?.let {
                safeRelease { it.releaseResources() }
            }
        }
    }
}
