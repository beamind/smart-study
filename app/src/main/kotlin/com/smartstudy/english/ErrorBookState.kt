package com.smartstudy.english

import com.smartstudy.core.AppResult
import com.smartstudy.core.edit.EditModeState

/**
 * 英语错题本功能的统一状态数据类
 *
 * 将多个独立的 LiveData 状态整合到一个数据类中，便于管理和维护。
 * 使用 data class 支持不可变状态，提高线程安全性。
 *
 * @param saveQuestionsResult 保存题目操作结果
 * @param deleteQuestionsResult 删除题目操作结果
 * @param currentUserId 当前用户ID
 * @param currentUserName 当前用户名
 * @param questions 题目列表
 * @param questionsWithStats 题目列表（带统计信息）
 * @param isLoadingQuestions 是否正在加载题目
 * @param loadQuestionsError 加载题目错误信息（null 表示无错误）
 * @param isEditMode 是否处于编辑模式
 * @param questionUpdateMap 题目更新映射（旧题目 -> 新题目）
 * @param questionsToDelete 要删除的题目集合
 * @param isViewMode 是否处于查看模式
 * @param isVariationMode 是否处于举一反三模式
 * @param isPracticeActive 练习是否激活
 * @param isWaitingForStart 是否在等待开始关键词
 * @param currentQuestionIndex 当前题目索引
 * @param practiceQuestions 练习题目列表
 * @param isPracticeSubmitted 练习是否已提交
 * @param isProcessingImage 是否正在处理图片
 * @param imageRecognitionError 图片识别错误信息（null 表示无错误）
 * @param selectedQuestions 选中的题目集合（用于编辑模式和提交模式）
 */
data class ErrorBookState(
    val saveQuestionsResult: AppResult<Unit>? = null,
    val deleteQuestionsResult: AppResult<Unit>? = null,
    val currentUserId: String = "",
    val currentUserName: String = "",
    val questions: List<QuestionAnswer> = emptyList(),
    val questionsWithStats: List<Pair<QuestionAnswer, EnglishErrorBookStorage.QuestionWithStats>> = emptyList(),
    val isLoadingQuestions: Boolean = false,
    val loadQuestionsError: String? = null,
    val _isEditMode: Boolean = false, // 使用下划线前缀避免与接口属性冲突
    val questionUpdateMap: Map<String, String> = emptyMap(),
    val questionsToDelete: Set<String> = emptySet(),
    val selectedQuestions: Set<String> = emptySet(),
    val isViewMode: Boolean = false,
    val isVariationMode: Boolean = false,
    val isPracticeActive: Boolean = false,
    val isWaitingForStart: Boolean = false,
    val currentQuestionIndex: Int = 0,
    val practiceQuestions: List<QuestionAnswer> = emptyList(),
    val isPracticeSubmitted: Boolean = false,
    val isProcessingImage: Boolean = false,
    val imageRecognitionError: String? = null,
    val selectedAnswerPositions: Set<Int> = emptySet(),
    val isAnswerCheckboxesEnabled: Boolean = true,
) : EditModeState<String, String> {
    // 显式 override 接口属性，委托给构造函数参数
    override val isEditMode: Boolean get() = this._isEditMode
    override val selectedItems: Set<String> get() = this.selectedQuestions
    override val editedItems: Map<String, String> get() = this.questionUpdateMap

    /**
     * 创建初始状态
     */
    companion object {
        fun initial() = ErrorBookState()
    }

    /**
     * 重置编辑模式状态
     */
    fun resetEditMode(): ErrorBookState {
        return copy(
            _isEditMode = false,
            questionUpdateMap = emptyMap(),
            questionsToDelete = emptySet(),
            selectedQuestions = emptySet(),
        )
    }

    /**
     * 重置练习状态
     */
    fun resetPracticeState(): ErrorBookState {
        return copy(
            isPracticeActive = false,
            isWaitingForStart = false,
            currentQuestionIndex = 0,
            practiceQuestions = emptyList(),
            isPracticeSubmitted = false,
            isVariationMode = false,
            selectedAnswerPositions = emptySet(),
            isAnswerCheckboxesEnabled = true,
        )
    }

    /**
     * 清除图片识别错误
     */
    fun clearImageRecognitionError(): ErrorBookState {
        return copy(imageRecognitionError = null)
    }

    /**
     * 清除操作结果
     */
    fun clearOperationResults(): ErrorBookState {
        return copy(
            saveQuestionsResult = null,
            deleteQuestionsResult = null,
        )
    }
}
