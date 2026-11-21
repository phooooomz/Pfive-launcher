# Audit source code, 14 November 2026

Putaran kedua pemeliharaan. Putaran pertama (13 November) ada di `docs/reports/pemeliharaan-2026-11-13/`. Putaran ini memusatkan pemeriksaan pada kode yang masuk sejak itu: terminal dracxterm di Tedit, tata letak jendela kecil, ikon Tedit, pilihan bahasa Tedit yang dibuat di awal putaran, serta keluhan baru pemilik tentang keyboard yang menutupi terminal.

## Inventaris

| Komponen | Ukuran | Isi |
|---|---|---|
| Aplikasi Kotlin (`app/src/main/kotlin`) | 45.034 baris, 123 berkas | Launcher, jendela, alat bawaan, File Explorer, LX Bridge, klien `lxdb` |
| Tedit (`app/src/main/kotlin/com/lxlauncher/tedit`) | 19.601 baris, 45 berkas | Editor, panel, terminal, Debian, ekstensi, server bahasa |
| dracxterm (`third_party/dracxterm`, Kotlin dan C++) | 3.690 baris | Mesin terminal, PTY, view terminal (GPL-3.0) |
| Uji instrumentasi (`app/src/androidTest`) | 6.839 baris | `SelfTestInstrumentation` dan grup `-e only ...`, termasuk dua grup baru |
| Core C++ (`core/`, `.cpp` dan `.h`) | 5.662 baris | Model tata letak, jendela, persistensi |
| Resource Tedit | 699 string Inggris, 699 Indonesia, 697 Mandarin, 20 plural | `values`, `values-in`, `values-zh-rCN` |

## Sumber kebenaran yang dibaca

- `PROMPT_PEMELIHARAAN_LX_LAUNCHER.md` (843 baris, md5 `a32deef65c9c79f50df70ce568e619ab`, tidak berubah sejak 13 November). Termasuk bagian 9 tentang regresi historis terminal, yang kini berlaku karena Tedit memakai terminal dracxterm.
- Laporan 13 November, terutama masalah terbuka F-4 (keyboard tertutup di Brave), R-1 (frame resize), F-7 (build debug di interpreter), R-2 (Tedit paling lambat dibuka), dan R-3 (uji keyboard jendela mengambang).
- `CHANGELOG.md`, `README.md`, `docs/architecture/tedit.md`, `third_party/dracxterm/README.lx.md`.
- Bukti dari ponsel: log setiap run, `ImeTracker`, heap dump, `jdb` dengan breakpoint di `InputMethodManager.hideSoftInputFromWindow`, tangkapan layar uji jendela kecil dan keyboard, serta isi `no_backup` (hanya dibaca).

## Alur yang diperiksa

| Alur | Cara | Hasil |
|---|---|---|
| Ganti bahasa Tedit | Source `TeditLocale.kt`, `TeditTool.onPref`; semua pembacaan string di `tedit/` (grep penerima `getString`) | Semua lewat `tool.ctx`. `Marketplace.install` dan `LinuxRuntime` memakai `applicationContext` hanya untuk berkas. `DocStore` dan pemeriksa masalah menerima `tool.ctx`. |
| Rebuild Tedit (tema, bahasa, kelas ukuran) | Source `build()`, `buildRail()`, `refreshDock()`, `TerminalPanel.build()`; heap dump | Dua kebocoran (RCA-2). |
| Panel Ekstensi dan marketplace | Source `ExtensionsPanel`, `Marketplace`, `OpenVsx` (timeout 15/30 s) | Pencarian tanpa tindakan pengguna (SEC-1). |
| Panel Cari di jendela kecil | Uji `responsive` dan tangkapan layarnya; source `SearchPanel.build()` | RCA-3. |
| Jendela layar penuh dan keyboard | Source `computeFrames`, `WindowManager::effective`; uji baru `tedit:ime` | RCA-1. |
| Penutupan keyboard oleh LX | `jdb` di `hideSoftInputFromWindow`, `ImeTracker` | Tidak ada pemanggilan dari kode LX selama run. Semua berasal dari uji sendiri. |
| Masukan keyboard ke terminal | Source `TerminalView.onCreateInputConnection` | `TYPE_NULL`, satu jalur Enter (`KEYCODE_ENTER` atau `performEditorAction`), teks composing ditahan sampai di-commit. Tidak ada jalur Enter ganda di kode. |
| Lint dan terjemahan | Lint penuh; pemeriksa placeholder 717 entri; uji mutasi lint | Lint 0 error, 7 warning (sama dengan sebelum putaran ini). |

## Graph `codebase-memory`

Indeks dibuat 12 November 22:03Z, sehingga semua berkas yang diubah berstatus `metadata_changed`. Karena itu keputusan diambil dari source yang dibaca langsung. Setelah perubahan, indeks dibangun ulang (selesai 13 November 18:37:59Z). `check_index_coverage` untuk delapan berkas yang diubah: enam tanpa catatan masalah, `LauncherActivity.kt` parsial di baris 1690, dan `SearchPanel.kt` parsial di baris 411-461 (adapter). Kedua rentang itu tidak bersinggungan dengan perubahan dan sudah dibaca langsung. `trace_path` atas `panel` sesudah perubahan hanya menyisakan `refreshDock`, `registerCommands`, `revealFolder`, dan `revealInExplorer`, yaitu panel yang memang ditampilkan. `computeFrames` hanya dipanggil dari `RootLayout.onLayout`. `detect_changes` tidak tersedia karena folder proyek bukan repo git.

## Temuan audit

| ID | Prioritas | Temuan | Status |
|---|---|---|---|
| A-1 | P1 | Jendela layar penuh tidak menyusut saat keyboard terbuka (RCA-1) | Diperbaiki |
| A-2 | P1 | Listener layout terminal menumpuk di setiap rebuild (RCA-2) | Diperbaiki |
| A-3 | P1 | Rail membuat keenam panel di setiap rebuild; Ekstensi mencari ke jaringan (RCA-2, SEC-1) | Diperbaiki |
| A-4 | P2 | Panel Cari terpotong dan terjepit di jendela kecil (RCA-3) | Diperbaiki |
| A-5 | P3 | Pemeriksa jendela kecil menghitung kontrol di bawah laci modal sebagai bertumpuk | Diperbaiki di uji |
| A-6 | P2 | Uji terminal menulis riwayat bash ke Debian milik pemilik (RCA-4, SEC-5) | Diperbaiki di runner |
| A-8 | P1 | Pemulihan data uji bisa membenamkan preferensi pemilik di `shared_prefs/shared_prefs` (RCA-5, SEC-7) | Diperbaiki di `scripts/ui_test_file_manager.py` |
| A-9 | P1 | Versi pertama perbaikan panel Cari menyembunyikan daftar kosong, dan ROM berhenti memberi boost saat resize (RCA-6) | Diperbaiki |
| A-10 | P2 | Setelan yang diubah di satu jendela Tedit tidak sampai ke jendela Tedit lain (RCA-7) | Diperbaiki |
| A-7 | P3 | Di panel Cari yang sangat sempit (sekitar 110 dp), tombol Aa, ab, dan regex turun satu per baris karena inden 24 dp. Semua bisa dijangkau dan bisa digulir, tetapi memakan tinggi. | Terbuka, kosmetik |

## Regresi historis terminal (bagian 9 prompt)

| Kandidat | Status di versi ini | Bukti |
|---|---|---|
| Keyboard memicu Enter otomatis | Tidak ditemukan di kode | Satu jalur Enter di `TerminalView`. Belum diuji dengan pengetikan Gboard sungguhan di terminal. |
| Prompt atau perintah ganda | Tidak terlihat | `dxterm` 33/33: prompt tetap satu setelah layar menyempit, dan 50 run beruntun masing-masing dengan keluarannya sendiri. |
| Kursor dan buffer tidak sinkron | Tidak terlihat | Uji mesin di `dxterm` (grid, kursor, teks semua baris). |
| Toolbar bertumpuk, berkedip, atau tertutup keyboard | **Terjadi** di jendela layar penuh, sudah diperbaiki | RCA-1, `tedit:ime` 7/7, 50 siklus keyboard. |
| Viewport terpotong | Diperbaiki untuk panel Cari | RCA-3, `responsive` 33/33. |
| White flash atau flicker render | Tidak diukur di putaran ini | Pemasangan ekstensi diukur per frame pada 13 November. |
| Flicker saat pertama dibuka | Tidak diukur di putaran ini | |
| Sesi hilang setelah pindah tab atau pengaturan | Tidak terjadi | `tedit:lang`: sesi bertahan dan menjawab setelah 50 pergantian bahasa. `dxterm`: sesi bertahan saat panel ditutup dan dibuka. |
| Nano atau Vim dengan keyboard terbuka | Sebagian | `top` menggambar dan memulihkan layar (`dxterm`). nano dan vi dengan keyboard terbuka belum diuji di putaran ini. |
| Konflik input, PTY, render, lifecycle | Tidak terlihat | 50 sesi tanpa kebocoran descriptor atau thread. Menutup jendela Tedit mengakhiri shell-nya. |
| Provisioning runtime | Berjalan | Debian milik pemilik siap (`.tedit-ready`), uji Debian di `dxterm` lulus. |
| Resource tidak dibersihkan | Ditemukan di rebuild Tedit, sudah diperbaiki | RCA-2. |
