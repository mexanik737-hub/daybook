package ru.daybook.app.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Shared JSON codec for the existing Daybook Drive document format.
 * The web client currently stores { tasks: [], notes: [] } with the same
 * entry fields represented by DaybookEntry.
 */
object DaybookJson {
    val format: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(document: DaybookDocument): String =
        format.encodeToString(document)

    fun decode(json: String): DaybookDocument =
        format.decodeFromString<DaybookDocument>(json)
}
