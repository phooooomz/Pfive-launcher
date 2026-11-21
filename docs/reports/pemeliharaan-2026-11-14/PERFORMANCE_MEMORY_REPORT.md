# Performa dan memori, 14 November 2026

Perangkat: Infinix HOT 60 5G, Android 15, layar 720x1600 pada 120 Hz (anggaran frame 8,3 ms). Semua angka berasal dari APK debug, yang di Android 15 berjalan di interpreter sampai JIT menghangat (F-7 dari putaran 13 November, masih terbuka).

## Build yang dibandingkan

Supaya setiap perubahan bisa dipisahkan, beberapa APK dibangun dari source yang sama dan dipasang bergantian di ponsel (`build/maint-1114/`):

| Build | Isi |
|---|---|
| A | Fitur bahasa, tanpa perbaikan putaran ini |
| B | A + panel dibuat saat tampil, listener terminal, pencarian Ekstensi yang tertunda |
| D | B + jendela layar penuh berhenti di atas keyboard |
| C | D + panel Cari versi pertama (daftar kosong disembunyikan) |
| E, F, G | Eksperimen C: tanpa `ScrollView`, daftar tetap tampil, tanpa `FlowRow` |
| H | D + panel Cari dengan daftar tetap tampil |
| I | H + potongan daftar kosong yang terlalu tipis diberikan ke area kueri |
| J | I + listener setelan Tedit dibagi semua jendela Tedit (build final) |

Pengukuran resize yang sah hanya yang dibuat dengan preferensi pemilik aktif (sesi Tedit dengan panel Cari terbuka). Run antara 02:54 dan 03:10 berjalan dengan preferensi pemilik terbenam (RCA-5) dan hanya dipakai untuk perbandingan dalam kondisi yang sama.

## Waktu buka Tedit

Dari `openTool` sampai frame yang menampilkan jendela, median tiga pembukaan per run (`-e only windows`):

| Build | Median per run |
|---|---|
| A | 167, 147 ms |
| B | 102, 112 ms |
| D | 111, 108, 105, 101 ms |
| H | 94, 104 ms |
| I | 103, 116 ms |
| J (final) | 137, 107, 119 ms |

Tedit kini terbuka sekitar 25-30% lebih cepat (rata-rata median 157 ms menjadi 105-121 ms tergantung build dan run). Seluruh selisih sudah terlihat di B. Penyebabnya, rail tidak lagi membuat keenam panel setiap kali Tedit dibangun. Itu termasuk Berkas yang membaca folder, Kontrol Sumber yang mencari repositori, dan Ekstensi yang menghubungi jaringan. Ini menjawab R-2 dari putaran sebelumnya (Tedit paling lambat dibuka, 122-167 ms). Pembukaan alat lain tidak berubah di luar sebarannya: Settings 91-110 ms, File Explorer 69-78 ms, Notes 35-50 ms.

## Ganti bahasa dan tema (rebuild Tedit)

Uji `tedit:lang`, 50 pergantian zh-CN dan en bergantian dengan shell berjalan, waktu `prefs.set` di thread utama:

| | Sebelum perbaikan (2 run) | Sesudah (4 run: build antara, C, H, I) |
|---|---|---|
| Median | 79, 72 ms | 52, 50, 48, 49 ms |
| Terlama | 135, 121 ms | 77, 69, 81, 78 ms |

## Memori

Heap Java setelah tiga kali GC, diukur setelah pergantian ke-10 dan ke-50:

| | Ke-10 | Ke-50 | Selisih |
|---|---|---|---|
| Sebelum, run 1 | 10.664 KiB | 19.604 KiB | +8.940 KiB |
| Sebelum, run 2 | 11.940 KiB | 21.224 KiB | +9.284 KiB |
| Sesudah, build antara | 7.164 KiB | 7.164 KiB | 0 |
| Sesudah, build C | 7.228 KiB | 7.228 KiB | 0 |
| Sesudah, build H | 7.257 KiB | 7.225 KiB | -32 KiB |
| Sesudah, build I | 7.220 KiB | 7.192 KiB | -28 KiB |
| Sesudah, build J (final), run 1 | 9.928 KiB | 10.772 KiB | +844 KiB |
| Sesudah, build J (final), run 2 | 10.296 KiB | 9.940 KiB | -356 KiB |

Di build J, uji berjalan dengan dua jendela Tedit dalam proses yang sama: alur instrumentasi meninggalkan dua `LauncherActivity`, dan heap dump menunjukkan dua `TeditTool` yang belum dihancurkan. Kini keduanya mendengar setiap perubahan setelan (RCA-7), jadi setiap pergantian membangun ulang dua jendela. Waktu `prefs.set` naik ke median 97 dan 102 ms (terlama 129 dan 166 ms), sekitar dua kali biaya satu jendela. Pada pemakaian biasa LX sebagai Home hanya punya satu instance. Heap J naik 844 KiB di run pertama dan turun 356 KiB di run kedua. Heap dump run kedua: `TextView` 364 ke 342, shallow total -326 KiB. Delapan `ExplorerPanel` masih terjangkau lewat dua jenis rantai: panel aktif di `TeditTool.panels`, dan panel lama yang ditahan pesan pudar scrollbar `ListView` yang masih antre. Jumlahnya turun dari 9 ke 8, jadi ini penahanan sementara, bukan kebocoran.

Heap dump (`Debug.dumpHprofData`, `hprof-conv`, pembaca HPROF di `scratchpad/hprof.py`), selisih jumlah objek antara pergantian ke-10 dan ke-50:

| Kelas | Sebelum | Sesudah |
|---|---|---|
| `android.widget.TextView` | +996 | 0 (208 tetap 208) |
| `android.graphics.Paint` | +9.804 | 0 (2.688 tetap 2.688) |
| `GradientDrawable$GradientState` | +7.140 | 0 |
| `TerminalPanel$ExtraKeys` | +40 (16 ke 56) | 0 (2 tetap 2) |
| `ExtensionsPanel` | +12 (5 ke 17) | 0 (0 tetap 0) |
| Total shallow | sekitar +9 MB | +2 KiB |

Uji terminal `dxterm`: 50 sesi tanpa kebocoran descriptor (239 menjadi 234) dan thread (31 tetap 31), dan tidak ada proses anak tersisa setelah 50 sesi.

## Resize jendela

`-e only windows` menarik sudut jendela 6 kali keluar dan masuk sambil merekam `FrameMetrics`.

**Boost ROM.** Frame resize di ponsel ini sangat bergantung pada boost CPU dari ROM Transsion (`PowerHalMgrImpl: hdl, pid` di log proses LX). Setiap run kini dirangkum dengan jumlah boost selama resize Tedit (`scratchpad/summ.sh`). Settings dan Notes juga bergoyang antar run karena boost berpindah dari alat ke alat: Settings 10,7-45%, Notes 0-43%, di semua build.

Resize Tedit dengan sesi pemilik:

| Build | Boost | Frame lewat anggaran | p90 thread UI |
|---|---|---|---|
| A | 24, 24 | 26,2%, 20,1% | 12,0, 11,5 ms |
| B | 24, 24 | 27,1%, 22,5% | 11,9, 11,3 ms |
| D | 24, 24, 24, 24 | 21,4%, 22,8%, 22,0%, 15,4% | 11,6, 11,5, 11,3, 10,9 ms |
| C | 0, 0, 0, 0 | 59,6%, 59,7%, 60,6%, 51,8% | 18,5, 18,2, 18,4, 18,3 ms |
| E (tanpa `ScrollView`) | 0, 0 | 49,9%, 50,9% | 16,9, 18,3 ms |
| F (daftar tampil) | 24 | 20,7% | 11,6 ms |
| G (tanpa `FlowRow`) | 0 | 56,5% | 17,7 ms |
| H | 24, 24 | 19,2%, 17,8% | 11,4, 11,4 ms |
| I | 24, 24 | 23,8%, 21,7% | 11,6, 11,2 ms |
| J (final) | 24, 24, 24 | 28,2%, 27,3%, 25,5% | 11,6, 11,8, 11,7 ms |

Build C sempat memperburuk resize Tedit sekitar 2,5 kali lipat. Eksperimen E, F, dan G menunjukkan pemicunya adalah daftar hasil Cari yang disembunyikan: tanpa daftar itu ROM berhenti memberi boost (RCA-6). Build final mengembalikan angka ke tingkat sebelum putaran ini. Resize Tedit tidak membaik dan tidak memburuk.

**Kerja thread utama.** Dengan preferensi bawaan, sehingga panel Cari tidak tampil, dan simpleperf terpasang pada jendela resize yang sama (detik 48-57 run), kerja C dan D setara: 4,706-4,750 dan 4,674-4,694 miliar siklus CPU, atau selisih 0,9%. Profil: `WindowLayer.place` 34%, `SplitLayout.onMeasure` 19,7% (halaman sambutan editor di dalam `ScrollView`), dan rebuild Tedit setelah jari diangkat 17%. `SearchPanel`, `FlowRow`, dan `Column` tidak muncul di atas 0,8%.

## Uji host

`scripts/test-host.sh`: 227 uji lulus (Debug) dan 226 uji lulus (ASan dan UBSan), sama dengan 13 November. Patokan Release: pindah jendela 0,070 µs, pencarian 400 item 42,8 µs, wallpaper 720x1600 22,4 ms, blur 180x400 8,3 ms.

## Keluhan pemilik

- "Aplikasi terasa berat ketika dibuka": Tedit terbuka sekitar 30% lebih cepat. Build debug yang berjalan di interpreter (F-7) tetap penyebab terbesar yang tersisa untuk semua alat.
- "Berat saat resize jendela": putaran ini tidak memperbaikinya dan, setelah RCA-6 diperbaiki, tidak memperburuknya. Waktu frame resize di ponsel ini lebih ditentukan oleh boost ROM dan build debug daripada oleh satu bagian kode LX.
