#!/bin/bash
# Memperbaiki sertifikat Agora yang terduplikasi (64 -> 32 karakter).
# Aman: hanya menulis kalau sidik hasil potongan cocok dengan yang diharapkan.
set -e
HARAP="37ff6a4be3ebee00831a8c7fe37c80edfffc9829f4744644c920ec90e7249328"
ENV_TEXT=$(systemctl show rezo-agora -p Environment --value)
C=$(printf '%s\n' "$ENV_TEXT" | tr ' ' '\n' | grep '^AGORA_APP_CERTIFICATE=' | head -1 | cut -d= -f2- | tr -d '"')
echo "panjang sekarang: ${#C}"
cek() { printf '%s' "$1" | sha256sum | cut -d' ' -f1; }
if [ "$(cek "$C")" = "$HARAP" ]; then echo "sertifikat sudah benar, tidak ada perubahan"; exit 0; fi
KANDIDAT=""
for potong in "${C:0:32}" "${C: -32}"; do
  if [ "$(cek "$potong")" = "$HARAP" ]; then KANDIDAT="$potong"; break; fi
done
if [ -z "$KANDIDAT" ]; then echo "GAGAL: potongan 32 karakter tidak cocok dengan sidik yang diharapkan. Tidak mengubah apa pun."; exit 2; fi
U=/etc/systemd/system/rezo-agora.service
cp $U $U.bak
sed -i "s|AGORA_APP_CERTIFICATE=${C}|AGORA_APP_CERTIFICATE=${KANDIDAT}|g" $U
systemctl daemon-reload
systemctl restart rezo-agora
sleep 2
echo "layanan: $(systemctl is-active rezo-agora)"
N=$(systemctl show rezo-agora -p Environment --value | tr ' ' '\n' | grep '^AGORA_APP_CERTIFICATE=' | head -1 | cut -d= -f2- | tr -d '"')
echo "panjang baru: ${#N} | cocok: $([ "$(cek "$N")" = "$HARAP" ] && echo YA || echo TIDAK)"
