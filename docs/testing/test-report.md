# Laporan pengujian 0.1.1

Tanggal: 25 Oktober 2026. Semua hasil di bawah berasal dari perintah yang benar-benar dijalankan. Perintahnya tercantum agar bisa diulang.

## Ringkasan

| Lapisan | Perintah | Waydroid | Infinix HOT 60 5G |
|---|---|---|---|
| Unit, fuzz, regresi core C++ (host) | `scripts/test-host.sh` | 95 lulus, 0 gagal | |
| Core C++ di bawah AddressSanitizer + UBSan | `scripts/test-host.sh` | 95 lulus, tanpa laporan sanitizer | |
| Lint Android | `./gradlew :app:lintDebug` | 0 error, 3 warning (versi compileSdk, targetSdk, dan Gradle sengaja dipatok) | |
| Integrasi (instrumentation) | `scripts/test-device.sh` | 9 lulus, 0 gagal | 9 lulus, 0 gagal |
| UI end-to-end batch 1 | `scripts/ui_test.py` | 29 lulus, 0 gagal | 29 lulus, 0 gagal |
| UI end-to-end batch 2 | `scripts/ui_test_more.py` | 18 lulus, 0 gagal | 18 lulus, 0 gagal |
| UI end-to-end batch 3 | `scripts/ui_test_full.py` | 30 lulus, 0 gagal | 30 lulus, 0 gagal |
| Uji stres acak | `monkey -p com.lxlauncher`, tiga seed | | 21.000 event, 0 crash, 0 ANR |
| Log aplikasi selama semua batch | `adb logcat`, disaring ke proses LX | 0 crash, 0 ANR | 0 crash, 0 ANR |

Perangkat:

- Waydroid, Android 13 (API 33), x86_64, 1920x1028 pada 180 dpi.
- Infinix HOT 60 5G (X6726B), Android 15 (API 35), arm64-v8a, 720x1600 pada 320 dpi, bahasa sistem Indonesia, dengan work profile aktif.

Kedua perangkat menjalankan APK dan skrip yang sama. Skrip UI membaca label dalam bahasa Inggris maupun Indonesia, menggulir strip tab dan daftar folder di layar sempit, dan memilih nama perangkat dari `settings get global device_name`.

## Audit stabilitas, 9 November 2026

Temuan, perbaikan, dan yang ditunda ada di `docs/audits/stability-audit-2026-11-09.md`. Ringkasan hasil uji setelah perbaikan:

- `scripts/test-host.sh`: 226 lulus; ASan dan UBSan 225 lulus, 1 dilewati, tanpa laporan sanitizer.
- `scripts/build.sh`: lint 0 error, 6 peringatan lama.
- Infinix HOT 60 5G, APK debug: suite instrumentation lengkap 49 lulus, `-e only maintenance` 7 lulus, `-e only windows` 16 lulus setelah uji `openNormal` diperbaiki (run pertama 15 dari 16, penyebabnya state `"normal"` yang tidak dikenal core), `ui_test_keyboard.py` 26 lulus, `ui_test_keyboard_edges.py` 36 lulus.
- Jendela uji resize kini selalu mulai di 480x664 px di titik yang sama, dulu di tempat yang ditinggalkan run sebelumnya. Angka resize sejak tanggal ini tidak sebanding dengan yang lebih lama.
- `pm hide` ditolak Android 15 karena izin `MANAGE_USERS`, jadi pembekuan aplikasi diuji lewat pemeriksaan "masih terpasang" di suite; pencopotan APK uji sungguhan tetap menghapus tempat aplikasinya.
- Log LX: tanpa crash, ANR, atau StrictMode. Satu-satunya peringatan LX berasal dari uji label aplikasi yang sengaja merusak berkasnya.
- Layout di HP sesudah pengujian berbeda dari salinan awal sesi karena sebuah jendela aplikasi dibuka dari menu Aplikasi pukul 03:49, di luar waktu uji otomatis. Layout itu dibiarkan.

## Animasi kategori di menu Aplikasi, 9 November 2026

Infinix HOT 60 5G, APK debug, menu Aplikasi terbuka dengan 9 kategori (110 aplikasi). Pengukuran: sepuluh ketukan di sisi kiri baris kategori (Game, Media, Produktivitas, Sosial, Lainnya, Kerja, Semua aplikasi, Terakhir, Game, Media), jeda 0,8 detik, lalu `dumpsys gfxinfo com.lxlauncher`. Satu putaran pemanasan dibuang, dua putaran dicatat.

| Ukuran | Build sebelumnya | Animasi saja | Animasi, baris dipakai ulang, dua baris kategori dibangun ulang |
|---|---|---|---|
| Frame janky | 6,0 sampai 6,5% | 3,0% | 2,3% |
| p95 | 36 sampai 38 ms | 13 sampai 15 ms | 14 sampai 15 ms |
| p99 | 81 sampai 93 ms | 61 sampai 65 ms | 28 sampai 30 ms |
| Membangun daftar kategori (`LxPerf: apps menu`) | sampai 40 baris dalam frame ketukan | 14 baris, 10 sampai 14 ms | 14 baris, 3 sampai 7 ms |

Animasi menambah frame yang digambar (sekitar 345 per putaran, dulu 168), tetapi jumlah frame janky tetap satu per ketukan atau kurang. Dengan `animator_duration_scale 0` (lalu dikembalikan ke 1.0) daftar berganti seketika: 1 frame janky dari 115, p99 21 ms.

Profil `simpleperf` sebelum penghematan: pembuatan `TextView` 13% waktu thread utama dan `groupRowView` 9%. Kini hanya baris kategori yang tampilannya berubah yang dibangun ulang, baris aplikasi dipakai ulang di antara daftar, dan aplikasi di bawah tepi menu ditambahkan sekitar 4 ms per frame setelah animasi masuk selesai.

Pemeriksaan:

- Rekaman layar (`screenrecord`, frame dibaca di komputer): submenu terbuka dari pita baris Produktivitas, baris terdekat datang lebih dulu; pindah ke Game menggeser submenu turun ke barisnya; menutup Game melipatnya di tempat sambil memudar dalam 4 frame. Putaran pertama memperlihatkan submenu yang melompat ke puncak menu sebelum melipat; urutan `removeSub()` dan `relayout()` diperbaiki dan direkam ulang.
- Baris yang dipakai ulang: sesudah berpindah dari Produktivitas ke Lainnya, pencarian "kalk" mengisi baris lama dengan hasil; mengetuk Kalkulator membukanya di jendela LX (display #34), dan tombol tutup jendela mengakhirinya.
- Keyboard: panah kanan pada Game membuka submenu dan memindah fokus ke AHA Games; panah kiri menutupnya dan fokus kembali ke baris Game yang baru dibangun.
- Suite instrumentation lengkap: 48 lulus, 0 gagal.
- Log LX selama semua langkah: tanpa error, crash, ANR, atau StrictMode. Satu-satunya peringatan LX berasal dari uji label aplikasi yang sengaja merusak berkasnya.
- Data LX dicadangkan sebelum Kalkulator dibuka dan dipulihkan sesudahnya; layout akhir sama dengan awal sesi.

## Infinix: keyboard jendela aplikasi, resize, dan start dingin, 9 November 2026

Infinix HOT 60 5G (X6726B), Android 15, 720x1600 pada 320 dpi, layar 120 Hz, bahasa Indonesia, Gboard, APK debug. Lanjutan sesi 8 November, yang hanya bisa menguji jalur keyboard aplikasi di Waydroid (Android 13). Data LX pemilik dicadangkan dengan `run-as` sebelum tiap uji dan dipulihkan sesudahnya.

Keyboard aplikasi di jendela LX yang memenuhi layar:

| Uji | Hasil |
|---|---|
| `scripts/ui_test_keyboard.py` (probe di jendela LX, 5 putaran) | 26 lulus, 0 gagal, dua kali: sebelum dan sesudah kode C++ build debug dioptimasi |
| Ketukan 1, 2, dan 3 dp di dalam tepi kiri dan kanan baris Q dan A, dan Hapus di tepi kanan | Mengetik q, p, a, l, dan menghapus satu huruf; keyboard tetap terbuka |
| Ketukan 1, 2, dan 3 dp di atas tepi bawah isi jendela (bantalan bawah Gboard) | Mengetik spasi |
| Ketukan di pita kiri dan kanan jendela, di garis bingkai, dan di judul | Teks tidak berubah, keyboard tetap terbuka, LX tidak mengambil keyboard |
| Jendela sesudah semua ketukan | Tidak bergeser, tidak berubah ukuran, tidak ada resize yang dimulai |

Uji tepi kini ada di `scripts/ui_test_keyboard_edges.py` (36 lulus, 0 gagal pada build ini). Letak tombol dicari dari warna probe dan latar Gboard di tangkapan layar yang hanya dibaca di memori. Satu pelajaran dari putaran pertama: isi jendela yang memenuhi layar berakhir di y=1388, lalu garis bingkai 3 px, lalu bilah bawah LX. Ketukan uji di y=1500 membuka Jam dari bilah itu, jadi skrip menolak ketukan di bawah isi jendela.

Profil `simpleperf` di ponsel (`simpleperf record --app com.lxlauncher -g`):

- Resize LX Settings: `WindowLayer.place` 49% waktu thread utama, `TextView.onMeasure` 27%, dan autosize judul jendela (`TextView.autoSizeText`) 7,8%. Judul jendela membangun beberapa layout teks di setiap frame resize untuk memilih ukurannya.
- Build debug: hampir semua kode Java, termasuk framework, berjalan di interpreter switch ART, ditambah CheckJNI pada tiap panggilan JNI. Kode C++ (inti, blur, `lxdb`) dikompilasi `-O0`; blur wallpaper 180x400 memakan sekitar 170 ms, padahal 6,9 ms di host.
- Start dingin: JIT 46% CPU, pembacaan daftar aplikasi 25% (80% di antaranya `LauncherActivityInfo.getLabel`), thread wallpaper 4% (68% di antaranya blur).

Perubahan:

- Resize membawa sisa gerak jari yang tertahan di ukuran terkecil atau terbesar ke event berikutnya. Sebelumnya, jari yang melewati ukuran terkecil lalu berbalik langsung membesarkan jendela lagi, dan koreksi saat jari diangkat meninggalkan jendela 1 px di atas ukuran terkecil (uji sudut Settings di ponsel: 481x361, mestinya 480x360).
- Isi jendela yang tata letaknya lebih dari 4 ms menunggu empat kali waktu tata letaknya (paling lama 150 ms) sebelum ditata lagi selama resize, dan ditata saat waktu tunggunya habis walaupun jari diam. Sebelumnya isi menunggu paling banyak 3 frame, jadi di layar 120 Hz Settings tetap ditata setiap 4 frame.
- Judul jendela memilih ukurannya dengan `Paint.measureText` pada ukuran sp bulat yang sama seperti autosize (`WindowTitle`).
- Daftar aplikasi menyimpan label dan waktu pasang tiap aplikasi di cache (`AppLabels`). Entri dipakai selama path APK aplikasinya sama, dan seluruh berkas hanya untuk bahasa dan build sistem yang sama.
- Wallpaper foto menyimpan hasil decode ukuran layar di cache (`WallpaperPixels`, sekitar 4,6 MB per orientasi di 720x1600), kunci: referensi, waktu pasang APK atau waktu ubah berkas, dan ukuran. Pixel wallpaper yang tidak lagi dipakai workspace mana pun dihapus bersama salinan gambarnya.
- Kode C++ build debug dikompilasi `RelWithDebInfo` (`-O2 -g`). Inti dan `lxdb` tidak memakai `assert()`, jadi `NDEBUG` tidak membuang pemeriksaan.
- `LxPerf` kini juga mencatat `app list read in N ms (M apps, K labels kept)`.

Angka di ponsel, build debug:

| Ukuran | Build 8 November | Build ini |
|---|---|---|
| Daftar 114 aplikasi siap saat start dingin | 1.249 sampai 1.527 ms | 208 sampai 372 ms |
| Wallpaper foto siap saat start dingin | 355 sampai 647 ms | 60 sampai 314 ms (396 ms pada start pertama sesudah pasang, saat foto masih di-decode) |
| Resize Settings: tata letak isi per tarikan 43 frame | 12 sampai 14 kali | 10 sampai 11 kali |
| Resize Settings: UI thread median, frame UI lewat anggaran 120 Hz | 2,52 ms, 77 dari 406 | 1,91 ms, 70 dari 410 |
| Resize Files: frame lewat anggaran | 14,4% | 9,2% |
| Resize Notes: UI thread p90, frame UI lewat anggaran | 5,28 ms, 1 | 4,48 ms, 0 |
| Buka Settings, Files, Monitor, Notes (median tiga kali) | 184, 56, 58, 43 ms | 175, 66, 64, 38 ms |

Build 8 November belum mencatat waktu daftar aplikasi; angka sebelumnya diukur pada build antara yang sudah punya log itu tetapi belum menyimpan label. Frame pertama tetap 150 sampai 240 ms sesudah activity dibuat. Waktu buka jendela berubah dalam batas variasi antarputaran. Frame lewat anggaran untuk Notes (34,9%) datang dari tahap sesudah UI thread (RenderThread dan GPU, issue median 3,8 ms); UI thread-nya tidak pernah lewat anggaran.

Pemeriksaan lain:

- `-e only windows`: 16 lulus, 0 gagal, termasuk dua uji baru: sudut yang ditarik melewati ukuran terkecil kembali ke bawah jari, dan cache pixel wallpaper hanya dipakai untuk gambar dan ukurannya sendiri.
- Suite instrumentation lengkap: 48 lulus, 0 gagal, termasuk dua uji baru (label aplikasi tersimpan, ukuran judul jendela) dan tiga uji LX Bridge yang gagal di Waydroid karena butuh Android 14. Suite ini macet bila tugas pemilik naik ke depan saat `am instrument` mematikan LX: Hiber membekukan proses LX (`cgroup.events`: `frozen 1`). Tekan Home dan suite berlanjut.
- `scripts/test-host.sh`: 222 lulus, 1 dilewati, juga di bawah AddressSanitizer dan UBSan.

Yang tersisa: halaman Tampilan di LX Settings masih menata ulang puluhan teks dalam 18 sampai 21 ms di build debug, jadi resize-nya masih tersendat beberapa kali per detik. Build release (R8, tanpa `debuggable`) tidak bisa diukur dengan `WindowChecks` karena kelasnya diganti nama; laporan Oktober mencatat start dingin build release di ponsel ini 302 sampai 336 ms, dibanding sekitar 830 ms untuk build debug.

## Keyboard di tepi jendela aplikasi, resize, dan waktu buka, 8 November 2026

Waydroid (LineageOS 20, Android 13, x86_64, 1920x1028 pada 180 dpi), APK debug. Pemilik melaporkan dua hal: keyboard kadang tertutup sendiri saat ia menyentuh bagian keyboard, dan LX terasa berat saat jendela diubah ukurannya dan kadang saat dibuka. Ponsel Infinix tidak tersambung selama sesi ini. Jalur aplikasi di jendela LX butuh Android 14, jadi bagian keyboard aplikasi hanya diuji sampai tingkat jalur sentuh (APK uji, `-e only windows`), tidak dengan keyboard aplikasi sungguhan.

Pemeriksaan baru ada di `app/src/androidTest/.../WindowChecks.kt` dan dijalankan dengan `adb shell am instrument -w -r -e only windows com.lxlauncher.test/com.lxlauncher.test.SelfTestInstrumentation`. Jari disuntik dari tes pada 60 Hz dan waktu tiap frame dibaca dari FrameMetrics. `input swipe` tidak dipakai untuk mengukur: ia mengirim gerakan hanya secepat LX menanganinya, sehingga LX yang lambat malah tampak lancar dan jendela tertinggal dari jari.

| Uji | Build 7 November | Build ini |
|---|---|---|
| Tekanan 3 dp di dalam tepi kanan, bawah, dan kiri jendela aplikasi | Jendela berubah ukuran (990 menjadi 1050 px); tekanan tidak sampai ke aplikasi | Sampai ke aplikasi, ukuran tetap |
| Tarikan dari 4 dp di luar tepi kanan, dan dari bawah sudut kanan bawah | Tidak berpengaruh | Mengubah ukuran (sudut: dua sisi) |
| Tabel keputusan pemegang keyboard (8 kasus) | Tidak ada | Lulus |
| Wallpaper yang tampil masih di cache sesudah `TRIM_MEMORY_UI_HIDDEN` | Dibuat ulang | Langsung tersedia |
| Sudut jendela di bawah jari sesudah tarikan (Settings, Files, Notes) | Meleset 3 sampai 4 px | Tepat (toleransi 1 px) |
| Isi jendela kembali penuh sesudah tarikan | Lulus (isi selalu ditata ulang di tiap frame) | Lulus untuk ketiga jendela |
| Resize LX Settings: frame lewat anggaran 59 Hz | 48,5% | 21,5% |
| Resize LX Settings: UI thread median / p90 | 9,02 / 12,79 ms | 3,38 / 9,91 ms |
| Resize File Explorer: UI thread median | 3,65 ms | 2,96 ms |
| Buka LX Settings: pertama / median tiga kali | 527 / 253 ms | 325 / 172 ms |

Angka itu dari satu putaran A/B berselang-seling pada beban host yang sama (load 1,5). Putaran sebelumnya bervariasi lebih lebar karena host menjalankan pekerjaan lain, jadi bandingkan arah perubahannya, bukan desimalnya.

Pemeriksaan lain:

- Suite instrumentation lengkap: 44 lulus, 3 gagal. Ketiganya pemeriksaan LX Bridge yang butuh Android 14 dan gagal sama persis di build 7 November. `-e only maintenance`: 7 lulus.
- `./gradlew :app:lintDebug`: 0 error, 6 warning yang sudah ada sebelumnya (CleanupTool, dua `PluralsCandidate`, versi Gradle dan targetSdk).
- Monkey `-p com.lxlauncher --pct-syskeys 0 --pct-appswitch 0 --pct-anyevent 0`, seed 4207 dan 9133: 7.000 event, 0 crash, 0 ANR. Log proses LX hanya berisi peringatan IME bawaan Android (InputConnection tidak aktif, `InputEventSender` urutan tak dikenal).
- Kolom teks LX dengan LatinIME: pencarian taskbar dan Notes di dalam jendela. Ketukan pada q dan Shift di tepi kiri, p, Backspace, dan Shift di tepi kanan, ?123 di sudut kiri bawah, dan spasi di tepi bawah semuanya mengetik, dan keyboard tetap terbuka. Jendela Notes naik ke atas keyboard.
- File Explorer dengan 8 gambar uji di `Pictures/LXThumbTest`: gambar mini tampil, dan tetap tampil sesudah jendela diperbesar dari 990x441 ke 1392x694 px.
- Ganti tema ke Graphite Mono lalu kembali: satu kali bangun halaman Settings, rebuild 194 ms, jendela dan halaman yang terbuka tetap.
- Mode malam: pergantian `uiMode` tidak lagi memicu rebuild. Di Waydroid SystemUI juga memasang ulang overlay warna tema (`Config changes=80000000`, path aset), sehingga Android memulai ulang activity LX. Itu di luar kendali aplikasi; frame pertama sesudahnya muncul 75 ms setelah activity dibuat.

Log baru untuk pemantauan dibaca dengan `adb logcat -s LxPerf:D LxInput:D`. `LxPerf` mencatat waktu muat layout, frame pertama, bangun tiap jendela dan halaman Settings, rebuild, wallpaper yang diolah, level trim memori, dan ringkasan tiap tarikan resize. `LxInput` mencatat setiap kali keyboard berpindah antara aplikasi dan LX.

Belum diuji: keyboard aplikasi sungguhan di jendela LX (Android 14+), termasuk ketukan pada pita di samping jendela aplikasi yang memenuhi layar, dan tombol Hapus/Enter dari keyboard LX saat jendela aplikasi di depan. Untuk ponsel, tambahkan ke `scripts/ui_test_keyboard.py` ketukan pada tombol tepi dan baris bawah keyboard aplikasi, lalu periksa `keyboard true` di log probe.

## Restart, keyboard, dan layar penuh, 3 November 2026

Infinix X6726B, Android 15. Pemilik ponsel melaporkan tiga masalah: setelah restart, jendela aplikasi memuat sebentar lalu berhenti di "LX Bridge tidak menyala"; keyboard kadang tidak muncul; dan layar penuh, tombol Kembali, serta tombol X belum bekerja seperti yang ia harapkan. Penyebab dan perbaikannya ada di ADR-007 (bagian Restart) dan ADR-008. Pengujian keyboard dan tombol jendela memakai dua aplikasi uji di APK uji (`LxProbeType`, `LxProbeAuto`), bukan aplikasi pemilik ponsel.

| Uji | Hasil |
|---|---|
| Tes host `scripts/test-host.sh` | 213 lulus, termasuk 2 tes baru untuk keluar dari layar penuh. ASan dan UBSan: 212 lulus tanpa laporan, 1 dilewati sengaja |
| Lint dan build `scripts/build.sh` | 0 error, 3 warning lama. `liblxdb.so` 358 KB untuk arm64 di APK release |
| `lxdb doctor` | 21 panggilan ditemukan, termasuk `removeTask` dan `syncInputTransactions` |
| `lxdb` dihentikan 10 kali, CPU dibebani 8 proses sibuk | Build lama: 0 dari 10 tersambung kembali. Build baru: 10 dari 10, sekitar 4,2 detik masing-masing |
| Simulasi boot: beban CPU, Wi-Fi menyala belakangan, token dihapus, port berganti | `lxdb` tersambung 12 detik setelah Wi-Fi menyala, jendela Chrome terbuka sendiri |
| `scripts/ui_test_keyboard.py --rounds 5` | 26 lulus, 0 gagal; dengan CPU dibebani juga 26 lulus. Build lama: Kembali di putaran pertama menutup keyboard dan aplikasinya sekaligus |
| `scripts/ui_test_window_controls.py` | 13 lulus, 0 gagal: layar penuh 720×1440 tanpa bilah judul, Kembali kembali ke 682×1097, X mengeluarkan aplikasi dari Recents dan mengakhiri prosesnya |
| `scripts/ui_test_framed_apps.py --cycles 2`, Kalkulator saja | 15 lulus, 0 gagal. Aplikasi lain tidak diikutkan: X kini mengakhiri aplikasinya, dan WhatsApp, Chrome, YouTube, serta Facebook di ponsel ini sedang dipakai pemiliknya |
| `scripts/ui_test_lxdb.sh` | 19 lulus, 0 gagal |
| `scripts/ui_test_display_awake.sh` | 7 lulus berurutan; 2 pemeriksaan orientasi lulus di putaran lain (40 ms). Pemeriksaan "kunci lepas setelah layar tersembunyi" tidak bisa diukur: pembeku Hiber milik ROM membekukan proses instrumentation LX selama hold tersembunyi, dan APK 2 November macet di titik yang sama |
| Instrumentation | 40 lulus, 0 gagal |

Skrip uji ikut diperbaiki setelah terbukti keliru: pemeriksaan kolom LX lama lulus karena keyboard aplikasi uji masih terbuka, dan pemeriksaan "tidak ada layar tersisa" gagal karena jendela pemilik ponsel memang terbuka.

Restart sungguhan setelah perbaikan, dengan pemilik ponsel membuka polanya: Wi-Fi tersambung 19 detik setelah kunci dibuka, `lxdb` tersambung 3,2 detik kemudian, jendela Chrome terbuka sendiri, dan Proses debug nirkabel dimatikan lagi. Tidak ada crash. Garis waktunya ada di `build/reboot-1103/boot-timeline.txt`.

## lxdb, 2 November 2026

Infinix X6726B, Android 15. `lxdb` menggantikan helper JVM (ADR-008). LX Bridge menjalankannya lewat `adbd` ponsel; tidak ada sakelar yang disentuh pengujian.

| Uji | Hasil |
|---|---|
| Tes host `scripts/test-host.sh` | 211 lulus, 32 di antaranya `lxdb`. ASan dan UBSan: 210 lulus tanpa laporan, 1 dilewati sengaja |
| Lint dan build `scripts/build.sh` | 0 error, 3 warning lama. Lint menangkap `VirtualDisplayConfig.Builder` yang baru publik di API 34, jadi batas bawah Mode B naik ke Android 14 |
| Instrumentation | 40 lulus, 0 gagal |
| `scripts/ui_test_framed_apps.py --cycles 2` | 55 lulus, 0 gagal |
| `scripts/ui_test_lxdb.sh` | 19 lulus, 0 gagal |
| `scripts/ui_test_display_awake.sh` | 10 lulus, 0 gagal. Orientasi terdengar dalam 37 ms |
| Wi-Fi dimatikan lewat `svc wifi disable` | Lulus: `lxdb shell` menjawab, Kalkulator terbuka, 9 × 4 = 36, pid daemon sama. Wi-Fi dinyalakan lagi sesudahnya |
| Perkecil lalu pulihkan | Lulus: layar `ON`, `RESUMED`, `frozen 0`, 56 tetap ada, + 2 = 58 |
| `injectKey` | Lulus: 789 masuk ke Kalkulator, Volume Up ditolak |
| LX dihentikan paksa | Lulus: daemon yang sama menghidupkannya dan tersambung dalam 2 detik |
| APK diganti | Lulus: daemon lama berhenti, LX menjalankan yang baru sendiri |

Belum diuji: restart sungguhan, Android 14 dan 16, x86_64, dan Proses debug USB dimatikan.

## LX Bridge dan aplikasi yang tetap berjalan, 2 November 2026

Infinix X6726B, Android 15. Shizuku sudah dihapus dari LX. Bagian yang menyebut helper memakai baris perintah `ShellHelper.daemonCommand`, dijalankan lewat USB.

| Uji | Hasil |
|---|---|
| Instrumentation | 40 lulus, 0 gagal, pada build tanpa Shizuku |
| `scripts/ui_test_bridge_pairing.sh`, klien `adb pair` asli melawan kode pairing LX di loopback | 2 lulus, 0 gagal: kode benar berhasil, kode salah ditolak dengan "Wrong password" |
| `scripts/ui_test_framed_apps.py --cycles 2` lewat helper | 55 lulus, 0 gagal, pada build final tanpa Shizuku |
| `scripts/ui_test_file_manager.py` | 22 lulus, 0 gagal, dijalankan sebelum Shizuku dihapus, dan bagian itu tidak menyentuh File Manager |
| Helper tersambung ke LX tanpa Shizuku | Lulus: status `NOT_RUNNING -> READY`, Kalkulator terbuka di layar sendiri |
| LX dihentikan paksa | Lulus: helper dengan pid yang sama kembali ke proses LX baru, aplikasinya terbuka lagi. Uji ini sempat menemukan bug binder LX yang hilang setelah GC, sudah diperbaiki |
| Semua jendela ditutup lebih dari 30 detik | Lulus: helper tetap hidup |
| APK LX diganti | Lulus: helper lama berhenti, "LX turned this helper away" |
| Pindah workspace, minimise, LX tertutup Pengaturan | Lulus: Kalkulator tetap `RESUMED`, layarnya `ON` |
| Jendela dimunculkan lagi | Lulus: "7" yang diketik sebelum disembunyikan tetap ada, "8" sesudahnya masuk, jadi gambar dan sentuhan kembali |
| Tengah seretan ubah ukuran | Lulus: gambar diskalakan seragam, tidak melar |
| Selesai ubah ukuran | Lulus: layar 640x755 dan 640x712, seluruh aplikasi terlihat |
| File Manager di bilah bawah | Lulus: ikon ikut bergeser saat bilah digeser |
| Layar dimatikan dan ponsel terkunci | Lulus selama 180 detik: Kalkulator `RESUMED`, cgroup `frozen 0`, waktu CPU naik dari 1699 ke 1838 tick, grup layar ponsel tidur (wakefulness 3), grup layar aplikasi bangun (wakefulness 1) |
| Pembekuan oleh ROM | Dicatat: freezer Transsion (`ro.thunderback.version=51`) membekukan LX dan aplikasi latar lain beberapa detik setelah keluar dari depan, termasuk saat instrumentation aktif. Status `RESUMED` saja tidak membuktikan aplikasi berjalan, jadi uji ini membaca `cgroup.events` |
| `scripts/ui_test_display_awake.sh` | 3 lulus, 0 gagal: layar memegang wake lock sendiri, aplikasinya tidak beku, kuncinya berakhir bersama layarnya |
| Tombol Sambungkan otomatis | Lulus: membuka halaman Aksesibilitas dengan entri LX Bridge; dengan Wi-Fi mati membuka panel Wi-Fi |
| Crash `WeakHashMap` di `GlassEnv.update` | Ditemukan regresi, diperbaiki. Android menghapus pilihan Home setelah launcher pihak ketiga crash, jadi pilihan Home dikembalikan ke LX |

Belum diuji:

- Seluruh langkah `BridgeSetupService` dan restart sungguhan. Pairing dengan `adbd` ponsel, `pm grant`, dan penulisan `adb_wifi_enabled` sudah berjalan sejak itu, lihat bagian `lxdb` di atas.
- Panel `NEEDS_WIFI`, yang butuh LX Bridge tersambung lebih dulu.
- Helper dengan Proses debug USB mati, karena pengujian memakai koneksi USB.
- Layar mati lebih lama dari 3 menit, dan pemakaian baterainya.
- Pemutaran video sungguhan di jendela yang tersembunyi. Status aktivitas diperiksa lewat `dumpsys`; video tidak diputar supaya riwayat akun pemilik ponsel tidak berubah.

## Tampilan File Manager dan jendela aplikasi, 1 November 2026

Semua uji perangkat dijalankan di Infinix HOT 60 5G. Tangkapan layar aplikasi pribadi dipikselkan sebelum dilihat dan dihapus sesudahnya, jadi hanya tata letaknya yang diperiksa.

| Lapisan | Perintah | Hasil |
|---|---|---|
| Build dan lint | `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:lintDebug` | Berhasil; 0 error, 3 warning versi toolchain |
| Core C++ (host) | `scripts/test-host.sh` | 179 lulus. ASan dan UBSan: 178 lulus, 1 dilewati sesuai rancangan, tanpa laporan sanitizer |
| Instrumentation | `adb shell am instrument -w com.lxlauncher.test/com.lxlauncher.test.SelfTestInstrumentation` | 30 lulus, 0 gagal |
| UI File Manager | `scripts/ui_test_file_manager.py --cycles 40` | 22 lulus, 0 gagal, termasuk 40 siklus ubah ukuran |
| Jendela aplikasi | `scripts/ui_test_framed_apps.py --cycles 2` | 55 lulus, 0 gagal: Kalkulator, Chrome, YouTube, WhatsApp, dan Facebook dibuka dan ditutup dua kali, diperkecil, tanpa letterbox, tanpa crash, tanpa ANR, tanpa layar tersisa |

Yang dicek manual di ponsel:

- Keyboard. Di pencarian WhatsApp, keyboard Gboard terbuka di dalam jendela dan WhatsApp menyesuaikan diri di atasnya (`imeLayeringTarget` di layar aplikasi, `mInputShown=true`). Ketikan masuk, dan Back menutup keyboard lebih dulu. Tidak ada pesan yang dikirim.
- Jendela kecil. Facebook terkunci potret (`requestedOrientation=SCREEN_ORIENTATION_PORTRAIT`). Di jendela mengambang yang lebih lebar daripada tingginya, LX memberinya layar 640x711, seluruh aplikasi tampil mengecil di tengah jendela, dan ketukan di tab Teman tepat sasaran.
- Video. YouTube memutar video di jendela. Layar penuh di YouTube tetap tegak, dengan video utuh di tengah jendela.
- Profil kerja. Files (kerja) terbuka di layar LX dengan `U=10`, 682x1097.
- Shizuku berhenti. Kalkulator tetap terbuka di jendela LX dengan pesan dan tombol Buka Shizuku. Setelah server Shizuku dijalankan lagi, Kalkulator terbuka sendiri.
- File Manager pada lebar sempit dan ponsel mendatar; kisi lima kolom, daftar, laci, menu rapat, dan dialog.

Bug yang ditemukan dan diperbaiki:

- Aplikasi tidak berbingkai LX tanpa menyalakan setelan. Bingkai LX kini bawaan.
- Isi aplikasi berjarak 8 dp dari bingkai.
- Di jendela kecil sisi kanan dan bawah aplikasi terpotong, karena buffer tekstur tetap selebar view.
- Aplikasi terkunci potret kena letterbox dan bergeser di jendela yang lebar.
- Kolom ketik di bawah tertutup keyboard, karena aplikasi tidak melihat keyboard di layar lain.
- Aplikasi yang dibuka dari kotak cari mendapat layar pendek lalu dibangun ulang.
- Pintasan Browser melewati bingkai LX.
- Skrip uji membaca teks widget di belakang jendela sebagai bagian jalur.

Review independen atas kode hari ini menemukan 12 cacat dan 4 cacat kecil, tanpa crash di jalur normal. Semua sudah diperbaiki sebelum uji akhir di atas. Yang paling berarti:

- Saat keyboard kolom LX muncul, layar aplikasi masih bisa diubah ukurannya, karena pemeriksaannya memakai ukuran layar yang sudah diperbesar. Kini yang dibandingkan ukuran jendela.
- Layar di jendela yang sangat pendek bisa melewati batas 8192 px, lalu ketukan meleset. Ukurannya kini dibatasi.
- Bentuk layar lama terbawa setelah Coba lagi.
- Pembacaan orientasi kini berjalan di thread sendiri, hanya membaca bagian layar yang bersangkutan, dan melambat untuk aplikasi yang ter-letterbox karena alasan lain.
- Pintasan Galeri bisa membuka aktivitas yang salah di paket yang punya dua ikon.
- Dialog bisa tertutup laci sidebar.
- Tepi jendela yang direbut untuk ubah ukuran kini 5 dp untuk isi yang mengisi bingkai, sebelumnya 8 dp.

Belum diuji: Android 13 dan 14, Shizuku lewat root, dan aplikasi FLAG_SECURE, yang tetap tampil hitam di jendela LX. Setelah ponsel dinyalakan ulang, Shizuku harus dijalankan sekali; tanpa Wi-Fi atau komputer, itu tidak bisa otomatis tanpa root.

## File Manager, arsip, gaya jendela, dan Mode B, 31 Oktober 2026

Semua uji perangkat dijalankan di Infinix HOT 60 5G (Android 15, MediaTek, kartu SD 268 GB). Data LX dicadangkan lewat `run-as` sebelum uji dan dipulihkan sesudahnya. Akses semua file diberikan hanya selama uji, lalu dicabut lagi, baik mode paket maupun mode uid.

| Lapisan | Perintah | Hasil |
|---|---|---|
| Build | `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease` | Berhasil. R8 mempertahankan `EmbedHelper.main`, `EmbedService`, `EmbedBinderProvider`, dan `NativeArchive$Progress` |
| Lint | `./gradlew :app:lintDebug` | 0 error, 3 warning versi toolchain yang sengaja dipatok |
| Core C++ (host) | `scripts/test-host.sh` | 179 lulus. ASan dan UBSan: 178 lulus, 1 dilewati (harness JNI tidak dibangun di build sanitizer), tanpa laporan sanitizer |
| Instrumentation | `adb shell am instrument -w com.lxlauncher.test/com.lxlauncher.test.SelfTestInstrumentation` | 28 lulus, 0 gagal, termasuk arsip lewat file descriptor, putusan path arsip, kontras setiap gaya dan setiap aksen Kustom, dan 13 cek Mode B |
| UI File Manager | `scripts/ui_test_file_manager.py --cycles 40` | 22 lulus, 0 gagal pada build final, termasuk 40 siklus ubah ukuran |

Yang dicek manual di ponsel:

- Tata letak File Manager. Pada 160 dpi, jendela 614 dp memakai menubar dan laci Tempat. Dengan ponsel mendatar (1600 px pada 160 dpi), sidebar menempel di kiri. Density dan rotasi dikembalikan sesudahnya.
- Menu bercabang. Saat ponsel mendatar, Tampilan > Urutkan terbuka di kanan, dan menu latar yang dibuka di tepi kanan membuka Baru di kiri. Saat tegak, submenu menggantikan induknya; baris kembali kembali ke Tampilan, dan Back menutup menu.
- Gaya jendela. LX Modern Gelap dan Kustom (dasar LX Modern, aksen hijau toska, tombol kotak) dipilih dari Pengaturan LX dan langsung terpasang di jendela yang terbuka.
- Jendela. Bilah judul Catatan memindahkan jendela 345 px untuk geseran 347 px. Geseran dari pinggir kiri bilah judul juga memindahkan, lebar tetap 646 px. Pindah ke workspace 3 menyembunyikan ketiga jendela, kembali ke workspace 2 menampilkannya lagi.
- Izin. Setelah akses semua file dicabut, Android menghentikan LX, LX hidup lagi dengan jendelanya, dan File Manager menampilkan "Izinkan akses semua file" dan "Tambah folder…". Selama izin berlaku, halaman Tentang menyebut File Manager boleh membuka seluruh penyimpanan bersama dan kartu SD tampil di sidebar.
- Mode B, dijalankan agen terpisah lewat UI LX. Lihat ADR-006 untuk waktu, layar virtual, dan hasil ketukan.

Bug yang ditemukan dan diperbaiki:

- Dengan gaya Rounded Modern, judul dan teks jendela Catatan samar terlihat di balik daftar berkas (selisih sekitar 5 tingkat kecerahan). Jendela, menu, dan panel kaca tanpa blur kini padat penuh; pikselnya kini sama dengan bagian lain.
- "Tampilkan berkas tersembunyi" terpotong di menu Tampilan. Lebar menu kini mengikuti baris terpanjang, sampai 360 dp.
- Baris kembali di submenu dibacakan "Kembali ke Urutkan" padahal kembali ke Tampilan. Sekarang "Urutkan, kembali ke menu sebelumnya".
- Tombol "Izinkan akses semua file" terpotong menjadi "Izinkan akses …". Tombol di layar kosong kini selebar label terpanjang.
- Halaman Tentang berkata "LX tidak melihat yang lain" padahal akses semua file diberikan.
- Enam teks masih menyebut LX Files.

Review independen atas perubahan di atas (tiga peninjau dengan sudut berbeda, lalu satu penyanggah untuk setiap temuan) mengonfirmasi lima cacat lagi, dan semuanya sudah diperbaiki:

- Dalam tata letak kanan-ke-kiri, setiap level menu bercabang menempel di tepi kanan dan submenu menutupi induknya. Kartu menu kini dipasang dari kiri secara absolut, arah dibaca dari host, dan menubar berpatokan pada tepi kanan tombolnya. Perbaikan ini belum dicoba di perangkat, karena tata letak kanan-ke-kiri harus dipaksakan di ponsel pemilik.
- Bilah pesan dan bilah mode edit masih 0,96 di atas jendela. Kini padat penuh.
- Panel status jendela aplikasi Mode B masih 0,96 di atas sisa gambar aplikasi. Kini padat penuh.
- Slider Kepekatan dock tidak berpengaruh apa pun, karena nilainya tidak pernah dibaca. Slider itu dihapus. Kunci setelannya tetap diterima supaya cadangan lama masih bisa dipulihkan.
- Di Android 8 sampai 10, halaman Tentang menyuruh mencari "akses aplikasi khusus", padahal izinnya adalah izin Penyimpanan biasa. Teksnya kini sesuai versi Android.

Setelah perbaikan itu: lint 0 error dan 3 warning, instrumentation 28 lulus dan 0 gagal pada APK final, dan menu File Manager dicek ulang di ponsel. Di akhir, `layout.lxl` identik byte demi byte dengan cadangan awal, juga setelah LX dibuka lagi lewat Home.

Belum diuji: flashdisk USB, kartu SD dicabut di tengah operasi, penyimpanan penuh, folder pilihan SAF di File Manager, keyboard fisik dan mouse di menu, Android 8 sampai 10, dan Mode B di Android 13 dan 14. Batch UI lama (`ui_test.py`, `ui_test_more.py`, `ui_test_full.py`) tidak dijalankan karena membersihkan data LX dengan `pm clear`.

## Kaca tema LX, menu di ukuran besar, dan jendela aplikasi, 31 Oktober 2026

Semua uji hari ini dijalankan di Infinix HOT 60 5G, sesuai permintaan pemilik ponsel; Waydroid tidak dipakai. Data LX dicadangkan lewat `run-as` sebelum uji. Setiap layout uji dibuat dari salinan setelan pemilik dengan satu atau dua kunci yang diganti, lalu cadangannya dipulihkan. Di akhir, `layout.lxl` identik byte demi byte dengan cadangan.

| Lapisan | Perintah | Hasil |
|---|---|---|
| Build dan lint | `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug` | Berhasil; 0 error, 3 warning versi yang sengaja dipatok |
| Instrumentation, termasuk uji kontras yang diperluas | `adb shell am instrument -w com.lxlauncher.test/com.lxlauncher.test.SelfTestInstrumentation` | 13 lulus, 0 gagal, pada APK final |
| Core C++ | Tidak dijalankan | Core tidak berubah sejak 30 Oktober |

Uji kontras kini mencakup tema LX di setiap palet, setiap aksen, dan kepekatan panel 35%, 74%, dan 100%. Setiap kombinasi diuji pada kaca dengan kepekatan yang dipilihnya di atas abu dari hitam sampai putih, pada menu, pada kaca tanpa blur, dan pada panel datar. Perilaku lama tidak lolos uji itu: di Oceanic, jendela tema LX pada 0,74 di atas putih memberi teks abu 3,16:1, dan pada 35% teks utama 1,88:1.

Yang diukur langsung di ponsel, dari tangkapan layar dan `dumpsys`:

- Kaca tema LX (gaya Ikut tema LX, Oceanic, kepekatan 0,74). Di atas wallpaper putih, jendela, panel atas, bilah bawah, dan bingkai widget terukur pada 0,918, dengan teks abu 5,60:1 dan judul 9,53:1. Di atas #808080, kepekatannya 0,808 (hitungan 0,81) dan teks abu 5,70:1. Di atas #202020, kepekatannya 0,733 (setelan 0,74) dan teks abu 7,70:1.
- Menu Aplikasi pada ukuran antarmuka 130%. Kolom kategori menjadi lajur ikon dan submenu mengisi 200 sampai 698 px tanpa menumpuk nama. Ketukan pada ikon kategori yang terbuka mengembalikan kolom 41 sampai 559 px. Pada 100%, nama kategori muat utuh di samping submenu (submenu mulai di x 345), jadi susunannya sama seperti sebelumnya.
- Jendela aplikasi. Dengan Otomatis, Kalkulator terbuka `freeform` di (16,184)-(704,1392), dengan isi di y 268 sampai 1392, sebelumnya 284 sampai 1142. Dengan Mengambang, Kalkulator terbuka di (32,200)-(688,1208). Mahjong Blast, sebuah game, terbuka `fullscreen` di (0,0)-(720,1600). Setelan putar otomatis ponsel mati, jadi layar tetap tegak.
- Gambar wallpaper yatim. Sebuah berkas uji di folder wallpaper LX terhapus setelah LX dinyalakan ulang.

Bug yang ditemukan dan diperbaiki:

- Pada 130% di layar 360 dp, submenu menutupi sebagian besar kolom kategori, sehingga nama terpotong di tengah kata ("Semu", "Terak"). Ketukan di tengah kategori yang terbuka mengenai baris aplikasi di atasnya dan membuka "Asisten". Kini kolom itu menjadi lajur ikon.
- Pintasan game masih akan terbuka sebagai jendela. Sekarang pintasan game juga meminta layar penuh, tetapi jalur ini belum diuji di perangkat.

Belum diuji: game mendatar dengan putar otomatis menyala; pintasan game; tab miring dalam bahasa kanan-ke-kiri, yang membutuhkan bahasa LX diganti di ponsel yang sedang dipakai pemiliknya; `scripts/ui_test_app_windows.py`, yang menggerakkan layar ponsel sekitar delapan menit; outline di Android 8 sampai 10; dead key dari keyboard fisik.

Sisa di ponsel: dua kartu di Recent, LX sebagai task biasa (#1127) dan halaman Pengaturan "Aplikasi asisten digital" (#1129). Keduanya terbuka karena uji. Perintah untuk menghapusnya ditolak pengaman otomatis, jadi pemilik ponsel perlu menggesernya. Task Mahjong Blast tetap di Recent dengan permintaan izin notifikasinya belum dijawab.

## Gaya jendela, wallpaper bersama, dan menu kaskade, 30 Oktober 2026

Build debug yang sama dipasang di kedua perangkat. Di Infinix, data LX dicadangkan lewat `run-as` sebelum uji. Sesudahnya, JSON layout dibandingkan kunci demi kunci. Satu-satunya beda adalah migrasi yang memang dirancang: wallpaper Ruang 4 diseragamkan ke wallpaper workspace aktif, dan setelan `window.style` bawaan tersimpan. Uji yang menjalankan `pm clear` dan uji jendela aplikasi tidak dijalankan di ponsel itu, karena pemilik ponsel sedang punya task Chrome dan Pengaturan yang terbuka. Di Waydroid data LX dibersihkan sebelum dan sesudah uji.

| Lapisan | Perintah | Waydroid | Infinix HOT 60 5G |
|---|---|---|---|
| Core C++ (host), termasuk wallpaper bersama dan skema `window.style` | `scripts/test-host.sh` | 104 lulus; 104 lulus di AddressSanitizer + UBSan tanpa laporan | |
| Lint Android | `./gradlew :app:lintDebug` | 0 error, 3 warning versi yang sengaja dipatok | |
| Instrumentation, termasuk empat uji baru | `scripts/test-device.sh`, `am instrument` | 13 lulus, 0 gagal | 13 lulus, 0 gagal |
| UI batch 1 sampai 3 | `scripts/test-device.sh` | 30, 18, dan 35 lulus, 0 gagal | Tidak dijalankan (menghapus data LX) |
| Jendela aplikasi, skenario A, B, C | `scripts/ui_test_app_windows.py` | 21 lulus, 0 gagal, 2 batas platform | Tidak dijalankan |
| Gaya jendela di ukuran antarmuka 85% | `scripts/ui_test_window_styles.py` | 13 lulus, 0 gagal | Tidak dijalankan (menghapus data LX) |

Empat uji instrumentation baru:

- Kontras teks setiap gaya jendela dan tema LX di atas wallpaper abu dari hitam sampai putih, termasuk baris yang difokus, ditunjuk mouse, dan terpilih, daun Nuansa Alam, serta menu tema LX pada kepekatan minimumnya. Wallpaper berwarna dan pembulatan blending GPU tidak dimodelkan.
- Autosize judul di setiap gaya dan ukuran antarmuka 85% sampai 130%.
- Outline Cyberpunk yang memakai persegi bulat untuk bounds kosong dan kecil.
- Penyaring ketikan menu Aplikasi untuk dead key, karakter kontrol, huruf, dan emoji.

Yang diperiksa langsung:

- Infinix: menu Aplikasi tidak lagi memenuhi layar. Kolom kategori 208 dp (24 sampai 440 px, sebelumnya 344 dp), Game membuka submenu lima baris sejajar barisnya, dan Produktivitas membuka submenu 31 aplikasi yang bergulir di dalam tinggi menu. Ketukan kedua pada kategori yang terbuka menutup submenunya. Tombol Aplikasi terisi aksen selama menu terbuka. Jendela Kalkulator terbuka di (32,200)-(688,1142), selebar 328 dp; sebelumnya 258 dp.
- Waydroid: lembar kontak sepuluh gaya di ukuran antarmuka 85%, dengan tepi jendela utuh sampai ke bilah judul.

Bug yang ditemukan dan diperbaiki sebelum dirilis. Sebagian besar datang dari dua putaran review kode, dan setiap temuannya dicek ulang ke source sebelum diperbaiki:

- Gaya Ringkas di ukuran antarmuka 85% atau 90% membuat LX crash: batas bawah autosize judul (12 sp) tidak lebih kecil dari batas atasnya.
- Gaya Cyberpunk akan crash di Android 8 dan 9: Android meminta outline jendela sebelum jendela punya ukuran, dan sudut potong pada ukuran nol menghasilkan path yang tidak konveks.
- Dead key di keyboard fisik akan menghentikan menu Aplikasi saat mengetik untuk mencari. Kini aksennya diteruskan ke kolom cari.
- Setelah dialog ditutup dengan Enter dari keyboard, seluruh layar LX tertutup selubung abu terang (sorotan fokus bawaan Android pada root). Piksel sudut kembali dari (80,83,92) ke (5,8,22).
- Tombol Aplikasi kembali tampak tertutup setiap kali bilah dibangun ulang, padahal menunya masih terbuka. Glyph-nya berwarna aksen dan di atas wallpaper sedang hanya mencapai sekitar 2,9:1; kini memakai warna teks.
- Tombol bilah judul bergaya lebih kecil dari target sentuh dan tidak ikut membesar dengan Target besar, termasuk 56x50 dp pada gaya Rounded.
- Isian bilah judul menutupi tepi jendela, termasuk tepi fokus dan bevel Retro.
- Sakelar Blur dan Neon tidak berlaku untuk jendela bergaya sampai jendelanya dibuka ulang.
- Teks sekunder Glassmorphism turun ke 3,8:1 di atas wallpaper biasa. Teks sekunder dan aksen di baris yang difokus atau terpilih turun ke 2,7 sampai 4,5:1 di beberapa gaya dan tema, termasuk keterangan hasil pencarian pertama di tema bawaan (4,06:1).
- Kaca adaptif memilih kepekatan dari rata-rata kecerahan di bawah jendela, sehingga bagian terang wallpaper bisa membuat teks tak terbaca; kini dari bagian paling terang.
- Tab miring Cyberpunk dan Taktis tidak dicerminkan di bahasa kanan-ke-kiri, dan judul panjang melewati potongan diagonalnya.
- Ikon nama workspace dan baterai di panel atas tampil 24 dp, bukan 16 dan 18 dp.
- Baris "Cari di semua tempat" muncul saat menu dipakai untuk memilih aplikasi, dan mengetuknya membatalkan pilihan.
- Layout lama dengan wallpaper per workspace meninggalkan gambar yang tidak dipakai lagi. Pembersihnya juga bisa menghapus gambar yang sedang diimpor; kini gambar itu dilindungi sampai dipasang.
- Lint menangkap pemanggilan `LauncherActivityInfo.getActivityInfo()` yang akan crash di Android 8 sampai 11, dan alokasi objek saat menggambar.

Satu langkah UI gagal sekali lalu lulus pada build yang sama: rename di LX Files. Skrip mengetik 0,8 detik setelah mengetuk Rename, kadang sebelum kolom dialog mendapat fokus, sehingga nama lama tersimpan. Skrip kini menunggu kolom yang fokus. Langkah Details dan Delete sesudahnya juga diperbaiki: sebelumnya keduanya lulus tanpa memeriksa apa pun bila rename gagal. Uji gaya jendela 85% juga pernah menyapu label slider, bukan slidernya, dan uji outline pertama tetap lulus pada kode lama. Keduanya ditemukan review dan sudah diperbaiki sebelum angka di atas diambil. Satu run window test juga gagal menyematkan Galeri ke bilah saat persiapan, sehingga skenario C untuk Galeri ikut gagal. Langkah yang sama lulus saat diulang terpisah dan pada dua run penuh berikutnya. Persiapan itu kini mencoba sekali lagi per kata kunci.

Belum diuji: jalur outline di Android 8 sampai 10 dan dead key dari keyboard fisik sungguhan (keduanya hanya lewat instrumentation di Android 13 dan 15), tab miring dalam bahasa kanan-ke-kiri, menu kaskade di ukuran antarmuka 130% pada ponsel 360 dp, dan pembersihan gambar yatim saat LX dibuka.

Masih terbuka: jendela LX dalam tema LX bawaan (Ikut tema LX) memakai kepekatan panel 0,74 tanpa penyesuaian, sehingga di atas wallpaper foto yang terang teksnya bisa turun di bawah 4,5:1. Ini sudah ada sebelum pekerjaan ini. Kepekatan adaptif seperti pada gaya kaca adalah perbaikan yang diusulkan. Diperbaiki 31 Oktober 2026; lihat bagian di atas.

## Menu Aplikasi dan jendela aplikasi, 29 Oktober 2026

Build debug yang sama dipasang di kedua perangkat. Di Infinix, data LX milik pemilik ponsel dicadangkan lewat `run-as` sebelum setiap uji, dipulihkan sesudahnya, lalu dicocokkan dengan md5 (`60d3d8f8` sebelum dan sesudah). Uji yang menjalankan `pm clear` tidak dijalankan di ponsel itu. Di Waydroid `run-as` ditolak, jadi data LX dibersihkan sebelum dan sesudah uji.

| Lapisan | Perintah | Waydroid | Infinix HOT 60 5G |
|---|---|---|---|
| Core C++ (host), termasuk key laci yang dipensiunkan | `scripts/test-host.sh` | 101 lulus; 101 lulus di AddressSanitizer + UBSan tanpa laporan | |
| Lint Android | `./gradlew :app:lintDebug` | 0 error, 3 warning versi yang sengaja dipatok | |
| Instrumentation | `scripts/test-device.sh` | 9 lulus, 0 gagal | Tidak dijalankan |
| UI batch 1 sampai 3 | `scripts/test-device.sh` | 30, 18, dan 32 lulus, 0 gagal | Tidak dijalankan (menghapus data LX) |
| Jendela aplikasi, skenario A, B, C | `scripts/ui_test_app_windows.py` | 21 lulus, 0 gagal, 2 batas platform | 27 lulus, 0 gagal |

Menu Aplikasi yang diperiksa langsung:

- Infinix (tegak, satu panel): tombol Aplikasi di 20 sampai 104 px, paling kiri. Menu duduk di atas bilah (bawah 1396, bilah mulai 1408). Game 5, Media 7, Produktivitas 31, Sosial 13, dan Lainnya 50 berjumlah 106, sama dengan Semua aplikasi. Kategori membuka daftar dengan baris Kembali ke kategori. Pencarian "kalku" menemukan Kalkulator dan membukanya sebagai jendela freeform. Tekan lama membuka menu aplikasi dan menyembunyikan keyboard. Back menutup menu aplikasi, lalu mengosongkan pencarian, lalu menutup menu.
- Waydroid (lebar, dua kolom): kolom kedua terbuka 4 px di kanan kolom pertama dan naik secukupnya supaya 17 baris muat. Panah bawah, kanan, kiri, dan Enter berpindah antar-kolom; Esc bertahap; Ctrl Shift A membuka menu. Tambah aplikasi memilih Kalkulator untuk desktop tanpa membukanya. Jam yang diseret ke desktop dan Kontak yang diseret ke taskbar tersimpan di sana.
- Layout lama berisi `drawer.sort` dan `drawer.layout` dimuat di Infinix tanpa notifikasi entri dilewati.

Bug yang ditemukan dan diperbaiki dalam pengujian ini:

- Aplikasi yang diseret keluar dari menu hilang di tengah jalan. Lapisan overlay tetap menerima drag setelah menu tertutup, jadi drop tidak pernah sampai ke desktop. Drop itu kini diteruskan ke panel di bawahnya.
- Enter pada kategori membuka kolom kedua tetapi fokus keyboard hilang. Enter kini masuk ke kategori seperti panah kanan.
- Menu tidak punya keadaan memuat, sehingga sempat menulis "Belum ada aplikasi di sini".
- Menu aplikasi yang dibuka dari hasil pencarian tertutup separuh oleh keyboard, dan menu yang menempel ke titik sentuh tertutup setiap kali keyboard muncul atau hilang.

Skrip uji juga diperbaiki setelah terbukti memberi hasil yang salah: digest data kosong dianggap cocok saat `run-as` ditolak, beberapa langkah lolos tanpa jendelanya pernah terbuka, langkah uninstall terlewat tanpa tercatat, tombol tutup diketuk saat jendela lain menutupinya (sempat tercatat sebagai batas platform), dan Kalkulator mewarisi teks dari uji sebelumnya.

Belum diuji: seret ke dock samping di tablet, membuka kategori dengan menunjuk memakai mouse, dan perilaku jendela aplikasi di perangkat dengan freeform mati.

Masih terbuka: selama jendela aplikasi memegang fokus, ketikan di kolom cari menu Aplikasi masuk ke aplikasi itu (Waydroid dan Infinix). Rinciannya di `docs/architecture/ADR-005-app-windows.md`.

## Redesain tampilan, 26 sampai 27 Oktober 2026

Pengujian ulang setelah tema warna, font, ikon Vivid, dan susunan ponsel ditambahkan. Build debug yang sama dipasang di kedua perangkat.

| Lapisan | Perintah | Waydroid 1920x1028 (tablet) | Infinix HOT 60 5G (ponsel tegak) |
|---|---|---|---|
| Core C++ (host), debug dan ASan + UBSan | `scripts/test-host.sh` | 97 lulus, 0 gagal, tanpa laporan sanitizer | |
| Lint Android | `./gradlew :app:lintDebug` | 0 error, 3 warning yang sama seperti 0.1.1 | |
| Instrumentation | `scripts/test-device.sh` | 9 lulus, 0 gagal | 9 lulus, 0 gagal |
| UI batch 1 | `scripts/ui_test.py` | 29 lulus, 0 gagal | 29 lulus, 0 gagal |
| UI batch 2 | `scripts/ui_test_more.py` | 18 lulus, 0 gagal | 18 lulus, 0 gagal |
| UI batch 3 | `scripts/ui_test_full.py` | 30 lulus, 0 gagal | 30 lulus, 0 gagal |
| Crash buffer | `adb logcat -b crash` | bersih | bersih |

Run Waydroid memakai build sebelum penampil lisensi dan perbaikan bilah ponsel ditambahkan. Kedua perubahan itu hanya aktif di susunan ponsel atau di halaman Tentang, dan run ponsel memakai build terakhir. Di ponsel, batch 3 lulus penuh setelah pengecekan impor di skrip dibuat bisa membaca nomor workspace di panel atas; aplikasinya tidak diubah di antara dua run itu.

Pemeriksaan dengan mata, lewat tangkapan layar di Waydroid berukuran 720x1600 @320 dan di ponsel:

- Kesepuluh tema tampil di deretan pratinjau. Plasma Night, Arctic Glass, Paper Light, dan Solarized Dusk dipilih dan dilihat di Pengaturan LX serta layar utama. Pilihan tersimpan setelah aplikasi dipasang ulang.
- Paper Light memakai teks gelap di panel terang. Kontras tinggi dicoba di Paper Light dan Plasma Night: panel buram penuh dan garis tepi memakai warna teks.
- Lato dan JetBrains Mono dipilih dan langsung dipakai di jendela, widget, dan panel. Pada skala font 1,3, contoh teks font monospace turun ke baris kedua, tidak terpotong.
- Gaya ikon Garis dan Vivid dicoba di taskbar dan LX Files.
- Pemasangan bersih (`pm clear`) memakai Plasma Night, Roboto, wallpaper Ikut tema, dan dock di bawah.
- Di 1920x1028 dock berdiri di kiri dengan label, dan taskbar memuat nomor workspace serta kotak pencarian.

Ditemukan dan diperbaiki selama pengujian ini:

- Setelah tema, font, atau kontras diganti, jendela yang terbuka hilang dan ponsel kembali ke susunan tablet. Tampilan baru dipasang sebelum punya ukuran, dan tidak dipasang ulang setelah ukurannya diketahui. LX kini memasang ulang panel setelah layout pertama, termasuk area potong jendela.
- Dengan kontras tinggi, isi widget di belakang jendela masih terlihat samar, karena blur mati dan tint panel hanya 97%. Panel kini buram penuh dengan kontras tinggi, dan minimal 96% di jendela tanpa kaca.
- Deretan tema dan tab Pengaturan LX kembali ke awal setelah pilihan diganti, sehingga pilihan yang aktif tidak terlihat. Keduanya kini menggulir ke pilihan aktif.
- Di bilah ponsel, entri dock keenam terpotong dan tombol jendela yang diminimalkan berada di luar layar. Padding bilah dirapatkan supaya enam entri dock muat di layar 360 dp, tepi yang terpotong memudar sebagai tanda bisa digulir, dan tombol jendela kini duduk tetap di samping tombol laci.

Skrip UI disesuaikan dengan susunan ponsel: tombol dock dicari lewat nama aksesibilitasnya, bilah digeser kalau entri ada di luar layar, dan workspace aktif dibaca dari panel atas maupun tombolnya.

Ukuran APK release (dua ABI, belum ditandatangani) kini 4,28 MB, dari 1,9 MB. Hampir seluruh tambahannya dari 20 berkas font.

## Master prompt, 28 Oktober 2026

Audit, perbaikan, dan uji ulang menurut brief di `readme.md`. Ponsel Infinix tidak tersambung selama fase ini, jadi semua uji perangkat berjalan di Waydroid.

| Lapisan | Perintah | Hasil |
|---|---|---|
| Core C++ (host), debug dan ASan + UBSan | `scripts/test-host.sh` | 100 lulus, 0 gagal, tanpa laporan sanitizer |
| Lint Android | `./gradlew :app:lintDebug` | 0 error, 3 warning versi yang sama |
| Isi APK | `unzip -Z1` di `scripts/test-device.sh` | Tidak ada berkas `referensi` |
| Instrumentation dan tiga batch UI | `scripts/test-device.sh` di Waydroid 1920x1028 | 9, 29, 18, dan 32 lulus, 0 gagal, pada build terakhir |
| Crash buffer dan log | `adb logcat` | Tidak ada crash, ANR, atau error dari kode LX |

Pemeriksaan langsung di Waydroid:

| Uji | Diharapkan | Hasil |
|---|---|---|
| Aplikasi pihak ketiga dalam jendela | Android membuka jendela freeform | Clock dan Calculator terbuka bersamaan, `mode=freeform`, bergeser 28 dp |
| Matriks kemampuan | Status sesuai perangkat | `app_windows=SUPPORTED`, `embedding=UNSUPPORTED`, `cpu_load=UNSUPPORTED`, 15 baris di log `LxCaps` |
| Rotasi dengan pencarian terbuka | Pencarian dan teks bertahan | "lxrotate" bertahan saat 1920x1028 ke 1028x1920 dan kembali |
| Jendela layar penuh | Di dalam bilah sistem | `[0,32][1920,965]`, sebelumnya seluruh layar |
| Pulihkan dari layar penuh | Kembali ke jendela mengambang | `[255,138][1245,700]` |
| Keyboard di jendela Catatan | Jendela naik di atas keyboard | `[255,138][1245,700]` menjadi `[255,114][1245,397]` |
| Layar penuh (immersive) | Bilah tersembunyi, muncul saat diusap | Sesuai; perilaku `SHOW_TRANSIENT_BARS_BY_SWIPE` |

Performa, build release yang sama-sama ditandatangani debug key SDK, diukur bergantian di Waydroid dengan `scripts/bench-device.sh` (cold start lima kali per putaran, PSS setelah 8 detik, `dumpsys gfxinfo` selama laci dibuka dan digulir, workspace diganti lima kali, dan Pengaturan dibuka dan ditutup):

| Build | Cold start (ms) | PSS | Frame, jank, p50/p90/p99 |
|---|---|---|---|
| Sebelum, putaran 1 | 152, 228, 153, 141, 163 | 81,6 MB | 216, 1,85%, 6/24/53 ms |
| Sesudah, putaran 1 | 151, 152, 153, 141, 148 | 81,9 MB | 212, 1,42%, 6/10/24 ms |
| Sebelum, putaran 2 | 177, 139, 146, 140, 155 | 81,5 MB | 214, 0,93%, 5/10/21 ms |
| Sesudah, putaran 2 | 144, 151, 152, 151, 150 | 76,3 MB | 215, 0,47%, 5/10/19 ms |

Tidak ada penurunan yang terukur. Jumlah frame per putaran kecil, jadi selisih jank belum cukup untuk disebut perbaikan.

Ditemukan dan diperbaiki dalam fase ini: lihat `docs/reports/engineering-report-2026-10-28.md`, bagian B.

Belum diuji pada fase ini: semua perubahan di ponsel Infinix; salin folder, pindah antar-folder induk, tombol Batal, dan kartu SD di LX Files; layar penuh di Android di bawah 11; uji stres jangka panjang.

## Empat perbaikan dari pengguna, 27 Oktober 2026

Tambah aplikasi, ukuran panel, widget yang bisa dipindah, dan aplikasi dalam jendela. Diuji di Infinix HOT 60 5G dengan build debug terakhir.

| Lapisan | Hasil |
|---|---|
| Core C++ (host), debug dan ASan + UBSan | 98 lulus, termasuk test baru untuk ikon yang tertutup widget |
| Lint Android | 0 error, 3 warning versi yang sama |
| Instrumentation dan tiga batch UI (`scripts/test-device.sh`) | 9, 29, 18, dan 30 lulus, 0 gagal |
| Crash buffer | bersih |

Diperiksa langsung di ponsel:

- Tekan lama area kosong, Tambah aplikasi, lalu ketuk Kalkulator: pintasan muncul di sel yang ditekan, dan LX tetap di depan.
- Panel atas 40 dp dan bilah bawah 48 dp. Ketujuh entri dock bawaan muat.
- Tekan lama widget Catatan cepat lalu geser 250 px: widget ikut bergeser 249 px dan posisinya tetap ada setelah LX dihentikan paksa. Tekan lama lalu lepas membuka menu widget satu kali.
- Widget yang digeser menutupi pintasan Kalkulator. Setelah perbaikan, pintasan itu pindah ke sel kosong di atas widget.
- Pengaturan LX > Jendela menampilkan pilihan jendela dalam keadaan menyala, penjelasan, dan tombol yang membuka Opsi pengembang. Android di ponsel ini melaporkan freeform tidak didukung, jadi Kalkulator tetap terbuka layar penuh.

Belum diuji: aplikasi yang benar-benar terbuka dalam jendela. Itu butuh "Enable freeform windows" dinyalakan di Opsi pengembang dan ponsel di-restart, dan pengaturan sistem ponsel itu tidak diubah selama pengujian.

## Test core C++ per suite

| Suite | Jumlah | Isi |
|---|---|---|
| Json | 12 | skalar, nesting, escape Unicode dan surrogate, input rusak, UTF-8 tidak valid, batas kedalaman, ukuran, string, dan elemen |
| WindowManagerTest, WindowManagerHint | 21 | ID unik, fokus, batas jumlah jendela, resize tidak pernah negatif, jendela tidak bisa hilang dari layar, tombol jendela tetap di layar setelah diseret di layar ponsel, NaN ditolak, maximise dan fullscreen, snap per orientasi, minimise dan restore, rotasi bolak-balik, tile tanpa tumpang tindih, hit test, target snap, JSON |
| DesktopTest | 24 | wallpaper `local:` ditolak kalau berisi `..` atau kosong, pintasan baru menghindari sel di bawah widget, widget baru mencari tempat kosong, workspace (batas 1 sampai 12, hapus dengan pindah atau hapus isi), grid, folder, uninstall menghapus semua referensi, geometri widget, geometri terpisah per orientasi, batas config, pin list, JSON yang sebagian rusak |
| Settings | 5 | skema, normalisasi, preset, JSON |
| Crc32, Envelope, Store | 9 | vektor CRC32, deteksi kerusakan, fallback cadangan, crash di antara dua rename, berkas terlalu besar |
| Search, Render | 8 | urutan relevansi, render deterministik dan opak, blur |
| Engine | 9 | default, default potret menyisakan dua baris untuk ikon, ekspor dan impor, penolakan impor, widget Android dibuang saat impor, pemulihan korupsi, hapus workspace bersama jendelanya |
| Fuzz | 4 | 20.000 input acak ke parser JSON, 3.000 tata letak termutasi, 5.000 mutasi amplop, 20.000 operasi jendela acak |
| Regression | 3 | copy-on-write JSON, aplikasi terakhir keluar dari folder, impor tidak mereset viewport |

## Instrumentation

Semua lulus di kedua perangkat, termasuk library native arm64 di ponsel: engine memuat default lewat JNI; string di luar BMP (emoji) utuh melewati JNI; simpan, rusak, lalu pulihkan dari cadangan di penyimpanan Android; impor menolak dokumen bersarang 5.000 level, versi skema masa depan, dan dokumen 2 MB; settings di luar skema ditolak; LauncherApps mengembalikan activity; ranking pencarian lewat JNI; render wallpaper dan blur ke Bitmap Android, dengan RGB_565 ditolak; AppWidgetHost mengalokasikan dan melepas ID.

## UI end-to-end

Batch 1 memeriksa langkah-langkah berikut:

- LX dipilih sebagai Home, lalu tombol Home membawa ke LX.
- Jendela Welcome tampil dan bisa ditutup.
- Laci aplikasi terbuka dan menampilkan aplikasi berlabel.
- Pencarian "ulator" menemukan kalkulator (Calculator di Waydroid, Kalkulator di ponsel) dan membukanya, lalu Home kembali ke LX.
- Kalkulator disematkan ke taskbar lewat menu tekan lama.
- Music ditambahkan ke desktop.
- Workspace 2 dipilih dari taskbar.
- LX Settings dibuka dari dock dan aksen diganti ke magenta.
- Jendela dimaksimalkan, diminimalkan, dipulihkan dari taskbar, lalu digeser lewat bilah judul. Di ponsel, jendela yang terbuka maksimal dipulihkan dulu, dan minimise lewat menu bilah judul karena tombolnya disembunyikan di jendela sempit.
- Widget kalender ditambahkan.
- Ctrl+K membuka pencarian.
- Layar diubah ke potret lalu kembali mendatar.
- Proses dimatikan paksa, lalu pin, aksen, dan widget tetap ada.
- Crash buffer bersih, dan Home dikembalikan ke Launcher3.

Batch 2 memeriksa langkah-langkah berikut:

- Back di desktop kosong tidak menutup LX.
- Folder dibuat dengan menyeret pintasan Jam ke pintasan Kalkulator, dibuka, lalu ditutup dengan Back.
- Mode atur widget: widget digeser, ukurannya diubah lewat sudut, lalu Selesai.
- Widget Android dari aplikasi jam di-host lewat dialog izin resmi Android.
- Workspace ditambah, diganti nama lewat Enter di keyboard fisik, lalu dihapus dengan isinya dipindah.
- LX Files menjelaskan izin folder, membuka pemilih folder Android, dan kembali setelah dibatalkan.
- Crash buffer bersih, dan Home dikembalikan.

Batch 3 memeriksa langkah-langkah berikut:

- LX Files diberi akses ke folder Documents/LXTest, lalu membuat folder, menyalin dan menempel berkas, mengganti nama, menampilkan detail, menghapus dengan konfirmasi, dan mencari. Setiap hasil dicek langsung di penyimpanan perangkat.
- Wallpaper bawaan Deep blue dipasang, lalu gambar dipilih lewat pemilih foto Android dan tampil sebagai wallpaper. Test hanya lulus kalau log mencatat gambar uji 941x1672, jadi foto lain di perangkat tidak pernah dipakai sebagai bukti. Pemilih foto Android 15 meminta konfirmasi Selesai; Android 13 tidak.
- Preset Minimal dan Cyberpunk, mode Performance, kontras tinggi, dan target sentuh besar diterapkan tanpa crash.
- Tata letak diekspor lewat pemilih dokumen Android. Workspace 1 diganti nama, lalu impor mengembalikan namanya.
- System Monitor terbuka dengan angka asli. LX Files ditempel ke kiri, jendela di-tile, lalu satu jendela dipindah ke workspace 2.
- Pencarian "dock" membuka halaman Dock di LX Settings. Ctrl 2 berpindah ke workspace 2, dan Alt F4 menutup jendela yang fokus.
- ZArchiver disembunyikan dari laci dan muncul di tab Tersembunyi. Dialog uninstall Android terbuka lalu dibatalkan, dan aplikasinya tetap terpasang.
- Catatan cepat bertahan setelah proses dimatikan paksa, dan widget jam berganti ke format 12 jam.
- Layar ponsel 720x1600 menampilkan launcher dan laci aplikasi.
- Crash buffer bersih, dan Home dikembalikan ke launcher sebelumnya (Launcher3 di Waydroid, XOS Launcher di ponsel).

`scripts/test-device.sh` menyiapkan folder dan gambar uji di penyimpanan perangkat sebelum batch 3. Screenshot setiap langkah ada di `build/ui-test/`, `build/ui-test-more/`, dan `build/ui-test-full/` setelah test dijalankan.

## Bug yang ditemukan dan diperbaiki saat pengujian

- Gambar pilihan pengguna tidak pernah tampil sebagai wallpaper. Decode ukuran gambar selalu dianggap gagal, sehingga LX kembali ke wallpaper bawaan. Diperbaiki, dan gambar kini disalin ke penyimpanan privat LX.
- Menyeret bilah judul pelan-pelan membuka menu jendela di akhir seretan. Bilah judul bergerak bersama jari, jadi Android tidak pernah membatalkan long press. LX kini membatalkannya begitu jari bergeser melewati touch slop.
- Esc di keyboard fisik tidak menutup dialog, karena dialog tidak mengambil fokus dari jendela di belakangnya. Esc kini ditangani di tingkat activity.
- Bilah mode atur widget mengubah visibilitasnya di tengah layout, sehingga Android menjalankan layout dua kali. Visibilitas kini diatur saat mode berganti.
- Ukuran jam memakai `scaledDensity`, yang tidak akurat dengan skala font nonlinear di Android 14 ke atas. Ukuran kini dihitung lewat `TypedValue`.

Ditemukan di ponsel:

- Jendela yang diseret ke kanan keluar dari layar 360 dp bersama tombol maksimalkan dan tutupnya. Core kini menjaga seluruh lebar jendela mengambang tetap di layar, dan menu bilah judul mendapat Maksimalkan atau Pulihkan.
- Jari yang diam pun bergetar sedikit. Getaran itu langsung memindahkan jendela yang maksimal, sehingga jendela lepas, bilah judul bergeser dari bawah jari, dan menu tekan lama batal. Jendela kini baru bergerak setelah jari melewati touch slop.
- Satu tekan lama pada widget membuka tiga menu bertumpuk. Pengamat tekan lama menerima ACTION_DOWN dua kali (intercept dan onTouchEvent), ditambah long click bawaan View. Pengamat kini menjadwalkan sekali, dan long click bawaan hanya melayani aksi aksesibilitas.
- Empat widget bawaan menutup 1% sampai 91% tinggi kanvas potret, sehingga pintasan baru jatuh di atas widget. Tata letak potret kini memakai tiga widget di dua pertiga atas, pintasan baru melewati sel yang lebih dari seperempatnya tertutup widget, dan widget baru mencari tempat kosong.
- Tanggal di widget jam dan keterangan di widget status terpotong pada kanvas pendek. Jam kini menyisakan baris tanggal, dan status menyembunyikan keterangan kalau tingginya tidak cukup.
- Daftar widget Android dibangun di thread utama dengan `loadLabel` di setiap perbandingan saat diurutkan. Kini 157 provider dimuat sekali dalam 163 ms di thread latar, lalu disimpan sampai ada aplikasi yang berubah.
- Judul jendela dan judul bagian LX Settings terpotong di jendela sempit. Judul jendela kini mengecil dulu, dan judul bagian boleh dua baris.
- Teks "sudah ada di desktop" terbaca seperti "sudah ada sebelumnya". Terjemahannya kini "kini ada di desktop".

## Log

Log diambil dengan `adb logcat` selama seluruh `scripts/test-device.sh` di kedua perangkat, lalu disaring ke proses LX (enam proses per run, karena test mematikan dan menghidupkan LX). Debug build menyalakan StrictMode untuk objek closeable yang bocor. Di kedua perangkat tidak ada crash maupun ANR. Yang tersisa:

- `LxDocs: document open failed (NullPointerException)` di Waydroid. MediaProvider Waydroid gagal membuka berkas tunggal dari pemilih dokumen; LX lalu membukanya lewat MediaStore dan impor berhasil. Di ponsel peringatan ini tidak muncul.
- StrictMode `LeakedClosableViolation` dengan callsite `View.startDragAndDrop` di Waydroid. `SurfaceControl` itu dibuat dan dipegang framework Android 13.
- `Parcel: Expecting binder but got null` dari thread binder platform saat proses baru mulai.
- Di ponsel, `ApkAssets: Deleting an ApkAssets object` untuk aplikasi lain. Baris ini dicetak saat GC membuang sumber daya aplikasi lain yang dibaca untuk label dan ikon, terutama ketika proses LX mulai lagi di setiap batch.
- Di ponsel, `No package ID 7f found` dan `Invalid resource ID 0x0` hanya pada detik pemasangan APK, dengan baris yang sama di system_server. Itu pemindaian sumber daya oleh ROM.
- Peringatan driver grafis dan cgroup (EGL, OpenGLRenderer, libprocessgroup), yang tidak berasal dari kode aplikasi.

## Performa

Di ponsel, diukur pada build release yang ditandatangani debug key SDK, karena build debug tidak mewakili kecepatan sebenarnya.

| Ukuran | LX-LAUNCHER | XOS Launcher bawaan (pembanding) | Cara ukur |
|---|---|---|---|
| Cold start (proses dimatikan dulu) | 327, 316, 336, 302, 303 ms | 489, 361, 319 ms | `am start -W` |
| Memori setelah stabil (PSS total) | 169 MB | 164 MB | `dumpsys meminfo` |
| Laci, scroll laci, dan ganti workspace | 1.930 frame, 0,67% jank, p50 7 ms, p90 11 ms, p99 15 ms | | `dumpsys gfxinfo` |
| Ukuran APK release (dua ABI) | 1,9 MB | | |

Build debug di ponsel: cold start sekitar 830 ms dan 1,98% jank, sebagian besar karena interpreter dan pemeriksaan debug.

Uji ketahanan memori di ponsel (build debug, GC dipaksa dengan `am dumpheap -g` sebelum setiap ukur, satu putaran = 30 kali buka-tutup Settings, Files, dan Monitor, buka laci, dan ganti workspace): native heap 24, 39, 43, lalu 45 MB; Java heap tetap sekitar 12 MB; jumlah View tetap 116. Kenaikan per putaran mengecil (15, 3,7, lalu 1,9 MB), cocok dengan cache ikon yang terisi lalu jenuh, bukan kebocoran yang terus tumbuh.

Waydroid (0.1.0, CPU host Ryzen 5 7520U): cold start 149 sampai 166 ms, PSS 66,7 MB, drag jendela 0,22% jank. Benchmark core di host (median, build release): ekspor tata letak 55 µs, snapshot 57 µs, impor 132 µs, satu langkah drag jendela 0,05 µs, ranking 400 label 31 µs, render wallpaper 720x1600 10 ms, blur 180x400 6,7 ms.

## Belum diuji

- Membuka aplikasi work profile. Aplikasi kerja sudah tampil di laci ponsel dengan label "(kerja)", tetapi belum dibuka dari LX.
- Rotasi fisik dengan sensor. Rotasi diuji dengan mengubah ukuran layar.
- Ponsel dengan layar lebih pendek dari 800 dp atau Android 8 sampai 12.
- TalkBack secara langsung. Label aksesibilitas diperiksa lewat `uiautomator`, bukan dengan pembaca layar.
- Kesepuluh tema dengan kontras tinggi satu per satu. Kontras tinggi dilihat di Paper Light dan Plasma Night; untuk tema lain hanya rasio kontras warna mentahnya yang dihitung (`DESIGN.md`).
- Kesepuluh font di ponsel. Lato dan JetBrains Mono dicoba di Waydroid, delapan lainnya hanya dilihat di daftar contoh.
- Performa dan memori setelah redesain belum diukur ulang.
- Ikon bertema (monochrome) Android 13. Layernya ada di APK, tetapi tampilannya belum dilihat dengan ikon bertema menyala.
- Wallpaper gambar lewat pemilih dokumen di Android 12 ke bawah, karena Waydroid menjalankan Android 13 yang memakai pemilih foto.

## Tedit (11 November 2026)

Di Waydroid Android 13, layar 720×1600 pada 320 dpi, keyboard AOSP. Infinix tidak dipakai pada sesi ini.

| Lapisan | Perintah | Hasil |
|---|---|---|
| Uji Tedit | `adb shell am instrument -w -e only tedit com.lxlauncher.test/.SelfTestInstrumentation` | 25 lulus, 0 gagal |
| Suite bawaan | `adb shell am instrument -w com.lxlauncher.test/.SelfTestInstrumentation` | 45 lulus, 4 gagal; keempatnya butuh Android 14 (LX Bridge) |
| File Explorer | `... -e only fm ...` | 4 lulus, 0 gagal |
| Pemeliharaan | `... -e only maintenance ...` | 7 lulus, 0 gagal |
| Lint | `./gradlew :app:lintDebug` | 0 error |

Pemeriksaan manual dengan tangkapan layar dan log `LxTedit` tercatat di `docs/architecture/tedit.md`, bagian Hasil uji.

## Tedit di Infinix (12 November 2026)

Infinix HOT 60 5G, Android 15, 720×1600 pada 320 dpi, LX Bridge aktif. Logcat LX direkam sepanjang sesi dan dibaca setelah setiap putaran.

| Lapisan | Perintah | Hasil |
|---|---|---|
| Uji Tedit | `adb shell am instrument -w -e only tedit com.lxlauncher.test/.SelfTestInstrumentation` | 24 lulus, 0 gagal |
| Uji beban Tedit | `... -e only teditload ...` | 20 lulus, 0 gagal |
| File Explorer | `... -e only fm ...` | 8 lulus, 0 gagal |
| Suite bawaan | `adb shell am instrument -w com.lxlauncher.test/.SelfTestInstrumentation` | 49 lulus, 0 gagal |
| Lint | `./gradlew :app:lintDebug` | 0 error |

Uji beban bisa dijalankan per bagian, misalnya `-e only teditload:files,typing`. Bagian yang tersedia: fuzz, files, typing, tabs, search, terminal, commands, cycles, lalu profile, show, dan themes untuk simpleperf dan tangkapan layar. Ponsel ini membekukan proses LX di latar, jadi setiap putaran perlu tombol HOME setelah dimulai.

Selama pengujian, uji menemukan dua crash, satu kegagalan simpan, dan dua keadaan yang membuat layar macet: berkas satu baris yang sangat panjang dan keluaran terminal yang deras. Semuanya sudah diperbaiki, dan rinciannya beserta angka sebelum dan sesudah ada di `docs/architecture/tedit.md`.

## Debian, ekstensi, dan server bahasa di Tedit (12 November 2026)

Di Waydroid Android 13 (x86_64), 720×1600 pada 320 dpi. Infinix tidak dipakai. Logcat LX direkam sepanjang sesi dan dibaca setelah setiap putaran.

| Lapisan | Perintah | Hasil |
|---|---|---|
| Debian lewat VHDP | `adb shell am instrument -w -e only linux:keep com.lxlauncher.test/.SelfTestInstrumentation` | 10 lulus, 0 gagal |
| Ekstensi dan Open VSX | `... -e only ext ...` | 9 lulus, 0 gagal |
| Mesin grammar TextMate | `... -e only textmate ...` | 23 lulus, 0 gagal |
| Warna grammar dibanding vscode-textmate | `... -e only tmparity ...` | 0 dari 2.020.813 karakter berbeda |
| Klien server bahasa | `... -e only lsp ...` | 11 lulus, 0 gagal |
| pylsp sungguhan di Debian | `... -e only teditload:lspreal ...` | 4 lulus, 0 gagal |
| Uji Tedit | `... -e only tedit ...` | 24 lulus, 0 gagal |
| Uji beban Tedit | `... -e only teditload ...` | 20 lulus, 0 gagal |
| File Explorer | `... -e only fm ...` | 4 lulus, 0 gagal |
| Suite bawaan | `adb shell am instrument -w com.lxlauncher.test/.SelfTestInstrumentation` | 46 lulus, 3 gagal: dua butuh Android 14 (LX Bridge), satu daftar layanan aksesibilitas yang kosong di Waydroid, sama seperti sebelumnya |
| Lint | `./gradlew :app:lintDebug` | 0 error |

Angka yang terukur:

- Image x86_64 diunduh dari rilis GitHub proot-distro (36,7 MB) dan terpasang dalam 20,9 detik: unduh sekitar 16 detik, bongkar 3,6 detik. Perintah pertama di Debian selesai dalam 202 ms, Ctrl+C dijawab dalam 101 ms, dan `apt-get update` ditambah `python3-minimal` selesai dalam 12,4 detik.
- Pencarian Open VSX 577 ms. pylsp memberi diagnosa pertama 1,1 detik setelah berkas dibuka.
- Grammar C++ dari Open VSX (better-cpp-syntax 1.27.1) di thread latar: layar pertama meminta 7,3 ms di thread utama dan berwarna benar dalam 337 ms; baris yang diedit 1 ms; lompat ke baris 2.900 berwarna dalam 1,2 detik.
- Mengetik 600 tombol di berkas 30.000 baris: thread utama 1,6 ms per tombol. Sebelum popup saran memakai ulang barisnya, angkanya 10,2 ms.

### Pembanding vscode-textmate

`-e only tmparity` membaca folder yang disiapkan `scripts/tmparity/prepare.sh` di komputer yang memasang VS Code, lalu dikirim ke `/sdcard/Download/tmparity`. Selama uji berjalan LX perlu akses semua berkas; perintah untuk memberi dan mencabutnya ada di kepala skrip itu. Isinya grammar bawaan VS Code beserta `injectTo`-nya, berkas uji, dan token setiap baris menurut vscode-textmate 9.3.2 dengan grammar yang sama. Grammar dan vscode-textmate diambil dari instalasi VS Code setempat, tidak disimpan di repositori. Putaran ini memakai 121 berkas nyata dari 30 bahasa. Uji ini membandingkan setiap karakter selain spasi. Baris di atas 20.000 karakter dilewati karena kedua editor juga tidak mewarnainya. `-e only tmparity:<indeks>` mencetak baris pertama yang berbeda, dan `-e only tmparity:compile` mendaftar pola yang ditolak mesin regex perangkat.

Pembanding ini menemukan beberapa hal, yang semuanya sudah diperbaiki:

- `patterns` di dalam capture, nama scope dengan `$1`, capture di atas nomor 99, `injections`, `\A` yang cocok di setiap baris, dan rule yang tidak bergerak. Di awal, beda dengan VS Code 2,8% di JVM desktop.
- Lima perilaku ICU yang tidak dimiliki Java: `^` setelah newline terakhir, `\G` di dalam lookaround, kuantifier pada lookaround, mode `(?x)`, dan `\p` tanpa kurung kurawal. Di Waydroid beda awalnya 4,9%.

Hasil akhirnya 0 karakter berbeda, baik di JVM desktop maupun di ICU Waydroid. Grammar C++ dari Open VSX juga diuji dengan cara yang sama: 3 berkas, 40.384 karakter, 0 berbeda.

