package com.smartstudy.dictation.core

/**
 * 听写单词统计数据类
 * @param word 词语
 * @param errorCount 错误次数
 * @param totalCount 总次数
 * @param errorRate 错误率（errorCount / totalCount）
 * @param chineseTranslation 中文翻译（可选，主要用于英文听写）
 * @param lastDictationTimestamp 最后一次听写的时间戳（毫秒）
 * @param sourceTypes 来源类型集合（"new"表示新的听写，"error"表示错题听写）
 * @param lastDictationIsCorrect 最近一次听写的结果（true表示正确，false表示错误，null表示无记录）
 */
data class DictationWordStat(
    val word: String,
    val errorCount: Int,
    val totalCount: Int,
    val errorRate: Float,
    val chineseTranslation: String = "",
    val lastDictationTimestamp: Long = 0L,
    val sourceTypes: Set<String> = emptySet(),
    val lastDictationIsCorrect: Boolean? = null,
)
