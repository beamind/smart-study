package com.smartstudy.dictation.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.smartstudy.core.Logger

/**
 * Mock语音识别服务
 * 用于E2E测试，支持手动触发关键词检测
 *
 * 功能特性：
 * - 支持手动触发关键词检测（"开始听写"、"下一个"等）
 * - 支持模拟识别结果回调
 * - 完全可控的测试环境
 */
class MockVoiceRecognitionService(context: Context) : VoiceRecognitionService(context) {
    companion object {
        private const val TAG = "MockVoiceRecognitionService"
    }

    private var currentCallback: VoiceRecognitionCallback? = null
    private var isListening = false
    private var isForStart = false
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun startListening(
        callback: VoiceRecognitionCallback,
        forStart: Boolean,
    ) {
        this.currentCallback = callback
        this.isListening = true
        this.isForStart = forStart

        Logger.d(TAG, "开始监听（forStart=$forStart）")
    }

    override fun stopListening() {
        Logger.d(TAG, "停止监听")
        isListening = false
        currentCallback = null
    }

    override fun destroy() {
        stopListening()
    }

    /**
     * 手动触发关键词检测（用于测试）
     * @param keyword 要触发的关键词（如"开始听写"、"下一个"）
     */
    fun triggerKeyword(keyword: String) {
        if (!isListening || currentCallback == null) {
            Logger.w(TAG, "未在监听状态，无法触发关键词: $keyword")
            return
        }

        Logger.d(TAG, "手动触发关键词: $keyword")

        // 在主线程上触发回调
        mainHandler.post {
            currentCallback?.onKeywordDetected(keyword)
        }
    }

    /**
     * 触发"开始听写"关键词
     */
    fun triggerStartKeyword() {
        triggerKeyword("开始听写")
    }

    /**
     * 触发"下一个"关键词
     */
    fun triggerNextKeyword() {
        triggerKeyword("下一个")
    }

    /**
     * 模拟识别结果
     * @param text 识别文本
     * @param isPartial 是否为部分结果
     */
    fun triggerRecognitionResult(
        text: String?,
        isPartial: Boolean = false,
    ) {
        if (!isListening || currentCallback == null) {
            Logger.w(TAG, "未在监听状态，无法触发识别结果")
            return
        }

        Logger.d(TAG, "模拟识别结果: $text (isPartial=$isPartial)")

        mainHandler.post {
            currentCallback?.onRecognitionResult(text, isPartial)
        }
    }

    /**
     * 模拟错误
     * @param error 错误信息
     */
    fun triggerError(error: String) {
        if (!isListening || currentCallback == null) {
            Logger.w(TAG, "未在监听状态，无法触发错误")
            return
        }

        Logger.d(TAG, "模拟错误: $error")

        mainHandler.post {
            currentCallback?.onError(error)
        }
    }

    /**
     * 检查是否正在监听
     */
    fun isCurrentlyListening(): Boolean = isListening

    /**
     * 检查是否在监听"开始"关键词
     */
    fun isListeningForStart(): Boolean = isForStart
}
