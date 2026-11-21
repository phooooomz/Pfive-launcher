# Kesehatan proyek, 13 November 2026

**Status: PARTIALLY VERIFIED.** Regresi besar yang ditemukan (jendela aplikasi mati setelah `lxdb` berhenti) sudah diperbaiki dan diverifikasi di Infinix. Resize kini lebih ringan untuk RenderThread, dan frame terburuk Tedit turun separuh. Keluhan keyboard di Brave belum bisa diulang di ponsel uji, jadi belum dinyatakan beres.

## Perbaikan yang terverifikasi

| Masalah | Bukti setelah perbaikan |
|---|---|
| `lxdb` tidak bisa dinyalakan sejak build Tedit dan Debian (RCA-1) | Daemon naik dari salinan pustaka hasil bongkar dan tersambung 20 ms setelah diluncurkan. Uji baru memeriksa bahwa pustaka itu bisa dibuka linker. |
| Fading edge menyalin isi layar setiap frame (RCA-2) | Waktu "issue" RenderThread saat resize: Settings 4,2-4,6 menjadi 2,5-3,1 ms, File Explorer 2,8 menjadi 2,1 ms, Tedit 4,5-4,9 menjadi 3,6-3,7 ms. |
| Tedit membangun ulang tampilan di tengah tarikan (RCA-2) | p99 resize Tedit 66-67 menjadi 31-32 ms. Uji Tedit 24/24. |
| Skala sentuhan berbeda dari skala gambar di jendela aplikasi (RCA-3) | Uji pemetaan untuk tiga keadaan lulus. Uji keyboard pada build akhir: 190 langkah lulus tanpa keyboard tertutup. |
| Pemulihan data uji menimpa tata letak pemilik (RCA-4) | Tata letak pemilik dipulihkan, dan setiap uji sesudahnya mengembalikan header yang sama. |

Pemeriksaan umum pada build akhir: instrumentasi lengkap 51/51, `-e only windows` tanpa FAIL di empat run, uji host 227 dan 226 lulus, lint 0 error.

## Masalah yang masih terbuka

| ID | Prioritas | Masalah | Langkah berikutnya |
|---|---|---|---|
| F-4 | P1 | Brave menutup keyboard setelah ketukan pertama (dua kali pada 12 November). Mekanismenya terbukti, ketukan sampai ke halaman dan bukan ke keyboard, tetapi pemicunya di ponsel pemilik belum terbukti. | Uji langsung di Brave dengan izin pemilik. Kalau terjadi lagi, `dumpsys input_method` segera sesudahnya menyimpan alasannya. |
| R-1 | P2 | Persentase frame resize yang lewat anggaran 120 Hz untuk Settings, Notes, dan File Explorer belum turun berarti (sekitar 32 sampai 57%). Waktunya kini terbagi rata antara thread utama, issue, swap, dan jeda. | Kurangi kerja thread utama per frame. Build rilis (F-7) kemungkinan memberi lompatan terbesar. |
| F-7 | P2 | Build debug tidak pernah dikompilasi Android 15 (`status=verify`), jadi LX berjalan di interpreter setiap kali prosesnya dimulai ulang. | Keputusan pemilik: memakai build rilis bertanda tangan untuk sehari-hari. Tanda tangan baru berarti LX harus dicopot dulu, jadi tata letak perlu diekspor lalu diimpor. |
| R-2 | P3 | Waktu buka Tedit 122 sampai 167 ms, paling lambat di antara alat LX. | Profil pembukaan Tedit dengan `simpleperf`. |
| R-3 | P3 | Jendela mengambang yang diperkecil belum bisa diuji keyboard-nya lewat UI di tata letak ponsel. | Uji instrumentasi yang mengatur ukuran jendela langsung. |

## Batasan pengujian

Semua uji perangkat dilakukan di satu ponsel, Infinix Android 15. Waydroid tidak dipakai sesuai permintaan pemilik. Aplikasi milik pemilik (Brave, Chrome, WhatsApp) tidak dipakai untuk uji. Restart ponsel sungguhan dengan build akhir belum dilakukan. Cakupan kode tidak diukur. Angka frame di ponsel ini bergoyang karena beban latar ROM, jadi setiap perbandingan dibuat bergantian dengan dua run per build.

## Rekomendasi pemeliharaan

1. Pertahankan uji instrumentasi pustaka `lxdb` di setiap perubahan build atau manifest. Regresi RCA-1 lolos karena Waydroid tidak menjalankan LX Bridge, dan daemon lama di Infinix menutupi masalahnya.
2. Jalankan `scripts/ui_test_keyboard_web.py` bersama dua uji keyboard lama setiap kali jalur sentuhan atau keyboard berubah.
3. Ukur resize dengan `-e only windows` secara A/B bergantian, dua run per build. Satu run tidak cukup di ponsel ini.
4. Hindari fading edge tanpa `fadeInto` atau layer sendiri pada view yang ikut digambar setiap frame.
5. Saat Brave menutup keyboard lagi, simpan `adb shell dumpsys input_method` segera sesudahnya. Riwayatnya hanya memuat 100 kejadian terakhir.
