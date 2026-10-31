package com.smartstudy.dictation.core.managers

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.smartstudy.R
import com.smartstudy.core.AppConfig
import com.smartstudy.core.Logger
import com.smartstudy.utils.ToastHelper

/**
 * 听写UI管理器
 *
 * 负责管理听写Activity的UI逻辑，包括：
 * - 对话框创建和显示（错题听写对话框、权限错误对话框等）
 * - UI状态更新（按钮文本、视图可见性等）
 * - 统一的UI操作封装
 *
 * 使用示例：
 * ```
 * val uiManager = DictationUIManager(
 *     activity = this,
 *     viewManager = viewManager
 * )
 * uiManager.showErrorDictationDialog { count -> startErrorDictation(count) }
 * ```
 *
 * @param activity Activity实例
 * @param viewManager 视图管理器实例
 */
class DictationUIManager(
    private val activity: AppCompatActivity,
    private val viewManager: com.smartstudy.dictation.core.handlers.DictationViewManager,
) {
    companion object {
        private const val TAG = "DictationUIManager"
    }

    /**
     * 显示错题听写对话框
     *
     * @param onConfirm 确认回调，参数为题目数量
     */
    fun showErrorDictationDialog(onConfirm: (Int) -> Unit) {
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_error_dictation, null)
        val etCount = dialogView.findViewById<EditText>(R.id.etCount)

        setupQuickButtons(dialogView, etCount)

        val dialog =
            AlertDialog.Builder(activity)
                .setTitle(R.string.error_dictation_title)
                .setView(dialogView)
                .setPositiveButton(R.string.dialog_start) { _, _ ->
                    handleDialogConfirm(etCount, onConfirm)
                }
                .setNegativeButton(R.string.cancel, null)
                .create()

        dialog.show()
    }

    /**
     * 显示存储权限错误对话框
     *
     * @param fileName 文件名
     */
    fun showStoragePermissionErrorDialog(fileName: String) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.storage_permission_old_file_title)
            .setMessage(activity.getString(R.string.storage_permission_old_file_message, fileName))
            .setPositiveButton(R.string.ok) { _, _ -> }
            .setNeutralButton(R.string.open_file_manager) { _, _ ->
                try {
                    val intent =
                        Intent(Intent.ACTION_VIEW).apply {
                            type = "resource/folder"
                            putExtra("android.content.extra.SHOW_ADVANCED", true)
                        }
                    activity.startActivity(Intent.createChooser(intent, activity.getString(R.string.select_file_manager)))
                } catch (ex: android.content.ActivityNotFoundException) {
                    Logger.w(TAG, "未找到文件管理器应用", ex)
                } catch (ex: SecurityException) {
                    Logger.w(TAG, "权限不足，无法打开文件管理器", ex)
                }
            }
            .show()
    }

    /**
     * 更新提交按钮状态（提交成功后）
     *
     * @param answerAdapter 答案适配器（用于禁用复选框）
     */
    fun updateSubmitButtonAfterSuccess(answerAdapter: Any?) {
        viewManager.btnSubmit.text = activity.getString(R.string.btn_return)
        // 禁用复选框，只能看不能再改
        if (answerAdapter != null) {
            try {
                val method = answerAdapter.javaClass.getMethod("setCheckboxesEnabled", Boolean::class.java)
                method.invoke(answerAdapter, false)
            } catch (e: Exception) {
                Logger.w(TAG, "无法禁用复选框", e)
            }
        }
    }

    /**
     * 设置快捷按钮
     */
    private fun setupQuickButtons(
        dialogView: View,
        etCount: EditText,
    ) {
        val quickValues = listOf(5, 10, 15, 20, 25, 30, 35, 40)
        val buttonIds =
            listOf(
                R.id.btnQuick5, R.id.btnQuick10, R.id.btnQuick15, R.id.btnQuick20,
                R.id.btnQuick25, R.id.btnQuick30, R.id.btnQuick35, R.id.btnQuick40,
            )

        quickValues.forEachIndexed { index, value ->
            dialogView.findViewById<Button>(buttonIds[index])?.setOnClickListener {
                etCount.setText(value.toString())
            }
        }
    }

    /**
     * 处理对话框确认
     */
    private fun handleDialogConfirm(
        etCount: EditText,
        onConfirm: (Int) -> Unit,
    ) {
        val countText = etCount.text?.toString()?.trim() ?: ""
        val count = countText.toIntOrNull() ?: 0
        if (count > 0) {
            onConfirm(count)
        } else {
            ToastHelper.showShort(activity, R.string.error_dictation_invalid_count)
        }
    }

    /**
     * 显示初始化提示并自动隐藏
     *
     * 显示初始化识别提示，并在指定时间后自动隐藏。
     * 用于服务初始化完成后的用户提示。
     *
     * @param message 初始化提示消息
     * @param onComplete 初始化完成回调（可选）
     */
    fun showInitHint(
        message: String,
        onComplete: (() -> Unit)? = null,
    ) {
        viewManager.updateStatusText(message)

        Handler(Looper.getMainLooper()).postDelayed({
            if (!activity.isFinishing && !activity.isDestroyed) {
                try {
                    viewManager.tvStatus.visibility = View.GONE
                    Logger.d(TAG, "初始化完成，隐藏状态提示")
                    onComplete?.invoke()
                } catch (e: UninitializedPropertyAccessException) {
                    Logger.w(TAG, "tvStatus 未初始化，跳过隐藏操作", e)
                }
            }
        }, AppConfig.INIT_HINT_DISPLAY_MILLIS)
    }

    /**
     * 显示初始化错误提示
     *
     * @param error 错误消息
     */
    fun showInitError(error: String) {
        viewManager.updateStatusText("初始化失败: $error")
        ToastHelper.showLong(activity, R.string.init_failed, error)
    }
}
