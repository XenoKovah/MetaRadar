package com.darkmentor.ui.journal

import com.darkmentor.domain.model.JournalEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class JournalViewModelMappingTest {

    @Test
    fun `mapping sorts newest first and preserves full error text`() {
        val old = errorEntry(timestamp = 1, title = "old", stackTrace = "old details")
        val newest = errorEntry(timestamp = 3, title = "newest", stackTrace = "new details")
        val middle = errorEntry(timestamp = 2, title = "middle", stackTrace = "middle details")

        val result = JournalViewModel.mapJournalHistoryNow(listOf(old, newest, middle))

        assertEquals(listOf("newest", "middle", "old"), result.map { it.title })
        assertEquals("new details", result.first().subtitle)
        assertEquals("new details", result.first().subtitleCollapsed)
    }

    @Test
    fun `mapping truncates only display fields at their documented limits`() {
        val title = "t".repeat(300)
        val stackTrace = "s".repeat(700)

        val result = JournalViewModel.mapJournalHistoryNow(
            listOf(errorEntry(timestamp = 1, title = title, stackTrace = stackTrace)),
        ).single()

        assertEquals(256, result.title.length)
        assertEquals(title.take(256), result.title)
        assertEquals(stackTrace, result.subtitle)
        assertEquals(stackTrace.take(500), result.subtitleCollapsed)
    }

    private fun errorEntry(
        timestamp: Long,
        title: String,
        stackTrace: String,
    ) = JournalEntry(
        id = null,
        timestamp = timestamp,
        report = JournalEntry.Report.Error(title = title, stackTrace = stackTrace),
    )
}
