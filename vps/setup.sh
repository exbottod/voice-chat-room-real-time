#!/usr/bin/env bash
# Penyiapan interaktif: cukup tempel nilai saat diminta, tidak perlu menyusun
# perintah panjang. Dipakai sekali (atau saat nilai berganti).
set -euo pipefail
echo "== Penyiapan layanan REZO (suara + upload aset)"
printf "Email admin panel (Google): "; read -r EMAIL_IN
printf "Sertifikat Agora: "; read -rs CERT_IN; echo
printf "Token GitHub (github_pat_...): "; read -rs GH_IN; echo
if [ -z "${EMAIL_IN:-}" ]; then echo "Email admin belum diisi"; exit 1; fi
if [ -z "${CERT_IN:-}" ]; then echo "Sertifikat Agora belum diisi"; exit 1; fi
if [ -z "${GH_IN:-}" ]; then echo "Token GitHub belum diisi"; exit 1; fi
case "$CERT_IN" in
  *[!0-9a-fA-F]*) echo "Sertifikat Agora harus 32 huruf/angka hex"; exit 1 ;;
esac
if [ "${#CERT_IN}" -ne 32 ]; then echo "Panjang sertifikat Agora harus 32, ini ${#CERT_IN}"; exit 1; fi
echo "== Menjalankan pemasangan..."
ADMIN_EMAIL="$EMAIL_IN" CERT="$CERT_IN" GH_TOKEN="$GH_IN" \
  bash <(curl -fsSL https://raw.githubusercontent.com/exbottod/voice-chat-room-real-time/main/vps/agora.sh)
