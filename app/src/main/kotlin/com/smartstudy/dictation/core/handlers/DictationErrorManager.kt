package com.smartstudy.dictation.core.handlers

import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.smartstudy.R
import com.smartstudy.core.AppErrorKind
import com.smartstudy.core.Logger
import com.smartstudy.storage.AppStorageManager
import com.smartstudy.utils.ToastHelper
import java.io.IOException

/**
 * 听写错误管理器
 *
 * 负责统一处理听写功能中的各种错误情况，将错误处理逻辑从 Activity 中分离出来。
 *
 * 功能特性：
 * - 统一的错误处理接口
 * - 根据错误类型提供不同的处理策略
 * - 友好的错误提示
 * - 支持存储异常的特殊处理
 *
 * 使用示例：
 * ```
 * val errorManager = DictationErrorManager(
 *     activity = this,
 *     viewManager = viewManager,
 *     permissionManager = permissionManager,
 *     onFinish = { finish() }
 * )
 * errorManager.handleImageRecognitionError(error)
 * ```
 *
 * @param activity Activity 实例
 * @param viewManager 视图管理器
 * @param permissionManager 权限管理器
 * @param onFinish 完成 Activity 的回调
 */
class DictationErrorManager(
    private val activity: AppCompatActivity,
    private val viewManager: DictationViewManager,
    private val permissionManager: DictationPermissionManager,
    private val onFinish: () -> Unit,
) {
    companion object {
        private const val TAG = "DictationErrorManager"
    }

    /**
     * 处理图片识别错误
     */
    fun handleImageRecognitionError(error: com.smartstudy.core.AppError) {
        // 检查视图是否已初始化
        if (!viewManager.areViewsInitialized()) {
            Logger.w(TAG, "视图未初始化，跳过错误显示")
            return
        }

        // 显示错误状态
        val detail = "${activity.getString(R.string.error_image_understanding)}: ${error.message}"
        viewManager.tvStatus.text = detail
        ToastHelper.showLong(activity, detail)

        // 恢复按钮可见性
        viewManager.btnTakePhoto.visibility = View.VISIBLE
        viewManager.btnViewErrors.visibility = View.VISIBLE
        viewManager.btnErrorDictation.visibility = View.VISIBLE

        // 根据错误类型处理
        when (error.kind) {
            AppErrorKind.MissingApiKey -> {
                showApiKeyDialog()
            }
            AppErrorKind.InvalidData -> {
                AlertDialog.Builder(activity)
                    .setTitle(R.string.image_unrecognizable_title)
                    .setMessage(R.string.image_unrecognizable_message)
                    .setPositiveButton(R.string.ok, null)
                    .show()
            }
            else -> {
                // 其他错误已显示消息
            }
        }
    }

    /**
     * 处理存储异常
     */
    fun handleStorageException(e: IOException) {
        val cannotCreateUserDataFile = activity.getString(R.string.error_cannot_create_user_data_file)
        val cannotCreateUserFile = activity.getString(R.string.error_cannot_create_user_file)
        if (e.message?.contains(cannotCreateUserDataFile) == true ||
            e.message?.contains(cannotCreateUserFile) == true
        ) {
            Logger.e(TAG, "无法创建用户文件", e)
            AppStorageManager.tryCreateSmartstudyFolderViaMediaStore(activity)

            AlertDialog.Builder(activity)
                .setTitle(R.string.storage_permission_title)
                .setMessage(R.string.storage_permission_message)
                .setPositiveButton(R.string.storage_permission_select_folder) { _, _ ->
                    permissionManager.appStorageLauncher.launch(null)
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    onFinish()
                }
                .setOnDismissListener {
                    onFinish()
                }
                .show()
        } else {
            handleInitError(e)
        }
    }

    /**
     * 处理初始化错误
     */
    fun handleInitError(e: Exception) {
        Logger.e(TAG, "Activity初始化失败", e)
        ToastHelper.showLong(activity, R.string.init_failed, e.message ?: "")
        // 使用Handler延迟关闭，避免Activity持有协程引用
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!activity.isFinishing && !activity.isDestroyed) {
                onFinish()
            }
        }, com.smartstudy.core.AppConfig.INIT_ERROR_DISPLAY_MILLIS)
    }

    /**
     * 显示API Key配置对话框
     */
    private fun showApiKeyDialog() {
        AlertDialog.Builder(activity)
            .setTitle(R.string.api_key_config_title)
            .setMessage(R.string.api_key_config_message)
            .setPositiveButton(R.string.confirm, null)
            .show()
    }
}
