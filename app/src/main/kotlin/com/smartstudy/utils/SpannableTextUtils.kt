package com.smartstudy.utils

import android.text.SpannableString
import android.text.Spanned
import android.text.style.UnderlineSpan

/**
 * SpannableString 工具类
 * 提供文本样式处理相关的工具方法
 */
object SpannableTextUtils {
    /**
     * Markdown 下划线标记的正则表达式
     * 匹配 __text__ 格式，确保只匹配恰好两个下划线（不是单个或多个）
     * (?<!_) 表示前面不是下划线
     * __ 表示恰好两个下划线
     * ([^_]+) 表示不包含下划线的内容
     * __(?!_) 表示后面不是下划线
     */
    private val UNDERLINE_PATTERN = Regex("(?<!_)__([^_]+)__(?!_)")

    /**
     * 移除文本中的 Markdown 下划线标记
     * 例如：__text__ → text
     *
     * @param text 包含 __text__ 标记的原始文本
     * @return 移除标记后的文本
     */
    fun removeUnderlineMarkers(text: String): String {
        return text.replace(UNDERLINE_PATTERN) { matchResult ->
            matchResult.groupValues[1] // 只保留内容，移除 __ 标记
        }
    }

    /**
     * 将 Markdown 格式的下划线应用到 SpannableString
     * 例如：__text__ → 对 text 应用 UnderlineSpan
     *
     * @param originalText 原始文本（包含 __text__ 标记）
     * @param spannable 已移除标记的 SpannableString
     * @param startOffset SpannableString 在原始文本中的起始偏移量（默认为 0）
     */
    fun applyUnderlineSpans(
        originalText: String,
        spannable: SpannableString,
        startOffset: Int = 0,
    ) {
        // 查找所有 __text__ 格式的下划线标记，按位置排序
        val matches =
            UNDERLINE_PATTERN.findAll(originalText)
                .sortedBy { it.range.first }
                .toList()

        var removedChars = 0 // 已移除的字符数（__ 标记）

        matches.forEach { match ->
            val content = match.groupValues[1] // 下划线内的内容
            val matchStart = match.range.first // __text__ 的开始位置

            // 在 SpannableString 中的位置（需要减去已移除的 __ 标记）
            val contentStartInSpannable = startOffset + matchStart - removedChars
            val contentEndInSpannable = contentStartInSpannable + content.length

            // 应用下划线样式
            if (contentStartInSpannable >= 0 && contentEndInSpannable <= spannable.length) {
                spannable.setSpan(
                    UnderlineSpan(),
                    contentStartInSpannable,
                    contentEndInSpannable,
                    Spanned.SPAN_INCLUSIVE_INCLUSIVE,
                )
            }

            // 更新已移除的字符数（每个 __text__ 标记移除 4 个字符：两个 __）
            removedChars += 4
        }
    }

    /**
     * 创建带有下划线样式的 SpannableString
     * 一步完成移除标记和应用样式的操作
     *
     * @param text 包含 __text__ 标记的原始文本
     * @return 处理后的 SpannableString
     */
    fun createUnderlinedText(text: String): SpannableString {
        val textWithoutMarkers = removeUnderlineMarkers(text)
        val spannableString = SpannableString(textWithoutMarkers)
        applyUnderlineSpans(text, spannableString)
        return spannableString
    }
}
