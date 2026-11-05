#!/usr/bin/env bash
# Tautkan nomor WhatsApp pengirim OTP. Pakai: sudo bash pair.sh 628xxxxxxxxxx
set -e
NOMOR="${1:-}"
DIR=/opt/wa-otp
RAW="${REPO_RAW:-https://raw.githubusercontent.com/exbottod/voice-chat-room-real-time/main/vps/wa-otp}"
[ "$(id -u)" = "0" ] || { echo "Jalankan dengan sudo"; exit 1; }
NOMOR=$(printf '%s' "$NOMOR" | tr -cd '0-9')
[ -n "$NOMOR" ] || { echo "Sertakan nomor: sudo bash pair.sh 628xxxxxxxxxx"; exit 1; }
SECRET=$(grep -oP 'WA_OTP_SECRET=\K.*' /etc/systemd/system/wa-otp.service | head -1)
[ -n "$SECRET" ] || { echo "WA_OTP_SECRET tidak ditemukan, pasang ulang dengan pasang.sh"; exit 1; }

cd "$DIR"
curl -fsSL "$RAW/server.mjs" -o "$DIR/server.mjs"
curl -fsSL "$RAW/package.json" -o "$DIR/package.json"
npm install --omit=dev >/dev/null 2>&1
find "$DIR/auth" -mindepth 1 -delete 2>/dev/null || true
mkdir -p "$DIR/auth"

cat > /etc/systemd/system/wa-otp.service <<SVC
[Unit]
Description=Gateway WhatsApp OTP REZO
After=network-online.target

[Service]
Type=simple
WorkingDirectory=$DIR
Environment=PORT=8099
Environment=WA_OTP_SECRET=$SECRET
Environment=WA_PAIR_NUMBER=$NOMOR
ExecStart=/usr/bin/node $DIR/server.mjs
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
SVC

systemctl daemon-reload
systemctl restart wa-otp
echo "Menunggu kode tautkan untuk nomor $NOMOR ..."
KODE=""
for i in $(seq 1 30); do
  KODE=$(journalctl -u wa-otp -n 80 --no-pager 2>/dev/null | grep -oP 'KODE: \K[A-Z0-9-]{8}' | tail -1)
  [ -n "$KODE" ] && break
  sleep 2
done
echo
if [ -n "$KODE" ]; then
  echo "=============================================="
  echo "  KODE TAUTKAN: $KODE"
  echo "  Buka WhatsApp nomor $NOMOR, lalu:"
  echo "  Perangkat tertaut > Tautkan perangkat > Tautkan dengan nomor telepon"
  echo "  Kode berlaku sekitar 1 menit. Kalau habis, ulangi skrip ini."
  echo "=============================================="
else
  echo "Kode belum muncul. Lihat log: journalctl -u wa-otp -n 120 -f"
fi
