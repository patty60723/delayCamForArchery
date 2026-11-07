package com.archery.delaycam.camera

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImmutableSegmentStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private fun segment(store: ImmutableSegmentStore, index: Int): SegmentInfo {
        val file = store.allocate().apply { writeText("original-$index") }
        store.publish(file)
        return SegmentInfo(file, index * 1000L, (index + 1) * 1000L)
    }

    @Test fun activePlaybackAndExportSurviveManyRingRotations() {
        val store = ImmutableSegmentStore(temp.root, "session")
        store.setKeepCount(2)
        val first = segment(store, 0)
        val playback = store.acquire(listOf(first))!!
        val export = store.acquire(listOf(first))!!
        repeat(100) { segment(store, it + 1) }
        assertEquals("original-0", first.file.readText())
        assertEquals(3, temp.root.listFiles()!!.size)
        playback.close()
        assertTrue(first.file.exists())
        export.close()
        export.close()
        assertFalse(first.file.exists())
        assertEquals(2, temp.root.listFiles()!!.size)
    }

    @Test fun oldSessionUnpublishedAndDeletedFilesAreRejectedAtomically() {
        val store = ImmutableSegmentStore(temp.root, "a")
        val other = ImmutableSegmentStore(temp.root, "b")
        val good = segment(store, 0)
        val foreign = segment(other, 1)
        assertNull(store.acquire(listOf(good, foreign)))
        val pending = SegmentInfo(store.allocate().apply { writeText("pending") }, 2, 3)
        assertNull(store.acquire(listOf(pending)))
        good.file.delete()
        assertNull(store.acquire(listOf(good)))
        store.close(); other.close()
        assertEquals(0, temp.root.listFiles()!!.size)
    }

    @Test fun shutdownDefersDeletionUntilLastReaderFinishes() {
        val store = ImmutableSegmentStore(temp.root, "session")
        val source = segment(store, 0)
        val lease = store.acquire(listOf(source))!!
        store.close()
        assertTrue(source.file.exists())
        assertNull(store.acquire(listOf(source)))
        lease.close()
        assertFalse(source.file.exists())
    }

    @Test fun reducingWindowDoesNotRemovePinnedSources() {
        val store = ImmutableSegmentStore(temp.root, "session")
        val sources = (0..8).map { segment(store, it) }
        val lease = store.acquire(sources)!!
        store.setKeepCount(2)
        assertEquals(9, temp.root.listFiles()!!.size)
        lease.close()
        assertEquals(2, temp.root.listFiles()!!.size)
    }
}
