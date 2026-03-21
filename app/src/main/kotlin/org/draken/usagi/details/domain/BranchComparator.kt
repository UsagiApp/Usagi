package org.draken.usagi.details.domain

import org.draken.usagi.core.util.LocaleStringComparator
import org.draken.usagi.details.ui.model.MangaBranch

class BranchComparator : Comparator<MangaBranch> {

	private val delegate = LocaleStringComparator()

	override fun compare(o1: MangaBranch, o2: MangaBranch): Int = delegate.compare(o1.name, o2.name)
}
