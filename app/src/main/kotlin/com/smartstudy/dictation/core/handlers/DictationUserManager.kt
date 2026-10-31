package com.smartstudy.dictation.core.handlers

import android.content.Intent
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.smartstudy.R
import com.smartstudy.core.AppResult
import com.smartstudy.core.Logger
import com.smartstudy.core.runAppCatchingSync
import com.smartstudy.storage.AppStorageManager
import com.smartstudy.user.UserManagementActivity
import com.smartstudy.user.usecases.UserUseCase
import com.smartstudy.utils.ToastHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 听写用户管理器
 *
 * 负责管理当前用户显示、处理用户切换、打开用户管理界面等。
 *
 * 功能特性：
 * - 管理当前用户显示
 * - 处理用户切换
 * - 打开用户管理界面
 * - 处理存储权限相关的用户初始化
 *
 * 使用示例：
 * ```
 * val userManager = DictationUserManager(
 *     activity = this,
 *     userManager = userManager,
 *     tvCurrentUser = tvCurrentUser,
 *     permissionManager = permissionManager,
 *     onUserChanged = { userId ->
 *         // 用户切换后的处理
 *     }
 * )
 * userManager.updateCurrentUserDisplay()
 * ```
 *
 * @param activity Activity实例
 * @param userUseCase 用户业务逻辑用例
 * @param tvCurrentUser 显示当前用户的TextView
 * @param permissionManager 权限管理器（用于处理存储权限）
 * @param onUserChanged 用户切换后的回调，参数为新用户ID
 */
class DictationUserManager(
    private val activity: AppCompatActivity,
    private val userUseCase: UserUseCase,
    private val tvCurrentUser: TextView,
    private val permissionManager: DictationPermissionManager,
    private val onUserChanged: (String) -> Unit,
) {
    companion object {
        private const val TAG = "DictationUserManager"
    }

    /**
     * 更新当前用户显示
     */
    fun updateCurrentUserDisplay() {
        CoroutineScope(Dispatchers.Main).launch {
            when (val result = userUseCase.getCurrentUser()) {
                is AppResult.Success -> {
                    val currentUser = result.data
                    if (currentUser != null) {
                        tvCurrentUser.text = currentUser.name
                    } else {
                        tvCurrentUser.text = activity.getString(R.string.guest)
                    }
                }
                is AppResult.Failure -> {
                    Logger.e(TAG, "获取当前用户失败", result.error.cause)
                    tvCurrentUser.text = activity.getString(R.string.guest)
                }
            }
        }
    }

    /**
     * 打开用户管理
     */
    fun openUserManagement() {
        if (!AppStorageManager.hasAppStoragePermission(activity)) {
            AppStorageManager.tryCreateSmartstudyFolderViaMediaStore(activity)

            AlertDialog.Builder(activity)
                .setTitle(R.string.storage_permission_title)
                .setMessage(R.string.storage_permission_required_short)
                .setPositiveButton(R.string.storage_permission_select_folder) { _, _ ->
                    permissionManager.pendingUserManagementIntent = Intent(activity, UserManagementActivity::class.java)
                    permissionManager.appStorageLauncher.launch(null)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            when (
                val result =
                    runAppCatchingSync(TAG, "启动用户管理") {
                        val intent = Intent(activity, UserManagementActivity::class.java)
                        require(intent.resolveActivity(activity.packageManager) != null) { "无法启动 UserManagementActivity" }
                        activity.startActivity(intent)
                    }
            ) {
                is AppResult.Success -> {
                    // 启动成功，无需处理
                }
                is AppResult.Failure -> {
                    Logger.e(TAG, "启动用户管理失败", result.error.cause)
                    ToastHelper.showShort(activity, R.string.activity_start_failed, result.error.message)
                }
            }
        }
    }

    /**
     * 检查并更新用户（用于 onResume 时检查用户是否切换）
     *
     * @param currentUserId 当前用户ID
     * @param onResult 结果回调，参数为新的用户ID（如果用户切换了）
     */
    fun checkAndUpdateUser(
        currentUserId: String,
        onResult: (String) -> Unit,
    ) {
        CoroutineScope(Dispatchers.Main).launch {
            when (val result = userUseCase.getCurrentUser()) {
                is AppResult.Success -> {
                    val currentUser = result.data
                    if (currentUser != null && currentUser.id != currentUserId) {
                        Logger.d(TAG, "检测到用户切换，更新用户ID: ${currentUser.name}")
                        updateCurrentUserDisplay()
                        onUserChanged(currentUser.id)
                        onResult(currentUser.id)
                    } else {
                        onResult(currentUserId)
                    }
                }
                is AppResult.Failure -> {
                    Logger.e(TAG, "获取当前用户失败", result.error.cause)
                    onResult(currentUserId)
                }
            }
        }
    }

    /**
     * 初始化用户（用于 onCreate 时）
     *
     * @param onResult 结果回调，参数为当前用户ID
     */
    fun initializeUser(onResult: (String) -> Unit) {
        CoroutineScope(Dispatchers.Main).launch {
            when (val result = userUseCase.ensureDefaultUser()) {
                is AppResult.Success -> {
                    val currentUser = result.data
                    updateCurrentUserDisplay()
                    onResult(currentUser.id)
                }
                is AppResult.Failure -> {
                    Logger.e(TAG, "初始化用户失败", result.error.cause)
                    // 如果失败，使用默认用户ID
                    onResult("")
                }
            }
        }
    }
}
