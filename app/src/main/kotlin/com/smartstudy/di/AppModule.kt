package com.smartstudy.di

import android.content.Context
import com.smartstudy.dictation.core.DictationRecordStorage
import com.smartstudy.dictation.core.ImageUnderstandingService
import com.smartstudy.dictation.core.TranslationService
import com.smartstudy.dictation.core.TtsService
import com.smartstudy.dictation.core.VoiceRecognitionService
import com.smartstudy.dictation.core.interfaces.ITtsService
import com.smartstudy.dictation.repository.DictationRepository
import com.smartstudy.english.EnglishErrorBookStorage
import com.smartstudy.english.ErrorBookViewModel
import com.smartstudy.english.QuestionGenerationService
import com.smartstudy.english.repository.ErrorBookRepository
import com.smartstudy.english.usecases.ErrorBookUseCase
import com.smartstudy.learningrecord.repository.LearningRecordRepository
import com.smartstudy.user.repository.UserRepository
import com.smartstudy.user.usecases.UserUseCase
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module

/**
 * Koin 依赖注入模块配置
 * 统一管理应用的服务依赖
 *
 * 设计说明：
 * - 单例服务（single）：无状态或可共享状态的服务，在整个应用生命周期内共享
 * - 工厂模式（factory）：需要Context参数或每个使用场景需要独立实例的服务
 *
 * 使用示例：
 * ```
 * // 在Activity/Fragment中使用
 * private val ttsService: ITtsService by inject()
 * private val userUseCase: UserUseCase by inject { parametersOf(this) }
 * ```
 */
val appModule =
    module {
        // ==================== 单例服务（应用生命周期内共享）====================

        /**
         * TTS服务：单例，因为TextToSpeech引擎可以在多个Activity间共享
         * 使用接口类型注册，便于测试时替换为Mock服务
         */
        single<ITtsService> { TtsService(androidContext()) }

        /**
         * 图片理解服务：单例，无状态服务，可以共享
         */
        single { ImageUnderstandingService() }

        /**
         * 题目生成服务：单例，无状态服务，可以共享
         */
        single { QuestionGenerationService() }

        /**
         * 翻译服务：单例，无状态服务，可以共享
         */
        single { TranslationService() }

        // ==================== 工厂模式（每次获取新实例）====================

        /**
         * 语音识别服务：工厂模式，因为每个Activity需要独立的监听状态
         * 需要Activity Context来访问模型文件和权限检查
         */
        factory { (context: Context) -> VoiceRecognitionService(context) }

        /**
         * 用户用例：工厂模式，需要Activity Context来访问存储
         */
        factory { (context: Context) ->
            UserUseCase(
                userRepository = get { parametersOf(context) },
            )
        }

        /**
         * 英语错题本存储：工厂模式，需要Activity Context来访问存储
         */
        factory { (context: Context) -> EnglishErrorBookStorage(context) }

        /**
         * 听写记录存储：工厂模式，需要Activity Context来访问存储
         */
        factory { (context: Context) -> DictationRecordStorage(context) }

        /**
         * 听写Repository：工厂模式，需要Activity Context来访问存储
         */
        factory { (context: Context) ->
            DictationRepository(
                context = context,
                imageUnderstandingService = get(),
            )
        }

        /**
         * 英语错题本Repository：工厂模式，需要Activity Context来访问存储
         */
        factory { (context: Context) -> ErrorBookRepository(context) }

        /**
         * 英语错题本UseCase：工厂模式，需要Repository和UserUseCase
         */
        factory { (context: Context) ->
            ErrorBookUseCase(
                errorBookRepository = get { parametersOf(context) },
                userUseCase = get { parametersOf(context) },
            )
        }

        /**
         * 用户Repository：工厂模式，需要Activity Context来访问存储
         */
        factory { (context: Context) -> UserRepository(context) }

        /**
         * 听写ViewModel：使用viewModel scope，支持生命周期感知
         * 每个Activity实例会有独立的ViewModel，并在Activity销毁时自动清理
         * 注意：ViewModel需要Context相关的服务，通过parameters传递
         */
        viewModel { (context: Context) ->
            com.smartstudy.dictation.ui.DictationViewModel(
                context = context.applicationContext,
                ttsService = get<ITtsService>(),
                imageUnderstandingService = get(),
                voiceRecognitionService = get { parametersOf(context) },
                errorWordStorage = get { parametersOf(context) },
                userUseCase = get { parametersOf(context) },
                dictationRepository = get { parametersOf(context) },
                translationService = get(),
            )
        }

        /**
         * 英语错题本ViewModel：使用viewModel scope，支持生命周期感知
         * 每个Activity实例会有独立的ViewModel，并在Activity销毁时自动清理
         * 注意：ViewModel需要Context相关的服务，通过parameters传递
         */
        viewModel { (context: Context) ->
            ErrorBookViewModel(
                errorBookUseCase = get { parametersOf(context) },
                imageUnderstandingService = get(),
                questionGenerationService = get(),
            )
        }

        /**
         * 错题列表ViewModel：使用viewModel scope，支持生命周期感知
         * 每个Activity实例会有独立的ViewModel，并在Activity销毁时自动清理
         * 注意：ViewModel需要Context相关的服务，通过parameters传递
         */
        viewModel { (context: Context) ->
            com.smartstudy.dictation.ErrorWordListViewModel(
                dictationRepository = get { parametersOf(context) },
                userUseCase = get { parametersOf(context) },
            )
        }

        /**
         * MainActivity ViewModel：使用viewModel scope，支持生命周期感知
         * 管理存储权限状态和Activity启动逻辑
         */
        viewModel { (context: Context) ->
            com.smartstudy.MainViewModel(context)
        }

        /**
         * 用户管理ViewModel：使用viewModel scope，支持生命周期感知
         * 管理用户列表状态和用户操作
         */
        viewModel { (context: Context) ->
            com.smartstudy.user.UserManagementViewModel(
                context = context.applicationContext,
                userUseCase = get { parametersOf(context) },
            )
        }

        /**
         * 听写记录ViewModel：使用viewModel scope，支持生命周期感知
         * 每个Activity实例会有独立的ViewModel，并在Activity销毁时自动清理
         * 注意：ViewModel需要Context相关的服务，通过parameters传递
         */
        viewModel { (context: Context) ->
            com.smartstudy.dictation.DictationRecordViewModel(
                context = context.applicationContext,
            )
        }

        /**
         * 学习记录Repository：工厂模式，需要Activity Context来访问存储
         */
        factory { (context: Context) ->
            LearningRecordRepository(
                context = context,
                dictationRepository = get { parametersOf(context) },
                errorBookRepository = get { parametersOf(context) },
            )
        }

        /**
         * 学习记录ViewModel：使用viewModel scope，支持生命周期感知
         * 每个Activity实例会有独立的ViewModel，并在Activity销毁时自动清理
         * 注意：ViewModel需要Context相关的服务，通过parameters传递
         */
        viewModel { (context: Context) ->
            com.smartstudy.learningrecord.LearningRecordViewModel(
                context = context.applicationContext,
            )
        }
    }
