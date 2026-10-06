package io.github.numq.kempo.benchmark

import io.github.numq.kempo.InternalKempoApi
import io.github.numq.kempo.buffer.MultiChannelBuffer
import kotlinx.benchmark.*

/**
 * Micro-benchmarks for [MultiChannelBuffer] measuring throughput across linear and wrap-around segments.
 */
@OptIn(InternalKempoApi::class)
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(BenchmarkTimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
open class BufferBenchmark {

    var channels: Int = 2
    var capacity: Int = 32768
    var blockSize: Int = 1024

    private lateinit var buffer: MultiChannelBuffer
    private lateinit var srcData: FloatArray
    private lateinit var dstData: FloatArray

    @Setup
    fun setup() {
        buffer = MultiChannelBuffer(channels, capacity)
        srcData = FloatArray(blockSize) { it.toFloat() }
        dstData = FloatArray(blockSize)
    }

    @Benchmark
    fun benchmarkBufferWrite(bh: Blackhole) {
        buffer.write(0, 0, srcData, 0, blockSize)
        buffer.head += blockSize
        bh.consume(buffer)
    }

    @Benchmark
    fun benchmarkBufferRead(bh: Blackhole) {
        buffer.read(0, 0, dstData, 0, blockSize, clearAfterRead = true)
        bh.consume(dstData)
    }

    @Benchmark
    fun benchmarkBufferAdd(bh: Blackhole) {
        buffer.add(0, 0, srcData, 0, blockSize)
        bh.consume(buffer)
    }

    @Benchmark
    fun benchmarkBufferClear(bh: Blackhole) {
        buffer.clear(0, 0, blockSize)
        bh.consume(buffer)
    }

    @Benchmark
    fun benchmarkBufferWrapAround(bh: Blackhole) {
        buffer.head = buffer.capacity - (blockSize / 2)
        buffer.write(0, 0, srcData, 0, blockSize)
        buffer.read(0, 0, dstData, 0, blockSize, clearAfterRead = false)
        bh.consume(dstData)
    }
}