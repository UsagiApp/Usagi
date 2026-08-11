# Manga Details Global Favorite Membership Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore reliable favorite add, display, and removal behavior after global membership was separated from editable categories.

**Architecture:** Reserved category ID `0` remains the single source of truth for global favorite membership. The favorite selector presents a dedicated system item for that membership and independent manual-category items; details combines the global membership flow with manual category labels instead of inferring one from the other.

**Tech Stack:** Kotlin 2.2.10, Room, Kotlin Flow, AndroidX ViewModel, JUnit, Android instrumentation tests.

## Global Constraints

- Keep reserved membership local-only and excluded from category backup and cloud-sync DTOs.
- Do not use fallback queries or fabricate membership from manual categories.
- Preserve API 21 compatibility and migrate existing schema 31 databases to schema 32 without rewriting favorite rows.
- Keep all code, tests, and documentation in English.

---

### Task 1: Persistence Contract

**Files:**
- Modify: `app/src/main/kotlin/org/draken/usagi/favourites/data/AllFavoritesInfrastructure.kt`
- Modify: `app/src/main/kotlin/org/draken/usagi/favourites/data/FavouritesDao.kt`
- Modify: `app/src/main/kotlin/org/draken/usagi/favourites/domain/FavouritesRepository.kt`
- Create: `app/src/main/kotlin/org/draken/usagi/core/db/migrations/Migration31To32.kt`
- Modify: `app/src/main/kotlin/org/draken/usagi/core/db/MangaDatabase.kt`
- Test: `app/src/androidTest/kotlin/org/draken/usagi/favourites/data/FavouritesOrganizerDaoTest.kt`
- Test: `app/src/androidTest/kotlin/org/draken/usagi/core/db/MangaDatabaseTest.kt`

**Interfaces:**
- Produces: `observeIsFavorite(mangaId: Long): Flow<Boolean>`.
- Produces: `addToFavourites(mangas: Collection<Manga>)`.
- Preserves: `removeFromFavourites(ids: Collection<Long>): ReversibleHandle` as explicit global removal.

- [x] **Step 1: Write failing Room tests**

Add tests proving a reserved membership can be inserted without a manual category, clearing the last manual membership leaves the reserved row active, and explicit favorite removal clears all memberships.

- [x] **Step 2: Run the focused instrumentation tests and verify RED**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.draken.usagi.favourites.data.FavouritesOrganizerDaoTest`

Expected: FAIL because direct global membership and independent manual removal are not implemented.

- [x] **Step 3: Implement the persistence API**

Add the reserved-membership observable and direct upsert operation. Remove the database trigger that implicitly deletes reserved membership after the last manual membership is deactivated, and migrate existing schema 31 databases by dropping that trigger. Keep explicit removal transactional across reserved and manual rows.

- [x] **Step 4: Run the focused instrumentation tests and verify GREEN**

Run the command from Step 2 and expect PASS.

### Task 2: Selector and Details State

**Files:**
- Modify: `app/src/main/kotlin/org/draken/usagi/favourites/ui/categories/select/FavoriteDialogViewModel.kt`
- Modify: `app/src/main/kotlin/org/draken/usagi/favourites/ui/categories/select/model/MangaCategoryItem.kt`
- Modify: `app/src/main/kotlin/org/draken/usagi/favourites/ui/categories/select/adapter/MangaCategoryAD.kt`
- Modify: `app/src/main/kotlin/org/draken/usagi/details/domain/DetailsInteractor.kt`
- Modify: `app/src/main/kotlin/org/draken/usagi/details/ui/DetailsViewModel.kt`
- Modify: `app/src/main/kotlin/org/draken/usagi/details/ui/DetailsActivity.kt`
- Modify: `app/src/main/kotlin/org/draken/usagi/details/ui/DetailsClassicActivity.kt`
- Test: `app/src/test/kotlin/org/draken/usagi/favourites/ui/categories/select/FavoriteSelectionStateTest.kt`

**Interfaces:**
- Consumes: `observeIsFavorite`, `addToFavourites`, and `removeFromFavourites` from Task 1.
- Produces: an immutable favorite-details state with global membership and manual-category labels.

- [x] **Step 1: Write failing selector-state unit tests**

Cover an empty manual-category list with an unchecked All favorites item, active global membership without manual categories, independent manual uncheck behavior, and explicit global uncheck behavior.

- [x] **Step 2: Run the focused unit tests and verify RED**

Run: `./gradlew :app:testDebugUnitTest --tests 'org.draken.usagi.favourites.ui.categories.select.*'`

Expected: FAIL because global membership is not represented in selector or details state.

- [x] **Step 3: Implement selector and details projection**

Always prepend the All favorites item, route its checkbox to direct global add or explicit removal, and keep manual items on category operations. Render the heart from global membership and use manual category names only as optional labels.

- [x] **Step 4: Run the focused unit tests and verify GREEN**

Run the command from Step 2 and expect PASS.

### Task 3: Build and Pixel Regression

**Files:**
- Modify: `openspec/changes/smart-favorites-organizer/tasks.md`

**Interfaces:**
- Consumes: completed persistence and UI behavior from Tasks 1 and 2.

- [x] **Step 1: Run focused tests and assemble the debug APK**

Run focused unit and instrumentation coverage, then `./gradlew :app:assembleDebug` with JDK 21.

- [x] **Step 2: Install without clearing Pixel data**

Install `app/build/outputs/apk/debug/app-debug.apk` with `adb install -r`.

- [x] **Step 3: Verify the zero-folder flow**

Open a non-favorite title, select All favorites, confirm the heart fills and the title appears in All favorites. Remove and restore a manual category membership, confirm global membership does not change, then clear All favorites and confirm the title and manual memberships are removed.

- [x] **Step 4: Validate OpenSpec and complete task 7.15**

Run `openspec validate smart-favorites-organizer --strict` and mark task 7.15 complete only after automated and Pixel verification pass.

## Verification Evidence

- Pixel 10 retained its existing database across `adb install -r`, opened Favorites after migration 31 to 32, and preserved 418 global favorites.
- Removing the title from `Read later` left `All favorites` checked and changed the details label to `All favorites`.
- Clearing `All favorites` cleared both selector rows and changed the details action to `Favorite this`; selecting it again restored the global membership, details label, count, and visible title.
- `FavouritesOrganizerDaoTest` completed 10 of 10 tests on Pixel, and the focused migration 31 to 32 test completed 1 of 1.
- `FavoriteSelectionStateTest` and `assembleDebug` completed in a fresh `--rerun-tasks` Gradle invocation.
- Focused Room, SQLite, AndroidRuntime, and LeakCanary logcat checks were empty after the smoke flow; unrelated cover-image requests still reported remote timeouts and HTTP 404 responses.
