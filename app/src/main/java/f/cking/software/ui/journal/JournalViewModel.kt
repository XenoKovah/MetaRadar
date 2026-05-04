package f.cking.software.ui.journal

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
import f.cking.software.R
import f.cking.software.data.repo.JournalRepository
import f.cking.software.dateTimeStringFormat
import f.cking.software.domain.model.JournalEntry
import f.cking.software.ui.ScreenNavigationCommands
import f.cking.software.utils.navigation.Router
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

class JournalViewModel(
    private val journalRepository: JournalRepository,
    private val router: Router,
    private val context: Application,
) : ViewModel() {

    var journal: List<JournalEntryUiModel> by mutableStateOf(emptyList())
    var loading by mutableStateOf(true)

    init {
        observeJournal()
    }

    fun onEntryClick(journalEntry: JournalEntry) {
        // do nothing
    }

    fun onJournalListItemClick(payload: String?) {
        if (payload != null) {
            router.navigate(ScreenNavigationCommands.OpenDeviceDetailsScreen(payload))
        } else {
            Toast.makeText(context, context.getString(R.string.journal_device_was_removed), Toast.LENGTH_SHORT).show()
        }
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
            history.asSequence()
                .sortedByDescending { it.timestamp }
                .map { map(it) }
                .toList()
        }
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
        val title = if (report.title.length > MAX_ERROR_TITLE_LENGTH) {
            report.title.substring(0 until MAX_ERROR_TITLE_LENGTH)
        } else {
            report.title
        }
        val description = report.stackTrace
        return JournalEntryUiModel(
            dateTime = journalEntry.timestamp.formattedDate(),
            color = { MaterialTheme.colorScheme.error },
            colorForeground = { MaterialTheme.colorScheme.onError },
            title = title,
            subtitle = description,
            subtitleCollapsed = description.substring(0 until min(MAX_ERROR_DESCRIPTION_COLLAPSED_LENGTH, description.length)),
            journalEntry = journalEntry,
            items = null,
        )
    }

    private fun Long.formattedDate() = dateTimeStringFormat("dd MMM yyyy, HH:mm")

    data class JournalEntryUiModel(
        val dateTime: String,
        val color: @Composable () -> Color,
        val colorForeground: @Composable () -> Color,
        val title: String,
        val subtitle: String?,
        val subtitleCollapsed: String?,
        val items: List<ListItemUiModel>?,
        val journalEntry: JournalEntry,
    ) {
        data class ListItemUiModel(
            val displayName: String,
            val payload: String?,
        )
    }

    companion object {
        private const val MAX_ERROR_TITLE_LENGTH = 256
        private const val MAX_ERROR_DESCRIPTION_COLLAPSED_LENGTH = 500
    }
}
