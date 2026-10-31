package com.smartstudy.utils

/**
 * String 扩展函数
 * 提供常用的字符串操作简化方法
 *
 * 检查字符串是否为空或仅包含空白字符
 */
fun String?.isBlankOrEmpty(): Boolean {
    return this.isNullOrBlank() || this.isEmpty()
}

/**
 * 安全地截取字符串
 * 如果长度超出范围，返回原字符串
 */
fun String.safeSubstring(
    startIndex: Int,
    endIndex: Int = length,
): String {
    val safeStart = startIndex.coerceIn(0, length)
    val safeEnd = endIndex.coerceIn(safeStart, length)
    return if (safeStart < safeEnd) {
        substring(safeStart, safeEnd)
    } else {
        ""
    }
}

/**
 * 安全地截取字符串（从开始到指定长度）
 */
fun String.safeTake(n: Int): String {
    return if (n >= 0 && n <= length) {
        take(n)
    } else if (n < 0) {
        ""
    } else {
        this
    }
}

/**
 * 限制字符串最大长度，超出部分用省略号表示
 */
fun String.limitLength(
    maxLength: Int,
    ellipsis: String = "...",
): String {
    return if (length <= maxLength) {
        this
    } else {
        take(maxLength - ellipsis.length) + ellipsis
    }
}
