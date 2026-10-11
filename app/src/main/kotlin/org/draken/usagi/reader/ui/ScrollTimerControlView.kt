package org.draken.usagi.reader.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.CompoundButton
import androidx.annotation.StringRes
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.transition.Slide
import androidx.transition.TransitionManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.plus
import org.draken.usagi.R
import org.draken.usagi.core.prefs.AppSettings
import org.draken.usagi.core.prefs.HoldScrollDirection
import org.draken.usagi.core.prefs.ReaderMode
import org.draken.usagi.core.prefs.observeAsStateFlow
import org.draken.usagi.core.util.ext.isAnimationsEnabled
import org.draken.usagi.core.util.ext.observe
import org.draken.usagi.core.util.ext.parentView
import org.draken.usagi.databinding.ViewScrollTimerBinding
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.math.abs

@AndroidEntryPoint
class ScrollTimerControlView
	@JvmOverloads
	constructor(
		context: Context,
		attrs: AttributeSet? = null,
	) : ConstraintLayout(context, attrs),
		CompoundButton.OnCheckedChangeListener,
		Slider.OnChangeListener,
		View.OnClickListener,
		LabelFormatter {
		@Inject
		lateinit var settings: AppSettings

		var onVisibilityChangeListener: OnVisibilityChangeListener? = null

		private val binding = ViewScrollTimerBinding.inflate(LayoutInflater.from(context), this)

		private var scrollTimer: ScrollTimer? = null
		private var labelPattern = context.getString(R.string.speed_value)
		private var readerMode: ReaderMode = ReaderMode.STANDARD

		init {
			binding.switchScrollTimer.setOnCheckedChangeListener(this)
			binding.sliderTimer.addOnChangeListener(this)
			binding.buttonFab.setOnClickListener(this)
			binding.buttonHold.setOnClickListener(this)
			binding.buttonDirectionTop.setOnClickListener(this)
			binding.buttonDirectionMiddleLeft.setOnClickListener(this)
			binding.buttonDirectionMiddleRight.setOnClickListener(this)
			binding.buttonDirectionBottom.setOnClickListener(this)
			binding.sliderTimer.setLabelFormatter(this)
			binding.buttonClose.setOnClickListener(this)
			binding.buttonFab.isGone = resources.getBoolean(R.bool.is_tablet)
			setPadding(0, 0, 0, context.resources.getDimensionPixelOffset(R.dimen.margin_normal))
		}

		fun attach(
			timer: ScrollTimer,
			lifecycleOwner: LifecycleOwner,
		) {
			scrollTimer = timer
			timer.isActive.observe(lifecycleOwner) {
				binding.switchScrollTimer.setOnCheckedChangeListener(null)
				binding.switchScrollTimer.isChecked = it
				binding.switchScrollTimer.setOnCheckedChangeListener(this)
			}
			settings
				.observeAsStateFlow(
					scope = lifecycleOwner.lifecycleScope + Dispatchers.Default,
					key = AppSettings.KEY_READER_AUTOSCROLL_SPEED,
					valueProducer = { readerAutoscrollSpeed },
				).observe(lifecycleOwner) {
					if (abs(it - binding.sliderTimer.value) > 0.0001) {
						binding.sliderTimer.value =
							it.coerceIn(
								binding.sliderTimer.valueFrom,
								binding.sliderTimer.valueTo,
							)
					}
				}
			settings
				.observeAsStateFlow(
					scope = lifecycleOwner.lifecycleScope + Dispatchers.Default,
					key = AppSettings.KEY_READER_AUTOSCROLL_FAB,
					valueProducer = { isReaderAutoscrollFabVisible },
				).observe(lifecycleOwner) {
					binding.buttonFab.isChecked = it
				}
			settings
				.observeAsStateFlow(
					scope = lifecycleOwner.lifecycleScope + Dispatchers.Default,
					key = AppSettings.KEY_READER_AUTOSCROLL_HOLD,
					valueProducer = { isReaderAutoscrollHoldMode },
				).observe(lifecycleOwner) {
					binding.buttonHold.isChecked = it
					binding.layoutDirections.isGone = !it
				}
			settings
				.observeAsStateFlow(
					scope = lifecycleOwner.lifecycleScope + Dispatchers.Default,
					key = AppSettings.KEY_READER_AUTOSCROLL_DIR_TOP,
					valueProducer = { readerAutoscrollTopDirection },
				).observe(lifecycleOwner) {
					binding.buttonDirectionTop.text = formatDirection(R.string.hold_scroll_top_row, it)
				}
			settings
				.observeAsStateFlow(
					scope = lifecycleOwner.lifecycleScope + Dispatchers.Default,
					key = AppSettings.KEY_READER_AUTOSCROLL_DIR_MID_LEFT,
					valueProducer = { readerAutoscrollMiddleLeftDirection },
				).observe(lifecycleOwner) {
					binding.buttonDirectionMiddleLeft.text = formatDirection(R.string.hold_scroll_middle_left, it)
				}
			settings
				.observeAsStateFlow(
					scope = lifecycleOwner.lifecycleScope + Dispatchers.Default,
					key = AppSettings.KEY_READER_AUTOSCROLL_DIR_MID_RIGHT,
					valueProducer = { readerAutoscrollMiddleRightDirection },
				).observe(lifecycleOwner) {
					binding.buttonDirectionMiddleRight.text = formatDirection(R.string.hold_scroll_middle_right, it)
				}
			settings
				.observeAsStateFlow(
					scope = lifecycleOwner.lifecycleScope + Dispatchers.Default,
					key = AppSettings.KEY_READER_AUTOSCROLL_DIR_BOTTOM,
					valueProducer = { readerAutoscrollBottomDirection },
				).observe(lifecycleOwner) {
					binding.buttonDirectionBottom.text = formatDirection(R.string.hold_scroll_bottom_row, it)
				}
			updateDescription()
		}

		fun onReaderModeChanged(mode: ReaderMode) {
			readerMode = mode
			updateDescription()
		}

		override fun onClick(v: View) {
			when (v.id) {
				R.id.button_close -> hide()
				R.id.button_fab -> settings.isReaderAutoscrollFabVisible = !settings.isReaderAutoscrollFabVisible
				R.id.button_hold -> settings.isReaderAutoscrollHoldMode = !settings.isReaderAutoscrollHoldMode
				R.id.button_direction_top ->
					showDirectionDialog(R.string.hold_scroll_top_row, settings.readerAutoscrollTopDirection) {
						settings.readerAutoscrollTopDirection = it
					}

				R.id.button_direction_middle_left ->
					showDirectionDialog(R.string.hold_scroll_middle_left, settings.readerAutoscrollMiddleLeftDirection) {
						settings.readerAutoscrollMiddleLeftDirection = it
					}

				R.id.button_direction_middle_right ->
					showDirectionDialog(R.string.hold_scroll_middle_right, settings.readerAutoscrollMiddleRightDirection) {
						settings.readerAutoscrollMiddleRightDirection = it
					}

				R.id.button_direction_bottom ->
					showDirectionDialog(R.string.hold_scroll_bottom_row, settings.readerAutoscrollBottomDirection) {
						settings.readerAutoscrollBottomDirection = it
					}
			}
		}

		private fun showDirectionDialog(
			@StringRes titleRes: Int,
			current: HoldScrollDirection,
			onSelected: (HoldScrollDirection) -> Unit,
		) {
			val values = HoldScrollDirection.entries
			MaterialAlertDialogBuilder(context)
				.setTitle(titleRes)
				.setSingleChoiceItems(
					values.map { context.getString(it.labelResId) }.toTypedArray(),
					current.ordinal,
				) { dialog, which ->
					onSelected(values[which])
					dialog.dismiss()
				}
				.setNegativeButton(android.R.string.cancel, null)
				.show()
		}

		private fun formatDirection(
			@StringRes regionRes: Int,
			direction: HoldScrollDirection,
		): String =
			context.getString(
				R.string.hold_scroll_direction_pattern,
				context.getString(regionRes),
				context.getString(direction.labelResId),
			)

		override fun getFormattedValue(value: Float): String {
			val valueFrom = binding.sliderTimer.valueFrom
			val valueTo = binding.sliderTimer.valueTo
			val percent = (value - valueFrom) / (valueTo - valueFrom)
			return labelPattern.format(0.1 + percent * 10) // just something to display
		}

		override fun onValueChange(
			slider: Slider,
			value: Float,
			fromUser: Boolean,
		) {
			if (fromUser) {
				settings.readerAutoscrollSpeed = value
			}
			updateDescription()
		}

		override fun onCheckedChanged(
			buttonView: CompoundButton,
			isChecked: Boolean,
		) {
			scrollTimer?.setActive(isChecked)
		}

		override fun setVisibility(visibility: Int) {
			super.setVisibility(visibility)
			onVisibilityChangeListener?.onVisibilityChanged(this, visibility)
		}

		fun show() {
			setupVisibilityTransition()
			isVisible = true
		}

		fun hide() {
			setupVisibilityTransition()
			isVisible = false
		}

		fun showOrHide() {
			setupVisibilityTransition()
			isVisible = !isVisible
		}

		private fun setupVisibilityTransition() {
			if (context.isAnimationsEnabled) {
				val sceneRoot = parentView ?: return
				val transition = Slide()
				transition.addTarget(this)
				TransitionManager.beginDelayedTransition(sceneRoot, transition)
			}
		}

		private fun updateDescription() {
			val timePerPage = scrollTimer?.pageSwitchDelay ?: 0L
			if (timePerPage <= 0L || readerMode == ReaderMode.WEBTOON) {
				binding.textViewDescription.isVisible = false
			} else {
				binding.textViewDescription.text =
					context.getString(
						R.string.page_switch_timer,
						TimeUnit.MILLISECONDS.toSeconds((scrollTimer ?: return).pageSwitchDelay),
					)
				binding.textViewDescription.isVisible = true
			}
		}

		fun interface OnVisibilityChangeListener {
			fun onVisibilityChanged(
				v: View,
				visibility: Int,
			)
		}
	}
