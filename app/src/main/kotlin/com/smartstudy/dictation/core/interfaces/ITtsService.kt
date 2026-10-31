package com.smartstudy.dictation.core.interfaces

/**
 * TTS服务接口
 *
 * 定义TTS服务的基本功能，便于测试和扩展。
 */
interface ITtsService {
    /**
     * TTS回调接口
     */
    interface TtsCallback {
        /**
         * 播放开始回调
         */
        fun onStart()

        /**
         * 播放完成回调
         */
        fun onComplete()

        /**
         * 播放错误回调
         * @param error 错误信息
         */
        fun onError(error: String)
    }

    /**
     * 播放文本
     *
     * @param text 要播放的文本
     * @param callback 播放回调
     */
    fun speak(
        text: String,
        callback: TtsCallback?,
    )

    /**
     * 设置语速
     *
     * @param rate 语速（0.0-2.0，1.0为正常速度）
     */
    fun setSpeechRate(rate: Float)

    /**
     * 设置音调
     *
     * @param pitch 音调（0.0-2.0，1.0为正常音调）
     */
    fun setPitch(pitch: Float)

    /**
     * 停止播放
     */
    fun stop()

    /**
     * 释放资源
     */
    fun release()
}
