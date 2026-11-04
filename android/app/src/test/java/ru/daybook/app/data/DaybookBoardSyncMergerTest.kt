package ru.daybook.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DaybookBoardSyncMergerTest {
    private fun board(id: String, title: String, updated: Long = 1) = DaybookEntry(
        id = id, title = title, created = 1, updated = updated
    )

    @Test
    fun mergesIndependentBoardChanges() {
        val base = listOf(board("a", "Base"))
        val local = base + board("local", "Created on phone", 2)
        val remote = base + board("remote", "Created on PC", 3)

        val result = DaybookBoardSyncMerger.merge(base, local, remote)

        assertTrue(result.succeeded)
        assertEquals(setOf("a", "local", "remote"), result.boards?.map { it.id }?.toSet())
    }

    @Test
    fun reportsConcurrentEditsToSameBoard() {
        val base = listOf(board("a", "Base"))
        val local = listOf(board("a", "Phone edit", 2))
        val remote = listOf(board("a", "PC edit", 3))

        val result = DaybookBoardSyncMerger.merge(base, local, remote)

        assertFalse(result.succeeded)
        assertEquals(listOf("board:a"), result.conflicts)
        assertEquals(null, result.boards)
    }

    @Test
    fun acceptsDeletionWhenOtherDeviceDidNotEditBoard() {
        val base = listOf(board("a", "Base"))

        val result = DaybookBoardSyncMerger.merge(base, emptyList(), base)

        assertTrue(result.succeeded)
        assertTrue(result.boards.isNullOrEmpty())
    }

    @Test
    fun reportsDeleteVersusEditConflict() {
        val base = listOf(board("a", "Base"))
        val remote = listOf(board("a", "Edited remotely", 2))

        val result = DaybookBoardSyncMerger.merge(base, emptyList(), remote)

        assertFalse(result.succeeded)
        assertEquals(listOf("board:a"), result.conflicts)
    }

    @Test
    fun acceptsSameChangeMadeOnBothDevices() {
        val base = listOf(board("a", "Base"))
        val changed = listOf(board("a", "Same edit", 2))

        val result = DaybookBoardSyncMerger.merge(base, changed, changed)

        assertTrue(result.succeeded)
        assertEquals(changed, result.boards)
    }
}
