# Risiko keamanan, 13 November 2026

Cakupan putaran ini: jalur yang diubah (peluncuran `lxdb`, pemetaan sentuhan jendela aplikasi, fading edge, resize Tedit), jalur yang menerima data dari luar dan belum pernah diaudit (pemasangan ekstensi `.vsix`, unduhan dan pembongkaran rootfs Debian), serta alat uji yang menyentuh data pemilik. Model ancaman lengkap tetap di `docs/audits/threat-model.md`. Audit 9 November (`docs/audits/stability-audit-2026-11-09.md`) tidak diulang.

## Temuan

| ID | Tingkat | Lokasi | Temuan | Bukti | Status |
|---|---|---|---|---|---|
| SEC-1 | Tinggi (ketersediaan) | `embed/ShellHelper.kt`, `bridge/LxBridge.kt` | `lxdb` tidak bisa dinyalakan karena pustakanya terkompresi di APK. Jendela aplikasi berhenti bekerja begitu daemon lama berakhir. Tidak ada celah akses, hanya fitur yang mati. | `error: unable to open file ".../base.apk!/lib/arm64-v8a/liblxdb.so"` di ponsel; log LX Bridge "did not reach LX". | Diperbaiki, diverifikasi di Infinix. |
| SEC-2 | Rendah | Perubahan SEC-1 | `lxdb` kini dijalankan dari `/data/app/.../lib/arm64/liblxdb.so`. Folder itu milik `system`, hanya bisa ditulis sistem, dan isinya dibongkar dari APK yang sama saat dipasang. Kepercayaannya setara `base.apk`. Baris perintah tetap menolak path yang mengandung tanda kutip, dan LX tetap memeriksa token serta path APK saat daemon tersambung. | `ls -la` folder pustaka: `drwxr-xr-x system system`; `ShellHelper.command` dengan `require`. | Diterima, tanpa tindakan. |
| SEC-3 | Info | `tedit/ext/ExtensionStore.kt` | Pemasangan `.vsix` hanya mengambil isi `extension/`, menolak `..`, membandingkan path kanonik dengan folder staging, dan membatasi jumlah berkas serta total ukuran. Folder baru ditukar ke tempatnya lewat rename di bawah kunci. | Baca source, baris 128-175. | Tidak ada celah ditemukan. |
| SEC-4 | Info | `tedit/linux/LinuxRuntime.kt`, `TarUnpacker.kt` | Arsip rootfs dari APK dan dari unduhan dicocokkan dengan SHA-256 yang tertanam di kode sebelum dibongkar. Nama di tar dibersihkan, dan nama absolut atau `..` ditolak. | Baca source. | Tidak ada celah ditemukan. Tautan simbolik di dalam rootfs belum diuji dengan arsip jahat, karena arsipnya tetap. |
| SEC-5 | Sedang (integritas data, alat uji) | `scripts/ui_test_file_manager.py`, `scripts/ui_test_app_windows.py` | Pemulihan data setelah uji bisa kalah balapan dengan Android yang menyalakan ulang LX, sehingga tata letak pemilik tertimpa tata letak bawaan. | RCA-4, log 01:25:00. | Diperbaiki dan diverifikasi. Data pemilik dikembalikan dari cadangan. |
| SEC-6 | Info | `embed/EmbeddedAppTool.kt` | Ketukan bisa mendarat di titik lain dalam aplikasi daripada yang terlihat (RCA-3). Ini bukan celah keamanan, tetapi ketukan yang salah tempat bisa menekan tombol aplikasi yang tidak dimaksud. | Baca source, uji pemetaan. | Diperbaiki. |

## Risiko yang tetap ada

Risiko lama dari model ancaman tidak berubah dan tidak diperiksa ulang secara aktif di putaran ini:

- Program di Debian bisa membaca berkas privat LX, karena berjalan sebagai pengguna Android milik LX.
- LX menerima sertifikat TLS apa pun dari port adbd di loopback. Token peluncur `lxdb` tetap tidak cukup untuk masuk, karena provider LX juga memeriksa izin dan uid shell.
- Nama socket abstrak `@lxdb-u0` bisa lebih dulu dipakai aplikasi lain. Akibatnya penolakan layanan, tanpa hak baru.

Tidak ada pemindaian advisory dependensi otomatis di putaran ini. Proyek tidak memakai dependensi Maven untuk kode aplikasi selain toolchain Android, dan daftar komponennya ada di `docs/research/sbom.cdx.json`.
