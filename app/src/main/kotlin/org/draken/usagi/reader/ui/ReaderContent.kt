package org.draken.usagi.reader.ui

import org.draken.usagi.reader.ui.pager.ReaderPage

data class ReaderContent(
	val pages: List<ReaderPage>,
	val state: ReaderState?
)