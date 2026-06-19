package com.darkmentor.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

data class JournalEntry(
    val id: Int?,
    val timestamp: Long,
    val report: Report,
) : java.io.Serializable {

    @Serializable
    sealed class Report : java.io.Serializable {

        @Serializable
        @SerialName("error_report")
        data class Error(
            val title: String,
            val stackTrace: String,
        ) : Report()
    }
}
