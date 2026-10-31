package com.smartstudy.dictation.core

import android.os.Handler
import android.os.Looper
import com.smartstudy.dictation.core.interfaces.ITtsService
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Mock TTS服务
 * 用于E2E测试，模拟TTS播放行为
 *
 * 功能特性：
 * - 立即完成或延迟完成回调
 * - 记录播放历史，便于验证
 * - 支持配置播放延迟时间
 */
class MockTtsService : ITtsService {
    companion object {
        private const val TAG = "MockTtsService"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRate: Float = 1.0f
    private var pitch: Float = 1.0f

    // 播放历史记录
    private val playHistory = CopyOnWriteArrayList<String>()

    // 播放延迟时间（毫秒），用于模拟真实TTS播放
    var playDelayMs: Long = 50L
        set(value) {
            field = value.coerceAtLeast(0)
        }

    /**
     * 获取播放历史
     */
    fun getPlayHistory(): List<String> = playHistory.toList()

    /**
     * 清空播放历史
     */
    fun clearHistory() {
        playHistory.clear()
    }

    override fun speak(
        text: String,
        callback: ITtsService.TtsCallback?,
    ) {
        // 记录播放历史
        playHistory.add(text)

        // 在主线程上执行回调
        mainHandler.post {
            callback?.onStart()
        }

        // 延迟后完成（模拟真实TTS播放）
        mainHandler.postDelayed({
            callback?.onComplete()
        }, playDelayMs)
    }

    override fun setSpeechRate(rate: Float) {
        this.speechRate = rate.coerceIn(0.0f, 2.0f)
    }

    override fun setPitch(pitch: Float) {
        this.pitch = pitch.coerceIn(0.0f, 2.0f)
    }

    override fun stop() {
        // Mock服务停止播放：清空待处理的回调
        mainHandler.removeCallbacksAndMessages(null)
    }

    override fun release() {
        // Mock服务无需释放资源
        clearHistory()
    }
}
