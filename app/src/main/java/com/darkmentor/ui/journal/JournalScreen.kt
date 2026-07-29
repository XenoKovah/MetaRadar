package com.darkmentor.ui.journal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.darkmentor.R
import com.darkmentor.utils.graphic.ContentPlaceholder
import com.darkmentor.utils.graphic.Divider
import com.darkmentor.utils.graphic.SystemNavbarSpacer
import com.darkmentor.utils.navigation.BackCommand
import com.darkmentor.utils.navigation.Router
import org.koin.androidx.compose.koinViewModel

object JournalScreen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun Screen(router: Router) {
        val viewModel: JournalViewModel = koinViewModel()
        Scaffold(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surface)
                .fillMaxSize(),
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                    title = { Text(text = stringResource(R.string.menu_journal)) },
                    navigationIcon = {
                        IconButton(onClick = { router.navigate(BackCommand) }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                            )
                        }
                    }
                )
            },
            content = { paddings ->
                Content(
                    modifier = Modifier
                        .padding(top = paddings.calculateTopPadding(), bottom = paddings.calculateBottomPadding()),
                    viewModel = viewModel,
                )
            }
        )
    }

    @Composable
    private fun Content(modifier: Modifier, viewModel: JournalViewModel) {
        val journal = viewModel.journal
        var showClearDialog by remember { mutableStateOf(false) }

        Box(
            modifier = modifier
                .background(MaterialTheme.colorScheme.surface)
                .fillMaxSize()
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Header(
                    entryCount = journal.size,
                    onClearClick = { showClearDialog = true },
                )
                if (journal.isEmpty()) {
                    ContentPlaceholder(
                        modifier = Modifier.fillMaxSize(),
                        text = stringResource(R.string.journal_placeholder)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        journal.map {
                            item { JournalEntry(uiModel = it) }
                            item { Divider() }
                        }
                        item { SystemNavbarSpacer() }
                    }
                }
            }
            if (viewModel.loading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        if (showClearDialog) {
            AlertDialog(
                onDismissRequest = { showClearDialog = false },
                title = { Text(text = stringResource(R.string.journal_clear_dialog_title)) },
                text = { Text(text = stringResource(R.string.journal_clear_dialog_body)) },
                confirmButton = {
                    TextButton(onClick = {
                        showClearDialog = false
                        viewModel.clearJournal()
                    }) {
                        Text(text = stringResource(R.string.journal_clear_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearDialog = false }) {
                        Text(text = stringResource(R.string.cancel))
                    }
                }
            )
        }
    }

    @Composable
    private fun Header(entryCount: Int, onClearClick: () -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.journal_header_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (entryCount > 0) {
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(onClick = onClearClick) {
                    Text(text = stringResource(R.string.journal_clear_button))
                }
            }
        }
    }

    @Composable
    fun JournalEntry(uiModel: JournalViewModel.JournalEntryUiModel) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(uiModel.color())
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        modifier = Modifier.weight(1f),
                        text = uiModel.title,
                        fontWeight = FontWeight.Bold,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        color = uiModel.colorForeground()
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = uiModel.dateTime, fontWeight = FontWeight.Thin, color = uiModel.colorForeground())
                }
                Spacer(modifier = Modifier.height(4.dp))
                var isExpanded by remember { mutableStateOf(false) }

                uiModel.subtitle?.let { subtitle ->
                    Text(
                        modifier = Modifier.clickable {
                            isExpanded = !isExpanded
                        },
                        text = if (isExpanded) uiModel.subtitle else uiModel.subtitleCollapsed.orEmpty(),
                        fontWeight = FontWeight.Normal,
                        maxLines = if (isExpanded) Int.MAX_VALUE else 5,
                        overflow = TextOverflow.Ellipsis,
                        color = uiModel.colorForeground()
                    )
                }

            }
        }
    }
}
