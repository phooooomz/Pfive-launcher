# Kompatibilitas dan batasan yang diketahui

## Perangkat yang diuji

| Perangkat | Android | ABI | Layar | Hasil |
|---|---|---|---|---|
| Waydroid | 13 (API 33) | x86_64 | 1920x1028, 180 dpi, mendatar | Semua test lulus |
| Infinix HOT 60 5G (X6726B) | 15 (API 35), XOS | arm64-v8a | 720x1600, 320 dpi, tegak | Semua test lulus, 21.000 event Monkey tanpa crash |

## Windowing per perangkat

| | Waydroid | Infinix HOT 60 5G (X6726B) |
|---|---|---|
| Android, API | 13, API 33 | 15, API 35, XOS |
| Fitur freeform yang dilaporkan | Ya (`FEATURE_FREEFORM_WINDOW_MANAGEMENT`) | Tidak. Freeform tersedia setelah "Enable freeform windows" (`enable_freeform_support=1`) dinyalakan di Opsi pengembang dan ponsel dihidupkan ulang; pemilik ponsel juga menyalakan `force_resizable_activities=1` |
| Multi-window | Tersedia (bukan perangkat bermemori rendah) | Tersedia (bukan perangkat bermemori rendah) |
| Izin yang diberikan | Home, satu folder uji untuk LX Files, satu widget Clock | Home, satu folder uji untuk LX Files. 31 Oktober 2026: akses semua file selama uji File Manager, dicabut sesudahnya; `lxdb` dijalankan LX Bridge lewat `adbd` ponsel untuk uji Mode B |
| Hasil | Kalkulator, Pengaturan, Jam, Kontak, dan Galeri terbuka sebagai jendela freeform; tiga jendela tampil bersamaan | Kalkulator, Pengaturan, Jam, Chrome, YouTube, WhatsApp, dan Files terbuka sebagai jendela freeform (`mode=freeform`); tiga jendela tampil bersamaan. Sebelum 29 Oktober 2026 Kalkulator terbuka layar penuh karena freeform mati dan LX belum meminta mode freeform. Sejak 30 Oktober 2026 jendela dibuka selebar layar dikurangi 8 dp di setiap sisi: Kalkulator di (32,200)-(688,1142), 328 dp, sebelumnya 258 dp. Sejak 31 Oktober 2026 jendela mengikuti "Jendela baru dibuka": dengan Otomatis Kalkulator terbuka maksimal di (16,184)-(704,1392), dengan Mengambang di (32,200)-(688,1208). Mahjong Blast, sebuah game, terbuka dengan `mode=fullscreen` di (0,0)-(720,1600) |
| Batas platform | Tombol tutup di caption tidak berbuat apa pun. Caption jendela yang dimaksimalkan berada di bawah status bar. Task yang dimaksimalkan tetap layar penuh saat dibuka lagi dari LX | Fokus hanya pindah kalau sentuhan ditangani isi jendela; ketukan di caption atau area kosong tidak memindahkannya. Floating Window XOS sendiri tidak punya API publik untuk launcher lain |
| Cadangan yang dipakai | Minimise, lalu buka lagi dari LX | Tidak perlu |

Jendela LX (Pengaturan, Files, Monitor, Catatan) bekerja sama di kedua perangkat karena LX yang menggambarnya. LX tidak pernah menampilkan aplikasi lain di dalam jendelanya sendiri.

## Status fitur

| Fitur | Status | Catatan |
|---|---|---|
| Dipilih sebagai Home lewat dialog Android | VERIFIED | Waydroid. `RoleManager` di Android 10+, pengaturan Home di versi lebih lama |
| Menu Aplikasi bertingkat: cari, kategori, favorit, sembunyikan, terakhir dibuka, seret ke desktop dan taskbar | VERIFIED (Infinix dan Waydroid), NOT TESTED (seret ke dock samping, menunjuk dengan mouse) | Kategori diambil dari `ApplicationInfo.category` yang diisi pembuat aplikasi. Urutan tanggal pasang dihapus bersama laci. Aplikasi sebuah kategori tampil sebagai submenu; di ponsel tegak menu tetap bertingkat dan tidak memenuhi layar. Di Infinix pada ukuran antarmuka 130%, kolom kategori menjadi lajur ikon selama submenu terbuka |
| Membuka aplikasi, info aplikasi, dialog uninstall Android | VERIFIED (buka, dialog uninstall terbuka lalu dibatalkan) | Uninstall tidak diselesaikan di test agar tidak menghapus aplikasi |
| Pintasan aplikasi (shortcut) | IMPLEMENTED / NOT TESTED | Android hanya memberikannya ke launcher default; aplikasi di Waydroid tidak menyediakan shortcut |
| Desktop: pintasan, folder, drag ke sel, drag ke taskbar, dock, dan workspace | VERIFIED | |
| Widget LX: jam, kalender, status, baterai, memori, penyimpanan, jaringan, media, catatan, favorit, perangkat | VERIFIED (jam termasuk format 12 jam, kalender, status, media, catatan yang bertahan setelah proses dimatikan), IMPLEMENTED / NOT TESTED (sisanya) | Semua memakai data asli |
| Widget Android | VERIFIED | Analog clock dari aplikasi Clock |
| Jendela LX: pindah, ubah ukuran, minimise, maximise, snap, tile, layar penuh, pindah workspace | VERIFIED (pindah, minimise, maximise, restore, snap kiri, tile, pindah workspace, tutup dengan Alt F4) | Layar penuh diuji di core |
| Workspace: tambah, ganti nama, hapus, satu wallpaper untuk semua workspace | VERIFIED (tambah, ganti nama, hapus, wallpaper bawaan, wallpaper gambar lewat pemilih foto, wallpaper yang sama di workspace 1 dan 2) | Gambar disalin ke penyimpanan privat LX, paling besar 40 MB. Layout lama dengan wallpaper per workspace memakai wallpaper workspace yang aktif |
| File Manager dengan akses semua file | VERIFIED di Infinix (penjelasan izin lalu halaman Android, penyimpanan internal dan kartu SD, folder baru, berkas teks baru, ganti nama, salin ke kartu SD dan pindah kembali, hapus dengan konfirmasi, cari, properti, berkas tersembunyi, urutan alami, tata letak sempit, sedang, dan lebar, tampilan tanpa izin setelah izin dicabut), NOT TESTED (flashdisk USB, kartu dicabut di tengah operasi, penyimpanan penuh, Android 8 sampai 10) | `scripts/ui_test_file_manager.py` memakai folder LXFM-Test di penyimpanan internal dan kartu SD, lalu menghapus keduanya |
| File Manager lewat SAF (folder pilihan) | VERIFIED sebagai LX Files (izin folder, folder baru, salin dan tempel, ganti nama, detail, hapus dengan konfirmasi, cari), IMPLEMENTED / NOT TESTED sejak menjadi File Manager | Jalurnya sama, sekarang di sidebar File Manager |
| Arsip ZIP, TAR, TAR.GZ | VERIFIED (kompres dan bongkar di Infinix dengan isi identik per md5, bentrok nama dengan Simpan keduanya, ZIP berbahaya dilewati; tes host dengan dan tanpa sanitizer; instrumentation lewat file descriptor) | Batas jumlah entri, ukuran, dan rasio pembengkakan diuji di host |
| Gaya jendela LX Modern Gelap dan Kustom | VERIFIED di Infinix (dipilih dari Pengaturan LX, langsung terpasang; kontras setiap gaya dasar dengan setiap aksen) | Jendela tanpa blur kini padat penuh |
| Aplikasi Android dalam jendela LX (Mode B) | VERIFIED di Infinix, Android 15, lewat `lxdb`: Kalkulator, Chrome, YouTube, WhatsApp, dan Facebook; aplikasi profil kerja pernah lulus lewat helper lama dan belum diulang; NOT TESTED (Android 14 dan 16, pengguna sekunder). Android 13 ke bawah memakai jendela Android | Bawaan sejak 1 November 2026. Aplikasi FLAG_SECURE tampil hitam. Lihat ADR-006, ADR-008, dan FC-07 |
| LX Bridge | Menjalankan `lxdb` lewat `adbd` ponsel, pertukaran kunci, dan format kunci VERIFIED di Infinix, Android 15. Menyala lagi dengan CPU dibebani penuh dan saat port berganti: VERIFIED (10 dari 10). Restart sungguhan gagal sebelum perbaikan 3 November 2026 dan VERIFIED sesudahnya: `lxdb` tersambung 3,2 detik setelah Wi-Fi. Langkah `BridgeSetupService` dan Android 14: NOT TESTED |
| lxdb | VERIFIED di Infinix, Android 15, arm64: semua perintah, jendela, input, jendela tersembunyi, dan orientasi. x86_64: terkompilasi, belum dijalankan. Android 14 dan 16: NOT TESTED. Aplikasi lain, termasuk terminal, tidak bisa memakainya: SELinux menolak |
| Gaya jendela LX, sepuluh gaya ditambah Ikut tema LX | VERIFIED (Waydroid: setiap gaya dipilih dari menu bilah judul dan Pengaturan LX tetap terbuka; Waydroid dan Infinix: kontras teks di atas wallpaper hitam sampai putih, judul di ukuran antarmuka 85% sampai 130%, outline Cyberpunk dengan bounds kosong), NOT TESTED (Android 8 sampai 10) | Hanya jendela LX. Jendela aplikasi Android tetap memakai bingkai Android |
| Jendela layar penuh di area aman, jendela naik di atas keyboard | VERIFIED di Waydroid. Jendela aplikasi layar penuh tanpa bilah judul, Kembali untuk keluar: VERIFIED di Infinix | |
| Keyboard di jendela aplikasi (Mode B) | VERIFIED di Infinix: ketuk kolom, ketik, tombol aplikasi, Kembali, layar yang meminta keyboard saat dibuka, dan kolom LX sesudahnya, juga saat CPU dibebani | Aplikasi uji di APK uji, `scripts/ui_test_keyboard.py` |
| Rotasi tanpa menutup dialog atau pencarian | VERIFIED di Waydroid | Menu yang menempel ke titik sentuh ditutup saat lebar berubah; keyboard tidak menutupnya |
| Layar penuh (immersive) | VERIFIED di Waydroid | Android di bawah 11 belum diuji |
| Matriks kemampuan perangkat di Tentang | VERIFIED di Waydroid | |
| System Monitor | VERIFIED | |
| Ekspor dan impor tata letak | VERIFIED di core, instrumentation, dan lewat UI dengan pemilih dokumen Android | |
| Preset dan settings | VERIFIED (aksen, preset Minimal dan Cyberpunk, mode Performance, kontras tinggi, target sentuh besar), IMPLEMENTED / NOT TESTED (sisanya lewat UI) | Semua nilai divalidasi skema di core |
| Pintasan keyboard | VERIFIED (Ctrl K, Ctrl 1, Ctrl 2, Alt F4, Enter dan Esc di dialog) | |
| Terminal | DEFERRED | Butuh runtime shell dan PTY yang lisensi serta keamanannya diaudit. Terminal palsu tidak dibuat |
| Widget cuaca | DEFERRED | Butuh penyedia data online; LX tidak punya izin internet |
| Beban CPU dan GPU sistem | Tidak didukung Android | Hanya CPU milik proses LX yang ditampilkan |
| Judul lagu di widget media | DEFERRED | Butuh akses notifikasi; widget hanya mengirim tombol media |
| Work profile | VERIFIED (aplikasi kerja tampil di kategori Kerja dengan label "(kerja)"), IMPLEMENTED / NOT TESTED (membukanya) | Diamati di ponsel yang punya profil kerja |

## Batasan Android

- LX tidak bisa memindahkan atau mengubah ukuran jendela aplikasi lain. Jendela aplikasi Android hanya bisa diminta lewat `setLaunchBounds` di perangkat yang mendukung freeform, dan Android yang memutuskan.
- Workspace LX hanya mengelompokkan isi LX. Aplikasi Android tetap berada di daftar tugas Android.
- Jendela freeform aplikasi selalu berada di atas desktop LX, termasuk di atas bilah bawah, menu Aplikasi, pencarian, dan jendela LX. Jendela yang digeser ke bawah bisa menutupi bilah; perkecil atau geser jendelanya. Saat aplikasi itu memegang fokus, ketikan masuk ke aplikasi tersebut. Perilaku ini diatur Android untuk semua launcher; jendela aplikasi bisa diminimalkan lewat tombol di bilah judulnya sendiri.
- Selama jendela aplikasi memegang fokus, ketikan di kolom cari menu Aplikasi masuk ke aplikasi itu, karena Android tidak memindahkan fokus saat layar Home disentuh. Tekan Home dulu, lalu ketik. Terlihat di Waydroid dan Infinix.
- Aplikasi biasa tidak bisa menyalakan "Enable freeform windows"; pemilik perangkat yang melakukannya di Opsi pengembang. LX selalu meminta jendela (ADR-005) dan Android yang menentukan.
- Wallpaper sistem tidak bisa dibaca aplikasi. Blur kaca hanya tersedia dengan wallpaper LX atau gambar pilihan pengguna.
- Gaya jendela LX tidak berlaku untuk bingkai jendela aplikasi Android. Caption dan tepinya digambar Android; LX hanya menentukan di mana dan seberapa besar jendela itu dibuka. Di XOS, isi aplikasi dimulai di bawah caption, jadi caption tidak menutupi aplikasi.
- Game dibuka layar penuh, tetapi game itu sendiri yang menentukan apakah bilah status disembunyikan dan ke arah mana layar diputar. Game yang sudah terbuka sebagai jendela sebelum diperbarui bisa tetap menjadi jendela sampai ditutup, karena di Android 13 opsi peluncuran hanya membentuk task baru. Arah mendatar belum diuji dengan game sungguhan.
- Di Android 15 dengan Google Foto, pemilih foto meminta Anda menekan Selesai setelah memilih satu gambar. Di Android 13 gambar langsung terpilih.
- Di Waydroid, MediaProvider melempar NullPointerException saat berkas di penyimpanan bersama (misalnya di Pictures atau Documents) dibuka lewat pemilih dokumen. LX lalu membuka berkas yang sama lewat MediaStore, dan log mencatat `LxDocs: document open failed`. Di Android 13, wallpaper gambar dipilih lewat pemilih foto, jadi jalur ini tidak terpakai.
- Android 13 meninggalkan `SurfaceControl` dari `View.startDragAndDrop` tanpa dilepas, sehingga log memuat peringatan CloseGuard setelah ikon atau widget diseret. Objek itu hanya dipegang framework, jadi aplikasi tidak bisa melepasnya.
- Notifikasi tidak bisa dibuka dari launcher tanpa izin sistem. Tombol Android di panel atas membuka Pengaturan Android.
- Daftar aplikasi yang sedang berjalan tidak tersedia untuk aplikasi biasa. "Terakhir" di menu Aplikasi berarti aplikasi yang dibuka dari LX.

## Kembali ke launcher lama

1. Buka LX Settings, bagian Tentang, lalu pilih "Kembali ke launcher sebelumnya". Pilihan ini membuka pengaturan aplikasi Home di Android.
2. Pilih launcher lama.
3. Tata letak LX tetap tersimpan kalau suatu saat Anda kembali. Untuk menghapusnya, copot pemasangan LX-LAUNCHER.

Lewat ADB: `adb shell cmd package set-home-activity <komponen launcher lama>`.
