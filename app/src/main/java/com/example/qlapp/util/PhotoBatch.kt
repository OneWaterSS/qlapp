package com.example.qlapp.util

import kotlinx.coroutines.CancellationException

data class BatchResult<T>(val succeeded: List<T>, val failed: List<T>)

suspend fun <T> processPhotoBatch(
    items: List<T>,
    onProgress: (Int, Int) -> Unit,
    operation: suspend (T) -> Unit,
): BatchResult<T> {
    val unique = items.distinct()
    val succeeded = mutableListOf<T>()
    val failed = mutableListOf<T>()
    unique.forEachIndexed { index, item ->
        try {
            operation(item)
            succeeded.add(item)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed.add(item)
        }
        onProgress(index + 1, unique.size)
    }
    return BatchResult(succeeded, failed)
}
