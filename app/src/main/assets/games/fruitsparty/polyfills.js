/* Small polyfills for old Android System WebViews (Android 7–9). Loaded before the app. */
(function () {
  var g = typeof globalThis !== "undefined" ? globalThis : typeof self !== "undefined" ? self : window;
  if (typeof globalThis === "undefined") { try { g.globalThis = g; } catch (e) {} }
  function def(obj, name, fn) {
    if (!obj[name]) { try { Object.defineProperty(obj, name, { value: fn, configurable: true, writable: true }); } catch (e) { obj[name] = fn; } }
  }
  def(Object, "fromEntries", function (it) {
    var o = {};
    Array.from(it).forEach(function (kv) { o[kv[0]] = kv[1]; });
    return o;
  });
  def(Object, "hasOwn", function (o, k) { return Object.prototype.hasOwnProperty.call(o, k); });
  def(Array.prototype, "flat", function (d) {
    d = d === undefined ? 1 : Number(d);
    return d < 1 ? Array.prototype.slice.call(this) : Array.prototype.reduce.call(this, function (acc, v) {
      return acc.concat(Array.isArray(v) ? v.flat(d - 1) : v);
    }, []);
  });
  def(Array.prototype, "flatMap", function (fn, t) { return Array.prototype.map.call(this, fn, t).flat(1); });
  function at(i) { i = Math.trunc(i) || 0; if (i < 0) i += this.length; return i < 0 || i >= this.length ? undefined : this[i]; }
  def(Array.prototype, "at", at);
  def(String.prototype, "at", at);
  def(Array.prototype, "findLast", function (fn, t) { for (var i = this.length - 1; i >= 0; i--) if (fn.call(t, this[i], i, this)) return this[i]; });
  def(Array.prototype, "findLastIndex", function (fn, t) { for (var i = this.length - 1; i >= 0; i--) if (fn.call(t, this[i], i, this)) return i; return -1; });
  def(String.prototype, "replaceAll", function (s, r) {
    if (s instanceof RegExp) return this.replace(s, r);
    return this.split(String(s)).join(typeof r === "function" ? r(String(s)) : r);
  });
  def(String.prototype, "trimStart", String.prototype.trimLeft);
  def(String.prototype, "trimEnd", String.prototype.trimRight);
  if (typeof Promise !== "undefined") {
    def(Promise, "allSettled", function (ps) {
      return Promise.all(Array.from(ps).map(function (p) {
        return Promise.resolve(p).then(function (v) { return { status: "fulfilled", value: v }; }, function (r) { return { status: "rejected", reason: r }; });
      }));
    });
    def(Promise.prototype, "finally", function (fn) {
      return this.then(function (v) { return Promise.resolve(fn()).then(function () { return v; }); },
        function (r) { return Promise.resolve(fn()).then(function () { throw r; }); });
    });
    if (typeof g.queueMicrotask !== "function") g.queueMicrotask = function (cb) { Promise.resolve().then(cb); };
  }
  if (typeof g.requestIdleCallback !== "function") {
    g.requestIdleCallback = function (cb) { var s = Date.now(); return setTimeout(function () { cb({ didTimeout: false, timeRemaining: function () { return Math.max(0, 50 - (Date.now() - s)); } }); }, 1); };
    g.cancelIdleCallback = function (id) { clearTimeout(id); };
  }
  if (typeof g.structuredClone !== "function") {
    g.structuredClone = function (v) { return v === undefined ? v : JSON.parse(JSON.stringify(v)); };
  }
})();
