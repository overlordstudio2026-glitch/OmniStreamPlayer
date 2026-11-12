package com.overlord.omnistream

import org.junit.Assert.*
import org.junit.Test

class PlaybackToggleLogicTest {

    data class MockItem(val id: String, val title: String)

    enum class Action { PLAY, PAUSE, SWITCH_AND_PLAY }

    private fun resolveClickAction(
        currentPlayingId: String?,
        isPlaying: Boolean,
        clickedItem: MockItem
    ): Action {
        return if (currentPlayingId == clickedItem.id) {
            if (isPlaying) Action.PAUSE else Action.PLAY
        } else {
            Action.SWITCH_AND_PLAY
        }
    }

    @Test
    fun testClickCurrentPlayingItem_pausesPlayback() {
        val currentId = "item_1"
        val isPlaying = true
        val clicked = MockItem("item_1", "音訊 1")

        val action = resolveClickAction(currentId, isPlaying, clicked)
        assertEquals(Action.PAUSE, action)
    }

    @Test
    fun testClickCurrentPausedItem_resumesPlayback() {
        val currentId = "item_1"
        val isPlaying = false
        val clicked = MockItem("item_1", "音訊 1")

        val action = resolveClickAction(currentId, isPlaying, clicked)
        assertEquals(Action.PLAY, action)
    }

    @Test
    fun testClickDifferentItem_switchesAndPlays() {
        val currentId = "item_1"
        val isPlaying = true
        val clicked = MockItem("item_2", "音訊 2")

        val action = resolveClickAction(currentId, isPlaying, clicked)
        assertEquals(Action.SWITCH_AND_PLAY, action)
    }

    @Test
    fun testClickWhenNothingPlaying_switchesAndPlays() {
        val currentId: String? = null
        val isPlaying = false
        val clicked = MockItem("item_1", "音訊 1")

        val action = resolveClickAction(currentId, isPlaying, clicked)
        assertEquals(Action.SWITCH_AND_PLAY, action)
    }
}
