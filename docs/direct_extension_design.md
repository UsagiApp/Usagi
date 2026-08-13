# Thiết kế tính năng quản lý và cài đặt extension Tachiyomi không cần APK

## Mục tiêu

Tính năng cho phép người dùng thêm một hoặc nhiều extension repository, duyệt catalog Tachiyomi/Mihon, tải artifact tương thích trực tiếp vào storage nội bộ của Usagi, nạp source vào runtime mà không đăng ký package Android, cập nhật hoặc gỡ extension, và tiếp tục dùng các chức năng enable/disable, language, source settings, search, browse và reader của Usagi.

## Quyết định kiến trúc

Usagi hiện có hai runtime khác nhau. `MangaDynamicRepository` nạp JAR plugin đã dex từ `filesDir/plugins`; TsukiMix `TachiyomiExtensionManager` nạp extension qua `PackageManager` từ APK đã cài. Vì yêu cầu không cài APK, không nên giả lập package bằng cách copy APK vào thư mục hoặc gọi package installer. Thay vào đó, feature sẽ thêm một direct Tachiyomi loader sử dụng cùng model `TachiyomiLoadResult`/`TachiyomiMangaSource`, nhưng nhận artifact path nội bộ.

Direct loader phải tách khỏi local Tsuki plugin loader. Nó sẽ đọc `AndroidManifest.xml` từ archive, lấy metadata `tachiyomi.extension.class` hoặc `tachiyomi.extension.factory`, `tachiyomix.extensionLib`, `tachiyomi.extension.nsfw` và `tachiyomix.contentWarning`, sau đó tạo classloader cô lập từ artifact. Classloader phải delegate các API dùng chung về classloader ứng dụng và ưu tiên plugin class cho package extension tương tự cách TsukiMix đang làm.

## Luồng dữ liệu

```text
User adds repo URL
        |
        v
TachiyomiRepoCatalogProvider -- HTTP/cache/JSON schema validation
        |
        v
Catalog UI -- search/filter/language/NSFW/compatibility
        |
        v
DirectExtensionStore -- download to .partial, verify, atomic rename, persist metadata
        |
        v
TachiyomiDirectExtensionLoader -- manifest + classloader + Source/SourceFactory
        |
        v
DirectExtensionManager -- installed/failed/source StateFlow
        |
        v
MangaSourceRegistry + MangaSourcesRepository + MangaRepository.Factory
```

## Định dạng lưu trữ và metadata

Mỗi extension được lưu theo `packageName` đã sanitize, không dùng tên hiển thị làm định danh. Artifact giữ phần mở rộng gốc nhưng chỉ được nạp sau khi kiểm tra. Metadata persisted trong một file JSON versioned tại `filesDir/tachiyomi-direct/metadata.json`, gồm repository URL, packageName, versionCode, versionName, libVersion, artifact URL, icon URL, source id list, SHA-256 nếu catalog cung cấp, installedAt và lastError.

| Trạng thái | Ý nghĩa | Hành vi |
| --- | --- | --- |
| `AVAILABLE` | Có trong catalog và chưa cài | Hiển thị Install |
| `INSTALLED` | Artifact đã tải và load thành công | Hiển thị Installed/Remove |
| `UPDATE_AVAILABLE` | versionCode hoặc versionName mới hơn | Hiển thị Update |
| `INCOMPATIBLE` | lib ngoài 1.4–1.6, thiếu metadata hoặc artifact không load được | Không cho cài, hiển thị lý do |
| `FAILED` | Download/parse/load thất bại | Giữ bản cũ nếu có, cho Retry |
| `DISABLED` | Đã cài nhưng người dùng tắt toàn bộ source của extension | Giữ artifact, không publish active sources |

## Tương thích artifact

Catalog hiện đại có `resources.jarUrl` và `resources.apkUrl`. Direct flow ưu tiên `jarUrl` nếu artifact là DEX-compatible với loader. Nếu `jarUrl` là bytecode JVM thuần thì không được đổi đuôi hoặc nạp trực tiếp; artifact phải được đánh dấu incompatible nếu không có bước dex đáng tin cậy. APK chỉ được xử lý như một archive direct artifact khi có thể đọc manifest, classes.dex và source classes bằng classloader nội bộ; tuyệt đối không gọi Android package installer.

Không tin cậy metadata catalog một cách mù quáng. Trước khi thay thế bản đang chạy, store cần kiểm tra:

1. URL dùng HTTPS hoặc được người dùng thêm rõ ràng với cảnh báo.
2. HTTP response thành công, kích thước nằm trong giới hạn hợp lý và không vượt quá quota storage.
3. Archive có manifest metadata source class/factory.
4. libVersion thuộc dải runtime hỗ trợ.
5. Có classes.dex hoặc định dạng mà `DexClassLoader` chấp nhận.
6. Có ít nhất một source khởi tạo thành công.
7. Nếu có digest/signing key, digest phải khớp; nếu chưa verify được chữ ký thì hiển thị nguồn chưa xác minh và không giả mạo là verified.

## Merge source vào runtime

Direct manager duy trì installed direct extensions và source list. Khi refresh, Usagi sẽ:

1. Nạp package-installed extension qua TsukiMix manager như hiện tại.
2. Nạp direct artifact qua direct loader.
3. Merge theo `sourceId`, ưu tiên direct extension nếu cùng package/source đang được người dùng quản lý trực tiếp và direct artifact load thành công.
4. Publish danh sách `TachiyomiMangaSource` mới qua một adapter hoặc mở rộng TsukiMix manager.
5. Cập nhật `MangaSourceRegistry` để `MangaSourcesRepository` assimilate source mới.
6. Xóa cache parser khi version registry thay đổi.

`MangaRepository.Factory` và `ExternalMangaRepository` có thể tiếp tục dùng `TachiyomiMangaSource` vì source wrapper vẫn chứa `CatalogueSource`. Cần bảo đảm `pkgName` ổn định và classloader sống lâu hơn mọi repository đang sử dụng source.

## UI và quản lý

Màn hình `PluginsManageFragment` được mở rộng thành vùng quản lý extension. Dialog import hiện có thêm mục “Extension repository”. Mục này cho phép nhập URL catalog hoặc URL repository GitHub/Codeberg, tải metadata, hiển thị danh sách extension theo search/language/NSFW/compatibility, và cài từng extension. Danh sách local tiếp tục hỗ trợ rename/delete cho plugin Tsuki; direct Tachiyomi extension dùng packageName làm định danh và không cho rename vì rename làm mất liên kết update.

Mỗi card direct extension cần hiển thị tên, version đang cài/mới nhất, số source, locale, cảnh báo NSFW, repository, trạng thái tương thích, cùng actions Install/Update/Retry/Remove. Khi cài hoặc update thất bại, card giữ bản cũ và hiển thị lỗi rút gọn; log chi tiết chỉ ghi vào diagnostics nội bộ.

## Update và rollback

Update chạy trong coroutine/WorkManager hiện có nhưng phải đi qua một mutex store. Download vào `<package>.partial`, validate đầy đủ, load thử vào classloader mới, rồi mới atomically replace artifact. Metadata cũ và file artifact cũ được giữ tới khi bản mới load thành công; nếu load fail thì xóa partial, phục hồi bản cũ và giữ source đang hoạt động. Auto-update chỉ cập nhật extension đã cài, tôn trọng setting hiện có và không tự cài extension mới.

## Phạm vi triển khai ưu tiên

Bản triển khai đầu tiên sẽ có repository catalog JSON, cache/reload, direct artifact store, compatibility inspection, direct loader, source merge, install/update/remove và UI cơ bản trong màn hình quản lý plugin. Protobuf index có thể bổ sung sau khi schema compiler của TsukiMix/Usagi được xác định; JSON đủ để hỗ trợ Keiyoushi `index.json` và các catalog tương thích.

## Giới hạn đã biết

Một số extension Mihon hiện đại dùng ext-lib 1.6 hoặc dependency/API riêng; không phải mọi artifact JAR trong repo đều là DEX-JAR phù hợp với `DexClassLoader` của Android. Các extension yêu cầu package-installed resources, Android service, content provider hoặc signature trust có thể không chạy direct-load. UI phải báo rõ `INCOMPATIBLE` thay vì cố cài hoặc fallback sang APK. Extension đã cài APK và direct extension có thể có cùng source id; merge policy phải deterministic và có cảnh báo trùng.
