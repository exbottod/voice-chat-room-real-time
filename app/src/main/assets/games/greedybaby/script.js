(function () {
  'use strict';
  var FRUITS = [
    { id: 'apple', img: 'img/fruit-apple.png', mult: 5, name: 'Apel' },
    { id: 'avocado', img: 'img/fruit-avocado.png', mult: 10, name: 'Alpukat' },
    { id: 'orange', img: 'img/fruit-orange.png', mult: 5, name: 'Jeruk' },
    { id: 'strawberry', img: 'img/fruit-strawberry.png', mult: 17, name: 'Stroberi' },
    { id: 'mango', img: 'img/fruit-mango.png', mult: 5, name: 'Mangga' },
    { id: 'pineapple', img: 'img/fruit-pineapple.png', mult: 27, name: 'Nanas' },
    { id: 'grape', img: 'img/fruit-grape.png', mult: 5, name: 'Anggur' },
    { id: 'watermelon', img: 'img/fruit-watermelon.png', mult: 51, name: 'Semangka' }
  ];
  var CHIPS = [
    { v: 100, l: '100', c: 'chip-teal' },
    { v: 1000, l: '1K', c: 'chip-blue' },
    { v: 10000, l: '10K', c: 'chip-red' },
    { v: 100000, l: '100K', c: 'chip-silver' },
    { v: 1000000, l: '1M', c: 'chip-silver' }
  ];
  var BET = 30, SPIN = 4, SHOW = 3, CYCLE = BET + SPIN + SHOW;
  var R = 40;

  var A = window.AndroidGame || null;
  var $ = function (id) { return document.getElementById(id); };
  var fmt = function (n) { return Number(n).toLocaleString('id-ID'); };

  function rng(seed) {
    var s = seed >>> 0;
    return function () { s = (Math.imul(s, 1664525) + 1013904223) >>> 0; return s / 4294967296; };
  }
  var roundOf = function (ms) { return Math.floor(ms / 1000 / CYCLE); };
  var secIn = function (ms) { return (ms / 1000) % CYCLE; };
  var resultOf = function (round) { return Math.floor(rng(round * 7919 + 13)() * 8); };

  var balance = 0, chip = 1, bets = {}, lastWin = 0;
  var settled = -1, placing = false, lastRoundSeen = -1, hl = -1;
  var offset = 0; // selisih jam perangkat dengan jam server

  function avatarHtml(url, name) {
    var initial = String(name || 'P').trim().charAt(0).toUpperCase();
    if (url) return '<span class="gg-rk-av"><img src="' + url + '" alt="" onerror="this.style.display=\'none\'"></span>';
    return '<span class="gg-rk-av">' + initial + '</span>';
  }

  function openRank() {
    $('rankPage').style.display = 'block';
    var list = $('rankList');
    list.innerHTML = '<li style="background:none;justify-content:center">Memuat…</li>';
    var rows = [];
    try { rows = A && A.ranking ? JSON.parse(A.ranking() || '[]') : []; } catch (e) { rows = []; }
    if (!rows.length) {
      list.innerHTML = '<li style="background:none">Belum ada pemenang. Mainkan satu putaran dulu.</li>';
      return;
    }
    var html = '';
    rows.forEach(function (r, i) {
      html += '<li' + (i === 0 ? ' class="top1"' : '') + '<span class="gg-rk-no">' + (i + 1) + '</span>' +
        avatarHtml(r.a, r.n) + '<span class="gg-rk-name">' + String(r.n).replace(/[<>&]/g, '') + '</span>' +
        '<span class="gg-rk-win">🪙 ' + fmt(r.w) + '</span></li>';
    });
    list.innerHTML = html;

    // foto profil menyusul per baris supaya daftar langsung tampil
    if (!A || !A.avatar) return;
    var items = list.querySelectorAll('li');
    rows.forEach(function (r, i) {
      if (r.a || !items[i]) return;
      setTimeout(function () {
        var url = '';
        try { url = A.avatar(r.u) || ''; } catch (e) { url = ''; }
        if (!url) return;
        var av = items[i].querySelector('.gg-rk-av');
        if (av) av.innerHTML = '<img src="' + url + '" alt="">';
      }, 0);
    });
  }

  function toast(msg) {
    var t = $('toast'); t.textContent = msg; t.style.display = 'block';
    clearTimeout(toast._t); toast._t = setTimeout(function () { t.style.display = 'none'; }, 2200);
  }

  function setBalance(b) { balance = Number(b) || 0; $('balance').textContent = fmt(balance); }
  function totalBets() { var s = 0; for (var k in bets) s += bets[k]; return s; }

  /* ---------- susun layar ---------- */
  var wheel = $('wheel');
  FRUITS.forEach(function (f, i) {
    var a = (i * 45 - 90) * Math.PI / 180;
    var b = document.createElement('button');
    b.className = 'gg-fruit'; b.id = 'f_' + f.id;
    b.style.left = (50 + R * Math.cos(a)) + '%';
    b.style.top = (50 + R * Math.sin(a)) + '%';
    b.innerHTML = '<span class="gg-ball"><img src="' + f.img + '" alt=""></span>' +
      '<span class="gg-mult">X' + f.mult + '</span><span class="gg-mine" style="display:none"></span>';
    b.addEventListener('click', function () { placeBet(f); });
    wheel.appendChild(b);
  });

  var chipsBox = $('chips');
  CHIPS.forEach(function (c, i) {
    var b = document.createElement('button');
    b.className = 'gg-chip ' + c.c + (i === chip ? ' is-sel' : '');
    b.textContent = c.l;
    b.addEventListener('click', function () {
      chip = i;
      var all = chipsBox.querySelectorAll('.gg-chip');
      for (var j = 0; j < all.length; j++) all[j].className = 'gg-chip ' + CHIPS[j].c + (j === chip ? ' is-sel' : '');
    });
    chipsBox.appendChild(b);
  });

  $('btnRank').onclick = openRank;
  $('btnRankClose').onclick = function () { $('rankPage').style.display = 'none'; };
  $('btnRules').onclick = function () { $('rules').style.display = 'block'; };
  $('btnRulesClose').onclick = function () { $('rules').style.display = 'none'; };

  function renderMine() {
    FRUITS.forEach(function (f) {
      var el = $('f_' + f.id).querySelector('.gg-mine');
      if (bets[f.id]) { el.style.display = ''; el.textContent = 'Mine:' + fmt(bets[f.id]); }
      else el.style.display = 'none';
    });
    $('myBets').textContent = totalBets() ? fmt(totalBets()) : '0';
  }

  /* ---------- taruhan: potong koin lewat dompet Supabase ---------- */
  function placeBet(f) {
    var now = Date.now() + offset;
    if (secIn(now) >= BET) { toast('Waktu taruhan sudah habis'); return; }
    if (placing) return;
    var v = CHIPS[chip].v;
    if (balance < v) { toast('Saldo koin tidak cukup'); return; }
    placing = true;
    setTimeout(function () {
      var nb = A && A.bet ? Number(A.bet(v)) : balance - v;
      placing = false;
      if (nb < 0) {
        var why = A && A.lastError ? A.lastError() : '';
        toast('Taruhan gagal' + (why ? ': ' + why : ''));
        return;
      }
      setBalance(nb);
      bets[f.id] = (bets[f.id] || 0) + v;
      renderMine();
    }, 0);
  }

  /* ---------- hasil ronde ---------- */
  function showResult(round, fruit, myWin, winners) {
    var html = '<div class="gg-dialog"><h3>Hasil Putaran ' + (round % 100000) + '</h3>' +
      '<div class="gg-result-fruit"><img src="' + fruit.img + '" alt=""><span>X' + fruit.mult + '</span></div>' +
      '<p class="gg-sub">' + (myWin ? 'Kamu menang 🪙 ' + fmt(myWin) + '!' : 'Kamu tidak menang putaran ini') + '</p>' +
      '<h4>🏆 Top 3 Pemenang</h4>';
    if (!winners.length) html += '<p class="gg-sub">Belum ada pemenang di putaran ini</p>';
    html += '<ol class="gg-top3">';
    winners.forEach(function (w, i) {
      html += '<li' + (w.me ? ' class="is-me"' : '') + '>' + avatarHtml(w.a, w.n) +
        '<span class="gg-rank r' + (i + 1) + '">' + (i + 1) +
        '</span><span class="gg-wname">' + String(w.n).replace(/[<>&]/g, '') + '</span><span>🪙 ' + fmt(w.w) + '</span></li>';
    });
    html += '</ol></div>';
    $('modal').innerHTML = html; $('modal').style.display = 'flex';
  }

  function settle(round) {
    var f = FRUITS[resultOf(round)];
    var myBet = bets[f.id] || 0;
    var myWin = myBet * f.mult;
    var betTotal = totalBets();
    var snapshotBets = bets;
    bets = {}; renderMine();

    if (myWin > 0 && A && A.win) {
      var nb = Number(A.win(myWin));
      if (nb >= 0) setBalance(nb); else setBalance(balance + myWin);
    } else if (myWin > 0) setBalance(balance + myWin);
    lastWin = myWin; $('lastWin').textContent = fmt(lastWin);

    var winners = [];
    if (A && A.report && betTotal > 0) {
      try { winners = JSON.parse(A.report(round, betTotal, myWin) || '[]'); } catch (e) { winners = []; }
    }
    winners = winners.filter(function (w) { return w.w > 0; });
    if (!winners.length && myWin > 0) { var mine = ''; try { mine = A && A.avatar ? A.avatar('') : ''; } catch (e) { mine = ''; } winners = [{ n: 'Saya', w: myWin, me: true, a: mine }]; }
    showResult(round, f, myWin, winners);
  }

  /* ---------- tampilan per-frame, semuanya dihitung dari jam ---------- */
  function paintHistory(round) {
    var h = '';
    for (var i = 0; i < 7; i++) {
      var f = FRUITS[resultOf(round - 1 - i)];
      h += '<span class="gg-hball"><img src="' + f.img + '" alt="">' + (i === 0 ? '<em>Last</em>' : '') + '</span>';
    }
    h += '<span class="gg-more">Lainnya ›</span>';
    $('history').innerHTML = h;
  }

  function tick() {
    var now = Date.now() + offset;
    var round = roundOf(now), sec = secIn(now);
    var phase = sec < BET ? 'bet' : sec < BET + SPIN ? 'spin' : 'show';
    var target = resultOf(round);

    $('roundNo').textContent = round % 100000;
    $('jackpot').textContent = fmt(6099084 + (round % 100000) * 37 + Math.floor(totalBets() * 0.01));

    if (round !== lastRoundSeen) {
      lastRoundSeen = round; paintHistory(round);
      $('modal').style.display = 'none';
    }

    var c = $('center');
    if (phase === 'bet') {
      hl = -1;
      c.innerHTML = '<b>' + Math.ceil(BET - sec) + 's</b><small>countdown</small>';
    } else {
      var total = 32 + target;
      var p = Math.min((sec - BET) / (SPIN - 0.6), 1);
      var eased = 1 - Math.pow(1 - p, 3);
      hl = Math.floor(eased * total) % 8;
      c.innerHTML = '<img class="gg-center-img" src="' + FRUITS[hl].img + '" alt=""><small>' + (phase === 'spin' ? 'undian...' : 'hasil') + '</small>';
      if (phase === 'show' && settled !== round) { settled = round; settle(round); }
    }
    FRUITS.forEach(function (f, i) {
      $('f_' + f.id).className = 'gg-fruit' + (hl === i ? ' is-active' : '') + (phase === 'show' && hl !== i ? ' is-dim' : '');
    });
  }

  /* ---------- mulai ---------- */
  function boot() {
    if (A && A.serverTime) { var st = Number(A.serverTime()); if (st > 0) offset = st - Date.now(); }
    if (A && A.balance) setBalance(Number(A.balance()));
    renderMine(); paintHistory(roundOf(Date.now() + offset));
    // jangan langsung "menyelesaikan" ronde yang sedang berjalan saat baru dibuka
    settled = roundOf(Date.now() + offset);
    setInterval(tick, 100); tick();
  }
  boot();
})();
