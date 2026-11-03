package io.github.codex07.iou.api

/** Result of every write and of fallible helpers: a value or a typed [LedgerError] (spec §6.2). */
sealed interface LedgerResult<out T> {
    data class Ok<out T>(val value: T) : LedgerResult<T>
    data class Err(val error: LedgerError) : LedgerResult<Nothing>
}

inline fun <T, R> LedgerResult<T>.map(transform: (T) -> R): LedgerResult<R> = when (this) {
    is LedgerResult.Ok -> LedgerResult.Ok(transform(value))
    is LedgerResult.Err -> this
}

inline fun <T, R> LedgerResult<T>.flatMap(transform: (T) -> LedgerResult<R>): LedgerResult<R> = when (this) {
    is LedgerResult.Ok -> transform(value)
    is LedgerResult.Err -> this
}

fun <T> LedgerResult<T>.getOrNull(): T? = (this as? LedgerResult.Ok)?.value

fun <T> LedgerResult<T>.errorOrNull(): LedgerError? = (this as? LedgerResult.Err)?.error

/** Scope of [ledgerResult]: `bind()` unwraps or stops the block with the error. */
class ResultScope @PublishedApi internal constructor() {

    @PublishedApi
    internal class Failure(val error: LedgerError, val scope: ResultScope) :
        RuntimeException(null, null, false, false)

    fun <T> LedgerResult<T>.bind(): T = when (this) {
        is LedgerResult.Ok -> value
        is LedgerResult.Err -> throw Failure(error, this@ResultScope)
    }

    fun fail(error: LedgerError): Nothing = throw Failure(error, this)

    inline fun ensure(condition: Boolean, error: () -> LedgerError) {
        if (!condition) fail(error())
    }
}

/**
 * Runs [block]; the first `bind()` of an error, or `fail`, becomes the result.
 * `bind`, `fail` and `ensure` stop the block by throwing, so a `catch (e: Exception)` or `runCatching`
 * around them inside the block would swallow the failure; don't wrap them that way.
 */
inline fun <T> ledgerResult(block: ResultScope.() -> T): LedgerResult<T> {
    val scope = ResultScope()
    return try {
        LedgerResult.Ok(scope.block())
    } catch (failure: ResultScope.Failure) {
        if (failure.scope !== scope) throw failure
        LedgerResult.Err(failure.error)
    }
}
