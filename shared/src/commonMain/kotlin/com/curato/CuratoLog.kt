package com.curato

/**
 * A minimal log sink.
 *
 * Exists so a swallowed network failure leaves a trace somewhere. The customer sees a
 * `userMessage` and nothing else by design, but a developer debugging "why is Home
 * empty" needs the cause, and silently discarding it is how that becomes a long
 * afternoon. Routes to stdout, which on Android is logcat.
 */
object CuratoLog {
    fun warn(message: String, cause: Throwable? = null) {
        println("CURATO W: $message${cause?.let { " :: ${it::class.simpleName}: ${it.message}" } ?: ""}")
    }
}
