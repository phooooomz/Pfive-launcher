# ADR-008: lxdb, alat native yang berbicara langsung dengan Android

Status: diterima, 2 November 2026. Menggantikan `EmbedHelper` dan `EmbedService` dari ADR-006 dan ADR-007. Layar virtual, pembagian Mode A dan B, dan LX Bridge sebagai pembuka hak shell tetap berlaku.

## Masalah

Jendela Mode B dikerjakan helper Kotlin yang berjalan di `app_process`, jadi setiap pengguna LX menjalankan satu JVM tambahan sebagai shell. Pemilik produk meminta alat pengganti yang ditulis dengan C++ atau assembly, fungsinya mirip adb, langsung berkomunikasi dengan perangkat tanpa jaringan atau USB, dan menjadi alat yang mengelola tampilan jendela aplikasi.

## Yang tidak bisa dihilangkan

Di ponsel tanpa root, hanya `adbd` yang bisa menjalankan proses sebagai uid shell, dan `adbd` hanya menerima sesi lewat USB atau lewat Proses debug nirkabel. Android menolak menyalakan Proses debug nirkabel tanpa Wi-Fi yang tersambung. Jalan lain ke uid shell berarti eksploit, dan LX tidak melakukannya.

Jadi "tanpa jaringan dan USB" berlaku untuk seluruh kerja `lxdb`, kecuali satu momen: menyalakannya sekali setelah ponsel dimulai ulang. Momen itu tetap lewat LX Bridge dan butuh Wi-Fi beberapa detik, atau `adb shell` dari komputer. Setelah menyala, `lxdb` tidak memakai jaringan maupun USB, dan tidak memegang satu pun soket jaringan.

## Pilihan

| Pilihan | Bahasa | Bergantung pada | Hasil |
|---|---|---|---|
| Helper Kotlin di `app_process` | Kotlin | JVM, hidden API | Lama. Dua JVM di ponsel, dan permintaan tidak sesuai arahan pemilik produk |
| Biner C++ yang memakai `libbinder.so` | C++ | Pustaka privat sistem | Ditolak. C++ ABI `libbinder` berubah per versi dan per ROM, dan NDK tidak menyediakannya |
| `libbinder_ndk` | C | API NDK | Ditolak. `AServiceManager_*` tidak tersedia untuk aplikasi di NDK, dan parcel framework tetap harus ditulis sendiri |
| Binder mentah ke `/dev/binder` | C++ dan assembly | Protokol kernel, yang stabil | Dipilih |

## Keputusan

`lxdb` adalah satu executable, `lib/<abi>/liblxdb.so` di dalam APK, dengan dua peran: daemon yang berjalan sebagai shell, dan perintah `lxdb` untuk dipakai dari `adb shell`. Kodenya ada di `lxdb/`.

**Tanpa berkas tambahan.** `ShellHelper.command` meminta `/system/bin/linker64` menjalankan `liblxdb.so` dari tempat Android menaruhnya, jadi tidak ada salinan di `/data/local/tmp`. Sejak Tedit butuh `extractNativeLibs="true"` (12 November 2026), build menyimpan pustaka terkompresi di APK dan Android membongkarnya ke folder pustaka native milik LX; `ShellHelper.library` memilih salinan itu. Linker hanya bisa membuka pustaka di dalam APK kalau tersimpan apa adanya, jadi jalur `base.apk!/lib/<abi>/liblxdb.so` tinggal cadangan untuk APK seperti itu. Uji instrumentasi "the library lxdb starts from is one the system linker can open on this phone" menjaga syarat ini.

**Binder mentah.** `sys_arm64.S` dan `sys_x86_64.S` berisi satu pintu syscall. `binder.cpp` membuka driver, memetakan buffer, menjalankan empat thread, dan berbicara dengan protokol yang sama dengan `libbinder`: `BINDER_WRITE_READ`, node lokal, handle dengan reference count, death notification, dan transaksi bersarang. `parcel.cpp` menulis format kabel yang sama, termasuk kata stabilitas sesudah setiap binder dan aturan bahwa binder null tidak masuk daftar offset kernel.

**Nomor transaksi dibaca dari ponsel.** Nomor metode AIDL adalah urutannya di berkas, dan ROM yang menambah metode menggeser semua nomor sesudahnya. `IActivityManager` di XOS punya 357 metode, dan `getContentProviderExternal` ada di nomor 130. Tabel per ROM tidak mungkin dirawat, jadi `dex.cpp` membaca `framework.jar` saat daemon mulai: konstanta `TRANSACTION_*` di kelas `Stub`, dan daftar parameter metodenya di kelas interface. Panggilan yang parameternya bukan yang ditulis `lxdb` dimatikan, dan `lxdb doctor` menyebutnya. Kalau panggilan wajib hilang, `create` menjawab `ERR_UNSUPPORTED` dan LX menawarkan jendela Android.

**Parcel framework ditulis framework.** Isi `VirtualDisplayConfig`, `Intent`, opsi aktivitas, `MotionEvent`, dan `KeyEvent` berubah antarversi Android, dan yang mengetahuinya hanya framework ponsel itu. LX menulisnya di prosesnya sendiri (`EmbedProtocol`), lalu `lxdb` meneruskannya byte demi byte, termasuk binder di dalamnya. `lxdb` hanya membaca dua hal: `displayId` yang harus diganti di event input, dan kode tombol yang harus disaring.

**Kontrak tetap AIDL.** `IEmbedService.aidl` tetap menjadi antarmuka LX, jadi Kotlin melihat `IBinder` biasa. Penyerahan binder juga tetap lewat `EmbedBinderProvider` dengan token yang sama, dan `lxdb` memastikan balasannya datang dari uid LX yang dicatat kernel.

**Jendela tersembunyi tidak digambar.** Klien Java mematikan layar virtual karena `DisplayManagerGlobal.setVirtualDisplaySurface` ikut memanggil `setVirtualDisplayState(false)` saat surface null. `lxdb` berbicara langsung dengan layanan, jadi layar tetap `ON` tanpa surface. `ImageReader` pembuang frame dihapus: aplikasi tetap `RESUMED`, dan tidak ada frame yang dikomposisi untuk jendela yang tidak terlihat.

**Orientasi dari peristiwa.** `lxdb` mendaftarkan `ITaskStackListener`. `onActivityRequestedOrientationChanged`, `onTaskRequestedOrientationChanged`, dan `onTaskMovedToFront` hanya menjadi bel, dan nilainya tetap dibaca dari `dumpsys`. Pembacaan berkala tetap ada sebagai cadangan, dengan jeda yang memanjang sampai 30 detik.

**Lepas dari sesi adb.** LX menjalankan `lxdb` sebagai perintah `shell:` tanpa protokol shell, dan untuk perintah seperti itu `adbd` menyediakan PTY. Saat proses pertama perintah itu keluar, kernel mengirim SIGHUP ke seluruh grup prosesnya. Daemon lama melakukan `fork`, induknya langsung keluar, dan anaknya baru memanggil `setsid()` saat mendapat giliran CPU. Di ponsel yang sibuk anak itu kalah dan mati tanpa satu baris log pun, seperti yang terjadi setelah restart (ADR-007, bagian Restart). Kini SIGHUP diabaikan sejak sebelum `fork`, dan induk baru keluar setelah anaknya, lewat pipe, memberi tahu bahwa ia sudah di sesi sendiri. Lewat `adb shell` dari komputer, yang memakai protokol shell tanpa PTY, masalah ini tidak muncul, sehingga tidak tertangkap uji sebelumnya.

**Keyboard dan fokus.** Keyboard aplikasi muncul di layar aplikasi itu sendiri (`setDisplayImePolicy` LOCAL), dan Android hanya menampilkannya untuk layar yang memegang fokus teratas. Setiap sentuhan di jendela aplikasi sampai ke jendela LX lebih dulu, dan Android memindahkan fokus ke LX sebentar sebelum `lxdb` mengembalikannya dengan `setFocusedTask`. Dua hal membuat perpindahan itu tidak lagi merusak keyboard. Pertama, `lxdb` memanggil `IWindowManager.syncInputTransactions` setelah memindahkan fokus, jadi aplikasi sudah tahu ia berfokus sebelum menerima sentuhan yang diteruskan. Kedua, selama orang bekerja di jendela aplikasi, LX memasang `FLAG_ALT_FOCUSABLE_IM` pada jendelanya sendiri. Tanpa flag itu, saat LX menerima fokus, Android menyimpan keyboard aplikasi, dan tombol Kembali menutup keyboard sekaligus membuat aplikasi mundur. Flag dilepas saat orang menyentuh bagian LX lain, atau saat kolom teks LX mendapat fokus.

**Tombol tutup.** `IEmbedService.close` menghapus tugas aplikasi di layar itu dengan `removeTask`, sama seperti menggesernya dari daftar aplikasi terbaru, lalu melepas layarnya. Sebelumnya layar dilepas begitu saja: aktivitasnya selesai, tetapi tugasnya tertinggal di Recents dan prosesnya tetap hidup. Jendela yang ditutup karena aplikasinya selesai sendiri, atau yang dipindah ke jendela Android, tidak memakai jalur ini.

**Daemon bertahan lebih lama.** Helper lama berhenti kalau LX tidak terjangkau selama 2 menit. Karena menyalakannya lagi butuh Wi-Fi, `lxdb` menunggu selama paket LX masih terpasang, dan permintaan provider-nya sendiri menghidupkan LX yang berhenti. Ia hanya berakhir kalau LX menolaknya (APK berganti), LX dihapus, atau `system_server` mati.

### Fungsi yang mirip adb

```
lxdb version              alat dan daemon
lxdb devices              ponsel ini
lxdb doctor               panggilan Android yang tersedia di ponsel ini
lxdb services             layanan Binder Android
lxdb windows              jendela aplikasi LX
lxdb shell [perintah]     shell sebagai uid shell, dengan terminal kalau tanpa perintah
lxdb push|pull            menyalin berkas
lxdb install|uninstall    memasang dan menghapus aplikasi
lxdb logcat|dumpsys       log dan dump Android
lxdb stop                 mengakhiri daemon
```

Perintah itu bicara dengan daemon lewat soket Unix abstrak `@lxdb-u<pengguna>`, tanpa port. Deskriptor pemanggil dikirim dengan `SCM_RIGHTS`, jadi `push`, `pull`, dan `logcat` mengalir langsung tanpa disalin daemon.

SELinux melarang proses aplikasi menyambung ke soket proses shell: `connect` dari domain aplikasi berakhir `Permission denied`. Soket itu hanya untuk `adb shell`. LX memakai `IEmbedService.exec` lewat Binder (`EmbedClient.shell`), dan aplikasi lain, termasuk aplikasi terminal, tidak punya jalan masuk.

## Konsekuensi

- `IEmbedService` tidak lagi sempit. ADR-006 menjanjikan tidak ada perintah shell bebas, dan `exec` mencabut janji itu karena fungsi mirip adb memang diminta. Kode yang berjalan di proses LX kini setara shell selama daemon hidup. Kunci adb LX sudah memberi hak yang sama saat Proses debug nirkabel menyala, tetapi jendela waktunya kini sepanjang daemon hidup.
- Pemeriksaan aktivitas peluncur pindah ke LX, karena `lxdb` meneruskan `Intent` tanpa membacanya. Daemon tetap memeriksa uid pemanggil, pemilik layar, batas 16 layar, pengguna dan profil kerjanya, serta tombol yang boleh diteruskan.
- `EmbedKeys.kt` dan `rules.cpp` memegang daftar tombol yang sama di dua bahasa. Keduanya perlu diubah bersamaan.
- Bentuk `IEmbedService` tidak boleh diubah tanpa mengubah `embed_service.cpp`. Argumen yang diteruskan apa adanya harus tetap paling akhir.
- `INTERNET` tetap ada untuk LX Bridge.
- Tidak ada ketergantungan baru. `lxdb` memakai `libz`, `liblog`, dan `libc` milik sistem, dan libc++ statis dari NDK.

## Batasan

- Batas bawah Mode B naik dari Android 13 ke Android 14. `VirtualDisplayConfig.Builder` baru publik di API 34, dan lint menangkapnya. Menulis parcel Android 13 secara manual berarti kode yang tidak bisa diuji di ponsel uji, dan Mode B memang belum pernah berjalan di Android 13. Android 13 ke bawah memakai jendela Android.
- Hanya Android 15 yang diuji. Android 14 dan 16 seharusnya tertangani oleh pemeriksaan bentuk, tetapi belum dijalankan. Untuk ROM yang `framework.jar`-nya tidak berisi DEX, daemon berhenti dengan catatan di log, dan belum ada perangkat yang menunjukkan kasus ini.
- `lxdb` hidup di cgroup `adbd`, sama seperti helper lama. Kalau Proses debug USB dan nirkabel dua-duanya mati, `init` mengakhirinya.
- Daftar tugas di layar hanya dibaca sampai id tugas pertamanya, karena isi `RootTaskInfo` berubah antarversi. Kalau bentuknya lain, deteksi layar kosong tetap bekerja, tetapi fokus keyboard ke aplikasi tidak berpindah.

## Bukti

Bukti per 2 November 2026, Infinix X6726B, Android 15, XOS, arm64, SELinux enforcing, pada build akhir kecuali yang ditandai:

- **Tanpa jaringan.** Dengan Wi-Fi dimatikan (`Wi-Fi is disabled`, `wifi_on=0`) dan Proses debug nirkabel mati, `lxdb shell id -u` menjawab 2000, Kalkulator terbuka di jendela LX, dan 9 × 4 = 36 lewat sentuhan. Pid daemon tetap sama dari awal sampai akhir. Tidak satu pun soket daemon ada di `/proc/<pid>/net/tcp`, `tcp6`, `udp`, atau `udp6`. Satu-satunya soket yang didengarkan adalah `@lxdb-u0`.
- **Dimulai LX sendiri.** Setiap APK diganti, LX Bridge menjalankan `lxdb` lewat `adbd` ponsel tanpa campur tangan. Dari "switching wireless debugging on" sampai "lxdb launched through port" 1,1 detik, dan `lxdb` tersambung ke LX sekitar 10 ms sesudah naik. `adb_wifi_enabled` kembali 0.
- **Berjalan dari APK.** `linker64` menjalankan `base.apk!/lib/arm64-v8a/liblxdb.so` sebagai uid shell, domain `u:r:shell:s0`, tanpa berkas tambahan. Sejak 13 November 2026 yang dijalankan adalah salinan yang dibongkar Android, `/data/app/.../lib/arm64/liblxdb.so` (lihat paragraf "Tanpa berkas tambahan").
- **Panggilan Android.** `lxdb doctor` menemukan ke-19 panggilan dengan parameter yang cocok dalam 30 ms, di antaranya `startActivityAsUser` #3, `getContentProviderExternal` #130, dan `setDisplayImePolicy` #110. `lxdb services` membaca 303 layanan.
- **Jendela.** `scripts/ui_test_framed_apps.py --cycles 2`: 55 lulus, 0 gagal, untuk Kalkulator, Chrome, YouTube, WhatsApp, dan Facebook, termasuk ubah ukuran, permintaan orientasi, dan tutup, tanpa crash atau ANR. Jendela WhatsApp pemilik ponsel terbuka kembali sendiri begitu `lxdb` tersambung.
- **Input.** Sentuhan lewat LX menghitung 7 × 8 = 56. `injectKey` mengetik 789 di aplikasi, dan Volume Up ditolak ("key 24 meta 0x0 refused").
- **Jendela tersembunyi.** Setelah tombol perkecil: layar `ON`, Kalkulator `RESUMED`, cgroup `frozen 0`. Setelah dipulihkan angka 56 tetap ada dan + 2 = 58. `scripts/ui_test_display_awake.sh`: 10 lulus, 0 gagal, termasuk layar tanpa surface.
- **Orientasi.** Aplikasi uji yang meminta arah mendatar saat berjalan terdengar `lxdb` dalam 37 sampai 43 ms.
- **Perintah.** `scripts/ui_test_lxdb.sh`: 19 lulus, 0 gagal, termasuk kode keluar, stdin, berkas 5 MB bolak-balik dengan SHA-256 sama, dan terminal lewat `adb shell -tt`.
- **Yang ditolak.** Dua aplikasi debuggable lewat `run-as` tidak bisa menyambung ke soket: `Permission denied` dari SELinux. Binder dari proses LX berhasil.
- **Siklus hidup.** LX dihentikan paksa: daemon dengan pid sama menghidupkannya lagi dan tersambung dalam 2 detik. APK diganti: "LX turned this daemon away". Daemon kedua mengambil alih lewat soket, dan token salah ditolak LX; LX lalu menjalankan yang baru dalam 4 detik.
- **Beban.** Daemon menganggur: 0 tick CPU dalam 20 detik, RSS 14,6 MB, 8 thread. `liblxdb.so` berukuran 357 KB di APK release.
- **Uji lain.** 211 tes host lulus, 32 di antaranya untuk `lxdb`, termasuk 3.000 jar rusak. Di bawah ASan dan UBSan 210 lulus tanpa laporan, dan `ArchiveJni.HarnessUnderCheckJni` memang dilewati di build itu. Instrumentation 40 lulus, 0 gagal. Lint 0 error, 3 warning yang sudah ada.

Bukti per 3 November 2026, perangkat yang sama:

- **Panggilan Android.** `lxdb doctor` kini menemukan 21 panggilan, termasuk `removeTask` #21 dan `syncInputTransactions` #111.
- **Menyala ulang di ponsel sibuk.** Dengan 8 proses sibuk membebani CPU, `lxdb` yang dihentikan dinyalakan lagi oleh LX 10 dari 10 kali; build sebelumnya 0 dari 10. Lihat ADR-007, bagian Restart.
- **Keyboard.** `scripts/ui_test_keyboard.py --rounds 5` dengan aplikasi uji di APK uji: 26 lulus, 0 gagal, dan hasilnya sama saat CPU dibebani penuh. Setiap putaran mengetuk kolom teks, mengetik, menekan tombol aplikasi, lalu menekan Kembali. Kolom pencarian LX tetap mendapat keyboard setelah itu, dan layar yang meminta keyboard saat dibuka juga mendapatkannya. Pada build sebelumnya, Kembali di putaran pertama menutup keyboard dan sekaligus aplikasinya.
- **Tombol tutup dan layar penuh.** `scripts/ui_test_window_controls.py`: 13 lulus, 0 gagal. Layar penuh memberi aplikasi 720×1440, seluruh area di antara bilah status dan navigasi, sebelumnya 682×1097. Kembali mengembalikan ukuran itu tanpa ikut sampai ke aplikasi. Tombol tutup mengeluarkan aplikasi dari Recents dan mengakhiri prosesnya.

- **Restart sungguhan.** Setelah perbaikan, `lxdb` naik 3,2 detik setelah Wi-Fi tersambung, dan jendela Chrome pemilik ponsel terbuka sendiri. Rinciannya di ADR-007, bagian Restart.

**Belum diuji:** Android 14 dan 16. x86_64 (terkompilasi, belum dijalankan). Proses debug USB dimatikan. Layar mati lebih lama dari 3 menit dengan daemon ini. `exec` dengan keluaran mendekati batas 512 KB.

## Rujukan

- AOSP `frameworks/native/libs/binder/Parcel.cpp`, `IPCThreadState.cpp`, `include/uapi/linux/android/binder.h`.
- AOSP `frameworks/base/core/java/android/hardware/display/DisplayManagerGlobal.java`, dibaca lewat DEX `framework.jar` dan `services.jar` Infinix X6726B.
- Format DEX: source.android.com/docs/core/runtime/dex-format.
