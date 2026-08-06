## ADDED Requirements

### Requirement: 16 KB native-library compatibility
The debug and release Android packages SHALL use 16 KB-compatible 64-bit native libraries and packaging.

#### Scenario: APK native libraries are verified
- **WHEN** the packaged arm64-v8a and x86_64 shared libraries are inspected
- **THEN** every ELF `PT_LOAD` segment has alignment of at least `0x4000`, uncompressed libraries are ZIP-aligned for 16 KB loading, and the platform does not reject their RELRO layout

#### Scenario: Pixel compatibility check
- **WHEN** the separate debug package is installed on a 16 KB-capable Pixel
- **THEN** Android does not report Conscrypt or AVIF as incompatible

### Requirement: Source catalog image lifecycle
The source catalog SHALL not retain a destroyed activity through favicon image requests.

#### Scenario: Catalog row is recycled
- **WHEN** a source-catalog row is recycled or the catalog activity is destroyed
- **THEN** the row's active image request is disposed and no request context retains the destroyed activity
