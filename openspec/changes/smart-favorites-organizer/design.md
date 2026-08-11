## Context

Favorites currently uses manual category tabs backed by `FavouriteCategoryEntity` and dynamic SQL in `FavouritesDao`. Reading progress is derived in multiple places with different completion semantics, while tracker state, manga source status, and the local manga index already contain the data needed for automatic organization. The implementation must preserve existing manual categories, paging behavior, cloud-sync DTOs, and the user's `Read later` category.

## Goals / Non-Goals

**Goals:**

- Provide lifecycle stages and stage counts for every Favorites scope without changing favorite membership.
- Add persistent smart folders with source, category, tag, content, and device rule groups.
- Make progress and completion behavior deterministic across reader, history, details, and Favorites.
- Preserve the familiar folder navigation, keep automatic subfolders and organizer controls compact, and make source-backed refresh explicit and bounded.
- Keep local backup/restore backward compatible and avoid new runtime dependencies.

**Non-Goals:**

- Arbitrary nested boolean expressions, per-title overrides, custom lifecycle stages, custom folder icons, rating thresholds, or a unified ordering of manual and smart folders.
- Cloud synchronization of smart folders or changes to the sync-server protocol.
- Migration or renaming of the existing manual `Read later` category.

## Decisions

### Derive organization instead of moving manga

`FavouriteScope` is a query value with `All`, `Category(id)`, and `SmartFolder(id)` variants. `FavouriteStage` is an independent query value. Membership remains in the existing favorites and category tables, so automatic organization cannot duplicate or silently relocate a title.

Global favorite membership uses reserved local category ID `0`, which is excluded from manual-category UI, backup category/favorite sections, and cloud-sync payloads. Adding or recovering any manual membership upserts the reserved row through database triggers, including memberships recovered through cloud sync or local backup. Explicit Remove from favorites deletes it, while deleting a manual folder restores the reserved row after removing that folder's memberships. Migration 29 to 30 introduces and backfills the reserved row from currently active favorites. Migration 30 to 31 repairs installations where a manual membership was recovered after the original backfill but its reserved row was absent, and replaces the recovery trigger with the same insert-or-reactivate behavior used for new memberships. The repair never removes an existing reserved row, so a favorite preserved after deletion of its last manual folder remains in All favorites.

The manga-details favorite selector exposes the reserved membership as a permanent `All favorites` choice followed by optional manual categories. Selecting it inserts or reactivates only the reserved row, so a title can become a favorite when no editable category exists. Clearing a manual category changes only that category membership and cannot implicitly remove the reserved row. Clearing `All favorites` is the explicit Remove from favorites action and removes every active membership for the title. Details observes reserved membership separately from manual-category labels: the heart reflects global membership, while the label shows selected manual categories or `All favorites` when none are selected. Migration 31 to 32 removes the legacy trigger that deactivated reserved membership after the last manual membership was cleared; existing rows are preserved unchanged.

Alternative considered: materialized memberships. It was rejected because tracker, history, and source changes would require error-prone reconciliation and sync behavior.

### Centralize validated progress semantics

A public progress calculator owns percent calculation, clamping, invalid-input handling, and a single completion threshold. Reader and progress-update paths call this calculator. Recalculation persists both percent and chapter count. Mark-as-completed resolves the preferred branch, its final chapter, and that chapter's source before updating history.

Alternative considered: fixing each call site independently. It was rejected because completion drift would recur.

### Use deterministic stage precedence

Stage classification is a pure domain operation over active history, unread tracker count, progress, and manga source status. Precedence is Not started, Reading, Completed, Waiting, then Needs review. Conflicting or unusable terminal metadata is never guessed.

### Persist versioned rule JSON in a local Room table

Smart-folder identity, ordering, timestamps, and soft deletion are relational columns; rule content is a versioned JSON payload. Core v1 supports source IDs, manual category IDs, tag IDs, content mode, and device mode. Different populated groups use AND; values inside a set use OR. Validation requires at least one populated group and rejects corrupted or unsupported payloads explicitly. The optional tag set is backward compatible with existing v1 JSON because missing fields decode to an empty set.

Alternative considered: one join table per rule dimension. It was rejected for Core v1 because it expands migration and DAO surface while the rule vocabulary is expected to evolve. The serialized model remains strongly typed and validated at the repository boundary.

### Treat invalid references as empty, never unrestricted

If a rule references a deleted or missing manual category, its category group evaluates false. Invalid or corrupted rule payloads produce an explicit UI error and no result set. There is no permissive fallback.

### Extend existing paging SQL

`FavouritesDao` continues to own list paging and ordering. Scope and stage clauses are appended to the current query builder so search, order, and remaining quick filters compose with the organizer. Lists and counts use one lifecycle predicate builder to avoid UI/list disagreement. Counter query failures use the existing visible error channel and do not fabricate zero counts.

### Preserve folder tabs and expose automatic subfolders

All, visible manual categories, and smart folders remain in the existing scrollable folder-tab navigation. All is an unconditional, non-hideable system folder and remains available when every editable folder has been deleted; the legacy All-visibility preference no longer controls this navigation. A dedicated adjacent add action creates a smart folder. Long-pressing an editable folder opens its existing management menu, while All exposes management without hide, edit, or delete actions. Lifecycle stages are presented below as automatic subfolder chips for the selected folder; selecting one changes only the derived query and never moves favorite membership. Compact rule-filter and refresh actions remain beside the automatic subfolders. Active rule summaries appear only when persistent rules, transient filters, or validation errors need explanation.

Alternative considered: replacing folder tabs with a single scope selector. It was rejected after Pixel feedback because it hides the user's category structure, adds a dialog to every scope change, and makes the organizer feel like a filter panel instead of a Favorites filing system.

### Remove redundant transient rule dimensions

Each Favorites scope keeps its own transient filter state. For a smart folder, source options are omitted when sources are fixed, tag options are omitted when tags are fixed, both content choices are omitted when content is fixed, and the on-device option is omitted when device state is fixed. Applied options that become unavailable are cleared. Switching to another scope recomputes availability from that scope's persistent rules, so unconstrained dimensions return without leaking filter selections between folders.

### Bind container controls only to the active Favorites page

Per-page stage, count, transient-filter, and organizer-refresh state remains owned by `FavouritesListViewModel` and is projected as one immutable page UI state. `FavouritesListFragment` exposes that state and its page commands through an explicit contract used by the pager container. The container owns exactly one binding job for the currently selected page, cancels it before every rebind, and collects only while both the container view exists and the current page view lifecycle is at least `STARTED`.

When a folder is deleted, the pager selects a valid remaining page and cancels the removed page's binding before its fragment can remain retained. Destroying a page also completes its lifecycle-bound collection, so no container coroutine or lifecycle observer keeps a strong reference to the destroyed fragment. The container does not register permanent observers for every page created by `FragmentStateAdapter`, and a weak collection is not used as a substitute for explicit subscription ownership.

Alternative considered: bind every page's flows to that page's lifecycle. This would prevent the reported leak, but it would retain unnecessary observers for inactive pages and preserve the implicit container-to-fragment coupling. Alternative considered: move all per-page state into `FavouritesContainerViewModel` keyed by `FavouriteScope`. It was rejected because list queries already consume that state in each page view model, creating either duplicated sources of truth or a significantly larger shared-state coordinator.

### Align AndroidX Hilt processing with Kotlin 2 and KSP2

The project uses Kotlin 2.2.10 and KSP2, while AndroidX Hilt compiler 1.2.0 predates the Kotlin 2 processor update. A full KSP pass incorrectly validates the generic `WorkerAssistedFactory<T>` used by each `@HiltWorker`, then leaves the FIR analysis session unusable for subsequent incremental passes. `hilt-compiler` is upgraded to 1.3.0, the first stable line whose processor targets Kotlin 2 and newer KSP2 toolchains. `hilt-work` remains on 1.2.0 because its 1.3.0 release raises the application minimum SDK from 21 to 23; the worker factory runtime API used by generated code is binary-identical between these versions. Dagger, WorkManager, Kotlin, KSP, and the application minimum SDK remain unchanged.

### Refresh only source-dependent terminal candidates

The organizer refresh action targets titles currently classified as Waiting, Completed, or Needs review in the selected scope. These are the stages whose correctness depends on a caught-up percentage and current source metadata. Each title is checked using the existing new-chapter path, then progress is recalculated from the returned chapter set even when the tracker reports no new log entry. Successful source status is persisted before Room re-evaluates counts. Refresh is user initiated, lifecycle-cancelled, bounded, and reports partial failures instead of silently treating them as success.

Alternative considered: automatically refreshing every favorite whenever Favorites opens. It was rejected because a large library can create hundreds of source requests and make screen entry slow or hostile to source servers.

### Cancel source-list image work when rows are recycled

Source list adapters own cleanup of favicon requests in `onViewRecycled`. Source activities and fragments detach RecyclerView adapters and drag helpers before releasing their view bindings. This closes the exact request and detached-view lifecycles shown by LeakCanary without changing the application-wide image loader.

### Require both ZIP and ELF 16 KB compatibility

The Android build uses modern uncompressed native-library packaging and the stable 16 KB-aligned Conscrypt 2.6 release line. Verification checks APK ZIP alignment, every 64-bit `PT_LOAD` alignment, and the Android platform compatibility result after a clean install. The device check is required because Conscrypt 2.5.3 satisfied the load-segment check but Android 17 still rejected its RELRO layout. AVIF is retained because its current binary uses `0x4000` load alignment and passes the same clean-install platform check once the incompatible Conscrypt binary is removed.

### Keep folder types independently ordered

The tab order is All, visible manual categories, then active smart folders. Smart folders have their own list-order column and management screen. This avoids changing the category sync/order contract in v1.

### Add an optional backup section only

The ZIP backup gains `SMART_FOLDERS`. Restore only reads it when present and selected. Old archives therefore remain valid, and ordinary category/favorite/history cloud sync stays byte-compatible.

## Risks / Trade-offs

- [Dynamic SQL becomes more complex] → Keep scope and stage predicate builders isolated and cover list/count parity with Room instrumentation tests.
- [Source status may be stale or conflicting] → Route caught-up titles to Needs review instead of guessing.
- [Rule JSON may outlive its schema] → Include a schema version and fail explicitly on unsupported/corrupted definitions.
- [Counts can add database work] → Calculate all stage counts in one query per scope and preserve paging for title lists.
- [Refresh can trigger many source calls] → Restrict it to source-dependent terminal candidates, cap concurrency, and require a user action.
- [The Conscrypt 2.6 Android artifact no longer supports API 19 or 20] → Usagi's minimum supported API is higher, and the provider is installed only below Android 10; keep targeted TLS and Pixel smoke checks in verification.
- [Preferred branch data can be incomplete] → Return a domain failure without altering history when a final chapter/source cannot be resolved.
- [Future rules need richer presentation] → Keep custom icons and rating thresholds outside Core v1 and evolve the versioned rule payload in a separate change.
- [Dynamic pager pages can outlive their visible selection or be destroyed after folder deletion] → Keep one explicit active-page binding, cancel it on every rebind, and bound collection to the active page view lifecycle.
- [Legacy favorite UI inferred membership from manual categories] → Expose reserved membership as a first-class observable and selector item, and keep manual-category mutations independent.
- [An outdated AndroidX Hilt processor can reject generated worker factories during a clean KSP pass] → Use the Kotlin 2-compatible compiler 1.3 line, retain the API 21-compatible worker runtime, and verify worker generation from clean build outputs.

## Migration Plan

1. Migrate Room 28 to 29 by creating the empty smart-folder table and indexes, then 29 to 30 by adding reserved global membership and backfilling active favorites.
2. Migrate Room 30 to 31 by repairing missing reserved rows for active manual memberships and replacing the recovery trigger with insert-or-reactivate semantics.
3. Migrate Room 31 to 32 by removing the legacy trigger that coupled last-manual-membership removal to global membership.
4. Deploy organizer queries and UI with `All` as the initial stage for every tab.
5. Backups created by v29 include the optional smart-folder section; restore remains tolerant of its absence.
6. Rollback to earlier schemas is not supported by Room; users can restore a pre-upgrade backup. Existing favorite and category rows are preserved during forward migration.

## Open Questions

None for Core v1. Advanced rule composition and cloud synchronization require separate proposals.
