import worker from "./src/index.js";
const env={TURSO_URL:"https://rezo-voice-chat-officialex.aws-ap-southeast-1.turso.io",TURSO_TOKEN:process.env.TURSO_TOKEN};
const uid="uji9";
for (let i=0;i<4;i++){
  const r=await worker.fetch(new Request("https://w/",{method:"POST",body:JSON.stringify({a:"gift_cashback",t:"x",p:{uid,price:100000,count:1,to:"uji9b"}})}),env);
  console.log(i, (await r.text()).slice(0,220));
}
