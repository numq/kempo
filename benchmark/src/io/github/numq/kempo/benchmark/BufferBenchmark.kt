package io.github.numq.kempo.benchmark

import io.github.numq.kempo.buffer.MultiChannelBuffer

/**
 * Micro-benchmarks for [MultiChannelBuffer] measuring throughput for block operations,
 * including linear segments and circular wrap-around boundaries.
 */
class BufferBenchmark {
    var channels: Int = 2
    var capacity: Int = 32768
    var blockSize: Int = 1024

    private lateinit var buffer: MultiChannelBuffer
    private lateinit var srcData: FloatArray
    private lateinit var dstData: FloatArray

    fun setup() {
        buffer = MultiChannelBuffer(channels, capacity)
        srcData = FloatArray(blockSize) { it.toFloat() }
        dstData = FloatArray(blockSize)
    }

    fun benchmarkBufferWrite(bh: DummyBlackhole) {
        buffer.write(0, 0, srcData, 0, blockSize)
        buffer.head += blockSize
        bh.consume(buffer)
    }

    fun benchmarkBufferRead(bh: DummyBlackhole) {
        buffer.read(0, 0, dstData, 0, blockSize, clearAfterRead = true)
        bh.consume(dstData)
    }

    fun benchmarkBufferAdd(bh: DummyBlackhole) {
        buffer.add(0, 0, srcData, 0, blockSize)
        bh.consume(buffer)
    }

    fun benchmarkBufferClear(bh: DummyBlackhole) {
        buffer.clear(0, 0, blockSize)
        bh.consume(buffer)
    }

    fun benchmarkBufferWrapAround(bh: DummyBlackhole) {
        // Position head across the power-of-two boundary to benchmark split memory copy
        buffer.head = buffer.capacity - (blockSize / 2)
        buffer.write(0, 0, srcData, 0, blockSize)
        buffer.read(0, 0, dstData, 0, blockSize, clearAfterRead = false)
        bh.consume(dstData)
    }
}