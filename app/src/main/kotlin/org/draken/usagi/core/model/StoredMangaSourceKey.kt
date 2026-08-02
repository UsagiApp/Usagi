package org.draken.usagi.core.model

import tsuki.model.MangaSource

fun mangaSourceFromStoredKey(key: String?): MangaSource = MangaSource(key)
