package com.smartstudy.dictation.managers

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.smartstudy.R
import com.smartstudy.databinding.ActivityErrorWordListBinding
import com.smartstudy.dictation.ErrorWordAdapter
import com.smartstudy.dictation.ErrorWordListViewModel
import com.smartstudy.dictation.core.DictationWordStat

/**
 * 错词列表UI管理器
 *
 * 负责管理错词列表Activity的UI逻辑，包括：
 * - 空状态显示和隐藏
 * - 列表适配器创建和更新
 * - 编辑模式UI切换
 * - 工具栏状态更新
 * - 标题更新
 * - RecyclerView设置
 *
 * 使用示例：
 * ```
 * val uiManager = ErrorWordListUIManager(
 *     activity = this,
 *     binding = binding
 * )
 * uiManager.setupRecyclerView()
 * uiManager.showEmptyState(true)
 * uiManager.updateErrorWordList(stats, viewModel)
 * ```
 *
 * @param activity Activity实例
 * @param binding View Binding实例
 */
class ErrorWordListUIManager(
    private val activity: AppCompatActivity,
    private val binding: ActivityErrorWordListBinding,
) {

    private var errorWordAdapter: ErrorWordAdapter? = null

    /**
     * 设置RecyclerView的布局管理器
     */
    fun setupRecyclerView() {
        binding.rvErrorWords.layoutManager = LinearLayoutManager(activity)
    }

    /**
     * 显示或隐藏空状态
     *
     * @param show 是否显示空状态
     */
    fun showEmptyState(show: Boolean) {
        binding.layoutEmpty.visibility = if (show) View.VISIBLE else View.GONE
        binding.rvErrorWords.visibility = if (show) View.GONE else View.VISIBLE
    }

    /**
     * 更新错词列表
     *
     * @param stats 错词统计数据列表
     * @param viewModel ViewModel实例
     * @return 创建的适配器
     */
    fun updateErrorWordList(
        stats: List<DictationWordStat>,
        viewModel: ErrorWordListViewModel,
    ): ErrorWordAdapter {
        val state = viewModel.uiState.value
        errorWordAdapter =
            ErrorWordAdapter(
                stats,
                viewModel,
                state.currentType,
            )
        binding.rvErrorWords.adapter = errorWordAdapter
        return errorWordAdapter!!
    }

    /**
     * 更新编辑模式UI
     */
    fun updateEditMode() {
        // 编辑模式UI已移至菜单，此方法保留以保持接口兼容性
    }

    /**
     * 更新编辑工具栏
     */
    fun updateEditToolbar() {
        // 编辑工具栏已移至菜单，此方法保留以保持接口兼容性
    }

    /**
     * 更新标题
     *
     * @param isChinese 是否为中文类型
     */
    fun updateTitle(isChinese: Boolean) {
        val titleText =
            if (isChinese) {
                activity.getString(R.string.chinese_error_word_list_title)
            } else {
                activity.getString(R.string.english_error_word_list_title)
            }
        activity.supportActionBar?.title = titleText
    }

    /**
     * 获取当前适配器
     *
     * @return 适配器实例，可能为null
     */
    fun getAdapter(): ErrorWordAdapter? {
        return errorWordAdapter
    }

    /**
     * 设置适配器（用于外部设置）
     *
     * @param adapter 适配器实例
     */
    fun setAdapter(adapter: ErrorWordAdapter?) {
        errorWordAdapter = adapter
        if (adapter != null) {
            binding.rvErrorWords.adapter = adapter
        }
    }

    /**
     * 更新保存按钮状态
     */
    fun updateSaveButtonState() {
        // 保存按钮已移至菜单，此方法保留以保持接口兼容性
    }
}
