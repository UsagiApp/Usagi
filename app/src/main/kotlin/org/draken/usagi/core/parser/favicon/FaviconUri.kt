package org.draken.usagi.core.parser.favicon

import android.net.Uri
import tsuki.model.MangaSource

const val URI_SCHEME_FAVICON = "favicon"

fun MangaSource.faviconUri(): Uri = Uri.fromParts(URI_SCHEME_FAVICON, name, null)