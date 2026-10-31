package com.smartstudy.dictation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartstudy.core.AppResult
import com.smartstudy.core.Logger
import com.smartstudy.dictation.core.DictationWordStat
import com.smartstudy.dictation.repository.DictationRepository
import com.smartstudy.user.usecases.UserUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.parameter.parametersOf
import java.util.Calendar

/**
 * 听写记录ViewModel
 */
class DictationRecordViewModel(
    private val context: Context,
) : ViewModel(), KoinComponent {
    companion object {
        private const val TAG = "DictationRecordViewModel"
    }

    private val dictationRepository: DictationRepository by inject { parametersOf(context) }
    private val userUseCase: UserUseCase by inject { parametersOf(context) }

    private val _uiState = MutableStateFlow(DictationRecordState())
    val uiState: StateFlow<DictationRecordState> = _uiState

    /**
     * 初始化
     * @param type 听写类型（"chinese" 或 "english"）
     * @param userId 用户ID
     */
    fun initialize(
        type: String,
        userId: String,
    ) {
        _uiState.update { it.copy(currentType = type, currentUserId = userId) }
        // 设置默认日期为当天
        val today = Calendar.getInstance()
        val startOfDay =
            Calendar.getInstance().apply {
                timeInMillis = today.timeInMillis
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
        val endOfDay =
            Calendar.getInstance().apply {
                timeInMillis = today.timeInMillis
                set(Calendar.HOUR_OF_DAY, 23)
                set(Calendar.MINUTE, 59)
                set(Calendar.SECOND, 59)
                set(Calendar.MILLISECOND, 999)
            }
        _uiState.update {
            it.copy(
                startDate = startOfDay.timeInMillis,
                endDate = endOfDay.timeInMillis,
            )
        }
        // 加载记录
        loadRecords()
    }

    /**
     * 设置开始日期
     */
    fun setStartDate(timestamp: Long) {
        _uiState.update { it.copy(startDate = timestamp) }
    }

    /**
     * 设置结束日期
     */
    fun setEndDate(timestamp: Long) {
        _uiState.update { it.copy(endDate = timestamp) }
    }

    /**
     * 加载听写记录
     */
    fun loadRecords() {
        viewModelScope.launch {
            val state = _uiState.value
            _uiState.update { it.copy(isLoading = true, error = null) }

            val currentUserResult = userUseCase.getCurrentUser()
            val userName =
                when (currentUserResult) {
                    is AppResult.Success -> currentUserResult.data?.name ?: "游客"
                    is AppResult.Failure -> "游客"
                }

            when (
                val result =
                    dictationRepository.loadDictationRecordsByDateRange(
                        userName,
                        state.currentType,
                        state.startDate,
                        state.endDate,
                    )
            ) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            records = result.data,
                        )
                    }
                }
                is AppResult.Failure -> {
                    Logger.e(TAG, "加载听写记录失败", result.error.cause)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = result.error.message ?: context.getString(com.smartstudy.R.string.error_io),
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
        _uiState.update { it.copy(_isEditMode = true, selectedWords = emptySet()) }
    }

    /**
     * 退出编辑模式
     */
    fun exitEditMode() {
        _uiState.update {
            it.copy(
                _isEditMode = false,
                selectedWords = emptySet(),
                editedWords = emptyMap(),
            )
        }
    }

    /**
     * 更新编辑后的单词
     * @param oldWord 旧单词
     * @param newWord 新单词
     */
    fun updateEditedWord(
        oldWord: String,
        newWord: String,
    ) {
        _uiState.update { state ->
            if (newWord.isBlank() || newWord == oldWord) {
                // 如果新词为空或与旧词相同，从编辑映射中移除
                state.copy(editedWords = state.editedWords - oldWord)
            } else {
                // 更新编辑映射
                state.copy(editedWords = state.editedWords + (oldWord to newWord))
            }
        }
    }

    /**
     * 切换词语选中状态
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
     * 全选
     */
    fun selectAll() {
        _uiState.update { state ->
            val allWords = state.records.map { it.word }.toSet()
            state.copy(selectedWords = allWords)
        }
    }

    /**
     * 取消全选
     */
    fun deselectAll() {
        _uiState.update { it.copy(selectedWords = emptySet()) }
    }

    /**
     * 清除错误信息
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * 获取用户名
     */
    private suspend fun getUserName(): String {
        val currentUserResult = userUseCase.getCurrentUser()
        return when (currentUserResult) {
            is AppResult.Success -> currentUserResult.data?.name ?: "游客"
            is AppResult.Failure -> "游客"
        }
    }

    /**
     * 更新单词
     * @param oldWord 旧单词
     * @param newWord 新单词
     */
    fun updateWord(
        oldWord: String,
        newWord: String,
    ) {
        viewModelScope.launch {
            val state = _uiState.value
            if (oldWord == newWord || newWord.isBlank()) {
                return@launch
            }

            val userName = getUserName()

            when (val result = dictationRepository.updateWord(userName, state.currentType, oldWord, newWord)) {
                is AppResult.Success -> {
                    // 更新成功后统一处理：退出编辑模式、重新加载列表
                    exitEditMode()
                    loadRecords()
                }
                is AppResult.Failure -> {
                    Logger.e(TAG, "更新单词失败: $oldWord -> $newWord", result.error.cause)
                    _uiState.update {
                        it.copy(error = result.error.message ?: context.getString(com.smartstudy.R.string.error_io))
                    }
                }
            }
        }
    }

    /**
     * 批量更新单词
     * @param editedWords 编辑后的单词映射（旧词 -> 新词）
     */
    fun updateWords(editedWords: Map<String, String>) {
        if (editedWords.isEmpty()) {
            Logger.w(TAG, "没有编辑的内容，无法保存")
            return
        }

        viewModelScope.launch {
            val state = _uiState.value
            val userName = getUserName()

            editedWords.forEach { (oldWord, newWord) ->
                if (oldWord == newWord || newWord.isBlank()) {
                    return@forEach
                }

                when (val result = dictationRepository.updateWord(userName, state.currentType, oldWord, newWord)) {
                    is AppResult.Success -> {
                        // 继续处理下一个
                    }
                    is AppResult.Failure -> {
                        Logger.e(TAG, "更新单词失败: $oldWord -> $newWord", result.error.cause)
                        _uiState.update {
                            it.copy(error = result.error.message ?: context.getString(com.smartstudy.R.string.error_io))
                        }
                        return@launch
                    }
                }
            }

            // 所有更新成功后统一处理：退出编辑模式、重新加载列表
            exitEditMode()
            loadRecords()
        }
    }

    /**
     * 删除选中的词语
     */
    fun deleteSelectedWords() {
        val state = _uiState.value
        if (state.selectedWords.isEmpty()) {
            Logger.w(TAG, "没有选中的单词，无法删除")
            return
        }

        viewModelScope.launch {
            val userName = getUserName()

            when (val result = dictationRepository.deleteWords(userName, state.currentType, state.selectedWords.toList())) {
                is AppResult.Success -> {
                    // 删除成功后统一处理：退出编辑模式、重新加载列表
                    exitEditMode()
                    loadRecords()
                }
                is AppResult.Failure -> {
                    Logger.e(TAG, "删除词语失败", result.error.cause)
                    _uiState.update {
                        it.copy(error = result.error.message ?: context.getString(com.smartstudy.R.string.error_io))
                    }
                }
            }
        }
    }
}

/**
 * 听写记录状态
 */
data class DictationRecordState(
    val currentType: String = "chinese",
    val currentUserId: String = "",
    val startDate: Long = 0L,
    val endDate: Long = 0L,
    val records: List<DictationWordStat> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val _isEditMode: Boolean = false, // 使用下划线前缀避免与接口属性冲突
    val selectedWords: Set<String> = emptySet(),
    val editedWords: Map<String, String> = emptyMap(), // 编辑后的单词映射（旧词 -> 新词）
) : com.smartstudy.core.edit.EditModeState<String, String> {
    // 显式 override 接口属性，委托给构造函数参数
    override val isEditMode: Boolean get() = this._isEditMode
    override val selectedItems: Set<String> get() = this.selectedWords
    override val editedItems: Map<String, String> get() = this.editedWords
}
