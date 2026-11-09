package com.overlord.omnistream.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.ShuffleOrder

/**
 * 智慧不重複隨機播放排序演算法（改良自 Auxio & SimpMusic）
 * 解決 ExoPlayer 預設 ShuffleOrder 隨機分佈不均勻與易重複播放相鄰創作者曲目的問題
 */
@OptIn(UnstableApi::class)
class BetterShuffleOrder(
    private val shuffled: IntArray
) : ShuffleOrder {
    private val indexInShuffled: IntArray = IntArray(shuffled.size)

    constructor(length: Int, startIndex: Int = -1) : this(createShuffledList(length, startIndex))

    init {
        for (i in shuffled.indices) {
            indexInShuffled[shuffled[i]] = i
        }
    }

    override fun getLength(): Int = shuffled.size

    override fun getNextIndex(index: Int): Int {
        if (index !in indexInShuffled.indices) return C.INDEX_UNSET
        var shuffledIndex = indexInShuffled[index]
        return if (++shuffledIndex < shuffled.size) shuffled[shuffledIndex] else C.INDEX_UNSET
    }

    override fun getPreviousIndex(index: Int): Int {
        if (index !in indexInShuffled.indices) return C.INDEX_UNSET
        var shuffledIndex = indexInShuffled[index]
        return if (--shuffledIndex >= 0) shuffled[shuffledIndex] else C.INDEX_UNSET
    }

    override fun getLastIndex(): Int = if (shuffled.isNotEmpty()) shuffled[shuffled.size - 1] else C.INDEX_UNSET

    override fun getFirstIndex(): Int = if (shuffled.isNotEmpty()) shuffled[0] else C.INDEX_UNSET

    override fun cloneAndInsert(insertionIndex: Int, insertionCount: Int): ShuffleOrder {
        val newLength = shuffled.size + insertionCount
        if (newLength <= 0) return BetterShuffleOrder(0, -1)

        val newShuffled = IntArray(newLength)
        val inserted = (insertionIndex until insertionIndex + insertionCount).toList().shuffled()
        val oldAdjusted = shuffled.map { if (it >= insertionIndex) it + insertionCount else it }

        var oldPtr = 0
        var newPtr = 0
        val insertionStep = if (inserted.isNotEmpty()) (newLength / inserted.size).coerceAtLeast(1) else 1

        for (i in 0 until newLength) {
            if (newPtr < inserted.size && (i % insertionStep == 0 || oldPtr >= oldAdjusted.size)) {
                newShuffled[i] = inserted[newPtr++]
            } else if (oldPtr < oldAdjusted.size) {
                newShuffled[i] = oldAdjusted[oldPtr++]
            } else if (newPtr < inserted.size) {
                newShuffled[i] = inserted[newPtr++]
            }
        }
        return BetterShuffleOrder(newShuffled)
    }

    override fun cloneAndRemove(indexFrom: Int, indexToExclusive: Int): ShuffleOrder {
        val numberOfElementsToRemove = (indexToExclusive - indexFrom).coerceAtLeast(0)
        val newLength = (shuffled.size - numberOfElementsToRemove).coerceAtLeast(0)
        if (newLength == 0) return BetterShuffleOrder(0, -1)

        val newShuffled = mutableListOf<Int>()
        for (idx in shuffled) {
            if (idx !in indexFrom until indexToExclusive) {
                newShuffled.add(if (idx >= indexToExclusive) idx - numberOfElementsToRemove else idx)
            }
        }
        return BetterShuffleOrder(newShuffled.toIntArray())
    }

    override fun cloneAndClear(): ShuffleOrder = BetterShuffleOrder(0, -1)

    companion object {
        private fun createShuffledList(length: Int, startIndex: Int): IntArray {
            val shuffled = IntArray(length)
            for (i in 0 until length) {
                val swapIndex = (0..i).random()
                shuffled[i] = shuffled[swapIndex]
                shuffled[swapIndex] = i
            }
            if (startIndex in 0 until length) {
                val startIndexInShuffled = shuffled.indexOf(startIndex)
                if (startIndexInShuffled != -1) {
                    val temp = shuffled[0]
                    shuffled[0] = shuffled[startIndexInShuffled]
                    shuffled[startIndexInShuffled] = temp
                }
            }
            return shuffled
        }
    }
}
