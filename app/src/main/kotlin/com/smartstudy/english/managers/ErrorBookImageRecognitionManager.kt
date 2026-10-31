package com.smartstudy.english.managers

import android.graphics.Bitmap
import android.util.Base64
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.viewModelScope
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam
import com.alibaba.dashscope.common.MultiModalMessage
import com.alibaba.dashscope.common.Role
import com.smartstudy.BuildConfig
import com.smartstudy.core.AppConfig
import com.smartstudy.core.AppResult
import com.smartstudy.core.Logger
import com.smartstudy.core.NetworkRetryHelper
import com.smartstudy.core.PromptConfig
import com.smartstudy.core.runAppCatching
import com.smartstudy.dictation.core.DashscopeTextExtractor
import com.smartstudy.dictation.core.ImageUnderstandingService
import com.smartstudy.dictation.core.interfaces.IImageUnderstandingService
import com.smartstudy.english.EnglishErrorBookParser
import com.smartstudy.english.ErrorBookViewModel
import com.smartstudy.english.QuestionAnswer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeoutException

/**
 * 错题本图片识别管理器
 *
 * 负责错题图片识别相关的逻辑，将图片识别功能从Activity中分离出来。
 *
 * 功能特性：
 * - 统一的图片识别流程
 * - 自动解析题目和答案
 * - 统一的错误处理
 *
 * 使用示例：
 * ```
 * val manager = ErrorBookImageRecognitionManager(
 *     viewModel = viewModel,
 *     imageUnderstandingService = imageUnderstandingService,
 *     onRecognitionSuccess = { questions -> handleQuestions(questions) },
 *     onRecognitionError = { error -> handleError(error) }
 * )
 * manager.recognizeErrorBookText(bitmap)
 * ```
 *
 * @param activity Activity实例
 * @param imageUnderstandingService 图片理解服务
 * @param onRecognitionSuccess 识别成功回调，参数为题目列表
 * @param onRecognitionError 识别失败回调，参数为错误信息
 */
class ErrorBookImageRecognitionManager(
    private val viewModel: ErrorBookViewModel,
    private val imageUnderstandingService: ImageUnderstandingService,
    private val onRecognitionSuccess: (List<QuestionAnswer>) -> Unit,
    private val onRecognitionError: (String) -> Unit,
) {
    companion object {
        private const val TAG = "ErrorBookImageRecognition"
    }

    /**
     * 识别错题文本（从Bitmap）- 第一阶段识别
     * 只执行第一阶段识别，识别题目文本
     * 第二阶段识别下划线需要通过 recognizeUnderlines() 方法手动触发
     */
    fun recognizeErrorBookText(bitmap: Bitmap) {
        viewModel.viewModelScope.launch {
            // 第一阶段：识别题目文本
            val stage1Result =
                imageUnderstandingService.understandWords(
                    bitmap,
                    IImageUnderstandingService.ImageUnderstandingMode.GENERIC,
                    PromptConfig.ERROR_BOOK_RECOGNITION,
                )

            when (stage1Result) {
                is AppResult.Success -> {
                    val words = stage1Result.data
                    val recognizedText = mergeWordsToJson(words)

                    if (recognizedText.isEmpty()) {
                        val originalResult =
                            if (words.isEmpty()) {
                                "识别结果为空"
                            } else {
                                "原始识别结果：${words.joinToString(" | ")}"
                            }
                        onRecognitionError("未能识别出题目，请确保图片中包含题目。$originalResult")
                        Logger.d(TAG, "识别结果为空，原始words: $words")
                        return@launch
                    }

                    Logger.d(TAG, "第一阶段识别到的文本: $recognizedText")

                    // 解析第一阶段结果
                    val stage1QuestionAnswers = EnglishErrorBookParser.parseQuestionsWithAnswers(recognizedText)

                    if (stage1QuestionAnswers.isEmpty()) {
                        val originalResult = "原始识别结果：$recognizedText"
                        onRecognitionError("未能解析出有效题目，请检查识别结果格式\n$originalResult")
                        Logger.d(TAG, "解析失败，原始识别结果: $recognizedText")
                        return@launch
                    }

                    // 只返回第一阶段结果，不自动执行第二阶段
                    onRecognitionSuccess(stage1QuestionAnswers)
                }

                is AppResult.Failure -> {
                    val error = stage1Result.error.message
                    Logger.e(TAG, "第一阶段识别失败: $error", stage1Result.error.cause)
                    onRecognitionError(error)
                }
            }
        }
    }

    /**
     * 将识别结果合并为JSON字符串
     */
    private fun mergeWordsToJson(words: List<String>): String {
        return if (words.isNotEmpty()) {
            if (words.size == 1) {
                // 单个元素，可能是完整 JSON 数组字符串或 JSON 对象字符串
                val text = words[0].trim()
                // 如果已经是 JSON 数组格式，直接使用
                if (text.startsWith("[") && text.endsWith("]")) {
                    text
                } else {
                    // 否则，可能是 JSON 对象字符串，需要包装成数组
                    "[$text]"
                }
            } else {
                // 多个元素，尝试合并为 JSON 数组
                val jsonObjects =
                    words
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .joinToString(",")
                "[$jsonObjects]"
            }
        } else {
            ""
        }
    }

    /**
     * 第二阶段：识别下划线（手动触发）
     * @param bitmap 原始图片
     * @param stage1QuestionAnswers 第一阶段识别结果
     * @param onSuccess 识别成功回调，参数为带下划线标记的题目列表
     * @param onError 识别失败回调，参数为错误信息
     */
    fun recognizeUnderlines(
        bitmap: Bitmap,
        stage1QuestionAnswers: List<QuestionAnswer>,
        onSuccess: (List<QuestionAnswer>) -> Unit,
        onError: (String) -> Unit,
    ) {
        viewModel.viewModelScope.launch {
            val result = recognizeUnderlinesInternal(bitmap, stage1QuestionAnswers)
            if (result != null) {
                onSuccess(result)
            } else {
                onError("下划线识别失败，请重试")
            }
        }
    }

    /**
     * 第二阶段：识别下划线（内部实现）
     * @param bitmap 原始图片
     * @param stage1QuestionAnswers 第一阶段识别结果
     * @return 带下划线标记的题目列表，如果识别失败则返回null
     */
    private suspend fun recognizeUnderlinesInternal(
        bitmap: Bitmap,
        stage1QuestionAnswers: List<QuestionAnswer>,
    ): List<QuestionAnswer>? {
        return withContext(Dispatchers.IO) {
            when (
                val result =
                    runAppCatching(TAG, "第二阶段下划线识别") {
                        val apiKey = AppConfig.dashscopeApiKey
                        check(apiKey.isNotBlank()) { "未配置 DASHSCOPE_API_KEY" }

                        val base64Image = bitmapToBase64(bitmap)
                        check(base64Image.length <= AppConfig.MAX_BASE64_CHARS) {
                            "图片过大（约${base64Image.length / 1024}KB Base64）"
                        }

                        // 为每个题目构建带空格的文本
                        val spacedTexts =
                            stage1QuestionAnswers.map { qa ->
                                insertSpacesBetweenChars(qa.question)
                            }

                        // 构建提示词，包含所有题目的带空格文本
                        val prompt = buildUnderlineRecognitionPrompt(spacedTexts, stage1QuestionAnswers)

                        // 使用重试机制执行网络请求
                        NetworkRetryHelper.retryWithBackoff(
                            maxRetries = 3,
                            initialDelayMs = 1000L,
                            maxDelayMs = 5000L,
                        ) {
                            val conv = MultiModalConversation()
                            val userMessage =
                                MultiModalMessage.builder()
                                    .role(Role.USER.value)
                                    .content(
                                        listOf(
                                            hashMapOf<String, Any>(
                                                "image" to "data:image/jpeg;base64,$base64Image",
                                            ),
                                            hashMapOf<String, Any>(
                                                "text" to prompt,
                                            ),
                                        ),
                                    )
                                    .build()

                            val param =
                                MultiModalConversationParam.builder()
                                    .apiKey(apiKey)
                                    .model(AppConfig.IMAGE_UNDERSTANDING_MODEL)
                                    .messages(listOf(userMessage))
                                    .build()

                            if (BuildConfig.DEBUG) {
                                Logger.d(TAG, "开始第二阶段下划线识别（图片Base64长度=${base64Image.length}）")
                            }
                            val apiResult =
                                try {
                                    withTimeout(AppConfig.API_TIMEOUT_MILLIS) {
                                        runInterruptible { conv.call(param) }
                                    }
                                } catch (e: TimeoutCancellationException) {
                                    throw TimeoutException("第二阶段识别请求超时")
                                }
                            val text = DashscopeTextExtractor.extractText(apiResult)
                            if (BuildConfig.DEBUG) {
                                Logger.d(TAG, "第二阶段识别原始输出: ${text.take(500)}")
                            }

                            // 解析第二阶段结果
                            val stage2Json = text.trim()
                            if (stage2Json.isEmpty()) {
                                Logger.w(TAG, "第二阶段识别结果为空，使用第一阶段结果")
                                return@retryWithBackoff null
                            }

                            val stage2QuestionAnswers = EnglishErrorBookParser.parseQuestionsWithAnswers(stage2Json)
                            if (stage2QuestionAnswers.isEmpty()) {
                                Logger.w(TAG, "第二阶段识别解析失败，使用第一阶段结果")
                                return@retryWithBackoff null
                            }

                            // 合并结果：用第二阶段带下划线的question替换第一阶段的question，保留第一阶段的answer
                            mergeRecognitionResults(stage1QuestionAnswers, stage2QuestionAnswers)
                        }
                    }
            ) {
                is AppResult.Success -> result.data
                is AppResult.Failure -> {
                    Logger.e(TAG, "第二阶段识别失败，使用第一阶段结果", result.error.cause)
                    null
                }
            }
        }
    }

    /**
     * 在文本字符间插入空格，但保留原有的空格、换行符和标点符号
     * 例如："Hello\nWorld" → "H e l l o \nW o r l d"
     * 例如："Hello World" → "H e l l o   W o r l d"（原有空格保留）
     */
    private fun insertSpacesBetweenChars(text: String): String {
        if (text.isEmpty()) return text

        val result = StringBuilder()
        var i = 0
        while (i < text.length) {
            val char = text[i]
            result.append(char)

            // 如果不是最后一个字符
            if (i < text.length - 1) {
                val nextChar = text[i + 1]
                // 如果当前字符不是空白字符，且下一个字符也不是空白字符，则在当前字符后插入空格
                // 这样可以确保所有非空白字符之间都有空格，同时保留原有的空白字符
                if (!char.isWhitespace() && !nextChar.isWhitespace()) {
                    result.append(' ')
                }
            }
            i++
        }
        return result.toString()
    }

    /**
     * 构建第二阶段识别下划线的提示词
     */
    private fun buildUnderlineRecognitionPrompt(
        spacedTexts: List<String>,
        originalQuestionAnswers: List<QuestionAnswer>,
    ): String {
        val basePrompt = PromptConfig.ERROR_BOOK_UNDERLINE_RECOGNITION

        // 构建题目文本部分，每个题目单独列出
        val textsSection =
            spacedTexts.mapIndexed { index, text ->
                "题目${index + 1}（字符间已插入空格）：\n$text"
            }.joinToString("\n\n")

        // 构建答案部分
        val answersSection =
            originalQuestionAnswers.mapIndexed { index, qa ->
                "题目${index + 1}的答案：${qa.answer}"
            }.joinToString("\n")

        return """
            $basePrompt
            
            以下是第一阶段识别到的题目文本（字符间已插入空格，请对照图片识别下划线）：
            
            $textsSection
            
            以下是第一阶段识别到的答案（请保持答案不变）：
            $answersSection
            """.trimIndent()
    }

    /**
     * 去除文本中额外添加的空格（第二阶段识别后使用）
     * 去除字符之间插入的空格，但保留原有的空格、换行符和下划线标记
     * 例如："H e __l__ l o \nW o r l d" → "H__e__llo \nWorld"
     * 例如："H e l l o   W o r l d" → "Hello World"（保留单词之间的空格）
     */
    private fun removeExtraSpaces(text: String): String {
        if (text.isEmpty()) return text

        val result = StringBuilder()
        var i = 0
        while (i < text.length) {
            val char = text[i]

            // 如果是空格
            if (char == ' ') {
                val prevChar = if (i > 0) text[i - 1] else null
                val nextChar = if (i < text.length - 1) text[i + 1] else null

                // 检查是否是下划线标记的一部分
                // 下划线标记格式：字母 + "__" + 字母，例如 "a__b"
                val isPartOfUnderline =
                    prevChar == '_' && nextChar == '_' ||
                        (i > 1 && text[i - 2] == '_' && text[i - 1] == '_') ||
                        (i < text.length - 2 && text[i + 1] == '_' && text[i + 2] == '_')

                // 如果前后都是非空白字符，且不是下划线标记的一部分，则这是额外添加的空格，需要去除
                if (prevChar != null && nextChar != null &&
                    !prevChar.isWhitespace() && !nextChar.isWhitespace() &&
                    !isPartOfUnderline
                ) {
                    // 跳过这个额外添加的空格
                    i++
                    continue
                }
                // 如果前后有空白字符（换行、制表符等），则保留这个空格（可能是原有的）
                // 如果前后都是空格，也保留（可能是原有的多个空格）
            }

            result.append(char)
            i++
        }
        return result.toString()
    }

    /**
     * 合并第一阶段和第二阶段的结果
     * 用第二阶段带下划线的question替换第一阶段的question，保留第一阶段的answer
     */
    private fun mergeRecognitionResults(
        stage1Results: List<QuestionAnswer>,
        stage2Results: List<QuestionAnswer>,
    ): List<QuestionAnswer> {
        // 如果第二阶段结果数量与第一阶段不一致，使用第一阶段结果
        if (stage2Results.size != stage1Results.size) {
            Logger.w(TAG, "第二阶段结果数量(${stage2Results.size})与第一阶段(${stage1Results.size})不一致，使用第一阶段结果")
            return stage1Results
        }

        return stage1Results.mapIndexed { index, stage1 ->
            if (index < stage2Results.size) {
                val stage2 = stage2Results[index]
                // 去除第二阶段question中额外添加的空格
                val cleanedQuestion = removeExtraSpaces(stage2.question)
                // 使用第二阶段的question（带下划线，已去除额外空格），保留第一阶段的answer
                QuestionAnswer(cleanedQuestion, stage1.answer)
            } else {
                stage1
            }
        }
    }

    /**
     * 将Bitmap转换为Base64字符串
     */
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(
            Bitmap.CompressFormat.JPEG,
            AppConfig.IMAGE_COMPRESS_QUALITY,
            outputStream,
        )
        val imageBytes = outputStream.toByteArray()
        return Base64.encodeToString(imageBytes, Base64.NO_WRAP)
    }
}
