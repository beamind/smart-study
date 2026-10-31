package com.smartstudy.utils

import android.app.ProgressDialog
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.smartstudy.core.Logger

/**
 * Activity 扩展函数
 * 提供常用的 Activity 操作简化方法
 *
 * 显示Toast消息（使用字符串资源ID）
 *
 * @param messageResId 字符串资源ID
 * @param duration Toast显示时长，默认为LENGTH_SHORT
 */
fun AppCompatActivity.showToast(
    @androidx.annotation.StringRes messageResId: Int,
    duration: Int = Toast.LENGTH_SHORT,
) {
    // 使用 ToastHelper 统一工具类，避免递归调用
    if (duration == Toast.LENGTH_SHORT) {
        ToastHelper.showShort(this, messageResId)
    } else {
        ToastHelper.showLong(this, messageResId)
    }
}

/**
 * 显示Toast消息（使用字符串）
 *
 * @param message 要显示的消息
 * @param duration Toast显示时长，默认为LENGTH_SHORT
 */
fun AppCompatActivity.showToast(
    message: String?,
    duration: Int = Toast.LENGTH_SHORT,
) {
    if (message.isNullOrBlank()) return
    // 使用 ToastHelper 统一工具类，避免递归调用
    if (duration == Toast.LENGTH_SHORT) {
        ToastHelper.showShort(this, message)
    } else {
        ToastHelper.showLong(this, message)
    }
}

/**
 * 显示长时Toast消息（使用字符串资源ID）
 *
 * @param messageResId 字符串资源ID
 */
fun AppCompatActivity.showLongToast(
    @androidx.annotation.StringRes messageResId: Int,
) {
    showToast(messageResId, Toast.LENGTH_LONG)
}

/**
 * 显示长时Toast消息（使用字符串）
 *
 * @param message 要显示的消息
 */
fun AppCompatActivity.showLongToast(message: String?) {
    showToast(message, Toast.LENGTH_LONG)
}

/**
 * 安全地显示ProgressDialog
 * 如果Activity已销毁或正在销毁，则不显示
 *
 * @param message 提示消息
 * @param cancelable 是否可取消
 * @return ProgressDialog实例，如果无法显示则返回null
 */
fun AppCompatActivity.showProgressDialog(
    message: String,
    cancelable: Boolean = false,
): ProgressDialog? {
    return if (!isFinishing && !isDestroyed) {
        try {
            ProgressDialog(this).apply {
                setMessage(message)
                setCancelable(cancelable)
                setCanceledOnTouchOutside(false)
                show()
            }
        } catch (e: android.view.WindowManager.BadTokenException) {
            Logger.e("ActivityExtensions", "显示ProgressDialog失败: Activity窗口无效", e)
            null
        } catch (e: IllegalStateException) {
            Logger.e("ActivityExtensions", "显示ProgressDialog失败: 状态异常", e)
            null
        }
    } else {
        null
    }
}

/**
 * 安全地关闭ProgressDialog
 *
 * @param dialog 要关闭的ProgressDialog
 */
fun AppCompatActivity.dismissProgressDialog(dialog: ProgressDialog?) {
    if (canDismissDialog(dialog)) {
        try {
            dialog?.dismiss()
        } catch (e: IllegalArgumentException) {
            Logger.e("ActivityExtensions", "关闭ProgressDialog失败: 参数异常", e)
        } catch (e: IllegalStateException) {
            Logger.e("ActivityExtensions", "关闭ProgressDialog失败: 状态异常", e)
        }
    }
}

/**
 * 检查是否可以关闭对话框
 */
private fun AppCompatActivity.canDismissDialog(dialog: ProgressDialog?): Boolean {
    if (isFinishing || isDestroyed) return false
    if (dialog == null) return false
    return dialog.isShowing
}

/**
 * 安全地显示AlertDialog
 * 如果Activity已销毁或正在销毁，则不显示
 *
 * @param title 标题
 * @param message 消息
 * @param positiveButtonText 确认按钮文本
 * @param negativeButtonText 取消按钮文本（可选）
 * @param onPositiveClick 确认按钮点击回调
 * @param onNegativeClick 取消按钮点击回调（可选）
 * @return AlertDialog实例，如果无法显示则返回null
 */
fun AppCompatActivity.showAlertDialog(
    title: String? = null,
    message: String,
    positiveButtonText: String = "确定",
    negativeButtonText: String? = null,
    onPositiveClick: (() -> Unit)? = null,
    onNegativeClick: (() -> Unit)? = null,
): AlertDialog? {
    return if (!isFinishing && !isDestroyed) {
        try {
            AlertDialog.Builder(this).apply {
                title?.let { setTitle(it) }
                setMessage(message)
                setPositiveButton(positiveButtonText) { _, _ ->
                    onPositiveClick?.invoke()
                }
                negativeButtonText?.let {
                    setNegativeButton(it) { _, _ ->
                        onNegativeClick?.invoke()
                    }
                }
            }.show()
        } catch (e: android.view.WindowManager.BadTokenException) {
            Logger.e("ActivityExtensions", "显示AlertDialog失败: Activity窗口无效", e)
            null
        } catch (e: IllegalStateException) {
            Logger.e("ActivityExtensions", "显示AlertDialog失败: 状态异常", e)
            null
        }
    } else {
        null
    }
}

/**
 * Fragment扩展：显示Toast消息
 */
fun Fragment.showToast(
    @androidx.annotation.StringRes messageResId: Int,
    duration: Int = Toast.LENGTH_SHORT,
) {
    activity?.showToast(messageResId, duration)
}

/**
 * Fragment扩展：显示Toast消息
 */
fun Fragment.showToast(
    message: String?,
    duration: Int = Toast.LENGTH_SHORT,
) {
    activity?.showToast(message, duration)
}

/**
 * Fragment扩展：显示长时Toast消息
 */
fun Fragment.showLongToast(
    @androidx.annotation.StringRes messageResId: Int,
) {
    activity?.showLongToast(messageResId)
}

/**
 * Fragment扩展：显示长时Toast消息
 */
fun Fragment.showLongToast(message: String?) {
    activity?.showLongToast(message)
}

/**
 * 安全地在主线程执行代码块
 * 如果Activity已销毁或正在销毁，则不执行
 *
 * @param block 要在主线程执行的代码块
 */
fun AppCompatActivity.runOnUiThreadSafe(block: () -> Unit) {
    if (!isFinishing && !isDestroyed) {
        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                block()
            }
        }
    }
}
