package com.smartstudy.dictation.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Debug
import androidx.exifinterface.media.ExifInterface
import com.smartstudy.core.Logger
import java.io.InputStream

/**
 * 图片处理进度回调
 *
 * @param progress 处理进度（0-100）
 * @param message 进度消息
 */
typealias ImageProcessingProgressCallback = (progress: Int, message: String) -> Unit

/**
 * 图片处理工具类
 * 提取图片处理相关的重复逻辑
 *
 * 功能特性：
 * - 图片缩放优化（降低内存占用）
 * - 安全的 Bitmap 回收
 * - OOM 保护（自动降级处理）
 * - 内存监控和进度回调
 *
 * 使用示例：
 * ```
 * val optimized = ImageProcessingHelper.optimizeBitmapForRecognition(
 *     bitmap,
 *     onProgress = { progress, message ->
 *         // 更新UI进度
 *     }
 * )
 * // 使用优化后的图片
 * ImageProcessingHelper.recycleBitmap(oldBitmap)
 * ```
 */
object ImageProcessingHelper {
    private const val TAG = "ImageProcessingHelper"

    /**
     * 获取当前内存使用情况（MB）
     */
    private fun getMemoryUsageMB(): Long {
        val runtime = Runtime.getRuntime()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        return usedMemory / (1024 * 1024)
    }

    /**
     * 记录内存使用情况
     */
    private fun logMemoryUsage(operation: String) {
        val memoryMB = getMemoryUsageMB()
        val nativeHeapMB = Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
        Logger.d(TAG, "$operation - 内存使用: Java堆=${memoryMB}MB, Native堆=${nativeHeapMB}MB")
    }

    /**
     * 将输入图片缩放到更适合网络上传/模型处理的尺寸，显著降低 Base64 与内存峰值。
     *
     * 优化策略：
     * 1. 如果图片尺寸在限制范围内，直接返回原图
     * 2. 如果超出限制，按比例缩放到最大边长限制
     * 3. 如果缩放时发生OOM，自动降级到更小的尺寸
     * 4. 如果仍然失败，返回原图（由调用方处理）
     *
     * @param input 原始 Bitmap，不能为null
     * @param onProgress 进度回调（可选）
     * @return 优化后的 Bitmap（如果不需要缩放，返回原图）
     * @throws IllegalArgumentException 如果input为null或尺寸无效
     */
    fun optimizeBitmapForRecognition(
        input: Bitmap,
        onProgress: ImageProcessingProgressCallback? = null,
    ): Bitmap {
        onProgress?.invoke(0, "开始处理图片")
        logMemoryUsage("处理前")
        if (input.isRecycled) {
            Logger.w(TAG, "输入的Bitmap已被回收，无法优化")
            throw IllegalArgumentException("Bitmap已被回收")
        }

        val w = input.width
        val h = input.height
        if (w <= 0 || h <= 0) {
            Logger.w(TAG, "Bitmap尺寸无效: ${w}x$h")
            return input
        }

        val maxSide = maxOf(w, h)
        val maxAllowed = com.smartstudy.core.AppConfig.MAX_INPUT_IMAGE_SIDE_PX

        // 如果图片尺寸在限制范围内，直接返回
        if (maxSide <= maxAllowed) {
            onProgress?.invoke(100, "图片尺寸合适，无需缩放")
            logMemoryUsage("处理后")
            return input
        }

        onProgress?.invoke(30, "计算缩放比例")

        // 计算缩放比例
        val scale = maxAllowed.toFloat() / maxSide.toFloat()
        val newW = (w * scale).toInt().coerceAtLeast(1)
        val newH = (h * scale).toInt().coerceAtLeast(1)

        Logger.d(TAG, "缩放图片: ${w}x$h -> ${newW}x$newH (scale=$scale)")
        onProgress?.invoke(50, "正在缩放图片...")

        return try {
            val scaled = Bitmap.createScaledBitmap(input, newW, newH, true)
            onProgress?.invoke(90, "缩放完成")
            logMemoryUsage("缩放后")
            onProgress?.invoke(100, "处理完成")
            scaled
        } catch (oom: OutOfMemoryError) {
            Logger.w(TAG, "缩放图片 OOM，尝试更小尺寸", oom)
            onProgress?.invoke(60, "内存不足，尝试降级处理")
            logMemoryUsage("OOM时")
            // 尝试使用降级尺寸
            tryFallbackScale(input, w, h, maxSide, onProgress)
        } catch (e: OutOfMemoryError) {
            Logger.w(TAG, "内存不足，缩放图片失败，使用原图", e)
            onProgress?.invoke(100, "处理失败，使用原图")
            input
        } catch (e: IllegalStateException) {
            Logger.w(TAG, "状态错误，缩放图片失败，使用原图: ${e.message}", e)
            onProgress?.invoke(100, "处理失败，使用原图")
            input
        }
    }

    /**
     * 尝试使用降级尺寸缩放图片（当正常缩放发生OOM时）
     *
     * @param input 原始Bitmap
     * @param w 原始宽度
     * @param h 原始高度
     * @param maxSide 原始最大边长
     * @param onProgress 进度回调（可选）
     * @return 缩放后的Bitmap，如果失败返回原图
     */
    private fun tryFallbackScale(
        input: Bitmap,
        w: Int,
        h: Int,
        maxSide: Int,
        onProgress: ImageProcessingProgressCallback? = null,
    ): Bitmap {
        val fallbackMax = com.smartstudy.core.AppConfig.FALLBACK_IMAGE_SIDE_PX
        val fallbackScale = fallbackMax.toFloat() / maxSide.toFloat()
        val fw = (w * fallbackScale).toInt().coerceAtLeast(1)
        val fh = (h * fallbackScale).toInt().coerceAtLeast(1)

        Logger.d(TAG, "尝试降级缩放: ${w}x$h -> ${fw}x$fh")
        onProgress?.invoke(70, "使用降级尺寸缩放...")

        return runCatching {
            val scaled = Bitmap.createScaledBitmap(input, fw, fh, true)
            onProgress?.invoke(90, "降级缩放完成")
            logMemoryUsage("降级缩放后")
            scaled
        }.getOrElse { e ->
            Logger.e(TAG, "降级缩放也失败，返回原图", e)
            onProgress?.invoke(100, "降级缩放失败，使用原图")
            input
        }
    }

    /**
     * 安全回收 Bitmap
     *
     * 注意：
     * - 只能回收通过 Bitmap.createBitmap() 等方法创建的 Bitmap
     * - 不能回收从资源文件、Drawable 等获取的 Bitmap
     * - 回收后的 Bitmap 不能再使用
     *
     * @param bitmap 要回收的 Bitmap，可以为 null
     */
    fun recycleBitmap(bitmap: Bitmap?) {
        bitmap?.let {
            try {
                // 检查是否已被回收
                if (it.isRecycled) {
                    Logger.w(TAG, "尝试回收已回收的 Bitmap")
                    return
                }

                // 检查 Bitmap 是否可回收（从资源加载的 Bitmap 不可回收）
                // 这里我们尝试回收，如果失败会捕获异常
                it.recycle()
                Logger.d(TAG, "Bitmap 已成功回收")
            } catch (e: IllegalStateException) {
                // 某些 Bitmap（如从资源加载的）不能回收，这是正常情况
                Logger.d(TAG, "Bitmap 回收失败（可能是系统管理的 Bitmap）: ${e.message}")
            } catch (e: java.lang.RuntimeException) {
                // 某些 Bitmap（如从资源加载的）不能回收，这是正常情况
                Logger.d(TAG, "Bitmap 回收失败（可能是系统管理的 Bitmap）: ${e.message}")
            }
        }
    }

    /**
     * 根据 EXIF orientation 旋转 Bitmap，保持拍照时的原始方向
     *
     * 这个方法会读取图片的 EXIF orientation 信息，并根据该信息旋转 Bitmap，
     * 确保图片按照拍照时的方向显示，而不是被自动旋转。
     *
     * @param bitmap 原始 Bitmap
     * @param imageUri 图片 URI
     * @param context Context 用于访问 ContentResolver
     * @return 旋转后的 Bitmap（如果需要旋转），否则返回原始 Bitmap
     */
    fun rotateBitmapToOriginalOrientation(
        bitmap: Bitmap,
        imageUri: Uri,
        context: android.content.Context,
    ): Bitmap {
        return try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(imageUri)
            inputStream?.use { stream ->
                val exif = ExifInterface(stream)
                val orientation =
                    exif.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL,
                    )

                val matrix = Matrix()
                var needsRotation = false

                when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> {
                        matrix.postRotate(90f)
                        needsRotation = true
                        Logger.d(TAG, "图片需要旋转 90 度")
                    }
                    ExifInterface.ORIENTATION_ROTATE_180 -> {
                        matrix.postRotate(180f)
                        needsRotation = true
                        Logger.d(TAG, "图片需要旋转 180 度")
                    }
                    ExifInterface.ORIENTATION_ROTATE_270 -> {
                        matrix.postRotate(270f)
                        needsRotation = true
                        Logger.d(TAG, "图片需要旋转 270 度")
                    }
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> {
                        matrix.postScale(-1f, 1f)
                        needsRotation = true
                        Logger.d(TAG, "图片需要水平翻转")
                    }
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                        matrix.postScale(1f, -1f)
                        needsRotation = true
                        Logger.d(TAG, "图片需要垂直翻转")
                    }
                    ExifInterface.ORIENTATION_TRANSPOSE -> {
                        matrix.postRotate(90f)
                        matrix.postScale(-1f, 1f)
                        needsRotation = true
                        Logger.d(TAG, "图片需要转置")
                    }
                    ExifInterface.ORIENTATION_TRANSVERSE -> {
                        matrix.postRotate(270f)
                        matrix.postScale(-1f, 1f)
                        needsRotation = true
                        Logger.d(TAG, "图片需要横向转置")
                    }
                    else -> {
                        // ORIENTATION_NORMAL 或其他，不需要旋转
                        Logger.d(TAG, "图片方向正常，无需旋转 (orientation=$orientation)")
                        return bitmap
                    }
                }

                if (needsRotation) {
                    // 创建旋转后的 Bitmap
                    val rotatedBitmap =
                        Bitmap.createBitmap(
                            bitmap,
                            0,
                            0,
                            bitmap.width,
                            bitmap.height,
                            matrix,
                            true,
                        )

                    // 如果创建了新的 Bitmap，回收原始 Bitmap
                    if (rotatedBitmap !== bitmap) {
                        recycleBitmap(bitmap)
                    }

                    rotatedBitmap
                } else {
                    bitmap
                }
            } ?: bitmap
        } catch (e: Exception) {
            Logger.w(TAG, "处理 EXIF orientation 失败，使用原始图片", e)
            bitmap
        }
    }

    /**
     * 从 URI 加载 Bitmap 并处理 EXIF orientation
     *
     * 这个方法会从 URI 加载 Bitmap，并根据 EXIF orientation 信息旋转图片，
     * 确保图片按照拍照时的方向显示。
     *
     * @param imageUri 图片 URI
     * @param context Context 用于访问 ContentResolver
     * @return 处理后的 Bitmap，如果加载失败返回 null
     */
    fun loadBitmapWithOrientation(
        imageUri: Uri,
        context: android.content.Context,
    ): Bitmap? {
        return try {
            // 先加载 Bitmap
            val inputStream = context.contentResolver.openInputStream(imageUri)
            val bitmap =
                inputStream?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }

            if (bitmap != null) {
                // 根据 EXIF orientation 旋转 Bitmap
                rotateBitmapToOriginalOrientation(bitmap, imageUri, context)
            } else {
                Logger.w(TAG, "无法从 URI 解码 Bitmap: $imageUri")
                null
            }
        } catch (e: Exception) {
            Logger.e(TAG, "从 URI 加载 Bitmap 失败: $imageUri", e)
            null
        }
    }
}
