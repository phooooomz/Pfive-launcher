# Feature contract

Kontrak untuk fitur yang ditambah atau diubah sejak brief master prompt (28 Oktober 2026). Status memakai istilah dari brief: VERIFIED berarti sudah diamati di perangkat atau test otomatis, INCOMPLETE berarti sebagian kriteria belum terbukti, UNSUPPORTED berarti Android tidak mengizinkannya untuk launcher biasa.

## FC-01 Gambar referensi tidak pernah menjadi wallpaper

| Bagian | Isi |
|---|---|
| Deskripsi | `referensi.png` hanya cetak biru desain. APK tidak memuatnya, dan uji UI tidak memakainya sebagai gambar wallpaper. |
| Input | Build APK, fixture uji dari `scripts/make-test-wallpaper.py` |
| Output | Desktop baru memakai wallpaper "Ikut tema" yang digambar kode; perangkat uji kembali ke data kosong setelah uji |
| Dependency | `scripts/test-device.sh`, `scripts/ui_test*.py` |
| Izin | Tidak ada |
| Lifecycle | Setiap batch uji memanggil `pm clear` di awal dan di akhir |
| Error handling | `test-device.sh` gagal kalau daftar isi APK memuat `referensi` |
| Kriteria terima | APK bebas dari gambar referensi; batch 3 lulus "New install starts on the theme wallpaper" dan "Theme wallpaper choice is applied" |
| Bukti | Lihat `docs/testing/test-report.md`, bagian master prompt |
| Status | VERIFIED di Waydroid: daftar isi APK tanpa `referensi`, batch 3 lulus 32 dari 32 dengan gambar uji 900x1600. Infinix NOT TESTED untuk fase ini (ponsel tidak tersambung) |

Akar masalah: `scripts/test-device.sh` menyalin `referensi.png` ke perangkat sebagai fixture pemilih foto, `ui_test_full.py` memilihnya sebagai wallpaper, lalu tidak ada langkah yang mengembalikan wallpaper. LX menyimpan salinan gambar yang dipilih, sehingga gambar referensi tetap tampil sebagai latar desktop setelah uji. Kode aplikasi tidak pernah memuat gambar itu.

## FC-02 Matriks kemampuan perangkat

| Bagian | Isi |
|---|---|
| Deskripsi | Pengaturan LX > Tentang menampilkan 15 kemampuan dengan status SUPPORTED, CONDITIONAL, PERMISSION_REQUIRED, UNSUPPORTED, atau UNKNOWN |
| Input | `PackageManager.hasSystemFeature`, `ActivityManager.isLowRamDevice`, `RoleManager` lewat `HomeRole`, `LauncherApps.hasShortcutHostPermission`, `persistedUriPermissions`, `StorageManager.storageVolumes`, `Configuration`, `InputDevice` |
| Output | Baris berlabel teks (warna bukan satu-satunya tanda) dan log `LxCaps` satu baris per kemampuan |
| Dependency | `system/DeviceCapabilities.kt`, `tools/SettingsTool.kt` |
| Izin | Tidak ada izin baru |
| Lifecycle | Dihitung ulang setiap halaman Tentang dibuka |
| Error handling | Volume penyimpanan yang tidak terbaca menjadi UNKNOWN |
| Ketidakpastian | Android tidak menyediakan API untuk membaca saklar "Enable freeform windows". Tanpa fitur sistem, statusnya CONDITIONAL dan teksnya menyuruh mencoba |
| Kriteria terima | Status sesuai kenyataan di perangkat yang diuji |
| Bukti | Log `LxCaps` Waydroid: `app_windows=SUPPORTED`, lalu dua aplikasi benar-benar terbuka `mode=freeform` |
| Status | VERIFIED di Waydroid; Infinix NOT TESTED untuk halaman ini |

## FC-03 Operasi berkas yang aman (File Manager)

| Bagian | Isi |
|---|---|
| Deskripsi | Jelajah penyimpanan internal, kartu SD, USB, dan folder pilihan; buat folder dan berkas teks, ganti nama, salin, potong dan tempel, salin ke, pindahkan ke, hapus, pilih banyak, cari, urutkan, berkas tersembunyi, properti dengan pratinjau, buka dengan, bagikan |
| Input | Akses semua file (opt-in) atau folder dari pemilih Android |
| Output | Perubahan di penyimpanan; pesan sukses hanya setelah operasi selesai |
| Dependency | `files/FileManagerTool.kt`, `files/FileJobs.kt`, `files/LocalBackend.kt`, `files/SafBackend.kt`, `files/AndroidStorage.kt`, `files/Aliases.kt`, `files/Journal.kt`, `files/ShareProvider.kt`, `ui/overlays/CascadeMenu.kt` |
| Izin | `MANAGE_EXTERNAL_STORAGE` (11+) atau izin penyimpanan lama (8 sampai 10), hanya setelah pengguna mengetuknya; tanpa itu, grant folder dari pemilih |
| Lifecycle | Operasi milik proses (`FileJobs`, dua pekerja), tidak ikut berhenti saat jendela ditutup atau dibangun ulang; sisa `.lxpart` dan `.lx-extract` dibersihkan lewat journal |
| Error handling | Tulis ke `.lxpart` lalu ganti nama; Ganti lewat `.lxkeep`; pindah menghapus hanya sumber yang tersalin dan terverifikasi; berkas yang sama lewat dua jalan dikenali; konflik ditanyakan; `Throwable` di pekerja dilaporkan tanpa mematikan launcher |
| Performa | Daftar folder di thread pekerja; thumbnail dibatasi 8 MiB cache; pencarian dibatasi 20.000 item diperiksa dan 1.000 hasil, dengan keterangan saat batas tercapai |
| Kriteria terima | Tidak ada jalur yang menghapus satu-satunya salinan; semua operasi dasar berjalan di penyimpanan internal dan kartu SD |
| Bukti | `scripts/ui_test_file_manager.py` di Infinix, 31 Oktober 2026: 22 lulus, 0 gagal (salin ke kartu SD dan pindah kembali, ganti nama, hapus dengan konfirmasi, cari, properti); 71 pemeriksaan JVM agen penyimpanan termasuk reproduksi dan uji mutasi; review independen |
| Status | PASS. Belum diuji di perangkat: USB OTG, kartu SD dicabut saat operasi, penyimpanan penuh, Android 8 sampai 10 |

## FC-11 Arsip ZIP, TAR, dan TAR.GZ

| Bagian | Isi |
|---|---|
| Deskripsi | Kompres berkas dan folder, ekstrak di sini atau ke folder lain, periksa arsip, properti arsip |
| Input | Berkas atau folder dari backend mana pun; arsip apa pun dari pengguna |
| Output | Arsip dengan path relatif terhadap induk sumber; folder hasil ekstrak |
| Dependency | `core/src/archive_*.cpp`, `app/src/main/cpp/archive_jni.cpp`, `files/NativeArchive.kt`, `files/FileJobs.kt` |
| Izin | Sama dengan FC-03 |
| Lifecycle | Streaming dari file descriptor, tanpa memuat arsip ke memori; ekstraksi ke staging tersembunyi lalu dipindahkan |
| Error handling | Path berbahaya, link, perangkat, dan entri terenkripsi dilewati dan dihitung; CRC ZIP dan gzip diperiksa; batas entri, byte, dan rasio |
| Performa | Buffer paling besar 256 KiB; central directory ZIP dengan 400.000 entri terbaca dalam 2,5 detik |
| Kriteria terima | Arsip buatan LX terbaca alat lain dan sebaliknya; tidak ada entri yang tertulis di luar tujuan |
| Bukti | 179 test host (biasa dan ASan/UBSan), interop dengan Python zipfile/tarfile, `unzip`, `tar`, dan `ZipInputStream`; fuzz sampai 20.000 iterasi; instrumentation "the archive engine writes and reads ZIP, TAR and TAR.GZ through file descriptors on the device"; uji UI kompres tiga format dengan isi identik byte demi byte dan ZIP berbahaya |
| Status | PASS |

## FC-12 Tombol File Manager di bilah bawah

| Bagian | Isi |
|---|---|
| Deskripsi | Tombol tetap tepat di kanan tombol Aplikasi; membuka File Manager, lalu bertindak sebagai entri taskbar jendelanya |
| Dependency | `ui/panels/Panels.kt` (`filesButton`) |
| Error handling | Pin `tool:files` di dock dan taskbar tidak ditampilkan dua kali |
| Bukti | Uji UI: tombol di x 112 sampai 196 tepat setelah Aplikasi (20 sampai 104); dua ketukan berikutnya memperkecil dan memunculkan jendela yang sama, jumlah jendela tetap 1 |
| Status | PASS |

## FC-04 Jendela LX, bilah sistem, dan keyboard

| Bagian | Isi |
|---|---|
| Deskripsi | Jendela layar penuh mengisi area aman (di dalam bilah status, bilah navigasi, dan notch) tanpa bilah judul, tepi, dan bayangannya, jadi isinya memakai seluruh area itu. Kembali mengembalikan keadaan sebelum layar penuh (maksimal, mengambang, atau tertempel), lengkap dengan bilah judul. Pulihkan dari menu tetap ke jendela mengambang. Jendela naik di atas keyboard |
| Input | `WindowInsets` (`systemBars`, `displayCutout`, `ime`) |
| Output | `NativeCore.setViewport` dengan area jendela, area aman, dan rasio kanvas desktop |
| Dependency | `LauncherActivity.computeFrames`, `core/src/engine.cpp`, `core/src/window_manager.cpp` |
| Lifecycle | Dihitung ulang di setiap layout; tidak membangun ulang jendela |
| Error handling | Kanvas yang lebih pendek dari 120 dp tidak menimbulkan crash saat keyboard muncul |
| Kriteria terima | Tidak ada judul jendela di bawah bilah status; kolom teks di jendela tidak tertutup keyboard; di layar penuh tidak ada bilah judul, dan Kembali tidak sampai ke isi jendela |
| Bukti | Waydroid: layar penuh `[0,32][1920,965]` (sebelumnya seluruh layar), Pulihkan kembali ke `[255,138][1245,700]`, jendela Catatan `[255,138][1245,700]` menjadi `[255,114][1245,397]` saat keyboard muncul. Test core `ToggleLeavesFullscreenToo`, `LeavingFullscreenGoesBackToTheStateBefore`, `TheStateBeforeFullscreenSurvivesARestart`. Infinix, 3 November 2026, `scripts/ui_test_window_controls.py`: aplikasi di jendela layar penuh mendapat 720×1440 (sebelumnya 682×1097 dengan bilah judul), Kembali mengembalikan 682×1097 dan bilah judulnya |
| Status | VERIFIED di Waydroid dan Infinix |

## FC-05 Rotasi tanpa membangun ulang

| Bagian | Isi |
|---|---|
| Deskripsi | Rotasi dan perubahan ukuran jendela hanya memicu layout ulang. Kepadatan, skala font, mode malam, dan bahasa tetap membangun ulang tampilan |
| Lifecycle | `rebuildUi()` kini memanggil `onHidden` dan `onDestroy` untuk setiap tool sebelum membuangnya, jadi timer System Monitor dan listener Pengaturan ikut berhenti |
| Error handling | Menu yang menempel ke titik sentuh ditutup saat lebar berubah, atau saat tinggi baru tidak lagi memuatnya; keyboard yang muncul atau hilang tidak menutupnya. Dialog dan pencarian tetap terbuka. Menu Aplikasi menutup dirinya saat lebar berubah dan hanya menata ulang posisinya saat tinggi berubah |
| Kriteria terima | Pencarian yang sedang diketik bertahan setelah rotasi |
| Bukti | Waydroid: teks "lxrotate" di pencarian bertahan saat 1920x1028 diubah ke 1028x1920 dan kembali |
| Status | VERIFIED di Waydroid |

## FC-06 Layar penuh (immersive)

| Bagian | Isi |
|---|---|
| Deskripsi | Pengaturan LX > Dock > Layar penuh menyembunyikan bilah status dan navigasi; usapan dari tepi memunculkannya sebentar |
| Input | Kunci `display.immersive` (bawaan mati) |
| API | `WindowInsetsController.hide(systemBars())` dengan `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` di Android 11+, `SYSTEM_UI_FLAG_IMMERSIVE_STICKY` di bawahnya |
| Kriteria terima | Back, Home, dan notifikasi tetap terjangkau lewat usapan |
| Bukti | Waydroid: setelah dinyalakan, bilah status dan navigasi hilang (`SHOW_TRANSIENT_BARS_BY_SWIPE`), usapan dari tepi bawah memunculkannya sebentar di atas tampilan, dan mematikan opsi mengembalikannya |
| Status | VERIFIED di Waydroid. Android di bawah 11 belum diuji |

## FC-07 Aplikasi Android dalam jendela

| Bagian | Isi |
|---|---|
| Deskripsi | Aplikasi, pintasan aplikasi, Browser, dan Galeri diluncurkan dengan launch bounds dan permintaan mode freeform lewat kunci bundle `android.activity.windowingMode`. Ukurannya mengikuti `window.defaultState`, aturan yang sama dengan jendela LX: maksimal mengisi area jendela di antara panel, mengambang di kanvas di bawah 600 dp selebar kanvas dikurangi 8 dp di setiap sisi dan setinggi ruangnya dikurangi tiga langkah kaskade, mengambang di kanvas lebar mengikuti `screenOrientation` aplikasi. Jendela mengambang berikutnya bergeser 28 dp. Game (`CATEGORY_GAME` atau `FLAG_IS_GAME`), juga lewat pintasannya, meminta mode layar penuh. Lihat ADR-005 |
| Jenis jendela | SystemManagedWindow: Android yang menggambar dan mengatur jendela itu, termasuk caption-nya. LX tidak menggambar tiruan |
| Izin | Tidak ada. Di ponsel yang tidak melaporkan fitur freeform, pemilik ponsel menyalakan "Enable freeform windows" di Opsi pengembang lalu menghidupkan ulang |
| Error handling | Di perangkat tanpa freeform, LX mengandalkan Android untuk membuka aplikasi layar penuh. Belum diuji |
| Kriteria terima | `mode=freeform` dan frame lebih kecil dari layar di `dumpsys`; dua dan tiga jendela tampil bersamaan; sentuhan, keyboard, Back, geser, ubah ukuran, minimise, restore dari LX, maximise, dan tutup bekerja |
| Bukti | `scripts/ui_test_app_windows.py`, 29 Oktober 2026. Infinix: 27 lolos, 0 gagal, termasuk Chrome, YouTube, WhatsApp, Files, dan Pengaturan sebagai jendela. Waydroid: lihat `docs/testing/test-report.md`. Infinix, 31 Oktober 2026, dibaca dari `dumpsys`: Kalkulator dengan Otomatis di (16,184)-(704,1392) `freeform`, dengan Mengambang di (32,200)-(688,1208); Mahjong Blast `fullscreen` di (0,0)-(720,1600) |
| Status | VERIFIED di Infinix (dengan freeform dinyalakan) dan Waydroid. Perilaku di perangkat tanpa freeform: NOT TESTED |
| Mode B, bingkai LX | Bawaan: Pengaturan LX > Jendela > Bingkai jendela aplikasi = LX. Aplikasi berjalan di layar virtual milik shell dan tampil di jendela LX dengan judul, ikon, dan tombol sesuai gaya jendela, mengisi bingkai sampai ke tepi (ADR-006). Seluruh aplikasi selalu terlihat: jendela kecil merender ukuran ponsel lalu mengecilkannya, aplikasi yang terkunci orientasinya mendapat layar berbentuk miliknya, dan keyboard terbuka di dalam jendela. Kalau LX Bridge belum siap, jendela LX menunjukkan caranya dan membuka aplikasi begitu siap. Kalau start gagal, jendela menyebut penyebabnya dan menawarkan Coba lagi atau Buka sebagai jendela Android. Selama `lxdb` belum menyala, jendela menunggu dan LX terus mencoba; begitu `lxdb` tersambung, jendela yang menunggu, gagal karenanya, atau kehilangan aplikasinya membuka aplikasinya lagi. Tombol tutup mengakhiri aplikasinya seperti menggesernya dari Recents. Game dan aplikasi di daftar "Selalu jendela Android" memakai jalur di atas |
| Bukti Mode B | Infinix X6726B (Android 15, MediaTek, Shizuku 13.6.0), 31 Oktober 2026. User service Shizuku gagal di ponsel ini (RikkaApps/Shizuku#1198); helper LX tersambung dan jendela pertama terbuka setelah 5,3 detik, berikutnya 0,27 sampai 0,41 detik. Kalkulator di jendela LX menghitung 7 × 8 = 56 dan 9 × 6 = 54. Ubah ukuran ke 539x836 menata ulang aplikasinya. Minimise mematikan layar virtual dan menjeda aplikasi, restore melanjutkannya. Pencarian Pengaturan Android menerima "wifi" dari Gboard. Tutup melepas layar dan task-nya. Instrumentation 28 dari 28, termasuk 13 cek Mode B |
| Bukti 1 November 2026 | `scripts/ui_test_framed_apps.py --cycles 2`: 55 lulus, 0 gagal untuk Kalkulator, Chrome, YouTube, WhatsApp, dan Facebook, termasuk jendela kecil tanpa letterbox. Manual: keyboard di dalam jendela, aplikasi profil kerja, dan Shizuku berhenti lalu jalan lagi. Instrumentation 30 dari 30 |
| LX Bridge | ADR-007. Satu-satunya sumber hak shell; Shizuku dihapus 2 November 2026. Pemasangan otomatis lewat satu sakelar aksesibilitas, dan kode sebagai cadangan. Sesudahnya LX menjalankan `lxdb` sendiri setiap boot. `lxdb` hidup sampai reboot dan kembali ke proses LX yang baru. Status `NEEDS_WIFI` menunggu Wi-Fi lalu membuka aplikasi sendiri |
| lxdb | ADR-008. Alat native C++ dan assembly yang mengelola jendela Mode B dan menggantikan helper JVM. Berbicara langsung dengan Android lewat `/dev/binder`, membaca nomor transaksi dari `framework.jar` ponsel, dan punya perintah mirip adb untuk `adb shell`. LX memakainya lewat Binder. Menyalakannya sekali setelah reboot tetap lewat LX Bridge atau USB |
| Aplikasi tetap berjalan | Menyembunyikan jendela (minimise, pindah workspace, LX tertutup aplikasi lain, layar mati) tidak mengubah state aplikasi: layar virtualnya tetap menyala tanpa surface, dan wake lock khusus layar itu menahannya dari timeout. Hanya menutup jendela yang mengakhiri aplikasi. Selama ada aplikasi di jendela LX, ponsel tidak tidur penuh |
| Ubah ukuran | Selama diseret, gambar terakhir diskalakan seragam dari sudut kiri atas. Layar virtual baru diubah 180 ms setelah seretan berhenti, dan transform dilepas saat frame ukuran baru tiba |
| Bukti 2 November 2026 | Infinix: 40 instrumentation lulus. `adb pair` asli berhasil melawan kode pairing LX. Helper mode daemon membuka aplikasi tanpa Shizuku, bertahan saat LX dihentikan paksa, dan berhenti saat APK diganti. Kalkulator tetap `RESUMED` dengan layar `ON` saat pindah workspace, diminimalkan, dan tertutup Pengaturan; gambar dan sentuhan kembali (angka yang diketik tetap ada). Dengan layar mati dan ponsel terkunci, Kalkulator tetap berjalan 180 detik tanpa dibekukan. Gambar di tengah seretan menunjukkan skala seragam. Pairing dengan `adbd` ponsel, restart sungguhan, dan layar mati lebih dari 3 menit: NOT TESTED |
| Bukti lxdb, 2 November 2026 | `scripts/ui_test_framed_apps.py --cycles 2`: 55 lulus, 0 gagal lewat `lxdb`. `scripts/ui_test_lxdb.sh`: 19 lulus. `scripts/ui_test_display_awake.sh`: 10 lulus. Dengan Wi-Fi mati, Kalkulator terbuka dan menghitung 9 × 4 = 36. Rinciannya di ADR-008 |
| Bukti 3 November 2026 | Restart sungguhan oleh pemilik ponsel memperlihatkan jendela yang berhenti di "LX Bridge tidak menyala"; tiga penyebabnya dan perbaikannya ada di ADR-007, bagian Restart. Dengan CPU dibebani penuh, `lxdb` menyala lagi 10 dari 10 kali (sebelumnya 0 dari 10). `scripts/ui_test_keyboard.py`: 26 lulus, juga di bawah beban. `scripts/ui_test_window_controls.py`: 13 lulus. Restart sungguhan setelah perbaikan: `lxdb` tersambung 3,2 detik setelah Wi-Fi, dan jendela Chrome terbuka sendiri |
| Status Mode B | VERIFIED di Infinix (Android 15) dengan `lxdb`, dijalankan LX Bridge lewat `adbd` ponsel. Restart sungguhan: VERIFIED 3 November 2026. Langkah `BridgeSetupService`, Android 14 dan 16, serta pengguna sekunder: NOT TESTED. Android 13 ke bawah memakai jendela Android. FLAG_SECURE tampil hitam |

## FC-08 Menu Aplikasi bertingkat

| Bagian | Isi |
|---|---|
| Deskripsi | Tombol Aplikasi di ujung kiri bilah bawah membuka menu: kotak cari, kolom kategori (Favorit, Terakhir, Semua aplikasi, kategori Android yang berisi, Lainnya, Kerja, Tersembunyi), dan kolom kedua berisi aplikasi kategori yang dibuka. Menggantikan laci aplikasi |
| Input | Ketukan, mouse (menunjuk 180 ms), keyboard (Ctrl Shift A, panah, Enter, Esc), Back, seret |
| Output | Aplikasi diluncurkan (FC-07), dipilih untuk desktop (Tambah aplikasi), atau dipindah ke desktop dan taskbar dengan diseret |
| Dependency | `StartMenu`, `OverlayHost`, `TaskbarView`, kategori dari `ApplicationInfo.category` |
| Izin | Tidak ada |
| Lifecycle | Menu menutup dirinya saat lebar layar berubah dan menyusut saat keyboard muncul. Daftar diperbarui saat aplikasi dipasang atau dicopot |
| Error handling | Menekan lama sebuah aplikasi menyembunyikan keyboard supaya menu aplikasinya tidak tertutup. Sebelum daftar aplikasi terbaca, kolom menulis "Memuat aplikasi". Layout lama yang masih berisi setting laci dimuat tanpa notifikasi entri dilewati. Drop yang mendarat di lapisan overlay kosong diteruskan ke panel di bawahnya |
| Kriteria terima | Tombol berada paling kiri; submenu membuka di samping kolom pertama di setiap lebar, dan di ponsel tegak menu tidak memenuhi layar; Back dan Esc bertahap; uji UI otomatis lulus |
| Bukti | Infinix, 31 Oktober 2026: pada ukuran antarmuka 130% kolom kategori menjadi lajur ikon dan submenu mengisi 200 sampai 698 px tanpa menumpuk nama kategori; ketukan pada ikon yang terbuka mengembalikan kolom 41 sampai 559 px. Pada 100% nama kategori muat utuh dan lajur tidak dipakai. Waydroid, 30 Oktober 2026, tata letak ponsel 720x1600: "A category opens as a submenu beside the categories, not across the screen" lulus, kolom kategori 24 sampai 440 px dan submenu 344 sampai 704 px. Infinix, 30 Oktober 2026: kolom kategori 208 dp (24 sampai 440 px), ketukan kedua pada kategori yang terbuka menutup submenunya, Produktivitas membuka submenu 31 aplikasi yang bergulir di dalam tinggi menu, Game membuka submenu lima baris sejajar barisnya. Infinix, 29 Oktober 2026: tombol di 20 sampai 104 px, menu di atas bilah, jumlah per kategori sama dengan Semua aplikasi (106), drill-down, cari, buka Kalkulator sebagai jendela, menu tekan lama, Back bertahap. Waydroid: kolom kedua di kanan kolom pertama, navigasi panah dan Enter, Esc bertahap, Ctrl Shift A, Tambah aplikasi, seret ke desktop dan taskbar. Test core `RetiredDrawerKeysAreDroppedQuietly` |
| Status | VERIFIED. Seret ke dock samping di tablet dan menunjuk dengan mouse: NOT TESTED |

## FC-09 Gaya jendela LX

| Bagian | Isi |
|---|---|
| Deskripsi | Setting `window.style` memilih satu dari sebelas gaya untuk jendela LX sendiri, `custom` untuk gaya Kustom yang diturunkan dari salah satunya, atau `theme` untuk panel kaca tema LX. Pemasangan baru memakai `lxmodern` (LX Modern Gelap). Tanpa lapisan wallpaper blur di bawahnya, badan jendela digambar padat penuh, jadi teks jendela di belakang tidak pernah tembus. Gaya membawa palet, sudut, bilah judul, tombol, tepi, bayangan, dan dekorasinya. Isi alat di dalam jendela digambar dengan palet gaya lewat `StyledShell`; menu, dialog, desktop, dan bilah tetap memakai tema LX |
| Input | Pengaturan LX > Tampilan > Gaya jendela (kisi contoh kecil), atau menu bilah judul > Gaya jendela |
| Output | Semua jendela LX dibangun ulang dengan gaya baru; jendela yang terbuka tetap terbuka di posisi yang sama |
| Dependency | `WindowStyles`, `WindowFrameDrawable`, `TitleBarDrawable`, `CaptionButton`, `LxTheme(windowStyle)`, skema setting di core |
| Izin | Tidak ada |
| Lifecycle | `window.style` termasuk kunci tema, jadi perubahannya membangun ulang UI. Blur dan Neon dibaca ulang dari tema terkini setiap kali bingkai digambar. Kepekatan kaca adaptif dipilih dari bagian paling terang wallpaper di bawah jendela dan dihitung ulang setelah jendela bergeser atau berubah ukuran |
| Error handling | Nilai di luar skema ditolak core. Teks yang tidak mencapai 4,5:1 di atas komposit jendela digeser warnanya atau diganti warna teks. Outline dengan bounds kosong atau lebih kecil dari dua kali potongan sudut memakai persegi bulat, dan di Android 8 sampai 10 path potongan hanya dipakai kalau konveks. Judul yang sudah di batas 12 sp tidak diberi autosize |
| Kriteria terima | Setiap gaya bisa dipilih dari menu bilah judul dan tercentang "Dipakai" di Pengaturan LX; jendela tetap terbuka; teks 4,5:1 di atas wallpaper hitam sampai putih; tidak ada crash di ukuran antarmuka 85% sampai 130% |
| Bukti | Test core `WindowStyles`. Uji UI "Every window style applies and keeps LX Settings open", 11 dari 11 di Waydroid. `scripts/ui_test_window_styles.py` di Waydroid: ukuran antarmuka turun ke 85%, lalu sebelas pilihan gaya dipasang tanpa crash, 13 dari 13. Instrumentation di Waydroid dan Infinix, 13 dari 13: kontras setiap gaya dan tema LX di 21 tingkat kecerahan wallpaper termasuk baris fokus, hover, dan terpilih, autosize judul di setiap gaya dan ukuran antarmuka, outline Cyberpunk yang menjadi persegi bulat pada bounds kosong dan kecil |
| Bukti 31 Oktober 2026 | Infinix: LX Modern Gelap dan Kustom (dasar LX Modern, aksen hijau toska, tombol kotak) dipilih dari Pengaturan LX dan langsung terpasang di jendela yang terbuka. Instrumentation 28 dari 28; cek kontras kini juga mencakup setiap gaya dasar dengan setiap aksen Kustom. Dengan gaya Rounded Modern, jendela Catatan di belakang File Manager sebelumnya tembus sekitar 5 tingkat kecerahan; setelah perbaikan pikselnya sama dengan bagian lain |
| Status | VERIFIED di Waydroid (API 33) dan Infinix (API 35). Jalur `setConvexPath` di Android 8 sampai 10: NOT TESTED, tidak ada perangkat uji |

## FC-10 Satu wallpaper untuk semua workspace

| Bagian | Isi |
|---|---|
| Deskripsi | Semua workspace memakai wallpaper yang sama. `setWallpaper` di core mengisi semua workspace, workspace baru mewarisi wallpaper yang ada, dan layout lama dengan wallpaper per workspace dimuat dengan wallpaper workspace yang aktif |
| Input | Pengaturan LX > Desktop > Wallpaper, menu tekan lama desktop, menu workspace |
| Output | Wallpaper yang sama di setiap workspace; gambar `local:` yang tidak dirujuk lagi dihapus |
| Dependency | `Desktop::setWallpaper`, `Desktop::addWorkspace`, `Desktop::fromJson`, `Wallpapers.prune` |
| Izin | Tidak ada. Gambar dipilih lewat pemilih foto atau pemilih dokumen |
| Lifecycle | Gambar yatim dibersihkan setelah memilih wallpaper apa pun, setelah impor layout, dan saat aktivitas dibuat kalau layout termuat bersih. Setelah load yang gagal atau memakai cadangan, gambar dibiarkan supaya layout masih bisa dipulihkan. Gambar yang sedang disalin, atau sudah disalin tapi belum dipasang, tidak ikut dibersihkan sampai pemasangannya selesai atau gagal |
| Error handling | Referensi wallpaper yang tidak valid atau workspace yang tidak ada ditolak tanpa mengubah apa pun |
| Kriteria terima | Wallpaper yang dipilih di workspace 1 tampil di workspace 2; Pengaturan LX tidak lagi punya tombol wallpaper per workspace |
| Bukti | Test core `OneWallpaperForEveryWorkspace` dan `LayoutsWithAWallpaperPerWorkspaceTakeTheActiveOne`. Uji UI "Every workspace shows the same wallpaper" di Waydroid |
| Status | VERIFIED di core dan Waydroid. Pembersihan gambar yatim saat start: diperiksa lewat review kode, belum lewat uji otomatis |
