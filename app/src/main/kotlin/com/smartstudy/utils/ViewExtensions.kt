package com.smartstudy.utils

import android.content.Context
import android.view.View
import android.widget.Toast
import androidx.annotation.StringRes
import com.smartstudy.core.Logger

/**
 * View 扩展函数
 * 提供常用的 View 操作简化方法
 *
 * 安全地设置 View 的可见性
 * 如果 View 未初始化或已销毁，会记录警告但不抛出异常
 */
fun View?.safeSetVisibility(visibility: Int) {
    this?.visibility = visibility
}

/**
 * 安全地设置 View 的可见性（GONE）
 */
fun View?.hide() {
    safeSetVisibility(View.GONE)
}

/**
 * 安全地设置 View 的可见性（VISIBLE）
 */
fun View?.show() {
    safeSetVisibility(View.VISIBLE)
}

/**
 * 安全地设置 View 的可见性（INVISIBLE）
 */
fun View?.invisible() {
    safeSetVisibility(View.INVISIBLE)
}

/**
 * 安全地设置 View 的启用状态
 */
fun View?.safeSetEnabled(enabled: Boolean) {
    this?.isEnabled = enabled
}

/**
 * 安全地设置 View 的文本（仅适用于 TextView）
 */
fun android.widget.TextView?.safeSetText(text: String?) {
    this?.text = text ?: ""
}

/**
 * 显示Toast消息（使用字符串资源ID）
 *
 * @param messageResId 字符串资源ID
 * @param duration Toast显示时长，默认为LENGTH_SHORT
 */
fun Context.showToast(
    @StringRes messageResId: Int,
    duration: Int = Toast.LENGTH_SHORT,
) {
    try {
        Toast.makeText(this, messageResId, duration).show()
    } catch (e: android.view.WindowManager.BadTokenException) {
        Logger.e("ViewExtensions", "显示Toast失败: Activity窗口无效", e)
    } catch (e: IllegalStateException) {
        Logger.e("ViewExtensions", "显示Toast失败: 状态异常", e)
    }
}

/**
 * 显示Toast消息（使用字符串）
 *
 * @param message 要显示的消息
 * @param duration Toast显示时长，默认为LENGTH_SHORT
 */
fun Context.showToast(
    message: String?,
    duration: Int = Toast.LENGTH_SHORT,
) {
    if (message.isNullOrBlank()) return
    try {
        Toast.makeText(this, message, duration).show()
    } catch (e: android.view.WindowManager.BadTokenException) {
        Logger.e("ViewExtensions", "显示Toast失败: Activity窗口无效", e)
    } catch (e: IllegalStateException) {
        Logger.e("ViewExtensions", "显示Toast失败: 状态异常", e)
    }
}

/**
 * 显示长时Toast消息（使用字符串资源ID）
 *
 * @param messageResId 字符串资源ID
 */
fun Context.showLongToast(
    @StringRes messageResId: Int,
) {
    showToast(messageResId, Toast.LENGTH_LONG)
}

/**
 * 显示长时Toast消息（使用字符串）
 *
 * @param message 要显示的消息
 */
fun Context.showLongToast(message: String?) {
    showToast(message, Toast.LENGTH_LONG)
}

/**
 * 为列表项设置长按进入编辑模式的功能
 *
 * 这个扩展函数统一处理长按进入编辑模式的逻辑：
 * - 在 itemView 和指定的 EditText 上设置长按监听器
 * - 在非编辑模式下，让 EditText 不拦截普通触摸事件，确保长按事件能正常触发
 * - 在编辑模式下，清除这些监听器，恢复正常的编辑行为
 *
 * @param isEditMode 当前是否为编辑模式
 * @param onEnterEditMode 进入编辑模式的回调（应该调用 ViewModel.enterEditMode()）
 * @param editTexts 需要设置长按监听的 EditText 列表（可变参数）
 */
fun View.setupLongPressEditMode(
    isEditMode: Boolean,
    onEnterEditMode: () -> Unit,
    vararg editTexts: android.widget.EditText,
) {
    val longClickListener =
        View.OnLongClickListener {
            if (!isEditMode) {
                onEnterEditMode()
                true
            } else {
                false
            }
        }

    // 在 itemView 上设置长按监听器
    setOnLongClickListener(longClickListener)

    // 在非编辑模式下，让 EditText 也能响应长按事件
    if (!isEditMode) {
        editTexts.forEach { editText ->
            // 对于 disabled 的 EditText，长按监听器不会触发
            // 所以只通过 setOnTouchListener 让事件传递到 itemView
            // itemView 的长按监听器会处理长按事件
            editText.setOnTouchListener { v, event ->
                if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                    v.clearFocus()
                }
                // 返回 false 让事件继续传递到 itemView
                false
            }
        }
    } else {
        // 编辑模式下清除这些监听器
        editTexts.forEach { editText ->
            editText.setOnLongClickListener(null)
            editText.setOnTouchListener(null)
        }
    }
}
