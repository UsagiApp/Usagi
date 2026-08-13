# Nghiên cứu hệ sinh thái extension Tachiyomi/Mihon

## Phạm vi đã kiểm chứng

Ngày nghiên cứu: 2026-08-13.

### 1. Repository extension gốc của Tachiyomi

Nguồn: [tachiyomiorg/extensions](https://github.com/tachiyomiorg/extensions).

Repository này đã được archive ngày 13/01/2024 và chỉ còn read-only. README mô tả đây là catalog extension cho Tachiyomi; extension được tải xuống, cài đặt và gỡ cài đặt qua ứng dụng chính, theo mô hình cài như ứng dụng Android thông thường và artifact là `.apk`. README cũng chỉ rõ artifact có thể tải trực tiếp từ nhánh `repo/apk` hoặc website extension. Điều này xác nhận mô hình cũ phụ thuộc APK/package system, không phải cơ chế nạp JAR trực tiếp.

### 2. Repository Keiyoushi hiện được duy trì

Nguồn: [keiyoushi/extensions](https://github.com/keiyoushi/extensions).

Repository có nhánh phân phối `repo` và các artifact/index sau:

| Thành phần | Vai trò quan sát được |
| --- | --- |
| `index.json` | Catalog dạng JSON đầy đủ |
| `index.min.json` | Catalog JSON rút gọn |
| `index.pb` | Catalog Protocol Buffers được README khuyến nghị cho Mihon/variants |
| `repo.json` | Metadata repository |
| `apk/` | Artifact APK |
| `jar/` | Artifact JAR |
| `icon/` | Icon extension |

README cung cấp URL phân phối `https://github.com/keiyoushi/extensions/raw/repo/index.pb`, trỏ tới source code tại `keiyoushi/extensions-source`, và khuyến nghị người dùng thêm repository vào Mihon hoặc các biến thể. README cũng nêu rằng nếu ứng dụng không thuộc fork hỗ trợ repo, có thể tải/cập nhật extension thủ công từ listing page. Dữ kiện này cho thấy catalog hiện đại có thể cung cấp đồng thời APK và JAR, nhưng format chính thức được Mihon khuyến nghị vẫn là index protobuf.

## Hệ quả kỹ thuật ban đầu cho Usagi

Usagi đã có một runtime nạp plugin JAR riêng (`PluginFileLoader` + `MangaDynamicRepository`) và đã tải release `.jar` từ GitHub. Vì vậy, hướng khả thi nhất là thêm một pipeline đọc catalog repository, chọn artifact JAR tương thích, tải vào thư mục plugin nội bộ, xác thực/giải nén metadata cần thiết, rồi gọi reload runtime; không nên cài APK hoặc khởi chạy Android package installer.

Tuy nhiên, cần kiểm tra tiếp schema thực tế của `index.json`/`index.pb`, tương thích ABI/API của JAR với Tsuki/TsukiMix và cách Mihon phân biệt extension variant, locale, versionCode, minSdk तथा apk/ jar. Không được giả định rằng mọi JAR từ repository Mihon có thể nạp trực tiếp vào `MangaDynamicRepository`; cần kiểm tra binary compatibility và xử lý lỗi theo từng extension.

### 3. Schema catalog JSON thực tế của Keiyoushi

Nguồn kiểm tra trực tiếp: [index.json](https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.json).

Catalog gốc có các trường cấp repository như `name`, `badgeLabel`, `signingKey`, `contact`; danh sách nằm tại `extensionList.extensions`. Mỗi extension quan sát được có `name`, `packageName`, `resources.apkUrl`, `resources.iconUrl`, `resources.jarUrl`, `extensionLib`, `versionCode`, `versionName`, có thể có `contentWarning`, và mảng `sources`. Mỗi source có `id`, `name`, `language`, `homeUrl`.

Usagi có thể dùng `resources.jarUrl` làm artifact ưu tiên cho pipeline không-APK, đồng thời dùng `packageName` làm định danh ổn định, `versionCode`/`versionName` cho update, `extensionLib` để kiểm tra ABI/API, `sources` để tạo `TachiyomiMangaSource`, và `iconUrl` để hiển thị. `signingKey` là dữ liệu cần giữ trong catalog/repository metadata nếu muốn bổ sung xác thực chữ ký; không nên bỏ qua khi triển khai production.

Catalog khá lớn, vì vậy implementation nên parse streaming hoặc tải/ghi cache có giới hạn; không nên giữ raw JSON toàn bộ trong UI state.

### 4. Ràng buộc build và tương thích từ source repository

Nguồn kiểm tra trực tiếp: [AGENTS.md của extensions-source](https://raw.githubusercontent.com/keiyoushi/extensions-source/main/AGENTS.md).

Hướng dẫn hiện hành nêu rằng source mới phải extend `KeiSource` với `libVersion = "1.6"`; `HttpSource`/lib 1.4 chỉ còn ở extension chưa migration. Metadata như `name`, `lang`, `id`, `baseUrl` được inject bằng KSP từ DSL `source {}` trong Gradle. Hướng dẫn cũng nhắc đến hỗ trợ ext-lib 1.6 và minSdk 21 trong lịch sử build.

Điều này tạo ra rủi ro tương thích quan trọng: JAR được cung cấp bởi repository có thể chứa implementation theo ext-lib 1.4 hoặc 1.6, trong khi runtime TsukiMix của Usagi phải nhận diện/bridge đúng interface và dependency. Việc chỉ thấy file `.jar` không đủ để kết luận có thể nạp an toàn. Pipeline nên đọc `extensionLib`, so sánh với các API runtime đã hỗ trợ, và trả về trạng thái `unsupported` thay vì cố nạp mù.

### 5. Artifact runtime của plugin Usagi

Nguồn kiểm tra: [UsagiApp/plugins README](https://raw.githubusercontent.com/UsagiApp/plugins/master/README.md).

Template plugin chính thức của Usagi mô tả quy trình build `plugin.jar`, sau đó chạy `d8 --release build/libs/plugin.jar --output plugin.jar` để dex thành JAR có thể dùng tại runtime; README cũng nói có thể chạy task `buildJar`. Đây là bằng chứng trực tiếp rằng runtime plugin của Usagi mong đợi một artifact JAR đã dex, khác với APK extension Mihon dù cả hai có thể bắt nguồn từ cùng source parser.

Vì vậy, tính năng mới cần ưu tiên `jarUrl` và có thể thêm bước kiểm tra artifact là DEX/JAR hợp lệ. APK nên bị loại khỏi flow mặc định, không tự động cài qua package manager và không coi APK là plugin runtime tương thích.

### 6. Kiểm tra artifact JAR thực tế

Đã tải thụ động artifact `tachiyomi-all.akuma-v1.4.10.jar` từ `https://raw.githubusercontent.com/keiyoushi/extensions/repo/jar/` và chỉ kiểm tra archive, không thực thi. Lệnh `file` nhận diện file là Android package có `AndroidManifest.xml`; archive có các `.class`, resource, chữ ký `META-INF/CERT.*` và không hiển thị `classes.dex` trong phần liệt kê đã kiểm tra.

Kết luận thận trọng: tên `.jar` trong catalog không đảm bảo artifact là DEX-JAR theo định dạng plugin của Usagi. Artifact có thể là JAR/ZIP chứa bytecode JVM hoặc APK-like package. Pipeline cài đặt phải kiểm tra cấu trúc bytecode, entry point/API cần thiết và khả năng load trước khi thay thế plugin đang chạy; nếu không tương thích thì giữ file cũ và báo lỗi rõ ràng.

### 7. Ràng buộc từ PluginClassLoader của Usagi

`PluginClassLoader` của Usagi kế thừa `dalvik.system.DexClassLoader`, nên file nạp vào runtime phải là DEX/APK/JAR mà Android có thể xử lý như dex path; bytecode JVM `.class` thuần túy trong một JAR chưa dex không đủ. Loader có quy tắc delegate các package dùng chung như `tsuki.*` và một số package parser cũ về classloader của ứng dụng, đồng thời thử tìm class plugin-local trong các namespace Tachiyomi/Kotatsu/Keiyoushi.

Do đó tính năng mới phải có bước `artifact inspection` trước khi cài: nhận diện APK, JAR chứa `classes.dex`, hoặc JAR bytecode thuần; chỉ cho phép nạp loại tương thích với `DexClassLoader` và API bridge của Usagi. JAR bytecode thuần từ source repository cần được đánh dấu không tương thích hoặc chuyển đổi bằng pipeline build đáng tin cậy, không được đổi đuôi rồi nạp trực tiếp.

### 8. TsukiMix là cầu nối chính thức của Usagi

Nguồn: [UsagiApp/TsukiMix](https://github.com/UsagiApp/TsukiMix).

README của TsukiMix mô tả rõ đây là core library dùng trong Usagi để "read and compile every Tachiyomi exts to External plugins". Cây source có các package `org/draken/tsukimix/core/parser/tachiyomi`, `eu/kanade/tachiyomi` và các thư viện hỗ trợ. Điều này xác nhận mục tiêu người dùng phù hợp với kiến trúc hiện tại: không cần biến APK thành extension Android cài riêng, nhưng phải sử dụng pipeline/bridge của TsukiMix để compile hoặc chuyển đổi Tachiyomi extension thành external plugin.

Vì vậy, implementation nên tích hợp với API `TachiyomiExtensionLoader`, `TachiyomiExtensionManager`, `TachiyomiMangaSource` hiện có thay vì tự thay thế toàn bộ parser runtime. Cần kiểm tra tiếp source cụ thể của TsukiMix để phát hiện API public cho compile/install từ APK hoặc JAR/catalog.

### 9. API công khai của TachiyomiExtensionManager

Nguồn kiểm tra trực tiếp: [TachiyomiExtensionManager.kt](https://raw.githubusercontent.com/UsagiApp/TsukiMix/master/library/src/main/kotlin/org/draken/tsukimix/core/parser/tachiyomi/TachiyomiExtensionManager.kt).

Manager chỉ quản lý các extension đã được `TachiyomiExtensionLoader.loadExtensions(context)` nạp từ context. API public gồm các StateFlow `installedExtensions`, `failedExtensions`, `isLoading`, `isReady`, `sources`; các hàm `loadExtensions()`, `ensureReady(forceRefresh)`, `getSourceById`, `getSourceByName`, `getSources`, `getActiveSources`, `getLanguage`, `setActiveLanguage`, `resolve` và `getSourcesByLanguage`.

Không thấy API public để cài đặt APK/JAR hoặc thêm artifact từ một URL. Do đó tính năng mới phải nằm ở Usagi (repository/provider + UI) để tải artifact về storage plugin của Usagi hoặc storage mà loader của TsukiMix thực sự quét, sau đó gọi `ensureReady(forceRefresh = true)`. Cần đọc tiếp `TachiyomiExtensionLoader.kt` để xác định chính xác thư mục/package scan và loại file loader nhận.

### 10. Điều kiện load extension của TsukiMix

Nguồn kiểm tra trực tiếp: [TachiyomiExtensionLoader.kt](https://raw.githubusercontent.com/UsagiApp/TsukiMix/master/library/src/main/kotlin/org/draken/tsukimix/core/parser/tachiyomi/TachiyomiExtensionLoader.kt).

Loader hiện tại không quét thư mục JAR tùy ý. Nó gọi `PackageManager.getInstalledPackages(...)`, lọc package có feature `tachiyomi.extension` hoặc metadata `tachiyomi.extension.class`/`tachiyomi.extension.factory`, đọc `tachiyomix.extensionLib`, giới hạn lib version từ 1.4 đến 1.6, yêu cầu source metadata, chữ ký APK, sau đó dùng `ChildFirstPathClassLoader(appInfo.sourceDir, ...)` để load class. Source class được khởi tạo và phải là `Source` hoặc `SourceFactory`.

Kết luận then chốt: TsukiMix hiện chỉ load extension từ APK đã được Android đăng ký là installed package. Đặt APK/JAR vào thư mục nội bộ rồi gọi `ensureReady()` sẽ không làm manager nhìn thấy nó. Để đáp ứng yêu cầu không cài APK, Usagi cần một loader thứ hai ở cấp ứng dụng: lấy APK/JAR từ catalog, mở archive, đọc manifest/metadata, tạo classloader từ đường dẫn file nội bộ, khởi tạo source class và chuyển các source về `TachiyomiMangaSource`, hoặc cần mở rộng TsukiMix API để hỗ trợ external artifact. Việc này phải giữ các ràng buộc lib 1.4–1.6, metadata source class/factory và dependency/classloader isolation.

### 11. Chi tiết API loader

Bản mã nguồn đầy đủ cho thấy `TachiyomiExtensionLoader` có `loadExtension(context, packageName)` và `getInstalledExtensions(context)`, nhưng cả hai đều gọi `PackageManager` để lấy `PackageInfo`, rồi sử dụng `ApplicationInfo.sourceDir` và metadata từ manifest. Loader không có hàm nhận `File`, `Uri`, URL hoặc artifact path tùy ý.

Để hỗ trợ không-APK, hướng ít xâm lấn nhất là mở rộng TsukiMix bằng một API song song kiểu `loadExtensionFromPath(context, path, metadata)` hoặc một `TachiyomiArtifactLoader` nội bộ, tái sử dụng `loadSources(...)` và các model `TachiyomiLoadResult`. Nếu chỉ sửa Usagi mà không sửa TsukiMix, phần logic reflection/classloader sẽ bị sao chép và khó đồng bộ với các cập nhật của TsukiMix.

### 12. Model source và load result

`TachiyomiLoadResult.Success` lưu `pkgName`, `appName`, `versionCode`, `versionName`, `libVersion`, `lang`, `isNsfw` và danh sách `Source`; `catalogueSources` lọc ra `CatalogueSource`. `Error` lưu package, message và exception.

`TachiyomiMangaSource` bọc một `CatalogueSource` cùng `pkgName`, sinh tên nội bộ `EXTERNAL_<sourceId>`, giữ locale/content type/title, so sánh theo source id và có `supportsLatest`. Vì vậy direct loader có thể tái sử dụng wrapper này nếu khởi tạo được các `CatalogueSource` từ artifact file và gán một `pkgName` ổn định từ catalog `packageName`.

Thiết kế đề xuất:

| Lớp | Trách nhiệm |
| --- | --- |
| `TachiyomiRepoCatalogProvider` | Tải/cached `repo.json` và `index.json`, parse repository metadata, normalize URL và validate schema |
| `TachiyomiExtensionArtifact` | Metadata catalog + URL JAR/APK + trạng thái compatibility/install |
| `TachiyomiDirectExtensionLoader` | Đọc manifest/classes từ artifact nội bộ, tạo classloader, load `Source`/`SourceFactory`, trả `TachiyomiLoadResult` |
| `TachiyomiDirectExtensionStore` | Atomic download, checksum/signature metadata, version state, delete/rollback |
| `TachiyomiExtensionManager` adapter | Merge installed direct sources với installed-package sources, publish StateFlow và refresh registry |
| Existing `PluginsManageViewModel` | Điều phối UI: catalog, install/update/remove, selection, error state |

Mục tiêu bảo toàn source APK cũ, đồng thời bổ sung direct artifact path; nếu artifact không tương thích, UI phải hiển thị lỗi và không làm mất source/plugin đang hoạt động.
