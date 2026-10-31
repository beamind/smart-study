package com.smartstudy.dictation.core.handlers

import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.smartstudy.R
import com.smartstudy.core.Logger
import com.smartstudy.dictation.core.DictationRecordStorage
import com.smartstudy.dictation.core.ImageUnderstandingService
import com.smartstudy.dictation.core.VoiceRecognitionService
import com.smartstudy.dictation.core.interfaces.ITtsService
import com.smartstudy.dictation.ui.DictationViewModel
import com.smartstudy.user.usecases.UserUseCase
import com.smartstudy.utils.ToastHelper

/**
 * 听写组件初始化器配置
 */
data class DictationComponentInitializerConfig(
    val imageUnderstandingService: ImageUnderstandingService,
    val ttsService: ITtsService,
    val voiceRecognitionService: VoiceRecognitionService,
    val errorWordStorage: DictationRecordStorage,
    val userUseCase: UserUseCase,
    val getDictationType: () -> String,
    val onImageUnderstandingSuccess: (List<String>) -> Unit,
    val onImageRecognitionError: (com.smartstudy.core.AppError) -> Unit,
    val onGoToNextWord: () -> Unit,
    val onStartDictation: () -> Unit,
    val onStopDictation: () -> Unit,
    val onShowErrorDictationDialog: () -> Unit,
    val onSubmitErrorWords: () -> Unit,
    val onFinish: () -> Unit,
)

/**
 * 听写组件初始化器
 *
 * 负责统一管理所有组件的初始化流程，将初始化逻辑从 Activity 中分离出来。
 *
 * 功能特性：
 * - 统一的组件初始化流程
 * - 按正确顺序初始化组件（权限 -> 视图 -> 用户 -> 语音 -> 图片）
 * - 支持生命周期感知的超时管理
 * - 统一的错误处理
 *
 * 使用示例：
 * ```
 * val config = DictationComponentInitializerConfig(
 *     imageUnderstandingService = imageUnderstandingService,
 *     ttsService = ttsService,
 *     voiceRecognitionService = voiceRecognitionService,
 *     errorWordStorage = errorWordStorage,
 *     userUseCase = userUseCase,
 *     getDictationType = { getDictationType() },
 *     onImageUnderstandingSuccess = { words -> handleWords(words) },
 *     onImageRecognitionError = { error -> handleError(error) },
 *     onGoToNextWord = { goToNextWord() },
 *     onStartDictation = { startDictation() },
 *     onStopDictation = { stopDictation() },
 *     onShowErrorDictationDialog = { showErrorDictationDialog() },
 *     onSubmitErrorWords = { submitErrorWords() },
 *     onFinish = { finish() }
 * )
 * val initializer = DictationComponentInitializer(
 *     activity = this,
 *     viewModel = viewModel,
 *     config = config
 * )
 * val components = initializer.initialize()
 * ```
 *
 * @param activity Activity 实例
 * @param viewModel ViewModel 实例
 * @param config 初始化器配置
 */
class DictationComponentInitializer(
    private val activity: AppCompatActivity,
    private val viewModel: DictationViewModel,
    private val config: DictationComponentInitializerConfig,
) {
    companion object {
        private const val TAG = "DictationComponentInitializer"
    }

    // 用于延迟访问 imageHandler
    private var imageHandlerRef: DictationImageManager? = null

    /**
     * 初始化后的组件集合
     */
    data class InitializedComponents(
        val viewManager: DictationViewManager,
        val imageHandler: DictationImageManager,
        val voiceHandler: DictationVoiceManager,
        val permissionManager: DictationPermissionManager,
        val userManagerHandler: DictationUserManager,
    )

    /**
     * 初始化所有组件
     * @return 初始化后的组件集合
     */
    fun initialize(): InitializedComponents {
        // 1. 初始化权限管理器
        val permissionManager = initPermissionManager()

        // 2. 初始化视图管理器
        val viewManager = initViewManager()

        // 3. 初始化用户管理器
        val userManagerHandler = initUserManager(viewManager, permissionManager)

        // 4. 初始化语音处理器
        val voiceHandler = initVoiceHandler(viewManager)

        // 5. 初始化图片处理器
        val imageHandler = initImageHandler(viewManager)

        // 6. 初始化用户
        initUser(userManagerHandler)

        // 7. 初始化屏幕常亮（不再需要整个流程的超时管理，改为单题超时）
        initKeepScreenOn()

        // 权限已在应用启动时统一请求，此处不再请求

        return InitializedComponents(
            viewManager = viewManager,
            imageHandler = imageHandler,
            voiceHandler = voiceHandler,
            permissionManager = permissionManager,
            userManagerHandler = userManagerHandler,
        )
    }

    /**
     * 初始化权限管理器
     */
    private fun initPermissionManager(): DictationPermissionManager {
        return DictationPermissionManager(
            activity = activity,
            userUseCase = config.userUseCase,
            onStoragePermissionGranted = {
                // 权限授予后，重新初始化用户
                // 注意：这里需要访问 userManagerHandler，但此时可能还未初始化
                // 所以这个回调会在权限授予后通过其他方式处理
                Logger.d(TAG, "存储权限已授予")
            },
            onNeedReselectStorage = {
                // 需要重新选择存储位置
                Logger.d(TAG, "需要重新选择存储位置")
            },
        )
    }

    /**
     * 初始化视图管理器
     */
    private fun initViewManager(): DictationViewManager {
        val viewManager =
            DictationViewManager(
                activity = activity,
                onNewDictationClick = {
                    // 新的听写按钮点击，显示图片来源选择对话框
                    imageHandlerRef?.showImageSourceDialog() ?: run {
                        Logger.e(TAG, "imageHandler未初始化，无法显示图片来源选择对话框")
                        ToastHelper.showShort(activity, R.string.system_init_not_complete)
                    }
                },
                onViewErrorsClick = {
                    // 查看错题
                    val currentUserId = viewModel.uiState.value.currentUserId
                    // 这里需要访问 viewManager，但此时正在创建，所以使用延迟初始化
                },
                onErrorDictationClick = {
                    config.onShowErrorDictationDialog()
                },
                onStartDictationClick = {
                    if (viewModel.uiState.value.isWaitingForStart) {
                        Logger.d(TAG, "点击开始听写按钮，直接开始听写")
                        viewModel.setWaitingForStart(false)
                        // voiceHandler 会在后续初始化，这里需要延迟处理
                        config.onStartDictation()
                    }
                },
                onUserClick = {
                    // 用户点击，需要延迟处理
                },
                onSubmitClick = {
                    if (viewModel.uiState.value.isSubmitted) {
                        config.onFinish()
                    } else {
                        config.onSubmitErrorWords()
                    }
                },
                onNextClick = {
                    config.onGoToNextWord()
                },
            )
        // 立即初始化视图，确保 tvCurrentUser 等视图已初始化
        viewManager.initViews()
        return viewManager
    }

    /**
     * 初始化用户管理器
     */
    private fun initUserManager(
        viewManager: DictationViewManager,
        permissionManager: DictationPermissionManager,
    ): DictationUserManager {
        return DictationUserManager(
            activity = activity,
            userUseCase = config.userUseCase,
            tvCurrentUser = viewManager.tvCurrentUser,
            permissionManager = permissionManager,
            onUserChanged = { userId ->
                viewModel.updateCurrentUser()
            },
        )
    }

    /**
     * 初始化语音处理器
     */
    private fun initVoiceHandler(viewManager: DictationViewManager): DictationVoiceManager {
        val voiceHandler =
            DictationVoiceManager(
                activity = activity,
                ttsService = config.ttsService,
                voiceRecognitionService = config.voiceRecognitionService,
                onKeywordDetected = {
                    config.onGoToNextWord()
                },
                onStartKeywordDetected = {
                    viewModel.setWaitingForStart(false)
                    // voiceHandler 正在创建，需要延迟处理
                    config.onStartDictation()
                },
                isWaitingForStart = { viewModel.uiState.value.isWaitingForStart },
                isInForeground = { viewModel.uiState.value.isInForeground },
            )
        voiceHandler.tvRecognitionText = viewManager.tvRecognitionText
        voiceHandler.tvRecognitionStatus = viewManager.tvRecognitionStatus
        return voiceHandler
    }

    /**
     * 初始化图片处理器
     */
    private fun initImageHandler(viewManager: DictationViewManager): DictationImageManager {
        val imageHandler =
            DictationImageManager(
                activity = activity,
                viewModel = viewModel,
                onImageRecognized = { words ->
                    config.onImageUnderstandingSuccess(words)
                },
                onImageRecognitionError = { error ->
                    config.onImageRecognitionError(error)
                },
            )

        // 设置管理器的视图引用
        with(imageHandler) {
            ivPhoto = viewManager.ivPhoto
            btnTakePhoto = viewManager.btnTakePhoto
            cropImageView = viewManager.cropImageView
            cropContainer = viewManager.cropContainer
            btnCropDone = viewManager.btnCropDone
            btnCropCancel = viewManager.btnCropCancel
            cropButtonLayout = viewManager.cropButtonLayout
            cardPhoto = viewManager.cardPhoto
            placeholderLayout = viewManager.placeholderLayout
            btnViewErrors = viewManager.btnViewErrors
            btnErrorDictation = viewManager.btnErrorDictation
            cardProgress = viewManager.cardProgress
            cardAnswers = viewManager.cardAnswers
        }

        // 设置听写类型和初始化裁剪视图
        imageHandler.setDictationType(config.getDictationType())
        imageHandler.initCropViews()

        // 设置引用，供 onNewDictationClick 使用
        imageHandlerRef = imageHandler

        return imageHandler
    }

    /**
     * 初始化用户
     */
    private fun initUser(userManagerHandler: DictationUserManager) {
        userManagerHandler.initializeUser { userId ->
            viewModel.updateCurrentUser()
        }
    }

    /**
     * 初始化屏幕常亮（不再需要整个流程的超时管理，改为单题超时）
     */
    private fun initKeepScreenOn() {
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Logger.d(TAG, "屏幕常亮设置已初始化（单题超时由 BaseDictationActivity 管理）")
    }
}
