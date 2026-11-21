# Laporan engineering LX-LAUNCHER, 31 Oktober 2026

Laporan ini menjawab master prompt 31 Oktober: satu standar dekorasi untuk jendela LX dan File Manager yang lengkap. Semua uji perangkat dijalankan di Infinix HOT 60 5G, sesuai permintaan pemilik ponsel. Waydroid tidak dipakai. Audit awalnya ada di `docs/audits/window-and-file-manager-audit.md`.

## LX-LAUNCHER ENGINEERING REPORT

| Bagian | Isi |
|---|---|
| Project Baseline | LX-LAUNCHER 0.1.1 (versionCode 2). Kotlin dengan Android View, tanpa AndroidX, di atas core C++20 lewat JNI (`lxnative`). Dependensi baru: zlib dari NDK untuk arsip, dan Shizuku-API 13.1.5 (MIT) untuk Mode B |
| Android API / Device | minSdk 26, targetSdk 35, compileSdk 35. Diuji di Infinix HOT 60 5G (X6726B), Android 15 (API 35), XOS, MediaTek, arm64-v8a, 720x1600 pada 320 dpi, bahasa Indonesia, dengan kartu SD 268 GB |
| Architecture | Jendela LX diatur `lx::WindowManager` di C++ dan digambar `WindowLayer`. Semua alat LX memakai satu `WindowStyle` lewat `StyledShell`. File Manager memakai kontrak `Backend`, `StorageCatalog`, dan `JobEngine`, dengan backend lokal (akses semua file) dan SAF (folder pilihan). Arsip ditangani `lx::archive` di C++. Mode B menjalankan aplikasi di layar virtual milik shell lewat Shizuku (ADR-006) |
| Modified Modules | `WindowLayer`, `WindowChrome`, `WindowStyles`, `Glass`, `Tokens`, `Shell` (menu bercabang), `OverlayHost`, `Panels` (tombol File Manager), `LauncherActivity`, `SettingsTool`, `AppearancePickers`, `DeviceCapabilities`, `Icons`; core `settings.cpp`, `window_manager.cpp`, `engine.cpp`; manifest, proguard, Gradle |
| New Modules | `files/` (File Manager: UI, backend lokal dan SAF, mesin operasi, journal, provider berbagi, pembungkus JNI arsip), `ui/overlays/CascadeMenu`, `embed/` (Mode B: layanan, klien, helper, provider binder), core `archive*.cpp` dan `archive_jni.cpp`, tes host arsip dan fuzz, `scripts/ui_test_file_manager.py` |
| Window Decoration | PASS. Gaya baru LX Modern Gelap sesuai token rujukan (#101F33, bilah judul #1B2D48 52 dp, tepi #55A9FF 1,5 dp, sudut 20 dp, tombol kuning, hijau, dan merah, ikon berwarna 20 dp, jendela tanpa fokus memudar) menjadi bawaan pemasangan baru. Gaya Kustom mengatur dasar, aksen, sudut, tepi, tinggi bilah judul, ukuran judul dan ikon, tombol, bayangan, dan pudar. Posisi dan ukuran jendela diingat per jenis alat |
| Internal Apps | PASS. Pengaturan LX, File Manager, Monitor Sistem, dan Catatan memakai bingkai yang sama |
| External App Compatibility | Mode A (launch bounds dan freeform): PASS, bingkai milik Android. Mode B (Shizuku): PASS di Android 15, termasuk ponsel MediaTek lewat helper LX; Android 13 dan 14 NOT TESTED. Mode C (mengganti dekorasi sistem): BLOCKED BY PLATFORM CAPABILITY, butuh tanda tangan platform atau root |
| File Manager | PASS. Akses semua file opt-in dengan penjelasan lebih dulu, operasi dasar, pilih banyak, cari, properti, urut, berkas tersembunyi, konflik nama, operasi yang bertahan setelah jendela ditutup. Folder pilihan lewat SAF: NOT TESTED sejak menjadi File Manager |
| SD Card Support | PASS untuk kartu SD (salin ke kartu dan pindah kembali, ruang kosong tampil di sidebar). Flashdisk USB dan kartu dicabut di tengah operasi: NOT TESTED |
| Archive Support | PASS. ZIP, TAR, dan TAR.GZ dibuat dan dibongkar dengan isi identik per md5. Path keluar folder, path absolut, symlink, entri terenkripsi, dan arsip yang membengkak ditolak |
| Cascading Menu | PASS untuk sentuhan: submenu terbuka di kanan, di kiri, atau menggantikan induknya. Keyboard, hover mouse, dan tata letak kanan-ke-kiri: NOT TESTED di perangkat |
| Taskbar Integration | PASS. Tombol File Manager tepat di kanan tombol Aplikasi, dengan indikator jendela terbuka, tanpa jendela ganda |
| Build Status | PASS. Debug, test APK, dan release (R8) berhasil. Lint 0 error, 3 warning versi toolchain yang sengaja dipatok |
| Unit Tests | PASS. Host: 179 lulus. ASan dan UBSan: 178 lulus, 1 dilewati sesuai rancangan (harness JNI tidak dibangun di build sanitizer), tanpa laporan sanitizer |
| Device Tests | PASS. Instrumentation 28 lulus, 0 gagal. `scripts/ui_test_file_manager.py`: 22 lulus, 0 gagal pada build final, termasuk 40 siklus ubah ukuran |
| Regression Tests | PASS dengan cakupan terbatas. Lihat bagian Regresi |
| Known Limitations | Lihat bagian Batas |
| Final Status | AC-01 sampai AC-16 di bawah. Tidak ada FAIL. Mode C BLOCKED BY PLATFORM CAPABILITY; beberapa jalur perangkat NOT TESTED |

## Kriteria terima

| AC | Kriteria | Status | Bukti |
|---|---|---|---|
| AC-01 | Audit repository selesai dan terdokumentasi | PASS | `docs/audits/window-and-file-manager-audit.md` |
| AC-02 | Aplikasi internal memakai standar dekorasi yang sama | PASS | Satu `WindowStyle` untuk semua alat LX. Di Infinix, LX Modern Gelap dan Kustom dipilih dari Pengaturan LX dan langsung terpasang di jendela yang terbuka. Instrumentation: kontras 4,5:1 setiap gaya, termasuk setiap gaya dasar dengan setiap aksen Kustom |
| AC-03 | Minimize, maximize, restore, close, drag, dan resize | PASS | Uji UI File Manager: tutup, buka dari taskbar, perkecil dan pulihkan, 40 siklus ubah ukuran. Manual: geser bilah judul Catatan 345 px, geser dari pinggir kiri bilah judul memindahkan tanpa mengubah lebar |
| AC-04 | Kompatibilitas jendela pihak ketiga diuji per mode | PASS | ADR-006 dan FC-07. Mode B di Infinix: Kalkulator 7 × 8 = 56 dan 9 × 6 = 54, ubah ukuran dengan tata letak ulang, minimise menjeda, keyboard Gboard, tutup melepas layar. Mode C BLOCKED BY PLATFORM CAPABILITY |
| AC-05 | File Manager mengakses storage sesuai izin | PASS | Dengan akses semua file: penyimpanan internal dan kartu SD. Setelah izin dicabut: layar mulai dengan "Izinkan akses semua file" dan "Tambah folder…". Halaman Tentang menyebut izin yang sedang berlaku. Jalur folder pilihan lewat SAF di File Manager: NOT TESTED |
| AC-06 | SD card terdeteksi dan dapat dikelola | PASS | Kartu SD 268 GB tampil di sidebar; salin ke kartu dan pindah kembali lulus. USB: NOT TESTED |
| AC-07 | Operasi file dasar | PASS | Folder baru, berkas teks baru, ganti nama, salin, pindah, hapus dengan konfirmasi, cari, properti "25 MB (4 berkas, 2 folder)", berkas tersembunyi, urutan alami |
| AC-08 | Submenu bercabang | PASS (sentuhan) | Ponsel mendatar: Tampilan > Urutkan terbuka di kanan; menu latar di tepi kanan membuka Baru di kiri. Tegak pada 720 dp: submenu menggantikan induk, baris kembali kembali ke Tampilan, Back menutup menu. Keyboard dan hover: NOT TESTED |
| AC-09 | Kompresi dan ekstraksi | PASS | ZIP, TAR, TAR.GZ: 4 dari 4 berkas identik per md5 dan nama folder tetap. Ekstrak dengan "Simpan keduanya" membuat salinan bernomor yang identik |
| AC-10 | Uji keamanan arsip | PASS | Tes host keamanan, path, dan fuzz, dengan dan tanpa sanitizer. Di perangkat, ZIP berbahaya: entri `..`, path absolut, dan symlink dilewati |
| AC-11 | Ikon File Manager tepat di kanan App Drawer | PASS | Tombol Aplikasi di x 20 sampai 104, File Manager di x 112 sampai 196 |
| AC-12 | File Manager berjalan sebagai jendela LX | PASS | Jenis alat `files` di `WindowLayer`; satu jendela, tidak pernah ganda |
| AC-13 | UI responsif saat resize | PASS | 40 siklus ubah ukuran tanpa crash. Tata letak sempit (360 dp), sedang (614 dp, menubar dan laci), dan lebar (ponsel mendatar, 1600 px pada 160 dpi, sidebar menempel) dicek di ponsel |
| AC-14 | Tidak ada regresi kritis | PASS (cakupan terbatas) | Instrumentation 28 dari 28, tes host, uji UI File Manager, dan smoke manual: menu Aplikasi, Pengaturan LX, pindah workspace, geser jendela. Batch UI lama (`ui_test.py` dan seterusnya) tidak dijalankan karena membersihkan data LX dengan `pm clear` |
| AC-15 | Build dan pengujian wajib berhasil | PASS | Lihat Build Status, Unit Tests, dan Device Tests |
| AC-16 | README dan laporan akhir diperbarui | PASS | README, CHANGELOG, DESIGN, ADR-006, kontrak fitur, matriks izin, threat model, laporan uji, matriks kompatibilitas, dan laporan ini |

## Cacat yang ditemukan dan diperbaiki hari ini

| # | Gejala | Akar masalah | Perbaikan |
|---|---|---|---|
| 1 | Judul dan teks jendela Catatan samar terlihat di balik daftar berkas | Jendela tanpa lapisan blur diisi pada alpha 0,96 | Badan jendela, menu, dan panel kaca tanpa blur digambar padat penuh. Setelah perbaikan, piksel di atas Catatan sama dengan bagian lain |
| 2 | "Tampilkan berkas tersembunyi" terpotong di menu Tampilan | Lebar setiap level menu tetap 264 dp | Lebar level mengikuti baris terpanjang, 264 sampai 360 dp |
| 3 | Pembaca layar mengucapkan "Kembali ke Urutkan" di dalam submenu Urutkan | Baris kembali diberi nama submenu yang sedang dibuka | "Urutkan, kembali ke menu sebelumnya" |
| 4 | Tombol "Izinkan akses semua file" terpotong di layar kosong File Manager | Kolom tombol selebar tombol kedua yang lebih pendek | Semua tombol di kolom itu mengisi lebar kolom, sehingga kolom selebar label terpanjang |
| 5 | Halaman Tentang berkata "LX tidak melihat yang lain" padahal akses semua file diberikan | Halaman itu hanya menghitung folder SAF | Teks khusus untuk akses semua file dan untuk kartu SD yang tampil di sidebar |
| 6 | Teks masih menyebut "LX Files" | Nama lama tertinggal di enam string | Diganti File Manager di kedua bahasa |
| 7 | Menu bercabang menumpuk di tepi kanan dalam tata letak kanan-ke-kiri | Kartu tanpa gravity mengikuti arah START, sehingga `leftMargin` diabaikan; arah dibaca sebelum view punya parent | Kartu dipasang dari kiri secara absolut, arah dibaca dari host, menubar berpatokan pada tepi kanan tombolnya. Belum dicoba di perangkat |
| 8 | Bilah pesan, bilah mode edit, dan panel status Mode B masih sedikit tembus | Ketiganya memakai 0,96 | Padat penuh |
| 9 | Slider Kepekatan dock tidak berpengaruh | Nilainya dihitung tetapi tidak pernah dibaca | Slider dihapus; kunci setelan tetap diterima untuk cadangan lama |
| 10 | Petunjuk mencabut akses semua file salah di Android 8 sampai 10 | Teks hanya menyebut halaman Android 11 ke atas | Teks terpisah untuk Android 8 sampai 10 |
| 11 | Mode B tidak bisa dipakai di MediaTek | Shizuku 13.6.0 gagal menjalankan user service (#1198) | Helper LX lewat `IShizukuService.newProcess`; empat cacat lanjutan (task freeform tergeser, keyboard tidak muncul, aktivitas dibuat ulang terus, ukuran awal terlalu besar) ikut diperbaiki. Detail di ADR-006 |

Cacat 7 sampai 10 ditemukan review independen: tiga peninjau dengan sudut berbeda (tata letak, rendering, teks dan izin), lalu satu penyanggah untuk setiap temuan. Kelimanya bertahan dari penyanggahan.

## Regresi

Instrumentation memeriksa engine lewat JNI, tata letak, impor yang ditolak, daftar aplikasi, pencarian, wallpaper dan blur, widget, kontras gaya, arsip, dan Mode B. Tes host memeriksa seluruh core. Di ponsel, menu Aplikasi, Pengaturan LX, pindah workspace, geser jendela, dan gaya jendela dicoba manual. Batch UI lama tidak diulang, karena skripnya membersihkan data LX dan ponsel ini dipakai pemiliknya.

## Batas

- Mode C butuh tanda tangan platform, peran sistem, atau root. Tidak dibangun.
- Mode B: jendela aplikasi pertama di ponsel MediaTek butuh sekitar 5 detik. Saat keyboard tampil, bagian bawah aplikasi tertutup. Konten FLAG_SECURE tampil hitam. Shizuku harus dinyalakan ulang setiap ponsel dinyalakan ulang.
- Belum diuji di perangkat: flashdisk USB, kartu SD dicabut di tengah operasi, penyimpanan penuh, dan folder pilihan SAF di File Manager. Android 8 sampai 10, keyboard fisik, mouse, dan tata letak kanan-ke-kiri di menu bercabang, serta Mode B di Android 13 dan 14 juga belum.
- Google Play mewajibkan deklarasi untuk `MANAGE_EXTERNAL_STORAGE`. Belum diajukan.

## Lingkungan uji dan pembersihan

Data LX dicadangkan dengan `run-as` sebelum uji dan dipulihkan sesudahnya. Density dan rotasi yang diubah untuk uji tata letak dikembalikan. Akses semua file dicabut lagi, baik mode paket maupun mode uid. Folder uji di penyimpanan internal dan kartu SD dihapus. Di akhir, `layout.lxl` identik byte demi byte dengan cadangan awal, juga setelah LX dibuka lagi lewat Home. Shizuku tetap terpasang dan berjalan, dan LX masih tercatat diizinkan di Shizuku; dengan bingkai aplikasi Android yang dipulihkan, LX tidak memakainya.

## Pembaruan 1 November 2026

Dua permintaan lanjutan dari pemilik ponsel, keduanya selesai dan diuji di Infinix.

| Bagian | Status | Bukti |
|---|---|---|
| Tampilan File Manager mengikuti gambar rujukan | PASS | Bilah atas datar, jalur berbentuk pil, sidebar kartu, grid lima kolom, ikon berwarna per jenis berkas, menu dan dialog versi rapat. `scripts/ui_test_file_manager.py`: 22 lulus, 0 gagal |
| Aplikasi apa pun memakai bingkai sesuai pengaturan | PASS dengan Shizuku berjalan | Bingkai LX jadi bawaan. Kalkulator, Chrome, YouTube, WhatsApp, Facebook, dan aplikasi profil kerja. Game tetap layar penuh sesuai permintaan sebelumnya |
| Seluruh layar aplikasi tampil di jendela | PASS | `scripts/ui_test_framed_apps.py`: 55 lulus, 0 gagal, termasuk jendela kecil dan aplikasi terkunci potret. Keyboard tampil di dalam jendela |
| Shizuku tidak berjalan | PASS | Jendela LX tetap terbuka dengan petunjuk, dan aplikasi terbuka sendiri setelah Shizuku jalan |
| Aplikasi FLAG_SECURE | BLOCKED BY PLATFORM CAPABILITY | Shell tidak boleh membuat layar virtual aman, jadi isinya hitam di jendela LX |
| Mulai Shizuku otomatis setelah ponsel dinyalakan ulang | BLOCKED BY PLATFORM CAPABILITY | Tanpa root butuh wireless debugging, dan itu butuh Wi-Fi |

Pemeriksaan akhir: lint 0 error dan 3 warning, tes host 179 lulus, ASan dan UBSan 178 lulus dengan 1 dilewati sesuai rancangan, instrumentation 30 lulus, build release berhasil.

## Pembaruan 2 November 2026

Empat masalah dari pemilik ponsel. Tiga selesai dan terbukti di Infinix. LX Bridge selesai dibangun, tetapi belum dijalankan melawan `adbd` ponsel.

| Bagian | Status | Bukti |
|---|---|---|
| Alat sendiri pengganti Shizuku setelah restart | PARTIAL | LX Bridge (ADR-007) sudah ada: pemasangan otomatis, pairing, klien ADB, helper yang hidup sampai reboot, panel dan notifikasi. Shizuku sudah dihapus dari APK. Android tetap meminta satu persetujuan pemilik ponsel. `adb pair` asli cocok dengan kode pairing LX. Helper membuka Kalkulator, Chrome, YouTube, WhatsApp, dan Facebook. Pairing dengan `adbd` ponsel, otomatisasinya, dan restart sungguhan belum dijalankan, karena auto mode classifier menolak ketukan toggle Proses debug nirkabel |
| Aplikasi tetap berjalan saat disembunyikan atau layar mati | PASS | Kalkulator `RESUMED` dan tidak dibekukan selama 180 detik dengan ponsel terkunci. Layar mati lebih lama dan pemutaran video sungguhan belum diuji |
| File Manager ikut bergulir di bilah bawah | PASS | Diperiksa di ponsel |
| Tampilan saat jendela diubah ukurannya | PASS | Gambar tengah seretan dan sesudahnya |

Cacat yang ditemukan pengujian dan sudah diperbaiki: binder LX hilang setelah GC sehingga helper tidak tahu LX mati; `GlassEnv.update` bisa crash saat GC membersihkan `WeakHashMap`; grup layar aplikasi akan tertidur setelah timeout layar.
