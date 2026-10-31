package com.smartstudy.dictation.managers

import android.view.LayoutInflater
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.smartstudy.R
import com.smartstudy.core.edit.BaseEditModeManager
import com.smartstudy.dictation.ErrorWordAdapter
import com.smartstudy.dictation.ErrorWordListState
import com.smartstudy.dictation.ErrorWordListViewModel
import com.smartstudy.utils.ToastHelper

/**
 * 错词列表管理器
 *
 * 负责管理错词列表的业务逻辑，将业务逻辑从Activity中分离出来。
 * 继承 BaseEditModeManager 以复用通用的编辑流程逻辑。
 *
 * 功能特性：
 * - 删除验证对话框管理（继承自基类）
 * - 删除操作管理（继承自基类）
 * - 保存编辑后的单词管理（继承自基类）
 * - 添加错题对话框管理
 * - 手动添加错题管理
 * - 编辑工具栏更新管理
 *
 * 使用示例：
 * ```
 * val manager = ErrorWordListManager(
 *     activity = this,
 *     viewModel = viewModel,
 *     adapterGetter = { uiManager.getAdapter() },
 *     onAddSuccess = { count -> ToastHelper.showShort(this, R.string.error_word_list_add_success, count) }
 * )
 * manager.performDelete()
 * manager.saveEditedWords()
 * ```
 *
 * @param activity Activity实例
 * @param viewModel 错词列表ViewModel
 * @param adapterGetter 获取适配器的函数（用于变化检测）
 * @param onAddSuccess 添加成功回调，参数为添加的单词数量（保留，因为添加是同步的）
 */
class ErrorWordListManager(
    activity: AppCompatActivity,
    private val viewModel: ErrorWordListViewModel,
    private val adapterGetter: () -> ErrorWordAdapter?,
    private val onAddSuccess: (Int) -> Unit,
) : BaseEditModeManager<String, Pair<String, String>, ErrorWordListState>(
        activity = activity,
    ) {

    override fun getCurrentState(): ErrorWordListState {
        return viewModel.uiState.value
    }

    override fun filterChangedItems(editedItems: Map<String, Pair<String, String>>): Map<String, Pair<String, String>> {
        val state = getCurrentState()
        val adapter = adapterGetter() ?: return emptyMap()

        // 过滤出真正有变化的编辑
        return editedItems.filter { (oldWord, newData) ->
            val (newWord, newTranslation) = newData
            val originalStat = adapter.stats.find { it.word == oldWord }
            val originalTranslation = originalStat?.chineseTranslation ?: ""
            oldWord != newWord || (state.currentType == "english" && newTranslation != originalTranslation)
        }
    }

    override fun executeDelete(selectedItems: Set<String>) {
        // 删除成功后会在 ViewModel 中统一处理退出编辑模式和重新加载
        viewModel.deleteSelectedWords()
    }

    override fun executeSave(changedItems: Map<String, Pair<String, String>>) {
        // 更新 ViewModel 的 editedTexts 为过滤后的结果
        viewModel.updateEditedTexts(changedItems)

        // 保存编辑后的单词（异步操作，保存成功后会在 ViewModel 中统一处理退出编辑模式和重新加载）
        viewModel.saveEditedWords()
    }

    override fun updateEditToolbar(
        selectedCount: Int,
        totalCount: Int,
    ) {
        // 这个方法由 UIManager 处理，这里不需要实现
    }

    /**
     * 保存编辑后的单词和翻译
     * 使用 ViewModel 中存储的 editedTexts
     * 保存成功后通过状态变化通知，不需要回调
     */
    fun saveEditedWords() {
        // 使用基类的统一保存逻辑
        saveEditedItems()
    }

    /**
     * 显示添加错题对话框
     */
    fun showAddWordsDialog() {
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_add_words, null)
        val etWords = dialogView.findViewById<EditText>(R.id.etWords)
        val tvHint = dialogView.findViewById<TextView>(R.id.tvHint)

        // 设置提示文本
        tvHint.text = activity.getString(R.string.error_word_list_add_dialog_hint)

        val dialog =
            AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.error_word_list_add_dialog_title))
                .setView(dialogView)
                .setPositiveButton(activity.getString(R.string.confirm), null)
                .setNegativeButton(activity.getString(R.string.cancel), null)
                .create()

        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveButton.setOnClickListener {
                val inputText = etWords.text.toString().trim()
                if (inputText.isEmpty()) {
                    ToastHelper.showShort(activity, R.string.error_word_list_input_at_least_one)
                    return@setOnClickListener
                }

                dialog.dismiss()
                addWordsManually(inputText)
            }
        }

        dialog.show()
    }

    /**
     * 手动添加错题记录
     * @param inputText 输入的文本，每行一个单词
     */
    private fun addWordsManually(inputText: String) {
        // 解析输入的文本：按行分割，过滤空行和空白字符
        val words =
            inputText.split("\n")
                .map { it.trim() }
                .filter { it.isNotEmpty() }

        if (words.isEmpty()) {
            ToastHelper.showShort(activity, R.string.no_valid_words_input)
            return
        }

        viewModel.addWordsManually(words)
        onAddSuccess(words.size)
    }
}
