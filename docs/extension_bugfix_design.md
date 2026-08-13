# Tachiyomi/Mihon extension bug-fix design

## Objectives

The implementation must restore a complete lifecycle for imported Tachiyomi/Mihon extensions, keep imported metadata visible in Manage plugins, allow removal from the direct-extension directory and source registry, make Source catalogs filter external extensions by plugin identity rather than source name, read content ratings without treating every non-empty string as NSFW, load APK/JAR artifacts through an explicit archive-to-Dex pipeline, and show an icon for every external source row.

## Metadata and content rating

Catalog metadata and packaged manifest metadata are separate inputs. The catalog uses the current TachiyomiX index enum (`UNSPECIFIED`, `SAFE`, `MIXED`, `NSFW`) while older extension manifests expose `tachiyomix.contentWarning` as an integer and also expose `tachiyomi.extension.nsfw`. The decoder will normalize all inputs into a tri-state/content-rating enum and only map `NSFW` to `isNsfw = true`. `SAFE`, `MIXED`, and `UNSPECIFIED` will not be marked NSFW by default. The packaged manifest remains authoritative after installation, with catalog metadata used as a fallback only when the archive does not expose a rating.

## Manage plugins lifecycle

Manage plugins will keep a catalog-backed list containing imported artifacts whether installed or not. Each item will show extension name, package, version, library version, source count, rating, and install state. The existing trailing actions will map to Install/Update and Delete. Delete will remove the staged artifact, remove its persisted record, unload its classloader reference, reload the direct source registry, and refresh Manage sources.

## Source catalogs filters

The plugin chip will be populated from extension/JAR identities (`PluginMangaSource.jarName` for native plugins and package/name for external artifacts), never from `source.name`. Filtering will compare a native source's `jarName` or an external source's owning package/artifact identity. Search remains the source-name/search field. External items will not be inserted into the native DB-only query path as if they were native sources.

## Artifact loading

The installer will stage the downloaded bytes, detect whether the payload is an APK/ZIP containing `AndroidManifest.xml` and DEX files, or a plain JAR containing JVM classes. For APK/Android archive payloads, metadata is read from the packaged manifest and the classloader points at the APK or an extracted archive. For JAR payloads, the implementation will extract nested APK/Dex payloads when present; otherwise it will use a DexClassLoader-compatible transformed/staged artifact only when DEX is present. Invalid artifacts fail before replacing the existing installed file. Updates remain atomic using partial and backup files.

## Icons

The catalog's extension-level `resources.iconUrl` is the canonical icon for all sources supplied by that extension because the TachiyomiX index schema does not define a per-source icon URL. The icon URL will be stored on each catalog source item and loaded through the existing image pipeline with a deterministic service-icon fallback on error.
