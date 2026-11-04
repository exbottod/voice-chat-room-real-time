#!/bin/bash
# Diagnosa konfigurasi suara. Nilai rahasia TIDAK dicetak, hanya sidik & panjang.
D=/opt/rezo-agora
echo "== isi $D"; ls -la $D 2>&1 | head -20
echo "== layanan"; systemctl list-units --type=service 2>/dev/null | grep -iE "rezo|agora|wallet|caddy" | head
echo "== cari nama variabel di berkas"
grep -rIl -iE "AGORA" $D /etc/systemd/system /etc/rezo* /root /home/ubuntu 2>/dev/null | grep -v node_modules | head -10
echo "== nilai (disamarkan)"
for f in $(grep -rIl -iE "AGORA" $D /etc/systemd/system /etc/rezo* /root /home/ubuntu 2>/dev/null | grep -v node_modules | head -6); do
  echo "berkas: $f"
  grep -hiE "AGORA[A-Z_]*(ID|CERT)" "$f" 2>/dev/null | sed -E 's/^[[:space:]]*(export[[:space:]]+)?//' | while IFS= read -r baris; do
    nama="${baris%%=*}"; nilai="${baris#*=}"; nilai="${nilai//\"/}"; nilai="${nilai//\'/}"; nilai="${nilai//[[:space:]]/}"
    case "$nama" in
      *ID*) echo "  $nama = $nilai";;
      *) echo "  $nama panjang=${#nilai} sidik=$(printf '%s' "$nilai" | sha256sum | cut -d' ' -f1)";;
    esac
  done
done
echo "== tes layanan lokal"
curl -s -o /dev/null -w "port 8090: %{http_code}\n" -X POST http://127.0.0.1:8090/agora 2>&1
