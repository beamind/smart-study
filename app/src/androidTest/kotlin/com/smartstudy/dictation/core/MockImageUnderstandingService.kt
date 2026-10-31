package com.smartstudy.dictation.core

import android.graphics.Bitmap
import com.smartstudy.core.AppError
import com.smartstudy.core.AppErrorKind
import com.smartstudy.core.AppResult
import com.smartstudy.dictation.core.interfaces.IImageUnderstandingService
import kotlinx.coroutines.delay

/**
 * Mock图片理解服务
 * 用于E2E测试，返回预定义的测试词语
 *
 * 功能特性：
 * - 返回预定义的测试词语列表
 * - 支持配置不同的返回结果
 * - 支持模拟延迟（模拟网络请求）
 */
class MockImageUnderstandingService : IImageUnderstandingService {
    companion object {
        private const val TAG = "MockImageUnderstandingService"

        // 默认测试词语列表
        private val DEFAULT_TEST_WORDS = listOf("苹果", "香蕉", "橙子")
    }

    // 可配置的返回结果
    var testWords: List<String> = DEFAULT_TEST_WORDS
        set(value) {
            field = value.ifEmpty { DEFAULT_TEST_WORDS }
        }

    // 模拟延迟时间（毫秒）
    var delayMs: Long = 100L
        set(value) {
            field = value.coerceAtLeast(0)
        }

    // 是否返回错误
    var shouldReturnError: Boolean = false
    var errorMessage: String = "图片理解失败"

    override suspend fun understandWords(bitmap: Bitmap): AppResult<List<String>> {
        return understandWords(bitmap, IImageUnderstandingService.ImageUnderstandingMode.GENERIC, null)
    }

    override suspend fun understandWords(
        bitmap: Bitmap,
        prompt: String?,
    ): AppResult<List<String>> {
        return understandWords(bitmap, IImageUnderstandingService.ImageUnderstandingMode.GENERIC, prompt)
    }

    override suspend fun understandWords(
        bitmap: Bitmap,
        mode: IImageUnderstandingService.ImageUnderstandingMode,
    ): AppResult<List<String>> {
        return understandWords(bitmap, mode, null)
    }

    override suspend fun understandWords(
        bitmap: Bitmap,
        mode: IImageUnderstandingService.ImageUnderstandingMode,
        prompt: String?,
    ): AppResult<List<String>> {
        // 模拟网络延迟
        if (delayMs > 0) {
            delay(delayMs)
        }

        // 如果配置为返回错误
        if (shouldReturnError) {
            return AppResult.Failure(
                AppError(
                    kind = AppErrorKind.NetworkOrApi,
                    message = errorMessage,
                ),
            )
        }

        // 返回测试词语
        return AppResult.Success(testWords)
    }

    /**
     * 重置为默认配置
     */
    fun reset() {
        testWords = DEFAULT_TEST_WORDS
        delayMs = 100L
        shouldReturnError = false
        errorMessage = "图片理解失败"
    }
}
