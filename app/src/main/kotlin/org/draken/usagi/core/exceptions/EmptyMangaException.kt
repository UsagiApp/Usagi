package org.draken.usagi.core.exceptions

import org.draken.usagi.details.ui.pager.EmptyMangaReason
import tsuki.model.Manga

class EmptyMangaException(
    val reason: EmptyMangaReason?,
    val manga: Manga,
    cause: Throwable?
) : IllegalStateException(cause)
