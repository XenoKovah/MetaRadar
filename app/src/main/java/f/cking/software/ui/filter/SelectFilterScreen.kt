package f.cking.software.ui.filter

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import f.cking.software.R
import f.cking.software.domain.model.DeviceFilter
import f.cking.software.utils.graphic.SystemNavbarSpacer
import f.cking.software.utils.navigation.BackCommand
import f.cking.software.utils.navigation.Router

@OptIn(ExperimentalMaterial3Api::class)
object SelectFilterScreen {

    @Composable
    fun Screen(
        initialFilterState: FilterUiState,
        router: Router,
        onConfirm: (filterState: DeviceFilter) -> Unit,
        // When non-null, the editor is being opened to edit an *existing* filter rather than
        // create a new one. The trash icon in the top app bar fires this callback and pops the
        // screen so the user has a single, obvious place to delete an existing filter (replaces
        // the old "delete by tapping the chip" affordance, which conflated edit and delete).
        onDelete: (() -> Unit)? = null,
    ) {

        val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                AppBar(
                    scrollBehavior = scrollBehavior,
                    onBackClick = { router.navigate(BackCommand) },
                    onDeleteClick = onDelete?.let { delete ->
                        {
                            router.navigate(BackCommand)
                            delete.invoke()
                        }
                    },
                )
            },
            content = { paddings ->
                val context = LocalContext.current
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(top = paddings.calculateTopPadding())
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                    ) {
                        FilterScreen.Filter(
                            filterState = initialFilterState,
                            router = router,
                            onDeleteClick = { router.navigate(BackCommand) }
                        )
                    }
                    Button(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        onClick = {
                            val filter = initialFilterState
                                .takeIf { it.isCorrect() }
                                ?.let { FilterUiMapper.mapToDomain(it) }

                            if (filter != null) {
                                router.navigate(BackCommand)
                                onConfirm.invoke(filter)
                            } else {
                                Toast.makeText(context, context.getString(R.string.filter_is_not_valid), Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Text(text = stringResource(R.string.confirm), color = MaterialTheme.colorScheme.onPrimary)
                    }
                    SystemNavbarSpacer()
                }
            }
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun AppBar(
        scrollBehavior: TopAppBarScrollBehavior,
        onBackClick: () -> Unit,
        onDeleteClick: (() -> Unit)?,
    ) {
        TopAppBar(
            scrollBehavior = scrollBehavior,
            colors = TopAppBarDefaults.topAppBarColors(
                scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
            title = {
                Text(text = stringResource(R.string.create_filter))
            },
            navigationIcon = {
                IconButton(onClick = onBackClick) {
                    Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
            },
            actions = {
                if (onDeleteClick != null) {
                    IconButton(onClick = onDeleteClick) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.delete),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
        )
    }
}