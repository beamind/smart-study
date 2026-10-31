package com.smartstudy.dictation.core

/**
 * 题目记录数据类
 * @param word 词语
 * @param isCorrect 是否正确
 * @param timestamp 时间戳（可选，用于记录答题时间）
 * @param chineseTranslation 中文翻译（可选，主要用于英文听写）
 * @param sourceType 来源类型（"new"表示新的听写，"error"表示错题听写）
 */
data class QuestionRecord(
    val word: String,
    val isCorrect: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val chineseTranslation: String = "",
    val sourceType: String = "error", // 默认为错题听写，兼容旧数据
)
