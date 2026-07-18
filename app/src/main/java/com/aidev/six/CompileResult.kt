package com.aidev.six

import java.io.File

/** 构建编译结果。 */
internal class CompileResult(
    val success: Boolean,
    val exitCode: Int = -1,
    val apk: File? = null,
    val buildLogPath: String? = null,
    val pkg: String? = null,
    val message: String = ""
)
