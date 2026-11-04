// Jalankan kode worker dompet REZO apa adanya di Node (Render/Railway/VPS).
// Kode worker hanya memakai fetch + env, jadi tinggal dibungkus satu server HTTP.
import http from "node:http";
import worker from "./worker.js";

const env = {
  TURSO_URL: process.env.TURSO_URL,
  TURSO_TOKEN: process.env.TURSO_TOKEN,
};

const server = http.createServer(async (req, res) => {
  try {
    if (req.url === "/health") {
      res.writeHead(200, { "content-type": "application/json" });
      res.end(JSON.stringify({ ok: 1, turso: !!env.TURSO_URL, token: !!env.TURSO_TOKEN }));
      return;
    }
    const chunks = [];
    for await (const c of req) chunks.push(c);
    const body = chunks.length ? Buffer.concat(chunks) : undefined;
    const url = "https://" + (req.headers.host || "localhost") + (req.url || "/");
    const request = new Request(url, {
      method: req.method,
      headers: req.headers,
      body: req.method === "GET" || req.method === "HEAD" ? undefined : body,
    });
    const out = await worker.fetch(request, env, { waitUntil() {} });
    const headers = {};
    out.headers.forEach((v, k) => { headers[k] = v; });
    res.writeHead(out.status, headers);
    res.end(Buffer.from(await out.arrayBuffer()));
  } catch (e) {
    res.writeHead(500, { "content-type": "application/json" });
    res.end(JSON.stringify({ ok: 0, e: "server error" }));
  }
});

const port = Number(process.env.PORT || 8080);
server.listen(port, () => console.log("dompet jalan di :" + port));
