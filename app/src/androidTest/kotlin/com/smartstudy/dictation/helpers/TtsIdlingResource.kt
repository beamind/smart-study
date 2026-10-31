package com.smartstudy.dictation.helpers

import androidx.test.espresso.IdlingResource
import com.smartstudy.dictation.core.MockTtsService
import java.util.concurrent.atomic.AtomicInteger

/**
 * TTS播放的IdlingResource
 * 用于等待TTS播放完成
 */
class TtsIdlingResource(
    private val mockTtsService: MockTtsService,
) : IdlingResource {
    private var resourceCallback: IdlingResource.ResourceCallback? = null
    private val pendingOperations = AtomicInteger(0)

    /**
     * 开始一个TTS操作
     */
    fun startOperation() {
        pendingOperations.incrementAndGet()
        updateIdleState()
    }

    /**
     * 完成一个TTS操作
     */
    fun finishOperation() {
        pendingOperations.decrementAndGet()
        updateIdleState()
    }

    private fun updateIdleState() {
        if (isIdleNow && resourceCallback != null) {
            resourceCallback?.onTransitionToIdle()
        }
    }

    override fun getName(): String = "TtsIdlingResource"

    override fun isIdleNow(): Boolean {
        return pendingOperations.get() == 0
    }

    override fun registerIdleTransitionCallback(callback: IdlingResource.ResourceCallback?) {
        this.resourceCallback = callback
    }
}

/**
 * 图片理解的IdlingResource
 * 用于等待图片理解完成
 */
class ImageUnderstandingIdlingResource : IdlingResource {
    private var resourceCallback: IdlingResource.ResourceCallback? = null
    private val isIdle = AtomicInteger(1) // 初始为idle状态

    /**
     * 开始图片理解操作
     */
    fun startOperation() {
        isIdle.set(0)
        updateIdleState()
    }

    /**
     * 完成图片理解操作
     */
    fun finishOperation() {
        isIdle.set(1)
        updateIdleState()
    }

    private fun updateIdleState() {
        if (isIdleNow && resourceCallback != null) {
            resourceCallback?.onTransitionToIdle()
        }
    }

    override fun getName(): String = "ImageUnderstandingIdlingResource"

    override fun isIdleNow(): Boolean {
        return isIdle.get() == 1
    }

    override fun registerIdleTransitionCallback(callback: IdlingResource.ResourceCallback?) {
        this.resourceCallback = callback
    }
}

/**
 * 语音识别的IdlingResource
 * 用于等待语音识别操作完成
 */
class VoiceRecognitionIdlingResource : IdlingResource {
    private var resourceCallback: IdlingResource.ResourceCallback? = null
    private val isIdle = AtomicInteger(1) // 初始为idle状态

    /**
     * 开始语音识别操作
     */
    fun startOperation() {
        isIdle.set(0)
        updateIdleState()
    }

    /**
     * 完成语音识别操作
     */
    fun finishOperation() {
        isIdle.set(1)
        updateIdleState()
    }

    private fun updateIdleState() {
        if (isIdleNow && resourceCallback != null) {
            resourceCallback?.onTransitionToIdle()
        }
    }

    override fun getName(): String = "VoiceRecognitionIdlingResource"

    override fun isIdleNow(): Boolean {
        return isIdle.get() == 1
    }

    override fun registerIdleTransitionCallback(callback: IdlingResource.ResourceCallback?) {
        this.resourceCallback = callback
    }
}
