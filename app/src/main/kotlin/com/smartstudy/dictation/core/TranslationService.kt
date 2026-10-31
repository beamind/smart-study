package com.smartstudy.dictation.core

import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam
import com.alibaba.dashscope.common.MultiModalMessage
import com.alibaba.dashscope.common.Role
import com.smartstudy.core.AppConfig
import com.smartstudy.core.AppResult
import com.smartstudy.core.Logger
import com.smartstudy.core.NetworkRetryHelper
import com.smartstudy.core.PromptConfig
import com.smartstudy.core.runAppCatching
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeoutException

/**
 * 千问翻译服务封装
 * 使用 qwen3-vl-flash 模型进行翻译
 *
 * 功能特性：
 * - 支持英文到中文的翻译
 * - 自动重试机制
 * - 超时保护
 *
 * 使用示例：
 * ```
 * val service = TranslationService()
 * val result = service.translate("apple")
 * when (result) {
 *     is AppResult.Success -> println("翻译结果: ${result.data}")
 *     is AppResult.Failure -> println("翻译失败: ${result.error.message}")
 * }
 * ```
 *
 * @param ioDispatcher 协程调度器，默认使用 Dispatchers.IO
 */
class TranslationService(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    companion object {
        private const val TAG = "TranslationService"
        private const val MODEL = "qwen3-vl-flash"
        private val API_TIMEOUT_MILLIS = AppConfig.API_TIMEOUT_MILLIS
    }

    /**
     * 翻译文本（英文到中文）
     * @param text 要翻译的英文文本
     */
    suspend fun translate(text: String): AppResult<String> =
        withContext(ioDispatcher) {
            runAppCatching(TAG, "翻译") {
                val apiKey = AppConfig.dashscopeApiKey
                check(apiKey.isNotBlank()) { "未配置 DASHSCOPE_API_KEY（请在本机 Gradle 属性或环境变量中设置）" }

                // 使用重试机制执行网络请求
                val result =
                    NetworkRetryHelper.retryWithBackoff(
                        maxRetries = 3,
                        initialDelayMs = 1000L,
                        maxDelayMs = 5000L,
                    ) {
                        val conv = MultiModalConversation()
                        val prompt = buildPrompt(text)
                        val userMessage =
                            MultiModalMessage.builder()
                                .role(Role.USER.value)
                                .content(
                                    listOf(
                                        hashMapOf<String, Any>(
                                            "text" to prompt,
                                        ),
                                    ),
                                )
                                .build()

                        val param =
                            MultiModalConversationParam.builder()
                                .apiKey(apiKey)
                                .model(MODEL)
                                .messages(listOf(userMessage))
                                .build()

                        Logger.d(TAG, "开始 qwen3-vl-flash 翻译调用: $text")
                        try {
                            withTimeout(API_TIMEOUT_MILLIS) {
                                runInterruptible { conv.call(param) }
                            }
                        } catch (e: TimeoutCancellationException) {
                            throw TimeoutException("翻译请求超时")
                        }
                    }
                val translated =
                    DashscopeTextExtractor.extractText(result)
                        .trim()
                        .trim('"', '“', '”')

                check(translated.isNotBlank()) { "翻译结果为空" }
                translated
            }
        }

    private fun buildPrompt(text: String): String {
        return PromptConfig.getTranslationPrompt(text)
    }
}
