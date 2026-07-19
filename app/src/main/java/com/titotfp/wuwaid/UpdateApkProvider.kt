package com.titotfp.wuwaid

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

class UpdateApkProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String {
        fileFor(uri)
        return MIME_TYPE
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r") { "APK update hanya boleh dibaca" }
        val file = fileFor(uri)
        check(file.isFile) { "APK update tidak ditemukan" }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val file = fileFor(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns, 1)
        cursor.addRow(columns.map { column ->
            when (column) {
                OpenableColumns.DISPLAY_NAME -> FILE_NAME
                OpenableColumns.SIZE -> file.length()
                else -> null
            }
        })
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Provider hanya-baca")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = error("Provider hanya-baca")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        error("Provider hanya-baca")

    private fun fileFor(uri: Uri): File {
        val appContext = requireNotNull(context)
        require(uri.scheme == ContentResolver.SCHEME_CONTENT) { "Skema URI update tidak valid" }
        require(uri.authority == authority(appContext)) { "Authority URI update tidak valid" }
        require(uri.pathSegments == listOf(FILE_NAME)) { "Path URI update tidak valid" }
        val directory = requireNotNull(appContext.getExternalFilesDir(DIRECTORY))
        return File(directory, FILE_NAME)
    }

    companion object {
        const val DIRECTORY = "updates"
        const val FILE_NAME = "WuwaID-Mobile-update.apk"
        const val MIME_TYPE = "application/vnd.android.package-archive"

        fun uri(context: Context): Uri = Uri.Builder()
            .scheme(ContentResolver.SCHEME_CONTENT)
            .authority(authority(context))
            .appendPath(FILE_NAME)
            .build()

        private fun authority(context: Context): String = "${context.packageName}.updates"
    }
}
