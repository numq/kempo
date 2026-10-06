package io.github.numq.kempo.buffer

import io.github.numq.kempo.InternalKempoApi
import kotlin.math.min

/**
 * High-performance multichannel circular ring buffer backed by a single flat [FloatArray].
 * Optimized with split-range memory block copies and unrolled accumulator loops.
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
        var sIdx = srcOffset
        var i = 0
        val limit1 = firstChunk - 3
        while (i < limit1) {
            data[idx] += src[sIdx]
            data[idx + 1] += src[sIdx + 1]
            data[idx + 2] += src[sIdx + 2]
            data[idx + 3] += src[sIdx + 3]
            idx += 4
            sIdx += 4
            i += 4
        }
        while (i < firstChunk) {
            data[idx++] += src[sIdx++]
            i++
        }

        val remaining = length - firstChunk
        if (remaining > 0) {
            var remIdx = chBase
            var j = 0
            val limit2 = remaining - 3
            while (j < limit2) {
                data[remIdx] += src[sIdx]
                data[remIdx + 1] += src[sIdx + 1]
                data[remIdx + 2] += src[sIdx + 2]
                data[remIdx + 3] += src[sIdx + 3]
                remIdx += 4
                sIdx += 4
                j += 4
            }
            while (j < remaining) {
                data[remIdx++] += src[sIdx++]
                j++
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