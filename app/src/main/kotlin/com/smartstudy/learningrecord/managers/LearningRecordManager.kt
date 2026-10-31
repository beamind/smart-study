package com.smartstudy.learningrecord.managers

import androidx.appcompat.app.AppCompatActivity
import com.smartstudy.core.edit.BaseEditModeManager
import com.smartstudy.learningrecord.LearningRecord
import com.smartstudy.learningrecord.LearningRecordState
import com.smartstudy.learningrecord.LearningRecordViewModel

/**
 * 学习记录管理器
 *
 * 负责管理学习记录的业务逻辑，将业务逻辑从Activity中分离出来。
 * 继承 BaseEditModeManager 以复用通用的编辑流程逻辑。
 *
 * @param activity Activity实例
 * @param viewModel 学习记录ViewModel
 */
class LearningRecordManager(
    activity: AppCompatActivity,
    private val viewModel: LearningRecordViewModel,
) : BaseEditModeManager<String, LearningRecord, LearningRecordState>(
        activity = activity,
    ) {
    // 添加 adapter 引用，用于获取编辑后的内容
    var adapter: com.smartstudy.learningrecord.LearningRecordAdapter? = null

    override fun getCurrentState(): LearningRecordState {
        return viewModel.uiState.value
    }

    override fun filterChangedItems(editedItems: Map<String, LearningRecord>): Map<String, LearningRecord> {
        // 过滤出真正有变化的编辑（记录内容有变化）
        val state = getCurrentState()
        val allRecords = state.recordGroups.flatMap { it.records }

        return editedItems.filter { (recordId, editedRecord) ->
            val originalRecord = allRecords.find { it.getUniqueId() == recordId }
            originalRecord != null && originalRecord != editedRecord
        }
    }

    override fun executeDelete(selectedItems: Set<String>) {
        // 删除成功后会在 ViewModel 中统一处理退出编辑模式和重新加载
        viewModel.deleteSelectedRecords()
    }

    override fun saveEditedItems() {
        // 从 Adapter 中获取所有编辑后的内容
        val editedRecords = adapter?.getEditedRecords() ?: emptyMap()

        // 统一验证：检查是否有编辑内容
        if (!validateChanges(editedRecords)) {
            return
        }

        // 过滤出真正有变化的编辑
        val changedItems = filterChangedItems(editedRecords)

        if (changedItems.isEmpty()) {
            com.smartstudy.utils.ToastHelper.showShort(activity, getNoChangesMessageResId())
            return
        }

        // 执行保存
        executeSave(changedItems)
    }

    override fun executeSave(changedItems: Map<String, LearningRecord>) {
        // 保存编辑后的记录（异步操作，保存成功后会在 ViewModel 中统一处理退出编辑模式和重新加载）
        viewModel.updateRecords(changedItems)
    }

    override fun updateEditToolbar(
        selectedCount: Int,
        totalCount: Int,
    ) {
        // 这个方法由 Activity 处理，这里不需要实现
    }
}
