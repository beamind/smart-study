package com.smartstudy.user.managers

import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import com.smartstudy.R
import com.smartstudy.databinding.ActivityUserManagementBinding
import com.smartstudy.user.User
import com.smartstudy.user.UserManagementAdapter
import com.smartstudy.user.UserManagementViewModel
import com.smartstudy.utils.ToastHelper

/**
 * 用户管理管理器
 *
 * 负责管理用户管理的业务逻辑，将业务逻辑从Activity中分离出来。
 *
 * 功能特性：
 * - 添加用户对话框管理
 * - 用户切换管理
 * - 错误处理管理
 * - UI状态管理（空状态显示）
 *
 * 使用示例：
 * ```
 * val manager = UserManagementManager(
 *     activity = this,
 *     binding = binding,
 *     viewModel = viewModel,
 *     onUserAdded = { user -> ToastHelper.showShort(this, R.string.user_added, user.name) },
 *     onUserSwitched = { userName -> ToastHelper.showShort(this, R.string.user_switched, userName) }
 * )
 * manager.showAddUserDialog()
 * manager.switchUser(userId)
 * manager.showEmptyState(true)
 * ```
 *
 * @param activity Activity实例
 * @param binding View Binding实例
 * @param viewModel 用户管理ViewModel
 * @param onUserAdded 用户添加成功回调
 * @param onUserSwitched 用户切换成功回调，参数为用户名
 */
class UserManagementManager(
    private val activity: AppCompatActivity,
    private val binding: ActivityUserManagementBinding,
    private val viewModel: UserManagementViewModel,
    private val onUserAdded: (com.smartstudy.user.User) -> Unit,
    private val onUserSwitched: (String) -> Unit,
) {
    companion object {
        private const val TAG = "UserManagementManager"
    }

    /**
     * 显示添加用户对话框
     */
    fun showAddUserDialog() {
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_add_user, null)
        val etUserName = dialogView.findViewById<EditText>(R.id.etUserName)

        val dialog =
            AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.register))
                .setView(dialogView)
                .setPositiveButton(activity.getString(R.string.add_user)) { _, _ ->
                    val userName = etUserName.text.toString().trim()
                    viewModel.addUser(
                        userName = userName,
                        onSuccess = { newUser ->
                            // 注册后自动选中新用户，但不切换当前用户
                            onUserAdded(newUser)
                        },
                        onError = { errorMessage ->
                            // 错误处理已在ViewModel的observeViewModel中处理
                        },
                    )
                }
                .setNegativeButton(activity.getString(R.string.cancel), null)
                .create()

        dialog.show()
    }

    /**
     * 切换用户
     */
    fun switchUser(userId: String) {
        val state = viewModel.uiState.value
        val currentUserId = state.currentUserId

        if (userId != currentUserId) {
            viewModel.switchUser(userId)
            val userName = state.users.find { it.id == userId }?.name ?: activity.getString(R.string.error_unknown_user)
            onUserSwitched(userName)
        }
    }

    /**
     * 处理错误信息显示
     */
    fun handleError(error: String) {
        if (isStoragePermissionError(error)) {
            // 显示更友好的错误提示，引导用户重新授权
            AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.storage_permission_issue))
                .setMessage(activity.getString(R.string.storage_permission_issue_message))
                .setPositiveButton(activity.getString(R.string.confirm), null)
                .show()
        } else {
            ToastHelper.showLong(activity, R.string.user_add_failed, error)
        }
    }

    /**
     * 检查是否是存储权限相关错误
     */
    private fun isStoragePermissionError(error: String): Boolean {
        val permissionKeywords = listOf("无法创建", "SAF", "权限", "smartstudy")
        return permissionKeywords.any { error.contains(it) }
    }

    /**
     * 创建并设置用户列表适配器
     *
     * @param recyclerView RecyclerView实例
     * @param users 用户列表
     * @param currentUserId 当前用户ID
     * @param selectedUserId 选中的用户ID
     * @param onUserSelect 用户选择回调
     * @return 创建的适配器
     */
    fun createAndSetUserAdapter(
        recyclerView: RecyclerView,
        users: List<User>,
        currentUserId: String?,
        selectedUserId: String?,
        onUserSelect: (String) -> Unit,
    ): UserManagementAdapter {
        val adapter =
            UserManagementAdapter(
                users = users,
                currentUserId = currentUserId,
                selectedUserId = selectedUserId,
                onUserSelect = onUserSelect,
            )
        recyclerView.adapter = adapter
        return adapter
    }

    /**
     * 更新适配器的选中用户
     *
     * @param adapter 用户适配器
     * @param selectedUserId 选中的用户ID
     */
    fun updateAdapterSelection(
        adapter: UserManagementAdapter?,
        selectedUserId: String?,
    ) {
        adapter?.updateSelectedUser(selectedUserId)
    }

    /**
     * 显示或隐藏空状态
     *
     * @param show 是否显示空状态
     */
    fun showEmptyState(show: Boolean) {
        binding.layoutEmpty.visibility = if (show) View.VISIBLE else View.GONE
        binding.rvUsers.visibility = if (show) View.GONE else View.VISIBLE
    }
}
