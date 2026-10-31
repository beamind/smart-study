package com.smartstudy.dictation.core

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ImageProcessingHelper 单元测试
 * 测试图片优化和Bitmap回收功能
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ImageProcessingHelperTest {
    /**
     * 创建一个测试用的Bitmap
     */
    private fun createTestBitmap(
        width: Int,
        height: Int,
    ): Bitmap {
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    }

    @Test
    fun `optimizeBitmapForRecognition returns original when size is within limit`() {
        // 创建一个小于限制的图片（1280x800）
        val bitmap = createTestBitmap(1280, 800)

        val result = ImageProcessingHelper.optimizeBitmapForRecognition(bitmap)

        // 应该返回原图（引用相同）
        assertTrue(result === bitmap)
    }

    @Test
    fun `optimizeBitmapForRecognition scales down large image`() {
        // 创建一个大于限制的图片（2000x1500）
        val bitmap = createTestBitmap(2000, 1500)

        val result = ImageProcessingHelper.optimizeBitmapForRecognition(bitmap)

        // 应该返回缩放后的图片
        assertNotNull(result)
        assertTrue(result.width <= 1280)
        assertTrue(result.height <= 1280)

        // 清理
        if (result !== bitmap) {
            bitmap.recycle()
        }
        result.recycle()
    }

    @Test
    fun `optimizeBitmapForRecognition handles zero size bitmap`() {
        // 创建一个无效尺寸的图片
        val bitmap = createTestBitmap(0, 0)

        val result = ImageProcessingHelper.optimizeBitmapForRecognition(bitmap)

        // 应该返回原图
        assertEquals(bitmap, result)
    }

    @Test
    fun `optimizeBitmapForRecognition handles very large image`() {
        // 创建一个非常大的图片（5000x5000）
        val bitmap = createTestBitmap(5000, 5000)

        val result = ImageProcessingHelper.optimizeBitmapForRecognition(bitmap)

        // 应该返回缩放后的图片
        assertNotNull(result)
        assertTrue(result.width <= 1280)
        assertTrue(result.height <= 1280)

        // 清理
        if (result !== bitmap) {
            bitmap.recycle()
        }
        result.recycle()
    }

    @Test
    fun `recycleBitmap handles null safely`() {
        // 应该不会抛出异常
        ImageProcessingHelper.recycleBitmap(null)
    }

    @Test
    fun `recycleBitmap recycles valid bitmap`() {
        val bitmap = createTestBitmap(100, 100)

        // 回收前应该未回收
        assertFalse(bitmap.isRecycled)

        // 回收
        ImageProcessingHelper.recycleBitmap(bitmap)

        // 回收后应该已回收
        assertTrue(bitmap.isRecycled)
    }

    @Test
    fun `recycleBitmap handles already recycled bitmap`() {
        val bitmap = createTestBitmap(100, 100)
        bitmap.recycle()

        // 再次回收应该不会抛出异常
        ImageProcessingHelper.recycleBitmap(bitmap)
    }
}
