# Perubahan pemeliharaan, 14 November 2026

Semua perubahan dibangun dengan `./gradlew --offline :app:assembleDebug :app:assembleDebugAndroidTest` dan diuji di Infinix (lihat `REGRESSION_TEST_REPORT.md`). Folder proyek bukan repo git, jadi daftar ini disusun dari berkas yang disunting.

## Fitur: bahasa Tedit

| Berkas | Perubahan | Alasan dan dampak |
|---|---|---|
| `app/src/main/kotlin/com/lxlauncher/tedit/TeditLocale.kt` (baru) | `TeditLocaleContext`: `ContextThemeWrapper` atas activity LX yang `getResources()`-nya memakai `createConfigurationContext` untuk bahasa pilihan. `setLanguage` menggantinya. | Hanya Tedit yang berganti bahasa. LX tetap memakai bahasa ponsel. |
| `tedit/TeditPrefs.kt` | Setelan `general.language` di kategori Umum: Bahasa ponsel, Bahasa Indonesia, English, 中文（简体）. | Nama bahasa ditulis dalam bahasanya sendiri supaya selalu terbaca. |
| `tedit/TeditTool.kt` | `ctx` kini `TeditLocaleContext`. `onPref` menangani `general.language` lewat jalan yang sama dengan tema: semua view dibangun ulang, sesi terminal tetap. | Semua teks Tedit berganti sekaligus tanpa menutup berkas. |
| `res/values/strings_tedit.xml`, `res/values-in/strings_tedit.xml` | Tiga string baru: `tedit_s_language`, `tedit_s_language_d`, `tedit_v_lang_system`. | |
| `res/values-zh-rCN/strings_tedit.xml` (baru) | 697 string dan 20 plural (hanya `other`, sesuai aturan plural Mandarin). `fm_open_in_tedit` dan `fm_open_tedit_folder` sengaja tidak diterjemahkan karena tampil di File Explorer. | Dibuat dengan skrip yang meng-escape `& < > ' "`. Placeholder dicocokkan 717/717. |
| `app/lint.xml` (baru) | Mengabaikan `MissingTranslation` hanya untuk pesan yang diawali `is not translated in "zh"`, dan `UnusedTranslation` hanya untuk Mandarin. | String LX sendiri memang tidak diterjemahkan ke Mandarin. Uji mutasi membuktikan string yang lupa diterjemahkan ke Indonesia tetap gagal lint. |
| `app/build.gradle.kts` | `bundle { language { enableSplit = false } }`. | Bila LX pernah dirilis sebagai app bundle, ketiga bahasa tetap ada di APK dasar, karena Tedit berganti bahasa di dalam aplikasi. |

## Perbaikan

| Berkas | Fungsi | Perubahan | RCA |
|---|---|---|---|
| `LauncherActivity.kt` | `computeFrames` | Saat keyboard terbuka, `safe.bottom` dipotong ke tepi atas keyboard (minimal 120 dp dari atas). Jendela layar penuh kini berhenti di atas keyboard. | RCA-1 |
| `tedit/Terminal.kt` | `TerminalPanel.build` | Listener tata letak baris tombol disimpan di `keysFit`, dan yang lama dilepas sebelum yang baru dipasang. Listener juga tidak lagi berhenti lebih awal saat tinggi tidak berubah, sehingga panel yang baru dibangun langsung menyembunyikan barisnya di panel yang terlalu pendek. | RCA-2 |
| `tedit/TeditTool.kt` | `PANEL_FACES`, `buildRail`, `refreshDock` | Ikon dan judul panel dari tabel tetap. Rail dan pengalih jendela kecil tidak lagi membuat panel. | RCA-2, SEC-1 |
| `tedit/ExtensionsPanel.kt` | `search`, `gen` | Tugas pencarian yang mengantre keluar bila panelnya dibuang atau pencariannya diganti. `gen` menjadi `@Volatile`. | RCA-2, SEC-1 |
| `tedit/TeditPrefs.kt` | `listeners`, `sharedListeners` | Daftar listener satu untuk semua jendela Tedit, sehingga bahasa, tema, dan setelan lain yang diubah di satu Tedit sampai ke Tedit di workspace lain. | RCA-7 |
| `tedit/TeditUi.kt` | `FlowRow.onMeasure` | Anak dengan ukuran tetap (tombol ikon) diukur `EXACTLY` sesuai LayoutParams-nya. Pemakai lama hanya memberi tombol tanpa LayoutParams, jadi perilakunya tidak berubah. | RCA-3 |
| `tedit/SearchPanel.kt` | `build`, `Column`, `render` | Deret pilihan memakai `FlowRow`. Area kueri ada di `ScrollView` dan dipendekkan bila daftar hasil berisi tetapi kurang dari 48 dp, minimal 40 dp. Daftar kosong tetap tampil tanpa tinggi minimum, dan layout diminta ulang hanya saat daftar berubah dari kosong ke berisi atau sebaliknya. | RCA-3, RCA-6 |

## Uji dan alat uji

| Berkas | Perubahan |
|---|---|
| `app/src/androidTest/.../TeditLanguageChecks.kt` (baru) | `-e only tedit:lang`: LX dibuka sebagai Home; perubahan dari satu objek `TeditPrefs` harus sampai ke listener objek lain; setiap pilihan bahasa dengan Settings, panel Berkas, dan terminal terbuka; tidak boleh ada teks, hint, atau label dari bahasa lain. LX harus tetap memakai bahasa ponsel. Lalu 50 pergantian dengan shell berjalan: sesi bertahan dan menjawab, heap tidak naik lebih dari 4 MB. Pilihan pemilik dikembalikan. |
| `app/src/androidTest/.../TeditKeyboardChecks.kt` (baru) | `-e only tedit:ime`: terminal dan baris tombolnya harus berada di atas keyboard pada jendela layar penuh, maximized, dan mengambang, lalu 50 siklus keyboard di layar penuh. LX dibuka sebagai Home dan uji menunggu fokus jendela. Tangkapan layar disimpan di cache LX (`ime/`). |
| `app/src/androidTest/.../ResponsiveChecks.kt` | Kontrol di bawah lapisan modal (bisa diklik, menutup minimal 95% bingkai induknya, tergambar di atasnya) tidak dibandingkan dengan isi lapisan itu. |
| `app/src/androidTest/.../SelfTestInstrumentation.kt` | Grup `tedit:lang` dan `tedit:ime`. |
| `scripts/ui_test_file_manager.py` | `restore_data` menukar folder dalam satu `run-as sh -c` dengan `mv -T`, lalu memeriksa setiap nama di `files/` dan `shared_prefs/` serta tidak adanya folder bersarang. `save_data` menolak data yang sudah bersarang. `top_entries` dan `nested_in` baru (RCA-5). |
| `build/maint-1113/run-instr.py` (sejak 15 November di `scripts/device/run-instr.py`) | Riwayat bash Debian milik pemilik dibaca sebelum run dan dikembalikan sesudahnya, lalu diverifikasi. |

## Dokumentasi

| Berkas | Perubahan |
|---|---|
| `docs/architecture/tedit.md` | Bagian Bahasa baru; catatan bahwa panel dibuat saat pertama kali tampil. |
| `CHANGELOG.md`, `README.md` | Bahasa Tedit, terminal di atas keyboard dalam layar penuh, Tedit lebih cepat dibuka, panel Cari di jendela kecil, Tedit tidak menghubungi Open VSX tanpa panel Ekstensi dibuka. |
| `docs/reports/pemeliharaan-2026-11-14/` | Tujuh laporan putaran ini. Laporan 13 November tidak diubah. |

## Yang tidak diubah

Inti C++ (`core/`), dracxterm (`third_party/dracxterm`), izin, endpoint jaringan, dan dependensi tidak berubah.

## Pembersihan project, 15 November 2026

Atas permintaan pemilik, isi yang tidak dipakai project dihapus permanen (project turun dari 4,8 GB ke 72 MB):

- Keluaran build dan cache yang dibuat ulang otomatis: `build/`, `app/build`, `app/.cxx`, `.gradle`, `.kotlin`, `scripts/__pycache__`. Di dalam `build/` ikut terhapus log dan tangkapan layar uji lama, APK pembanding (`build/dist`, APK di `build/maint-1113` dan `build/maint-1114`, termasuk build A sampai J di laporan ini), cadangan data LX lama (`build/backup`, `lx-data-*.tar`), cache font, dan tarball googletest.
- Berkas lepas yang tidak dirujuk: `readme.md` dan satu berkas catatan tugas lama lainnya, `image.png`, `image-1.png`, `image-2.png` (salinan identik potongan screenshot), dan `.impeccable/review` (screenshot ulasan desain 23 Oktober).

Skrip bantu uji ponsel dipindah dari `build/` ke `scripts/device/`, dengan path di dalamnya disesuaikan: `run-instr.py`, `install.sh`, `ab-resize.sh`, `jank-cat.sh`, dan `ui.py`. Keluaran `ab-resize.sh` dan `jank-cat.sh` kini ditulis ke `build/device/`.

Verifikasi setelah pembersihan: build dari nol `assembleDebug`, `assembleDebugAndroidTest`, dan `lintDebug` berhasil dalam 589 detik dengan 0 error lint. APK debug hasilnya berisi 262 berkas yang sama dengan build J yang terpasang di ponsel, dengan ukuran isi terkompresi 48.244.976 dan 48.244.965 byte. Berkas APK lama 1,2 MB lebih besar karena ruang kosong sisa pengemasan bertahap. APK uji berisi semua kelas uji. `scripts/test-host.sh` lulus dengan 227 dan 226 uji, dan googletest diunduh lewat URL di `CMakeLists.txt`. Keluaran verifikasi itu dihapus lagi sesudahnya. Rujukan ke path `build/` di laporan-laporan ini adalah catatan sejarah; berkasnya sudah tidak ada.

