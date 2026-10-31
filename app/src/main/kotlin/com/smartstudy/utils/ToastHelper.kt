package com.smartstudy.utils

import android.content.Context
import android.widget.Toast

/**
 * Toast显示工具类
 * 统一管理Toast的显示，避免重复代码
 *
 * 使用示例：
 * ```
 * ToastHelper.showShort(context, R.string.message)
 * ToastHelper.showLong(context, "错误信息")
 * ToastHelper.showShort(context, R.string.message, "参数")
 * ```
 */
object ToastHelper {
    /**
     * 显示短时Toast（默认2秒）
     *
     * @param context Context
     * @param message 消息文本
     */
    fun showShort(
        context: Context,
        message: String,
    ) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * 显示短时Toast（默认2秒）
     *
     * @param context Context
     * @param resId 字符串资源ID
     */
    fun showShort(
        context: Context,
        resId: Int,
    ) {
        Toast.makeText(context, resId, Toast.LENGTH_SHORT).show()
    }

    /**
     * 显示短时Toast（默认2秒），支持格式化参数
     *
     * @param context Context
     * @param resId 字符串资源ID
     * @param formatArgs 格式化参数
     */
    fun showShort(
        context: Context,
        resId: Int,
        vararg formatArgs: Any,
    ) {
        Toast.makeText(context, context.getString(resId, *formatArgs), Toast.LENGTH_SHORT).show()
    }

    /**
     * 显示长时Toast（默认3.5秒）
     *
     * @param context Context
     * @param message 消息文本
     */
    fun showLong(
        context: Context,
        message: String,
    ) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    /**
     * 显示长时Toast（默认3.5秒）
     *
     * @param context Context
     * @param resId 字符串资源ID
     */
    fun showLong(
        context: Context,
        resId: Int,
    ) {
        Toast.makeText(context, resId, Toast.LENGTH_LONG).show()
    }

    /**
     * 显示长时Toast（默认3.5秒），支持格式化参数
     *
     * @param context Context
     * @param resId 字符串资源ID
     * @param formatArgs 格式化参数
     */
    fun showLong(
        context: Context,
        resId: Int,
        vararg formatArgs: Any,
    ) {
        Toast.makeText(context, context.getString(resId, *formatArgs), Toast.LENGTH_LONG).show()
    }
}
