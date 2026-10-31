package com.smartstudy.learningrecord

/**
 * 学习记录类型
 */
enum class LearningRecordType {
    CHINESE_DICTATION, // 语文听写
    ENGLISH_DICTATION, // 英语听写
    ERROR_BOOK, // 错题本
}

/**
 * 统一的学习记录数据类
 *
 * @param type 学习记录类型
 * @param content 内容（词语/题目）
 * @param timestamp 时间戳（毫秒）
 * @param isCorrect 是否正确
 * @param translation 翻译（可选，主要用于英文听写）
 * @param answer 答案（可选，主要用于错题本）
 * @param errorCount 错误次数（可选）
 * @param totalCount 总次数（可选）
 */
data class LearningRecord(
    val type: LearningRecordType,
    val content: String,
    val timestamp: Long,
    val isCorrect: Boolean,
    val translation: String = "",
    val answer: String = "",
    val errorCount: Int = 0,
    val totalCount: Int = 0,
) {
    /**
     * 获取类型显示名称
     */
    fun getTypeDisplayName(): String {
        return when (type) {
            LearningRecordType.CHINESE_DICTATION -> "语文听写"
            LearningRecordType.ENGLISH_DICTATION -> "英语听写"
            LearningRecordType.ERROR_BOOK -> "错题本"
        }
    }

    /**
     * 获取唯一标识符
     * 格式：${type}_${content}
     */
    fun getUniqueId(): String {
        return "${type}_$content"
    }
}

/**
 * 按类型分组的学习记录
 */
data class LearningRecordGroup(
    val type: LearningRecordType,
    val records: List<LearningRecord>,
) {
    /**
     * 获取类型显示名称
     */
    fun getTypeDisplayName(): String {
        return when (type) {
            LearningRecordType.CHINESE_DICTATION -> "语文听写"
            LearningRecordType.ENGLISH_DICTATION -> "英语听写"
            LearningRecordType.ERROR_BOOK -> "错题本"
        }
    }
}
