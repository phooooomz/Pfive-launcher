# ADR-003: Tiga tingkat kemampuan jendela

Status: diterima, 23 Oktober 2026

## Masalah

Launcher tidak berhak memindahkan atau mengubah ukuran jendela aplikasi lain. Brief meminta window manager internal sekaligus kejujuran soal batas Android.

## Keputusan

| Tingkat | Isi | Siapa yang mengatur |
|---|---|---|
| 1. Jendela LX | LX Settings, File Manager, System Monitor, Notes, Welcome | `lx::WindowManager` di C++: ID, z-order, fokus, state, batas viewport, snap, tile, perpindahan workspace |
| 2. Jendela aplikasi Android | Aplikasi pihak ketiga yang dibuka dengan `ActivityOptions.setLaunchBounds` | Android. LX hanya meminta, dan hanya jika `FEATURE_FREEFORM_WINDOW_MANAGEMENT` ada dan pengguna menyalakan opsinya |
| 3. Layar penuh | Perilaku normal Android | Android |

Geometri jendela LX disimpan sebagai pecahan viewport. Akibatnya jendela tetap berada di posisi relatif yang sama setelah rotasi, dan kembali ke posisi semula saat rotasi dibalik. Ukuran tidak pernah di bawah batas minimum, dan bilah judul selalu terjangkau.

LX tidak pernah menampilkan tangkapan layar aplikasi seolah-olah aplikasi itu berjalan di dalam jendela LX. Taskbar hanya mencantumkan jendela LX yang benar-benar terbuka, bukan proses Android.

## Bukti

20 test unit window manager dan fuzz 20.000 operasi acak (`Fuzz.WindowOperationsKeepInvariants`). UI test di Waydroid: maximise, minimise, restore lewat taskbar, dan drag bilah judul.
