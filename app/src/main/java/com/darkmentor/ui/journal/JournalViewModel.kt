package com.darkmentor.ui.journal

import android.app.Application
import android.widget.Toast
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.darkmentor.R
import com.darkmentor.data.repo.JournalRepository
import com.darkmentor.dateTimeStringFormat
import com.darkmentor.domain.model.JournalEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

class JournalViewModel(
    private val journalRepository: JournalRepository,
    private val context: Application,
) : ViewModel() {

    var journal: List<JournalEntryUiModel> by mutableStateOf(emptyList())
    var loading by mutableStateOf(true)

    init {
        observeJournal()
    }

    fun clearJournal() {
        viewModelScope.launch {
            journalRepository.clearAll()
            Toast.makeText(context, context.getString(R.string.journal_cleared), Toast.LENGTH_SHORT).show()
        }
    }

    private fun observeJournal() {
        viewModelScope.launch {
            journalRepository.observe()
                .onStart { loading = true }
                .map {
                    loading = true
                    mapJournalHistory(it)
                }
                .collect { update ->
                    journal = update
                    loading = false
                }
        }
    }

    private suspend fun mapJournalHistory(history: List<JournalEntry>): List<JournalEntryUiModel> {
        return withContext(Dispatchers.Default) {
            mapJournalHistoryNow(history)
        }
    }

    data class JournalEntryUiModel(
        val dateTime: String,
        val color: @Composable () -> Color,
        val colorForeground: @Composable () -> Color,
        val title: String,
        val subtitle: String?,
        val subtitleCollapsed: String?,
    )

    companion object {
        private const val MAX_ERROR_TITLE_LENGTH = 256
        private const val MAX_ERROR_DESCRIPTION_COLLAPSED_LENGTH = 500

        internal fun mapJournalHistoryNow(history: List<JournalEntry>): List<JournalEntryUiModel> {
            return history.asSequence()
                .sortedByDescending { it.timestamp }
                .map(::map)
                .toList()
        }

        private fun map(from: JournalEntry): JournalEntryUiModel {
            return when (from.report) {
                is JournalEntry.Report.Error -> mapReportError(from, from.report)
            }
        }

        private fun mapReportError(
            journalEntry: JournalEntry,
            report: JournalEntry.Report.Error,
        ): JournalEntryUiModel {
            val title = report.title.take(MAX_ERROR_TITLE_LENGTH)
            val description = report.stackTrace
            return JournalEntryUiModel(
                dateTime = journalEntry.timestamp.dateTimeStringFormat("dd MMM yyyy, HH:mm"),
                color = { MaterialTheme.colorScheme.error },
                colorForeground = { MaterialTheme.colorScheme.onError },
                title = title,
                subtitle = description,
                subtitleCollapsed = description.take(
                    min(MAX_ERROR_DESCRIPTION_COLLAPSED_LENGTH, description.length),
                ),
            )
        }
    }
}
