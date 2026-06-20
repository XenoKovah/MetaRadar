package com.darkmentor.ui

import com.darkmentor.domain.model.DeviceData
import com.darkmentor.domain.model.DeviceFilter
import com.darkmentor.domain.model.LocationModel
import com.darkmentor.domain.model.ManufacturerInfo
import com.darkmentor.ui.about.AboutScreen
import com.darkmentor.ui.backgroundlocationrequest.BackgroundLocationRequestScreen
import com.darkmentor.ui.devicedetails.DeviceDetailsScreen
import com.darkmentor.ui.exclusionzones.ExclusionZonesScreen
import com.darkmentor.ui.filter.FilterUiState
import com.darkmentor.ui.filter.SelectFilterScreen
import com.darkmentor.ui.journal.JournalScreen
import com.darkmentor.ui.main.MainScreen
import com.darkmentor.ui.selectdevice.SelectDeviceScreen
import com.darkmentor.ui.selectlocation.SelectLocationScreen
import com.darkmentor.ui.selectmanufacturer.SelectManufacturerScreen
import com.darkmentor.utils.navigation.AddToStackCommand
import com.darkmentor.utils.navigation.BackCommand

object ScreenNavigationCommands {

    object OpenMainScreen : AddToStackCommand(screenFunction = { key, _ -> MainScreen.Screen() })

    class OpenCreateFilterScreen(
        initialFilterState: FilterUiState,
        onConfirm: (filterState: DeviceFilter) -> Unit,
        // Non-null when editing an existing custom filter — surfaces a top-bar trash icon that
        // pops the editor and removes the filter. Null on the create-new path so the icon
        // doesn't appear (there's nothing to delete yet).
        onDelete: (() -> Unit)? = null,
    ) : AddToStackCommand(screenFunction = { key, router ->
        SelectFilterScreen.Screen(initialFilterState, router, onConfirm, onDelete)
    })

    class OpenSelectManufacturerScreen(
        onSelected: (manufacturerInfo: ManufacturerInfo) -> Unit
    ) : AddToStackCommand(screenFunction = { key, _ ->
        SelectManufacturerScreen.Screen(onSelected = onSelected)
    })

    class OpenSelectDeviceScreen(
        onSelected: (device: DeviceData) -> Unit
    ) : AddToStackCommand(screenFunction = { key, _ ->
        SelectDeviceScreen.Screen(onSelected = onSelected)
    })

    class OpenDeviceDetailsScreen(val address: String) : AddToStackCommand(screenFunction = { key, _ ->
        DeviceDetailsScreen.Screen(address = address)
    })

    object OpenBackgroundLocationScreen : AddToStackCommand(screenFunction = { key, router ->
        BackgroundLocationRequestScreen.Screen()
    })

    object OpenAboutScreen : AddToStackCommand(screenFunction = { key, router ->
        AboutScreen.Screen(router)
    })

    object OpenJournalScreen : AddToStackCommand(screenFunction = { key, router ->
        JournalScreen.Screen(router)
    })

    object OpenExclusionZonesScreen : AddToStackCommand(screenFunction = { key, router ->
        ExclusionZonesScreen.Screen(router)
    })

    class OpenSelectLocationScreen(
        initialLocationModel: LocationModel?,
        initialRadius: Float?,
        onSelected: (location: LocationModel, radiusMeters: Float) -> Unit
    ) : AddToStackCommand(screenFunction = { key, router ->
        SelectLocationScreen.Screen(
            onSelected = { location, radius ->
                onSelected.invoke(location, radius)
                router.navigate(BackCommand)
            },
            onCloseClick = {
                router.navigate(BackCommand)
            },
            initialLocationModel = initialLocationModel,
            initialRadius = initialRadius,
        )
    })
}