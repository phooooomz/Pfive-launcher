# ADR-005: Membuka aplikasi Android sebagai jendela freeform

Status: diterima, 29 Oktober 2026. Melengkapi tingkat 2 di ADR-003.

## Masalah

Di Infinix HOT 60 5G (XOS 15.1.2, Android 15), aplikasi yang dibuka dari LX selalu tampil layar penuh, padahal LX sudah mengirim `ActivityOptions.setLaunchBounds`. Dua penyebab terbukti di perangkat:

1. Freeform mati di konfigurasi platform. Ponsel itu tidak melaporkan `FEATURE_FREEFORM_WINDOW_MANAGEMENT`. Freeform baru tersedia setelah `enable_freeform_support=1` dinyalakan (Opsi pengembang, "Enable freeform windows") dan ponsel dihidupkan ulang.
2. LX tidak meminta mode freeform. Android hanya memakai launch bounds kalau area tampilannya sudah freeform atau kalau peluncur meminta mode itu secara eksplisit. Area tampilan ponsel selalu fullscreen, jadi bounds diabaikan.

`ActivityOptions.setLaunchWindowingMode` tidak bisa dipakai: metode itu `@TestApi`, dan pemanggilan lewat refleksi ditolak oleh pembatasan hidden API (`NoSuchMethodException`).

## Keputusan

`AppRepository.launchOptions` mengirim launch bounds bersama kunci bundle `android.activity.windowingMode` bernilai 5 (`WINDOWING_MODE_FREEFORM`). Kunci ini dibaca oleh konstruktor `ActivityOptions(Bundle)` di sisi sistem. LX tidak memanggil API tersembunyi dan tidak memakai refleksi; ia hanya menaruh satu angka di bundle yang memang dikirim ke `startActivity`.

Bounds dan mode dikirim selama Pengaturan LX > Jendela menyalakan jendela aplikasi (bawaannya menyala). LX tidak memeriksa dukungan perangkat sebelum mengirimnya, dan mengandalkan Android untuk membuka aplikasi layar penuh kalau freeform tidak didukung. Asumsi itu belum diuji di perangkat dengan freeform mati, karena kedua perangkat uji sudah mendukung freeform saat kunci ini ditambahkan. Itu uji pertama yang perlu dijalankan di perangkat baru.

### Ukuran jendela, diubah 30 Oktober 2026

Bounds awal selebar 3/4 kanvas membuat jendela di ponsel terlalu sempit untuk aplikasi yang ditata untuk layar penuh. Di Infinix, Kalkulator terbuka selebar 258 dp. `LauncherActivity.appWindowBounds` sekarang membedakan dua kasus:

- Kanvas di bawah 600 dp (ponsel tegak): lebar kanvas dikurangi margin 8 dp di setiap sisi, dan tinggi 78% kanvas tanpa melewati tinggi area dikurangi tiga langkah kaskade. Setiap jendela berikutnya turun 28 dp, berulang setiap empat jendela. Kalkulator di Infinix menjadi 328 dp.
- Kanvas yang lebih lebar: bentuk mengikuti `screenOrientation` aktivitas di manifest. Aplikasi tegak mendapat 92% tinggi area dengan rasio 9:16 (minimal 360 dp), aplikasi mendatar 72% lebar dengan rasio 16:10 (minimal 480 dp), dan sisanya 62% x 82%. Jendela diletakkan sedikit ke kiri atas dari tengah, lalu dikaskade.

### Jendela baru dibuka dan game, diubah 31 Oktober 2026

Jendela aplikasi kini mengikuti setelan yang sama dengan jendela LX, "Jendela baru dibuka" (`window.defaultState`). Satu fungsi, `opensMaximized`, memutuskan untuk keduanya, sehingga Otomatis berarti maksimal di kanvas di bawah 600 dp dan mengambang di kanvas yang lebih lebar.

- Maksimal: bounds sama dengan area jendela LX di antara panel atas dan bilah bawah. Aplikasi mendapat seluruh ruang itu, dan panel tetap bisa diketuk karena jendela tidak menutupinya. Di Infinix, Kalkulator terbuka di (16,184)-(704,1392) dan isinya dimulai di y=268, tepat di bawah caption XOS.
- Mengambang di kanvas sempit: lebar tetap kanvas dikurangi 8 dp di setiap sisi, tetapi tingginya kini tinggi ruang dikurangi tiga langkah kaskade, bukan 78% kanvas. Empat jendela yang bertumpuk tetap berada di atas bilah bawah. Kalkulator di Infinix menjadi (32,200)-(688,1208).
- Game meminta `WINDOWING_MODE_FULLSCREEN` (1) lewat kunci bundle yang sama, termasuk pintasan game. Di jendela freeform, Android mengabaikan `screenOrientation` aplikasi, sehingga game mendatar tampil tegak di jendela sempit. Di layar penuh, arah yang diminta game dan setelan putar otomatis berlaku lagi. Game dikenali dari `ApplicationInfo.CATEGORY_GAME` atau dari `FLAG_IS_GAME` lama, dan aturan yang sama dipakai submenu Game. Mahjong Blast di Infinix terbuka dengan `mode=fullscreen` di (0,0)-(720,1600). Arah mendatar belum diuji dengan game sungguhan.

Orientasi dibaca lewat `LauncherActivityInfo.getActivityInfo()` di API 31 ke atas dan lewat `PackageManager.getActivityInfo` di bawahnya, karena metode pertama tidak ada di Android 8 sampai 11 dan akan crash di sana. Lint `NewApi` menangkap pemanggilan yang tidak dijaga itu sebelum rilis.

## Audit kompatibilitas dan perawatan

- Kunci ini bukan API publik, jadi namanya tidak dijamin tetap. Kalau suatu rilis Android menggantinya, sistem tidak lagi membaca angka itu dari bundle dan permintaannya kembali hanya berupa launch bounds. Tidak ada crash dan tidak ada `SecurityException` yang teramati di Android 13 maupun 15.
- Android tetap yang memutuskan bentuk jendelanya. LX hanya meminta, dan tidak bisa memaksa aplikasi tertentu menjadi jendela.
- Yang perlu diuji ulang setiap target SDK naik: `scripts/ui_test_app_windows.py` di satu perangkat yang mendukung freeform.

## Batas platform yang teramati

- Jendela freeform selalu berada di atas layar Home, termasuk taskbar LX. Jendela yang digeser ke bawah bisa menutupi taskbar; jendelanya perlu diminimalkan atau digeser dulu.
- Di XOS, fokus pindah ke jendela lain hanya kalau sentuhan ditangani isinya (tombol, gulir). Ketukan di caption atau di area kosong tidak memindahkan fokus.
- Di Waydroid (Android 13), tombol tutup di caption tidak berbuat apa pun (tidak ada satu baris pun di log), padahal minimise dan maximise di baris yang sama bekerja. Caption jendela yang dimaksimalkan berada di bawah status bar sehingga tidak bisa disentuh.
- Selama jendela aplikasi memegang fokus, sentuhan pada LX sampai ke LX tetapi fokus keyboard tetap di aplikasi. Teks yang diketik di kolom cari menu Aplikasi masuk ke aplikasi itu. Terlihat di Waydroid dan XOS. Tekan Home dulu, lalu ketik. LX bisa memaksa fokus hanya dengan membawa dirinya ke depan seperti tombol Home, yang ikut menyembunyikan semua jendela aplikasi; itu belum dipilih.
- Android 13 membawa task yang sedang dimaksimalkan ke depan tanpa mengubah modenya, karena opsi peluncuran hanya membentuk task baru. XOS mengembalikannya ke jendela. Di Waydroid, Jam yang task-nya sudah dihentikan lalu dibuka lagi dari LX kembali menjadi jendela.

## Bukti

`scripts/ui_test_app_windows.py` membaca mode dan frame dari `dumpsys activity` dan `dumpsys window`, bukan dari tangkapan layar.

- Waydroid, 29 Oktober 2026: 21 lolos, 0 gagal, 2 batas platform (tutup di caption saat jendelanya terbukti di depan dan memegang fokus, task maksimal). Kalkulator, Pengaturan, Jam, Kontak, dan Galeri terbuka sebagai jendela; dua dan tiga jendela tampil bersamaan; sentuhan, keyboard, geser, ubah ukuran, minimise, restore, dan maximise bekerja.
- Infinix, 29 Oktober 2026 (dengan `enable_freeform_support=1` dan `force_resizable_activities=1` yang dinyalakan pemilik ponsel): Kalkulator pertama kali terbuka di frame (18,251)-(534,1157) dengan mode `freeform` dan caption sistem. Chrome, YouTube, WhatsApp, Files, dan Pengaturan juga terbuka sebagai jendela. Uji lengkap: 27 lolos, 0 gagal, termasuk tutup lewat caption, maximise, dan kembali ke jendela dari LX.
