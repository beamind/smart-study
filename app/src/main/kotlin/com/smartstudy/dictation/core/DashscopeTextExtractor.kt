package com.smartstudy.dictation.core

import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult
import com.smartstudy.core.Logger

/**
 * 从 DashScope SDK 的结果对象中提取纯文本输出。
 * 目前 图片理解/翻译 都依赖 MultiModalConversation 的 message.content[*].text。
 */
internal object DashscopeTextExtractor {
    private const val TAG = "DashscopeTextExtractor"

    fun extractText(result: MultiModalConversationResult): String {
        return try {
            val output = result.output
            val choices = output?.choices
            if (choices.isNullOrEmpty()) return ""

            val content = choices[0]?.message?.content
            if (content.isNullOrEmpty()) return ""

            val texts = mutableListOf<String>()
            for (item in content) {
                val t = item["text"]?.toString()
                if (!t.isNullOrBlank()) texts.add(t)
            }
            texts.joinToString("\n").trim()
        } catch (e: org.json.JSONException) {
            Logger.e(TAG, "JSON解析错误，提取模型输出失败", e)
            ""
        } catch (e: IllegalStateException) {
            Logger.e(TAG, "状态错误，提取模型输出失败", e)
            ""
        } catch (e: NullPointerException) {
            Logger.e(TAG, "空指针错误，提取模型输出失败", e)
            ""
        }
    }
}
