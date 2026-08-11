## ADDED Requirements

### Requirement: 16 KB native-library compatibility
The debug and release Android packages SHALL use 16 KB-compatible 64-bit native libraries and packaging.

#### Scenario: APK native libraries are verified
- **WHEN** the packaged arm64-v8a and x86_64 shared libraries are inspected
- **THEN** every ELF `PT_LOAD` segment has alignment of at least `0x4000`, uncompressed libraries are ZIP-aligned for 16 KB loading, and the platform does not reject their RELRO layout

#### Scenario: Pixel compatibility check
- **WHEN** the separate debug package is installed on a 16 KB-capable Pixel
- **THEN** Android does not report Conscrypt or AVIF as incompatible

### Requirement: Source list image lifecycle
Source management and catalog lists SHALL not retain a destroyed activity through favicon image requests or attached RecyclerView infrastructure.

#### Scenario: Source row is recycled
- **WHEN** a source row is recycled or its fragment or activity view is destroyed
- **THEN** the row's active image request is disposed, the adapter and drag helper are detached, and no request context retains the destroyed activity

### Requirement: Favorites pager page lifecycle
The Favorites container SHALL bind its controls only to the currently selected pager page and SHALL NOT retain list fragments destroyed by page replacement, folder deletion, or container teardown.

#### Scenario: Selected folder is deleted before opening All favorites
- **WHEN** the user deletes one or more folders and navigates to All favorites while the container remains active
- **THEN** the previous page binding is cancelled, the All favorites page becomes the only observed page, and no container coroutine or lifecycle observer retains a destroyed `FavouritesListFragment`

#### Scenario: Current page changes
- **WHEN** the selected Favorites pager page changes
- **THEN** the previous page binding is cancelled before the new page is bound, exactly one active page supplies stage, count, filter, and refresh state to the container controls, and inactive pages cannot update those controls

### Requirement: Kotlin 2 Hilt processing compatibility
The Android build SHALL use an AndroidX Hilt processor line compatible with the project's Kotlin 2 and KSP2 toolchain without raising the application's minimum SDK above 21.

#### Scenario: Hilt workers are generated from clean outputs
- **WHEN** debug KSP processing runs after application build outputs are cleaned
- **THEN** every `@HiltWorker` assisted factory is generated without generic `WorkerAssistedFactory<T>` validation failures or FIR analysis errors, using the API 21-compatible worker runtime
