# ADR-002: Satu dokumen JSON berversi dengan penulisan atomik, bukan SQLite

Status: diterima, 23 Oktober 2026

## Masalah

Tata letak (workspace, pintasan, folder, widget, jendela, pin, settings) harus tahan crash, bisa diekspor dan diimpor, dan tidak boleh hilang seluruhnya karena satu entri rusak.

## Pilihan

- SQLite: transaksi bawaan, tetapi menambah dependensi native, migrasi skema tabel, dan format ekspor terpisah.
- DataStore: hanya sisi Kotlin, tidak bisa dipakai core C++.
- Satu dokumen JSON yang ditulis C++: format penyimpanan sama dengan format ekspor, ukurannya sekitar 8 KB untuk 60 pintasan dan 10 jendela.

## Keputusan

Dokumen JSON dengan field `format` dan `version`, disimpan di `files/layout.lxl` dalam amplop `LXL1 <crc32> <panjang>`.

Penulisan berjalan seperti ini:

1. Tulis ke `layout.lxl.tmp`, lalu `fsync`.
2. Jika berkas utama lolos checksum, ganti namanya menjadi `layout.lxl.bak`.
3. Ganti nama berkas sementara menjadi `layout.lxl`, lalu `fsync` folder.

Pembacaan mencoba berkas utama, lalu cadangan. Berkas utama yang gagal checksum dipindah ke `layout.lxl.corrupt` untuk diagnosis.

Validasi berjalan per entitas. Workspace, pintasan, folder, widget, jendela, atau pin yang tidak valid dilewati satu per satu dan dihitung. Dokumen dari versi skema yang lebih baru ditolak tanpa menyentuh data yang ada. Jalur migrasi ada di `Engine::apply` untuk kenaikan versi berikutnya. Saat ini hanya ada versi 1, jadi belum ada langkah migrasi.

## Bukti

Test host `PersistenceTest`, `EngineTest.PersistsAndRecoversFromCorruption`, dan fuzz 3.000 dokumen termutasi. Di perangkat: instrumentation "save, damage and recover the layout on device storage" lulus di Waydroid.
