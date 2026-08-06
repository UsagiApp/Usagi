## ADDED Requirements

### Requirement: Validated reading progress
The system SHALL calculate reading progress through one shared public operation, clamp valid results to the supported range, and return no active progress for missing chapters, pages, or an unresolved chapter position.

#### Scenario: Page progress is calculated within a chapter
- **WHEN** a known chapter and page are supplied with positive chapter and page counts
- **THEN** the system returns the completed fraction of preceding chapters plus the completed fraction of the current chapter

#### Scenario: Progress input is unusable
- **WHEN** the chapter is absent from the selected branch or chapter/page counts are not positive
- **THEN** the system returns the no-progress value without producing NaN, infinity, or an out-of-range percent

### Requirement: Consistent completion threshold
The system SHALL use the same completion threshold in UI rendering, history filtering, Favorites classification, and progress updates.

#### Scenario: Percent reaches the threshold
- **WHEN** stored or calculated progress reaches the shared completion threshold
- **THEN** every progress consumer treats the manga as completed

### Requirement: Progress recalculation persistence
The system SHALL persist both the recalculated percent and the current chapter count when chapter data changes.

#### Scenario: New chapters change total progress
- **WHEN** progress is recalculated after the selected branch chapter count changes
- **THEN** history stores the new percent and new chapter count together

### Requirement: Preferred-branch completion
The system SHALL mark a manga completed using the final chapter of its preferred branch and the source associated with that chapter.

#### Scenario: Preferred branch has a final chapter
- **WHEN** the user marks a manga completed and the preferred branch can be resolved
- **THEN** history points to that branch's final chapter, source, final page, completed percent, and branch chapter count

#### Scenario: Preferred branch cannot be completed
- **WHEN** the preferred branch, final chapter, or chapter source cannot be resolved
- **THEN** the system reports failure and leaves history unchanged
