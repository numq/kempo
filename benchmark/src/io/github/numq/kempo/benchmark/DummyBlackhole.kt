package io.github.numq.kempo.benchmark

/**
 * Lightweight blackhole utility designed to prevent Dead Code Elimination (DCE)
 * by the JIT compiler without requiring external benchmark framework overhead.
 */
class DummyBlackhole {
    @Volatile
    var consumed: Any? = null

    fun consume(obj: Any?) {
        consumed = obj
    }

    fun consume(value: Float) {
        consumed = value
    }

    fun consume(value: Double) {
        consumed = value
    }

    fun consume(value: Int) {
        consumed = value
    }

    fun consume(value: Long) {
        consumed = value
    }
}