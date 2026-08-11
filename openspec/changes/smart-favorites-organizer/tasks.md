## 1. Progress Foundation

- [x] 1.1 Add public-behavior unit tests for valid, boundary, and invalid progress calculations
- [x] 1.2 Implement the shared progress calculator and completion threshold, then migrate reader and SQL consumers
- [x] 1.3 Add a test for recalculation after chapter-count changes and persist percent plus chapter count atomically
- [x] 1.4 Add a preferred-branch completion test and update MarkAsReadUseCase to resolve the matching final chapter and source

## 2. Lifecycle Domain

- [x] 2.1 Add FavouriteScope and FavouriteStage domain models
- [x] 2.2 Add lifecycle-classifier tests one scenario at a time and implement the classification precedence
- [x] 2.3 Add rule serialization and validation tests one scenario at a time and implement versioned SmartFolderRules

## 3. Persistence and Queries

- [x] 3.1 Add SmartFolderEntity, DAO, repository, and Room migration 28 to 29
- [x] 3.2 Add migration instrumentation coverage that preserves existing data
- [x] 3.3 Extend Favorites queries for All, Category, and SmartFolder scopes with AND/OR rule semantics
- [x] 3.4 Add stage filtering and a single-query stage-count projection using shared predicates
- [x] 3.5 Add Room instrumentation coverage for scopes, rules, invalid references, and reactive counts

## 4. Favorites UI

- [x] 4.1 Combine All, visible categories, and ordered smart folders in the tab model
- [x] 4.2 Add lifecycle chips with counts and per-tab selected-stage state
- [x] 4.3 Connect scope and stage selections to the paged Favorites list
- [x] 4.4 Add smart-folder create, edit, reorder, delete, validation, and explicit corrupt-rule error UI
- [x] 4.5 Remove the redundant Completed Favorites quick filter while retaining other quick filters

## 5. Backup and Restore

- [x] 5.1 Add SmartFolderBackup and the optional SMART_FOLDERS archive section
- [x] 5.2 Restore smart folders with domain validation without changing cloud-sync DTOs
- [x] 5.3 Add new-archive and legacy-archive instrumentation coverage

## 6. Verification

- [ ] 6.1 Run testDebugUnitTest and fix failures
- [ ] 6.2 Run lintDebug and fix errors
- [x] 6.3 Run assembleDebug and fix build errors
- [ ] 6.4 Run connectedDebugAndroidTest on a Pixel and manually verify favorite to reading to waiting to new chapter to completed

## 7. Pixel Feedback

- [x] 7.1 Add tag rule serialization and Room query tests, then extend persistent and transient rule selection with tags
- [x] 7.2 Add an organizer refresh path for Waiting, Completed, and Needs review candidates with progress/status re-evaluation and partial-failure reporting
- [x] 7.3 Replace the scope tabs and permanent quick-filter row with a compact preset/rules header; wrap lifecycle stages on narrow screens
- [ ] 7.4 Dispose source-management and source-catalog favicon requests, detach RecyclerView infrastructure, and verify the reported LeakCanary paths no longer reproduce
- [x] 7.5 Upgrade native packaging and Conscrypt alignment, then verify ZIP, ELF, RELRO, and Pixel platform compatibility
- [ ] 7.6 Rebuild, install the separate debug package on Pixel, and repeat the revised Favorites flow
- [x] 7.7 Restore folder-tab navigation with an adjacent create action, long-press management, and automatic lifecycle subfolders
- [x] 7.8 Preserve soft-deleted folders in backups, share lifecycle SQL predicates between lists and counts, and surface counter failures explicitly
- [x] 7.9 Keep All favorites as a permanent non-hideable folder and cover the zero-editable-folder case
- [x] 7.10 Hide transient filter dimensions already constrained by the selected smart folder and restore them per scope
- [x] 7.11 Separate global favorite membership from editable folders and preserve it when the last manual category is deleted
- [x] 7.12 Repair missing global memberships during migration and upsert them when manual memberships are recovered
- [ ] 7.13 Replace per-fragment Favorites container observers with one lifecycle-bound active-page contract and verify the reported LeakCanary paths no longer reproduce
- [ ] 7.14 Upgrade AndroidX Hilt compiler to the Kotlin 2-compatible 1.3 line, retain the API 21-compatible worker runtime, and verify clean worker generation
- [x] 7.15 Make global membership directly selectable from manga details, keep manual-category removal independent, and cover the zero-folder favorite flow
