package com.smartstudy.english.managers

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.smartstudy.databinding.ActivityEnglishErrorBookBinding
import com.smartstudy.english.ErrorBookViewModel
import com.smartstudy.english.QuestionAdapter
import kotlinx.coroutines.launch

/**
 * 英语错题本状态观察器
 *
 * 负责观察 ViewModel 的状态变化并更新 UI，包括：
 * - 当前用户名显示更新
 * - 编辑模式工具栏显示/隐藏
 * - 编辑工具栏按钮状态更新
 *
 * 使用示例：
 * ```
 * val stateObserver = EnglishErrorBookStateObserver(
 *     binding = binding,
 *     viewModel = viewModel,
 *     uiManager = uiManager
 * )
 * stateObserver.observeState(lifecycleScope)
 * ```
 *
 * @param binding View Binding实例
 * @param viewModel 错题本ViewModel
 * @param uiManager UI管理器
 */
class EnglishErrorBookStateObserver(
    private val binding: ActivityEnglishErrorBookBinding,
    private val viewModel: ErrorBookViewModel,
    private val uiManager: com.smartstudy.english.managers.EnglishErrorBookUIManager,
) {

    private var questionAdapter: QuestionAdapter? = null

    /**
     * 更新题目适配器引用
     */
    fun updateQuestionAdapter(adapter: QuestionAdapter?) {
        questionAdapter = adapter
    }

    /**
     * 开始观察 ViewModel 状态变化
     *
     * @param lifecycleScope 生命周期作用域
     * @param lifecycleOwner 生命周期所有者
     */
    fun observeState(
        lifecycleScope: LifecycleCoroutineScope,
        lifecycleOwner: LifecycleOwner,
    ) {
        lifecycleScope.launch {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    // 更新当前用户名显示
                    if (state.currentUserName.isNotBlank()) {
                        binding.toolbar.tvCurrentUser.text = state.currentUserName
                    }

                    // 更新适配器的编辑模式状态
                    questionAdapter?.setEditMode(state.isEditMode)

                    // 处理编辑模式下的按钮显示/隐藏
                    // 只在选择模式下（识别完成后，非查看模式，且有题目列表）才需要处理编辑按钮
                    // 检查是否在识别完成后的状态：非查看模式 && 有题目适配器 && 题目列表可见
                    val isInSelectionState =
                        !state.isViewMode &&
                            questionAdapter != null &&
                            binding.rvQuestions.visibility == android.view.View.VISIBLE

                    // 检查是否在练习答案页面（答案卡片可见）
                    val isInPracticeAnswersState = binding.cardAnswers.visibility == android.view.View.VISIBLE

                    if (isInSelectionState) {
                        // 确保按钮容器可见
                        binding.btnContainer.visibility = android.view.View.VISIBLE

                        if (state.isEditMode) {
                            // 进入编辑模式：隐藏编辑按钮，显示保存按钮
                            uiManager.hideEditButton()
                            uiManager.showSaveButton()
                        } else {
                            // 退出编辑模式：显示编辑按钮，隐藏保存按钮，显示提交按钮
                            uiManager.showEditButton()
                            uiManager.hideSaveButton()
                            // 确保提交按钮可见
                            binding.btnSubmit.visibility = android.view.View.VISIBLE
                            binding.btnSubmit.text = binding.root.context.getString(com.smartstudy.R.string.error_book_submit_button)
                        }
                    } else if (isInPracticeAnswersState) {
                        // 在练习答案页面，确保按钮容器和提交按钮可见
                        binding.btnContainer.visibility = android.view.View.VISIBLE
                        binding.btnSubmit.visibility = android.view.View.VISIBLE
                        binding.btnEdit.visibility = android.view.View.GONE
                    } else {
                        // 不在选择状态且不在练习答案页面（主页或其他状态），确保隐藏按钮
                        binding.btnContainer.visibility = android.view.View.GONE
                        binding.btnSubmit.visibility = android.view.View.GONE
                        binding.btnEdit.visibility = android.view.View.GONE
                    }

                    // 编辑工具栏按钮状态更新已移至菜单，这里不再需要更新
                    // 菜单状态会在 onPrepareOptionsMenu 中根据状态更新

                    // 查看模式和练习提交状态已由 ViewModel 管理
                    // 可以根据需要在这里添加其他UI更新逻辑
                }
            }
        }
    }
}
