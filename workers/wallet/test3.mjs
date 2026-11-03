import worker from "./src/index.js";
import crypto from "node:crypto";
const PROJ = "arboreal-cat-511705-p6";
const { publicKey, privateKey } = crypto.generateKeyPairSync("rsa", { modulusLength: 2048 });
const jwk = { ...publicKey.export({ format: "jwk" }), kid: "k1", alg: "RS256", use: "sig" };
const b64 = (o) => Buffer.from(JSON.stringify(o)).toString("base64url");
const tok = (sub, email) => {
  const h = b64({ alg: "RS256", kid: "k1", typ: "JWT" });
  const n = Math.floor(Date.now() / 1000);
  const pl = { aud: PROJ, iss: `https://securetoken.google.com/${PROJ}`, sub, exp: n + 600, iat: n };
  if (email) pl.email = email;
  const p = b64(pl);
  return `${h}.${p}.${crypto.sign("sha256", Buffer.from(`${h}.${p}`), privateKey).toString("base64url")}`;
};
const asli = globalThis.fetch;
globalThis.fetch = async (u, o) =>
  String(u).includes("securetoken")
    ? new Response(JSON.stringify({ keys: [jwk] }), { headers: { "Content-Type": "application/json" } })
    : asli(u, o);
const env = { TURSO_URL: "https://rezo-voice-chat-officialex.aws-ap-southeast-1.turso.io", TURSO_TOKEN: process.env.TURSO_TOKEN };
const app = async (a, p = {}, sub = "uji3") => {
  const r = await worker.fetch(new Request("https://w/", { method: "POST", body: JSON.stringify({ a, t: tok(sub), p }) }), env);
  return [r.status, await r.json()];
};
const adm = async (p, email = "developerex100@gmail.com") => {
  const r = await worker.fetch(new Request("https://w/admin", { method: "POST", body: JSON.stringify({ t: tok("admin1", email), p }) }), env);
  return [r.status, await r.json()];
};
const ringkas = (x) => JSON.stringify(x).slice(0, 200);

console.log("panel bukan admin", ringkas(await adm({ op: "manual_pending" }, "orang@gmail.com")));
console.log("pay_set", ringkas(await adm({ op: "pay_set", dana: "08123456789", name: "REZO", gopay: "08123456789", qris: "" })));
console.log("pay_info app", ringkas(await app("pay_info")));
console.log("manual_create", ringkas(await app("manual_create", { id: "TM1", coins: 6000, amount: 1000000, payer: "Budi S" })));
console.log("manual_history", ringkas(await app("manual_history")));
console.log("manual_pending", ringkas(await adm({ op: "manual_pending" })));
console.log("manual_decide ok", ringkas(await adm({ op: "manual_decide", id: "TM1", ok: true })));
console.log("manual_decide ulang", ringkas(await adm({ op: "manual_decide", id: "TM1", ok: true })));
console.log("saldo uji3", ringkas(await app("get")));
console.log("rocket_claim", ringkas(await app("rocket_claim", { mode: "launch", lv: 5 })));
