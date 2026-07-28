package org.draken.usagi.local.data

import android.content.ContentResolver
import android.net.Uri

private fun isPdfExtension(ext: String?): Boolean = ext.equals("pdf", ignoreCase = true)

fun hasPdfExtension(string: String): Boolean {
	val ext = string.substringAfterLast('.', "")
	return isPdfExtension(ext)
}

fun isPdfUri(
	contentResolver: ContentResolver,
	uri: Uri,
): Boolean = contentResolver.getType(uri) == "application/pdf"
