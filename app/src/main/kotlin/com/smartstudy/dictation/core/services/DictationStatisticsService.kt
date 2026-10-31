package com.smartstudy.dictation.core.services

import com.smartstudy.core.Logger
import com.smartstudy.dictation.core.DictationWordStat
import com.smartstudy.dictation.core.QuestionRecord

/**
 * 听写统计服务
 *
 * 负责计算听写相关的统计数据，将业务逻辑从存储层分离出来。
 *
 * 功能特性：
 * - 计算错题统计（错误次数、总次数、错误率）
 * - 计算最后听写时间戳
 * - 按错误率排序，最近一小时内的题目排到最后
 *
 * 使用示例：
 * ```
 * val service = DictationStatisticsService()
 * val stats = service.calculateStats(records)
 * ```
 */
class DictationStatisticsService {
    companion object {
        private const val TAG = "DictationStatisticsService"
    }

    /**
     * 简单的六元组数据类，用于临时存储统计数据
     */
    private data class Sextuple<A, B, C, D, E, F>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D,
        val fifth: E,
        val sixth: F,
    )

    /**
     * 计算错题统计（包含所有题目，包括没有错误的）
     * @param records 题目记录列表
     * @return 错题统计列表，最近一小时内的题目排到最后，其余按错误率降序排序（包含所有题目）
     */
    fun calculateStats(records: List<QuestionRecord>): List<DictationWordStat> {
        // 统计每个词语的错误次数、总次数、翻译、最后一次听写时间戳、来源类型集合和最近一次听写结果
        // word -> (errorCount, totalCount, translation, lastDictationTimestamp, sourceTypes, lastDictationIsCorrect)
        val wordStats = mutableMapOf<String, Sextuple<Int, Int, String, Long, MutableSet<String>, Boolean?>>()

        records.forEach { record ->
            val current = wordStats.getOrDefault(record.word, Sextuple(0, 0, "", 0L, mutableSetOf(), null))
            val errorCount = current.first + if (!record.isCorrect) 1 else 0
            val totalCount = current.second + 1
            // 使用最新的非空翻译（如果当前记录有翻译，使用它；否则保持之前的翻译）
            val translation =
                record.chineseTranslation.ifEmpty {
                    current.third // 保持之前的翻译
                }
            // 记录最后一次听写时间戳和结果（取最大值的时间戳对应的记录）
            val (lastTimestamp, lastIsCorrect) =
                if (record.timestamp >= current.fourth) {
                    // 如果当前记录的时间戳更大或相等，使用当前记录的结果
                    Pair(record.timestamp, record.isCorrect)
                } else {
                    // 否则保持之前的结果
                    Pair(current.fourth, current.sixth)
                }
            // 添加来源类型到集合中
            val sourceTypes = current.fifth.apply { add(record.sourceType) }
            wordStats[record.word] = Sextuple(errorCount, totalCount, translation, lastTimestamp, sourceTypes, lastIsCorrect)
        }

        // 转换为 DictationWordStat 列表并计算错误率，包含所有题目（不再过滤）
        val stats =
            wordStats.map { (word, data) ->
                val (errorCount, totalCount, translation, lastTimestamp, sourceTypes, lastIsCorrect) = data
                val errorRate = if (totalCount > 0) errorCount.toFloat() / totalCount else 0f

                DictationWordStat(
                    word = word,
                    errorCount = errorCount,
                    totalCount = totalCount,
                    errorRate = errorRate,
                    chineseTranslation = translation,
                    lastDictationTimestamp = lastTimestamp,
                    sourceTypes = sourceTypes.toSet(),
                    lastDictationIsCorrect = lastIsCorrect,
                )
            }

        // 获取当前时间戳和一小时前的时间戳
        val currentTime = System.currentTimeMillis()
        val oneHourAgo = currentTime - 60 * 60 * 1000L // 1小时 = 60分钟 * 60秒 * 1000毫秒

        // 排序：最近一小时内的题目排到最后，其余按错误率降序排序
        val sortedStats =
            stats.sortedWith(
                compareBy<DictationWordStat> { stat ->
                    // 判断是否在最近一小时内：在最近一小时内的返回true（排后），否则返回false（排前）
                    stat.lastDictationTimestamp >= oneHourAgo
                }.thenByDescending { stat ->
                    // 在每个组内按错误率降序排序
                    stat.errorRate
                },
            )

        // 记录统计信息
        Logger.d(TAG, "统计完成：总词数=${sortedStats.size}（包含所有题目）")

        return sortedStats
    }
}
