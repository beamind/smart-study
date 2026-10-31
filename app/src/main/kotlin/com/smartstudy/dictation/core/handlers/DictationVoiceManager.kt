package com.smartstudy.dictation.core.handlers

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import com.smartstudy.core.AppConfig
import com.smartstudy.core.Logger
import com.smartstudy.dictation.core.VoiceRecognitionService
import com.smartstudy.dictation.core.interfaces.ITtsService
import com.smartstudy.utils.runOnUiThreadSafe

/**
 * 听写语音处理管理器
 *
 * 负责语音识别和TTS播放管理，将语音相关功能从Activity中分离出来。
 *
 * 功能特性：
 * - 支持普通模式和"开始"关键词监听模式
 * - 自动管理ASR UI显示和隐藏（使用协程，生命周期感知）
 * - TTS播放管理（准备就绪提示、词语播放）
 * - 生命周期感知，确保资源正确释放
 * - 统一的错误处理
 *
 * 使用示例：
 * ```
 * val voiceManager = DictationVoiceManager(
 *     activity = this,
 *     ttsService = ttsService,
 *     voiceRecognitionService = voiceRecognitionService,
 *     onKeywordDetected = { goToNextWord() },
 *     onStartKeywordDetected = { startDictation() },
 *     isWaitingForStart = { stateManager.isWaitingForStart },
 *     isInForeground = { stateManager.isInForeground }
 * )
 * voiceManager.tvRecognitionText = tvRecognitionText
 * voiceManager.tvRecognitionStatus = tvRecognitionStatus
 * voiceManager.startListening()
 * ```
 *
 * **注意**：需要在Activity的onDestroy中调用cleanup()方法释放资源。
 *
 * @param activity Activity实例
 * @param ttsService TTS服务
 * @param voiceRecognitionService 语音识别服务
 * @param onKeywordDetected 普通模式关键词检测回调
 * @param onStartKeywordDetected "开始"关键词检测回调
 * @param isWaitingForStart 检查是否等待开始关键词的函数
 * @param isInForeground 检查是否在前台的函数
 */
class DictationVoiceManager(
    private val activity: AppCompatActivity,
    private val ttsService: ITtsService,
    private val voiceRecognitionService: VoiceRecognitionService,
    private val onKeywordDetected: () -> Unit,
    private val onStartKeywordDetected: () -> Unit,
    private val isWaitingForStart: () -> Boolean,
    private val isInForeground: () -> Boolean,
) {
    companion object {
        private const val TAG = "DictationVoiceManager"
    }

    // ASR UI处理 - 使用Handler延迟，避免Activity持有协程引用
    private var hideAsrUiRunnable: Runnable? = null

    // UI视图（需要从外部设置）
    var tvRecognitionText: TextView? = null
    var tvRecognitionStatus: TextView? = null

    /**
     * 开始监听（普通模式）
     */
    fun startListening() {
        voiceRecognitionService.startListening(
            object : VoiceRecognitionService.VoiceRecognitionCallback {
                override fun onKeywordDetected(keyword: String) {
                    activity.runOnUiThreadSafe {
                        Logger.d(TAG, "检测到关键词: $keyword，进入下一个词语")
                        onKeywordDetected()
                    }
                }

                override fun onError(error: String) {
                    activity.runOnUiThreadSafe {
                        Logger.e(TAG, "语音识别错误: $error")
                        onError(error)
                    }
                }

                override fun onRecognitionResult(
                    text: String?,
                    isPartial: Boolean,
                ) {
                    activity.runOnUiThreadSafe {
                        updateAsrUI(text, isPartial)
                        onRecognitionResult(text, isPartial)
                    }
                }
            },
        )
    }

    /**
     * 开始监听"开始"关键词
     */
    fun startListeningForStart() {
        voiceRecognitionService.startListening(
            object : VoiceRecognitionService.VoiceRecognitionCallback {
                override fun onKeywordDetected(keyword: String) {
                    activity.runOnUiThreadSafe {
                        if (isWaitingForStart()) {
                            if (!isInForeground() || !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                                Logger.w(TAG, "检测到开始关键词: $keyword，但Activity不在前台或未启动，返回")
                                return@runOnUiThreadSafe
                            }

                            Logger.d(TAG, "✓ 检测到开始关键词，开始听写")
                            onStartKeywordDetected()
                        }
                    }
                }

                override fun onError(error: String) {
                    activity.runOnUiThreadSafe {
                        Logger.e(TAG, "语音识别错误: $error")
                        onError(error)
                    }
                }

                override fun onRecognitionResult(
                    text: String?,
                    isPartial: Boolean,
                ) {
                    activity.runOnUiThreadSafe {
                        updateAsrUI(text, isPartial)
                    }
                }
            },
            forStart = true,
        )
    }

    /**
     * 停止监听
     */
    fun stopListening(clearAsrUi: Boolean = true) {
        voiceRecognitionService.stopListening()
        if (clearAsrUi) {
            // 取消延迟任务
            hideAsrUiRunnable?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
            hideAsrUiRunnable = null
            hideAsrUI()
        }
    }

    /**
     * 播放准备就绪提示
     */
    fun speakReadyPrompt(onComplete: () -> Unit) {
        ttsService.speak(
            "我已经准备好了，请对我说开始听写",
            object : com.smartstudy.dictation.core.interfaces.ITtsService.TtsCallback {
                override fun onStart() {
                    Logger.d(TAG, "开始播放准备就绪提示")
                }

                override fun onComplete() {
                    activity.runOnUiThread {
                        Logger.d(TAG, "准备就绪提示播放完成，开始监听")
                        onComplete()
                    }
                }

                override fun onError(error: String) {
                    activity.runOnUiThread {
                        Logger.e(TAG, "播放准备就绪提示失败: $error")
                        // 即使TTS失败，也继续监听
                        onComplete()
                    }
                }
            },
        )
    }

    /**
     * 播放词语（支持两遍播放，带间隔）
     *
     * @param text 要播放的文本
     * @param intervalMillis 两遍播放之间的间隔（毫秒）
     * @param onComplete 播放完成回调（第二遍播放完成后调用）
     * @param onError 播放错误回调
     */
    fun speakWord(
        text: String,
        intervalMillis: Long,
        onComplete: () -> Unit,
        onError: (String) -> Unit,
    ) {
        speakWordOnce(text, intervalMillis, isFirstTime = true, onComplete, onError)
    }

    /**
     * 播放词语一次（内部方法，用于实现读两遍的逻辑）
     */
    private fun speakWordOnce(
        text: String,
        intervalMillis: Long,
        isFirstTime: Boolean,
        onComplete: () -> Unit,
        onError: (String) -> Unit,
    ) {
        ttsService.speak(
            text,
            object : com.smartstudy.dictation.core.interfaces.ITtsService.TtsCallback {
                override fun onStart() {
                    Logger.d(TAG, "开始播放${if (isFirstTime) "第一遍" else "第二遍"}: $text")
                }

                override fun onComplete() {
                    if (isFirstTime) {
                        // 第一遍播放完成，等待间隔后播放第二遍
                        // 使用Handler延迟，避免Activity持有协程引用
                        Logger.d(TAG, "第一遍播放完成: $text，等待${intervalMillis}ms后播放第二遍")
                        Handler(Looper.getMainLooper()).postDelayed({
                            if (!activity.isFinishing && !activity.isDestroyed) {
                                speakWordOnce(text, intervalMillis, isFirstTime = false, onComplete, onError)
                            }
                        }, intervalMillis)
                    } else {
                        // 第二遍播放完成，调用完成回调
                        Logger.d(TAG, "第二遍播放完成: $text，开始监听语音")
                        activity.runOnUiThread {
                            onComplete()
                        }
                    }
                }

                override fun onError(error: String) {
                    Logger.e(TAG, "播放${if (isFirstTime) "第一遍" else "第二遍"}失败: $error")
                    activity.runOnUiThread {
                        onError(error)
                    }
                }
            },
        )
    }

    /**
     * 更新ASR UI
     */
    private fun updateAsrUI(
        text: String?,
        isPartial: Boolean,
    ) {
        // 检查视图是否已初始化
        if (tvRecognitionText == null || tvRecognitionStatus == null) {
            Logger.w(TAG, "ASR视图未初始化，跳过UI更新")
            return
        }

        if (!text.isNullOrBlank()) {
            Logger.d(TAG, "ASR ${if (isPartial) "partial" else "final"}: \"$text\"")

            tvRecognitionText?.text = text
            if (isPartial) {
                tvRecognitionStatus?.text = "识别中"
                tvRecognitionStatus?.visibility = View.VISIBLE
            } else {
                tvRecognitionStatus?.text = "识别到"
                tvRecognitionStatus?.visibility = View.VISIBLE
            }

            // 取消之前的延迟任务
            hideAsrUiRunnable?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
            if (!isPartial) {
                // 使用Handler延迟隐藏ASR UI，避免Activity持有协程引用
                hideAsrUiRunnable =
                    Runnable {
                        if (canHideAsrUi()) {
                            tvRecognitionText?.text = ""
                            tvRecognitionStatus?.visibility = View.GONE
                        }
                    }
                Handler(Looper.getMainLooper()).postDelayed(hideAsrUiRunnable!!, AppConfig.ASR_FINAL_DISPLAY_MILLIS)
            }
        } else {
            if (!isPartial) {
                // 识别结果为空
                Logger.d(TAG, "识别结果为空")
            }
        }
    }

    /**
     * 隐藏ASR UI
     */
    private fun hideAsrUI() {
        tvRecognitionText?.text = ""
        tvRecognitionStatus?.visibility = View.GONE
    }

    /**
     * 清理资源
     */
    fun cleanup() {
        hideAsrUiRunnable?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
        hideAsrUiRunnable = null
        stopListening()
    }

    /**
     * 检查是否可以隐藏ASR UI
     */
    private fun canHideAsrUi(): Boolean {
        if (activity.isFinishing || activity.isDestroyed) return false
        if (tvRecognitionText == null || tvRecognitionStatus == null) return false
        return true
    }
}
