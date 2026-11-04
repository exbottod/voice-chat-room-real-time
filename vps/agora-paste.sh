#!/usr/bin/env bash
# Pemindah layanan token Agora ke VPS, tanpa perlu menyunting perintah.
# Tempel App Certificate saat diminta (tidak ditampilkan di layar).
set -e
read -rsp "Tempel App Certificate lalu tekan Enter: " CERT
echo
CERT="$(printf '%s' "$CERT" | tr -d '[:space:]')"
if [ -z "$CERT" ]; then echo "Sertifikat kosong, dibatalkan."; exit 1; fi
if [ "${#CERT}" -ne 32 ]; then echo "Panjang sertifikat ${#CERT} karakter, seharusnya 32. Coba salin ulang."; exit 1; fi
export CERT
BASE="https://raw.githubusercontent.com/exbottod/voice-chat-room-real-time/main/vps"
curl -fsSL "$BASE/agora.sh" -o /tmp/agora.sh
exec bash /tmp/agora.sh
