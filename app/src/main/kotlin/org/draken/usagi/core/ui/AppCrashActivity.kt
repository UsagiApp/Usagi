package org.draken.usagi.core.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process.killProcess
import android.os.Process.myPid
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import android.R as androidR
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.R as materialR
import org.draken.usagi.R
import org.draken.usagi.databinding.ActivityCrashBinding
import org.draken.usagi.main.ui.MainActivity

class AppCrashActivity : BaseActivity<ActivityCrashBinding>() {

	private var headingRunnable: Runnable? = null
	private val handler = Handler(Looper.getMainLooper())
	private val finishReceiver = object : BroadcastReceiver() {
		override fun onReceive(context: Context?, intent: Intent?) = finishAndRemoveTask()
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		applyTheme()
		super.onCreate(savedInstanceState)
		setContentView(ActivityCrashBinding.inflate(layoutInflater))
		val stackTrace = intent.getStringExtra(EXTRA_STACK_TRACE) ?: NO_TRACE
		viewBinding.crashTextView.text = stackTrace
		viewBinding.buttonCopy.setOnClickListener {
			getSystemService<ClipboardManager>()?.setPrimaryClip(
				ClipData.newPlainText("crash_log", stackTrace),
			)
		}
		viewBinding.buttonRestart.setOnClickListener {
			startActivity(
				Intent(this, MainActivity::class.java)
					.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
			)
			finishAndRemoveTask()
		}
		ContextCompat.registerReceiver(
			this, finishReceiver,
			IntentFilter(ACTION_FINISH_CRASH),
			ContextCompat.RECEIVER_NOT_EXPORTED,
		)
		setupView()
		setupText()
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat) = insets

	override fun onDestroy() {
		headingRunnable?.let(handler::removeCallbacks)
		runCatching { unregisterReceiver(finishReceiver) }
		super.onDestroy()
		if (isFinishing) killProcess(myPid())
	}

	private fun applyTheme() {
		val nightMode = intent.getIntExtra(EXTRA_THEME_NIGHT_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
		AppCompatDelegate.setDefaultNightMode(nightMode)
		val styleRes = intent.getIntExtra(EXTRA_THEME_STYLE, 0)
		if (styleRes != 0) setTheme(styleRes)
		if (intent.getBooleanExtra(EXTRA_THEME_AMOLED, false)) setTheme(R.style.ThemeOverlay_Usagi_Amoled)
	}

	private fun setupView() = with(viewBinding.starView) {
		val blended = ColorUtils.blendARGB(
			resolveColor(materialR.attr.colorPrimaryContainer),
			resolveColor(androidR.attr.colorBackground),
			0.5f,
		)
		setColorFilter(blended)
		ObjectAnimator.ofFloat(this, "rotation", -20f, 20f).apply {
			duration = 5_000
			repeatMode = ValueAnimator.REVERSE
			repeatCount = ValueAnimator.INFINITE
			start()
		}
	}

	private fun setupText() {
		var index = 0
		val titles = arrayOf(getString(R.string.crash_oops), getString(R.string.error_occurred))
		headingRunnable = object : Runnable {
			override fun run() {
				index = (index + 1) % titles.size
				viewBinding.heading.fadeTo(titles[index])
				handler.postDelayed(this, SWAP_DELAY)
			}
		}.also { handler.postDelayed(it, SWAP_DELAY) }
	}

	private fun resolveColor(attr: Int): Int {
		val tv = TypedValue()
		theme.resolveAttribute(attr, tv, true)
		return tv.data
	}

	private fun TextView.fadeTo(newText: String) {
		animate().alpha(0f).setDuration(FADE_DURATION).withEndAction {
			text = newText
			animate().alpha(1f).setDuration(FADE_DURATION).start()
		}.start()
	}

	companion object {
		const val EXTRA_STACK_TRACE = "stack_trace"
		const val EXTRA_THEME_STYLE = "theme_style"
		const val EXTRA_THEME_AMOLED = "theme_amoled"
		const val EXTRA_THEME_NIGHT_MODE = "theme_night_mode"
		const val ACTION_FINISH_CRASH = "org.draken.usagi.ACTION_FINISH_CRASH"
		private const val NO_TRACE = "noStackTrace"
		private const val SWAP_DELAY = 7_500L
		private const val FADE_DURATION = 300L
	}
}
