package com.smartstudy.core

import kotlinx.coroutines.CancellationException

/**
 * 统一的 try/catch + 日志 + 错误映射。
 *
 * 这是一个函数，用于统一处理可能抛出异常的操作，自动转换为 [AppResult]。
 *
 * 功能特性：
 * - 自动捕获异常并转换为 [AppResult.Failure]
 * - 自动记录错误日志
 * - 自动映射异常为 [AppError]
 * - 保留 [CancellationException]（协程取消异常）以便正确处理
 *
 * 使用示例：
 * ```
 * val result = runAppCatching(TAG, "读取文件") {
 *     readFileContent()
 * }
 * when (result) {
 *     is AppResult.Success -> handleSuccess(result.data)
 *     is AppResult.Failure -> handleError(result.error)
 * }
 * ```
 *
 * @param tag 日志标签，用于错误日志记录
 * @param operationName 操作名称，用于错误日志记录（如 "读取文件"、"网络请求"）
 * @param block 要执行的操作代码块
 * @return [AppResult.Success] 如果操作成功，[AppResult.Failure] 如果操作失败
 * @throws CancellationException 如果是协程取消异常，会重新抛出以便正确处理
 */
fun <T> runAppCatchingSync(
    tag: String,
    operationName: String,
    block: () -> T,
): AppResult<T> {
    return try {
        AppResult.Success(block())
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        Logger.e(tag, "$operationName 失败: ${t.message}", t)
        AppResult.Failure(t.toAppError())
    }
}

/**
 * 统一的 suspend 函数 try/catch + 日志 + 错误映射。
 *
 * 这是 suspend 版本的 [runAppCatching]，用于在协程中统一处理可能抛出异常的操作。
 *
 * 功能特性：
 * - 自动捕获异常并转换为 [AppResult.Failure]
 * - 自动记录错误日志
 * - 自动映射异常为 [AppError]
 * - 保留 [CancellationException]（协程取消异常）以便正确处理
 *
 * 使用示例：
 * ```
 * val result = runAppCatching(TAG, "网络请求") {
 *     apiService.fetchData()
 * }
 * when (result) {
 *     is AppResult.Success -> handleSuccess(result.data)
 *     is AppResult.Failure -> handleError(result.error)
 * }
 * ```
 *
 * @param tag 日志标签，用于错误日志记录
 * @param operationName 操作名称，用于错误日志记录（如 "网络请求"、"文件读取"）
 * @param block 要执行的 suspend 操作代码块
 * @return [AppResult.Success] 如果操作成功，[AppResult.Failure] 如果操作失败
 * @throws CancellationException 如果是协程取消异常，会重新抛出以便正确处理
 */
suspend inline fun <T> runAppCatching(
    tag: String,
    operationName: String,
    crossinline block: suspend () -> T,
): AppResult<T> {
    return try {
        AppResult.Success(block())
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        Logger.e(tag, "$operationName 失败: ${t.message}", t)
        AppResult.Failure(t.toAppError())
    }
}
