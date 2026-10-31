package com.smartstudy.english

import com.smartstudy.core.Logger
import org.json.JSONArray

/**
 * 英语错题本解析器
 * 提供题目解析相关的工具方法
 */
object EnglishErrorBookParser {
    private const val TAG = "EnglishErrorBookParser"

    /**
     * 去除题目开头的题号（多种常见格式）。
     */
    fun removeQuestionNumber(question: String): String {
        var cleaned = question.trim()

        // 去除各种题号格式（按优先级顺序）
        // 1. 数字+点+可选空格：1. 、2. 等
        cleaned = cleaned.replaceFirst(Regex("^\\d+\\.\\s*"), "")

        // 2. 数字+右括号+可选空格：1) 、2) 等
        cleaned = cleaned.replaceFirst(Regex("^\\d+\\)\\s*"), "")

        // 3. 左括号+数字+右括号+可选空格：(1) 、（1）、(2) 、（2）等
        cleaned = cleaned.replaceFirst(Regex("^[（(]\\d+[）)]\\s*"), "")

        // 4. 中文数字+点/句号+可选空格：一.、二。等（一到十）
        cleaned = cleaned.replaceFirst(Regex("^[一二三四五六七八九十]+[.。]\\s*"), "")

        // 5. 数字+中文顿号/逗号+可选空格：1、2、等
        cleaned = cleaned.replaceFirst(Regex("^\\d+[、,]\\s*"), "")

        return cleaned.trim()
    }

    /**
     * 解析题目和答案：优先按 JSON 数组格式解析。
     *
     * 输入示例：`[{\"question\":\"...\",\"answer\":\"...\"}]`
     */
    fun parseQuestionsWithAnswers(jsonText: String): List<QuestionAnswer> {
        if (jsonText.isBlank()) return emptyList()

        return try {
            val jsonArray = JSONArray(jsonText.trim())
            val questionAnswers = mutableListOf<QuestionAnswer>()

            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)
                var question = item.optString("question", "").trim()
                val answer = item.optString("answer", "").trim()

                // 兜底：去除题目开头题号
                question = removeQuestionNumber(question)

                if (question.isNotEmpty()) {
                    questionAnswers.add(QuestionAnswer(question, answer))
                }
            }

            Logger.d(TAG, "解析出 ${questionAnswers.size} 道题目和答案")
            questionAnswers
        } catch (e: org.json.JSONException) {
            Logger.e(TAG, "JSON解析错误，解析JSON失败: ${e.message}", e)
            // JSON 解析失败，返回空列表
            emptyList()
        } catch (e: IllegalStateException) {
            Logger.e(TAG, "状态错误，解析JSON失败: ${e.message}", e)
            // JSON 解析失败，返回空列表
            emptyList()
        }
    }
}
