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
  GITHUB_TOKEN: process.env.GITHUB_TOKEN || "",
  GITHUB_REPO: process.env.GITHUB_REPO || "",
  GITHUB_BRANCH: process.env.GITHUB_BRANCH || "main",
};
const PORT = Number(process.env.PORT || 8090);
const HOST = process.env.HOST || "127.0.0.1";
// Aset frame/efek bisa beberapa MB, jadi batas dinaikkan ke 30 MB.
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
server.listen(PORT, HOST, () => console.log(`layanan REZO jalan di http://${HOST}:${PORT}`));
