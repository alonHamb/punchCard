package com.punchcard.app.backup

import android.content.Context
import android.net.Uri
import androidx.core.content.edit

/** Tiny wrapper around SharedPreferences for the one setting the backup
 *  job needs outside the database: which folder to write CSVs into. */
class BackupPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("hours_log_prefs", Context.MODE_PRIVATE)

    fun getFolderUri(): Uri? = prefs.getString(KEY_FOLDER_URI, null)?.let { Uri.parse(it) }

    fun setFolderUri(uri: Uri) {
        prefs.edit { putString(KEY_FOLDER_URI, uri.toString()) }
    }

    fun getFolderDisplayName(): String? = prefs.getString(KEY_FOLDER_NAME, null)

    fun setFolderDisplayName(name: String) {
        prefs.edit { putString(KEY_FOLDER_NAME, name) }
    }

    companion object {
        private const val KEY_FOLDER_URI = "backup_folder_uri"
        private const val KEY_FOLDER_NAME = "backup_folder_name"
    }
}
