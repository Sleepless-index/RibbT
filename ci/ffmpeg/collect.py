"""Gathers the lean ffmpeg build into ffmpeg-lean.zip: the two programs and every shared library they need.

Runs inside the Termux builder container after build-package.sh: the built package is in output/, the
libraries it depends on are installed under the Termux prefix. Libraries are followed from each
program's NEEDED entries and copied under their SONAME (symbolic links are resolved), so the zip holds
plain files only.
"""
import glob
import os
import shutil
import subprocess
import sys
import zipfile

PREFIX = "/data/data/com.termux/files/usr"
# Provided by Android itself (or shipped separately by the app).
SYSTEM = {
    "libc.so", "libm.so", "libdl.so", "liblog.so", "libz.so", "libandroid.so", "libmediandk.so",
    "libc++_shared.so", "libvulkan.so", "libOpenSLES.so", "libEGL.so", "libGLESv2.so", "libnativewindow.so",
}


def find_readelf():
    found = shutil.which("llvm-readelf")
    if found:
        return found
    for pattern in ("/home/builder/lib/android-ndk*/toolchains/llvm/prebuilt/*/bin/llvm-readelf", "/**/llvm-readelf"):
        hits = glob.glob(pattern, recursive=True)
        if hits:
            return hits[0]
    sys.exit("llvm-readelf not found")


READELF = find_readelf()


def needed(path):
    text = subprocess.check_output([READELF, "-d", path], text=True)
    names = []
    for line in text.splitlines():
        if "(NEEDED)" in line and "[" in line:
            names.append(line.split("[", 1)[1].split("]", 1)[0])
    return names


debs = sorted(glob.glob("output/ffmpeg_*.deb"))
if not debs:
    sys.exit("no ffmpeg deb in output/")
extract = "/tmp/ffmpeg-deb"
shutil.rmtree(extract, ignore_errors=True)
subprocess.check_call(["dpkg-deb", "-x", debs[-1], extract])
built = extract + PREFIX

search = [built + "/lib", PREFIX + "/lib"]
programs = {name: os.path.join(built, "bin", name) for name in ("ffmpeg", "ffprobe")}
for path in programs.values():
    if not os.path.isfile(path):
        sys.exit(f"{path} missing")

libs = {}
queue = [p for p in programs.values()]
while queue:
    current = queue.pop()
    for name in needed(current):
        if name in SYSTEM or name in libs:
            continue
        for folder in search:
            candidate = os.path.join(folder, name)
            if os.path.exists(candidate):
                libs[name] = os.path.realpath(candidate)
                queue.append(libs[name])
                break
        else:
            sys.exit(f"{name} (needed by {current}) not found")

out = "output/ffmpeg-lean.zip"
total = 0
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
    for name, path in sorted(libs.items()):
        archive.write(path, "usr/lib/" + name)
        total += os.path.getsize(path)
        print(f"{os.path.getsize(path) / 1e6:7.2f} MB  {name}")
    for name, path in programs.items():
        archive.write(path, "bin/" + name)
        print(f"{os.path.getsize(path) / 1e6:7.2f} MB  bin/{name}")
print(f"libraries: {total / 1e6:.1f} MB unpacked, zip {os.path.getsize(out) / 1e6:.1f} MB")
print("libavcodec needs:", needed(libs["libavcodec.so.63"]) if "libavcodec.so.63" in libs else "?")
