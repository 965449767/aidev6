package com.aidev.four.domain

import androidx.compose.runtime.Immutable

@Immutable
data class BackupResult(
    val type: ResultType,
    val message: String,
    val data: Any? = null
) {
    enum class ResultType {
        PROGRESS,
        SUCCESS,
        ERROR
    }
}
