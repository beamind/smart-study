package com.smartstudy.core

/**
 * 错误类型：用于 UI 决策（是否弹"配置 API Key"提示等）。
 *
 * 错误类型说明：
 * - [MissingApiKey]: API Key 未配置，需要提示用户配置
 * - [NetworkOrApi]: 网络或 API 请求失败，可能需要重试
 * - [StoragePermission]: 存储权限不足，需要请求权限
 * - [StorageSpaceFull]: 存储空间不足，需要清理空间
 * - [FileCorrupted]: 文件损坏，可能需要恢复备份
 * - [FileNotFound]: 文件不存在，可能需要创建新文件
 * - [InvalidData]: 输入数据不合法
 * - [Parse]: 数据解析失败
 * - [Unknown]: 未知错误
 */
enum class AppErrorKind {
    /** API Key 未配置 */
    MissingApiKey,

    /** 网络或 API 请求失败 */
    NetworkOrApi,

    /** 存储权限不足 */
    StoragePermission,

    /** 存储空间不足 */
    StorageSpaceFull,

    /** 文件损坏 */
    FileCorrupted,

    /** 文件不存在 */
    FileNotFound,

    /** 输入数据不合法 */
    InvalidData,

    /** 数据解析失败 */
    Parse,

    /** 未知错误 */
    Unknown,
}
