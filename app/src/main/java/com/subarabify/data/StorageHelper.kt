package com.subarabify.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

object StorageHelper {
    // Boilerplate helper for Storage Access Framework actions
    fun getFolderFromUri(context: Context, folderUriString: String): DocumentFile? {
        val folderUri = Uri.parse(folderUriString)
        return DocumentFile.fromTreeUri(context, folderUri)
    }
}
