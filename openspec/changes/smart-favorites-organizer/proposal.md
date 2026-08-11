## Why

Large Favorites libraries are difficult to maintain manually, and the current reading-progress calculations can classify nearly completed or branch-based manga inconsistently. Users need an organizer that derives useful lifecycle views from existing history, tracker, source, and local-library data without moving or duplicating manga.

## What Changes

- Add lifecycle stages to every Favorites scope: All, Not started, Reading, Waiting, Completed, and Needs review.
- Add configurable smart folders based on sources, manual categories, content rating, and on-device state.
- Unify progress calculation and completion semantics, persist updated chapter counts, and complete manga on the preferred branch and matching source.
- Add stage-aware Favorites queries and counters while retaining existing manual categories and quick filters except the redundant Favorites Completed filter.
- Add smart-folder creation, editing, deletion, ordering, explicit invalid-rule errors, and per-tab stage selection.
- Restore the familiar Favorites folder tabs with a permanent All folder followed by manual categories and smart folders, add an adjacent create action and long-press management, and present lifecycle stages as automatic subfolders with compact rule and refresh actions.
- Preserve global favorite membership independently of editable folders so deleting a manual category cannot empty All favorites.
- Make global membership directly selectable from manga details so titles can be added when no editable folders exist and the favorite indicator does not depend on manual-category membership.
- Extend smart-folder and transient rule selection with manga tags while preserving the existing source, category, content, device, and new-chapter filters; hide transient filter dimensions already fixed by the selected folder.
- Refresh caught-up candidates against their sources so stale progress and source status cannot leave readable titles in Waiting, Completed, or Needs review.
- Dispose source-management and source-catalog image requests with recycled rows and ship 16 KB-compatible native libraries on modern Android devices.
- Bind Favorites container controls to only the active pager page through an explicit page contract so deleting folders or switching pages cannot retain destroyed list fragments.
- Align the AndroidX Hilt compiler with the existing Kotlin 2 and KSP2 toolchain while retaining the API 21-compatible Hilt worker runtime.
- Include smart folders in local backup and restore while leaving cloud synchronization unchanged.

## Capabilities

### New Capabilities

- `reading-progress`: Validated, consistent progress calculation and preferred-branch completion behavior.
- `favorites-lifecycle`: Automatic lifecycle classification, stage filtering, and counts for Favorites scopes.
- `favorites-smart-folders`: Persistent rule-based Favorites scopes and their management UI.
- `smart-folder-backup`: Backward-compatible local backup and restore of smart-folder definitions.
- `android-runtime-quality`: Lifecycle-safe source-catalog image loading and 16 KB native-library compatibility.

### Modified Capabilities

None. This repository has no existing OpenSpec capability specifications.

## Impact

- Room database advances from version 28 to 32 with a new local smart-folder table, reserved global favorite membership, repair of missing global memberships, independent manual-membership removal, and migration coverage.
- Favorites domain, DAO, repository, view models, fragments, adapters, layouts, strings, tag rules, refresh behavior, and quick-filter presentation are extended.
- Reader, history, and details progress paths share one validated calculator and completion threshold.
- Local backup archives gain an optional smart-folders section; existing category, favorite, history, and cloud-sync formats remain unchanged.
- Existing Conscrypt packaging is upgraded to a 16 KB-aligned build; no new runtime dependency is introduced.
- AndroidX Hilt compiler advances from 1.2.0 to 1.3.0 for Kotlin 2 and KSP2 compatibility, while `hilt-work` remains on the API 21-compatible 1.2.0 runtime.
