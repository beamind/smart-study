package com.smartstudy.english.usecases

import com.smartstudy.R
import com.smartstudy.core.AppResult
import com.smartstudy.english.EnglishErrorBookStorage
import com.smartstudy.english.QuestionAnswer
import com.smartstudy.english.repository.ErrorBookRepository
import com.smartstudy.user.usecases.UserUseCase

/**
 * 英语错题本业务逻辑用例类
 *
 * 将业务逻辑从ViewModel中分离出来，提高代码的可测试性和可维护性。
 *
 * 功能特性：
 * - 错题加载业务逻辑（包括过滤、排序等业务规则）
 * - 错题保存业务逻辑（包括验证等）
 * - 错题删除业务逻辑
 * - 错题更新业务逻辑
 * - 用户相关业务逻辑
 *
 * 使用示例：
 * ```
 * val useCase = ErrorBookUseCase(
 *     errorBookRepository = errorBookRepository,
 *     userUseCase = userUseCase
 * )
 * val result = useCase.loadAllQuestionsWithAnswers(userName)
 * ```
 *
 * @param errorBookRepository 错题本数据仓库
 * @param userUseCase 用户业务逻辑用例
 */
class ErrorBookUseCase(
    private val errorBookRepository: ErrorBookRepository,
    private val userUseCase: UserUseCase,
) {

    /**
     * 获取当前用户名
     *
     * 业务逻辑：
     * - 如果当前用户存在，返回用户名
     * - 如果当前用户不存在，返回默认值"游客"
     *
     * @return 当前用户名
     */
    suspend fun getCurrentUserName(): String {
        // 注意：此方法返回的是用户名，不是显示文本，所以不能直接使用字符串资源
        // 如果用户不存在，返回默认用户名"guest"，这应该与UserUseCase中的默认用户名一致
        return when (val result = userUseCase.getCurrentUser()) {
            is AppResult.Success -> result.data?.name ?: "guest"
            is AppResult.Failure -> "guest"
        }
    }

    /**
     * 确保默认用户存在
     *
     * 业务逻辑：
     * - 如果当前用户不存在，创建默认用户
     * - 返回当前用户信息
     *
     * @return 当前用户
     */
    suspend fun ensureDefaultUser(): AppResult<com.smartstudy.user.User> {
        return userUseCase.ensureDefaultUser()
    }

    /**
     * 加载所有错题（带统计信息）
     *
     * 业务逻辑：
     * - 从Repository获取错题数据（带统计信息）
     * - 返回题目和答案对列表（带统计信息）
     *
     * @param userName 用户名
     * @return 题目和答案对列表（带统计信息）
     */
    suspend fun loadAllQuestionsWithAnswers(
        userName: String,
    ): AppResult<List<Pair<QuestionAnswer, EnglishErrorBookStorage.QuestionWithStats>>> {
        // 业务逻辑：验证用户名
        if (userName.isBlank()) {
            return AppResult.Failure(
                com.smartstudy.core.AppError(
                    com.smartstudy.core.AppErrorKind.InvalidData,
                    errorBookRepository.getContext().getString(R.string.validation_error_username_empty),
                    IllegalArgumentException("用户名不能为空"),
                ),
            )
        }

        return errorBookRepository.loadAllQuestionsWithAnswers(userName)
    }

    /**
     * 加载所有错题题目列表
     *
     * 业务逻辑：
     * - 从Repository获取错题列表
     * - 返回题目列表
     *
     * @param userName 用户名
     * @return 题目列表
     */
    suspend fun loadAllQuestions(userName: String): AppResult<List<String>> {
        // 业务逻辑：验证用户名
        if (userName.isBlank()) {
            return AppResult.Failure(
                com.smartstudy.core.AppError(
                    com.smartstudy.core.AppErrorKind.InvalidData,
                    errorBookRepository.getContext().getString(R.string.validation_error_username_empty),
                    IllegalArgumentException("用户名不能为空"),
                ),
            )
        }

        return errorBookRepository.loadAllQuestions(userName)
    }

    /**
     * 保存错题记录（带答案）
     *
     * 业务逻辑：
     * - 验证用户名和题目列表
     * - 保存错题到存储
     *
     * @param userName 用户名
     * @param questions 题目和答案对列表
     * @return 操作结果
     */
    suspend fun saveQuestionsWithAnswers(
        userName: String,
        questions: List<QuestionAnswer>,
    ): AppResult<Unit> {
        // 业务逻辑：验证用户名
        if (userName.isBlank()) {
            return AppResult.Failure(
                com.smartstudy.core.AppError(
                    com.smartstudy.core.AppErrorKind.InvalidData,
                    errorBookRepository.getContext().getString(R.string.validation_error_username_empty),
                    IllegalArgumentException("用户名不能为空"),
                ),
            )
        }

        // 业务逻辑：验证题目列表
        if (questions.isEmpty()) {
            return AppResult.Failure(
                com.smartstudy.core.AppError(
                    com.smartstudy.core.AppErrorKind.InvalidData,
                    errorBookRepository.getContext().getString(R.string.validation_error_question_list_empty),
                    IllegalArgumentException("题目列表不能为空"),
                ),
            )
        }

        // 业务逻辑：验证每个题目和答案
        questions.forEach { qa ->
            if (qa.question.isBlank()) {
                return AppResult.Failure(
                    com.smartstudy.core.AppError(
                        com.smartstudy.core.AppErrorKind.InvalidData,
                        errorBookRepository.getContext().getString(R.string.validation_error_question_content_empty),
                        IllegalArgumentException("题目内容不能为空"),
                    ),
                )
            }
        }

        return errorBookRepository.saveQuestionsWithAnswers(userName, questions)
    }

    /**
     * 删除错题
     *
     * 业务逻辑：
     * - 验证用户名和题目列表
     * - 删除错题
     *
     * @param userName 用户名
     * @param questions 要删除的题目列表
     * @return 操作结果
     */
    suspend fun deleteQuestions(
        userName: String,
        questions: List<String>,
    ): AppResult<Unit> {
        // 业务逻辑：验证用户名
        if (userName.isBlank()) {
            return AppResult.Failure(
                com.smartstudy.core.AppError(
                    com.smartstudy.core.AppErrorKind.InvalidData,
                    errorBookRepository.getContext().getString(R.string.validation_error_username_empty),
                    IllegalArgumentException("用户名不能为空"),
                ),
            )
        }

        // 业务逻辑：验证题目列表
        if (questions.isEmpty()) {
            return AppResult.Failure(
                com.smartstudy.core.AppError(
                    com.smartstudy.core.AppErrorKind.InvalidData,
                    errorBookRepository.getContext().getString(R.string.validation_error_delete_question_list_empty),
                    IllegalArgumentException("要删除的题目列表不能为空"),
                ),
            )
        }

        return errorBookRepository.deleteQuestions(userName, questions)
    }

    /**
     * 更新错题（当题目内容改变时，需要更新key）
     *
     * 业务逻辑：
     * - 验证用户名和题目
     * - 更新错题
     *
     * @param userName 用户名
     * @param oldQuestion 旧的题目内容
     * @param newQuestion 新的题目内容
     * @return 操作结果
     */
    suspend fun updateQuestion(
        userName: String,
        oldQuestion: String,
        newQuestion: String,
    ): AppResult<Unit> {
        // 业务逻辑：验证用户名
        if (userName.isBlank()) {
            return AppResult.Failure(
                com.smartstudy.core.AppError(
                    com.smartstudy.core.AppErrorKind.InvalidData,
                    errorBookRepository.getContext().getString(R.string.validation_error_username_empty),
                    IllegalArgumentException("用户名不能为空"),
                ),
            )
        }

        // 业务逻辑：验证题目
        if (oldQuestion.isBlank()) {
            return AppResult.Failure(
                com.smartstudy.core.AppError(
                    com.smartstudy.core.AppErrorKind.InvalidData,
                    errorBookRepository.getContext().getString(R.string.validation_error_old_question_empty),
                    IllegalArgumentException("旧题目内容不能为空"),
                ),
            )
        }

        if (newQuestion.isBlank()) {
            return AppResult.Failure(
                com.smartstudy.core.AppError(
                    com.smartstudy.core.AppErrorKind.InvalidData,
                    errorBookRepository.getContext().getString(R.string.validation_error_new_question_empty),
                    IllegalArgumentException("新题目内容不能为空"),
                ),
            )
        }

        // 业务逻辑：如果新旧题目相同，无需更新
        if (oldQuestion == newQuestion) {
            return AppResult.Success(Unit)
        }

        return errorBookRepository.updateQuestion(userName, oldQuestion, newQuestion)
    }

    /**
     * 更新错题的答案
     *
     * 业务逻辑：
     * - 验证用户名、题目和答案
     * - 更新错题答案
     *
     * @param userName 用户名
     * @param question 题目内容
     * @param newAnswer 新的答案
     * @return 操作结果
     */
    suspend fun updateAnswer(
        userName: String,
        question: String,
        newAnswer: String,
    ): AppResult<Unit> {
        // 业务逻辑：验证用户名
        if (userName.isBlank()) {
            return AppResult.Failure(
                com.smartstudy.core.AppError(
                    com.smartstudy.core.AppErrorKind.InvalidData,
                    errorBookRepository.getContext().getString(R.string.validation_error_username_empty),
                    IllegalArgumentException("用户名不能为空"),
                ),
            )
        }

        // 业务逻辑：验证题目
        if (question.isBlank()) {
            return AppResult.Failure(
                com.smartstudy.core.AppError(
                    com.smartstudy.core.AppErrorKind.InvalidData,
                    errorBookRepository.getContext().getString(R.string.validation_error_question_content_empty),
                    IllegalArgumentException("题目内容不能为空"),
                ),
            )
        }

        return errorBookRepository.updateAnswer(userName, question, newAnswer)
    }

    /**
     * 按照题目顺序重新排列JSON对象
     *
     * 业务逻辑：
     * - 验证用户名和题目顺序
     * - 重新排列错题顺序
     *
     * @param userName 用户名
     * @param questionOrder 题目顺序列表（按显示顺序）
     * @return 操作结果
     */
    suspend fun reorderJsonByQuestionOrder(
        userName: String,
        questionOrder: List<String>,
    ): AppResult<Unit> {
        // 业务逻辑：验证用户名
        if (userName.isBlank()) {
            return AppResult.Failure(
                com.smartstudy.core.AppError(
                    com.smartstudy.core.AppErrorKind.InvalidData,
                    errorBookRepository.getContext().getString(R.string.validation_error_username_empty),
                    IllegalArgumentException("用户名不能为空"),
                ),
            )
        }

        // 业务逻辑：验证题目顺序列表
        if (questionOrder.isEmpty()) {
            return AppResult.Failure(
                com.smartstudy.core.AppError(
                    com.smartstudy.core.AppErrorKind.InvalidData,
                    errorBookRepository.getContext().getString(R.string.validation_error_question_order_empty),
                    IllegalArgumentException("题目顺序列表不能为空"),
                ),
            )
        }

        return errorBookRepository.reorderJsonByQuestionOrder(userName, questionOrder)
    }

    /**
     * 加载所有错题题目列表（带统计信息）
     *
     * 业务逻辑：
     * - 从Repository获取错题统计信息
     * - 返回题目统计信息列表
     *
     * @param userName 用户名
     * @return 题目统计信息列表
     */
    suspend fun loadAllQuestionsWithStats(
        userName: String,
    ): AppResult<List<EnglishErrorBookStorage.QuestionWithStats>> {
        // 业务逻辑：验证用户名
        if (userName.isBlank()) {
            return AppResult.Failure(
                com.smartstudy.core.AppError(
                    com.smartstudy.core.AppErrorKind.InvalidData,
                    errorBookRepository.getContext().getString(R.string.validation_error_username_empty),
                    IllegalArgumentException("用户名不能为空"),
                ),
            )
        }

        return errorBookRepository.loadAllQuestionsWithStats(userName)
    }

    /**
     * 加载所有错题题目列表（带完整统计信息，用于采样）
     *
     * 业务逻辑：
     * - 从Repository获取错题完整统计信息
     * - 返回题目完整统计信息列表
     *
     * @param userName 用户名
     * @return 题目完整统计信息列表
     */
    suspend fun loadAllQuestionsWithFullStats(
        userName: String,
    ): AppResult<List<EnglishErrorBookStorage.QuestionStat>> {
        // 业务逻辑：验证用户名
        if (userName.isBlank()) {
            return AppResult.Failure(
                com.smartstudy.core.AppError(
                    com.smartstudy.core.AppErrorKind.InvalidData,
                    errorBookRepository.getContext().getString(R.string.validation_error_username_empty),
                    IllegalArgumentException("用户名不能为空"),
                ),
            )
        }

        return errorBookRepository.loadAllQuestionsWithFullStats(userName)
    }
}
