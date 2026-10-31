package com.smartstudy.core.edit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

/**
 * 编辑模式 ViewModel 基类
 *
 * 提供编辑模式和选中项管理的基础方法，统一删除/保存后的处理流程。
 * 子类需要实现具体的删除和保存逻辑。
 *
 * 注意：由于 Kotlin data class 的限制，子类需要自己实现状态更新逻辑。
 * 基类提供通用的方法模板和统一的成功处理流程。
 */
abstract class BaseEditModeViewModel<Key, EditedValue> : ViewModel() {
    /**
     * 进入编辑模式
     * 子类需要实现具体的状态更新逻辑
     */
    abstract fun enterEditMode()

    /**
     * 退出编辑模式
     * 清空选中项和编辑内容
     * 子类需要实现具体的状态更新逻辑
     */
    abstract fun exitEditMode()

    /**
     * 切换选中状态
     * @param key 项的键
     * @param isSelected 是否选中
     * 子类需要实现具体的状态更新逻辑
     */
    abstract fun toggleSelection(
        key: Key,
        isSelected: Boolean,
    )

    /**
     * 全选
     * @param allKeys 所有项的键列表
     * 子类需要实现具体的状态更新逻辑
     */
    abstract fun selectAll(allKeys: List<Key>)

    /**
     * 取消全选
     * 子类需要实现具体的状态更新逻辑
     */
    abstract fun deselectAll()

    /**
     * 全选/取消全选切换
     * @param allKeys 所有项的键列表
     * 子类需要实现具体的状态更新逻辑
     */
    abstract fun toggleSelectAll(allKeys: List<Key>)

    /**
     * 更新编辑内容
     * @param key 项的键
     * @param editedValue 编辑后的值
     * 子类需要实现具体的状态更新逻辑
     */
    abstract fun updateEditedItem(
        key: Key,
        editedValue: EditedValue,
    )

    /**
     * 批量更新编辑内容
     * @param editedItems 编辑后的内容映射
     * 子类需要实现具体的状态更新逻辑
     */
    abstract fun updateEditedItems(editedItems: Map<Key, EditedValue>)

    /**
     * 删除选中的项
     * 子类需要实现具体的删除逻辑，删除成功后应调用 handleDeleteSuccess()
     */
    abstract fun deleteSelectedItems()

    /**
     * 保存编辑后的内容
     * 子类需要实现具体的保存逻辑，保存成功后应调用 handleSaveSuccess()
     */
    abstract fun saveEditedItems()

    /**
     * 处理删除成功
     * 统一处理：退出编辑模式、重新加载数据
     */
    protected fun handleDeleteSuccess() {
        viewModelScope.launch {
            exitEditMode()
            reloadData()
        }
    }

    /**
     * 处理保存成功
     * 统一处理：退出编辑模式、重新加载数据
     */
    protected fun handleSaveSuccess() {
        viewModelScope.launch {
            exitEditMode()
            reloadData()
        }
    }

    /**
     * 重新加载数据
     * 子类需要实现具体的数据加载逻辑
     */
    protected abstract fun reloadData()
}
