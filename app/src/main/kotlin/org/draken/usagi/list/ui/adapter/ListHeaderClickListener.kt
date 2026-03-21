package org.draken.usagi.list.ui.adapter

import android.view.View
import org.draken.usagi.list.ui.model.ListHeader

interface ListHeaderClickListener {

	fun onListHeaderClick(item: ListHeader, view: View)
}
