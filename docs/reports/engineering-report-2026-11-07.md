# Laporan engineering LX-LAUNCHER, 7 November 2026

Laporan ini menjawab `PROMPT_PEMELIHARAAN_LX_LAUNCHER.md` dan `PROMPT_REDESAIN_LX_LAUNCHER.txt`. Backlog-nya `docs/audits/maintenance-audit-2026-11-07.md`; status setiap temuan juga dicatat di sana. Semua uji perangkat dijalankan di Infinix HOT 60 5G (X6726B, Android 15, arm64, 720x1600 pada 320 dpi, bahasa Indonesia). Waydroid tidak dipakai. Emulator API 26 dan API 30 tidak tersedia di mesin pengembang (tidak ada system image), jadi jalur Android 8 sampai 13 ditulis NOT TESTED.

Keputusan pemilik untuk pekerjaan ini (7 November 2026): mode antislop "during"; perubahan visual boleh tanpa bertanya asal fungsi, teks UI, dan alur pengaturan tetap; lanjut otomatis antar-batch; `pm clear` dan skrip uji lama boleh dipakai di Infinix dengan cadangan `run-as` dulu. `pm clear` akhirnya tidak dipakai sama sekali.

## Ringkasan

| Bagian | Isi |
|---|---|
| Baseline | LX-LAUNCHER 0.1.1 (versionCode 2), APK terpasang 7 November 02.25, disimpan di `build/backup/lx-installed-before-maint.apk` dan dipakai sebagai pembanding semua angka "sebelum" |
| Build | PASS. `scripts/build.sh`: lint 0 error (sebelumnya 0 error, 14 warning; sekarang 6 warning, delapan `DrawAllocation` hilang), debug, release R8, APK test |
| Uji host | PASS. `scripts/test-host.sh`: 223 lulus tanpa sanitizer, 222 lulus dan 1 dilewati sesuai rancangan dengan ASan dan UBSan (sebelumnya 213 dan 212) |
| Instrumentation | PASS. 47 lulus, 0 gagal, termasuk 7 check baru untuk audit ini (`-e only maintenance`) |
| Ukuran APK | Release 18.404.320 menjadi 7.459.175 byte (minus 59,5%) |
| Temuan selesai | P0: 7 dari 7. P1: 16 penuh, 2 sebagian, 1 ditunda. P2: 12 dari 16, 4 ditunda. P3: 5 dari 15. Temuan baru: 2, keduanya selesai |
| Ditunda dan alasannya | Lihat bagian Keputusan pemilik dan tabel status di laporan audit |

## Cara kerja

Setiap temuan dibaca ulang di source sebelum diubah; semua baris yang dirujuk audit masih cocok. Untuk perubahan core C++ test host ditulis lebih dulu dan dijalankan sebelum perbaikan: `DesktopTest.UninstallInOneProfileKeepsTheOtherProfilesApp` gagal kompilasi karena API per profil belum ada, `Engine.UsableBackupSurvivesAMainFileThatDoesNotLoad`, `Engine.BackupThatDoesNotLoadIsSetAsideToo`, dan `Engine.LayoutsUpToTheStoreLimitLoad` gagal (tema kembali ke `palette`), lalu lulus sesudah perbaikan. Logika Kotlin diuji lewat instrumentation di `MaintenanceChecks.kt`.

Graph codebase-memory dipakai untuk mencari simbol dan pemanggilnya sebelum menyunting. Repositori ini bukan repo Git, jadi `detect_changes` tidak bisa dipakai; dampak dicek lewat `trace_path`, grep pemanggil, dan kompilasi.

Batch 1 sampai 7 diuji di perangkat pada satu build yang sama (`build/dist` 7 November 04.31), karena kode batch berikutnya sudah ditulis saat build batch sebelumnya berjalan. Gerbang tiap batch (uji host, lint, uji perangkat, angka sebelum dan sesudah) tetap diperiksa per temuan.

## Batch 1, P0: risiko data, crash, dan kebocoran

| # | Perubahan | Bukti | Status | Risiko tersisa |
|---|---|---|---|---|
| P0-1 | Salinan yang disimpan dipilih dari DCIM, lalu Pictures, lalu yang tertua dan path terpendek (`Duplicates.keeper`). Sebelum menghapus, ukuran dan SHA-256 berkas dan salinan yang disimpan dibaca ulang (`Duplicates.stillSafeToDelete`) | Instrumentation: "the camera original stays", "a copy is deleted only while both files still match the scan" PASS | PASS | Sapuan sungguhan tidak dijalankan di Infinix karena menghapus berkas pemilik. Trash Android 11+ DITUNDA |
| P0-2 | `Desktop::removePackage(package, userSerial)`, JNI `removePackage(handle, pkg, userSerial)`, `PackageEvent.Removed` membawa serial profil | Host: `DesktopTest.UninstallInOneProfileKeepsTheOtherProfilesApp`, `...WithoutAProfileStillRemovesEveryCopy`. Instrumentation lewat JNI PASS | PASS | Uninstall sungguhan di work profile: NOT TESTED |
| P0-3 | `LxWidget` hanya berjalan selama diminta dan terpasang di window; `rebuildUi` mematikan widget dan panel lama | Infinix, workspace dengan widget jam dan status: receiver terdaftar 3, tetap 3 setelah enam kali ganti tema Graphite Mono dan Arctic Glass (`build/maint/receivers.sh`, bagian "Registered Receivers") | PASS | Angka sebelum di perangkat tidak diukur; kebocoran lama berdasarkan pembacaan source audit |
| P0-4 | `BitmapSample.sampleFor` dengan batas piksel dan sisi: pratinjau 4 MP dan 4096 px, thumbnail 1 MP, wallpaper 32 MP | Instrumentation: 1080x25000 untuk pratinjau 640 px menjadi 135x3125; foto 4000x3000 untuk layar ponsel tetap sample 1 seperti sebelumnya | PASS | Gambar 1080x25000 sungguhan di Properti: NOT TESTED |
| P0-5 | `AdbPairing.pair` diberi guard `SDK_INT < UPSIDE_DOWN_CAKE` dan `@TargetApi`/`@SuppressLint` dibuang; `BridgeSetupService` `android:enabled="@bool/bridge_supported"` (true hanya di `values-v34`) dan mematikan diri di bawah Android 14. Tiga `@SuppressLint("InlinedApi")` lain diganti guard | Lint 0 error; Infinix: LX Bridge tetap menyala dan `lxdb` tersambung 6 detik setelah instal | PASS | Android 8 sampai 13: NOT TESTED |
| P0-6 | `ConflictDesk` di tingkat proses: job menunggu, pertanyaan pindah ke jendela File Explorer berikutnya, tap di sheet lama diabaikan, Batal di baris progres mengakhiri tunggu | Instrumentation: dua check P0-6 PASS | PASS | Alur ganti tema saat dialog konflik terbuka di UI: NOT TESTED |
| P0-7 | Berkas utama yang lolos CRC tapi tidak terbaca disisihkan sebagai `layout.lxl.unusable` dan `.bak` dicoba; `.bak` yang juga tidak terbaca menjadi `layout.lxl.bak.unusable`; parse memakai batas 4 MB milik Store | Host: tiga test Engine di atas gagal sebelum dan lulus sesudah perbaikan | PASS | Tidak ada pemulihan otomatis dari berkas `.unusable` kalau versi baru dipasang lagi |

## Batch 2: baterai `lxdb` dan LX Bridge

| # | Perubahan | Bukti | Status | Risiko tersisa |
|---|---|---|---|---|
| P1-1 | Jadwal poll per display: 400 ms saat aplikasi datang dan saat memastikan display kosong, 3 detik saat aplikasi berjalan (`pollDelay`); `onTaskRemoved` membangunkan watcher | Host: `LxdbRules.RunningAppsArePolledSlowly`. Infinix: CPU `lxdb` sendiri per 300 detik turun dari 1,61 ke 0,09 s (terlihat) dan dari 1,65 ke 0,10 s (diperkecil) | PASS | Menutup aplikasi dari dalam aplikasi itu sendiri sekarang terdeteksi lewat event; tanpa event (framework lain) paling lambat 3,4 detik |
| P1-2 | Orientasi dibaca dari argumen `onTaskRequestedOrientationChanged` dan `onActivityRequestedOrientationChanged` (`orientationOf`); dump hanya sekali per task, saat jendela tampil lagi, atau tanpa listener, dengan backoff yang tidak lagi kembali ke 1,5 detik; dump tidak dibaca saat surface null | Host: `TaskEventOrientationMatchesTheDumpWords`, `OrientationReadsBackOffEvenWhileHeld`. Log Infinix: "display 29 asks for orientation 1" 300 ms setelah start | PASS | |
| P1-3 | Tidak diubah | Wake lock itu yang membuat aplikasi tetap jalan saat jendela tersembunyi atau layar mati, fitur yang tertulis di README | DITUNDA | Keputusan pemilik |
| P1-4 | `keepBridgeUp` hanya saat LX di depan; tanpa jendela yang menunggu, berhenti setelah 3 start gagal sampai Wi-Fi berganti atau jendela meminta (`withService`) | Kompilasi dan uji jalan normal setelah instal | PASS | Uji Wi-Fi di latar belakang: NOT TESTED, supaya Wi-Fi pemilik tidak diganggu |

Pengukuran baterai yang diminta standar (satu jendela aplikasi, layar mati 30 menit, `dumpsys batterystats` dan `dumpsys power`) tidak bisa dijalankan tanpa pemilik: ponsel uji berkunci PIN begitu layar mati, dan uji UI berikutnya tertahan. Sebagai gantinya dipakai ukuran yang bisa diulang: waktu CPU `lxdb` sendiri dan proses anaknya (`dumpsys`, dari `/proc/<pid>/stat` kolom 14 sampai 17) selama 300 detik dengan Kalkulator di jendela LX, terlihat lalu diperkecil (`build/maint/lxdb-cpu2.sh`).

| Kalkulator di jendela LX, 300 detik | `lxdb` sendiri sebelum | sesudah | `dumpsys` (proses anak) sebelum | sesudah |
|---|---|---|---|---|
| Jendela terlihat | 1,61 s | 0,09 s | 7,99 s | 0,00 s |
| Jendela diperkecil | 1,65 s | 0,10 s | 8,17 s | 0,00 s |

Run pembanding pertama (jendela di workspace lain) memberi angka yang sama: 1,69 s dan 8,13 s. Di build lama `lxdb` beserta `dumpsys`-nya memakai sekitar 3,2% satu core terus-menerus selama Kalkulator terbuka, terlihat atau tidak; di build baru 0,03%. CPU proses LX sendiri dengan jendela terlihat 6,40 s sebelum dan 7,87 s sesudah; build baru baru saja dipasang dan belum dikompilasi ART, jadi angka itu tidak dibandingkan. Diperkecil: 0,10 s dan 0,09 s. Uji `batterystats` dan `dumpsys power` 30 menit layar mati: NOT TESTED.

## Batch 3: jank UI

| # | Perubahan | Status |
|---|---|---|
| P1-9 | `save` di JNI: kunci engine hanya selama `exportDocument`; tulis berkas di luar kunci, satu penyimpanan pada satu waktu | PASS |
| P1-10 | `LxTheme` dibuat ulang hanya saat setelan, konfigurasi, atau sakelar animasi Android berubah; `glass.update` ikut; `TopPanel`, `DockView`, `TaskbarView` melewatkan bind dengan masukan yang sama | PASS |
| P1-11 | Pencarian ditunda 90 ms, kategori di-cache, baris aplikasi dibuat per 40. Recycling DITUNDA | SEBAGIAN |
| P1-12 | Saat menggeser, bingkai digeser dengan `offsetLeftAndRight`/`offsetTopAndBottom`; `RectF`, `IntArray`, dan setelan snap dipakai ulang | PASS |
| P1-5 | Receiver panel atas aktif hanya antara `onStart` dan `onStop`; baterai dibaca dari Intent yang diterima; glyph dibuat ulang hanya saat status mengisi berubah | PASS, Infinix: receiver LX 3 saat di depan, 0 saat Kalkulator di depan, 3 lagi saat kembali |
| P1-6 | `MediaWidget` memakai `AudioPlaybackCallback` | PASS, widget media di perangkat NOT TESTED |
| P1-7 | `SystemInfo.memory` tanpa PSS; Monitor membaca `ownPssKb` di thread sendiri | PASS |
| P1-8 | Pindaian dipakai ulang 2 menit; `Files.walkFileTree`; hash 64 KB awal sebelum hash penuh | PASS |
| P1-16 | Bingkai widget dua workspace terakhir disimpan GONE; info widget Android di-cache | PASS, NOT TESTED dengan widget Android |
| P1-17 | Fokus jendela memakai `model.front`, tanpa tulis disk | SEBAGIAN |
| P1-18 | Reload daftar aplikasi serial dengan nomor generasi, debounce 300 ms | PASS |
| P1-19 | Desktop INVISIBLE dan widget berhenti saat jendela maksimal atau layar penuh di workspace aktif | PASS, Infinix: receiver widget lepas saat Pengaturan LX dimaksimalkan |
| N-1 | Shader `Orb` di Pembersih dibuat sekali per ukuran | PASS, lint: delapan `DrawAllocation` hilang |

`adb shell dumpsys gfxinfo com.lxlauncher`, tiga run per build, dari reset per skenario (`build/maint/jank-menu.sh`, `jank-drag.sh`). Kondisi sama di kedua build: workspace 2, tanpa jendela LX lain.

| Skenario | Sebelum: janky / p90 / p95 | Sesudah: janky / p90 / p95 |
|---|---|---|
| Mengetik 3 x 10 huruf di menu Aplikasi | 12,60% / 32 / 38 ms; 12,14% / 32 / 40 ms; 11,98% / 30 / 36 ms | 10,97% / 34 / 44 ms; 11,53% / 26 / 38 ms; 11,54% / 30 / 38 ms |
| Buka "Semua aplikasi" dan 12 gesekan | 1,33% / 9 / 10 ms; 1,68% / 9 / 10 ms; 1,56% / 9 / 10 ms | 1,56% / 8 / 9 ms; 1,67% / 8 / 9 ms; 1,78% / 8 / 9 ms |
| Geser jendela Kalkulator 20 kali | 5,61% / 17 / 19 ms; 5,65% / 17 / 20 ms; 4,98% / 17 / 20 ms | 3,27% / 10 / 14 ms; 3,05% / 11 / 15 ms; 3,56% / 11 / 15 ms |

Mengetik: median janky turun dari 12,14% ke 11,53%, median p90 dari 32 ke 30 ms, p95 sama (38 ms). Gulir menu: p90 dan p95 turun 1 ms di ketiga run; persentase janky berada di rentang yang sama (1,33 sampai 1,68% sebelum, 1,56 sampai 1,78% sesudah). Geser jendela: median janky turun dari 5,61% ke 3,27%, p90 dari 17 ke 11 ms, p95 dari 20 ke 15 ms.

Dua kesalahan pengukuran ditemukan dan dibuang: satu set run "sesudah" berjalan dengan jendela File Explorer dari pengukuran pembanding masih terbuka, dan satu run drag membuka Kalkulator di luar jendela LX. Skrip kini memeriksa kedua hal itu.

## Batch 4: File Explorer untuk folder besar

| # | Perubahan | Status |
|---|---|---|
| P1-13 | Daftar di atas 300 entri diurutkan di thread `lx-fm-sort`, urutan basi dibuang; pembanding nama tanpa `substring` dan hasilnya identik dengan versi lama pada 20.000 pasangan acak; `key`, `extension`, `hidden` dihitung sekali | PASS |
| P1-14 | Hitung ukuran folder di executor `lx-fm-walk`, batal saat sheet ditutup, folder berganti, atau jendela ditutup | PASS |
| P1-15 | Antrean thumbnail dikosongkan per folder, view yang sudah lewat dilewati, gagal decode diingat, hasil ke view yang cocok tanpa `notifyDataSetChanged`, thread prioritas background | PASS |
| P2-5 | Satu buffer per job; `transferTo` per 4 MiB antara dua berkas biasa (diperiksa dengan `fstat`), selain itu buffer | PASS, salin di perangkat NOT TESTED |
| P2-6 | Tidak diubah | DITUNDA |
| N-2 | `LocalBackend.children`: `mounts()` sekali per folder, izin tulis berkas biasa diturunkan dari folder yang bisa ditulis | PASS |

Folder uji `/sdcard/Documents/LXFM-10k` berisi 10.000 berkas teks, dihapus sesudah uji. Waktu dari ketuk folder sampai "10000 item" tampil (dipolling dengan uiautomator, sekitar 2 detik per poll, `build/maint/fe-time.sh`):

| | Sebelum | Sesudah |
|---|---|---|
| Ketuk folder sampai 10.000 berkas tampil | 20,7 s; 18,3 s | 5,0 s; 7,1 s; 7,1 s |
| Frame 8 detik pertama sesudah ketuk | 418 frame, p50 27 ms (masih Memuat) | 113 frame, janky 0,88%, p90 11 ms, p99 57 ms |
| Gulir 10 kali | tidak bisa dibandingkan: folder masih Memuat | janky 1,17% dan 1,44%, p90 10 dan 13 ms, p95 12 dan 13 ms |

Urutan nama tetap alami (berkas 1, berkas 2, ... berkas 10). Folder 300+ aplikasi tidak bisa dibuat: ponsel uji punya 110 aplikasi di menu.

## Batch 5: RAM

| # | Perubahan | Status |
|---|---|---|
| P2-1 | Ikon dirender ke bitmap per kelipatan 24 px, `LruCache` dalam byte (seperdelapan heap, paling besar 24 MB), setiap view mendapat `BitmapDrawable` sendiri, `trimMemory` membuang separuh di `TRIM_MEMORY_BACKGROUND` dan semua di `TRIM_MEMORY_MODERATE` | PASS |
| P2-2 | TextureView jendela aplikasi dilepas setelah 5 detik tersembunyi dan dipasang lagi saat tampil | PASS, Infinix: 8 detik setelah jendela Kalkulator diperkecil, PSS LX turun 9,0 MB (Graphics 9,1 MB); isi aplikasi tampil lagi dalam 1,5 detik setelah dipulihkan |
| P2-3 | Kode `ITaskStackListener` dibaca saat `Android::open`, lalu `Jar::close` melepas dex yang di-inflate dan munmap | PASS, `lxdb` naik dan tersambung di Infinix |
| P2-4 | Cache gambar tajam (2 entri, worker thread) terpisah dari salinan blur | PASS |

`adb shell dumpsys meminfo com.lxlauncher` pada skenario audit: proses segar (force-stop lalu Home), menu Aplikasi, "Semua aplikasi", gulir sampai bawah. APK lama dipasang ulang sementara untuk run "sebelum", di kondisi ponsel yang sama.

| | Sebelum | Sesudah |
|---|---|---|
| PSS 10 detik setelah start | 267,7 dan 258,1 MB | 256,1 dan 254,1 MB |
| PSS setelah menu digulir | 306,8 dan 336,2 MB | 201,0 dan 197,6 MB |
| Native heap setelah menu | 103,1 dan 134,9 MB | 48,8 dan 48,7 MB |
| Graphics setelah menu | 104,5 dan 104,3 MB | 63,3 dan 63,7 MB |

RSS `lxdb` sesudah perubahan 2,9 MB; angka sebelum tidak dicatat sebelum daemon lama berhenti. Angka idle 154,3 MB di `build/maint/baseline-0.txt` diambil dari proses yang sudah lama berjalan dan tidak dipakai sebagai pembanding.

## Batch 6: ukuran APK

| Berkas | Sebelum | Sesudah |
|---|---|---|
| APK release (unsigned) | 18.404.320 | 7.459.175 |
| APK debug | 26.336.788 | 11.656.556 |
| `liblxnative.so` arm64-v8a | 935.168 | 729.336 |
| `liblxnative.so` x86_64 | 946.400 | 747.856 |
| `liblxdb.so` arm64-v8a | 358.288 | 362.496 |
| `liblxdb.so` x86_64 | 352.920 | 357.528 |
| Tujuh wallpaper bawaan | 12.987.744 | 2.421.056 |

`liblxdb.so` naik 4 KB karena jadwal poll, event orientasi, dan `Jar::close`. Wallpaper (P2-7) dibuat ulang oleh `scripts/make-wallpaper-webp.py` dari sumber di `wallpaper/`: PSNR 40,94 dB (violet_grid) sampai 46,77 dB (prism_shards); blok 64x64 terburuk 34,82 dB. Di layar Infinix, wallpaper foto bawaan yang dipakai pemilik dibandingkan sebelum dan sesudah di area desktop: PSNR 39,16 dB, selisih rata-rata 1,5 tingkat warna; potongan yang diperbesar 1,5 kali tidak bisa dibedakan (`build/maint/wallpaper-compare.png`).

P2-10: `CMAKE_CXX_VISIBILITY_PRESET hidden` dan `CMAKE_VISIBILITY_INLINES_HIDDEN` untuk `lxnative` dan core, `-Wl,--exclude-libs,ALL`, `-Wl,--icf=safe`. LTO tidak dipakai. P2-11: angka asal float ditulis dengan `to_chars(float)`; test host `Json.FloatsWidenedToDoubleWriteShort`, dan `Engine.ExportImportRoundTrip` tetap lulus. P2-8 dan P2-9 DITUNDA.

## Batch 7: kompatibilitas dan tata letak layar

| # | Perubahan | Status |
|---|---|---|
| P2-12 | Android 14 ke atas: satu perintah `am kill` untuk semua paket lewat `EmbedClient.shell`, nama paket divalidasi regex; tanpa `lxdb` hasilnya 0. Android 13 ke bawah tetap `killBackgroundProcesses` | PASS, sapuan di perangkat NOT TESTED |
| P2-13 | `configChanges` ditambah `fontWeightAdjustment`, `colorMode`, `mcc`, `mnc`, `touchscreen`, `grammaticalGender`; rebuild untuk teks tebal dan mode warna; `onDestroy` menutup overlay, jendela, widget, panel atas, listener keyboard; thread `io` berhenti sendiri setelah 60 detik menganggur | PASS, ganti Teks tebal di perangkat NOT TESTED |
| P2-14 | Overlay memakai inset kiri dan kanan dari system bar dan cutout; kartu Ringkasan dan sel folder menyempit agar muat | PASS, Infinix 360 dp: Ringkasan workspace dua kolom muat utuh dan baris hitungan turun ke dua baris (`build/maint/overview-final.png`). Mendatar dengan navigasi 3 tombol dan ukuran antarmuka 130%: NOT TESTED |
| P2-15 | Sebelum Android 11, tinggi keyboard dari `getWindowVisibleDisplayFrame` lewat `OnGlobalLayoutListener` di decor view | PASS, API 26 sampai 29 NOT TESTED |
| P2-16 | Tidak diubah | DITUNDA |

## Batch 8: P3

| # | Perubahan | Status |
|---|---|---|
| P3-3 | `validate::clampedInt` untuk `version`, `cols`, `rows`, `col`, `row`; `appWidgetId` negatif menjadi -1 sebelum cast. Test host `Engine.HugeNumbersInAFileAreClampedNotCast` di bawah UBSan | PASS |
| P3-7 | Kunci LX Bridge di-cache per proses, ditulis lewat `.tmp`, fsync, rename; yang tak terbaca disisihkan ke `.unreadable`; konteks TLS dibuat sekali per kunci | PASS |
| P3-8 | Socket ditutup untuk `Throwable` apa pun di `AdbLink` dan `AdbPairing` | PASS |
| P3-9 | `BridgeLog` menulis di thread `lx-bridge-log` | PASS |
| P3-15 | Prune wallpaper di thread `io`; pratinjau Ringkasan digambar sekali per wallpaper | PASS |

P3-1, P3-2, P3-4, P3-5, P3-6, dan P3-10 sampai P3-14 DITUNDA karena waktu; tidak satu pun menyangkut data pengguna.

## PROMPT_REDESAIN

Isi prompt redesain sudah ada di build 0.1.1 dan diperiksa ulang di Infinix, tidak dibangun ulang: sepuluh tema dengan pratinjau dan penanda Dipakai berupa tepi, centang, dan teks (screenshot `build/maint/after-theme.png`), sepuluh font di Pengaturan LX > Tampilan, ikon Vivid untuk folder, berkas, dan alat LX dengan lisensi GPL-3.0 di `NOTICE`, kontras tinggi, dan gerak yang mengikuti animasi Android. Pekerjaan ini menambah satu hal yang diminta redesain: ringkasan workspace dan popup folder kini muat di ponsel 360 dp dan menghormati cutout serta navigasi samping (P2-14). DESIGN.md hanya diperbarui untuk nama File Explorer.

## Keputusan pemilik

- P0-1 trash Android 11+: hapus lewat `MediaStore.createTrashRequest` bisa dipulihkan, tetapi ruangnya baru lega setelah 30 hari dan Android menampilkan dialog konfirmasinya sendiri.
- P1-3: melepas wake lock saat jendela tersembunyi menghemat baterai, tetapi aplikasi di jendela itu berhenti berjalan, berlawanan dengan yang dijanjikan README.
- P2-8: subset font menghemat sekitar 1,5 MB; beberapa keluarga (misalnya IBM Plex, Source Sans 3) punya Reserved Font Name, jadi subsetnya harus berganti nama menurut OFL 1.1.
- P2-9: memisah APK per ABI menghemat sekitar 1,3 MB untuk ponsel, tetapi mengubah berkas rilis dan dukungan Waydroid serta emulator x86_64.
- P2-16: perlu kenaikan `targetSdk` ke 36.
- Uji baterai 30 menit layar mati: butuh pemilik membuka kunci ponsel sesudahnya.

## Lingkungan uji dan pembersihan

Data LX dicadangkan dengan `run-as` sebelum uji, pukul 03.27 dan 04.32 (`build/backup/`). `pm clear` tidak dipakai. Folder uji `LXFM-10k` dan berkas dump uiautomator dihapus; APK test dicopot. Tata letak dan preferensi File Explorer dikembalikan ke cadangan 03.27 (md5 sama); status LX Bridge dibiarkan supaya sambungan ke `lxdb` tidak putus. LX tetap Home, `lxdb` berjalan, dan jendela aplikasi pemilik dibuka lagi oleh LX seperti sebelum sesi. Akses semua file sudah diizinkan sebelum sesi dan tidak diubah. Kalkulator (aplikasi uji) dibuka dan ditutup beberapa kali; satu ketukan uji mengenai tombolnya, jadi angka di layarnya berubah. Ukuran dan density layar tidak diubah.

Skrip pengukuran ada di `build/maint/` (`jank-menu.sh`, `jank-drag.sh`, `lxdb-cpu2.sh`, `fe.sh`, `fe-time.sh`, `ram.sh`, `receivers.sh`, `ui.py`) bersama keluaran mentahnya. Dua jebakan yang ditemukan saat mengukur: selama jendela aplikasi memegang fokus, huruf yang diketik dan dump uiautomator jatuh ke aplikasi itu, bukan ke LX; dan menekan Home saat LX sudah di Home menutup overlay, sehingga ketukan berikutnya bisa meleset.
