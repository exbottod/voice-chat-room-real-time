#!/usr/bin/env python3
# Ambil tangkapan layar APK di Android virtual (emulator CI).
# Setiap langkah bersifat "coba saja": kalau satu layar gagal dibuka,
# langkah berikutnya tetap jalan dan hasil yang berhasil tetap disimpan.
import json
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PKG = os.environ.get("APP_PACKAGE", "com.rezochatvoice.exceo")
OUT = os.environ.get("SHOT_DIR", "docs/shots")
EMAIL = os.environ.get("TEST_EMAIL", "")
PASSWORD = os.environ.get("TEST_PASSWORD", "")
hasil = []


def adb(*args, timeout=90):
    try:
        return subprocess.run(["adb", *args], capture_output=True, text=True, timeout=timeout).stdout
    except subprocess.TimeoutExpired:
        return ""


def tunggu(sec):
    time.sleep(sec)


def dump():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    adb("pull", "/sdcard/ui.xml", "/tmp/ui.xml")
    try:
        return ET.parse("/tmp/ui.xml").getroot()
    except Exception:
        return None


def cari_teks(kata):
    root = dump()
    if root is None:
        return None
    kata = kata.lower()
    for node in root.iter("node"):
        for attr in ("text", "content-desc"):
            nilai = (node.get(attr) or "").strip()
            if nilai and kata in nilai.lower():
                b = re.findall(r"\d+", node.get("bounds", ""))
                if len(b) == 4:
                    x1, y1, x2, y2 = map(int, b)
                    return (x1 + x2) // 2, (y1 + y2) // 2
    return None


def ketuk_teks(kata, jeda=2):
    titik = cari_teks(kata)
    if not titik:
        return False
    adb("shell", "input", "tap", str(titik[0]), str(titik[1]))
    tunggu(jeda)
    return True


def isi_kolom(urutan, teks, jeda=1):
    root = dump()
    if root is None:
        return False
    kolom = [n for n in root.iter("node") if "EditText" in (n.get("class") or "")]
    if len(kolom) <= urutan:
        return False
    b = re.findall(r"\d+", kolom[urutan].get("bounds", ""))
    if len(b) != 4:
        return False
    x1, y1, x2, y2 = map(int, b)
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
    tunggu(1)
    adb("shell", "input", "text", teks.replace(" ", "%s"))
    tunggu(jeda)
    return True


def potret(nama, keterangan):
    os.makedirs(OUT, exist_ok=True)
    jalur = os.path.join(OUT, nama + ".png")
    adb("shell", "screencap", "-p", "/sdcard/s.png")
    adb("pull", "/sdcard/s.png", jalur)
    if os.path.exists(jalur) and os.path.getsize(jalur) > 5000:
        hasil.append({"file": os.path.basename(jalur), "judul": keterangan})
        print("tersimpan:", jalur)
        return True
    return False


def main():
    print("paket:", PKG)
    adb("shell", "settings", "put", "system", "user_rotation", "0")
    adb("shell", "am", "start", "-n", PKG + "/.MainActivity")
    tunggu(18)
    # Dialog izin mikrofon
    ketuk_teks("While using", 3) or ketuk_teks("Saat menggunakan", 3)
    tunggu(3)
    potret("01-login", "Halaman masuk")

    if EMAIL and PASSWORD:
        isi_kolom(0, EMAIL)
        isi_kolom(1, PASSWORD)
        adb("shell", "input", "keyevent", "4")  # tutup papan ketik
        tunggu(1)
        ketuk_teks("LOGIN", 8) or ketuk_teks("Masuk", 8)
        tunggu(6)
        potret("02-beranda", "Beranda setelah masuk")
        if ketuk_teks("ROOM", 6) or ketuk_teks("Room", 6):
            potret("03-room", "Daftar room")
            ketuk_teks("Buat", 5) or ketuk_teks("Gabung", 5)
            potret("04-dalam-room", "Di dalam room")
            ketuk_teks("Hadiah", 4) or ketuk_teks("Gift", 4)
            potret("05-daftar-gift", "Daftar gift")
            ketuk_teks("Cashback", 4)
            potret("06-cashback", "Tab cashback")
    with open(os.path.join(OUT, "index.json"), "w") as f:
        json.dump(hasil, f, indent=2, ensure_ascii=False)
    print("total:", len(hasil))


if __name__ == "__main__":
    main()
