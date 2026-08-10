package com.trackhub

/** Kotlin's runCatching catches every Throwable, including VM-fatal errors. */
internal inline fun <T> runCatchingException(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (failure: Exception) {
    Result.failure(failure)
}
