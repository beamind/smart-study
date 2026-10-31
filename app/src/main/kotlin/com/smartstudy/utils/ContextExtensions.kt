package com.smartstudy.utils

import android.content.Context
import android.content.pm.PackageManager
import com.smartstudy.core.Logger

/**
 * Context 扩展函数
 * 提供常用的 Context 操作简化方法
 *
 * 检查是否拥有指定权限
 *
 * @param permission 权限名称
 * @return true 如果已授予权限，false 否则
 */
fun Context.hasPermission(permission: String): Boolean {
    return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}

/**
 * 检查是否拥有所有指定权限
 *
 * @param permissions 权限名称数组
 * @return true 如果所有权限都已授予，false 否则
 */
fun Context.hasAllPermissions(vararg permissions: String): Boolean {
    return permissions.all { hasPermission(it) }
}

/**
 * 检查是否拥有任一指定权限
 *
 * @param permissions 权限名称数组
 * @return true 如果至少有一个权限已授予，false 否则
 */
fun Context.hasAnyPermission(vararg permissions: String): Boolean {
    return permissions.any { hasPermission(it) }
}

/**
 * 安全地获取字符串资源
 * 如果资源不存在，返回默认值
 *
 * @param resId 资源ID
 * @param defaultValue 默认值
 * @return 字符串资源或默认值
 */
fun Context.getSafeString(
    @androidx.annotation.StringRes resId: Int,
    defaultValue: String = "",
): String {
    return try {
        getString(resId)
    } catch (e: android.content.res.Resources.NotFoundException) {
        Logger.w("ContextExtensions", "获取字符串资源失败: resId=$resId", e)
        defaultValue
    }
}

/**
 * 安全地获取字符串资源（带格式化参数）
 * 如果资源不存在，返回默认值
 *
 * @param resId 资源ID
 * @param defaultValue 默认值
 * @param formatArgs 格式化参数
 * @return 格式化后的字符串资源或默认值
 */
fun Context.getSafeString(
    @androidx.annotation.StringRes resId: Int,
    defaultValue: String = "",
    vararg formatArgs: Any,
): String {
    return try {
        getString(resId, *formatArgs)
    } catch (e: android.content.res.Resources.NotFoundException) {
        Logger.w("ContextExtensions", "获取字符串资源失败: resId=$resId", e)
        defaultValue
    }
}

/**
 * 安全地获取颜色资源
 * 如果资源不存在，返回默认值
 *
 * @param resId 资源ID
 * @param defaultValue 默认颜色值（ARGB格式）
 * @return 颜色值
 */
fun Context.getSafeColor(
    @androidx.annotation.ColorRes resId: Int,
    defaultValue: Int = 0xFF000000.toInt(),
): Int {
    return try {
        getColor(resId)
    } catch (e: android.content.res.Resources.NotFoundException) {
        Logger.w("ContextExtensions", "获取颜色资源失败: resId=$resId", e)
        defaultValue
    }
}

/**
 * 安全地获取尺寸资源（像素值）
 * 如果资源不存在，返回默认值
 *
 * @param resId 资源ID
 * @param defaultValue 默认值（像素）
 * @return 尺寸值（像素）
 */
fun Context.getSafeDimensionPixelSize(
    @androidx.annotation.DimenRes resId: Int,
    defaultValue: Int = 0,
): Int {
    return try {
        resources.getDimensionPixelSize(resId)
    } catch (e: android.content.res.Resources.NotFoundException) {
        Logger.w("ContextExtensions", "获取尺寸资源失败: resId=$resId", e)
        defaultValue
    }
}
