# Dependensi dan manifest versi

Semua versi dipatok. Tidak ada dependensi yang mengikuti branch atau `latest`.

## Ikut terkirim di APK

| Komponen | Versi | Lisensi | Sumber |
|---|---|---|---|
| Kotlin standard library | 2.1.20 | Apache 2.0 | Maven Central |
| libc++ (static, NDK) | NDK 27.0.12077973, Clang 18.0.1 | Apache 2.0 with LLVM Exceptions | Android NDK |
| Path data Material Icons | dari set Material Icons | Apache 2.0 | Disalin sebagai path vektor di `res/drawable/ic_*.xml` |
| Core LX (`core/`) dan aplikasi | 1.0.0 | Apache 2.0 atau versi sesudahnya | Project ini |
| Ikon Vivid Plasma Themes (24 ikon) | commit `598e24d41b1a040620e81e971020e32fa9480944` | GPL-3.0 | `github.com/L4ki/Vivid-Plasma-Themes`, set Vivid-Dark-Icons. SVG dan SHA-256 tiap berkas di `third_party/vivid-icons` |
| Roboto, Inter, Noto Sans, Noto Serif, Noto Sans Mono, Nunito Sans, JetBrains Mono, Lato, Plus Jakarta Sans, Manrope, Poppins, Montserrat, Open Sans, Work Sans, DM Sans, Figtree, Red Hat Text, Lexend, Atkinson Hyperlegible Next, Exo 2, Rubik, Nunito, Literata | google/fonts commit `e7db6ca603fd4862010383ff17ffd6cc4ffac1c0` | OFL 1.1 | `third_party/fonts/SOURCES.txt` mencatat URL dan SHA-256 tiap unduhan |
| Source Sans 3 | 3.052R | OFL 1.1 | Rilis `adobe-fonts/source-sans` |
| IBM Plex Sans | `@ibm/plex-sans@1.1.0` | OFL 1.1 | Rilis `IBM/plex` |

Tidak ada AndroidX, AppCompat, Compose, atau Qt.

Karena ikon Vivid berlisensi GPL-3.0, APK sebagai satu kesatuan didistribusikan di bawah GPL-3.0. Kode LX tetap Apache 2.0 atau versi sesudahnya, dan Apache 2.0 boleh digabung dengan karya GPL-3.0. Ikon browser, pengelola berkas, dan PDF dari set Vivid tidak diambil karena mirip logo produk lain.

`scripts/fetch-fonts.py` dan `scripts/render-vivid-icons.py` mengunduh dari alamat yang dipatok di atas dan menolak berkas yang SHA-256-nya berbeda dari catatan. Kedua skrip hanya dijalankan saat menyiapkan aset; aplikasi tidak mengunduh apa pun.

Font dan ikon menambah sekitar 2,4 MB pada APK release: 4,28 MB sekarang, sebelumnya 1,9 MB. Lato (dua berkas, 1,3 MB) dan Source Sans 3 (0,85 MB) paling besar karena Reserved Font Name melarang memotong berkasnya, jadi keduanya dikirim utuh.

## Hanya untuk build dan test

| Komponen | Versi | Lisensi | Verifikasi |
|---|---|---|---|
| Android Gradle Plugin | 8.11.1 | Apache 2.0 | Google Maven |
| Gradle | 8.14.3 | Apache 2.0 | Wrapper, `distributionUrl` resmi |
| GoogleTest | 1.15.2 | BSD-3-Clause | `https://github.com/google/googletest/releases/download/v1.15.2/googletest-1.15.2.tar.gz`, SHA-256 `7b42b4d6ed48810c5362c265a17faebe90dc2373c885e5216439d37927f02926` (dicatat saat unduhan pertama, tidak ada checksum resmi) |
| CMake | 3.22.1 (Android), 3.28.3 (host) | BSD-3-Clause | SDK dan paket sistem |
| fontTools | 4.63.0 | MIT | Memotong font variabel menjadi Regular dan Medium statis |
| Pillow | 10.2.0 | MIT-CMU | Menyimpan render ikon sebagai WebP lossless |
| Chrome headless shell dari Playwright | build 1243 | BSD-3-Clause | Merender SVG Vivid ke PNG 192 px |

## Kandidat dari brief yang tidak dipakai

| Kandidat | Alasan |
|---|---|
| Qt Core, Qt Quick, QML, Qt SVG | Lihat ADR-001 |
| SQLite | Lihat ADR-002 |
| fmt | Format teks di core cukup dengan `std::to_chars` dan `snprintf` |
| Google Benchmark | `benchmarks/core_bench.cpp` mengukur median dengan `std::chrono` tanpa library tambahan |

SBOM dalam format CycloneDX ada di `docs/research/sbom.cdx.json`.
