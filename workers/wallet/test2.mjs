import worker from "./src/index.js";
import crypto from "node:crypto";
const PROJ = "arboreal-cat-511705-p6";
const { publicKey, privateKey } = crypto.generateKeyPairSync("rsa", { modulusLength: 2048 });
const jwk = { ...publicKey.export({ format: "jwk" }), kid: "k1", alg: "RS256", use: "sig" };
const b64 = (o) => Buffer.from(JSON.stringify(o)).toString("base64url");
const tok = (sub) => {
  const h = b64({ alg: "RS256", kid: "k1", typ: "JWT" });
  const n = Math.floor(Date.now() / 1000);
  const p = b64({ aud: PROJ, iss: `https://securetoken.google.com/${PROJ}`, sub, exp: n + 600, iat: n });
  return `${h}.${p}.${crypto.sign("sha256", Buffer.from(`${h}.${p}`), privateKey).toString("base64url")}`;
};
const asli = globalThis.fetch;
globalThis.fetch = async (u, o) =>
  String(u).includes("securetoken")
    ? new Response(JSON.stringify({ keys: [jwk] }), { headers: { "Content-Type": "application/json" } })
    : asli(u, o);
const env = { TURSO_URL: "https://rezo-voice-chat-officialex.aws-ap-southeast-1.turso.io", TURSO_TOKEN: process.env.TURSO_TOKEN };
const call = async (a, p = {}, sub = "uji2") => {
  const r = await worker.fetch(new Request("https://w/", { method: "POST", body: JSON.stringify({ a, t: tok(sub), p }) }), env);
  return [r.status, await r.json()];
};
const w = (x) => JSON.stringify(x[1].w ?? x[1]);
console.log("topup", w(await call("topup", { coins: 300000000, req: "t9" })));
console.log("buy_vip 5", w(await call("buy_vip", { tier: 5 })));
console.log("buy_vip 5 lagi (perpanjang)", w(await call("buy_vip", { tier: 5 })));
console.log("buy_item", w(await call("buy_item", { item_id: "frame_emas", price: 1000000, days: 30 })));
console.log("equip", w(await call("equip", { slot: "frame", id: "frame_emas" })));
console.log("claim_level", w(await call("claim_level")));
console.log("claim_level lagi", w(await call("claim_level")));
console.log("backfill", w(await call("backfill")));
console.log("wave_prepare", w(await call("wave_prepare", { name: "Budi", avatar: "http://x/a.png" })));
console.log("gift pakai item_id", w(await call("send_gift", { price: 1000, item_id: "mawar", room_id: "r99" })));
