package f.cking.software.ui.connectall

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
import f.cking.software.R
import f.cking.software.utils.graphic.ContentPlaceholder
import f.cking.software.utils.graphic.DeviceListItem
import f.cking.software.utils.graphic.Divider
import f.cking.software.utils.graphic.SystemNavbarSpacer
import f.cking.software.utils.graphic.Switcher
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
                value = viewModel.retryForever,
                title = stringResource(R.string.bulk_gatt_retry_forever_title),
                subtitle = stringResource(R.string.bulk_gatt_retry_forever_subtitle),
                onClick = { viewModel.onRetryForeverToggled() }
            )
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
            // Status line: while a pass is running this shows "Connecting X/Y …". Once the user
            // hits Stop (or before any run) it reverts to the live "N potentially connectable
            // devices visible" count, re-polled every 10 s by the VM and on every scan batch.
            // The persistent Done summary below captures the prior pass's outcome separately.
            Spacer(modifier = Modifier.height(8.dp))
            val statusOrCandidates = if (viewModel.inProgress) {
                viewModel.statusLine
            } else {
                stringResource(
                    R.string.connect_all_candidates_count,
                    viewModel.candidateDevices.size,
                    viewModel.candidateVendorFiltered,
                )
            }
            Text(text = statusOrCandidates, fontWeight = FontWeight.Light)
            if (viewModel.lastDoneSummary.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                ExpandableDoneSummary(viewModel)
            }
        }
    }

    @Composable
    private fun ExpandableDoneSummary(viewModel: ConnectAllViewModel) {
        // Persistent "Done: …" summary from the most recent completed pass. Always shown after
        // any pass finishes, so under "Retry forever" the user can still inspect prior errors
        // even after the next pass has already started.
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.onToggleErrorsExpanded() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (viewModel.errorsExpanded) Icons.Filled.KeyboardArrowDown
                                  else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(text = viewModel.lastDoneSummary, fontWeight = FontWeight.Light)
            }
            AnimatedVisibility(visible = viewModel.errorsExpanded) {
                Column(modifier = Modifier.padding(start = 24.dp, top = 4.dp)) {
                    if (viewModel.errorDetails.isEmpty()) {
                        Text(
                            text = stringResource(R.string.connect_all_errors_none),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        viewModel.errorDetails.forEach { entry ->
                            Text(
                                text = "• ${entry.device.buildDisplayName()} (${entry.device.address}): " +
                                        "${entry.outcome} — ${entry.message ?: "no details"}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                        }
                    }
                }
            }
        }
    }

}
