## ADDED Requirements

### Requirement: Persistent smart folders
The system SHALL let users create, edit, reorder, and soft-delete smart folders independently of manual categories.

#### Scenario: Smart folder is active
- **WHEN** a valid smart folder is saved
- **THEN** it appears after visible manual categories according to smart-folder order

#### Scenario: Smart folder is deleted
- **WHEN** a user deletes a smart folder
- **THEN** it no longer appears or matches titles while its soft-delete metadata is retained

### Requirement: Core rule semantics
The system SHALL support source sets, manual-category sets, tag sets, Any/SFW/NSFW content mode, and Any/On device/Not on device device mode. Populated groups SHALL combine with AND, and multiple values within a group SHALL combine with OR.

#### Scenario: Values inside a group
- **WHEN** a source group contains multiple source IDs
- **THEN** a favorite satisfies the group by matching any listed source

#### Scenario: Multiple rule groups
- **WHEN** source, tag, content, and device groups are populated
- **THEN** a favorite matches only when it satisfies every populated group

#### Scenario: Values inside a tag group
- **WHEN** a tag group contains multiple tag IDs
- **THEN** a favorite satisfies the group by matching any listed tag

### Requirement: Rule validation
The system SHALL require at least one effective condition and SHALL never broaden results when a rule is invalid, corrupted, unsupported, or references a deleted category.

#### Scenario: Rule has no conditions
- **WHEN** a user attempts to save a rule with no source, category, content, or device condition
- **THEN** the system rejects the rule with a validation error

#### Scenario: Referenced category is deleted
- **WHEN** a smart folder references a missing or deleted category
- **THEN** its category group matches no favorites and other groups do not turn it into an unrestricted scope

#### Scenario: Stored rule payload is corrupted
- **WHEN** a smart folder rule cannot be decoded or validated
- **THEN** the UI shows an explicit error and the folder returns no favorites

### Requirement: Organizer UI composition
The system SHALL expose All first, then visible manual categories, then smart folders through the familiar scrollable folder tabs, with an adjacent smart-folder create action, long-press management for editable folders, and single-selection automatic lifecycle subfolders below them.

#### Scenario: Smart folder is created from Favorites
- **WHEN** a user activates the add action beside the folder tabs
- **THEN** the smart-folder editor opens without replacing or migrating existing manual categories

#### Scenario: Editable folder is long-pressed
- **WHEN** a user long-presses a manual category or smart folder tab
- **THEN** the existing edit and management actions for that folder type are available

#### Scenario: Favorites quick filters are shown
- **WHEN** a user opens the rule filter control in Favorites
- **THEN** Downloaded, New chapters, SFW/NSFW, source, and tag filters remain available while the redundant Completed filter is absent

#### Scenario: Folder fixes a rule dimension
- **WHEN** the selected smart folder already constrains source, tag, content, or device
- **THEN** transient filters from that dimension are hidden and any previously applied conflicting option for that folder is cleared

#### Scenario: User switches to an unrestricted folder
- **WHEN** the user switches from a constrained smart folder to All, a manual category, or a folder without that constraint
- **THEN** the transient filter dimension is available again and the destination folder retains its own transient filter state

#### Scenario: Narrow Favorites screen
- **WHEN** available width cannot fit every lifecycle stage
- **THEN** lifecycle subfolders remain reachable without reducing the manga list to a multi-row organizer header

#### Scenario: Existing scope presets
- **WHEN** Favorites opens
- **THEN** All, existing manual categories, and existing smart folders remain selectable without migrating or duplicating them

#### Scenario: No editable folders exist
- **WHEN** no visible manual categories or active smart folders exist
- **THEN** All remains the first selectable folder and displays its Favorites list instead of a missing-categories container state

#### Scenario: Existing Read later category exists
- **WHEN** the organizer is enabled for an existing installation
- **THEN** the manual Read later category is unchanged and the automatic no-history stage is labeled Not started
