# ADR-001: Android View native dengan core C++20, tanpa Qt

Status: diterima, 23 Oktober 2026

## Masalah

Brief merekomendasikan Qt Quick dengan C++20, tetapi meminta keputusan akhir mengikuti bukti. Launcher harus bisa menjadi Home, meng-host widget Android, dan tetap ringan di ponsel RAM rendah.

## Pilihan yang dinilai

| | A. Qt for Android | B. Activity Android + QtQuickView | C. Android View + core C++20 lewat JNI |
|---|---|---|---|
| Tersedia di mesin | Tidak, perlu unduhan sekitar 1,5 sampai 2 GB | Tidak | Ya, SDK dan NDK sudah ada |
| Widget Android (`AppWidgetHostView`) | Harus ditumpuk sebagai View Android di atas permukaan Qt; urutan z dengan jendela internal sulit | Lebih mungkin, tapi tetap dua toolkit di satu layar | View biasa di dalam desktop; ikut urutan z, drag, dan resize |
| Ukuran APK | Runtime Qt menambah belasan MB per ABI | Sama | 1,8 MB release untuk dua ABI |
| Lisensi | LGPLv3 atau komersial, perlu kepatuhan relink | Sama | Apache 2.0 untuk dependensi yang ikut dikirim |
| Aksesibilitas | Lewat jembatan aksesibilitas Qt | Campuran | TalkBack membaca View native langsung |

## Keputusan

Pilihan C. Semua logika yang bisa diuji tanpa Android ditulis di C++20 (`core/`): window manager, workspace, model desktop, skema settings, validasi, parser JSON berbatas, penyimpanan atomik, ranking pencarian, render wallpaper, dan blur. Kotlin hanya mengisi bagian yang memang diwajibkan framework: Activity Home, LauncherApps, AppWidgetHost, Storage Access Framework, dan gambar View.

## Bukti

- PoC Qt tidak dibangun karena Qt belum terpasang. Operator memilih jalur ini lewat pertanyaan di awal sesi, setelah diberi tahu biaya unduhan dan masalah hosting widget.
- Hasil terukur jalur C di Waydroid: cold start 149 sampai 166 ms (Launcher3 bawaan 153 sampai 179 ms), PSS 66,7 MB (Launcher3 106 MB), 0 jank dari 156 frame saat membuka laci dan berganti workspace. Detail di `docs/testing/test-report.md`.

## Akibat

- Tidak ada QML. Komponen UI ditulis sebagai View Kotlin dengan design token terpusat (`ui/theme/Tokens.kt`).
- Batas JNI dijaga di satu berkas (`app/src/main/cpp/jni_bridge.cpp`) dengan konversi UTF-16 ke UTF-8 yang benar dan satu mutex per engine.
- Kalau suatu saat Qt dibutuhkan, core C++ tetap bisa dipakai karena tidak bergantung pada Android.
