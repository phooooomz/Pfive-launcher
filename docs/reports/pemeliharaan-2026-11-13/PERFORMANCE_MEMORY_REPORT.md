# Performa dan memori, 13 November 2026

Semua angka diambil di Infinix HOT 60 5G (Android 15, 120 Hz, anggaran 8,3 ms per frame) dengan APK debug. Uji resize adalah `-e only windows` di `WindowChecks`. Uji itu membuka jendela 480x664 px, menarik sudut kanan bawah keluar lalu kembali tiga kali (45 langkah per tarikan), dan membaca setiap frame lewat `FrameMetrics`. Ponsel ini punya beban latar sendiri (loadavg sekitar 13), jadi setiap perbandingan dijalankan bergantian A, B, A, B dengan dua run per build. Berkas mentah ada di `build/maint-1113/ab-*.txt`.

Build yang dibandingkan:

- `b0`: source sebelum sesi ini.
- `exp-nofade`: `b0` dengan semua fading edge dimatikan (hanya eksperimen, tidak dipakai).
- `c1`: perbaikan pemetaan sentuhan, penundaan rebuild Tedit, dan fading edge bergradasi di dalam jendela.
- `c3`: `c1` ditambah layer untuk deret di bilah kaca dan perbaikan peluncuran `lxdb`. Ini build akhir.

## Profil sebelum perbaikan

`simpleperf record --app com.lxlauncher -g -f 1000` selama resize Settings, Files, dan Notes:

| Thread | Bagian CPU LX |
|---|---|
| RenderThread | 46,4% |
| thread utama | 42,1% |
| lain-lain (binder, JIT, metrik) | 11,5% |

Di RenderThread, 14,75% waktu habis di `GrCopyRenderTask` (`glCopyTexSubImage2D`), dipanggil dari `SkCanvas::only_axis_aligned_saveBehind`, yaitu fading edge tanpa warna latar solid. `eglSwapBuffers` 17,4%, wajar untuk GPU yang mengirim pekerjaannya saat swap. Di thread utama, `WindowLayer.place` 39,7%, `GridView.layoutChildren` (File Explorer) 11,4%, dan pengukuran teks (`BoringLayout.isBoring`) 10,5%. Sebagian besar kode LX di thread utama berjalan di interpreter (`ExecuteSwitchImplCpp`) karena build debug.

## Eksperimen fading edge (b0 lawan exp-nofade)

| Jendela | Issue median b0 | Issue median tanpa fade | Lewat anggaran b0 | Lewat anggaran tanpa fade |
|---|---|---|---|---|
| Settings | 4,04 / 4,42 ms | 2,37 / 2,63 ms | 53,5 / 57,7% | 41,6 / 49,2% |
| Notes | 4,65 / 4,71 ms | 3,64 / 3,68 ms | 52,9 / 53,1% | 51,6 / 51,8% |
| Files | 2,80 / 2,76 ms | 2,22 / 2,21 ms | 32,6 / 33,7% | 33,4 / 34,4% |
| Tedit | 4,57 / 3,85 ms | 3,90 / 3,74 ms | 72,4 / 65,5% | 71,0 / 72,3% |

## Hasil akhir (b0 lawan c3)

| Jendela | Issue median | Issue p90 | Lewat anggaran | Total p99 | Thread UI p90 |
|---|---|---|---|---|---|
| Settings b0 | 4,55 / 4,21 ms | 6,46 / 6,23 ms | 57,2 / 57,4% | 30,1 / 29,3 ms | 18,6 / 17,2 ms |
| Settings c3 | 2,45 / 3,08 ms | 4,63 / 4,77 ms | 51,6 / 57,5% | 30,1 / 30,2 ms | 13,5 / 19,0 ms |
| Files b0 | 2,79 / 2,84 ms | 3,21 / 3,27 ms | 35,7 / 36,7% | 14,4 / 15,4 ms | 7,5 / 7,7 ms |
| Files c3 | 2,12 / 2,08 ms | 2,35 / 2,38 ms | 36,3 / 32,1% | 13,9 / 14,0 ms | 7,5 / 7,4 ms |
| Notes b0 | 3,66 / 4,56 ms | 5,70 / 6,40 ms | 31,5 / 48,1% | 17,0 / 18,4 ms | 5,5 / 5,8 ms |
| Notes c3 | 3,43 / 3,57 ms | 4,32 / 4,27 ms | 49,5 / 53,9% | 16,9 / 17,3 ms | 6,2 / 6,2 ms |
| Tedit b0 | 4,50 / 4,86 ms | 6,51 / 7,40 ms | 74,6 / 78,4% | 67,1 / 66,5 ms | 12,0 / 11,9 ms |
| Tedit c3 | 3,71 / 3,64 ms | 5,43 / 5,47 ms | 63,4 / 67,5% | 32,1 / 30,9 ms | 13,8 / 13,4 ms |

Yang membaik dengan jelas: beban RenderThread untuk setiap jendela, terutama Settings (median issue turun sekitar sepertiga), dan frame terburuk Tedit yang kini separuhnya. Yang belum membaik: persentase frame yang lewat anggaran 120 Hz untuk Settings, Notes, dan Files. Kolom swap (median 0,7 sampai 1,6 ms) dan jeda (median 0,7 sampai 1,3 ms) yang baru ditambahkan menunjukkan sisa waktunya terbagi rata: thread utama sekitar 2 sampai 4 ms, issue 2 sampai 3,6 ms, swap sekitar 1 ms, dan jeda sekitar 1 ms. Tidak ada lagi satu sumber besar.

## Waktu membuka jendela

Dari pemanggilan `openTool` sampai frame yang menampilkan jendela, median dari tiga pembukaan per run, empat run:

| Jendela | b0 | c3 |
|---|---|---|
| Settings | 100 / 108 ms | 95 / 85 ms |
| File Explorer | 75 / 73 ms | 70 / 66 ms |
| Monitor | 64 / 57 ms | 61 / 58 ms |
| Notes | 44 / 37 ms | 44 / 42 ms |
| Tedit | 131 / 130 ms | 144 / 136 ms |

Perbedaan antar build masih di dalam sebaran antar run. Tidak ada regresi waktu buka yang terlihat.

## Start dingin

Diukur dari log `LxPerf` setiap kali skrip uji menghentikan LX lalu menekan HOME: frame pertama 461 sampai 735 ms setelah proses dimulai (183 sampai 329 ms setelah activity dibuat), tata letak dimuat dalam 15 sampai 32 ms, dan daftar 116 aplikasi siap dalam 244 sampai 341 ms di thread latar.

## Build debug dan kompilasi

`dumpsys package dexopt` mencatat LX berstatus `verify`. Perintah `cmd package compile -m speed -f com.lxlauncher` berhasil, tetapi statusnya tetap `verify`, karena Android 15 hanya memverifikasi aplikasi `debuggable`. Akibatnya kode LX berjalan di interpreter sampai JIT menghangat, setiap kali proses LX dimulai ulang. Build rilis yang ditandatangani akan dikompilasi Android dan tidak memakai CheckJNI. Keuntungannya belum diukur di ponsel ini, karena memasang build non-debug menghentikan cadangan dan pemulihan data lewat `run-as` yang dipakai semua uji.

## Memori

Dua deret di bilah kaca kini masing-masing punya hardware layer seukuran deret itu. Ukurannya dicatat di bagian akhir laporan uji regresi (`REGRESSION_TEST_REPORT.md`, `dumpsys gfxinfo`).
