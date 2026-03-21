package org.draken.usagi.core.ui

import android.os.Bundle
import android.view.View
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import com.google.android.material.appbar.AppBarLayout
import dagger.hilt.android.AndroidEntryPoint
import org.draken.usagi.R
import org.draken.usagi.core.util.ext.consumeSystemBarsInsets
import org.draken.usagi.databinding.ActivityContainerBinding
import org.draken.usagi.main.ui.owners.AppBarOwner
import org.draken.usagi.main.ui.owners.SnackbarOwner

@AndroidEntryPoint
abstract class FragmentContainerActivity(private val fragmentClass: Class<out Fragment>) :
	BaseActivity<ActivityContainerBinding>(),
	AppBarOwner,
	SnackbarOwner {

	override val appBar: AppBarLayout
		get() = viewBinding.appbar

	override val snackbarHost: CoordinatorLayout
		get() = viewBinding.root

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityContainerBinding.inflate(layoutInflater))
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)
		val fm = supportFragmentManager
		if (fm.findFragmentById(R.id.container) == null) {
			fm.commit {
				setReorderingAllowed(true)
				replace(R.id.container, fragmentClass, getFragmentExtras())
			}
		}
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
		viewBinding.appbar.updatePadding(
			left = bars.left,
			right = bars.right,
			top = bars.top,
		)
		return insets.consumeSystemBarsInsets(top = true)
	}

	protected open fun getFragmentExtras(): Bundle? = intent.extras
}
