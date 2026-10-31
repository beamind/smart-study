package com.smartstudy.core.repository

import com.smartstudy.core.AppResult
import com.smartstudy.core.runAppCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repository 模式的基础接口
 *
 * Repository 模式用于统一数据访问层，提供以下优势：
 * - 统一错误处理：所有数据访问操作都返回 AppResult
 * - 线程安全：自动在 IO 线程执行数据操作
 * - 易于测试：可以轻松替换数据源进行单元测试
 * - 单一职责：将数据访问逻辑从业务逻辑中分离
 *
 * 实现 Repository 时应该：
 * - 所有数据访问方法都应该是 suspend 函数
 * - 所有方法都应该返回 AppResult<T>
 * - 在 IO 线程执行数据操作
 * - 使用 runAppCatching 统一错误处理
 */
interface BaseRepository {
    /**
     * Repository 的日志标签
     */
    val tag: String
}

/**
 * Repository 的扩展函数，用于统一执行数据访问操作
 *
 * @param operationName 操作名称，用于日志记录
 * @param block 要执行的数据访问操作
 * @return AppResult<T> 操作结果
 */
suspend inline fun <T> BaseRepository.executeDataOperation(
    operationName: String,
    crossinline block: suspend () -> T,
): AppResult<T> {
    return runAppCatching(tag, operationName) {
        withContext(Dispatchers.IO) {
            block()
        }
    }
}
