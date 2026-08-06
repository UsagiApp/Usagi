## ADDED Requirements

### Requirement: Smart-folder backup section
The system SHALL store active and soft-deleted smart-folder definitions in a dedicated optional backup section.

#### Scenario: New backup is created
- **WHEN** a user creates a local backup after smart folders exist
- **THEN** the archive includes a smart-folders entry with folder identity, order, rules, timestamps, and deletion metadata

### Requirement: Backward-compatible restore
The system SHALL restore smart folders when their section is present and selected, while continuing to restore archives created before the section existed.

#### Scenario: New backup is restored
- **WHEN** a user selects Smart folders from a backup that contains the section
- **THEN** valid definitions are restored through the smart-folder repository

#### Scenario: Old backup is restored
- **WHEN** an archive has no smart-folders entry
- **THEN** existing supported sections restore normally without a smart-folder error

### Requirement: Cloud sync remains unchanged
The system SHALL NOT add smart-folder data to existing cloud-sync category, favorite, or history payloads in Core v1.

#### Scenario: Ordinary synchronization runs
- **WHEN** cloud synchronization processes categories, favorites, or history
- **THEN** their existing payload contracts and behavior remain unchanged
