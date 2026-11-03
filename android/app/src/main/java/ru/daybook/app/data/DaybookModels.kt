package ru.daybook.app.data

import kotlinx.serialization.Serializable

/**
 * Mirrors the existing web export format. Unknown JSON properties must be
 * retained during import/export so older and newer clients remain compatible.
 */
@Serializable
data class DaybookDocument(
    val tasks: List<DaybookEntry> = emptyList(),
    val notes: List<DaybookEntry> = emptyList()
)

@Serializable
data class DaybookEntry(
    val id: String,
    val title: String = "",
    val body: String = "",
    val created: Long = 0,
    val updated: Long = 0,
    val due: String? = null,
    val priority: String? = null,
    val done: Boolean? = null
)
