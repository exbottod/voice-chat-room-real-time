#!/usr/bin/env python3
"""Pembuat file selisih (patch) untuk pembaruan APK kecil.

Membandingkan APK versi lama dengan APK versi baru, lalu menulis file
"hanya bagian yang berubah" (format BSDIFF40). Aplikasi mengunduh file ini,
menempelkannya ke APK lama di HP, dan hasilnya menjadi APK baru yang utuh.

Pemakaian:
  python3 scripts/buat_patch.py --lama lama.apk --baru baru.apk \
      --kunci arm64 --keluaran patch-arm64.bin \
      --versi-lama 1.0.821 --versi-baru 1.0.822 --json docs/patch-info.json

Kalau --lama tidak ada (rilis pertama), skrip tetap menulis manifest tanpa
entri patch untuk kunci tersebut.
"""
import argparse
import hashlib
import json
import os
import sys


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for blok in iter(lambda: f.read(1 << 20), b""):
            h.update(blok)
    return h.hexdigest()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--lama", required=True)
    ap.add_argument("--baru", required=True)
    ap.add_argument("--kunci", required=True, help="arm64 / armv7 / universal")
    ap.add_argument("--keluaran", required=True)
    ap.add_argument("--versi-lama", default="")
    ap.add_argument("--versi-baru", default="")
    ap.add_argument("--json", default="docs/patch-info.json")
    args = ap.parse_args()

    info = {}
    if os.path.exists(args.json):
        try:
            info = json.load(open(args.json))
        except Exception:
            info = {}
    info["versi_lama"] = args.versi_lama
    info["versi_baru"] = args.versi_baru

    if args.versi_lama and args.versi_lama == args.versi_baru:
        print("Versi lama sama dengan versi baru, patch dilewati.")
        info.pop(args.kunci, None)
        os.makedirs(os.path.dirname(args.json) or ".", exist_ok=True)
        json.dump(info, open(args.json, "w"), indent=2, ensure_ascii=False)
        return 0

    if not (os.path.exists(args.lama) and os.path.exists(args.baru)):
        print("APK lama/baru tidak lengkap, patch dilewati untuk", args.kunci)
        info.pop(args.kunci, None)
    else:
        import bsdiff4

        lama = open(args.lama, "rb").read()
        baru = open(args.baru, "rb").read()
        if lama == baru:
            print("APK identik, tidak perlu patch.")
            info.pop(args.kunci, None)
        else:
            patch = bsdiff4.diff(lama, baru)
            with open(args.keluaran, "wb") as f:
                f.write(patch)
            # Uji dulu: hasil gabungan harus sama persis dengan APK baru.
            if bsdiff4.patch(lama, patch) != baru:
                print("PERINGATAN: uji gabung patch gagal untuk", args.kunci)
                info.pop(args.kunci, None)
            else:
                info[args.kunci] = {
                    "file": os.path.basename(args.keluaran),
                    "ukuran": len(patch),
                    "sha256": sha256(args.baru),
                    "apk_ukuran": len(baru),
                }
                print(
                    "%s: patch %.2f MB (APK %.1f MB)"
                    % (args.kunci, len(patch) / 1048576, len(baru) / 1048576)
                )

    os.makedirs(os.path.dirname(args.json) or ".", exist_ok=True)
    with open(args.json, "w") as f:
        json.dump(info, f, indent=2, ensure_ascii=False)
    print("Manifest ditulis:", args.json)


if __name__ == "__main__":
    sys.exit(main())
