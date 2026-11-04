#!/usr/bin/env bash
# Pemasangan API dompet REZO di VPS sendiri (Ubuntu 24.04).
# Jalankan: sudo bash bootstrap.sh
# Yang dipasang: Node 22, API dompet (kode sama dengan Cloudflare), Caddy HTTPS.
set -euo pipefail

REPO_RAW="${REPO_RAW:-https://raw.githubusercontent.com/exbottod/voice-chat-room-real-time/main}"
TURSO_URL_DEFAULT="https://rezo-voice-chat-officialex.aws-ap-southeast-1.turso.io"
DIR=/opt/rezo-api
APP_USER=rezo

bicara() { echo -e "\n\033[1;32m== $*\033[0m"; }

[ "$(id -u)" -eq 0 ] || { echo "Jalankan dengan sudo."; exit 1; }

if [ -z "${TURSO_TOKEN:-}" ]; then
  echo "Tempel token Turso (hak tulis), lalu Enter. Tidak akan tampil di layar."
  read -r -s TURSO_TOKEN
  echo
fi
[ -n "${TURSO_TOKEN:-}" ] || { echo "Token Turso kosong, berhenti."; exit 1; }
TURSO_URL="${TURSO_URL:-$TURSO_URL_DEFAULT}"

IP=$(curl -4 -s --max-time 10 https://api.ipify.org || hostname -I | awk '{print $1}')
DOMAIN="${DOMAIN:-$(echo "$IP" | tr '.' '-').sslip.io}"

bicara "Menyiapkan paket dasar"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq curl ca-certificates gnupg debian-keyring debian-archive-keyring apt-transport-https

bicara "Memasang Node 22"
if ! command -v node >/dev/null || [ "$(node -v | cut -c2- | cut -d. -f1)" -lt 20 ]; then
  curl -fsSL https://deb.nodesource.com/setup_22.x | bash - >/dev/null
  apt-get install -y -qq nodejs
fi
node -v

bicara "Mengunduh kode dompet"
mkdir -p "$DIR"
curl -fsSL "$REPO_RAW/workers/wallet/src/index.js" -o "$DIR/index.js"
curl -fsSL "$REPO_RAW/vps/server.js" -o "$DIR/server.js"
printf '{"type":"module","private":true}\n' > "$DIR/package.json"
id -u "$APP_USER" >/dev/null 2>&1 || useradd --system --home "$DIR" --shell /usr/sbin/nologin "$APP_USER"
chown -R "$APP_USER":"$APP_USER" "$DIR"

bicara "Menyimpan kredensial"
install -d -m 750 /etc/rezo
umask 077
cat > /etc/rezo/api.env <<EOF
TURSO_URL=$TURSO_URL
TURSO_TOKEN=$TURSO_TOKEN
PORT=8080
HOST=127.0.0.1
EOF
chmod 600 /etc/rezo/api.env

bicara "Membuat layanan systemd"
cat > /etc/systemd/system/rezo-api.service <<EOF
[Unit]
Description=API Dompet REZO
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=$APP_USER
WorkingDirectory=$DIR
EnvironmentFile=/etc/rezo/api.env
ExecStart=/usr/bin/node $DIR/server.js
Restart=always
RestartSec=3
NoNewPrivileges=true
PrivateTmp=true

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable --now rezo-api >/dev/null
sleep 2
systemctl is-active rezo-api

bicara "Memasang Caddy (HTTPS otomatis)"
if ! command -v caddy >/dev/null; then
  apt-get install -y -qq caddy || {
    curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
    curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' > /etc/apt/sources.list.d/caddy-stable.list
    apt-get update -qq && apt-get install -y -qq caddy
  }
fi
cat > /etc/caddy/Caddyfile <<EOF
$DOMAIN {
	encode gzip
	reverse_proxy 127.0.0.1:8080
}
EOF
systemctl restart caddy
sleep 3

bicara "Memeriksa hasil"
echo "lokal :";  curl -s --max-time 8 http://127.0.0.1:8080/health || true; echo
echo "publik:"; curl -s --max-time 20 "https://$DOMAIN/health" || echo "(HTTPS belum siap, tunggu 1-2 menit lalu coba lagi)"

cat <<EOF

Selesai. Alamat API baru:
https://$DOMAIN

Kalau /health publik belum menjawab, biasanya port 80 dan 443 belum dibuka
di panel Helipod/Tencent. Buka HTTP 80 dan HTTPS 443 untuk 0.0.0.0/0,
lalu jalankan: sudo systemctl restart caddy
EOF
