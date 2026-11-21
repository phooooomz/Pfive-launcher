# Laporan engineering LX-LAUNCHER, 28 Oktober 2026

Laporan ini menjawab master prompt di `readme.md`, dengan urutan audit, analisis, desain, implementasi, verifikasi, test, optimasi, regresi, dokumentasi, dan penilaian rilis. Proyek yang dikerjakan tetap LX-LAUNCHER yang sudah ada: Kotlin Android View di atas core C++20 lewat JNI. Tidak ada proyek pengganti. Sebelum ada perubahan, source disimpan di `~/Desktop/lxLauncher-snapshots/lxLauncher-before-masterprompt-2026-10-28.tar.gz`.

## A. Ringkasan

Prioritas pertama brief adalah gambar referensi yang tampil sebagai wallpaper. Akar masalahnya ada di skrip uji, bukan di aplikasi. APK tidak pernah memuat gambar itu, dan desktop LX dibentuk oleh komponen native. Skrip uji memilih `referensi.png` sebagai wallpaper lewat pemilih foto dan tidak pernah mengembalikannya, sehingga gambar itu tertinggal di perangkat. Skrip sekarang memakai gambar uji buatannya sendiri, mengembalikan wallpaper ke tema, membersihkan data LX setelah setiap batch, dan gagal kalau APK memuat gambar referensi.

Selain itu, audit menemukan dan memperbaiki 21 masalah: tujuh dari audit awal, sembilan dari review independen atas perubahan, tiga dari pengujian, dan dua dari lint. Fitur yang ditambahkan:

- matriks kemampuan perangkat;
- operasi berkas yang aman beserta fitur yang sebelumnya hilang;
- jendela yang menghormati bilah sistem dan keyboard;
- rotasi tanpa membangun ulang tampilan;
- layar penuh (immersive);
- Alt+Tab.

Aplikasi Android dalam jendela terbukti bekerja di Waydroid: dua aplikasi terbuka bersamaan sebagai jendela freeform.

Status: VALIDATED ON TESTED DEVICES untuk Waydroid, REQUIRES DEVICE VERIFICATION untuk ponsel Infinix, yang tidak tersambung selama fase ini.

## B. Temuan audit dan akar masalah

| # | Temuan | Akar masalah | Perbaikan |
|---|---|---|---|
| 1 | Gambar referensi tampil sebagai wallpaper | `scripts/test-device.sh` menyalin `referensi.png` sebagai fixture, `ui_test_full.py` memilihnya, dan tidak ada langkah yang mengembalikan wallpaper atau membersihkan data | Gambar uji dari `scripts/make-test-wallpaper.py`, wallpaper dikembalikan ke tema, `pm clear` di akhir setiap batch, pemeriksaan isi APK |
| 2 | Rotasi menutup dialog dan pencarian, menghapus isi jendela, dan membocorkan tool lama | `onConfigurationChanged` selalu memanggil `rebuildUi()`, yang membuang jendela dengan `removeAllViews()` tanpa `onHidden`/`onDestroy` | Rebuild hanya untuk kepadatan, font, mode malam, dan bahasa; `WindowLayer.destroyAll()` mengakhiri lifecycle tool |
| 3 | Jendela layar penuh tertutup bilah status, navigasi, dan notch | Area layar penuh di core diisi seluruh layar | Area aman dari `systemBars()` dan `displayCutout()` |
| 4 | Keyboard menutup kolom teks di jendela | Viewport jendela tidak memperhitungkan `ime()` | Viewport jendela berhenti di atas keyboard; desktop tidak ikut bergeser |
| 5 | Pulihkan pada jendela layar penuh malah memaksimalkan | `toggleMaximize` hanya mengenali Maximized | Fullscreen juga kembali ke Floating; test `ToggleLeavesFullscreenToo` |
| 6 | Workspace tujuan tidak divalidasi | `moveToWorkspace` hanya menolak id 0 | `Engine::openWindow` dan `Engine::moveWindowToWorkspace` memeriksa workspace; test baru |
| 7 | Gulir dekat tepi isi jendela terbaca sebagai resize | Zona resize 10 dp berada di dalam isi (margin 1,5 dp) | Bingkai resize 8 dp di luar isi |
| 8 | Salinan yang gagal meninggalkan berkas setengah jadi; pindah tidak memeriksa salinan | `copyFile` tidak membersihkan dan tidak menghitung byte | Salinan dihitung per byte dan dihapus kalau tidak lengkap |
| 9 | Pindah folder bisa menghapus isi yang tidak tersalin | Daftar folder dibatasi 5000 entri | Salinan memakai daftar tanpa batas, menolak daftar yang masih dimuat provider, dan memeriksa ulang sumber sebelum menghapus |
| 10 | Launcher bisa crash kalau jendela Files ditutup saat operasi berjalan | Pekerjaan yang selesai belakangan mengirim tugas ke executor yang sudah dimatikan | Penanda `destroyed`, pemeriksaan sebelum setiap kiriman |
| 11 | Sentuhan di area kosong jendela tembus ke ikon desktop | Perubahan gerak di fase ini mengembalikan false untuk sentuhan di bawah bilah judul | Sentuhan itu ditelan oleh jendela |
| 12 | Crash saat keyboard muncul di jendela pendek | `coerceIn` dengan batas bawah di atas batas atas | Batas bawah dipotong |
| 13 | Rasio kanvas desktop ikut berubah saat keyboard terbuka | Rasio diambil dari viewport jendela | Rasio dikirim terpisah dari kanvas desktop |
| 14 | Menu tertinggal di posisi lama setelah rotasi | Menu menempel ke titik sentuh | Menu ditutup saat ukuran berubah |
| 15 | Tombol Lupakan ditawarkan untuk subfolder yang hilang | Semua kegagalan baca dianggap root hilang | Subfolder yang hilang menawarkan kembali ke atas |
| 16 | Operasi berkas kedua menyembunyikan tombol Batal yang pertama | Tidak ada pengecekan sedang bekerja | Operasi berkas eksklusif |
| 17 | Berkas tanpa ekstensi menjadi `.txt`; ganti nama yang gagal diumumkan berhasil | Tipe cadangan `text/plain`; hasil null diabaikan | `application/octet-stream`; hasil null menjadi galat |
| 18 | Pemeriksaan isi APK bisa lolos diam-diam | `grep -q` dengan `pipefail` | Daftar ditulis ke berkas dulu |
| 19 | `isChildDocument` dipanggil di bawah API 29 | Ditemukan lint; review independen keliru menyebut API 26 | Guard API 29 dan perbandingan id dokumen |
| 20 | Teks layar kosong LX Files menyarankan root Download | Android 11+ menolak grant root Download | Teks diganti |
| 21 | Navigasi dari hasil pencarian dan Refresh saat mencari keliru | Stack dan mode pencarian tidak dibangun ulang | Rantai folder disimpan di setiap hasil; Refresh mengulang pencarian |

Komponen UI yang masih berupa gambar statis: tidak ada. Satu-satunya bitmap besar adalah wallpaper, dan itu lapisan latar yang bisa diganti pengguna. Gambar ikon Vivid hanya dipakai untuk ikon.

## C. Perubahan arsitektur

- **Window manager.** Core menerima tiga area: area jendela (kanvas di atas keyboard), area aman untuk layar penuh, dan rasio kanvas desktop. Validasi workspace dipindah ke `Engine`, satu-satunya tempat yang tahu daftar workspace. Jendela aplikasi Android tetap SystemManagedWindow: LX hanya meminta batas lewat `ActivityOptions.setLaunchBounds` dan tidak pernah menggambarnya.
- **Rendering.** Rotasi dan perubahan ukuran memicu layout ulang. Panel dipasang ulang bila orientasi atau susunan ponsel berubah. Tampilan baru hanya dibangun untuk perubahan yang memang mengubah ukuran semua elemen.
- **Workspace.** Tidak berubah, kecuali validasi tujuan.
- **Integrasi Android.** `DeviceCapabilities` membaca kemampuan perangkat lewat API resmi dan menulisnya ke log `LxCaps`. Layar penuh memakai `WindowInsetsController`. Ikon bilah status mengikuti terang atau gelapnya wallpaper LX.
- **Izin.** Tidak ada izin baru. Akses kartu SD memakai `StorageVolume.createOpenDocumentTreeIntent()`, tetap lewat pemilih Android.

## D. Bukti implementasi

Berkas yang diubah:

- `app/src/main/kotlin/com/lxlauncher/LauncherActivity.kt`
- `ui/windows/WindowLayer.kt`
- `ui/overlays/OverlayHost.kt`
- `tools/FilesTool.kt`
- `tools/SettingsTool.kt`
- `system/DeviceCapabilities.kt` (baru)
- `core/NativeCore.kt`
- `app/src/main/cpp/jni_bridge.cpp`
- `core/include/lx/engine.hpp`
- `core/src/engine.cpp`
- `core/src/window_manager.cpp`
- `core/src/settings.cpp` (kunci `display.immersive`)
- teks Inggris dan Indonesia
- `tests/unit/engine_test.cpp`, `window_manager_test.cpp`, `settings_test.cpp`
- `scripts/test-device.sh`, `scripts/ui_test.py`, `ui_test_more.py`, `ui_test_full.py`
- `scripts/make-test-wallpaper.py` dan `scripts/bench-device.sh` (baru)

Kontrak setiap fitur, termasuk input, izin, lifecycle, penanganan galat, dan kriteria terima, ada di `docs/architecture/feature-contracts.md` (FC-01 sampai FC-07). Matriks izin ada di `docs/architecture/permissions.md`.

Audit dikerjakan tiga agen paralel: File Manager, window manager, serta izin dan insets. Satu agen lagi mereview diff secara independen. Semua temuan mereka dicocokkan ulang dengan source sebelum diperbaiki. Satu klaim reviewer, bahwa `isChildDocument` tersedia sejak API 26, ternyata salah, dan lint yang menangkapnya.

## E. Kompatibilitas perangkat

| | Waydroid | Infinix HOT 60 5G |
|---|---|---|
| Android | 13 (API 33), x86_64, 1920x1028 @180 | 15 (API 35), arm64, 720x1600 @320, XOS |
| Jendela LX | SUPPORTED, diuji | SUPPORTED, diuji di fase sebelumnya |
| Aplikasi dalam jendela | SUPPORTED: Clock dan Calculator `mode=freeform` bersamaan | CONDITIONAL: freeform tidak dilaporkan, aplikasi terbuka layar penuh |
| Layar terbagi | CONDITIONAL, dimulai dari Terbaru oleh Android | Sama |
| Aplikasi lain di dalam jendela LX | UNSUPPORTED oleh Android untuk launcher | Sama |
| Beban CPU sistem | UNSUPPORTED sejak Android 8 | Sama |
| Hasil fase ini | Semua uji lulus | NOT TESTED: ponsel tidak tersambung |

Rincian per fitur ada di `docs/testing/compatibility.md`.

## F. Hasil pengujian

| Test | Lingkungan | Diharapkan | Hasil sebenarnya | Status | Bukti |
|---|---|---|---|---|---|
| Unit, fuzz, regresi core | Host, debug dan ASan + UBSan | Semua lulus | 100 lulus, tanpa laporan sanitizer | PASS | `scripts/test-host.sh` |
| Lint | Gradle | 0 error | 0 error, 3 warning versi | PASS | `app/build/reports/lint-results-debug.xml` |
| APK tanpa gambar referensi | `unzip -Z1` | Tidak ada | Tidak ada | PASS | `build/apk-contents.txt` |
| Instrumentation | Waydroid | 9 lulus | 9 lulus | PASS | `scripts/test-device.sh` |
| UI batch 1, 2, 3 | Waydroid | Semua lulus | 29, 18, 32 lulus | PASS | `build/ui-test*/results.json` |
| Pemasangan baru memakai wallpaper tema | Waydroid | "Ikut tema" | "Follows the theme" | PASS | Batch 3 |
| Gambar pilihan jadi wallpaper | Waydroid | Gambar uji 900x1600 | `imported wallpaper image 900x1600` | PASS | Batch 3 |
| Dua aplikasi dalam jendela | Waydroid | `mode=freeform` | Clock dan Calculator, keduanya freeform | PASS | `dumpsys activity` |
| Rotasi dengan pencarian terbuka | Waydroid | Pencarian bertahan | Bertahan dua arah | PASS | uiautomator |
| Layar penuh di area aman | Waydroid | Di dalam bilah | `[0,32][1920,965]` | PASS | uiautomator |
| Jendela di atas keyboard | Waydroid | Naik | `[255,138][1245,700]` menjadi `[255,114][1245,397]` | PASS | uiautomator |
| Immersive | Waydroid | Bilah hilang, muncul saat diusap | Sesuai | PASS | tangkapan layar, `dumpsys window` |
| Semua di atas | Infinix | | | NOT TESTED | Ponsel tidak tersambung |

Masalah yang tersisa: lihat bagian H.

## G. Perbandingan performa

Build release sebelum dan sesudah, sama-sama ditandatangani debug key SDK, diukur bergantian dua putaran di Waydroid dengan `scripts/bench-device.sh`.

| Ukuran | Sebelum | Sesudah |
|---|---|---|
| Cold start, 10 kali | 139 sampai 228 ms, median 152 ms | 141 sampai 153 ms, median 151 ms |
| PSS setelah stabil | 81,5 dan 81,6 MB | 76,3 dan 81,9 MB |
| Frame jank | 1,85% dan 0,93% | 1,42% dan 0,47% |
| Frame p99 | 53 dan 21 ms | 24 dan 19 ms |
| Ukuran APK release | 4,29 MB | 4,32 MB |

Tidak ada penurunan yang terukur. Setiap putaran hanya sekitar 215 frame, jadi selisih jank belum cukup untuk disebut perbaikan. Ponsel tidak diukur ulang di fase ini.

## H. Keterbatasan

- **Android.** Launcher tidak bisa menampilkan layar aplikasi lain di dalam jendelanya sendiri, tidak bisa memulai layar terbagi untuk aplikasi lain, dan tidak bisa membaca beban CPU seluruh sistem.
- **Jendela freeform.** Jendela freeform aplikasi selalu berada di atas desktop LX, termasuk di atas laci dan jendela LX. Selama aplikasi itu memegang fokus, ketikan masuk ke aplikasi tersebut.
- **Saklar Opsi pengembang.** Android tidak menyediakan cara untuk membaca saklar "Enable freeform windows".
- **Perangkat.** Ponsel Infinix tidak melaporkan freeform. Fitur Floating Window XOS tidak bisa dipakai launcher lain.
- **Belum diuji.** Semua perubahan fase ini di ponsel Infinix; salin folder, pindah antar-folder induk, tombol Batal, dan akses kartu SD di LX Files; layar penuh di Android di bawah 11; uji stres jangka panjang dan profil memori ulang.
- **Ditunda.** Terminal dan widget cuaca tetap ditunda, dengan alasan di `docs/testing/compatibility.md`.

## I. Panduan operator

- **Launcher.** Pilih LX di dialog aplikasi Home Android. Untuk kembali ke launcher lama, buka Pengaturan LX > Tentang > Kembali ke launcher sebelumnya.
- **Workspace.** Ketuk angka 1 sampai 4 di panel atas (ponsel) atau taskbar (tablet), usap ke kiri atau kanan di desktop, atau tekan Ctrl 1 sampai 9. Tekan lama angkanya untuk mengganti nama, mengganti wallpaper, membuka ringkasan, atau menghapus workspace.
- **Kontrol jendela.** Geser jendela dari bilah judulnya dan ubah ukurannya dari bingkai. Tombol judul berisi Minimalkan, Maksimalkan atau Pulihkan, dan Tutup. Tekan lama bilah judul untuk menu Layar penuh, Tempel kiri dan kanan, dan Pindah ke workspace. Dengan keyboard: Alt+Tab, Alt F4, Ctrl M, dan Meta dengan panah.
- **Taskbar.** Menampilkan pin, jendela LX yang terbuka (yang diminimalkan tampak pudar), dan tombol laci. Ketuk jendela di taskbar untuk memulihkannya.
- **Dock.** Di ponsel tegak, dock menyatu dengan bilah bawah. Seret aplikasi ke dock untuk menyematkannya, dan tekan lama entri untuk memindah atau melepasnya.
- **File Manager.** Pilih folder lewat tombol Pilih folder. Kartu SD dan drive USB muncul di menu folder. Tekan lama berkas untuk Salin, Pindahkan, Ganti nama, Detail, atau Hapus, lalu buka folder tujuan dan ketuk Tempel. Toolbar berisi Folder baru, Berkas baru, Cari, Urutkan, dan Muat ulang.
- **Layar penuh.** Pengaturan LX > Dock > Layar penuh. Usap dari tepi atas atau bawah untuk memunculkan bilah sistem sebentar.
- **Izin.** LX tidak meminta izin di awal. Android menanyakan setiap akses saat dibutuhkan: Home, folder, widget, dan gambar. Akses folder bisa dicabut di Pengaturan LX > Privasi atau menu folder di LX Files.
- **Tata letak.** Tekan lama area kosong desktop untuk Tambah widget, Tambah aplikasi, Wallpaper, dan Atur. Tekan lama widget lalu geser untuk memindahkannya. Tema, font, ikon, ukuran antarmuka, dan kontras ada di Pengaturan LX > Tampilan. Tata letak bisa diekspor dan diimpor di Pengaturan LX > Cadangan.

## J. Penilaian rilis

**Memerlukan pengujian tambahan.**

Kandidat ini lulus semua gerbang di lingkungan yang diuji:

- build berhasil;
- lint tanpa error;
- 100 test core lulus;
- 88 langkah perangkat lulus di Waydroid;
- tidak ada crash atau ANR;
- tidak ada izin baru;
- dokumentasi sesuai dengan yang diamati.

Kandidat ini belum boleh ditandai RELEASE CANDIDATE APPROVED karena:

1. Perubahan fase ini belum dijalankan di ponsel sungguhan. Sebelumnya, ponsel menemukan masalah yang tidak terlihat di Waydroid.
2. Beberapa jalur LX Files yang baru, yaitu salin folder, pindah antar-folder induk, Batal, dan kartu SD, belum dicoba di perangkat.

Langkah berikutnya:

1. Sambungkan ponsel dan jalankan `scripts/test-device.sh` dengan `HOME_RESTORE` launcher XOS.
2. Uji manual keempat jalur LX Files tadi dengan folder uji.
3. Kalau keduanya lulus, kandidat bisa dinilai ulang.
