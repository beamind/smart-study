package com.smartstudy.user

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartstudy.R
import com.smartstudy.core.AppResult
import com.smartstudy.core.Logger
import com.smartstudy.user.usecases.UserUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 用户管理Activity的ViewModel
 * 管理用户列表状态和用户操作
 *
 * 职责：
 * - 管理用户列表
 * - 管理当前选中用户
 * - 处理用户添加、切换等操作
 *
 * @param context Application Context，用于获取字符串资源
 * @param userUseCase 用户业务逻辑用例
 */
class UserManagementViewModel(
    private val context: Context,
    private val userUseCase: UserUseCase,
) : ViewModel() {
    companion object {
        private const val TAG = "UserManagementViewModel"
    }

    /**
     * UI状态
     */
    data class UserManagementState(
        val users: List<User> = emptyList(),
        val currentUserId: String? = null,
        val selectedUserId: String? = null,
        val isLoading: Boolean = false,
        val error: String? = null,
        val isEmpty: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UserManagementState())
    val uiState: StateFlow<UserManagementState> = _uiState.asStateFlow()

    init {
        loadUsers()
    }

    /**
     * 加载用户列表
     */
    fun loadUsers() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            // 确保至少有一个用户
            when (val ensureResult = userUseCase.ensureDefaultUser()) {
                is AppResult.Success -> {
                    // 加载用户列表
                    when (val usersResult = userUseCase.getAllUsers()) {
                        is AppResult.Success -> {
                            val users = usersResult.data
                            val currentUserIdResult = userUseCase.getCurrentUserId()
                            val currentUserId =
                                when (currentUserIdResult) {
                                    is AppResult.Success -> currentUserIdResult.data
                                    is AppResult.Failure -> null
                                }

                            // 初始化选中用户为当前用户
                            val selectedUserId = _uiState.value.selectedUserId ?: currentUserId

                            _uiState.update {
                                it.copy(
                                    users = users,
                                    currentUserId = currentUserId,
                                    selectedUserId = selectedUserId,
                                    isLoading = false,
                                    isEmpty = users.isEmpty(),
                                )
                            }
                        }
                        is AppResult.Failure -> {
                            Logger.e(TAG, "加载用户列表失败", usersResult.error.cause)
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    error = usersResult.error.message ?: context.getString(R.string.error_io),
                                    isEmpty = true,
                                )
                            }
                        }
                    }
                }
                is AppResult.Failure -> {
                    Logger.e(TAG, "确保默认用户失败", ensureResult.error.cause)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = ensureResult.error.message ?: context.getString(R.string.error_io),
                            isEmpty = true,
                        )
                    }
                }
            }
        }
    }

    /**
     * 设置选中用户
     */
    fun setSelectedUser(userId: String?) {
        _uiState.update { it.copy(selectedUserId = userId) }
    }

    /**
     * 添加用户
     */
    fun addUser(
        userName: String,
        onSuccess: (User) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (userName.isBlank()) {
            onError(context.getString(R.string.user_name_empty))
            return
        }

        viewModelScope.launch {
            when (val result = userUseCase.addUser(userName)) {
                is AppResult.Success -> {
                    val newUser = result.data
                    // 添加成功后重新加载列表
                    loadUsers()
                    // 自动选中新用户
                    _uiState.update { it.copy(selectedUserId = newUser.id) }
                    onSuccess(newUser)
                }
                is AppResult.Failure -> {
                    Logger.e(TAG, "添加用户失败", result.error.cause)
                    val errorMessage = result.error.message ?: context.getString(R.string.error_io)
                    _uiState.update { it.copy(error = errorMessage) }
                    onError(errorMessage)
                }
            }
        }
    }

    /**
     * 切换用户
     */
    fun switchUser(userId: String?) {
        userId?.let {
            viewModelScope.launch {
                when (val result = userUseCase.setCurrentUser(it)) {
                    is AppResult.Success -> {
                        // 切换成功后更新当前用户ID
                        _uiState.update { it.copy(currentUserId = userId) }
                    }
                    is AppResult.Failure -> {
                        Logger.e(TAG, "切换用户失败", result.error.cause)
                        _uiState.update {
                            it.copy(error = result.error.message ?: context.getString(R.string.error_io))
                        }
                    }
                }
            }
        }
    }

    /**
     * 清除错误信息
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
