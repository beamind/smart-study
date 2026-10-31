package com.smartstudy.core

import com.smartstudy.BuildConfig

/**
 * 应用配置管理单例
 * 统一管理应用的所有配置项，包括API配置、超时时间、重试策略等
 *
 * 功能特性：
 * - 集中管理所有配置常量
 * - 支持运行时配置（如TTS语速）
 * - 便于配置调整和维护
 *
 * 使用示例：
 * ```
 * val timeout = AppConfig.apiTimeoutMillis
 * val maxRetries = AppConfig.networkMaxRetries
 * ```
 */
object AppConfig {
    // ==================== API配置 ====================

    /**
     * DashScope API Key
     * 通过 Gradle 注入：优先读取 Gradle 属性 `DASHSCOPE_API_KEY`，其次读取环境变量 `DASHSCOPE_API_KEY`
     */
    val dashscopeApiKey: String
        get() = BuildConfig.DASHSCOPE_API_KEY

    /**
     * 图片理解API使用的模型
     */
    const val IMAGE_UNDERSTANDING_MODEL = "qwen-vl-max"

    /**
     * API请求超时时间（毫秒）
     */
    const val API_TIMEOUT_MILLIS = 45_000L

    /**
     * 图片Base64最大字符数（约3MB文本，不含data:前缀）
     * 限制上传体积，避免极端图片导致OOM/超时/卡顿
     */
    const val MAX_BASE64_CHARS = 3_000_000

    // ==================== 网络重试配置 ====================

    /**
     * 网络请求最大重试次数
     */
    const val NETWORK_MAX_RETRIES = 3

    /**
     * 网络请求初始延迟时间（毫秒）
     */
    const val NETWORK_INITIAL_DELAY_MS = 1000L

    /**
     * 网络请求最大延迟时间（毫秒）
     */
    const val NETWORK_MAX_DELAY_MS = 10_000L

    /**
     * 网络请求延迟倍数（指数退避）
     */
    const val NETWORK_DELAY_MULTIPLIER = 2.0

    // ==================== 超时配置 ====================

    /**
     * 单题超时时间（秒）
     * 每道题目（包括听写）的最长答题时间，超时自动进入下一题
     */
    const val QUESTION_TIMEOUT_SECONDS = 60L

    /**
     * 英文错题本单题超时时间（秒）
     * 英文错题本中每道题目的最长答题时间，超时自动进入下一题
     */
    const val ENGLISH_ERROR_BOOK_QUESTION_TIMEOUT_SECONDS = 120L

    // ==================== TTS配置 ====================

    /**
     * TTS引擎初始化超时时间（秒）
     */
    const val TTS_INIT_TIMEOUT_SECONDS = 5L

    /**
     * 中文听写TTS播放间隔（毫秒）
     */
    const val TTS_INTERVAL_MILLIS_CHINESE = 1000L

    /**
     * 英文听写TTS播放间隔（毫秒）
     */
    const val TTS_INTERVAL_MILLIS_ENGLISH = 1500L

    /**
     * TTS默认语速（范围0.0-2.0，1.0为正常速度）
     */
    const val TTS_DEFAULT_SPEECH_RATE = 0.5f

    /**
     * TTS默认音调（范围0.0-2.0，1.0为正常音调）
     */
    const val TTS_DEFAULT_PITCH = 1.0f

    // ==================== 语音识别配置 ====================

    /**
     * ASR最终结果显示时长（毫秒）
     */
    const val ASR_FINAL_DISPLAY_MILLIS = 8000L

    // ==================== 图片处理配置 ====================

    /**
     * 图片识别最大边长（像素）
     */
    const val MAX_INPUT_IMAGE_SIDE_PX = 1280

    /**
     * 降级后的图片最大边长（像素）
     */
    const val FALLBACK_IMAGE_SIDE_PX = 1024

    /**
     * 图片压缩质量（0-100，80为平衡质量和文件大小）
     */
    const val IMAGE_COMPRESS_QUALITY = 80

    // ==================== 存储配置 ====================

    /**
     * 保留的旧文件数量
     */
    const val MAX_OLD_FILES_COUNT = 10

    // ==================== 时间转换常量 ====================

    /**
     * 毫秒转秒的倍数
     */
    const val MILLIS_PER_SECOND = 1000L

    /**
     * 秒转分钟的倍数
     */
    const val SECONDS_PER_MINUTE = 60L

    /**
     * 分钟转小时的倍数
     */
    const val MINUTES_PER_HOUR = 60L

    /**
     * 小时转天的倍数
     */
    const val HOURS_PER_DAY = 24L

    /**
     * 毫秒转分钟的倍数
     */
    const val MILLIS_PER_MINUTE = MILLIS_PER_SECOND * SECONDS_PER_MINUTE

    /**
     * 毫秒转小时的倍数
     */
    const val MILLIS_PER_HOUR = MILLIS_PER_MINUTE * MINUTES_PER_HOUR

    /**
     * 毫秒转天的倍数
     */
    const val MILLIS_PER_DAY = MILLIS_PER_HOUR * HOURS_PER_DAY

    // ==================== UI延迟配置 ====================

    /**
     * 初始化错误提示显示时长（毫秒）
     * 用于显示错误消息后延迟关闭Activity
     */
    const val INIT_ERROR_DISPLAY_MILLIS = 2000L

    /**
     * 初始化提示显示时长（毫秒）
     * 用于显示初始化状态提示后自动隐藏
     */
    const val INIT_HINT_DISPLAY_MILLIS = 500L

    // ==================== 权限请求码 ====================

    /**
     * 相机权限请求码
     */
    const val REQUEST_CAMERA = 100

    /**
     * 通用权限请求码
     */
    const val REQUEST_PERMISSIONS = 200

    // ==================== 听写关键词 ====================

    /**
     * 开始关键词
     */
    const val KEYWORD_START = "开始"

    /**
     * 开始听写关键词
     */
    const val KEYWORD_START_DICTATION = "开始听写"

    /**
     * 下一个关键词
     */
    const val KEYWORD_NEXT = "下一个"

    /**
     * 写好了关键词
     */
    const val KEYWORD_DONE = "写好了"

    // ==================== 听写类型 ====================

    /**
     * 中文听写类型
     */
    const val TYPE_CHINESE = "chinese"

    /**
     * 英文听写类型
     */
    const val TYPE_ENGLISH = "english"

    // ==================== 运行时配置（可修改）====================

    /**
     * TTS语速（运行时可修改）
     * 范围：0.0-2.0，1.0为正常速度
     */
    var ttsSpeechRate: Float = TTS_DEFAULT_SPEECH_RATE
        set(value) {
            val clamped = value.coerceIn(0.0f, 2.0f)
            if (clamped != value) {
                Logger.w("AppConfig", "TTS语速超出范围（$value），已限制为 $clamped")
            }
            field = clamped
        }

    /**
     * TTS音调（运行时可修改）
     * 范围：0.0-2.0，1.0为正常音调
     */
    var ttsPitch: Float = TTS_DEFAULT_PITCH
        set(value) {
            val clamped = value.coerceIn(0.0f, 2.0f)
            if (clamped != value) {
                Logger.w("AppConfig", "TTS音调超出范围（$value），已限制为 $clamped")
            }
            field = clamped
        }

    /**
     * 验证配置的有效性
     * 在应用启动时调用，确保所有配置都在合理范围内
     */
    fun validateConfig() {
        val issues = mutableListOf<String>()

        // 验证 API Key
        if (dashscopeApiKey.isBlank()) {
            issues.add("DASHSCOPE_API_KEY 未配置")
        }

        // 验证超时时间
        if (API_TIMEOUT_MILLIS <= 0) {
            issues.add("API_TIMEOUT_MILLIS 必须大于 0")
        }

        // 验证图片尺寸限制
        if (MAX_INPUT_IMAGE_SIDE_PX <= 0 || FALLBACK_IMAGE_SIDE_PX <= 0) {
            issues.add("图片尺寸限制必须大于 0")
        }
        if (FALLBACK_IMAGE_SIDE_PX >= MAX_INPUT_IMAGE_SIDE_PX) {
            issues.add("降级图片尺寸应小于正常尺寸")
        }

        // 验证单题超时时间
        if (QUESTION_TIMEOUT_SECONDS <= 0) {
            issues.add("QUESTION_TIMEOUT_SECONDS 必须大于 0")
        }

        if (issues.isNotEmpty()) {
            Logger.e("AppConfig", "配置验证失败: ${issues.joinToString("; ")}")
            error("应用配置无效: ${issues.joinToString("; ")}")
        }

        Logger.i("AppConfig", "配置验证通过")
    }
}
