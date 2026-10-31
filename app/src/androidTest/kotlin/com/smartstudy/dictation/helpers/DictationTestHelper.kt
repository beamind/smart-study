package com.smartstudy.dictation.helpers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matcher
import org.hamcrest.Matchers.*
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * 听写测试辅助工具类
 * 提供测试中常用的工具方法
 */
object DictationTestHelper {
    private const val TAG = "DictationTestHelper"

    /**
     * 创建测试用的图片文件
     * @param text 图片上要绘制的文字（用于模拟听写内容）
     * @return 图片文件的Uri（使用FileProvider，可以被CropImageView正确读取）
     */
    fun createTestImage(text: String = "苹果 香蕉 橙子"): Uri {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // 使用external files dir而不是cache dir，与生产代码保持一致
        // 这样FileProvider配置已经支持，且更可靠
        val storageDir =
            context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)
                ?: context.filesDir // 如果external files dir不可用，回退到files dir
        val testImageFile = File(storageDir, "test_dictation_image_${System.currentTimeMillis()}.jpg")

        // 确保目录存在
        storageDir.mkdirs()

        // 创建一个简单的测试图片
        val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.WHITE)

        // 在图片上绘制文字（模拟听写内容）
        val canvas = Canvas(bitmap)
        val paint =
            Paint().apply {
                color = android.graphics.Color.BLACK
                textSize = 48f
                isAntiAlias = true
            }
        canvas.drawText(text, 100f, 300f, paint)

        // 保存图片，确保刷新输出流
        FileOutputStream(testImageFile).use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)) {
                throw IOException("Failed to compress bitmap")
            }
            out.flush()
        }

        bitmap.recycle()

        // 验证文件已创建且可读
        if (!testImageFile.exists() || !testImageFile.canRead()) {
            throw IOException("Test image file not accessible: ${testImageFile.absolutePath}")
        }

        // 使用FileProvider创建URI，而不是Uri.fromFile()
        // 这样CropImageView才能正确读取图片（Android 7.0+要求使用FileProvider）
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            testImageFile,
        )
    }

    /**
     * 等待视图出现
     * @param matcher 视图匹配器
     * @param timeoutMs 超时时间（毫秒）
     * @throws AssertionError 如果超时仍未找到视图
     */
    fun waitForView(
        matcher: Matcher<android.view.View>,
        timeoutMs: Long = 5000,
    ) {
        val startTime = System.currentTimeMillis()
        var lastException: Exception? = null
        var ambiguousCount = 0

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            try {
                // 检查视图是否存在且可见
                // 如果匹配器已经包含 isDisplayed()，这里会正确验证
                onView(matcher).check(matches(isDisplayed()))
                return
            } catch (e: androidx.test.espresso.AmbiguousViewMatcherException) {
                // 如果遇到歧义异常，等待一小段时间后重试
                // 这通常发生在视图动画或状态转换期间
                ambiguousCount++
                lastException = e

                // 如果连续遇到多次歧义异常，增加等待时间
                val sleepTime =
                    if (ambiguousCount > 10) {
                        200L // 如果连续遇到多次歧义，等待更长时间
                    } else {
                        50L // 否则快速重试
                    }
                Thread.sleep(sleepTime)
            } catch (e: androidx.test.espresso.NoMatchingViewException) {
                // 视图不存在，继续等待
                lastException = e
                Thread.sleep(100L)
            } catch (e: Exception) {
                lastException = e
                Thread.sleep(100L)
            }
        }

        // 如果最后遇到的是歧义异常，提供更详细的错误信息
        if (lastException is androidx.test.espresso.AmbiguousViewMatcherException) {
            throw AssertionError(
                "Multiple views matched the matcher within timeout: $timeoutMs ms (retried $ambiguousCount times). " +
                    "Try using a more specific matcher (e.g., add isClickable(), isEnabled(), or withText()). " +
                    "Matcher: $matcher",
                lastException,
            )
        }

        throw AssertionError(
            "View not found within timeout: $timeoutMs ms. Matcher: $matcher",
            lastException,
        )
    }

    /**
     * 等待视图包含指定文本
     * @param textMatcher 文本匹配器
     * @param timeoutMs 超时时间（毫秒）
     */
    fun waitForViewWithText(
        textMatcher: Matcher<String>,
        timeoutMs: Long = 5000,
    ) {
        waitForView(
            allOf(
                isDisplayed(),
                hasTextContent(textMatcher),
            ),
            timeoutMs,
        )
    }

    /**
     * 等待Activity启动（通过检查特定视图）
     * @param viewId 要检查的视图ID
     * @param timeoutMs 超时时间（毫秒）
     */
    fun waitForActivity(
        viewId: Int,
        timeoutMs: Long = 3000,
    ) {
        waitForView(
            allOf(
                isDisplayed(),
                withId(viewId),
            ),
            timeoutMs,
        )
    }

    /**
     * 将测试图片复制到目标URI指向的文件位置
     * 用于测试场景：当Mock拍照Intent时，需要将测试图片复制到DictationImageManager创建的文件位置
     * @param sourceUri 源图片URI（测试图片）
     * @param targetUri 目标URI（DictationImageManager创建的文件URI）
     */
    fun copyTestImageToTarget(
        sourceUri: Uri,
        targetUri: Uri,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        try {
            // 从FileProvider URI获取实际文件路径
            val sourceFile = getFileFromUri(context, sourceUri)
            val targetFile = getFileFromUri(context, targetUri)

            if (sourceFile == null || targetFile == null) {
                throw IOException("无法从URI获取文件路径: source=$sourceUri, target=$targetUri")
            }

            // 确保目标文件的父目录存在
            targetFile.parentFile?.mkdirs()

            // 复制文件
            FileInputStream(sourceFile).use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            }

            // 验证目标文件已创建且可读
            if (!targetFile.exists() || !targetFile.canRead()) {
                throw IOException("目标文件无法访问: ${targetFile.absolutePath}")
            }
        } catch (e: Exception) {
            throw IOException("复制测试图片失败: ${e.message}", e)
        }
    }

    /**
     * 从FileProvider URI获取实际文件
     * @param context 上下文
     * @param uri FileProvider URI
     * @return 文件对象，如果无法解析则返回null
     */
    private fun getFileFromUri(
        context: android.content.Context,
        uri: Uri,
    ): File? {
        return try {
            // 尝试从URI路径解析文件
            if (uri.scheme == "content" && uri.authority == "${context.packageName}.fileprovider") {
                // FileProvider URI格式: content://authority/path_segment1/path_segment2/...
                val pathSegments = uri.pathSegments
                if (pathSegments.isNotEmpty()) {
                    // 根据file_paths.xml配置，my_images映射到external-files-path/Pictures
                    val name = pathSegments.lastOrNull()
                    if (name != null) {
                        val storageDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)
                        if (storageDir != null) {
                            File(storageDir, name)
                        } else {
                            null
                        }
                    } else {
                        null
                    }
                } else {
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 清理测试图片文件
     * @param uri 图片文件的Uri（可能是FileProvider URI）
     */
    fun cleanupTestImage(uri: Uri?) {
        if (uri == null) return

        try {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            // 清理external files dir和cache dir中的测试图片
            val dirsToClean =
                listOf(
                    context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES),
                    context.cacheDir,
                    context.filesDir,
                ).filterNotNull()

            dirsToClean.forEach { dir ->
                if (dir.exists() && dir.isDirectory) {
                    dir.listFiles()?.forEach { file ->
                        if (file.isFile && (file.name.startsWith("test_dictation_image_") || file.name.startsWith("dictation_")) && file.name.endsWith(".jpg")) {
                            try {
                                file.delete()
                            } catch (e: Exception) {
                                // 忽略单个文件删除错误
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // 忽略清理错误
        }
    }

    /**
     * 创建文本内容匹配器（用于检查TextView的文本）
     */
    private fun hasTextContent(textMatcher: Matcher<String>): Matcher<android.view.View> {
        return object : org.hamcrest.TypeSafeMatcher<android.view.View>() {
            override fun describeTo(description: org.hamcrest.Description) {
                description.appendText("has text content matching: ")
                textMatcher.describeTo(description)
            }

            override fun matchesSafely(item: android.view.View): Boolean {
                if (item !is android.widget.TextView) {
                    return false
                }
                return textMatcher.matches(item.text.toString())
            }
        }
    }
}
