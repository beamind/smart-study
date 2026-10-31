package com.smartstudy.user.usecases

import com.smartstudy.core.AppResult
import com.smartstudy.user.User
import com.smartstudy.user.repository.UserRepository
import java.util.UUID

/**
 * 用户业务逻辑用例类
 *
 * 将业务逻辑从Repository中分离出来，提高代码的可测试性和可维护性。
 *
 * 功能特性：
 * - 用户创建业务逻辑（生成ID、验证名称等）
 * - 用户查询业务逻辑
 * - 默认用户管理业务逻辑
 * - 用户切换业务逻辑
 *
 * 使用示例：
 * ```
 * val useCase = UserUseCase(userRepository)
 * val result = useCase.addUser("新用户")
 * when (result) {
 *     is AppResult.Success -> handleSuccess(result.data)
 *     is AppResult.Failure -> handleError(result.error)
 * }
 * ```
 *
 * @param userRepository 用户数据仓库
 */
class UserUseCase(
    private val userRepository: UserRepository,
) {
    companion object {
        private const val TAG = "UserUseCase"
    }

    /**
     * 获取所有用户列表
     *
     * @return 用户列表
     */
    suspend fun getAllUsers(): AppResult<List<User>> {
        return userRepository.getAllUsers()
    }

    /**
     * 添加新用户
     *
     * @param name 用户名称
     * @return 新创建的用户
     */
    suspend fun addUser(name: String): AppResult<User> {
        // 业务逻辑：验证名称
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) {
            return AppResult.Failure(
                com.smartstudy.core.AppError(
                    com.smartstudy.core.AppErrorKind.InvalidData,
                    userRepository.getContext().getString(com.smartstudy.R.string.validation_error_username_empty),
                    IllegalArgumentException("用户名不能为空"),
                ),
            )
        }

        // 业务逻辑：检查是否已存在同名用户
        val existingUsersResult = userRepository.getAllUsers()
        if (existingUsersResult is AppResult.Success) {
            val existingUsers = existingUsersResult.data
            if (existingUsers.any { it.name == trimmedName }) {
                return AppResult.Failure(
                    com.smartstudy.core.AppError(
                        com.smartstudy.core.AppErrorKind.InvalidData,
                        "用户名已存在",
                        IllegalArgumentException("用户名已存在: $trimmedName"),
                    ),
                )
            }
        }

        // 业务逻辑：生成用户ID
        val newUser =
            User(
                id = UUID.randomUUID().toString(),
                name = trimmedName,
            )

        // 获取当前用户列表并添加新用户
        val currentUsersResult = userRepository.getAllUsers()
        return when (currentUsersResult) {
            is AppResult.Success -> {
                val currentUsers = currentUsersResult.data.toMutableList()
                val currentUserId =
                    when (val result = userRepository.getCurrentUserId()) {
                        is AppResult.Success -> result.data
                        is AppResult.Failure -> null
                    }
                currentUsers.add(newUser)

                // 保存用户列表（保持当前用户ID不变）
                val saveResult = userRepository.saveUsers(currentUsers, currentUserId)
                when (saveResult) {
                    is AppResult.Success -> AppResult.Success(newUser)
                    is AppResult.Failure -> saveResult
                }
            }
            is AppResult.Failure -> currentUsersResult
        }
    }

    /**
     * 获取当前选中的用户ID
     *
     * @return 当前用户ID，如果不存在则返回 null
     */
    suspend fun getCurrentUserId(): AppResult<String?> {
        return userRepository.getCurrentUserId()
    }

    /**
     * 设置当前用户
     *
     * @param userId 用户ID
     * @return 操作结果
     */
    suspend fun setCurrentUser(userId: String): AppResult<Unit> {
        // 业务逻辑：验证用户是否存在
        val usersResult = userRepository.getAllUsers()
        return when (usersResult) {
            is AppResult.Success -> {
                val users = usersResult.data
                if (users.any { it.id == userId }) {
                    userRepository.setCurrentUser(userId)
                } else {
                    AppResult.Failure(
                        com.smartstudy.core.AppError(
                            com.smartstudy.core.AppErrorKind.InvalidData,
                            "用户不存在",
                            IllegalArgumentException("用户不存在: $userId"),
                        ),
                    )
                }
            }
            is AppResult.Failure -> usersResult
        }
    }

    /**
     * 获取当前用户
     *
     * @return 当前用户，如果不存在则返回 null
     */
    suspend fun getCurrentUser(): AppResult<User?> {
        val userIdResult = userRepository.getCurrentUserId()
        if (userIdResult is AppResult.Failure) {
            return userIdResult
        }

        val userId =
            when (userIdResult) {
                is AppResult.Success -> userIdResult.data
                is AppResult.Failure -> return AppResult.Success(null)
            } ?: return AppResult.Success(null)

        val usersResult = userRepository.getAllUsers()
        return when (usersResult) {
            is AppResult.Success -> {
                val user = usersResult.data.find { it.id == userId }
                AppResult.Success(user)
            }
            is AppResult.Failure -> usersResult
        }
    }

    /**
     * 确保至少有一个用户（如果没有用户，创建默认用户）
     *
     * @return 当前用户（可能是新创建的默认用户）
     */
    suspend fun ensureDefaultUser(): AppResult<User> {
        val usersResult = userRepository.getAllUsers()
        return when (usersResult) {
            is AppResult.Success -> {
                val users = usersResult.data
                if (users.isEmpty()) {
                    // 创建默认用户
                    val defaultUserResult = addUser("游客")
                    if (defaultUserResult is AppResult.Success) {
                        val defaultUser = defaultUserResult.data
                        // 设置为当前用户
                        val setResult = setCurrentUser(defaultUser.id)
                        if (setResult is AppResult.Success) {
                            AppResult.Success(defaultUser)
                        } else {
                            defaultUserResult
                        }
                    } else {
                        defaultUserResult
                    }
                } else {
                    // 如果没有当前用户，设置第一个用户为当前用户
                    val currentUserIdResult = userRepository.getCurrentUserId()
                    val currentUserId =
                        when (currentUserIdResult) {
                            is AppResult.Success -> currentUserIdResult.data
                            is AppResult.Failure -> null
                        }
                    if (currentUserId == null) {
                        val firstUser = users.first()
                        val setResult = setCurrentUser(firstUser.id)
                        if (setResult is AppResult.Success) {
                            when (val userResult = getCurrentUser()) {
                                is AppResult.Success -> {
                                    val user = userResult.data
                                    if (user != null) {
                                        AppResult.Success(user)
                                    } else {
                                        AppResult.Success(firstUser)
                                    }
                                }
                                is AppResult.Failure -> AppResult.Success(firstUser)
                            }
                        } else {
                            AppResult.Success(firstUser)
                        }
                    } else {
                        when (val userResult = getCurrentUser()) {
                            is AppResult.Success -> {
                                val user = userResult.data
                                if (user != null) {
                                    AppResult.Success(user)
                                } else {
                                    // 修复数据不一致：当前用户ID指向的用户不存在，自动设置为第一个用户
                                    val firstUser = users.first()
                                    val setResult = setCurrentUser(firstUser.id)
                                    if (setResult is AppResult.Success) {
                                        AppResult.Success(firstUser)
                                    } else {
                                        // 即使设置失败，也返回第一个用户，避免阻塞用户操作
                                        AppResult.Success(firstUser)
                                    }
                                }
                            }
                            is AppResult.Failure -> {
                                // 如果获取当前用户失败，也尝试修复：设置为第一个用户
                                val firstUser = users.first()
                                val setResult = setCurrentUser(firstUser.id)
                                if (setResult is AppResult.Success) {
                                    AppResult.Success(firstUser)
                                } else {
                                    // 即使设置失败，也返回第一个用户，避免阻塞用户操作
                                    AppResult.Success(firstUser)
                                }
                            }
                        }
                    }
                }
            }
            is AppResult.Failure -> usersResult
        }
    }
}
