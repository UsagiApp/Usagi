## Context

Favorites currently uses manual category tabs backed by `FavouriteCategoryEntity` and dynamic SQL in `FavouritesDao`. Reading progress is derived in multiple places with different completion semantics, while tracker state, manga source status, and the local manga index already contain the data needed for automatic organization. The implementation must preserve existing manual categories, paging behavior, cloud-sync DTOs, and the user's `Read later` category.

## Goals / Non-Goals

**Goals:**

- Provide lifecycle stages and stage counts for every Favorites scope without changing favorite membership.
- Add persistent smart folders with source, category, tag, content, and device rule groups.
- Make progress and completion behavior deterministic across reader, history, details, and Favorites.
- Keep organizer controls compact and make source-backed refresh explicit and bounded.
- Keep local backup/restore backward compatible and avoid new runtime dependencies.

**Non-Goals:**

- Arbitrary nested boolean expressions, per-title overrides, custom lifecycle stages, or a unified ordering of manual and smart folders.
- Cloud synchronization of smart folders or changes to the sync-server protocol.
- Migration or renaming of the existing manual `Read later` category.

## Decisions

### Derive organization instead of moving manga

`FavouriteScope` is a query value with `All`, `Category(id)`, and `SmartFolder(id)` variants. `FavouriteStage` is an independent query value. Membership remains in the existing favorites and category tables, so automatic organization cannot duplicate or silently relocate a title.

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

`FavouritesDao` continues to own list paging and ordering. Scope and stage clauses are appended to the current query builder so search, order, and remaining quick filters compose with the organizer. Counts use the same predicates to avoid UI/list disagreement.

### Use one compact preset and rule header

The first persistent carousel is replaced by a single scope selector for All, manual categories, and smart-folder presets. A compact action row exposes create/edit rules, transient filters, and refresh. Active rule summaries wrap instead of scrolling horizontally. Lifecycle stages remain the only primary selection group and wrap across lines on narrow screens. Existing quick filters remain available through the rule control instead of permanently consuming a third horizontal row.

Alternative considered: keeping all three horizontal rows and only shortening labels. It was rejected because it preserves ambiguous hierarchy and requires repeated side-scrolling on phone widths.

### Refresh only source-dependent terminal candidates

The organizer refresh action targets titles currently classified as Waiting, Completed, or Needs review in the selected scope. These are the stages whose correctness depends on a caught-up percentage and current source metadata. Each title is checked using the existing new-chapter path, then progress is recalculated from the returned chapter set even when the tracker reports no new log entry. Successful source status is persisted before Room re-evaluates counts. Refresh is user initiated, lifecycle-cancelled, bounded, and reports partial failures instead of silently treating them as success.

Alternative considered: automatically refreshing every favorite whenever Favorites opens. It was rejected because a large library can create hundreds of source requests and make screen entry slow or hostile to source servers.

### Cancel source-catalog image work when rows are recycled

`SourcesCatalogAdapter` owns cleanup of favicon requests in `onViewRecycled`, while the activity continues to detach the adapter during destruction. This closes the exact request lifecycle shown by LeakCanary without changing the application-wide image loader.

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

## Migration Plan

1. Migrate Room 28 to 29 by creating the empty smart-folder table and indexes; existing data is unchanged.
2. Deploy organizer queries and UI with `All` as the initial stage for every tab.
3. Backups created by v29 include the optional smart-folder section; restore remains tolerant of its absence.
4. Rollback to v28 is not supported by Room; users can restore a pre-upgrade backup. No existing table is rewritten, so forward migration risk is limited to table creation.

## Open Questions

None for Core v1. Advanced rule composition and cloud synchronization require separate proposals.
