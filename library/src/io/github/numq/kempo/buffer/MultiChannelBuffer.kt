package io.github.numq.kempo.buffer

import io.github.numq.kempo.InternalKempoApi
import kotlin.math.min

/**
 * High-performance multichannel circular ring buffer backed by a single flat [FloatArray].
 * Optimized with split-range memory block copies.
 */
@InternalKempoApi
class MultiChannelBuffer(val channels: Int, minCapacity: Int) {
    var capacity: Int = 1
        private set
    var mask: Int = 0
        private set
    var head: Int = 0

    var data: FloatArray = FloatArray(0)
        private set

    init {
        resize(minCapacity)
    }

    fun resize(minCapacity: Int) {
        capacity = 1
        while (capacity < minCapacity) capacity *= 2
        mask = capacity - 1
        data = FloatArray(channels * capacity)
        head = 0
    }

    fun reset(fill: Float = 0f) {
        data.fill(fill)
        head = 0
    }

    fun clear(channel: Int, offsetFromHead: Int, length: Int) {
        if (length <= 0) return
        val chBase = channel * capacity
        val startPos = (head + offsetFromHead) and mask
        val firstChunk = min(length, capacity - startPos)

        data.fill(0f, chBase + startPos, chBase + startPos + firstChunk)
        val remaining = length - firstChunk
        if (remaining > 0) {
            data.fill(0f, chBase, chBase + remaining)
        }
    }

    fun write(channel: Int, offsetFromHead: Int, src: FloatArray, srcOffset: Int, length: Int) {
        if (length <= 0) return
        val chBase = channel * capacity
        val startPos = (head + offsetFromHead) and mask
        val firstChunk = min(length, capacity - startPos)

        src.copyInto(
            destination = data,
            destinationOffset = chBase + startPos,
            startIndex = srcOffset,
            endIndex = srcOffset + firstChunk
        )
        val remaining = length - firstChunk
        if (remaining > 0) {
            src.copyInto(
                destination = data,
                destinationOffset = chBase,
                startIndex = srcOffset + firstChunk,
                endIndex = srcOffset + length
            )
        }
    }

    fun read(
        channel: Int, offsetFromHead: Int, dst: FloatArray, dstOffset: Int, length: Int, clearAfterRead: Boolean = false
    ) {
        if (length <= 0) return
        val chBase = channel * capacity
        val startPos = (head + offsetFromHead) and mask
        val firstChunk = min(length, capacity - startPos)

        data.copyInto(
            destination = dst,
            destinationOffset = dstOffset,
            startIndex = chBase + startPos,
            endIndex = chBase + startPos + firstChunk
        )
        if (clearAfterRead) {
            data.fill(0f, chBase + startPos, chBase + startPos + firstChunk)
        }

        val remaining = length - firstChunk
        if (remaining > 0) {
            data.copyInto(
                destination = dst,
                destinationOffset = dstOffset + firstChunk,
                startIndex = chBase,
                endIndex = chBase + remaining
            )
            if (clearAfterRead) {
                data.fill(0f, chBase, chBase + remaining)
            }
        }
    }

    fun add(channel: Int, offsetFromHead: Int, src: FloatArray, srcOffset: Int, length: Int) {
        if (length <= 0) return
        val chBase = channel * capacity
        val startPos = (head + offsetFromHead) and mask
        val firstChunk = min(length, capacity - startPos)

        var idx = chBase + startPos
        for (i in 0 until firstChunk) {
            data[idx++] += src[srcOffset + i]
        }
        val remaining = length - firstChunk
        if (remaining > 0) {
            var remIdx = chBase
            val offsetRemaining = srcOffset + firstChunk
            for (i in 0 until remaining) {
                data[remIdx++] += src[offsetRemaining + i]
            }
        }
    }

    fun copyFrom(other: MultiChannelBuffer) {
        this.head = other.head
        this.capacity = other.capacity
        this.mask = other.mask
        if (this.data.size != other.data.size) {
            this.data = other.data.copyOf()
        } else {
            other.data.copyInto(this.data)
        }
    }

    fun swap(other: MultiChannelBuffer) {
        val tmpHead = this.head
        this.head = other.head
        other.head = tmpHead

        val tmpCap = this.capacity
        this.capacity = other.capacity
        other.capacity = tmpCap

        val tmpMask = this.mask
        this.mask = other.mask
        other.mask = tmpMask

        val tmpData = this.data
        this.data = other.data
        other.data = tmpData
    }
}