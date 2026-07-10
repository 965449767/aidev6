package com.aidev.six

object ErrorHandler {
    suspend fun <T> execute(block: suspend () -> T): Result<T> {
        return try {
            Result.success(block())
        } catch (e: Exception) {
            AIDevLogger.e("ErrorHandler", "execute failed", e)
            Result.failure(e)
        }
    }
}
