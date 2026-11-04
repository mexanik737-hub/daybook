package ru.daybook.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DaybookSyncMergerTest {
    private fun entry(id: String, title: String, updated: Long = 1) = DaybookEntry(
        id = id, title = title, created = 1, updated = updated
    )

    @Test
    fun mergesIndependentChangesFromBothDevices() {
        val base = DaybookDocument(tasks = listOf(entry("a", "Original")))
        val local = base.copy(tasks = base.tasks + entry("local", "Phone note", 2))
        val remote = base.copy(notes = listOf(entry("remote", "Desktop note", 3)))

        val result = DaybookSyncMerger.merge(base, local, remote)

        assertTrue(result.succeeded)
        assertEquals(setOf("a", "local"), result.document?.tasks?.map { it.id }?.toSet())
        assertEquals(listOf("remote"), result.document?.notes?.map { it.id })
    }

    @Test
    fun reportsConcurrentEditsToSameEntry() {
        val base = DaybookDocument(tasks = listOf(entry("a", "Original")))
        val local = base.copy(tasks = listOf(entry("a", "Edited on phone", 2)))
        val remote = base.copy(tasks = listOf(entry("a", "Edited on PC", 3)))

        val result = DaybookSyncMerger.merge(base, local, remote)

        assertFalse(result.succeeded)
        assertEquals(listOf("task:a"), result.conflicts)
        assertEquals(null, result.document)
    }

    @Test
    fun acceptsDeletionWhenOtherDeviceDidNotChangeEntry() {
        val base = DaybookDocument(tasks = listOf(entry("a", "Original")))
        val local = base.copy(tasks = emptyList())

        val result = DaybookSyncMerger.merge(base, local, base)

        assertTrue(result.succeeded)
        assertTrue(result.document?.tasks.isNullOrEmpty())
    }

    @Test
    fun reportsDeleteVersusEditConflict() {
        val base = DaybookDocument(tasks = listOf(entry("a", "Original")))
        val local = base.copy(tasks = emptyList())
        val remote = base.copy(tasks = listOf(entry("a", "Edited elsewhere", 2)))

        val result = DaybookSyncMerger.merge(base, local, remote)

        assertFalse(result.succeeded)
        assertEquals(listOf("task:a"), result.conflicts)
    }
}
