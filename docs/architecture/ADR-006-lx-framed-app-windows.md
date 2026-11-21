# ADR-006: Aplikasi Android di dalam jendela LX lewat Shizuku

Status: diterima, 31 Oktober 2026. Jalur Shizuku di ADR ini dihapus 2 November 2026 dan digantikan ADR-007. `EmbedHelper` dan `EmbedService` digantikan `lxdb` di ADR-008, jadi janji "tidak ada perintah shell bebas" dan `ImageReader` parkir tidak berlaku lagi. Batas bawah Mode B kini Android 14. Layar virtual dan pembagian mode tetap berlaku. Melengkapi ADR-003 (tingkat jendela) dan ADR-005 (jendela freeform).

## Masalah

Pengguna ingin jendela Chrome, YouTube, dan aplikasi lain memakai bingkai LX: ikon dan nama aplikasi, tombol kuning, hijau, dan merah, serta tepi membulat. Di Infinix (XOS, Android 15) jendela freeform memakai caption hitam milik sistem.

Riset atas sumber AOSP 11 sampai 16 menunjukkan siapa yang menggambar caption itu. Sampai Android 13, caption digambar `DecorCaptionView` di dalam proses aplikasinya sendiri. Mulai Android 14, caption digambar WM Shell di SystemUI (`CaptionWindowDecoration`, atau `DesktopModeWindowDecoration` di perangkat yang punya mode desktop). Tidak ada API publik yang membuat launcher bisa mengubah, menyembunyikan, atau mengganti caption aplikasi lain. API caption yang ada (`APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND`, `WindowInsets.Type.captionBar`) hanya bisa dipakai aplikasi pemilik jendela, dan hanya di mode desktop. `TaskOrganizer` butuh `MANAGE_ACTIVITY_TASKS`, yang hanya dimiliki sistem, aplikasi recents, dan shell. Mendaftarkan organizer kedua dari shell akan merebut task dari SystemUI dan merusak freeform, split, serta PiP.

## Keputusan

Ada tiga jalur, dipilih menurut kemampuan perangkat.

| | Mode A: launcher biasa | Mode B: hak shell lewat Shizuku | Mode C: integrasi sistem |
|---|---|---|---|
| Cara kerja | `ActivityOptions.setLaunchBounds` dan kunci mode jendela (ADR-005) | Aplikasi berjalan di layar virtual milik shell. LX menampilkan layar itu di `TextureView` di dalam jendela LX biasa, lalu meneruskan sentuhan dan tombol ke layar itu | Mengganti dekorasi jendela di SystemUI (WM Shell) atau lewat hook root |
| Izin | Tidak ada | Shizuku dinyalakan pengguna lewat ADB atau wireless debugging; LX mendapat izin di dialog Shizuku | Tanda tangan platform, peran recents, atau root |
| Android | 7+, tergantung freeform | 13+ (shell mendapat `ADD_TRUSTED_DISPLAY` di Android 13) | Sesuai ROM |
| Bingkai | Caption sistem | Bingkai LX penuh, sama dengan alat LX | Bingkai apa pun |
| Kalau tidak tersedia | Layar penuh biasa | Kembali ke Mode A | Tidak dibangun |

Mode C tidak dibangun. Launcher biasa tidak bisa menjalankannya, dan ADR ini tidak menganggapnya tersedia.

### Mode B

- **Setelan.** Pengaturan LX > Jendela > "Bingkai jendela aplikasi" berisi LX (bawaan sejak 1 November 2026) atau Android. Dengan LX, setiap aplikasi mendapat jendela LX, juga saat Shizuku belum berjalan: jendelanya menunjukkan cara menyalakan Shizuku dan membuka aplikasi begitu Shizuku siap. Android 12 ke bawah dan ponsel tanpa Shizuku tetap memakai Mode A. Game dan aplikasi di daftar "Selalu jendela Android" juga memakai Mode A.
- **Layanan di proses shell (`EmbedService`).** Layanan ini membuat layar virtual dengan flag `PUBLIC | OWN_CONTENT_ONLY | SUPPORTS_TOUCH | DESTROY_CONTENT_ON_REMOVAL | TRUSTED | OWN_DISPLAY_GROUP | ALWAYS_UNLOCKED | TOUCH_FEEDBACK_DISABLED`, ditambah `OWN_FOCUS | DEVICE_DISPLAY_GROUP` di Android 14 ke atas. `ROTATES_WITH_CONTENT` sengaja tidak dipakai: dengan flag itu, aplikasi yang terkunci tegak digambar miring di jendela lebar. Aktivitas dibuka dengan `setLaunchDisplayId`. Sentuhan dan tombol disuntikkan dengan `InputEvent.setDisplayId`. Keyboard memakai kebijakan lokal, jadi keyboard terbuka di layar virtual itu sendiri, di dalam jendela.
- **Antarmuka yang sempit.** Binder layanan hanya menerima uid LX. Argumen divalidasi: ukuran 1 sampai 8192, dpi 32 sampai 1280, paling banyak 16 layar, hanya layar milik pemanggil, hanya aktivitas MAIN/LAUNCHER yang diekspor milik pengguna LX atau profil kerjanya. Tombol yang bekerja untuk seluruh ponsel (Power, panggilan, notifikasi, tangkapan layar, Meta, Alt+Tab) ditolak di kedua sisi (`EmbedKeys`). Tidak ada perintah shell bebas dan tidak ada akses berkas.
- **Siklus hidup.** Menutup jendela LX melepas layar virtual, dan task aplikasinya ikut berakhir. Jendela yang diminimalkan atau ada di workspace lain melepas permukaannya; aplikasi berhenti sementara dan lanjut saat jendela tampil lagi. Kalau LX mati, layanan melepas semua layarnya.
- **MediaTek.** Shizuku 13.6.0 tidak bisa menjalankan user service di ponsel MediaTek (RikkaApps/Shizuku#1198, terbuka sejak Juni 2025). Galatnya NPE di `LoadedApk.makeApplicationInner`, karena kode tambahan MediaTek memanggil `Application.getProcessName()` yang bernilai null. Karena itu LX punya jalur kedua: `EmbedHelper`, proses milik LX yang dijalankan sebagai shell lewat `IShizukuService.newProcess`, lalu menyerahkan binder-nya lewat `EmbedBinderProvider`. Provider itu diekspor tetapi dilindungi `INTERACT_ACROSS_USERS_FULL`, hanya menjawab `call("attach")` dari uid 0 atau 2000, hanya menerima token sekali pakai yang dibuat LX dalam 15 detik terakhir, dan tidak menyimpan data.
- **Urutan start.** LX meminta user service lebih dulu. Kalau belum tersambung setelah 5 detik, LX ikut menjalankan helper dan memakai mana pun yang lebih dulu tersambung; yang lain dihentikan. Setelah 15 detik user service yang belum jalan dilepas. LX mencatat kegagalan itu per versi Shizuku dan build ROM, jadi pembukaan berikutnya langsung lewat helper; kalau helper gagal, user service dicoba lagi. Start dianggap gagal hanya kalau kedua jalur gagal. Catatan itu dihapus begitu user service pernah tersambung.
- **Fokus dan keyboard.** Aktivitas dibuka dengan mode jendela fullscreen (`ActivityOptions.setLaunchWindowingMode`), supaya task yang terakhir dipakai sebagai jendela freeform tidak tergeser keluar layar virtual. Sebelum setiap sentuhan yang diteruskan, layanan memindahkan fokus atas ke task di layar itu (`IActivityTaskManager.setFocusedTask`), sehingga keyboard muncul. Keyboard tampil di layar virtual, karena hanya di sana aplikasi melihatnya dan menaikkan kolom ketiknya. Kebijakan lama yang memakai keyboard di layar utama membuat kolom ketik chat WhatsApp tertutup. Saat LX menaikkan jendela di atas keyboard kolom LX sendiri, ukuran layar virtual tidak diubah, karena perubahan itu membuat banyak aplikasi dibuat ulang.
- **Ukuran dan bentuk layar.** Isi aplikasi mengisi bingkai sampai ke garis tepi, dengan sudut bawah mengikuti sudut jendela. Layar virtual selebar jendela. Kalau sisi pendek jendela di bawah 320 dp, layar dibuat sebesar ukuran ponsel dengan bentuk yang sama dan digambar mengecil, jadi tata letak aplikasi tetap utuh. Buffer `SurfaceTexture` mengikuti ukuran layar; dengan buffer selebar view, Android memotong sisi kanan dan bawah. Layar virtual tidak bisa berputar mengikuti aplikasi, jadi aplikasi yang terkunci di bentuk yang tidak cocok dengan jendelanya mendapat layar berbentuk miliknya sendiri, digambar di tengah jendela. Layanan melihat tanda letterbox di `TaskInfo.appCompatTaskInfo`, lalu membaca `requestedOrientation` dari `dumpsys activity -a -p <paket> activities`.
- **Profil kerja.** Layanan menerima pengguna profil dari pengguna LX sendiri, jadi aplikasi kerja ikut terbuka di jendela LX.

## Bukti

- **Infinix X6726B (Android 15), sebagai uid 2000:**
  - Layar virtual dengan flag di atas diterima: `dumpsys display` menunjukkan FLAG_TRUSTED, FLAG_OWN_DISPLAY_GROUP, dan FLAG_OWN_FOCUS.
  - Kalkulator berjalan di layar #2.
  - Ketukan yang disuntikkan menghitung 7 + 8 = 15. Lewat jalur penerusan LX, 8 + 7 + 8 = 23.
  - Ukuran layar berubah ke 672x800, 600x1200, dan 672x480, dan aplikasi menata ulang tampilannya. Di 672x480 Kalkulator memakai tata letak ilmiah mendatar.
  - Mengetik lewat keyboard ponsel dan lewat keyboard di layar virtual sama-sama masuk.
  - Tombol Power, Notifikasi, Meta+A, dan Alt+Tab ditolak dan layar tetap menyala.
- **Lewat user service Shizuku 13.6.0 di ponsel yang sama:** gagal sebelum kode LX berjalan (#1198).
- **Lewat helper LX di ponsel yang sama, dari UI LX, 31 Oktober 2026:**
  - Jendela pertama terbuka 5,3 detik setelah diminta: starter Shizuku mati 0,4 detik setelah bind, helper dijalankan pada detik ke-5 dan tersambung 0,3 detik kemudian. Pembukaan berikutnya 0,27 sampai 0,41 detik, termasuk setelah LX dihentikan paksa.
  - `ps` menunjukkan `com.lxlauncher:embed-helper-u0` milik shell. `dumpsys display` menunjukkan `lx-app4` 656x1084 milik `com.android.shell (uid 2000)`, dan Kalkulator berjalan di layar itu dengan `mode=fullscreen`.
  - Bilah judul LX menampilkan "Kalkulator" dengan ikon aslinya. Ketukan menghitung 7 × 8 = 56 dan 9 × 6 = 54.
  - Menyeret sudut jendela mengubah layar ke 539x836 dan Kalkulator menata ulang tampilannya. Maksimalkan 656x1084, pulihkan kembali ke 539x836.
  - Minimise dari taskbar mematikan layar virtual dan menjeda Kalkulator; restore menyalakannya lagi.
  - Mengetik "wifi" dengan Gboard di pencarian Pengaturan Android menampilkan hasilnya.
  - Tombol tutup melepas layar dan task-nya. Tanpa jendela aplikasi tersisa, helper berhenti sendiri 30 detik kemudian dengan kode 0. Kalau LX mati, helper melepas layarnya dan keluar.
  - `content call` ke provider dari shell dengan token karangan dijawab `null`.
  - Skenario review: catatan kegagalan ada, helper dibunuh sebelum tersambung. LX beralih ke user service 3 ms kemudian, menyerah setelah 15 detik dengan pesan dan tombol Coba lagi, dan Coba lagi tersambung lewat helper dalam 0,27 detik.
- **Instrumentation:** 28 lulus, 0 gagal, termasuk 13 cek Mode B. Tujuh di antaranya memeriksa penerusan tombol, penolakan tombol global, sentuhan dan tombol mouse, perutean, pesan penyebab, dan siklus hidup. Enam lainnya memeriksa token helper, perintah helper, penjagaan provider, kebijakan pemanggil, dan urutan start dalam sembilan urutan kejadian.
- **R8:** build release mempertahankan nama `EmbedHelper.main`, `EmbedService`, dan `EmbedBinderProvider`.

## Batas

- Konten FLAG_SECURE (perbankan, video DRM) tampil hitam, karena shell tidak boleh membuat layar virtual yang aman.
- Shizuku harus dinyalakan ulang setiap ponsel dinyalakan ulang.
- Aplikasi yang sudah terbuka di layar utama pindah ke jendela LX, dan menutup jendela itu menutup task aplikasinya.
- Kartu Recents dari aplikasi yang pernah dibuka tetap ada setelah jendelanya ditutup.
- Aplikasi yang terkunci tegak tampil lebih kecil di tengah jendela yang lebar, dengan sisa ruang berwarna bingkai.
- Setelah ponsel dinyalakan ulang, Shizuku harus dijalankan sekali. Mulai otomatis tanpa root butuh wireless debugging, dan itu butuh Wi-Fi.
- Di ponsel yang terkena #1198, jendela aplikasi pertama setelah Shizuku atau ROM diperbarui butuh sekitar 5 detik.
- Setiap sentuhan di jendela aplikasi memindahkan fokus ponsel ke aplikasi itu.
- Pesan "Shizuku berhenti" juga muncul kalau yang mati adalah helper LX.
- Hanya Android 15 yang diuji. Android 13 dan 14, Shizuku lewat root, dan pengguna sekunder belum diuji. Pemanggilan provider dari helper hanya memakai bentuk `IContentProvider.call` Android 12 ke atas, dan helper hanya dijalankan di Android 13 ke atas.
