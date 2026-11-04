#!/usr/bin/env bash
# Memindahkan layanan token Agora ke VPS.
# Sertifikat boleh sudah diisi lewat CERT=... atau ditempel saat diminta.
set -e
if [ -n "${CERT:-}" ]; then
  echo "Sertifikat sudah terisi lewat perintah."
else
  echo "Tempel App Certificate (32 karakter) lalu tekan Enter."
  echo "Kalau tempelan macet, tekan Ctrl+C dan pakai cara CERT=... seperti di panduan."
  printf 'Certificate: '
  read -r CERT
fi
CERT="$(printf '%s' "$CERT" | tr -d '[:space:]")"
if [ -z "$CERT" ]; then echo "Sertifikat kosong, dibatalkan."; exit 1; fi
if [ "${#CERT}" -ne 32 ]; then echo "Panjang ${#CERT} karakter, seharusnya 32. Salin ulang."; exit 1; fi
export CERT
BASE="https://raw.githubusercontent.com/exbottod/voice-chat-room-real-time/main/vps"
curl -fsSL "$BASE/agora.sh" -o /tmp/agora.sh
exec bash /tmp/agora.sh
