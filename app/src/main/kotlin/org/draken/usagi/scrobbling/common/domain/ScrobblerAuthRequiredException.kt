package org.draken.usagi.scrobbling.common.domain

import okio.IOException
import org.draken.usagi.scrobbling.common.domain.model.ScrobblerService

class ScrobblerAuthRequiredException(
	val scrobbler: ScrobblerService,
) : IOException()
