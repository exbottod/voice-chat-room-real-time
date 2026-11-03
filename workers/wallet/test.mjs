// Uji worker tanpa deploy: JWKS Google diganti kunci uji, Turso tetap asli.
import worker from "./src/index.js";
import crypto from "node:crypto";

const PROJ = "arboreal-cat-511705-p6";
const { publicKey, privateKey } = crypto.generateKeyPairSync("rsa", { modulusLength: 2048 });
const jwk = { ...publicKey.export({ format: "jwk" }), kid: "testkid", alg: "RS256", use: "sig" };

const b64 = (o) => Buffer.from(JSON.stringify(o)).toString("base64url");
function token(sub) {
  const h = b64({ alg: "RS256", kid: "testkid", typ: "JWT" });
  const now = Math.floor(Date.now() / 1000);
  const p = b64({ aud: PROJ, iss: `https://securetoken.google.com/${PROJ}`, sub, exp: now + 600, iat: now });
  const s = crypto.sign("sha256", Buffer.from(`${h}.${p}`), privateKey).toString("base64url");
  return `${h}.${p}.${s}`;
}

const asli = globalThis.fetch;
globalThis.fetch = async (url, opt) => {
  if (String(url).includes("securetoken@system.gserviceaccount.com"))
    return new Response(JSON.stringify({ keys: [jwk] }), { headers: { "Content-Type": "application/json" } });
  return asli(url, opt);
};

const env = { TURSO_URL: "https://rezo-voice-chat-officialex.aws-ap-southeast-1.turso.io", TURSO_TOKEN: process.env.TURSO_TOKEN };
const call = async (a, p = {}, sub = "uji1") => {
  const r = await worker.fetch(new Request("https://w/", { method: "POST", body: JSON.stringify({ a, t: token(sub), p }) }), env);
  return [r.status, await r.json()];
};

console.log("health", await worker.fetch(new Request("https://w/health"), env).then(r => r.json()));
console.log("token palsu", await worker.fetch(new Request("https://w/", { method: "POST", body: JSON.stringify({ a: "get", t: "a.b.c", p: {} }) }), env).then(r => [r.status, r.statusText]));
console.log("get", JSON.stringify(await call("get")));
console.log("uid lain ditolak", (await call("get", { uid: "oranglain" }))[0]);
console.log("gift 5jt", JSON.stringify(await call("send_gift", { price: 5000000, req: "g1", itemId: "x" })));
console.log("gift mahal 9e9", JSON.stringify(await call("send_gift", { price: 9000000000, req: "g2" })));

console.log("--- alur uang ---");
console.log("topup 100jt", JSON.stringify(await call("topup", { coins: 100000000, req: "t1" })));
console.log("topup ulang", JSON.stringify(await call("topup", { coins: 100000000, req: "t1" })));
console.log("gift 5jt", JSON.stringify(await call("send_gift", { price: 5000000, req: "g3", itemId: "mawar" })));
console.log("gift req sama", JSON.stringify(await call("send_gift", { price: 5000000, req: "g3", itemId: "mawar" })));
console.log("hive bet 1jt", JSON.stringify(await call("hive_uang", { kind: 1, amount: 1000000, orderId: "o1" })));
console.log("hive bet ulang", JSON.stringify(await call("hive_uang", { kind: 1, amount: 1000000, orderId: "o1" })));
console.log("hive win 3jt", JSON.stringify(await call("hive_uang", { kind: 2, amount: 3000000, orderId: "o2" })));
console.log("tukar 20 diamond", JSON.stringify(await call("exchange", { diamonds: 20, req: "x1" })));
