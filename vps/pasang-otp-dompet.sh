#!/usr/bin/env bash
# Sambungkan dompet VPS ke gateway WhatsApp OTP.
# Pakai: sudo bash <(curl -fsSL https://raw.githubusercontent.com/exbottod/voice-chat-room-real-time/main/vps/pasang-otp-dompet.sh)
set -e
[ "$(id -u)" = "0" ] || { echo "Jalankan dengan sudo"; exit 1; }
RAW="${REPO_RAW:-https://raw.githubusercontent.com/exbottod/voice-chat-room-real-time/main}"
DIR=/opt/rezo-api

SECRET=$(grep -oP 'WA_OTP_SECRET=\K.*' /etc/systemd/system/wa-otp.service | head -1)
[ -n "$SECRET" ] || { echo "WA_OTP_SECRET tidak ada. Pasang gateway dulu dengan pasang.sh"; exit 1; }

echo "Mengunduh kode dompet terbaru..."
curl -fsSL "$RAW/workers/wallet/src/index.js" -o "$DIR/index.js"
curl -fsSL "$RAW/vps/server.js" -o "$DIR/server.js"
node --check "$DIR/index.js"

mkdir -p /etc/systemd/system/rezo-api.service.d
cat > /etc/systemd/system/rezo-api.service.d/otp.conf <<CONF
[Service]
Environment=WA_OTP_URL=http://127.0.0.1:8099
Environment=WA_OTP_SECRET=$SECRET
CONF

systemctl daemon-reload
systemctl restart rezo-api
sleep 3
echo -n "status dompet : "; systemctl is-active rezo-api
echo -n "health        : "; curl -s --max-time 8 http://127.0.0.1:8080/health || echo "(gagal)"
echo
echo "Uji kirim kode ke nomor Anda sendiri:"
echo "curl -s -X POST https://43-156-99-4.sslip.io/otp/minta -H 'Content-Type: application/json' -d '{\"nomor\":\"6281380331213\"}'"
