package org.draken.usagi.local.domain

import org.draken.usagi.core.util.MultiMutex
import tsuki.model.Manga
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MangaLock @Inject constructor() : MultiMutex<Manga>()
