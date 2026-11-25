# Kesehatan proyek, 14 November 2026

**Status: PARTIALLY VERIFIED.** Semua perbaikan dan fitur putaran ini terverifikasi di Infinix pada build final (J), dengan preferensi pemilik aktif dan LX Bridge tersambung. Itu termasuk keluhan baru pemilik (terminal Tedit tertutup keyboard dalam layar penuh), dua kebocoran memori, pencarian Open VSX tanpa tindakan pengguna, panel Cari di jendela kecil, dan dua cacat alat uji yang menyentuh data pemilik. Status keseluruhan belum VERIFIED karena keluhan keyboard yang tertutup sendiri (F-4) masih belum bisa diulang, build debug tetap berjalan di interpreter (F-7), dan resize tidak membaik di putaran ini.

## Yang dikerjakan dan terverifikasi

| Masalah atau fitur | Bukti pada build final |
|---|---|
| Pilihan bahasa Tedit: Bahasa ponsel, Indonesia, English, 中文 | `tedit:lang` 10/10 di dua run: tidak ada teks dari bahasa lain di Settings, panel Berkas, dan terminal untuk keempat pilihan; tangkapan layar setiap bahasa sesuai; LX tetap memakai bahasa ponsel; 50 pergantian dengan shell berjalan. Lint 0 error. |
| Terminal tertutup keyboard saat Tedit layar penuh (RCA-1) | `tedit:ime` 7/7, termasuk 50 siklus keyboard. Panel terminal berakhir 50 px di atas keyboard. Tangkapan layar memperlihatkan prompt dan baris tombol di atas keyboard. |
| Memori naik sekitar 9 MB per 40 rebuild Tedit (RCA-2) | Heap tidak naik di build I (7.220 ke 7.192 KiB). Di build J, dengan dua jendela Tedit yang ikut dibangun ulang, +844 KiB lalu -356 KiB; heap dump tanpa kelas yang bertambah. |
| Rail membuat keenam panel; Ekstensi mencari ke Open VSX tanpa diminta (RCA-2, SEC-1) | 0 `ExtensionsPanel` setelah 50 rebuild. Tedit terbuka sekitar 30% lebih cepat (median 157 ke sekitar 106 ms). |
| Panel Cari terpotong dan terjepit di jendela kecil (RCA-3) | `responsive` 33/33 dengan panel Cari terbuka. Build sebelum perbaikan 29/33 pada kondisi yang sama. |
| Resize Tedit melambat karena perbaikan panel Cari versi pertama (RCA-6) | Ditemukan lewat pembanding dengan kondisi pemilik dan eksperimen E, F, G. Build final kembali ke tingkat sebelum putaran ini (25,5-28,2%, 24 boost; build sebelum putaran 20,1-27,1%). |
| Setelan yang diubah di satu Tedit tidak sampai ke Tedit lain (RCA-7) | Ditemukan dari tangkapan layar uji bahasa. Listener kini dibagi semua jendela, dan cek baru lulus. |
| Uji menulis ke Debian milik pemilik (RCA-4, SEC-5) | Riwayat bash dikembalikan; runner kini mengembalikannya otomatis dan memeriksa hasilnya. |
| Pemulihan uji membenamkan preferensi pemilik (RCA-5, SEC-7) | Preferensi dikembalikan dan `lxdb` tersambung lagi pukul 03:11:02. Pemulihan kini atomik dengan `mv -T` dan diperiksa per berkas; kelima preferensi utuh setelah setiap run sesudahnya. |

Pemeriksaan umum pada build final: suite instrumentasi lengkap 51/51, `responsive` 33/33, `windows` 18/18 (3 run), `tedit` 24/24, `tedit:lang` 10/10, `tedit:ime` 7/7. Grup yang tidak menyentuh panel Cari dan setelan Tedit lulus di build C: `dxterm` 33/33, `ext` 10/10, `maintenance` 7/7, `fm` 8/8. Uji host 227 dan 226, lint 0 error. Tidak ada `FATAL EXCEPTION` atau ANR LX di log semua run.

## Masalah yang masih terbuka

| ID | Prioritas | Masalah | Langkah berikutnya |
|---|---|---|---|
| F-4 | P1 | Keyboard tertutup sendiri saat mengetik (keluhan pemilik, terakhir terlihat di Brave). Di putaran ini `jdb` membuktikan tidak ada kode LX yang menutup keyboard selama uji Tedit, tetapi pemicunya di pemakaian pemilik belum tertangkap. Satu pemicu nyata ditemukan: HOME menutup keyboard LX (`stateHidden`). Itu perilaku Android, bukan bug. | Bila terjadi lagi, segera ambil `adb shell dumpsys input_method`. Riwayat `ImeTracker` di sana menyebut asal dan alasan setiap penutupan. |
| F-7 | P2 | Build debug tidak pernah dikompilasi Android 15 dan berjalan di interpreter. Ini masih penyebab terbesar rasa berat saat dibuka. | Keputusan pemilik: build rilis bertanda tangan untuk pemakaian harian. |
| R-1 | P2 | Resize tidak membaik di putaran ini. Frame resize sangat bergantung pada boost ROM: tanpa boost, resize Tedit 50-60% frame lewat anggaran. | Kurangi kerja per frame di `WindowLayer.place` (34% profil) dan halaman sambutan editor (20%). Setiap pembanding harus mencatat jumlah boost. |
| R-4 | P3 | Kegagalan tunggal "notes window's corner stays under the finger" di build antara D (1 dari 30 run `windows`). | Ulangi `windows` beberapa kali. Bila muncul lagi, rekam `getevent` selama tarikan. |
| A-7 | P3 | Di panel Cari selebar sekitar 110 dp, tombol Aa, ab, dan regex turun satu per baris. | Kurangi inden 24 dp di panel sempit. |
| L-1 | P3 | Terjemahan Mandarin dibuat tanpa pemeriksaan penutur asli. | Minta penutur asli memeriksa `values-zh-rCN/strings_tedit.xml`. |
| T-1 | P3 | Kandidat regresi terminal yang tidak diuji putaran ini: white flash, flicker saat pertama dibuka, Enter otomatis dengan Gboard sungguhan, nano dan vi dengan keyboard terbuka. | Uji pengetikan IME sungguhan di terminal Tedit. |

## Batasan pengujian

Satu perangkat (Infinix, Android 15, Gboard). Waydroid tidak dipakai. Aplikasi milik pemilik tidak dipakai. Restart ponsel dengan build final tidak dilakukan. Cakupan kode tidak diukur. Hasil resize bergoyang karena boost ROM, jadi perbandingan yang bisa dipercaya hanya yang dibuat dengan jumlah boost yang sama atau dengan siklus CPU. Run antara 02:54 dan 03:10 berjalan dengan preferensi pemilik terbenam, dan kesimpulan yang bergantung pada sesi pemilik diambil dari run sesudah pemulihan.

## Rekomendasi

1. Pakai build rilis bertanda tangan untuk sehari-hari (F-7). Ini kemungkinan memberi lompatan terbesar pada rasa berat saat dibuka dan saat resize.
2. Setiap pembanding performa di ponsel ini harus mencatat boost ROM per run dan menyertakan siklus CPU. Tanpa itu, satu run tanpa boost cukup untuk menyesatkan kesimpulan, seperti yang hampir terjadi di putaran ini.
3. Setelah setiap uji, periksa `shared_prefs` LX selain header tata letak. Pemeriksa baru di `restore_data` melakukannya otomatis.
4. Biarkan `tedit:lang` dan `tedit:ime` di setiap putaran berikutnya. Keduanya murah dan menjaga dua jalur yang mudah rusak tanpa terlihat: rebuild dan keyboard.
