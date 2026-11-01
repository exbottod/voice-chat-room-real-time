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
      var info = "WebView: " + (navigator.userAgent || "-");
      if (!r) info += "\nROOT tidak ada";
      else if (r.children.length === 0) info += "\nROOT kosong: script game tidak jalan";
      else info += "\nROOT terisi (" + r.children.length + " bagian)";
      if (window.innerHeight < 200) info += "\nTinggi layar aneh: " + window.innerHeight;
      info += "\nLebar: " + window.innerWidth + "x" + window.innerHeight;
      show(info);
    }, 3500);
  });
})();
