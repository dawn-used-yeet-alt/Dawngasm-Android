package dev.dawngasm.app.ui

import dev.dawngasm.app.ui.components.CardGroupPosition
import dev.dawngasm.app.ui.components.computeSelectionGroupPosition
import dev.dawngasm.app.ui.components.formatHostFilterDisplay
import org.junit.Assert.assertEquals
import org.junit.Test

class SelectionGroupingTest {

    @Test
    fun unselectedItemAlwaysStandalone() {
        val list = listOf(true, false, true)
        assertEquals(CardGroupPosition.Standalone, computeSelectionGroupPosition(1, list))
    }

    @Test
    fun singleSelectedItemIsStandalone() {
        val list = listOf(false, true, false, false)
        assertEquals(CardGroupPosition.Standalone, computeSelectionGroupPosition(1, list))
    }

    @Test
    fun twoConsecutiveSelectedItemsFormTopAndBottom() {
        val list = listOf(false, true, true, false)
        assertEquals(CardGroupPosition.Top, computeSelectionGroupPosition(1, list))
        assertEquals(CardGroupPosition.Bottom, computeSelectionGroupPosition(2, list))
    }

    @Test
    fun threeConsecutiveSelectedItemsFormTopMiddleBottom() {
        val list = listOf(false, true, true, true, false)
        assertEquals(CardGroupPosition.Top, computeSelectionGroupPosition(1, list))
        assertEquals(CardGroupPosition.Middle, computeSelectionGroupPosition(2, list))
        assertEquals(CardGroupPosition.Bottom, computeSelectionGroupPosition(3, list))
    }

    @Test
    fun disjointSelectionsRemainStandalone() {
        val list = listOf(false, true, false, true, false)
        assertEquals(CardGroupPosition.Standalone, computeSelectionGroupPosition(1, list))
        assertEquals(CardGroupPosition.Standalone, computeSelectionGroupPosition(3, list))
    }

    @Test
    fun outOfBoundsReturnsStandalone() {
        val list = listOf(true, true)
        assertEquals(CardGroupPosition.Standalone, computeSelectionGroupPosition(-1, list))
        assertEquals(CardGroupPosition.Standalone, computeSelectionGroupPosition(5, list))
    }

    @Test
    fun allItemsSelectedFormsFullGroup() {
        val list = listOf(true, true, true, true)
        assertEquals(CardGroupPosition.Top, computeSelectionGroupPosition(0, list))
        assertEquals(CardGroupPosition.Middle, computeSelectionGroupPosition(1, list))
        assertEquals(CardGroupPosition.Middle, computeSelectionGroupPosition(2, list))
        assertEquals(CardGroupPosition.Bottom, computeSelectionGroupPosition(3, list))
    }

    @Test
    fun hostDisplayEmptyOrAllReturnsAllHosts() {
        assertEquals("All hosts", formatHostFilterDisplay(emptyList()))
        assertEquals("All hosts", formatHostFilterDisplay(listOf("soundgasm", "whyp", "audiochan", "hotaudio")))
    }

    @Test
    fun hostDisplaySingleReturnsHostName() {
        assertEquals("Soundgasm", formatHostFilterDisplay(listOf("soundgasm")))
        assertEquals("Whyp", formatHostFilterDisplay(listOf("whyp")))
        assertEquals("Audiochan", formatHostFilterDisplay(listOf("audiochan")))
        assertEquals("Hotaudio", formatHostFilterDisplay(listOf("hotaudio")))
    }

    @Test
    fun hostDisplayMultipleReturnsCount() {
        assertEquals("2 hosts", formatHostFilterDisplay(listOf("soundgasm", "whyp")))
        assertEquals("2 hosts", formatHostFilterDisplay(listOf("soundgasm", "audiochan")))
        assertEquals("3 hosts", formatHostFilterDisplay(listOf("soundgasm", "whyp", "hotaudio")))
    }
}
