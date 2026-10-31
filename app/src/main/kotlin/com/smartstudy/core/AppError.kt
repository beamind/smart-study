package com.smartstudy.core

/**
 * 统一的错误模型：给 UI 展示的 message + 可选的 cause 方便日志定位。
 *
 * 初学者建议：业务层尽量返回结构化错误，而不是到处 throw Exception。
 *
 * @param kind 错误类型，用于 UI 决策（如是否显示配置 API Key 对话框）
 * @param message 用户友好的错误消息
 * @param cause 原始异常（可选），用于日志定位问题
 */
data class AppError(
    val kind: AppErrorKind,
    val message: String,
    val cause: Throwable? = null,
)
