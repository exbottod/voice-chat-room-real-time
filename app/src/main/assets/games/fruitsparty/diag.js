// Penampil pesan galat di layar: kalau game gagal jalan, pesannya muncul
// di HP supaya penyebabnya bisa diketahui tanpa kabel USB.
(function () {
  function box() {
    var d = document.getElementById("fpdiag");
    if (!d) {
      d = document.createElement("div");
      d.id = "fpdiag";
      d.style.cssText =
        "position:fixed;left:0;right:0;bottom:0;z-index:2147483647;background:rgba(0,0,0,.92);" +
        "color:#ffe9a8;font:11px/1.45 monospace;padding:8px;white-space:pre-wrap;max-height:50%;" +
        "overflow:auto;border-top:1px solid #f5c542";
      d.style.height = "50%";
      (document.body || document.documentElement).appendChild(d);
    }
    return d;
  }
  function show(msg) {
    try {
      var d = box();
      d.textContent += msg + "\n";
    } catch (e) {}
  }
  window.fpShow = show;

  window.addEventListener("error", function (e) {
    if (e && e.message) show("ERROR: " + e.message + (e.lineno ? " (baris " + e.lineno + ")" : ""));
    else if (e && e.target && e.target.src) show("GAGAL MUAT: " + e.target.src);
  }, true);

  window.addEventListener("unhandledrejection", function (e) {
    var r = e && e.reason;
    show("PROMISE: " + (r && r.message ? r.message : String(r)));
  });

  window.addEventListener("load", function () {
    setTimeout(function () {
      var r = document.getElementById("root");
      var ua = navigator.userAgent || "-";
      var ver = (ua.match(/Chrome\/[\d.]+/) || ["-"])[0];
      var out = [];
      out.push("WebView: " + ver);
      out.push("Layar: " + window.innerWidth + "x" + window.innerHeight + " dpr " + (window.devicePixelRatio || 1));
      out.push("Stylesheet: " + document.styleSheets.length + " | bg body: " + getComputedStyle(document.body).backgroundColor);
      if (!r) {
        out.push("ROOT tidak ada");
      } else {
        out.push("ROOT anak: " + r.children.length + " isi " + r.innerHTML.length + " char");
        var c = r.firstElementChild;
        if (c) {
          out.push("Anak1: " + (c.className || c.tagName).toString().slice(0, 48));
          out.push("  tinggi " + c.offsetHeight + " lebar " + c.offsetWidth + " display " + getComputedStyle(c).display);
          var g = c.firstElementChild;
          if (g) out.push("  cucu: " + g.children.length + " bagian, tinggi " + g.offsetHeight);
        }
      }
      var imgs = document.images, bad = 0, ok = 0;
      for (var i = 0; i < imgs.length; i++) { if (imgs[i].complete && imgs[i].naturalWidth === 0) bad++; else ok++; }
      out.push("Gambar: " + imgs.length + " (siap " + ok + ", gagal " + bad + ")");
      out.push("AndroidGame: " + (window.AndroidGame ? "ada" : "TIDAK ADA"));
      show(out.join("\n"));
    }, 4000);
  });
})();
