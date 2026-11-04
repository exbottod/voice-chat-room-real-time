#!/usr/bin/env bash
# Memasang layanan token Agora di VPS, terpisah dari API dompet.
# Pakai: CERT=<App Certificate Agora> bash agora.sh
set -euo pipefail
DOMAIN="${DOMAIN:-43-156-99-4.sslip.io}"
CERT="${CERT:-}"
APPID="${APPID:-a64c0421b7814ded8f0b2806f00e1a90}"
CLD_CLOUD="${CLD_CLOUD:-}"; CLD_KEY="${CLD_KEY:-}"; CLD_SECRET="${CLD_SECRET:-}"
ADMIN_EMAIL_IN="${ADMIN_EMAIL:-}"
if [ -z "$CERT" ]; then echo "CERT (App Certificate Agora) belum diisi"; exit 1; fi

echo "== Menyiapkan folder"
sudo mkdir -p /opt/rezo-agora
sudo curl -fsSL "https://raw.githubusercontent.com/exbottod/voice-chat-room-real-time/main/token-worker/worker-standalone.js" -o /opt/rezo-agora/worker.js
sudo curl -fsSL "https://raw.githubusercontent.com/exbottod/voice-chat-room-real-time/main/token-worker/package.json" -o /opt/rezo-agora/package.json 2>/dev/null || true
sudo sed -i 's/"type": *"module"/"type": "module"/' /opt/rezo-agora/package.json 2>/dev/null || true
printf '{"type":"module"}\n' | sudo tee /opt/rezo-agora/package.json >/dev/null

echo "== Menulis pembungkus HTTP"
sudo tee /opt/rezo-agora/server.mjs >/dev/null <<'EOF'
import http from "node:http";
import worker from "./worker.js";
const env = {
  FIREBASE_PROJECT_ID: process.env.FIREBASE_PROJECT_ID || "",
  AGORA_APP_ID: process.env.AGORA_APP_ID || "",
  AGORA_APP_CERTIFICATE: process.env.AGORA_APP_CERTIFICATE || "",
  ADMIN_EMAIL: process.env.ADMIN_EMAIL || "",
  CLOUDINARY_CLOUD: process.env.CLOUDINARY_CLOUD || "",
  CLOUDINARY_API_KEY: process.env.CLOUDINARY_API_KEY || "",
  CLOUDINARY_API_SECRET: process.env.CLOUDINARY_API_SECRET || "",
};
const PORT = Number(process.env.PORT || 8090);
const HOST = process.env.HOST || "127.0.0.1";
const BATAS = 30 * 1024 * 1024;
function baca(req) {
  return new Promise((ok, gagal) => {
    const potongan = []; let total = 0;
    req.on("data", (c) => { total += c.length; if (total > BATAS) { gagal(new Error("terlalu besar")); req.destroy(); return; } potongan.push(c); });
    req.on("end", () => ok(Buffer.concat(potongan)));
    req.on("error", gagal);
  });
}
const server = http.createServer(async (req, res) => {
  const mulai = Date.now();
  try {
    const isi = req.method === "GET" || req.method === "HEAD" ? undefined : await baca(req);
    const alamat = `http://${req.headers.host || HOST + ":" + PORT}${req.url}`;
    const permintaan = new Request(alamat, { method: req.method, headers: req.headers, body: isi && isi.length ? isi : undefined });
    const jawaban = await worker.fetch(permintaan, env);
    res.writeHead(jawaban.status, Object.fromEntries(jawaban.headers));
    res.end(Buffer.from(await jawaban.arrayBuffer()));
    console.log(`${req.method} ${req.url} ${jawaban.status} ${Date.now() - mulai}ms`);
  } catch (e) {
    console.error(`${req.method} ${req.url} gagal: ${e?.message ?? e}`);
    res.writeHead(500, { "content-type": "application/json; charset=utf-8" });
    res.end(JSON.stringify({ error: "Server suara sibuk" }));
  }
});
server.listen(PORT, HOST, () => console.log(`token suara REZO jalan di http://${HOST}:${PORT}`));
EOF

echo "== Membuat layanan systemd"
sudo tee /etc/systemd/system/rezo-agora.service >/dev/null <<EOF
[Unit]
Description=REZO token suara (Agora)
After=network.target

[Service]
WorkingDirectory=/opt/rezo-agora
Environment=FIREBASE_PROJECT_ID=arboreal-cat-511705-p6
Environment=AGORA_APP_ID=$APPID
Environment=AGORA_APP_CERTIFICATE=$CERT
Environment=ADMIN_EMAIL=$ADMIN_EMAIL_IN
Environment=CLOUDINARY_CLOUD=$CLD_CLOUD
Environment=CLOUDINARY_API_KEY=$CLD_KEY
Environment=CLOUDINARY_API_SECRET=$CLD_SECRET
ExecStart=/usr/bin/node /opt/rezo-agora/server.mjs
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
EOF
sudo systemctl daemon-reload
sudo systemctl enable --now rezo-agora
sudo systemctl restart rezo-agora

echo "== Mengarahkan Caddy: /agora ke layanan suara, sisanya ke dompet"
sudo tee /etc/caddy/Caddyfile >/dev/null <<EOF
$DOMAIN {
	encode gzip
	handle /agora* {
		reverse_proxy 127.0.0.1:8090
	}
	handle {
		reverse_proxy 127.0.0.1:8080
	}
}
EOF
sudo systemctl restart caddy
sleep 4

echo "== Memeriksa"
echo -n "dompet : "; curl -s --max-time 8 http://127.0.0.1:8080/health; echo
echo -n "suara  : "; curl -s --max-time 8 -o /dev/null -w "%{http_code}" -X POST http://127.0.0.1:8090/agora -H 'content-type: application/json' -d '{"channel":"tes"}'; echo
echo -n "publik : "; curl -s --max-time 20 -o /dev/null -w "%{http_code}" -X POST "https://$DOMAIN/agora" -H 'content-type: application/json' -d '{"channel":"tes"}'; echo
cat <<EOF

Selesai. Alamat token suara baru:
https://$DOMAIN/agora

Kode 401 di atas artinya layanan jalan dan hanya menolak karena belum ada tiket login.
EOF
