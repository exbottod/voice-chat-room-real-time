#!/usr/bin/env python3
# Cek isi APK tanpa perlu memasangnya di HP.
# Hasilnya ditulis ke docs/build-info.json supaya bisa dilihat di halaman web.
import hashlib
import json
import os
import re
import sys
import zipfile

APK = sys.argv[1] if len(sys.argv) > 1 else "app/build/outputs/apk/release/app-release.apk"
OUT = sys.argv[2] if len(sys.argv) > 2 else "docs/build-info.json"

# Penanda fitur di dalam kode APK. Kalau penanda ini tidak ada, fitur itu
# benar-benar tidak ikut terpasang di APK.
FITUR = [
    ("Banner cashback terpisah", "giftCbBanner"),
    ("Banner gift biasa", "giftBanner"),
    ("Animasi cashback di avatar", "GiftFlyOverlay"),
    ("Tombol kombo cashback", "GiftComboBar"),
    ("Roket level 1-5 di room", "RocketLaunchOverlay"),
    ("Peluncuran roket cadangan", "localFx"),
    ("Sambung ulang suara otomatis", "menyambung ulang"),
    ("Tukar diamond nominal sendiri", "Tukar jumlah sendiri"),
    ("Papan peringkat roket", "rocketRank"),
]

def main():
    z = zipfile.ZipFile(APK)
    names = z.namelist()
    dex = b"".join(z.read(n) for n in names if n.endswith(".dex"))

    fitur = []
    for label, marker in FITUR:
        fitur.append({"nama": label, "ada": marker.encode() in dex})

    versi = os.environ.get("VERSION_NAME") or ""
    if not versi:
        m = re.search(rb"1\.0\.(\d{1,4})", dex)
        versi = "1.0." + m.group(1).decode() if m else ""

    sha = hashlib.sha256()
    with open(APK, "rb") as f:
        for blok in iter(lambda: f.read(1 << 20), b""):
            sha.update(blok)

    # Aset roket wajib: 5 level x (launch + burst) = 10 file.
    aset = [n for n in names if n.startswith("assets/rocket/")]
    level_ok = []
    for lv in range(1, 6):
        punya = any(("lv%d_" % lv) in n or ("_%d_" % lv) in n for n in aset)
        level_ok.append({"level": lv, "ada": punya})

    masalah = [f["nama"] for f in fitur if not f["ada"]]
    masalah += ["Aset roket level %d" % x["level"] for x in level_ok if not x["ada"]]

    info = {
        "status": "ok" if not masalah else "ada masalah",
        "masalah": masalah,
        "versi": versi,
        "ukuran_mb": round(os.path.getsize(APK) / (1024 * 1024), 1),
        "sha256": sha.hexdigest(),
        "commit": (os.environ.get("GITHUB_SHA") or "")[:7],
        "waktu": os.environ.get("BUILD_TIME") or "",
        "workflow": os.environ.get("GITHUB_SERVER_URL", "https://github.com") + "/" + (os.environ.get("GITHUB_REPOSITORY") or "") + "/actions",
        "aset_roket": len([n for n in names if n.startswith("assets/rocket")]),
        "aset_total": len([n for n in names if n.startswith("assets/")]),
        "fitur": fitur,
        "roket_level": level_ok,
    }
    with open(OUT, "w") as f:
        json.dump(info, f, indent=2, ensure_ascii=False)
    print(json.dumps(info, ensure_ascii=False, indent=2))
    hilang = [f["nama"] for f in fitur if not f["ada"]]
    if hilang:
        # Ditandai sebagai peringatan, bukan gagal build.
        print("::warning::Fitur tidak terdeteksi di APK: " + ", ".join(hilang))

if __name__ == "__main__":
    main()
