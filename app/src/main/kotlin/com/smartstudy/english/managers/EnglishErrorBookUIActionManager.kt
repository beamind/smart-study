package com.smartstudy.english.managers

import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import com.smartstudy.R
import com.smartstudy.databinding.ActivityEnglishErrorBookBinding
import com.smartstudy.english.QuestionAdapter
import com.smartstudy.english.handlers.ErrorBookImageManager
import com.smartstudy.utils.showToast

/**
 * 英语错题本UI操作管理器
 *
 * 负责管理英语错题本Activity的UI操作逻辑，包括：
 * - 点击监听器设置和管理
 * - 提交按钮处理逻辑
 * - 用户交互处理
 *
 * 使用示例：
 * ```
 * val actionManager = EnglishErrorBookUIActionManager(
 *     activity = this,
 *     binding = binding,
 *     viewModel = viewModel,
 *     questionManager = questionManager,
 *     practiceFlowManager = practiceFlowManager,
 *     imageHandler = imageHandler,
 *     uiManager = uiManager
 * )
 * actionManager.setupClickListeners(
 *     questionAdapter = questionAdapter,
 *     onPracticeStart = { count -> startPractice(count) },
 *     onVariationStart = { count -> startVariation(count) },
 *     onReturnToInitialState = { returnToInitialState() }
 * )
 * ```
 *
 * @param activity Activity实例
 * @param binding View Binding实例
 * @param viewModel 错题本ViewModel
 * @param questionManager 题目管理器
 * @param practiceFlowManager 练习流程管理器
 * @param imageHandler 图片处理管理器
 * @param uiManager UI管理器
 */
class EnglishErrorBookUIActionManager(
    private val activity: AppCompatActivity,
    private val binding: ActivityEnglishErrorBookBinding,
    private val viewModel: com.smartstudy.english.ErrorBookViewModel,
    private val questionManager: ErrorBookQuestionManager,
    private val practiceFlowManager: ErrorBookPracticeFlowManager,
    private val imageHandler: ErrorBookImageManager,
    private val uiManager: EnglishErrorBookUIManager,
) {

    private var questionAdapter: QuestionAdapter? = null
    private var answerAdapter: com.smartstudy.english.AnswerAdapter? = null
    private var onPracticeStart: ((Int) -> Unit)? = null
    private var onVariationStart: ((Int) -> Unit)? = null
    private var onReturnToInitialState: (() -> Unit)? = null
    private var onQuestionsSaved: (() -> Unit)? = null
    private var onQuestionsSaveError: ((String) -> Unit)? = null

    /**
     * 设置点击监听器
     *
     * @param questionAdapter 题目适配器
     * @param answerAdapter 答案适配器
     * @param onPracticeStart 开始练习回调
     * @param onVariationStart 开始举一反三回调
     * @param onReturnToInitialState 返回初始状态回调
     * @param onQuestionsSaved 题目保存成功回调
     * @param onQuestionsSaveError 题目保存错误回调
     */
    fun setupClickListeners(
        questionAdapter: QuestionAdapter?,
        answerAdapter: com.smartstudy.english.AnswerAdapter? = null,
        onPracticeStart: (Int) -> Unit,
        onVariationStart: (Int) -> Unit,
        onReturnToInitialState: () -> Unit,
        onQuestionsSaved: () -> Unit,
        onQuestionsSaveError: (String) -> Unit,
    ) {
        this.questionAdapter = questionAdapter
        this.answerAdapter = answerAdapter
        this.onPracticeStart = onPracticeStart
        this.onVariationStart = onVariationStart
        this.onReturnToInitialState = onReturnToInitialState
        this.onQuestionsSaved = onQuestionsSaved
        this.onQuestionsSaveError = onQuestionsSaveError

        setupActionButtonListeners()
    }

    /**
     * 更新适配器引用
     */
    fun updateAdapters(
        questionAdapter: QuestionAdapter? = null,
        answerAdapter: com.smartstudy.english.AnswerAdapter? = null,
    ) {
        questionAdapter?.let { this.questionAdapter = it }
        answerAdapter?.let { this.answerAdapter = it }
    }

    /**
     * 创建并设置题目适配器（用于识别成功后的选择模式）
     *
     * @param questions 题目列表
     * @param createEditModeCallback 创建编辑模式回调的函数
     * @return 创建的适配器
     */
    fun createAndSetQuestionAdapter(
        questions: List<com.smartstudy.english.QuestionAnswer>,
        createEditModeCallback: () -> QuestionAdapter.EditModeCallback,
    ): QuestionAdapter {
        val adapter =
            QuestionAdapter(
                questions.toMutableList(),
                null,
                createEditModeCallback(),
            )
        // 选择模式：显示复选框，隐藏删除按钮
        adapter.setEditMode(false)
        binding.rvQuestions.adapter = adapter

        // 更新内部引用
        this.questionAdapter = adapter

        return adapter
    }

    /**
     * 创建并设置答案适配器（用于练习完成后显示答案）
     *
     * @param questions 题目列表
     * @return 创建的适配器
     */
    fun createAndSetAnswerAdapter(
        questions: List<com.smartstudy.english.QuestionAnswer>,
    ): com.smartstudy.english.AnswerAdapter {
        val adapter = com.smartstudy.english.AnswerAdapter(questions, viewModel)
        binding.rvAnswers.adapter = adapter

        // 更新内部引用
        this.answerAdapter = adapter

        return adapter
    }

    /**
     * 设置操作按钮监听器
     */
    private fun setupActionButtonListeners() {
        binding.btnParse.setOnClickListener {
            imageHandler.showImageSourceDialog()
        }

        binding.btnView.setOnClickListener {
            questionManager.loadAndShowQuestions()
        }

        binding.btnPractice.setOnClickListener {
            uiManager.showPracticeDialog { count ->
                onPracticeStart?.invoke(count)
            }
        }

        binding.btnVariation.setOnClickListener {
            uiManager.showVariationDialog { count ->
                onVariationStart?.invoke(count)
            }
        }

        binding.btnEdit.setOnClickListener {
            handleEditClick()
        }

        binding.btnSubmit.setOnClickListener {
            handleSubmitClick()
        }

        binding.btnRecognizeUnderline.setOnClickListener {
            handleRecognizeUnderlineClick()
        }

        binding.btnPreview.setOnClickListener {
            handlePreviewClick()
        }

        binding.btnManualUnderline.setOnClickListener {
            handleManualUnderlineClick()
        }

        binding.btnNext.setOnClickListener {
            practiceFlowManager.goToNextQuestion()
        }
    }

    /**
     * 处理预览按钮点击
     */
    private fun handlePreviewClick() {
        // 退出编辑模式，查看预览效果
        questionManager.exitEditMode()
    }

    /**
     * 处理手动下划线按钮点击
     * 在当前获得焦点的EditText的光标位置插入两个下划线（__）
     */
    private fun handleManualUnderlineClick() {
        // 查找当前获得焦点的EditText
        val focusedEditText = findFocusedEditText()

        if (focusedEditText != null) {
            // 获取当前光标位置
            val selectionStart = focusedEditText.selectionStart
            val selectionEnd = focusedEditText.selectionEnd

            // 获取当前文本
            val text = focusedEditText.text ?: return

            // 在光标位置插入两个下划线
            text.insert(selectionStart, "__")

            // 将光标移动到插入文本之后
            focusedEditText.setSelection(selectionStart + 2)
        } else {
            // 如果没有获得焦点的EditText，提示用户
            activity.showToast(com.smartstudy.R.string.error_book_manual_underline_no_focus)
        }
    }

    /**
     * 查找当前获得焦点的EditText
     * @return 获得焦点的EditText，如果没有则返回null
     */
    private fun findFocusedEditText(): EditText? {
        // 遍历RecyclerView的所有ViewHolder，查找获得焦点的EditText
        val recyclerView = binding.rvQuestions
        val childCount = recyclerView.childCount

        for (i in 0 until childCount) {
            val childView = recyclerView.getChildAt(i)
            val viewHolder = recyclerView.getChildViewHolder(childView) as? QuestionAdapter.QuestionViewHolder

            viewHolder?.let { holder ->
                // 检查题目EditText是否获得焦点
                holder.etQuestion.takeIf { it.hasFocus() }?.let { return it }
                // 检查答案EditText是否获得焦点
                holder.etAnswer.takeIf { it.hasFocus() }?.let { return it }
            }
        }

        return null
    }

    /**
     * 处理识别下划线按钮点击
     */
    private fun handleRecognizeUnderlineClick() {
        // 这个方法需要在Activity中实现，因为需要访问bitmap和questions
        // 通过回调传递给Activity处理
        onRecognizeUnderlineClick?.invoke()
    }

    /**
     * 设置识别下划线按钮点击回调
     */
    fun setRecognizeUnderlineClickListener(onClick: () -> Unit) {
        this.onRecognizeUnderlineClick = onClick
    }

    private var onRecognizeUnderlineClick: (() -> Unit)? = null

    /**
     * 处理编辑按钮点击
     */
    private fun handleEditClick() {
        // 只在选择模式下（识别完成后）才能进入编辑模式
        if (!viewModel.uiState.value.isViewMode) {
            questionManager.enterEditMode()
            // 进入编辑模式后，隐藏编辑按钮，显示保存按钮
            uiManager.hideEditButton()
            uiManager.showSaveButton()
            activity.showToast(com.smartstudy.R.string.error_book_enter_edit_mode)
        }
    }

    /**
     * 处理提交按钮点击
     */
    fun handleSubmitClick() {
        when {
            isAnswersVisibleAndSubmitted() -> {
                onReturnToInitialState?.invoke()
            }
            isAnswersVisible() -> {
                handlePracticeAnswerSubmit()
            }
            isViewMode() -> {
                handleViewModeSave()
            }
            isEditMode() -> {
                // 在编辑模式下，保存编辑后的内容
                handleEditModeSave()
            }
            else -> {
                handleSelectionSubmit()
            }
        }
    }

    /**
     * 检查是否在编辑模式
     */
    private fun isEditMode(): Boolean {
        return viewModel.uiState.value.isEditMode
    }

    /**
     * 检查是否在答案页面且已提交
     */
    private fun isAnswersVisibleAndSubmitted(): Boolean {
        return binding.cardAnswers.visibility == View.VISIBLE &&
            viewModel.uiState.value.isPracticeSubmitted
    }

    /**
     * 检查是否在答案页面
     */
    private fun isAnswersVisible(): Boolean {
        return binding.cardAnswers.visibility == View.VISIBLE
    }

    /**
     * 检查是否在查看模式
     */
    private fun isViewMode(): Boolean {
        return viewModel.uiState.value.isViewMode
    }

    /**
     * 处理练习答案页面提交
     */
    private fun handlePracticeAnswerSubmit() {
        val adapter = answerAdapter ?: return
        val allQuestions = practiceFlowManager.getPracticedQuestions()
        val selectedQuestions = adapter.getSelectedQuestions().toSet()
        val isVariationMode = viewModel.uiState.value.isVariationMode

        uiManager.setSubmitButtonLoading(activity.getString(R.string.submit_progress))

        if (allQuestions.isNotEmpty()) {
            // 将选中的题目同步到ViewModel
            viewModel.setSelectedQuestions(selectedQuestions)
            questionManager.submitErrorQuestionsFromPractice(allQuestions, selectedQuestions, isVariationMode)
        } else {
            uiManager.resetSubmitButton(activity.getString(R.string.error_book_submit_error_button))
            activity.showToast(activity.getString(R.string.no_questions_to_save))
        }
    }

    /**
     * 处理查看模式保存
     */
    private fun handleViewModeSave() {
        val adapter = questionAdapter ?: return
        uiManager.setSubmitButtonLoading(activity.getString(R.string.save_progress))

        val questionsToDelete = viewModel.getQuestionsToDeleteSync()
        val questionUpdateMap = viewModel.getQuestionUpdateMapSync()

        questionManager.saveEditedQuestions(
            adapter,
            questionsToDelete,
            questionUpdateMap,
            onSuccess = {
                uiManager.resetSubmitButton(activity.getString(R.string.error_book_save_button))
                // 添加保存成功提示
                val savedCount = questionUpdateMap.size + (adapter.questionAnswers.size - questionsToDelete.size)
                activity.showToast(activity.getString(R.string.error_book_save_success, savedCount))
                onQuestionsSaved?.invoke()
            },
            onError = { error ->
                uiManager.resetSubmitButton(activity.getString(R.string.error_book_save_button))
                onQuestionsSaveError?.invoke(error)
            },
        )
    }

    /**
     * 处理编辑模式保存
     */
    private fun handleEditModeSave() {
        val adapter = questionAdapter ?: return
        uiManager.setSubmitButtonLoading(activity.getString(R.string.save_progress))

        val questionsToDelete = viewModel.getQuestionsToDeleteSync()
        val questionUpdateMap = viewModel.getQuestionUpdateMapSync()

        questionManager.saveEditedQuestions(
            adapter,
            questionsToDelete,
            questionUpdateMap,
            onSuccess = {
                // 保存成功后，退出编辑模式，恢复按钮状态
                questionManager.exitEditMode()
                // 恢复提交按钮文本和状态（状态观察器会自动处理按钮的显示/隐藏）
                uiManager.resetSubmitButton(activity.getString(R.string.error_book_submit_button))
                // 添加保存成功提示
                val savedCount = questionUpdateMap.size + (adapter.questionAnswers.size - questionsToDelete.size)
                activity.showToast(activity.getString(R.string.error_book_save_success, savedCount))
                onQuestionsSaved?.invoke()
            },
            onError = { error ->
                uiManager.resetSubmitButton(activity.getString(R.string.error_book_save_button))
                onQuestionsSaveError?.invoke(error)
            },
        )
    }

    /**
     * 处理选择模式提交
     */
    private fun handleSelectionSubmit() {
        val adapter = questionAdapter ?: return
        val selectedQuestions = adapter.getSelectedQuestionAnswers()

        if (selectedQuestions.isEmpty()) {
            activity.showToast(activity.getString(R.string.select_at_least_one_question))
            return
        }

        uiManager.setSubmitButtonLoading(activity.getString(R.string.submit_progress))
        questionManager.submitErrorQuestions(selectedQuestions)
    }
}
