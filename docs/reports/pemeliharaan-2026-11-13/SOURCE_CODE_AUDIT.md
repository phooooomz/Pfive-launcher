# Audit source code, 13 November 2026

## Inventaris

| Komponen | Ukuran | Isi |
|---|---|---|
| Aplikasi Kotlin (`app/src/main/kotlin`) | 44.536 baris, 121 berkas | Launcher, jendela, alat bawaan, File Explorer, Tedit, LX Bridge, klien `lxdb` |
| Uji instrumentasi (`app/src/androidTest`) | 5.753 baris | `SelfTestInstrumentation` dan grup uji `-e only ...` |
| Core C++ (`core/`) | 6.695 baris | model tata letak, jendela, persistensi, JSON, arsip |
| `lxdb/` | 3.927 baris | daemon shell lewat Binder mentah |
| JNI aplikasi (`app/src/main/cpp`) | 959 baris | jembatan core, arsip, XZ |
| VHDP (`vhdp/`) | 16.219 baris | runtime Debian untuk Tedit |
| Skrip uji (`scripts/`) | 4.586 baris | uji UI di ponsel, uji host, pembanding TextMate |

Titik masuk: `LxApp` (Application), `LauncherActivity` (Home), `EmbedBinderProvider` (pintu `lxdb` ke LX), `BridgeSetupService` (pairing LX Bridge), dan daemon `lxdb` yang dijalankan LX lewat adbd ponsel.

## Sumber kebenaran yang dibaca

- `PROMPT_PEMELIHARAAN_LX_LAUNCHER.md`, termasuk dua gejala dari pemilik: keyboard tertutup saat area keyboard disentuh, dan resize serta pembukaan yang terasa berat.
- Riwayat: `CHANGELOG.md` (tiga putaran perbaikan keyboard sebelumnya, 1, 3, dan 8 sampai 9 November), `docs/audits/stability-audit-2026-11-09.md`, dan catatan pengujian.
- Bukti dari ponsel pemilik: buffer crash (dua crash Tedit 12 November), `dumpsys input_method` (riwayat 100 kejadian keyboard), `dumpsys package dexopt`, `no_backup/lx-bridge.log`, dan log yang direkam selama setiap uji.
- Konfigurasi build: `app/build.gradle.kts` (packaging, lint), `AndroidManifest.xml` (`extractNativeLibs`), dan isi APK hasil build (`unzip -v`).

Graph `codebase-memory` dipakai untuk mencari simbol dan pemanggil (misalnya `forward`, `holdShape`, `showTop`, `releaseShape` di `EmbeddedAppTool`). Cakupan indeksnya diperiksa dengan `check_index_coverage`: `EmbeddedAppTool.kt` dan `EmbedChecks.kt` tidak punya catatan masalah, sedangkan `LauncherActivity.kt` terindeks sebagian di baris 1685, jadi bagian itu dibaca langsung. `detect_changes` tidak tersedia karena folder proyek bukan repo git.

## Alur yang ditelusuri

| Alur | Dari tindakan sampai hasil | Metode |
|---|---|---|
| Ketukan pada jendela aplikasi | `LauncherActivity.dispatchTouchEvent` → `keyboardAfterPress` → `WindowLayer.onInterceptTouchEvent` / `WindowFrame` → `EmbeddedAppTool` (listener `TextureView`, `forward`) → `IEmbedService.injectMotion` → `lxdb` `EmbedService::injectMotion` dan `focus` → display aplikasi | Baca source C++ dan Kotlin, log `WindowManager` (fokus berpindah di setiap ketukan), uji probe |
| Keyboard aplikasi | `appClaimsKeyboard` / `keyboardToApp` (`FLAG_ALT_FOCUSABLE_IM`) → kebijakan IME lokal di display aplikasi (`setImePolicy`) → riwayat IMMS | Baca source, `dumpsys input_method`, uji probe EditText dan WebView |
| Bentuk tampilan jendela aplikasi | `sizeChanged` → `holdShape` → `scheduleResize` → `resizeTask` → `showTop` / `releaseShape` → `frameDrawn` | Baca source, uji pemetaan baru |
| Resize jendela | `WindowFrame.handleDrag` → `follow` → `NativeCore.resizeWindow` → `place` → `measureChildWithMargins` (pembatas konten) → gambar | FrameMetrics, `simpleperf` |
| Peluncuran `lxdb` | `LxBridge.launchOn` → `AdbLink.shell` → `ShellHelper.command` → `linker64` → `lxdb` `main` → `EmbedBinderProvider` → `LxBridge.attach` | Log LX Bridge, uji tangan di ponsel, isi APK |
| Resize Tedit | `rootFrame.onSizeChanged` → `TeditTool.onResized` → `build()` / lebar panel / mode tinggi | Baca source, FrameMetrics |
| Pemasangan ekstensi | `ExtensionStore.install` | Baca source |
| Pemasangan Debian | `LinuxRuntime` (unduhan, SHA-256) → `Xz` → `TarUnpacker` | Baca source |
| Cadangan dan pemulihan data uji | `save_data` / `restore_data` | Log, perbandingan header tata letak |

## Temuan

| ID | Prioritas | Lokasi | Ringkas | Status |
|---|---|---|---|---|
| F-1 | P0 | `ShellHelper.kt`, `LxBridge.kt`, manifest | `lxdb` gagal dinyalakan karena pustaka terkompresi di APK (RCA-1) | Diperbaiki, diverifikasi |
| F-2 | P1 | `scripts/ui_test_file_manager.py`, `ui_test_app_windows.py` | Pemulihan data uji kalah balapan dan menimpa tata letak pemilik (RCA-4) | Diperbaiki, diverifikasi |
| F-3 | P1 | `EmbeddedAppTool.kt` | Skala sentuhan dan skala gambar bisa berbeda setelah `holdShape` disusul `showTop` (RCA-3) | Diperbaiki, uji pemetaan lulus |
| F-4 | P1 | keyboard di Brave (RCA-3) | Brave menutup keyboard sendiri setelah ketukan pertama, dua kali pada 12 November | Belum direproduksi dengan Brave, terbuka |
| F-5 | P2 | 7 fading edge (`Settings`, `Shell.column`, pemilih, path File Explorer, dua deret bilah kaca) | Salinan isi layar setiap frame di GPU per ubin (RCA-2) | Diperbaiki, diukur |
| F-6 | P2 | `TeditTool.onResized` | Rebuild semua view di tengah tarikan resize (RCA-2) | Diperbaiki, diukur |
| F-7 | P2 | build debug | Kode LX selalu di interpreter setelah proses dimulai ulang; Android 15 tidak mengompilasi aplikasi `debuggable` (RCA-5) | Rekomendasi, butuh keputusan pemilik |
| F-8 | P3 | `AndroidManifest.xml` | Komentar `extractNativeLibs` keliru | Dibetulkan |
| F-9 | P3 | `README.md`, `ADR-008-lxdb.md`, `tedit.md`, `ui_test_lxdb.sh` | Masih menyebut `lxdb` dijalankan dari dalam APK | Dibetulkan |

## Yang tidak diperiksa di putaran ini

Pemeriksaan putaran ini terarah pada dua gejala pemilik dan jalur yang menyentuhnya. Fungsi berikut tidak dibaca ulang baris demi baris: sebagian besar File Explorer (`FileJobs`, `SafBackend`, `LocalBackend`), Pembersih, widget, Tedit di luar resize dan crash yang tercatat (TextMate, LSP, Git, Terminal), VHDP, dan core C++ di luar yang sudah diaudit 9 November. Hasil uji otomatis untuk bagian itu ada di `REGRESSION_TEST_REPORT.md`, tetapi lulus uji tidak sama dengan sudah diaudit.
