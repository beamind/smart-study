package com.smartstudy.dictation.core

import com.smartstudy.core.AppConfig
import java.util.Calendar
import java.util.Random

/**
 * 词语选择辅助工具类
 *
 * 提供基于错误率和日期概率的智能词语选择算法。
 *
 * 算法说明：
 * - 采样概率 = min(max(错误率, 日期概率), 1.0)
 * - 日期概率 = (今天日期 - 最后一次听写日期) * 0.01
 * - 优先选择错误率高或长时间未练习的词语
 * - 如果选择不足，从剩余词语中随机补充
 *
 * 使用示例：
 * ```
 * val selectedWords = WordSelectionHelper.selectByErrorRate(
 *     errorStats = stats,
 *     count = 10,
 *     selector = { it.word }
 * )
 * ```
 */
object WordSelectionHelper {
    /**
     * 日期概率系数（每天增加0.01的概率）
     */
    private const val DATE_PROBABILITY_COEFFICIENT = 0.01

    /**
     * 最大尝试次数倍数（防止无限循环）
     */
    private const val MAX_ATTEMPTS_MULTIPLIER = 20

    /**
     * 根据错误率和日期概率选择词语
     *
     * @param errorStats 错题统计列表
     * @param count 需要选择的数量
     * @param selector 从统计对象中提取词语的函数
     * @return 选中的词语列表
     */
    fun <T> selectByErrorRate(
        errorStats: List<DictationWordStat>,
        count: Int,
        selector: (DictationWordStat) -> T,
    ): List<T> {
        if (errorStats.isEmpty() || count <= 0) {
            return emptyList()
        }

        val selectedItems = mutableSetOf<T>()
        val selectedWords = mutableSetOf<String>()
        val random = Random()

        // 计算今天的日期（0点）
        val todayMillis = getTodayStartMillis()

        // 对每个词计算采样概率并独立采样
        var attempts = 0
        val maxAttempts = count * MAX_ATTEMPTS_MULTIPLIER

        while (selectedItems.size < count && attempts < maxAttempts) {
            attempts++

            // 遍历所有未选中的词
            for (stat in errorStats) {
                if (selectedItems.size >= count) break

                // 只处理未选中的词
                if (!selectedWords.contains(stat.word)) {
                    // 计算采样概率
                    val samplingProbability = calculateSamplingProbability(stat, todayMillis)

                    // 根据概率决定是否采样
                    if (random.nextDouble() < samplingProbability) {
                        selectedItems.add(selector(stat))
                        selectedWords.add(stat.word)
                    }
                }
            }
        }

        // 如果选择不足，从剩余未选中的词中随机补充
        if (selectedItems.size < count) {
            val remainingStats = errorStats.filter { !selectedWords.contains(it.word) }
            if (remainingStats.isNotEmpty()) {
                val shuffled = remainingStats.shuffled()
                val needMore = count - selectedItems.size
                val toAdd = shuffled.take(needMore)
                selectedItems.addAll(toAdd.map { selector(it) })
                selectedWords.addAll(toAdd.map { it.word })
            }
        }

        return selectedItems.toList()
    }

    /**
     * 计算采样概率
     *
     * 采样概率 = min(max(错误率, 日期概率), 1.0)
     * 日期概率 = (今天日期 - 最后一次听写日期) * 0.01
     *
     * @param stat 词语统计信息
     * @param todayMillis 今天的开始时间戳（毫秒）
     * @return 采样概率（0.0-1.0）
     */
    private fun calculateSamplingProbability(
        stat: DictationWordStat,
        todayMillis: Long,
    ): Double {
        // 计算日期差（天数）
        val lastDictationDate = getLastDictationDate(stat, todayMillis)
        val daysDiff = ((todayMillis - lastDictationDate.timeInMillis) / AppConfig.MILLIS_PER_DAY).toInt()
        val dateProbability = daysDiff * DATE_PROBABILITY_COEFFICIENT

        // 计算采样概率 = min(max(错误率, 日期概率), 1.0)
        return maxOf(stat.errorRate.toDouble(), dateProbability).coerceIn(0.0, 1.0)
    }

    /**
     * 获取今天的开始时间戳（0点）
     */
    private fun getTodayStartMillis(): Long {
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    /**
     * 获取最后一次听写日期
     *
     * @param stat 词语统计信息
     * @param todayMillis 今天的开始时间戳（毫秒）
     * @return 最后一次听写日期的Calendar对象（0点）
     */
    private fun getLastDictationDate(
        stat: DictationWordStat,
        todayMillis: Long,
    ): Calendar {
        return if (stat.lastDictationTimestamp > 0) {
            Calendar.getInstance().apply {
                timeInMillis = stat.lastDictationTimestamp
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
        } else {
            // 如果没有时间戳，使用默认值（今天，即0天）
            Calendar.getInstance().apply {
                timeInMillis = todayMillis
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
        }
    }
}
