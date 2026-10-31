package com.smartstudy.english

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartstudy.core.AppResult
import com.smartstudy.core.Logger
import com.smartstudy.dictation.core.ImageUnderstandingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 英语错题本功能的 ViewModel
 * 管理错题本相关的UI状态和状态管理
 *
 * 职责：
 * - 管理错题列表和状态
 * - 管理编辑模式和查看模式
 * - 管理练习状态
 * - 管理用户信息
 * - 提供 UI 状态（StateFlow，推荐使用）
 *
 * 注意：
 * - 此 ViewModel 只负责状态管理，不包含业务逻辑
 * - 业务逻辑应在 ErrorBookUseCase 中处理
 *
 * 使用示例：
 * ```
 * val viewModel: ErrorBookViewModel by viewModels()
 * // 使用 StateFlow
 * lifecycleScope.launch {
 *     viewModel.uiState.collect { state ->
 *         // 处理状态变化
 *     }
 * }
 * ```
 *
 * @param errorBookUseCase 错题本业务逻辑用例
 * @param imageUnderstandingService 图片理解服务
 * @param questionGenerationService 题目生成服务
 */
class ErrorBookViewModel(
    private val errorBookUseCase: com.smartstudy.english.usecases.ErrorBookUseCase,
    private val imageUnderstandingService: ImageUnderstandingService,
    private val questionGenerationService: QuestionGenerationService,
) : ViewModel() {
    // ==================== 统一状态管理（StateFlow）====================

    private val _uiState = MutableStateFlow(ErrorBookState.initial())
    val uiState: StateFlow<ErrorBookState> = _uiState.asStateFlow()

    init {
        // 初始化当前用户
        updateCurrentUser()
    }

    /**
     * 更新当前用户信息
     */
    fun updateCurrentUser() {
        viewModelScope.launch {
            when (val result = errorBookUseCase.ensureDefaultUser()) {
                is AppResult.Success -> {
                    val currentUser = result.data
                    _uiState.update {
                        it.copy(
                            currentUserId = currentUser.id,
                            currentUserName = currentUser.name,
                        )
                    }
                }
                is AppResult.Failure -> {
                    // 用户获取失败，使用默认值或记录错误
                    _uiState.update {
                        it.copy(
                            currentUserId = "",
                            currentUserName = "游客",
                        )
                    }
                }
            }
        }
    }

    /**
     * 加载所有错题（带统计信息）
     */
    fun loadAllQuestions() {
        viewModelScope.launch {
            val userName = _uiState.value.currentUserName
            if (userName.isBlank()) return@launch

            _uiState.update { it.copy(isLoadingQuestions = true, loadQuestionsError = null) }

            val result = errorBookUseCase.loadAllQuestionsWithAnswers(userName)

            _uiState.update { it.copy(isLoadingQuestions = false) }

            when (result) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            questionsWithStats = result.data,
                            questions = result.data.map { it.first },
                            loadQuestionsError = null,
                        )
                    }
                }
                is AppResult.Failure -> {
                    _uiState.update {
                        it.copy(loadQuestionsError = result.error.message)
                    }
                }
            }
        }
    }

    /**
     * 设置编辑模式
     */
    fun setEditMode(editMode: Boolean) {
        if (editMode) {
            _uiState.update { it.copy(_isEditMode = true) }
        } else {
            // 退出编辑模式时清空编辑状态
            _uiState.update { it.resetEditMode() }
        }
    }

    /**
     * 添加题目更新映射
     */
    fun addQuestionUpdate(
        oldQuestion: String,
        newQuestion: String,
    ) {
        _uiState.update { state ->
            val currentMap = state.questionUpdateMap.toMutableMap()
            currentMap[oldQuestion] = newQuestion
            state.copy(questionUpdateMap = currentMap)
        }
    }

    /**
     * 添加要删除的题目
     */
    fun addQuestionToDelete(question: String) {
        _uiState.update { state ->
            val currentSet = state.questionsToDelete.toMutableSet()
            currentSet.add(question)
            state.copy(questionsToDelete = currentSet)
        }
    }

    /**
     * 清除题目更新映射
     */
    fun clearQuestionUpdateMap() {
        _uiState.update { it.copy(questionUpdateMap = emptyMap()) }
    }

    /**
     * 清除要删除的题目列表
     */
    fun clearQuestionsToDelete() {
        _uiState.update { it.copy(questionsToDelete = emptySet()) }
    }

    /**
     * 设置查看模式
     */
    fun setViewMode(viewMode: Boolean) {
        _uiState.update { it.copy(isViewMode = viewMode) }
    }

    /**
     * 设置举一反三模式
     */
    fun setVariationMode(variationMode: Boolean) {
        _uiState.update { it.copy(isVariationMode = variationMode) }
    }

    /**
     * 设置练习激活状态
     */
    fun setPracticeActive(active: Boolean) {
        _uiState.update { it.copy(isPracticeActive = active) }
    }

    /**
     * 设置等待开始状态
     */
    fun setWaitingForStart(waiting: Boolean) {
        _uiState.update { it.copy(isWaitingForStart = waiting) }
    }

    /**
     * 更新当前题目索引
     */
    fun updateQuestionIndex(index: Int) {
        _uiState.update { it.copy(currentQuestionIndex = index) }
    }

    /**
     * 增加题目索引
     */
    fun incrementQuestionIndex() {
        _uiState.update { state ->
            state.copy(currentQuestionIndex = state.currentQuestionIndex + 1)
        }
    }

    /**
     * 重置题目索引
     */
    fun resetQuestionIndex() {
        _uiState.update { it.copy(currentQuestionIndex = 0) }
    }

    /**
     * 设置练习题目列表
     */
    fun setPracticeQuestions(questions: List<QuestionAnswer>) {
        _uiState.update {
            it.copy(
                practiceQuestions = questions,
                currentQuestionIndex = 0,
            )
        }
    }

    /**
     * 设置练习提交状态
     */
    fun setPracticeSubmitted(submitted: Boolean) {
        _uiState.update { it.copy(isPracticeSubmitted = submitted) }
    }

    /**
     * 重置练习状态
     */
    fun resetPracticeState() {
        _uiState.update { it.resetPracticeState() }
    }

    /**
     * 设置图片处理状态
     */
    fun setProcessingImage(processing: Boolean) {
        _uiState.update { it.copy(isProcessingImage = processing) }
    }

    /**
     * 设置图片识别错误
     */
    fun setImageRecognitionError(error: String?) {
        _uiState.update { it.copy(imageRecognitionError = error) }
    }

    /**
     * 清除图片识别错误
     */
    fun clearImageRecognitionError() {
        _uiState.update { it.clearImageRecognitionError() }
    }

    /**
     * 保存题目列表
     */
    fun saveQuestions(questions: List<QuestionAnswer>) {
        viewModelScope.launch {
            val userName = _uiState.value.currentUserName
            if (userName.isBlank()) return@launch

            val result = errorBookUseCase.saveQuestionsWithAnswers(userName, questions)
            _uiState.update { it.copy(saveQuestionsResult = result) }
        }
    }

    /**
     * 删除题目
     */
    fun deleteQuestions(questions: List<String>) {
        viewModelScope.launch {
            val userName = _uiState.value.currentUserName
            if (userName.isBlank()) return@launch

            val result = errorBookUseCase.deleteQuestions(userName, questions)
            _uiState.update { it.copy(deleteQuestionsResult = result) }
        }
    }

    /**
     * 获取当前用户名称（同步方法，用于需要立即获取的场景）
     */
    fun getCurrentUserNameSync(): String? = _uiState.value.currentUserName

    /**
     * 获取题目更新映射（同步方法）
     */
    fun getQuestionUpdateMapSync(): Map<String, String> = _uiState.value.questionUpdateMap

    /**
     * 获取要删除的题目列表（同步方法）
     */
    fun getQuestionsToDeleteSync(): Set<String> = _uiState.value.questionsToDelete

    /**
     * 批量更新题目更新映射
     */
    fun updateQuestionMap(updates: Map<String, String>) {
        _uiState.update { state ->
            val currentMap = state.questionUpdateMap.toMutableMap()
            currentMap.putAll(updates)
            state.copy(questionUpdateMap = currentMap)
        }
    }

    /**
     * 批量添加要删除的题目
     */
    fun addQuestionsToDelete(questions: Set<String>) {
        _uiState.update { state ->
            val currentSet = state.questionsToDelete.toMutableSet()
            currentSet.addAll(questions)
            state.copy(questionsToDelete = currentSet)
        }
    }

    /**
     * 移除题目更新映射中的条目
     */
    fun removeQuestionUpdate(oldQuestion: String) {
        _uiState.update { state ->
            val currentMap = state.questionUpdateMap.toMutableMap()
            currentMap.remove(oldQuestion)
            state.copy(questionUpdateMap = currentMap)
        }
    }

    /**
     * 移除要删除的题目
     */
    fun removeQuestionToDelete(question: String) {
        _uiState.update { state ->
            val currentSet = state.questionsToDelete.toMutableSet()
            currentSet.remove(question)
            state.copy(questionsToDelete = currentSet)
        }
    }

    /**
     * 添加选中的题目
     */
    fun addSelectedQuestion(question: String) {
        _uiState.update { state ->
            val currentSet = state.selectedQuestions.toMutableSet()
            currentSet.add(question)
            state.copy(selectedQuestions = currentSet)
        }
    }

    /**
     * 移除选中的题目
     */
    fun removeSelectedQuestion(question: String) {
        _uiState.update { state ->
            val currentSet = state.selectedQuestions.toMutableSet()
            currentSet.remove(question)
            state.copy(selectedQuestions = currentSet)
        }
    }

    /**
     * 切换题目选中状态
     */
    fun toggleQuestionSelection(question: String) {
        _uiState.update { state ->
            val currentSet = state.selectedQuestions.toMutableSet()
            if (currentSet.contains(question)) {
                currentSet.remove(question)
            } else {
                currentSet.add(question)
            }
            state.copy(selectedQuestions = currentSet)
        }
    }

    /**
     * 设置选中的题目集合
     */
    fun setSelectedQuestions(questions: Set<String>) {
        _uiState.update { it.copy(selectedQuestions = questions) }
    }

    /**
     * 清除所有选中的题目
     */
    fun clearSelectedQuestions() {
        _uiState.update { it.copy(selectedQuestions = emptySet()) }
    }

    /**
     * 获取选中的题目集合（同步方法）
     */
    fun getSelectedQuestionsSync(): Set<String> = _uiState.value.selectedQuestions

    /**
     * 获取当前题目索引（同步方法）
     */
    fun getCurrentQuestionIndexSync(): Int = _uiState.value.currentQuestionIndex

    /**
     * 获取练习题目列表（同步方法）
     */
    fun getPracticeQuestionsSync(): List<QuestionAnswer> = _uiState.value.practiceQuestions

    /**
     * 获取当前题目（同步方法）
     */
    fun getCurrentQuestion(): QuestionAnswer? {
        val index = getCurrentQuestionIndexSync()
        val questions = getPracticeQuestionsSync()
        return if (index < questions.size) questions[index] else null
    }

    /**
     * 设置选中的答案位置集合
     */
    fun setSelectedAnswerPositions(positions: Set<Int>) {
        _uiState.update { it.copy(selectedAnswerPositions = positions) }
    }

    /**
     * 设置答案复选框是否可用
     */
    fun setAnswerCheckboxesEnabled(enabled: Boolean) {
        _uiState.update { it.copy(isAnswerCheckboxesEnabled = enabled) }
    }

    /**
     * 检查是否还有下一题
     */
    fun hasNextQuestion(): Boolean {
        val index = getCurrentQuestionIndexSync()
        val questions = getPracticeQuestionsSync()
        return index < questions.size - 1
    }

    /**
     * 检查是否练习完成
     */
    fun isPracticeComplete(): Boolean {
        val index = getCurrentQuestionIndexSync()
        val questions = getPracticeQuestionsSync()
        return index >= questions.size
    }

    /**
     * 优化图片（在后台线程执行）
     * @param bitmap 原始图片
     * @param onSuccess 成功回调，返回优化后的图片
     * @param onError 错误回调
     */
    fun optimizeImage(
        bitmap: Bitmap,
        onSuccess: (Bitmap) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val optimizedBitmap = com.smartstudy.dictation.core.ImageProcessingHelper.optimizeBitmapForRecognition(bitmap)
                withContext(Dispatchers.Main) {
                    onSuccess(optimizedBitmap)
                }
            } catch (e: OutOfMemoryError) {
                Logger.e(TAG, "图片优化失败：内存不足", e)
                withContext(Dispatchers.Main) {
                    onError(e)
                }
            } catch (e: IllegalArgumentException) {
                Logger.e(TAG, "图片优化失败：参数错误", e)
                withContext(Dispatchers.Main) {
                    onError(e)
                }
            } catch (e: Exception) {
                Logger.e(TAG, "图片优化失败：未知错误", e)
                withContext(Dispatchers.Main) {
                    onError(e)
                }
            }
        }
    }

    companion object {
        private const val TAG = "ErrorBookViewModel"
    }
}
