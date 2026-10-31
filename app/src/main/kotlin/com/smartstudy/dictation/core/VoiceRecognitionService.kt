package com.smartstudy.dictation.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.smartstudy.core.Logger

/**
 * 语音识别服务
 * 使用本地 Sherpa-onnx KWS 模型进行关键词检测（完全离线运行）
 * 监听语音并识别关键词（"下一个"、"写好了"、"开始"、"开始听写"）
 *
 * 功能特性：
 * - 完全离线运行，无需网络连接
 * - 支持两种监听模式：开始关键词和听写关键词
 * - 自动处理权限检查
 * - 线程安全，支持并发调用
 *
 * 使用示例：
 * ```
 * val service = VoiceRecognitionService(context)
 * service.startListening(object : VoiceRecognitionCallback {
 *     override fun onKeywordDetected(keyword: String) { }
 *     override fun onError(error: String) { }
 * })
 * ```
 *
 * @param context Android Context，用于访问模型文件和权限检查
 */
open class VoiceRecognitionService(private val context: Context) {
    companion object {
        private const val TAG = "VoiceRecognitionService"
    }

    private var callback: VoiceRecognitionCallback? = null
    private var isListening = false
    private val kwsService = SherpaKwsService(context)
    private var isInitialized = false

    /**
     * 开始监听语音
     * @param callback 回调接口
     * @param forStart 是否监听"开始"关键词（true）还是听写关键词（false）
     */
    open fun startListening(
        callback: VoiceRecognitionCallback,
        forStart: Boolean = false,
    ) {
        this.callback = callback
        this.isListening = true

        // 检查权限
        val hasPermission =
            context.checkSelfPermission(
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            val errorMsg = "缺少录音权限，请授予麦克风权限"
            Logger.e(TAG, errorMsg)
            callback.onError(errorMsg)
            return
        }

        // 初始化 KWS 模型（如果还没初始化）
        if (!isInitialized) {
            val initialized = kwsService.initialize()
            if (!initialized) {
                callback.onError("KWS 模型初始化失败，请检查模型文件是否存在")
                return
            }
            isInitialized = true
        }

        // 确定监听模式
        val mode =
            if (forStart) {
                SherpaKwsService.ListeningMode.START
            } else {
                SherpaKwsService.ListeningMode.DICTATION
            }

        // 开始监听
        kwsService.startListening(
            object : SherpaKwsService.KeywordCallback {
                override fun onKeywordDetected(keyword: String) {
                    if (!isListening) {
                        return
                    }

                    // 调用带关键词的回调
                    callback?.onKeywordDetected(keyword)
                }

                override fun onError(error: String) {
                    Logger.e(TAG, "KWS 错误: $error")
                    callback?.onError(error)
                }
            },
            mode,
        )
    }

    /**
     * 停止监听
     */
    open fun stopListening() {
        isListening = false
        kwsService.stopListening()
    }

    /**
     * 释放资源
     */
    open fun destroy() {
        stopListening()
        kwsService.release()
        isInitialized = false
        callback = null
    }

    /**
     * 语音识别回调接口
     */
    interface VoiceRecognitionCallback {
        /**
         * 检测到关键词回调
         * @param keyword 检测到的关键词文本
         */
        fun onKeywordDetected(keyword: String)

        fun onError(error: String)

        /**
         * 识别结果回调（包括部分结果和最终结果）
         * @param text 识别的文本，null表示未识别到内容
         * @param isPartial 是否为部分结果（对于 ASR API，通常为 false）
         */
        fun onRecognitionResult(
            text: String?,
            isPartial: Boolean,
        ) {
            // 默认实现为空，子类可以选择性实现
        }
    }
}
