package com.smartstudy.core.edit

import androidx.appcompat.app.AppCompatActivity
import com.smartstudy.R
import com.smartstudy.utils.DialogHelper
import com.smartstudy.utils.ToastHelper

/**
 * 编辑模式管理器基类
 *
 * 实现删除验证对话框、编辑工具栏和通用验证逻辑。
 * 子类需要实现具体的保存和删除操作。
 *
 * @param Key 选中项和编辑内容的键类型（通常是 String）
 * @param EditedValue 编辑内容的值类型
 * @param State 状态类型，必须实现 EditModeState 接口
 */
abstract class BaseEditModeManager<Key, EditedValue, State : EditModeState<Key, EditedValue>>(
    protected val activity: AppCompatActivity,
) : EditModeManager<Key, EditedValue> {
    /**
     * 获取当前状态
     */
    protected abstract fun getCurrentState(): State

    /**
     * 获取选中项为空时的提示消息资源ID
     * 子类可以重写以自定义消息
     */
    protected open fun getEmptySelectionMessageResId(): Int {
        return R.string.error_word_list_delete_dialog_input_hint // 默认使用通用消息
    }

    /**
     * 获取没有变化时的提示消息资源ID
     * 子类可以重写以自定义消息
     */
    protected open fun getNoChangesMessageResId(): Int {
        return R.string.no_content_changed
    }

    /**
     * 显示删除验证对话框
     */
    override fun showDeleteVerificationDialog(onConfirm: () -> Unit) {
        DialogHelper.showDeleteVerificationDialog(activity, onConfirm).show()
    }

    /**
     * 执行删除操作
     * 包括验证选中项、显示验证对话框、执行删除
     */
    override fun performDelete() {
        val state = getCurrentState()

        // 统一验证：检查是否有选中的项
        if (!validateSelection(state.selectedItems)) {
            return
        }

        // 显示删除验证对话框
        showDeleteVerificationDialog {
            // 验证通过后执行删除
            executeDelete(state.selectedItems)
        }
    }

    /**
     * 保存编辑后的内容
     * 包括验证是否有变化、执行保存
     */
    override fun saveEditedItems() {
        val state = getCurrentState()

        // 统一验证：检查是否有编辑内容
        if (!validateChanges(state.editedItems)) {
            return
        }

        // 过滤出真正有变化的编辑
        val changedItems = filterChangedItems(state.editedItems)

        if (changedItems.isEmpty()) {
            ToastHelper.showShort(activity, getNoChangesMessageResId())
            return
        }

        // 执行保存
        executeSave(changedItems)
    }

    /**
     * 更新编辑工具栏
     * 子类可以重写以自定义工具栏更新逻辑
     */
    override fun updateEditToolbar(
        selectedCount: Int,
        totalCount: Int,
    ) {
        // 默认实现：子类需要重写此方法以更新具体的UI
    }

    /**
     * 验证选中项是否为空
     * @param selectedItems 选中的项集合
     * @return true 如果有选中的项，false 如果为空
     */
    protected fun validateSelection(selectedItems: Set<Key>): Boolean {
        if (selectedItems.isEmpty()) {
            ToastHelper.showShort(activity, getEmptySelectionMessageResId())
            return false
        }
        return true
    }

    /**
     * 验证是否有编辑内容
     * @param editedItems 编辑后的内容映射
     * @return true 如果有编辑内容，false 如果为空
     */
    protected fun validateChanges(editedItems: Map<Key, EditedValue>): Boolean {
        if (editedItems.isEmpty()) {
            ToastHelper.showShort(activity, getNoChangesMessageResId())
            return false
        }
        return true
    }

    /**
     * 过滤出真正有变化的编辑内容
     * 子类可以重写以自定义变化检测逻辑
     *
     * @param editedItems 所有编辑后的内容映射
     * @return 真正有变化的编辑内容映射
     */
    protected open fun filterChangedItems(editedItems: Map<Key, EditedValue>): Map<Key, EditedValue> {
        // 默认实现：返回所有编辑内容
        // 子类应该重写此方法以实现具体的变化检测逻辑
        return editedItems
    }

    /**
     * 执行删除操作
     * 子类需要实现具体的删除逻辑
     *
     * @param selectedItems 要删除的项集合
     */
    protected abstract fun executeDelete(selectedItems: Set<Key>)

    /**
     * 执行保存操作
     * 子类需要实现具体的保存逻辑
     *
     * @param changedItems 真正有变化的编辑内容映射
     */
    protected abstract fun executeSave(changedItems: Map<Key, EditedValue>)
}
