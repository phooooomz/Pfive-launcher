# Analisis akar masalah, 14 November 2026

Perangkat uji: Infinix HOT 60 5G (X6726B), Android 15, layar 720x1600, keyboard Gboard, bahasa ponsel id-ID. LX dipasang sebagai APK debug. Setiap run instrumentasi berjalan di antara cadangan dan pemulihan data LX (`build/maint-1113/run-instr.py`), dan log LX direkam dengan `adb logcat -v threadtime` selama run.

Putaran ini mengikuti dua permintaan pemilik: pilihan bahasa Inggris dan Mandarin di Tedit, lalu pemeliharaan menurut `PROMPT_PEMELIHARAAN_LX_LAUNCHER.md`. Di tengah pekerjaan pemilik menambahkan satu keluhan: terminal Tedit tertutup keyboard saat Tedit layar penuh.

## RCA-1. Terminal Tedit tertutup keyboard saat jendelanya layar penuh (P1, laporan pemilik)

**Gejala.** Tedit dalam keadaan layar penuh, terminal dibuka, lalu keyboard muncul. Prompt dan baris tombol terminal ada di bawah keyboard dan tidak terlihat.

**Reproduksi.** Uji baru `-e only tedit:ime` (`TeditKeyboardChecks.kt`) membuka Tedit, menyalakan shell di panel terminal, mengatur jendela ke `fullscreen`, `maximized`, dan `floating`, memanggil `TerminalPanel.focusInput()`, lalu membandingkan tepi bawah panel terminal di layar dengan tepi atas keyboard (`WindowInsets.Type.ime()`).

**Bukti sebelum perbaikan** (`instr-ime-jdb.txt`):

```
INFO fullscreen: keyboard top 952 px, terminal bottom 1398 px, panel bottom 1470 px, terminal 148 dp tall
FAIL Tedit fullscreen: ... the keyboard covers 518 px of the terminal panel
INFO maximized: keyboard top 952 px, terminal bottom 810 px, panel bottom 882 px
PASS Tedit maximized ...
```

**Akar masalah.** `LauncherActivity.computeFrames` menghitung dua area untuk jendela. `windows` adalah kanvas di atas keyboard dan dipakai jendela maximized dan mengambang. `safe` adalah layar di dalam bilah sistem dan dipakai jendela layar penuh (`WindowManager::effective` di `core/src/window_manager.cpp` mengembalikan `full_` untuk `Fullscreen`). Hanya `windows` yang dipotong tinggi keyboard, sehingga jendela layar penuh tetap setinggi layar dan bagian bawahnya tertutup keyboard. Tedit menaruh terminal di bagian bawah, jadi terminal yang paling dulu hilang.

**Perbaikan.** Saat keyboard terbuka, `safe.bottom` dipotong ke tepi atas keyboard, dengan batas bawah 120 dp dari atas seperti `windows`. Inti C++ tidak berubah: perubahan `safe` sudah memicu `NativeCore.setViewport` dan publikasi ulang batas jendela.

**Verifikasi** (`instr-ime-after.txt`, `instr-ime-after2.txt`): fullscreen kini "terminal bottom 830 px, panel bottom 902 px" dengan keyboard di 952 px. Maximized dan mengambang tidak berubah. Keyboard diturunkan dan dinaikkan 50 kali dalam jendela layar penuh: setiap kali panel kembali di atas keyboard dalam 1,5 detik, dan shell masih menjawab `echo lx-ime-ok-$((6*7))` di akhir. Tangkapan layar `ime/fullscreen.png` memperlihatkan prompt dan baris tombol tepat di atas keyboard.

**Catatan dari pelacakan.** Pada run pertama keyboard sempat turun sendiri. `ImeTracker` mencatat `HIDE_STATE_HIDDEN_FORWARD_NAV` setiap kali skrip uji menekan HOME (LX adalah Home dan memakai `stateHidden`), dan aktivitas yang dibuka uji di task terpisah kehilangan fokus jendela. `jdb` dipasang ke proses LX dengan breakpoint di `InputMethodManager.hideSoftInputFromWindow`. Kesepuluh pemanggilan yang tertangkap berasal dari uji sendiri (`TeditKeyboardChecks.keyboardDown`). Tidak ada kode LX yang menutup keyboard selama run itu. Uji kini membuka LX sebagai Home dan menunggu fokus jendela.

## RCA-2. Memori bertambah setiap Tedit membangun ulang tampilannya (P1)

**Gejala.** Uji baru `-e only tedit:lang` mengganti bahasa Tedit 50 kali sambil shell berjalan. Heap Java setelah GC naik dari 10.664 ke 19.604 KiB antara pergantian ke-10 dan ke-50, dan pada run kedua dari 11.940 ke 21.224 KiB. Pergantian bahasa memakai jalan yang sama dengan pergantian tema, ukuran antarmuka, dan rebuild karena kelas ukuran jendela berubah.

**Bukti.** Dua heap dump (`Debug.dumpHprofData` setelah pergantian ke-10 dan ke-50) dikonversi dengan `hprof-conv` dan dibaca dengan pembaca HPROF kecil (`scratchpad/hprof.py`) yang menghitung instance per kelas dan mencari jalur kuat terpendek dari GC root, dengan `Reference.referent` diabaikan. Selisih 40 pergantian: +996 `TextView`, +9.804 `Paint`, +7.140 `GradientDrawable$GradientState`, +720 lambda `TerminalPanel$ExtraKeys`, dan +12 `ExtensionsPanel`. Ada dua rantai penahan:

```
TerminalPanel$ExtraKeys <- lambda f$0 <- ListenerInfo.mOnClickListener <- TextView <- ... <- HorizontalScrollView
  <- TerminalPanel$$ExternalSyntheticLambda25 (f$1) <- ArrayList mOnLayoutChangeListeners <- LinearLayout (root panel terminal)
  <- TeditTool.bottom <- root0x3

ExtensionsPanel <- lambda f$0 <- LinkedBlockingQueue$Node.item  (11 dari 17)
ExtensionsPanel <- dijalankan thread tedit-ext (5 dari 17)
```

**Akar masalah 1.** `TerminalPanel.build()` memanggil `root.addOnLayoutChangeListener` pada `root`, yang hidup lebih lama dari rebuild, tanpa pernah melepas listener sebelumnya. Setiap rebuild menambah satu listener yang menahan baris tombol lama beserta drawable-nya, dan semua listener itu ikut berjalan di setiap layout panel terminal.

**Akar masalah 2.** `TeditTool.buildRail()` dan pengalih panel di jendela kecil memanggil `panel(id)` untuk keenam panel hanya demi ikon dan judulnya. Akibatnya setiap rebuild membuat ulang Berkas, Cari, Kontrol Sumber, Jalankan, Ekstensi, dan Masalah. `ExtensionsPanel` di konstruktornya langsung menampilkan marketplace, dan bila izin marketplace sudah diberikan ia mencari ke open-vsx.org. Pencarian itu masuk antrean `Marketplace.io` (3 thread, timeout 15 s connect dan 30 s baca). Panel yang sudah di-`dispose` (`gen=2`, `loading=true` di heap dump) tetap tertahan oleh tugas yang mengantre sampai giliran jaringannya selesai.

**Bukan kebocoran.** `TeditTool` kedua di heap dump tampak tertahan lewat listener `FileJobs`. BFS ulang tanpa referensi statis `FileJobs` menunjukkan ia tetap terjangkau lewat `LauncherActivity` kedua yang masih hidup (task Home, `mDestroyed=0`). Ini jendela Tedit sungguhan, bukan sisa.

**Perbaikan.**
- `TerminalPanel` menyimpan listener di `keysFit` dan melepas yang lama sebelum memasang yang baru. Listener tidak lagi keluar lebih awal saat tinggi tidak berubah, sehingga panel yang dibangun ulang langsung mendapat keadaan baris tombol yang benar.
- `TeditTool.PANEL_FACES` memberi ikon dan judul keenam panel. Rail dan pengalih tidak lagi membuat panel, jadi panel baru dibuat saat pertama kali tampil.
- Tugas pencarian `ExtensionsPanel` keluar lebih dulu bila `gen` sudah berubah (panel dibuang atau pencarian diganti). `gen` kini `@Volatile` karena dibaca dari thread `tedit-ext`.

**Verifikasi.** Heap setelah 10 dan 50 pergantian: 7.164 dan 7.164 KiB. Heap dump sesudah perbaikan: `TextView` 208 ke 208, `Paint` 2.688 ke 2.688, `ExtraKeys` 2 ke 2, `ExtensionsPanel` 0 ke 0, selisih shallow total 2 KiB. Pergantian bahasa di thread utama: median 72 ke 52 ms, terlama 121 ke 77 ms.

## RCA-3. Panel Cari terpotong dan terjepit di jendela kecil (P2)

**Gejala.** `-e only responsive` gagal 4 dari 33 untuk Tedit. Pada 320x320 dan 360x480 dp, tombol regex dan Filter "cut (0% shown)". Pada 280x240 dp daftar hasil hanya 4 dp. Pada 240x180 dp kontrol header dilaporkan bertumpuk dengan laci panel.

**Bukti bahwa ini cacat lama, bukan regresi.** APK sebelum perbaikan putaran ini (`build/maint-1114/A-before.apk`) gagal dengan pesan yang sama pada keadaan ponsel yang sama. Cacat ini baru terlihat karena sesi Tedit di ponsel sekarang membuka panel Cari. Di putaran 13 November panelnya tertutup, sehingga hasilnya 33/33.

**Akar masalah.**
- Deret pilihan (Aa, ab, regex, Filter) adalah `LinearLayout` satu baris dengan inden 24 dp, di panel selebar sekitar 115 dp. Dua kontrol terakhir terdorong keluar.
- Kolom panel Cari menyusun area kueri dengan tinggi alami di atas daftar berbobot 1. Di laci jendela 280x240, isi panel hanya 109 dp, sedangkan area kueri butuh sekitar 122 dp.
- Pada 240x180, laci panel adalah lapisan modal di atas scrim yang bisa diketuk. Pemeriksa uji membandingkan kontrol di bawah scrim dengan isi laci, padahal kontrol itu tidak bisa disentuh selama laci terbuka. Ini kesalahan pemeriksa.

**Perbaikan.**
- `FlowRow` menghormati ukuran tetap anak (tombol ikon). Deret pilihan panel Cari memakai `FlowRow`, sehingga turun ke baris berikutnya.
- Area kueri dibungkus `ScrollView` di dalam kolom `SearchPanel.Column`. Bila daftar hasil kurang dari 48 dp, area kueri dipendekkan dan bisa digulir, tetapi tidak di bawah satu baris (40 dp). Daftar hasil yang kosong tidak diberi tinggi minimum. Versi pertama menyembunyikannya, dan itu memicu RCA-6.
- `ResponsiveChecks` mengabaikan kontrol yang tergambar di bawah lapisan modal, yaitu lapisan yang bisa diklik dan menutup minimal 95% bingkai induknya.

**Verifikasi.** `responsive` 33/33 dengan panel Cari terbuka (`instr-r3-responsive.txt`), dan Tedit 0 masalah di keempat ukuran.

## RCA-4. Uji terminal menulis ke Debian milik pemilik (temuan proses uji)

**Gejala.** Uji `dxterm` dan `tedit:lang` hari ini berjalan di bash Debian. Penanda `no_backup/linux/debian/.tedit-ready` dibuat pukul 01:27:20 waktu ponsel. Catatan sesi kerja tidak berisi satu pun perintah antara 13 November 06:01 dan 14 November 01:37, jadi Debian itu dipasang pemilik sendiri, sepuluh menit sebelum permintaan ini.

**Dampak.** `run-instr.py` hanya mencadangkan `files` dan `shared_prefs`, bukan `no_backup`. Perintah uji (166 baris, misalnya `echo cycle-N` dan `cd -- '.../dxterm-check-…' && …`) masuk ke `/home/dracos/.bash_history` milik pemilik. Baris pertama (`ls`) adalah milik pemilik.

**Penanganan.** Riwayat dicadangkan ke scratchpad, setiap baris setelah baris pertama dipastikan cocok dengan pola perintah uji, lalu berkasnya dikembalikan ke satu baris itu dengan izin `600` seperti semula. Debian tidak dihapus.

**Pencegahan.** `build/maint-1113/run-instr.py` kini membaca riwayat itu sebelum mencadangkan data LX dan menulisnya kembali setelah pemulihan bila isinya berubah. Penulisan dilakukan dalam dua langkah, karena `adb exec-in` mengakhiri perintah jarak jauh begitu masukannya habis: percobaan pertama hanya sempat menulis berkas sementara. Hasilnya dibaca ulang dan dibandingkan byte demi byte. Dua baris dari run `tedit:ime` yang lolos sebelum perbaikan ini sudah dibuang dengan cara yang sama. Catatan bahwa Debian di Infinix milik pemilik juga disimpan di memori pengujian.

## RCA-5. Preferensi pemilik terbenam di `shared_prefs/shared_prefs` setelah pemulihan uji (P1, integritas data, alat uji)

**Gejala.** Saat pembersihan akhir, `shared_prefs` LX hanya berisi satu folder `shared_prefs`. Kelima preferensi pemilik (`lx_bridge.xml`, `lx_embed.xml`, `lx_files.xml`, `perf_img_scale.xml`, `tedit.xml`) ada di dalamnya dan tidak terbaca LX. Akibatnya LX menganggap LX Bridge belum dipasangkan (tidak ada `lxdb` yang berjalan, entri terakhir di `lx-bridge.log` pukul 02:50:29), sesi Tedit pemilik tidak terbaca, dan pengaturan File Explorer kembali ke bawaan.

**Bukti.** Folder bersarang ber-mtime 02:54. Log run `windows` D-025022: `restore_data` dimulai pukul 02:54:16.664 (`am force-stop; rm -rf files shared_prefs; mv restore.tmp/files files; mv restore.tmp/shared_prefs shared_prefs`), sedangkan `ActivityManager` mencatat `Start proc 31034:com.lxlauncher ... for content provider` pukul 02:54:16.596 dan `for restart` pukul 02:54:16.798.

**Akar masalah.** `restore_data` (`scripts/ui_test_file_manager.py`) menjalankan hapus dan pindah sebagai perintah `run-as` terpisah. `lxdb` tetap hidup setelah LX dihentikan, dan permintaannya ke provider LX menyalakan LX lagi dalam hitungan milidetik. LX lalu menulis preferensi dan membuat `shared_prefs` baru sebelum `mv` berjalan, sehingga `mv` memindahkan salinan ke dalam folder itu. Pemeriksaan sesudahnya hanya membaca header `files/layout.lxl`, yang memang benar, jadi kesalahan ini lolos. Setiap run berikutnya mencadangkan dan memulihkan struktur bersarang yang sama.

**Penanganan.** Struktur bersarang dicadangkan (`prefs-rescue/shared_prefs-nested-031029.tar`). Kelima berkas dipindahkan kembali dan LX dihentikan dalam satu perintah. Setelah HOME, `lxdb` tersambung lagi pukul 03:11:02, dan header tata letak tetap `LXL1 a77a950d 3968`. `lx_bridge.xml` yang dikembalikan adalah versi terakhir yang ditulis LX (02:46), sehingga tokennya tidak tertinggal.

**Perbaikan.**
- Pertukaran kini berjalan dalam satu `run-as sh -c` dengan `mv -T`. Di toybox ponsel, `mv -T` gagal dengan rc=1 bila tujuan sudah ada, alih-alih memindahkan ke dalamnya (diuji di ponsel).
- Pemeriksaan sesudahnya mencocokkan setiap nama langsung di `files/` dan `shared_prefs/` dari tar dengan isi ponsel, dan gagal bila ada `files/files` atau `shared_prefs/shared_prefs`.
- `save_data` menolak mencadangkan data yang sudah bersarang, supaya kesalahan ini tidak lagi terbawa dari run ke run.

**Verifikasi.** Tiga siklus `save_data` dan `restore_data` berturut-turut di ponsel: kelima preferensi dan keenam entri `files` kembali utuh, tanpa folder bersarang.

**Dampak pada pengukuran.** Semua run setelah 02:54 berjalan tanpa preferensi pemilik: `windows` C-final-025427, profil C1-C3 dan D1-D2, serta run akhir pertama (suite lengkap, `ext`, `windows`). Hasil fungsionalnya tetap berlaku untuk LX dengan pengaturan bawaan. Pengukuran resize dan suite lengkap diulang dalam kondisi pemilik (lihat laporan performa dan regresi).

## RCA-6. Resize Tedit melambat karena daftar hasil Cari disembunyikan (P1, regresi dari putaran ini, diperbaiki)

**Gejala.** Pada sesi Tedit pemilik (panel Cari terbuka), resize Tedit di build C 51,8 sampai 60,6% frame lewat anggaran dengan p90 thread UI sekitar 18 ms. Build sebelum perubahan panel Cari (D) 15,4 sampai 22,0% dengan p90 sekitar 11 ms. Polanya sama dalam empat run C dan tidak pernah muncul di run A, B, atau D.

**Bukti.** Log setiap run dipotong ke rentang resize Tedit, lalu baris `PowerHalMgrImpl: hdl, pid` (boost CPU ROM Transsion di proses LX) dihitung:

| Build | Isi panel Cari | Boost | Frame lewat anggaran |
|---|---|---|---|
| D | seperti sebelum putaran ini | 24, 24 | 22,0%, 15,4% |
| C | `ScrollView`, `FlowRow`, daftar disembunyikan saat kosong | 0, 0 | 60,6%, 51,8% |
| E | C tanpa `ScrollView` | 0, 0 | 49,9%, 50,9% |
| F | C dengan daftar tetap tampil | 24 | 20,7% |
| G | C tanpa `FlowRow` | 0 | 56,5% |

Hanya F yang kembali normal. Pemicunya adalah `list.visibility = GONE`, bukan `ScrollView` atau `FlowRow`. Dengan profiler terpasang dan boost yang sama, kerja thread utama C dan D setara (siklus CPU berselisih 0,9%). Jadi biaya kodenya tidak bertambah. Yang hilang adalah boost ROM, dan tanpa boost frame melambat.

**Akar masalah.** Perbaikan RCA-3 menyembunyikan daftar hasil yang kosong supaya area kueri mendapat ruang. Heuristik boost ROM Transsion (`ScrollIdentify`, `PowerHalMgrImpl`) ternyata berhenti memberi boost selama resize Tedit bila daftar itu tidak terlihat. Mekanisme di dalam ROM tidak bisa dibaca dari LX, tetapi hubungan sebab-akibatnya terbukti oleh eksperimen F dan G.

**Perbaikan.** Daftar hasil tetap `VISIBLE`. Saat kosong, `SearchPanel.Column` tidak memberinya tinggi minimum 48 dp, jadi area kueri mendapat ruang lebih dulu dan daftar boleh mengecil sampai 0 dp di jendela yang sangat kecil. `render()` meminta layout ulang hanya saat daftar berubah dari kosong ke berisi atau sebaliknya.

**Pelajaran.** Pembanding resize di ponsel ini harus memeriksa jumlah boost ROM per run. Pemeriksaan ini kini bagian dari ringkasan `summ.sh` di scratchpad.

## RCA-7. Bahasa atau tema yang dipilih di satu Tedit tidak sampai ke Tedit lain (P2, cacat fitur bahasa, diperbaiki)

**Gejala.** Tangkapan layar uji bahasa untuk `en` dan `in` memperlihatkan Tedit yang masih berbahasa Mandarin. Tedit yang tampil di layar adalah jendela Tedit milik instance LX lain, yaitu task Home. Jendela itu dibangun saat bahasa sedang zh-CN dan tidak pernah berganti lagi.

**Akar masalah.** Setiap `TeditTool` membuat `TeditPrefs` sendiri, dan daftar listener-nya milik objek itu. Perubahan yang ditulis satu jendela tersimpan di SharedPreferences yang sama, tetapi hanya jendela itu yang mendapat `onPref`. LX membolehkan satu jendela Tedit per workspace. Jadi bahasa yang dipilih di Tedit pada workspace 1 tidak sampai ke Tedit pada workspace 2. Karena `TeditLocaleContext` hanya berganti lewat `onPref`, Tedit itu tetap memakai bahasa lama bahkan setelah dibangun ulang. Tema dan setelan lain mengalami hal yang sama sejak sebelum putaran ini.

**Perbaikan.** Daftar listener `TeditPrefs` kini satu untuk semua objek (`sharedListeners` di companion, hanya thread utama). Setiap jendela tetap melepas listener-nya di `onDestroy`.

**Pencegahan.** `tedit:lang` mendapat cek "a language chosen in one Tedit reaches every open Tedit", yang memakai dua objek `TeditPrefs` terpisah. Uji itu kini membuka LX sebagai Home, sehingga tangkapan layarnya memperlihatkan jendela yang benar-benar diuji.

**Cara ditemukan.** Cek teks di uji membaca isi `TextView` jendela yang diuji dan lulus. Baru tangkapan layar sungguhan yang memperlihatkan bahwa jendela di layar adalah jendela lain. Bukti visual tetap perlu di samping cek program.

