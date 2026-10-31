package com.smartstudy.core

import android.util.Log
import com.smartstudy.BuildConfig

/**
 * 统一的日志工具类
 * 提供统一的日志 TAG 命名规范和日志级别管理
 */
object Logger {
    private const val DEFAULT_TAG_PREFIX = "SmartStudy"
    private const val MAX_TAG_LENGTH = 23 // Android 限制 TAG 最大长度为 23

    /**
     * 创建标准化的日志 TAG
     * 格式：SmartStudy_ClassName
     */
    fun createTag(clazz: Class<*>): String {
        val className = clazz.simpleName
        val tag = "${DEFAULT_TAG_PREFIX}_$className"
        return if (tag.length > MAX_TAG_LENGTH) {
            // 如果超过长度限制，截取类名
            val truncatedClassName = className.take(MAX_TAG_LENGTH - DEFAULT_TAG_PREFIX.length - 1)
            "${DEFAULT_TAG_PREFIX}_$truncatedClassName"
        } else {
            tag
        }
    }

    /**
     * DEBUG 级别日志（仅在 Debug 构建时输出）
     */
    fun d(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        if (BuildConfig.DEBUG) {
            if (throwable != null) {
                Log.d(tag, message, throwable)
            } else {
                Log.d(tag, message)
            }
        }
    }

    /**
     * INFO 级别日志
     * 用于记录关键操作和状态变化
     */
    fun i(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        if (throwable != null) {
            Log.i(tag, message, throwable)
        } else {
            Log.i(tag, message)
        }
    }

    /**
     * INFO 级别日志（带上下文信息）
     * 用于记录关键操作，包含更多上下文信息便于问题定位
     */
    fun i(
        tag: String,
        message: String,
        context: Map<String, Any?>,
    ) {
        val contextStr = context.entries.joinToString(", ") { "${it.key}=${it.value}" }
        Log.i(tag, "$message [$contextStr]")
    }

    /**
     * WARN 级别日志
     */
    fun w(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        if (throwable != null) {
            Log.w(tag, message, throwable)
        } else {
            Log.w(tag, message)
        }
    }

    /**
     * ERROR 级别日志
     * 用于记录错误，包含完整堆栈信息
     */
    fun e(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        if (throwable != null) {
            Log.e(tag, message, throwable)
        } else {
            Log.e(tag, message)
        }
    }

    /**
     * ERROR 级别日志（带上下文信息）
     * 用于记录错误，包含上下文信息和完整堆栈
     */
    fun e(
        tag: String,
        message: String,
        context: Map<String, Any?>,
        throwable: Throwable? = null,
    ) {
        val contextStr = context.entries.joinToString(", ") { "${it.key}=${it.value}" }
        if (throwable != null) {
            Log.e(tag, "$message [$contextStr]", throwable)
        } else {
            Log.e(tag, "$message [$contextStr]")
        }
    }
}
