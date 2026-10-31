package com.smartstudy.core

/**
 * 统一的成功/失败返回类型（替代 throw + callback）。
 *
 * 使用示例：
 * ```
 * when (val result = service.doSomething()) {
 *     is AppResult.Success -> handleSuccess(result.data)
 *     is AppResult.Failure -> handleError(result.error)
 * }
 * ```
 *
 * @param T 成功时返回的数据类型
 */
sealed interface AppResult<out T> {
    /**
     * 成功结果，包含数据
     * @param data 成功返回的数据
     */
    data class Success<T>(val data: T) : AppResult<T>

    /**
     * 失败结果，包含错误信息
     * @param error 错误详情
     */
    data class Failure(val error: AppError) : AppResult<Nothing>
}

/**
 * 成功时的回调扩展函数
 * @param block 成功时执行的代码块
 * @return 原 AppResult 对象，支持链式调用
 */
inline fun <T> AppResult<T>.onSuccess(block: (T) -> Unit): AppResult<T> {
    if (this is AppResult.Success) block(data)
    return this
}

/**
 * 失败时的回调扩展函数
 * @param block 失败时执行的代码块
 * @return 原 AppResult 对象，支持链式调用
 */
inline fun <T> AppResult<T>.onFailure(block: (AppError) -> Unit): AppResult<T> {
    if (this is AppResult.Failure) block(error)
    return this
}
