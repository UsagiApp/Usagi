## ADDED Requirements

### Requirement: Favorites scopes
The system SHALL query Favorites through All, manual Category, and Smart Folder scopes without modifying favorite membership.

#### Scenario: Manual category scope
- **WHEN** a user selects a manual category
- **THEN** only favorites assigned to that category are eligible for display

#### Scenario: Smart folder scope
- **WHEN** a user selects a valid smart folder
- **THEN** only favorites matching its rules are eligible for display

### Requirement: Deterministic lifecycle classification
The system SHALL classify each eligible favorite using the ordered stages Not started, Reading, Completed, Waiting, and Needs review.

#### Scenario: No active history
- **WHEN** a favorite has no active reading history
- **THEN** it is classified as Not started

#### Scenario: Reading is active
- **WHEN** a favorite has unread tracked chapters or progress below the completion threshold
- **THEN** it is classified as Reading

#### Scenario: Finished title is caught up
- **WHEN** a favorite is caught up and its source status is FINISHED
- **THEN** it is classified as Completed

#### Scenario: Continuing title is caught up
- **WHEN** a favorite is caught up and its source status is ONGOING, PAUSED, or UPCOMING
- **THEN** it is classified as Waiting

#### Scenario: Status requires review
- **WHEN** a favorite is caught up and its source status is absent, conflicting, ABANDONED, RESTRICTED, or otherwise unusable
- **THEN** it is classified as Needs review

### Requirement: Stage filtering and counts
The system SHALL expose an All stage plus single-stage filtering and mutually consistent counts for the selected scope.

#### Scenario: All stage is selected
- **WHEN** a user selects All for a scope
- **THEN** every favorite eligible for that scope remains visible

#### Scenario: Lifecycle data changes
- **WHEN** history, tracker, manga status, or local-index data changes
- **THEN** the visible results and stage counts update from the same predicates

### Requirement: Per-scope stage state
The system SHALL keep one selected lifecycle stage per tab for the lifetime of the Favorites screen and default a newly observed tab to All.

#### Scenario: User switches tabs
- **WHEN** a user selects different stages in two tabs and moves between them
- **THEN** each tab restores its own selected stage

### Requirement: Source-backed organizer refresh
The system SHALL let a user refresh source-dependent terminal candidates in the selected scope without refreshing unrelated Not started or actively Reading titles.

#### Scenario: Waiting progress is stale
- **WHEN** a caught-up favorite is in Waiting but its source now contains unread chapters
- **THEN** refresh recalculates its progress from the current preferred branch and the favorite moves to Reading

#### Scenario: Source status changed
- **WHEN** refresh receives a newer source status for a caught-up favorite
- **THEN** the persisted status and lifecycle stage update from the same successful result

#### Scenario: A source refresh fails
- **WHEN** one or more source checks fail
- **THEN** successful titles are updated, failures are reported, and failed titles are not assigned fabricated metadata
