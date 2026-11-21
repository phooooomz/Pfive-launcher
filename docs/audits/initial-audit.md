# Audit awal

## Isi folder saat mulai

Folder `lxLauncher` hanya berisi `readme.md` (brief) dan `referensi.png` (941x1672, mockup ponsel). Tidak ada source code, konfigurasi build, atau repository Git. Project diinisialisasi dari nol.

## Urutan sumber kebenaran yang dipakai

1. Jawaban operator di awal sesi: antislop dipakai sejak penulisan, glass dan neon mengikuti brief tetapi bisa diatur, arsitektur native Android dengan core C++20, dan pengujian di Waydroid.
2. `referensi.png`.
3. `readme.md`.
4. Dokumentasi resmi Android: LauncherApps, RoleManager, AppWidgetHost, Storage Access Framework, package visibility.

Konflik antara brief dan aturan desain antislop (dosis glass dan glow) diputuskan operator: brief diikuti, glow hanya untuk elemen yang sedang fokus, dan semuanya bisa dimatikan.

## Audit gambar referensi

| Elemen di referensi | Implementasi | Catatan |
|---|---|---|
| Panel status atas: logo, nama, ikon status, baterai, jam | `TopPanel`: logo, nama, workspace aktif, cari, pengaturan Android, baterai, jam | Sejak 0.1.1 logo dari icon.png dipakai di panel atas dan sebagai ikon aplikasi. Merek NEXUS tidak dipakai sesuai brief |
| Dock kiri: Home, Files, Terminal, Browser, Settings, Apps, Games, Gallery, Trash | `DockView`: Home, Files, Browser, Settings, Apps, Games, Gallery, Monitor. Isinya bisa diatur | Terminal dan Trash tidak dimasukkan. Terminal palsu dilarang brief; Android tidak punya tempat sampah sistem untuk aplikasi |
| Jendela System Monitor (CPU, GPU, RAM, baterai, Wi-Fi, Bluetooth) | Jendela System Monitor dan widget Status | CPU dan GPU sistem tidak bisa dibaca aplikasi, jadi tidak ditampilkan. Bluetooth butuh izin runtime, jadi juga tidak ditampilkan. Yang tampil hanya data asli |
| Jendela Browser berisi situs | Tombol Browser membuka browser default lewat Intent | Tidak ada mesin browser buatan sendiri, sesuai brief |
| Jendela Terminal dengan neofetch | Tidak dibuat | Keluaran perintah palsu dilarang brief. Terminal ditandai DEFERRED |
| Widget Music | Widget Kontrol media (tombol media sungguhan) | Judul lagu butuh akses notifikasi, yang tidak diminta |
| Jendela Files | LX Files lewat Storage Access Framework | Hanya folder yang dipilih pengguna |
| Bilah pencarian dengan Ctrl K | `SearchBarView` dan overlay pencarian | Petunjuk Ctrl K hanya muncul jika keyboard fisik terpasang |
| Taskbar: workspace 1 sampai 4, aplikasi tersemat, tombol laci | `TaskbarView` | Ditambah daftar jendela LX yang terbuka |
| Wallpaper kota cyberpunk | Wallpaper "Night skyline" yang digambar prosedural di C++ | Ilustrasi referensi tidak disalin |

## Audit lisensi

Hanya dependensi berlisensi permisif yang ikut terkirim di APK: Kotlin stdlib (Apache 2.0), libc++ dari NDK (Apache 2.0 dengan LLVM Exceptions), dan path data Material Icons (Apache 2.0). GoogleTest (BSD-3-Clause) hanya dipakai saat build test. Rincian di `docs/research/dependencies.md`.

Sejak 0.1.1 kode LX-LAUNCHER memakai Apache License 2.0 atau versi sesudahnya (SPDX `Apache-2.0+`). Setiap berkas sumber membawa header SPDX. Teks lisensi ada di `LICENSE`, atribusi di `NOTICE`, dan salinan `NOTICE` ikut di APK sebagai `assets/NOTICE.txt`.

## Kompatibilitas Android

- `minSdk` 26 (Android 8.0): ikon adaptif dan `ApplicationInfo.category` tersedia sejak versi ini.
- `targetSdk` 35 (Android 15). API 36 tersedia di SDK, tetapi perubahan perilakunya belum diuji, jadi belum dipakai.
- ABI: arm64-v8a dan x86_64. Library native ditaut dengan `max-page-size=16384` untuk perangkat ber-page 16 KB.
- Tanpa `QUERY_ALL_PACKAGES`. Visibilitas paket dibatasi lewat `<queries>` untuk activity LAUNCHER, Home, dan penyedia widget.
