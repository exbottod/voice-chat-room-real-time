/* Fruits Party - efek suara & musik latar.
   Semua suara dibuat langsung oleh Web Audio (tanpa file audio),
   jadi ringan dan jalan di Android 8 ke atas. */
(function () {
  var AC = window.AudioContext || window.webkitAudioContext;
  if (!AC) return;

  var ctx = null;
  var master = null;
  var musicBus = null;
  var sfxBus = null;
  var noiseBuf = null;
  var musicOn = true;
  try { musicOn = localStorage.getItem('fp_music') !== '0'; } catch (e) {}

  function now() { return ctx ? ctx.currentTime : 0; }

  function makeNoise() {
    var len = Math.floor(ctx.sampleRate * 1.2);
    var buf = ctx.createBuffer(1, len, ctx.sampleRate);
    var d = buf.getChannelData(0);
    for (var i = 0; i < len; i++) d[i] = Math.random() * 2 - 1;
    return buf;
  }

  function boot() {
    if (ctx) {
      if (ctx.state === 'suspended') ctx.resume();
      return;
    }
    ctx = new AC();
    master = ctx.createGain();
    master.gain.value = 0.9;
    master.connect(ctx.destination);

    sfxBus = ctx.createGain();
    sfxBus.gain.value = 0.85;
    sfxBus.connect(master);

    musicBus = ctx.createGain();
    musicBus.gain.value = musicOn ? 0.10 : 0.0;
    musicBus.connect(master);

    noiseBuf = makeNoise();
    if (ctx.state === 'suspended') ctx.resume();
    scheduleMusic();
  }

  // ---------- tap pola / chip ----------
  function tapSound() {
    if (!ctx || !sfxBus) return;
    var t = now();
    var o = ctx.createOscillator();
    var g = ctx.createGain();
    o.type = 'triangle';
    o.frequency.setValueAtTime(520, t);
    o.frequency.exponentialRampToValueAtTime(190, t + 0.07);
    g.gain.setValueAtTime(0.0001, t);
    g.gain.exponentialRampToValueAtTime(0.5, t + 0.008);
    g.gain.exponentialRampToValueAtTime(0.0001, t + 0.09);
    o.connect(g); g.connect(sfxBus);
    o.start(t); o.stop(t + 0.11);

    // "ketukan" logam kecil
    var n = ctx.createBufferSource();
    n.buffer = noiseBuf;
    var bp = ctx.createBiquadFilter();
    bp.type = 'bandpass';
    bp.frequency.value = 2600;
    bp.Q.value = 6;
    var ng = ctx.createGain();
    ng.gain.setValueAtTime(0.22, t);
    ng.gain.exponentialRampToValueAtTime(0.0001, t + 0.05);
    n.connect(bp); bp.connect(ng); ng.connect(sfxBus);
    n.start(t); n.stop(t + 0.06);
  }

  // ---------- pola berputar ----------
  var spin = null;

  function startSpin() {
    if (!ctx || spin) return;
    var t = now();
    var src = ctx.createBufferSource();
    src.buffer = noiseBuf;
    src.loop = true;

    var bp = ctx.createBiquadFilter();
    bp.type = 'bandpass';
    bp.frequency.setValueAtTime(420, t);
    bp.frequency.linearRampToValueAtTime(2100, t + 0.9);
    bp.Q.value = 2.2;

    var g = ctx.createGain();
    g.gain.setValueAtTime(0.0001, t);
    g.gain.exponentialRampToValueAtTime(0.20, t + 0.18);
    g.gain.setValueAtTime(0.20, t + 0.5);
    g.gain.exponentialRampToValueAtTime(0.10, t + 1.4);

    // getaran halus supaya terasa berputar
    var lfo = ctx.createOscillator();
    var lfoGain = ctx.createGain();
    lfo.frequency.value = 9;
    lfoGain.gain.value = 160;
    lfo.connect(lfoGain); lfoGain.connect(bp.frequency);

    src.connect(bp); bp.connect(g); g.connect(sfxBus);
    src.start(t); lfo.start(t);
    spin = { src: src, g: g, lfo: lfo };
  }

  function stopSpin() {
    if (!spin || !ctx) return;
    var s = spin;
    spin = null;
    var t = now();
    try {
      s.g.gain.cancelScheduledValues(t);
      s.g.gain.setValueAtTime(Math.max(s.g.gain.value, 0.0001), t);
      s.g.gain.exponentialRampToValueAtTime(0.0001, t + 0.25);
      s.src.stop(t + 0.3);
      s.lfo.stop(t + 0.3);
    } catch (e) {}
  }

  // ---------- musik latar aestetik ----------
  // Akor lembut: C - Am - F - G, dengan nada bel pelan di atasnya.
  var CHORDS = [
    [261.63, 329.63, 392.00, 493.88],
    [220.00, 261.63, 329.63, 392.00],
    [174.61, 220.00, 261.63, 349.23],
    [196.00, 246.94, 293.66, 392.00]
  ];
  var BAR = 3.6;
  var beat = 0;

  function pad(freqs, when, dur) {
    for (var i = 0; i < freqs.length; i++) {
      var o = ctx.createOscillator();
      var g = ctx.createGain();
      o.type = i % 2 ? 'sine' : 'triangle';
      o.frequency.value = freqs[i];
      o.detune.value = (i - 1.5) * 4;
      g.gain.setValueAtTime(0.0001, when);
      g.gain.exponentialRampToValueAtTime(0.16, when + 1.1);
      g.gain.exponentialRampToValueAtTime(0.0001, when + dur);
      o.connect(g); g.connect(musicBus);
      o.start(when); o.stop(when + dur + 0.05);
    }
  }

  function bell(freq, when) {
    var o = ctx.createOscillator();
    var g = ctx.createGain();
    o.type = 'sine';
    o.frequency.value = freq;
    g.gain.setValueAtTime(0.0001, when);
    g.gain.exponentialRampToValueAtTime(0.10, when + 0.02);
    g.gain.exponentialRampToValueAtTime(0.0001, when + 0.9);
    o.connect(g); g.connect(musicBus);
    o.start(when); o.stop(when + 1.0);
  }

  function scheduleMusic() {
    if (!ctx) return;
    var chord = CHORDS[beat % CHORDS.length];
    var when = now() + 0.08;
    pad(chord, when, BAR + 0.6);
    // bel pelan, dua kali per bar
    bell(chord[2] * 2, when + 0.9);
    bell(chord[3] * 2, when + BAR * 0.62);
    if (beat % 4 === 2) bell(chord[0] * 4, when + BAR * 0.34);
    beat++;
    setTimeout(scheduleMusic, BAR * 1000);
  }

  function setMusic(on) {
    musicOn = !!on;
    try { localStorage.setItem('fp_music', musicOn ? '1' : '0'); } catch (e) {}
    if (musicBus && ctx) {
      var t = now();
      musicBus.gain.cancelScheduledValues(t);
      musicBus.gain.setValueAtTime(Math.max(musicBus.gain.value, 0.0001), t);
      musicBus.gain.linearRampToValueAtTime(musicOn ? 0.10 : 0.0, t + 0.5);
    }
    var b = document.getElementById('fpMusicBtn');
    if (b) b.textContent = musicOn ? '🎵' : '🔇';
  }

  // ---------- deteksi pola berputar dari perubahan tampilan ----------
  var spinning = false;
  var hits = 0;
  var stopTimer = null;

  function watchSpin() {
    if (!window.MutationObserver) return;
    var obs = new window.MutationObserver(function (list) {
      var t = Date.now();
      if (spinning) {
        clearTimeout(stopTimer);
        stopTimer = setTimeout(function () { spinning = false; stopSpin(); }, 900);
        return;
      }
      hits += list.length;
      clearTimeout(stopTimer);
      stopTimer = setTimeout(function () { hits = 0; }, 400);
      if (hits >= 5) {
        spinning = true;
        startSpin();
        clearTimeout(stopTimer);
        stopTimer = setTimeout(function () { spinning = false; stopSpin(); }, 900);
        hits = 0;
      }
    });
    obs.observe(document.body, { attributes: true, attributeFilter: ['class'], subtree: true });
  }

  // ---------- tombol musik ----------
  function addMusicButton() {
    if (document.getElementById('fpMusicBtn')) return;
    var b = document.createElement('button');
    b.id = 'fpMusicBtn';
    b.textContent = musicOn ? '🎵' : '🔇';
    b.setAttribute('aria-label', 'Musik');
    b.style.cssText = 'position:absolute;right:4px;top:60px;width:44px;height:44px;' +
      'display:grid;place-items:center;border-radius:12px;border:0;font-size:19px;' +
      'background:rgba(0,0,0,.35);color:#ffd76e;z-index:40;';
    b.addEventListener('click', function (ev) {
      ev.stopPropagation();
      boot();
      setMusic(!musicOn);
    });
    var root = document.querySelector('.fp-root') || document.body;
    root.appendChild(b);
  }

  function attach() {
    if (!document.getElementById('fpMusicBtn')) {
      if (document.querySelector('.fp-root')) addMusicButton();
      else return setTimeout(attach, 400);
    }
    watchSpin();
  }

  // buka audio pada sentuhan pertama (syarat Chrome/WebView)
  function unlock() {
    boot();
    document.removeEventListener('touchstart', unlock, true);
    document.removeEventListener('mousedown', unlock, true);
    document.removeEventListener('click', unlock, true);
  }
  document.addEventListener('touchstart', unlock, true);
  document.addEventListener('mousedown', unlock, true);
  document.addEventListener('click', unlock, true);

  // suara ketukan setiap kali pola/tombol ditekan
  document.addEventListener('pointerdown', function (ev) {
    var el = ev.target;
    while (el && el !== document.body) {
      if (el.id === 'fpMusicBtn') return;
      el = el.parentElement;
    }
    if (!ctx) return;
    tapSound();
  }, true);

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function () { setTimeout(attach, 300); });
  } else {
    setTimeout(attach, 300);
  }

  document.addEventListener('visibilitychange', function () {
    if (!ctx) return;
    if (document.hidden) {
      stopSpin();
      if (ctx.state === 'running') ctx.suspend();
    } else if (ctx.state === 'suspended') {
      ctx.resume();
    }
  });
})();
