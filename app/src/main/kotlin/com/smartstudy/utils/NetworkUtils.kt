package com.smartstudy.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.smartstudy.core.Logger

/**
 * 网络工具类
 * 提供网络状态检查功能
 *
 * 功能特性：
 * - 检查网络连接状态
 * - 检查网络类型（WiFi、移动数据等）
 * - 提供友好的错误提示
 *
 * 使用示例：
 * ```
 * if (!NetworkUtils.isNetworkAvailable(context)) {
 *     showError("网络不可用，请检查网络连接")
 * }
 * ```
 */
object NetworkUtils {
    private const val TAG = "NetworkUtils"

    /**
     * 检查网络是否可用
     *
     * @param context Android Context
     * @return true 如果网络可用，false 否则
     */
    fun isNetworkAvailable(context: Context): Boolean {
        return try {
            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (e: SecurityException) {
            Logger.e(TAG, "检查网络状态失败: 权限异常", e)
            false
        } catch (e: IllegalStateException) {
            Logger.e(TAG, "检查网络状态失败: 状态异常", e)
            false
        }
    }

    /**
     * 获取网络类型描述
     *
     * @param context Android Context
     * @return 网络类型描述（如 "WiFi"、"移动数据"、"无网络"）
     */
    fun getNetworkType(context: Context): String {
        return try {
            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            val network = connectivityManager.activeNetwork ?: return "无网络"
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return "无网络"

            when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动数据"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网"
                else -> "未知网络"
            }
        } catch (e: SecurityException) {
            Logger.e(TAG, "获取网络类型失败: 权限异常", e)
            "未知"
        } catch (e: IllegalStateException) {
            Logger.e(TAG, "获取网络类型失败: 状态异常", e)
            "未知"
        }
    }
}
