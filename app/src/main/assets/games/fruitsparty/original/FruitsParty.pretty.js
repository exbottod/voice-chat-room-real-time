import {
  n as e,
  r as t,
  t as n
} from "./index-i24743aa.js";
var r = t(e()),
  i = `/assets/loading-bg-BmMCXqAP.jpg`,
  a = (...e) => e.filter((e, t, n) => !!e && e.trim() !== `` && n.indexOf(e) === t).join(` `).trim(),
  o = e => e.replace(/([a-z0-9])([A-Z])/g, `$1-$2`).toLowerCase(),
  s = e => e.replace(/^([A-Z])|[\s-_]+(\w)/g, (e, t, n) => n ? n.toUpperCase() : t.toLowerCase()),
  c = e => {
    let t = s(e);
    return t.charAt(0).toUpperCase() + t.slice(1)
  },
  l = {
    xmlns: `http://www.w3.org/2000/svg`,
    width: 24,
    height: 24,
    viewBox: `0 0 24 24`,
    fill: `none`,
    stroke: `currentColor`,
    strokeWidth: 2,
    strokeLinecap: `round`,
    strokeLinejoin: `round`
  },
  u = e => {
    for (let t in e)
      if (t.startsWith(`aria-`) || t === `role` || t === `title`) return !0;
    return !1
  },
  d = (0, r.forwardRef)(({
    color: e = `currentColor`,
    size: t = 24,
    strokeWidth: n = 2,
    absoluteStrokeWidth: i,
    className: o = ``,
    children: s,
    iconNode: c,
    ...d
  }, f) => (0, r.createElement)(`svg`, {
    ref: f,
    ...l,
    width: t,
    height: t,
    stroke: e,
    strokeWidth: i ? Number(n) * 24 / Number(t) : n,
    className: a(`lucide`, o),
    ...!s && !u(d) && {
      "aria-hidden": `true`
    },
    ...d
  }, [...c.map(([e, t]) => (0, r.createElement)(e, t)), ...Array.isArray(s) ? s : [s]])),
  f = (e, t) => {
    let n = (0, r.forwardRef)(({
      className: n,
      ...i
    }, s) => (0, r.createElement)(d, {
      ref: s,
      iconNode: t,
      className: a(`lucide-${o(c(e))}`, `lucide-${e}`, n),
      ...i
    }));
    return n.displayName = c(e), n
  },
  p = f(`chevron-right`, [
    [`path`, {
      d: `m9 18 6-6-6-6`,
      key: `mthhwq`
    }]
  ]),
  m = f(`crown`, [
    [`path`, {
      d: `M11.562 3.266a.5.5 0 0 1 .876 0L15.39 8.87a1 1 0 0 0 1.516.294L21.183 5.5a.5.5 0 0 1 .798.519l-2.834 10.246a1 1 0 0 1-.956.734H5.81a1 1 0 0 1-.957-.734L2.02 6.02a.5.5 0 0 1 .798-.519l4.276 3.664a1 1 0 0 0 1.516-.294z`,
      key: `1vdc57`
    }],
    [`path`, {
      d: `M5 21h14`,
      key: `11awu3`
    }]
  ]),
  h = f(`lightbulb`, [
    [`path`, {
      d: `M15 14c.2-1 .7-1.7 1.5-2.5 1-.9 1.5-2.2 1.5-3.5A6 6 0 0 0 6 8c0 1 .2 2.2 1.5 3.5.7.7 1.3 1.5 1.5 2.5`,
      key: `1gvzjb`
    }],
    [`path`, {
      d: `M9 18h6`,
      key: `x1upvd`
    }],
    [`path`, {
      d: `M10 22h4`,
      key: `ceow96`
    }]
  ]),
  g = f(`power`, [
    [`path`, {
      d: `M12 2v10`,
      key: `mnfbl`
    }],
    [`path`, {
      d: `M18.4 6.6a9 9 0 1 1-12.77.04`,
      key: `obofu9`
    }]
  ]),
  _ = f(`volume-2`, [
    [`path`, {
      d: `M11 4.702a.705.705 0 0 0-1.203-.498L6.413 7.587A1.4 1.4 0 0 1 5.416 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2.416a1.4 1.4 0 0 1 .997.413l3.383 3.384A.705.705 0 0 0 11 19.298z`,
      key: `uqj9uw`
    }],
    [`path`, {
      d: `M16 9a5 5 0 0 1 0 6`,
      key: `1q6k2b`
    }],
    [`path`, {
      d: `M19.364 18.364a9 9 0 0 0 0-12.728`,
      key: `ijwkga`
    }]
  ]),
  v = f(`volume-x`, [
    [`path`, {
      d: `M11 4.702a.705.705 0 0 0-1.203-.498L6.413 7.587A1.4 1.4 0 0 1 5.416 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2.416a1.4 1.4 0 0 1 .997.413l3.383 3.384A.705.705 0 0 0 11 19.298z`,
      key: `uqj9uw`
    }],
    [`line`, {
      x1: `22`,
      x2: `16`,
      y1: `9`,
      y2: `15`,
      key: `1ewh16`
    }],
    [`line`, {
      x1: `16`,
      x2: `22`,
      y1: `9`,
      y2: `15`,
      key: `5ykzw1`
    }]
  ]),
  y = f(`wifi`, [
    [`path`, {
      d: `M12 20h.01`,
      key: `zekei9`
    }],
    [`path`, {
      d: `M2 8.82a15 15 0 0 1 20 0`,
      key: `dnpr2z`
    }],
    [`path`, {
      d: `M5 12.859a10 10 0 0 1 14 0`,
      key: `1x1e6c`
    }],
    [`path`, {
      d: `M8.5 16.429a5 5 0 0 1 7 0`,
      key: `1bycff`
    }]
  ]),
  b = `/assets/lemon-3I0zOtaB.png`,
  x = `/assets/raspberry-7RC_4E2m.png`,
  S = `/assets/kiwi-D6Ok9y_g.png`,
  C = `/assets/cherry-BlkpnqMb.png`,
  w = `/assets/plum-C607fkJq.png`,
  T = `/assets/watermelon-DhD69GuB.png`,
  E = `/assets/banana-CkKGY-_w.png`,
  D = `/assets/strawberry-F2Mi9-nY.png`,
  O = {
    "2x": `/assets/mult-2x-CwQag8y2.png`,
    "3x": `/assets/mult-3x-HYFo5R4C.png`
  },
  k = e => e === `x2` ? `2x` : e === `x3` ? `3x` : null,
  A = {
    lemon: {
      id: `lemon`,
      img: b,
      times: 5,
      tone: `teal`
    },
    raspberry: {
      id: `raspberry`,
      img: x,
      times: 5,
      tone: `teal`
    },
    kiwi: {
      id: `kiwi`,
      img: S,
      times: 5,
      tone: `teal`
    },
    cherry: {
      id: `cherry`,
      img: C,
      times: 40,
      tone: `purple`
    },
    plum: {
      id: `plum`,
      img: w,
      times: 5,
      tone: `teal`
    },
    watermelon: {
      id: `watermelon`,
      img: T,
      times: 20,
      tone: `purple`
    },
    banana: {
      id: `banana`,
      img: E,
      times: 10,
      tone: `purple`
    },
    strawberry: {
      id: `strawberry`,
      img: D,
      times: 10,
      tone: `purple`
    }
  },
  j = [`lemon`, `raspberry`, `kiwi`, `cherry`, null, `plum`, `watermelon`, `banana`, `strawberry`],
  M = [`lemon`, `raspberry`, `kiwi`, `plum`, `strawberry`, `banana`, `watermelon`, `cherry`];

function N(e = Math.random) {
  let t = M.map(e => 1 / A[e].times),
    n = t.reduce((e, t) => e + t, 0),
    r = e() * n;
  for (let e = 0; e < M.length; e++)
    if (r -= t[e], r <= 0) return M[e];
  return `cherry`
}

function ee(e = Math.random) {
  let t = e();
  return t < .55 ? `none` : t < .68 ? `+1` : t < .77 ? `+2` : t < .82 ? `+3` : t < .92 ? `x2` : t < .98 ? `x3` : `jackpot`
}

function te(e) {
  return e >= 1e6 ? .7 : e >= 1e5 ? .5 : e >= 1e4 ? .3 : e >= 1e3 ? .1 : 0
}
var P = [{
    value: 5e4,
    label: `50K`
  }, {
    value: 5e5,
    label: `500K`
  }, {
    value: 1e6,
    label: `1M`
  }, {
    value: 5e6,
    label: `5M`
  }],
  F = e => e >= 1e6 ? `${e/1e6}M` : e >= 1e3 ? `${e/1e3}K` : `${e}`,
  I = e => Object.values(e).reduce((e, t) => e + (t ?? 0), 0),
  ne = e => e === `x2` ? 2 : e === `x3` ? 3 : e === `jackpot` ? 5 : 1,
  L = (e, t, n) => Array.from(new Set(t)).reduce((t, n) => t + (e[n] ?? 0) * A[n].times, 0) * n,
  R = n(),
  z = 20,
  re = 5,
  ie = e => e === `none` ? `—` : e === `jackpot` ? `JP` : e;

function B({
  onDone: e
}) {
  let [t, n] = (0, r.useState)(0);
  return (0, r.useEffect)(() => {
    let t = setInterval(() => n(n => {
      let r = Math.min(100, n + 2 + Math.random() * 4);
      return r >= 100 && (clearInterval(t), setTimeout(e, 300)), r
    }), 80);
    return () => clearInterval(t)
  }, [e]), (0, R.jsxs)(`div`, {
    className: `fp-root relative flex h-dvh justify-center overflow-hidden`,
    "data-tsd-source": `/src/components/FruitsParty.tsx:31:5`,
    children: [(0, R.jsx)(`img`, {
      src: i,
      alt: `Fruit Party`,
      width: 864,
      height: 1536,
      className: `absolute inset-0 h-full w-full object-cover`,
      "data-tsd-source": `/src/components/FruitsParty.tsx:32:7`
    }), (0, R.jsxs)(`div`, {
      className: `absolute bottom-[16%] left-1/2 w-[72%] max-w-[340px] -translate-x-1/2`,
      "data-tsd-source": `/src/components/FruitsParty.tsx:33:7`,
      children: [(0, R.jsx)(`div`, {
        className: `fp-load-track h-4 overflow-hidden rounded-full p-[2px]`,
        "data-tsd-source": `/src/components/FruitsParty.tsx:34:9`,
        children: (0, R.jsx)(`div`, {
          className: `fp-load-bar h-full rounded-full transition-[width] duration-100`,
          style: {
            width: `${t}%`
          },
          "data-tsd-source": `/src/components/FruitsParty.tsx:35:11`
        })
      }), (0, R.jsxs)(`div`, {
        className: `mt-1 text-center text-xs font-bold text-fp-gold`,
        "data-tsd-source": `/src/components/FruitsParty.tsx:37:9`,
        children: [Math.floor(t), `%`]
      })]
    })]
  })
}

function V() {
  let [e, t] = (0, r.useState)(!1);
  return e ? (0, R.jsx)(H, {
    "data-tsd-source": `/src/components/FruitsParty.tsx:46:10`
  }) : (0, R.jsx)(B, {
    onDone: () => t(!0),
    "data-tsd-source": `/src/components/FruitsParty.tsx:45:23`
  })
}

function H() {
  let [e, t] = (0, r.useState)(1612), [n, i] = (0, r.useState)(`bet`), [a, o] = (0, r.useState)(z), [s, c] = (0, r.useState)(298578147), [l, u] = (0, r.useState)(1e7), [d, f] = (0, r.useState)(0), [b, x] = (0, r.useState)(1), [S, C] = (0, r.useState)({}), [w, T] = (0, r.useState)({}), [E, D] = (0, r.useState)(!1), [B, V] = (0, r.useState)(-1), [H, U] = (0, r.useState)([]), [W, G] = (0, r.useState)([{
    fruit: `lemon`
  }, {
    fruit: `cherry`
  }, {
    fruit: `raspberry`
  }, {
    fruit: `watermelon`
  }, {
    fruit: `cherry`
  }, {
    fruit: `raspberry`
  }, {
    fruit: `plum`
  }, {
    fruit: `plum`
  }]), [K, q] = (0, r.useState)([`+2`, `+2`, `+2`]), [J, ae] = (0, r.useState)(!0), [oe, Y] = (0, r.useState)(!1), [X, Z] = (0, r.useState)(null), [se, ce] = (0, r.useState)(0), Q = (0, r.useRef)(null), [le, ue] = (0, r.useState)(1);
  (0, r.useEffect)(() => {
    let e = () => {
      let e = Q.current;
      e && ue(Math.min(1, window.innerHeight / e.offsetHeight, window.innerWidth / Math.min(e.offsetWidth, 440)))
    };
    return e(), window.addEventListener(`resize`, e), () => window.removeEventListener(`resize`, e)
  }, []);
  let $ = (0, r.useRef)(S);
  $.current = S;
  let de = I(S);
  (0, r.useEffect)(() => {
    let e = setInterval(() => c(e => e + Math.floor(Math.random() * 2e4)), 800);
    return () => clearInterval(e)
  }, []), (0, r.useEffect)(() => {
    if (n !== `bet`) return;
    if (a <= 0) {
      pe();
      return
    }
    let e = setTimeout(() => o(e => e - 1), 1e3);
    return () => clearTimeout(e)
  }, [n, a]), (0, r.useEffect)(() => {
    if (n === `bet` && a === z && E) {
      let e = I(w);
      e > 0 && e <= l && (C(w), u(t => t - e))
    }
  }, [n, a]);

  function fe(e) {
    if (n !== `bet`) return;
    let t = P[b].value;
    l < t || (u(e => e - t), C(n => ({
      ...n,
      [e]: (n[e] ?? 0) + t
    })))
  }

  function pe() {
    i(`spin`);
    let e = ee();
    q([e, e, e]);
    let t = e === `x2` ? 2 : e === `x3` ? 3 : 1,
      n = [],
      r = B >= 0 ? B : 0,
      a = i => {
        let o = N(),
          s = M.length * (i === 0 ? 3 : 2) + (M.indexOf(o) - r + M.length) % M.length,
          c = 0,
          l = () => {
            if (V((r + c) % M.length), c >= s) {
              r = M.indexOf(o), n.push(o), U([...n]), n.length < t ? setTimeout(() => a(i + 1), 700) : me(n, e);
              return
            }
            c++;
            let u = s - c;
            setTimeout(l, u < 8 ? 80 + (8 - u) * 45 : 55)
          };
        l()
      };
    a(0)
  }

  function me(t, n) {
    let r = t[0],
      a = [...t],
      c = n === `+1` ? 1 : n === `+2` ? 2 : n === `+3` ? 3 : 0,
      l = M.filter(e => !t.includes(e)).sort(() => Math.random() - .5);
    a = a.concat(l.slice(0, c)), n === `jackpot` && (a = [...M]);
    let d = ne(n);
    U(Array.from(new Set(a)));
    let p = $.current,
      m = I(p),
      h = L(p, a, d);
    n === `jackpot` && m > 0 && (h += Math.floor(s * te(m) * .05));
    let g = h > 0 ? [{
      name: `You`,
      win: h,
      me: !0
    }] : [];
    u(e => e + h), f(e => e + h), m > 0 && T(p);
    let _ = k(n),
      v = _ ? [{
        mult: _
      }] : t.map(e => ({
        fruit: e
      }));
    G(e => [...[...v].reverse(), ...e].slice(0, 8)), ce(_ ? 1 : Math.min(t.length, 8)), Z({
      round: e,
      fruit: r,
      spun: t,
      earn: h,
      cost: m,
      bonus: n,
      ranking: g
    }), i(`result`), o(re)
  }(0, r.useEffect)(() => {
    if (n !== `result`) return;
    if (a <= 0) {
      Z(null), U([]), V(-1), C({}), t(e => e + 1), o(z), i(`bet`);
      return
    }
    let e = setTimeout(() => o(e => e - 1), 1e3);
    return () => clearTimeout(e)
  }, [n, a]);
  let he = B >= 0 ? M[B] : null;
  return (0, R.jsx)(`div`, {
    className: `fp-root flex h-dvh justify-center overflow-hidden`,
    "data-tsd-source": `/src/components/FruitsParty.tsx:197:5`,
    children: (0, R.jsxs)(`div`, {
      ref: Q,
      style: {
        transform: `scale(${le})`,
        transformOrigin: `top center`
      },
      className: `relative h-fit w-full max-w-[440px] shrink-0 select-none overflow-hidden px-2 pb-2 pt-2`,
      "data-tsd-source": `/src/components/FruitsParty.tsx:198:7`,
      children: [(0, R.jsxs)(`div`, {
        className: `relative h-[190px]`,
        "data-tsd-source": `/src/components/FruitsParty.tsx:200:9`,
        children: [(0, R.jsx)(`button`, {
          "aria-label": `Reset`,
          onClick: () => {
            u(1e7), f(0)
          },
          className: `fp-icon-btn absolute left-1 top-1 grid size-11 place-items-center rounded-xl`,
          "data-tsd-source": `/src/components/FruitsParty.tsx:201:11`,
          children: (0, R.jsx)(g, {
            className: `size-6`,
            "data-tsd-source": `/src/components/FruitsParty.tsx:201:187`
          })
        }), (0, R.jsx)(`button`, {
          "aria-label": `Sound`,
          onClick: () => ae(e => !e),
          className: `fp-icon-btn absolute left-1 top-[60px] grid size-11 place-items-center rounded-xl`,
          "data-tsd-source": `/src/components/FruitsParty.tsx:202:11`,
          children: J ? (0, R.jsx)(v, {
            className: `size-6`,
            "data-tsd-source": `/src/components/FruitsParty.tsx:202:177`
          }) : (0, R.jsx)(_, {
            className: `size-6`,
            "data-tsd-source": `/src/components/FruitsParty.tsx:202:210`
          })
        }), (0, R.jsxs)(`div`, {
          className: `absolute left-2 top-[112px] flex flex-col items-center text-[10px] text-fp-gold`,
          "data-tsd-source": `/src/components/FruitsParty.tsx:203:11`,
          children: [(0, R.jsx)(y, {
            className: `size-6`,
            "data-tsd-source": `/src/components/FruitsParty.tsx:203:108`
          }), `109ms`]
        }), (0, R.jsx)(`button`, {
          "aria-label": `Rules`,
          onClick: () => Y(!0),
          className: `fp-icon-btn absolute right-1 top-1 grid size-11 place-items-center rounded-xl`,
          "data-tsd-source": `/src/components/FruitsParty.tsx:204:11`,
          children: (0, R.jsx)(h, {
            className: `size-6`,
            "data-tsd-source": `/src/components/FruitsParty.tsx:204:162`
          })
        }), (0, R.jsxs)(`div`, {
          className: `absolute left-[64px] top-0 text-lg text-fp-dim`,
          "data-tsd-source": `/src/components/FruitsParty.tsx:206:11`,
          children: [`round:`, e]
        }), (0, R.jsx)(`div`, {
          className: `fp-jackpot-title pointer-events-none absolute inset-x-0 top-5 text-center text-[52px] leading-none`,
          "data-tsd-source": `/src/components/FruitsParty.tsx:207:11`,
          children: `JACKPOT`
        }), (0, R.jsx)(`div`, {
          className: `fp-jackpot-box absolute left-1/2 top-[78px] -translate-x-1/2 rounded-md px-3 py-0.5`,
          "data-tsd-source": `/src/components/FruitsParty.tsx:208:11`,
          children: (0, R.jsx)(`span`, {
            className: `fp-gold-text text-3xl font-black tabular-nums`,
            "data-tsd-source": `/src/components/FruitsParty.tsx:209:13`,
            children: s.toLocaleString(`en-US`)
          })
        }), (0, R.jsx)(`div`, {
          className: `absolute left-1/2 top-[122px] flex -translate-x-1/2 gap-1 rounded-md p-1 fp-jackpot-box`,
          "data-tsd-source": `/src/components/FruitsParty.tsx:211:11`,
          children: K.map((e, t) => (0, R.jsx)(`div`, {
            className: `fp-reel grid h-14 w-16 place-items-center rounded`,
            "data-tsd-source": `/src/components/FruitsParty.tsx:213:15`,
            children: (0, R.jsx)(`div`, {
              className: `fp-diamond grid size-9 rotate-45 place-items-center`,
              "data-tsd-source": `/src/components/FruitsParty.tsx:214:17`,
              children: (0, R.jsx)(`span`, {
                className: `-rotate-45 text-xs font-black text-fp-text`,
                "data-tsd-source": `/src/components/FruitsParty.tsx:215:19`,
                children: n === `spin` ? `?` : ie(e)
              })
            })
          }, t))
        })]
      }), (0, R.jsxs)(`div`, {
        className: `fp-cabinet relative rounded-t-3xl px-3 pb-3 pt-4`,
        "data-tsd-source": `/src/components/FruitsParty.tsx:223:9`,
        children: [(0, R.jsx)(`div`, {
          className: `fp-lights absolute bottom-24 left-0 top-8 w-2 rounded`,
          "data-tsd-source": `/src/components/FruitsParty.tsx:224:11`
        }), (0, R.jsx)(`div`, {
          className: `fp-lights absolute bottom-24 right-0 top-8 w-2 rounded`,
          "data-tsd-source": `/src/components/FruitsParty.tsx:225:11`
        }), (0, R.jsxs)(`div`, {
          className: `fp-board rounded-xl p-2`,
          "data-tsd-source": `/src/components/FruitsParty.tsx:226:11`,
          children: [(0, R.jsx)(`div`, {
            className: `grid grid-cols-3 gap-2`,
            "data-tsd-source": `/src/components/FruitsParty.tsx:227:13`,
            children: j.map((e, t) => {
              if (!e) return (0, R.jsx)(`div`, {
                className: `fp-timer grid aspect-[1/0.92] place-items-center`,
                "data-tsd-source": `/src/components/FruitsParty.tsx:231:21`,
                children: (0, R.jsx)(`span`, {
                  className: `fp-digit text-5xl font-black`,
                  "data-tsd-source": `/src/components/FruitsParty.tsx:232:23`,
                  children: n === `result` ? `--` : String(a).padStart(2, `0`)
                })
              }, t);
              let r = A[e],
                i = he === e || H.includes(e),
                o = S[e] ?? 0;
              return (0, R.jsxs)(`button`, {
                onClick: () => fe(e),
                className: `fp-tile relative flex aspect-[1/0.92] flex-col items-center justify-end pb-1 ${i?`fp-tile-on`:r.tone===`teal`?`fp-tile-teal`:`fp-tile-purple`} ${n===`result`&&H.includes(e)?`fp-tile-win`:``}`,
                "data-tsd-source": `/src/components/FruitsParty.tsx:240:19`,
                children: [(0, R.jsx)(`img`, {
                  src: r.img,
                  alt: e,
                  width: 816,
                  height: 816,
                  className: `absolute inset-x-0 top-1 mx-auto h-[62%] w-auto object-contain drop-shadow-lg`,
                  "data-tsd-source": `/src/components/FruitsParty.tsx:242:21`
                }), o > 0 && (0, R.jsx)(`span`, {
                  className: `fp-chip absolute right-2 top-2 rounded-full px-1.5 text-[10px] font-black`,
                  "data-tsd-source": `/src/components/FruitsParty.tsx:244:23`,
                  children: F(o)
                }), (0, R.jsxs)(`span`, {
                  className: `relative text-lg font-bold`,
                  "data-tsd-source": `/src/components/FruitsParty.tsx:246:21`,
                  children: [r.times, ` times`]
                })]
              }, e)
            })
          }), (0, R.jsxs)(`div`, {
            className: `mt-2 flex items-center gap-1 rounded-lg bg-background/10 px-2 py-1`,
            "data-tsd-source": `/src/components/FruitsParty.tsx:251:13`,
            children: [(0, R.jsx)(`span`, {
              className: `text-sm text-fp-dim`,
              "data-tsd-source": `/src/components/FruitsParty.tsx:252:15`,
              children: `result`
            }), W.map((e, t) => (0, R.jsxs)(`div`, {
              className: `relative flex-1`,
              "data-tsd-source": `/src/components/FruitsParty.tsx:254:17`,
              children: [(0, R.jsx)(`img`, {
                src: `mult` in e ? O[e.mult] : A[e.fruit].img,
                alt: `mult` in e ? e.mult : e.fruit,
                width: 816,
                height: 816,
                loading: `lazy`,
                className: `mx-auto h-7 w-auto`,
                "data-tsd-source": `/src/components/FruitsParty.tsx:255:19`
              }), t < se && (0, R.jsx)(`span`, {
                className: `absolute -bottom-1 left-1/2 -translate-x-1/2 rounded bg-primary px-1 text-[7px] text-primary-foreground`,
                "data-tsd-source": `/src/components/FruitsParty.tsx:256:36`,
                children: `NEW`
              })]
            }, t))]
          })]
        }), (0, R.jsx)(`div`, {
          className: `mt-3 grid grid-cols-4 gap-1.5`,
          "data-tsd-source": `/src/components/FruitsParty.tsx:263:11`,
          children: P.map((e, t) => (0, R.jsxs)(`div`, {
            className: `flex flex-col items-center gap-2`,
            "data-tsd-source": `/src/components/FruitsParty.tsx:265:15`,
            children: [(0, R.jsxs)(`button`, {
              onClick: () => x(t),
              className: `${b===t?`fp-pill-on`:`fp-pill`} flex w-full items-center justify-center gap-1 rounded-full py-1`,
              "data-tsd-source": `/src/components/FruitsParty.tsx:266:17`,
              children: [(0, R.jsx)(`span`, {
                className: `fp-chip grid size-6 place-items-center rounded-full text-[7px] font-black`,
                "data-tsd-source": `/src/components/FruitsParty.tsx:267:19`,
                children: e.label
              }), (0, R.jsx)(`span`, {
                className: `text-lg font-bold`,
                "data-tsd-source": `/src/components/FruitsParty.tsx:268:19`,
                children: e.label
              })]
            }), (0, R.jsx)(`button`, {
              "aria-label": `Chip ${e.label}`,
              onClick: () => x(t),
              className: `${b===t?`fp-btn-red`:`fp-btn-blue`} h-8 w-16 rounded-[50%]`,
              "data-tsd-source": `/src/components/FruitsParty.tsx:270:17`
            })]
          }, e.label))
        }), (0, R.jsxs)(`div`, {
          className: `mt-5 grid grid-cols-3 gap-2`,
          "data-tsd-source": `/src/components/FruitsParty.tsx:276:11`,
          children: [(0, R.jsxs)(`div`, {
            className: `fp-stat flex flex-col items-center rounded-full py-1`,
            "data-tsd-source": `/src/components/FruitsParty.tsx:277:13`,
            children: [(0, R.jsx)(`span`, {
              className: `text-sm text-fp-dim`,
              "data-tsd-source": `/src/components/FruitsParty.tsx:278:15`,
              children: `mine`
            }), (0, R.jsxs)(`span`, {
              className: `flex items-center gap-1 text-base font-bold text-fp-gold`,
              "data-tsd-source": `/src/components/FruitsParty.tsx:279:15`,
              children: [(0, R.jsx)(`i`, {
                className: `fp-coin size-4`,
                "data-tsd-source": `/src/components/FruitsParty.tsx:279:90`
              }), l.toLocaleString(`en-US`), (0, R.jsx)(p, {
                className: `size-4 text-fp-text`,
                "data-tsd-source": `/src/components/FruitsParty.tsx:279:155`
              })]
            })]
          }), (0, R.jsxs)(`div`, {
            className: `fp-stat flex flex-col items-center rounded-full py-1`,
            "data-tsd-source": `/src/components/FruitsParty.tsx:281:13`,
            children: [(0, R.jsx)(`span`, {
              className: `text-sm text-fp-dim`,
              "data-tsd-source": `/src/components/FruitsParty.tsx:282:15`,
              children: `Today Win`
            }), (0, R.jsxs)(`span`, {
              className: `flex items-center gap-1 text-base font-bold text-fp-gold`,
              "data-tsd-source": `/src/components/FruitsParty.tsx:283:15`,
              children: [(0, R.jsx)(`i`, {
                className: `fp-coin size-4`,
                "data-tsd-source": `/src/components/FruitsParty.tsx:283:90`
              }), d.toLocaleString(`en-US`)]
            })]
          }), (0, R.jsx)(`button`, {
            onClick: () => D(e => !e),
            className: `${E?`fp-pill-on`:`fp-pill`} rounded-full text-2xl font-bold`,
            "data-tsd-source": `/src/components/FruitsParty.tsx:285:13`,
            children: `Auto`
          })]
        }), (0, R.jsxs)(`p`, {
          className: `mt-2 text-center text-xs text-fp-dim`,
          "data-tsd-source": `/src/components/FruitsParty.tsx:287:11`,
          children: [`Bet this round: `, de.toLocaleString(`en-US`)]
        })]
      }), X && (0, R.jsx)(`div`, {
        className: `absolute inset-x-2 top-[210px] z-20`,
        "data-tsd-source": `/src/components/FruitsParty.tsx:292:11`,
        children: (0, R.jsxs)(`div`, {
          className: `fp-panel fp-fade-in relative rounded-3xl px-4 pb-6 pt-10`,
          "data-tsd-source": `/src/components/FruitsParty.tsx:293:13`,
          children: [(0, R.jsxs)(`span`, {
            className: `absolute right-4 top-3 text-xl font-bold text-fp-red`,
            "data-tsd-source": `/src/components/FruitsParty.tsx:294:15`,
            children: [`(`, a, `s)`]
          }), (0, R.jsxs)(`div`, {
            className: `grid grid-cols-[1fr_auto] items-center gap-x-4 gap-y-3 text-lg font-semibold`,
            "data-tsd-source": `/src/components/FruitsParty.tsx:295:15`,
            children: [(0, R.jsxs)(`span`, {
              className: `text-right`,
              "data-tsd-source": `/src/components/FruitsParty.tsx:296:17`,
              children: [`Round `, X.round, `'s Result:`]
            }), (0, R.jsx)(`span`, {
              className: `flex items-center gap-1`,
              "data-tsd-source": `/src/components/FruitsParty.tsx:297:17`,
              children: k(X.bonus) ? (0, R.jsx)(`img`, {
                src: O[k(X.bonus)],
                alt: k(X.bonus),
                width: 816,
                height: 816,
                className: `h-16 w-auto`,
                "data-tsd-source": `/src/components/FruitsParty.tsx:299:21`
              }) : X.spun.map((e, t) => (0, R.jsx)(`img`, {
                src: A[e].img,
                alt: e,
                width: 816,
                height: 816,
                className: `h-12 w-auto`,
                "data-tsd-source": `/src/components/FruitsParty.tsx:302:23`
              }, t))
            }), (0, R.jsx)(`span`, {
              className: `text-right`,
              "data-tsd-source": `/src/components/FruitsParty.tsx:306:17`,
              children: `This Round Earnings:`
            }), (0, R.jsxs)(`span`, {
              className: `flex items-center gap-2`,
              "data-tsd-source": `/src/components/FruitsParty.tsx:307:17`,
              children: [(0, R.jsx)(`i`, {
                className: `fp-coin size-6`,
                "data-tsd-source": `/src/components/FruitsParty.tsx:307:59`
              }), X.earn.toLocaleString(`en-US`)]
            }), (0, R.jsx)(`span`, {
              className: `text-right`,
              "data-tsd-source": `/src/components/FruitsParty.tsx:308:17`,
              children: `This Round Cost:`
            }), (0, R.jsxs)(`span`, {
              className: `flex items-center gap-2`,
              "data-tsd-source": `/src/components/FruitsParty.tsx:309:17`,
              children: [(0, R.jsx)(`i`, {
                className: `fp-coin size-6`,
                "data-tsd-source": `/src/components/FruitsParty.tsx:309:59`
              }), X.cost.toLocaleString(`en-US`)]
            }), X.bonus !== `none` && (0, R.jsxs)(R.Fragment, {
              children: [(0, R.jsx)(`span`, {
                className: `text-right`,
                "data-tsd-source": `/src/components/FruitsParty.tsx:310:48`,
                children: `Bonus:`
              }), (0, R.jsx)(`span`, {
                className: `font-black text-fp-gold`,
                "data-tsd-source": `/src/components/FruitsParty.tsx:310:90`,
                children: X.bonus === `jackpot` ? `JACKPOT!` : X.bonus
              })]
            })]
          }), (0, R.jsxs)(`div`, {
            className: `mt-6 flex items-center gap-2 text-fp-gold`,
            "data-tsd-source": `/src/components/FruitsParty.tsx:312:15`,
            children: [(0, R.jsx)(`span`, {
              className: `flex-1 border-t-2 border-dashed border-fp-gold`,
              "data-tsd-source": `/src/components/FruitsParty.tsx:313:17`
            }), (0, R.jsx)(`span`, {
              className: `text-xl font-bold`,
              "data-tsd-source": `/src/components/FruitsParty.tsx:314:17`,
              children: `This Round Ranking:`
            }), (0, R.jsx)(`span`, {
              className: `flex-1 border-t-2 border-dashed border-fp-gold`,
              "data-tsd-source": `/src/components/FruitsParty.tsx:315:17`
            })]
          }), (0, R.jsx)(`div`, {
            className: `mt-4 grid grid-cols-3 items-end gap-2`,
            "data-tsd-source": `/src/components/FruitsParty.tsx:317:15`,
            children: [1, 0, 2].map(e => {
              let t = X.ranking[e];
              return (0, R.jsx)(`div`, {
                className: `flex flex-col items-center ${e===0?`-translate-y-2`:``}`,
                "data-tsd-source": `/src/components/FruitsParty.tsx:321:21`,
                children: t ? (0, R.jsxs)(R.Fragment, {
                  children: [(0, R.jsxs)(`div`, {
                    className: `relative`,
                    "data-tsd-source": `/src/components/FruitsParty.tsx:324:27`,
                    children: [(0, R.jsx)(m, {
                      className: `absolute -right-2 -top-4 size-6 rotate-12 ${e===0?`text-fp-gold`:e===1?`text-fp-dim`:`text-fp-red`}`,
                      fill: `currentColor`,
                      "data-tsd-source": `/src/components/FruitsParty.tsx:325:29`
                    }), (0, R.jsx)(`div`, {
                      className: `grid size-16 place-items-center rounded-full text-2xl font-black ${t.me?`bg-primary text-primary-foreground`:`bg-secondary text-secondary-foreground`}`,
                      "data-tsd-source": `/src/components/FruitsParty.tsx:326:29`,
                      children: t.name[0]
                    })]
                  }), (0, R.jsx)(`span`, {
                    className: `mt-1 text-sm italic`,
                    "data-tsd-source": `/src/components/FruitsParty.tsx:328:27`,
                    children: t.name
                  }), (0, R.jsxs)(`span`, {
                    className: `flex items-center gap-1 text-base font-bold`,
                    "data-tsd-source": `/src/components/FruitsParty.tsx:329:27`,
                    children: [(0, R.jsx)(`i`, {
                      className: `fp-coin size-5`,
                      "data-tsd-source": `/src/components/FruitsParty.tsx:329:89`
                    }), t.win.toLocaleString(`en-US`)]
                  })]
                }) : (0, R.jsx)(`span`, {
                  className: `text-sm text-fp-dim`,
                  "data-tsd-source": `/src/components/FruitsParty.tsx:331:27`,
                  children: `-`
                })
              }, e)
            })
          })]
        })
      }), oe && (0, R.jsx)(`div`, {
        className: `absolute inset-0 z-30 flex items-center justify-center bg-background/40 p-2`,
        onClick: () => Y(!1),
        "data-tsd-source": `/src/components/FruitsParty.tsx:342:11`,
        children: (0, R.jsxs)(`div`, {
          className: `fp-panel fp-fade-in mx-1 flex w-full max-h-full flex-col overflow-hidden rounded-3xl`,
          onClick: e => e.stopPropagation(),
          "data-tsd-source": `/src/components/FruitsParty.tsx:343:13`,
          children: [(0, R.jsxs)(`div`, {
            className: `fp-panel-head relative shrink-0 py-2 text-center text-2xl`,
            "data-tsd-source": `/src/components/FruitsParty.tsx:344:15`,
            children: [`Rule`, (0, R.jsx)(`button`, {
              onClick: () => Y(!1),
              className: `absolute right-4 top-2 text-xl`,
              "data-tsd-source": `/src/components/FruitsParty.tsx:345:17`,
              children: `✕`
            })]
          }), (0, R.jsxs)(`div`, {
            className: `min-h-0 flex-1 space-y-2 overflow-y-auto px-4 py-4 text-[15px] leading-relaxed`,
            "data-tsd-source": `/src/components/FruitsParty.tsx:347:15`,
            children: [(0, R.jsx)(`p`, {
              "data-tsd-source": `/src/components/FruitsParty.tsx:348:17`,
              children: `1. Choose your cost amount and choose the fruit to cost on;`
            }), (0, R.jsx)(`p`, {
              "data-tsd-source": `/src/components/FruitsParty.tsx:349:17`,
              children: `2. Please finish selection before the end of the countdown, the result will be announced immediately after counting down;`
            }), (0, R.jsx)(`p`, {
              "data-tsd-source": `/src/components/FruitsParty.tsx:350:17`,
              children: `3. If the published result matches the fruit you selected, you will be rewarded according to the corresponding odds;`
            }), (0, R.jsx)(`p`, {
              "data-tsd-source": `/src/components/FruitsParty.tsx:351:17`,
              children: `4. After each round of draw, there is a possibility to activate the minislot game. The slot result may be one of the following bonus: Fruit +1, Fruit +2, Fruit +3, Bonus x2, Bonus x3, Jackpot bonus, or there may not be any bonus.`
            }), (0, R.jsx)(`p`, {
              "data-tsd-source": `/src/components/FruitsParty.tsx:352:17`,
              children: `5. The official bonus pool will increase as more users participate in the game.`
            }), (0, R.jsx)(`p`, {
              className: `pl-4`,
              "data-tsd-source": `/src/components/FruitsParty.tsx:353:17`,
              children: `The winners get 10% of the jackpot for cost of 1,000 - 9,999;`
            }), (0, R.jsx)(`p`, {
              className: `pl-4`,
              "data-tsd-source": `/src/components/FruitsParty.tsx:354:17`,
              children: `The winners get 30% of the jackpot for cost of 10,000 - 99,999;`
            }), (0, R.jsx)(`p`, {
              className: `pl-4`,
              "data-tsd-source": `/src/components/FruitsParty.tsx:355:17`,
              children: `The winners get 50% of the jackpot for cost of 100,000 - 999,999;`
            }), (0, R.jsx)(`p`, {
              className: `pl-4`,
              "data-tsd-source": `/src/components/FruitsParty.tsx:356:17`,
              children: `The winners get 70% of the jackpot when their cost are greater than or equal to 1,000,000.`
            }), (0, R.jsx)(`p`, {
              className: `pt-2`,
              "data-tsd-source": `/src/components/FruitsParty.tsx:357:17`,
              children: `"JACKPOT" bonus distribution rules:`
            }), (0, R.jsx)(`p`, {
              "data-tsd-source": `/src/components/FruitsParty.tsx:358:17`,
              children: `-Distribute according to the proportion of the winners' cost in this round.`
            }), (0, R.jsx)(`p`, {
              "data-tsd-source": `/src/components/FruitsParty.tsx:359:17`,
              children: `-Example: If there are 3 winners, and their cost are 100, 20, and 1 respectively, the total bonus will be divided into 121 parts, and the winners will receive 100, 20, and 1 respectively.`
            })]
          })]
        })
      })]
    })
  })
}
var U = V;
export {
  U as component
};