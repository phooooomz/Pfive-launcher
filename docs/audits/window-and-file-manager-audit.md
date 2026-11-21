# Audit jendela dan pengelola berkas, 31 Oktober 2026

Audit ini dibuat sebelum implementasi dua tugas: dekorasi jendela yang seragam, dan File Manager yang menggantikan LX Files. Nomor baris merujuk kode sebelum perubahan hari itu (salinan acuan disimpan di luar repo). Bukti perangkat berasal dari Infinix HOT 60 5G (XOS, Android 15, arm64, 360 dp, bahasa id-ID) yang terhubung lewat ADB.

## Masukan yang diterima

Prompt menyebut empat tangkapan layar, tetapi yang terlampir tiga:

1. Desktop LX dengan jendela Browser, Catatan, dan File Saya yang semuanya berbingkai gaya LX. Status bar dan isi jendelanya menunjukkan ini gambar rancangan, bukan tangkapan layar aplikasi yang sedang berjalan: di ponsel yang sama, Chrome dalam jendela memakai caption XOS.
2. Lembar sepuluh gaya jendela "File Manager". Gaya-gaya ini sudah ada di LX sejak 30 Oktober.
3. Rancangan Browser dan YouTube dalam jendela berbingkai LX.

Jendela Catatan di gambar pertama dipakai sebagai acuan visual. Ponsel pengguna memakai gaya `rounded` (Rounded Modern) dengan palet Oceanic, dan gaya itu paling dekat dengan acuan. Tautan percakapan eksternal di prompt tidak dibuka, karena isinya tidak bisa diakses dari lingkungan ini.

LX tidak punya browser internal. Manifest sengaja tidak meminta izin `INTERNET` (`docs/architecture/permissions.md`). "Browser" di gambar adalah Chrome, aplikasi pihak ketiga.

## Arsitektur saat ini

- **Platform dan core.** Activity Android dengan View Kotlin, tanpa AndroidX. Core C++20 dipanggil lewat JNI (ADR-001).
- **Window manager.** Kode C++ di `core/src/window_manager.cpp` mengatur ID, urutan z, fokus, keadaan, snap, dan batas viewport jendela LX. Geometri jendela mengambang disimpan sebagai pecahan viewport, jadi tetap proporsional setelah rotasi (ADR-003).
- **Jendela LX.** `WindowLayer` menggambar jendela-jendela itu. Setiap jendela berisi `ToolView`: Pengaturan LX, LX Files, Monitor Sistem, Catatan, atau Selamat datang. Kelima alat memakai bingkai `WindowFrame` yang sama, dan gayanya diambil dari salah satu dari sepuluh `WindowStyle` atau dari tema LX.
- **Jendela aplikasi Android.** LX hanya meminta mode freeform lewat `ActivityOptions.setLaunchBounds` dan kunci bundle `android.activity.windowingMode` (ADR-005). Sesudah aplikasi terbuka, LX tidak lagi mengatur apa pun pada jendela itu.

### Membuka jendela

`LauncherActivity.openTool` (`:688`) memakai ulang jendela sejenis di workspace aktif. Kalau belum ada, ia memanggil `NativeCore.openWindow`, yang menuju `WindowManager::open` (`window_manager.cpp:113`). Posisi awal diambil dari `defaultNorm`, sebuah kaskade. Jendela yang ditutup tidak meninggalkan catatan posisi apa pun.

### Mengubah ukuran

Ada zona sentuh 8 dp di kiri, kanan, dan bawah, dan 4,8 dp di atas (`WindowLayer.kt:348-356`). Gerakan diteruskan ke `WindowManager::resize`, yang menjaga ukuran minimum 240 x 180 dp dan memastikan bilah judul tetap di layar.

### Dekorasi internal dan eksternal

| | Jendela LX | Jendela aplikasi Android |
|---|---|---|
| Yang menggambar bingkai | LX (`WindowFrame`, `WindowChrome`) | SystemUI/WM Shell; di XOS Transsion, caption hitam |
| Gaya bisa diatur | Ya, sepuluh gaya | Tidak ada API publik untuk launcher |
| Tombol, geser, ubah ukuran | LX | Sistem |

Riset atas sumber AOSP 11 sampai 16 (rinciannya di `docs/architecture/ADR-006-lx-framed-app-windows.md`) menunjukkan caption freeform digambar oleh proses aplikasi sampai Android 13. Mulai Android 14 caption digambar SystemUI. Launcher biasa tidak bisa mengubah, menyembunyikan, atau mengganti caption itu. `TaskOrganizer` butuh `MANAGE_ACTIVITY_TASKS`, yang hanya dimiliki sistem, aplikasi recents, dan shell.

### API Android yang dipakai

`LauncherApps`, `ActivityOptions.setLaunchBounds`, `AppWidgetHost`, `RoleManager`, dan Storage Access Framework (`ACTION_OPEN_DOCUMENT_TREE` dengan izin URI persisten).

### Akses penyimpanan

LX Files hanya melihat folder yang dipilih lewat pemilih sistem. Di Android 11 ke atas, pemilih itu menolak akar penyimpanan internal, folder Download, dan akar kartu SD, sehingga penyimpanan internal tidak pernah bisa dikelola utuh.

## Masalah yang ditemukan

| No | Masalah | Bukti | Akar masalah |
|---|---|---|---|
| 1 | Chrome, YouTube, dan aplikasi lain tidak berbingkai LX | Caption XOS di jendela freeform | Caption milik SystemUI; tidak ada API untuk launcher |
| 2 | Memindahkan folder lintas penyimpanan bisa menghapus berkas yang belum tersalin | `FilesTool.kt:577-578, 623` | Sumber dihapus rekursif setelah salinan, padahal pemeriksaan perubahan hanya menghitung jumlah anak per folder |
| 3 | Salinan yang sedang berjalan terputus diam-diam saat tema atau mode malam berganti | `LauncherActivity.kt:328-352`, `FilesTool.kt:659-664` | Operasi berjalan di executor milik jendela, dan jendela dibangun ulang |
| 4 | Tidak ada arsip ZIP/TAR, pilih banyak, berbagi, info ruang kosong, berkas tersembunyi, dan riwayat maju/mundur | Tabel fitur di audit LX Files | Belum dibuat |
| 5 | Ketukan di 8 dp terluar tombol tutup memulai ubah ukuran | `WindowLayer.kt:351-352, 363-369` | Zona tepi kanan menimpa bilah judul |
| 6 | Jendela yang ditutup lalu dibuka lagi kembali ke posisi kaskade | `window_manager.cpp:129` | Tidak ada memori posisi per alat |
| 7 | Gaya jendela tidak bisa disesuaikan | `settings.cpp:58-60` | Hanya pilihan dari sepuluh preset |
| 8 | Ikon di bilah judul selalu glyph satu warna | `WindowLayer.kt:245-252` | Ukuran dan warna ikon dikunci |

## Risiko perubahan

- **Izin `MANAGE_EXTERNAL_STORAGE`.** Izin ini mengubah janji privasi di README dan matriks izin. Pengguna menyetujuinya pada 31 Oktober sebagai izin opt-in yang hanya diminta dari File Manager. Untuk Google Play, izin ini perlu deklarasi, dan launcher belum tentu lolos aturan "fungsi inti".
- **Shizuku.** Shizuku adalah dependensi pertama di luar Kotlin. Kode di proses shell punya hak ADB, jadi antarmukanya harus sempit. Pengguna setuju Shizuku dipasang dan diuji di Infinix.
- **Kerapatan bilah bawah.** Tombol tetap File Manager menambah 42 dp di bilah bawah ponsel. Pin `tool:files` di dock dan taskbar disembunyikan supaya tidak muncul dua kali.
- **Kode JNI baru.** Semua JNI baru berjalan tanpa kunci mesin, di thread pekerja, dengan handle per operasi. Mutex engine tidak boleh tersentuh.

## Rencana dan prioritas

1. Core arsip di C++ (`lx::archive`): ZIP, TAR, dan TAR.GZ, baca dan tulis, kebijakan path, dan batas aman. Diuji di host dengan ASan/UBSan dan fuzz.
2. Lapisan penyimpanan Kotlin: volume, backend lokal dan SAF, mesin operasi yang tidak ikut mati bersama jendela, journal untuk membersihkan sisa, dan provider berbagi.
3. UI File Manager dengan menubar bertingkat, ditambah komponen `CascadeMenu` untuk semua menu LX.
4. Tombol File Manager tetap di kanan tombol Aplikasi, lengkap dengan indikator berjalan.
5. Jendela LX: preset LX Modern Gelap sebagai bawaan baru, gaya Kustom, memori posisi per alat, gaya aktif dan nonaktif, ikon berwarna, dan perbaikan zona sentuh tombol tutup.
6. Mode B: aplikasi pihak ketiga di layar virtual lewat Shizuku, ditampilkan dalam jendela LX.
7. Uji perangkat di Infinix, dokumentasi, dan laporan akhir.
