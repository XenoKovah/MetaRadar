package com.darkmentor.domain.model

/**
 * Apple shuffles BLE addresses each 15 minutes but we can use airdrop contacts fingerprint to distinguish them
 */
data class ManufacturerInfo(
    val id: Int,
    val name: String,
    var airdrop: AppleAirDrop?,
) {

    fun isApple(): Boolean {
        return id == APPLE_ID
    }

    companion object {
        const val APPLE_ID = 0x004C
        const val SAMSUNG_ID = 0x0075
    }
}