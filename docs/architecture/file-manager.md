# File Manager

File Manager menggantikan LX Files sejak 31 Oktober 2026. Ia tetap alat jendela LX dengan jenis `files`, jadi pin, Ctrl+E, dan baris "Cari berkas" di pencarian LX masih membukanya. Tombolnya punya tempat tetap di kanan tombol Aplikasi di bilah bawah.

Sejak 11 November 2026 berkas yang diketuk terbuka di Tedit (`docs/architecture/tedit.md`), alat jendela LX berjenis `tedit`; menu "Buka dengan" ke aplikasi lain dihapus. Tekan lama folder memberi pilihan Buka folder di Tedit.

## Lapisan

| Lapisan | Berkas | Tugas |
|---|---|---|
| Jendela | `files/FileManagerTool.kt`, `FmAdapter.kt`, `FmSheets.kt`, `FmSupport.kt` | Bilah atas, kolom jalur, sidebar, kisi atau daftar, pilihan, dialog di dalam jendela, progres, riwayat Mundur dan Maju |
| Menu | `ui/overlays/CascadeMenu.kt` | Menu bertingkat untuk semua menu LX, bukan hanya File Manager |
| Kontrak | `files/Fs.kt` | `Backend`, `FsNode`, `StorageCatalog`, `JobEngine`, alasan galat |
| Penyimpanan | `LocalBackend.kt`, `SafBackend.kt`, `AndroidStorage.kt`, `Aliases.kt`, `Trees.kt`, `FsErrors.kt`, `FileTypes.kt` | Daftar volume, akses lokal dan SAF, pengenalan berkas yang sama lewat dua jalan |
| Operasi | `FileJobs.kt`, `Journal.kt` | Salin, pindah, hapus, kompres, ekstrak, periksa; pembersihan sisa |
| Arsip | `core/src/archive_*.cpp`, `app/src/main/cpp/archive_jni.cpp`, `files/NativeArchive.kt` | Membaca dan menulis ZIP, TAR, TAR.GZ di C++ |
| Berbagi | `ShareProvider.kt` | URI baca saja dengan token acak untuk "Bagikan" dan untuk Bagikan di Tedit |

## Akses penyimpanan

Ada dua jalan, dan keduanya bisa dipakai bersamaan.

1. **Akses semua file.** Di Android 11 ke atas lewat `MANAGE_EXTERNAL_STORAGE`, di Android 8 sampai 10 lewat izin penyimpanan lama. Izin ini hanya diminta setelah pengguna mengetuk "Izinkan akses semua file" dan membaca penjelasannya. Dengan izin ini, `LocalBackend` membuka penyimpanan internal, kartu SD, dan USB lewat path. Daftar Terbaru dibaca dari MediaStore.
2. **Folder pilihan.** `SafBackend` memakai pohon `ACTION_OPEN_DOCUMENT_TREE` dengan izin URI persisten. Jalan ini tetap tersedia tanpa izin luas. Android 11 ke atas menolak akar penyimpanan internal, folder Download, dan akar kartu SD di pemilih ini; akar USB diizinkan.

Volume dibaca dari `StorageManager.getStorageVolumes`. Path diambil dari `getDirectory()` di Android 11 ke atas, dan diturunkan dari `getExternalFilesDirs` di bawahnya. Ruang kosong dihitung dengan `StatFs`. Pemasangan dan pelepasan volume dipantau lewat `StorageVolumeCallback` di Android 11 ke atas, dan lewat siaran `MEDIA_*` di bawahnya.

## Akses penuh lewat LX Bridge

Selama `lxdb` berjalan (Android 14 ke atas, LX Bridge tersambung), File Manager memakai hak shell untuk tempat yang tertutup bagi aplikasi: Android/data dan Android/obb di setiap volume, serta semua path di luar volume, termasuk `/`, `/system`, dan `/data/local/tmp`. Di tempat lain panggilan LX sendiri tetap dipakai lebih dulu, dan shell baru ditanya kalau Android menolaknya dengan EACCES atau EPERM. Path yang memang tidak ada tidak memicu perintah shell.

Kalau akses semua file belum diberikan, File Manager memberikannya sendiri lewat `appops set` (mode uid dan mode paket) begitu `lxdb` siap. Tidak ada dialog, banner, atau tanda di jendela; pemilik meminta begitu pada 10 November 2026. Saat `lxdb` tersambung atau putus, sidebar, Beranda, dan folder yang sedang terbuka dibaca ulang.

| Kebutuhan | Cara |
|---|---|
| Daftar folder | Satu perintah `stat` untuk folder itu sendiri dan `find ... -exec stat` untuk isinya. Kalau keluaran mencapai batas 512 KB `lxdb`, nama dibaca dulu dengan `find -print0`, lalu metadata per 2.000 entri |
| Hak tulis | Dihitung dari bit mode terhadap uid 2000 dan grup shell (`id -G`); partisi baca-saja tetap menolak saat operasi dengan alasannya sendiri |
| Membaca berkas | Sampai 32 MB disalin dulu ke cache privat LX lalu dibuka dari sana, jadi bisa di-seek (thumbnail dan pratinjau). Lebih besar lewat pipa. `dd` membaca blok 64 KB karena FUSE bisa memotong satu read yang lebih besar |
| Menulis berkas | Lewat pipa ke thread yang menambahkan potongan 256 KB dengan `cat >>`. Pemanggil menutup deskriptor lalu memanggil `commitWrite`, yang menunggu thread itu dan melempar galatnya |
| Salin di antara dua tempat shell | `dd` 16 MB per perintah di dalam shell, jadi Batal berhenti dalam satu atau dua detik dan progres tetap jalan |
| Pindah | `mv` hanya kalau kedua ujung satu perangkat menurut `stat %d`; selain itu disalin dan diverifikasi seperti biasa |
| Bagikan | Aplikasi lain menerima salinan di cache privat LX, yang dihapus setelah sehari |

Path dikirim ke shell hanya di dalam tanda kutip tunggal. Tautan simbolik ditampilkan sebagai tautan (`inode/symlink`), tidak pernah diikuti oleh penjelajahan, salin, atau hapus, dan hanya dibuka jendela saat pengguna mengetuknya. Pencarian tidak masuk ke `/proc`, `/sys`, `/dev`, dan sistem berkas kernel lain. Ini bukan root: `/data/data` aplikasi lain dan `/data` sendiri tetap tertutup.

## Beranda dan Pembersih

Beranda membaca volume dari `StorageManager` dan jumlah serta ukuran per jenis berkas dari MediaStore (gambar, video, audio menurut `media_type`; dokumen, arsip, dan APK menurut MIME; unduhan menurut path). Di Infinix penghitungannya 120 sampai 180 ms.

Pembersih (`Cleaner.kt`) memindai dulu dan tidak menghapus apa pun sampai pengguna mengonfirmasi. Cache semua aplikasi diambil dari `dumpsys diskstats`, yang diperbarui Android sekitar sekali sehari, dan dibersihkan dengan `pm trim-caches`; ruang yang benar-benar kosong diukur dari partisi data sebelum dan sesudah. Cache di Android/data dihitung dengan `du` dan dikosongkan per aplikasi lewat shell, foldernya tetap ada. Thumbnail, berkas sementara, folder kosong, APK, berkas ganda, dan berkas besar datang dari satu penjelajahan penyimpanan bersama yang tidak masuk ke Android/ dan folder tersembunyi. Sebelum menghapus, setiap berkas dibaca ulang: ukuran yang berubah membuatnya dilewati, dan salinan ganda hanya dihapus selama isinya masih sama persis dengan yang disimpan. Alat cepat menjalankan `am kill-all` (memori tersedia diukur sebelum dan sesudah) dan `sm fstrim`.

## Operasi tanpa kehilangan data

- **Salin.** Setiap berkas ditulis ke nama tersembunyi `.<nama>.lxpart-<6 hex>`. Ukurannya dibandingkan dengan sumber, baru namanya diganti ke nama akhir. Nama sementara dicatat di `files/fm-journal.txt` sebelum penulisan dimulai. Saat File Manager dibuka lagi setelah proses mati, sisa dengan pola itu dihapus. Nama lain tidak pernah disentuh.
- **Ganti.** Ganti dijalankan dengan pertukaran. Berkas lama disingkirkan dulu ke `.<nama>.lxkeep-<6 hex>`, yang baru dipasang, baru yang lama dihapus. Kalau ada langkah yang gagal, yang lama dikembalikan. Kalau pengembalian pun gagal, hasilnya menyebut path yang tersisa. Berkas `.lxkeep` tidak pernah dihapus otomatis.
- **Pindah.** Di volume atau penyedia yang sama, pindah memakai rename. Di luar itu, isi disalin dulu, lalu yang dihapus hanya berkas sumber yang sudah tersalin dan terverifikasi. Folder sumber dihapus hanya kalau sudah kosong, jadi berkas yang muncul di tengah jalan selamat.
- **Berkas yang sama lewat dua jalan.** Contohnya `/storage/emulated/0/Documents/a.txt` dan `primary:Documents/a.txt` di SAF. `Aliases` mengenalinya, sehingga berkas tidak pernah dianggap bentrok dengan dirinya sendiri. Untuk penyedia dengan ID tertutup, pertukaran memeriksa sekali lagi bahwa sumbernya masih ada di namanya sebelum menghapus apa pun.
- **Konflik nama.** Konflik ditanyakan ke jendela lewat `ConflictResolver`: Ganti (Gabungkan untuk dua folder), Simpan keduanya, Lewati, atau Hentikan, dengan pilihan "lakukan hal yang sama untuk sisanya". Kalau jendela tertutup saat menunggu jawaban, operasi berhenti.
- **Kesalahan berat.** `Throwable` apa pun di pekerja dicatat sebagai kegagalan operasi dan dilaporkan ke jendela. Proses launcher tidak ikut mati.

## Arsip

Format ZIP, TAR, dan TAR.GZ dibaca dan ditulis di C++ dengan zlib, langsung dari file descriptor. Seluruh arsip tidak pernah dimuat ke memori.

- **ZIP.** Dibaca dari central directory, sehingga entri STORED dengan data descriptor dan ZIP64 juga terbaca. Nama tanpa flag UTF-8 dibaca sebagai CP437. Entri yang tumpang tindih ditolak. CRC-32 diperiksa per entri, dan entri terenkripsi dilewati.
- **TAR.** Header ustar, GNU (`L`/`K`, angka base-256), dan PAX (`path`, `linkpath`, `size`, `mtime`) dikenali. Untuk TAR.GZ, member gzip yang bersambung dibaca berurutan.
- **Ekstraksi.** Semua isi masuk dulu ke folder tersembunyi `.lx-extract-<6 hex>` di tujuan. Hasilnya baru dipindahkan setelah semua entri berhasil, dan kalau gagal atau dibatalkan, folder itu dihapus. Arsip berisi lebih dari satu item di tingkat teratas mendapat folder baru bernama arsipnya.
- **Kompresi.** Path dihitung relatif terhadap induk sumber, jadi `MyProject/` tetap menjadi folder di dalam arsip. Arsip ditulis ke nama `.lxpart` lalu namanya diganti, dan berkas arsip itu sendiri tidak ikut dimasukkan.

Aturan keamanannya ada di `docs/audits/threat-model.md`.

## Siklus hidup

Operasi milik proses, bukan milik jendela. Dua pekerja berjalan bersamaan, sehingga penghapusan tidak menunggu salinan besar selesai. Mengganti tema atau mode malam membangun ulang jendela tanpa menghentikan operasi. Jendela baru langsung menampilkan progres operasi yang masih berjalan.

LX tidak memakai foreground service. LX adalah aplikasi Home, sehingga prosesnya berada di `HOME_APP_ADJ` dan tetap berjalan selama pengguna membuka aplikasi lain. Kalau proses tetap dimatikan, journal dan nama `.lxpart` atau `.lx-extract` memastikan tidak ada berkas setengah jadi yang tertinggal dengan nama asli. Keputusan ini menghindari izin notifikasi dan izin foreground service, dengan risiko yang tercatat: operasi yang sedang berjalan bisa terputus kalau Android kehabisan memori.

## Batas yang diketahui

- Penyedia SAF selain penyimpanan bawaan mencari nama dengan mendaftar seluruh folder, jadi folder besar di sana lebih lambat.
- Nama di kartu SD dan USB mengikuti aturan FAT: tanpa `" * : < > ? |`, dan tidak boleh diakhiri spasi atau titik.
- Symlink dan hardlink di dalam arsip selalu dilewati, karena penyimpanan bersama Android tidak bisa menyimpannya.
- Di Android 8 sampai 10, USB biasanya hanya bisa dibuka sebagai folder pilihan.
