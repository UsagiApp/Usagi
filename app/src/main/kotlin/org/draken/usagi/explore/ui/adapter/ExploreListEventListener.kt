package org.draken.usagi.explore.ui.adapter

import android.view.View
import org.draken.usagi.list.ui.adapter.ListHeaderClickListener
import org.draken.usagi.list.ui.adapter.ListStateHolderListener

interface ExploreListEventListener : ListStateHolderListener, View.OnClickListener, ListHeaderClickListener
