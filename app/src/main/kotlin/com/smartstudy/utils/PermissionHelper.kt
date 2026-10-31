package com.smartstudy.utils

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import com.smartstudy.core.Logger

/**
 * 权限管理工具类
 *
 * 统一处理应用所需的权限请求，支持权限状态缓存和永久拒绝检测。
 *
 * 功能特性：
 * - 统一的权限检查接口
 * - 自动检测权限是否被永久拒绝
 * - 支持单个和批量权限请求
 * - 权限状态持久化缓存
 *
 * 使用示例：
 * ```
 * val permissionHelper = PermissionHelper(this)
 * if (!permissionHelper.checkAndRequestCamera()) {
 *     // 权限请求已发起，等待结果
 * }
 *
 * override fun onRequestPermissionsResult(...) {
 *     permissionHelper.handlePermissionResult(...)
 * }
 * ```
 *
 * @param activity Activity实例
 */
class PermissionHelper(private val activity: Activity) {
    companion object {
        private const val TAG = "PermissionHelper"
        private const val PREFS_NAME = "permission_status"
        private const val PREF_KEY_PERMANENTLY_DENIED = "permanently_denied_"
    }

    private val prefs = activity.getSharedPreferences(PREFS_NAME, Activity.MODE_PRIVATE)

    /**
     * 检查权限是否被永久拒绝（用户选择了"不再询问"）
     */
    fun isPermanentlyDenied(permission: String): Boolean {
        return prefs.getBoolean("${PREF_KEY_PERMANENTLY_DENIED}$permission", false)
    }

    /**
     * 标记权限为永久拒绝
     */
    private fun markPermanentlyDenied(permission: String) {
        prefs.edit().putBoolean("${PREF_KEY_PERMANENTLY_DENIED}$permission", true).apply()
    }

    /**
     * 清除权限的永久拒绝标记（当权限被授予时）
     */
    private fun clearPermanentlyDenied(permission: String) {
        prefs.edit().remove("${PREF_KEY_PERMANENTLY_DENIED}$permission").apply()
    }

    /**
     * 检查并请求相机权限
     * @return true 如果已有权限，false 如果需要请求权限
     */
    fun checkAndRequestCamera(): Boolean {
        return checkAndRequestPermission(Manifest.permission.CAMERA)
    }

    /**
     * 检查并请求麦克风权限
     * @return true 如果已有权限，false 如果需要请求权限
     */
    fun checkAndRequestMicrophone(): Boolean {
        return checkAndRequestPermission(Manifest.permission.RECORD_AUDIO)
    }

    /**
     * 检查并请求多个权限
     * @param permissions 权限列表
     * @param requestCode 请求码
     * @return true 如果所有权限都已授予，false 如果需要请求权限
     */
    fun checkAndRequestPermissions(
        permissions: Array<String>,
        requestCode: Int,
    ): Boolean {
        val permissionsNeeded = mutableListOf<String>()

        permissions.forEach { permission ->
            if (activity.checkSelfPermission(permission)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsNeeded.add(permission)
            }
        }

        if (permissionsNeeded.isNotEmpty()) {
            Logger.d(TAG, "请求权限: ${permissionsNeeded.joinToString(", ")}")
            activity.requestPermissions(
                permissionsNeeded.toTypedArray(),
                requestCode,
            )
            return false
        }

        Logger.d(TAG, "所有权限已授予")
        return true
    }

    /**
     * 检查单个权限
     * @param permission 权限名称
     * @return true 如果已有权限，false 如果需要请求权限
     */
    private fun checkAndRequestPermission(permission: String): Boolean {
        if (activity.checkSelfPermission(permission)
            == PackageManager.PERMISSION_GRANTED
        ) {
            return true
        }

        Logger.d(TAG, "请求权限: $permission")
        activity.requestPermissions(
            arrayOf(permission),
            com.smartstudy.core.AppConfig.REQUEST_PERMISSIONS,
        )
        return false
    }

    /**
     * 检查权限请求结果
     * @param requestCode 请求码
     * @param permissions 权限列表
     * @param grantResults 授权结果
     * @return true 如果所有权限都已授予，false 如果有权限被拒绝
     */
    fun handlePermissionResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ): Boolean {
        if (requestCode == com.smartstudy.core.AppConfig.REQUEST_PERMISSIONS) {
            val allGranted =
                grantResults.all {
                    it == PackageManager.PERMISSION_GRANTED
                }

            if (allGranted) {
                Logger.d(TAG, "权限已全部授予")
                // 清除永久拒绝标记
                permissions.forEach { clearPermanentlyDenied(it) }
                return true
            } else {
                val deniedPermissions = mutableListOf<String>()
                val permanentlyDeniedPermissions = mutableListOf<String>()

                permissions.forEachIndexed { index, permission ->
                    if (grantResults[index] != PackageManager.PERMISSION_GRANTED) {
                        deniedPermissions.add(permission)

                        // 检查是否被永久拒绝
                        if (!activity.shouldShowRequestPermissionRationale(permission)) {
                            permanentlyDeniedPermissions.add(permission)
                            markPermanentlyDenied(permission)
                        }
                    }
                }

                if (permanentlyDeniedPermissions.isNotEmpty()) {
                    Logger.w(TAG, "权限被永久拒绝（不再询问）: ${permanentlyDeniedPermissions.joinToString(", ")}")
                } else {
                    Logger.w(TAG, "权限被拒绝: ${deniedPermissions.joinToString(", ")}")
                }
                return false
            }
        }
        return false
    }

    /**
     * 检查是否已授予指定权限
     *
     * @param permission 权限名称
     * @return true 如果权限已授予，false 如果未授予
     */
    fun hasPermission(permission: String): Boolean {
        return activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 检查是否已授予所有指定权限
     *
     * @param permissions 权限列表
     * @return true 如果所有权限都已授予，false 如果有任何权限未授予
     */
    fun hasAllPermissions(permissions: Array<String>): Boolean {
        return permissions.all { hasPermission(it) }
    }

    /**
     * 检查是否已授予相机和麦克风权限（听写功能所需）
     *
     * @return true 如果两个权限都已授予，false 如果有任何权限未授予
     */
    fun hasDictationPermissions(): Boolean {
        return hasPermission(Manifest.permission.CAMERA) &&
            hasPermission(Manifest.permission.RECORD_AUDIO)
    }
}
