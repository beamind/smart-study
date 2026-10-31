package com.smartstudy.core

import kotlinx.coroutines.delay
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException

/**
 * 网络请求重试工具类
 * 实现指数退避重试策略
 *
 * 功能特性：
 * - 自动判断异常类型，决定是否重试
 * - 指数退避策略，避免频繁重试
 * - 支持自定义重试次数和延迟时间
 *
 * 使用示例：
 * ```
 * val result = NetworkRetryHelper.retryWithBackoff {
 *     // 网络请求代码
 *     api.call()
 * }
 * ```
 */
object NetworkRetryHelper {
    private const val TAG = "NetworkRetryHelper"

    // 默认重试配置（从AppConfig读取）
    private val DEFAULT_MAX_RETRIES = AppConfig.NETWORK_MAX_RETRIES
    private val DEFAULT_INITIAL_DELAY_MS = AppConfig.NETWORK_INITIAL_DELAY_MS
    private val DEFAULT_MAX_DELAY_MS = AppConfig.NETWORK_MAX_DELAY_MS
    private val DEFAULT_MULTIPLIER = AppConfig.NETWORK_DELAY_MULTIPLIER

    /**
     * 执行带重试的网络请求
     *
     * @param maxRetries 最大重试次数（默认3次）
     * @param initialDelayMs 初始延迟时间（毫秒）
     * @param maxDelayMs 最大延迟时间（毫秒）
     * @param multiplier 延迟倍数（每次重试延迟时间 = 上次延迟 * multiplier）
     * @param block 要执行的网络请求代码块
     * @return 请求结果
     */
    suspend fun <T> retryWithBackoff(
        maxRetries: Int = DEFAULT_MAX_RETRIES,
        initialDelayMs: Long = DEFAULT_INITIAL_DELAY_MS,
        maxDelayMs: Long = DEFAULT_MAX_DELAY_MS,
        multiplier: Double = DEFAULT_MULTIPLIER,
        block: suspend () -> T,
    ): T {
        var currentDelay = initialDelayMs
        var lastException: Throwable? = null

        repeat(maxRetries) { attempt ->
            try {
                return block()
            } catch (e: Throwable) {
                lastException = e

                // 判断是否应该重试
                if (!shouldRetry(e)) {
                    Logger.w(TAG, "遇到不可重试的错误，停止重试: ${e.javaClass.simpleName}")
                    throw e
                }

                // 如果是最后一次尝试，直接抛出异常
                if (attempt == maxRetries - 1) {
                    Logger.w(TAG, "达到最大重试次数 ($maxRetries)，放弃重试")
                    throw e
                }

                // 计算延迟时间（指数退避）
                val delayMs = currentDelay.coerceAtMost(maxDelayMs)
                Logger.d(TAG, "网络请求失败（尝试 ${attempt + 1}/$maxRetries），${delayMs}ms 后重试: ${e.message}")

                delay(delayMs)
                currentDelay = (currentDelay * multiplier).toLong()
            }
        }

        // 理论上不会执行到这里，但为了类型安全
        throw lastException ?: RuntimeException("重试失败：未知错误")
    }

    /**
     * 判断异常是否应该重试
     *
     * 根据异常类型智能判断是否应该重试：
     * - 网络相关异常（超时、连接失败等）：可以重试
     * - 参数或状态错误：不应该重试
     * - API错误（4xx）：根据错误码判断
     * - 服务器错误（5xx）：可以重试
     */
    private fun shouldRetry(exception: Throwable): Boolean {
        // 网络相关异常，可以重试
        if (isNetworkException(exception)) {
            return true
        }

        // 参数错误或状态错误，不应该重试
        if (isClientError(exception)) {
            return false
        }

        // API异常，需要特殊判断
        if (exception is com.alibaba.dashscope.exception.ApiException) {
            return shouldRetryApiException(exception)
        }

        // 其他异常，默认重试（保守策略）
        return true
    }

    /**
     * 判断是否是网络相关异常
     */
    private fun isNetworkException(exception: Throwable): Boolean {
        return exception is TimeoutException ||
            exception is SocketTimeoutException ||
            exception is UnknownHostException ||
            exception is java.net.ConnectException ||
            exception is java.io.IOException
    }

    /**
     * 判断是否是客户端错误（不应该重试）
     */
    private fun isClientError(exception: Throwable): Boolean {
        return exception is IllegalArgumentException ||
            exception is IllegalStateException
    }

    /**
     * 从异常消息中尝试提取HTTP状态码
     *
     * @param message 异常消息
     * @return 提取到的状态码，如果无法提取则返回null
     */
    private fun extractStatusCodeFromMessage(message: String): Int? {
        // 尝试匹配常见的状态码格式，如 "400", "404", "500" 等
        val patterns =
            listOf(
                // 匹配三位数字
                Regex("""\b(\d{3})\b"""),
                // 匹配 "status: 400" 格式
                Regex("""status[:\s]+(\d{3})""", RegexOption.IGNORE_CASE),
                // 匹配 "code: 400" 格式
                Regex("""code[:\s]+(\d{3})""", RegexOption.IGNORE_CASE),
                // 匹配 "HTTP 400" 格式
                Regex("""HTTP[/\s]+(\d{3})""", RegexOption.IGNORE_CASE),
            )

        for (pattern in patterns) {
            val match = pattern.find(message)
            if (match != null) {
                val code = match.groupValues[1].toIntOrNull()
                if (code != null && code in 100..599) {
                    return code
                }
            }
        }

        return null
    }

    /**
     * 判断 ApiException 是否应该重试
     */
    private fun shouldRetryApiException(exception: com.alibaba.dashscope.exception.ApiException): Boolean {
        val message = exception.message ?: ""
        val statusCode = extractStatusCodeFromMessage(message)

        return when {
            statusCode != null && statusCode in 400..499 -> {
                // 4xx客户端错误，通常不应该重试（除非是429限流）
                statusCode == 429
            }
            statusCode != null && statusCode in 500..599 -> {
                // 5xx服务器错误，可以重试
                true
            }
            isRateLimitError(message) -> {
                // 检测到限流错误，可以重试
                true
            }
            isTimeoutError(message) -> {
                // 超时错误，可以重试
                true
            }
            else -> {
                // 无法确定状态码，采用保守策略：默认重试
                true
            }
        }
    }

    /**
     * 检查是否是限流错误
     */
    private fun isRateLimitError(message: String): Boolean {
        val keywords = listOf("429", "rate limit", "限流")
        return keywords.any { message.contains(it, ignoreCase = true) }
    }

    /**
     * 检查是否是超时错误
     */
    private fun isTimeoutError(message: String): Boolean {
        val keywords = listOf("timeout", "超时")
        return keywords.any { message.contains(it, ignoreCase = true) }
    }
}
