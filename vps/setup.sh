#!/usr/bin/env bash
# Penyiapan interaktif yang tahan tempelan dari HP.
# Karakter kontrol (^P dan sejenisnya) dibuang, input salah diminta ulang.
set -uo pipefail

bersih() { tr -d '\000-\037\177' | tr -d ' ' | tr -d '\r'; }

tanya() { # tanya <label> <pola> <petunjuk>
  local label="$1" pola="$2" petunjuk="$3" nilai="" n=0
  while [ "$n" -lt 5 ]; do
    n=$((n + 1))
    printf "%s: " "$label"
    IFS= read -r masuk || masuk=""
    nilai="$(printf '%s' "$masuk" | bersih)"
    if [ -n "$nilai" ] && printf '%s' "$nilai" | grep -qE "$pola"; then
      printf "   diterima: %s\n" "$petunjuk"
      eval "$4=\"\$nilai\""
      return 0
    fi
    if [ -z "$nilai" ]; then
      printf "   kosong. Coba tempel lagi (ketikan terlihat sekarang).\n"
    else
      printf "   bentuknya belum pas. Coba lagi.\n"
    fi
  done
  return 1
}

echo "== Penyiapan layanan REZO (suara + upload aset)"
echo "Tempel nilainya lalu tekan Enter. Ketikan akan terlihat, itu normal."
tanya "Email admin panel" '^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$' "email ok" EMAIL_IN || { echo "Email admin gagal dibaca"; exit 1; }
tanya "Sertifikat Agora (32 huruf/angka)" '^[0-9a-fA-F]{32}$' "32 karakter hex ok" CERT_IN || { echo "Sertifikat Agora gagal dibaca. Bisa juga diketik manual, 32 karakter."; exit 1; }
tanya "Token GitHub (github_pat_...)" '^(github_pat_[A-Za-z0-9_]{20,}|ghp_[A-Za-z0-9]{20,})$' "token ok" GH_IN || { echo "Token GitHub gagal dibaca"; exit 1; }

echo "== Menjalankan pemasangan..."
ADMIN_EMAIL="$EMAIL_IN" CERT="$CERT_IN" GH_TOKEN="$GH_IN" \
  bash <(curl -fsSL https://raw.githubusercontent.com/exbottod/voice-chat-room-real-time/main/vps/agora.sh)
