# Perubahan pemeliharaan, 13 November 2026

Setiap baris di bawah menyebut berkas, alasan, dan akibatnya. Analisis lengkapnya ada di `ROOT_CAUSE_ANALYSIS.md`, angka sebelum dan sesudahnya di `PERFORMANCE_MEMORY_REPORT.md`.

## Kode aplikasi

| Berkas | Perubahan | Alasan | Akibat |
|---|---|---|---|
| `embed/ShellHelper.kt` | `library()` baru memilih `liblxdb.so` hasil bongkar Android di `nativeLibraryDir`, dengan jalur di dalam APK sebagai cadangan. `command()` kini menerima jalur pustaka itu. | Sejak `extractNativeLibs="true"`, pustaka di APK terkompresi dan `linker64` gagal membukanya (RCA-1). | `lxdb` kembali bisa dinyalakan LX, jadi jendela aplikasi terbuka lagi setelah daemon lama berhenti atau ponsel dimulai ulang. |
| `bridge/LxBridge.kt` | `launchOn` memakai `ShellHelper.library`. | Sama dengan baris di atas. | Sama. |
| `embed/EmbeddedAppTool.kt` | `forward` memetakan sentuhan lewat transform yang sedang dipasang di `TextureView` (`getTransform` dan `viewToDisplay`). `showTop` mereset bentuk tertahan dan memasangnya lagi saat dimatikan. `sizeChanged` menghitung ulang tampilan atas untuk ukuran baru. | Skala sentuhan bisa berbeda dari skala gambar setelah `holdShape` disusul `showTop` (RCA-3). | Ketukan pada aplikasi mendarat di titik yang terlihat, juga saat keyboard LX terbuka atau sedang turun. |
| `tedit/TeditTool.kt` | `onLiveResize` mencatat tarikan. Selama tarikan, `onResized` menunggu, dan ukuran akhir diterapkan sekali lewat layout berikutnya (`resizeOwed`). | Tedit membangun ulang semua view saat melewati 300 dp di tengah tarikan, dan setiap perubahan lebar panel memaksa layout penuh (RCA-2). | p99 resize Tedit turun dari 66-67 ms ke 31-32 ms. |
| `ui/StableScroll.kt` | `fadeInto` dan `getSolidColor()` di `StableScrollView` dan `StableHorizontalScrollView`. | Fading edge tanpa warna solid menyalin isi layar setiap frame (RCA-2). | Pudaran di atas warna buram digambar sebagai gradasi. |
| `ui/theme/Tokens.kt` | `LxTheme.windowBody`: warna panel bila badan jendela buram, 0 pada gaya kaca. | Sumber warna untuk `fadeInto`. | Gaya kaca tetap memakai pudaran asli. |
| `ui/Cards.kt` | `raisedColor()` dipisah dari `raised()`. | Pemilih di kartu Settings memudar ke warna kartu. | Warna kartu tidak berubah. |
| `tools/SettingsTool.kt`, `tools/SimpleTools.kt` | Kolom gulir memakai `fadeInto = { windowBody }`. | RCA-2. | Kurang satu salinan layar per frame di Settings dan alat lain. |
| `tools/AppearancePickers.kt` | Dua pemilih memakai `fadeInto = { Cards.raisedColor(theme) }`. | RCA-2. | Sama. |
| `files/FileManagerTool.kt` | Path di File Explorer kini `StableHorizontalScrollView` dengan `fadeInto` warna kolomnya. | RCA-2. | Sama, dan path tidak diukur ulang di setiap frame resize. |
| `ui/panels/Panels.kt` | Deret nomor workspace dan deret ikon bilah bawah memakai `LAYER_TYPE_HARDWARE`. | Bilah berlatar kaca, jadi pudarannya tidak bisa berupa gradasi satu warna. Tanpa layer, salinannya terjadi di setiap frame layar (RCA-2). | Pudaran dihitung hanya saat deret berubah. Tampilan sama. |
| `AndroidManifest.xml` | Komentar `extractNativeLibs` dibetulkan. | Komentar lama menyatakan pustaka tetap tidak terkompresi. | Dokumentasi sesuai perilaku build. |

## Uji dan alat uji

| Berkas | Perubahan | Alasan |
|---|---|---|
| `androidTest/.../KeyboardWebProbeActivity.java`, `androidTest/AndroidManifest.xml` | Probe WebView baru (`LxProbeWeb`) dengan tiga warna yang sama seperti probe lama, mencatat fokus jendela, keyboard, fokus dan blur kolom, teks, dan tombol. | Keluhan keyboard berasal dari Brave (Chromium). Uji lama hanya memakai `EditText` dan `input text`. |
| `scripts/ui_test_keyboard_web.py` | Uji baru: ketukan tunggal, rentetan cepat, ketukan saat keyboard naik, jendela yang langsung diketik, varian jendela mengambang (`--floating`), dan pembanding di luar LX (`--outside-only`). | Reproduksi RCA-3 dan pencegahan regresi. |
| `scripts/ui_test_file_manager.py`, `scripts/ui_test_app_windows.py` | `restore_data` mengekstrak ke `restore.tmp`, menukar dengan `mv` di antara dua `force-stop`, lalu memeriksa header tata letak. `save_data` menolak cadangan tanpa tata letak. | RCA-4: tata letak pemilik tertimpa tata letak bawaan. |
| `scripts/ui_test_lxdb.sh` | Menjalankan `lxdb` dari salinan hasil bongkar bila ada. | RCA-1. |
| `androidTest/.../EmbedChecks.kt` | Dua uji baru: pemetaan sentuhan untuk tiga keadaan transform, dan pustaka `lxdb` yang bisa dibuka linker. Uji baris peluncuran disesuaikan. | Pencegahan RCA-1 dan RCA-3. |
| `androidTest/.../WindowChecks.kt` | Tedit ikut diukur waktu buka dan resize. Laporan frame menambah `swap` dan `delay`. | Keluhan "berat saat dibuka" dan resize Tedit. |

## Dokumen

`README.md`, `docs/architecture/ADR-008-lxdb.md`, dan `docs/architecture/tedit.md` kini menyebut bahwa `lxdb` dijalankan dari salinan pustaka hasil bongkar. Tujuh laporan di folder ini baru.

## Yang tidak diubah

Tidak ada fitur yang dihapus, tidak ada izin baru, dan tidak ada dependensi baru. Format tata letak tidak berubah.
