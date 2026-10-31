package com.smartstudy.learningrecord

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartstudy.core.AppResult
import com.smartstudy.core.Logger
import com.smartstudy.core.edit.EditModeState
import com.smartstudy.learningrecord.repository.LearningRecordRepository
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
 * 学习记录ViewModel
 */
class LearningRecordViewModel(
    private val context: Context,
) : ViewModel(), KoinComponent {
    companion object {
        private const val TAG = "LearningRecordViewModel"
    }

    private val learningRecordRepository: LearningRecordRepository by inject { parametersOf(context) }
    private val userUseCase: UserUseCase by inject { parametersOf(context) }

    private val _uiState = MutableStateFlow(LearningRecordState())
    val uiState: StateFlow<LearningRecordState> = _uiState

    init {
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
     * 加载学习记录
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
                    learningRecordRepository.loadLearningRecordsByDateRange(
                        userName,
                        state.startDate,
                        state.endDate,
                    )
            ) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            recordGroups = result.data,
                        )
                    }
                }
                is AppResult.Failure -> {
                    Logger.e(TAG, "加载学习记录失败", result.error.cause)
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
     * 清除错误信息
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * 进入编辑模式
     */
    fun enterEditMode() {
        _uiState.update { it.copy(_isEditMode = true, selectedRecords = emptySet()) }
    }

    /**
     * 退出编辑模式
     */
    fun exitEditMode() {
        _uiState.update {
            it.copy(
                _isEditMode = false,
                selectedRecords = emptySet(),
                editedRecords = emptyMap(),
            )
        }
    }

    /**
     * 切换记录选中状态
     * @param recordId 记录的唯一标识符
     * @param isSelected 是否选中
     */
    fun toggleSelection(
        recordId: String,
        isSelected: Boolean,
    ) {
        _uiState.update { state ->
            val newSelectedRecords =
                if (isSelected) {
                    state.selectedRecords + recordId
                } else {
                    state.selectedRecords - recordId
                }
            state.copy(selectedRecords = newSelectedRecords)
        }
    }

    /**
     * 全选
     */
    fun selectAll() {
        _uiState.update { state ->
            val allRecordIds =
                state.recordGroups.flatMap { group ->
                    group.records.map { it.getUniqueId() }
                }.toSet()
            state.copy(selectedRecords = allRecordIds)
        }
    }

    /**
     * 取消全选
     */
    fun deselectAll() {
        _uiState.update { it.copy(selectedRecords = emptySet()) }
    }

    /**
     * 更新编辑后的记录
     * @param recordId 记录的唯一标识符
     * @param editedRecord 编辑后的记录
     */
    fun updateEditedRecord(
        recordId: String,
        editedRecord: LearningRecord,
    ) {
        _uiState.update { state ->
            state.copy(editedRecords = state.editedRecords + (recordId to editedRecord))
        }
    }

    /**
     * 删除单个记录
     * @param recordId 记录的唯一标识符
     */
    fun deleteRecord(recordId: String) {
        viewModelScope.launch {
            val currentUserResult = userUseCase.getCurrentUser()
            val userName =
                when (currentUserResult) {
                    is AppResult.Success -> currentUserResult.data?.name ?: "游客"
                    is AppResult.Failure -> "游客"
                }

            // 将唯一标识符转换为 (类型, 内容) 对
            val recordToDelete =
                LearningRecordType.values().firstOrNull { type ->
                    recordId.startsWith("${type.name}_")
                }?.let { type ->
                    val content = recordId.substring(type.name.length + 1) // +1 是为了跳过下划线
                    Pair(type, content)
                }

            if (recordToDelete == null) {
                Logger.w(TAG, "无法解析要删除的记录ID: $recordId")
                _uiState.update {
                    it.copy(error = context.getString(com.smartstudy.R.string.error_io))
                }
                return@launch
            }

            when (val result = learningRecordRepository.deleteLearningRecords(userName, listOf(recordToDelete))) {
                is AppResult.Success -> {
                    // 删除成功后，直接更新 ViewModel 中的列表，而不是重新加载
                    // 这样可以保持顺序不变
                    _uiState.update { currentState ->
                        val updatedGroups =
                            currentState.recordGroups.map { group ->
                                val updatedRecords =
                                    group.records.filterNot { record ->
                                        record.getUniqueId() == recordId
                                    }
                                group.copy(records = updatedRecords)
                            }.filter { it.records.isNotEmpty() } // 移除空的分组

                        currentState.copy(recordGroups = updatedGroups)
                    }
                }
                is AppResult.Failure -> {
                    Logger.e(TAG, "删除记录失败: $recordId", result.error.cause)
                    _uiState.update {
                        it.copy(error = result.error.message ?: context.getString(com.smartstudy.R.string.error_io))
                    }
                }
            }
        }
    }

    /**
     * 删除选中的记录（保留此方法以兼容旧代码，但不再使用）
     */
    fun deleteSelectedRecords() {
        val state = _uiState.value
        if (state.selectedRecords.isEmpty()) {
            Logger.w(TAG, "没有选中的记录，无法删除")
            return
        }

        viewModelScope.launch {
            val currentUserResult = userUseCase.getCurrentUser()
            val userName =
                when (currentUserResult) {
                    is AppResult.Success -> currentUserResult.data?.name ?: "游客"
                    is AppResult.Failure -> "游客"
                }

            // 将唯一标识符转换为 (类型, 内容) 对
            val recordsToDelete =
                state.selectedRecords.mapNotNull { recordId ->
                    // 解析 recordId: "${type}_${content}"
                    // 由于 type 可能包含下划线（如 CHINESE_DICTATION），需要从枚举名称匹配
                    LearningRecordType.values().firstOrNull { type ->
                        recordId.startsWith("${type.name}_")
                    }?.let { type ->
                        val content = recordId.substring(type.name.length + 1) // +1 是为了跳过下划线
                        Pair(type, content)
                    }
                }

            if (recordsToDelete.isEmpty()) {
                Logger.w(TAG, "无法解析要删除的记录ID")
                return@launch
            }

            when (val result = learningRecordRepository.deleteLearningRecords(userName, recordsToDelete)) {
                is AppResult.Success -> {
                    // 删除成功后，直接更新 ViewModel 中的列表，而不是重新加载
                    // 这样可以保持顺序不变
                    _uiState.update { currentState ->
                        val updatedGroups =
                            currentState.recordGroups.map { group ->
                                val updatedRecords =
                                    group.records.filterNot { record ->
                                        // 如果这个记录在要删除的列表中，则过滤掉
                                        state.selectedRecords.contains(record.getUniqueId())
                                    }
                                group.copy(records = updatedRecords)
                            }.filter { it.records.isNotEmpty() } // 移除空的分组

                        currentState.copy(
                            recordGroups = updatedGroups,
                            _isEditMode = false,
                            selectedRecords = emptySet(),
                            editedRecords = emptyMap(),
                        )
                    }
                }
                is AppResult.Failure -> {
                    Logger.e(TAG, "删除记录失败", result.error.cause)
                    _uiState.update {
                        it.copy(error = result.error.message ?: context.getString(com.smartstudy.R.string.error_io))
                    }
                }
            }
        }
    }

    /**
     * 批量更新记录
     * @param editedRecords 编辑后的记录映射（唯一标识符 -> 编辑后的记录）
     */
    fun updateRecords(editedRecords: Map<String, LearningRecord>) {
        if (editedRecords.isEmpty()) {
            Logger.w(TAG, "没有编辑的内容，无法保存")
            return
        }

        viewModelScope.launch {
            val currentUserResult = userUseCase.getCurrentUser()
            val userName =
                when (currentUserResult) {
                    is AppResult.Success -> currentUserResult.data?.name ?: "游客"
                    is AppResult.Failure -> "游客"
                }

            // 获取原始记录
            val state = _uiState.value
            val allRecords = state.recordGroups.flatMap { it.records }

            // 创建一个映射：旧记录对象 -> 新记录对象
            val recordUpdateMap = mutableMapOf<LearningRecord, LearningRecord>()

            editedRecords.forEach { (recordId, newRecord) ->
                // 查找原始记录
                val oldRecord = allRecords.find { it.getUniqueId() == recordId }
                if (oldRecord == null) {
                    Logger.w(TAG, "找不到原始记录: $recordId")
                    return@forEach
                }

                // 检查是否有变化
                if (oldRecord == newRecord) {
                    return@forEach
                }

                when (val result = learningRecordRepository.updateLearningRecord(userName, oldRecord, newRecord)) {
                    is AppResult.Success -> {
                        // 保存映射关系，用于后续更新列表
                        recordUpdateMap[oldRecord] = newRecord
                    }
                    is AppResult.Failure -> {
                        Logger.e(TAG, "更新记录失败: $recordId", result.error.cause)
                        _uiState.update {
                            it.copy(error = result.error.message ?: context.getString(com.smartstudy.R.string.error_io))
                        }
                        return@launch
                    }
                }
            }

            // 所有更新成功后，直接更新 ViewModel 中的列表，而不是重新加载
            // 这样可以保持顺序不变
            _uiState.update { currentState ->
                val updatedGroups =
                    currentState.recordGroups.map { group ->
                        val updatedRecords =
                            group.records.map { record ->
                                // 如果这个记录被编辑了，使用编辑后的记录
                                recordUpdateMap[record] ?: record
                            }
                        group.copy(records = updatedRecords)
                    }
                currentState.copy(
                    recordGroups = updatedGroups,
                    _isEditMode = false,
                    selectedRecords = emptySet(),
                    editedRecords = emptyMap(),
                )
            }
        }
    }
}

/**
 * 学习记录状态
 */
data class LearningRecordState(
    val startDate: Long = 0L,
    val endDate: Long = 0L,
    val recordGroups: List<LearningRecordGroup> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val _isEditMode: Boolean = false, // 使用下划线前缀避免与接口属性冲突
    val selectedRecords: Set<String> = emptySet(), // 存储唯一标识符
    val editedRecords: Map<String, LearningRecord> = emptyMap(), // 存储编辑后的记录（唯一标识符 -> 编辑后的记录）
) : EditModeState<String, LearningRecord> {
    // 显式 override 接口属性，委托给构造函数参数
    override val isEditMode: Boolean get() = this._isEditMode
    override val selectedItems: Set<String> get() = this.selectedRecords
    override val editedItems: Map<String, LearningRecord> get() = this.editedRecords
}
