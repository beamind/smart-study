package com.smartstudy.english

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import com.smartstudy.R
import com.smartstudy.core.Logger
import com.smartstudy.core.ResourceManager
import com.smartstudy.databinding.ActivityEnglishErrorBookBinding
import com.smartstudy.dictation.core.ImageUnderstandingService
import com.smartstudy.english.managers.ErrorBookImageRecognitionManager
import com.smartstudy.utils.SpannableTextUtils
import com.smartstudy.utils.dismissProgressDialog
import com.smartstudy.utils.showLongToast
import com.smartstudy.utils.showProgressDialog
import com.smartstudy.utils.showToast
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.core.parameter.parametersOf

/**
 * 题目和答案数据类
 */
data class QuestionAnswer(
    val question: String,
    val answer: String,
    // 默认为正确，用于练习提交
    val isCorrect: Boolean = true,
    // 是否为大模型生成的题目
    val isAiGenerated: Boolean = false,
)

/**
 * 英语错题本Activity
 *
 * 提供英语错题本的完整功能，包括：
 * - 拍照识别错题：使用AI识别英语试卷中的错题
 * - 错题管理：查看、编辑、删除已保存的错题
 * - 错题练习：基于错误率智能选择错题进行练习
 * - 举一反三：使用AI生成相似的新题目
 *
 * 实现ResourceManager接口，确保资源正确释放，防止内存泄漏。
 * 实现三个回调接口，将业务逻辑委托给Manager/Handler处理。
 *
 * @see ResourceManager
 */
class EnglishErrorBookActivity :
    AppCompatActivity(),
    ResourceManager,
    com.smartstudy.english.handlers.ImageHandlerUICallback,
    com.smartstudy.english.managers.PracticeFlowUICallback,
    com.smartstudy.english.managers.QuestionManagerUICallback {
    companion object {
        private const val TAG = "EnglishErrorBookActivity"
        // 练习超时（分钟）- 使用AppConfig中的配置
    }

    private lateinit var binding: ActivityEnglishErrorBookBinding
    private var progressDialog: android.app.ProgressDialog? = null

    // 使用依赖注入获取服务和ViewModel
    private val errorBookUseCase: com.smartstudy.english.usecases.ErrorBookUseCase by inject { parametersOf(this) }
    private val imageUnderstandingService: ImageUnderstandingService by inject()
    private val viewModel: ErrorBookViewModel by viewModel { parametersOf(this) }

    // 图片处理管理器
    private lateinit var imageHandler: com.smartstudy.english.handlers.ErrorBookImageManager

    // 业务逻辑Manager
    private lateinit var questionManager: com.smartstudy.english.managers.ErrorBookQuestionManager
    private lateinit var practiceFlowManager: com.smartstudy.english.managers.ErrorBookPracticeFlowManager

    // UI管理器
    private lateinit var uiManager: com.smartstudy.english.managers.EnglishErrorBookUIManager
    private lateinit var uiActionManager: com.smartstudy.english.managers.EnglishErrorBookUIActionManager
    private lateinit var stateObserver: com.smartstudy.english.managers.EnglishErrorBookStateObserver

    private val questionGenerationService: QuestionGenerationService by inject()
    private var questionAdapter: QuestionAdapter? = null
    private var answerAdapter: AnswerAdapter? = null

    // 保存识别相关的数据，用于第二阶段下划线识别
    private var currentRecognitionBitmap: Bitmap? = null
    private var currentRecognitionQuestions: List<QuestionAnswer>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEnglishErrorBookBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 设置 Toolbar
        setSupportActionBar(binding.toolbar.toolbar)
        // 隐藏默认标题，使用自定义布局中的标题
        supportActionBar?.setDisplayShowTitleEnabled(false)
        // 设置自定义标题
        binding.toolbar.tvToolbarTitle.text = getString(R.string.error_book_title)

        initViews()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_english_error_book, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val state = viewModel.uiState.value
        val editItem = menu.findItem(R.id.menu_edit)
        val selectAllItem = menu.findItem(R.id.menu_select_all)
        val deleteItem = menu.findItem(R.id.menu_delete)
        val saveItem = menu.findItem(R.id.menu_save)
        val cancelItem = menu.findItem(R.id.menu_cancel)

        if (editItem != null) {
            // 只在查看模式（isViewMode）下显示编辑菜单
            if (state.isViewMode) {
                editItem.isVisible = true
                if (state.isEditMode) {
                    editItem.setTitle("完成")
                    editItem.setIcon(android.R.drawable.ic_menu_close_clear_cancel)
                } else {
                    editItem.setTitle("编辑")
                    editItem.setIcon(android.R.drawable.ic_menu_edit)
                }
            } else {
                editItem.isVisible = false
            }
        }

        // 编辑模式下的菜单项
        if (state.isEditMode && state.isViewMode) {
            // 显示编辑模式菜单项
            selectAllItem?.isVisible = true
            deleteItem?.isVisible = true
            saveItem?.isVisible = true
            cancelItem?.isVisible = true

            // 更新全选菜单项标题
            questionAdapter?.let { adapter ->
                val allPositions = adapter.getAllPositions().toSet()
                val selectedPositions = adapter.getSelectedPositions()
                val isAllSelected = allPositions.isNotEmpty() && selectedPositions.size == allPositions.size
                selectAllItem?.setTitle(
                    if (isAllSelected) {
                        getString(R.string.error_word_list_deselect_all)
                    } else {
                        getString(R.string.error_word_list_select_all)
                    },
                )
            }

            // 根据选中数量启用/禁用删除按钮
            deleteItem?.isEnabled = state.selectedQuestions.isNotEmpty()
        } else {
            // 隐藏编辑模式菜单项
            selectAllItem?.isVisible = false
            deleteItem?.isVisible = false
            saveItem?.isVisible = false
            cancelItem?.isVisible = false
        }

        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_edit -> handleEditMenuItem()
            R.id.menu_select_all -> handleSelectAllMenuItem()
            R.id.menu_delete -> handleDeleteMenuItem()
            R.id.menu_save -> handleSaveMenuItem()
            R.id.menu_cancel -> handleCancelMenuItem()
            else -> super.onOptionsItemSelected(item)
        }
    }

    /**
     * 处理编辑菜单项
     */
    private fun handleEditMenuItem(): Boolean {
        val state = viewModel.uiState.value
        if (state.isEditMode) {
            questionManager.exitEditMode()
        } else {
            questionManager.enterEditMode()
            binding.root.post {
                showToast(com.smartstudy.R.string.error_book_enter_edit_mode)
            }
        }
        invalidateOptionsMenu()
        return true
    }

    /**
     * 处理全选菜单项
     */
    private fun handleSelectAllMenuItem(): Boolean {
        questionAdapter?.let { adapter ->
            val allPositions = adapter.getAllPositions().toSet()
            val selectedPositions = adapter.getSelectedPositions()
            if (allPositions.isEmpty()) {
                return@let
            }
            if (selectedPositions.size == allPositions.size) {
                // 全选状态，取消全选
                adapter.setSelectedPositions(emptySet())
                viewModel.clearSelectedQuestions()
            } else {
                // 未全选，执行全选
                adapter.setSelectedPositions(allPositions)
                val allQuestions = adapter.questionAnswers.mapNotNull { it.question }.toSet()
                viewModel.setSelectedQuestions(allQuestions)
            }
            val state = viewModel.uiState.value
            questionManager.updateEditToolbar(state.selectedQuestions.size, adapter.questionAnswers.size)
            invalidateOptionsMenu()
        } ?: run {
            Logger.w(TAG, "questionAdapter为null，无法执行全选操作")
        }
        return true
    }

    /**
     * 处理删除菜单项
     */
    private fun handleDeleteMenuItem(): Boolean {
        val state = viewModel.uiState.value
        if (state.selectedQuestions.isNotEmpty()) {
            val adapter = questionAdapter ?: return true
            questionManager.performDelete(state.selectedQuestions.toList(), adapter)
        }
        return true
    }

    /**
     * 处理保存菜单项
     */
    private fun handleSaveMenuItem(): Boolean {
        val adapter = questionAdapter ?: return true
        val questionsToDelete = viewModel.getQuestionsToDeleteSync()
        val questionUpdateMap = viewModel.getQuestionUpdateMapSync()
        questionManager.saveEditedQuestions(
            adapter,
            questionsToDelete,
            questionUpdateMap,
            onSuccess = { onQuestionsSaved(false) },
            onError = { error -> onQuestionsSaveError(error) },
        )
        return true
    }

    /**
     * 处理取消菜单项
     */
    private fun handleCancelMenuItem(): Boolean {
        questionManager.exitEditMode()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        // 清理适配器引用，防止内存泄漏
        questionAdapter = null
        answerAdapter = null
        releaseResources()
    }

    /**
     * 释放所有资源
     * 实现ResourceManager接口，统一管理资源释放，防止内存泄漏
     */
    override fun releaseResources() {
        // 停止练习并释放资源
        com.smartstudy.core.ResourceManagerHelper.safeRelease {
            practiceFlowManager.cleanup()
        }

        // 清理进度对话框
        dismissProgressDialog(progressDialog)
        progressDialog = null
    }

    override fun onResume() {
        super.onResume()
        // 更新用户显示（可能在用户管理界面切换了用户）
        updateCurrentUserDisplay()
    }

    /**
     * 更新当前用户显示
     */
    private fun updateCurrentUserDisplay() {
        // ViewModel会自动更新，这里不需要手动更新
    }

    private fun initViews() {
        initBasicViews()
        initImageHandlers()
        initManagers()
        initUIManager()
        initUIActionManager()
        initStateObserver()
        initPracticeViews()
        // 设置RecyclerView（委托给UIManager）
        uiManager.setupRecyclerViews()
        setupClickListeners()
        uiManager.showInputState()
        stateObserver.observeState(lifecycleScope, this)
        observeStateForMenu()
    }

    /**
     * 初始化基础视图
     * View Binding已自动处理视图查找，无需手动初始化
     */
    private fun initBasicViews() {
        // View Binding已自动处理，无需手动初始化
    }

    /**
     * 初始化图片处理管理器
     */
    private fun initImageHandlers() {
        // 初始化图片处理管理器（使用回调接口）
        imageHandler =
            com.smartstudy.english.handlers.ErrorBookImageManager(
                activity = this,
                viewModel = viewModel,
                imageUnderstandingService = imageUnderstandingService,
                uiCallback = this,
            )

        // 设置管理器的视图引用
        imageHandler.ivPhoto = binding.ivPhoto
        imageHandler.cropImageView = binding.cropImageView
        imageHandler.cropContainer = binding.cropContainer
        imageHandler.btnCropDone = binding.btnCropDone
        imageHandler.btnCropCancel = binding.btnCropCancel
        imageHandler.cropButtonLayout = binding.cropButtonLayout
        imageHandler.cardPhoto = binding.cardPhoto
        imageHandler.cardActions = binding.cardActions

        // 初始化裁剪视图
        imageHandler.initCropViews()
    }

    /**
     * 初始化业务逻辑Manager
     */
    private fun initManagers() {
        questionManager =
            com.smartstudy.english.managers.ErrorBookQuestionManager(
                activity = this,
                errorBookUseCase = errorBookUseCase,
                viewModel = viewModel,
                uiCallback = this,
            )

        practiceFlowManager =
            com.smartstudy.english.managers.ErrorBookPracticeFlowManager(
                activity = this,
                errorBookUseCase = errorBookUseCase,
                questionGenerationService = questionGenerationService,
                viewModel = viewModel,
                uiCallback = this,
            )
    }

    /**
     * 初始化UI管理器
     */
    private fun initUIManager() {
        uiManager =
            com.smartstudy.english.managers.EnglishErrorBookUIManager(
                activity = this,
                binding = binding,
            )
    }

    /**
     * 初始化UI操作管理器
     */
    private fun initUIActionManager() {
        uiActionManager =
            com.smartstudy.english.managers.EnglishErrorBookUIActionManager(
                activity = this,
                binding = binding,
                viewModel = viewModel,
                questionManager = questionManager,
                practiceFlowManager = practiceFlowManager,
                imageHandler = imageHandler,
                uiManager = uiManager,
            )
    }

    /**
     * 初始化状态观察器
     */
    private fun initStateObserver() {
        stateObserver =
            com.smartstudy.english.managers.EnglishErrorBookStateObserver(
                binding = binding,
                viewModel = viewModel,
                uiManager = uiManager,
            )
    }

    /**
     * 初始化练习相关视图
     * View Binding已自动处理视图查找，无需手动初始化
     */
    private fun initPracticeViews() {
        // View Binding已自动处理，无需手动初始化
    }

    /**
     * 设置点击监听器
     */
    private fun setupClickListeners() {
        uiActionManager.setupClickListeners(
            questionAdapter = questionAdapter,
            answerAdapter = answerAdapter,
            onPracticeStart = { count -> startPractice(count) },
            onVariationStart = { count -> startVariation(count) },
            onReturnToInitialState = { returnToInitialState() },
            onQuestionsSaved = { onQuestionsSaved(false) },
            onQuestionsSaveError = { error -> onQuestionsSaveError(error) },
        )
        // 设置识别下划线按钮点击监听
        uiActionManager.setRecognizeUnderlineClickListener {
            handleRecognizeUnderlineClick()
        }
    }

    /**
     * 处理识别下划线按钮点击
     */
    private fun handleRecognizeUnderlineClick() {
        val bitmap = currentRecognitionBitmap
        val questions = currentRecognitionQuestions

        if (bitmap == null || questions == null) {
            showLongToast("无法识别下划线：缺少必要的识别数据")
            Logger.w(TAG, "识别下划线失败：bitmap或questions为null")
            return
        }

        // 禁用按钮
        binding.btnRecognizeUnderline.isEnabled = false

        // 显示进度对话框
        showProgressDialog(getString(R.string.error_book_recognizing_underline))

        // 创建识别管理器并执行第二阶段识别
        val recognitionManager =
            ErrorBookImageRecognitionManager(
                viewModel = viewModel,
                imageUnderstandingService = imageUnderstandingService,
                onRecognitionSuccess = { _ -> }, // 不需要处理，因为使用回调
                onRecognitionError = { _ -> }, // 不需要处理，因为使用回调
            )

        recognitionManager.recognizeUnderlines(
            bitmap, questions,
            onSuccess = { updatedQuestions ->
                hideProgressDialog()
                // 更新题目列表
                currentRecognitionQuestions = updatedQuestions
                questionAdapter?.let { adapter ->
                    adapter.questionAnswers.clear()
                    adapter.questionAnswers.addAll(updatedQuestions)
                    adapter.notifyDataSetChanged()
                }
                // 隐藏下划线按钮（识别完成后不再需要）
                binding.btnRecognizeUnderline.visibility = View.GONE
                showToast(getString(R.string.error_book_underline_recognition_success))
            },
            onError = { error ->
                hideProgressDialog()
                // 恢复按钮状态（识别失败时保持按钮可见，允许重试）
                binding.btnRecognizeUnderline.isEnabled = true
                showLongToast(getString(R.string.error_book_underline_recognition_failed, error))
            },
        )
    }


    // 以下方法已迁移到 QuestionManager：
    // - loadAndShowQuestions()
    // - createEditModeCallback()
    // - enterEditMode()
    // - exitEditMode()
    // - updateEditToolbar()
    // - showDeleteVerificationDialog()
    // - performDelete()

    // 以下方法已迁移到 QuestionManager：
    // - saveEditedQuestions()
    // - deleteMarkedQuestions()
    // - updateQuestions()
    // - updateAnswers()
    // - reorderQuestions()
    // - finishSaving()
    // - syncQuestionInAdapter()
    // - syncAnswerInAdapter()
    // - submitErrorQuestions()

    // 去除题目开头的题号（兜底逻辑） - moved to EnglishErrorBookParser.kt

    // 解析题目和答案 - moved to EnglishErrorBookParser.kt

    // 解析题目 - moved to EnglishErrorBookParser.kt

    // 根据错误率和日期差计算采样概率，随机选择题目 - moved to EnglishErrorBookSampling.kt

    /**
     * 开始举一反三
     */
    private fun startVariation(count: Int) {
        // 显示生成进度对话框
        val progressDialog =
            showProgressDialog(getString(R.string.generating_questions), cancelable = false)
                ?: return
        progressDialog.setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL)
        progressDialog.max = count
        progressDialog.progress = 0

        practiceFlowManager.startVariation(
            count = count,
            onProgress = { current, total ->
                progressDialog.progress = current
            },
            onProgressDialogDismiss = {
                progressDialog.dismiss()
            },
        )
    }

    /**
     * 开始错题练习
     */
    private fun startPractice(count: Int) {
        showToast(R.string.error_book_loading_questions)
        practiceFlowManager.startPractice(count)
    }

    /**
     * 返回初始状态（从练习答案页面返回）
     */
    private fun returnToInitialState() {
        uiManager.returnToInitialState()
        practiceFlowManager.clearPracticeState()
    }

    // 根据错误率采样题目和答案对 - moved to EnglishErrorBookSampling.kt

    // 以下方法已迁移到 PracticeFlowManager：
    // - speakReadyPrompt()
    // - startListeningForStart()
    // - startQuestionPractice()
    // - speakQuestion()
    // - goToNextQuestion()
    // - startListening()
    // - stopListening()
    // - showAnswers()
    // - submitErrorQuestionsFromPractice()
    // - stopPractice()
    // - startTimeoutTimer()
    // - resetTimeoutTimer()


    // ==================== ImageHandlerUICallback 实现 ====================

    override fun onRecognitionSuccess(
        questions: List<QuestionAnswer>,
        bitmap: Bitmap?,
    ) {
        hideRecognizingProgress()

        // 保存bitmap和识别结果，用于第二阶段下划线识别
        currentRecognitionBitmap = bitmap
        currentRecognitionQuestions = questions

        // 使用 UI Manager 处理 UI 更新
        uiManager.handleRecognitionSuccess()

        // 创建适配器并显示题目列表（委托给UIActionManager）
        questionAdapter =
            uiActionManager.createAndSetQuestionAdapter(
                questions = questions,
                createEditModeCallback = { createEditModeCallback() },
            )

        // 更新状态观察器中的适配器引用
        stateObserver.updateQuestionAdapter(questionAdapter)

        // 切换到选择状态
        viewModel.setViewMode(false)
        updateUIForSelectionState()

        showToast(getString(R.string.error_book_recognition_success, questions.size))
    }

    override fun onRecognitionError(error: String) {
        hideRecognizingProgress()

        // 使用 UI Manager 处理 UI 更新
        uiManager.handleRecognitionError()
        updateUIForErrorState()

        showLongToast(getString(R.string.error_book_recognition_failed, error))
    }

    override fun showProgressDialog(message: String) {
        progressDialog = showProgressDialog(message, cancelable = false)
    }

    override fun hideProgressDialog() {
        dismissProgressDialog(progressDialog)
        progressDialog = null
    }

    override fun updateUIForSelectionState() {
        uiManager.showSelectionState()
    }

    override fun updateUIForErrorState() {
        uiManager.updateUIForErrorState()
    }

    // ==================== PracticeFlowUICallback 实现 ====================

    override fun onPracticeStarted() {
        uiManager.handlePracticeStarted()
    }

    override fun onQuestionChanged(
        index: Int,
        total: Int,
        question: String,
    ) {
        uiManager.updateQuestionDisplay(index, total, question)
    }

    override fun onPracticeCompleted() {
        // 练习完成，UI已由showAnswers更新
    }

    override fun onPracticeStopped() {
        // 练习停止，可以在这里做清理工作
    }

    override fun updateStatusText(text: String) {
        uiManager.updateStatusText(text)
    }

    override fun showAnswers(questions: List<QuestionAnswer>) {
        if (questions.isEmpty()) {
            Logger.w(TAG, "没有练习答案可显示")
            return
        }

        // 使用 UI Manager 处理 UI 更新
        uiManager.showPracticeAnswers()

        // 创建并设置答案适配器（委托给UIActionManager）
        answerAdapter = uiActionManager.createAndSetAnswerAdapter(questions)

        Logger.d(TAG, "显示练习答案，共 ${questions.size} 道题目")
    }

    override fun updateProgressVisibility(visible: Boolean) {
        uiManager.updateProgressVisibility(visible)
    }

    override fun updateStatusVisibility(visible: Boolean) {
        uiManager.updateStatusVisibility(visible)
    }

    override fun updateAnswersVisibility(visible: Boolean) {
        uiManager.updateAnswersVisibility(visible)
    }

    // ==================== QuestionManagerUICallback 实现 ====================

    override fun onQuestionsLoaded(
        adapter: QuestionAdapter,
        statsMap: Map<String, com.smartstudy.dictation.core.DictationWordStat>,
    ) {
        questionAdapter = adapter
        binding.rvQuestions.adapter = adapter

        // 更新 Manager 中的适配器引用
        uiActionManager.updateAdapters(questionAdapter = questionAdapter)
        stateObserver.updateQuestionAdapter(questionAdapter)
    }

    override fun onQuestionsLoadError(error: String) {
        showToast(error)
    }

    override fun onQuestionsSaved(isFromPractice: Boolean) {
        if (isFromPractice) {
            // 从练习页面提交，禁用复选框
            viewModel.setAnswerCheckboxesEnabled(false)
            answerAdapter?.notifyStateChanged()
            // 按钮状态已在 QuestionManager 中更新
        } else {
            // 使用 UI Manager 处理 UI 更新
            uiManager.handleQuestionsSaved(
                isFromPractice = isFromPractice,
                isViewMode = viewModel.uiState.value.isViewMode,
                isEditMode = viewModel.uiState.value.isEditMode,
                onResetToInputState = {
                    // 从拍照识别页面提交，重置到初始状态（返回到英语错题本主界面）
                    questionAdapter = null
                    uiActionManager.updateAdapters(questionAdapter = null)
                    stateObserver.updateQuestionAdapter(null)
                    // 清理 ViewModel 状态
                    viewModel.setEditMode(false)
                    viewModel.clearQuestionUpdateMap()
                    viewModel.clearQuestionsToDelete()
                    viewModel.setViewMode(false)
                    updateUIForInputState()
                },
                onReloadQuestions = {
                    // 从查看模式保存，重新加载错题列表
                    questionManager.loadAndShowQuestions()
                },
            )
        }
    }

    override fun updateSubmitButton(
        text: String,
        enabled: Boolean,
    ) {
        uiManager.updateSubmitButton(text, enabled)
    }

    override fun updateViewButton(
        text: String,
        enabled: Boolean,
    ) {
        uiManager.updateViewButton(text, enabled)
    }

    // 重载方法：接受资源ID的版本（非接口方法，不标记override）
    fun updateSubmitButton(
        textResId: Int,
        enabled: Boolean,
    ) {
        uiManager.updateSubmitButton(textResId, enabled)
    }

    override fun onQuestionsSaveError(error: String) {
        showLongToast(error)
    }

    override fun onQuestionsDeleted() {
        showToast(R.string.error_book_delete_success)
        questionManager.exitEditMode()
    }

    override fun onQuestionsDeleteError(error: String) {
        showLongToast(error)
    }

    override fun showDeleteVerificationDialog(onConfirm: () -> Unit) {
        uiManager.showDeleteVerificationDialog(onConfirm)
    }

    override fun updateUIForViewState() {
        uiManager.showViewState()
    }

    // updateUIForSelectionState() 已在 ImageHandlerUICallback 实现部分定义（第 841 行）
    // 由于 ImageHandlerUICallback 和 QuestionManagerUICallback 都定义了相同的方法，
    // 只需要一个实现即可同时满足两个接口的要求

    override fun updateUIForInputState() {
        uiManager.showInputState()
    }

    override fun updateEditToolbar(
        selectedCount: Int,
        totalCount: Int,
        isAllSelected: Boolean,
    ) {
        uiManager.updateEditToolbar(selectedCount, totalCount, isAllSelected)
    }

    override fun createEditModeCallback(): QuestionAdapter.EditModeCallback {
        return object : QuestionAdapter.EditModeCallback {
            override fun onEnterEditMode() {
                this@EnglishErrorBookActivity.questionManager.enterEditMode()
            }

            override fun onQuestionDeleted(
                position: Int,
                question: String,
            ) {
                // 单个删除功能已移除，使用批量删除
            }

            override fun onQuestionUpdated(
                position: Int,
                oldQuestion: String,
                newQuestion: String,
            ) {
                if (oldQuestion != newQuestion) {
                    // 如果该题目之前有更新记录，移除旧的
                    viewModel.removeQuestionUpdate(oldQuestion)
                    // 添加新的更新记录
                    viewModel.addQuestionUpdate(oldQuestion, newQuestion)
                }
            }

            override fun onSelectionChanged(selectedPositions: Set<Int>) {
                val selectedQuestions = mutableSetOf<String>()
                this@EnglishErrorBookActivity.questionAdapter?.let { adapter ->
                    selectedPositions.forEach { position ->
                        if (position < adapter.questionAnswers.size) {
                            val question = adapter.questionAnswers[position].question
                            selectedQuestions.add(question)
                        }
                    }
                }
                // 更新ViewModel中的选中状态
                viewModel.setSelectedQuestions(selectedQuestions)
                this@EnglishErrorBookActivity.questionManager.updateEditToolbar(
                    selectedQuestions.size,
                    this@EnglishErrorBookActivity.questionAdapter?.questionAnswers?.size ?: 0,
                )
            }
        }
    }

    // ==================== 辅助方法 ====================


    /**
     * 隐藏识别进度对话框
     */
    private fun hideRecognizingProgress() {
        hideProgressDialog()
    }

    /**
     * 观察状态变化以刷新菜单
     */
    private fun observeStateForMenu() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    // 编辑模式或查看模式变化时刷新菜单
                    invalidateOptionsMenu()
                }
            }
        }
    }
}

/**
 * 答案列表适配器（用于练习完成后显示）
 *
 * 状态管理已迁移到 ViewModel，适配器不再维护自己的状态。
 * 所有状态（selectedAnswerPositions, isAnswerCheckboxesEnabled）都从 ViewModel 获取。
 */
class AnswerAdapter(
    private val questionAnswers: List<QuestionAnswer>,
    private val viewModel: ErrorBookViewModel,
) : RecyclerView.Adapter<AnswerAdapter.AnswerViewHolder>() {
    class AnswerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val cbSelect: CheckBox = itemView.findViewById(R.id.cbSelect)
        val tvAnswerWord: TextView = itemView.findViewById(R.id.tvAnswerWord)
        val tvAnswerTranslation: TextView? = itemView.findViewById(R.id.tvAnswerTranslation)
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): AnswerViewHolder {
        val view =
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_answer, parent, false)
        return AnswerViewHolder(view)
    }

    override fun onBindViewHolder(
        holder: AnswerViewHolder,
        position: Int,
    ) {
        val qa = questionAnswers[position]
        // 显示题目和答案，答案部分用蓝色显示
        val answerPrefix = holder.itemView.context.getString(R.string.answer_prefix)

        // 移除题目中的 Markdown 下划线标记（__text__ → text）
        val questionWithoutMarkers = SpannableTextUtils.removeUnderlineMarkers(qa.question)

        // 构建完整文本：第X题\n题目内容\n答案：答案内容
        val questionNumber = "第${position + 1}题"
        val fullText = "$questionNumber\n$questionWithoutMarkers\n$answerPrefix${qa.answer}"
        val spannableString = SpannableString(fullText)

        // 设置"第X题"的颜色为蓝色（与"答案"颜色一致）
        val answerColor = holder.itemView.context.getColor(R.color.primary_blue)
        spannableString.setSpan(
            ForegroundColorSpan(answerColor),
            0,
            questionNumber.length,
            Spanned.SPAN_INCLUSIVE_INCLUSIVE,
        )

        // 应用下划线样式（在移除标记后应用，只应用到题目部分）
        // 题目内容的起始位置 = 题号长度 + 1（换行符）
        val questionStartIndex = questionNumber.length + 1
        SpannableTextUtils.applyUnderlineSpans(qa.question, spannableString, questionStartIndex)

        // 找到"答案："的位置
        val answerStartIndex = spannableString.toString().indexOf(answerPrefix)
        if (answerStartIndex >= 0) {
            // 从"答案："开始到文本结束，设置为蓝色
            spannableString.setSpan(
                ForegroundColorSpan(answerColor),
                answerStartIndex,
                spannableString.length,
                Spanned.SPAN_INCLUSIVE_INCLUSIVE,
            )
        }

        holder.tvAnswerWord.text = spannableString

        // 隐藏翻译 TextView（如果存在）
        holder.tvAnswerTranslation?.visibility = View.GONE

        // 为第一项设置顶部边距，确保分隔线可见
        val layoutParams = holder.itemView.layoutParams as? ViewGroup.MarginLayoutParams
        if (layoutParams != null) {
            if (position == 0) {
                // 第一项设置为 0，覆盖 item_answer.xml 中的默认 marginTop="4dp"
                // 这样 RecyclerView 的 paddingTop="12dp" 就能提供足够空间显示分隔线
                layoutParams.topMargin = 0
            } else {
                // 其他项保持原有的 marginTop（item_answer.xml 中设置的 4dp）
                // 如果 layoutParams 已经有值（来自布局文件），就不需要重新设置
                if (layoutParams.topMargin == 0) {
                    layoutParams.topMargin = 4.dpToPx(holder.itemView.context)
                }
            }
            holder.itemView.layoutParams = layoutParams
        }

        // 先清除监听器，避免在设置状态时触发
        holder.cbSelect.setOnCheckedChangeListener(null)

        // 从 ViewModel 获取状态
        val state = viewModel.uiState.value
        val isSelected = state.selectedAnswerPositions.contains(position)
        holder.cbSelect.isChecked = isSelected

        // 设置复选框是否可用
        holder.cbSelect.isEnabled = state.isAnswerCheckboxesEnabled

        // 设置复选框点击事件（只有在可用时才设置）
        if (state.isAnswerCheckboxesEnabled) {
            holder.cbSelect.setOnCheckedChangeListener { _, isChecked ->
                val currentState = viewModel.uiState.value
                val newPositions =
                    if (isChecked) {
                        currentState.selectedAnswerPositions + position
                    } else {
                        currentState.selectedAnswerPositions - position
                    }
                viewModel.setSelectedAnswerPositions(newPositions)
            }
        } else {
            holder.cbSelect.setOnCheckedChangeListener(null)
        }
    }

    private fun Int.dpToPx(context: Context): Int {
        return (this * context.resources.displayMetrics.density).toInt()
    }

    override fun getItemCount(): Int = questionAnswers.size

    /**
     * 获取选中的题目列表
     */
    fun getSelectedQuestions(): List<String> {
        val state = viewModel.uiState.value
        return state.selectedAnswerPositions.sorted().map { questionAnswers[it].question }
    }

    /**
     * 通知适配器状态已更新（由 Activity 在观察 ViewModel 状态时调用）
     */
    fun notifyStateChanged() {
        notifyDataSetChanged()
    }
}
