# Audit environment

Tanggal: 23 Oktober 2026. Semua nilai di bawah dibaca langsung dari mesin, bukan diasumsikan.

## Host

| Item | Nilai |
|---|---|
| Sistem operasi | Zorin OS 18.1, kernel Linux 6.17.0-35-generic, x86_64 |
| CPU | AMD Ryzen 5 7520U, 4 core / 8 thread |
| RAM | 7,0 GiB, swap 15 GiB |
| Disk kosong | 279 GB di `/` |
| JDK | OpenJDK 17.0.20.1 |
| Android SDK | `~/Android/Sdk`, platform 34, 35, 36; build-tools 34.0.0 dan 35.0.0 |
| Android NDK | 26.1, 27.0.12077973 (dipakai, Clang 18.0.1), 29.0 |
| CMake | 3.22.1 dari SDK (untuk Android), 3.28.3 sistem (untuk test host) |
| Ninja | 1.11.1 |
| Compiler host | GCC 13.3.0 |
| Gradle | 8.14.3 lewat wrapper (distribusi sudah ada di cache) |
| Android Gradle Plugin | 8.11.1 (ada di cache) |
| Kotlin | 2.1.20 |
| Qt | Tidak terpasang. Tidak ada `qmake` atau `qt-cmake`, tidak ada `~/Qt` |
| Git | 2.43.0. Folder project belum berupa repository Git |
| ADB | 1.0.41, platform-tools 37.0.0 |
| Waydroid | 1.6.2, image LineageOS Android 13 x86_64 |

## Target pengujian

| Target | Detail | Dipakai |
|---|---|---|
| Waydroid | Android 13 (API 33), ABI x86_64, layar 1920x1028 pada 180 dpi, fitur `freeform_window_management` dan `app_widgets` ada | Ya, semua pengujian perangkat |
| Infinix X6726B | Android 15 (API 35), ABI arm64-v8a, 720x1600 pada 320 dpi, Home bawaan `com.transsion.XOSLauncher` | Tidak. Operator meminta pengujian di Waydroid |

Tampilan ponsel diuji dengan meniru ukuran Infinix di Waydroid (`wm size 720x1600`, `wm density 320`). Cara ini menguji tata letak, bukan perangkat keras: performa, gestur, dan ABI arm64 di ponsel nyata belum diverifikasi.

## Catatan

- Emulator Android resmi tidak dipakai. Waydroid berjalan sebagai container LXC di kernel host, jadi angka performa di sana mencerminkan CPU host, bukan ponsel.
- `run-as` diblokir di Waydroid (`setegid ... Operation not permitted`). Pengujian membaca hasil lewat UI dan instrumentation, bukan dengan membaca berkas aplikasi.
- `adb exec-out screencap` di Waydroid mencampur pesan driver ke dalam stream PNG. `scripts/shot.sh` menyimpan ke berkas dulu lalu menariknya.
