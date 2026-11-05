/**
 * Gateway WhatsApp untuk OTP login REZO.
 * Berjalan di VPS Helipod, dipanggil server dompet (worker) untuk
 * mengirim kode OTP lewat WhatsApp. Tanpa biaya SMS.
 *
 * Endpoint (semua wajib header x-secret):
 *   POST /send   { "nomor": "6285xxxx", "pesan": "kode 123456" }
 *   GET  /health
 */
import express from "express";
import makeWASocket, { useMultiFileAuthState, DisconnectReason, fetchLatestBaileysVersion } from "@whiskeysockets/baileys";
import qrcode from "qrcode-terminal";
import pino from "pino";

const PORT = Number(process.env.PORT || 8099);
const SECRET = process.env.WA_OTP_SECRET || "";
if (!SECRET) {
  console.error("WA_OTP_SECRET belum diisi");
  process.exit(1);
}

const logger = pino({ level: "warn" });
let sock = null;
let tersambung = false;

async function mulai() {
  const { state, saveCreds } = await useMultiFileAuthState("/opt/wa-otp/auth");
  const { version } = await fetchLatestBaileysVersion();
  sock = makeWASocket({ version, auth: state, logger, printQRInTerminal: false, syncFullHistory: false });

  sock.ev.on("creds.update", saveCreds);
  sock.ev.on("connection.update", (u) => {
    const { connection, lastDisconnect, qr } = u;
    if (qr) {
      console.log("\n=== SCAN QR INI DENGAN NOMOR WHATSAPP CADANGAN ===");
      qrcode.generate(qr, { small: true });
      console.log("WhatsApp > Perangkat tertaut > Tautkan perangkat\n");
    }
    if (connection === "open") {
      tersambung = true;
      console.log("[wa-otp] tersambung sebagai", sock.user?.id);
    }
    if (connection === "close") {
      tersambung = false;
      const kode = lastDisconnect?.error?.output?.statusCode;
      const keluar = kode === DisconnectReason.loggedOut;
      console.log("[wa-otp] koneksi tertutup, kode", kode, keluar ? "(logout, perlu scan ulang)" : "(sambung ulang)");
      setTimeout(mulai, keluar ? 30000 : 3000);
    }
  });
}

await mulai();

const app = express();
app.use(express.json({ limit: "32kb" }));

app.get("/health", (_req, res) => res.json({ ok: tersambung ? 1 : 0, tersambung, nomor: sock?.user?.id || null }));

app.post("/send", async (req, res) => {
  if (String(req.headers["x-secret"] || "") !== SECRET) return res.status(401).json({ ok: 0, e: "secret salah" });
  const nomor = String(req.body?.nomor || "").replace(/[^0-9]/g, "");
  const pesan = String(req.body?.pesan || "").slice(0, 900);
  if (nomor.length < 8 || !pesan) return res.status(400).json({ ok: 0, e: "nomor atau pesan kosong" });
  if (!tersambung || !sock) return res.status(503).json({ ok: 0, e: "WhatsApp belum tersambung" });
  try {
    await sock.sendMessage(nomor + "@s.whatsapp.net", { text: pesan });
    res.json({ ok: 1 });
  } catch (e) {
    res.status(500).json({ ok: 0, e: String(e?.message || e) });
  }
});

app.listen(PORT, () => console.log("[wa-otp] mendengar di port", PORT));
