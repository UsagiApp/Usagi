package org.draken.usagi.reader.ui.pager.curl

/** Whether a page turn can start right now. */
enum class CurlAvailability {
	OK,

	/** No page in that direction (first / last page). */
	NONE,

	/** Not possible yet (host is scrolling, neighbours not laid out). */
	BUSY,
}
