#!/usr/bin/env bash
# Perbarui kode dompet di VPS lalu jalankan ulang layanannya.
# Pakai: bash <(curl -fsSL https://raw.githubusercontent.com/exbottod/voice-chat-room-real-time/main/vps/update.sh)
set -euo pipefail
DIR="${DIR:-/opt/rezo-api}"
RAW="${REPO_RAW:-https://raw.githubusercontent.com/exbottod/voice-chat-room-real-time/main}"
[ "$(id -u)" = "0" ] || { echo "Jalankan sebagai root"; exit 1; }
echo "Mengunduh kode dompet terbaru..."
curl -fsSL "$RAW/workers/wallet/src/index.js" -o "$DIR/index.js"
curl -fsSL "$RAW/vps/server.js" -o "$DIR/server.js"
node --check "$DIR/index.js"
systemctl restart rezo-api
sleep 2
echo -n "status layanan : "; systemctl is-active rezo-api
echo -n "cek lokal      : "; curl -s --max-time 8 http://127.0.0.1:8080/health || echo "(gagal)"
echo
echo "Selesai. Versi dompet di VPS sudah yang terbaru."
