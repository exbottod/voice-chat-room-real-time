/* Penjaga diam: hanya muncul kalau halaman gagal jalan.
   Tidak menutupi layar saat game normal. */
(function () {
  var shown = false;
  function warn(msg) {
    if (shown) return;
    shown = true;
    var d = document.createElement("div");
    d.style.cssText =
      "position:fixed;left:0;right:0;bottom:0;z-index:2147483647;background:rgba(0,0,0,.9);" +
      "color:#ffe9a8;font:11px/1.4 monospace;padding:6px 8px;white-space:pre-wrap;max-height:40%;overflow:auto";
    d.textContent = msg;
    (document.body || document.documentElement).appendChild(d);
  }
  window.addEventListener("error", function (e) {
    if (e && e.message) warn("Galat: " + e.message + (e.lineno ? " (baris " + e.lineno + ")" : ""));
    else if (e && e.target && e.target.src) warn("Gagal memuat: " + e.target.src);
  }, true);
  window.addEventListener("unhandledrejection", function (e) {
    var r = e && e.reason;
    warn("Galat: " + (r && r.message ? r.message : String(r)));
  });
  window.addEventListener("load", function () {
    setTimeout(function () {
      var r = document.getElementById("root");
      if (r && r.children.length === 0) {
        warn("Game tidak tampil. WebView " + ((navigator.userAgent.match(/Chrome\/[\d.]+/) || ["-"])[0]) +
             ", layar " + window.innerWidth + "x" + window.innerHeight);
      }
    }, 8000);
  });
})();
