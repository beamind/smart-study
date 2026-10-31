package com.smartstudy.dictation.core.interfaces

import android.graphics.Bitmap
import com.smartstudy.core.AppResult
import com.smartstudy.dictation.core.ImageUnderstandingService

/**
 * 图片理解服务接口
 *
 * 定义图片理解服务的基本功能，便于测试和扩展。
 */
interface IImageUnderstandingService {
    /**
     * 图片理解模式
     */
    enum class ImageUnderstandingMode {
        /** 中文听写模式：从图片中提取中文词语 */
        CHINESE_DICTATION,

        /** 英文听写模式：从图片中提取英文单词/短语 */
        ENGLISH_DICTATION,

        /** 通用模式：可用于错题识别等场景，支持自定义提示词 */
        GENERIC,
    }

    /**
     * 从图片中提取词语/短语（默认通用模式）
     */
    suspend fun understandWords(bitmap: Bitmap): AppResult<List<String>>

    /**
     * 从图片中提取词语/短语（带自定义提示）
     */
    suspend fun understandWords(
        bitmap: Bitmap,
        prompt: String?,
    ): AppResult<List<String>>

    /**
     * 从图片中提取词语/短语（指定场景模式）
     */
    suspend fun understandWords(
        bitmap: Bitmap,
        mode: ImageUnderstandingMode,
    ): AppResult<List<String>>

    /**
     * 从图片中提取词语/短语（指定场景模式 + 可选自定义提示）
     */
    suspend fun understandWords(
        bitmap: Bitmap,
        mode: ImageUnderstandingMode,
        prompt: String?,
    ): AppResult<List<String>>
}

/**
 * 将 ImageUnderstandingService.ImageUnderstandingMode 转换为接口中的枚举
 */
fun ImageUnderstandingService.ImageUnderstandingMode.toInterfaceMode(): IImageUnderstandingService.ImageUnderstandingMode {
    return when (this) {
        ImageUnderstandingService.ImageUnderstandingMode.CHINESE_DICTATION ->
            IImageUnderstandingService.ImageUnderstandingMode.CHINESE_DICTATION
        ImageUnderstandingService.ImageUnderstandingMode.ENGLISH_DICTATION ->
            IImageUnderstandingService.ImageUnderstandingMode.ENGLISH_DICTATION
        ImageUnderstandingService.ImageUnderstandingMode.GENERIC ->
            IImageUnderstandingService.ImageUnderstandingMode.GENERIC
    }
}

/**
 * 将接口中的枚举转换为 ImageUnderstandingService.ImageUnderstandingMode
 */
fun IImageUnderstandingService.ImageUnderstandingMode.toServiceMode(): ImageUnderstandingService.ImageUnderstandingMode {
    return when (this) {
        IImageUnderstandingService.ImageUnderstandingMode.CHINESE_DICTATION ->
            ImageUnderstandingService.ImageUnderstandingMode.CHINESE_DICTATION
        IImageUnderstandingService.ImageUnderstandingMode.ENGLISH_DICTATION ->
            ImageUnderstandingService.ImageUnderstandingMode.ENGLISH_DICTATION
        IImageUnderstandingService.ImageUnderstandingMode.GENERIC ->
            ImageUnderstandingService.ImageUnderstandingMode.GENERIC
    }
}
