package com.smartstudy.core

import com.alibaba.dashscope.exception.ApiException
import com.alibaba.dashscope.exception.NoApiKeyException
import com.alibaba.dashscope.exception.UploadFileException
import org.json.JSONException
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.TimeoutException

/**
 * 将各种异常统一映射为 AppError，便于 UI 与日志统一处理。
 *
 * 异常映射规则：
 * - [NoApiKeyException] -> [AppErrorKind.MissingApiKey]
 * - [TimeoutException] -> [AppErrorKind.NetworkOrApi]
 * - [SecurityException] -> [AppErrorKind.StoragePermission]
 * - [FileNotFoundException] -> [AppErrorKind.FileNotFound]
 * - [JSONException] -> [AppErrorKind.Parse]
 * - [IllegalArgumentException] -> [AppErrorKind.InvalidData]
 * - [IOException] -> 根据错误消息内容智能判断（存储空间、权限、文件损坏等）
 * - [ApiException] / [UploadFileException] -> [AppErrorKind.NetworkOrApi]
 * - 其他异常 -> [AppErrorKind.Unknown]
 *
 * @return 映射后的 [AppError] 对象，包含错误类型和用户友好的错误消息
 */
fun Throwable.toAppError(): AppError {
    // 使用映射表简化条件判断，降低复杂度
    val directMapping = getDirectExceptionMapping(this)
    if (directMapping != null) {
        return directMapping
    }

    // 需要特殊处理的异常类型
    return when (this) {
        is IllegalStateException -> mapIllegalStateException(this)
        is IOException -> mapIOException(this)
        else -> AppError(AppErrorKind.Unknown, message ?: "未知错误", this)
    }
}

/**
 * 获取直接映射的异常（无需特殊处理）
 */
private fun getDirectExceptionMapping(exception: Throwable): AppError? {
    return when (exception) {
        is NoApiKeyException -> AppError(AppErrorKind.MissingApiKey, "未配置 DASHSCOPE_API_KEY", exception)
        is TimeoutException -> AppError(AppErrorKind.NetworkOrApi, exception.message ?: "请求超时", exception)
        is SecurityException -> AppError(AppErrorKind.StoragePermission, exception.message ?: "权限不足", exception)
        is FileNotFoundException -> AppError(AppErrorKind.FileNotFound, exception.message ?: "文件不存在", exception)
        is JSONException -> AppError(AppErrorKind.Parse, exception.message ?: "数据解析失败", exception)
        is IllegalArgumentException -> AppError(AppErrorKind.InvalidData, exception.message ?: "输入数据不合法", exception)
        is ApiException,
        is UploadFileException,
        -> AppError(AppErrorKind.NetworkOrApi, exception.message ?: "网络/接口请求失败", exception)
        else -> null
    }
}

/**
 * 映射 IllegalStateException 为 AppError
 */
private fun mapIllegalStateException(exception: IllegalStateException): AppError {
    val msg = exception.message ?: "状态错误"
    return when {
        isApiKeyMissingError(msg) -> {
            AppError(AppErrorKind.MissingApiKey, msg, exception)
        }
        isImageSizeError(msg) -> {
            AppError(AppErrorKind.InvalidData, msg, exception)
        }
        else -> {
            AppError(AppErrorKind.Unknown, msg, exception)
        }
    }
}

/**
 * 检查是否是API密钥缺失错误
 */
private fun isApiKeyMissingError(message: String): Boolean {
    return message.contains("DASHSCOPE_API_KEY", ignoreCase = true)
}

/**
 * 检查是否是图片大小错误
 */
private fun isImageSizeError(message: String): Boolean {
    return message.contains("图片过大") || message.contains("Base64", ignoreCase = true)
}

/**
 * 映射 IOException 为 AppError
 */
private fun mapIOException(exception: IOException): AppError {
    val msg = exception.message ?: "文件操作失败"
    return when {
        isStorageSpaceError(msg) -> {
            AppError(AppErrorKind.StorageSpaceFull, "存储空间不足，请清理空间后重试", exception)
        }
        isStoragePermissionError(msg) -> {
            AppError(AppErrorKind.StoragePermission, "文件访问权限不足", exception)
        }
        isFileCorruptedError(msg) -> {
            AppError(AppErrorKind.FileCorrupted, "文件已损坏，将尝试使用备份文件", exception)
        }
        else -> {
            AppError(AppErrorKind.Unknown, msg, exception)
        }
    }
}

/**
 * 检查是否是存储空间错误
 */
private fun isStorageSpaceError(message: String): Boolean {
    val keywords = listOf("空间", "space", "ENOSPC")
    return keywords.any { message.contains(it, ignoreCase = true) }
}

/**
 * 检查是否是存储权限错误
 */
private fun isStoragePermissionError(message: String): Boolean {
    val keywords = listOf("权限", "permission", "denied")
    return keywords.any { message.contains(it, ignoreCase = true) }
}

/**
 * 检查是否是文件损坏错误
 */
private fun isFileCorruptedError(message: String): Boolean {
    val keywords = listOf("损坏", "corrupt", "格式")
    return keywords.any { message.contains(it, ignoreCase = true) }
}
