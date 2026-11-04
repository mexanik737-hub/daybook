package ru.daybook.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteChecklistTest {
    @Test
    fun parsesChecklistItemsAndTheirOffsets() {
        val body = "Заголовок\n- [ ] Купить молоко\n  - [x] Позвонить"

        val items = NoteChecklist.parse(body)

        assertEquals(2, items.size)
        assertEquals("Купить молоко", items[0].text)
        assertFalse(items[0].checked)
        assertEquals(body.indexOf("  - [x]"), items[1].lineStart)
        assertEquals("Позвонить", items[1].text)
        assertTrue(items[1].checked)
    }

    @Test
    fun togglesOnlyTheRequestedItemAndPreservesOtherText() {
        val body = "Заметка\n- [ ] Первый пункт\n- [x] Второй пункт"
        val firstLine = body.indexOf("- [ ]")
        val secondLine = body.indexOf("- [x]")

        val afterFirstToggle = NoteChecklist.toggleLine(body, firstLine)
        val afterSecondToggle = NoteChecklist.toggleLine(afterFirstToggle, secondLine)

        assertEquals("Заметка\n- [x] Первый пункт\n- [ ] Второй пункт", afterSecondToggle)
    }

    @Test
    fun appendsChecklistItemWithoutLosingExistingText() {
        assertEquals("- [ ] ", NoteChecklist.appendItem(""))
        assertEquals("Заметка\n- [ ] ", NoteChecklist.appendItem("Заметка"))
        assertEquals("Заметка\n- [ ] ", NoteChecklist.appendItem("Заметка\n"))
    }

    @Test
    fun ignoresOrdinaryBulletsAndInvalidOffsets() {
        val body = "- обычный пункт\nпросто текст"

        assertTrue(NoteChecklist.parse(body).isEmpty())
        assertEquals(body, NoteChecklist.toggleLine(body, 0))
        assertEquals(body, NoteChecklist.toggleLine(body, -1))
        assertEquals(body, NoteChecklist.toggleLine(body, body.length + 1))
    }
}
