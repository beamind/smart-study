package com.smartstudy.core

import com.alibaba.dashscope.exception.NoApiKeyException
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeoutException

/**
 * AppErrorMapper 单元测试
 * 测试异常到AppError的映射逻辑
 */
class AppErrorMapperTest {
    @Test
    fun `maps IllegalArgumentException to InvalidData`() {
        val err = IllegalArgumentException("bad").toAppError()
        assertEquals(AppErrorKind.InvalidData, err.kind)
        assertEquals("bad", err.message)
    }

    @Test
    fun `maps JSONException to Parse`() {
        val err = JSONException("bad json").toAppError()
        assertEquals(AppErrorKind.Parse, err.kind)
        assertEquals("bad json", err.message)
    }

    @Test
    fun `maps SecurityException to StoragePermission`() {
        val err = SecurityException("no permission").toAppError()
        assertEquals(AppErrorKind.StoragePermission, err.kind)
        assertEquals("no permission", err.message)
    }

    @Test
    fun `maps NoApiKeyException to MissingApiKey`() {
        val err = NoApiKeyException().toAppError()
        assertEquals(AppErrorKind.MissingApiKey, err.kind)
    }

    @Test
    fun `maps api key missing signal to MissingApiKey`() {
        val err = IllegalStateException("未配置 DASHSCOPE_API_KEY").toAppError()
        assertEquals(AppErrorKind.MissingApiKey, err.kind)
    }

    @Test
    fun `maps TimeoutException to NetworkOrApi`() {
        val err = TimeoutException("请求超时").toAppError()
        assertEquals(AppErrorKind.NetworkOrApi, err.kind)
    }

    @Test
    fun `maps SocketTimeoutException to NetworkOrApi`() {
        val err = SocketTimeoutException("连接超时").toAppError()
        assertEquals(AppErrorKind.NetworkOrApi, err.kind)
    }

    @Test
    fun `maps FileNotFoundException to FileNotFound`() {
        val err = FileNotFoundException("文件不存在").toAppError()
        assertEquals(AppErrorKind.FileNotFound, err.kind)
    }

    @Test
    fun `maps IOException with space message to StorageSpaceFull`() {
        val err = IOException("存储空间不足").toAppError()
        assertEquals(AppErrorKind.StorageSpaceFull, err.kind)
    }

    @Test
    fun `maps IOException with permission message to StoragePermission`() {
        val err = IOException("权限不足").toAppError()
        assertEquals(AppErrorKind.StoragePermission, err.kind)
    }

    @Test
    fun `maps IOException with corrupt message to FileCorrupted`() {
        val err = IOException("文件已损坏").toAppError()
        assertEquals(AppErrorKind.FileCorrupted, err.kind)
    }

    @Test
    fun `preserves exception cause`() {
        val originalException = IllegalArgumentException("原始错误")
        val err = originalException.toAppError()
        assertEquals(originalException, err.cause)
    }

    @Test
    fun `handles exception with null message`() {
        val err = Exception(null as String?).toAppError()
        assertNotNull(err.message)
        assertEquals(AppErrorKind.Unknown, err.kind)
    }
}
