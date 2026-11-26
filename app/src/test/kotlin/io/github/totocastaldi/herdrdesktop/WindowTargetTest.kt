package io.github.totocastaldi.herdrdesktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WindowTargetTest {
    @Test
    fun newWindowOpensOnTheNextSpace() {
        assertEquals("b", WindowTarget.otherSpace(listOf("a", "b", "c"), "a"))
        assertEquals("c", WindowTarget.otherSpace(listOf("a", "b", "c"), "b"))
    }

    @Test
    fun afterTheLastSpaceItWrapsToTheFirst() {
        assertEquals("a", WindowTarget.otherSpace(listOf("a", "b", "c"), "c"))
    }

    @Test
    fun withOneSpaceOrAnUnknownCurrentItOpensAnExistingSpace() {
        assertEquals("a", WindowTarget.otherSpace(listOf("a"), "a"))
        assertEquals("a", WindowTarget.otherSpace(listOf("a", "b"), "gone"))
        assertEquals("a", WindowTarget.otherSpace(listOf("a", "b"), null))
    }

    @Test
    fun withoutSpacesThereIsNoSpaceToOpen() {
        assertNull(WindowTarget.otherSpace(emptyList(), null))
    }
}
