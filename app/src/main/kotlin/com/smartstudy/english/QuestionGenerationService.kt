package com.smartstudy.english

import com.alibaba.dashscope.aigc.generation.Generation
import com.alibaba.dashscope.aigc.generation.GenerationParam
import com.alibaba.dashscope.common.Message
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
import org.json.JSONObject
import java.util.concurrent.TimeoutException

/**
 * 题目生成服务
 * 使用 qwen3-max 模型生成考察相同知识点的相似题目
 *
 * 功能特性：
 * - 根据原始错题生成相似的新题目
 * - 保持相同的知识点和难度
 * - 支持自动重试机制
 *
 * 使用示例：
 * ```
 * val service = QuestionGenerationService()
 * val result = service.generateSimilarQuestion(originalQuestion, originalAnswer)
 * when (result) {
 *     is AppResult.Success -> {
 *         val (newQuestion, newAnswer) = result.data
 *         // 使用新题目
 *     }
 *     is AppResult.Failure -> {
 *         // 处理错误
 *     }
 * }
 * ```
 *
 * @param ioDispatcher 协程调度器，默认使用 Dispatchers.IO
 */
class QuestionGenerationService(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    companion object {
        private const val TAG = "QuestionGenerationService"
        private const val MODEL = "qwen3-max"
        private val API_TIMEOUT_MILLIS = AppConfig.API_TIMEOUT_MILLIS
    }

    /**
     * 根据原始题目生成相似题目和答案
     * @param originalQuestion 原始题目
     * @param originalAnswer 原始答案（可选，用于帮助理解知识点）
     */
    suspend fun generateSimilarQuestion(
        originalQuestion: String,
        originalAnswer: String = "",
    ): AppResult<Pair<String, String>> =
        withContext(ioDispatcher) {
            runAppCatching(TAG, "题目生成") {
                val apiKey = AppConfig.dashscopeApiKey
                check(apiKey.isNotBlank()) { "未配置 DASHSCOPE_API_KEY（请在本机 Gradle 属性或环境变量中设置）" }

                // 使用重试机制执行网络请求
                val result =
                    NetworkRetryHelper.retryWithBackoff(
                        maxRetries = 3,
                        initialDelayMs = 1000L,
                        maxDelayMs = 5000L,
                    ) {
                        val gen = Generation()
                        val prompt = buildPrompt(originalQuestion, originalAnswer)

                        // 构建系统消息（从配置文件读取）
                        val systemMsg =
                            Message.builder()
                                .role(Role.SYSTEM.value)
                                .content(PromptConfig.getQuestionGenerationSystemMessage())
                                .build()

                        // 构建用户消息
                        val userMsg =
                            Message.builder()
                                .role(Role.USER.value)
                                .content(prompt)
                                .build()

                        val param =
                            GenerationParam.builder()
                                .apiKey(apiKey)
                                .model(MODEL)
                                .temperature(1.99f)
                                .messages(listOf(systemMsg, userMsg))
                                .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                                .build()

                        try {
                            withTimeout(API_TIMEOUT_MILLIS) {
                                runInterruptible { gen.call(param) }
                            }
                        } catch (e: TimeoutCancellationException) {
                            throw TimeoutException("题目生成请求超时")
                        }
                    }

                val generatedText = extractTextFromResult(result)?.trim()
                check(!generatedText.isNullOrBlank()) { "大模型返回结果为空，请检查API配置" }

                val questionAnswer = parseQuestionAnswer(generatedText)
                checkNotNull(questionAnswer) {
                    Logger.e(TAG, "解析失败，原始输出: $generatedText")
                    "生成结果格式错误，无法解析题目和答案。原始输出: ${generatedText.take(100)}"
                }

                Logger.d(
                    TAG,
                    "成功解析题目和答案: question=${questionAnswer.first.take(50)}..., answer=${questionAnswer.second}",
                )
                questionAnswer
            }
        }

    /**
     * 从 GenerationResult 中提取文本内容
     */
    private fun extractTextFromResult(result: com.alibaba.dashscope.aigc.generation.GenerationResult): String? {
        return try {
            val output = result.output
            val choices = output?.choices
            when {
                choices.isNullOrEmpty() -> {
                    Logger.w(TAG, "GenerationResult 中没有 choices")
                    null
                }
                else -> {
                    // 获取第一个 choice 的 message content
                    val message = choices[0]?.message
                    val content = message?.content
                    when {
                        content.isNullOrBlank() -> {
                            Logger.w(TAG, "GenerationResult message content 为空")
                            null
                        }
                        else -> content.trim()
                    }
                }
            }
        } catch (e: IllegalStateException) {
            Logger.e(TAG, "状态错误，提取 GenerationResult 文本失败", e)
            null
        } catch (e: NullPointerException) {
            Logger.e(TAG, "空指针错误，提取 GenerationResult 文本失败", e)
            null
        } catch (e: IndexOutOfBoundsException) {
            Logger.e(TAG, "索引越界，提取 GenerationResult 文本失败", e)
            null
        }
    }

    /**
     * 构建生成题目的Prompt
     */
    private fun buildPrompt(
        originalQuestion: String,
        originalAnswer: String,
    ): String {
        return PromptConfig.getQuestionGenerationPrompt(originalQuestion, originalAnswer)
    }

    /**
     * 解析生成的题目和答案
     * 尝试从文本中提取JSON格式的题目和答案
     */
    private fun parseQuestionAnswer(text: String): Pair<String, String>? {
        return try {
            // 尝试直接解析JSON
            val trimmed = text.trim()

            // 如果文本包含JSON对象，尝试提取
            val jsonStart = trimmed.indexOf('{')
            val jsonEnd = trimmed.lastIndexOf('}')

            val jsonResult = if (jsonStart >= 0 && jsonEnd > jsonStart) {
                val jsonStr = trimmed.substring(jsonStart, jsonEnd + 1)
                val json = JSONObject(jsonStr)
                val question = json.optString("question", "").trim()
                val answer = json.optString("answer", "").trim()
                if (question.isNotEmpty() && answer.isNotEmpty()) {
                    Pair(question, answer)
                } else {
                    null
                }
            } else {
                null
            }

            jsonResult ?: run {
                // 如果直接解析失败，尝试查找"question"和"answer"关键词
                val questionMatch = Regex("""["']?question["']?\s*[:：]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(trimmed)
                val answerMatch = Regex("""["']?answer["']?\s*[:：]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(trimmed)

                when {
                    questionMatch != null && answerMatch != null -> {
                        val question = questionMatch.groupValues[1].trim()
                        val answer = answerMatch.groupValues[1].trim()
                        if (question.isNotEmpty() && answer.isNotEmpty()) {
                            Pair(question, answer)
                        } else {
                            null
                        }
                    }
                    else -> null
                }
            } ?: run {
                Logger.w(TAG, "无法解析题目和答案，原始文本: ${text.take(200)}")
                null
            }
        } catch (e: java.util.regex.PatternSyntaxException) {
            Logger.e(TAG, "正则表达式错误，解析题目和答案失败", e)
            null
        } catch (e: IllegalStateException) {
            Logger.e(TAG, "状态错误，解析题目和答案失败", e)
            null
        }
    }
}
