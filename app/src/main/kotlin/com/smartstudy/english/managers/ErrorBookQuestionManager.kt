package com.smartstudy.english.managers

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.viewModelScope
import com.smartstudy.R
import com.smartstudy.core.Logger
import com.smartstudy.dictation.core.DictationWordStat
import com.smartstudy.english.ErrorBookViewModel
import com.smartstudy.english.QuestionAdapter
import com.smartstudy.english.QuestionAnswer
import com.smartstudy.english.usecases.ErrorBookUseCase
import com.smartstudy.utils.showToast
import kotlinx.coroutines.launch

/**
 * 题目管理UI回调接口
 * 用于与Activity通信，保持解耦
 */
interface QuestionManagerUICallback {
    /**
     * 题目加载成功回调
     * @param adapter 题目适配器
     * @param statsMap 统计信息映射
     */
    fun onQuestionsLoaded(
        adapter: QuestionAdapter,
        statsMap: Map<String, DictationWordStat>,
    )

    /**
     * 题目加载错误回调
     * @param error 错误信息
     */
    fun onQuestionsLoadError(error: String)

    /**
     * 题目保存成功回调
     * @param isFromPractice 是否从练习答案页面提交
     */
    fun onQuestionsSaved(isFromPractice: Boolean = false)

    /**
     * 题目保存错误回调
     * @param error 错误信息
     */
    fun onQuestionsSaveError(error: String)

    /**
     * 更新提交按钮状态（用于练习答案页面）
     * @param text 按钮文本
     * @param enabled 是否启用
     */
    fun updateSubmitButton(
        text: String,
        enabled: Boolean,
    )

    /**
     * 更新查看按钮状态
     * @param text 按钮文本
     * @param enabled 是否启用
     */
    fun updateViewButton(
        text: String,
        enabled: Boolean,
    )

    /**
     * 题目删除成功回调
     */
    fun onQuestionsDeleted()

    /**
     * 题目删除错误回调
     * @param error 错误信息
     */
    fun onQuestionsDeleteError(error: String)

    /**
     * 显示删除验证对话框
     * @param onConfirm 确认删除回调
     */
    fun showDeleteVerificationDialog(onConfirm: () -> Unit)

    /**
     * 更新UI为查看状态
     */
    fun updateUIForViewState()

    /**
     * 更新UI为选择状态
     */
    fun updateUIForSelectionState()

    /**
     * 更新UI为输入状态
     */
    fun updateUIForInputState()

    /**
     * 更新编辑工具栏
     * @param selectedCount 选中数量
     * @param totalCount 总数量
     * @param isAllSelected 是否全选
     */
    fun updateEditToolbar(
        selectedCount: Int,
        totalCount: Int,
        isAllSelected: Boolean,
    )

    /**
     * 创建编辑模式回调
     * @return 编辑模式回调实例
     */
    fun createEditModeCallback(): QuestionAdapter.EditModeCallback
}

/**
 * 错题本题目管理Manager
 *
 * 负责错题本的CRUD操作，将题目管理相关逻辑从Activity中分离出来。
 *
 * 功能特性：
 * - 统一的题目加载、保存、删除、更新操作
 * - 自动处理题目和答案的同步
 * - 统一的错误处理
 * - 编辑模式管理
 * - 删除验证对话框
 *
 * 使用示例：
 * ```
 * val questionManager = ErrorBookQuestionManager(
 *     activity = this,
 *     errorBookUseCase = errorBookUseCase,
 *     viewModel = viewModel,
 *     uiCallback = this // Activity实现QuestionManagerUICallback接口
 * )
 * questionManager.loadAndShowQuestions()
 * ```
 *
 * @param activity Activity实例，用于访问生命周期
 * @param errorBookUseCase 错题本业务逻辑用例
 * @param viewModel 错题本ViewModel，用于管理状态
 * @param uiCallback UI回调接口，用于与Activity通信
 */
class ErrorBookQuestionManager(
    private val activity: AppCompatActivity,
    private val errorBookUseCase: ErrorBookUseCase,
    private val viewModel: ErrorBookViewModel,
    private val uiCallback: QuestionManagerUICallback,
) {
    companion object {
        private const val TAG = "ErrorBookQuestionManager"
    }

    /**
     * 加载并显示已保存的错题
     * 包括创建适配器和更新UI状态
     */
    fun loadAndShowQuestions() {
        viewModel.viewModelScope.launch {
            // 确保默认用户存在并获取用户名
            val userName =
                when (val userResult = errorBookUseCase.ensureDefaultUser()) {
                    is com.smartstudy.core.AppResult.Success -> userResult.data.name
                    is com.smartstudy.core.AppResult.Failure -> {
                        uiCallback.updateViewButton(activity.getString(R.string.error_book_view_button_text), true)
                        uiCallback.onQuestionsLoadError(userResult.error.message)
                        return@launch
                    }
                }

            val result = errorBookUseCase.loadAllQuestionsWithAnswers(userName)

            when (result) {
                is com.smartstudy.core.AppResult.Success -> {
                    val questionAnswersWithStats = result.data

                    if (questionAnswersWithStats.isEmpty()) {
                        uiCallback.updateViewButton(activity.getString(R.string.error_book_view_button_text), true)
                        uiCallback.onQuestionsLoadError(activity.getString(R.string.error_book_no_saved_questions))
                        return@launch
                    }

                    // 创建题目和答案列表和统计信息映射
                    val questionAnswers = questionAnswersWithStats.map { it.first }.toMutableList()
                    val statsMap =
                        questionAnswersWithStats.associateBy { it.first.question }
                            .mapValues { (_, pair) ->
                                val stats = pair.second
                                val errorRate =
                                    if (stats.totalCount > 0) {
                                        stats.errorCount.toFloat() / stats.totalCount.toFloat()
                                    } else {
                                        0f
                                    }
                                DictationWordStat(
                                    word = stats.question,
                                    errorCount = stats.errorCount,
                                    totalCount = stats.totalCount,
                                    errorRate = errorRate,
                                    chineseTranslation = "",
                                    lastDictationTimestamp = 0L,
                                )
                            }

                    // 创建适配器并显示题目列表（默认禁用编辑模式，需要长按进入编辑模式）
                    val adapter =
                        QuestionAdapter(
                            questionAnswers,
                            questionAnswersWithStats.associateBy { it.first.question }.mapValues { it.value.second },
                            uiCallback.createEditModeCallback(),
                            isViewMode = true,
                        )
                    adapter.setEditMode(false) // 默认禁用编辑模式

                    // 切换到查看状态
                    viewModel.setViewMode(true)
                    viewModel.clearQuestionUpdateMap()
                    viewModel.clearQuestionsToDelete()

                    // 通知UI更新
                    uiCallback.onQuestionsLoaded(adapter, statsMap)
                    uiCallback.updateUIForViewState()

                    // 恢复按钮状态
                    uiCallback.updateViewButton(activity.getString(R.string.error_book_view_button_text), true)
                }
                is com.smartstudy.core.AppResult.Failure -> {
                    uiCallback.updateViewButton(activity.getString(R.string.error_book_view_button_text), true)
                    uiCallback.onQuestionsLoadError(result.error.message)
                }
            }
        }
    }

    /**
     * 提交错题（从识别结果页面）
     *
     * @param questions 要提交的题目列表
     */
    fun submitErrorQuestions(questions: List<QuestionAnswer>) {
        if (questions.isEmpty()) {
            uiCallback.onQuestionsSaveError(activity.getString(R.string.error_book_select_at_least_one))
            return
        }

        viewModel.viewModelScope.launch {
            // 确保默认用户存在并获取用户名
            val userName =
                when (val userResult = errorBookUseCase.ensureDefaultUser()) {
                    is com.smartstudy.core.AppResult.Success -> userResult.data.name
                    is com.smartstudy.core.AppResult.Failure -> {
                        uiCallback.onQuestionsSaveError(userResult.error.message)
                        return@launch
                    }
                }

            val result = errorBookUseCase.saveQuestionsWithAnswers(userName, questions)

            when (result) {
                is com.smartstudy.core.AppResult.Success -> {
                    activity.runOnUiThread {
                        activity.showToast(activity.getString(R.string.error_book_questions_saved_simple, questions.size))
                    }
                    // 从识别页面提交，需要重置到初始状态
                    uiCallback.onQuestionsSaved(isFromPractice = false)
                }
                is com.smartstudy.core.AppResult.Failure -> {
                    val errorMessage =
                        when (result.error.kind) {
                            com.smartstudy.core.AppErrorKind.StoragePermission -> activity.getString(R.string.error_book_save_permission_issue)
                            com.smartstudy.core.AppErrorKind.StorageSpaceFull -> result.error.message ?: "存储空间不足，请清理空间后重试"
                            com.smartstudy.core.AppErrorKind.InvalidData -> result.error.message ?: "输入数据不合法"
                            else -> result.error.message ?: "保存失败，请稍后重试"
                        }
                    Logger.e(TAG, "保存错题失败: $errorMessage", result.error.cause)
                    uiCallback.onQuestionsSaveError(errorMessage)
                }
            }
        }
    }

    /**
     * 提交错题（从练习答案页面）
     *
     * @param allQuestions 所有练习的题目列表
     * @param selectedQuestions 用户勾选的错题集合
     * @param isVariationMode 是否为举一反三模式
     */
    fun submitErrorQuestionsFromPractice(
        allQuestions: List<QuestionAnswer>,
        selectedQuestions: Set<String>,
        isVariationMode: Boolean,
    ) {
        if (allQuestions.isEmpty()) {
            uiCallback.onQuestionsSaveError(activity.getString(R.string.error_book_no_questions_to_save))
            return
        }

        // 构建所有题目及其状态的列表（带答案）
        val questionAnswers =
            allQuestions.map { qa ->
                QuestionAnswer(
                    qa.question,
                    qa.answer,
                    isCorrect = !selectedQuestions.contains(qa.question),
                    // 如果是举一反三模式，标记为 AI 生成
                    isAiGenerated = isVariationMode,
                )
            }

        viewModel.viewModelScope.launch {
            // 确保默认用户存在并获取用户名
            val userName =
                when (val userResult = errorBookUseCase.ensureDefaultUser()) {
                    is com.smartstudy.core.AppResult.Success -> userResult.data.name
                    is com.smartstudy.core.AppResult.Failure -> {
                        uiCallback.onQuestionsSaveError(userResult.error.message)
                        return@launch
                    }
                }

            val result = errorBookUseCase.saveQuestionsWithAnswers(userName, questionAnswers)

            when (result) {
                is com.smartstudy.core.AppResult.Success -> {
                    val correctCount = questionAnswers.count { it.isCorrect }
                    val errorCount = questionAnswers.size - correctCount
                    Logger.d(TAG, "题目保存成功，共 ${questionAnswers.size} 个，正确 $correctCount 个，错误 $errorCount 个")

                    // 保存成功后，将按钮改为"返回"，让小朋友可以反思错题
                    viewModel.setPracticeSubmitted(true)

                    activity.runOnUiThread {
                        activity.showToast(activity.getString(com.smartstudy.R.string.error_book_questions_saved, questionAnswers.size, correctCount, errorCount))

                        // 更新按钮状态
                        uiCallback.updateSubmitButton(activity.getString(com.smartstudy.R.string.error_book_return_button), true)

                        // 禁用复选框，只能看不能再改
                        // 注意：这需要在 Activity 中处理，因为需要访问 answerAdapter
                    }

                    // 从练习页面提交，调用回调但不重新加载列表
                    uiCallback.onQuestionsSaved(isFromPractice = true)
                }
                is com.smartstudy.core.AppResult.Failure -> {
                    val errorMessage =
                        when (result.error.kind) {
                            com.smartstudy.core.AppErrorKind.StoragePermission -> activity.getString(R.string.error_book_save_permission_issue)
                            com.smartstudy.core.AppErrorKind.StorageSpaceFull -> result.error.message ?: "存储空间不足，请清理空间后重试"
                            com.smartstudy.core.AppErrorKind.InvalidData -> result.error.message ?: "输入数据不合法"
                            else -> result.error.message ?: "保存失败，请稍后重试"
                        }
                    Logger.e(TAG, "保存错题失败: $errorMessage", result.error.cause)
                    uiCallback.onQuestionsSaveError(errorMessage)
                }
            }
        }
    }

    /**
     * 执行删除操作（包括验证对话框）
     *
     * @param questions 要删除的题目列表
     * @param adapter 题目适配器
     */
    fun performDelete(
        questions: List<String>,
        adapter: QuestionAdapter,
    ) {
        if (questions.isEmpty()) {
            return
        }

        // 显示删除验证对话框
        uiCallback.showDeleteVerificationDialog {
            // 确认删除后执行删除操作
            executeDelete(questions, adapter)
        }
    }

    /**
     * 执行删除操作
     */
    private fun executeDelete(
        questions: List<String>,
        adapter: QuestionAdapter,
    ) {
        val questionsToDeleteNow = questions.toList()

        // 将选中的题目添加到删除列表（用于保存时过滤）
        questions.forEach { question ->
            // 如果该题目正在被更新，先移除更新记录
            viewModel.removeQuestionUpdate(question)
            viewModel.addQuestionToDelete(question)
        }

        // 从适配器中移除选中的项目
        val selectedPositions = adapter.getSelectedPositions().sorted().reversed()
        selectedPositions.forEach { position ->
            if (position < adapter.questionAnswers.size) {
                adapter.questionAnswers.removeAt(position)
            }
        }
        adapter.notifyDataSetChanged()

        // 过滤掉那些已经被更新的题目（因为更新会创建新key，旧key会被删除）
        val toDelete =
            questionsToDeleteNow.filter {
                !viewModel.getQuestionUpdateMapSync().containsKey(it)
            }

        if (toDelete.isEmpty()) {
            // 没有需要删除的题目（可能都被更新了），直接返回
            uiCallback.onQuestionsDeleted()
            return
        }

        // 执行删除
        deleteQuestions(
            questions = toDelete,
            onSuccess = {
                // 从删除列表中移除已删除的题目
                questionsToDeleteNow.forEach { viewModel.removeQuestionToDelete(it) }
                uiCallback.onQuestionsDeleted()
            },
            onError = { error ->
                uiCallback.onQuestionsDeleteError(error)
            },
        )
    }

    /**
     * 进入编辑模式
     */
    fun enterEditMode() {
        if (viewModel.uiState.value.isEditMode) return
        viewModel.setEditMode(true)
    }

    /**
     * 退出编辑模式
     */
    fun exitEditMode() {
        if (!viewModel.uiState.value.isEditMode) return
        viewModel.setEditMode(false)
    }

    /**
     * 更新编辑工具栏
     *
     * @param selectedCount 选中数量
     * @param totalCount 总数量
     */
    fun updateEditToolbar(
        selectedCount: Int,
        totalCount: Int,
    ) {
        val isAllSelected = selectedCount == totalCount && totalCount > 0
        uiCallback.updateEditToolbar(selectedCount, totalCount, isAllSelected)
    }

    /**
     * 加载所有错题（带统计信息）
     *
     * @param onSuccess 成功回调，参数为题目列表和统计信息映射
     * @param onError 失败回调，参数为错误消息
     */
    fun loadAllQuestions(
        onSuccess: (List<QuestionAnswer>, Map<String, DictationWordStat>) -> Unit,
        onError: (String) -> Unit,
    ) {
        viewModel.viewModelScope.launch {
            // 确保默认用户存在并获取用户名
            val userName =
                when (val userResult = errorBookUseCase.ensureDefaultUser()) {
                    is com.smartstudy.core.AppResult.Success -> userResult.data.name
                    is com.smartstudy.core.AppResult.Failure -> {
                        onError(userResult.error.message)
                        return@launch
                    }
                }

            val result = errorBookUseCase.loadAllQuestionsWithAnswers(userName)

            when (result) {
                is com.smartstudy.core.AppResult.Success -> {
                    val questionAnswersWithStats = result.data

                    if (questionAnswersWithStats.isEmpty()) {
                        onError(activity.getString(R.string.error_book_no_saved_questions))
                        return@launch
                    }

                    // 创建题目和答案列表和统计信息映射
                    val questionAnswers = questionAnswersWithStats.map { it.first }
                    val statsMap =
                        questionAnswersWithStats.associateBy { it.first.question }
                            .mapValues { (_, pair) ->
                                val stats = pair.second
                                val errorRate =
                                    if (stats.totalCount > 0) {
                                        stats.errorCount.toFloat() / stats.totalCount.toFloat()
                                    } else {
                                        0f
                                    }
                                DictationWordStat(
                                    word = stats.question,
                                    errorCount = stats.errorCount,
                                    totalCount = stats.totalCount,
                                    errorRate = errorRate,
                                    chineseTranslation = "",
                                    lastDictationTimestamp = 0L,
                                )
                            }

                    onSuccess(questionAnswers, statsMap)
                }
                is com.smartstudy.core.AppResult.Failure -> {
                    onError(result.error.message)
                }
            }
        }
    }

    /**
     * 保存题目列表
     *
     * @param questions 要保存的题目列表
     * @param onSuccess 成功回调
     * @param onError 失败回调，参数为错误消息
     */
    fun saveQuestions(
        questions: List<QuestionAnswer>,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        viewModel.viewModelScope.launch {
            // 确保默认用户存在并获取用户名
            val userName =
                when (val userResult = errorBookUseCase.ensureDefaultUser()) {
                    is com.smartstudy.core.AppResult.Success -> userResult.data.name
                    is com.smartstudy.core.AppResult.Failure -> {
                        onError(userResult.error.message)
                        return@launch
                    }
                }

            val result = errorBookUseCase.saveQuestionsWithAnswers(userName, questions)

            when (result) {
                is com.smartstudy.core.AppResult.Success -> {
                    onSuccess()
                }
                is com.smartstudy.core.AppResult.Failure -> {
                    onError(result.error.message)
                }
            }
        }
    }

    /**
     * 删除题目
     *
     * @param questions 要删除的题目列表
     * @param onSuccess 成功回调
     * @param onError 失败回调，参数为错误消息
     */
    fun deleteQuestions(
        questions: List<String>,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        viewModel.viewModelScope.launch {
            // 确保默认用户存在并获取用户名
            val userName =
                when (val userResult = errorBookUseCase.ensureDefaultUser()) {
                    is com.smartstudy.core.AppResult.Success -> userResult.data.name
                    is com.smartstudy.core.AppResult.Failure -> {
                        onError(userResult.error.message)
                        return@launch
                    }
                }

            val result = errorBookUseCase.deleteQuestions(userName, questions)

            when (result) {
                is com.smartstudy.core.AppResult.Success -> {
                    onSuccess()
                }
                is com.smartstudy.core.AppResult.Failure -> {
                    onError(result.error.message)
                }
            }
        }
    }

    /**
     * 更新题目
     *
     * @param oldQuestion 旧题目
     * @param newQuestion 新题目
     * @param onSuccess 成功回调
     * @param onError 失败回调，参数为错误消息
     */
    fun updateQuestion(
        oldQuestion: String,
        newQuestion: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        viewModel.viewModelScope.launch {
            // 确保默认用户存在并获取用户名
            val userName =
                when (val userResult = errorBookUseCase.ensureDefaultUser()) {
                    is com.smartstudy.core.AppResult.Success -> userResult.data.name
                    is com.smartstudy.core.AppResult.Failure -> {
                        onError(userResult.error.message)
                        return@launch
                    }
                }

            val result = errorBookUseCase.updateQuestion(userName, oldQuestion, newQuestion)

            when (result) {
                is com.smartstudy.core.AppResult.Success -> {
                    onSuccess()
                }
                is com.smartstudy.core.AppResult.Failure -> {
                    onError(result.error.message)
                }
            }
        }
    }

    /**
     * 更新答案
     *
     * @param question 题目
     * @param answer 新答案
     * @param onSuccess 成功回调
     * @param onError 失败回调，参数为错误消息
     */
    fun updateAnswer(
        question: String,
        answer: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        viewModel.viewModelScope.launch {
            // 确保默认用户存在并获取用户名
            val userName =
                when (val userResult = errorBookUseCase.ensureDefaultUser()) {
                    is com.smartstudy.core.AppResult.Success -> userResult.data.name
                    is com.smartstudy.core.AppResult.Failure -> {
                        onError(userResult.error.message)
                        return@launch
                    }
                }

            val result = errorBookUseCase.updateAnswer(userName, question, answer)

            when (result) {
                is com.smartstudy.core.AppResult.Success -> {
                    onSuccess()
                }
                is com.smartstudy.core.AppResult.Failure -> {
                    onError(result.error.message)
                }
            }
        }
    }

    /**
     * 批量保存编辑后的题目
     *
     * 包括删除、更新题目、更新答案、重新排序等操作
     *
     * @param adapter 题目适配器
     * @param questionsToDelete 要删除的题目列表
     * @param questionUpdateMap 题目更新映射（oldQuestion -> newQuestion）
     * @param onSuccess 成功回调
     * @param onError 失败回调，参数为错误消息
     */
    fun saveEditedQuestions(
        adapter: QuestionAdapter,
        questionsToDelete: Set<String>,
        questionUpdateMap: Map<String, String>,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        viewModel.viewModelScope.launch {
            // 确保默认用户存在并获取用户名
            val userName =
                when (val userResult = errorBookUseCase.ensureDefaultUser()) {
                    is com.smartstudy.core.AppResult.Success -> userResult.data.name
                    is com.smartstudy.core.AppResult.Failure -> {
                        onError(userResult.error.message)
                        return@launch
                    }
                }

            // 执行批量操作
            val deleteResult = deleteQuestionsInStorage(userName, questionsToDelete, questionUpdateMap)
            if (deleteResult is com.smartstudy.core.AppResult.Failure) {
                onError(deleteResult.error.message)
                return@launch
            }

            val updateQuestionResult = updateQuestionsInStorage(userName, adapter, questionUpdateMap)
            if (updateQuestionResult is com.smartstudy.core.AppResult.Failure) {
                onError(updateQuestionResult.error.message)
                return@launch
            }

            val updateAnswerResult = updateAnswersInStorage(userName, adapter, questionUpdateMap)
            if (updateAnswerResult is com.smartstudy.core.AppResult.Failure) {
                onError(updateAnswerResult.error.message)
                return@launch
            }

            val reorderResult = reorderQuestionsInStorage(userName, adapter)
            if (reorderResult is com.smartstudy.core.AppResult.Failure) {
                onError(reorderResult.error.message)
                return@launch
            }

            onSuccess()
        }
    }

    /**
     * 删除题目（在存储中）
     *
     * @param userName 用户名
     * @param questionsToDelete 要删除的题目列表
     * @param questionUpdateMap 题目更新映射（用于过滤已更新的题目）
     */
    private suspend fun deleteQuestionsInStorage(
        userName: String,
        questionsToDelete: Set<String>,
        questionUpdateMap: Map<String, String>,
    ): com.smartstudy.core.AppResult<Unit> {
        val toDelete = questionsToDelete.filter { !questionUpdateMap.containsKey(it) }
        return if (toDelete.isNotEmpty()) {
            errorBookUseCase.deleteQuestions(userName, toDelete)
        } else {
            com.smartstudy.core.AppResult.Success(Unit)
        }
    }

    /**
     * 更新题目（在存储中）
     *
     * @param userName 用户名
     * @param adapter 题目适配器
     * @param questionUpdateMap 题目更新映射（oldQuestion -> newQuestion）
     */
    private suspend fun updateQuestionsInStorage(
        userName: String,
        adapter: QuestionAdapter,
        questionUpdateMap: Map<String, String>,
    ): com.smartstudy.core.AppResult<Unit> {
        questionUpdateMap.forEach { (oldQuestion, newQuestion) ->
            if (oldQuestion != newQuestion) {
                Logger.d(TAG, "更新题目: $oldQuestion -> $newQuestion")
                val result = errorBookUseCase.updateQuestion(userName, oldQuestion, newQuestion)
                if (result is com.smartstudy.core.AppResult.Failure) {
                    return result
                }
                // 同步适配器中的题目
                syncQuestionInAdapter(adapter, oldQuestion, newQuestion)
            }
        }
        return com.smartstudy.core.AppResult.Success(Unit)
    }

    /**
     * 更新答案（在存储中）
     *
     * @param userName 用户名
     * @param adapter 题目适配器
     * @param questionUpdateMap 题目更新映射（用于判断题目是否已更新）
     */
    private suspend fun updateAnswersInStorage(
        userName: String,
        adapter: QuestionAdapter,
        questionUpdateMap: Map<String, String>,
    ): com.smartstudy.core.AppResult<Unit> {
        val allQuestionAnswers = adapter.getAllQuestionAnswers()
        val originalQuestionAnswers = adapter.getAllQuestionAnswersOriginal()

        for (qa in allQuestionAnswers) {
            val currentQuestion = qa.question
            val currentAnswer = qa.answer

            // 检查这个题目是否是从旧题目更新来的
            val oldQuestion = questionUpdateMap.entries.find { it.value == currentQuestion }?.key

            val shouldUpdate = when {
                oldQuestion != null -> {
                    // 这是从旧题目更新来的新题目
                    val original = originalQuestionAnswers.find { it.question == oldQuestion }
                    original == null || currentAnswer != original.answer
                }
                else -> {
                    // 这是没有更新的题目，检查答案是否改变
                    val original = originalQuestionAnswers.find { it.question == currentQuestion }
                    original != null && currentAnswer != original.answer
                }
            }

            if (shouldUpdate) {
                val logMessage = if (oldQuestion != null) "更新答案（题目已更新）" else "更新答案（题目未更新）"
                Logger.d(TAG, "$logMessage: $currentQuestion -> $currentAnswer")
                val result = errorBookUseCase.updateAnswer(userName, currentQuestion, currentAnswer)
                if (result is com.smartstudy.core.AppResult.Failure) {
                    return result
                }
            }

            // 同步更新适配器中的答案
            syncAnswerInAdapter(adapter, currentQuestion, currentAnswer)
        }
        return com.smartstudy.core.AppResult.Success(Unit)
    }

    /**
     * 重新排序题目（在存储中）
     *
     * @param userName 用户名
     * @param adapter 题目适配器
     */
    private suspend fun reorderQuestionsInStorage(
        userName: String,
        adapter: QuestionAdapter,
    ): com.smartstudy.core.AppResult<Unit> {
        val questionOrder = adapter.questionAnswers.map { it.question }
        return if (questionOrder.isNotEmpty()) {
            errorBookUseCase.reorderJsonByQuestionOrder(userName, questionOrder)
        } else {
            com.smartstudy.core.AppResult.Success(Unit)
        }
    }

    /**
     * 同步适配器中的题目
     */
    private fun syncQuestionInAdapter(
        adapter: QuestionAdapter,
        oldQuestion: String,
        newQuestion: String,
    ) {
        adapter.questionAnswers.forEachIndexed { index, qa ->
            if (qa.question == oldQuestion) {
                adapter.questionAnswers[index] =
                    QuestionAnswer(
                        newQuestion,
                        qa.answer,
                        qa.isCorrect,
                        qa.isAiGenerated,
                    )
            }
        }
    }

    /**
     * 同步适配器中的答案
     */
    private fun syncAnswerInAdapter(
        adapter: QuestionAdapter,
        currentQuestion: String,
        currentAnswer: String,
    ) {
        adapter.questionAnswers.forEachIndexed { idx, qaItem ->
            if (qaItem.question == currentQuestion) {
                adapter.questionAnswers[idx] =
                    QuestionAnswer(
                        currentQuestion,
                        currentAnswer,
                        qaItem.isCorrect,
                        qaItem.isAiGenerated,
                    )
            }
        }
    }
}
