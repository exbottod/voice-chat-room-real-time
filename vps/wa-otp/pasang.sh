#!/usr/bin/env bash
# Pasang gateway WhatsApp OTP di VPS. Jalankan sekali: bash pasang.sh
set -e
DIR=/opt/wa-otp
SECRET=${WA_OTP_SECRET:-$(head -c 24 /dev/urandom | base64 | tr -d '/+=' | head -c 32)}

command -v node >/dev/null || { echo "Node.js belum ada. Pasang dulu: curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && apt install -y nodejs"; exit 1; }

mkdir -p "$DIR/auth"
cp server.mjs package.json "$DIR"/
cd "$DIR"
npm install --omit=dev

cat > /etc/systemd/system/wa-otp.service <<SVC
[Unit]
Description=Gateway WhatsApp OTP REZO
After=network-online.target

[Service]
Type=simple
WorkingDirectory=$DIR
Environment=PORT=8099
Environment=WA_OTP_SECRET=$SECRET
ExecStart=/usr/bin/node $DIR/server.mjs
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
SVC

systemctl daemon-reload
systemctl enable --now wa-otp
sleep 3
systemctl --no-pager status wa-otp | head -20

echo
echo "================ SIMPAN INI ================"
echo "WA_OTP_SECRET = $SECRET"
echo "Alamat health  = http://127.0.0.1:8099/health"
echo "Lihat QR       = journalctl -u wa-otp -n 200 -f"
echo "============================================"
