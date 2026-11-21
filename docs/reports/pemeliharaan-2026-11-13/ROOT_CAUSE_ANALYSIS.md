# Analisis akar masalah, 13 November 2026

Perangkat uji: Infinix HOT 60 5G (X6726B), Android 15, layar 720x1600 pada 120 Hz, GPU PowerVR, keyboard Gboard. LX dipasang sebagai APK debug. Setiap uji di ponsel merekam log LX, `lxdb`, `ImeTracker`, `InputMethodManager`, `WindowManager:W`, `ActivityManager:W`, `AndroidRuntime`, dan `StrictMode` ke `build/maint-1113/logs/`.

## RCA-1. Jendela aplikasi tidak bisa dibuka setelah `lxdb` berhenti (P0, regresi)

**Gejala.** Sejak build terbaru dipasang pukul 01:40, log LX berisi "LX Bridge did not bring lxdb up in 25000 ms" berulang kali. Log LX Bridge (`no_backup/lx-bridge.log`) mencatat "lxdb launched through port 45049", lalu enam detik kemudian "lxdb did not reach LX within 6000 ms". Tidak ada proses `liblxdb.so daemon` di ponsel.

**Reproduksi.** Perintah peluncuran yang dipakai LX, dijalankan dengan tangan lewat `adb shell`:

```
/system/bin/linker64 '/data/app/~~wtIG.../base.apk!/lib/arm64-v8a/liblxdb.so'
error: unable to open file ".../base.apk!/lib/arm64-v8a/liblxdb.so"
```

Perintah yang sama dengan salinan di folder pustaka native (`/data/app/.../lib/arm64/liblxdb.so`) mencetak bantuan `lxdb`.

**Bukti.** `unzip -v` pada APK 9 November menunjukkan `liblxdb.so` berstatus `Stored`. Pada semua build sejak fase Debian (12 November) statusnya `Defl:N`. Manifest berisi `android:extractNativeLibs="true"`, dan komentarnya mengira `useLegacyPackaging = false` tetap membuat pustaka tersimpan apa adanya. Kenyataannya, dengan atribut itu build mengompresi semua pustaka.

**Akar masalah.** `ShellHelper.command` meminta `linker64` membuka `liblxdb.so` dari dalam APK. Linker hanya bisa melakukannya untuk pustaka yang tersimpan tanpa kompresi. Fase Debian membutuhkan `extractNativeLibs="true"` supaya `libphdp.so` bisa dieksekusi, dan efek sampingnya mematahkan peluncuran `lxdb`. Masalah ini tidak tertangkap karena di Waydroid (Android 13) LX Bridge tidak berjalan, dan di Infinix masih ada daemon `lxdb` lama sejak 11 November yang terus melayani LX.

**Perbaikan.** `ShellHelper.library` memilih salinan yang dibongkar Android di `applicationInfo.nativeLibraryDir` bila berkasnya ada, dan jatuh kembali ke jalur di dalam APK bila tidak. `LxBridge.launchOn` memakainya. Daemon tetap menerima path APK sebagai argumen terakhir, jadi pemeriksaan "versi APK sama" di `LxBridge.attach` tidak berubah.

**Verifikasi.** Setelah C3 dipasang dan HOME ditekan, `lxdb` naik dalam sekitar 5 detik. Log LX Bridge: "lxdb launched through port 45049" pukul 02:08:13.047, lalu "lxdb attached" pukul 02:08:13.067. `ps` menunjukkan `linker64 /data/app/.../lib/arm64/liblxdb.so daemon ...`.

**Pencegahan.** Uji instrumentasi baru, "the library lxdb starts from is one the system linker can open on this phone", gagal bila jalur yang dipilih tidak ada, atau bila pustaka di dalam APK terkompresi tanpa salinan hasil bongkar.

## RCA-2. Resize jendela tersendat

**Gejala.** Patokan di Infinix (`-e only windows`, FrameMetrics, anggaran 8,3 ms pada 120 Hz): 51 sampai 62% frame resize LX Settings lewat anggaran, Notes 22 sampai 53%, File Explorer 33 sampai 37%, dan Tedit 66 sampai 78% dengan p99 66 ms.

**Bukti dari profil.** `simpleperf record --app com.lxlauncher -g` selama resize. RenderThread memakai 46% CPU LX, dan 14,75% waktu RenderThread habis di `GrCopyRenderTask` lewat `glCopyTexSubImage2D`. Rantai pemanggilnya `SkCanvas::only_axis_aligned_saveBehind` dari `DisplayListData::draw`. Itu jalur `Canvas.saveUnclippedLayer` yang dipakai Android untuk fading edge pada view yang tidak punya warna latar solid (`View.getSolidColor() == 0`).

**Akar masalah 1: fading edge.** Fading edge tanpa warna solid menyalin potongan frame di bawah tepi pada setiap frame view itu digambar. GPU PowerVR merender per ubin, jadi salinan di tengah frame memaksanya menyelesaikan dan menulis keluar pekerjaannya lebih dulu. Tujuh view LX memakai fading edge: kolom LX Settings, kolom alat lain (`Shell.column`), dua pemilih di halaman Tampilan, path File Explorer, deret nomor workspace di panel atas, dan deret ikon di bilah bawah. Dua yang terakhir ada di layar terus dan ikut digambar ulang setiap frame saat jendela di-resize.

**Pembuktian.** Build eksperimen dengan semua fading edge dimatikan menurunkan waktu "issue" RenderThread: Settings dari 4,0-4,4 ms ke 2,4-2,6 ms, Notes dari 4,7 ke 3,6 ms, dan Files dari 2,8 ke 2,2 ms. Build yang hanya memperbaiki fading edge di dalam jendela tidak mengubah angka Notes. Artinya bilah atas dan bawah memang ikut membayar salinan itu di setiap frame.

**Perbaikan 1.** `StableScrollView` dan `StableHorizontalScrollView` mendapat `fadeInto`, warna buram di belakang view, yang dikembalikan lewat `getSolidColor()`. Android lalu menggambar fading edge sebagai gradasi biasa. Isi jendela memakai `LxTheme.windowBody`, yaitu warna panel bila badan jendela buram, termasuk gaya `lxmodern` dan custom milik pemilik. Pada gaya kaca nilainya 0, jadi pudaran asli tetap dipakai. Pemilih di halaman Tampilan memakai warna kartu (`Cards.raisedColor`), dan path File Explorer memakai warna kolomnya. Deret di bilah kaca atas dan bawah digambar ke hardware layer masing-masing, sehingga pudarannya dihitung hanya ketika deret itu berubah.

**Akar masalah 2: Tedit membangun ulang semua view di tengah tarikan.** `TeditTool.onResized` membangun ulang tampilan setiap kali lebar melewati 300 dp atau 600 dp, dan mengubah lebar panel samping serta tinggi header di setiap ukuran. Saat ditarik, itu terjadi berkali-kali, dan setiap permintaan layout dari dalam Tedit menembus pembatas layout konten yang dipasang 9 November.

**Perbaikan 2.** Selama `onLiveResize(true)`, `onResized` menunggu. Ketika jari diangkat, layout berikutnya menerapkan ukuran akhir satu kali.

**Verifikasi.** A/B bergantian, patokan `b0` lawan kandidat `c3`, dua run masing-masing, angka lengkap di `PERFORMANCE_MEMORY_REPORT.md`. Waktu "issue" median Settings turun dari 4,2-4,6 ms ke 2,5-3,1 ms, Files dari 2,8 ke 2,1 ms, dan Tedit dari 4,5-4,9 ke 3,6-3,7 ms. p99 Tedit turun dari 66-67 ms ke 31-32 ms.

**Yang belum selesai.** Persentase frame yang lewat anggaran 120 Hz untuk Settings, Notes, dan Files belum turun berarti. Total frame adalah jumlah banyak bagian: thread utama (yang di build debug berjalan di interpreter, lihat RCA-5), "issue", swap, dan jeda. Tidak ada satu bagian pun yang dominan lagi.

## RCA-3. Keyboard tertutup saat menyentuh area keyboard (masih terbuka)

**Pengamatan.** Riwayat keyboard Android (`dumpsys input_method`, `mImeTrackerService#History`) dari pemakaian pemilik pada 12 November mencatat dua kejadian yang cocok dengan keluhan, keduanya di Brave dalam jendela LX (display 36):

- 18:10:05,366 keyboard muncul (animasi 1.174 ms), lalu 18:10:06,728 Brave sendiri meminta keyboard ditutup (`HIDE_SOFT_INPUT`, `ORIGIN_CLIENT`).
- 23:31:27,513 keyboard muncul (1.224 ms), lalu 23:31:28,755 Brave menutupnya dengan cara yang sama. Permintaan berikutnya pada 23:31:30,5 juga berupa penutupan.

Kolom teksnya kolom halaman web (`inputType 0x840a1`, variasi `WEB_EDIT_TEXT`). Sepanjang riwayat itu tidak ada penutupan yang berasal dari LX.

**Reproduksi.** Probe WebView baru (`KeyboardWebProbeActivity`, mesin yang sama dengan Chrome dan Brave) dan skrip `scripts/ui_test_keyboard_web.py`:

- 50 ketukan tunggal pada tombol huruf, 5 rentetan 8 ketukan cepat, 14 ketukan 0,3 sampai 1,5 detik setelah kolom diketuk, dan 8 jendela yang langsung diketik setelah dibuka. Semua lulus pada build lama di ponsel maupun C3.
- Ketukan 0,15 detik setelah kolom diketuk, sebelum keyboard terlihat, jatuh ke halaman. Halaman lalu mencatat `field blur`, dan WebView menutup keyboard dengan `HIDE_SOFT_INPUT` dari client. Polanya sama dengan catatan Brave.

Jadi mekanismenya terbukti: ketukan yang sampai ke halaman, bukan ke keyboard, membuat Chromium melepas fokus kolom lalu menutup keyboard. Yang belum terbukti adalah apa yang membuat ketukan pemilik sampai ke halaman pada saat keyboard sudah tampak.

**Cacat yang ditemukan dan diperbaiki.** `EmbeddedAppTool.forward` menghitung skala sentuhan dari `heldX`, `heldY`, dan `showingTop`, terpisah dari transform yang dipasang ke `TextureView`. Ada dua urutan yang membuat keduanya berbeda:

1. `holdShape()` memasang bentuk tertahan (`heldX` < 1), lalu `showTop(true)` mengganti transform tetapi membiarkan `heldX`. Ketukan dipetakan dengan skala yang tidak lagi digambar.
2. `showTop(false)` mengosongkan transform tanpa mereset `heldX` dan `heldY`.

Urutan ini terjadi bila keyboard LX sendiri, misalnya dari kolom cari menu Aplikasi, terbuka atau sedang turun sementara jendela aplikasi terlihat. Selama itu ketukan pada aplikasi bergeser dari tempat yang dilihat pemilik. Perbaikannya, `forward` membaca transform dari `TextureView` (`getTransform`) dan memetakan balik lewat `viewToDisplay`, sehingga gambar dan ketukan selalu memakai satu sumber. `showTop` kini juga mereset bentuk tertahan dan memasangnya lagi bila perlu.

**Status.** Belum dinyatakan selesai. Cacat di atas nyata dan sudah diperbaiki, tetapi hubungannya dengan kejadian Brave belum terbukti, karena kejadian itu tidak bisa diulang tanpa Brave milik pemilik. Uji di Brave atau Chrome perlu izin pemilik, karena memakai aplikasi dan data miliknya.

## RCA-4. Tata letak pemilik tertimpa tata letak bawaan saat uji (alat uji, bukan LX)

**Gejala.** Pukul 01:25 LX mulai dengan jendela welcome, dan `layout loaded in 15 ms (1 windows, 3 widgets)`. Tata letak pemilik (palet arctic, gaya jendela custom, wallpaper `ember_shards`, workspace 4) diganti tata letak bawaan.

**Bukti.** `lxdb: LX died; releasing display 42` pukul 01:25:00,594, dan LX sudah memuat tata letak pada 01:25:00,797. Android menyalakan LX lagi sebagai Home sekitar 200 ms setelah `am force-stop`.

**Akar masalah.** `restore_data` di `scripts/ui_test_file_manager.py` dan `scripts/ui_test_app_windows.py` menghentikan LX, menghapus `files` dan `shared_prefs`, lalu mengekstrak cadangan. LX yang hidup lagi di sela langkah itu tidak menemukan tata letak, membuat yang bawaan, lalu menyimpannya di atas berkas yang baru dipulihkan.

**Perbaikan.** Cadangan kini diekstrak ke `restore.tmp` di samping folder aktif, ditukar dengan dua `mv` di antara dua `force-stop`, lalu header `files/layout.lxl` di ponsel dibandingkan dengan salinannya. Bila belum cocok, langkah itu diulang sampai empat kali, dan setelah itu skrip berhenti dengan pesan galat. `save_data` juga menolak cadangan yang tidak berisi tata letak.

**Verifikasi.** Data pemilik dipulihkan dari cadangan pukul 01:18 (`LXL1 f25abd4f 3971`). LX memuat tata letak itu (`0 windows, 3 widgets`), dan setiap uji sesudahnya mengembalikan header yang sama.

## RCA-5. LX terasa berat saat dibuka (sebagian bawaan build debug)

**Ukuran.** Start dingin di Infinix: frame pertama 461 sampai 735 ms setelah proses dimulai, 183 sampai 329 ms setelah activity dibuat. Membuka jendela: Settings 85 sampai 113 ms, File Explorer 56 sampai 76 ms, Monitor 45 sampai 64 ms, Notes 27 sampai 46 ms, dan Tedit 119 sampai 167 ms.

**Bukti.** Di profil resize, thread utama didominasi `art::interpreter::ExecuteSwitchImplCpp` dan `CheckJNI`. `dumpsys package dexopt` mencatat LX berstatus `verify`. `cmd package compile -m speed -f com.lxlauncher` berhasil dijalankan, tetapi statusnya tetap `verify`: Android 15 hanya memverifikasi aplikasi `debuggable`.

**Kesimpulan.** Pada build debug, kode LX berjalan di interpreter setiap kali prosesnya dimulai ulang, sampai JIT menghangat. ROM Infinix membekukan dan mematikan LX di latar belakang, jadi ini sering terjadi. Hal ini tidak bisa diatasi dari ponsel tanpa build rilis bertanda tangan. Rekomendasinya ada di `FINAL_PROJECT_HEALTH_REPORT.md`.

## Catatan: crash Tedit 12 November

Buffer crash ponsel mencatat dua crash LX pada 12 November pukul 01:39 dan 01:42, keduanya di Tedit: `IndexOutOfBoundsException` dari `CodeEditView.matchBracket`, dan `StackOverflowError` dari rekursi `ExplorerPanel` ke `TeditTool.refreshPanelActions`. Keduanya sudah diperbaiki di source sebelum sesi ini. `onDraw` kini menjepit kursor ke isi dokumen lewat `clampSession`, dan `refreshPanelActions` hanya memakai panel yang sudah selesai dibangun. Selama sesi ini tidak ada crash LX baru di buffer crash.
