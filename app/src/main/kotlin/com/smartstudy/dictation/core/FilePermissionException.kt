package com.smartstudy.dictation.core

/**
 * 文件权限异常
 * @param fileName 文件名
 */
class FilePermissionException(val fileName: String) : Exception("文件权限不足: $fileName")
