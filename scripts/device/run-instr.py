#!/usr/bin/env python3
"""Runs the self-test instrumentation with `-e only <part>` on the phone, between a checked backup and restore of
LX's data. Usage: ANDROID_SERIAL=... run-instr.py <part> <out.txt>"""
import os, subprocess, sys, time
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
from ui_test import sh  # noqa
from ui_test_file_manager import adb_cmd, restore_data, save_data  # noqa
part, out = sys.argv[1], sys.argv[2]
backup = out + ".lxdata.tar"
# Debian in no_backup is the owner's, and the backup above does not cover it. Shells the tests start append their
# commands to its bash history; the history is copied here and put back as it was once the run is over.
HIST = "no_backup/linux/debian/home/dracos/.bash_history"


def read_history():
    r = subprocess.run(adb_cmd() + ["exec-out", f"run-as com.lxlauncher sh -c 'if [ -f {HIST} ]; then echo Y; cat {HIST}; else echo N; fi'"],
                       capture_output=True, timeout=60)
    head, _, body = r.stdout.partition(b"\n")
    return body if head.strip() == b"Y" else None


def put_history(data):
    # exec-in ends the remote command once its input is used up, so the copy is written first and moved in after.
    subprocess.run(adb_cmd() + ["exec-in", f"run-as com.lxlauncher sh -c 'cat > {HIST}.lxtmp'"], input=data, timeout=60, check=True)
    subprocess.run(adb_cmd() + ["shell", f"run-as com.lxlauncher sh -c 'chmod 600 {HIST}.lxtmp && mv {HIST}.lxtmp {HIST}'"], timeout=60, check=True)
    if read_history() != data:
        raise RuntimeError("Debian's bash history did not come back as it was")


history = read_history()
save_data(backup)
try:
    with open(out, "w") as f:
        p = subprocess.Popen(adb_cmd() + ["shell", ("am instrument -w " + ("" if part == "all" else f"-e only {part} ") + "com.lxlauncher.test/com.lxlauncher.test.SelfTestInstrumentation")],
                             stdout=f, stderr=subprocess.STDOUT)
        # The ROM freezes LX in the background; HOME brings it forward and thaws it (see the phone notes).
        time.sleep(4)
        # `-e only windows` starts LauncherActivity in a task of its own; HOME would bring the other one forward.
        if os.environ.get("NO_HOME") != "1":
            sh("input keyevent KEYCODE_HOME", check=False)
        # Optional: a simpleperf recording of LX that starts PERF_AT seconds in and lasts PERF_FOR seconds.
        perf = None
        if os.environ.get("PERF_AT"):
            time.sleep(max(0, float(os.environ["PERF_AT"]) - 4))
            perf = subprocess.Popen(adb_cmd() + ["shell", "simpleperf record --app com.lxlauncher -g -f 1000 --duration "
                                    + os.environ.get("PERF_FOR", "30") + " -o /data/local/tmp/lx.perf.data"],
                                    stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)
        keep_home = os.environ.get("KEEP_HOME") == "1"
        while p.poll() is None:
            time.sleep(15)
            if keep_home and p.poll() is None:
                sh("input keyevent KEYCODE_HOME", check=False)
finally:
    if 'perf' in dir() and perf is not None:
        perf.wait()
    restore_data(backup)
    if history is not None and read_history() != history:
        put_history(history)
    os.remove(backup)
    sh("input keyevent KEYCODE_HOME", check=False)
print(open(out).read()[-3000:])
