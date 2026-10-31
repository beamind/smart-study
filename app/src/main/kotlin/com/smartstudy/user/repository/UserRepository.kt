package com.smartstudy.user.repository

import android.content.Context
import com.smartstudy.core.AppResult
import com.smartstudy.core.repository.BaseRepository
import com.smartstudy.core.repository.executeDataOperation
import com.smartstudy.user.User
import com.smartstudy.user.storage.UserStorage

/**
 * 用户数据仓库
 *
 * 统一管理用户相关的数据访问，包括：
 * - 用户数据的读取和保存
 * - 当前用户ID的读取和保存
 *
 * 功能特性：
 * - 统一错误处理：所有方法返回 AppResult
 * - 线程安全：自动在 IO 线程执行数据操作
 * - 易于测试：可以轻松替换数据源进行单元测试
 * - 单一职责：将数据访问逻辑从业务逻辑中分离
 *
 * 注意：此 Repository 只负责数据访问，不包含业务逻辑（如生成用户ID、验证等）
 * 业务逻辑应在 UserUseCase 中处理
 *
 * 使用示例：
 * ```
 * val repository = UserRepository(context)
 * when (val result = repository.getAllUsers()) {
 *     is AppResult.Success -> handleSuccess(result.data)
 *     is AppResult.Failure -> handleError(result.error)
 * }
 * ```
 *
 * @param context Android Context
 */
class UserRepository(
    private val context: Context,
) : BaseRepository {
    override val tag: String = "UserRepository"

    /**
     * 获取Context，用于访问字符串资源
     */
    fun getContext(): Context = context

    private val storage: UserStorage by lazy {
        UserStorage(context)
    }

    /**
     * 获取所有用户列表
     *
     * @return 用户列表
     */
    suspend fun getAllUsers(): AppResult<List<User>> =
        executeDataOperation("获取所有用户") {
            val data = storage.readUserData()
            data.users
        }

    /**
     * 保存用户列表和当前用户ID
     *
     * @param users 用户列表
     * @param currentUserId 当前用户ID，如果为 null 则不设置当前用户
     * @return 操作结果
     */
    suspend fun saveUsers(
        users: List<User>,
        currentUserId: String? = null,
    ): AppResult<Unit> =
        executeDataOperation("保存用户列表") {
            val currentData = storage.readUserData()
            val newCurrentUserId = currentUserId ?: currentData.currentUserId
            storage.saveUserData(UserStorage.UserData(users, newCurrentUserId))
        }

    /**
     * 获取当前选中的用户ID
     *
     * @return 当前用户ID，如果不存在则返回 null
     */
    suspend fun getCurrentUserId(): AppResult<String?> =
        executeDataOperation("获取当前用户ID") {
            val data = storage.readUserData()
            data.currentUserId
        }

    /**
     * 设置当前用户
     *
     * @param userId 用户ID
     * @return 操作结果
     */
    suspend fun setCurrentUser(userId: String): AppResult<Unit> =
        executeDataOperation("设置当前用户") {
            val currentData = storage.readUserData()
            storage.saveUserData(UserStorage.UserData(currentData.users, userId))
        }
}
