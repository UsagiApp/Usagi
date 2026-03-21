package org.draken.usagi.list.ui.adapter

import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegate
import org.draken.usagi.R
import org.draken.usagi.list.ui.model.ListModel
import org.draken.usagi.list.ui.model.LoadingFooter

fun loadingFooterAD() = adapterDelegate<LoadingFooter, ListModel>(R.layout.item_loading_footer) {
}