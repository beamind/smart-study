package com.smartstudy.utils

/**
 * 集合扩展函数
 * 提供常用的集合操作简化方法
 *
 * 安全地获取列表元素
 * 如果索引超出范围，返回 null
 */
fun <T> List<T>.safeGet(index: Int): T? {
    return if (index in 0 until size) {
        this[index]
    } else {
        null
    }
}

/**
 * 检查列表是否为空或null
 */
fun <T> List<T>?.isNullOrEmpty(): Boolean {
    return this == null || this.isEmpty()
}

/**
 * 检查列表是否不为空
 */
fun <T> List<T>?.isNotNullOrEmpty(): Boolean {
    return !isNullOrEmpty()
}

/**
 * 安全地获取第一个元素
 */
fun <T> List<T>.safeFirst(): T? {
    return if (isNotEmpty()) first() else null
}

/**
 * 安全地获取最后一个元素
 */
fun <T> List<T>.safeLast(): T? {
    return if (isNotEmpty()) last() else null
}
