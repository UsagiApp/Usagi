# Favorites Active Page Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace permanent per-fragment Favorites observers with one explicit, lifecycle-bound binding for the currently selected pager page.

**Architecture:** `FavouritesListViewModel` projects all container-facing page state into one immutable `FavouritesPageUiState`. `FavouritesListFragment` implements a narrow `FavouritesPage` contract, while `FavouritesPageBinding` owns exactly one collection job and cancels it on page replacement, page-view destruction, or container-view destruction.

**Tech Stack:** Kotlin, AndroidX Fragment and Lifecycle, Kotlin Coroutines and StateFlow, ViewPager2, JUnit 4, kotlinx-coroutines-test, LeakCanary.

## Global Constraints

- Keep per-page state in `FavouritesListViewModel`; do not duplicate it in `FavouritesContainerViewModel`.
- Bind only the currently selected pager page and collect only while its view lifecycle is at least `Lifecycle.State.STARTED`.
- Do not use weak collections or fallback behavior as lifecycle ownership mechanisms.
- Preserve existing Favorites tab, stage, transient-filter, organizer-refresh, selection, and list behavior.
- Add no runtime dependency and keep all code and documentation in English.
- Use AndroidX Hilt compiler 1.3.0 with the existing Kotlin 2.2.10 and KSP2 toolchain, retain `hilt-work` 1.2.0 and minSdk 21, and do not change Dagger, WorkManager, Kotlin, or KSP versions.
- Keep the existing dirty feature work intact and do not commit unless the user requests it.

---

## File Structure

- Create `app/src/main/kotlin/org/draken/usagi/favourites/ui/FavouritesPage.kt`: immutable container-facing page state and the explicit page command/state contract.
- Create `app/src/main/kotlin/org/draken/usagi/favourites/ui/container/FavouritesPageBinding.kt`: single-job lifecycle binding for the active page.
- Create `app/src/test/kotlin/org/draken/usagi/favourites/ui/container/FavouritesPageBindingTest.kt`: cancellation and lifecycle regression coverage.
- Modify `app/src/main/kotlin/org/draken/usagi/favourites/ui/list/FavouritesListViewModel.kt`: combine existing page flows into one `StateFlow<FavouritesPageUiState>`.
- Modify `app/src/main/kotlin/org/draken/usagi/favourites/ui/list/FavouritesListFragment.kt`: implement `FavouritesPage` instead of exposing unrelated flows individually.
- Modify `app/src/main/kotlin/org/draken/usagi/favourites/ui/container/FavouritesContainerFragment.kt`: own one `FavouritesPageBinding`, remove the weak observer set, and render from immutable state.
- Modify `openspec/changes/smart-favorites-organizer/tasks.md`: mark task 7.13 complete only after automated and Pixel verification.

### Task 1: Specify active-page replacement with a failing test

**Files:**
- Create: `app/src/test/kotlin/org/draken/usagi/favourites/ui/container/FavouritesPageBindingTest.kt`

**Interfaces:**
- Consumes: the wished-for `FavouritesPage`, `FavouritesPageUiState`, and `FavouritesPageBinding` APIs, which intentionally do not exist before the RED run.
- Produces: behavioral coverage proving that a replaced or destroyed page cannot update container state.

- [ ] **Step 1: Write the replacement-cancellation test before production code**

Use `runTest`, `StandardTestDispatcher`, `Dispatchers.setMain`, two fake pages backed by `MutableStateFlow`, and lifecycle owners backed by `LifecycleRegistry.createUnsafe`. Start both owners, bind page A, then bind page B. Emit from both pages and assert that only page B can append a new rendered state after replacement.

```kotlin
@Test
fun `binding a new page stops updates from the previous page`() = runTest {
	val renderedStages = mutableListOf<FavouriteStage>()
	val ownerA = StartedLifecycleOwner()
	val ownerB = StartedLifecycleOwner()
	val pageA = FakeFavouritesPage(FavouriteStage.ALL)
	val pageB = FakeFavouritesPage(FavouriteStage.READING)
	val binding = FavouritesPageBinding(this, { renderedStages += it.selectedStage }, {})

	binding.bind(pageA, ownerA)
	advanceUntilIdle()
	binding.bind(pageB, ownerB)
	advanceUntilIdle()
	pageA.emitStage(FavouriteStage.COMPLETED)
	pageB.emitStage(FavouriteStage.WAITING)
	advanceUntilIdle()

	assertEquals(FavouriteStage.WAITING, renderedStages.last())
	assertFalse(FavouriteStage.COMPLETED in renderedStages)
}
```

- [ ] **Step 2: Add the destroyed-page case to the same wished-for API**

Bind one started fake page, move its lifecycle through `ON_STOP` and `ON_DESTROY`, emit another state, advance the test scheduler, and assert that no value emitted after destruction reaches `render`. Also call `clear()` twice to specify idempotent container teardown.

- [ ] **Step 3: Run the focused test and confirm RED**

Run:

```bash
env JAVA_HOME='/opt/homebrew/Cellar/openjdk@21/21.0.12/libexec/openjdk.jdk/Contents/Home' ./gradlew :app:testDebugUnitTest --tests 'org.draken.usagi.favourites.ui.container.FavouritesPageBindingTest'
```

Expected: FAIL because `FavouritesPageBinding` does not exist.

### Task 2: Implement the page contract, immutable state, and lifecycle binding

**Files:**
- Create: `app/src/main/kotlin/org/draken/usagi/favourites/ui/FavouritesPage.kt`
- Create: `app/src/main/kotlin/org/draken/usagi/favourites/ui/container/FavouritesPageBinding.kt`
- Modify: `app/src/main/kotlin/org/draken/usagi/favourites/ui/list/FavouritesListViewModel.kt`
- Modify: `app/src/main/kotlin/org/draken/usagi/favourites/ui/list/FavouritesListFragment.kt`

**Interfaces:**
- Consumes: existing `FavouriteStage`, `FavouriteStageCounts`, `ListFilterOption`, `FavouriteOrganizerRefreshResult`, and `Event` types.
- Produces: `FavouritesPageUiState`, `FavouritesPage`, `FavouritesPageBinding.bind(page, lifecycleOwner)`, and `FavouritesPageBinding.clear()`.

- [ ] **Step 1: Add the explicit page contract required by the failing test**

Create `FavouritesPage.kt` with the following shape inside the Favorites UI module:

```kotlin
data class FavouritesPageUiState(
	val selectedStage: FavouriteStage,
	val stageCounts: FavouriteStageCounts?,
	val availableRuleOptions: List<ListFilterOption>,
	val selectedRuleOptions: Set<ListFilterOption>,
	val isOrganizerRefreshing: Boolean,
)

interface FavouritesPage {
	val uiState: StateFlow<FavouritesPageUiState>
	val organizerRefreshResults: Flow<Event<FavouriteOrganizerRefreshResult>?>

	fun setStage(stage: FavouriteStage)
	fun setRuleOption(option: ListFilterOption, isApplied: Boolean)
	fun clearRuleOptions()
	fun refreshOrganizer()
}
```

- [ ] **Step 2: Implement the lifecycle binding required by the failing test**

Create a focused binding owner that cancels its previous job before launching the replacement and uses the page view lifecycle as the inner collection boundary:

```kotlin
internal class FavouritesPageBinding(
	private val scope: CoroutineScope,
	private val render: (FavouritesPageUiState) -> Unit,
	private val showRefreshResult: suspend (FavouriteOrganizerRefreshResult) -> Unit,
) {
	private var job: Job? = null

	fun bind(page: FavouritesPage, lifecycleOwner: LifecycleOwner) {
		clear()
		job =
			scope.launch {
				lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
					launch { page.uiState.collect(render) }
					launch {
						page.organizerRefreshResults.collect { event ->
							event?.consume { result -> showRefreshResult(result) }
						}
					}
				}
			}
	}

	fun clear() {
		job?.cancel()
		job = null
	}
}
```

- [ ] **Step 3: Project existing view-model state into one StateFlow**

In `FavouritesListViewModel`, combine the existing state sources without adding another mutable source of truth:

```kotlin
val pageUiState =
	combine(
		selectedStage,
		stageCounts,
		availableRuleOptions,
		selectedRuleOptions,
		isOrganizerRefreshing,
	) { selectedStage, stageCounts, availableRuleOptions, selectedRuleOptions, isRefreshing ->
		FavouritesPageUiState(
			selectedStage = selectedStage,
			stageCounts = stageCounts,
			availableRuleOptions = availableRuleOptions,
			selectedRuleOptions = selectedRuleOptions,
			isOrganizerRefreshing = isRefreshing,
		)
	}.stateIn(
		viewModelScope + Dispatchers.Default,
		SharingStarted.Eagerly,
		FavouritesPageUiState(
			selectedStage = selectedStage.value,
			stageCounts = stageCounts.value,
			availableRuleOptions = availableRuleOptions.value,
			selectedRuleOptions = selectedRuleOptions.value,
			isOrganizerRefreshing = isOrganizerRefreshing.value,
		),
	)
```

- [ ] **Step 4: Implement the contract in the page fragment**

Make `FavouritesListFragment` implement `FavouritesPage`. Expose `viewModel.pageUiState` and `viewModel.onOrganizerRefreshed`, retain the existing command delegation, and remove the five individual state properties that the container no longer needs.

- [ ] **Step 5: Run the focused tests and confirm GREEN**

Run the focused command from Task 1 Step 3.

Expected: both replacement and destroyed-page tests PASS.

### Task 3: Migrate the Favorites container to the active-page binding

**Files:**
- Modify: `app/src/main/kotlin/org/draken/usagi/favourites/ui/container/FavouritesContainerFragment.kt`

**Interfaces:**
- Consumes: `FavouritesPageBinding`, `FavouritesPage`, and `FavouritesPageUiState` from Tasks 1 and 2.
- Produces: one binding for the current ViewPager page and state-only rendering methods with no captured child fragment.

- [ ] **Step 1: Replace per-fragment observer storage**

Remove `observedFragments`, `Collections`, and `WeakHashMap`. Add a nullable `FavouritesPageBinding` field, initialize it with `viewLifecycleOwner.lifecycleScope`, `::renderCurrentPage`, and `::showRefreshResult` after view creation, and call `clear()` before dropping it in `onDestroyView`.

- [ ] **Step 2: Bind only the current page**

Update `bindCurrentPage()` to resolve the current fragment once, cast it to `FavouritesPage`, and replace the active binding:

```kotlin
private fun bindCurrentPage() {
	val fragment = findCurrentFragment() as? FavouritesListFragment ?: return
	pageBinding?.bind(fragment, fragment.viewLifecycleOwner)
}
```

Every page-selection callback and tab-list update may call this method; replacement is safe because `bind()` cancels the previous job first.

- [ ] **Step 3: Render from immutable state**

Change `renderStages` and `renderOrganizerHeader` to accept `FavouritesPageUiState`, and add one `renderCurrentPage(state)` method that invokes both. Do not read flows or capture a `FavouritesListFragment` inside a rendering callback.

- [ ] **Step 4: Route user commands through the page contract**

Add `findCurrentPage(): FavouritesPage?` and use it for stage, rule, and refresh commands. Keep `findCurrentFragment()` only where the fragment instance is required for `RecyclerViewOwner`, category compatibility, or the view lifecycle owner.

- [ ] **Step 5: Run focused and existing Favorites unit tests**

Run:

```bash
env JAVA_HOME='/opt/homebrew/Cellar/openjdk@21/21.0.12/libexec/openjdk.jdk/Contents/Home' ./gradlew :app:testDebugUnitTest --tests 'org.draken.usagi.favourites.ui.container.*'
```

Expected: `FavouritesPageBindingTest` and `FavouritesContainerViewModelTest` PASS.

- [ ] **Step 6: Build the debug APK**

Run:

```bash
env JAVA_HOME='/opt/homebrew/Cellar/openjdk@21/21.0.12/libexec/openjdk.jdk/Contents/Home' ./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL with the APK at `app/build/outputs/apk/debug/app-debug.apk`.

### Task 4: Align AndroidX Hilt with the existing Kotlin 2 toolchain

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `openspec/changes/smart-favorites-organizer/design.md`
- Modify: `openspec/changes/smart-favorites-organizer/specs/android-runtime-quality/spec.md`
- Modify: `openspec/changes/smart-favorites-organizer/tasks.md`

**Interfaces:**
- Consumes: Kotlin 2.2.10, KSP 2.2.10-2.0.2, Dagger 2.57.2, and the four existing `@HiltWorker` implementations.
- Produces: clean AndroidX Hilt worker processing with `hilt-compiler` 1.3.0 and the API 21-compatible `hilt-work` 1.2.0 runtime.

- [ ] **Step 1: Split the AndroidX Hilt compiler and runtime versions**

Set `hiltCompiler = "1.3.0"` for `androidx.hilt:hilt-compiler` and `hiltWork = "1.2.0"` for `androidx.hilt:hilt-work` in the version catalog. Do not change minSdk or the Dagger, WorkManager, Kotlin, or KSP coordinates.

- [ ] **Step 2: Clean application build outputs and run KSP**

Run:

```bash
env JAVA_HOME='/opt/homebrew/Cellar/openjdk@21/21.0.12/libexec/openjdk.jdk/Contents/Home' ./gradlew :app:clean
env JAVA_HOME='/opt/homebrew/Cellar/openjdk@21/21.0.12/libexec/openjdk.jdk/Contents/Home' ./gradlew :app:kspDebugKotlin
```

Expected: KSP generates all four worker assisted factories without `Invalid return type: T`, FIR phase errors, or a minSdk manifest conflict. Run clean separately because this project enables parallel Gradle execution and build tasks depend on Spotless.

- [ ] **Step 3: Run the Favorites tests and assemble the APK**

Run the focused test and assemble commands from Task 3. Both commands must complete from the new processor outputs before task 7.14 is marked complete.

### Task 5: Verify the reported deletion lifecycle on Pixel

**Files:**
- Modify after verification: `openspec/changes/smart-favorites-organizer/tasks.md`

**Interfaces:**
- Consumes: debug APK and Pixel serial `59051FDCR006LP`.
- Produces: runtime evidence that the reported `FavouritesContainerFragment$bindCurrentPage` retention path no longer reproduces.

- [ ] **Step 1: Install the debug APK without clearing application data**

Run:

```bash
/Users/dmitrii/Library/Android/sdk/platform-tools/adb -s 59051FDCR006LP install -r app/build/outputs/apk/debug/app-debug.apk
```

Expected: `Success`; existing Favorites data remains available.

- [ ] **Step 2: Reproduce the exact user flow**

Open Favorites, delete folders, then navigate to All favorites. Repeat with at least two folder create/delete cycles and several transitions between All favorites and another remaining folder.

- [ ] **Step 3: Check runtime errors and LeakCanary**

Confirm All favorites still displays its titles, inspect `AndroidRuntime`, `Room`, and `SQLiteLog`, then allow LeakCanary enough time to analyze destroyed pages. The two reported paths through `FavouritesContainerFragment$bindCurrentPage` and destroyed `FavouritesListFragment` instances must not reappear.

- [ ] **Step 4: Synchronize OpenSpec completion**

Mark task 7.13 complete only after the focused tests, debug build, Pixel flow, and LeakCanary check pass. Run:

```bash
openspec validate smart-favorites-organizer --strict
git diff --check
```

Expected: OpenSpec reports the change as valid and `git diff --check` exits successfully.
