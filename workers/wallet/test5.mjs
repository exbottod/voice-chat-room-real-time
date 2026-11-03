import worker from "./src/index.js";
import crypto from "node:crypto";
const PROJ="arboreal-cat-511705-p6";
const {publicKey,privateKey}=crypto.generateKeyPairSync("rsa",{modulusLength:2048});
const jwk={...publicKey.export({format:"jwk"}),kid:"k1",alg:"RS256",use:"sig"};
const b64=(o)=>Buffer.from(JSON.stringify(o)).toString("base64url");
const tok=(sub,email)=>{const h=b64({alg:"RS256",kid:"k1",typ:"JWT"});const n=Math.floor(Date.now()/1000);
 const pl={aud:PROJ,iss:`https://securetoken.google.com/${PROJ}`,sub,exp:n+600,iat:n}; if(email)pl.email=email;
 const p=b64(pl);return `${h}.${p}.${crypto.sign("sha256",Buffer.from(`${h}.${p}`),privateKey).toString("base64url")}`;};
const asli=globalThis.fetch;
globalThis.fetch=async(u,o)=>String(u).includes("securetoken")?new Response(JSON.stringify({keys:[jwk]}),{headers:{"Content-Type":"application/json"}}):asli(u,o);
const env={TURSO_URL:"https://rezo-voice-chat-officialex.aws-ap-southeast-1.turso.io",TURSO_TOKEN:process.env.TURSO_TOKEN};
const app=async(a,p={},sub="uji5")=>{const r=await worker.fetch(new Request("https://w/",{method:"POST",body:JSON.stringify({a,t:tok(sub),p})}),env);return await r.json();};
// persis seperti yang dikirim app: coins + payer + method, tanpa amount
console.log("30jt", JSON.stringify(await app("manual_create",{coins:30000000,payer:"test",method:"dana"})));
console.log("angka ngawur", JSON.stringify(await app("manual_create",{coins:12345,payer:"test"})));
console.log("riwayat", JSON.stringify((await app("manual_history")).list?.map(x=>[x.coins,x.amount,x.status])));
