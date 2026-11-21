# dracxterm in LX

Tedit's terminal is dracxterm's terminal. This folder holds the parts of dracxterm 1.1.3 that LX builds into Tedit,
kept apart from LX's own code so they can be compared with dracxterm and updated from it.

Source: dracxterm 1.1.3 by the same owner, commit 6ce5a43b3a6699a357d18caf9ea13ad3db349b89, and its release APK
`dracxterm-1.1.3-vc9-release.apk` (SHA-256 f544f65c4f7dbdc1ae1649ef191999293c715c3b1124d928d98a0bea48b6223b).
Licence: GPL-3.0 (see LICENSE). The LX APK as a whole is already distributed under GPL-3.0 (see NOTICE), so these
files keep their licence; LX's own source stays Apache-2.0.

## Copied unchanged

- `cpp/native-lib.cpp`, `cpp/engine/**`, `cpp/core/logger/Log.h`, `cpp/include/xterm/*`: the terminal engine
  (pseudo terminal, ANSI/VT parser, screen buffer with scrollback, selection, search, UTF-8) and its JNI binding.
  Built as `libxterm.so` by `app/src/main/cpp/CMakeLists.txt`.
- `kotlin/com/dracxterm/NativeTerminal.kt`: the Kotlin side of that binding.
- `assets/dracxterm/bashrc`: dracxterm's prompt (`DRAC⚔TERM > (device)`), installed in Debian as the dracos user's
  `~/.bashrc`.
- `assets/dracxterm/compat/{sudo,su,fakeroot,whoami,id,logname}`: the identity shims dracxterm installs in
  `/usr/local/bin` and `/usr/local/sbin` of the guest.
- `jniLibs/<abi>/libbusybox.so`: BusyBox 1.38.0 from the release APK, byte for byte the file in dracxterm's
  `app/src/main/jniLibs`; dracxterm builds it from source with `prebuilts/busybox/build.sh` (GPL-2.0, source and
  recipe pinned in dracxterm's `prebuilts/manifest.env`). It is the shell when Debian is not set up.

## Copied and changed

- `kotlin/com/dracxterm/TerminalSession.kt`: `start` takes the command line from the caller instead of dracxterm's
  `Bootstrap`, which also sets up claw, Ollama and the distro catalogue that Tedit does not include.
- `kotlin/com/dracxterm/TerminalView.kt`: the renderer and input view, without the in-terminal `xset` dashboard, the
  asset fonts and the first-run zoom that fits dracxterm's 78-column login banner. The host now sets the font
  (Tedit's code font) and the size (Tedit's terminal font setting), and may show the keyboard its own way. Three
  small additions serve Tedit's panel: a hook told of input from the person (so Tedit knows when the shell waits at
  its prompt), a short settle time before a new size reaches the shell, with a hook told once it has, and
  `performClick` for screen readers.
  `CursorStyle` moved into this file from dracxterm's `xset` package.

Every change is marked "Changed for LX's Tedit" in the file. The shell setup itself (which shell, its environment,
the prompt for BusyBox, the guest home and shims) is LX code in
`app/src/main/kotlin/com/lxlauncher/tedit/DxShell.kt`, written after dracxterm's `Bootstrap.kt`.

## Not included

claw, Ollama, Scrapling, the `xset` settings program, the Kali and Debian download catalogue, PRoot (Tedit's Debian
runs on VHDP only, as before), the login banner, and the `ifconfig` and `nmap` shims, which read files only
dracxterm's app writes.

Checksums of every file here are in SOURCES.txt.
