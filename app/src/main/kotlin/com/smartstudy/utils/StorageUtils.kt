package com.smartstudy.utils

import android.content.Context
import android.os.Environment
import android.os.StatFs
import com.smartstudy.core.Logger

/**
 * 存储工具类
 * 提供存储空间检查功能
 *
 * 功能特性：
 * - 检查可用存储空间
 * - 检查存储空间是否充足
 * - 提供友好的错误提示
 *
 * 使用示例：
 * ```
 * val availableSpace = StorageUtils.getAvailableSpaceMB(context)
 * if (availableSpace < 100) {
 *     showError("存储空间不足，请清理空间后重试")
 * }
 * ```
 */
object StorageUtils {
    private const val TAG = "StorageUtils"

    /**
     * 最小可用空间（MB），低于此值会提示用户清理空间
     */
    private const val MIN_AVAILABLE_SPACE_MB = 100L

    /**
     * 获取可用存储空间（MB）
     *
     * @param context Android Context
     * @return 可用存储空间（MB），如果无法获取则返回-1
     */
    fun getAvailableSpaceMB(context: Context): Long {
        return try {
            val path = Environment.getExternalStorageDirectory()
            val stat = StatFs(path.path)
            val availableBytes = stat.availableBytes
            availableBytes / (1024 * 1024)
        } catch (e: IllegalArgumentException) {
            Logger.e(TAG, "获取可用存储空间失败: 参数异常", e)
            -1L
        } catch (e: SecurityException) {
            Logger.e(TAG, "获取可用存储空间失败: 权限异常", e)
            -1L
        }
    }

    /**
     * 检查存储空间是否充足
     *
     * @param context Android Context
     * @param requiredSpaceMB 需要的存储空间（MB）
     * @return true 如果存储空间充足，false 否则
     */
    fun hasEnoughSpace(
        context: Context,
        requiredSpaceMB: Long = MIN_AVAILABLE_SPACE_MB,
    ): Boolean {
        val availableSpace = getAvailableSpaceMB(context)
        if (availableSpace < 0) {
            // 无法获取存储空间信息，假设空间充足
            Logger.w(TAG, "无法获取存储空间信息，假设空间充足")
            return true
        }
        return availableSpace >= requiredSpaceMB
    }

    /**
     * 获取存储空间不足的错误消息
     *
     * @param context Android Context
     * @param requiredSpaceMB 需要的存储空间（MB）
     * @return 错误消息，如果空间充足则返回null
     */
    fun getStorageSpaceErrorMessage(
        context: Context,
        requiredSpaceMB: Long = MIN_AVAILABLE_SPACE_MB,
    ): String? {
        if (hasEnoughSpace(context, requiredSpaceMB)) {
            return null
        }
        val availableSpace = getAvailableSpaceMB(context)
        return "存储空间不足（可用：${availableSpace}MB，需要：${requiredSpaceMB}MB），请清理空间后重试"
    }
}
