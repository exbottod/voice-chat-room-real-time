#!/usr/bin/env bash
# Memperbarui layanan yang SUDAH terpasang di VPS tanpa menyentuh sertifikat
# Agora (sertifikat lama dipertahankan). Yang ditambahkan hanya email admin dan
# token GitHub, sekaligus memasang rumus token suara terbaru (4 izin).
set -uo pipefail
REPO="exbottod/voice-chat-room-real-time"
RAW="https://raw.githubusercontent.com/$REPO/main"
UNIT=/etc/systemd/system/rezo-agora.service
DIR=/opt/rezo-agora

bersih() { tr -d '\000-\037\177' | tr -d ' \r'; }
tanya() {
  local label="$1" pola="$2" nilai="" n=0
  while [ "$n" -lt 5 ]; do
    n=$((n + 1))
    printf "%s: " "$label"
    IFS= read -r masuk || masuk=""
    nilai="$(printf '%s' "$masuk" | bersih)"
    if [ -n "$nilai" ] && printf '%s' "$nilai" | grep -qE "$pola"; then
      printf "   ok\n"; eval "$3=\"\$nilai\""; return 0
    fi
    printf "   belum pas/kosong, coba lagi\n"
  done
  return 1
}

if [ ! -f "$UNIT" ]; then
  echo "Layanan belum pernah dipasang. Jalankan dulu vps/agora.sh."; exit 1
fi

EMAIL_IN="${ADMIN_EMAIL:-}"; GH_IN="${GH_TOKEN:-}"
# Mode tanpa tanya-jawab (dipakai GitHub Actions): nilai datang dari env.
if [ -z "$EMAIL_IN" ] || [ -z "$GH_IN" ]; then
  tanya "Email admin panel" '^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$' EMAIL_IN || { echo "email gagal"; exit 1; }
  tanya "Token GitHub" '^(github_pat_[A-Za-z0-9_]{20,}|ghp_[A-Za-z0-9]{20,})$' GH_IN || { echo "token gagal"; exit 1; }
fi

echo "== Memasang kode terbaru"
sudo mkdir -p "$DIR"
sudo curl -fsSL "$RAW/token-worker/worker-standalone.js" | sudo tee "$DIR/worker.js" >/dev/null
sudo curl -fsSL "$RAW/vps/server.mjs" | sudo tee "$DIR/server.mjs" >/dev/null
printf '{"type":"module"}\n' | sudo tee "$DIR/package.json" >/dev/null

echo "== Memperbarui pengaturan layanan"
sudo sed -i '/^Environment=ADMIN_EMAIL=/d;/^Environment=GITHUB_TOKEN=/d;/^Environment=GITHUB_REPO=/d;/^Environment=GITHUB_BRANCH=/d' "$UNIT"
sudo sed -i "/^Environment=AGORA_APP_CERTIFICATE=/a Environment=ADMIN_EMAIL=$EMAIL_IN\nEnvironment=GITHUB_TOKEN=$GH_IN\nEnvironment=GITHUB_REPO=$REPO\nEnvironment=GITHUB_BRANCH=main" "$UNIT"
sudo systemctl daemon-reload
sudo systemctl restart rezo-agora
sleep 3

echo "== Pemeriksaan"
printf "layanan     : "; systemctl is-active rezo-agora
printf "token suara : "; curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:8090/agora
printf "sertifikat  : "; sudo sed -n 's/^Environment=AGORA_APP_CERTIFICATE=\(.*\)/ada (\1)/p' "$UNIT" | sed 's/ada (\(..\).*/ada, diawali \1.../'
printf "github      : "; curl -s -H "Authorization: Bearer $GH_IN" https://api.github.com/repos/$REPO | sed -n 's/.*"full_name": *"\([^"]*\)".*/tulis ke \1/p' | head -1
printf "upload      : "; curl -s -o /dev/null -w "%{http_code} (401 wajar, berarti layanan menolak tanpa login)\n" -X POST http://127.0.0.1:8090/agora/upload
