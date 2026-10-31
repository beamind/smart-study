package com.smartstudy.dictation.core

import android.graphics.Bitmap
import android.util.Base64
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam
import com.alibaba.dashscope.common.MultiModalMessage
import com.alibaba.dashscope.common.Role
import com.google.gson.JsonParser
import com.smartstudy.BuildConfig
import com.smartstudy.core.AppResult
import com.smartstudy.core.Logger
import com.smartstudy.core.PromptConfig
import com.smartstudy.core.runAppCatching
import com.smartstudy.dictation.core.interfaces.IImageUnderstandingService
import com.smartstudy.dictation.core.interfaces.toServiceMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeoutException

/**
 * 千问图片理解服务封装（用于从图片中提取可用于听写的词语/短语）。
 *
 * 功能特性：
 * - 支持中文听写、英文听写和通用模式
 * - 自动重试机制（指数退避策略）
 * - 图片大小自动限制，避免 OOM
 * - 支持自定义提示词
 *
 * 使用示例：
 * ```
 * val service = ImageUnderstandingService()
 * val result = service.understandWords(bitmap, ImageUnderstandingMode.CHINESE_DICTATION)
 * when (result) {
 *     is AppResult.Success -> println("识别到词语: ${result.data}")
 *     is AppResult.Failure -> println("识别失败: ${result.error.message}")
 * }
 * ```
 *
 * @param ioDispatcher 协程调度器，默认使用 Dispatchers.IO
 */
class ImageUnderstandingService(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : com.smartstudy.dictation.core.interfaces.IImageUnderstandingService {
    companion object {
        private const val TAG = "ImageUnderstanding"
        private val MODEL = com.smartstudy.core.AppConfig.IMAGE_UNDERSTANDING_MODEL
        private val API_TIMEOUT_MILLIS = com.smartstudy.core.AppConfig.API_TIMEOUT_MILLIS
        private val MAX_BASE64_CHARS = com.smartstudy.core.AppConfig.MAX_BASE64_CHARS
    }

    /**
     * 图片理解模式
     */
    enum class ImageUnderstandingMode {
        /** 中文听写模式：从图片中提取中文词语 */
        CHINESE_DICTATION,

        /** 英文听写模式：从图片中提取英文单词/短语 */
        ENGLISH_DICTATION,

        /** 通用模式：可用于错题识别等场景，支持自定义提示词 */
        GENERIC,
    }

    /**
     * 从图片中提取词语/短语（默认通用模式）。
     */
    override suspend fun understandWords(bitmap: Bitmap): AppResult<List<String>> =
        understandWords(bitmap, IImageUnderstandingService.ImageUnderstandingMode.GENERIC, null)

    /**
     * 从图片中提取词语/短语（带自定义提示）。
     */
    override suspend fun understandWords(
        bitmap: Bitmap,
        prompt: String?,
    ): AppResult<List<String>> =
        understandWords(bitmap, IImageUnderstandingService.ImageUnderstandingMode.GENERIC, prompt)

    /**
     * 从图片中提取词语/短语（指定场景模式）。
     */
    override suspend fun understandWords(
        bitmap: Bitmap,
        mode: IImageUnderstandingService.ImageUnderstandingMode,
    ): AppResult<List<String>> =
        understandWords(bitmap, mode, null)

    /**
     * 从图片中提取词语/短语（指定场景模式 + 可选自定义提示）。
     *
     * 这是一个 suspend 函数：由调用方（Activity/Fragment）决定协程作用域与取消时机，更符合 Android 生命周期。
     * 支持自动重试机制（指数退避策略）。
     */
    override suspend fun understandWords(
        bitmap: Bitmap,
        mode: IImageUnderstandingService.ImageUnderstandingMode,
        prompt: String?,
    ): AppResult<List<String>> =
        withContext(ioDispatcher) {
            runAppCatching(TAG, "图片理解") {
                val apiKey = com.smartstudy.core.AppConfig.dashscopeApiKey
                check(apiKey.isNotBlank()) { "未配置 DASHSCOPE_API_KEY（请在本机 Gradle 属性或环境变量中设置）" }

                val base64Image = bitmapToBase64(bitmap)
                check(base64Image.length <= MAX_BASE64_CHARS) {
                    "图片过大（约${base64Image.length / 1024}KB Base64），请在裁剪界面缩小范围后重试"
                }

                // 使用重试机制执行网络请求
                com.smartstudy.core.NetworkRetryHelper.retryWithBackoff(
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
                                        "text" to buildPrompt(mode.toServiceMode(), prompt),
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

                    if (BuildConfig.DEBUG) {
                        Logger.d(TAG, "开始 qwen-vl-max 图片理解调用（图片Base64长度=${base64Image.length}）")
                    }
                    val result =
                        try {
                            withTimeout(API_TIMEOUT_MILLIS) {
                                runInterruptible { conv.call(param) }
                            }
                        } catch (e: TimeoutCancellationException) {
                            throw TimeoutException("图片理解请求超时")
                        }
                    val text = DashscopeTextExtractor.extractText(result)
                    if (BuildConfig.DEBUG) {
                        Logger.d(TAG, "qwen-vl-max 图片理解原始输出: ${text.take(500)}")
                    }

                    parseWords(text, mode.toServiceMode())
                }
            }
        }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, com.smartstudy.core.AppConfig.IMAGE_COMPRESS_QUALITY, outputStream)
        val imageBytes = outputStream.toByteArray()
        return Base64.encodeToString(imageBytes, Base64.NO_WRAP)
    }

    private fun buildPrompt(
        mode: ImageUnderstandingMode,
        prompt: String?,
    ): String {
        val userTask = prompt?.takeIf { it.isNotBlank() }

        return when (mode) {
            ImageUnderstandingMode.CHINESE_DICTATION -> PromptConfig.getChineseDictationPrompt(userTask)
            ImageUnderstandingMode.ENGLISH_DICTATION -> PromptConfig.getEnglishDictationPrompt(userTask)
            ImageUnderstandingMode.GENERIC -> PromptConfig.getGenericPrompt(userTask)
        }
    }

    private fun parseWords(
        text: String,
        mode: ImageUnderstandingMode,
    ): List<String> {
        // 边界检查：空字符串或null
        if (text.isBlank()) {
            Logger.w(TAG, "输入文本为空，返回空列表")
            return emptyList()
        }

        val cleaned = text.trim()
        if (cleaned.isEmpty()) return emptyList()

        // 1) JSON 数组优先
        val jsonResult = tryParseJsonArray(cleaned, mode)
        if (jsonResult != null) {
            return jsonResult
        }

        // 2) 兜底：按标点/空白切分
        return parseByDelimiters(cleaned, mode)
    }

    private fun tryParseJsonArray(
        cleaned: String,
        mode: ImageUnderstandingMode,
    ): List<String>? {
        return try {
            val element = JsonParser.parseString(cleaned)
            if (element.isJsonArray) {
                parseJsonArray(element.asJsonArray, cleaned, mode)
            } else {
                null
            }
        } catch (e: org.json.JSONException) {
            Logger.w(TAG, "JSON解析失败，尝试其他解析方式", e)
            null
        } catch (e: IllegalStateException) {
            Logger.w(TAG, "状态错误，JSON解析失败，尝试其他解析方式", e)
            null
        }
    }

    private fun parseJsonArray(
        array: com.google.gson.JsonArray,
        cleaned: String,
        mode: ImageUnderstandingMode,
    ): List<String>? {
        // 边界检查：空数组
        if (array.size() == 0) {
            Logger.w(TAG, "JSON数组为空，返回空列表")
            return emptyList()
        }

        // 检查是否是对象数组（用于错题本等场景，GENERIC模式）
        if (isJsonObjectArray(array, mode)) {
            return handleJsonObjectArray(cleaned)
        }

        // 原有的字符串数组解析逻辑
        return parseStringArrayAndFilter(array, mode)
    }

    /**
     * 检查是否是JSON对象数组
     */
    private fun isJsonObjectArray(
        array: com.google.gson.JsonArray,
        mode: ImageUnderstandingMode,
    ): Boolean {
        return mode == ImageUnderstandingMode.GENERIC &&
            array.size() > 0 &&
            array[0].isJsonObject
    }

    /**
     * 处理JSON对象数组
     */
    private fun handleJsonObjectArray(cleaned: String): List<String> {
        if (BuildConfig.DEBUG) {
            Logger.d(TAG, "检测到JSON对象数组，返回原始JSON字符串")
        }
        return listOf(cleaned)
    }

    /**
     * 解析字符串数组并过滤
     */
    private fun parseStringArrayAndFilter(
        array: com.google.gson.JsonArray,
        mode: ImageUnderstandingMode,
    ): List<String>? {
        val result = parseStringArray(array)
        return if (result.isNotEmpty()) {
            filterByMode(result, mode)
        } else {
            Logger.w(TAG, "解析后结果为空，可能所有元素都被过滤")
            null
        }
    }

    private fun parseStringArray(array: com.google.gson.JsonArray): List<String> {
        val result = mutableListOf<String>()
        array.forEach { e ->
            try {
                val v = e.asString?.trim()
                if (!v.isNullOrEmpty() && v.length <= 200) { // 限制单个词语长度
                    result.add(v)
                } else if (v != null && v.length > 200) {
                    Logger.w(TAG, "词语过长（${v.length}字符），跳过: ${v.take(50)}...")
                }
            } catch (e: org.json.JSONException) {
                Logger.w(TAG, "JSON解析错误，解析数组元素失败，跳过", e)
            } catch (e: ClassCastException) {
                Logger.w(TAG, "类型转换错误，解析数组元素失败，跳过", e)
            }
        }
        return result
    }

    private fun parseByDelimiters(
        cleaned: String,
        mode: ImageUnderstandingMode,
    ): List<String> {
        val raw =
            cleaned
                .split(Regex("[\\s，。、；：！？,.;:!?\\[\\]\"“”]+"))
                .map { it.trim() }
                .filter { it.isNotEmpty() && it.length <= 30 }

        return filterByMode(raw, mode)
    }

    private fun filterByMode(
        items: List<String>,
        mode: ImageUnderstandingMode,
    ): List<String> {
        val filtered = when (mode) {
            ImageUnderstandingMode.CHINESE_DICTATION -> filterChineseDictation(items)
            ImageUnderstandingMode.ENGLISH_DICTATION -> filterEnglishDictation(items)
            ImageUnderstandingMode.GENERIC -> items
        }

        val deduped = dedupeByMode(filtered, mode)

        if (deduped.size != filtered.size) {
            if (BuildConfig.DEBUG) {
                Logger.d(TAG, "图片理解结果已去重: ${filtered.size} -> ${deduped.size}（mode=$mode）")
            }
        }

        return deduped
    }

    private fun filterChineseDictation(items: List<String>): List<String> {
        val chinese = Regex("[\\u4e00-\\u9fa5]")
        val hasLatin = Regex("[A-Za-z]")
        val hasDigit = Regex("\\d")
        return items
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { chinese.containsMatchIn(it) }
            .filter { !hasLatin.containsMatchIn(it) && !hasDigit.containsMatchIn(it) }
            .map { it.trim('\"', '“', '”', '《', '》', '（', '）', '(', ')') }
            .filter { it.isNotBlank() && it.length <= 10 }
    }

    private fun filterEnglishDictation(items: List<String>): List<String> {
        val chinese = Regex("[\\u4e00-\\u9fa5]")
        val hasDigit = Regex("\\d")
        val valid = Regex("^[A-Za-z][A-Za-z\\s\\-']*[A-Za-z]$|^[A-Za-z]+$")
        return items
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { !chinese.containsMatchIn(it) }
            .filter { !hasDigit.containsMatchIn(it) }
            // 去掉常见音标包裹符号
            .map { it.replace("/", "").replace("[", "").replace("]", "") }
            .map { it.trim('\"', '“', '”', '(', ')', '（', '）') }
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotBlank() && it.length <= 200 }
            .filter { valid.matches(it) }
    }

    private fun dedupePreserveOrder(
        list: List<String>,
        keySelector: (String) -> String,
    ): List<String> {
        if (list.isEmpty()) return emptyList()
        val seen = HashSet<String>(list.size * 2)
        val out = ArrayList<String>(list.size)
        for (v in list) {
            val key = keySelector(v)
            if (seen.add(key)) out.add(v)
        }
        return out
    }

    private fun dedupeByMode(
        filtered: List<String>,
        mode: ImageUnderstandingMode,
    ): List<String> {
        return when (mode) {
            ImageUnderstandingMode.ENGLISH_DICTATION -> dedupePreserveOrder(filtered) { it.lowercase().trim() }
            else -> dedupePreserveOrder(filtered) { it.trim() }
        }
    }
}
