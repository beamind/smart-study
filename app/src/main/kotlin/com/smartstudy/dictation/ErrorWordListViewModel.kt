package com.smartstudy.dictation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartstudy.core.AppResult
import com.smartstudy.core.Logger
import com.smartstudy.dictation.core.QuestionRecord
import com.smartstudy.dictation.repository.DictationRepository
import com.smartstudy.user.usecases.UserUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * 错题列表功能的 ViewModel
 * 管理错题列表相关的业务逻辑和状态
 *
 * 职责：
 * - 管理错题列表和状态
 * - 管理编辑模式和查看模式
 * - 管理用户信息
 * - 提供 UI 状态（StateFlow）
 *
 * 使用示例：
 * ```
 * val viewModel: ErrorWordListViewModel by viewModels { parametersOf(this) }
 * // 使用 StateFlow
 * lifecycleScope.launch {
 *     viewModel.uiState.collect { state ->
 *         // 处理状态变化
 *     }
 * }
 * ```
 *
 * @param dictationRepository 听写数据仓库
 * @param userUseCase 用户业务逻辑用例
 */
class ErrorWordListViewModel(
    private val dictationRepository: DictationRepository,
    private val userUseCase: UserUseCase,
) : ViewModel() {
    companion object {
        private const val TAG = "ErrorWordListViewModel"
    }

    // ==================== 统一状态管理（StateFlow）====================

    private val _uiState = MutableStateFlow(ErrorWordListState.initial())
    val uiState: StateFlow<ErrorWordListState> = _uiState.asStateFlow()

    /**
     * 初始化状态
     * @param type 类型（"chinese" 或 "english"）
     * @param userId 用户ID
     */
    fun initialize(
        type: String,
        userId: String,
    ) {
        viewModelScope.launch {
            val currentUserIdResult = userUseCase.getCurrentUserId()
            val currentUserId =
                when (currentUserIdResult) {
                    is AppResult.Success -> currentUserIdResult.data ?: ""
                    is AppResult.Failure -> ""
                }
            _uiState.update { state ->
                state.copy(
                    currentType = type,
                    currentUserId = userId.ifEmpty { currentUserId },
                )
            }
            loadErrorWords()
        }
    }

    /**
     * 加载错题列表
     */
    fun loadErrorWords() {
        val state = _uiState.value
        if (state.currentType.isEmpty()) {
            Logger.w(TAG, "类型为空，无法加载错题列表")
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            // 获取当前用户名
            val userName = getUserName(state.currentUserId)

            when (val result = dictationRepository.loadErrorWordStats(userName, state.currentType)) {
                is AppResult.Success -> {
                    val stats = result.data
                    _uiState.update {
                        it.copy(
                            errorWordStats = stats,
                            isLoading = false,
                            error = null,
                            isEmpty = stats.isEmpty(),
                        )
                    }
                }
                is AppResult.Failure -> {
                    Logger.e(TAG, "加载错题列表失败", result.error.cause)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = result.error.message ?: "加载失败",
                            isEmpty = true,
                        )
                    }
                }
            }
        }
    }

    /**
     * 进入编辑模式
     */
    fun enterEditMode() {
        _uiState.update {
            it.copy(_isEditMode = true)
        }
    }

    /**
     * 退出编辑模式
     */
    fun exitEditMode() {
        _uiState.update {
            it.copy(
                _isEditMode = false,
                selectedWords = emptySet(),
                editedTexts = emptyMap(),
            )
        }
    }

    /**
     * 切换单词选中状态
     * @param word 单词
     * @param isSelected 是否选中
     */
    fun toggleWordSelection(
        word: String,
        isSelected: Boolean,
    ) {
        _uiState.update { state ->
            val newSelectedWords =
                if (isSelected) {
                    state.selectedWords + word
                } else {
                    state.selectedWords - word
                }
            state.copy(selectedWords = newSelectedWords)
        }
    }

    /**
     * 全选/取消全选
     * @param allWords 所有单词列表
     */
    fun toggleSelectAll(allWords: List<String>) {
        _uiState.update { state ->
            val newSelectedWords =
                if (state.selectedWords.size == allWords.size) {
                    emptySet()
                } else {
                    allWords.toSet()
                }
            state.copy(selectedWords = newSelectedWords)
        }
    }

    /**
     * 删除选中的单词
     */
    fun deleteSelectedWords() {
        val state = _uiState.value
        if (state.selectedWords.isEmpty()) {
            Logger.w(TAG, "没有选中的单词，无法删除")
            return
        }

        viewModelScope.launch {
            val userName = getUserName(state.currentUserId)

            when (val result = dictationRepository.deleteWords(userName, state.currentType, state.selectedWords.toList())) {
                is AppResult.Success -> {
                    // 删除成功后统一处理：退出编辑模式、重新加载列表
                    exitEditMode()
                    loadErrorWords()
                }
                is AppResult.Failure -> {
                    Logger.e(TAG, "删除错题失败", result.error.cause)
                    _uiState.update {
                        it.copy(error = result.error.message ?: "删除失败")
                    }
                }
            }
        }
    }

    /**
     * 更新编辑后的文本
     * @param word 原始单词
     * @param newWord 新单词
     * @param newTranslation 新翻译
     */
    fun updateEditedText(
        word: String,
        newWord: String,
        newTranslation: String,
    ) {
        _uiState.update { state ->
            val currentPair = state.editedTexts[word]
            val updatedPair =
                Pair(
                    newWord.ifEmpty { currentPair?.first ?: word },
                    newTranslation.ifEmpty { currentPair?.second ?: "" },
                )
            state.copy(
                editedTexts = state.editedTexts + (word to updatedPair),
            )
        }
    }

    /**
     * 批量更新编辑后的文本
     * @param editedTexts 编辑后的文本映射（原始单词 -> (新单词, 新翻译)）
     */
    fun updateEditedTexts(editedTexts: Map<String, Pair<String, String>>) {
        _uiState.update { it.copy(editedTexts = editedTexts) }
    }

    /**
     * 保存编辑后的单词
     * 使用 ViewModel 中存储的 editedTexts
     */
    fun saveEditedWords() {
        val state = _uiState.value
        val editedTexts = state.editedTexts
        if (editedTexts.isEmpty()) {
            Logger.w(TAG, "没有编辑的内容，无法保存")
            return
        }
        viewModelScope.launch {
            try {
                val userName = getUserName(state.currentUserId)

                editedTexts.forEach { (oldWord, newData) ->
                    val (newWord, newTranslation) = newData
                    when (val result = dictationRepository.updateWord(userName, state.currentType, oldWord, newWord, newTranslation)) {
                        is AppResult.Success -> {
                            // 继续处理下一个
                        }
                        is AppResult.Failure -> {
                            Logger.e(TAG, "更新单词失败: $oldWord -> $newWord", result.error.cause)
                            _uiState.update {
                                it.copy(error = result.error.message ?: "更新失败")
                            }
                            return@launch
                        }
                    }
                }

                // 保存成功后统一处理：退出编辑模式、重新加载列表
                exitEditMode()
                loadErrorWords()
            } catch (e: IOException) {
                Logger.e(TAG, "IO错误，保存编辑后的单词失败", e)
                _uiState.update {
                    it.copy(error = e.message ?: "IO错误")
                }
            } catch (e: SecurityException) {
                Logger.e(TAG, "权限不足，保存编辑后的单词失败", e)
                _uiState.update {
                    it.copy(error = e.message ?: "权限不足")
                }
            }
        }
    }

    /**
     * 手动添加错题
     * @param words 单词列表
     */
    fun addWordsManually(words: List<String>) {
        if (words.isEmpty()) {
            Logger.w(TAG, "单词列表为空，无法添加")
            return
        }

        val state = _uiState.value
        viewModelScope.launch {
            try {
                val userName = getUserName(state.currentUserId)

                // 为每个单词创建错误记录
                val questions =
                    words.map { word ->
                        QuestionRecord(
                            word = word,
                            isCorrect = false,
                            timestamp = System.currentTimeMillis(),
                            chineseTranslation = "",
                        )
                    }

                when (val result = dictationRepository.saveQuestions(userName, state.currentType, questions)) {
                    is AppResult.Success -> {
                        // 添加成功
                    }
                    is AppResult.Failure -> {
                        Logger.e(TAG, "添加错题失败", result.error.cause)
                        _uiState.update {
                            it.copy(error = result.error.message ?: "添加失败")
                        }
                        return@launch
                    }
                }

                // 添加成功后重新加载列表
                loadErrorWords()
            } catch (e: IOException) {
                Logger.e(TAG, "IO错误，添加错题失败", e)
                _uiState.update {
                    it.copy(error = e.message ?: "IO错误")
                }
            } catch (e: SecurityException) {
                Logger.e(TAG, "权限不足，添加错题失败", e)
                _uiState.update {
                    it.copy(error = e.message ?: "权限不足")
                }
            }
        }
    }

    /**
     * 清除错误信息
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * 获取用户名
     * @param userId 用户ID
     * @return 用户名
     */
    private suspend fun getUserName(userId: String): String {
        // 注意：这里返回的是用户名，不是显示文本，所以不需要使用字符串资源
        // "游客"是默认用户名，不是显示文本
        return if (userId.isNotEmpty()) {
            when (val result = userUseCase.getAllUsers()) {
                is AppResult.Success -> result.data.find { it.id == userId }?.name ?: "游客"
                is AppResult.Failure -> "游客"
            }
        } else {
            when (val result = userUseCase.getCurrentUser()) {
                is AppResult.Success -> result.data?.name ?: "游客"
                is AppResult.Failure -> "游客"
            }
        }
    }
}
