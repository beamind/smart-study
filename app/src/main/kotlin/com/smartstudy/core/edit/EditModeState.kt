package com.smartstudy.core.edit

/**
 * 编辑模式状态接口
 *
 * 定义编辑模式的通用状态属性，用于统一管理编辑模式、选中项和编辑内容。
 * 各功能的 State 类应实现此接口。
 *
 * @param Key 选中项和编辑内容的键类型（通常是 String）
 * @param EditedValue 编辑内容的值类型（可以是 String、Pair<String, String> 等）
 */
interface EditModeState<Key, EditedValue> {
    /**
     * 是否处于编辑模式
     * 注意：当 data class 实现此接口时，如果构造函数参数名称与此属性名称相同，
     * data class 的属性会自动实现此接口属性，不需要显式 override
     */
    val isEditMode: Boolean

    /**
     * 选中的项集合
     */
    val selectedItems: Set<Key>

    /**
     * 编辑后的内容映射（原始键 -> 编辑后的值）
     */
    val editedItems: Map<Key, EditedValue>
}
