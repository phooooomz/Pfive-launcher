# Audit pemeliharaan LX-LAUNCHER, 7 November 2026

Audit ini membaca source LX-LAUNCHER 0.1.1 (Kotlin, core C++20, dan `lxdb`) untuk mencari bagian yang perlu diperbaiki atau ditingkatkan dari sisi performa, skalabilitas, kompatibilitas, responsivitas layar, serta pemakaian RAM dan storage. Semua perbaikan yang diusulkan mempertahankan fitur yang ada. Tidak ada kode yang diubah.

Pembacaan dibagi empat subsistem: lapisan UI, File Explorer dan engine arsip, aplikasi/bridge/embed, serta core native dan `lxdb`. Setiap subsistem diaudit lewat graph codebase-memory lalu dicocokkan dengan source. Ukuran aset diukur langsung di mesin pengembang. Belum ada pengukuran di HP, jadi angka biaya selain ukuran aset adalah perkiraan dari kode.

Kolom Bukti:

- Source: baris itu saya baca sendiri dan perilakunya cocok dengan deskripsi.
- Audit: ditemukan dan dicocokkan dengan source oleh audit subsistem, belum saya baca ulang.
- Diukur: diukur pada berkas di repo.
- Perlu HP: hanya bisa dipastikan di perangkat.

## Sepuluh yang paling perlu didahulukan

1. Pembersih bisa menghapus semua salinan sebuah foto, termasuk foto asli di DCIM (P0-1).
2. Uninstall aplikasi di work profile ikut menghapus pintasan aplikasi yang sama di profil utama (P0-2).
3. Setiap ganti tema membocorkan widget lama, yang terus berdetak dan memegang receiver sesudah LX di latar belakang (P0-3).
4. `lxdb` mem-poll ATMS tiap 400 ms per jendela aplikasi selama aplikasinya hidup, dan menjalankan `dumpsys` tiap 1,5 detik untuk aplikasi yang mengunci orientasi (P1-1, P1-2).
5. Mutex engine tertahan selama layout disimpan dengan fsync, sehingga drag jendela bisa tersendat (P1-9).
6. Hampir seluruh panel dibangun ulang pada setiap `model.edit` (P1-10).
7. Start Menu membangun ulang seluruh kolom pada setiap ketikan, tanpa recycling (P1-11).
8. File Explorer mengurutkan seluruh isi folder di main thread (P1-13).
9. Cache ikon dibatasi 400 entri, bukan byte, dan tidak pernah di-trim (P2-1).
10. Wallpaper bawaan menambah 13 MB ke APK, padahal WebP q90 cukup 2,4 MB (P2-7).

## P0. Risiko data, crash, dan kebocoran

| # | Lokasi | Masalah dan dampak | Perbaikan | Bukti |
|---|---|---|---|---|
| P0-1 | `tools/CleanupTool.kt:243`, `:288-292` | Sweep menghapus daftar duplikat hasil scan lama tanpa memeriksa ulang. Kalau salinan yang disimpan sudah dihapus atau diubah setelah scan, semua salinan hilang. Salinan yang disimpan dipilih dari path terpendek, jadi foto asli di `DCIM/Camera` bisa terhapus sementara salinannya di `Download` dipertahankan. | Tepat sebelum menghapus, pastikan salinan yang disimpan masih ada dengan ukuran dan hash yang sama. Prioritaskan DCIM sebagai yang disimpan. Di API 30+ pakai `MediaStore.createTrashRequest` supaya bisa dipulihkan. | Source |
| P0-2 | `apps/AppRepository.kt:77-80`, `LxApp.kt:39-43`, `core/src/desktop.cpp:389-392` | `onPackageRemoved` mengabaikan `user`, dan `Desktop::removePackage` hanya mencocokkan nama paket. Menghapus aplikasi di work profile atau profil clone ikut menghapus pintasan, isi folder, dan pin aplikasi yang sama di profil utama. | Teruskan serial user ke core dan hapus hanya key milik user itu. | Source |
| P0-3 | `LauncherActivity.kt:344-356`, `widgets/NativeWidgets.kt:96-138` | `rebuildUi()` membuang `DesktopView` lama tanpa `setWidgetsActive(false)`, dan `LxWidget` tidak punya `onDetachedFromWindow`. Receiver jam dan baterai tetap terdaftar, loop `postDelayed` (memori 5 s, media 2 s, kalender 60 s) tetap jalan, termasuk sesudah `onStop`. Terjadi setiap kali salah satu dari 21 kunci `themeKeyOf` berubah, dan menumpuk. | Panggil `desktop.setWidgetsActive(false)` sebelum `buildUi()`, dan tambahkan `onDetachedFromWindow { setActive(false) }` di `LxWidget`. | Source |
| P0-4 | `files/FileManagerTool.kt:1553` | Preview di Properti menghitung `inSampleSize` dari lebar saja. Screenshot gulir 1080x25000 menjadi bitmap sekitar 108 MB, di atas batas gambar Canvas (100 MB), dan bisa menutup paksa proses launcher. | Batasi sisi terpanjang atau jumlah piksel saat menghitung sample. | Source, Perlu HP |
| P0-5 | `bridge/AdbPairing.kt:50-61`, `bridge/BridgeSetupService.kt:43-49`, manifest `:113-124` | `SSLSockets.exportKeyingMaterial` (API 31) dipanggil di bawah `@TargetApi(S)`, jadi lint diam. `BridgeSetupService` aktif di semua versi tanpa cek SDK, dan `NoSuchMethodError` tidak tertangkap. Di Android 8 sampai 10 layanan ini juga bisa menyalakan Opsi developer untuk fitur yang tidak akan jalan. | `android:enabled="@bool/bridge_supported"` yang bernilai true hanya di `values-v34`, ditambah guard SDK di `onServiceConnected` dan sebelum pairing. | Source (jalur tanpa guard), Perlu HP (crash belum direproduksi) |
| P0-6 | `files/FileManagerTool.kt:1681-1698`, `files/FileJobs.kt:149-156` | Job salin/pindah menyimpan resolver konflik milik jendela. Sesudah ganti tema (`rebuildUi` lalu `destroyAll`), pertanyaan konflik langsung dijawab CANCEL, padahal dokumen menjanjikan job tetap jalan. Pohon view jendela lama juga tertahan sampai job selesai. | Resolver tingkat proses di `FileJobs` yang bertanya ke jendela File Explorer yang sedang terbuka, atau menunggu sampai jendela itu dibuka lagi. | Audit |
| P0-7 | `core/src/engine.cpp:197-209`, `core/src/persistence.cpp:196-204` | Kalau berkas utama lolos CRC tetapi gagal di-parse (batas parse 2 MB lebih kecil dari batas Store 4 MB, atau skema lebih baru setelah downgrade), `.bak` tidak dicoba. Dua kali simpan berikutnya menimpa backup yang masih bagus. | Coba `.bak` dulu, karantina berkas utama, dan samakan batas parse dengan batas Store. | Audit |

## P1. Baterai

| # | Lokasi | Masalah dan dampak | Perbaikan | Bukti |
|---|---|---|---|---|
| P1-1 | `lxdb/src/embed_service.cpp:40`, `:597-646` | `watch()` memanggil `firstTask()` (binder sinkron `GetAllRootTaskInfosOnDisplay`) tiap 400 ms untuk setiap display yang aplikasinya masih hidup, tanpa melihat status layar. 16 jendela berarti 40 panggilan per detik. | Poll cepat hanya selama fase kedatangan (`kArrivalTimeoutMs`), sesudah itu 2 sampai 5 detik ditambah sinyal dari task listener. | Source |
| P1-2 | `lxdb/src/embed_service.cpp:690-705` | Aplikasi yang meminta PORTRAIT atau LANDSCAPE membuat `lookEvery` kembali ke 1500 ms selamanya. Setiap look menjalankan fork+exec `dumpsys activity -a -p <pkg> activities`, juga saat layar mati atau jendela tersembunyi. | Baca orientasi dari argumen `onTaskRequestedOrientationChanged`/`onActivityRequestedOrientationChanged`. Pakai `dumpsys` hanya kalau listener tidak tersedia, biarkan backoff tetap naik, dan hentikan look saat surface null. | Source |
| P1-3 | `lxdb/src/embed_service.cpp:275-295` | `keepAwake` memegang `SCREEN_BRIGHT_WAKE_LOCK` per display tanpa timeout, juga saat jendela tersembunyi. | Lepas saat `setSurface(null)`, ambil lagi saat surface diberikan. Cek dulu dengan `adb shell dumpsys power` saat satu jendela aplikasi terbuka dan layar mati. | Audit, Perlu HP |
| P1-4 | `embed/EmbedClient.kt:144-152`, `:166-183`, `:262-270`, `bridge/LxBridge.kt:95-101` | `keepBridgeUp` tidak melihat `inFront`. Perubahan Wi-Fi bisa memulai percobaan bridge (sampai 25 s: tulis Settings, NSD, TLS) saat LX di latar belakang. Di foreground, retry tidak pernah berhenti walau tidak ada jendela yang butuh `lxdb`. | Syaratkan `inFront` atau ada jendela yang menunggu. Berhenti setelah N kali gagal sampai ada aksi pengguna, boot, atau jaringan baru. | Audit |
| P1-5 | `ui/panels/Panels.kt:191-219`, `system/SystemInfo.kt:33` | Receiver TopPanel (TIME_TICK, BATTERY_CHANGED) tetap aktif sesudah `onStop`. Setiap broadcast memanggil `registerReceiver(null)` lagi dan membuat drawable glyph baru. | Register di onStart, unregister di onStop, baca nilai dari Intent yang diterima, lewati update kalau nilainya sama. | Audit |
| P1-6 | `widgets/NativeWidgets.kt:516` | MediaWidget mem-poll tiap 2 s. | `AudioManager.registerAudioPlaybackCallback` (API 26). | Audit |
| P1-7 | `system/SystemInfo.kt:49`, `widgets/NativeWidgets.kt:304`, `:366`, `:436`, `tools/SimpleTools.kt:157-161` | `memory()` selalu memanggil `Debug.getPss()` (membaca smaps) di main thread. Widget Memori dan Status memanggilnya tiap 5 s tanpa memakai hasil PSS, Monitor tiap 2 s. | Pisahkan memori sistem (murah) dari PSS milik LX, dan hitung PSS di thread latar hanya untuk Monitor. | Audit |
| P1-8 | `tools/CleanupTool.kt:161`, `:176-260` | Setiap `onShown` (termasuk tiap `onStart` launcher) men-walk sampai 300 ribu entri lewat FUSE dengan 3 sampai 4 stat per entri, lalu SHA-256 seluruh isi berkas per grup ukuran. Hasil tidak di-cache. | Cache hasil dengan timestamp, `Files.walkFileTree` dengan `BasicFileAttributes`, hash 64 KB awal sebelum hash penuh. | Audit |

## P1. Performa dan responsivitas UI

| # | Lokasi | Masalah dan dampak | Perbaikan | Bukti |
|---|---|---|---|---|
| P1-9 | `app/src/main/cpp/jni_bridge.cpp:123-126`, `:201-205`, `core/src/engine.cpp:216`, `core/LauncherModel.kt:205-210` | Makro `LX_ENGINE` mengunci mutex engine selama seluruh `saveTo`: serialize, write, fsync, baca ulang CRC, dua rename, fsync direktori. UI thread memanggil `moveWindow`/`resizeWindow` di setiap MOVE, jadi drag yang dimulai kurang dari 400 ms setelah drag sebelumnya bisa tertahan selama fsync. | Kunci hanya untuk `exportDocument()`, tulis berkas di luar lock. | Source |
| P1-10 | `LauncherActivity.kt:366-409`, `ui/panels/Panels.kt:276-310`, `:422-589` | Setiap `model.edit` membuat dua `LxTheme`, meng-invalidate semua glass, lalu `TaskbarView.bind`/`DockView.bind` memanggil `removeAllViews` dan membangun ulang setiap tombol. Pemicunya sering: fokus jendela, ketikan Catatan (jeda 600 ms), buka aplikasi, akhir drag, keyboard muncul. Scroll taskbar dan fokus TalkBack ikut hilang. | Bind berbasis diff per panel, pakai ulang tombol per entri, cache `LxTheme` per `themeKey`, panggil `glass.update` hanya saat tema atau wallpaper berubah. | Source |
| P1-11 | `ui/overlays/StartMenu.kt:181-184`, `:425-462` | `afterTextChanged` memanggil `render()`, yang mengosongkan dan membangun ulang kedua kolom dengan sampai 200 hasil. Kolom aplikasi berupa ScrollView + LinearLayout tanpa recycling. Dengan 300 aplikasi di "Semua" ada sekitar 900 view dibuat di main thread. | RecyclerView atau ListView framework untuk kolom aplikasi, debounce pencarian sekitar 100 ms, cache hasil `buildGroups()`. | Source |
| P1-12 | `ui/windows/WindowLayer.kt:484-503` | Setiap MOVE saat drag membuat `RectF`, `IntArray`, memanggil JNI, lalu `requestLayout()` pada seluruh layer. `RootLayout.onLayout` menghitung ulang sekitar 12 Rect dan me-layout semua jendela. | Pindahkan hanya frame yang diseret (`offsetLeftAndRight`/`offsetTopAndBottom`), pakai ulang `RectF`, baca `window.edgeSnap` sekali saat drag mulai. | Source |
| P1-13 | `files/FileManagerTool.kt:868-877`, `files/FmSupport.kt:60-75`, `files/Fs.kt:45-46` | `applyFilter` mengurutkan seluruh isi folder di main thread. Komparator natural membuat `substring` untuk setiap deret angka, dan `key`/`extension` adalah getter yang menggabung string setiap dibaca. Folder 10 ribu berkas berarti sekitar 140 ribu perbandingan. | Urutkan di thread `io` sebelum hasil di-post, bandingkan angka tanpa substring, jadikan `key` dan `extension` properti yang dihitung sekali. | Source |
| P1-14 | `files/FileManagerTool.kt:1512-1536` | Hitung ukuran folder di Properti berjalan di executor `io` satu-thread yang juga melayani listing dan navigasi, sampai 50 ribu node, dan hanya berhenti saat jendela dihancurkan. Sesudah sheet ditutup, navigasi menunggu walk selesai. | Executor terpisah dengan `Cancel` yang dibatalkan saat sheet ditutup atau saat navigasi. | Source |
| P1-15 | `files/FmAdapter.kt:162-193`, `:208` | Antrean thumbnail FIFO tanpa batas, tidak dibersihkan saat pindah folder, dan setiap hasil memanggil `notifyDataSetChanged()`. Gambar yang gagal di-decode (SVG, HEIC di API 26-27) dicoba lagi di setiap bind. | Cek `tag` sebelum decode, kosongkan antrean di `load()`, perbarui hanya child yang cocok, tambah negative cache, thread prioritas background. | Audit |
| P1-16 | `ui/desktop/DesktopView.kt:257-263`, `widgets/AndroidWidgets.kt:77-87` | Ganti workspace menghancurkan frame widget. Saat kembali, `getAppWidgetInfo` dipanggil 3 kali dan RemoteViews di-inflate di main thread bersamaan dengan animasi geser. | Simpan frame 1 sampai 2 workspace terakhir (GONE, nonaktif) dan cache info per `appWidgetId`. | Audit |
| P1-17 | `core/LauncherModel.kt:175-212`, `core/src/engine.cpp:257-279`, `app/src/main/cpp/jni_bridge.cpp:61-93` | Setiap edit membangun pohon `json::Value` utuh, men-serialize, menyalin ke `vector<jchar>`, `NewString` menyalin lagi, lalu org.json mem-parse di main thread. Fokus jendela dan Alt+Tab ikut menulis ke disk. | Tulis JSON langsung ke `std::string` yang di-`reserve`. Fokus dan urutan-z lewat `live()` dengan commit yang digabung. | Audit |
| P1-18 | `apps/AppRepository.kt:62`, `:76-144` | Setiap callback paket memicu reload penuh semua profil tanpa debounce. Pool 2 thread bisa menjalankan dua reload sekaligus, dan hasil lama bisa menimpa yang baru. | Executor serial dengan nomor generasi, debounce sekitar 300 ms, update per paket lewat `getActivityList(pkg, user)`. | Audit |
| P1-19 | `LauncherActivity.kt:745-749`, `ui/desktop/DesktopView.kt:155-158` | Di ponsel jendela dibuka maximized, tetapi desktop dan widget di bawahnya tetap digambar dan berdetak. | Saat ada jendela maximized di workspace aktif, set desktop INVISIBLE dan `setWidgetsActive(false)`. | Audit |

## P2. RAM

| # | Lokasi | Masalah dan dampak | Perbaikan | Bukti |
|---|---|---|---|---|
| P2-1 | `apps/AppRepository.kt:62`, `:151-177`, `LxApp.kt:52-55` | `LruCache<String, Drawable>(400)` dihitung per entri. Key tanpa ukuran, jadi ukuran pertama yang diminta yang tersimpan (ikon buram atau boros). `sizePx > 128` menggandakan density, dan cache tidak pernah di-trim di `onTrimMemory`. Satu Drawable dipasang ke banyak ImageView berbeda ukuran, sehingga bounds saling timpa. | Render ke Bitmap per bucket ukuran, LruCache berbasis byte (misalnya `maxMemory/16`) dengan ukuran di key, evict di `onTrimMemory`, beri tiap view `BitmapDrawable` sendiri yang berbagi bitmap. | Source |
| P2-2 | `embed/EmbeddedAppTool.kt:211-217`, `:446-448`, `ui/windows/WindowLayer.kt:88` | Jendela aplikasi yang diperkecil atau di workspace lain hanya diberi GONE. TextureView tetap terpasang, jadi buffer frame terakhir seukuran display tetap ada (sekitar 4 MB per jendela di 720x1600). | Setelah tersembunyi beberapa detik atau saat `onTrimMemory`, lepas TextureView dari parent dan pasang lagi di `onShown`. Aplikasinya tetap jalan. | Audit |
| P2-3 | `lxdb/src/dex.cpp:346-357`, `lxdb/src/main.cpp:255` | `lxdb` meng-inflate semua `classes*.dex` dari framework.jar ke heap dan menyimpannya seumur daemon, padahal hanya dipakai saat start. | Resolve kode listener di `Android::open`, lalu bebaskan buffer dan munmap. Cek dengan `dumpsys meminfo` untuk proses lxdb. | Audit, Perlu HP |
| P2-4 | `ui/theme/Wallpapers.kt:94-108` | Key cache memuat radius blur, jadi menggeser slider blur men-decode ulang gambar tajam yang sama. Cache menyimpan sampai 3 set; di tablet 1440x3200 satu bitmap tajam sekitar 18 MB. | Pisahkan cache gambar tajam (key: ref dan ukuran) dari salinan blur (key: radius). | Source |
| P2-5 | `files/FileJobs.kt:701-721` | `ByteArray(256 KiB)` baru untuk setiap berkas yang disalin, data lewat heap Java dua kali, ditambah sekitar 20 syscall metadata per berkas. | Satu buffer per job. `android.os.FileUtils.copy` (API 29+), `FileChannel.transferTo` per chunk di API 26-28 dengan cek cancel per chunk. | Audit |
| P2-6 | `files/FileJobs.kt` (`plan()`) | Seluruh pohon disimpan sebagai `Item` + `FsNode` sebelum menyalin. 100 ribu berkas bisa memakan puluhan MB. | Rencanakan secara streaming per folder. | Audit |

## P2. Ukuran APK dan storage

| # | Lokasi | Masalah dan dampak | Perbaikan | Bukti |
|---|---|---|---|---|
| P2-7 | `app/src/main/assets/wallpapers/` | 7 wallpaper 1440x3200 (3 PNG, 4 JPEG q95) berukuran 12,99 MB, dan APK debug naik dari 10,8 MB ke 22,7 MB. WebP lossy q90 dengan ukuran sama: 2,42 MB, PSNR 40,9 sampai 46,8 dB terhadap aslinya. JPEG q90: 4,35 MB. | Konversi ke WebP q90 (minSdk 26 mendukungnya), ubah path di `Wallpapers.PHOTO_WALLPAPERS`, bandingkan visual di HP. Simpan migrasi untuk ref `builtin:` yang sudah tersimpan. | Diukur |
| P2-8 | `app/src/main/res/font/` | 20 TTF berukuran 4,28 MB. Subset Latin, Latin Extended, Vietnam, tanda baca, mata uang, panah, dan simbol: 2,81 MB. Lato turun dari 657 KB ke 297 KB. | `pyftsubset` dengan `--layout-features='*'`. Huruf di luar subset (Sirilik, Yunani) jatuh ke font sistem lewat fallback Android, jadi label aplikasi berbahasa lain perlu dicek di HP. | Diukur |
| P2-9 | `app/build.gradle.kts:22-25` | APK release memuat `x86_64` (sekitar 1,3 MB library) yang hanya dipakai Waydroid dan emulator. | `splits { abi }` atau AAB untuk distribusi, APK universal tetap untuk build uji Waydroid. | Diukur |
| P2-10 | `app/src/main/cpp/CMakeLists.txt:16`, `core/CMakeLists.txt:19-21` | `-fvisibility=hidden` hanya untuk source lxnative; object lxcore diekspor semua, jadi `--gc-sections` tidak bisa membuangnya. Release memakai RelWithDebInfo `-O2 -g` tanpa LTO. | `CMAKE_CXX_VISIBILITY_PRESET hidden`, `-Wl,--exclude-libs,ALL`, opsional `-flto=thin` dan `-Wl,--icf=all`. Ukur ukuran `.so` sebelum dan sesudah. | Audit |
| P2-11 | `core/src/json.cpp:469-483` dan penulis koordinat | `0.94f` ditulis `0.9399999976158142`, sekitar 3 kali lebih panjang, di berkas layout dan setiap snapshot. | Tulis dengan `to_chars(float)` kalau nilainya pas sebagai float. | Audit |

## P2. Kompatibilitas dan layar

| # | Lokasi | Masalah dan dampak | Perbaikan | Bukti |
|---|---|---|---|---|
| P2-12 | `tools/CleanupTool.kt:294-296` | Sejak Android 14, `killBackgroundProcesses` hanya bisa menghentikan proses milik LX sendiri. LX tetap memanggilnya untuk 300+ paket dan melaporkan jumlahnya sebagai aplikasi yang ditutup. | Di API 34+ pakai `EmbedClient.shell("am kill …")` di luar main thread saat `lxdb` menyala. Kalau `shell()` null, jangan tampilkan angka itu. | Source |
| P2-13 | `AndroidManifest.xml:81` | `configChanges` belum memuat `fontWeightAdjustment` (Teks tebal, Android 12+), `colorMode`, `mcc`, `mnc`, `touchscreen`, dan `grammaticalGender`. Perubahan itu membuat activity dibuat ulang, dan `onDestroy` tidak memanggil `windowLayer.destroyAll()` maupun `overlays.clear()`. | Tambahkan nilai itu, dan bersihkan jendela, overlay, serta executor `io` di `onDestroy`. | Source (manifest), Audit (onDestroy) |
| P2-14 | `ui/overlays/MoreOverlays.kt:231-233`, `:327-360`, `LauncherActivity.kt:594-595`, `:641-644` | Jumlah kolom Ringkasan workspace ditentukan dari orientasi: butuh 372 dp di layar 360 dp, lebih lebar lagi dengan `ui.scale` 1.3. FolderPopup butuh 364 dp. Overlay hanya memakai inset atas dan bawah, jadi di landscape dengan navigasi 3 tombol atau cutout, menu bisa jatuh di bawah bilah navigasi. | Kolom = lebar tersedia / lebar sel, tambahkan inset kiri dan kanan dari `systemBars or displayCutout`. | Audit |
| P2-15 | `LauncherActivity.kt:534` | Inset IME hanya dibaca di API 30+, sementara manifest memakai `adjustNothing`. Di Android 8 sampai 10 jendela LX kemungkinan tidak naik di atas keyboard. | Fallback untuk API 26-29 (misalnya `ViewTreeObserver` dan `getWindowVisibleDisplayFrame`), lalu uji di emulator API 26 sampai 29. | Audit, Perlu HP |
| P2-16 | Back handling di `LauncherActivity` | `onBackPressed` tanpa `OnBackInvokedCallback` masih jalan di target 35, tetapi tidak dipanggil lagi begitu `targetSdk` dinaikkan ke 36. | Pasang `OnBackInvokedDispatcher` (API 33+) dengan fallback `onBackPressed`, dan aktifkan `android:enableOnBackInvokedCallback`. | Audit |

## P3. Ketahanan dan perapian

| # | Lokasi | Masalah | Perbaikan | Bukti |
|---|---|---|---|---|
| P3-1 | `core/src/json.cpp:84-94`, `:199`, `core/src/desktop.cpp:789-833`, `:876-898` | `Value::set` mencari duplikat key secara linear (O(k²)), jumlah shortcut dan folder saat impor tidak dibatasi, `settle` O(n²). Berkas cadangan yang rusak bisa membuat impor lambat di main thread, dan berulang di setiap start. | Dedupe key sekali di akhir parse, batasi item per workspace ke `cols x rows`, bitset okupansi. | Audit |
| P3-2 | `core/src/json.cpp:391-437` | Parsing angka memakai `long double`, yang di Android arm64 adalah quad 128-bit soft-float. Benchmark host x86 tidak memperlihatkan biaya ini. | Jalur cepat `double` (15 digit, eksponen sampai 22), jalur lama sebagai fallback. | Audit |
| P3-3 | `core/src/desktop.cpp:750-751`, `:785-786`, `:855`, `core/src/engine.cpp:103` | `static_cast<int>` dari double di luar jangkauan int (misalnya `"col":1e300`) adalah undefined behaviour. | Clamp di domain double sebelum cast. | Audit |
| P3-4 | `app/src/main/cpp/jni_bridge.cpp` | `std::bad_alloc` tidak ditangkap, padahal `archive_jni.cpp` menangkapnya. Exception yang lolos dari fungsi `extern "C"` memicu `std::terminate`. | Wrapper try/catch yang sama dengan `archive_jni.cpp`. | Audit |
| P3-5 | `lxdb/src/process.cpp:92-118`, `lxdb/src/control.cpp:111-127` | Saat timeout hanya `sh` yang di-kill, anaknya tertinggal. `waitpid` tanpa tenggat bisa mengikat satu dari 4 looper. `waitForChild` mem-poll tiap 200 ms. | `setpgid` lalu `kill(-pid)`, tenggat pada wait, `pidfd_open` dengan fallback. | Audit |
| P3-6 | `lxdb/src/embed_service.cpp:494`, `:534-545` | Setiap DOWN/UP memanggil `firstTask`, `SetFocusedTask`, dan `SyncInputTransactions` secara sinkron di antrean oneway tunggal, jadi MOVE berikutnya tertunda. | Pakai `e->task` dari watcher. | Audit |
| P3-7 | `bridge/AdbKeys.kt:132-139`, `bridge/LxBridge.kt:242` | Setiap percobaan port membuka Keystore, membuat SSLContext, dan menandatangani sertifikat lagi. `writeBytes` tidak atomik: berkas yang rusak diganti key baru dan pengguna harus pairing ulang. | Cache instance, tulis lewat temp + rename. | Audit |
| P3-8 | `bridge/AdbLink.kt:28-53`, `bridge/AdbPairing.kt:53-57` | Socket plain tidak ditutup kalau `keys.tls()` atau `createSocket` melempar exception selain `IOException`. | try/finally. | Audit |
| P3-9 | `bridge/BridgeLog.kt:35-43` | Append berkas sinkron di main thread, ditambah tulis ulang saat berkas lewat 48 KB. | Pindahkan ke thread worker. | Audit |
| P3-10 | `files/SafBackend.kt:155-176`, `:340`, `files/FileJobs.kt:971` | Rename SAF sekitar 9 sampai 10 panggilan binder per berkas. Ekstraksi memanggil `child()` per entri ke folder staging yang masih baru, O(n²) di provider non-external. `freeBytes` dipanggil per entri. | Flag tujuan baru, teruskan parent yang sudah diketahui ke rename, cek ruang kosong per N MiB. | Audit |
| P3-11 | `files/FileManagerTool.kt:1162`, `files/FileJobs.kt:1061-1112` | Properti arsip menjalankan uji penuh (inflate dan CRC setiap entri) hanya untuk menampilkan jumlah dan ukuran. | Job info yang hanya memanggil `next()`. Tombol Uji tetap seperti sekarang. | Audit |
| P3-12 | `files/FileJobs.kt:829`, `core/src/archive_zip.cpp:604` | Level deflate 6 juga dipakai untuk JPEG, MP4, dan APK yang sudah terkompresi. `deflateInit2`/`End` per berkas. | Simpan (store) per entri untuk tipe terkompresi, `deflateReset` di antara berkas. | Audit |
| P3-13 | `tools/AppManagerTool.kt:54-76` | Setiap `onShown` membangun ulang 300+ baris dan 300+ job ikon di main thread. | List dengan recycling, rebuild hanya saat daftar aplikasi berubah. | Audit |
| P3-14 | Alokasi di jalur gambar | `Panels.kt:793` (4 `LinearGradient` per draw), `WindowChrome.kt:53` dan `:361`, `IconView.kt:123`, `:137`, `StartMenu.kt:472`. | Cache objek dan buat ulang hanya saat ukuran atau tema berubah. | Audit |
| P3-15 | `LauncherActivity.kt:1098`, `ui/overlays/MoreOverlays.kt:283-295` | `prune` wallpaper (`listFiles`, `delete`) dan render thumbnail Ringkasan workspace berjalan di main thread. | Pindahkan ke executor latar. | Audit |

## Yang sudah baik

Bagian ini sudah diperiksa dan tidak perlu diubah: glass memakai BitmapShader di atas wallpaper yang sudah di-blur, tanpa `RenderEffect` atau `saveLayer`; semua panggilan API 28 sampai 31 yang dibaca sudah diberi guard; simpan layout atomik (tmp, fsync, rename, `.bak`) dengan debounce 400 ms; konversi string JNI memakai UTF-16 sehingga emoji aman; parser JSON punya batas kedalaman dan ukuran; engine arsip streaming dengan batas zip bomb, ZIP64, dan penolakan path `..` serta symlink; listing dan pencarian File Explorer jalan di latar dengan pembatalan; executor jendela File Explorer dimatikan di `onDestroy`; `max-page-size=16384` sudah terpasang di `liblxnative.so` dan `liblxdb.so`; `largeHeap=false`.

## Urutan pengerjaan

Setiap batch berdiri sendiri dan bisa diuji sebelum batch berikutnya.

1. P0-1 sampai P0-7. Tambahkan test host untuk P0-2 (`removePackage` per user) dan P0-7 (fallback `.bak`).
2. Baterai `lxdb` dan bridge: P1-1 sampai P1-4. Ukur sebelum dan sesudah dengan `adb shell dumpsys batterystats --reset`, satu jendela aplikasi terbuka, layar mati 30 menit, lalu `dumpsys batterystats` dan `dumpsys power`.
3. Jank UI: P1-9 sampai P1-12, lalu P1-5 sampai P1-8 dan P1-16 sampai P1-19. Ukur dengan `adb shell dumpsys gfxinfo com.lxlauncher` sesudah drag jendela 20 kali dan mengetik di Start Menu.
4. File Explorer: P1-13 sampai P1-15, P2-5, P2-6. Uji dengan folder 10 ribu berkas.
5. RAM: P2-1 sampai P2-4. Ukur dengan `adb shell dumpsys meminfo com.lxlauncher` setelah membuka Start Menu, "Semua", dan menggulir sampai bawah.
6. Ukuran APK: P2-7 sampai P2-11. Bandingkan ukuran APK release dan `.so` sebelum dan sesudah.
7. Kompatibilitas dan layar: P2-12 sampai P2-16, diuji di Infinix (720x1600), mode landscape dengan navigasi 3 tombol, dan emulator API 26.
8. P3 sesuai waktu yang tersisa.

Setelah setiap batch: `scripts/test-host.sh` (termasuk ASan dan UBSan), `scripts/build.sh` (lint, debug, release), lalu uji di Infinix sesuai `docs/testing/`.

## Status pengerjaan, 7 November 2026

Dikerjakan sesuai `PROMPT_PEMELIHARAAN_LX_LAUNCHER.md`. Bukti, perintah, dan angka sebelum dan sesudah ada di `docs/reports/engineering-report-2026-11-07.md`. PASS berarti perbaikan ada di kode dan lolos uji yang disebut di laporan; NOT TESTED berarti jalur itu belum dicoba di perangkat.

| # | Status | Catatan |
|---|---|---|
| P0-1 | PASS | Salinan di DCIM, lalu Pictures, yang disimpan; ukuran dan SHA-256 kedua berkas diperiksa ulang tepat sebelum menghapus. `createTrashRequest` DITUNDA: ruang baru lega setelah 30 hari dan Android menampilkan dialog sendiri, keputusan pemilik |
| P0-2 | PASS | `removePackage` per profil di core, JNI, dan `AppRepository` |
| P0-3 | PASS | Diuji di Infinix: receiver LX tetap 3 setelah enam kali ganti tema |
| P0-4 | PASS | `BitmapSample` untuk pratinjau, thumbnail, dan wallpaper. Gambar 1080x25000 sungguhan di Properti: NOT TESTED |
| P0-5 | PASS | Guard SDK menggantikan `@TargetApi`, layanan mati sebelum Android 14 lewat `@bool/bridge_supported`. Android 8 sampai 13: NOT TESTED (tidak ada emulator) |
| P0-6 | PASS | `ConflictDesk` di tingkat proses. Alur UI ganti tema saat dialog konflik terbuka: NOT TESTED |
| P0-7 | PASS | `.bak` dicoba, berkas yang tidak terbaca disisihkan sebagai `.unusable`, batas parse sama dengan batas Store |
| P1-1 | PASS | Poll 400 ms hanya saat aplikasi datang, 3 detik sesudahnya, ditambah event `onTaskRemoved` |
| P1-2 | PASS | Orientasi dari event task; dump hanya sekali, saat jendela tampil lagi, atau tanpa listener |
| P1-3 | DITUNDA | Wake lock itu yang membuat aplikasi tetap berjalan saat jendelanya tersembunyi atau layar mati (README, CHANGELOG). Melepasnya mengurangi fitur; keputusan pemilik |
| P1-4 | PASS | Start hanya saat LX di depan; tanpa jendela yang menunggu, berhenti setelah 3 kali gagal sampai jaringan berganti. Uji Wi-Fi di Infinix: NOT TESTED |
| P1-5 | PASS | Diuji di Infinix: receiver LX 0 saat aplikasi lain di depan |
| P1-6 | PASS | `AudioPlaybackCallback`. Widget media di perangkat: NOT TESTED |
| P1-7 | PASS | PSS hanya di Monitor, di thread sendiri |
| P1-8 | PASS | Hasil pindaian dipakai ulang 2 menit, `walkFileTree`, hash 64 KB awal dulu |
| P1-9 | PASS | Berkas layout ditulis di luar kunci engine |
| P1-10 | PASS | `LxTheme` dipakai ulang selama setelan sama; dock, taskbar, dan panel atas tidak dibangun ulang tanpa perubahan |
| P1-11 | SEBAGIAN | Pencarian ditunda 90 ms, kategori di-cache, baris dibuat per 40. Kolom dengan recycling (ListView) DITUNDA: risiko pada navigasi keyboard |
| P1-12 | PASS | Saat menggeser hanya bingkai itu yang dipindah |
| P1-13 | PASS | Urut di thread sendiri, pembanding tanpa `substring`, `key` dan `extension` dihitung sekali |
| P1-14 | PASS | Executor sendiri, batal saat sheet ditutup atau folder berganti |
| P1-15 | PASS | Antrean dikosongkan per folder, gagal decode diingat, hasil hanya ke view yang cocok |
| P1-16 | PASS | Bingkai widget dua workspace terakhir disimpan, info widget Android di-cache. Uji di perangkat: NOT TESTED |
| P1-17 | SEBAGIAN | Fokus jendela tidak lagi memicu tulis disk. Menulis JSON langsung tanpa pohon `json::Value`: DITUNDA |
| P1-18 | PASS | Executor serial dengan nomor generasi, debounce 300 ms |
| P1-19 | PASS | Diuji di Infinix: receiver widget lepas saat Pengaturan LX dimaksimalkan |
| P2-1 | PASS | Bitmap per ukuran, LruCache dalam byte, `onTrimMemory` |
| P2-2 | PASS | TextureView dilepas setelah 5 detik tersembunyi. Infinix: PSS turun 9,0 MB 8 detik setelah diperkecil, isi tampil lagi dalam 1,5 detik |
| P2-3 | PASS | Kode listener dibaca saat `Android::open`, lalu jar ditutup |
| P2-4 | PASS | Cache gambar tajam terpisah dari salinan blur |
| P2-5 | PASS | Satu buffer per job; `transferTo` per 4 MiB antara dua berkas biasa. Salin di perangkat: NOT TESTED |
| P2-6 | DITUNDA | Mengubah struktur rencana salin; risiko keselamatan data lebih besar dari manfaatnya sekarang |
| P2-7 | PASS | WebP q90 lewat `scripts/make-wallpaper-webp.py`; nama ref `builtin:` tidak berubah, jadi tidak perlu migrasi |
| P2-8 | DITUNDA | Subset font yang punya Reserved Font Name (misalnya IBM Plex, Source Sans 3) wajib berganti nama menurut OFL 1.1; keputusan pemilik |
| P2-9 | DITUNDA | Memisah ABI mengubah berkas rilis dan dukungan x86_64; keputusan pemilik |
| P2-10 | PASS | Visibilitas hidden untuk core, `--exclude-libs,ALL`, `--icf=safe` |
| P2-11 | PASS | Angka asal float ditulis dengan `to_chars(float)` |
| P2-12 | PASS | Android 14 ke atas lewat `lxdb` (`am kill`), satu perintah; tanpa `lxdb` jumlahnya 0. Sapuan di Infinix: NOT TESTED (menghapus berkas pemilik) |
| P2-13 | PASS | `configChanges` ditambah; `onDestroy` membersihkan jendela, overlay, widget, dan panel |
| P2-14 | PASS | Inset kiri dan kanan untuk overlay; kartu Ringkasan dan sel folder menyempit agar muat. Infinix 360 dp: Ringkasan muat utuh. Mendatar: NOT TESTED |
| P2-15 | PASS | Tinggi keyboard dari area terlihat sebelum Android 11. API 26 sampai 29: NOT TESTED (tidak ada emulator) |
| P2-16 | DITUNDA | Tidak berdampak selama `targetSdk` 35; dikerjakan bersama kenaikan `targetSdk`, yang butuh persetujuan pemilik |
| P3-3 | PASS | `validate::clampedInt` sebelum cast |
| P3-7 | PASS | Kunci di-cache per proses, ditulis lewat temp, fsync, rename; yang tak terbaca disisihkan |
| P3-8 | PASS | Socket ditutup untuk kegagalan apa pun |
| P3-9 | PASS | Log LX Bridge ditulis di thread sendiri |
| P3-15 | PASS | Prune wallpaper di thread `io`; pratinjau Ringkasan digambar sekali |
| P3-1, P3-2, P3-4, P3-5, P3-6, P3-10 sampai P3-14 | DITUNDA | Batas waktu batch P3; tidak ada yang menyangkut data pengguna |

### Temuan baru

| # | Prioritas | Lokasi | Masalah dan dampak | Status |
|---|---|---|---|---|
| N-1 | P2 | `tools/CleanupTool.kt` (`Orb.onDraw`) | Empat `RadialGradient` dibuat di setiap frame selama bola berputar; lint melapor 8 `DrawAllocation` | PASS: shader dibuat sekali per ukuran |
| N-2 | P1 | `files/LocalBackend.kt` (`children`) | Membuka folder 10.000 berkas butuh 18 sampai 21 detik di Infinix; per berkas ada `access()` lewat FUSE dan `mounts()` | PASS: `mounts()` sekali per folder, izin tulis berkas biasa diturunkan dari foldernya. Infinix: 5,0 sampai 7,1 detik |
| N-3 | P3 | `ui/overlays/MoreOverlays.kt` (`drawThumbnail`) | Pratinjau Ringkasan workspace untuk wallpaper foto bawaan selalu menggambar "skyline", bukan fotonya | Belum dikerjakan |
