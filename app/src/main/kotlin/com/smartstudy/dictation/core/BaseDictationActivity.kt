package com.smartstudy.dictation.core

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import com.smartstudy.R
import com.smartstudy.core.AppConfig
import com.smartstudy.core.Logger
import com.smartstudy.core.ResourceManager
import com.smartstudy.core.ResourceManagerHelper
import com.smartstudy.dictation.core.handlers.DictationErrorManager
import com.smartstudy.dictation.core.handlers.DictationImageManager
import com.smartstudy.dictation.core.handlers.DictationPermissionManager
import com.smartstudy.dictation.core.handlers.DictationStateObserver
import com.smartstudy.dictation.core.handlers.DictationUserManager
import com.smartstudy.dictation.core.handlers.DictationViewManager
import com.smartstudy.dictation.core.handlers.DictationVoiceManager
import com.smartstudy.dictation.core.interfaces.ITtsService
import com.smartstudy.dictation.ui.DictationViewModel
import com.smartstudy.storage.AppStorageManager
import com.smartstudy.user.usecases.UserUseCase
import com.smartstudy.utils.ToastHelper
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.core.parameter.parametersOf
import java.io.IOException

/**
 * 听写Activity基类
 *
 * 抽象中文和英文听写Activity的公共逻辑，提供统一的听写功能实现。
 *
 * 功能特性：
 * - 统一的图片识别流程
 * - 统一的语音识别和 TTS 播放
 * - 统一的错词管理
 * - 统一的权限处理
 * - 生命周期感知的资源管理
 * - 使用 ViewModel 管理状态，支持配置变更时状态保持
 *
 * 状态管理：
 * - 使用 [DictationViewModel] 管理所有听写相关状态
 * - 状态通过 StateFlow 提供响应式更新
 * - 支持配置变更时自动保持状态
 * - 通过 [DictationStateObserver] 统一观察状态变化并更新 UI
 * - UI 更新逻辑集中在 [DictationViewManager] 中，通过状态观察器自动调用
 * - 在业务逻辑中需要实时状态时，使用属性访问器（如 currentWordIndex）
 * - 避免在 UI 更新逻辑中直接读取状态，应该通过状态观察器自动处理
 *
 * 子类需要实现以下抽象方法：
 * - [getLayoutResId]: 返回布局资源ID
 * - [getDictationType]: 返回听写类型（"chinese" 或 "english"）
 * - [getTtsIntervalMillis]: 返回TTS播放间隔（毫秒）
 * - [initServices]: 初始化服务（子类可以添加特定服务）
 * - [onImageUnderstandingSuccess]: 处理图片理解成功的结果
 * - [startDictation]: 开始听写
 * - [showAnswers]: 显示答案
 * - [submitErrorWords]: 提交错题
 * - [createAnswerAdapter]: 创建答案适配器
 * - [handleManualInput]: 处理手动输入
 * - [showErrorDictationDialog]: 显示错题听写对话框
 *
 * 实现ResourceManager接口，确保资源正确释放，防止内存泄漏。
 *
 * @see ResourceManager
 * @see com.smartstudy.dictation.chinese.ChineseDictationActivity
 * @see com.smartstudy.dictation.english.EnglishDictationActivity
 */
abstract class BaseDictationActivity : AppCompatActivity(), ResourceManager {
    companion object {
        private const val TAG = "BaseDictationActivity"
    }

    // 视图组件（通过 ViewManager 访问）
    protected lateinit var viewManager: DictationViewManager

    // 服务（使用依赖注入）
    protected val imageUnderstandingService: ImageUnderstandingService by inject()
    protected val ttsService: ITtsService by inject()
    protected val voiceRecognitionService: VoiceRecognitionService by inject { parametersOf(this) }
    protected val errorWordStorage: DictationRecordStorage by inject { parametersOf(this) }
    protected val userUseCase: UserUseCase by inject { parametersOf(this) }

    // ViewModel - 管理状态
    protected val viewModel: DictationViewModel by viewModel { parametersOf(this) }

    // Manager
    protected lateinit var imageHandler: DictationImageManager
    protected lateinit var voiceHandler: DictationVoiceManager
    protected lateinit var permissionManager: DictationPermissionManager
    protected lateinit var userManagerHandler: DictationUserManager

    // 状态观察器和错误处理器
    private lateinit var stateObserver: DictationStateObserver
    private lateinit var errorHandler: DictationErrorManager

    // 单题超时管理
    private val questionTimeoutHandler = Handler(Looper.getMainLooper())
    private var questionTimeoutRunnable: Runnable? = null

    // 视图访问器（委托给 viewManager）
    protected val tvStatus: TextView get() = viewManager.tvStatus
    protected val tvCurrentUser: TextView get() = viewManager.tvCurrentUser
    protected val rvAnswers: RecyclerView get() = viewManager.rvAnswers

    /**
     * 状态访问器（委托给 ViewModel）
     *
     * 这些属性访问器提供状态访问方式，确保状态更新通过 ViewModel。
     *
     * 最佳实践：
     * 1. **UI 更新**：应该通过 [DictationStateObserver] 观察状态变化自动更新，而不是手动读取状态
     * 2. **业务逻辑判断**：在回调函数或生命周期方法中需要实时状态时，使用这些属性访问器
     * 3. **状态修改**：通过 setter 方法更新状态，确保状态变更通过 ViewModel
     * 4. **性能考虑**：这些访问器每次访问都会读取最新状态，对于频繁访问的场景，
     *    建议通过状态观察器缓存状态或使用 StateFlow 的 collect 方法
     *
     * 使用场景：
     * - ✅ 在回调函数中检查状态（如按钮点击、语音识别回调）
     * - ✅ 在生命周期方法中读取状态（如 onResume）
     * - ❌ 在 UI 更新逻辑中直接读取（应该通过状态观察器）
     * - ❌ 在循环或频繁调用的代码中（应该缓存状态值）
     */
    protected var currentUserId: String
        get() = viewModel.uiState.value.currentUserId
        set(value) {
            // currentUserId 通过 userManager 管理，设置时更新 ViewModel 状态
            // 注意：实际应该通过 userManagerHandler 来更新用户，这里只是同步状态
            viewModel.updateCurrentUser()
        }
    protected var currentWordIndex: Int
        get() = viewModel.uiState.value.currentWordIndex
        set(value) {
            viewModel.updateWordIndex(value)
        }
    protected var isSubmitted: Boolean
        get() = viewModel.uiState.value.isSubmitted
        set(value) {
            viewModel.setSubmitted(value)
        }
    protected var isWaitingForStart: Boolean
        get() = viewModel.uiState.value.isWaitingForStart
        set(value) {
            viewModel.setWaitingForStart(value)
        }
    protected var isDictationActive: Boolean
        get() = viewModel.uiState.value.isDictationActive
        set(value) {
            viewModel.setDictationActive(value)
        }
    protected var isInForeground: Boolean
        get() = viewModel.uiState.value.isInForeground
        set(value) {
            viewModel.setInForeground(value)
        }

    // 抽象方法：子类必须实现

    /**
     * 获取布局资源ID
     */
    protected abstract fun getLayoutResId(): Int

    /**
     * 获取听写类型（"chinese" 或 "english"）
     */
    protected abstract fun getDictationType(): String

    /**
     * 获取TTS播放间隔（毫秒）
     */
    protected abstract fun getTtsIntervalMillis(): Long

    /**
     * 初始化服务（子类可以添加特定服务，如TranslationService）
     */
    protected abstract fun initServices()

    /**
     * 处理图片理解成功的结果
     */
    protected abstract fun onImageUnderstandingSuccess(words: List<String>)

    /**
     * 开始听写（子类实现具体逻辑）
     */
    protected abstract fun startDictation()

    /**
     * 显示答案（子类实现具体逻辑）
     */
    protected abstract fun showAnswers()

    /**
     * 提交错题（子类实现具体逻辑）
     */
    protected abstract fun submitErrorWords()

    /**
     * 获取答案适配器
     */
    protected abstract fun createAnswerAdapter(): RecyclerView.Adapter<*>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            setContentView(getLayoutResId())
            initializeComponents()
        } catch (e: IOException) {
            // 错误处理器将在组件初始化后创建，这里先临时处理
            if (::errorHandler.isInitialized) {
                errorHandler.handleStorageException(e)
            } else {
                handleStorageException(e)
            }
        } catch (e: SecurityException) {
            Logger.e(TAG, "权限不足，无法初始化Activity", e)
            if (::errorHandler.isInitialized) {
                errorHandler.handleInitError(e)
            } else {
                handleInitError(e)
            }
        } catch (e: IllegalStateException) {
            Logger.e(TAG, "状态错误，无法初始化Activity", e)
            if (::errorHandler.isInitialized) {
                errorHandler.handleInitError(e)
            } else {
                handleInitError(e)
            }
        }
    }

    /**
     * 初始化所有组件
     * 将初始化逻辑拆分为多个小方法，提高可读性和可维护性
     */
    private fun initializeComponents() {
        initPermissionManager()
        initViewManager()
        initUserManager()
        initVoiceHandler()
        initUser()
        initStateObserver()
        initErrorHandler()
        initialize()
    }

    /**
     * 初始化状态观察器
     */
    private fun initStateObserver() {
        stateObserver =
            DictationStateObserver(
                activity = this,
                viewManager = viewManager,
                onImageUnderstandingSuccess = { words ->
                    onImageUnderstandingSuccess(words)
                },
                onImageRecognitionError = { error ->
                    handleImageRecognitionError(error)
                },
            )
        // 开始观察状态和事件
        startObservingState()
    }

    /**
     * 开始观察ViewModel的状态和事件
     */
    private fun startObservingState() {
        // 观察UI状态变化
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    stateObserver.updateUIFromState(state)
                }
            }
        }
        // 观察事件
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { event ->
                    stateObserver.handleEvent(event)
                }
            }
        }
    }

    /**
     * 初始化错误处理器
     */
    private fun initErrorHandler() {
        errorHandler =
            DictationErrorManager(
                activity = this,
                viewManager = viewManager,
                permissionManager = permissionManager,
                onFinish = {
                    finish()
                },
            )
    }

    /**
     * 初始化权限管理器
     */
    private fun initPermissionManager() {
        permissionManager =
            DictationPermissionManager(
                activity = this,
                userUseCase = userUseCase,
                onStoragePermissionGranted = {
                    // 权限授予后，重新初始化用户
                    if (::userManagerHandler.isInitialized) {
                        userManagerHandler.initializeUser { userId ->
                            viewModel.updateCurrentUser()
                            if (!::viewManager.isInitialized) {
                                initialize()
                            } else {
                                userManagerHandler.updateCurrentUserDisplay()
                            }
                        }
                    }
                },
                onNeedReselectStorage = {
                    permissionManager.appStorageLauncher.launch(null)
                },
            )
    }

    /**
     * 初始化视图管理器
     */
    private fun initViewManager() {
        viewManager =
            DictationViewManager(
                activity = this,
                onNewDictationClick = {
                    // 新的听写按钮点击，显示图片来源选择对话框
                    if (::imageHandler.isInitialized) {
                        imageHandler.showImageSourceDialog()
                    } else {
                        Logger.e(TAG, "imageHandler未初始化，无法显示图片来源选择对话框")
                        ToastHelper.showShort(this, R.string.system_init_not_complete)
                    }
                },
                onViewErrorsClick = {
                    // 使用属性访问器而不是直接访问 uiState.value
                    viewManager.showErrorWordList(getDictationType(), currentUserId)
                },
                onErrorDictationClick = {
                    showErrorDictationDialog()
                },
                onStartDictationClick = {
                    // 使用属性访问器而不是直接访问 uiState.value
                    if (isWaitingForStart) {
                        Logger.d(TAG, "点击开始听写按钮，直接开始听写")
                        isWaitingForStart = false
                        voiceHandler.stopListening(clearAsrUi = false)
                        viewManager.hideStartDictationViews()
                        startDictation()
                    }
                },
                onUserClick = {
                    userManagerHandler.openUserManagement()
                },
                onSubmitClick = {
                    // 使用属性访问器而不是直接访问 uiState.value
                    if (isSubmitted) {
                        finish()
                    } else {
                        submitErrorWords()
                    }
                },
                onNextClick = {
                    goToNextWord()
                },
            )
        // 立即初始化视图，确保 tvCurrentUser 等视图已初始化
        viewManager.initViews()

        // 设置 Toolbar（如果存在）
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        if (toolbar != null) {
            setSupportActionBar(toolbar)
            // 隐藏默认标题，使用自定义布局中的标题
            supportActionBar?.setDisplayShowTitleEnabled(false)
            // 设置自定义标题
            val titleTextView = findViewById<android.widget.TextView>(R.id.tvToolbarTitle)
            titleTextView?.text =
                when (getDictationType()) {
                    AppConfig.TYPE_CHINESE -> getString(R.string.dictation_module)
                    AppConfig.TYPE_ENGLISH -> getString(R.string.english_dictation_module)
                    else -> ""
                }
        }
    }

    /**
     * 初始化用户管理器
     */
    private fun initUserManager() {
        userManagerHandler =
            DictationUserManager(
                activity = this,
                userUseCase = userUseCase,
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
    private fun initVoiceHandler() {
        voiceHandler =
            DictationVoiceManager(
                activity = this,
                ttsService = ttsService,
                voiceRecognitionService = voiceRecognitionService,
                onKeywordDetected = {
                    goToNextWord()
                },
                onStartKeywordDetected = {
                    viewModel.setWaitingForStart(false)
                    voiceHandler.stopListening(clearAsrUi = false)
                    viewManager.hideStartDictationViews()
                    startDictation()
                },
                // 注意：这些 lambda 回调函数需要实时获取状态，使用属性访问器
                // 虽然会读取 uiState.value，但这是合理的，因为回调函数只在需要时被调用
                isWaitingForStart = { isWaitingForStart },
                isInForeground = { isInForeground },
            )
        voiceHandler.tvRecognitionText = viewManager.tvRecognitionText
        voiceHandler.tvRecognitionStatus = viewManager.tvRecognitionStatus
    }

    /**
     * 初始化用户
     */
    private fun initUser() {
        userManagerHandler.initializeUser { userId ->
            viewModel.updateCurrentUser()
        }
    }

    /**
     * 处理存储异常（实际使用 errorHandler）
     */
    private fun handleStorageException(e: IOException) {
        if (::errorHandler.isInitialized) {
            errorHandler.handleStorageException(e)
        } else {
            // 如果错误处理器未初始化，使用临时处理
            Logger.e(TAG, "无法创建用户文件", e)
            AppStorageManager.tryCreateSmartstudyFolderViaMediaStore(this)
            Handler(Looper.getMainLooper()).postDelayed({
                if (!isFinishing && !isDestroyed) {
                    finish()
                }
            }, AppConfig.INIT_ERROR_DISPLAY_MILLIS)
        }
    }

    /**
     * 处理初始化错误（实际使用 errorHandler）
     */
    private fun handleInitError(e: Exception) {
        if (::errorHandler.isInitialized) {
            errorHandler.handleInitError(e)
        } else {
            // 如果错误处理器未初始化，使用临时处理
            Logger.e(TAG, "Activity初始化失败", e)
            ToastHelper.showLong(this, R.string.init_failed, e.message ?: "")
            Handler(Looper.getMainLooper()).postDelayed({
                if (!isFinishing && !isDestroyed) {
                    finish()
                }
            }, AppConfig.INIT_ERROR_DISPLAY_MILLIS)
        }
    }

    /**
     * 检查所有必需的组件是否已初始化
     * @return true 如果所有组件都已初始化，false 否则
     */
    protected fun areComponentsInitialized(): Boolean {
        return try {
            checkComponentInitialized(::viewManager, "viewManager") &&
                checkComponentInitialized(::imageHandler, "imageHandler") &&
                checkComponentInitialized(::voiceHandler, "voiceHandler") &&
                checkComponentInitialized(::permissionManager, "permissionManager") &&
                checkComponentInitialized(::userManagerHandler, "userManagerHandler") &&
                viewManager.areViewsInitialized()
        } catch (e: kotlin.UninitializedPropertyAccessException) {
            Logger.w(TAG, "组件未初始化: ${e.message}", e)
            false
        } catch (e: IllegalStateException) {
            Logger.w(TAG, "状态错误，检查组件初始化失败: ${e.message}", e)
            false
        }
    }

    /**
     * 检查单个组件是否已初始化
     * @param property 组件属性的引用
     * @param name 组件名称（用于日志）
     * @return true 如果已初始化，false 否则
     */
    private fun <T> checkComponentInitialized(
        property: kotlin.reflect.KProperty0<T>,
        name: String,
    ): Boolean {
        return try {
            // 通过访问属性值来检查是否已初始化
            // 如果未初始化，会抛出 UninitializedPropertyAccessException
            property.get()
            true
        } catch (e: UninitializedPropertyAccessException) {
            Logger.w(TAG, "组件 $name 未初始化: ${e.message}", e)
            false
        }
    }

    /**
     * 初始化Activity
     *
     * 初始化流程：
     * 1. 初始化视图组件
     * 2. 初始化图片处理管理器（负责图片选择、裁剪、识别）
     * 3. 初始化子类特定的服务（如TranslationService）
     * 4. 初始化屏幕常亮和超时管理
     *
     * 注意：
     * - 语音识别服务通过依赖注入自动获取，无需手动创建
     * - 权限（相机、麦克风、存储）已在应用启动时统一请求，此处不再请求
     */
    private fun initialize() {
        // 初始化图片处理管理器
        initImageHandler()

        // 初始化子类特定的服务
        initServices()

        // 初始化屏幕常亮和超时管理
        initKeepScreenOn()

        // 权限已在应用启动时统一请求，此处不再请求
    }

    /**
     * 初始化屏幕常亮（不再需要整个流程的超时管理，改为单题超时）
     */
    private fun initKeepScreenOn() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Logger.d(TAG, "屏幕常亮设置已初始化（单题超时由 startQuestionTimeoutTimer() 管理）")
    }

    protected fun setKeepScreenOn(enable: Boolean) {
        if (enable) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /**
     * 初始化图片处理管理器
     */
    private fun initImageHandler() {
        // 创建图片处理管理器
        imageHandler = createImageHandler()

        // 设置管理器的视图引用
        setupImageHandlerViews()

        // 设置听写类型和初始化裁剪视图
        imageHandler.setDictationType(getDictationType())
        imageHandler.initCropViews()
    }

    /**
     * 创建图片处理管理器实例
     */
    private fun createImageHandler(): DictationImageManager {
        return DictationImageManager(
            activity = this,
            viewModel = viewModel,
            onImageRecognized = { words ->
                onImageUnderstandingSuccess(words)
            },
            onImageRecognitionError = { error ->
                handleImageRecognitionError(error)
            },
        )
    }

    /**
     * 设置图片处理管理器的视图引用
     */
    private fun setupImageHandlerViews() {
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
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        permissionManager.handlePermissionResult(
            requestCode,
            permissions,
            grantResults,
            onPermissionGranted = {
                // 权限授予后，重新初始化语音识别服务
                voiceRecognitionService.destroy()
            },
        )
    }

    /**
     * 处理图片识别错误（委托给 errorHandler）
     */
    protected fun handleImageRecognitionError(error: com.smartstudy.core.AppError) {
        if (::errorHandler.isInitialized) {
            errorHandler.handleImageRecognitionError(error)
        } else {
            // 如果错误处理器未初始化，使用临时处理
            Logger.w(TAG, "错误处理器未初始化，使用临时处理")
            ToastHelper.showLong(this, error.message)
        }
    }

    /**
     * 处理手动输入（子类实现）
     */
    protected abstract fun handleManualInput(words: List<String>)

    /**
     * 播放准备就绪提示
     */
    protected fun speakReadyPrompt() {
        // 显示开始听写界面
        viewManager.showStartDictationViews()

        // 播放TTS提示并开始监听
        voiceHandler.speakReadyPrompt {
            voiceHandler.startListeningForStart()
        }
    }

    /**
     * 进入下一个词语
     */
    protected open fun goToNextWord() {
        stopQuestionTimeoutTimer() // 停止当前词语的计时器
        viewModel.goToNextWord()
        startDictation()
    }

    /**
     * 开始监听
     */
    protected fun startListening() {
        voiceHandler.startListening()
    }

    /**
     * 停止监听
     */
    protected fun stopListening(clearAsrUi: Boolean = true) {
        voiceHandler.stopListening(clearAsrUi)
    }

    /**
     * 停止听写并释放资源
     */
    protected fun stopDictation() {
        viewModel.setDictationActive(false)
        stopListening()
        setKeepScreenOn(false)
        stopQuestionTimeoutTimer() // 停止单题计时器
        viewManager.hideDictationViews()
    }

    /**
     * 启动单题超时计时器（1分钟）
     */
    protected fun startQuestionTimeoutTimer() {
        stopQuestionTimeoutTimer()
        questionTimeoutRunnable = Runnable {
            // 检查Activity是否仍然有效
            if (!isFinishing && !isDestroyed && isDictationActive) {
                Logger.w(TAG, "题目超时（1分钟），自动进入下一题")
                // 超时自动进入下一题
                runOnUiThread {
                    goToNextWord()
                }
            }
        }
        questionTimeoutRunnable?.let { runnable ->
            questionTimeoutHandler.postDelayed(
                runnable,
                AppConfig.QUESTION_TIMEOUT_SECONDS * AppConfig.MILLIS_PER_SECOND
            )
            Logger.d(TAG, "已启动单题超时计时器（${AppConfig.QUESTION_TIMEOUT_SECONDS}秒）")
        } ?: Logger.e(TAG, "questionTimeoutRunnable 创建失败，无法启动单题超时计时器")
    }

    /**
     * 停止单题超时计时器
     */
    protected fun stopQuestionTimeoutTimer() {
        questionTimeoutRunnable?.let {
            questionTimeoutHandler.removeCallbacks(it)
            questionTimeoutRunnable = null
        }
    }

    /**
     * 显示进度
     */
    protected fun showProgress(show: Boolean) {
        viewManager.showProgress(show)
    }

    /**
     * 显示错题听写对话框（子类实现）
     */
    protected abstract fun showErrorDictationDialog()

    override fun onPause() {
        super.onPause()
        setKeepScreenOn(false)
        stopListening()
        viewModel.setInForeground(false)
        Logger.d(TAG, "Activity 暂停")
    }

    override fun onResume() {
        super.onResume()
        // 使用属性访问器而不是直接访问 uiState.value
        if (isDictationActive) setKeepScreenOn(true)
        isInForeground = true

        // 更新用户显示
        if (::userManagerHandler.isInitialized) {
            userManagerHandler.updateCurrentUserDisplay()
            val previousUserId = currentUserId
            userManagerHandler.checkAndUpdateUser(previousUserId) { updatedUserId ->
                if (updatedUserId != previousUserId) {
                    viewModel.updateCurrentUser()
                }
            }
        }
        Logger.d(TAG, "Activity 恢复")
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseResources()
        Logger.d(TAG, "Activity 销毁")
    }

    /**
     * 释放所有资源
     *
     * 实现ResourceManager接口，统一管理资源释放，防止内存泄漏。
     *
     * 释放的资源包括：
     * - 停止听写并清理相关状态
     * - 清理Manager资源
     * - 清理超时管理器（通过LifecycleObserver自动清理）
     * - 释放TTS服务资源
     * - 销毁语音识别服务
     *
     * 注意：使用ResourceManagerHelper.safeRelease确保即使某个资源释放失败，
     * 也不会影响其他资源的释放。
     */
    override fun releaseResources() {
        stopDictation()

        // 清理Manager资源
        ResourceManagerHelper.safeRelease {
            if (::voiceHandler.isInitialized) {
                voiceHandler.cleanup()
            }
        }

        // 清理Handler回调，防止内存泄漏
        ResourceManagerHelper.safeRelease {
            questionTimeoutHandler.removeCallbacksAndMessages(null)
            questionTimeoutRunnable = null
        }

        // 只停止TTS播放，不释放资源
        // 注意：ttsService 是单例服务，不应该在 Activity 销毁时释放
        // 单例服务应该在应用生命周期结束时才释放（如果需要的话）
        // 如果在这里释放，会导致后续听写无法使用 TTS
        ResourceManagerHelper.safeRelease {
            ttsService.stop()
        }

        // 检查服务是否已初始化后再释放资源
        // voiceRecognitionService 是通过依赖注入的委托属性，总是已初始化
        ResourceManagerHelper.safeRelease {
            voiceRecognitionService.destroy()
        }
    }
}
