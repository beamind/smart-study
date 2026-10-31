package com.smartstudy.dictation.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.smartstudy.R
import com.smartstudy.core.Logger
import com.smartstudy.dictation.core.interfaces.ITtsService
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TTS服务封装
 * 使用 Android 系统自带的 TextToSpeech 引擎
 * 无需网络连接和API密钥
 *
 * 功能特性：
 * - 支持自定义语速和音调
 * - 支持选择音色（如果设备支持）
 * - 自动处理引擎初始化
 * - 线程安全，支持并发调用
 *
 * 使用示例：
 * ```
 * val ttsService = TtsService(context)
 * ttsService.speak("你好", object : ITtsService.TtsCallback {
 *     override fun onStart() { }
 *     override fun onComplete() { }
 *     override fun onError(error: String) { }
 * })
 * ```
 *
 * @param context Android Context，用于初始化 TTS 引擎
 */
class TtsService(private val context: Context) : ITtsService {
    companion object {
        private const val TAG = "TtsService"
        private const val UTTERANCE_ID = "TtsService_Utterance"
        private const val INIT_TIMEOUT_SECONDS = 5L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var textToSpeech: TextToSpeech? = null
    private val isInitialized = AtomicBoolean(false)
    private val initLatch = CountDownLatch(1)
    private var currentCallback: ITtsService.TtsCallback? = null
    private val ttsLock = Any()
    private val isReleased = AtomicBoolean(false)

    // 串行化 TTS 操作：避免每次 speak() 新建 Thread、减少竞态
    private val ttsExecutor =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "TtsService").apply { isDaemon = true }
        }

    // TTS参数设置
    private var speechRate: Float = 0.5f // 默认正常语速，范围 0.0-2.0
    private var pitch: Float = 1.0f // 默认正常音调，范围 0.0-2.0
    private var voice: android.speech.tts.Voice? = null // 当前使用的音色

    init {
        // 初始化 TextToSpeech
        textToSpeech =
            TextToSpeech(context) { status ->
                synchronized(ttsLock) {
                    if (status == TextToSpeech.SUCCESS) {
                        isInitialized.set(true)
                        // 设置默认语速和音调
                        textToSpeech?.setSpeechRate(speechRate)
                        textToSpeech?.setPitch(pitch)
                        Logger.d(TAG, "TTS引擎初始化成功，已设置默认参数（语速=$speechRate, 音调=$pitch）")
                    } else {
                        Logger.e(TAG, "TTS引擎初始化失败，状态码: $status")
                    }
                    initLatch.countDown()
                }
            }

        // 设置播放进度监听器
        textToSpeech?.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Logger.d(TAG, "TTS播放开始: $utteranceId")
                    mainHandler.post {
                        currentCallback?.onStart()
                    }
                }

                override fun onDone(utteranceId: String?) {
                    Logger.d(TAG, "TTS播放完成: $utteranceId")
                    mainHandler.post {
                        currentCallback?.onComplete()
                        currentCallback = null
                    }
                }

                override fun onError(utteranceId: String?) {
                    Logger.e(TAG, "TTS播放错误: $utteranceId")
                    mainHandler.post {
                        currentCallback?.onError("TTS播放失败")
                        currentCallback = null
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(
                    utteranceId: String?,
                    errorCode: Int,
                ) {
                    // 调用非deprecated版本的onError方法，减少重复代码
                    onError(utteranceId)
                }
            },
        )
    }

    /**
     * 等待TTS引擎初始化完成
     */
    private fun waitForInitialization(): Boolean {
        return try {
            val success = initLatch.await(INIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!success) {
                Logger.e(TAG, "TTS引擎初始化超时")
                return false
            }
            isInitialized.get()
        } catch (e: InterruptedException) {
            Logger.e(TAG, "等待TTS初始化被中断", e)
            false
        }
    }

    /**
     * 将语言类型字符串转换为Locale
     */
    private fun getLocaleFromLanguageType(languageType: String?): Locale {
        return when (languageType?.lowercase()) {
            "chinese", "zh", "zh-cn" -> Locale.CHINESE
            "english", "en" -> Locale.ENGLISH
            "japanese", "ja" -> Locale.JAPANESE
            "korean", "ko" -> Locale.KOREAN
            "french", "fr" -> Locale.FRENCH
            "german", "de" -> Locale.GERMAN
            "spanish", "es" -> Locale("es")
            "italian", "it" -> Locale.ITALIAN
            else -> Locale.getDefault() // 默认使用系统语言
        }
    }

    /**
     * 设置语速
     *
     * @param rate 语速，范围 0.0-2.0，1.0 为正常速度
     *              - 0.5：慢速（适合初学者）
     *              - 1.0：正常速度
     *              - 2.0：快速
     */
    override fun setSpeechRate(rate: Float) {
        val clampedRate = rate.coerceIn(0.0f, 2.0f)
        speechRate = clampedRate
        synchronized(ttsLock) {
            textToSpeech?.setSpeechRate(clampedRate)
        }
        Logger.d(TAG, "语速已设置为: $clampedRate")
    }

    /**
     * 获取当前语速
     */
    fun getSpeechRate(): Float = speechRate

    /**
     * 设置音调
     *
     * @param pitch 音调，范围 0.0-2.0，1.0 为正常音调
     *               - 0.5：低音调
     *               - 1.0：正常音调
     *               - 2.0：高音调
     */
    override fun setPitch(pitch: Float) {
        val clampedPitch = pitch.coerceIn(0.0f, 2.0f)
        this.pitch = clampedPitch
        synchronized(ttsLock) {
            textToSpeech?.setPitch(clampedPitch)
        }
        Logger.d(TAG, "音调已设置为: $clampedPitch")
    }

    /**
     * 获取当前音调
     */
    fun getPitch(): Float = pitch

    /**
     * 获取可用的音色列表
     * @return 可用音色列表
     */
    fun getAvailableVoices(): List<android.speech.tts.Voice> {
        return synchronized(ttsLock) {
            if (waitForInitialization()) {
                textToSpeech?.voices?.toList() ?: emptyList()
            } else {
                emptyList()
            }
        }
    }

    /**
     * 设置音色
     * @param voiceName 音色名称，如果为null则使用系统默认音色
     * @return 是否设置成功
     */
    fun setVoice(voiceName: String?): Boolean {
        return synchronized(ttsLock) {
            if (!waitForInitialization()) {
                Logger.e(TAG, "TTS未初始化，无法设置音色")
                return false
            }

            val tts = textToSpeech ?: return false

            if (voiceName == null) {
                // 使用系统默认音色
                voice = null
                Logger.d(TAG, "已重置为系统默认音色")
                return true
            }

            // 查找匹配的音色
            val availableVoices = tts.voices
            val matchedVoice =
                availableVoices?.find {
                    it.name.equals(voiceName, ignoreCase = true) ||
                        it.locale.toString().contains(voiceName, ignoreCase = true)
                }

            if (matchedVoice != null) {
                val result = tts.setVoice(matchedVoice)
                if (result == TextToSpeech.SUCCESS) {
                    voice = matchedVoice
                    Logger.d(TAG, "音色已设置为: ${matchedVoice.name}, 语言: ${matchedVoice.locale}")
                    return true
                } else {
                    Logger.e(TAG, "设置音色失败，错误码: $result")
                    return false
                }
            } else {
                Logger.w(TAG, "未找到音色: $voiceName，可用音色: ${availableVoices?.map { it.name }}")
                return false
            }
        }
    }

    /**
     * 获取当前使用的音色
     */
    fun getCurrentVoice(): android.speech.tts.Voice? = voice

    /**
     * 朗读文本（使用默认语言：中文）
     *
     * @param text 要朗读的文本
     * @param callback TTS回调接口，用于监听播放状态
     */
    override fun speak(
        text: String,
        callback: ITtsService.TtsCallback?,
    ) {
        speak(text, null, "Chinese", callback)
    }

    /**
     * 朗读文本（带自定义参数）
     *
     * 注意：此方法是异步的，会在后台线程执行，回调在主线程执行。
     *
     * @param text 要朗读的文本
     * @param voice 音色名称（如果设备支持，将尝试设置该音色），null 表示使用系统默认音色
     * @param languageType 语言类型（如 "Chinese", "English" 等），用于设置 TTS 语言
     * @param callback TTS回调接口，用于监听播放状态
     */
    fun speak(
        text: String,
        voice: String?,
        languageType: String?,
        callback: ITtsService.TtsCallback?,
    ) {
        if (isReleased.get()) {
            callback?.onError(context.getString(R.string.tts_released))
            return
        }

        try {
            ttsExecutor.execute {
                try {
                    // 如果正在播放，先停止（串行队列内执行，保证 stop->speak 顺序）
                    stop()

                    // 等待TTS引擎初始化
                    if (!waitForInitialization()) {
                        mainHandler.post { callback?.onError(context.getString(R.string.tts_init_failed_or_timeout)) }
                        return@execute
                    }

                    synchronized(ttsLock) {
                        val tts = textToSpeech
                        if (tts == null) {
                            mainHandler.post { callback?.onError(context.getString(R.string.tts_init_failed_or_timeout)) }
                            return@execute
                        }

                        // 确保语速和音调已设置
                        tts.setSpeechRate(speechRate)
                        tts.setPitch(pitch)

                        // 设置语言
                        val locale = getLocaleFromLanguageType(languageType)
                        val result = tts.setLanguage(locale)
                        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                            Logger.w(TAG, "语言不支持: $languageType，使用默认语言")
                            tts.setLanguage(Locale.getDefault())
                        }

                        // 如果指定了 voice 参数，尝试设置音色
                        if (voice != null) {
                            val voiceSet = setVoice(voice)
                            if (!voiceSet) {
                                Logger.d(TAG, "无法设置音色: $voice，使用当前音色")
                            }
                        }

                        // 保存回调
                        currentCallback = callback

                        Logger.d(TAG, "开始TTS播放: text=$text, languageType=$languageType, locale=$locale, 语速=$speechRate, 音调=$pitch")
                        val speakResult =
                            tts.speak(
                                text,
                                TextToSpeech.QUEUE_FLUSH,
                                null,
                                UTTERANCE_ID,
                            )
                        if (speakResult != TextToSpeech.SUCCESS) {
                            mainHandler.post {
                                callback?.onError(context.getString(R.string.error_tts) + ": $speakResult")
                                currentCallback = null
                            }
                        }
                    }
                } catch (e: IllegalStateException) {
                    Logger.e(TAG, "状态错误，TTS处理异常", e)
                    mainHandler.post {
                        callback?.onError(context.getString(R.string.tts_process_failed, e.message ?: context.getString(R.string.error_state)))
                        currentCallback = null
                    }
                } catch (e: IllegalArgumentException) {
                    Logger.e(TAG, "参数错误，TTS处理异常", e)
                    mainHandler.post {
                        callback?.onError(context.getString(R.string.tts_process_failed, e.message ?: context.getString(R.string.error_argument)))
                        currentCallback = null
                    }
                }
            }
        } catch (e: RejectedExecutionException) {
            // TTS 线程池已关闭（通常在 release() 后调用 speak() 时发生）
            // 这是合理的异常处理：通知回调错误，但不记录错误日志（因为这是预期的行为）
            Logger.d(TAG, "TTS线程已关闭，无法执行speak操作")
            callback?.onError("TTS线程已关闭")
        }
    }

    /**
     * 停止播放
     */
    override fun stop() {
        synchronized(ttsLock) {
            try {
                textToSpeech?.stop()
                currentCallback = null
                Logger.d(TAG, "TTS播放已停止")
            } catch (e: IllegalStateException) {
                Logger.e(TAG, "状态错误，停止播放失败", e)
            }
        }
    }

    /**
     * 释放资源
     * 应在Activity的onDestroy中调用
     */
    override fun release() {
        if (!isReleased.compareAndSet(false, true)) return

        // 先停止队列中的任务，避免销毁后仍在操作 TTS 对象
        runCatching { ttsExecutor.shutdownNow() }

        synchronized(ttsLock) {
            try {
                // 清理所有Handler回调，防止内存泄漏
                mainHandler.removeCallbacksAndMessages(null)

                textToSpeech?.stop()
                textToSpeech?.shutdown()
                textToSpeech = null
                currentCallback = null
                isInitialized.set(false)
                Logger.d(TAG, "TTS资源已释放")
            } catch (e: IllegalStateException) {
                Logger.e(TAG, "状态错误，释放TTS资源失败", e)
            }
        }
    }
}
