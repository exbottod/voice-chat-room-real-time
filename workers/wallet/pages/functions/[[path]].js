// Cermin API dompet di Cloudflare Pages, dipakai kalau workers.dev tidak bisa
// dihubungi dari HP user (DNS/ISP). Kode intinya sama persis dengan worker.
import worker from "../api.js";

export const onRequest = async (ctx) => worker.fetch(ctx.request, ctx.env);
