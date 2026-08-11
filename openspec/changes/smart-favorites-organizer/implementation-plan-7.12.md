# Global Favorite Membership Repair Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Repair existing favorites that are missing reserved global membership and guarantee that every recovered manual membership inserts or reactivates its global row.

**Architecture:** Keep reserved category ID `0` as the source of truth for `All favorites` and smart-folder eligibility. Replace the recovery-only update trigger with insert-or-reactivate behavior, then add Room migration `30 → 31` to repair affected databases and install the corrected trigger definition.

**Tech Stack:** Kotlin, Room, SQLite triggers, AndroidX MigrationTestHelper, Android instrumentation tests, Gradle.

## Global Constraints

- Preserve active and soft-deleted manual favorite rows.
- Never remove an existing reserved global favorite during repair.
- Keep reserved category ID `0` excluded from sync and backup category/favorite payloads.
- Do not add fallback query behavior; repair the membership invariant at the database boundary.
- Keep all code and documentation changes in English.
- Do not stage, commit, or push the shared dirty worktree unless the user explicitly requests it.

---

### Task 1: Recovered Membership Trigger Regression

**Files:**
- Modify: `app/src/androidTest/kotlin/org/draken/usagi/favourites/data/FavouritesOrganizerDaoTest.kt`
- Modify: `app/src/main/kotlin/org/draken/usagi/favourites/data/AllFavoritesInfrastructure.kt`

**Interfaces:**
- Consumes: `FavouritesDao.upsert(FavouriteEntity)` and `SupportSQLiteDatabase.createAllFavoritesInfrastructure()`.
- Produces: `favourites_global_after_insert` and `favourites_global_after_recover` triggers that insert missing global rows and reactivate existing ones.

- [x] **Step 1: Add the failing real-DAO regression test**

```kotlin
@Test
fun recoveringManualMembershipCreatesMissingGlobalMembership() =
    runTest {
        insertCategory(1, "Read later")
        insertManga(1, source = "source-a", isNsfw = false, state = "ONGOING")
        insertFavourite(1, 1)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE favourites SET deleted_at = 10 WHERE manga_id = 1 AND category_id = 1",
        )
        database.openHelper.writableDatabase.execSQL(
            "DELETE FROM favourites WHERE manga_id = 1 AND category_id = 0",
        )

        database.getFavouritesDao().upsert(
            FavouriteEntity(
                mangaId = 1,
                categoryId = 1,
                sortKey = 1,
                isPinned = false,
                createdAt = 1,
                deletedAt = 0,
            ),
        )

        assertEquals(setOf(1L), observeIds(FavouriteScope.All))
    }
```

- [x] **Step 2: Run only the new instrumentation test and verify RED**

Build and install the debug and test APKs without uninstalling the user's app data, then run:

```bash
adb shell am instrument -w \
  -e class org.draken.usagi.favourites.data.FavouritesOrganizerDaoTest#recoveringManualMembershipCreatesMissingGlobalMembership \
  org.draken.usagi.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: FAIL because `favourites_global_after_recover` updates a missing category `0` row instead of inserting it.

- [x] **Step 3: Implement insert-or-reactivate trigger behavior**

Update both manual-membership activation triggers to use two statements:

```sql
INSERT OR IGNORE INTO favourites (manga_id, category_id, sort_key, pinned, created_at, deleted_at)
VALUES (NEW.manga_id, 0, 0, NEW.pinned, NEW.created_at, 0);
UPDATE favourites
SET deleted_at = 0
WHERE manga_id = NEW.manga_id AND category_id = 0;
```

Drop the named triggers before recreating them so migration `30 → 31` can replace the old definition. Preserve existing global metadata when the row already exists.

- [x] **Step 4: Re-run the targeted instrumentation test and verify GREEN**

Expected: PASS with the recovered manual row visible through `FavouriteScope.All`.

### Task 2: Room 30 to 31 Repair Migration

**Files:**
- Create: `app/src/main/kotlin/org/draken/usagi/core/db/migrations/Migration30To31.kt`
- Modify: `app/src/main/kotlin/org/draken/usagi/core/db/MangaDatabase.kt`
- Modify: `app/src/androidTest/kotlin/org/draken/usagi/core/db/MangaDatabaseTest.kt`
- Modify: `app/schemas/org.draken.usagi.core.db.MangaDatabase/31.json` through Room schema export.

**Interfaces:**
- Consumes: `SupportSQLiteDatabase.createAllFavoritesInfrastructure()`.
- Produces: `Migration30To31 : Migration(30, 31)` and `DATABASE_VERSION = 31`.

- [x] **Step 1: Add the failing migration regression**

Create a version 30 fixture containing two active manual favorites: one without a global row and one with a soft-deleted global row. Run `Migration30To31()` and assert both category `0` rows are active while an unrelated existing global favorite remains active.

```kotlin
database.query(
    "SELECT COUNT(*) FROM favourites WHERE category_id = 0 AND deleted_at = 0 AND manga_id IN (1, 2, 3)",
).use { cursor ->
    cursor.moveToFirst()
    assertEquals(3, cursor.getInt(0))
}
```

- [x] **Step 2: Compile the migration test and verify RED**

Expected: compilation fails because `Migration30To31` and schema version `31` do not exist yet.

- [x] **Step 3: Implement the minimal migration**

```kotlin
class Migration30To31 : Migration(30, 31) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.createAllFavoritesInfrastructure()
    }
}
```

Inside `createAllFavoritesInfrastructure()`, first insert missing reserved rows from active manual memberships, then reactivate reserved rows for those memberships, and finally recreate the triggers. Register the migration and advance `DATABASE_VERSION` to `31`.

- [x] **Step 4: Run the targeted migration and trigger tests and verify GREEN**

Expected: both targeted tests pass, including schema validation for version `31`.

### Task 3: Documentation and Focused Verification

**Files:**
- Modify: `openspec/changes/smart-favorites-organizer/tasks.md`
- Verify: `openspec/changes/smart-favorites-organizer/proposal.md`
- Verify: `openspec/changes/smart-favorites-organizer/design.md`
- Verify: `openspec/changes/smart-favorites-organizer/specs/favorites-lifecycle/spec.md`

**Interfaces:**
- Consumes: passing trigger and migration regressions.
- Produces: completed OpenSpec task `7.12` and a fresh debug APK for manual device validation.

- [x] **Step 1: Mark OpenSpec task 7.12 complete only after both regressions pass**

Change `- [ ] 7.12` to `- [x] 7.12`.

- [x] **Step 2: Validate documentation and source formatting**

Run:

```bash
openspec validate smart-favorites-organizer --strict
git diff --check
```

Expected: both commands exit with status `0`.

- [x] **Step 3: Build the debug APK**

Run:

```bash
./gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL` and `app/build/outputs/apk/debug/app-debug.apk` exists.

- [x] **Step 4: Do not deploy automatically**

Report the APK path and wait for the user's explicit installation request before replacing the currently installed dev package. The user later granted unrestricted Pixel test deployment permission, so the verified APK was installed without clearing app data.
