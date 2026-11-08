package org.draken.usagi.core.ui.util

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.graphics.createBitmap
import androidx.core.view.OneShotPreDrawListener
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.motion.MotionUtils
import org.draken.usagi.core.ui.DefaultActivityLifecycleCallbacks
import org.draken.usagi.core.util.ext.isLowRamDevice
import org.draken.usagi.core.util.ext.printStackTraceDebug
import java.util.Collections
import java.util.WeakHashMap
import javax.inject.Inject
import javax.inject.Singleton
import com.google.android.material.R as materialR

private const val MAX_SETTLE_TIME = 400L

@Singleton
class ActivityRecreationHandle
	@Inject
	constructor() : DefaultActivityLifecycleCallbacks {
		private val activities = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())
		private val stale = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())
		private var snapshot: Snapshot? = null

		override fun onActivityCreated(
			activity: Activity,
			savedInstanceState: Bundle?,
		) {
			activities.add(activity)
		}

		override fun onActivityStarted(activity: Activity) {
			if (stale.remove(activity)) {
				ActivityCompat.recreate(activity)
			}
		}

		override fun onActivityResumed(activity: Activity) {
			val pending = snapshot ?: return
			if (pending.activityClass == activity.javaClass && pending.taskId == activity.taskId) {
				snapshot = null
				fadeOut(activity, pending.bitmap)
			}
		}

		override fun onActivityDestroyed(activity: Activity) {
			activities.remove(activity)
			stale.remove(activity)
		}

		fun recreateAll(waitForAnimations: Boolean = false) {
			recreateOrMarkStale(activities.toList(), waitForAnimations)
		}

		fun recreate(cls: Class<out Activity>) {
			recreateOrMarkStale(activities.filter { it.javaClass == cls }, waitForAnimations = false)
		}

		private fun recreateOrMarkStale(
			targets: List<Activity>,
			waitForAnimations: Boolean,
		) {
			val (visible, hidden) = targets.partition { it.isVisible() }
			stale.addAll(hidden)
			if (visible.isEmpty()) {
				recreateHiddenWhenIdle()
			}
			visible.forEach { activity -> captureThen(activity, waitForAnimations) { ActivityCompat.recreate(activity) } }
		}

		fun setNightMode(mode: Int) {
			val (visible, hidden) = activities.toList().partition { it.isVisible() }
			for (activity in hidden) {
				(activity as? AppCompatActivity)?.delegate?.localNightMode = activity.currentNightMode()
				stale.add(activity)
			}
			val activity = visible.firstOrNull()
			if (activity == null) {
				AppCompatDelegate.setDefaultNightMode(mode)
				recreateHiddenWhenIdle()
			} else {
				captureThen(activity, waitForAnimations = false) { AppCompatDelegate.setDefaultNightMode(mode) }
			}
		}

		private fun recreateHiddenWhenIdle() {
			Looper.myQueue().addIdleHandler {
				for (activity in stale.toList()) {
					if (!activity.isVisible() && !activity.isFinishing) {
						stale.remove(activity)
						ActivityCompat.recreate(activity)
					}
				}
				false
			}
		}

		private fun captureThen(
			activity: Activity,
			waitForAnimations: Boolean,
			action: () -> Unit,
		) {
			val decor = activity.window.peekDecorView()
			if (decor == null || decor.width == 0 || decor.height == 0 || activity.isLowRamDevice()) {
				action()
				recreateHiddenWhenIdle()
				return
			}
			val capture = capture@{
				if (activity.isDestroyed) {
					return@capture
				}
				val bitmap = createBitmap(decor.width, decor.height)
				val isDrawn =
					runCatching { decor.draw(Canvas(bitmap)) }
						.onFailure { it.printStackTraceDebug() }
						.isSuccess
				if (isDrawn) {
					snapshot?.bitmap?.recycle()
					snapshot = Snapshot(activity.javaClass, activity.taskId, bitmap)
				} else {
					bitmap.recycle()
					recreateHiddenWhenIdle()
				}
				action()
			}
			if (!waitForAnimations) {
				decor.postOnAnimation { decor.post { capture() } }
				return
			}
			var draws = 1
			val drawCounter = ViewTreeObserver.OnDrawListener { draws++ }
			decor.viewTreeObserver.addOnDrawListener(drawCounter)
			val deadline = SystemClock.uptimeMillis() + MAX_SETTLE_TIME
			val check =
				object : Runnable {
					override fun run() {
						if (activity.isDestroyed || draws == 0 || SystemClock.uptimeMillis() >= deadline) {
							decor.viewTreeObserver.removeOnDrawListener(drawCounter)
							capture()
						} else {
							draws = 0
							decor.postOnAnimation(this)
						}
					}
				}
			decor.postOnAnimation(check)
		}

		private fun fadeOut(
			activity: Activity,
			bitmap: Bitmap,
		) {
			val decor = activity.window.decorView as? ViewGroup
			if (decor == null) {
				bitmap.recycle()
				recreateHiddenWhenIdle()
				return
			}
			val overlay = ImageView(activity)
			overlay.setImageBitmap(bitmap)
			overlay.scaleType = ImageView.ScaleType.FIT_XY
			decor.addView(overlay, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
			val duration = MotionUtils.resolveThemeDuration(activity, materialR.attr.motionDurationShort3, 150).toLong()
			OneShotPreDrawListener.add(overlay) {
				overlay.post {
					overlay
						.animate()
						.alpha(0f)
						.setDuration(duration)
						.withEndAction {
							decor.removeView(overlay)
							overlay.setImageDrawable(null)
							bitmap.recycle()
							recreateHiddenWhenIdle()
						}.start()
				}
			}
		}

		private fun Activity.isVisible() = (this as? LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == true

		private fun Activity.currentNightMode() =
			if (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) {
				AppCompatDelegate.MODE_NIGHT_YES
			} else {
				AppCompatDelegate.MODE_NIGHT_NO
			}

		private class Snapshot(
			val activityClass: Class<out Activity>,
			val taskId: Int,
			val bitmap: Bitmap,
		)
	}
