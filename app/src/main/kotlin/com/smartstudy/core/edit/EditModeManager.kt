package com.smartstudy.core.edit

/**
 * 编辑模式管理器接口
 *
 * 定义编辑流程的标准方法，包括删除验证、保存操作等。
 * 使用策略模式处理不同功能的特定逻辑。
 *
 * @param Key 选中项和编辑内容的键类型（通常是 String）
 * @param EditedValue 编辑内容的值类型
 */
interface EditModeManager<Key, EditedValue> {
    /**
     * 显示删除验证对话框
     * @param onConfirm 验证通过后的确认回调
     */
    fun showDeleteVerificationDialog(onConfirm: () -> Unit)

    /**
     * 执行删除操作
     * 包括验证选中项、显示验证对话框、执行删除
     */
    fun performDelete()

    /**
     * 保存编辑后的内容
     * 包括验证是否有变化、执行保存
     */
    fun saveEditedItems()

    /**
     * 更新编辑工具栏
     * @param selectedCount 选中数量
     * @param totalCount 总数量
     */
    fun updateEditToolbar(
        selectedCount: Int,
        totalCount: Int,
    )
}
