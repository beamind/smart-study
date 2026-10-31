package com.smartstudy.di

import android.content.Context
import com.smartstudy.dictation.core.MockImageUnderstandingService
import com.smartstudy.dictation.core.MockTtsService
import com.smartstudy.dictation.core.MockVoiceRecognitionService
import com.smartstudy.dictation.core.VoiceRecognitionService
import com.smartstudy.dictation.core.interfaces.IImageUnderstandingService
import com.smartstudy.dictation.core.interfaces.ITtsService
import org.koin.dsl.module

/**
 * 测试专用的Koin模块
 * 用于替换真实服务为Mock服务，便于E2E测试
 */
val testAppModule =
    module {
        // ==================== Mock服务（替换真实服务）====================

        /**
         * Mock TTS服务：单例，模拟TTS播放
         */
        single<ITtsService> { MockTtsService() }

        /**
         * Mock图片理解服务：单例，返回预定义的测试词语
         */
        single<IImageUnderstandingService> { MockImageUnderstandingService() }

        /**
         * Mock语音识别服务：工厂模式，支持手动触发关键词检测
         */
        factory { (context: Context) -> MockVoiceRecognitionService(context) as VoiceRecognitionService }

        // ==================== 其他服务保持原样（使用真实实现）====================
        // 注意：如果需要Mock其他服务，可以在这里添加
    }
