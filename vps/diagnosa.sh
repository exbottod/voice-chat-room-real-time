#!/bin/bash
# Hanya mencetak sidik (hash) dan panjang; nilai rahasia tidak pernah dicetak.
U=/etc/systemd/system/rezo-agora.service
echo "== status layanan: $(systemctl is-active rezo-agora)"
echo "== jumlah baris Environment: $(grep -c '^Environment' $U)"
ENV_TEXT=$(systemctl show rezo-agora -p Environment --value)
for nama in AGORA_APP_ID AGORA_APP_CERTIFICATE; do
  nilai=$(printf '%s\n' "$ENV_TEXT" | tr ' ' '\n' | grep "^$nama=" | head -1 | cut -d= -f2-)
  nilai=${nilai//\"/}
  if [ "$nama" = AGORA_APP_ID ]; then
    echo "$nama = $nilai (panjang ${#nilai})"
  else
    echo "$nama panjang=${#nilai} sidik=$(printf '%s' "$nilai" | sha256sum | cut -d' ' -f1)"
    echo "$nama awal-akhir=${nilai:0:2}…${nilai: -2}"
  fi
done
echo "== sidik yang diharapkan (sertifikat dari owner): 37ff6a4be3ebee00831a8c7fe37c80edfffc9829f4744644c920ec90e7249328"
