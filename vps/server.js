/**
 * Pembungkus Node untuk dompet REZO.
 *
 * Kode dompet tetap satu sumber: workers/wallet/src/index.js (dipakai juga di
 * Cloudflare). Berkas ini hanya menjalankan fungsi fetch yang sama di atas
 * HTTP Node, jadi tidak ada logika uang yang diduplikasi.
 */
import http from "node:http";
import worker from "./index.js";

const env = {
  TURSO_URL: process.env.TURSO_URL || "",
  TURSO_TOKEN: process.env.TURSO_TOKEN || "",
};
if (!env.TURSO_URL || !env.TURSO_TOKEN) {
  console.error("TURSO_URL / TURSO_TOKEN belum diisi");
  process.exit(1);
}

const PORT = Number(process.env.PORT || 8080);
const HOST = process.env.HOST || "127.0.0.1";
const BATAS_ISI = 1024 * 1024; // 1 MB, cukup untuk semua permintaan dompet

function bacaIsi(req) {
  return new Promise((resolve, reject) => {
    const potongan = [];
    let total = 0;
    req.on("data", (c) => {
      total += c.length;
      if (total > BATAS_ISI) {
        reject(new Error("Isi permintaan terlalu besar"));
        req.destroy();
        return;
      }
      potongan.push(c);
    });
    req.on("end", () => resolve(Buffer.concat(potongan)));
    req.on("error", reject);
  });
}

const server = http.createServer(async (req, res) => {
  const mulai = Date.now();
  try {
    const isi = req.method === "GET" || req.method === "HEAD" ? undefined : await bacaIsi(req);
    const alamat = `http://${req.headers.host || HOST + ":" + PORT}${req.url}`;
    const permintaan = new Request(alamat, {
      method: req.method,
      headers: req.headers,
      body: isi && isi.length ? isi : undefined,
    });
    const jawaban = await worker.fetch(permintaan, env, { waitUntil() {} });
    res.writeHead(jawaban.status, Object.fromEntries(jawaban.headers));
    const badan = Buffer.from(await jawaban.arrayBuffer());
    res.end(badan);
    if (process.env.LOG !== "0") {
      console.log(`${req.method} ${req.url} ${jawaban.status} ${Date.now() - mulai}ms`);
    }
  } catch (e) {
    console.error(`${req.method} ${req.url} gagal: ${e?.message ?? e}`);
    res.writeHead(500, { "content-type": "application/json; charset=utf-8" });
    res.end(JSON.stringify({ ok: 0, e: "Server dompet sibuk" }));
  }
});

server.listen(PORT, HOST, () => {
  console.log(`dompet REZO jalan di http://${HOST}:${PORT}`);
});
