package com.smartstudy.dictation

import com.smartstudy.core.edit.EditModeState
import com.smartstudy.dictation.core.DictationWordStat

/**
 * 错题列表功能的统一状态数据类
 *
 * 将多个独立的状态整合到一个数据类中，便于管理和维护。
 * 使用 data class 支持不可变状态，提高线程安全性。
 *
 * @param currentType 当前类型（"chinese" 或 "english"）
 * @param currentUserId 当前用户ID
 * @param errorWordStats 错题统计列表
 * @param isLoading 是否正在加载
 * @param error 错误信息（null 表示无错误）
 * @param isEditMode 是否处于编辑模式
 * @param selectedWords 选中的单词集合
 * @param editedTexts 编辑后的文本映射（原始单词 -> (新单词, 新翻译)）
 * @param isEmpty 是否为空状态
 */
data class ErrorWordListState(
    val currentType: String = "chinese",
    val currentUserId: String = "",
    val errorWordStats: List<DictationWordStat> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val _isEditMode: Boolean = false, // 使用下划线前缀避免与接口属性冲突
    val selectedWords: Set<String> = emptySet(),
    val editedTexts: Map<String, Pair<String, String>> = emptyMap(),
    val isEmpty: Boolean = false,
) : EditModeState<String, Pair<String, String>> {
    // 显式 override 接口属性，委托给构造函数参数
    override val isEditMode: Boolean get() = this._isEditMode
    override val selectedItems: Set<String> get() = this.selectedWords
    override val editedItems: Map<String, Pair<String, String>> get() = this.editedTexts

    /**
     * 创建初始状态
     */
    companion object {
        fun initial() = ErrorWordListState()
    }
}
