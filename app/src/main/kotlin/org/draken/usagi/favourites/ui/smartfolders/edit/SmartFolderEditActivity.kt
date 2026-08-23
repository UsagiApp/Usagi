package org.draken.usagi.favourites.ui.smartfolders.edit

import android.os.Bundle
import android.text.Editable
import android.view.View
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import org.draken.usagi.R
import org.draken.usagi.core.db.entity.toEntity
import org.draken.usagi.core.model.getTitle
import org.draken.usagi.core.nav.AppRouter
import org.draken.usagi.core.ui.BaseActivity
import org.draken.usagi.core.ui.dialog.SearchableSelectionDialog
import org.draken.usagi.core.ui.dialog.SearchableSelectionItem
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
	private var isLoading = false
	private var hasStoredRulesError = false

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivitySmartFolderEditBinding.inflate(layoutInflater))
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = true)
		setTitle(
			if (intent.getLongExtra(AppRouter.KEY_ID, SmartFolderEditViewModel.NO_ID) == SmartFolderEditViewModel.NO_ID) {
				R.string.create_smart_folder
			} else {
				R.string.edit_smart_folder
			},
		)
		viewBinding.buttonDone.setOnClickListener(this)
		viewBinding.rowSources.setOnClickListener(this)
		viewBinding.rowCategories.setOnClickListener(this)
		viewBinding.rowTags.setOnClickListener(this)
		viewBinding.rowContent.setOnClickListener(this)
		viewBinding.rowDevice.setOnClickListener(this)
		viewBinding.rowSort.setOnClickListener(this)
		viewBinding.editName.addTextChangedListener(this)
		updateDraftUi()

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
			R.id.row_sources -> {
				showSourcesDialog()
			}

			R.id.row_categories -> {
				showCategoriesDialog()
			}

			R.id.row_tags -> {
				showTagsDialog()
			}

			R.id.row_content -> {
				showContentDialog()
			}

			R.id.row_device -> {
				showDeviceDialog()
			}

			R.id.row_sort -> {
				showSortDialog()
			}

			R.id.button_done -> {
				viewModel.save(
					title =
						viewBinding.editName.text
							?.toString()
							.orEmpty()
							.trim(),
					listOrder = selectedSortOrder,
					rules = draftRules(),
				)
			}
		}
	}

	override fun afterTextChanged(s: Editable?) = updateDraftUi()

	private fun populateFolder(folder: SmartFolder?) {
		if (folder == null || isPopulated) return
		isPopulated = true
		viewBinding.editName.setText(folder.title)
		selectedSortOrder = folder.listOrder
		val rules =
			when (val result = folder.rules) {
				is SmartFolderRulesResult.Success -> {
					result.rules
				}

				is SmartFolderRulesResult.Error -> {
					hasStoredRulesError = true
					result.rules
				}
			}
		if (rules != null) {
			selectedSources += rules.sources
			selectedCategoryIds += rules.categoryIds
			selectedTagIds += rules.tagIds
			selectedContent = rules.content
			selectedDevice = rules.device
		}
		updateDraftUi()
	}

	private fun showSourcesDialog() {
		val popularSources = viewModel.sources.value
		val items =
			buildList {
				popularSources.mapTo(this) { source ->
					SearchableSelectionItem(source.name, source.getTitle(this@SmartFolderEditActivity))
				}
				val knownNames = popularSources.mapTo(hashSetOf()) { source -> source.name }
				selectedSources.filterNot(knownNames::contains).mapTo(this) { name ->
					SearchableSelectionItem(name, name)
				}
			}
		SearchableSelectionDialog.show(this, R.string.smart_folder_sources, items, selectedSources) { selected ->
			selectedSources.clear()
			selectedSources += selected
			onRulesEdited()
		}
	}

	private fun showCategoriesDialog() {
		val categories = viewModel.categories.value
		val items =
			buildList {
				categories.mapTo(this) { category -> SearchableSelectionItem(category.id, category.title) }
				val knownIds = categories.mapTo(hashSetOf()) { category -> category.id }
				selectedCategoryIds.filterNot(knownIds::contains).mapTo(this) { id ->
					SearchableSelectionItem(id, getString(R.string.smart_folder_missing_category, id))
				}
			}
		SearchableSelectionDialog.show(this, R.string.categories, items, selectedCategoryIds) { selected ->
			selectedCategoryIds.clear()
			selectedCategoryIds += selected
			onRulesEdited()
		}
	}

	private fun showTagsDialog() {
		val tags = viewModel.tags.value
		val items =
			buildList {
				tags.mapTo(this) { tag -> SearchableSelectionItem(tag.toEntity().id, tag.title) }
				val knownIds = tags.mapTo(hashSetOf()) { tag -> tag.toEntity().id }
				selectedTagIds.filterNot(knownIds::contains).mapTo(this) { id ->
					SearchableSelectionItem(id, id.toString())
				}
			}
		SearchableSelectionDialog.show(this, R.string.genres, items, selectedTagIds) { selected ->
			selectedTagIds.clear()
			selectedTagIds += selected
			onRulesEdited()
		}
	}

	private fun showContentDialog() {
		val labels = listOf(getString(R.string.any), getString(R.string.sfw), getString(R.string.nsfw))
		showSingleChoiceDialog(R.string.smart_folder_content, labels, selectedContent.ordinal) { index ->
			selectedContent = SmartFolderContent.entries[index]
			onRulesEdited()
		}
	}

	private fun showDeviceDialog() {
		val labels = listOf(getString(R.string.any), getString(R.string.on_device), getString(R.string.smart_folder_not_on_device))
		showSingleChoiceDialog(R.string.smart_folder_device, labels, selectedDevice.ordinal) { index ->
			selectedDevice = SmartFolderDevice.entries[index]
			onRulesEdited()
		}
	}

	private fun showSortDialog() {
		showSingleChoiceDialog(
			titleResId = R.string.sort_order,
			labels = sortOrders.map { order -> getString(order.titleResId) },
			selectedIndex = sortOrders.indexOf(selectedSortOrder),
		) { index ->
			selectedSortOrder = sortOrders[index]
			updateDraftUi()
		}
	}

	private fun showSingleChoiceDialog(
		@StringRes titleResId: Int,
		labels: List<String>,
		selectedIndex: Int,
		onSelected: (Int) -> Unit,
	) {
		MaterialAlertDialogBuilder(this)
			.setTitle(titleResId)
			.setSingleChoiceItems(labels.toTypedArray(), selectedIndex) { dialog, index ->
				onSelected(index)
				dialog.dismiss()
			}.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	private fun onRulesEdited() {
		hasStoredRulesError = false
		updateDraftUi()
	}

	private fun updateDraftUi() {
		val rules = draftRules()
		viewBinding.buttonDone.isEnabled =
			!isLoading &&
			SmartFolderDraftValidator.canSave(
				viewBinding.editName.text
					?.toString()
					.orEmpty(),
				rules,
			)
		viewBinding.rowSources.subtitle = selectionSubtitle(selectedSources.size)
		viewBinding.rowCategories.subtitle = selectionSubtitle(selectedCategoryIds.size)
		viewBinding.rowTags.subtitle = selectionSubtitle(selectedTagIds.size)
		viewBinding.rowContent.subtitle = contentLabel(selectedContent)
		viewBinding.rowDevice.subtitle = deviceLabel(selectedDevice)
		viewBinding.rowSort.subtitle = getString(selectedSortOrder.titleResId)
		viewBinding.textViewError.isVisible = hasStoredRulesError
		viewBinding.textViewError.setText(R.string.smart_folder_invalid_rules)
	}

	private fun selectionSubtitle(count: Int): String =
		if (count == 0) {
			getString(R.string.any)
		} else {
			resources.getQuantityString(R.plurals.items, count, count)
		}

	private fun draftRules() =
		SmartFolderRules(
			sources = selectedSources,
			categoryIds = selectedCategoryIds,
			tagIds = selectedTagIds,
			content = selectedContent,
			device = selectedDevice,
		)

	private fun onLoadingChanged(loading: Boolean) {
		isLoading = loading
		viewBinding.rowSources.isEnabled = !loading
		viewBinding.rowCategories.isEnabled = !loading
		viewBinding.rowTags.isEnabled = !loading
		viewBinding.rowContent.isEnabled = !loading
		viewBinding.rowDevice.isEnabled = !loading
		viewBinding.rowSort.isEnabled = !loading
		if (loading) viewBinding.textViewError.isVisible = false
		updateDraftUi()
	}

	private fun contentLabel(content: SmartFolderContent): String =
		getString(
			when (content) {
				SmartFolderContent.ANY -> R.string.any
				SmartFolderContent.SFW -> R.string.sfw
				SmartFolderContent.NSFW -> R.string.nsfw
			},
		)

	private fun deviceLabel(device: SmartFolderDevice): String =
		getString(
			when (device) {
				SmartFolderDevice.ANY -> R.string.any
				SmartFolderDevice.ON_DEVICE -> R.string.on_device
				SmartFolderDevice.NOT_ON_DEVICE -> R.string.smart_folder_not_on_device
			},
		)
}
