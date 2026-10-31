package com.smartstudy.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AppResultHelpers 单元测试
 * 测试 runAppCatching 函数的各种场景
 */
class AppResultHelpersTest {
    @Test
    fun `runAppCatching returns Success for successful operation`() =
        runTest {
            val result =
                runAppCatching("TestTag", "测试操作") {
                    "success"
                }

            assertTrue(result is AppResult.Success)
            if (result is AppResult.Success) {
                assertEquals("success", result.data)
            }
        }

    @Test
    fun `runAppCatching returns Failure for exception`() =
        runTest {
            val result =
                runAppCatching("TestTag", "测试操作") {
                    throw IllegalArgumentException("测试错误")
                }

            assertTrue(result is AppResult.Failure)
            if (result is AppResult.Failure) {
                assertEquals(AppErrorKind.InvalidData, result.error.kind)
                assertNotNull(result.error.message)
            }
        }

    @Test
    fun `runAppCatching rethrows CancellationException`() =
        runTest {
            try {
                runAppCatching("TestTag", "测试操作") {
                    throw CancellationException("协程取消")
                }
                assertTrue("应该抛出 CancellationException", false)
            } catch (e: CancellationException) {
                // 预期行为：CancellationException 应该被重新抛出
                // 这是测试代码，捕获异常是预期的
                assertTrue("应该抛出 CancellationException", true)
                throw e // 重新抛出以符合测试逻辑
            }
        }

    @Test
    fun `runAppCatching maps IOException correctly`() =
        runTest {
            val result =
                runAppCatching("TestTag", "测试操作") {
                    throw java.io.IOException("IO错误")
                }

            assertTrue(result is AppResult.Failure)
            if (result is AppResult.Failure) {
                assertEquals(AppErrorKind.Unknown, result.error.kind)
                assertNotNull(result.error.cause)
            }
        }

    @Test
    fun `runAppCatching handles RuntimeException`() =
        runTest {
            val result =
                runAppCatching("TestTag", "测试操作") {
                    throw RuntimeException("运行时错误")
                }

            assertTrue(result is AppResult.Failure)
            if (result is AppResult.Failure) {
                assertEquals(AppErrorKind.Unknown, result.error.kind)
            }
        }

    @Test
    fun `runAppCatching preserves exception cause`() =
        runTest {
            val originalException = IllegalArgumentException("原始错误")
            val result =
                runAppCatching("TestTag", "测试操作") {
                    throw originalException
                }

            assertTrue(result is AppResult.Failure)
            if (result is AppResult.Failure) {
                assertEquals(originalException, result.error.cause)
            }
        }
}
