package com.darkmentor.domain.model

data class ExtendedAddressInfo(
    val address: String,
    val type: BleAddressType
) {
    enum class BleAddressType {
        PUBLIC,
        STATIC_RANDOM,
        RESOLVABLE_PRIVATE,
        NON_RESOLVABLE_PRIVATE,
        INVALID
    }
}