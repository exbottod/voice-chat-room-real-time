#!/bin/bash
# Memasang LiveKit server + layanan token + aturan Caddy. Aman dijalankan ulang.
set -e
VER="1.13.11"
DOMAIN="43-156-99-4.sslip.io"
RAW="https://raw.githubusercontent.com/exbottod/voice-chat-room-real-time/main"
DIR=/opt/rezo-livekit
mkdir -p $DIR

echo "== unduh LiveKit $VER"
if [ ! -x /usr/local/bin/livekit-server ] || ! /usr/local/bin/livekit-server --version 2>/dev/null | grep -q "$VER"; then
  curl -fsSL "https://github.com/livekit/livekit/releases/download/v$VER/livekit_${VER}_linux_amd64.tar.gz" -o /tmp/lk.tgz
  tar -xzf /tmp/lk.tgz -C /usr/local/bin livekit-server
  chmod +x /usr/local/bin/livekit-server
fi
/usr/local/bin/livekit-server --version | head -1

echo "== kunci API (dibuat sekali, dipertahankan)"
if [ ! -f $DIR/kunci.env ]; then
  K="API$(openssl rand -hex 6)"; S="$(openssl rand -hex 24)"
  printf 'LIVEKIT_API_KEY=%s\nLIVEKIT_API_SECRET=%s\n' "$K" "$S" > $DIR/kunci.env
  chmod 600 $DIR/kunci.env
fi
. $DIR/kunci.env

IP_PUBLIK=$(curl -fsS -m 8 https://api.ipify.org || echo 43.156.99.4)
cat > $DIR/livekit.yaml <<YAML
port: 7880
bind_addresses: ["127.0.0.1"]
rtc:
  tcp_port: 7881
  port_range_start: 50000
  port_range_end: 50100
  use_external_ip: false
  node_ip: $IP_PUBLIK
  allow_tcp_fallback: true
  ice_disconnected_timeout: 30
  ice_failed_timeout: 60
keys:
  $LIVEKIT_API_KEY: $LIVEKIT_API_SECRET
room:
  empty_timeout: 300
  departure_timeout: 60
  max_participants: 200
  enable_remote_unmute: true
logging:
  level: info
YAML

cat > /etc/systemd/system/rezo-livekit.service <<UNIT
[Unit]
Description=LiveKit server (suara REZO)
After=network.target
[Service]
ExecStart=/usr/local/bin/livekit-server --config $DIR/livekit.yaml
Restart=always
RestartSec=3
LimitNOFILE=65535
[Install]
WantedBy=multi-user.target
UNIT

echo "== layanan token"
curl -fsSL "$RAW/vps/livekit/token.mjs" -o $DIR/token.mjs
cat > /etc/systemd/system/rezo-lktoken.service <<UNIT
[Unit]
Description=Token LiveKit REZO
After=network.target
[Service]
EnvironmentFile=$DIR/kunci.env
Environment=PORT=8091
Environment=FIREBASE_PROJECT_ID=arboreal-cat-511705-p6
ExecStart=$(command -v node) $DIR/token.mjs
Restart=always
RestartSec=3
[Install]
WantedBy=multi-user.target
UNIT

echo "== firewall (ufw / iptables bila aktif)"
if command -v ufw >/dev/null && ufw status | grep -q active; then
  ufw allow 7881/tcp >/dev/null; ufw allow 50000:50100/udp >/dev/null
fi
iptables -C INPUT -p tcp --dport 7881 -j ACCEPT 2>/dev/null || iptables -I INPUT -p tcp --dport 7881 -j ACCEPT 2>/dev/null || true
iptables -C INPUT -p udp --dport 50000:50100 -j ACCEPT 2>/dev/null || iptables -I INPUT -p udp --dport 50000:50100 -j ACCEPT 2>/dev/null || true

echo "== Caddy"
CF=/etc/caddy/Caddyfile
if ! grep -q "/livekit" $CF; then
  cp $CF $CF.bak
  python3 - <<'PY'
p="/etc/caddy/Caddyfile"
s=open(p).read()
add="""	handle /lktoken* {
		reverse_proxy 127.0.0.1:8091
	}
	handle_path /livekit* {
		reverse_proxy 127.0.0.1:7880
	}
"""
i=s.index("\thandle /agora*")
open(p,"w").write(s[:i]+add+s[i:])
PY
fi
caddy validate --config $CF --adapter caddyfile >/dev/null 2>&1 && systemctl reload caddy || echo "PERINGATAN: Caddyfile tidak valid, dikembalikan"
caddy validate --config $CF --adapter caddyfile >/dev/null 2>&1 || cp $CF.bak $CF

systemctl daemon-reload
systemctl enable --now rezo-livekit rezo-lktoken >/dev/null 2>&1
systemctl restart rezo-livekit rezo-lktoken
sleep 3

echo "== pemeriksaan"
echo "livekit  : $(systemctl is-active rezo-livekit)"
echo "token    : $(systemctl is-active rezo-lktoken)"
echo "health   : $(curl -s -m 8 http://127.0.0.1:8091/lktoken/health)"
echo "lk 7880  : $(curl -s -o /dev/null -w '%{http_code}' -m 8 http://127.0.0.1:7880/)"
echo "publik   : $(curl -s -o /dev/null -w '%{http_code}' -m 10 https://$DOMAIN/lktoken/health)"
echo "ip publik: $IP_PUBLIK"
