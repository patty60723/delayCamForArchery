package com.archery.delaycam.gallery

import org.junit.Assert.*
import org.junit.Test

class GallerySelectionTest {
    @Test fun removingLastSelectionKeepsModeForFurtherSelection() {
        val state = GallerySelection().toggle("a").toggle("a")
        assertTrue(state.active)
        assertTrue(state.ids.isEmpty())
    }
    @Test fun refreshRemovesMissingButNeverSelectsNewClips() {
        val state = GallerySelection(true, setOf("a", "b")).reconcile(listOf("b", "c"))
        assertEquals(setOf("b"), state.ids)
    }
    @Test fun selectAllAndClearOperateOnAvailableSnapshot() {
        val all = GallerySelection().toggle("a").toggleAll(listOf("a", "b"))
        assertEquals(setOf("a", "b"), all.ids)
        assertTrue(all.toggleAll(listOf("a", "b")).ids.isEmpty())
    }
    @Test fun reorderDoesNotChangeSelection() {
        assertEquals(setOf("b"), GallerySelection().toggle("b").reconcile(listOf("c", "b", "a")).ids)
    }
}
