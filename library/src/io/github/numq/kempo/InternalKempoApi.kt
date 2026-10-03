package io.github.numq.kempo

/**
 * Marks internal DSP and buffering primitives of the Kempo engine.
 *
 * Public access to these components is unstable and intended exclusively
 * for the engine internals and benchmarking harness.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.WARNING,
    message = "This is an internal Kempo DSP API. It may be changed or removed without notice."
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class InternalKempoApi