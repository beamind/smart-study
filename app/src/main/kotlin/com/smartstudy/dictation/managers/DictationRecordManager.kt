package com.smartstudy.dictation.managers

import androidx.appcompat.app.AppCompatActivity
import com.smartstudy.core.edit.BaseEditModeManager
import com.smartstudy.dictation.DictationRecordState
import com.smartstudy.dictation.DictationRecordViewModel

/**
 * 听写记录管理器
 *
 * 负责管理听写记录的业务逻辑，将业务逻辑从Activity中分离出来。
 * 继承 BaseEditModeManager 以复用通用的编辑流程逻辑。
 *
 * @param activity Activity实例
 * @param viewModel 听写记录ViewModel
 */
class DictationRecordManager(
    activity: AppCompatActivity,
    private val viewModel: DictationRecordViewModel,
) : BaseEditModeManager<String, String, DictationRecordState>(
        activity = activity,
    ) {
    override fun getCurrentState(): DictationRecordState {
        return viewModel.uiState.value
    }

    override fun filterChangedItems(editedItems: Map<String, String>): Map<String, String> {
        // 过滤出真正有变化的编辑（新词不为空且与旧词不同）
        return editedItems.filter { (oldWord, newWord) ->
            newWord.isNotBlank() && newWord != oldWord
        }
    }

    override fun executeDelete(selectedItems: Set<String>) {
        // 删除成功后会在 ViewModel 中统一处理退出编辑模式和重新加载
        viewModel.deleteSelectedWords()
    }

    override fun executeSave(changedItems: Map<String, String>) {
        // 保存编辑后的单词（异步操作，保存成功后会在 ViewModel 中统一处理退出编辑模式和重新加载）
        viewModel.updateWords(changedItems)
    }

    override fun updateEditToolbar(
        selectedCount: Int,
        totalCount: Int,
    ) {
        // 这个方法由 Activity 处理，这里不需要实现
    }
}
