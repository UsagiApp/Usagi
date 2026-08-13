# Extension bug-fix research findings

## TachiyomiX manifest contract

The Mihon `mihonapp/tachiyomix` README states that an extension manifest declares `tachiyomi.extension`, `tachiyomix.name`, `tachiyomix.contentWarning`, `tachiyomix.extensionLib`, and either `tachiyomi.extension.class` or `tachiyomi.extension.factory`. The documented content warning values are `0 = Safe`, `1 = Mixed`, and `2 = NSFW`. The metadata value is numeric, not a boolean string. The repository README also states that version 1.6 is the current compatible library target for the referenced branch.

Source: https://github.com/mihonapp/tachiyomix

## Index format

The TachiyomiX index documentation says an extension store index can be represented as Protobuf or its JSON equivalent, and host apps should support both formats. The index may also be gzip-compressed. The index schema is maintained under `index/index.proto` and includes a `CONTENT_WARNING_UNSPECIFIED` enum value in the current branch.

Source: https://github.com/mihonapp/tachiyomix/tree/main/index

## Local implementation findings

The current Usagi implementation treats any non-empty catalog `contentWarning` string as NSFW, which incorrectly marks values such as `CONTENT_WARNING_UNSPECIFIED` or safe/mixed values. It also stores only source id/name/language/home URL and does not persist source icon URL. The Source catalogs UI currently uses the plugin chip as a JAR/plugin filter, but Tachiyomi items are mixed into that path without a clear external-extension filter model.

The current direct loader passes APK/JAR paths to `PackageManager.getPackageArchiveInfo` and a `ChildFirstPathClassLoader`, but the requested behavior requires an explicit artifact pipeline that can extract or dex the extension payload before loading, including JARs that do not expose a conventional Android archive layout.

The current Manage plugins view has had its Tachiyomi repository/import option removed, and its ViewModel reconstructs direct-extension entries only from the installed StateFlow. This makes imported-but-not-installed metadata invisible and prevents normal removal/update actions from being consistently represented.

## Current index schema details

The current `index.proto` defines `Extension.contentWarning` as an enum and defines extension resources with `apkUrl` and `iconUrl`. Each `Source` carries an id, name, language, home URL, mirror URLs, and optional message. The current enum is `CONTENT_WARNING_UNSPECIFIED = 0`, `CONTENT_WARNING_SAFE = 1`, `CONTENT_WARNING_MIXED = 2`, and `CONTENT_WARNING_NSFW = 3`. This is distinct from the older TachiyomiX manifest README example that documents numeric `tachiyomix.contentWarning` values `0/1/2`; the loader therefore needs version-aware and type-aware decoding instead of treating any non-empty catalog string as NSFW.

Source: https://raw.githubusercontent.com/mihonapp/tachiyomix/main/index/index.proto
Source: https://github.com/mihonapp/tachiyomix

The index exposes an extension-level icon URL, not a separate icon URL per source. The UI can therefore use the extension icon as the source-row icon, with a stable fallback when unavailable.
