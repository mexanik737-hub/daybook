package ru.daybook.app.data

import android.content.Context

/**
 * Stores the last common snapshot used by the three-way Drive sync merge.
 * This is separate from the user's notes database so clearing/rebuilding the
 * local cache cannot silently erase the conflict-detection baseline.
 */
class DaybookSyncStateStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "daybook_drive_sync",
        Context.MODE_PRIVATE
    )

    fun baselineJson(): String? = preferences.getString(KEY_BASELINE, null)
    fun remoteFileId(): String? = preferences.getString(KEY_FILE_ID, null)

    fun save(remoteFileId: String, baselineJson: String) {
        preferences.edit()
            .putString(KEY_FILE_ID, remoteFileId)
            .putString(KEY_BASELINE, baselineJson)
            .apply()
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    private companion object {
        const val KEY_BASELINE = "baseline_json"
        const val KEY_FILE_ID = "remote_file_id"
    }
}
