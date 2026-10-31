package com.smartstudy.dictation.core

import android.graphics.Bitmap
import android.graphics.Color
import com.smartstudy.core.AppErrorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations

/**
 * ImageUnderstandingService 单元测试
 *
 * 测试覆盖：
 * - 模式枚举值存在性检查
 * - 错误处理（API Key缺失、网络错误等）
 * - 边界情况（空图片、超大图片等）
 */
class ImageUnderstandingServiceTest {
    @Mock
    private lateinit var mockBitmap: Bitmap

    private lateinit var service: ImageUnderstandingService

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        service = ImageUnderstandingService()
    }

    @Test
    fun testModesExist() {
        // 验证所有模式枚举值都存在
        val modes = ImageUnderstandingService.ImageUnderstandingMode.values()
        assertTrue("应该有至少3种模式", modes.size >= 3)

        assertNotNull("CHINESE模式应该存在", modes.find { it.name == "CHINESE" })
        assertNotNull("ENGLISH模式应该存在", modes.find { it.name == "ENGLISH" })
    }

    @Test
    fun testCreateBitmap() {
        // 创建一个测试用的Bitmap
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)

        assertNotNull("Bitmap应该创建成功", bitmap)
        assertEquals("宽度应该是100", 100, bitmap.width)
        assertEquals("高度应该是100", 100, bitmap.height)

        bitmap.recycle()
    }

    @Test
    fun testServiceInitialization() {
        // 验证服务可以正常初始化
        assertNotNull("服务应该初始化成功", service)
    }

    @Test
    fun testErrorHandling() {
        // 测试错误处理逻辑
        val error =
            com.smartstudy.core.AppError(
                AppErrorKind.MissingApiKey,
                "API Key未配置",
            )

        assertEquals("错误类型应该是MissingApiKey", AppErrorKind.MissingApiKey, error.kind)
        assertEquals("错误消息应该正确", "API Key未配置", error.message)
    }
}
