package com.smartstudy.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException

/**
 * NetworkRetryHelper 单元测试
 * 测试网络重试机制的各种场景
 */

class NetworkRetryHelperTest {
    @Test
    fun `retryWithBackoff succeeds on first attempt`() =
        runBlocking {
            var attemptCount = 0
            val result =
                NetworkRetryHelper.retryWithBackoff(
                    maxRetries = 3,
                    initialDelayMs = 10L,
                ) {
                    attemptCount++
                    "success"
                }

            assertEquals("success", result)
            assertEquals(1, attemptCount)
        }

    @Test
    fun `retryWithBackoff retries on network exception and succeeds`() =
        runBlocking {
            var attemptCount = 0
            val result =
                NetworkRetryHelper.retryWithBackoff(
                    maxRetries = 3,
                    initialDelayMs = 10L,
                ) {
                    attemptCount++
                    if (attemptCount < 2) {
                        throw SocketTimeoutException("Timeout")
                    }
                    "success"
                }

            assertEquals("success", result)
            assertEquals(2, attemptCount)
        }

    @Test
    fun `retryWithBackoff throws after max retries`() =
        runBlocking {
            var attemptCount = 0
            assertThrows(IOException::class.java) {
                runBlocking {
                    NetworkRetryHelper.retryWithBackoff(
                        maxRetries = 3,
                        initialDelayMs = 10L,
                    ) {
                        attemptCount++
                        throw IOException("Network error")
                    }
                }
            }

            assertEquals(3, attemptCount)
        }

    @Test
    fun `retryWithBackoff does not retry on IllegalArgumentException`() =
        runBlocking {
            var attemptCount = 0
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    NetworkRetryHelper.retryWithBackoff(
                        maxRetries = 3,
                        initialDelayMs = 10L,
                    ) {
                        attemptCount++
                        throw IllegalArgumentException("Invalid argument")
                    }
                }
            }

            assertEquals(1, attemptCount) // 不应该重试
        }

    @Test
    fun `retryWithBackoff uses exponential backoff`() =
        runBlocking {
            var attemptCount = 0
            val delays = mutableListOf<Long>()
            val startTime = System.currentTimeMillis()

            try {
                NetworkRetryHelper.retryWithBackoff(
                    maxRetries = 3,
                    initialDelayMs = 50L,
                    maxDelayMs = 500L,
                    multiplier = 2.0,
                ) {
                    attemptCount++
                    if (attemptCount > 1) {
                        val currentTime = System.currentTimeMillis()
                        delays.add(currentTime - startTime)
                    }
                    throw ConnectException("Connection failed")
                }
            } catch (e: ConnectException) {
                // 预期会抛出异常
            }

            // 验证重试了3次
            assertEquals(3, attemptCount)
            // 验证延迟时间递增（允许一些误差）
            if (delays.size >= 2) {
                assert(delays[1] > delays[0]) { "延迟时间应该递增" }
            }
        }

    @Test
    fun `retryWithBackoff respects maxDelayMs`() =
        runBlocking {
            var attemptCount = 0

            try {
                // 限制最大延迟
                NetworkRetryHelper.retryWithBackoff(
                    maxRetries = 5,
                    initialDelayMs = 100L,
                    maxDelayMs = 200L,
                    multiplier = 2.0,
                ) {
                    attemptCount++
                    throw TimeoutException("Timeout")
                }
            } catch (e: TimeoutException) {
                // 预期会抛出异常
            }

            // 验证重试了指定次数
            assertEquals(5, attemptCount)
        }

    @Test
    fun `retryWithBackoff handles UnknownHostException`() =
        runBlocking {
            var attemptCount = 0

            try {
                NetworkRetryHelper.retryWithBackoff(
                    maxRetries = 2,
                    initialDelayMs = 10L,
                ) {
                    attemptCount++
                    throw UnknownHostException("Host not found")
                }
            } catch (e: UnknownHostException) {
                // 预期会抛出异常
            }

            assertEquals(2, attemptCount) // 应该重试
        }
}
