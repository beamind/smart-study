package com.smartstudy.dictation.core.handlers

import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.smartstudy.core.AppConfig
import com.smartstudy.core.AppResult
import com.smartstudy.core.Logger
import com.smartstudy.core.runAppCatchingSync
import com.smartstudy.storage.AppStorageManager
import com.smartstudy.user.usecases.UserUseCase
import com.smartstudy.utils.PermissionHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 听写权限管理器
 *
 * 负责管理权限请求和处理，将权限相关功能从Activity中分离出来。
 *
 * 功能特性：
 * - 管理权限请求（相机、麦克风）
 * - 处理权限结果回调
 * - 管理存储权限（通过 ActivityResultLauncher）
 * - 处理权限相关的错误提示
 *
 * 使用示例：
 * ```
 * val permissionManager = DictationPermissionManager(
 *     activity = this,
 *     userManager = userManager,
 *     onStoragePermissionGranted = {
 *         // 权限授予后的处理
 *     },
 *     onNeedReselectStorage = {
 *         // 需要重新选择存储目录
 *     }
 * )
 * permissionManager.requestPermissions()
 * ```
 *
 * @param activity Activity实例
 * @param userUseCase 用户业务逻辑用例
 * @param onStoragePermissionGranted 存储权限授予后的回调
 * @param onNeedReselectStorage 需要重新选择存储目录的回调
 */
class DictationPermissionManager(
    private val activity: AppCompatActivity,
    private val userUseCase: UserUseCase,
    private val onStoragePermissionGranted: () -> Unit,
    private val onNeedReselectStorage: () -> Unit,
) {
    companion object {
        private const val TAG = "DictationPermissionManager"
    }

    private val permissionHelper = PermissionHelper(activity)

    // 待处理的Intent
    var pendingUserManagementIntent: Intent? = null

    // 存储权限launcher
    val appStorageLauncher: ActivityResultLauncher<Uri?> =
        activity.registerForActivityResult(
            ActivityResultContracts.OpenDocumentTree(),
        ) { uri ->
            handleStoragePermissionResult(uri)
        }

    /**
     * 请求权限
     */
    fun requestPermissions() {
        val permissions =
            arrayOf(
                android.Manifest.permission.CAMERA,
                android.Manifest.permission.RECORD_AUDIO,
            )
        permissionHelper.checkAndRequestPermissions(permissions, AppConfig.REQUEST_PERMISSIONS)
    }

    /**
     * 处理权限结果
     *
     * @param requestCode 请求码
     * @param permissions 权限数组
     * @param grantResults 授权结果数组
     * @param onPermissionGranted 权限授予后的回调（用于重新初始化服务）
     */
    fun handlePermissionResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
        onPermissionGranted: () -> Unit,
    ): Boolean {
        if (permissionHelper.handlePermissionResult(requestCode, permissions, grantResults)) {
            // 权限授予后，重新初始化语音识别服务
            when (
                val result =
                    runAppCatchingSync(TAG, "重新初始化语音识别服务") {
                        onPermissionGranted()
                    }
            ) {
                is AppResult.Success -> {
                    Logger.d(TAG, "权限授予后，语音识别服务已就绪")
                }
                is AppResult.Failure -> {
                    Logger.e(TAG, "重新初始化语音识别服务失败", result.error.cause)
                }
            }
            return true
        }
        return false
    }

    /**
     * 处理存储权限结果
     */
    private fun handleStoragePermissionResult(uri: Uri?) {
        AppStorageManager.handleStoragePermissionResult(
            activity,
            uri,
            onPermissionGranted = {
                // 权限授予后，重新初始化用户
                CoroutineScope(Dispatchers.Main).launch {
                    when (val result = userUseCase.ensureDefaultUser()) {
                        is AppResult.Success -> {
                            // 通知外部更新用户ID
                            onStoragePermissionGranted()
                        }
                        is AppResult.Failure -> {
                            Logger.e(TAG, "授权后重新初始化用户失败", result.error.cause)
                        }
                    }
                }
            },
            pendingIntent = pendingUserManagementIntent,
            onNeedReselect = {
                onNeedReselectStorage()
            },
        )
        pendingUserManagementIntent = null
    }
}
