# Gambaran arsitektur

```
┌──────────────────────── Android (Kotlin) ─────────────────────────┐
│ LauncherActivity        Home activity, layout, input, picker       │
│ ui/desktop  ui/windows  ui/panels  ui/overlays  tools  widgets     │
│ apps/AppRepository      LauncherApps, ikon, launch, shortcut       │
│ widgets/AndroidWidgets  AppWidgetHost, bind, configure             │
│ core/LauncherModel      snapshot, debounce save di thread lain     │
│ core/NativeCore         deklarasi JNI                              │
└──────────────────────────────┬─────────────────────────────────────┘
                               │ jni_bridge.cpp (UTF-8 benar, mutex)
┌──────────────────────────────┴───── core C++20 (core/) ────────────┐
│ Engine        menyatukan semuanya, migrasi skema, impor/ekspor     │
│ Desktop       workspace, pintasan, folder, widget, pin list        │
│ WindowManager jendela LX: z-order, fokus, state, snap, tile        │
│ Settings      skema bertipe, preset                                │
│ json          parser dan writer berbatas                           │
│ persistence   CRC32, amplop, penulisan atomik, cadangan            │
│ search        ranking label                                        │
│ render        wallpaper prosedural, box blur                       │
│ validate      kunci aplikasi, nama, referensi wallpaper            │
└────────────────────────────────────────────────────────────────────┘
```

`lxdb/` berdiri di luar diagram ini: executable C++20 dan assembly yang berjalan sebagai uid shell dan mengelola jendela aplikasi Android. LX berbicara dengannya lewat Binder (`IEmbedService.aidl`), dan ia berbicara langsung dengan Android lewat `/dev/binder`. Lihat ADR-008.

## Aliran data

1. UI memanggil `LauncherModel.edit { NativeCore.xxx(handle, ...) }`.
2. Core memvalidasi dan mengubah state di bawah mutex engine.
3. Kalau berhasil, model mengambil `snapshot()` (JSON) dan mengabarkan listener: activity, panel, desktop, jendela.
4. Penyimpanan dijadwalkan 400 ms kemudian di thread `lx-save`. Saat `onStop`, penyimpanan langsung dijadwalkan.

Drag jendela memakai `LauncherModel.live`: tanpa snapshot dan tanpa penyimpanan per frame. Snapshot diambil sekali saat jari diangkat.

## Siklus hidup

- Engine dibuat di `LxApp.onCreate` dan hidup selama proses hidup. Rotasi dan pembuatan ulang activity tidak menyentuhnya.
- Widget LX hanya mendengarkan update (broadcast baterai, callback jaringan, timer) selama activity terlihat dan workspace-nya aktif.
- System Monitor berhenti me-refresh saat jendelanya diminimalkan atau activity berhenti.
- Perubahan density, ukuran huruf, mode UI, atau aksen membangun ulang tree View dari snapshot.

## Lokasi berkas penting

| Berkas | Isi |
|---|---|
| `core/include/lx/*.hpp` | API core |
| `app/src/main/cpp/jni_bridge.cpp` | Seluruh batas JNI |
| `app/src/main/kotlin/com/lxlauncher/ui/theme/Tokens.kt` | Design token: warna, tipografi, motion, target sentuh |
| `app/src/main/kotlin/com/lxlauncher/ui/theme/Glass.kt` | Panel kaca dan neon |
| `tests/` | Test C++ host |
| `app/src/androidTest/` | Instrumentation di perangkat |
| `scripts/ui_test*.py` | UI test end-to-end |
