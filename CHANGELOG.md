# Changelog

## 1.0.0, 15 November 2026

LX-LAUNCHER sampai di versi 1.0.0 🎉 Sejak 0.1.1, LX berubah dari launcher bergaya desktop menjadi tempat kerja kecil di ponsel. Sekarang ada editor kode, Linux, pengelola berkas, dan aplikasi Android yang bisa dibuka di dalam jendela.

- ✍️ Tedit, editor kode yang tampilannya mengikuti VS Code. Debian 13 bisa dipasang dari dalam APK tanpa root, dan di ponsel ARM64 tidak perlu internet. Terminalnya terminal dracxterm, ekstensinya dari Open VSX, dan menunya tersedia dalam bahasa Indonesia, Inggris, dan Mandarin.
- 📁 File Explorer menggantikan LX Files. Berandanya menunjukkan isi penyimpanan, ia bisa membuat dan membongkar arsip ZIP dan TAR, dan berkas yang Anda ketuk terbuka di Tedit. Pembersih ikut membantu melegakan ruang.
- 🪟 Aplikasi Android bisa berjalan di jendela LX lewat LX Bridge, tanpa Shizuku, dan jendelanya kembali bekerja setelah ponsel dimulai ulang.
- 🎨 Gaya jendela dari Glassmorphism sampai Retro, sepuluh tema warna, 25 font, ikon Vivid, dan animasi untuk menu Aplikasi.

Versi 1.0.0 diuji di Infinix HOT 60 5G dengan Android 15. Uji bawaan lengkap lulus 51 dari 51, dan uji Tedit, terminal, jendela kecil, ekstensi, keyboard, serta bahasa juga lulus semua.

Gambar-gambarnya ada di README. Di bawah ini perubahannya satu per satu, dari yang terbaru.

Tedit kini bisa dipakai dalam bahasa Inggris dan Mandarin, selain Indonesia. Pilih di Settings Tedit > Umum > Bahasa. Menu, pengaturan, dan pesan Tedit langsung berganti, sedangkan berkas, tab, dan terminal yang sedang berjalan tetap di tempatnya. Pilihan Bahasa ponsel membuat Tedit kembali mengikuti ponsel, seperti bagian LX yang lain. Pilihan itu, juga tema dan setelan Tedit lainnya, kini ikut berlaku di Tedit yang terbuka di workspace lain. Dulu Tedit itu baru berubah setelah ditutup dan dibuka lagi.

Terminal Tedit tidak lagi tertutup keyboard saat jendelanya layar penuh. Dulu jendela layar penuh tetap setinggi layar, jadi prompt dan baris tombol terminal berada di bawah keyboard. Sekarang jendela itu berhenti di tepi atas keyboard, sama seperti jendela yang dimaksimalkan.

Tedit terbuka lebih cepat dan tidak lagi menumpuk memori. Setiap kali Tedit dibuka, diputar, atau diganti tema dan bahasanya, keenam panel samping dulu dibuat ulang walaupun hanya satu yang tampil, dan sebagian tampilan lama tidak pernah dibuang. Di ponsel uji Tedit kini terbuka dalam sekitar 110 ms, dulu sekitar 155 ms. Ganti bahasa 40 kali dulu menambah sekitar 9 MB yang baru kembali saat Android menutup LX. Sekarang nol.

Tedit juga tidak lagi menghubungi open-vsx.org selama panel Ekstensi tidak dibuka. Setelah marketplace diizinkan, Tedit dulu mencari ekstensi setiap kali tampilannya disusun, dan di jaringan lambat pencarian itu mengantre.

Panel Cari dan panel Ekstensi tetap bisa dipakai di jendela kecil. Tombol huruf besar-kecil, kata utuh, ekspresi reguler, dan Filter turun ke baris berikutnya kalau panelnya sempit. Di jendela yang pendek, kolom pencarian, tab, dan kategori bisa digulir, jadi daftar hasilnya tetap kebagian tempat.

Yang belum bisa: terjemahan Mandarin belum diperiksa penutur asli, dan bagian LX di luar Tedit belum tersedia dalam Mandarin.

Diuji di Infinix HOT 60 5G dengan Android 15. Uji bawaan lengkap lulus 51 dari 51. Uji baru untuk bahasa memeriksa keempat pilihan dan 50 pergantian, dan uji baru untuk keyboard menaikkan serta menurunkan keyboard 50 kali di Tedit layar penuh. Keduanya lulus.

Terminal Tedit kini terminal dracxterm. Program layar penuh seperti nano, vi, dan top bisa dipakai, warnanya tampil, dan program yang Anda jalankan bisa dijawab dari keyboard.

- Kalau Debian sudah dipasang, terminal membuka bash di Debian sebagai pengguna dracos, dengan prompt dracxterm dan `sudo` untuk perintah yang butuh root. Sebelum Debian dipasang, terminal memakai shell BusyBox. Ponsel yang menolak BusyBox, seperti Infinix uji, mendapat sh Android.
- Run dan tugas berjalan di terminal, masing-masing di sesinya sendiri, seperti di VS Code. Program yang menanyakan sesuatu bisa langsung dijawab. Hentikan bekerja seperti Ctrl+C, dan setelah run selesai keluarannya tetap tersalin ke panel Run.
- Di bawah terminal ada baris tombol dracxterm: panah, Ctrl dan Alt, Esc, Tab, Home, End, PgUp, PgDn, ukuran teks, cari, dan tempel. Tekan lama untuk memilih dan menyalin teks.
- Di Debian, `make` dengan resep yang sangat panjang dan program yang membuat proses dengan cara Linux terbaru kini selesai dengan benar.

Tedit kini memakai gambar pemilik sebagai ikonnya, di bilah bawah, judul jendela, pencarian, dan menu. Tombol Tedit selalu ada di bilah bawah, tepat di kanan File Explorer, sama besar dan sejajar dengan aplikasi di sebelahnya. Ketuk sekali untuk membuka Tedit; kalau jendelanya sudah terbuka, tombol yang sama memunculkan atau memperkecilnya.

Layar Tedit tidak lagi berkedip saat ekstensi dipasang. Dulu setiap kabar kemajuan unduhan, kira-kira delapan kali per detik, membuat gambar setiap ekstensi di daftar hilang sesaat. Di ponsel uji gambar-gambar itu berkedip 17 sampai 26 kali selama satu pemasangan, sekarang nol.

Semua alat LX kini tetap rapi di jendela sekecil yang LX izinkan.

- File Explorer di jendela yang pendek dan sempit memindah nama folder ke judul jendela dan menyembunyikan baris status, jadi satu baris berkas tetap terlihat utuh dengan namanya.
- Tombol bola di Pembersih mengecil mengikuti jendela, tidak terpotong.
- Tedit menaruh Pengaturan di menu Lainnya saat jendelanya sempit, header-nya tetap ada di jendela pendek, dan rail-nya bisa digulir kalau tidak muat. Kalau editor tidak kebagian ruang, terminal mengisi jendela; tutup terminal untuk kembali ke editor.
- Membuka Tedit dengan terminal dari sesi terakhir tidak lagi memunculkan keyboard sendiri.

Yang belum bisa: di sh Android, baris perintah yang lebih panjang dari lebar terminal bergeser ke samping, tidak turun ke baris berikutnya. claw, Ollama, `xset`, dan banner pembuka dracxterm tidak ikut ke Tedit.

Diuji di Infinix HOT 60 5G dengan Android 15. Uji terminal lulus 33 dari 33 di Debian, termasuk 50 sesi dan 50 run beruntun. Delapan alat LX diperiksa di empat ukuran jendela, dari yang terkecil, dan 33 dari 33 pemeriksaan lulus. Pemasangan ekstensi direkam dan dibandingkan per frame: 8 dari 8 pemeriksaan lulus.

Aplikasi kembali bisa dibuka di jendela LX setelah ponsel dimulai ulang, dan menarik tepi jendela terasa lebih ringan.

- Sejak versi yang membawa Tedit dan Debian, LX tidak bisa lagi menyalakan layanan yang menjalankan aplikasi di jendelanya. Jendela aplikasi hanya bekerja selama layanan dari versi sebelumnya masih hidup, dan setelah ponsel dimulai ulang jendela itu tertahan di pesan LX Bridge. Kini LX menyalakannya lagi sendiri, di ponsel uji sekitar lima detik setelah LX tampil.
- Sentuhan pada aplikasi di jendela LX mendarat tepat di tempat yang Anda lihat, juga ketika keyboard LX sendiri sedang terbuka atau baru turun, misalnya dari pencarian menu Aplikasi. Dulu sentuhan itu bisa bergeser ke kanan bawah. Peramban menutup keyboard bila yang tersentuh ternyata halaman di luar kolom teks.
- Keyboard yang tertutup sendiri di Brave belum bisa diulang di ponsel uji. Pada halaman web uji dengan mesin yang sama, 50 huruf yang diketik satu per satu, rentetan cepat, dan huruf yang diketik saat keyboard masih naik semuanya masuk, dan keyboard tetap terbuka.
- Saat jendela ditarik, daftar yang memudar di tepinya dan deret ikon di bilah bawah tidak lagi membebani setiap frame. Di ponsel uji, waktu gambar per frame turun sekitar sepertiga untuk LX Settings dan seperempat untuk File Explorer.
- Tedit menata ulang tampilannya sekali, setelah jari diangkat, bukan berkali-kali di tengah tarikan. Frame terlamanya saat ditarik turun dari 66 ms ke 31 ms.

Berkas yang Anda ketuk di File Explorer kini terbuka di Tedit, editor kode di dalam LX yang tampilannya mengikuti VS Code. Dulu LX menyerahkan berkas ke aplikasi lain lewat "Buka dengan"; menu itu sudah tidak ada, sedangkan Bagikan dan ekstrak arsip tetap ada.

- Tedit punya rail di kiri untuk Berkas, Cari, Kontrol Sumber, Jalankan dan Debug, Ekstensi, dan Masalah, panel yang menempel di samping editor, tab, split editor, dan status bar. Di ponsel yang dipegang tegak panelnya mengambil sekitar dua perlima lebar jendela dan bisa diseret; mengetuk ikon panel yang sedang terbuka menyembunyikannya. Begitu Anda membuka berkas dari panel, panel itu menepi supaya kode memakai seluruh lebar layar. Kalau lebih suka panelnya tetap terbuka, ubah Settings > Tampilan > Setelah membuka berkas.
- Kode diwarnai untuk sekitar 30 bahasa dengan warna yang dikenal pengguna VS Code. Kurung dan kutip ditutup sendiri, Enter menjaga indentasi, dan ada komentar baris, pindah dan duplikat baris, cari dan ganti, termasuk dengan ekspresi reguler, ke baris, serta format untuk JSON.
- Teks, tab, dan tombol berukuran seperti VS Code supaya lebih banyak kode muat di layar ponsel: kode dan teks antarmuka 13 sp, tab setinggi 32 dp. Skala antarmuka dan Kerapatan di Settings bisa memperbesar atau merapatkannya lagi.
- Di atas keyboard layar ada baris Tab, panah, dan simbol yang sering dipakai di kode. Keyboard fisik dan mouse juga jalan, termasuk Ctrl+S, Ctrl+P, dan Ctrl+Shift+P. Ctrl+W menutup tab, bukan jendela Tedit.
- Buka folder dari menu Tedit atau tekan lama folder di File Explorer, lalu pilih Buka folder di Tedit. Pohonnya bisa membuat, mengganti nama, menyalin, memindah, mengompres, mengekspor, dan menghapus berkas.
- Kontrol Sumber membaca repositori git di folder itu: branch, berkas yang berubah, diff, dan riwayat. Tedit tidak menulis ke repositori, jadi tombol untuk merekam perubahan tampil nonaktif beserta alasannya.
- Jalankan menjalankan berkas yang sedang dibuka atau tugas dari .tedit/tasks.json, lengkap dengan keluaran, Hentikan, dan Mulai ulang. Terminal di bawah editor memakai bash Debian bila Debian sudah dipasang, dan sh Android bila belum, keduanya dengan izin LX sendiri, tanpa root. Keluaran panjang tidak membuatnya macet: 100.000 baris tampil dalam waktu kurang dari setengah detik, dan teksnya bisa dipilih lalu disalin.
- Gambar tampil sebagai pratinjau. Berkas lain menampilkan ukuran, lokasi, dan jenisnya, dengan pilihan membukanya sebagai teks hanya baca.
- Perubahan yang belum disimpan tidak hilang saat jendela ditutup atau LX dihentikan Android; Tedit mengembalikannya saat dibuka lagi. Menutup tab yang belum disimpan menanyakan Simpan, Jangan simpan, atau Batal.
- Settings Tedit punya 12 kategori, misalnya Editor dan Privasi, serta tema Gelap, Terang, Tengah malam, dan Hitam OLED. Setiap perubahan langsung terlihat di editor.

Yang belum bisa: Tedit tidak punya debugger, dan terminalnya tidak bisa menampilkan program layar penuh seperti vi atau top. Kontrol Sumber hanya membaca repositori di penyimpanan internal.

Diuji di Infinix HOT 60 5G dengan Android 15 dan di Waydroid Android 13. Di Infinix, 24 uji Tedit, 20 uji beban, dan ke-49 uji bawaan lulus. Uji beban itu mencakup 400 perintah acak, berkas sampai 44 MB, dan 40 tab sekaligus. Log 10 MB terbuka dalam setengah detik dan tetap lancar digulir, JSON 3 MB yang ditulis dalam satu baris juga lancar, dan memorinya kembali seperti semula setelah tabnya ditutup. Keempat tema dan ukuran font sistem 1,3× sudah dilihat di layar ponsel.

Tedit kini punya Debian 13 sendiri. Terminalnya memakai bash Debian, `apt install` menambah program, dan Run menjalankan Python, JavaScript, C, C++, Go, Rust, Java, Ruby, PHP, Lua, atau skrip shell. Semuanya berjalan tanpa root, lewat VHDP, dengan izin LX sendiri.

- Pasang Debian dari menu Run > Linux (Debian) atau dari menu terminal. Di ponsel ARM64 image-nya sudah ada di APK, jadi tidak perlu internet; di perangkat x86_64 seperti Waydroid Tedit mengunduhnya dari GitHub dan memeriksa SHA-256-nya sebelum dipakai. Image yang sama di folder Download juga bisa dipakai.
- Folder yang sedang dibuka dan penyimpanan internal muncul di Debian dengan path yang sama, jadi berkas yang Anda sunting di Tedit langsung terlihat oleh program di dalamnya.
- Ctrl+C dan tombol interupsi menghentikan perintah yang sedang berjalan beserta sisa baris perintahnya, seperti di terminal biasa.
- Kalau Run tidak menemukan penerjemahnya, misalnya `node`, Tedit menyebut paket Debian yang membawanya dan menawarkan tombol Pasang.

Panel Ekstensi kini berisi marketplace. Ekstensi VS Code dicari dan dipasang dari Open VSX, menurut kategori seperti di VS Code, lengkap dengan jumlah unduhan, rating, dan README-nya. Tedit baru menghubungi open-vsx.org setelah Anda mengizinkannya sekali.

- Tema warna dari ekstensi muncul di perintah Tema warna bersama tema bawaan Tedit. Begitu ekstensi tema terpasang, Tedit langsung menawarkan untuk memakainya.
- Ekstensi bahasa menambah jenis berkas baru, misalnya template Jinja, lengkap dengan warna, komentar, dan pasangan kurungnya. Grammar dari ekstensi seperti C/C++ menggantikan pewarnaan bawaan Tedit, dan warnanya sama dengan yang diberikan VS Code untuk grammar itu. Pewarnaannya berjalan di latar, jadi berkas C++ yang panjang tetap lancar digulir dan baris yang sedang diketik langsung berwarna.
- Snippet dari ekstensi muncul di saran saat mengetik dan di perintah Sisipkan Snippet. Tab berpindah ke isian berikutnya. Daftar saran tidak memperlambat ketikan, bahkan di berkas 30.000 baris.
- Server bahasa berjalan di Debian dan memberi diagnosa bergaris di bawah kode dan di panel Masalah, saran saat mengetik, info simbol, ke definisi (F12), dan format dokumen. Server yang dikenal: pylsp untuk Python, clangd untuk C dan C++, gopls, rust-analyzer, serta server untuk TypeScript, Bash, JSON, CSS, HTML, dan YAML. Kalau server belum terpasang, Tedit menawarkan perintah pemasangannya.
- Halaman setiap ekstensi menyebut bagian yang dipakai Tedit dan bagian yang tidak dijalankan.

Yang belum bisa: JavaScript di dalam ekstensi tidak dijalankan, jadi ekstensi yang hanya berisi kode terpasang tanpa efek. Debian hanya berjalan di prosesor ARM64 dan x86_64, dan image-nya menambah APK sekitar 35 MB. Program di Debian bisa membaca berkas LX sendiri, jadi pasang hanya paket yang Anda percayai.

Diuji di Waydroid x86_64. Image Debian terunduh dari GitHub dan terpasang dalam 21 detik, perintah pertama selesai dalam 0,2 detik, dan apt memasang python3 dalam 12 detik. pylsp memberi diagnosa sekitar satu detik setelah berkas dibuka. Tema Dracula terpasang dari Open VSX dan dipakai. Warna dari grammar dibandingkan dengan VS Code pada 121 berkas dari 30 bahasa, dan setiap karakternya sama. Di Infinix belum diuji.

File Explorer kini bisa masuk ke seluruh isi ponsel yang terjangkau ADB, punya halaman Beranda dan Pembersih, dan Pengaturan LX tersusun ulang dengan pratinjau langsung. Ada 15 font baru dan 5 cara baru membuka kategori di menu Aplikasi.

- Selama LX Bridge berjalan, File Explorer menampilkan isi akar perangkat (/), /system, /data/local/tmp, serta Android/data dan Android/obb milik semua aplikasi. Dulu Android/data hanya menampilkan folder LX sendiri. Menyalin, memindah, mengganti nama, menghapus, dan membuka berkas berjalan di sana seperti di folder lain, tanpa pesan atau tanda tambahan. Kalau akses semua file belum diberikan, LX memberikannya sendiri lewat LX Bridge, tanpa halaman izin.
- Akses itu setara `adb shell`, bukan root. Data privat aplikasi lain di /data/data tetap tertutup, dan partisi sistem yang hanya bisa dibaca tetap tidak bisa diubah.
- Tautan seperti /sdcard dan /etc punya ikon sendiri. Mengetuknya membuka tujuannya, dan menghapusnya hanya menghapus tautan itu, bukan isi tujuannya.
- File Explorer terbuka di Beranda: ruang terpakai tiap penyimpanan, jumlah dan ukuran gambar, video, audio, dokumen, arsip, installer APK, dan unduhan, lalu jalan pintas ke Pembersih, Terbaru, dan folder sistem. Kalau Anda terakhir meninggalkannya di sebuah folder, ia kembali ke folder itu.
- Pembersih di File Explorer mencari cache semua aplikasi, cache di Android/data, thumbnail galeri, berkas .tmp dan .log, folder kosong, installer APK, berkas ganda, dan berkas mulai 100 MB. Installer yang aplikasinya sudah terpasang dipilih otomatis, berkas besar tidak pernah dipilihkan, dan penghapusan ditanyakan sekali sebelum berjalan. Angka cache aplikasi adalah hitungan terakhir Android, sedangkan ruang yang benar-benar kosong diukur saat dibersihkan. Pemindaian di ponsel uji selesai dalam sekitar satu detik.
- Di bawahnya ada dua alat cepat. Tutup aplikasi latar menyebut berapa memori yang kembali kosong (539 MB di ponsel uji), dan Rapikan penyimpanan meminta Android menjalankan TRIM saat itu juga.
- Pengaturan LX di ponsel terbuka pada daftar halaman yang dikelompokkan, dan tiap baris menyebut apa yang sedang dipakai, misalnya "Roboto · 100%". Di jendela selebar 560 dp ke atas, daftar itu tetap di kiri. Halaman Tampilan yang lama kini terbagi menjadi Tema dan warna, Font dan teks, Jendela dan panel, serta Animasi, dan sakelar Jam 24 jam pindah ke halaman Desktop.
- Font dan teks memperlihatkan font yang dipakai dalam ukuran judul, isi, dan label. Jendela dan panel punya pratinjau panel di atas latar gelap dan terang yang ikut berubah selama penggeser kepekatan dan sudut digeser.
- 15 font baru: Plus Jakarta Sans, Manrope, Poppins, Montserrat, Open Sans, Work Sans, DM Sans, Figtree, Red Hat Text, Lexend, Atkinson Hyperlegible Next, Exo 2, Rubik, Nunito, dan Literata. Totalnya 25, dikelompokkan menjadi Sans, Bulat, Serif, dan Monospace. APK rilis bertambah 1,3 MB karenanya.
- Di Pengaturan LX > Animasi Anda bisa memilih cara aplikasi sebuah kategori muncul di menu Aplikasi: Pita yang lama, Geser, Mekar, Tirai, Engsel, Gelombang, atau Mati. Pratinjau di atas daftar memutar pilihan Anda dengan gerak yang sama seperti menu sungguhan. Di ponsel uji, membuka dan berpindah kategori sama ringannya dengan semua pilihan.
- Tombol Pengaturan LX di menu Aplikasi bisa diketuk lagi di ponsel. Sejak Pembersih masuk ke bagian bawah menu, tombol itu terhimpit sampai tinggal beberapa piksel. Di ponsel yang dipegang tegak, tombolnya kini ada di ujung kanan kotak cari.

Tata letak Pengaturan LX untuk jendela lebar belum dilihat di layar lebar sungguhan, karena ponsel uji hanya selebar 360 dp.

Diuji di Infinix HOT 60 5G dengan Android 15. Ke-49 uji bawaan dan 8 uji baru untuk akses shell, Pembersih, font, dan animasi lulus, dan log LX tidak mencatat galat dari fitur ini selama pengujian.

Pembersih tidak lagi menghapus foto yang masih Anda lihat di galeri, dan aplikasi yang dibekukan Island tetap punya pintasan dan pinnya.

- Kalau sebuah foto punya salinan di sampah galeri, Pembersih kini menyimpan foto yang terlihat. Dulu ia menyimpan salinan di sampah dan menghapus yang terlihat, sehingga foto itu hilang begitu galeri mengosongkan sampahnya sekitar 30 hari kemudian.
- Membekukan aplikasi lewat Island atau pengelola profil kerja lain tidak lagi menghapus pintasan, isi folder, dan pinnya dari desktop. Android mengabarkan aplikasi beku dengan cara yang sama seperti aplikasi yang dicopot, jadi LX kini memastikan dulu apakah aplikasinya masih terpasang.
- Impor tata letak meminta konfirmasi dulu, sama seperti reset. Berkas yang tidak berisi tata letak LX ditolak dengan pesan; sebelumnya berkas seperti itu menghasilkan desktop kosong dan melepas widget Android Anda.
- Mengimpor tata letak yang sangat besar tidak lagi membuat LX macet sampai Android menutupnya. Di komputer uji, tata letak 1 MiB berisi sekitar 90.000 entri dulu dibaca 38 sampai 52 detik, sekarang kurang dari setengah detik.
- Tata letak dengan nomor item di atas satu miliar ditolak utuh saat diimpor. Dulu impor seperti itu diterima, tetapi apa pun yang Anda tambahkan sesudahnya hilang saat LX dimulai ulang.
- Kalau tata letak gagal dibaca dan LX memakai cadangannya, widget Android dari tata letak itu tetap terdaftar. Pulihkan berkas lamanya, dan widgetnya kembali.
- Ctrl+W dan Alt+F4 menutup menu atau dialog yang sedang terbuka lebih dulu. Sebelumnya kedua tombol itu menutup jendela di belakangnya, dan menekan OK di dialog App Manager sesudahnya membuat LX tertutup paksa.
- Menutup jendela aplikasi sebelum aplikasinya siap tidak lagi membuat LX menyalakan wireless debugging berulang kali selama LX di depan.
- Koneksi yang putus saat Android mengganti port wireless debugging tidak lagi menghapus pairing LX.
- Saat mengekstrak arsip, dua berkas yang namanya hanya beda huruf besar dan kecil, misalnya `Makefile` dan `makefile`, tidak lagi saling menimpa diam-diam. Yang kedua dilewati. Pesan di akhir ekstrak menghitungnya.
- Memindahkan berkas ke kartu SD atau USB menunggu salinannya benar-benar tertulis sebelum aslinya dihapus.
- Penyimpanan ponsel yang penuh tidak lagi membuat LX tertutup paksa setiap kali dimulai.
- Menarik sisi jendela melewati tepi layar kini menghentikan sisi itu di tepi. Dulu jendelanya malah melebar ke arah seberang.
- Judul jendela kembali pas saat nama aplikasi menggantikan nama paketnya.
- Di menu Aplikasi, fokus keyboard tetap pada kategori yang Anda pilih. Dalam bahasa yang ditulis dari kanan ke kiri, daftar aplikasinya kini muncul di sisi yang benar.

Diuji di Infinix HOT 60 5G dengan Android 15. Membekukan aplikasi belum bisa dicoba langsung di sana karena Android 15 tidak mengizinkan perintah shell membekukan aplikasi, jadi yang diuji adalah pemeriksaan LX untuk aplikasi yang masih terpasang dan yang benar-benar dicopot.

Membuka kategori di menu Aplikasi kini punya animasi, dan berpindah di antaranya tidak lagi tersendat.

- Daftar aplikasi terbuka dari baris kategori yang Anda ketuk, lalu aplikasinya muncul satu per satu, mulai dari yang paling dekat dengan baris itu. Memilih kategori lain menggeser daftarnya ke baris yang baru, dan mengetuk kategori yang sedang terbuka melipat daftarnya kembali.
- Daftar langsung tampil di sebelah barisnya. Dulu ia muncul sekejap di puncak menu sebelum melompat ke tempatnya.
- Di ponsel uji, sebuah ketukan tidak lagi membekukan layar sampai 90 ms. Frame paling lambat saat berpindah turun dari 81 sampai 93 ms ke 28 sampai 30 ms, karena LX hanya membangun aplikasi yang terlihat dulu dan memakai ulang baris dari daftar sebelumnya.
- Kalau animasi dimatikan di Android, pengaturan Animasi di LX Settings dipilih Mati, atau LX memakai mode Performa, daftarnya berganti seketika.

Keyboard aplikasi di jendela LX tidak lagi menutup sendiri saat Anda mengetuk tombol di pinggirnya. Mengubah ukuran jendela terasa lebih ringan, dan setelah Android menutup LX di latar belakang, ikon dan menu Aplikasi siap sekitar satu detik lebih cepat.

- Tombol keyboard di pinggir kiri, pinggir kanan, dan baris paling bawah kini mengetik hurufnya pada aplikasi di jendela LX. Dulu ketukan di beberapa piksel terluar jendela dianggap menarik tepinya, hurufnya hilang, dan keyboard tertutup.
- Ketukan yang sedikit meleset ke samping atau ke bawah keyboard juga tidak lagi menutupnya, termasuk pada jendela aplikasi yang memenuhi layar ponsel. Hal yang sama berlaku untuk bilah judul dan bingkai jendela yang sedang Anda pakai mengetik. Mengetuk taskbar, desktop, menu, atau jendela lain tetap memindahkan keyboard ke LX.
- Tombol Hapus, Enter, dan angka dari keyboard layar selalu masuk ke kolom teks LX yang sedang Anda isi, misalnya Notes atau pencarian File Explorer. Sebelumnya tombol itu bisa nyasar ke aplikasi di jendela LX yang ada di depan, lalu keyboard berhenti bekerja.
- Jendela aplikasi kini diubah ukurannya dengan menarik dari garis bingkainya atau dari pita selebar 8 dp tepat di luar tepinya. Dekat sudut, tarikan itu mengubah dua sisi sekaligus. Layar aplikasinya sendiri menerima ketukan sampai ke tepi.
- Saat Anda menarik tepi jendela, bingkainya mengikuti jari di setiap frame. Isi yang berat, seperti halaman Tampilan di LX Settings, menyesuaikan diri beberapa kali per detik, menyusul begitu jari berhenti, dan memenuhi jendela begitu jari diangkat. Judul jendela kini dihitung jauh lebih cepat, jadi jendela apa pun lebih ringan diubah ukurannya.
- Sudut jendela berhenti tepat di bawah jari saat dilepas. Sebelumnya sudut itu bisa meleset 3 sampai 4 piksel. Kalau Anda menarik melewati ukuran terkecil lalu berbalik, tepinya menunggu sampai jari kembali ke sana. Dulu ukurannya langsung bertambah lagi, dan tepinya tertinggal sejauh jari tadi melewati batas.
- Aplikasi di jendela LX menata ulang tampilannya sekali, setelah tarikan selesai. Dulu jari yang berhenti sejenak di tengah tarikan sudah membuat aplikasinya menata ulang, bahkan memulai ulang.
- File Explorer yang menampilkan banyak berkas lebih ringan saat diubah ukurannya, dan gambar mininya tetap tampil.
- LX Settings dibangun sekali setiap dibuka, dan tidak dibangun lagi saat LX kembali ke depan selama isinya sama. Di Waydroid, pembukaan pertama turun dari 527 ke 325 ms, pembukaan berikutnya dari 253 ke 172 ms. Pratinjau font di halaman Tampilan muncul dengan font antarmuka dulu, lalu berganti ke font masing-masing begitu terbaca.
- Berganti ke mode gelap atau terang tidak lagi membuat LX membangun ulang semua tampilan; hanya widget Android di desktop yang dibuat ulang supaya warnanya ikut berganti. Kalau Android sendiri memulai ulang LX karena warna tema sistem ikut berubah, LX hanya membangun jendela yang sedang terlihat.
- Jendela yang diperkecil atau yang ada di workspace lain baru dibangun saat pertama kali terlihat. Saat LX muncul kembali setelah Android menutupnya di latar belakang, LX tidak lagi membangun semua jendela tersimpan sekaligus.
- Setelah Android menutup LX di latar belakang, ikon di bilah bawah, di desktop, dan di menu Aplikasi muncul lebih cepat. LX mengingat nama aplikasi dari pembacaan sebelumnya dan hanya membaca ulang aplikasi yang baru dipasang atau diperbarui. Di ponsel uji, daftar 114 aplikasi kini siap dalam 0,2 sampai 0,4 detik, dulu 1,2 sampai 1,5 detik. Saat bahasa ponsel atau sistemnya berganti, semua nama dibaca ulang.
- Wallpaper foto, bawaan maupun foto Anda sendiri, tampil lebih cepat setelah LX dimulai ulang. LX menyimpan versi siap tampilnya di cache; kalau Android mengosongkan cache itu, LX mengolah fotonya sekali lagi.
- Wallpaper yang sedang tampil tetap disimpan saat LX pindah ke latar belakang. Kalau tampilan LX dibangun ulang sesudahnya, latarnya langsung ada tanpa diolah ulang.
- Menu Aplikasi dibangun sekali setiap dibuka. Dulu dua kali.

Diuji di Infinix HOT 60 5G dengan Android 15. Ketukan 1 sampai 3 dp dari tepi kiri, kanan, dan bawah keyboard mengetik hurufnya, termasuk Hapus dan spasi, dan keyboard tetap terbuka. Ketukan di pita samping jendela, di garis bingkai, dan di judul juga tidak menutupnya. Yang masih terasa di ponsel itu: halaman Tampilan di LX Settings tetap sedikit tersendat saat diubah ukurannya, karena menata ulang puluhan teksnya memakan hampir 20 ms di build debug.

Pembersih tidak lagi bisa menghapus foto asli Anda, aplikasi di jendela LX jauh lebih hemat baterai, dan APK-nya kini 7,5 MB, dulu 18,4 MB.

- Pembersih menyimpan salinan foto yang ada di folder kamera (DCIM), lalu Pictures, dan memeriksa ulang isi kedua berkas tepat sebelum menghapus. Dulu yang disimpan salinan dengan alamat terpendek, sehingga foto di DCIM bisa terhapus dan salinannya di Download yang tersisa. Gambar yang berubah sesudah dipindai kini dibiarkan.
- Menghapus aplikasi dari profil kerja tidak lagi ikut menghapus pintasan, isi folder, dan pin aplikasi yang sama di profil pribadi.
- Kalau berkas tata letak tidak terbaca, misalnya karena ditulis versi LX yang lebih baru, LX memakai cadangannya. Berkas itu disimpan di sampingnya. Cadangan yang masih bagus tidak tertimpa.
- Mengganti tema tidak lagi meninggalkan widget lama yang terus berjalan di belakang. Selama aplikasi lain di depan, jam dan baterai di panel atas juga berhenti mendengarkan.
- Properti untuk gambar yang sangat panjang, seperti screenshot gulir, kini terbuka tanpa menutup LX.
- Pertanyaan "nama sudah dipakai" saat menyalin tetap ada ketika tema diganti. Salinannya menunggu jawaban Anda di File Explorer.
- Selama satu aplikasi terbuka di jendela LX, di ponsel uji LX memakai sekitar 0,03% waktu prosesor untuk menjaganya, dulu sekitar 3%, juga saat jendelanya diperkecil. Aplikasi yang minta layar tegak atau mendatar tetap diikuti seketika.
- LX hanya menyalakan LX Bridge selama LX ada di depan. Kalau tidak ada jendela yang menunggu, LX berhenti mencoba setelah tiga kali gagal, lalu mulai lagi saat Wi-Fi berganti atau saat Anda membuka aplikasi di jendela.
- Jendela aplikasi yang diperkecil lebih dari lima detik melepas gambar terakhirnya; di ponsel uji itu sekitar 9 MB memori. Aplikasinya tetap berjalan dan muncul begitu jendelanya dibuka.
- File Explorer membuka folder berisi 10.000 berkas dalam 5 sampai 7 detik di ponsel uji, dulu 18 sampai 21 detik, dan layar tidak tersendat saat folder besar diurutkan. Menghitung ukuran folder di Properti berhenti begitu lembarnya ditutup.
- Menggeser jendela jarang tersendat. Di ponsel uji frame yang terlambat turun dari 5,6% ke 3,3%. Membuka menu Aplikasi dan menggulir semua aplikasi kini memakai memori sekitar 100 MB lebih sedikit.
- Wallpaper bawaan kini lima kali lebih kecil, tanpa beda yang terlihat di layar ponsel uji.
- Di Android 14 ke atas, Pembersih benar-benar bisa meminta Android menutup aplikasi lain di latar belakang, lewat LX Bridge. Tanpa LX Bridge jumlah aplikasi yang ditutup tertulis 0, karena Android tidak mengizinkan LX menutupnya sendiri.
- Ringkasan workspace dan isi folder kini muat utuh di ponsel selebar 360 dp.

File Manager sekarang bernama File Explorer, dengan cara cepat menyalin path, ditambah alat Pembersih untuk melegakan ruang penyimpanan dan memori.

- File Explorer: nama barunya dipakai di mana saja. Menu pada sebuah berkas atau folder kini punya "Salin path" yang menaruh lokasinya ke papan klip, siap ditempel ke mana pun.
- Pembersih: alat baru di kaki menu Aplikasi. Ia membersihkan cache LX sendiri, menemukan folder kosong dan gambar yang sama persis untuk dihapus, dan bisa meminta Android menutup aplikasi yang menganggur di latar belakang. Setiap penghapusan bertanya dulu, dan satu salinan tiap gambar selalu disimpan.
- Menggeser jendela dan membuka menu terasa lebih ringan, dan wallpaper layar penuh memakai memori lebih sedikit.
- Keyboard di jendela aplikasi lebih jarang tertutup sendiri ketika aplikasi mengambil fokus untuk mengetik.

Membuka aplikasi di jendela LX setelah ponsel dimulai ulang tidak lagi berakhir di pesan "LX Bridge tidak menyala". Keyboard di jendela aplikasi juga lebih bisa diandalkan, layar penuh kini benar-benar penuh, dan tombol tutup ikut menutup aplikasinya.

- Setelah restart, jendela aplikasi menunggu LX Bridge lalu membuka aplikasinya sendiri begitu siap. Sebelumnya LX menyerah setelah 25 detik, dan jendelanya tetap di pesan itu walaupun LX Bridge menyala tidak lama kemudian. Selama menunggu, jendela menyebut bahwa LX masih mencoba, dan Buka sebagai jendela Android tetap bisa dipilih.
- LX Bridge kini menyala juga saat ponsel sedang sibuk. Dengan prosesor ponsel uji dibebani penuh, LX menyalakannya 10 dari 10 kali, padahal sebelumnya tidak sekali pun. LX juga tidak lagi tersesat saat Proses debug nirkabel dinyalakan ulang oleh Android atau aplikasi lain, misalnya Shizuku, yang menyalakannya setiap ponsel menyala.
- Jendela yang aplikasinya ikut tertutup karena layanan jendela LX berhenti kini membuka aplikasinya lagi begitu layanan itu kembali.
- Mengetuk tombol atau bagian kosong di aplikasi tidak lagi menutup keyboard. Kembali saat keyboard terbuka kini hanya menutup keyboard; sebelumnya aplikasinya ikut mundur, bahkan tertutup.
- Layar penuh di menu jendela kini menyembunyikan bilah judul dan bingkai, jadi aplikasi memakai seluruh layar di antara bilah status dan tombol navigasi. Tekan Kembali untuk kembali ke ukuran sebelumnya, lengkap dengan tombol-tombolnya.
- Tombol tutup di bilah judul kini mengakhiri aplikasinya, sama seperti menggesernya dari daftar aplikasi terbaru. Sebelumnya aplikasi itu tertinggal di daftar dan tetap berjalan.
- Kalau Android tidak mengizinkan Proses debug nirkabel di Wi-Fi yang sedang dipakai, jendela mengatakannya, dan LX mencoba lagi sendiri saat Wi-Fi berganti.
- Di ponsel uji, setelah restart sungguhan, Chrome terbuka di jendela LX sekitar 3 detik setelah Wi-Fi tersambung. Pada keadaan yang lebih berat, dengan prosesor dibebani dan port Proses debug nirkabel berganti di tengah jalan, aplikasinya terbuka 12 detik setelah Wi-Fi menyala.

Jendela aplikasi kini dikelola `lxdb`, alat baru LX yang ditulis dengan C++ dan assembly. Cara kerjanya mirip adb, tetapi ia berbicara langsung dengan Android dari dalam ponsel, tanpa jaringan dan tanpa kabel USB.

- Setelah menyala, `lxdb` tetap bekerja walaupun Wi-Fi dimatikan. Wi-Fi hanya dibutuhkan beberapa detik setiap ponsel selesai dimulai ulang, karena Android hanya mengizinkan `adbd` menjalankan alat seperti ini, dan cara lain berarti membobol ponsel.
- Jendela yang diperkecil atau ada di workspace lain tidak lagi digambar sama sekali, sedangkan aplikasinya tetap berjalan.
- Saat aplikasi meminta arah layar lain, jendelanya langsung mengikuti. Di ponsel uji permintaan itu sampai dalam kurang dari 50 ms.
- Kalau LX berhenti mendadak, `lxdb` menghidupkannya lagi dan tersambung sendiri dalam sekitar 2 detik, tanpa perlu Wi-Fi. `lxdb` juga menunggu selama LX masih terpasang, jadi LX yang lama tidak berjalan tidak lagi memaksa Anda menyalakan Wi-Fi.
- Kalau ROM ponsel Anda mengubah bagian Android yang dipakai `lxdb`, LX menawarkan jendela Android dan tidak membuka jendela yang tidak bisa disentuh. `lxdb doctor` menyebut bagian mana yang berbeda.
- Jendela LX kini butuh Android 14 ke atas, karena `lxdb` memakai cara Android 14 untuk menjelaskan layar. Di Android 13, aplikasi terbuka di jendela Android seperti biasa.
- Dari `adb shell`, Anda bisa memakai `lxdb shell`, `push`, `pull`, `install`, `uninstall`, `logcat`, `dumpsys`, `services`, dan `windows`. Aplikasi lain, termasuk aplikasi terminal, tidak bisa memakainya, karena Android dan `lxdb` sama-sama menolaknya.

LX kini punya alat sendiri untuk membuka aplikasi di jendelanya. Shizuku tidak dipakai lagi, jadi Anda tidak perlu menyalakan apa pun setiap ponsel dimulai ulang.

- LX Bridge: buka aplikasi, ketuk Sambungkan otomatis, lalu nyalakan satu izin di halaman Aksesibilitas. LX sendiri yang menyalakan Opsi developer dan Proses debug nirkabel, menyambungkan dirinya, lalu mematikan izin itu. Ponsel berkunci bisa meminta PIN Anda sekali, dan Android memang tidak mengizinkan aplikasi melewati persetujuan itu. Kalau mau, Anda juga bisa mengetik kodenya sendiri lewat Sambungkan dengan kode.
- Setelah ponsel dimulai ulang, LX menyalakan LX Bridge sendiri begitu Wi-Fi tersambung, dan aplikasi yang menunggu langsung terbuka. Wi-Fi hanya perlu beberapa detik dan tidak harus punya internet. LX sudah menyalakan dirinya seperti ini di ponsel uji setiap kali diperbarui. Pemasangan pertama lewat izin aksesibilitas belum dicoba.
- Aplikasi di jendela LX kini tetap berjalan saat Anda pindah workspace, memperkecil jendelanya, membuka aplikasi lain di atas LX, atau mematikan layar. Sebelumnya aplikasinya berhenti dan baru jalan lagi saat jendelanya terlihat. Selama ada aplikasi di jendela LX, ponsel tidak tidur penuh, jadi baterai lebih cepat turun; tutup jendelanya kalau tidak dipakai.
- Saat Anda mengubah ukuran jendela, aplikasi di dalamnya ikut mengecil atau membesar utuh tanpa melar, lalu menata ulang tampilannya begitu Anda berhenti menyeret.
- Di ponsel, tombol File Manager kini ikut bergulir bersama ikon aplikasi di bilah bawah.
- LX tidak lagi sesekali menutup sendiri saat tema, wallpaper, atau ukuran layar berubah.

File Manager menggantikan LX Files. Ia bisa membuka seluruh penyimpanan internal dan kartu SD, membuat dan membongkar arsip ZIP, TAR, dan TAR.GZ, dan punya tempat tetap di bilah bawah, tepat di kanan tombol Aplikasi.

- Ketuk ikon map biru di bilah bawah untuk membuka File Manager. Ketuk lagi untuk memperkecil atau memunculkan jendela yang sama; ikon itu tidak pernah membuka jendela kedua. Garis kecil di bawah ikon menandai jendelanya sedang terbuka.
- File Manager tampil seperti file manager desktop: bilah atas datar, jalur folder dalam satu kolom membulat, sidebar berbentuk kartu, dan ikon berwarna per jenis berkas. Di ponsel berkas tersusun lima kolom, dan nama panjang dipotong di tengah supaya akhir namanya tetap terlihat. Warna dasarnya mengikuti gaya jendela yang Anda pilih.
- Tombol menu di File Manager berisi Berkas, Edit, Tampilan, dan Arsip, dan setiap menunya bisa bercabang, seperti di desktop Linux. Di jendela yang sempit, cabangnya terbuka di tempat dengan baris "kembali" di atasnya. Semua menu LX lain kini bisa bercabang dengan cara yang sama, termasuk dengan panah keyboard dan mouse.
- Izinkan akses semua file dari File Manager untuk melihat penyimpanan internal, kartu SD, dan flashdisk USB secara utuh. LX menjelaskan dulu izin itu lalu membuka halaman izinnya di Android, dan Anda bisa mencabutnya kapan saja. Tanpa izin itu, File Manager tetap bisa membuka folder yang Anda pilih sendiri, seperti LX Files dulu.
- Sidebar berisi Terbaru, penyimpanan internal, Unduhan, Dokumen, Gambar, Kamera, Video, Musik, Arsip, kartu SD, dan folder pilihan Anda, dan tempat yang sedang dibuka ditandai. Di jendela yang sempit, sidebar menjadi laci yang dibuka dengan tombol Tempat.
- Pilih beberapa berkas sekaligus dengan menekan lama satu berkas lalu mengetuk yang lain. Salin, potong, tempel, salin ke, pindahkan ke, ganti nama, hapus, bagikan, buka dengan, dan properti bekerja untuk pilihan itu. Properti menghitung isi folder dan menampilkan pratinjau gambar atau awal berkas teks.
- Kompres folder menjadi ZIP, TAR, atau TAR.GZ. Nama foldernya tetap ada di dalam arsip, jadi `MyProject.zip` dibongkar kembali menjadi `MyProject/`. Ekstrak di sini atau ke folder lain, dan periksa arsip tanpa membongkarnya.
- Arsip dari internet tidak bisa menulis di luar folder tujuan. Path dengan `..`, path absolut, symlink, dan entri terenkripsi dilewati dan disebutkan jumlahnya, dan arsip yang membengkak tidak wajar dihentikan sebelum memenuhi penyimpanan. Isi arsip ditulis ke folder tersembunyi dulu, lalu baru dipindahkan setelah semuanya lolos.
- Kalau nama sudah dipakai, File Manager bertanya: ganti, gabungkan folder, simpan keduanya, lewati, atau hentikan, dengan pilihan "lakukan hal yang sama untuk sisanya". Berkas yang diganti baru dihapus setelah berkas barunya lengkap.
- Menyalin dan mengompres tetap berjalan walaupun jendela File Manager ditutup atau tema diganti, dan progresnya tampil lagi saat jendela dibuka. Berkas setengah jadi dari proses yang terputus dibersihkan saat File Manager dibuka berikutnya.
- Kembali dan Maju mengingat folder yang sudah Anda buka, nama berkas diurutkan seperti cara orang menghitung (berkas 2 sebelum berkas 10), dan berkas tersembunyi bisa ditampilkan dari menu Tampilan.
- Jendela LX baru memakai gaya LX Modern Gelap: badan navy padat, bilah judul penuh, tepi biru tipis, sudut 20 dp, tombol kuning, hijau, dan merah, serta ikon alat berwarna. Gaya yang sudah Anda pilih tidak diubah.
- Gaya jendela Kustom: mulai dari salah satu gaya, lalu atur sudut, tebal tepi, tinggi bilah judul, ukuran judul dan ikon, bentuk tombol, bayangan, warna aksen, dan apakah jendela tanpa fokus memudar. Setelannya muncul di Pengaturan LX > Tampilan, di bawah daftar gaya.
- Jendela LX yang ditutup lalu dibuka lagi kembali ke posisi dan ukuran terakhirnya.
- Tepi kanan tombol tutup tidak lagi memulai ubah ukuran jendela.
- Aplikasi Android seperti YouTube, Chrome, WhatsApp, dan Facebook terbuka di dalam jendela LX dengan bilah judul, nama, ikon, dan tombol sesuai gaya jendela yang Anda pilih. Ini kini pilihan bawaan di Pengaturan LX > Jendela > Bingkai jendela aplikasi. Game tetap dibuka layar penuh, dan aplikasi yang Anda tandai tetap memakai jendela Android.
- Seluruh aplikasi selalu terlihat di jendelanya. Isinya mengisi jendela sampai ke tepi, di jendela kecil tampil diperkecil, dan aplikasi yang terkunci tegak tampil utuh di jendela yang lebar. Keyboard muncul di dalam jendela, jadi kolom ketik di bagian bawah, misalnya di chat WhatsApp, naik ke atas keyboard.
- Kalau LX Bridge belum tersambung, jendela LX tetap terbuka dan menawarkan caranya, atau membuka aplikasi di jendela Android kalau Anda memilih itu. Begitu tersambung, aplikasinya terbuka sendiri.
- Fitur itu sejauh ini baru dicoba di Android 15, di ponsel MediaTek.
- Jendela LX tidak lagi tembus ke jendela di belakangnya saat blur mati. Sebelumnya judul dan teks jendela di belakang samar terlihat di balik daftar berkas. Bilah pesan dan bilah mode edit kini juga padat.
- Slider Kepekatan dock dihapus dari Pengaturan LX > Dock, karena tidak pernah mengubah apa pun. Dock mengikuti Kepekatan panel di Pengaturan LX > Tampilan.
- Dalam bahasa yang ditulis dari kanan ke kiri, menu bercabang kini terbuka di posisi yang benar dan submenunya tidak lagi menutupi menu induk.
- Menu kini melebar mengikuti label terpanjangnya, sampai 360 dp, jadi pilihan seperti "Tampilkan berkas tersembunyi" tidak terpotong lagi.
- Pengaturan LX > Tentang kini menyebut kalau File Manager boleh membuka seluruh penyimpanan bersama, dan bahwa kartu SD yang terpasang langsung tampil di sidebar-nya.

Tampilan LX dirapikan untuk ponsel dan tablet. Tema, huruf, dan gaya jendelanya kini bisa Anda ganti.

- Sepuluh tema warna di Pengaturan LX > Tampilan, masing-masing dengan pratinjau kecil. Plasma Night tetap jadi bawaan. Paper Light adalah tema terang dengan teks gelap, dan kontras tinggi bisa dipakai di tema mana pun.
- Sepuluh font untuk seluruh LX, dari Roboto sampai JetBrains Mono. Setiap pilihan ditulis dengan hurufnya sendiri. Semua font ikut terpasang di aplikasi, jadi tidak perlu internet.
- Jendela LX bisa memakai satu dari sepuluh gaya: Minimalis Gelap, Glassmorphism, Cyberpunk Neon, Rounded Modern, Taktis, Ringkas, Material You, Retro, Nuansa Alam, dan Hybrid Desktop Linux. Masing-masing punya bingkai, bilah judul, tombol, sudut, dan warnanya sendiri. Pilih di Pengaturan LX > Tampilan > Gaya jendela, yang menampilkan contoh kecil setiap gaya, atau tekan lama bilah judul jendela mana pun lalu pilih Gaya jendela. Gaya ini dipakai Pengaturan LX, File Manager, Monitor Sistem, dan Catatan, sedangkan desktop, menu, dan bilah tetap mengikuti tema LX. Pemasangan baru memakai LX Modern Gelap; gaya yang sudah Anda pilih tidak diubah.
- Glassmorphism dan Nuansa Alam memperlihatkan wallpaper yang di-blur di balik jendela, dan jendelanya makin pekat di atas wallpaper yang terang supaya teks tetap terbaca. Dengan blur mati atau di mode Performance, jendelanya padat.
- Jendela dengan gaya Ikut tema LX, panel atas, dan bilah bawah kini juga makin pekat kalau wallpaper-nya terang. Pada wallpaper putih, teks abu di jendela itu sebelumnya hanya 3,2:1, dan teks utamanya 1,9:1 kalau Kepekatan panel diset 35%; sekarang teks abu tetap 5,6:1 berapa pun setelannya. Dengan wallpaper gelap, Kepekatan panel tetap bekerja seperti biasa.
- Pilihan yang sedang dipakai ditandai dengan garis tepi, centang, dan tulisan "Dipakai", tidak hanya dengan warna.
- Folder, berkas, dan alat LX bisa memakai ikon berwarna dari set Vivid atau ikon garis yang lebih polos. Ikon aplikasi yang terpasang tidak diganti.
- Di ponsel yang dipegang tegak, dock dan taskbar menyatu menjadi satu bilah di bawah, nomor workspace pindah ke panel atas, dan pencarian dibuka dari panel atas atau dengan mengusap layar ke bawah. Jendela yang terbuka atau diminimalkan selalu terlihat di ujung kanan bilah, sedangkan entri dock yang tidak muat bisa digeser. Di tablet, dock tetap di kiri. Kalau Anda sudah memakai LX sebelum ini, pilih Otomatis di Pengaturan LX > Dock untuk mendapatkan susunan ini.
- Wallpaper baru, Ikut tema, mengikuti warna tema yang Anda pilih. Pemasangan baru langsung memakainya, sedangkan wallpaper yang sudah Anda pilih tidak diubah.
- Semua workspace kini memakai satu wallpaper, jadi latar tidak berganti saat Anda berpindah workspace. Memilih wallpaper dari Pengaturan LX > Desktop, dari menu tekan lama desktop, atau dari menu sebuah workspace mengganti wallpaper semuanya. Kalau sebelumnya tiap workspace punya wallpaper sendiri, LX memakai wallpaper workspace yang sedang aktif, lalu menghapus foto yang tidak dipakai lagi dari penyimpanannya.
- Dengan kontras tinggi, panel kini buram penuh. Sebelumnya isi widget di belakang jendela masih samar terlihat.
- Mengganti tema, font, atau kontras tidak lagi menyembunyikan jendela yang sedang terbuka, dan Pengaturan LX tetap di halaman serta posisi gulir yang sama.
- Teks lisensi bisa dibaca di Pengaturan LX > Tentang. Karena ikon Vivid berlisensi GPL-3.0, APK sebagai satu kesatuan kini didistribusikan di bawah GPL-3.0. Kode sumber LX tetap Apache 2.0.
- Tambah aplikasi di menu tekan lama desktop kini menaruh pintasan aplikasi di tempat Anda menekan. Sebelumnya mengetuk aplikasi di daftar itu malah membukanya.
- Panel atas dan bilah bawah lebih ramping, seukuran desktop: 40 dp dan 48 dp di ponsel, sebelumnya 52 dp dan 64 dp. Dengan begitu ketujuh entri dock bawaan muat tanpa digeser. Target besar di Aksesibilitas mengembalikan ukuran yang lebih besar.
- Tombol Aplikasi punya wadah sendiri dan terisi warna aksen selama menunya terbuka. Entri bilah bawah ponsel dibagi rata selebar bilah. Workspace aktif ditandai pil berwarna, dan di layar lebar ikon baterai di panel atas berubah menjadi petir saat mengisi daya.
- Widget bisa dipindah langsung: tekan lama, lalu geser tanpa mengangkat jari. Tekan lama lalu lepas tetap membuka menu widget. Ikon yang tertutup widget setelah dipindah otomatis pindah ke tempat kosong, jadi tetap bisa diketuk.
- Laci aplikasi diganti menu Aplikasi, yang dibuka dari tombol di ujung kiri bilah bawah atau dengan Ctrl Shift A. Kategori tersusun di satu kolom: Favorit, Terakhir, Semua aplikasi, kategori Android yang berisi, Lainnya, dan Tersembunyi kalau ada. Ketuk atau tunjuk sebuah kategori, dan aplikasinya terbuka sebagai submenu di sebelahnya, sejajar dengan kategorinya. Di ponsel yang dipegang tegak, kedua kolom menyempit dan submenu boleh menumpuk sedikit di ujung kolom kategori, jadi menu tidak pernah memenuhi layar. Kalau tumpukan itu akan memotong nama kategori, misalnya di ukuran antarmuka 130%, kolom kategori menyusut menjadi deretan ikon selama submenu terbuka. Ketuk lagi kategori yang terbuka untuk menutup submenunya dan memunculkan namanya kembali. Back menutup kategori yang terbuka sebelum menutup menu. Menekan lama aplikasi di hasil pencarian menyembunyikan keyboard, jadi seluruh menu aplikasinya terlihat. Pengurutan menurut tanggal pasang ikut hilang bersama laci.
- Kotak cari menu Aplikasi berbentuk pil di bagian atas, setiap kategori punya ikonnya sendiri, dan kategori yang terbuka terisi warna aksen. Aplikasi di dalam kategori tampil satu per baris dengan ikon dan namanya, dan kategori yang panjang bergulir di dalam tinggi menu. Di bagian bawah menu ada LX Files, Monitor Sistem, Catatan, dan tombol bulat untuk Pengaturan LX. Mengetik saat menu terbuka langsung mengisi kotak cari, dan kalau tidak ada aplikasi yang cocok, menu menawarkan mencari kata itu di semua tempat.
- Seret aplikasi dari menu Aplikasi ke desktop atau taskbar untuk menaruhnya di sana. Kalau jari tidak digeser setelah ditekan lama, menu aplikasinya yang terbuka.
- Aplikasi kini benar-benar terbuka sebagai jendela yang bisa digeser, diubah ukurannya, diperkecil, dan dibuka beberapa sekaligus, asalkan "Enable freeform windows" di Opsi pengembang sudah menyala. Sebelumnya permintaan jendela diabaikan dan aplikasi tetap layar penuh. Jendela aplikasi selalu berada di atas bilah LX; geser atau perkecil jendela yang menutupinya.
- Jendela aplikasi kini mengikuti Pengaturan LX > Jendela > Jendela baru dibuka, sama seperti jendela LX. Dengan Otomatis atau Maksimal, aplikasi di ponsel terbuka mengisi seluruh area antara panel atas dan bilah bawah, jadi tampilannya tidak terpotong dan kedua bilah tetap bisa diketuk. Di Infinix HOT 60 5G, isi Kalkulator kini setinggi 1.124 px, sebelumnya 858 px.
- Dengan Mengambang, jendela aplikasi di ponsel selebar layar dikurangi tepi 8 dp. Tingginya sebesar mungkin tanpa membuat empat jendela yang bertumpuk turun melewati bilah bawah. Di tablet, aplikasi yang hanya berjalan tegak dibuka sebagai jendela tegak, dan aplikasi mendatar dibuka mendatar.
- Game selalu terbuka layar penuh, karena selama game berada di jendela Android mengabaikan arah layar yang dimintanya. Di layar penuh, arah itu kembali berlaku bersama setelan putar otomatis ponsel Anda.
- Aplikasi Android kini dibuka dalam jendela secara bawaan, termasuk lewat pintasan aplikasi, Browser, dan Galeri. Android yang menentukan apakah jendela itu diizinkan; kalau tidak, aplikasi terbuka layar penuh seperti biasa. Di ponsel yang belum mengizinkan, Pengaturan LX > Jendela menjelaskan cara menyalakan "Enable freeform windows" di Opsi pengembang.
- Pengaturan LX > Tentang menunjukkan apa yang diizinkan perangkat Anda: jendela aplikasi, layar terbagi, gambar dalam gambar, kartu SD, keyboard dan mouse, dan lainnya, masing-masing dengan status dan alasannya.
- LX Files bisa membuat berkas baru, mengurutkan menurut nama, tanggal, ukuran, atau jenis, menyalin folder, dan memindah antar-folder yang diizinkan. Kartu SD dan drive USB muncul sebagai pilihan tersendiri.
- Menyalin dan memindah di LX Files lebih aman. Salinan yang gagal, dibatalkan, atau ukurannya berbeda dihapus lagi, dan berkas asli baru dihapus setelah salinannya lengkap. Operasi yang lama punya tombol Batal, dan nama yang sudah dipakai di folder tujuan ditolak.
- Memutar layar tidak lagi menutup dialog atau pencarian yang sedang Anda ketik, dan isi jendela LX tetap utuh.
- Saat keyboard muncul, jendela LX naik sehingga kolom teksnya tetap terlihat. Jendela layar penuh tidak lagi tertutup bilah status dan bilah navigasi, dan Pulihkan mengembalikannya ke jendela biasa.
- Pilihan baru Layar penuh di Pengaturan LX > Dock menyembunyikan bilah status dan navigasi. Usap dari tepi untuk memunculkannya sebentar.
- Alt+Tab berpindah antar-jendela LX.
- Menu tekan lama tidak lagi tertutup sendiri saat keyboard muncul atau hilang.
- Keterangan dan teks berwarna aksen tetap terbaca di baris yang sedang difokus keyboard. Fokus kini ditandai cincin 2 dp di atas isian yang lebih tipis.
- Ikon bilah status menjadi gelap di atas wallpaper terang, jadi jam dan baterai tetap terbaca.
- Mengubah ukuran jendela kini dilakukan dari bingkainya. Menggulir isi jendela dekat tepinya tidak lagi mengubah ukuran, dan sentuhan di area kosong jendela tidak lagi tembus ke ikon desktop di bawahnya.
- Kalau desktop LX Anda menampilkan gambar referensi desain sebagai wallpaper, itu sisa dari uji otomatis, bukan bagian aplikasi. Pilih wallpaper lain di Pengaturan LX > Desktop. Uji otomatis kini memakai gambar uji sendiri dan membersihkan data LX setelah selesai.
- APK release bertambah sekitar 2,4 MB, hampir seluruhnya untuk font.

## 0.1.1, 25 Oktober 2026

Rilis ini diuji di ponsel sungguhan (Infinix HOT 60 5G, Android 15), dan sebagian besar perbaikannya terasa di layar tegak.

- Foto pilihan Anda sekarang benar-benar tampil sebagai wallpaper. Di 0.1.0 foto itu diterima, tapi layar tetap menampilkan wallpaper bawaan.
- LX menyimpan salinannya sendiri, jadi wallpaper tetap ada walau berkas aslinya dipindah atau dihapus. Salinan yang tidak lagi dipakai workspace mana pun ikut dihapus.
- Di Android 13 ke atas, gambarnya dipilih lewat pemilih foto Android, tanpa izin penyimpanan.
- Tata letak awal di ponsel menyisakan ruang untuk ikon. Jam, status, dan catatan ada di bagian atas; kontrol media bisa ditambahkan dari LX Settings.
- Ikon yang Anda tambahkan ke desktop tidak lagi jatuh di atas widget, dan widget baru mencari tempat kosong sendiri.
- Tanggal di widget jam dan keterangan di widget status tidak lagi terpotong di layar pendek.
- Jendela tidak bisa lagi digeser sampai tombol tutup dan maksimalkannya keluar layar. Menu di bilah judul kini juga punya Maksimalkan dan Pulihkan.
- Bilah judul lebih bisa diandalkan di layar sentuh. Tekan lama selalu membuka menunya, juga saat jendela sedang maksimal, sedangkan seretan pelan tidak lagi membukanya saat jari diangkat.
- Tekan lama pada widget membuka satu menu. Sebelumnya beberapa menu bisa bertumpuk.
- Tombol Esc di keyboard menutup dialog dan menu yang sedang terbuka, misalnya Detail di LX Files.
- Judul jendela dan judul bagian di LX Settings tidak lagi terpotong di jendela sempit.
- Di Waydroid, mengimpor tata letak yang tersimpan di folder seperti Documents atau Pictures tidak lagi gagal.
- Ikon aplikasi baru, dan logo yang sama muncul di panel atas.
- LX-LAUNCHER kini berlisensi Apache 2.0, dan Anda boleh memakai versi lisensi Apache yang lebih baru. Lihat `LICENSE` dan `NOTICE`.

## 0.1.0, 23 Oktober 2026

Rilis pertama.

- Bisa dipilih sebagai aplikasi Home lewat dialog Android dan dikembalikan ke launcher lama dari LX Settings.
- Desktop dengan empat workspace, dock, taskbar, panel atas, dan bilah pencarian. Di layar lebar, pencarian pindah ke taskbar.
- Laci aplikasi dengan pencarian, kategori, favorit, aplikasi tersembunyi, dan aplikasi yang terakhir dibuka dari LX.
- Folder dibuat dengan menyeret satu ikon ke ikon lain.
- Sebelas widget LX dengan data asli dari perangkat, ditambah widget dari aplikasi lain.
- LX Settings, LX Files, System Monitor, dan Notes terbuka sebagai jendela yang bisa digeser, diubah ukurannya, ditempel ke sisi layar, dan diperkecil ke taskbar.
- Susunan widget untuk layar tegak dan mendatar disimpan terpisah, jadi memutar layar tidak merusak susunan yang sudah Anda atur.
- Tata letak bisa diekspor ke berkas dan diimpor kembali. Kalau berkas tersimpan rusak, LX memakai salinan sebelumnya.
- Bahasa Inggris dan Bahasa Indonesia.
