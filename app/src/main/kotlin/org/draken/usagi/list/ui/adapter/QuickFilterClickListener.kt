package org.draken.usagi.list.ui.adapter

import org.draken.usagi.list.domain.ListFilterOption

interface QuickFilterClickListener {

	fun onFilterOptionClick(option: ListFilterOption)
}
