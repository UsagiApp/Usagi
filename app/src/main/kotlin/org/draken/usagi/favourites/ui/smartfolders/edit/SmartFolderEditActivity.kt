package org.draken.usagi.favourites.ui.smartfolders.edit

import android.os.Bundle
import android.text.Editable
import android.view.View
import android.widget.ArrayAdapter
import androidx.activity.viewModels
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import org.draken.usagi.R
import org.draken.usagi.core.db.entity.toEntity
import org.draken.usagi.core.model.getTitle
import org.draken.usagi.core.ui.BaseActivity
import org.draken.usagi.core.ui.util.DefaultTextWatcher
import org.draken.usagi.core.util.ext.consumeAllSystemBarsInsets
import org.draken.usagi.core.util.ext.getDisplayMessage
import org.draken.usagi.core.util.ext.observe
import org.draken.usagi.core.util.ext.observeEvent
import org.draken.usagi.core.util.ext.sortedByOrdinal
import org.draken.usagi.core.util.ext.systemBarsInsets
import org.draken.usagi.databinding.ActivitySmartFolderEditBinding
import org.draken.usagi.favourites.domain.SmartFolder
import org.draken.usagi.favourites.domain.SmartFolderContent
import org.draken.usagi.favourites.domain.SmartFolderDevice
import org.draken.usagi.favourites.domain.SmartFolderRules
import org.draken.usagi.favourites.domain.SmartFolderRulesResult
import org.draken.usagi.list.domain.ListSortOrder

@AndroidEntryPoint
class SmartFolderEditActivity :
	BaseActivity<ActivitySmartFolderEditBinding>(),
	View.OnClickListener,
	DefaultTextWatcher {
	private val viewModel by viewModels<SmartFolderEditViewModel>()
	private val selectedSources = linkedSetOf<String>()
	private val selectedCategoryIds = linkedSetOf<Long>()
	private val selectedTagIds = linkedSetOf<Long>()
	private val sortOrders = ListSortOrder.FAVORITES.sortedByOrdinal()
	private var selectedContent = SmartFolderContent.ANY
	private var selectedDevice = SmartFolderDevice.ANY
	private var selectedSortOrder = ListSortOrder.NEWEST
	private var isPopulated = false

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivitySmartFolderEditBinding.inflate(layoutInflater))
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = true)
		setTitle(if (intent.getLongExtra(org.draken.usagi.core.nav.AppRouter.KEY_ID, SmartFolderEditViewModel.NO_ID) == SmartFolderEditViewModel.NO_ID) R.string.create_smart_folder else R.string.edit_smart_folder)
		initDropdowns()
		viewBinding.buttonDone.setOnClickListener(this)
		viewBinding.buttonSources.setOnClickListener(this)
		viewBinding.buttonCategories.setOnClickListener(this)
		viewBinding.buttonTags.setOnClickListener(this)
		viewBinding.editName.addTextChangedListener(this)
		updateSelectionButtons()

		viewModel.folder.observe(this, ::populateFolder)
		viewModel.onSaved.observeEvent(this) { finishAfterTransition() }
		viewModel.isLoading.observe(this, ::onLoadingChanged)
		viewModel.onError.observeEvent(this) { error ->
			viewBinding.textViewError.text = error.getDisplayMessage(resources)
			viewBinding.textViewError.isVisible = true
		}
	}

	override fun onApplyWindowInsets(
		v: View,
		insets: WindowInsetsCompat,
	): WindowInsetsCompat {
		val bars = insets.systemBarsInsets
		viewBinding.root.setPadding(bars.left, bars.top, bars.right, bars.bottom)
		return insets.consumeAllSystemBarsInsets()
	}

	override fun onClick(v: View) {
		when (v.id) {
			R.id.button_sources -> {
				showSourcesDialog()
			}

			R.id.button_categories -> {
				showCategoriesDialog()
			}

			R.id.button_tags -> {
				showTagsDialog()
			}

			R.id.button_done -> {
				viewModel.save(
					title =
						viewBinding.editName.text
							?.toString()
							.orEmpty(),
					listOrder = selectedSortOrder,
					rules =
						SmartFolderRules(
							sources = selectedSources,
							categoryIds = selectedCategoryIds,
							tagIds = selectedTagIds,
							content = selectedContent,
							device = selectedDevice,
						),
				)
			}
		}
	}

	override fun afterTextChanged(s: Editable?) {
		viewBinding.buttonDone.isEnabled = !s.isNullOrBlank() && !viewModel.isLoading.value
	}

	private fun initDropdowns() {
		val contentLabels =
			listOf(
				getString(R.string.smart_folder_any),
				getString(R.string.smart_folder_sfw),
				getString(R.string.smart_folder_nsfw),
			)
		viewBinding.editContent.setAdapter(ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, contentLabels))
		viewBinding.editContent.setText(contentLabels.first(), false)
		viewBinding.editContent.setOnItemClickListener { _, _, position, _ -> selectedContent = SmartFolderContent.entries[position] }

		val deviceLabels =
			listOf(
				getString(R.string.smart_folder_any),
				getString(R.string.smart_folder_on_device),
				getString(R.string.smart_folder_not_on_device),
			)
		viewBinding.editDevice.setAdapter(ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, deviceLabels))
		viewBinding.editDevice.setText(deviceLabels.first(), false)
		viewBinding.editDevice.setOnItemClickListener { _, _, position, _ -> selectedDevice = SmartFolderDevice.entries[position] }

		val sortLabels = sortOrders.map { order -> getString(order.titleResId) }
		viewBinding.editSort.setAdapter(ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, sortLabels))
		viewBinding.editSort.setText(sortLabels[sortOrders.indexOf(selectedSortOrder)], false)
		viewBinding.editSort.setOnItemClickListener { _, _, position, _ -> selectedSortOrder = sortOrders[position] }
	}

	private fun populateFolder(folder: SmartFolder?) {
		if (folder == null || isPopulated) return
		isPopulated = true
		viewBinding.editName.setText(folder.title)
		selectedSortOrder = folder.listOrder
		viewBinding.editSort.setText(getString(folder.listOrder.titleResId), false)
		val rules =
			when (val result = folder.rules) {
				is SmartFolderRulesResult.Success -> {
					result.rules
				}

				is SmartFolderRulesResult.Error -> {
					viewBinding.textViewError.setText(R.string.smart_folder_invalid_rules)
					viewBinding.textViewError.isVisible = true
					result.rules
				}
			}
		if (rules != null) {
			selectedSources += rules.sources
			selectedCategoryIds += rules.categoryIds
			selectedTagIds += rules.tagIds
			selectedContent = rules.content
			selectedDevice = rules.device
			viewBinding.editContent.setText(contentLabel(selectedContent), false)
			viewBinding.editDevice.setText(deviceLabel(selectedDevice), false)
		}
		updateSelectionButtons()
	}

	private fun showSourcesDialog() {
		val popularSources = viewModel.sources.value
		val sources =
			buildList {
				popularSources.mapTo(this) { source -> source.name to source.getTitle(this@SmartFolderEditActivity) }
				val popularNames = popularSources.mapTo(hashSetOf()) { source -> source.name }
				selectedSources.filterNot(popularNames::contains).mapTo(this) { name -> name to name }
			}
		val pending = selectedSources.toMutableSet()
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.smart_folder_sources)
			.setMultiChoiceItems(
				sources.map { source -> source.second }.toTypedArray(),
				sources.map { source -> source.first in selectedSources }.toBooleanArray(),
			) { _, index, checked ->
				if (checked) pending += sources[index].first else pending -= sources[index].first
			}.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(android.R.string.ok) { _, _ ->
				selectedSources.clear()
				selectedSources += pending
				updateSelectionButtons()
			}.show()
	}

	private fun showCategoriesDialog() {
		val activeCategories = viewModel.categories.value
		val categories =
			buildList {
				activeCategories.mapTo(this) { category -> category.id to category.title }
				val activeIds = activeCategories.mapTo(hashSetOf()) { category -> category.id }
				selectedCategoryIds.filterNot(activeIds::contains).mapTo(this) { id ->
					id to getString(R.string.smart_folder_missing_category, id)
				}
			}
		val pending = selectedCategoryIds.toMutableSet()
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.smart_folder_categories)
			.setMultiChoiceItems(
				categories.map { category -> category.second }.toTypedArray(),
				categories.map { category -> category.first in selectedCategoryIds }.toBooleanArray(),
			) { _, index, checked ->
				if (checked) pending += categories[index].first else pending -= categories[index].first
			}.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(android.R.string.ok) { _, _ ->
				selectedCategoryIds.clear()
				selectedCategoryIds += pending
				updateSelectionButtons()
			}.show()
	}

	private fun showTagsDialog() {
		val popularTags = viewModel.tags.value
		val tags =
			buildList {
				popularTags.mapTo(this) { tag -> tag.toEntity().id to tag.title }
				val popularIds = popularTags.mapTo(hashSetOf()) { tag -> tag.toEntity().id }
				selectedTagIds.filterNot(popularIds::contains).mapTo(this) { id -> id to id.toString() }
			}
		val pending = selectedTagIds.toMutableSet()
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.smart_folder_tags)
			.setMultiChoiceItems(
				tags.map { tag -> tag.second }.toTypedArray(),
				tags.map { tag -> tag.first in selectedTagIds }.toBooleanArray(),
			) { _, index, checked ->
				if (checked) pending += tags[index].first else pending -= tags[index].first
			}.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(android.R.string.ok) { _, _ ->
				selectedTagIds.clear()
				selectedTagIds += pending
				updateSelectionButtons()
			}.show()
	}

	private fun updateSelectionButtons() {
		viewBinding.buttonSources.text = getString(R.string.smart_folder_selected_count, getString(R.string.smart_folder_sources), selectedSources.size)
		viewBinding.buttonCategories.text = getString(R.string.smart_folder_selected_count, getString(R.string.smart_folder_categories), selectedCategoryIds.size)
		viewBinding.buttonTags.text = getString(R.string.smart_folder_selected_count, getString(R.string.smart_folder_tags), selectedTagIds.size)
	}

	private fun onLoadingChanged(isLoading: Boolean) {
		viewBinding.buttonDone.isEnabled = !isLoading && !viewBinding.editName.text.isNullOrBlank()
		viewBinding.buttonSources.isEnabled = !isLoading
		viewBinding.buttonCategories.isEnabled = !isLoading
		viewBinding.buttonTags.isEnabled = !isLoading
		viewBinding.editContent.isEnabled = !isLoading
		viewBinding.editDevice.isEnabled = !isLoading
		viewBinding.editSort.isEnabled = !isLoading
		if (isLoading) viewBinding.textViewError.isVisible = false
	}

	private fun contentLabel(content: SmartFolderContent): String =
		getString(
			when (content) {
				SmartFolderContent.ANY -> R.string.smart_folder_any
				SmartFolderContent.SFW -> R.string.smart_folder_sfw
				SmartFolderContent.NSFW -> R.string.smart_folder_nsfw
			},
		)

	private fun deviceLabel(device: SmartFolderDevice): String =
		getString(
			when (device) {
				SmartFolderDevice.ANY -> R.string.smart_folder_any
				SmartFolderDevice.ON_DEVICE -> R.string.smart_folder_on_device
				SmartFolderDevice.NOT_ON_DEVICE -> R.string.smart_folder_not_on_device
			},
		)
}
