// Panel developer: kontrol room (hapus foto, hapus nama -> Unknown, sematkan, hapus room)
(function () {
  const tabs = document.querySelector(".tabs");
  const tab = document.createElement("div");
  tab.className = "tab"; tab.dataset.t = "rooms"; tab.textContent = "🏠 Room";
  tabs.appendChild(tab);

  const box = document.createElement("div");
  box.id = "tab-rooms"; box.className = "hide";
  box.innerHTML =
    '<div class="card"><h2>Kontrol Room</h2>' +
    '<p class="muted">Sematkan room agar selalu di paling atas. Room di urutan 1-3 otomatis berlabel TOP1, TOP2, TOP3 di aplikasi.</p>' +
    '<input type="text" id="roomQ" placeholder="Cari nama room atau nomor room (opsional)">' +
    '<button class="btn out" id="roomLoad">Muat daftar room</button>' +
    '<div class="muted" id="roomCount" style="margin-top:8px"></div>' +
    '<div id="roomList"></div></div>';
  document.getElementById("msg").before(box);

  // tab bawaan hanya mengenal tab lama, jadi tab Room diatur di sini
  tab.onclick = () => {
    document.querySelectorAll(".tab").forEach(x => x.classList.toggle("on", x === tab));
    ["banner", "ban", "coin", "user", "vip", "store"].forEach(k => $("tab-" + k).classList.add("hide"));
    box.classList.remove("hide");
    load();
  };
  document.querySelectorAll(".tab").forEach(t => {
    if (t === tab) return;
    t.addEventListener("click", () => box.classList.add("hide"));
  });

  async function act(label, fn) {
    try {
      await Promise.race([fn(), new Promise((_, rej) => setTimeout(() => rej(new Error("waktu habis, kemungkinan kuota Firestore habis")), 20000))]);
      toast(label + " berhasil");
      load();
    } catch (e) {
      toast(e.code === "permission-denied" ? "Ditolak rules Firestore (admin belum diizinkan mengubah room)" : "Gagal: " + e.message);
    }
  }

  function btn(text, cls, fn) {
    const b = document.createElement("button");
    b.className = "btn " + (cls || "out"); b.textContent = text; b.style.cssText = "padding:7px 10px;font-size:12px";
    b.onclick = fn; return b;
  }

  async function load() {
    const list = $("roomList"); list.textContent = "Memuat...";
    try {
      const snap = await db.collection("rooms").orderBy("createdAt", "desc").limit(100).get();
      list.textContent = "";
      const q = $("roomQ").value.trim().toLowerCase();
      const docs = snap.docs.slice().sort((a, b) => {
        const pa = a.data().pinned ? (a.data().pinnedAt || 1) : 0, pb = b.data().pinned ? (b.data().pinnedAt || 1) : 0;
        return pb - pa;
      });
      let shown = 0;
      docs.forEach(d => {
        const x = d.data(), name = x.name || "(tanpa nama)";
        if (q && !(name.toLowerCase().includes(q) || String(x.roomNo || "").includes(q))) return;
        shown++;
        const row = document.createElement("div"); row.className = "item"; row.style.flexWrap = "wrap";
        let av;
        if (x.photoB64) { av = document.createElement("img"); av.src = "data:image/jpeg;base64," + x.photoB64; av.style.cssText = "width:48px;height:48px;border-radius:10px;aspect-ratio:1;object-fit:cover"; }
        else { av = document.createElement("div"); av.style.cssText = "width:48px;height:48px;border-radius:10px;background:#263238;display:flex;align-items:center;justify-content:center;flex:none"; av.textContent = "🎙️"; }
        const info = document.createElement("div"); info.className = "info";
        const t = document.createElement("b"); t.textContent = (x.pinned ? "📌 " : "") + name;
        const s = document.createElement("span"); s.className = "muted";
        s.textContent = "No " + (x.roomNo || "-") + " · " + (x.ownerName || "-");
        info.append(t, s);
        const acts = document.createElement("div"); acts.style.cssText = "display:flex;gap:6px;flex-wrap:wrap;width:100%";
        const ref = d.ref;
        acts.append(
          btn(x.pinned ? "Lepas sematan" : "Sematkan", "out", () =>
            act(x.pinned ? "Lepas sematan" : "Sematkan", () => ref.update(x.pinned ? { pinned: false, pinnedAt: 0 } : { pinned: true, pinnedAt: Date.now() }))),
          btn("Hapus foto", "out", () => {
            if (!x.photoB64) return toast("Room ini tidak punya foto");
            if (confirm("Hapus foto room " + name + "?")) act("Hapus foto", () => ref.update({ photoB64: null }));
          }),
          btn("Hapus nama", "out", () => {
            if (confirm("Ubah nama room " + name + " menjadi Unknown?")) act("Hapus nama", () => ref.update({ name: "Unknown" }));
          }),
          btn("Hapus room", "bad", () => {
            if (confirm("HAPUS room " + name + " selamanya?")) act("Hapus room", () => ref.delete());
          })
        );
        row.append(av, info, acts); list.appendChild(row);
      });
      $("roomCount").textContent = shown + " dari " + snap.size + " room";
      if (!shown) list.textContent = "Tidak ada room.";
    } catch (e) {
      list.textContent = e.code === "permission-denied" ? "Ditolak rules Firestore." : "Gagal: " + e.message;
    }
  }
  $("roomLoad").onclick = load;
})();
