package com.darkmentor.ui.connectall

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.darkmentor.R
import com.darkmentor.utils.graphic.ContentPlaceholder
import com.darkmentor.utils.graphic.DeviceListItem
import com.darkmentor.utils.graphic.Divider
import com.darkmentor.utils.graphic.SystemNavbarSpacer
import com.darkmentor.utils.graphic.Switcher
import org.koin.androidx.compose.koinViewModel

object ConnectAllScreen {

    @Composable
    fun Screen() {
        val viewModel: ConnectAllViewModel = koinViewModel()
        // Kick off scanning the moment the user lands on this pane so the candidate count
        // populates without them having to switch tabs to the Devices FAB. The VM polls every
        // 10 s after that, so the count keeps refreshing while they sit here. On dispose
        // (tab switch / back navigation) tear the scan down again — if Connect All started it,
        // it should die when Connect All goes away. Manual scans started via the Devices FAB
        // (mode == USER_EXPLICIT) are left alone by `onPaneHidden`.
        DisposableEffect(Unit) {
            viewModel.ensureScanRunning()
            onDispose { viewModel.onPaneHidden() }
        }
        // Single LazyColumn for the whole pane so the header (toggles, button, status, expandable
        // Done summary) scrolls together with the connected-device list. Only successfully-
        // enumerated devices are listed — the live candidate count appears in the status line
        // below the button to avoid implying that yet-unattempted devices have been verified.
        LazyColumn(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surface)
                .fillMaxSize()
        ) {
            item { Header(viewModel) }
            val connected = viewModel.connectedDevices
            if (connected.isEmpty()) {
                item {
                    ContentPlaceholder(
                        modifier = Modifier.fillMaxWidth(),
                        text = stringResource(R.string.connect_all_empty),
                    )
                }
            } else {
                connected.forEach { device ->
                    item {
                        DeviceListItem(
                            device = device,
                            showSignalData = device.rssi != null,
                            showLastUpdate = false,
                            onClick = { viewModel.onDeviceClick(device) },
                        )
                    }
                    item { Divider() }
                }
            }
            item { SystemNavbarSpacer() }
        }
    }

    @Composable
    private fun Header(viewModel: ConnectAllViewModel) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.connect_all_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Switcher(
                value = viewModel.bulkSkipApple,
                title = stringResource(R.string.bulk_gatt_skip_apple_title),
                subtitle = stringResource(R.string.bulk_gatt_skip_apple_subtitle),
                onClick = { viewModel.onSkipAppleToggled() }
            )
            Switcher(
                value = viewModel.bulkSkipSamsung,
                title = stringResource(R.string.bulk_gatt_skip_samsung_title),
                subtitle = stringResource(R.string.bulk_gatt_skip_samsung_subtitle),
                onClick = { viewModel.onSkipSamsungToggled() }
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { viewModel.onConnectAllClick() },
            ) {
                Text(
                    text = if (viewModel.inProgress) {
                        stringResource(R.string.connect_all_stop)
                    } else {
                        stringResource(R.string.connect_all_start)
                    },
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
            // Status block:
            //   - In-progress: a short headline ("Pass N — Starting…", or the latest finish
            //     line) followed by one line per active worker slot (4 LE + 1 BR/EDR, up to
            //     5 simultaneous "Connecting BDADDR Name…" rows).
            //   - Idle: the live "N potentially connectable devices visible" count, re-polled
            //     every 10 s by the VM and on every scan batch.
            // The persistent Done summary below captures the prior pass's outcome separately.
            Spacer(modifier = Modifier.height(8.dp))
            if (viewModel.inProgress) {
                Text(text = viewModel.statusLine, fontWeight = FontWeight.Light)
                viewModel.inFlightLines.forEach { line ->
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "• $line",
                        fontWeight = FontWeight.Light,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                Text(
                    text = stringResource(
                        R.string.connect_all_candidates_count,
                        viewModel.candidateDevices.size,
                        viewModel.candidateVendorFiltered,
                    ),
                    fontWeight = FontWeight.Light,
                )
            }
            // Three running-total categories (Connected / Errors / Too-many-attempts). Each
            // shows just its count when collapsed; expanding reveals the most-recent-first
            // device list. Always rendered (even with zero count) so the user knows the
            // session bookkeeping is live.
            Spacer(modifier = Modifier.height(8.dp))
            CategoryBlock(
                title = stringResource(R.string.connect_all_category_connected, viewModel.connectedEntries.size),
                expanded = viewModel.connectedExpanded,
                onToggle = { viewModel.onToggleConnectedExpanded() },
                emptyText = stringResource(R.string.connect_all_category_connected_empty),
                lines = viewModel.connectedEntries.map { entry ->
                    "• ${entry.device.buildDisplayName()} (${entry.device.address}): ${entry.outcome}"
                },
            )
            CategoryBlock(
                title = stringResource(R.string.connect_all_category_errors, viewModel.errorEntries.size),
                expanded = viewModel.errorsExpanded,
                onToggle = { viewModel.onToggleErrorsExpanded() },
                emptyText = stringResource(R.string.connect_all_category_errors_empty),
                lines = viewModel.errorEntries.map { entry ->
                    "• ${entry.device.buildDisplayName()} (${entry.device.address}) " +
                            "[attempt ${entry.attempts}]: ${entry.outcome} — ${entry.message ?: "no details"}"
                },
            )
            CategoryBlock(
                title = stringResource(R.string.connect_all_category_too_many_attempts, viewModel.tooManyAttemptsEntries.size),
                expanded = viewModel.tooManyAttemptsExpanded,
                onToggle = { viewModel.onToggleTooManyAttemptsExpanded() },
                emptyText = stringResource(R.string.connect_all_category_too_many_attempts_empty),
                lines = viewModel.tooManyAttemptsEntries.map { entry ->
                    "• ${entry.device.buildDisplayName()} (${entry.device.address}) " +
                            "[${entry.attempts} attempts]: ${entry.lastError ?: "no details"}"
                },
            )
        }
    }

    /**
     * Collapsible category row: header (arrow + count line) is the always-on tap target;
     * the body is the most-recent-first device list, materialised only when expanded.
     */
    @Composable
    private fun CategoryBlock(
        title: String,
        expanded: Boolean,
        onToggle: () -> Unit,
        emptyText: String,
        lines: List<String>,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowDown
                                  else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(text = title, fontWeight = FontWeight.Light)
            }
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(start = 24.dp, top = 4.dp)) {
                    if (lines.isEmpty()) {
                        Text(
                            text = emptyText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        lines.forEach { line ->
                            Text(text = line, style = MaterialTheme.typography.bodySmall)
                            Spacer(modifier = Modifier.height(2.dp))
                        }
                    }
                }
            }
        }
    }

}
