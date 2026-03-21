package org.draken.usagi.settings.storage.directories

import org.draken.usagi.list.ui.model.ListModel
import java.io.File

data class DirectoryConfigModel(
    val title: String,
    val path: File,
    val isDefault: Boolean,
    val isAppPrivate: Boolean,
    val isAccessible: Boolean,
    val size: Long,
    val available: Long,
) : ListModel {

    override fun areItemsTheSame(other: ListModel): Boolean {
        return other is DirectoryConfigModel && path == other.path
    }
}
