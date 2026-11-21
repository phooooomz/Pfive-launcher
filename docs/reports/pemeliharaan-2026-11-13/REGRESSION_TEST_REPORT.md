# Uji regresi, 13 November 2026

Lingkungan: Infinix HOT 60 5G (X6726B, serial disamarkan), Android 15, 720x1600 pada 320 dpi dan 120 Hz, Gboard, Wi-Fi tersambung. LX dipasang sebagai APK debug. Build akhir `c3` identik dengan build dari source terakhir (hash dex, manifest, dan pustaka sama). Komputer: Linux, NDK 29, Gradle. Setiap uji di ponsel memakai cadangan data LX lewat `run-as` dan memulihkannya sesudahnya. Header tata letak pemilik (`LXL1 f25abd4f 3971`) diperiksa sesudah setiap uji, dan selalu cocok sejak RCA-4 diperbaiki. Log dari setiap uji ada di `build/maint-1113/`.

## Matriks

| Uji | Tujuan | Build | Hasil | Bukti |
|---|---|---|---|---|
| `ui_test_keyboard_web.py`: 50 ketukan tunggal | Huruf masuk dan keyboard tetap terbuka di halaman web (Chromium) | build lama di ponsel, `c3` | PASS 50/50, PASS 50/50 | `kbweb-before-2.out`, `kbweb-c3.out` |
| `ui_test_keyboard_web.py`: rentetan cepat | 5 rentetan 8 ketukan | build lama, `c3` | PASS 5/5, PASS 5/5 | sama |
| `ui_test_keyboard_web.py`: ketukan saat keyboard naik | 14 ketukan 0,3 sampai 1,5 detik setelah kolom diketuk | build lama, `c3` | PASS 14/14, PASS 14/14 | `kbweb-early-lx`, `kbweb-c3-early` |
| `ui_test_keyboard_web.py`: jendela baru | 8 jendela, kolom diketuk saat jendela masih menata diri, tiga huruf langsung | `c3` | PASS 8/8 | `kbweb-c3.out` |
| `ui_test_keyboard_web.py --outside-only` | Pembanding: probe sebagai aplikasi biasa di luar LX | build lama | PASS 14/14 | `kbweb-outside` |
| `ui_test_keyboard_web.py --floating` | Jendela mengambang yang diperkecil | `c3` | BLOCKED | Ketuk dua kali di judul tidak mengubah lebar jendela di tata letak ponsel; skenario belum bisa dibuat lewat UI |
| `ui_test_keyboard.py` (probe EditText, 5 putaran) | Keyboard, tombol aplikasi, ruang kosong, Back, kolom LX | `c3` | PASS 26/26 | `kb-c3.out` |
| `ui_test_keyboard_edges.py` | Tombol di tepi kiri, kanan, dan bawah keyboard, pita samping, garis bingkai, judul | `c3` | PASS 36/36 | `kbedges-c3.out` |
| Instrumentasi lengkap (tanpa `-e only`) | Semua uji umum, termasuk uji pemetaan sentuhan dan pustaka `lxdb` yang baru | `c3` | PASS 51/51 | `instr-all-c3.txt` |
| `-e only windows` × 4 | Jendela, tepi aplikasi, resize di bawah jari, waktu buka, frame resize | `b0` × 2, `c3` × 2 | PASS semua, 0 FAIL | `ab-c3-*.txt` |
| `-e only tedit` | 24 uji Tedit | `c3` | PASS 24/24 | `instr-tedit-c3.txt` |
| Peluncuran `lxdb` | Daemon naik dan tersambung setelah APK diganti | `c3` | PASS: "lxdb launched through port 45049", lalu "lxdb attached" 20 ms kemudian | `no_backup/lx-bridge.log` |
| Pemulihan data uji | Tata letak pemilik kembali setelah setiap uji | semua uji sejak perbaikan | PASS di setiap uji | header tata letak |
| `scripts/test-host.sh` | Core C++ di host, dengan dan tanpa sanitizer | source akhir | PASS 227, PASS 226 | `test-host.log` |
| `:app:lintDebug` | Lint Android | source akhir | 0 error, 8 peringatan (sama dengan sebelumnya) | `app/build/reports/lint-results-debug.txt` |

Jumlah pengulangan untuk alur keyboard yang berulang kali dilaporkan: 50 ketukan tunggal, 40 ketukan dalam rentetan, 14 ketukan saat keyboard naik, dan 24 huruf di 8 jendela baru per build. Ditambah 26 langkah EditText dan 36 langkah tepi keyboard, totalnya 190 langkah uji keyboard pada build akhir, semuanya lulus, tanpa satu pun keyboard tertutup.

## Kegagalan selama sesi dan penanganannya

| Uji | Kegagalan | Sebab | Tindakan |
|---|---|---|---|
| `ui_test_keyboard_web.py` putaran pertama | Baris keyboard tidak ditemukan | Garis fokus kolom web memutus warna probe, sehingga awal keyboard salah terbaca | Pencari `page()` mengabaikan celah pendek |
| Ketukan 0,15 detik setelah kolom diketuk | Keyboard tertutup (`field blur`, `HIDE_SOFT_INPUT` dari client) | Ketukan dikirim sebelum keyboard tampil, jadi jatuh ke halaman. Ini memperlihatkan mekanisme RCA-3 | Batas bawah uji dinaikkan ke 0,3 detik, waktu tercepat orang melihat keyboard |
| Ketukan saat keyboard naik di `c3`, run pertama | 4 dari 14 tercatat gagal | Pemeriksa skrip mengira huruf ada di ujung teks, padahal kursor jatuh di tengah teks panjang. Hurufnya masuk dan keyboard tetap terbuka | Pemeriksa menghitung huruf. Run ulang: 14/14 |
| `-e only windows` pertama | Semua jendela 0x0 | Pembungkus uji menekan HOME, yang membawa task Home lain ke depan | Opsi `NO_HOME` |
| Data pemilik setelah uji 01:25 | Tata letak bawaan menimpa milik pemilik | RCA-4 | Diperbaiki dan dipulihkan dari cadangan pukul 01:18 |

## Log

Log LX berisi peringatan `LX Bridge did not bring lxdb up in 25000 ms` setiap kali build tanpa perbaikan RCA-1 terpasang, terakhir pukul 02:11:52 saat A/B memasang `b0`. Sesudah `c3` dipasang terakhir kali pukul 02:12:06, peringatan itu tidak muncul lagi sampai perekaman dihentikan. Tidak ada `FATAL EXCEPTION`, ANR, atau pelanggaran StrictMode dari LX atau `lxdb` di rekaman log. Uji keyboard dan instrumentasi juga memeriksa buffer crash dan tidak menemukan crash LX.

## Memori setelah uji

`dumpsys gfxinfo`: memori GPU LX 8,16 MB, dengan 1,12 MB render target scratch (5 entri) yang memuat layer bilah kaca. `dumpsys meminfo`: PSS total 125 MB. Tidak ada angka pembanding dari build lama, jadi selisih memori akibat layer tidak diklaim.

## Yang tidak diuji

- Brave atau Chrome milik pemilik. Uji itu memakai aplikasi dan data pemilik, jadi menunggu izinnya.
- Jendela mengambang yang diperkecil (BLOCKED, lihat matriks).
- Restart ponsel sungguhan dengan build akhir. Peluncuran `lxdb` dari nol diuji lewat penggantian APK, yang juga menghentikan daemon lama.
- Waydroid tidak dipakai, sesuai permintaan pemilik.
- Cakupan kode (code coverage) tidak diukur. Proyek belum punya instrumen cakupan untuk uji di perangkat.
