# ADR-007: LX Bridge, jalan LX sendiri ke hak shell

Status: diterima, 2 November 2026. Menggantikan jalur Shizuku di ADR-006. Proses yang dijalankan LX Bridge kini `lxdb` (ADR-008), bukan `EmbedHelper`. LX Bridge tetap menjadi satu-satunya pintu ke hak shell dan kini sudah berjalan sungguhan melawan `adbd` ponsel, lihat bagian Bukti.

## Masalah

Jendela Mode B butuh proses ber-uid shell. Sampai sekarang proses itu datang dari Shizuku, dan Android menghentikan Shizuku setiap ponsel dimulai ulang. Setelah restart, setiap aplikasi menampilkan pesan "Shizuku tidak berjalan" sampai pengguna membuka Shizuku dan menjalankannya sendiri. Pengguna meminta alat di dalam LX yang bisa berjalan otomatis.

## Pilihan

| Jalur | Hasil |
|---|---|
| Mode TCP `adb tcpip` lewat `persist.adb.tcp.port`, seperti fork thedjchi | Ditolak. Port 5555 terbuka di jaringan dan tidak memakai pairing |
| Root atau integrasi sistem | Di luar jangkauan: LX berjalan di ponsel biasa |
| Instrumentation atau delegasi izin shell | Hanya aktif selama `am instrument` hidup, dan tidak memberi trusted display |
| Klien ADB di dalam LX lewat Proses debug nirkabel | Dipilih. Memakai pairing resmi Android, dengan kode dan TLS, tanpa kode pihak ketiga |

## Keputusan

LX berbicara langsung dengan `adbd` ponsel itu sendiri di alamat loopback.

1. **Sekali saja.** Pengguna menyalakan Proses debug nirkabel, mengetuk "Sambungkan perangkat dengan kode penyambungan", lalu mengetik kodenya di notifikasi LX (`BridgePairing`). Notifikasi dipakai karena meninggalkan halaman Android menutup kodenya. LX menyerahkan kunci RSA 2048 miliknya lewat `AdbPairing`.
2. **Sesi pertama.** Lewat `AdbLink`, LX menjalankan `pm grant com.lxlauncher android.permission.WRITE_SECURE_SETTINGS`. Izin ini berflag `development`, jadi shell boleh memberikannya dan Android tidak pernah memberikannya saat instalasi.
3. **Setiap boot.** `LxBridge` menulis `adb_wifi_enabled=1`, mengikuti port yang diumumkan lewat NSD (`_adb-tls-connect._tcp`), tersambung ke `127.0.0.1:<port>`, menjalankan `lxdb`, lalu mengembalikan setelan itu 10 detik setelah `lxdb` tersambung. Kalau satu percobaan habis waktunya, `EmbedClient` mencoba lagi, lihat bagian Restart.
4. **Sesudahnya.** `lxdb` hidup sampai ponsel dimulai ulang. Saat LX mati, `lxdb` melepas layar milik LX lalu menyerahkan binder-nya ke proses LX berikutnya lewat `EmbedBinderProvider`. Kalau APK LX berganti, LX menolaknya dan `lxdb` berhenti, karena isinya kode lama.

Pemilik produk meminta Shizuku dihapus seluruhnya, jadi LX Bridge kini satu-satunya sumber hak shell. `dev.rikka.shizuku`, `ShizukuProvider`, user service, dan status yang berkaitan sudah keluar dari APK. Status `NEEDS_WIFI` membuat jendela LX menunggu dan membuka aplikasinya sendiri begitu Wi-Fi tersambung.

### Kriptografi

Android tidak memberi aplikasi akses ke SPAKE2, dan kode publik yang ada berlisensi LGPL atau tidak jelas lisensinya. `Spake2.kt` menulis ulang varian BoringSSL dengan `BigInteger`: titik M dan N yang sama, `password_scalar` dengan trik kofaktor yang sama, nama "adb pair client" dan "adb pair server", dan urutan hash yang sama. Waktu eksekusinya tidak konstan. Ini diterima karena skalarnya hanya hidup selama satu pairing, di koneksi TLS loopback.

Kunci RSA dibuat sebagai kunci perangkat lunak, karena TLS Conscrypt perlu menandatangani dengannya dan `adbd` membaca format mincrypt. Bagian privatnya tersimpan di `files/lx-bridge-key`, disegel AES-GCM dengan kunci di Android Keystore.

## Pemasangan otomatis

Pemilik produk meminta pemasangan tanpa langkah manual, apa pun caranya (2 November 2026). Di ponsel tanpa root, nol langkah pertama tidak mungkin: Android sengaja melarang aplikasi menyalakan Opsi developer, menyalakan Proses debug nirkabel, atau membaca kode penyambungan. Jalan melewati larangan itu berarti eksploit, dan LX tidak melakukannya. Yang dibangun adalah jalur dengan langkah paling sedikit yang diizinkan Android, dicoba berurutan:

| Keadaan ponsel | Yang dilakukan pengguna | Yang dilakukan LX |
|---|---|---|
| Biasa | Menyalakan satu sakelar aksesibilitas, lalu mengetik PIN kalau Android memintanya | `BridgeSetupService`: mengetuk Nomor bentukan, menyalakan Proses debug nirkabel, menjawab pertanyaan Android soal Wi-Fi, membuka kode, membacanya, memasangkan diri, lalu mematikan layanannya sendiri dengan `disableSelf` |
| Aksesibilitas gagal | Mengetik kode di notifikasi | Alur `BridgePairing` |

Saat Shizuku masih didukung ada jalur tanpa sakelar sama sekali: hak shell cukup untuk `IAdbManager.enablePairingByQrCode` dengan kata sandi pilihan LX. Jalur itu ikut hilang karena butuh shell lebih dulu, dan tanpa Shizuku LX tidak punya shell sebelum pairing.

`BridgeSetupService` melanggar komitmen lama "tanpa layanan aksesibilitas", dan keputusan ini datang langsung dari pemilik produk. Pembatasnya: hanya melihat `com.android.settings` dan `com.android.systemui`, mati secara bawaan, mematikan diri setelah pairing atau setelah 120 detik, dan tidak mengetik PIN. Label dibaca dari resource kedua aplikasi itu, jadi mengikuti bahasa dan ROM ponsel. APK yang dipasang dari berkas bisa kena "setelan terbatas" Android 13 ke atas; pengguna perlu mengizinkannya dari Info aplikasi, dan jalur kode tetap tersedia.

## Batasan yang tidak bisa dihindari

**Wi-Fi.** Android menolak menyalakan Proses debug nirkabel tanpa Wi-Fi yang tersambung. Setelah restart, LX butuh Wi-Fi beberapa detik, tanpa perlu internet. Di jaringan yang belum dipercaya, Android memunculkan dialognya sendiri satu kali.

**Hidup bersama `adbd`.** `lxdb` berada di cgroup `adbd` (`uid_0/pid_<adbd>`), dan `cgroup.procs` hanya bisa ditulis `system`. Saat Android menjalankan `ctl.stop adbd`, init mematikan seluruh cgroup itu. Android menghentikan `adbd` hanya kalau Proses debug USB dan nirkabel dua-duanya mati. Maka:

- Proses debug USB menyala: LX mematikan lagi Proses debug nirkabel, `lxdb` tetap hidup.
- Proses debug USB mati: LX membiarkan Proses debug nirkabel menyala selama `lxdb` berjalan, dan Pengaturan LX menyebutnya. Kalau Wi-Fi putus, Android mematikan `adbd` dan `lxdb` ikut berakhir. Port itu hanya menerima kunci yang sudah dipasangkan.

LX tidak pernah menyalakan Proses debug USB sendiri. Itu keputusan pengguna.

Fakta cgroup dibaca langsung dari Infinix. Perilaku `ctl.stop` berasal dari sumber init AOSP, dan belum diamati di ponsel karena mengujinya memutus koneksi USB uji.

**LX dibekukan.** ROM Transsion membekukan LX beberapa detik setelah LX keluar dari depan. Saat pairing, LX berada di belakang halaman Pengaturan, jadi langkah sesudah pairing bisa tertahan sampai LX kembali ke depan. Karena itu pairing yang berhasil langsung tersimpan, `LxBridge.ready` tidak menunggu `WRITE_SECURE_SETTINGS`, dan `EmbedClient.onForeground` melanjutkan dari sana.

**Kunci bisa kedaluwarsa.** Android melupakan kunci yang tidak dipakai selama 7 hari, kecuali pemiliknya mematikan batas itu. LX memakai kuncinya setiap boot. Kalau `adbd` menolak, LX menghapus status tersambung dan meminta pairing lagi.

## Konsekuensi

- LX kini punya izin `INTERNET`. Android mewajibkannya untuk soket apa pun, termasuk loopback. `AdbLink` dan `AdbPairing` hanya membuka `InetAddress.getLoopbackAddress()`, dan tidak ada kode lain di LX yang membuka soket.
- `WRITE_SECURE_SETTINGS` adalah izin kuat. LX hanya menulis `adb_wifi_enabled`.
- Sertifikat TLS `adbd` tidak diverifikasi, seperti di klien `adb`. Yang membuktikan identitas adalah kunci LX (sesi) dan kode pairing (pairing). Aplikasi jahat yang mengiklankan port palsu hanya melihat kunci publik dan token, dan token itu tidak berguna tanpa uid shell.
- Pemakai lama dan ponsel ber-root ikut melewati LX Bridge. Aplikasi Shizuku di ponsel tidak dipakai lagi oleh LX.

## Bukti

Bukti per 2 November 2026, Infinix X6726B, Android 15:

- 12 cek di `BridgeChecks.kt` dan 2 di `EmbedChecks.kt` lulus di ponsel, termasuk pertukaran kunci dua arah, kode salah, titik di luar kurva, format kunci `adbd`, sertifikat, pairing, dan TLS `AdbLink` melawan peer loopback.
- `scripts/ui_test_bridge_pairing.sh`: klien `adb pair` asli (BoringSSL) berhasil pairing dengan kode Kotlin LX sebagai server, dan kode salah ditolak dengan "Wrong password".
- Helper, dijalankan lewat USB dengan baris perintah yang sama persis: tersambung, membuka aplikasi, bertahan saat LX dihentikan paksa lalu kembali, tetap hidup saat tidak ada jendela, dan berhenti saat APK diganti.

- Semua label otomatisasi ditemukan di Pengaturan dan System UI ponsel.
- Tombol Sambungkan otomatis membuka halaman Aksesibilitas dengan entri LX Bridge, dan dengan Wi-Fi mati membuka panel Wi-Fi.

**Berjalan sungguhan, 2 November 2026:** ponsel uji sudah dipasangkan dan LX memegang `WRITE_SECURE_SETTINGS` sebelum sesi ini dimulai. Setiap APK LX diganti, LX menulis `adb_wifi_enabled=1`, tersambung ke `adbd` lewat loopback, menjalankan `lxdb`, lalu mengembalikan setelan itu ke 0. Log menunjukkan 1,1 detik dari "switching wireless debugging on" sampai "lxdb launched through port". Pengujian tidak menyentuh sakelar apa pun.

**Belum dijalankan:** langkah `BridgeSetupService`, karena belum ada catatan bahwa pairing ponsel ini memakainya.

## Restart, 3 November 2026

Setelah pemilik ponsel memulai ulang ponselnya, jendela Chrome di LX berhenti di pesan "LX Bridge tidak menyala". Log Android di ponsel ini hanya menyimpan sekitar satu menit, jadi penyebabnya dicari dengan mengulang keadaan yang sama tanpa restart. Ada tiga cacat, dan saat boot ketiganya bisa muncul bersamaan:

1. **Port lama.** Proses debug nirkabel mendapat port baru setiap kali dinyalakan, sedangkan nama layanannya tetap. `AdbPorts` me-resolve tiap layanan sekali, dan NSD terus menjawab dengan port sebelumnya. LX mengetuk port yang sudah tertutup sampai batas 25 detik, padahal `adbd` mendengarkan di port baru. Saat boot setelan itu dinyalakan dan dimatikan berkali-kali (`generation:10` di `dumpsys settings`) oleh LX, oleh Android, dan oleh Shizuku, yang masih terpasang di ponsel uji dan menyalakannya setiap boot tanpa pernah mematikannya. Di Android 14 ke atas, `AdbPorts` kini mengikuti setiap layanan lewat `registerServiceInfoCallback`, jadi port baru langsung sampai. Port yang tertutup tiga kali dianggap usang, dan pencarian dimulai dari awal.
2. **`lxdb` mati sebelum lepas dari sesi.** Penyebabnya ada di ADR-008, bagian lepas dari sesi adb. LX mencatat "lxdb launched", lalu menunggu daemon yang sudah tidak ada. Kini LX juga menjalankan `lxdb` lagi, paling banyak dua kali, kalau tidak ada kabar dalam 6 detik.
3. **Satu percobaan, lalu buntu.** `EmbedClient` menyerah setelah 25 detik, dan jendela masuk FAILED. Status tetap `READY` sebelum dan sesudah `lxdb` tersambung, jadi listener tidak pernah dipanggil dan jendela tidak pernah pulih. Kini LX mencoba lagi setelah 3, 6, 12, dan 24 detik, lalu setiap 30 detik, selama LX di depan. Jendela tetap menunggu sambil menyebut bahwa LX masih mencoba, dan `EmbedClient` memberi tahu listener setiap kali `lxdb` tersambung, sehingga jendela yang gagal atau kehilangan aplikasinya membuka lagi aplikasinya. Pesan gagal tinggal untuk satu keadaan: Android mematikan setelan itu tiga kali tepat setelah LX menyalakannya, yang terjadi di Wi-Fi yang belum diizinkan.

LX juga menyalakan lagi Proses debug nirkabel kalau ada yang mematikannya di tengah satu percobaan (Android melakukannya selama Wi-Fi belum siap), dan menunda pengembalian setelan 10 detik setelah `lxdb` tersambung, supaya aplikasi lain yang menyalakannya bersamaan sempat selesai. Setiap langkah tercatat di `no_backup/lx-bridge.log` (300 baris terakhir, tidak ikut dicadangkan), yang bisa dibaca dengan `run-as com.lxlauncher cat no_backup/lx-bridge.log`.

**Bukti, Infinix X6726B, Android 15.** `lxdb` dihentikan 10 kali sementara 8 proses sibuk membebani CPU, dan LX dibiarkan menyalakannya lagi. Build lama: 0 dari 10 tersambung, karena port lama dan tanpa percobaan ulang. Build baru: 10 dari 10, masing-masing sekitar 4,2 detik, 3 detik di antaranya menunggu daemon lama kembali. Simulasi boot dengan beban yang sama, Wi-Fi menyala belakangan, token lama dihapus, dan setelan dimatikan lalu dinyalakan pihak lain sehingga port berganti: `lxdb` tersambung 12 detik setelah Wi-Fi menyala, dan jendela Chrome terbuka sendiri. Sebelum perbaikan juga tercatat satu kejadian nyata "lxdb launched" tanpa daemon, tanpa beban buatan.

**Restart sungguhan setelah perbaikan, 3 November 2026 pukul 02:53.** Pemilik ponsel membuka pola 10 detik setelah layar menyala, dan LX mulai 2 detik kemudian. Wi-Fi baru tersambung 19 detik setelah kunci dibuka; selama itu jendela Chrome menunggu Wi-Fi. LX memulai LX Bridge saat itu juga, `lxdb` tersambung 3,2 detik kemudian, Chrome terbuka di jendela LX setengah detik setelahnya, dan Proses debug nirkabel dimatikan lagi 10 detik sesudahnya. Tidak ada pesan gagal dan tidak ada crash. Garis waktu boot itu, disaring ke baris LX, `lxdb`, dan `adbd`, tersimpan di `build/reboot-1103/boot-timeline.txt`.

## Rujukan

- AOSP `system/core/adb/pairing_auth`, `pairing_connection`, `AdbDebuggingManager.java`, `AdbService.java`, `system/core/init/service.cpp`.
- BoringSSL `crypto/curve25519/spake25519.cc`.
- Shizuku dan fork thedjchi, sebagai rujukan alur pengguna. Tidak ada kode yang disalin, dan LX tidak lagi bergantung padanya.
