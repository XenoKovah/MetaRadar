package com.darkmentor.domain.model

data class SavedDeviceHandle(
    val previouslySeenAtTime: Long,
    val device: DeviceData,
    val airdrop: AirdropHandle?,
) {
    data class AirdropHandle(
        val contactShaToPreviouslySeenAtTime: Map<Int, Long>,
    )
}