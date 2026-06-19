package com.darkmentor.ui.backgroundlocationrequest

import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.darkmentor.collectAsState
import com.darkmentor.data.helpers.PermissionHelper
import com.darkmentor.utils.navigation.BackCommand
import com.darkmentor.utils.navigation.Router
import kotlinx.coroutines.flow.map

class BackgroundLocationRequestViewModel(
    private val permissionHelper: PermissionHelper,
    private val router: Router,
) : ViewModel() {

    val grantButtonEnabled by permissionHelper.observeBackgroundLocationPermission()
        .map { !it }
        .collectAsState(viewModelScope, true)

    fun onBack() {
        router.navigate(BackCommand)
    }

    fun grantPermission() {
        permissionHelper.checkOrRequestPermission {
            permissionHelper.checkOrRequestPermission(permissions = PermissionHelper.BACKGROUND_LOCATION) {
                onBack()
            }
        }
    }
}