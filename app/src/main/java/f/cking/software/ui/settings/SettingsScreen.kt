package f.cking.software.ui.settings

import android.text.format.Formatter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vanpra.composematerialdialogs.rememberMaterialDialogState
import f.cking.software.BuildConfig
import f.cking.software.R
import f.cking.software.dateTimeStringFormat
import f.cking.software.utils.graphic.FABSpacer
import f.cking.software.utils.graphic.RoundedBox
import f.cking.software.utils.graphic.Switcher
import f.cking.software.utils.graphic.ThemedDialog
import org.koin.androidx.compose.koinViewModel

object SettingsScreen {

    @Composable
    fun Screen() {
        val viewModel: SettingsViewModel = koinViewModel()
        // Re-poll the BTIDES log size every time the Settings tab enters composition. The
        // ViewModel survives tab switches, so the cached size goes stale once the user runs a
        // scan / Connect All on another tab. Tab navigation tears down + rebuilds this
        // composable, so a `LaunchedEffect(Unit)` re-fires on each visit.
        LaunchedEffect(Unit) { viewModel.refreshBTIDESLogSize() }
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surface)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            ProjectInformationBlock(viewModel = viewModel)
            Spacer(modifier = Modifier.height(8.dp))
            DiscoveryTransportsBlock(viewModel = viewModel)
            Spacer(modifier = Modifier.height(8.dp))
            AppSettings(viewModel = viewModel)
            Spacer(modifier = Modifier.height(8.dp))
            DatabaseBlock(viewModel = viewModel)
            Spacer(modifier = Modifier.height(8.dp))
            BtidalpoolBlock(viewModel = viewModel)
            Spacer(modifier = Modifier.height(8.dp))
            BTIDESBlock(viewModel = viewModel)
            Spacer(modifier = Modifier.height(8.dp))
            LocationBlock(viewModel = viewModel)
            Spacer(modifier = Modifier.height(8.dp))
            JournalBlock(viewModel = viewModel)
            Spacer(modifier = Modifier.height(8.dp))
            AppInfo()
            FABSpacer()
        }
    }

    @Composable
    private fun LocationInfo(viewModel: SettingsViewModel) {
        Column {
            val locationData = viewModel.locationData
            if (locationData == null) {
                Text(text = stringResource(R.string.no_location_data_yet), fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = stringResource(R.string.location_fetches_only_if_service_is_turned_on), fontWeight = FontWeight.Light)
            } else {
                val formattedTime = locationData.emitTime.dateTimeStringFormat("HH:mm")
                Text(text = stringResource(R.string.last_location_update_time, formattedTime), fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = stringResource(R.string.lat_template, locationData.location.latitude), fontWeight = FontWeight.Light)
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = stringResource(R.string.lng_template, locationData.location.longitude), fontWeight = FontWeight.Light)
            }
        }
    }

    @Composable
    private fun LocationBlock(viewModel: SettingsViewModel) {
        RoundedBox(internalPaddings = 0.dp) {
            Box(modifier = Modifier.padding(16.dp)) {
                LocationInfo(viewModel)
            }
            UseGpsLocationOnly(viewModel)
        }
    }

    @Composable
    private fun DatabaseBlock(viewModel: SettingsViewModel) {
        RoundedBox {
            Text(text = stringResource(R.string.database_information), fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))

            val databaseInfo = viewModel.databaseInfo
            if (databaseInfo != null) {
                Text(text = stringResource(R.string.database_size, Formatter.formatFileSize(LocalContext.current, databaseInfo.sizeBytes)))
                Text(text = stringResource(R.string.database_devices_count, databaseInfo.totalDevices.toString()))
                Text(text = stringResource(R.string.database_locations_count, databaseInfo.totalGeotags.toString()))

                Spacer(modifier = Modifier.height(12.dp))
            }
            Text(text = stringResource(R.string.database_actions), fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))
            BackupDB(viewModel = viewModel)
            Spacer(modifier = Modifier.height(8.dp))
            RestoreDB(viewModel = viewModel)
            Spacer(modifier = Modifier.height(8.dp))
            ClearGarbageButton(viewModel)
            Spacer(modifier = Modifier.height(8.dp))
            ClearLocationsButton(viewModel)
            Spacer(modifier = Modifier.height(8.dp))
            ClearDatabaseButton(viewModel)
        }
    }

    @Composable
    private fun ClearDatabaseButton(viewModel: SettingsViewModel) {
        // Destructive: wipes every device + apple-contact row. Behind a confirm dialog with
        // a red "Confirm" so it doesn't blend into the surrounding non-destructive actions.
        val dialogState = rememberMaterialDialogState()
        ThemedDialog(
            dialogState = dialogState,
            buttons = {
                negativeButton(
                    text = stringResource(R.string.cancel),
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface),
                ) { dialogState.hide() }
                positiveButton(
                    text = stringResource(R.string.confirm),
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.error),
                ) {
                    dialogState.hide()
                    viewModel.onClearDatabaseClick()
                }
            },
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.clear_all_devices_title),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(text = stringResource(R.string.clear_all_devices_subtitle))
            }
        }
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = { dialogState.show() },
            enabled = !viewModel.clearDatabaseInProgress,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
        ) {
            Text(
                text = stringResource(R.string.settings_clear_database),
                color = MaterialTheme.colorScheme.onError,
            )
        }
    }

    @Composable
    private fun BtidalpoolBlock(viewModel: SettingsViewModel) {
        if (viewModel.btidalpoolPasteDialogVisible) {
            BtidalpoolPasteTokenDialog(
                inProgress = viewModel.btidalpoolSignInInProgress,
                onSubmit = { viewModel.onBtidalpoolPasteSubmit(it) },
                onDismiss = { viewModel.onBtidalpoolPasteDismiss() },
            )
        }
        viewModel.btidalpoolStatusDialogMessage?.let { message ->
            BtidalpoolStatusDialog(
                message = message,
                onDismiss = { viewModel.onBtidalpoolStatusDialogDismiss() },
            )
        }
        RoundedBox {
            Text(text = stringResource(R.string.btidalpool_section_title), fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = stringResource(R.string.btidalpool_section_description), fontWeight = FontWeight.Light)
            Spacer(modifier = Modifier.height(8.dp))
            val auth = viewModel.btidalpoolAuth
            if (auth == null) {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { viewModel.onBtidalpoolSignInClick() },
                ) {
                    Text(text = stringResource(R.string.btidalpool_sign_in_button), color = MaterialTheme.colorScheme.onPrimary)
                }
            } else {
                Text(
                    text = stringResource(R.string.btidalpool_signed_in_as, auth.email ?: "?"),
                    fontWeight = FontWeight.Light,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Switcher(
                    value = viewModel.btidalpoolUseTestDb,
                    title = stringResource(R.string.btidalpool_use_test_db_title),
                    subtitle = stringResource(R.string.btidalpool_use_test_db_subtitle),
                    onClick = { viewModel.onToggleBtidalpoolUseTestDb() },
                )
                Spacer(modifier = Modifier.height(8.dp))
                UploadToBtidalpoolButton(
                    viewModel = viewModel,
                    label = stringResource(R.string.btidalpool_upload_current_button),
                    onClick = { viewModel.onUploadCurrentBtidalpoolClick() },
                    // Bumped to 16sp from the prior 14sp so the "current" label is bigger
                    // than the default-sized "all" sibling — easier to spot at a glance for
                    // the more common single-log upload.
                    fontSize = 16.sp,
                )
                Spacer(modifier = Modifier.height(8.dp))
                UploadToBtidalpoolButton(
                    viewModel = viewModel,
                    label = stringResource(R.string.btidalpool_upload_all_button),
                    onClick = { viewModel.onUploadAllBtidalpoolClick() },
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { viewModel.onBtidalpoolSignOutClick() },
                    enabled = !viewModel.btidalpoolUploadInProgress,
                ) {
                    Text(text = stringResource(R.string.btidalpool_sign_out_button), color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }

    /**
     * Inline-progress upload button. Same fill-overlay pattern as [ExportBTIDESForADBButton]:
     * while [SettingsViewModel.btidalpoolUploadInProgress] is true, a translucent overlay
     * grows left-to-right tracking the export-then-network progress fraction. The flat
     * disabled colour matches the enabled colour so the button doesn't dim during the work.
     *
     * Both Upload-current and Upload-all share this composable; only the label and click
     * handler differ. The same VM progress state drives them — only one upload can run at a
     * time, and both buttons reflect the same in-progress state.
     */
    @Composable
    private fun UploadToBtidalpoolButton(
        viewModel: SettingsViewModel,
        label: String,
        onClick: () -> Unit,
        fontSize: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
    ) {
        val inProgress = viewModel.btidalpoolUploadInProgress
        val baseHeight = 40.dp
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(baseHeight),
        ) {
            val width = maxWidth
            Button(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(percent = 50)),
                onClick = onClick,
                enabled = !inProgress && !viewModel.btidesInProgress,
                colors = ButtonDefaults.buttonColors(
                    disabledContainerColor = MaterialTheme.colorScheme.primary,
                    disabledContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {}
            if (inProgress) {
                val animatedFraction by animateFloatAsState(
                    targetValue = viewModel.btidalpoolUploadProgress.coerceIn(0f, 1f),
                    animationSpec = tween(durationMillis = 200),
                    label = "btidalpoolUploadFraction",
                )
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(percent = 50))
                        .fillMaxWidth(animatedFraction)
                        .background(Color(0x554DB6AC)),
                )
            }
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                // Per-call-site font size: the "current" upload button passes 14sp explicitly
                // for its longer label; the "all" button leaves it Unspecified to inherit the
                // Compose Button default.
                Text(text = label, color = MaterialTheme.colorScheme.onPrimary, fontSize = fontSize)
            }
            @Suppress("UNUSED_EXPRESSION") width
        }
    }

    @Composable
    private fun BtidalpoolStatusDialog(message: String, onDismiss: () -> Unit) {
        // Modal acknowledgement: the only way out is the OK button. Uploads finish
        // asynchronously and the user may have switched apps in the meantime, so a toast
        // would be too easy to miss — especially on a duplicate / auth-failed result that
        // changes whether they need to sign in again.
        val dialogState = rememberMaterialDialogState(initialValue = true)
        LaunchedEffect(dialogState.showing) {
            if (!dialogState.showing) onDismiss()
        }
        ThemedDialog(
            dialogState = dialogState,
            buttons = {
                positiveButton(
                    text = stringResource(R.string.btidalpool_status_dialog_ok),
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface),
                ) { dialogState.hide() }
            },
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.btidalpool_status_dialog_title),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = message)
            }
        }
    }

    @Composable
    private fun BtidalpoolPasteTokenDialog(
        inProgress: Boolean,
        onSubmit: (String) -> Unit,
        onDismiss: () -> Unit,
    ) {
        val dialogState = rememberMaterialDialogState(initialValue = true)
        LaunchedEffect(dialogState.showing) {
            if (!dialogState.showing) onDismiss()
        }
        var pasted by remember { mutableStateOf("") }
        ThemedDialog(
            dialogState = dialogState,
            buttons = {
                negativeButton(
                    text = stringResource(R.string.cancel),
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface),
                ) { dialogState.hide() }
                positiveButton(
                    text = stringResource(R.string.btidalpool_paste_submit),
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface),
                ) {
                    onSubmit(pasted)
                }
            },
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.btidalpool_paste_dialog_title),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = stringResource(R.string.btidalpool_paste_dialog_subtitle))
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = pasted,
                    onValueChange = { pasted = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !inProgress,
                    placeholder = { Text(text = stringResource(R.string.btidalpool_paste_field_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    singleLine = false,
                    maxLines = 6,
                )
            }
        }
    }

    @Composable
    private fun BTIDESBlock(viewModel: SettingsViewModel) {
        RoundedBox {
            Text(text = stringResource(R.string.btides_section_title), fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = stringResource(R.string.btides_section_description), fontWeight = FontWeight.Light)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(
                    R.string.btides_log_size,
                    Formatter.formatFileSize(LocalContext.current, viewModel.btidesLogSizeBytes)
                ),
            )
            Spacer(modifier = Modifier.height(8.dp))

            ExportBTIDESForADBButton(viewModel = viewModel)
            Spacer(modifier = Modifier.height(8.dp))
            ExportBTIDESButton(viewModel = viewModel)
            Spacer(modifier = Modifier.height(8.dp))
            ClearCurrentBTIDESLogButton(viewModel = viewModel)
            Spacer(modifier = Modifier.height(8.dp))
            ClearAllBTIDESLogsButton(viewModel = viewModel)
        }
    }

    @Composable
    private fun JournalBlock(viewModel: SettingsViewModel) {
        RoundedBox {
            Text(text = stringResource(R.string.menu_journal), fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = stringResource(R.string.journal_block_description), fontWeight = FontWeight.Light)
            Spacer(modifier = Modifier.height(8.dp))
            Button(modifier = Modifier.fillMaxWidth(), onClick = { viewModel.onOpenJournalClick() }) {
                Text(text = stringResource(R.string.journal_open_button), color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }

    @Composable
    private fun ExportBTIDESForADBButton(viewModel: SettingsViewModel) {
        // Inline progress bar: while the export is in flight the button surface fills left-to-right
        // proportional to bytes-of-source-JSONL processed. Tapping while in flight surfaces a
        // cancel-confirmation dialog instead of starting a second export.
        val inProgress = viewModel.btidesInProgress
        val baseHeight = 40.dp

        if (viewModel.btidesCancelDialogVisible) {
            CancelBTIDESExportDialog(
                onConfirm = { viewModel.onConfirmCancelBTIDESExport() },
                onDismiss = { viewModel.onDismissCancelBTIDESExport() },
            )
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(baseHeight),
        ) {
            val width = maxWidth
            Button(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(percent = 50)),
                onClick = { viewModel.onExportBTIDESForAdbClick() },
                colors = ButtonDefaults.buttonColors(
                    disabledContainerColor = MaterialTheme.colorScheme.primary,
                    disabledContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {}
            if (inProgress) {
                // Smooth out the visual jumps between progress callbacks (which arrive per-line
                // and can come in bursts) so the bar slides rather than ticks.
                val animatedFraction by animateFloatAsState(
                    targetValue = viewModel.btidesProgress.coerceIn(0f, 1f),
                    animationSpec = tween(durationMillis = 200),
                    label = "btidesExportFraction",
                )
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(percent = 50))
                        .fillMaxWidth(animatedFraction)
                        .background(Color(0x554DB6AC)),
                )
            }
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text(text = stringResource(R.string.btides_export_for_adb), color = MaterialTheme.colorScheme.onPrimary)
            }
            @Suppress("UNUSED_EXPRESSION") width
        }
    }

    @Composable
    private fun BatteryOptimizationDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
        // Same dialog scaffold as CancelBTIDESExportDialog. Confirm → IntentHelper opens the
        // OS battery-optimisation page; dismiss → just hide the dialog (the toggle remains
        // ON, the user just chose not to opt out — the auto-start may then be killed by doze).
        val dialogState = rememberMaterialDialogState(initialValue = true)
        LaunchedEffect(dialogState.showing) {
            if (!dialogState.showing) onDismiss()
        }
        ThemedDialog(
            dialogState = dialogState,
            buttons = {
                negativeButton(
                    text = stringResource(R.string.background_startup_battery_dialog_dismiss),
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface),
                ) { dialogState.hide() }
                positiveButton(
                    text = stringResource(R.string.background_startup_battery_dialog_open_settings),
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface),
                ) {
                    dialogState.hide()
                    onConfirm()
                }
            },
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(text = stringResource(R.string.background_startup_battery_dialog_title), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(text = stringResource(R.string.background_startup_battery_dialog_message))
            }
        }
    }

    @Composable
    private fun CancelBTIDESExportDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
        val dialogState = rememberMaterialDialogState(initialValue = true)
        // Tying onCloseRequest into the VM happens via the dialog buttons below; observe state
        // changes so a tap-outside (which hides the dialog) also clears VM state.
        LaunchedEffect(dialogState.showing) {
            if (!dialogState.showing) onDismiss()
        }
        ThemedDialog(
            dialogState = dialogState,
            buttons = {
                negativeButton(
                    text = stringResource(R.string.cancel),
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface),
                ) { dialogState.hide() }
                positiveButton(
                    text = stringResource(R.string.confirm),
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface),
                ) {
                    dialogState.hide()
                    onConfirm()
                }
            },
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(text = stringResource(R.string.btides_export_cancel_title), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(text = stringResource(R.string.btides_export_cancel_subtitle))
            }
        }
    }

    @Composable
    private fun ExportBTIDESButton(viewModel: SettingsViewModel) {
        val dialogState = rememberMaterialDialogState()

        ThemedDialog(
            dialogState = dialogState,
            buttons = {
                negativeButton(
                    text = stringResource(R.string.cancel),
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface)
                ) { dialogState.hide() }
                positiveButton(text = stringResource(R.string.confirm), textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface)) {
                    dialogState.hide()
                    viewModel.onExportBTIDESClick()
                }
            },
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(text = stringResource(R.string.btides_export_dialog_title), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(text = stringResource(R.string.btides_export_dialog_subtitle))
            }
        }

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = { dialogState.show() },
            enabled = !viewModel.btidesInProgress,
        ) {
            Text(text = stringResource(R.string.btides_export_to_file), color = MaterialTheme.colorScheme.onPrimary)
        }
    }

    @Composable
    private fun ClearCurrentBTIDESLogButton(viewModel: SettingsViewModel) {
        ConfirmingDestructiveBTIDESButton(
            buttonLabel = stringResource(R.string.btides_clear_current_log),
            confirmTitle = stringResource(R.string.btides_clear_current_log_confirm_title),
            confirmSubtitle = stringResource(R.string.btides_clear_current_log_confirm_subtitle),
            enabled = !viewModel.btidesInProgress,
            onConfirm = { viewModel.onClearCurrentBTIDESLogClick() },
        )
    }

    @Composable
    private fun ClearAllBTIDESLogsButton(viewModel: SettingsViewModel) {
        ConfirmingDestructiveBTIDESButton(
            buttonLabel = stringResource(R.string.btides_clear_all_logs),
            confirmTitle = stringResource(R.string.btides_clear_all_logs_confirm_title),
            confirmSubtitle = stringResource(R.string.btides_clear_all_logs_confirm_subtitle),
            enabled = !viewModel.btidesInProgress,
            onConfirm = { viewModel.onClearAllBTIDESLogsClick() },
        )
    }

    /**
     * Shared scaffolding for the two destructive Clear buttons in the BTIDES block. Renders
     * the same red-on-red [error/onError] palette as Clear Database and Clear Locations
     * History elsewhere on the Settings screen, gated behind a confirm dialog.
     */
    @Composable
    private fun ConfirmingDestructiveBTIDESButton(
        buttonLabel: String,
        confirmTitle: String,
        confirmSubtitle: String,
        enabled: Boolean,
        onConfirm: () -> Unit,
    ) {
        val dialogState = rememberMaterialDialogState()
        ThemedDialog(
            dialogState = dialogState,
            buttons = {
                negativeButton(
                    text = stringResource(R.string.cancel),
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface)
                ) { dialogState.hide() }
                positiveButton(
                    text = stringResource(R.string.confirm),
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface),
                ) {
                    dialogState.hide()
                    onConfirm()
                }
            },
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(text = confirmTitle, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(text = confirmSubtitle)
            }
        }
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = { dialogState.show() },
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
        ) {
            Text(text = buttonLabel, color = MaterialTheme.colorScheme.onError)
        }
    }

    @Composable
    private fun ClearGarbageButton(viewModel: SettingsViewModel) {
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = { viewModel.onRemoveGarbageClick() },
            enabled = !viewModel.garbageRemovingInProgress
        ) {
            Text(text = stringResource(R.string.clear_garbage), color = MaterialTheme.colorScheme.onPrimary)
        }
    }

    @Composable
    private fun RestoreDB(viewModel: SettingsViewModel) {
        val dialogState = rememberMaterialDialogState()

        ThemedDialog(
            dialogState = dialogState,
            buttons = {
                negativeButton(
                    text = stringResource(R.string.cancel),
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface)
                ) { dialogState.hide() }
                positiveButton(text = stringResource(R.string.confirm), textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface)) {
                    dialogState.hide()
                    viewModel.onRestoreDBClick()
                }
            },
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(text = stringResource(R.string.restore_data_from_file_title), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(text = stringResource(R.string.restore_data_from_file_subtitle))
            }
        }

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = { dialogState.show() },
            enabled = !viewModel.backupDbInProgress
        ) {
            Text(text = stringResource(R.string.settings_restore_database), color = MaterialTheme.colorScheme.onPrimary)
        }
    }

    @Composable
    private fun BackupDB(viewModel: SettingsViewModel) {
        val dialogState = rememberMaterialDialogState()

        ThemedDialog(
            dialogState = dialogState,
            buttons = {
                negativeButton(
                    text = stringResource(R.string.cancel),
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface)
                ) { dialogState.hide() }
                positiveButton(text = stringResource(R.string.confirm), textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface)) {
                    dialogState.hide()
                    viewModel.onBackupDBClick()
                }
            },
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(text = stringResource(R.string.backup_database_title), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(text = stringResource(R.string.backup_database_subtitle))
            }
        }

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = { dialogState.show() },
            enabled = !viewModel.backupDbInProgress
        ) {
            Text(text = stringResource(R.string.settings_backup_database), color = MaterialTheme.colorScheme.onPrimary)
        }
    }

    @Composable
    private fun ClearLocationsButton(viewModel: SettingsViewModel) {

        val dialogState = rememberMaterialDialogState()

        ThemedDialog(
            dialogState = dialogState,
            buttons = {
                negativeButton(
                    text = stringResource(R.string.cancel),
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface)
                ) { dialogState.hide() }
                positiveButton(text = stringResource(R.string.confirm), textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface)) {
                    dialogState.hide()
                    viewModel.onClearLocationsClick()
                }
            },
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(text = stringResource(R.string.clear_all_location_history_dialog_title), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = { dialogState.show() },
            enabled = !viewModel.locationRemovingInProgress,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
        ) {
            Text(
                text = stringResource(R.string.settings_clear_all_location_history),
                color = MaterialTheme.colorScheme.onError,
            )
        }
    }

    @Composable
    private fun UseGpsLocationOnly(viewModel: SettingsViewModel) {
        Switcher(
            value = viewModel.useGpsLocationOnly,
            title = stringResource(R.string.settings_use_gps_title),
            subtitle = stringResource(R.string.settings_use_gps_subtitle),
            onClick = { viewModel.onUseGpsLocationOnlyClick() }
        )
    }

    @Composable
    private fun DiscoveryTransportsBlock(viewModel: SettingsViewModel) {
        RoundedBox(internalPaddings = 0.dp) {
            Text(
                modifier = Modifier.padding(16.dp),
                text = stringResource(R.string.discovery_transports_title),
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Switcher(
                value = viewModel.discoverLeEnabled,
                title = stringResource(R.string.discover_le_title),
                subtitle = stringResource(R.string.discover_le_subtitle),
                onClick = { viewModel.toggleDiscoverLe() },
            )
            Switcher(
                value = viewModel.discoverBrEdrEnabled,
                title = stringResource(R.string.discover_br_edr_title),
                subtitle = stringResource(R.string.discover_br_edr_subtitle),
                onClick = { viewModel.toggleDiscoverBrEdr() },
            )
        }
    }

    @Composable
    private fun AppSettings(viewModel: SettingsViewModel) {
        if (viewModel.batteryOptimizationDialogVisible) {
            BatteryOptimizationDialog(
                onConfirm = { viewModel.onBatteryOptimizationDialogOpenSettings() },
                onDismiss = { viewModel.onBatteryOptimizationDialogDismiss() },
            )
        }
        RoundedBox(internalPaddings = 0.dp) {
            Text(modifier = Modifier.padding(16.dp), text = stringResource(id = R.string.app_settings_title), fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(4.dp))
            Switcher(
                value = viewModel.silentModeEnabled,
                title = stringResource(R.string.silent_mode_title),
                subtitle = stringResource(id = R.string.silent_mode_subtitle),
                onClick = { viewModel.changeSilentMode() }
            )
            Switcher(
                value = viewModel.runOnStartup,
                title = stringResource(R.string.launch_on_system_startup_title),
                subtitle = null,
                onClick = { viewModel.setRunOnStartup() }
            )
            Switcher(
                value = viewModel.runConnectAllOnStartup,
                title = stringResource(R.string.launch_connect_all_on_startup_title),
                subtitle = stringResource(R.string.launch_connect_all_on_startup_subtitle),
                onClick = { viewModel.setRunConnectAllOnStartup() }
            )

            Switcher(
                value = viewModel.wakeUpWhileScanning,
                title = stringResource(R.string.settings_keep_screen_on_while_scanning_title),
                subtitle = stringResource(R.string.settings_keep_screen_on_while_scanning_description),
                onClick = {
                    viewModel.toggleWakeUpOnScreen()
                }
            )
        }
    }

    @Composable
    private fun ProjectInformationBlock(viewModel: SettingsViewModel) {
        RoundedBox {
            Text(text = stringResource(R.string.project_github_title, stringResource(id = R.string.app_name)), fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(4.dp))
            Button(modifier = Modifier.fillMaxWidth(), onClick = { viewModel.onGithubClick() }) {
                Text(text = stringResource(R.string.open_github), color = MaterialTheme.colorScheme.onPrimary)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = stringResource(R.string.report_issue_title), fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(4.dp))
            Button(modifier = Modifier.fillMaxWidth(), onClick = { viewModel.onReportIssueClick() }) {
                Text(text = stringResource(R.string.report), color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }

    @Composable
    private fun AppInfo() {
        RoundedBox {
            Text(text = stringResource(R.string.app_info_title), fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = stringResource(R.string.app_info_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE))
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = stringResource(if (BuildConfig.DEBUG) R.string.app_info_build_type_debug else R.string.app_info_build_type_release))
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = stringResource(R.string.app_info_distribution, BuildConfig.DISTRIBUTION))
        }
    }

}
