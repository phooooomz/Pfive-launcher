# Tedit

Tedit adalah editor teks dan kode di dalam LX, dibuka sebagai jendela LX dengan jenis `tedit`. Sejak 11 November 2026 setiap berkas yang diketuk di File Explorer terbuka di Tedit: teks untuk disunting, gambar sebagai pratinjau, berkas lain sebagai halaman rincian. Menu "Buka dengan" ke aplikasi lain sudah tidak ada; Bagikan dan ekstrak arsip tetap ada.

Tampilannya mengikuti VS Code dan gambar referensi pemilik: header dengan logo `</>`, rail aktivitas di kiri, panel samping yang menempel, tab dengan ikon jenis berkas, warna sintaks Dark+ dan Light+, serta status bar. Brief lengkapnya ada di `DESIGN.md`.

## Struktur layar

| Bagian | Isi | Berkas |
|---|---|---|
| Header | Menu utama, logo, nama Tedit, pemilih folder kerja (layar lebar saja), Cari, Run (tombol hijau), Settings, Lainnya | `tedit/TeditTool.kt` |
| Rail aktivitas | Berkas, Cari, Kontrol Sumber, Jalankan dan Debug, Ekstensi, Masalah; Terminal di bawah | `TeditTool.kt` |
| Panel samping | Isi panel yang dipilih rail, lebarnya bisa diseret | `ExplorerPanel.kt`, `SearchPanel.kt`, `ScmPanel.kt`, `RunPanel.kt`, `ExtensionsPanel.kt`, `Problems.kt` |
| Area editor | Satu atau dua grup; tiap grup punya tab, path, bilah cari dan ganti, lalu editor atau pratinjau | `EditorGroup.kt`, `CodeEditView.kt` |
| Terminal | Terminal dracxterm di bawah editor (layar lebar) atau selebar jendela (ponsel); mengisi jendela kalau editor tidak kebagian ruang | `Terminal.kt`, `DxShell.kt` |
| Baris tombol tambahan | Tab, panah, simbol, urungkan, ulangi, sembunyikan keyboard; tampil saat keyboard layar terbuka | `TeditTool.kt` |
| Status bar | Akhir baris, encoding, bahasa, branch, jumlah masalah, tugas yang berjalan, lalu status simpan, indentasi, baris dan kolom | `TeditTool.kt` |
| Lapisan | Menu bertingkat, dialog, palet perintah, halaman Settings, pesan singkat, bilah seleksi | `TeditUi.kt`, `CommandPalette.kt`, `SettingsPage.kt` |

Tedit tidak memakai tema LX. Paletnya sendiri (`TeditTheme.kt`) diambil dari `DESIGN.md`: dasar `#0D111C`, panel `#141A29`, aksen ungu `#8657F5`. Ada Gelap, Terang, Tengah malam, dan Hitam OLED, dengan lima warna aksen. Setiap warna teks diperiksa minimal 4,5:1 terhadap permukaan tempatnya berada. Gradasi ungu ke biru hanya dipakai di logo, sesuai keputusan pemilik.

Ikon antarmuka digambar sebagai garis (`TIcon.kt`) supaya ketebalannya sama. Ikon jenis berkas (`FileIcons.kt`) memakai lencana berwarna (JS, TS, K, Py) atau simbol (`{}` untuk JSON, `<>` untuk markup).

## Ukuran layar

- Lebar di bawah 600 dp (ponsel tegak): rail tetap tampil, panel menempel selebar sekitar 40% jendela (bisa diseret, disimpan), terminal selebar jendela. Setelah berkas dibuka dari panel, panel menutup supaya editor memakai seluruh lebar; Settings > Tampilan > Setelah membuka berkas bisa membuatnya tetap terbuka.
- Ukuran mengikuti VS Code di ponsel: teks antarmuka 13 sp, kode 13 sp dengan tinggi baris 1,4, header 42 dp, rail 44 dp, tab 32 dp dengan teks 12 sp dan tombol tutup 24 dp, status bar 24 dp, baris pohon 32 dp. Tombol dan baris daftar 40 dp, tombol ikon sebaris 36 dp. Kerapatan Ringkas mengurangi semuanya sekitar 4 dp, dan Skala antarmuka (0,85× sampai 1,3×) memperbesar atau memperkecil seluruhnya.
- Lebar 600 dp ke atas: pemilih folder kerja ada di header, lebar panel memakai pengaturan Lebar panel samping.
- Jendela di bawah 300 dp: panel menutupi editor sebagai laci dengan baris pengganti rail.
- Tinggi di bawah 420 dp: path disembunyikan dan header dipendekkan. Di bawah 230 dp (biasanya saat keyboard terbuka dan ponsel mendatar) header dan status bar menyingkir. Ini dilakukan tanpa membangun ulang tampilan supaya keyboard tetap tersambung ke editor.
- Split: atas-bawah saat ponsel tegak, kiri-kanan saat ponsel mendatar. Arah ini mengikuti orientasi perangkat, bukan bentuk jendela, karena keyboard membuat jendela tegak menjadi lebih lebar daripada tinggi.

## Hubungan antar fitur

- File Explorer mengirim `open\0<backend>\0<ref>` atau `folder\0<backend>\0<ref>` lewat `Shell.openTool`. Kalau jendela Tedit sudah ada, `LauncherActivity.openTool` meneruskannya ke `TeditTool.handleExtra`.
- Semua akses berkas lewat lapisan berkas File Explorer (`Backend`, `FsNode`, `FileJobs`). Jadi Tedit menjangkau tempat yang sama: penyimpanan lokal dengan akses semua file, folder SAF yang diizinkan, dan folder shell selama LX Bridge berjalan. Satu berkas dari pemilih Android dibuka sebagai URI konten.
- Folder kerja dipakai bersama oleh Berkas, Cari, Kontrol Sumber, Jalankan, dan Quick Open. Mengganti folder memberi tahu semuanya lewat `TeditTool.setWorkspace`.
- Perintah (`Commands.kt`) satu sumber untuk menu utama, palet, dan pintasan, sehingga nama, ketersediaan, dan alasan "tidak tersedia" selalu sama. Item yang tidak tersedia tampil redup dengan alasannya.
- Pintasan Tedit didahulukan atas pintasan LX: `ToolView.onShortcut` dipanggil sebelum `handleShortcut`. Ctrl+W menutup tab, bukan jendela.
- Masalah dihitung dari dokumen terbuka dan tampil di panel Masalah dan status bar. Kontrol Sumber memberi tanda di gutter editor dan huruf status di pohon berkas.

## Fungsi tombol dan navigasi

| Tombol | Hasil |
|---|---|
| Menu utama | Menu bertingkat: berkas baru, buka berkas, buka folder, buka terbaru, simpan, simpan sebagai, simpan semua, tutup berkas, tutup folder, pengelola ruang kerja, lalu Edit, Seleksi, Tampilan, Navigasi, Jalankan, Preferensi, Tentang |
| Cari (header) | Membuka panel Cari |
| Run | Menjalankan skrip shell yang terbuka, atau tugas dari `.tedit/tasks.json` |
| Settings | Halaman Settings dengan 12 kategori |
| Lainnya | Palet perintah, terminal, split, bungkus baris, ekstensi, masalah, settings |
| Ikon rail | Membuka panelnya; mengetuk ikon panel yang sedang terbuka menutup panel |
| Tab | Ketuk untuk pindah. Tekan lama lalu geser untuk mengurutkan, tekan lama tanpa geser untuk menu (tutup, tutup lainnya, tutup di kanan, sematkan, geser, split, salin path, tampilkan di Berkas). Titik berarti belum disimpan |
| Path | Bagian folder membuka folder itu di panel Berkas |
| Item status bar | Akhir baris dan encoding membuka pilihannya, bahasa membuka mode bahasa, branch membuka Kontrol Sumber, masalah membuka panel Masalah, indentasi membuka menu indentasi, baris dan kolom membuka Ke baris |

Pintasan utama: Ctrl+N, Ctrl+O, Ctrl+S, Ctrl+Shift+S, Ctrl+Alt+S, Ctrl+W, Ctrl+Shift+T, Ctrl+Tab, Ctrl+P, Ctrl+Shift+P atau F1, Ctrl+G, Ctrl+F, Ctrl+H, F3, Ctrl+Shift+F, Ctrl+B, Ctrl+\`, Ctrl+\\, Alt+Z, Shift+Alt+F, F5, Shift+F5, Ctrl+,. Di editor: Ctrl+Z/Y, Ctrl+/, Ctrl+] dan Ctrl+[, Alt+panah, Shift+Alt+panah bawah, Ctrl+Shift+K, Ctrl+L, Ctrl+D, Ctrl+Enter, Ctrl+M (Tab memindah fokus). Daftar lengkap ada di Preferensi > Pintasan keyboard.

## Editor

- Teks disimpan per baris (`TextBuffer.kt`). Hanya baris yang terlihat yang digambar, dan baris panjang hanya kolom yang terlihat. Kolom dihitung dalam sel: tab ke kelipatan lebar tab, karakter CJK dan emoji dua sel, karakter kontrol tampil sebagai gambar Unicode-nya.
- Undo (`EditHistory`) mengelompokkan ketikan dan hapus di satu tempat dalam satu langkah; kata yang direvisi keyboard (composing) juga satu langkah. Status tersimpan dilacak per langkah, jadi undo kembali ke titik simpan membuat dokumen bersih lagi. Batas memori undo bisa diatur.
- Keyboard layar memakai `EditorInput.kt` dengan offset karakter seperti EditText. Keyboard fisik: panah, Home/End, PgUp/PgDn, seleksi Shift, lompat kata Ctrl, dan semua pintasan di atas. Mouse: seret untuk memilih, klik kanan untuk menu, roda untuk gulir, Ctrl+roda untuk zoom.
- Sentuhan: ketuk menaruh kursor, ketuk dua kali memilih kata, tekan lama memilih kata dan menampilkan bilah Potong, Salin, Tempel, Pilih semua. Pegangan seleksi bisa diseret. Cubit untuk zoom. Scrollbar dan minimap bisa diseret.
- Pewarnaan (`Syntax.kt`, `Highlighter.kt`) per baris dengan state yang dibawa ke baris berikutnya, untuk sekitar 30 bahasa. Baris di atas batas panjang (20.000 karakter) tidak diwarnai.
- Kurung dan kutip berpasangan, Enter menjaga indentasi dan masuk satu tingkat setelah kurung buka, kurung tutup menyesuaikan indentasi, komentar baris atau blok, indentasi dan sebaliknya, duplikat, pindah, hapus baris.
- Encoding: UTF-8, UTF-8 dengan BOM, UTF-16 LE dan BE, ISO 8859-1, Windows 1252. Akhir baris LF, CRLF, CR dideteksi dan dipertahankan; berkas campuran diberi tahu.
- Simpan ke path lokal menulis berkas sementara di samping target lalu mengganti namanya, jadi berkas tidak pernah setengah tertulis. Tempat lain ditulis langsung. Kalau berkas berubah di disk sejak dibuka, Tedit bertanya sebelum menimpa.
- Perubahan yang belum disimpan disalin ke penyimpanan privat LX sesaat setelah mengetik, dan dipulihkan setelah jendela ditutup atau proses LX dihentikan.
- Berkas di atas batas (16 MB, bisa 2 sampai 64) dibuka hanya baca, bagian awalnya saja.

## Panel

- Berkas: pohon dimuat per folder, urut menurut nama, jenis, atau tanggal, folder di atas, saring nama, berkas tersembunyi, baru, ganti nama, duplikat, salin, potong, tempel, kompres, ekstrak, ekspor, impor, salin path, properti, pilih beberapa, hapus dengan konfirmasi. Operasi panjang memakai job File Explorer.
- Cari: huruf besar kecil, kata utuh, regex, sertakan dan kecualikan, hanya berkas terbuka; hasil per berkas dengan cuplikan dan sorotan; Ganti semua dengan konfirmasi. Berkas terbuka diganti sebagai satu langkah undo; berkas tertutup ditulis langsung dan peringatannya disebut di dialog.
- Kontrol Sumber: branch, perubahan staged dan belum, diff per berkas, riwayat 200 commit. Hanya baca: kotak pesan, Commit, Stage, dan Buang perubahan tampil nonaktif dengan alasannya.
- Jalankan dan Debug: status, progres, waktu, keluaran, Hentikan, Mulai ulang, Bersihkan, riwayat 20 run, `.tedit/tasks.json`. Debug ditampilkan sebagai tidak tersedia dengan alasannya.
- Ekstensi: tujuh paket bahasa bawaan, pemformat JSON, dan pemeriksaan masalah, masing-masing bisa dimatikan. Tidak ada katalog unduhan.
- Masalah: JSON, XML, dan kurung yang tidak berpasangan di dokumen terbuka; ketuk untuk melompat.
- Panel Cari dan Ekstensi memakai `PanelColumn` (`TeditUi.kt`): bagian atas (kueri, tab, kategori) bisa digulir, daftar yang berisi tetap setinggi minimal 48 dp, dan kalau ruangnya terlalu sempit untuk keduanya bagian atas mendapat seluruh tinggi. Daftar tidak pernah disembunyikan, karena daftar yang disembunyikan membuat ROM Infinix berhenti memberi boost CPU saat jendela diubah ukurannya.
- Panel baru dibuat saat pertama kali tampil. Ikon dan nama di rail serta di pengalih jendela kecil diambil dari `TeditTool.PANEL_FACES`, jadi membangun ulang tampilan tidak membuat panel. Sampai 14 November 2026 rail membuat keenam panel di setiap rebuild; panel Ekstensi lalu mencari ke Open VSX, dan di jaringan lambat pencarian itu mengantre sambil menahan panel yang sudah dibuang.

## Bahasa

Settings > Umum > Bahasa memilih bahasa Tedit: Bahasa ponsel, Bahasa Indonesia, English, atau 中文（简体）. LX di sekitarnya tetap memakai bahasa ponsel.

- `TeditTool.ctx` adalah `TeditLocaleContext` (`tedit/TeditLocale.kt`): jendela LX dengan temanya, tetapi `getResources()` memberi resource untuk bahasa pilihan. Semua teks Tedit dibaca lewat konteks ini, termasuk pesan dari `DocStore` dan pemeriksa masalah.
- Saat bahasanya berubah, Tedit membangun ulang semua tampilannya lewat jalan yang sama dengan pergantian tema (`onPref`). Berkas, tab, kursor, dan sesi terminal tetap. Listener `TeditPrefs` dibagi semua jendela, sehingga Tedit di workspace lain ikut berganti.
- Teks ada di `res/values/strings_tedit.xml` (Inggris), `values-in/` (Indonesia), dan `values-zh-rCN/` (Mandarin sederhana). String File Explorer `fm_open_in_tedit` dan `fm_open_tedit_folder` tidak diterjemahkan ke Mandarin karena tampil di LX.
- `app/lint.xml` mengabaikan `MissingTranslation` hanya untuk Mandarin, sebab string LX sendiri memang tidak diterjemahkan ke sana. String yang belum ada di bahasa Indonesia tetap gagal lint. Mandarin juga tidak masuk `locales_config.xml`, supaya pengaturan bahasa per aplikasi di Android tidak menawarkan Mandarin untuk seluruh LX.
- `bundle.language.enableSplit = false` menjaga ketiga bahasa tetap ada di APK dasar kalau LX pernah dirilis sebagai app bundle.
- Uji: `-e only tedit:lang` (`TeditLanguageChecks.kt`) membaca setiap teks, hint, dan label aksesibilitas di jendela Tedit untuk setiap pilihan bahasa, dengan Settings, panel Berkas, dan terminal terbuka. Uji itu juga mengganti bahasa 50 kali selagi shell berjalan.

## Terminal dan tugas

Terminal Tedit adalah terminal dracxterm 1.1.3 (`third_party/dracxterm`, GPL-3.0; asal dan perubahannya di `README.lx.md` folder itu). Mesinnya dari C++ dracxterm apa adanya: pseudo terminal (`forkpty`), parser ANSI/VT, buffer layar dengan scrollback, seleksi, cari, dan UTF-8, dibangun sebagai `libxterm.so`. `TerminalView` dracxterm menggambar kisi dan menerima input (keyboard layar, keyboard fisik, pinch, tekan lama untuk memilih dan menyalin), tanpa dasbor `xset`, dengan font kode Tedit dan ukuran dari setelan terminal Tedit. Baris tombol tambahan mengikuti dracxterm (panah, Ctrl dan Alt yang menahan, Esc, Tab, Home, End, PgUp, PgDn, ukuran teks, cari, tempel, hapus mundur) dan menepi kalau panel terlalu rendah.

Shell-nya dipilih `DxShell.kt`, ditulis mengikuti `Bootstrap.kt` dracxterm:

- Debian terpasang: `bash -l` di Debian lewat VHDP sebagai pengguna dracos (`HOME=/home/dracos`), dengan `~/.bashrc` dracxterm (prompt `DRAC⚔TERM > (merek ponsel)`), shim `sudo`, `su`, `fakeroot`, `whoami`, `id`, dan `logname` di `/usr/local/sbin` dan `/usr/local/bin`, serta zona waktu ponsel. Semua ditulis ulang setiap sesi dimulai, seperti di dracxterm.
- Debian belum ada: `ash` BusyBox 1.38.0 dari APK dracxterm, dengan prompt dracxterm, perintah BusyBox lebih dulu di PATH.
- BusyBox ditolak ponsel (kebijakan seccomp aplikasi membunuhnya dengan SIGSYS, status 159; terjadi di Infinix uji) atau setelan Shell memilih Android: `sh` Android (mksh), dengan prompt dracxterm.

Setiap shell melapor ke Tedit setiap kali prompt kembali lewat escape app-control dracxterm (`OSC 5391;tedit:prompt=<status>`, dari `PROMPT_COMMAND` di bash dan dari PS1 di ash dan mksh). bash juga menyertakan perintah yang tidak ditemukan (`;missing=<nama>`). Mesin hanya menyimpan muatan terakhir, jadi satu laporan memuat semuanya.

Run dan tugas berjalan di terminal, seperti di VS Code: setiap run mendapat sesi sendiri ("Jalankan: nama"), perintah `cd -- '<folder>' && <perintah>` diketik setelah laporan prompt pertama, dan laporan prompt berikutnya membawa status keluarnya. Program bisa dijawab dari keyboard. Hentikan mengirim Ctrl+C, sekali lagi setelah 1,5 detik, lalu menutup sesinya setelah 3 detik. Saat run selesai, isi sesinya disalin ke panel Run. Sesi run sebelumnya ditutup ketika run baru dimulai, kecuali setelan Bersihkan keluaran dimatikan. Batas waktu (bawaan 10 menit) menghentikan run dengan cara yang sama. Menutup jendela Tedit menutup semua sesinya.

mksh mengosongkan baris perintahnya saat terminal menyempit dan baru menggambarnya lagi pada tombol berikutnya. Panel mengirim Ctrl+L (perintah `redraw` mksh) hanya bila sesinya mksh dan sedang di prompt tanpa ketikan baru.

## Linux (Debian) lewat VHDP

Terminal, Run, dan server bahasa memakai Debian 13 (trixie) yang berjalan lewat VHDP (`vhdp/`, VHDP 1.0.2 dari dracxterm 1.1.3, Apache-2.0). VHDP menelusuri program tamu dengan ptrace dan seccomp, menerjemahkan path ke rootfs, lalu memuat ELF tamu lewat loader userland, sehingga tidak perlu root dan tidak perlu exec dari penyimpanan aplikasi (Android 10 ke atas melarangnya). Program di dalamnya melihat dirinya sebagai root, tetapi berjalan sebagai pengguna Android milik LX.

- Build: CMake LX membangun `phdp` dan `vhdp_loader` untuk arm64-v8a dan x86_64, lalu memberinya nama `libphdp.so` dan `libvhdp-loader.so` seperti lxdb. `android:extractNativeLibs="true"` di manifest membuat keduanya ada sebagai berkas di folder pustaka native, satu-satunya tempat aplikasi boleh exec. Dengan atribut itu build menyimpan semua pustaka terkompresi di APK, jadi lxdb dijalankan dari salinan di folder yang sama (`ShellHelper.library`), bukan dari dalam APK.
- Image: `assets/linux/debian-trixie-aarch64-pd-v4.37.0.tar.xz` (35 MB, SHA-256 `9bd3b19f…`) untuk ARM64. Untuk x86_64, Tedit mengunduh `debian-trixie-x86_64-pd-v4.29.0.tar.xz` dari rilis GitHub proot-distro (SHA-256 `4b8f33b8…` sesuai daftar GitHub). Versi v4.37.0 x86_64 hanya ada di easycli.sh, yang menolak unduhan non-browser. Salinan dengan nama yang sama di folder Download, atau berkas yang dipilih lewat pemilih Android, juga diterima asal checksum-nya cocok.
- Pemasangan (`tedit/linux/LinuxRuntime.kt`): cek ruang (6× ukuran arsip), XZ Embedded lewat JNI (`third_party/xz-embedded`, `xz_jni.cpp`), pembongkar tar di Kotlin (`TarUnpacker.kt`), konfigurasi, uji jalan pertama, lalu penanda siap. Konfigurasi menulis `resolv.conf` (8.8.8.8, 1.1.1.1), `hosts`, `nsswitch.conf`, titik `/dev` untuk proyeksi minimal VHDP, dan `apt.conf.d/99tedit` (tanpa user sandbox, tanpa pty untuk dpkg). Semuanya ada di `no_backup/linux/debian`.
- Perintah: `/system/bin/sh -c 'echo __TEDIT_PID__$$; exec "$@" 3>>events'` lalu `libphdp.so run --engine rootless --uid 0 --gid 0 --proc host --dev minimal --cwd … --clear-env -e … --bind … <rootfs> -- <argv>`. Penyimpanan internal dan folder kerja di-bind ke path yang sama di dalam Debian. Ctrl+C mengirim SIGINT ke shell tamu dan anak-anaknya. Server bahasa memakai perintah yang sama tanpa baris PID, karena stdout mereka adalah protokol.
- Terminal memakai `bash -l` di pseudo terminal (lihat Terminal dan tugas). Lingkungannya dari dracxterm (`TERM=xterm-256color`), bukan `TERM=dumb` milik tugas lama dan server bahasa.

## Ekstensi dari Open VSX

- `tedit/ext/OpenVsx.kt`: pencarian per kategori dan urutan, detail, README, ikon (cache memori dan disk), unduhan .vsix lewat HTTPS dengan cek SHA-256 dari registry. Panel baru menghubungi open-vsx.org setelah pengguna mengizinkan (setelan Privasi `extensions.marketplace`: ask, on, off).
- `ExtensionStore.kt`: membongkar `extension/` dari .vsix ke `files/tedit/extensions/<publisher.name>`, dengan batas 20.000 berkas dan 300 MB, dan menolak nama yang keluar folder.
- Yang dipakai: tema warna (`VsTheme.kt`, termasuk `include` dan .tmTheme), grammar TextMate (`TextMate.kt`), bahasa beserta `language-configuration.json` (`ExtLanguages.kt`), dan snippet (`Snippets.kt`, tabstop dengan Tab). Grammar dari ekstensi menggantikan pewarnaan bawaan untuk bahasa yang sama.
- Yang tidak dijalankan: JavaScript ekstensi (`main`/`browser`). Ekstensi yang mewakili server bahasa yang dikenal (Python, clangd, Go, Rust, YAML, Bash) mengarahkan Tedit ke preset server itu.

### Grammar TextMate

`TextMate.kt` mengikuti vscode-textmate: stack rule dibawa dari baris ke baris, setiap baris diberi `\n` di ujungnya, dan di setiap posisi pola yang paling awal cocok menang. Yang ikut didukung: `patterns` di dalam capture (teks capture ditokenisasi ulang), nama scope dengan `$1` dan `${1:/downcase}`, `injections` milik grammar dan grammar lain yang menyuntik lewat `injectTo`, `\A` hanya di baris pertama dokumen, serta aturan vscode-textmate untuk rule yang tidak bergerak (rule yang terbuka dan tertutup di tempat yang sama tetap terbuka sampai akhir baris).

Pola Oniguruma diterjemahkan ke `java.util.regex`. Di Android mesinnya ICU, yang punya perilaku sendiri:

- `^` di ICU juga cocok setelah `\n` penutup teks, jadi `^` ditulis sebagai `(?:^(?=[\s\S]))`.
- Di dalam `(?!...)` dan `(?=...)`, ICU memindahkan `\G` ke batas lookaround bila bound region transparan, dan lookbehind ICU tidak bisa melihat ke belakang awal region (tiket ICU 6067). Pencarian dari anchor memakai region tanpa transparent bounds di ICU, dan dengan transparent bounds di Java. Satu probe saat runtime menentukan mana yang dipakai.
- ICU menolak kuantifier pada lookaround, jadi panggilan `\g<...>` terdalam (ekspansi tiga tingkat) ditulis `[^\s\S]`, bukan `(?!)`.
- Spasi dan komentar mode `(?x)` dibuang saat penerjemahan, `(?~X)` menjadi `(?:(?!X)[\s\S])*`, `*`/`+` di dalam lookbehind diberi batas 50, dan `\p` tanpa kurung kurawal dibaca sebagai huruf.

Dari 5.628 pola di 84 grammar bawaan VS Code, 5 tetap ditolak ICU: kondisional `(?(...)...)`, lookbehind yang berisi backreference, dan nama grup ganda. Rule yang memakainya dilewati dan dicatat sekali di log `LxTextMate`.

### Warna di latar

Lexer bawaan Tedit cukup cepat untuk berjalan saat editor menggambar. Grammar TextMate bisa butuh beberapa milidetik per baris (grammar C++ VS Code sekitar 3 ms di Waydroid), jadi `Highlighter` mengerjakannya di thread latar `tedit-highlight`, seperti VS Code. Baris yang state awalnya sudah diketahui, misalnya baris yang sedang diketik, tetap diwarnai langsung selama anggaran 6 ms per frame belum habis, dengan batas 20 ms per baris. Baris lain digambar dengan warna lamanya, atau polos, sampai hasil dari latar tiba. Hasil itu dikirim bertahap setiap 50 ms, lalu semua editor yang menampilkan dokumen digambar ulang. Setiap edit menaikkan nomor versi sehingga pekerjaan lama berhenti dan hasilnya dibuang. Di latar, satu baris boleh memakan sampai 500 ms sebelum dipotong. Grammar yang baru dipakai (polanya belum dikompilasi) juga mulai dari latar.

## Server bahasa

`tedit/lsp/LspClient.kt` adalah klien JSON-RPC dan LSP (sinkronisasi teks penuh, posisi UTF-16). `LspManager.kt` menyalakan satu server per preset dan folder di Debian saat dokumen bahasanya dibuka, mengirim teks penuh 350 ms setelah ketikan berhenti, dan mematikan server saat jendela ditutup. Dokumen di atas 30.000 baris tidak dikirim. Hasilnya dipakai di tiga tempat:

- Diagnosa digambar sebagai garis bergelombang dan ikut tampil di panel Masalah.
- Saran (`Completion.kt`) menggabungkan item server, snippet, dan kata di berkas, lalu tampil di lapisan mengambang tanpa merebut keyboard. Daftar itu hanya muncul karena ketikan dan tertutup saat panel, menu, atau halaman lain menutupi editor.
- Info simbol, ke definisi (F12), dan format dokumen memakai server bila ada.

Preset ada di `LspServers.kt`: pylsp (ditambah pyflakes, pycodestyle, dan autopep8, karena paket Debian hanya menyarankannya), clangd, gopls, rust-analyzer, typescript-language-server, bash-language-server, server JSON, CSS, dan HTML, serta yaml-language-server.

## Keterbatasan

- Sebelum Debian dipasang, Run hanya menjalankan skrip shell. Android tidak membawa interpreter Python, Node, dan lainnya.
- Di mksh, baris perintah yang lebih panjang dari lebar terminal bergulir ke samping (tanda `<` atau `>`), bukan turun ke baris berikutnya.
- Debug tidak tersedia.
- Kontrol Sumber hanya membaca repositori di penyimpanan internal, tidak di folder dari pemilih Android. `.gitignore` dibaca untuk pola umum (bintang, dua bintang, garis miring, negasi); submodule dilewati; daftar berkas untracked berhenti di 40.000 berkas.
- Pemformat hanya untuk JSON. Pemeriksaan masalah hanya JSON, XML, dan kurung. Tidak ada pelengkapan kode, lipat kode, multi-kursor, dan pewarnaan pasangan kurung.
- JavaScript dan CSS di dalam berkas HTML tidak diwarnai.
- Pembaca layar mendengar nama berkas editor, tetapi belum bisa membaca isi baris kode.
- JavaScript ekstensi VS Code tidak dijalankan. Lima pola grammar bawaan VS Code tidak bisa dijalankan di ICU (lihat Grammar TextMate). Pada lompatan lebih dari 1.500 baris, pewarnaan dari grammar ekstensi dimulai ulang 200 baris di atas tujuan, dan grammar ekstensi baru berwarna sesaat setelah baris tampil.
- Grammar yang disuntikkan lewat `injectTo` hanya bekerja pada bahasa yang diwarnai grammar TextMate. Bahasa yang masih memakai lexer bawaan Tedit tidak menerimanya.
- Debian hanya untuk ARM64 dan x86_64. Program di dalamnya bisa membaca berkas privat LX (lihat threat model).
- Saat ponsel mendatar, jendela LX pendek; Tedit menyembunyikan header dan status bar, tetapi ruang editornya tetap sempit.

## Hasil uji (11 November 2026)

Diuji di Waydroid Android 13 dengan layar 720×1600 pada 320 dpi, keyboard AOSP (LatinIME). Belum diuji di Infinix pada sesi ini.

- `-e only tedit` (`app/src/androidTest/.../TeditChecks.kt`): 25 dari 25 lulus. Isinya buffer, undo termasuk revisi keyboard, encoding, akhir baris, cari, pewarnaan, deteksi bahasa, diff, pembaca git (repositori yang ditulis byte demi byte, dan folder uji dengan pack dan delta, 33 commit), pemeriksaan JSON dan XML, pemformat, simpan atomik, lalu satu suntingan sungguhan lewat jendela Tedit yang disimpan dengan Ctrl+S. Putaran terakhir setelah folder uji dihapus: 24 dari 24 dengan izin akses semua berkas, 21 dari 21 tanpa izin itu (cek yang membutuhkannya dilewati dengan keterangan).
- Suite bawaan: 45 dari 49 lulus. Empat yang gagal butuh Android 14 (LX Bridge dan layanan penyiapannya), sama seperti sebelum Tedit. `-e only fm` 4 dari 4, `-e only maintenance` 7 dari 7. Lint tanpa galat. Uji C++ di komputer: 227 lulus, dan 226 lulus di bawah sanitizer.
- Angka dari log `LxTedit`: jendela dibangun 84 ms, App.kt (775 B) terbuka 25 ms, log 7,8 MB dengan 120.001 baris terbuka 285 ms dengan heap Java 41 MB, status git 82 sampai 174 ms, cari di 18 berkas 80 ms, simpan 5 ms, tugas Hello selesai 98 ms.
- Diperiksa dengan tangkapan layar: buka dari File Explorer, ketik lewat keyboard layar, baris tombol tambahan, folder kerja dari penyimpanan internal, pohon berkas dengan tanda git, Cari, Kontrol Sumber, Jalankan, Terminal, Masalah, Settings dengan tema Terang, palet perintah, split, gambar, berkas biner, berkas besar, pemulihan setelah `am force-stop`, dialog simpan saat menutup tab, dan posisi mendatar.

## Hasil uji di Infinix (12 November 2026)

Infinix HOT 60 5G (X6726B), Android 15, 720×1600 pada 320 dpi, bahasa sistem Indonesia, LX Bridge aktif, layar 60 Hz. Ponsel ini memang sibuk di latar (load average 13 sampai 18), jadi angka frame bisa bergeser beberapa milidetik antar putaran.

- `-e only tedit`: 24 dari 24 lulus (cek repositori git dari folder uji dilewati karena folder itu tidak ada di ponsel).
- `-e only teditload` (`TeditStress.kt`, baru): 20 dari 20 lulus. Isinya 4.000 suntingan acak dan undo/redo dibanding model string, pewarnaan inkremental dibanding pewarnaan dari nol untuk 12 bahasa, semua lexer pada 3.000 baris acak, cari dibanding pemindaian regex, cari di latar belakang, encoding bolak-balik, lima berkas besar, mengetik 600 karakter, 40 tab, cari di 3.000 berkas, 100.000 baris terminal, 400 perintah acak, folder yang bertahan melewati ganti tema dan buka ulang, dan 12 kali buka-tutup jendela.
- `-e only fm` 8 dari 8 dan suite bawaan 49 dari 49 lulus. Lint tanpa galat.
- Fuzz perintah: 48 perintah berbeda, 400 kali, tanpa crash. Perintah clipboard, simpan, buka lewat pemilih Android, dan Run dikecualikan karena menyentuh hal di luar Tedit.
- Tampilan diperiksa dengan tangkapan layar: panel Berkas, editor selebar layar, terminal dengan keyboard terbuka, empat tema (Terang, Tengah malam, Hitam OLED, Gelap), dan font sistem 1,3×.

| Pengukuran | Sebelum diperbaiki | Sesudah |
|---|---|---|
| Frame JSON 3,3 MB dalam satu baris | 777 ms | 9 sampai 15 ms |
| 100.000 baris di terminal | 66 detik, satu frame 38 detik | 0,33 sampai 0,42 detik, frame terlama sekitar 150 ms |
| Pindah tab di antara 40 tab | 100 ms per frame | 20 sampai 26 ms |
| Cari "finished" di code.kt (30.000 baris) | 131 ms | 25 ms |
| Cari di big.log (110.000 baris) | 154 ms | 38 ms |
| Cari di huge.log (16 MB pertama dari 44 MB) | 568 ms | 187 sampai 214 ms, di thread lain saat mengetik di kolom cari |
| Bagian main thread untuk status bar saat mengetik | 27% | 5% |

Angka lain dari putaran terakhir: membuka code.kt 0,5 detik, big.log (10 MB) 0,49 detik, huge.log 0,67 detik dan hanya baca, menggulir pelan semua berkas besar 9 sampai 15 ms per frame, 600 ketikan 0,8 sampai 1,2 ms main thread per tombol, jendela Tedit terbuka ulang 150 sampai 250 ms (350 sampai 530 ms bila sesi memulihkan folder dan beberapa tab). Setelah tab ditutup, heap Java kembali ke angka sebelum berkas dibuka (selisih 0,0 MB untuk big.log, huge.log, min.json, dan wide.txt), dan 12 kali buka-tutup jendela tidak meninggalkan thread.

## Hasil uji Debian, ekstensi, dan server bahasa (12 November 2026)

Di Waydroid. Debian 10/10, ekstensi 9/9, mesin grammar 23/23, klien LSP 11/11, pylsp sungguhan 4/4, uji Tedit 24/24, uji beban 20/20. Warna grammar sama dengan vscode-textmate untuk setiap karakter di 121 berkas dari 30 bahasa. Rinciannya, beserta cara mengulang uji pembanding, ada di `docs/testing/test-report.md`.

## Masalah yang ditemukan dan diperbaiki selama pengujian

Di Infinix, 12 November 2026:

- Saat LX Bridge aktif, semua path di luar penyimpanan bersama dialihkan ke shell, termasuk folder data LX sendiri yang tidak bisa dibaca shell, sehingga menyimpan di sana gagal dengan NOT_FOUND. Folder LX sendiri (data privat dan foldernya di Android/data dan Android/obb) kini dibuka LX langsung.
- Membuat panel Berkas saat folder kerja sudah terbuka memanggil dirinya sendiri tanpa akhir (StackOverflowError). Ini terjadi saat ganti tema, saat ganti kerapatan, dan saat Tedit dibuka ulang dengan folder dari sesi sebelumnya, dan LX ikut tertutup.
- Kursor sesi yang tidak tampil, atau panel split lain, bisa menunjuk baris yang sudah dihapus. Pencocokan kurung lalu membaca di luar buffer dan LX tertutup. Kursor, seleksi, dan teks komposisi kini dijepit ke isi buffer.
- Baris sangat panjang dipindai penuh beberapa kali per baris tampil per frame, dan drawText(String, start, end) menyalin seluruh string lewat JNI pada setiap potongan.
- Terminal memproses keluaran per 4 KB dengan pemotongan baris kuadratik dan menata ulang seluruh scrollback di setiap potongan.
- Strip tab dibangun ulang saat tab aktif berganti, status bar dibangun ulang setiap kali kursor bergerak, panel Cari menggambar ulang hasil di setiap batch, dan pemeriksa masalah menyalin dan memeriksa ulang semua dokumen setiap kali ada perubahan.
- Pemuatan yang dibatalkan karena jendela ditutup dicatat sebagai "open failed".

Yang belum selesai: frame saat mengetik masih 16 sampai 35 ms tergantung beban ponsel, jadi belum selalu masuk 16,7 ms layar 60 Hz. Sisanya kini ada di RenderThread yang menggambar ulang semua baris tampil pada setiap ketikan. Fling secepat mungkin di wide.txt (baris 400 karakter) juga sekitar 30 ms per frame.

Di Waydroid, 11 November 2026:


- Membuka Tedit untuk kedua kali menutup LX karena sesi dipulihkan sebelum semua properti kelas terisi.
- Keyboard membuat jendela tegak lebih lebar daripada tinggi, Tedit menganggapnya mendatar, membangun ulang tampilan, dan editor kehilangan fokus.
- Berkas ISO 8859-1 dibaca sebagai UTF-8 karena karakter terakhirnya dianggap terpotong.
- Gambar dan berkas biner tertahan di "Membuka" setelah termuat.
- Panel Cari tidak tahu folder kerja yang dipulihkan dari sesi, dan kolom pencariannya tergencet di panel sempit.
- Gutter menandai semua baris berkas untracked sebagai tambahan.
