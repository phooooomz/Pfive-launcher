# ADR-004: Efek kaca dari salinan wallpaper yang di-blur sekali

Status: diterima, 23 Oktober 2026

## Masalah

Brief meminta glassmorphism yang bisa diatur, tetapi juga melarang blur mahal di permukaan besar dan redraw terus-menerus. `RenderEffect` Android hanya mem-blur isi View itu sendiri, bukan apa yang ada di belakangnya. Blur di belakang jendela (`setBackgroundBlurRadius`) mem-blur seluruh wallpaper sekaligus.

## Keputusan

- Wallpaper bawaan digambar di C++ (`lx::render::renderWallpaper`). Gambar pilihan pengguna di-decode dengan sampling sesuai ukuran layar.
- Salinan seperempat resolusi di-blur sekali dengan box blur tiga lintasan di C++. Hasil terukur di host: 6,6 ms untuk 180x400.
- Setiap panel kaca (`PanelDrawable`) menggambar potongan salinan itu lewat `BitmapShader` sesuai posisinya di layar, lalu menambah tint panel dan garis tipis aksen. Tidak ada blur per frame.
- Neon hanya ada di jendela yang fokus, workspace aktif, dan target drop. Widget memakai tint datar tanpa blur.
- Mode Performance mematikan blur, neon, dan animasi. Mode Balanced memakai radius blur 75%. Mode Quality memakai radius penuh. Kontras tinggi mematikan blur dan glow serta membuat panel pekat.

## Batas yang diketahui

- Wallpaper sistem tidak bisa dibaca aplikasi biasa sejak Android 13 (`READ_WALLPAPER_INTERNAL` berlevel signature). Dengan wallpaper sistem, panel memakai tint lebih pekat tanpa blur.
- Panel yang menumpuk tidak saling mem-blur. Yang di-blur hanya wallpaper.

## Bukti

Drag jendela di Waydroid: 462 frame, 1 jank (0,22%), p99 12 ms.
