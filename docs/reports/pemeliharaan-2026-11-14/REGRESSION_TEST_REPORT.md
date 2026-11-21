# Uji regresi, 14 November 2026

Perangkat: Infinix HOT 60 5G (X6726B), Android 15, id-ID, Gboard. Semua run instrumentasi lewat `build/maint-1113/run-instr.py`: data LX dicadangkan, uji berjalan, lalu data dikembalikan. Sejak putaran ini pemulihan diperiksa per berkas dan per folder, dan riwayat bash Debian milik pemilik ikut dikembalikan. Log LX direkam dengan `adb logcat -v threadtime` selama setiap run, lalu diperiksa untuk `FATAL EXCEPTION` dan `ANR in com.lxlauncher`. Hasil mentah ada di folder scratchpad sesi (`r2/` sampai `r8/`, `instr-*.txt`, `logcat-*.txt`).

## Build final (J), dengan preferensi pemilik aktif

| Grup | Isi | Hasil | Crash/ANR |
|---|---|---|---|
| Suite lengkap (tanpa `-e only`) | Inti, LX Bridge, jendela aplikasi, File Explorer, Tedit dasar | 51/51 PASS | 0/0 |
| `responsive` | 8 alat × 4 ukuran jendela, Tedit dengan panel Cari terbuka | 33/33 PASS | 0/0 |
| `windows` | Waktu buka, resize 4 alat, tepi jendela aplikasi | 18/18 PASS (3 run) | 0/0 |
| `tedit` | Buffer, undo, encoding, cari, warna, git, JSON/XML, simpan, sunting lewat jendela | 24/24 PASS | 0/0 |
| `tedit:lang` (baru) | LX sebagai Home, perubahan sampai ke Tedit lain, 4 pilihan bahasa dengan tangkapan layar, 50 pergantian, heap | 10/10 PASS (2 run) | 0/0 |
| `tedit:ime` (baru) | Terminal di atas keyboard: layar penuh, maximized, mengambang; 50 siklus keyboard | 7/7 PASS | 0/0 |

Setelah setiap run, `shared_prefs` berisi kelima preferensi pemilik di tempatnya (dicatat di `r9/prefs.txt`). Tangkapan layar Tedit untuk keempat pilihan bahasa (`r9/shots/`) dilihat satu per satu. Teks Mandarin tampil dengan glyph lengkap, dan pilihan Indonesia serta Bahasa ponsel tampil dalam bahasa Indonesia.

Build I (sama dengan J kecuali listener bersama) juga lulus seluruh tabel di atas: 51/51, 33/33, 18/18 dua run, 24/24, 9/9, 7/7.

## Grup yang tidak diulang di build final

Build J berbeda dari build C di `SearchPanel` dan `TeditPrefs`. Hasil grup yang tidak menyentuh keduanya tetap berlaku:

| Grup | Build | Hasil |
|---|---|---|
| `dxterm` | C | 33/33 PASS |
| `ext` | B dan C | 10/10 PASS |
| `maintenance` | C | 7/7 PASS |
| `fm` | C | 8/8 PASS |
| Uji host | source final | 227 dan 226 PASS |
| Lint | source final | 0 error, 7 warning (sama dengan sebelum putaran) |

## Pengulangan kritis (minimal 50 siklus)

| Alur | Siklus | Hasil |
|---|---|---|
| Ganti bahasa Tedit dengan shell berjalan | 50 per run, 6 run sesudah perbaikan | Sesi bertahan dan menjawab, heap tidak naik |
| Keyboard turun dan naik di Tedit layar penuh | 50 per run, 7 run | Panel terminal di atas keyboard setiap kali, shell menjawab |
| Sesi terminal mulai, menjawab, ditutup | 50 | Tanpa kebocoran descriptor atau thread |
| Run beruntun di terminal | 50 | Setiap run dengan keluaran dan status sendiri |
| Cadangkan dan pulihkan data LX | 3 berturut-turut, lalu sekali di setiap run sesudahnya | Kelima preferensi dan enam entri `files` utuh, tanpa folder bersarang |

## Bukti untuk setiap perbaikan

| Perbaikan | Sebelum | Sesudah |
|---|---|---|
| RCA-1 jendela layar penuh dan keyboard | `FAIL ... keyboard covers 518 px of the terminal panel` | Bawah panel 902 px, keyboard 952 px; tangkapan layar `ime/fullscreen.png` |
| RCA-2 memori rebuild | +8.940 dan +9.284 KiB per 40 pergantian | -28 KiB di build I; +844 dan -356 KiB di build J dengan dua jendela Tedit |
| RCA-2 panel tidak dibuat di rail | 17 `ExtensionsPanel` di heap setelah 50 rebuild | 0 |
| RCA-3 panel Cari di jendela kecil | 29/33 (build A, kondisi ponsel sama) | 33/33 |
| RCA-5 pemulihan data uji | Preferensi pemilik di `shared_prefs/shared_prefs`, LX Bridge mati | Preferensi di tempatnya setelah setiap run, `lxdb` tersambung |
| RCA-6 resize Tedit di build C | 51,8-60,6% frame lewat anggaran, 0 boost | 21,7% dan 23,8%, 24 boost (setara build sebelum putaran) |
| RCA-7 setelan ke semua jendela Tedit | Tedit instance lain tetap berbahasa Mandarin | Cek "reaches every open Tedit" lulus; tangkapan layar sesuai pilihan |

## Uji mutasi

- Lint: string baru tanpa terjemahan Indonesia ditambahkan sementara, lalu lint gagal dengan `is not translated in "in" (Indonesian) or "zh" (Chinese)`. Berkas dikembalikan dan dibandingkan byte demi byte.
- `tedit:lang` gagal di cek heap pada build sebelum perbaikan kebocoran.
- `tedit:ime` gagal di layar penuh dan lulus di maximized serta mengambang pada build tanpa perbaikan RCA-1.
- `responsive` gagal pada build A (panel Cari terpotong) dan pada build H (potongan daftar kosong 4 dp). Keduanya lulus di build final.

## Hasil yang tidak lulus atau janggal

| Run | Apa yang terjadi | Penilaian |
|---|---|---|
| `tedit:ime`, dua run pertama | Keyboard turun sendiri dan tidak mau naik lagi | Kesalahan uji: HOME dari runner menutup keyboard, dan aktivitas uji berada di task lain tanpa fokus. Dibuktikan dengan `ImeTracker` dan `jdb` (10 dari 10 penutupan berasal dari uji). Uji diperbaiki. |
| `windows` build D, run 025022 | `a notes window's corner stays under the finger`: tarikan ke dalam tidak menggeser jendela (480x664 tetap 480x664) | Satu kali dalam 30 run `windows` di semua build. Notes tidak tersentuh perubahan putaran ini. Belum direproduksi. |
| `windows` A-windows-2 | Keluar dengan kode 1 | Data LX sudah dipulihkan. Galat datang dari versi pertama pemulih riwayat bash (`exec-in`), yang berjalan bersamaan dengan perbaikan manual. Sudah diperbaiki. |
| `tedit:lang` dengan tangkapan layar, run pertama | Gambar `in` dan `system` kosong, lalu menampilkan Tedit berbahasa Mandarin | Uji membuka LX di task sendiri, sehingga layar menampilkan Tedit milik instance Home. Temuan ini membuka RCA-7. Uji kini membuka LX sebagai Home, dan tangkapannya benar. |
| Run 02:54 sampai 03:10 | Berjalan dengan preferensi pemilik terbenam (RCA-5) | Hasil fungsional berlaku untuk pengaturan bawaan. Run yang menentukan diulang dengan preferensi pemilik (r5, r7, r8). |

## Regresi historis terminal

Lihat tabel di `SOURCE_CODE_AUDIT.md`. Ringkasnya, "toolbar tertutup keyboard" memang terjadi di jendela layar penuh dan sudah diperbaiki. Kandidat lain tidak muncul pada cakupan uji. White flash, flicker saat pertama dibuka, Enter otomatis dengan Gboard sungguhan, serta nano dan vi dengan keyboard terbuka tidak diuji di putaran ini.

## Keterbatasan

Satu perangkat (Infinix, Android 15). Waydroid tidak dipakai. Aplikasi milik pemilik tidak dipakai untuk uji. Restart ponsel sungguhan dengan build final belum dilakukan. Cakupan kode tidak diukur. Debian yang dipakai uji terminal milik pemilik, dan riwayat bash-nya dikembalikan setelah setiap run.
