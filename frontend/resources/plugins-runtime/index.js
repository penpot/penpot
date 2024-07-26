var Hn = (t, e, r) => {
  if (!e.has(t))
    throw TypeError("Cannot " + r);
};
var Ee = (t, e, r) => (Hn(t, e, "read from private field"), r ? r.call(t) : e.get(t)), Gr = (t, e, r) => {
  if (e.has(t))
    throw TypeError("Cannot add the same private member more than once");
  e instanceof WeakSet ? e.add(t) : e.set(t, r);
}, Br = (t, e, r, n) => (Hn(t, e, "write to private field"), n ? n.call(t, r) : e.set(t, r), r);
const k = globalThis, {
  Array: Bs,
  Date: Hs,
  FinalizationRegistry: kt,
  Float32Array: Vs,
  JSON: Ws,
  Map: Pe,
  Math: qs,
  Number: So,
  Object: _n,
  Promise: Ks,
  Proxy: Cr,
  Reflect: Ys,
  RegExp: We,
  Set: Ct,
  String: pe,
  Symbol: St,
  WeakMap: Me,
  WeakSet: $t
} = globalThis, {
  // The feral Error constructor is safe for internal use, but must not be
  // revealed to post-lockdown code in any compartment including the start
  // compartment since in V8 at least it bears stack inspection capabilities.
  Error: ue,
  RangeError: Js,
  ReferenceError: lt,
  SyntaxError: tr,
  TypeError: v,
  AggregateError: Hr
} = globalThis, {
  assign: $r,
  create: Z,
  defineProperties: F,
  entries: re,
  freeze: y,
  getOwnPropertyDescriptor: J,
  getOwnPropertyDescriptors: Ze,
  getOwnPropertyNames: Dt,
  getPrototypeOf: j,
  is: Nr,
  isFrozen: jl,
  isSealed: Zl,
  isExtensible: zl,
  keys: Eo,
  prototype: bn,
  seal: Gl,
  preventExtensions: Xs,
  setPrototypeOf: xo,
  values: ko,
  fromEntries: mt
} = _n, {
  species: Vr,
  toStringTag: qe,
  iterator: rr,
  matchAll: Po,
  unscopables: Qs,
  keyFor: ea,
  for: ta
} = St, { isInteger: ra } = So, { stringify: To } = Ws, { defineProperty: na } = _n, M = (t, e, r) => {
  const n = na(t, e, r);
  if (n !== t)
    throw v(
      `Please report that the original defineProperty silently failed to set ${To(
        pe(e)
      )}. (SES_DEFINE_PROPERTY_FAILED_SILENTLY)`
    );
  return n;
}, {
  apply: ne,
  construct: mr,
  get: oa,
  getOwnPropertyDescriptor: sa,
  has: Ao,
  isExtensible: aa,
  ownKeys: De,
  preventExtensions: ia,
  set: Io
} = Ys, { isArray: Et, prototype: _e } = Bs, { prototype: Nt } = Pe, { prototype: Rr } = RegExp, { prototype: nr } = Ct, { prototype: Le } = pe, { prototype: Or } = Me, { prototype: Co } = $t, { prototype: wn } = Function, { prototype: $o } = Ks, { prototype: No } = j(
  // eslint-disable-next-line no-empty-function, func-names
  function* () {
  }
), ca = j(Uint8Array.prototype), { bind: tn } = wn, P = tn.bind(tn.call), oe = P(bn.hasOwnProperty), Ke = P(_e.filter), ut = P(_e.forEach), Mr = P(_e.includes), Rt = P(_e.join), se = (
  /** @type {any} */
  P(_e.map)
), Ro = (
  /** @type {any} */
  P(_e.flatMap)
), gr = P(_e.pop), X = P(_e.push), la = P(_e.slice), ua = P(_e.some), Oo = P(_e.sort), da = P(_e[rr]), $e = P(Nt.set), Ue = P(Nt.get), Lr = P(Nt.has), fa = P(Nt.delete), pa = P(Nt.entries), ha = P(Nt[rr]), Sn = P(nr.add);
P(nr.delete);
const Vn = P(nr.forEach), En = P(nr.has), ma = P(nr[rr]), xn = P(Rr.test), kn = P(Rr.exec), ga = P(Rr[Po]), Mo = P(Le.endsWith), Lo = P(Le.includes), ya = P(Le.indexOf);
P(Le.match);
const yr = P(No.next), Fo = P(No.throw), vr = (
  /** @type {any} */
  P(Le.replace)
), va = P(Le.search), Pn = P(Le.slice), Tn = P(Le.split), Do = P(Le.startsWith), _a = P(Le[rr]), ba = P(Or.delete), L = P(Or.get), An = P(Or.has), ie = P(Or.set), Fr = P(Co.add), or = P(Co.has), wa = P(wn.toString), Sa = P(tn);
P($o.catch);
const Uo = (
  /** @type {any} */
  P($o.then)
), Ea = kt && P(kt.prototype.register);
kt && P(kt.prototype.unregister);
const In = y(Z(null)), Ye = (t) => _n(t) === t, Dr = (t) => t instanceof ue, jo = eval, ve = Function, xa = () => {
  throw v('Cannot eval with evalTaming set to "noEval" (SES_NO_EVAL)');
}, He = J(Error("er1"), "stack"), Wr = J(v("er2"), "stack");
let Zo, zo;
if (He && Wr && He.get)
  if (
    // In the v8 case as we understand it, all errors have an own stack
    // accessor property, but within the same realm, all these accessor
    // properties have the same getter and have the same setter.
    // This is therefore the case that we repair.
    typeof He.get == "function" && He.get === Wr.get && typeof He.set == "function" && He.set === Wr.set
  )
    Zo = y(He.get), zo = y(He.set);
  else
    throw v(
      "Unexpected Error own stack accessor functions (SES_UNEXPECTED_ERROR_OWN_STACK_ACCESSOR)"
    );
const qr = Zo, ka = zo;
function Pa() {
  return this;
}
if (Pa())
  throw v("SES failed to initialize, sloppy mode (SES_NO_SLOPPY)");
const { freeze: at } = Object, { apply: Ta } = Reflect, Cn = (t) => (e, ...r) => Ta(t, e, r), Aa = Cn(Array.prototype.push), Wn = Cn(Array.prototype.includes), Ia = Cn(String.prototype.split), nt = JSON.stringify, ir = (t, ...e) => {
  let r = t[0];
  for (let n = 0; n < e.length; n += 1)
    r = `${r}${e[n]}${t[n + 1]}`;
  throw Error(r);
}, Go = (t, e = !1) => {
  const r = [], n = (c, l, u = void 0) => {
    typeof c == "string" || ir`Environment option name ${nt(c)} must be a string.`, typeof l == "string" || ir`Environment option default setting ${nt(
      l
    )} must be a string.`;
    let d = l;
    const f = t.process || void 0, h = typeof f == "object" && f.env || void 0;
    if (typeof h == "object" && c in h) {
      e || Aa(r, c);
      const p = h[c];
      typeof p == "string" || ir`Environment option named ${nt(
        c
      )}, if present, must have a corresponding string value, got ${nt(
        p
      )}`, d = p;
    }
    return u === void 0 || d === l || Wn(u, d) || ir`Unrecognized ${nt(c)} value ${nt(
      d
    )}. Expected one of ${nt([l, ...u])}`, d;
  };
  at(n);
  const o = (c) => {
    const l = n(c, "");
    return at(l === "" ? [] : Ia(l, ","));
  };
  at(o);
  const a = (c, l) => Wn(o(c), l), i = () => at([...r]);
  return at(i), at({
    getEnvironmentOption: n,
    getEnvironmentOptionsList: o,
    environmentOptionsListHas: a,
    getCapturedEnvironmentOptionNames: i
  });
};
at(Go);
const {
  getEnvironmentOption: le,
  getEnvironmentOptionsList: Bl,
  environmentOptionsListHas: Hl
} = Go(globalThis, !0), _r = (t) => (t = `${t}`, t.length >= 1 && Lo("aeiouAEIOU", t[0]) ? `an ${t}` : `a ${t}`);
y(_r);
const Bo = (t, e = void 0) => {
  const r = new Ct(), n = (o, a) => {
    switch (typeof a) {
      case "object": {
        if (a === null)
          return null;
        if (En(r, a))
          return "[Seen]";
        if (Sn(r, a), Dr(a))
          return `[${a.name}: ${a.message}]`;
        if (qe in a)
          return `[${a[qe]}]`;
        if (Et(a))
          return a;
        const i = Eo(a);
        if (i.length < 2)
          return a;
        let c = !0;
        for (let u = 1; u < i.length; u += 1)
          if (i[u - 1] >= i[u]) {
            c = !1;
            break;
          }
        if (c)
          return a;
        Oo(i);
        const l = se(i, (u) => [u, a[u]]);
        return mt(l);
      }
      case "function":
        return `[Function ${a.name || "<anon>"}]`;
      case "string":
        return Do(a, "[") ? `[${a}]` : a;
      case "undefined":
      case "symbol":
        return `[${pe(a)}]`;
      case "bigint":
        return `[${a}n]`;
      case "number":
        return Nr(a, NaN) ? "[NaN]" : a === 1 / 0 ? "[Infinity]" : a === -1 / 0 ? "[-Infinity]" : a;
      default:
        return a;
    }
  };
  try {
    return To(t, n, e);
  } catch {
    return "[Something that failed to stringify]";
  }
};
y(Bo);
const { isSafeInteger: Ca } = Number, { freeze: vt } = Object, { toStringTag: $a } = Symbol, qn = (t) => {
  const r = {
    next: void 0,
    prev: void 0,
    data: t
  };
  return r.next = r, r.prev = r, r;
}, Kn = (t, e) => {
  if (t === e)
    throw TypeError("Cannot splice a cell into itself");
  if (e.next !== e || e.prev !== e)
    throw TypeError("Expected self-linked cell");
  const r = e, n = t.next;
  return r.prev = t, r.next = n, t.next = r, n.prev = r, r;
}, Kr = (t) => {
  const { prev: e, next: r } = t;
  e.next = r, r.prev = e, t.prev = t, t.next = t;
}, Ho = (t) => {
  if (!Ca(t) || t < 0)
    throw TypeError("keysBudget must be a safe non-negative integer number");
  const e = /* @__PURE__ */ new WeakMap();
  let r = 0;
  const n = qn(void 0), o = (d) => {
    const f = e.get(d);
    if (!(f === void 0 || f.data === void 0))
      return Kr(f), Kn(n, f), f;
  }, a = (d) => o(d) !== void 0;
  vt(a);
  const i = (d) => {
    const f = o(d);
    return f && f.data && f.data.get(d);
  };
  vt(i);
  const c = (d, f) => {
    if (t < 1)
      return u;
    let h = o(d);
    if (h === void 0 && (h = qn(void 0), Kn(n, h)), !h.data)
      for (r += 1, h.data = /* @__PURE__ */ new WeakMap(), e.set(d, h); r > t; ) {
        const p = n.prev;
        Kr(p), p.data = void 0, r -= 1;
      }
    return h.data.set(d, f), u;
  };
  vt(c);
  const l = (d) => {
    const f = e.get(d);
    return f === void 0 || (Kr(f), e.delete(d), f.data === void 0) ? !1 : (f.data = void 0, r -= 1, !0);
  };
  vt(l);
  const u = vt({
    has: a,
    get: i,
    set: c,
    delete: l,
    // eslint-disable-next-line jsdoc/check-types
    [
      /** @type {typeof Symbol.toStringTag} */
      $a
    ]: "LRUCacheMap"
  });
  return u;
};
vt(Ho);
const { freeze: pr } = Object, { isSafeInteger: Na } = Number, Ra = 1e3, Oa = 100, Vo = (t = Ra, e = Oa) => {
  if (!Na(e) || e < 1)
    throw TypeError(
      "argsPerErrorBudget must be a safe positive integer number"
    );
  const r = Ho(t), n = (a, i) => {
    const c = r.get(a);
    c !== void 0 ? (c.length >= e && c.shift(), c.push(i)) : r.set(a, [i]);
  };
  pr(n);
  const o = (a) => {
    const i = r.get(a);
    return r.delete(a), i;
  };
  return pr(o), pr({
    addLogArgs: n,
    takeLogArgsArray: o
  });
};
pr(Vo);
const Pt = new Me(), Je = (t, e = void 0) => {
  const r = y({
    toString: y(() => Bo(t, e))
  });
  return ie(Pt, r, t), r;
};
y(Je);
const Ma = y(/^[\w:-]( ?[\w:-])*$/), rn = (t, e = void 0) => {
  if (typeof t != "string" || !xn(Ma, t))
    return Je(t, e);
  const r = y({
    toString: y(() => t)
  });
  return ie(Pt, r, t), r;
};
y(rn);
const Ur = new Me(), Wo = ({ template: t, args: e }) => {
  const r = [t[0]];
  for (let n = 0; n < e.length; n += 1) {
    const o = e[n];
    let a;
    An(Pt, o) ? a = `${o}` : Dr(o) ? a = `(${_r(o.name)})` : a = `(${_r(typeof o)})`, X(r, a, t[n + 1]);
  }
  return Rt(r, "");
}, qo = y({
  toString() {
    const t = L(Ur, this);
    return t === void 0 ? "[Not a DetailsToken]" : Wo(t);
  }
});
y(qo.toString);
const ft = (t, ...e) => {
  const r = y({ __proto__: qo });
  return ie(Ur, r, { template: t, args: e }), /** @type {DetailsToken} */
  /** @type {unknown} */
  r;
};
y(ft);
const Ko = (t, ...e) => (e = se(
  e,
  (r) => An(Pt, r) ? r : Je(r)
), ft(t, ...e));
y(Ko);
const Yo = ({ template: t, args: e }) => {
  const r = [t[0]];
  for (let n = 0; n < e.length; n += 1) {
    let o = e[n];
    An(Pt, o) && (o = L(Pt, o));
    const a = vr(gr(r) || "", / $/, "");
    a !== "" && X(r, a);
    const i = vr(t[n + 1], /^ /, "");
    X(r, o, i);
  }
  return r[r.length - 1] === "" && gr(r), r;
}, hr = new Me();
let nn = 0;
const Yn = new Me(), Jo = (t, e = t.name) => {
  let r = L(Yn, t);
  return r !== void 0 || (nn += 1, r = `${e}#${nn}`, ie(Yn, t, r)), r;
}, La = (t) => {
  const e = Ze(t), {
    name: r,
    message: n,
    errors: o = void 0,
    cause: a = void 0,
    stack: i = void 0,
    ...c
  } = e, l = De(c);
  if (l.length >= 1) {
    for (const d of l)
      delete t[d];
    const u = Z(bn, c);
    $n(
      t,
      ft`originally with properties ${Je(u)}`
    );
  }
  for (const u of De(t)) {
    const d = e[u];
    d && oe(d, "get") && M(t, u, {
      value: t[u]
      // invoke the getter to convert to data property
    });
  }
  y(t);
}, on = (t = ft`Assert failed`, e = k.Error, {
  errorName: r = void 0,
  cause: n = void 0,
  errors: o = void 0,
  sanitize: a = !0
} = {}) => {
  typeof t == "string" && (t = ft([t]));
  const i = L(Ur, t);
  if (i === void 0)
    throw v(`unrecognized details ${Je(t)}`);
  const c = Wo(i), l = n && { cause: n };
  let u;
  return typeof Hr < "u" && e === Hr ? u = Hr(o || [], c, l) : (u = /** @type {ErrorConstructor} */
  e(
    c,
    l
  ), o !== void 0 && M(u, "errors", {
    value: o,
    writable: !0,
    enumerable: !1,
    configurable: !0
  })), ie(hr, u, Yo(i)), r !== void 0 && Jo(u, r), a && La(u), u;
};
y(on);
const { addLogArgs: Fa, takeLogArgsArray: Da } = Vo(), sn = new Me(), $n = (t, e) => {
  typeof e == "string" && (e = ft([e]));
  const r = L(Ur, e);
  if (r === void 0)
    throw v(`unrecognized details ${Je(e)}`);
  const n = Yo(r), o = L(sn, t);
  if (o !== void 0)
    for (const a of o)
      a(t, n);
  else
    Fa(t, n);
};
y($n);
const Ua = (t) => {
  if (!("stack" in t))
    return "";
  const e = `${t.stack}`, r = ya(e, `
`);
  return Do(e, " ") || r === -1 ? e : Pn(e, r + 1);
}, br = {
  getStackString: k.getStackString || Ua,
  tagError: (t) => Jo(t),
  resetErrorTagNum: () => {
    nn = 0;
  },
  getMessageLogArgs: (t) => L(hr, t),
  takeMessageLogArgs: (t) => {
    const e = L(hr, t);
    return ba(hr, t), e;
  },
  takeNoteLogArgsArray: (t, e) => {
    const r = Da(t);
    if (e !== void 0) {
      const n = L(sn, t);
      n ? X(n, e) : ie(sn, t, [e]);
    }
    return r || [];
  }
};
y(br);
const jr = (t = void 0, e = !1) => {
  const r = e ? Ko : ft, n = r`Check failed`, o = (f = n, h = void 0, p = void 0) => {
    const m = on(f, h, p);
    throw t !== void 0 && t(m), m;
  };
  y(o);
  const a = (f, ...h) => o(r(f, ...h));
  function i(f, h = void 0, p = void 0, m = void 0) {
    f || o(h, p, m);
  }
  const c = (f, h, p = void 0, m = void 0, _ = void 0) => {
    Nr(f, h) || o(
      p || r`Expected ${f} is same as ${h}`,
      m || Js,
      _
    );
  };
  y(c);
  const l = (f, h, p) => {
    if (typeof f !== h) {
      if (typeof h == "string" || a`${Je(h)} must be a string`, p === void 0) {
        const m = _r(h);
        p = r`${f} must be ${rn(m)}`;
      }
      o(p, v);
    }
  };
  y(l);
  const d = $r(i, {
    error: on,
    fail: o,
    equal: c,
    typeof: l,
    string: (f, h = void 0) => l(f, "string", h),
    note: $n,
    details: r,
    Fail: a,
    quote: Je,
    bare: rn,
    makeAssert: jr
  });
  return y(d);
};
y(jr);
const z = jr(), Xo = J(
  ca,
  qe
);
z(Xo);
const Qo = Xo.get;
z(Qo);
const ja = (t) => ne(Qo, t, []) !== void 0, Za = (t) => {
  const e = +pe(t);
  return ra(e) && pe(e) === t;
}, za = (t) => {
  Xs(t), ut(De(t), (e) => {
    const r = J(t, e);
    z(r), Za(e) || M(t, e, {
      ...r,
      writable: !1,
      configurable: !1
    });
  });
}, Ga = () => {
  if (typeof k.harden == "function")
    return k.harden;
  const t = new $t(), { harden: e } = {
    /**
     * @template T
     * @param {T} root
     * @returns {T}
     */
    harden(r) {
      const n = new Ct();
      function o(d) {
        if (!Ye(d))
          return;
        const f = typeof d;
        if (f !== "object" && f !== "function")
          throw v(`Unexpected typeof: ${f}`);
        or(t, d) || En(n, d) || Sn(n, d);
      }
      const a = (d) => {
        ja(d) ? za(d) : y(d);
        const f = Ze(d), h = j(d);
        o(h), ut(De(f), (p) => {
          const m = f[
            /** @type {string} */
            p
          ];
          oe(m, "value") ? o(m.value) : (o(m.get), o(m.set));
        });
      }, i = qr === void 0 && ka === void 0 ? (
        // On platforms without v8's error own stack accessor problem,
        // don't pay for any extra overhead.
        a
      ) : (d) => {
        if (Dr(d)) {
          const f = J(d, "stack");
          f && f.get === qr && f.configurable && M(d, "stack", {
            // NOTE: Calls getter during harden, which seems dangerous.
            // But we're only calling the problematic getter whose
            // hazards we think we understand.
            // @ts-expect-error TS should know FERAL_STACK_GETTER
            // cannot be `undefined` here.
            // See https://github.com/endojs/endo/pull/2232#discussion_r1575179471
            value: ne(qr, d, [])
          });
        }
        return a(d);
      }, c = () => {
        Vn(n, i);
      }, l = (d) => {
        Fr(t, d);
      }, u = () => {
        Vn(n, l);
      };
      return o(r), c(), u(), r;
    }
  };
  return e;
}, es = {
  // *** Value Properties of the Global Object
  Infinity: 1 / 0,
  NaN: NaN,
  undefined: void 0
}, ts = {
  // *** Function Properties of the Global Object
  isFinite: "isFinite",
  isNaN: "isNaN",
  parseFloat: "parseFloat",
  parseInt: "parseInt",
  decodeURI: "decodeURI",
  decodeURIComponent: "decodeURIComponent",
  encodeURI: "encodeURI",
  encodeURIComponent: "encodeURIComponent",
  // *** Constructor Properties of the Global Object
  Array: "Array",
  ArrayBuffer: "ArrayBuffer",
  BigInt: "BigInt",
  BigInt64Array: "BigInt64Array",
  BigUint64Array: "BigUint64Array",
  Boolean: "Boolean",
  DataView: "DataView",
  EvalError: "EvalError",
  // https://github.com/tc39/proposal-float16array
  Float16Array: "Float16Array",
  Float32Array: "Float32Array",
  Float64Array: "Float64Array",
  Int8Array: "Int8Array",
  Int16Array: "Int16Array",
  Int32Array: "Int32Array",
  Map: "Map",
  Number: "Number",
  Object: "Object",
  Promise: "Promise",
  Proxy: "Proxy",
  RangeError: "RangeError",
  ReferenceError: "ReferenceError",
  Set: "Set",
  String: "String",
  SyntaxError: "SyntaxError",
  TypeError: "TypeError",
  Uint8Array: "Uint8Array",
  Uint8ClampedArray: "Uint8ClampedArray",
  Uint16Array: "Uint16Array",
  Uint32Array: "Uint32Array",
  URIError: "URIError",
  WeakMap: "WeakMap",
  WeakSet: "WeakSet",
  // https://github.com/tc39/proposal-iterator-helpers
  Iterator: "Iterator",
  // https://github.com/tc39/proposal-async-iterator-helpers
  AsyncIterator: "AsyncIterator",
  // https://github.com/endojs/endo/issues/550
  AggregateError: "AggregateError",
  // *** Other Properties of the Global Object
  JSON: "JSON",
  Reflect: "Reflect",
  // *** Annex B
  escape: "escape",
  unescape: "unescape",
  // ESNext
  lockdown: "lockdown",
  harden: "harden",
  HandledPromise: "HandledPromise"
  // TODO: Until Promise.delegate (see below).
}, Jn = {
  // *** Constructor Properties of the Global Object
  Date: "%InitialDate%",
  Error: "%InitialError%",
  RegExp: "%InitialRegExp%",
  // Omit `Symbol`, because we want the original to appear on the
  // start compartment without passing through the whitelist mechanism, since
  // we want to preserve all its properties, even if we never heard of them.
  // Symbol: '%InitialSymbol%',
  // *** Other Properties of the Global Object
  Math: "%InitialMath%",
  // ESNext
  // From Error-stack proposal
  // Only on initial global. No corresponding
  // powerless form for other globals.
  getStackString: "%InitialGetStackString%"
  // TODO https://github.com/Agoric/SES-shim/issues/551
  // Need initial WeakRef and FinalizationGroup in
  // start compartment only.
}, rs = {
  // *** Constructor Properties of the Global Object
  Date: "%SharedDate%",
  Error: "%SharedError%",
  RegExp: "%SharedRegExp%",
  Symbol: "%SharedSymbol%",
  // *** Other Properties of the Global Object
  Math: "%SharedMath%"
}, ns = [
  EvalError,
  RangeError,
  ReferenceError,
  SyntaxError,
  TypeError,
  URIError
  // https://github.com/endojs/endo/issues/550
  // Commented out to accommodate platforms prior to AggregateError.
  // Instead, conditional push below.
  // AggregateError,
];
typeof AggregateError < "u" && X(ns, AggregateError);
const an = {
  "[[Proto]]": "%FunctionPrototype%",
  length: "number",
  name: "string"
  // Do not specify "prototype" here, since only Function instances that can
  // be used as a constructor have a prototype property. For constructors,
  // since prototype properties are instance-specific, we define it there.
}, Ba = {
  // This property is not mentioned in ECMA 262, but is present in V8 and
  // necessary for lockdown to succeed.
  "[[Proto]]": "%AsyncFunctionPrototype%"
}, s = an, Xn = Ba, R = {
  get: s,
  set: "undefined"
}, Ie = {
  get: s,
  set: s
}, Qn = (t) => t === R || t === Ie;
function ot(t) {
  return {
    // Properties of the NativeError Constructors
    "[[Proto]]": "%SharedError%",
    // NativeError.prototype
    prototype: t
  };
}
function st(t) {
  return {
    // Properties of the NativeError Prototype Objects
    "[[Proto]]": "%ErrorPrototype%",
    constructor: t,
    message: "string",
    name: "string",
    // Redundantly present only on v8. Safe to remove.
    toString: !1,
    // Superfluously present in some versions of V8.
    // https://github.com/tc39/notes/blob/master/meetings/2021-10/oct-26.md#:~:text=However%2C%20Chrome%2093,and%20node%2016.11.
    cause: !1
  };
}
function ge(t) {
  return {
    // Properties of the TypedArray Constructors
    "[[Proto]]": "%TypedArray%",
    BYTES_PER_ELEMENT: "number",
    prototype: t
  };
}
function ye(t) {
  return {
    // Properties of the TypedArray Prototype Objects
    "[[Proto]]": "%TypedArrayPrototype%",
    BYTES_PER_ELEMENT: "number",
    constructor: t
  };
}
const eo = {
  E: "number",
  LN10: "number",
  LN2: "number",
  LOG10E: "number",
  LOG2E: "number",
  PI: "number",
  SQRT1_2: "number",
  SQRT2: "number",
  "@@toStringTag": "string",
  abs: s,
  acos: s,
  acosh: s,
  asin: s,
  asinh: s,
  atan: s,
  atanh: s,
  atan2: s,
  cbrt: s,
  ceil: s,
  clz32: s,
  cos: s,
  cosh: s,
  exp: s,
  expm1: s,
  floor: s,
  fround: s,
  hypot: s,
  imul: s,
  log: s,
  log1p: s,
  log10: s,
  log2: s,
  max: s,
  min: s,
  pow: s,
  round: s,
  sign: s,
  sin: s,
  sinh: s,
  sqrt: s,
  tan: s,
  tanh: s,
  trunc: s,
  // See https://github.com/Moddable-OpenSource/moddable/issues/523
  idiv: !1,
  // See https://github.com/Moddable-OpenSource/moddable/issues/523
  idivmod: !1,
  // See https://github.com/Moddable-OpenSource/moddable/issues/523
  imod: !1,
  // See https://github.com/Moddable-OpenSource/moddable/issues/523
  imuldiv: !1,
  // See https://github.com/Moddable-OpenSource/moddable/issues/523
  irem: !1,
  // See https://github.com/Moddable-OpenSource/moddable/issues/523
  mod: !1,
  // See https://github.com/Moddable-OpenSource/moddable/issues/523#issuecomment-1942904505
  irandom: !1
}, wr = {
  // ECMA https://tc39.es/ecma262
  // The intrinsics object has no prototype to avoid conflicts.
  "[[Proto]]": null,
  // %ThrowTypeError%
  "%ThrowTypeError%": s,
  // *** The Global Object
  // *** Value Properties of the Global Object
  Infinity: "number",
  NaN: "number",
  undefined: "undefined",
  // *** Function Properties of the Global Object
  // eval
  "%UniqueEval%": s,
  isFinite: s,
  isNaN: s,
  parseFloat: s,
  parseInt: s,
  decodeURI: s,
  decodeURIComponent: s,
  encodeURI: s,
  encodeURIComponent: s,
  // *** Fundamental Objects
  Object: {
    // Properties of the Object Constructor
    "[[Proto]]": "%FunctionPrototype%",
    assign: s,
    create: s,
    defineProperties: s,
    defineProperty: s,
    entries: s,
    freeze: s,
    fromEntries: s,
    getOwnPropertyDescriptor: s,
    getOwnPropertyDescriptors: s,
    getOwnPropertyNames: s,
    getOwnPropertySymbols: s,
    getPrototypeOf: s,
    hasOwn: s,
    is: s,
    isExtensible: s,
    isFrozen: s,
    isSealed: s,
    keys: s,
    preventExtensions: s,
    prototype: "%ObjectPrototype%",
    seal: s,
    setPrototypeOf: s,
    values: s,
    // https://github.com/tc39/proposal-array-grouping
    groupBy: s,
    // Seen on QuickJS
    __getClass: !1
  },
  "%ObjectPrototype%": {
    // Properties of the Object Prototype Object
    "[[Proto]]": null,
    constructor: "Object",
    hasOwnProperty: s,
    isPrototypeOf: s,
    propertyIsEnumerable: s,
    toLocaleString: s,
    toString: s,
    valueOf: s,
    // Annex B: Additional Properties of the Object.prototype Object
    // See note in header about the difference between [[Proto]] and --proto--
    // special notations.
    "--proto--": Ie,
    __defineGetter__: s,
    __defineSetter__: s,
    __lookupGetter__: s,
    __lookupSetter__: s
  },
  "%UniqueFunction%": {
    // Properties of the Function Constructor
    "[[Proto]]": "%FunctionPrototype%",
    prototype: "%FunctionPrototype%"
  },
  "%InertFunction%": {
    "[[Proto]]": "%FunctionPrototype%",
    prototype: "%FunctionPrototype%"
  },
  "%FunctionPrototype%": {
    apply: s,
    bind: s,
    call: s,
    constructor: "%InertFunction%",
    toString: s,
    "@@hasInstance": s,
    // proposed but not yet std. To be removed if there
    caller: !1,
    // proposed but not yet std. To be removed if there
    arguments: !1,
    // Seen on QuickJS. TODO grab getter for use by console
    fileName: !1,
    // Seen on QuickJS. TODO grab getter for use by console
    lineNumber: !1
  },
  Boolean: {
    // Properties of the Boolean Constructor
    "[[Proto]]": "%FunctionPrototype%",
    prototype: "%BooleanPrototype%"
  },
  "%BooleanPrototype%": {
    constructor: "Boolean",
    toString: s,
    valueOf: s
  },
  "%SharedSymbol%": {
    // Properties of the Symbol Constructor
    "[[Proto]]": "%FunctionPrototype%",
    asyncDispose: "symbol",
    asyncIterator: "symbol",
    dispose: "symbol",
    for: s,
    hasInstance: "symbol",
    isConcatSpreadable: "symbol",
    iterator: "symbol",
    keyFor: s,
    match: "symbol",
    matchAll: "symbol",
    prototype: "%SymbolPrototype%",
    replace: "symbol",
    search: "symbol",
    species: "symbol",
    split: "symbol",
    toPrimitive: "symbol",
    toStringTag: "symbol",
    unscopables: "symbol",
    // Seen at core-js https://github.com/zloirock/core-js#ecmascript-symbol
    useSimple: !1,
    // Seen at core-js https://github.com/zloirock/core-js#ecmascript-symbol
    useSetter: !1,
    // Seen on QuickJS
    operatorSet: !1
  },
  "%SymbolPrototype%": {
    // Properties of the Symbol Prototype Object
    constructor: "%SharedSymbol%",
    description: R,
    toString: s,
    valueOf: s,
    "@@toPrimitive": s,
    "@@toStringTag": "string"
  },
  "%InitialError%": {
    // Properties of the Error Constructor
    "[[Proto]]": "%FunctionPrototype%",
    prototype: "%ErrorPrototype%",
    // Non standard, v8 only, used by tap
    captureStackTrace: s,
    // Non standard, v8 only, used by tap, tamed to accessor
    stackTraceLimit: Ie,
    // Non standard, v8 only, used by several, tamed to accessor
    prepareStackTrace: Ie
  },
  "%SharedError%": {
    // Properties of the Error Constructor
    "[[Proto]]": "%FunctionPrototype%",
    prototype: "%ErrorPrototype%",
    // Non standard, v8 only, used by tap
    captureStackTrace: s,
    // Non standard, v8 only, used by tap, tamed to accessor
    stackTraceLimit: Ie,
    // Non standard, v8 only, used by several, tamed to accessor
    prepareStackTrace: Ie
  },
  "%ErrorPrototype%": {
    constructor: "%SharedError%",
    message: "string",
    name: "string",
    toString: s,
    // proposed de-facto, assumed TODO
    // Seen on FF Nightly 88.0a1
    at: !1,
    // Seen on FF and XS
    stack: Ie,
    // Superfluously present in some versions of V8.
    // https://github.com/tc39/notes/blob/master/meetings/2021-10/oct-26.md#:~:text=However%2C%20Chrome%2093,and%20node%2016.11.
    cause: !1
  },
  // NativeError
  EvalError: ot("%EvalErrorPrototype%"),
  RangeError: ot("%RangeErrorPrototype%"),
  ReferenceError: ot("%ReferenceErrorPrototype%"),
  SyntaxError: ot("%SyntaxErrorPrototype%"),
  TypeError: ot("%TypeErrorPrototype%"),
  URIError: ot("%URIErrorPrototype%"),
  // https://github.com/endojs/endo/issues/550
  AggregateError: ot("%AggregateErrorPrototype%"),
  "%EvalErrorPrototype%": st("EvalError"),
  "%RangeErrorPrototype%": st("RangeError"),
  "%ReferenceErrorPrototype%": st("ReferenceError"),
  "%SyntaxErrorPrototype%": st("SyntaxError"),
  "%TypeErrorPrototype%": st("TypeError"),
  "%URIErrorPrototype%": st("URIError"),
  // https://github.com/endojs/endo/issues/550
  "%AggregateErrorPrototype%": st("AggregateError"),
  // *** Numbers and Dates
  Number: {
    // Properties of the Number Constructor
    "[[Proto]]": "%FunctionPrototype%",
    EPSILON: "number",
    isFinite: s,
    isInteger: s,
    isNaN: s,
    isSafeInteger: s,
    MAX_SAFE_INTEGER: "number",
    MAX_VALUE: "number",
    MIN_SAFE_INTEGER: "number",
    MIN_VALUE: "number",
    NaN: "number",
    NEGATIVE_INFINITY: "number",
    parseFloat: s,
    parseInt: s,
    POSITIVE_INFINITY: "number",
    prototype: "%NumberPrototype%"
  },
  "%NumberPrototype%": {
    // Properties of the Number Prototype Object
    constructor: "Number",
    toExponential: s,
    toFixed: s,
    toLocaleString: s,
    toPrecision: s,
    toString: s,
    valueOf: s
  },
  BigInt: {
    // Properties of the BigInt Constructor
    "[[Proto]]": "%FunctionPrototype%",
    asIntN: s,
    asUintN: s,
    prototype: "%BigIntPrototype%",
    // See https://github.com/Moddable-OpenSource/moddable/issues/523
    bitLength: !1,
    // See https://github.com/Moddable-OpenSource/moddable/issues/523
    fromArrayBuffer: !1,
    // Seen on QuickJS
    tdiv: !1,
    // Seen on QuickJS
    fdiv: !1,
    // Seen on QuickJS
    cdiv: !1,
    // Seen on QuickJS
    ediv: !1,
    // Seen on QuickJS
    tdivrem: !1,
    // Seen on QuickJS
    fdivrem: !1,
    // Seen on QuickJS
    cdivrem: !1,
    // Seen on QuickJS
    edivrem: !1,
    // Seen on QuickJS
    sqrt: !1,
    // Seen on QuickJS
    sqrtrem: !1,
    // Seen on QuickJS
    floorLog2: !1,
    // Seen on QuickJS
    ctz: !1
  },
  "%BigIntPrototype%": {
    constructor: "BigInt",
    toLocaleString: s,
    toString: s,
    valueOf: s,
    "@@toStringTag": "string"
  },
  "%InitialMath%": {
    ...eo,
    // `%InitialMath%.random()` has the standard unsafe behavior
    random: s
  },
  "%SharedMath%": {
    ...eo,
    // `%SharedMath%.random()` is tamed to always throw
    random: s
  },
  "%InitialDate%": {
    // Properties of the Date Constructor
    "[[Proto]]": "%FunctionPrototype%",
    now: s,
    parse: s,
    prototype: "%DatePrototype%",
    UTC: s
  },
  "%SharedDate%": {
    // Properties of the Date Constructor
    "[[Proto]]": "%FunctionPrototype%",
    // `%SharedDate%.now()` is tamed to always throw
    now: s,
    parse: s,
    prototype: "%DatePrototype%",
    UTC: s
  },
  "%DatePrototype%": {
    constructor: "%SharedDate%",
    getDate: s,
    getDay: s,
    getFullYear: s,
    getHours: s,
    getMilliseconds: s,
    getMinutes: s,
    getMonth: s,
    getSeconds: s,
    getTime: s,
    getTimezoneOffset: s,
    getUTCDate: s,
    getUTCDay: s,
    getUTCFullYear: s,
    getUTCHours: s,
    getUTCMilliseconds: s,
    getUTCMinutes: s,
    getUTCMonth: s,
    getUTCSeconds: s,
    setDate: s,
    setFullYear: s,
    setHours: s,
    setMilliseconds: s,
    setMinutes: s,
    setMonth: s,
    setSeconds: s,
    setTime: s,
    setUTCDate: s,
    setUTCFullYear: s,
    setUTCHours: s,
    setUTCMilliseconds: s,
    setUTCMinutes: s,
    setUTCMonth: s,
    setUTCSeconds: s,
    toDateString: s,
    toISOString: s,
    toJSON: s,
    toLocaleDateString: s,
    toLocaleString: s,
    toLocaleTimeString: s,
    toString: s,
    toTimeString: s,
    toUTCString: s,
    valueOf: s,
    "@@toPrimitive": s,
    // Annex B: Additional Properties of the Date.prototype Object
    getYear: s,
    setYear: s,
    toGMTString: s
  },
  // Text Processing
  String: {
    // Properties of the String Constructor
    "[[Proto]]": "%FunctionPrototype%",
    fromCharCode: s,
    fromCodePoint: s,
    prototype: "%StringPrototype%",
    raw: s,
    // See https://github.com/Moddable-OpenSource/moddable/issues/523
    fromArrayBuffer: !1
  },
  "%StringPrototype%": {
    // Properties of the String Prototype Object
    length: "number",
    at: s,
    charAt: s,
    charCodeAt: s,
    codePointAt: s,
    concat: s,
    constructor: "String",
    endsWith: s,
    includes: s,
    indexOf: s,
    lastIndexOf: s,
    localeCompare: s,
    match: s,
    matchAll: s,
    normalize: s,
    padEnd: s,
    padStart: s,
    repeat: s,
    replace: s,
    replaceAll: s,
    // ES2021
    search: s,
    slice: s,
    split: s,
    startsWith: s,
    substring: s,
    toLocaleLowerCase: s,
    toLocaleUpperCase: s,
    toLowerCase: s,
    toString: s,
    toUpperCase: s,
    trim: s,
    trimEnd: s,
    trimStart: s,
    valueOf: s,
    "@@iterator": s,
    // Annex B: Additional Properties of the String.prototype Object
    substr: s,
    anchor: s,
    big: s,
    blink: s,
    bold: s,
    fixed: s,
    fontcolor: s,
    fontsize: s,
    italics: s,
    link: s,
    small: s,
    strike: s,
    sub: s,
    sup: s,
    trimLeft: s,
    trimRight: s,
    // See https://github.com/Moddable-OpenSource/moddable/issues/523
    compare: !1,
    // https://github.com/tc39/proposal-is-usv-string
    isWellFormed: s,
    toWellFormed: s,
    unicodeSets: s,
    // Seen on QuickJS
    __quote: !1
  },
  "%StringIteratorPrototype%": {
    "[[Proto]]": "%IteratorPrototype%",
    next: s,
    "@@toStringTag": "string"
  },
  "%InitialRegExp%": {
    // Properties of the RegExp Constructor
    "[[Proto]]": "%FunctionPrototype%",
    prototype: "%RegExpPrototype%",
    "@@species": R,
    // The https://github.com/tc39/proposal-regexp-legacy-features
    // are all optional, unsafe, and omitted
    input: !1,
    $_: !1,
    lastMatch: !1,
    "$&": !1,
    lastParen: !1,
    "$+": !1,
    leftContext: !1,
    "$`": !1,
    rightContext: !1,
    "$'": !1,
    $1: !1,
    $2: !1,
    $3: !1,
    $4: !1,
    $5: !1,
    $6: !1,
    $7: !1,
    $8: !1,
    $9: !1
  },
  "%SharedRegExp%": {
    // Properties of the RegExp Constructor
    "[[Proto]]": "%FunctionPrototype%",
    prototype: "%RegExpPrototype%",
    "@@species": R
  },
  "%RegExpPrototype%": {
    // Properties of the RegExp Prototype Object
    constructor: "%SharedRegExp%",
    exec: s,
    dotAll: R,
    flags: R,
    global: R,
    hasIndices: R,
    ignoreCase: R,
    "@@match": s,
    "@@matchAll": s,
    multiline: R,
    "@@replace": s,
    "@@search": s,
    source: R,
    "@@split": s,
    sticky: R,
    test: s,
    toString: s,
    unicode: R,
    unicodeSets: R,
    // Annex B: Additional Properties of the RegExp.prototype Object
    compile: !1
    // UNSAFE and suppressed.
  },
  "%RegExpStringIteratorPrototype%": {
    // The %RegExpStringIteratorPrototype% Object
    "[[Proto]]": "%IteratorPrototype%",
    next: s,
    "@@toStringTag": "string"
  },
  // Indexed Collections
  Array: {
    // Properties of the Array Constructor
    "[[Proto]]": "%FunctionPrototype%",
    from: s,
    isArray: s,
    of: s,
    prototype: "%ArrayPrototype%",
    "@@species": R,
    // Stage 3:
    // https://tc39.es/proposal-relative-indexing-method/
    at: s,
    // https://tc39.es/proposal-array-from-async/
    fromAsync: s
  },
  "%ArrayPrototype%": {
    // Properties of the Array Prototype Object
    at: s,
    length: "number",
    concat: s,
    constructor: "Array",
    copyWithin: s,
    entries: s,
    every: s,
    fill: s,
    filter: s,
    find: s,
    findIndex: s,
    flat: s,
    flatMap: s,
    forEach: s,
    includes: s,
    indexOf: s,
    join: s,
    keys: s,
    lastIndexOf: s,
    map: s,
    pop: s,
    push: s,
    reduce: s,
    reduceRight: s,
    reverse: s,
    shift: s,
    slice: s,
    some: s,
    sort: s,
    splice: s,
    toLocaleString: s,
    toString: s,
    unshift: s,
    values: s,
    "@@iterator": s,
    "@@unscopables": {
      "[[Proto]]": null,
      copyWithin: "boolean",
      entries: "boolean",
      fill: "boolean",
      find: "boolean",
      findIndex: "boolean",
      flat: "boolean",
      flatMap: "boolean",
      includes: "boolean",
      keys: "boolean",
      values: "boolean",
      // Failed tc39 proposal
      // Seen on FF Nightly 88.0a1
      at: "boolean",
      // See https://github.com/tc39/proposal-array-find-from-last
      findLast: "boolean",
      findLastIndex: "boolean",
      // https://github.com/tc39/proposal-change-array-by-copy
      toReversed: "boolean",
      toSorted: "boolean",
      toSpliced: "boolean",
      with: "boolean",
      // https://github.com/tc39/proposal-array-grouping
      group: "boolean",
      groupToMap: "boolean",
      groupBy: "boolean"
    },
    // See https://github.com/tc39/proposal-array-find-from-last
    findLast: s,
    findLastIndex: s,
    // https://github.com/tc39/proposal-change-array-by-copy
    toReversed: s,
    toSorted: s,
    toSpliced: s,
    with: s,
    // https://github.com/tc39/proposal-array-grouping
    group: s,
    // Not in proposal? Where?
    groupToMap: s,
    // Not in proposal? Where?
    groupBy: s
  },
  "%ArrayIteratorPrototype%": {
    // The %ArrayIteratorPrototype% Object
    "[[Proto]]": "%IteratorPrototype%",
    next: s,
    "@@toStringTag": "string"
  },
  // *** TypedArray Objects
  "%TypedArray%": {
    // Properties of the %TypedArray% Intrinsic Object
    "[[Proto]]": "%FunctionPrototype%",
    from: s,
    of: s,
    prototype: "%TypedArrayPrototype%",
    "@@species": R
  },
  "%TypedArrayPrototype%": {
    at: s,
    buffer: R,
    byteLength: R,
    byteOffset: R,
    constructor: "%TypedArray%",
    copyWithin: s,
    entries: s,
    every: s,
    fill: s,
    filter: s,
    find: s,
    findIndex: s,
    forEach: s,
    includes: s,
    indexOf: s,
    join: s,
    keys: s,
    lastIndexOf: s,
    length: R,
    map: s,
    reduce: s,
    reduceRight: s,
    reverse: s,
    set: s,
    slice: s,
    some: s,
    sort: s,
    subarray: s,
    toLocaleString: s,
    toString: s,
    values: s,
    "@@iterator": s,
    "@@toStringTag": R,
    // See https://github.com/tc39/proposal-array-find-from-last
    findLast: s,
    findLastIndex: s,
    // https://github.com/tc39/proposal-change-array-by-copy
    toReversed: s,
    toSorted: s,
    with: s
  },
  // The TypedArray Constructors
  BigInt64Array: ge("%BigInt64ArrayPrototype%"),
  BigUint64Array: ge("%BigUint64ArrayPrototype%"),
  // https://github.com/tc39/proposal-float16array
  Float16Array: ge("%Float16ArrayPrototype%"),
  Float32Array: ge("%Float32ArrayPrototype%"),
  Float64Array: ge("%Float64ArrayPrototype%"),
  Int16Array: ge("%Int16ArrayPrototype%"),
  Int32Array: ge("%Int32ArrayPrototype%"),
  Int8Array: ge("%Int8ArrayPrototype%"),
  Uint16Array: ge("%Uint16ArrayPrototype%"),
  Uint32Array: ge("%Uint32ArrayPrototype%"),
  Uint8Array: ge("%Uint8ArrayPrototype%"),
  Uint8ClampedArray: ge("%Uint8ClampedArrayPrototype%"),
  "%BigInt64ArrayPrototype%": ye("BigInt64Array"),
  "%BigUint64ArrayPrototype%": ye("BigUint64Array"),
  // https://github.com/tc39/proposal-float16array
  "%Float16ArrayPrototype%": ye("Float16Array"),
  "%Float32ArrayPrototype%": ye("Float32Array"),
  "%Float64ArrayPrototype%": ye("Float64Array"),
  "%Int16ArrayPrototype%": ye("Int16Array"),
  "%Int32ArrayPrototype%": ye("Int32Array"),
  "%Int8ArrayPrototype%": ye("Int8Array"),
  "%Uint16ArrayPrototype%": ye("Uint16Array"),
  "%Uint32ArrayPrototype%": ye("Uint32Array"),
  "%Uint8ArrayPrototype%": ye("Uint8Array"),
  "%Uint8ClampedArrayPrototype%": ye("Uint8ClampedArray"),
  // *** Keyed Collections
  Map: {
    // Properties of the Map Constructor
    "[[Proto]]": "%FunctionPrototype%",
    "@@species": R,
    prototype: "%MapPrototype%",
    // https://github.com/tc39/proposal-array-grouping
    groupBy: s
  },
  "%MapPrototype%": {
    clear: s,
    constructor: "Map",
    delete: s,
    entries: s,
    forEach: s,
    get: s,
    has: s,
    keys: s,
    set: s,
    size: R,
    values: s,
    "@@iterator": s,
    "@@toStringTag": "string"
  },
  "%MapIteratorPrototype%": {
    // The %MapIteratorPrototype% Object
    "[[Proto]]": "%IteratorPrototype%",
    next: s,
    "@@toStringTag": "string"
  },
  Set: {
    // Properties of the Set Constructor
    "[[Proto]]": "%FunctionPrototype%",
    prototype: "%SetPrototype%",
    "@@species": R,
    // Seen on QuickJS
    groupBy: !1
  },
  "%SetPrototype%": {
    add: s,
    clear: s,
    constructor: "Set",
    delete: s,
    entries: s,
    forEach: s,
    has: s,
    keys: s,
    size: R,
    values: s,
    "@@iterator": s,
    "@@toStringTag": "string",
    // See https://github.com/tc39/proposal-set-methods
    intersection: s,
    // See https://github.com/tc39/proposal-set-methods
    union: s,
    // See https://github.com/tc39/proposal-set-methods
    difference: s,
    // See https://github.com/tc39/proposal-set-methods
    symmetricDifference: s,
    // See https://github.com/tc39/proposal-set-methods
    isSubsetOf: s,
    // See https://github.com/tc39/proposal-set-methods
    isSupersetOf: s,
    // See https://github.com/tc39/proposal-set-methods
    isDisjointFrom: s
  },
  "%SetIteratorPrototype%": {
    // The %SetIteratorPrototype% Object
    "[[Proto]]": "%IteratorPrototype%",
    next: s,
    "@@toStringTag": "string"
  },
  WeakMap: {
    // Properties of the WeakMap Constructor
    "[[Proto]]": "%FunctionPrototype%",
    prototype: "%WeakMapPrototype%"
  },
  "%WeakMapPrototype%": {
    constructor: "WeakMap",
    delete: s,
    get: s,
    has: s,
    set: s,
    "@@toStringTag": "string"
  },
  WeakSet: {
    // Properties of the WeakSet Constructor
    "[[Proto]]": "%FunctionPrototype%",
    prototype: "%WeakSetPrototype%"
  },
  "%WeakSetPrototype%": {
    add: s,
    constructor: "WeakSet",
    delete: s,
    has: s,
    "@@toStringTag": "string"
  },
  // *** Structured Data
  ArrayBuffer: {
    // Properties of the ArrayBuffer Constructor
    "[[Proto]]": "%FunctionPrototype%",
    isView: s,
    prototype: "%ArrayBufferPrototype%",
    "@@species": R,
    // See https://github.com/Moddable-OpenSource/moddable/issues/523
    fromString: !1,
    // See https://github.com/Moddable-OpenSource/moddable/issues/523
    fromBigInt: !1
  },
  "%ArrayBufferPrototype%": {
    byteLength: R,
    constructor: "ArrayBuffer",
    slice: s,
    "@@toStringTag": "string",
    // See https://github.com/Moddable-OpenSource/moddable/issues/523
    concat: !1,
    // See https://github.com/tc39/proposal-resizablearraybuffer
    transfer: s,
    resize: s,
    resizable: R,
    maxByteLength: R,
    // https://github.com/tc39/proposal-arraybuffer-transfer
    transferToFixedLength: s,
    detached: R
  },
  // SharedArrayBuffer Objects
  SharedArrayBuffer: !1,
  // UNSAFE and purposely suppressed.
  "%SharedArrayBufferPrototype%": !1,
  // UNSAFE and purposely suppressed.
  DataView: {
    // Properties of the DataView Constructor
    "[[Proto]]": "%FunctionPrototype%",
    BYTES_PER_ELEMENT: "number",
    // Non std but undeletable on Safari.
    prototype: "%DataViewPrototype%"
  },
  "%DataViewPrototype%": {
    buffer: R,
    byteLength: R,
    byteOffset: R,
    constructor: "DataView",
    getBigInt64: s,
    getBigUint64: s,
    // https://github.com/tc39/proposal-float16array
    getFloat16: s,
    getFloat32: s,
    getFloat64: s,
    getInt8: s,
    getInt16: s,
    getInt32: s,
    getUint8: s,
    getUint16: s,
    getUint32: s,
    setBigInt64: s,
    setBigUint64: s,
    // https://github.com/tc39/proposal-float16array
    setFloat16: s,
    setFloat32: s,
    setFloat64: s,
    setInt8: s,
    setInt16: s,
    setInt32: s,
    setUint8: s,
    setUint16: s,
    setUint32: s,
    "@@toStringTag": "string"
  },
  // Atomics
  Atomics: !1,
  // UNSAFE and suppressed.
  JSON: {
    parse: s,
    stringify: s,
    "@@toStringTag": "string",
    // https://github.com/tc39/proposal-json-parse-with-source/
    rawJSON: s,
    isRawJSON: s
  },
  // *** Control Abstraction Objects
  // https://github.com/tc39/proposal-iterator-helpers
  Iterator: {
    // Properties of the Iterator Constructor
    "[[Proto]]": "%FunctionPrototype%",
    prototype: "%IteratorPrototype%",
    from: s
  },
  "%IteratorPrototype%": {
    // The %IteratorPrototype% Object
    "@@iterator": s,
    // https://github.com/tc39/proposal-iterator-helpers
    constructor: "Iterator",
    map: s,
    filter: s,
    take: s,
    drop: s,
    flatMap: s,
    reduce: s,
    toArray: s,
    forEach: s,
    some: s,
    every: s,
    find: s,
    "@@toStringTag": "string",
    // https://github.com/tc39/proposal-async-iterator-helpers
    toAsync: s,
    // See https://github.com/Moddable-OpenSource/moddable/issues/523#issuecomment-1942904505
    "@@dispose": !1
  },
  // https://github.com/tc39/proposal-iterator-helpers
  "%WrapForValidIteratorPrototype%": {
    "[[Proto]]": "%IteratorPrototype%",
    next: s,
    return: s
  },
  // https://github.com/tc39/proposal-iterator-helpers
  "%IteratorHelperPrototype%": {
    "[[Proto]]": "%IteratorPrototype%",
    next: s,
    return: s,
    "@@toStringTag": "string"
  },
  // https://github.com/tc39/proposal-async-iterator-helpers
  AsyncIterator: {
    // Properties of the Iterator Constructor
    "[[Proto]]": "%FunctionPrototype%",
    prototype: "%AsyncIteratorPrototype%",
    from: s
  },
  "%AsyncIteratorPrototype%": {
    // The %AsyncIteratorPrototype% Object
    "@@asyncIterator": s,
    // https://github.com/tc39/proposal-async-iterator-helpers
    constructor: "AsyncIterator",
    map: s,
    filter: s,
    take: s,
    drop: s,
    flatMap: s,
    reduce: s,
    toArray: s,
    forEach: s,
    some: s,
    every: s,
    find: s,
    "@@toStringTag": "string",
    // See https://github.com/Moddable-OpenSource/moddable/issues/523#issuecomment-1942904505
    "@@asyncDispose": !1
  },
  // https://github.com/tc39/proposal-async-iterator-helpers
  "%WrapForValidAsyncIteratorPrototype%": {
    "[[Proto]]": "%AsyncIteratorPrototype%",
    next: s,
    return: s
  },
  // https://github.com/tc39/proposal-async-iterator-helpers
  "%AsyncIteratorHelperPrototype%": {
    "[[Proto]]": "%AsyncIteratorPrototype%",
    next: s,
    return: s,
    "@@toStringTag": "string"
  },
  "%InertGeneratorFunction%": {
    // Properties of the GeneratorFunction Constructor
    "[[Proto]]": "%InertFunction%",
    prototype: "%Generator%"
  },
  "%Generator%": {
    // Properties of the GeneratorFunction Prototype Object
    "[[Proto]]": "%FunctionPrototype%",
    constructor: "%InertGeneratorFunction%",
    prototype: "%GeneratorPrototype%",
    "@@toStringTag": "string"
  },
  "%InertAsyncGeneratorFunction%": {
    // Properties of the AsyncGeneratorFunction Constructor
    "[[Proto]]": "%InertFunction%",
    prototype: "%AsyncGenerator%"
  },
  "%AsyncGenerator%": {
    // Properties of the AsyncGeneratorFunction Prototype Object
    "[[Proto]]": "%FunctionPrototype%",
    constructor: "%InertAsyncGeneratorFunction%",
    prototype: "%AsyncGeneratorPrototype%",
    // length prop added here for React Native jsc-android
    // https://github.com/endojs/endo/issues/660
    // https://github.com/react-native-community/jsc-android-buildscripts/issues/181
    length: "number",
    "@@toStringTag": "string"
  },
  "%GeneratorPrototype%": {
    // Properties of the Generator Prototype Object
    "[[Proto]]": "%IteratorPrototype%",
    constructor: "%Generator%",
    next: s,
    return: s,
    throw: s,
    "@@toStringTag": "string"
  },
  "%AsyncGeneratorPrototype%": {
    // Properties of the AsyncGenerator Prototype Object
    "[[Proto]]": "%AsyncIteratorPrototype%",
    constructor: "%AsyncGenerator%",
    next: s,
    return: s,
    throw: s,
    "@@toStringTag": "string"
  },
  // TODO: To be replaced with Promise.delegate
  //
  // The HandledPromise global variable shimmed by `@agoric/eventual-send/shim`
  // implements an initial version of the eventual send specification at:
  // https://github.com/tc39/proposal-eventual-send
  //
  // We will likely change this to add a property to Promise called
  // Promise.delegate and put static methods on it, which will necessitate
  // another whitelist change to update to the current proposed standard.
  HandledPromise: {
    "[[Proto]]": "Promise",
    applyFunction: s,
    applyFunctionSendOnly: s,
    applyMethod: s,
    applyMethodSendOnly: s,
    get: s,
    getSendOnly: s,
    prototype: "%PromisePrototype%",
    resolve: s
  },
  Promise: {
    // Properties of the Promise Constructor
    "[[Proto]]": "%FunctionPrototype%",
    all: s,
    allSettled: s,
    // https://github.com/Agoric/SES-shim/issues/550
    any: s,
    prototype: "%PromisePrototype%",
    race: s,
    reject: s,
    resolve: s,
    // https://github.com/tc39/proposal-promise-with-resolvers
    withResolvers: s,
    "@@species": R
  },
  "%PromisePrototype%": {
    // Properties of the Promise Prototype Object
    catch: s,
    constructor: "Promise",
    finally: s,
    then: s,
    "@@toStringTag": "string",
    // Non-standard, used in node to prevent async_hooks from breaking
    "UniqueSymbol(async_id_symbol)": Ie,
    "UniqueSymbol(trigger_async_id_symbol)": Ie,
    "UniqueSymbol(destroyed)": Ie
  },
  "%InertAsyncFunction%": {
    // Properties of the AsyncFunction Constructor
    "[[Proto]]": "%InertFunction%",
    prototype: "%AsyncFunctionPrototype%"
  },
  "%AsyncFunctionPrototype%": {
    // Properties of the AsyncFunction Prototype Object
    "[[Proto]]": "%FunctionPrototype%",
    constructor: "%InertAsyncFunction%",
    // length prop added here for React Native jsc-android
    // https://github.com/endojs/endo/issues/660
    // https://github.com/react-native-community/jsc-android-buildscripts/issues/181
    length: "number",
    "@@toStringTag": "string"
  },
  // Reflection
  Reflect: {
    // The Reflect Object
    // Not a function object.
    apply: s,
    construct: s,
    defineProperty: s,
    deleteProperty: s,
    get: s,
    getOwnPropertyDescriptor: s,
    getPrototypeOf: s,
    has: s,
    isExtensible: s,
    ownKeys: s,
    preventExtensions: s,
    set: s,
    setPrototypeOf: s,
    "@@toStringTag": "string"
  },
  Proxy: {
    // Properties of the Proxy Constructor
    "[[Proto]]": "%FunctionPrototype%",
    revocable: s
  },
  // Appendix B
  // Annex B: Additional Properties of the Global Object
  escape: s,
  unescape: s,
  // Proposed
  "%UniqueCompartment%": {
    "[[Proto]]": "%FunctionPrototype%",
    prototype: "%CompartmentPrototype%",
    toString: s
  },
  "%InertCompartment%": {
    "[[Proto]]": "%FunctionPrototype%",
    prototype: "%CompartmentPrototype%",
    toString: s
  },
  "%CompartmentPrototype%": {
    constructor: "%InertCompartment%",
    evaluate: s,
    globalThis: R,
    name: R,
    import: Xn,
    load: Xn,
    importNow: s,
    module: s,
    "@@toStringTag": "string"
  },
  lockdown: s,
  harden: { ...s, isFake: "boolean" },
  "%InitialGetStackString%": s
}, Ha = (t) => typeof t == "function";
function Va(t, e, r) {
  if (oe(t, e)) {
    const n = J(t, e);
    if (!n || !Nr(n.value, r.value) || n.get !== r.get || n.set !== r.set || n.writable !== r.writable || n.enumerable !== r.enumerable || n.configurable !== r.configurable)
      throw v(`Conflicting definitions of ${e}`);
  }
  M(t, e, r);
}
function Wa(t, e) {
  for (const [r, n] of re(e))
    Va(t, r, n);
}
function os(t, e) {
  const r = { __proto__: null };
  for (const [n, o] of re(e))
    oe(t, n) && (r[o] = t[n]);
  return r;
}
const ss = () => {
  const t = Z(null);
  let e;
  const r = (c) => {
    Wa(t, Ze(c));
  };
  y(r);
  const n = () => {
    for (const [c, l] of re(t)) {
      if (!Ye(l) || !oe(l, "prototype"))
        continue;
      const u = wr[c];
      if (typeof u != "object")
        throw v(`Expected permit object at whitelist.${c}`);
      const d = u.prototype;
      if (!d)
        throw v(`${c}.prototype property not whitelisted`);
      if (typeof d != "string" || !oe(wr, d))
        throw v(`Unrecognized ${c}.prototype whitelist entry`);
      const f = l.prototype;
      if (oe(t, d)) {
        if (t[d] !== f)
          throw v(`Conflicting bindings of ${d}`);
        continue;
      }
      t[d] = f;
    }
  };
  y(n);
  const o = () => (y(t), e = new $t(Ke(ko(t), Ha)), t);
  y(o);
  const a = (c) => {
    if (!e)
      throw v(
        "isPseudoNative can only be called after finalIntrinsics"
      );
    return or(e, c);
  };
  y(a);
  const i = {
    addIntrinsics: r,
    completePrototypes: n,
    finalIntrinsics: o,
    isPseudoNative: a
  };
  return y(i), r(es), r(os(k, ts)), i;
}, qa = (t) => {
  const { addIntrinsics: e, finalIntrinsics: r } = ss();
  return e(os(t, rs)), r();
};
function Ka(t, e) {
  let r = !1;
  const n = (h, ...p) => (r || (console.groupCollapsed("Removing unpermitted intrinsics"), r = !0), console[h](...p)), o = ["undefined", "boolean", "number", "string", "symbol"], a = new Pe(
    St ? se(
      Ke(
        re(wr["%SharedSymbol%"]),
        ([h, p]) => p === "symbol" && typeof St[h] == "symbol"
      ),
      ([h]) => [St[h], `@@${h}`]
    ) : []
  );
  function i(h, p) {
    if (typeof p == "string")
      return p;
    const m = Ue(a, p);
    if (typeof p == "symbol") {
      if (m)
        return m;
      {
        const _ = ea(p);
        return _ !== void 0 ? `RegisteredSymbol(${_})` : `Unique${pe(p)}`;
      }
    }
    throw v(`Unexpected property name type ${h} ${p}`);
  }
  function c(h, p, m) {
    if (!Ye(p))
      throw v(`Object expected: ${h}, ${p}, ${m}`);
    const _ = j(p);
    if (!(_ === null && m === null)) {
      if (m !== void 0 && typeof m != "string")
        throw v(`Malformed whitelist permit ${h}.__proto__`);
      if (_ !== t[m || "%ObjectPrototype%"])
        throw v(`Unexpected intrinsic ${h}.__proto__ at ${m}`);
    }
  }
  function l(h, p, m, _) {
    if (typeof _ == "object")
      return f(h, p, _), !0;
    if (_ === !1)
      return !1;
    if (typeof _ == "string") {
      if (m === "prototype" || m === "constructor") {
        if (oe(t, _)) {
          if (p !== t[_])
            throw v(`Does not match whitelist ${h}`);
          return !0;
        }
      } else if (Mr(o, _)) {
        if (typeof p !== _)
          throw v(
            `At ${h} expected ${_} not ${typeof p}`
          );
        return !0;
      }
    }
    throw v(`Unexpected whitelist permit ${_} at ${h}`);
  }
  function u(h, p, m, _) {
    const S = J(p, m);
    if (!S)
      throw v(`Property ${m} not found at ${h}`);
    if (oe(S, "value")) {
      if (Qn(_))
        throw v(`Accessor expected at ${h}`);
      return l(h, S.value, m, _);
    }
    if (!Qn(_))
      throw v(`Accessor not expected at ${h}`);
    return l(`${h}<get>`, S.get, m, _.get) && l(`${h}<set>`, S.set, m, _.set);
  }
  function d(h, p, m) {
    const _ = m === "__proto__" ? "--proto--" : m;
    if (oe(p, _))
      return p[_];
    if (typeof h == "function" && oe(an, _))
      return an[_];
  }
  function f(h, p, m) {
    if (p == null)
      return;
    const _ = m["[[Proto]]"];
    c(h, p, _), typeof p == "function" && e(p);
    for (const S of De(p)) {
      const T = i(h, S), N = `${h}.${T}`, x = d(p, m, T);
      if (!x || !u(N, p, S, x)) {
        x !== !1 && n("warn", `Removing ${N}`);
        try {
          delete p[S];
        } catch (D) {
          if (S in p) {
            if (typeof p == "function" && S === "prototype" && (p.prototype = void 0, p.prototype === void 0)) {
              n(
                "warn",
                `Tolerating undeletable ${N} === undefined`
              );
              continue;
            }
            n("error", `failed to delete ${N}`, D);
          } else
            n("error", `deleting ${N} threw`, D);
          throw D;
        }
      }
    }
  }
  try {
    f("intrinsics", t, wr);
  } finally {
    r && console.groupEnd();
  }
}
function Ya() {
  try {
    ve.prototype.constructor("return 1");
  } catch {
    return y({});
  }
  const t = {};
  function e(r, n, o) {
    let a;
    try {
      a = (0, eval)(o);
    } catch (l) {
      if (l instanceof tr)
        return;
      throw l;
    }
    const i = j(a), c = function() {
      throw v(
        "Function.prototype.constructor is not a valid constructor."
      );
    };
    F(c, {
      prototype: { value: i },
      name: {
        value: r,
        writable: !1,
        enumerable: !1,
        configurable: !0
      }
    }), F(i, {
      constructor: { value: c }
    }), c !== ve.prototype.constructor && xo(c, ve.prototype.constructor), t[n] = c;
  }
  return e("Function", "%InertFunction%", "(function(){})"), e(
    "GeneratorFunction",
    "%InertGeneratorFunction%",
    "(function*(){})"
  ), e(
    "AsyncFunction",
    "%InertAsyncFunction%",
    "(async function(){})"
  ), e(
    "AsyncGeneratorFunction",
    "%InertAsyncGeneratorFunction%",
    "(async function*(){})"
  ), t;
}
function Ja(t = "safe") {
  if (t !== "safe" && t !== "unsafe")
    throw v(`unrecognized dateTaming ${t}`);
  const e = Hs, r = e.prototype, n = {
    /**
     * `%SharedDate%.now()` throw a `TypeError` starting with "secure mode".
     * See https://github.com/endojs/endo/issues/910#issuecomment-1581855420
     */
    now() {
      throw v("secure mode Calling %SharedDate%.now() throws");
    }
  }, o = ({ powers: c = "none" } = {}) => {
    let l;
    return c === "original" ? l = function(...d) {
      return new.target === void 0 ? ne(e, void 0, d) : mr(e, d, new.target);
    } : l = function(...d) {
      if (new.target === void 0)
        throw v(
          "secure mode Calling %SharedDate% constructor as a function throws"
        );
      if (d.length === 0)
        throw v(
          "secure mode Calling new %SharedDate%() with no arguments throws"
        );
      return mr(e, d, new.target);
    }, F(l, {
      length: { value: 7 },
      prototype: {
        value: r,
        writable: !1,
        enumerable: !1,
        configurable: !1
      },
      parse: {
        value: e.parse,
        writable: !0,
        enumerable: !1,
        configurable: !0
      },
      UTC: {
        value: e.UTC,
        writable: !0,
        enumerable: !1,
        configurable: !0
      }
    }), l;
  }, a = o({ powers: "original" }), i = o({ powers: "none" });
  return F(a, {
    now: {
      value: e.now,
      writable: !0,
      enumerable: !1,
      configurable: !0
    }
  }), F(i, {
    now: {
      value: n.now,
      writable: !0,
      enumerable: !1,
      configurable: !0
    }
  }), F(r, {
    constructor: { value: i }
  }), {
    "%InitialDate%": a,
    "%SharedDate%": i
  };
}
function Xa(t = "safe") {
  if (t !== "safe" && t !== "unsafe")
    throw v(`unrecognized mathTaming ${t}`);
  const e = qs, r = e, { random: n, ...o } = Ze(e), i = Z(bn, {
    ...o,
    random: {
      value: {
        /**
         * `%SharedMath%.random()` throws a TypeError starting with "secure mode".
         * See https://github.com/endojs/endo/issues/910#issuecomment-1581855420
         */
        random() {
          throw v("secure mode %SharedMath%.random() throws");
        }
      }.random,
      writable: !0,
      enumerable: !1,
      configurable: !0
    }
  });
  return {
    "%InitialMath%": r,
    "%SharedMath%": i
  };
}
function Qa(t = "safe") {
  if (t !== "safe" && t !== "unsafe")
    throw v(`unrecognized regExpTaming ${t}`);
  const e = We.prototype, r = (a = {}) => {
    const i = function(...l) {
      return new.target === void 0 ? We(...l) : mr(We, l, new.target);
    };
    if (F(i, {
      length: { value: 2 },
      prototype: {
        value: e,
        writable: !1,
        enumerable: !1,
        configurable: !1
      }
    }), Vr) {
      const c = J(
        We,
        Vr
      );
      if (!c)
        throw v("no RegExp[Symbol.species] descriptor");
      F(i, {
        [Vr]: c
      });
    }
    return i;
  }, n = r(), o = r();
  return t !== "unsafe" && delete e.compile, F(e, {
    constructor: { value: o }
  }), {
    "%InitialRegExp%": n,
    "%SharedRegExp%": o
  };
}
const ei = {
  "%ObjectPrototype%": {
    toString: !0
  },
  "%FunctionPrototype%": {
    toString: !0
    // set by "rollup"
  },
  "%ErrorPrototype%": {
    name: !0
    // set by "precond", "ava", "node-fetch"
  },
  "%IteratorPrototype%": {
    toString: !0,
    // https://github.com/tc39/proposal-iterator-helpers
    constructor: !0,
    // https://github.com/tc39/proposal-iterator-helpers
    [qe]: !0
  }
}, as = {
  "%ObjectPrototype%": {
    toString: !0,
    valueOf: !0
  },
  "%ArrayPrototype%": {
    toString: !0,
    push: !0,
    // set by "Google Analytics"
    concat: !0,
    // set by mobx generated code (old TS compiler?)
    [rr]: !0
    // set by mobx generated code (old TS compiler?)
  },
  // Function.prototype has no 'prototype' property to enable.
  // Function instances have their own 'name' and 'length' properties
  // which are configurable and non-writable. Thus, they are already
  // non-assignable anyway.
  "%FunctionPrototype%": {
    constructor: !0,
    // set by "regenerator-runtime"
    bind: !0,
    // set by "underscore", "express"
    toString: !0
    // set by "rollup"
  },
  "%ErrorPrototype%": {
    constructor: !0,
    // set by "fast-json-patch", "node-fetch"
    message: !0,
    name: !0,
    // set by "precond", "ava", "node-fetch", "node 14"
    toString: !0
    // set by "bluebird"
  },
  "%TypeErrorPrototype%": {
    constructor: !0,
    // set by "readable-stream"
    message: !0,
    // set by "tape"
    name: !0
    // set by "readable-stream", "node 14"
  },
  "%SyntaxErrorPrototype%": {
    message: !0,
    // to match TypeErrorPrototype.message
    name: !0
    // set by "node 14"
  },
  "%RangeErrorPrototype%": {
    message: !0,
    // to match TypeErrorPrototype.message
    name: !0
    // set by "node 14"
  },
  "%URIErrorPrototype%": {
    message: !0,
    // to match TypeErrorPrototype.message
    name: !0
    // set by "node 14"
  },
  "%EvalErrorPrototype%": {
    message: !0,
    // to match TypeErrorPrototype.message
    name: !0
    // set by "node 14"
  },
  "%ReferenceErrorPrototype%": {
    message: !0,
    // to match TypeErrorPrototype.message
    name: !0
    // set by "node 14"
  },
  // https://github.com/endojs/endo/issues/550
  "%AggregateErrorPrototype%": {
    message: !0,
    // to match TypeErrorPrototype.message
    name: !0
    // set by "node 14"?
  },
  "%PromisePrototype%": {
    constructor: !0
    // set by "core-js"
  },
  "%TypedArrayPrototype%": "*",
  // set by https://github.com/feross/buffer
  "%Generator%": {
    constructor: !0,
    name: !0,
    toString: !0
  },
  "%IteratorPrototype%": {
    toString: !0,
    // https://github.com/tc39/proposal-iterator-helpers
    constructor: !0,
    // https://github.com/tc39/proposal-iterator-helpers
    [qe]: !0
  }
}, ti = {
  ...as,
  /**
   * Rollup (as used at least by vega) and webpack
   * (as used at least by regenerator) both turn exports into assignments
   * to a big `exports` object that inherits directly from
   * `Object.prototype`. Some of the exported names we've seen include
   * `hasOwnProperty`, `constructor`, and `toString`. But the strategy used
   * by rollup and webpack potentionally turns any exported name
   * into an assignment rejected by the override mistake. That's why
   * the `severe` enablements takes the extreme step of enabling
   * everything on `Object.prototype`.
   *
   * In addition, code doing inheritance manually will often override
   * the `constructor` property on the new prototype by assignment. We've
   * seen this several times.
   *
   * The cost of enabling all these is that they create a miserable debugging
   * experience specifically on Node.
   * https://github.com/Agoric/agoric-sdk/issues/2324
   * explains how it confused the Node console.
   *
   * (TODO Reexamine the vscode situation. I think it may have improved
   * since the following paragraph was written.)
   *
   * The vscode debugger's object inspector shows the own data properties of
   * an object, which is typically what you want, but also shows both getter
   * and setter for every accessor property whether inherited or own.
   * With the `'*'` setting here, all the properties inherited from
   * `Object.prototype` are accessors, creating an unusable display as seen
   * at As explained at
   * https://github.com/endojs/endo/blob/master/packages/ses/docs/lockdown.md#overridetaming-options
   * Open the triangles at the bottom of that section.
   */
  "%ObjectPrototype%": "*",
  /**
   * The widely used Buffer defined at https://github.com/feross/buffer
   * on initialization, manually creates the equivalent of a subclass of
   * `TypedArray`, which it then initializes by assignment. These assignments
   * include enough of the `TypeArray` methods that here, the `severe`
   * enablements just enable them all.
   */
  "%TypedArrayPrototype%": "*",
  /**
   * Needed to work with Immer before https://github.com/immerjs/immer/pull/914
   * is accepted.
   */
  "%MapPrototype%": "*",
  /**
   * Needed to work with Immer before https://github.com/immerjs/immer/pull/914
   * is accepted.
   */
  "%SetPrototype%": "*"
};
function ri(t, e, r = []) {
  const n = new Ct(r);
  function o(u, d, f, h) {
    if ("value" in h && h.configurable) {
      const { value: p } = h, m = En(n, f), { get: _, set: S } = J(
        {
          get [f]() {
            return p;
          },
          set [f](T) {
            if (d === this)
              throw v(
                `Cannot assign to read only property '${pe(
                  f
                )}' of '${u}'`
              );
            oe(this, f) ? this[f] = T : (m && console.error(v(`Override property ${f}`)), M(this, f, {
              value: T,
              writable: !0,
              enumerable: !0,
              configurable: !0
            }));
          }
        },
        f
      );
      M(_, "originalValue", {
        value: p,
        writable: !1,
        enumerable: !1,
        configurable: !1
      }), M(d, f, {
        get: _,
        set: S,
        enumerable: h.enumerable,
        configurable: h.configurable
      });
    }
  }
  function a(u, d, f) {
    const h = J(d, f);
    h && o(u, d, f, h);
  }
  function i(u, d) {
    const f = Ze(d);
    f && ut(De(f), (h) => o(u, d, h, f[h]));
  }
  function c(u, d, f) {
    for (const h of De(f)) {
      const p = J(d, h);
      if (!p || p.get || p.set)
        continue;
      const m = `${u}.${pe(h)}`, _ = f[h];
      if (_ === !0)
        a(m, d, h);
      else if (_ === "*")
        i(m, p.value);
      else if (Ye(_))
        c(m, p.value, _);
      else
        throw v(`Unexpected override enablement plan ${m}`);
    }
  }
  let l;
  switch (e) {
    case "min": {
      l = ei;
      break;
    }
    case "moderate": {
      l = as;
      break;
    }
    case "severe": {
      l = ti;
      break;
    }
    default:
      throw v(`unrecognized overrideTaming ${e}`);
  }
  c("root", t, l);
}
const { Fail: cn, quote: Sr } = z, ni = /^(\w*[a-z])Locale([A-Z]\w*)$/, is = {
  // See https://tc39.es/ecma262/#sec-string.prototype.localecompare
  localeCompare(t) {
    if (this === null || this === void 0)
      throw v(
        'Cannot localeCompare with null or undefined "this" value'
      );
    const e = `${this}`, r = `${t}`;
    return e < r ? -1 : e > r ? 1 : (e === r || cn`expected ${Sr(e)} and ${Sr(r)} to compare`, 0);
  },
  toString() {
    return `${this}`;
  }
}, oi = is.localeCompare, si = is.toString;
function ai(t, e = "safe") {
  if (e !== "safe" && e !== "unsafe")
    throw v(`unrecognized localeTaming ${e}`);
  if (e !== "unsafe") {
    M(pe.prototype, "localeCompare", {
      value: oi
    });
    for (const r of Dt(t)) {
      const n = t[r];
      if (Ye(n))
        for (const o of Dt(n)) {
          const a = kn(ni, o);
          if (a) {
            typeof n[o] == "function" || cn`expected ${Sr(o)} to be a function`;
            const i = `${a[1]}${a[2]}`, c = n[i];
            typeof c == "function" || cn`function ${Sr(i)} not found`, M(n, o, { value: c });
          }
        }
    }
    M(So.prototype, "toLocaleString", {
      value: si
    });
  }
}
const ii = (t) => ({
  eval(r) {
    return typeof r != "string" ? r : t(r);
  }
}).eval, { Fail: to } = z, ci = (t) => {
  const e = function(n) {
    const o = `${gr(arguments) || ""}`, a = `${Rt(arguments, ",")}`;
    new ve(a, ""), new ve(o);
    const i = `(function anonymous(${a}
) {
${o}
})`;
    return t(i);
  };
  return F(e, {
    // Ensure that any function created in any evaluator in a realm is an
    // instance of Function in any evaluator of the same realm.
    prototype: {
      value: ve.prototype,
      writable: !1,
      enumerable: !1,
      configurable: !1
    }
  }), j(ve) === ve.prototype || to`Function prototype is the same accross compartments`, j(e) === ve.prototype || to`Function constructor prototype is the same accross compartments`, e;
}, li = (t) => {
  M(
    t,
    Qs,
    y(
      $r(Z(null), {
        set: y(() => {
          throw v(
            "Cannot set Symbol.unscopables of a Compartment's globalThis"
          );
        }),
        enumerable: !1,
        configurable: !1
      })
    )
  );
}, cs = (t) => {
  for (const [e, r] of re(es))
    M(t, e, {
      value: r,
      writable: !1,
      enumerable: !1,
      configurable: !1
    });
}, ls = (t, {
  intrinsics: e,
  newGlobalPropertyNames: r,
  makeCompartmentConstructor: n,
  markVirtualizedNativeFunction: o
}) => {
  for (const [i, c] of re(ts))
    oe(e, c) && M(t, i, {
      value: e[c],
      writable: !0,
      enumerable: !1,
      configurable: !0
    });
  for (const [i, c] of re(r))
    oe(e, c) && M(t, i, {
      value: e[c],
      writable: !0,
      enumerable: !1,
      configurable: !0
    });
  const a = {
    globalThis: t
  };
  a.Compartment = y(
    n(
      n,
      e,
      o
    )
  );
  for (const [i, c] of re(a))
    M(t, i, {
      value: c,
      writable: !0,
      enumerable: !1,
      configurable: !0
    }), typeof c == "function" && o(c);
}, ln = (t, e, r) => {
  {
    const n = y(ii(e));
    r(n), M(t, "eval", {
      value: n,
      writable: !0,
      enumerable: !1,
      configurable: !0
    });
  }
  {
    const n = y(ci(e));
    r(n), M(t, "Function", {
      value: n,
      writable: !0,
      enumerable: !1,
      configurable: !0
    });
  }
}, { Fail: ui, quote: us } = z, ds = new Cr(
  In,
  y({
    get(t, e) {
      ui`Please report unexpected scope handler trap: ${us(pe(e))}`;
    }
  })
), di = {
  get(t, e) {
  },
  set(t, e, r) {
    throw lt(`${pe(e)} is not defined`);
  },
  has(t, e) {
    return e in k;
  },
  // note: this is likely a bug of safari
  // https://bugs.webkit.org/show_bug.cgi?id=195534
  getPrototypeOf(t) {
    return null;
  },
  // See https://github.com/endojs/endo/issues/1510
  // TODO: report as bug to v8 or Chrome, and record issue link here.
  getOwnPropertyDescriptor(t, e) {
    const r = us(pe(e));
    console.warn(
      `getOwnPropertyDescriptor trap on scopeTerminatorHandler for ${r}`,
      v().stack
    );
  },
  // See https://github.com/endojs/endo/issues/1490
  // TODO Report bug to JSC or Safari
  ownKeys(t) {
    return [];
  }
}, fs = y(
  Z(
    ds,
    Ze(di)
  )
), fi = new Cr(
  In,
  fs
), ps = (t) => {
  const e = {
    // inherit scopeTerminator behavior
    ...fs,
    // Redirect set properties to the globalObject.
    set(o, a, i) {
      return Io(t, a, i);
    },
    // Always claim to have a potential property in order to be the recipient of a set
    has(o, a) {
      return !0;
    }
  }, r = y(
    Z(
      ds,
      Ze(e)
    )
  );
  return new Cr(
    In,
    r
  );
};
y(ps);
const { Fail: pi } = z, hi = () => {
  const t = Z(null), e = y({
    eval: {
      get() {
        return delete t.eval, jo;
      },
      enumerable: !1,
      configurable: !0
    }
  }), r = {
    evalScope: t,
    allowNextEvalToBeUnsafe() {
      const { revoked: n } = r;
      n !== null && pi`a handler did not reset allowNextEvalToBeUnsafe ${n.err}`, F(t, e);
    },
    /** @type {null | { err: any }} */
    revoked: null
  };
  return r;
}, ro = "\\s*[@#]\\s*([a-zA-Z][a-zA-Z0-9]*)\\s*=\\s*([^\\s\\*]*)", mi = new We(
  `(?:\\s*//${ro}|/\\*${ro}\\s*\\*/)\\s*$`
), Nn = (t) => {
  let e = "<unknown>";
  for (; t.length > 0; ) {
    const r = kn(mi, t);
    if (r === null)
      break;
    t = Pn(t, 0, t.length - r[0].length), r[3] === "sourceURL" ? e = r[4] : r[1] === "sourceURL" && (e = r[2]);
  }
  return e;
};
function Rn(t, e) {
  const r = va(t, e);
  if (r < 0)
    return -1;
  const n = t[r] === `
` ? 1 : 0;
  return Tn(Pn(t, 0, r), `
`).length + n;
}
const hs = new We("(?:<!--|-->)", "g"), ms = (t) => {
  const e = Rn(t, hs);
  if (e < 0)
    return t;
  const r = Nn(t);
  throw tr(
    `Possible HTML comment rejected at ${r}:${e}. (SES_HTML_COMMENT_REJECTED)`
  );
}, gs = (t) => vr(t, hs, (r) => r[0] === "<" ? "< ! --" : "-- >"), ys = new We(
  "(^|[^.]|\\.\\.\\.)\\bimport(\\s*(?:\\(|/[/*]))",
  "g"
), vs = (t) => {
  const e = Rn(t, ys);
  if (e < 0)
    return t;
  const r = Nn(t);
  throw tr(
    `Possible import expression rejected at ${r}:${e}. (SES_IMPORT_REJECTED)`
  );
}, _s = (t) => vr(t, ys, (r, n, o) => `${n}__import__${o}`), gi = new We(
  "(^|[^.])\\beval(\\s*\\()",
  "g"
), bs = (t) => {
  const e = Rn(t, gi);
  if (e < 0)
    return t;
  const r = Nn(t);
  throw tr(
    `Possible direct eval expression rejected at ${r}:${e}. (SES_EVAL_REJECTED)`
  );
}, ws = (t) => (t = ms(t), t = vs(t), t), Ss = (t, e) => {
  for (const r of e)
    t = r(t);
  return t;
};
y({
  rejectHtmlComments: y(ms),
  evadeHtmlCommentTest: y(gs),
  rejectImportExpressions: y(vs),
  evadeImportExpressionTest: y(_s),
  rejectSomeDirectEvalExpressions: y(bs),
  mandatoryTransforms: y(ws),
  applyTransforms: y(Ss)
});
const yi = [
  // 11.6.2.1 Keywords
  "await",
  "break",
  "case",
  "catch",
  "class",
  "const",
  "continue",
  "debugger",
  "default",
  "delete",
  "do",
  "else",
  "export",
  "extends",
  "finally",
  "for",
  "function",
  "if",
  "import",
  "in",
  "instanceof",
  "new",
  "return",
  "super",
  "switch",
  "this",
  "throw",
  "try",
  "typeof",
  "var",
  "void",
  "while",
  "with",
  "yield",
  // Also reserved when parsing strict mode code
  "let",
  "static",
  // 11.6.2.2 Future Reserved Words
  "enum",
  // Also reserved when parsing strict mode code
  "implements",
  "package",
  "protected",
  "interface",
  "private",
  "public",
  // Reserved but not mentioned in specs
  "await",
  "null",
  "true",
  "false",
  "this",
  "arguments"
], vi = /^[a-zA-Z_$][\w$]*$/, no = (t) => t !== "eval" && !Mr(yi, t) && xn(vi, t);
function oo(t, e) {
  const r = J(t, e);
  return r && //
  // The getters will not have .writable, don't let the falsyness of
  // 'undefined' trick us: test with === false, not ! . However descriptors
  // inherit from the (potentially poisoned) global object, so we might see
  // extra properties which weren't really there. Accessor properties have
  // 'get/set/enumerable/configurable', while data properties have
  // 'value/writable/enumerable/configurable'.
  r.configurable === !1 && r.writable === !1 && //
  // Checks for data properties because they're the only ones we can
  // optimize (accessors are most likely non-constant). Descriptors can't
  // can't have accessors and value properties at the same time, therefore
  // this check is sufficient. Using explicit own property deal with the
  // case where Object.prototype has been poisoned.
  oe(r, "value");
}
const _i = (t, e = {}) => {
  const r = Dt(t), n = Dt(e), o = Ke(
    n,
    (i) => no(i) && oo(e, i)
  );
  return {
    globalObjectConstants: Ke(
      r,
      (i) => (
        // Can't define a constant: it would prevent a
        // lookup on the endowments.
        !Mr(n, i) && no(i) && oo(t, i)
      )
    ),
    moduleLexicalConstants: o
  };
};
function so(t, e) {
  return t.length === 0 ? "" : `const {${Rt(t, ",")}} = this.${e};`;
}
const bi = (t) => {
  const { globalObjectConstants: e, moduleLexicalConstants: r } = _i(
    t.globalObject,
    t.moduleLexicals
  ), n = so(
    e,
    "globalObject"
  ), o = so(
    r,
    "moduleLexicals"
  ), a = ve(`
    with (this.scopeTerminator) {
      with (this.globalObject) {
        with (this.moduleLexicals) {
          with (this.evalScope) {
            ${n}
            ${o}
            return function() {
              'use strict';
              return eval(arguments[0]);
            };
          }
        }
      }
    }
  `);
  return ne(a, t, []);
}, { Fail: wi } = z, On = ({
  globalObject: t,
  moduleLexicals: e = {},
  globalTransforms: r = [],
  sloppyGlobalsMode: n = !1
}) => {
  const o = n ? ps(t) : fi, a = hi(), { evalScope: i } = a, c = y({
    evalScope: i,
    moduleLexicals: e,
    globalObject: t,
    scopeTerminator: o
  });
  let l;
  const u = () => {
    l || (l = bi(c));
  };
  return { safeEvaluate: (f, h) => {
    const { localTransforms: p = [] } = h || {};
    u(), f = Ss(f, [
      ...p,
      ...r,
      ws
    ]);
    let m;
    try {
      return a.allowNextEvalToBeUnsafe(), ne(l, t, [f]);
    } catch (_) {
      throw m = _, _;
    } finally {
      const _ = "eval" in i;
      delete i.eval, _ && (a.revoked = { err: m }, wi`handler did not reset allowNextEvalToBeUnsafe ${m}`);
    }
  } };
}, Si = ") { [native code] }";
let Yr;
const Es = () => {
  if (Yr === void 0) {
    const t = new $t();
    M(wn, "toString", {
      value: {
        toString() {
          const r = wa(this);
          return Mo(r, Si) || !or(t, this) ? r : `function ${this.name}() { [native code] }`;
        }
      }.toString
    }), Yr = y(
      (r) => Fr(t, r)
    );
  }
  return Yr;
};
function Ei(t = "safe") {
  if (t !== "safe" && t !== "unsafe")
    throw v(`unrecognized domainTaming ${t}`);
  if (t === "unsafe")
    return;
  const e = k.process || void 0;
  if (typeof e == "object") {
    const r = J(e, "domain");
    if (r !== void 0 && r.get !== void 0)
      throw v(
        "SES failed to lockdown, Node.js domains have been initialized (SES_NO_DOMAINS)"
      );
    M(e, "domain", {
      value: null,
      configurable: !1,
      writable: !1,
      enumerable: !1
    });
  }
}
const Mn = y([
  ["debug", "debug"],
  // (fmt?, ...args) verbose level on Chrome
  ["log", "log"],
  // (fmt?, ...args) info level on Chrome
  ["info", "info"],
  // (fmt?, ...args)
  ["warn", "warn"],
  // (fmt?, ...args)
  ["error", "error"],
  // (fmt?, ...args)
  ["trace", "log"],
  // (fmt?, ...args)
  ["dirxml", "log"],
  // (fmt?, ...args)          but TS typed (...data)
  ["group", "log"],
  // (fmt?, ...args)           but TS typed (...label)
  ["groupCollapsed", "log"]
  // (fmt?, ...args)  but TS typed (...label)
]), Ln = y([
  ["assert", "error"],
  // (value, fmt?, ...args)
  ["timeLog", "log"],
  // (label?, ...args) no fmt string
  // Insensitive to whether any argument is an error. All arguments can pass
  // thru to baseConsole as is.
  ["clear", void 0],
  // ()
  ["count", "info"],
  // (label?)
  ["countReset", void 0],
  // (label?)
  ["dir", "log"],
  // (item, options?)
  ["groupEnd", "log"],
  // ()
  // In theory tabular data may be or contain an error. However, we currently
  // do not detect these and may never.
  ["table", "log"],
  // (tabularData, properties?)
  ["time", "info"],
  // (label?)
  ["timeEnd", "info"],
  // (label?)
  // Node Inspector only, MDN, and TypeScript, but not whatwg
  ["profile", void 0],
  // (label?)
  ["profileEnd", void 0],
  // (label?)
  ["timeStamp", void 0]
  // (label?)
]), xs = y([
  ...Mn,
  ...Ln
]), xi = (t, { shouldResetForDebugging: e = !1 } = {}) => {
  e && t.resetErrorTagNum();
  let r = [];
  const n = mt(
    se(xs, ([i, c]) => {
      const l = (...u) => {
        X(r, [i, ...u]);
      };
      return M(l, "name", { value: i }), [i, y(l)];
    })
  );
  y(n);
  const o = () => {
    const i = y(r);
    return r = [], i;
  };
  return y(o), y({ loggingConsole: (
    /** @type {VirtualConsole} */
    n
  ), takeLog: o });
};
y(xi);
const it = {
  NOTE: "ERROR_NOTE:",
  MESSAGE: "ERROR_MESSAGE:",
  CAUSE: "cause:",
  ERRORS: "errors:"
};
y(it);
const Fn = (t, e) => {
  if (!t)
    return;
  const { getStackString: r, tagError: n, takeMessageLogArgs: o, takeNoteLogArgsArray: a } = e, i = (S, T) => se(S, (x) => Dr(x) ? (X(T, x), `(${n(x)})`) : x), c = (S, T, N, x, D) => {
    const G = n(T), B = N === it.MESSAGE ? `${G}:` : `${G} ${N}`, K = i(x, D);
    t[S](B, ...K);
  }, l = (S, T, N = void 0) => {
    if (T.length === 0)
      return;
    if (T.length === 1 && N === void 0) {
      f(S, T[0]);
      return;
    }
    let x;
    T.length === 1 ? x = "Nested error" : x = `Nested ${T.length} errors`, N !== void 0 && (x = `${x} under ${N}`), t.group(x);
    try {
      for (const D of T)
        f(S, D);
    } finally {
      t.groupEnd();
    }
  }, u = new $t(), d = (S) => (T, N) => {
    const x = [];
    c(S, T, it.NOTE, N, x), l(S, x, n(T));
  }, f = (S, T) => {
    if (or(u, T))
      return;
    const N = n(T);
    Fr(u, T);
    const x = [], D = o(T), G = a(
      T,
      d(S)
    );
    D === void 0 ? t[S](`${N}:`, T.message) : c(
      S,
      T,
      it.MESSAGE,
      D,
      x
    );
    let B = r(T);
    typeof B == "string" && B.length >= 1 && !Mo(B, `
`) && (B += `
`), t[S](B), T.cause && c(S, T, it.CAUSE, [T.cause], x), T.errors && c(S, T, it.ERRORS, T.errors, x);
    for (const K of G)
      c(S, T, it.NOTE, K, x);
    l(S, x, N);
  }, h = se(Mn, ([S, T]) => {
    const N = (...x) => {
      const D = [], G = i(x, D);
      t[S](...G), l(S, D);
    };
    return M(N, "name", { value: S }), [S, y(N)];
  }), p = Ke(
    Ln,
    ([S, T]) => S in t
  ), m = se(p, ([S, T]) => {
    const N = (...x) => {
      t[S](...x);
    };
    return M(N, "name", { value: S }), [S, y(N)];
  }), _ = mt([...h, ...m]);
  return (
    /** @type {VirtualConsole} */
    y(_)
  );
};
y(Fn);
const ki = (t, e, r) => {
  const [n, ...o] = Tn(t, e), a = Ro(o, (i) => [e, ...r, i]);
  return ["", n, ...a];
}, ks = (t) => y((r) => {
  const n = [], o = (...l) => (n.length > 0 && (l = Ro(
    l,
    (u) => typeof u == "string" && Lo(u, `
`) ? ki(u, `
`, n) : [u]
  ), l = [...n, ...l]), r(...l)), a = (l, u) => ({ [l]: (...d) => u(...d) })[l], i = mt([
    ...se(Mn, ([l]) => [
      l,
      a(l, o)
    ]),
    ...se(Ln, ([l]) => [
      l,
      a(l, (...u) => o(l, ...u))
    ])
  ]);
  for (const l of ["group", "groupCollapsed"])
    i[l] && (i[l] = a(l, (...u) => {
      u.length >= 1 && o(...u), X(n, " ");
    }));
  return i.groupEnd && (i.groupEnd = a("groupEnd", (...l) => {
    gr(n);
  })), harden(i), Fn(
    /** @type {VirtualConsole} */
    i,
    t
  );
});
y(ks);
const Pi = (t, e, r = void 0) => {
  const n = Ke(
    xs,
    ([i, c]) => i in t
  ), o = se(n, ([i, c]) => [i, y((...u) => {
    (c === void 0 || e.canLog(c)) && t[i](...u);
  })]), a = mt(o);
  return (
    /** @type {VirtualConsole} */
    y(a)
  );
};
y(Pi);
const ao = (t) => {
  if (kt === void 0)
    return;
  let e = 0;
  const r = new Pe(), n = (d) => {
    fa(r, d);
  }, o = new Me(), a = (d) => {
    if (Lr(r, d)) {
      const f = Ue(r, d);
      n(d), t(f);
    }
  }, i = new kt(a);
  return {
    rejectionHandledHandler: (d) => {
      const f = L(o, d);
      n(f);
    },
    unhandledRejectionHandler: (d, f) => {
      e += 1;
      const h = e;
      $e(r, h, d), ie(o, f, h), Ea(i, f, h, f);
    },
    processTerminationHandler: () => {
      for (const [d, f] of pa(r))
        n(d), t(f);
    }
  };
}, Jr = (t) => {
  throw v(t);
}, io = (t, e) => y((...r) => ne(t, e, r)), Ti = (t = "safe", e = "platform", r = "report", n = void 0) => {
  t === "safe" || t === "unsafe" || Jr(`unrecognized consoleTaming ${t}`);
  let o;
  n === void 0 ? o = br : o = {
    ...br,
    getStackString: n
  };
  const a = (
    /** @type {VirtualConsole} */
    // eslint-disable-next-line no-nested-ternary
    typeof k.console < "u" ? k.console : typeof k.print == "function" ? (
      // Make a good-enough console for eshost (including only functions that
      // log at a specific level with no special argument interpretation).
      // https://console.spec.whatwg.org/#logging
      ((u) => y({ debug: u, log: u, info: u, warn: u, error: u }))(
        // eslint-disable-next-line no-undef
        io(k.print)
      )
    ) : void 0
  );
  if (a && a.log)
    for (const u of ["warn", "error"])
      a[u] || M(a, u, {
        value: io(a.log, a)
      });
  const i = (
    /** @type {VirtualConsole} */
    t === "unsafe" ? a : Fn(a, o)
  ), c = k.process || void 0;
  if (e !== "none" && typeof c == "object" && typeof c.on == "function") {
    let u;
    if (e === "platform" || e === "exit") {
      const { exit: d } = c;
      typeof d == "function" || Jr("missing process.exit"), u = () => d(c.exitCode || -1);
    } else
      e === "abort" && (u = c.abort, typeof u == "function" || Jr("missing process.abort"));
    c.on("uncaughtException", (d) => {
      i.error(d), u && u();
    });
  }
  if (r !== "none" && typeof c == "object" && typeof c.on == "function") {
    const d = ao((f) => {
      i.error("SES_UNHANDLED_REJECTION:", f);
    });
    d && (c.on("unhandledRejection", d.unhandledRejectionHandler), c.on("rejectionHandled", d.rejectionHandledHandler), c.on("exit", d.processTerminationHandler));
  }
  const l = k.window || void 0;
  if (e !== "none" && typeof l == "object" && typeof l.addEventListener == "function" && l.addEventListener("error", (u) => {
    u.preventDefault(), i.error(u.error), (e === "exit" || e === "abort") && (l.location.href = "about:blank");
  }), r !== "none" && typeof l == "object" && typeof l.addEventListener == "function") {
    const d = ao((f) => {
      i.error("SES_UNHANDLED_REJECTION:", f);
    });
    d && (l.addEventListener("unhandledrejection", (f) => {
      f.preventDefault(), d.unhandledRejectionHandler(f.reason, f.promise);
    }), l.addEventListener("rejectionhandled", (f) => {
      f.preventDefault(), d.rejectionHandledHandler(f.promise);
    }), l.addEventListener("beforeunload", (f) => {
      d.processTerminationHandler();
    }));
  }
  return { console: i };
}, Ai = [
  // suppress 'getThis' definitely
  "getTypeName",
  // suppress 'getFunction' definitely
  "getFunctionName",
  "getMethodName",
  "getFileName",
  "getLineNumber",
  "getColumnNumber",
  "getEvalOrigin",
  "isToplevel",
  "isEval",
  "isNative",
  "isConstructor",
  "isAsync",
  // suppress 'isPromiseAll' for now
  // suppress 'getPromiseIndex' for now
  // Additional names found by experiment, absent from
  // https://v8.dev/docs/stack-trace-api
  "getPosition",
  "getScriptNameOrSourceURL",
  "toString"
  // TODO replace to use only whitelisted info
], Ii = (t) => {
  const r = mt(se(Ai, (n) => {
    const o = t[n];
    return [n, () => ne(o, t, [])];
  }));
  return Z(r, {});
}, Ci = (t) => se(t, Ii), $i = /\/node_modules\//, Ni = /^(?:node:)?internal\//, Ri = /\/packages\/ses\/src\/error\/assert.js$/, Oi = /\/packages\/eventual-send\/src\//, Mi = [
  $i,
  Ni,
  Ri,
  Oi
], Li = (t) => {
  if (!t)
    return !0;
  for (const e of Mi)
    if (xn(e, t))
      return !1;
  return !0;
}, Fi = /^((?:.*[( ])?)[:/\w_-]*\/\.\.\.\/(.+)$/, Di = /^((?:.*[( ])?)[:/\w_-]*\/(packages\/.+)$/, Ui = [
  Fi,
  Di
], ji = (t) => {
  for (const e of Ui) {
    const r = kn(e, t);
    if (r)
      return Rt(la(r, 1), "");
  }
  return t;
}, Zi = (t, e, r, n) => {
  const o = t.captureStackTrace, a = (p) => n === "verbose" ? !0 : Li(p.getFileName()), i = (p) => {
    let m = `${p}`;
    return n === "concise" && (m = ji(m)), `
  at ${m}`;
  }, c = (p, m) => Rt(
    se(Ke(m, a), i),
    ""
  ), l = new Me(), u = {
    // The optional `optFn` argument is for cutting off the bottom of
    // the stack --- for capturing the stack only above the topmost
    // call to that function. Since this isn't the "real" captureStackTrace
    // but instead calls the real one, if no other cutoff is provided,
    // we cut this one off.
    captureStackTrace(p, m = u.captureStackTrace) {
      if (typeof o == "function") {
        ne(o, t, [p, m]);
        return;
      }
      Io(p, "stack", "");
    },
    // Shim of proposed special power, to reside by default only
    // in the start compartment, for getting the stack traceback
    // string associated with an error.
    // See https://tc39.es/proposal-error-stacks/
    getStackString(p) {
      let m = L(l, p);
      if (m === void 0 && (p.stack, m = L(l, p), m || (m = { stackString: "" }, ie(l, p, m))), m.stackString !== void 0)
        return m.stackString;
      const _ = c(p, m.callSites);
      return ie(l, p, { stackString: _ }), _;
    },
    prepareStackTrace(p, m) {
      if (r === "unsafe") {
        const _ = c(p, m);
        return ie(l, p, { stackString: _ }), `${p}${_}`;
      } else
        return ie(l, p, { callSites: m }), "";
    }
  }, d = u.prepareStackTrace;
  t.prepareStackTrace = d;
  const f = new $t([d]), h = (p) => {
    if (or(f, p))
      return p;
    const m = {
      prepareStackTrace(_, S) {
        return ie(l, _, { callSites: S }), p(_, Ci(S));
      }
    };
    return Fr(f, m.prepareStackTrace), m.prepareStackTrace;
  };
  return F(e, {
    captureStackTrace: {
      value: u.captureStackTrace,
      writable: !0,
      enumerable: !1,
      configurable: !0
    },
    prepareStackTrace: {
      get() {
        return t.prepareStackTrace;
      },
      set(p) {
        if (typeof p == "function") {
          const m = h(p);
          t.prepareStackTrace = m;
        } else
          t.prepareStackTrace = d;
      },
      enumerable: !1,
      configurable: !0
    }
  }), u.getStackString;
}, co = J(ue.prototype, "stack"), lo = co && co.get, zi = {
  getStackString(t) {
    return typeof lo == "function" ? ne(lo, t, []) : "stack" in t ? `${t.stack}` : "";
  }
};
function Gi(t = "safe", e = "concise") {
  if (t !== "safe" && t !== "unsafe")
    throw v(`unrecognized errorTaming ${t}`);
  if (e !== "concise" && e !== "verbose")
    throw v(`unrecognized stackFiltering ${e}`);
  const r = ue.prototype, n = typeof ue.captureStackTrace == "function" ? "v8" : "unknown", { captureStackTrace: o } = ue, a = (u = {}) => {
    const d = function(...h) {
      let p;
      return new.target === void 0 ? p = ne(ue, this, h) : p = mr(ue, h, new.target), n === "v8" && ne(o, ue, [p, d]), p;
    };
    return F(d, {
      length: { value: 1 },
      prototype: {
        value: r,
        writable: !1,
        enumerable: !1,
        configurable: !1
      }
    }), d;
  }, i = a({ powers: "original" }), c = a({ powers: "none" });
  F(r, {
    constructor: { value: c }
  });
  for (const u of ns)
    xo(u, c);
  F(i, {
    stackTraceLimit: {
      get() {
        if (typeof ue.stackTraceLimit == "number")
          return ue.stackTraceLimit;
      },
      set(u) {
        if (typeof u == "number" && typeof ue.stackTraceLimit == "number") {
          ue.stackTraceLimit = u;
          return;
        }
      },
      // WTF on v8 stackTraceLimit is enumerable
      enumerable: !1,
      configurable: !0
    }
  }), F(c, {
    stackTraceLimit: {
      get() {
      },
      set(u) {
      },
      enumerable: !1,
      configurable: !0
    }
  }), n === "v8" && F(c, {
    prepareStackTrace: {
      get() {
        return () => "";
      },
      set(u) {
      },
      enumerable: !1,
      configurable: !0
    },
    captureStackTrace: {
      value: (u, d) => {
        M(u, "stack", {
          value: ""
        });
      },
      writable: !1,
      enumerable: !1,
      configurable: !0
    }
  });
  let l = zi.getStackString;
  return n === "v8" ? l = Zi(
    ue,
    i,
    t,
    e
  ) : t === "unsafe" ? F(r, {
    stack: {
      get() {
        return l(this);
      },
      set(u) {
        F(this, {
          stack: {
            value: u,
            writable: !0,
            enumerable: !0,
            configurable: !0
          }
        });
      }
    }
  }) : F(r, {
    stack: {
      get() {
        return `${this}`;
      },
      set(u) {
        F(this, {
          stack: {
            value: u,
            writable: !0,
            enumerable: !0,
            configurable: !0
          }
        });
      }
    }
  }), {
    "%InitialGetStackString%": l,
    "%InitialError%": i,
    "%SharedError%": c
  };
}
const { Fail: Bi, details: un, quote: xe } = z, Hi = () => {
};
async function Vi(t, e, r) {
  const n = t(...e);
  let o = yr(n);
  for (; !o.done; )
    try {
      const a = await o.value;
      o = yr(n, a);
    } catch (a) {
      o = Fo(n, r(a));
    }
  return o.value;
}
function Wi(t, e) {
  const r = t(...e);
  let n = yr(r);
  for (; !n.done; )
    try {
      n = yr(r, n.value);
    } catch (o) {
      n = Fo(r, o);
    }
  return n.value;
}
const qi = (t, e) => y({
  compartment: t,
  specifier: e
}), Ki = (t, e, r) => {
  const n = Z(null);
  for (const o of t) {
    const a = e(o, r);
    n[o] = a;
  }
  return y(n);
}, uo = (t, e, r, n, o, a, i, c, l) => {
  const { resolveHook: u, moduleRecords: d } = L(
    t,
    r
  ), f = Ki(
    o.imports,
    u,
    n
  ), h = y({
    compartment: r,
    staticModuleRecord: o,
    moduleSpecifier: n,
    resolvedImports: f,
    importMeta: l
  });
  for (const p of ko(f))
    a(Ut, [
      t,
      e,
      r,
      p,
      a,
      i,
      c
    ]);
  return $e(d, n, h), h;
};
function* Yi(t, e, r, n, o, a, i) {
  const { importHook: c, importNowHook: l, moduleMap: u, moduleMapHook: d, moduleRecords: f } = L(t, r);
  let h = u[n];
  if (h === void 0 && d !== void 0 && (h = d(n)), typeof h == "string")
    z.fail(
      un`Cannot map module ${xe(n)} to ${xe(
        h
      )} in parent compartment, not yet implemented`,
      v
    );
  else if (h !== void 0) {
    const m = L(e, h);
    m === void 0 && z.fail(
      un`Cannot map module ${xe(
        n
      )} because the value is not a module exports namespace, or is from another realm`,
      lt
    );
    const _ = yield Ut(
      t,
      e,
      m.compartment,
      m.specifier,
      o,
      a,
      i
    );
    return $e(f, n, _), _;
  }
  if (Lr(f, n))
    return Ue(f, n);
  const p = yield a(
    c,
    l
  )(n);
  if ((p === null || typeof p != "object") && Bi`importHook must return a promise for an object, for module ${xe(
    n
  )} in compartment ${xe(r.name)}`, p.specifier !== void 0) {
    if (p.record !== void 0) {
      if (p.compartment !== void 0)
        throw v(
          "Cannot redirect to an explicit record with a specified compartment"
        );
      const {
        compartment: m = r,
        specifier: _ = n,
        record: S,
        importMeta: T
      } = p, N = uo(
        t,
        e,
        m,
        _,
        S,
        o,
        a,
        i,
        T
      );
      return $e(f, n, N), N;
    }
    if (p.compartment !== void 0) {
      if (p.importMeta !== void 0)
        throw v(
          "Cannot redirect to an implicit record with a specified importMeta"
        );
      const m = yield Ut(
        t,
        e,
        p.compartment,
        p.specifier,
        o,
        a,
        i
      );
      return $e(f, n, m), m;
    }
    throw v("Unnexpected RedirectStaticModuleInterface record shape");
  }
  return uo(
    t,
    e,
    r,
    n,
    p,
    o,
    a,
    i
  );
}
const Ut = (t, e, r, n, o, a, i) => {
  const { name: c } = L(
    t,
    r
  );
  let l = Ue(i, r);
  l === void 0 && (l = new Pe(), $e(i, r, l));
  let u = Ue(l, n);
  return u !== void 0 || (u = a(Vi, Wi)(
    Yi,
    [
      t,
      e,
      r,
      n,
      o,
      a,
      i
    ],
    (d) => {
      throw z.note(
        d,
        un`${d.message}, loading ${xe(n)} in compartment ${xe(
          c
        )}`
      ), d;
    }
  ), $e(l, n, u)), u;
};
function Ji() {
  const t = new Ct(), e = [];
  return { enqueueJob: (o, a) => {
    Sn(
      t,
      Uo(o(...a), Hi, (i) => {
        X(e, i);
      })
    );
  }, drainQueue: async () => {
    for (const o of t)
      await o;
    return e;
  } };
}
function Ps({ errors: t, errorPrefix: e }) {
  if (t.length > 0) {
    const r = le("COMPARTMENT_LOAD_ERRORS", "", ["verbose"]) === "verbose";
    throw v(
      `${e} (${t.length} underlying failures: ${Rt(
        se(t, (n) => n.message + (r ? n.stack : "")),
        ", "
      )}`
    );
  }
}
const Xi = (t, e) => e, Qi = (t, e) => t, fo = async (t, e, r, n) => {
  const { name: o } = L(
    t,
    r
  ), a = new Pe(), { enqueueJob: i, drainQueue: c } = Ji();
  i(Ut, [
    t,
    e,
    r,
    n,
    i,
    Qi,
    a
  ]);
  const l = await c();
  Ps({
    errors: l,
    errorPrefix: `Failed to load module ${xe(n)} in package ${xe(
      o
    )}`
  });
}, ec = (t, e, r, n) => {
  const { name: o } = L(
    t,
    r
  ), a = new Pe(), i = [], c = (l, u) => {
    try {
      l(...u);
    } catch (d) {
      X(i, d);
    }
  };
  c(Ut, [
    t,
    e,
    r,
    n,
    c,
    Xi,
    a
  ]), Ps({
    errors: i,
    errorPrefix: `Failed to load module ${xe(n)} in package ${xe(
      o
    )}`
  });
}, { quote: yt } = z, tc = () => {
  let t = !1;
  const e = Z(null, {
    // Make this appear like an ESM module namespace object.
    [qe]: {
      value: "Module",
      writable: !1,
      enumerable: !1,
      configurable: !1
    }
  });
  return y({
    activate() {
      t = !0;
    },
    exportsTarget: e,
    exportsProxy: new Cr(e, {
      get(r, n, o) {
        if (!t)
          throw v(
            `Cannot get property ${yt(
              n
            )} of module exports namespace, the module has not yet begun to execute`
          );
        return oa(e, n, o);
      },
      set(r, n, o) {
        throw v(
          `Cannot set property ${yt(n)} of module exports namespace`
        );
      },
      has(r, n) {
        if (!t)
          throw v(
            `Cannot check property ${yt(
              n
            )}, the module has not yet begun to execute`
          );
        return Ao(e, n);
      },
      deleteProperty(r, n) {
        throw v(
          `Cannot delete property ${yt(n)}s of module exports namespace`
        );
      },
      ownKeys(r) {
        if (!t)
          throw v(
            "Cannot enumerate keys, the module has not yet begun to execute"
          );
        return De(e);
      },
      getOwnPropertyDescriptor(r, n) {
        if (!t)
          throw v(
            `Cannot get own property descriptor ${yt(
              n
            )}, the module has not yet begun to execute`
          );
        return sa(e, n);
      },
      preventExtensions(r) {
        if (!t)
          throw v(
            "Cannot prevent extensions of module exports namespace, the module has not yet begun to execute"
          );
        return ia(e);
      },
      isExtensible() {
        if (!t)
          throw v(
            "Cannot check extensibility of module exports namespace, the module has not yet begun to execute"
          );
        return aa(e);
      },
      getPrototypeOf(r) {
        return null;
      },
      setPrototypeOf(r, n) {
        throw v("Cannot set prototype of module exports namespace");
      },
      defineProperty(r, n, o) {
        throw v(
          `Cannot define property ${yt(n)} of module exports namespace`
        );
      },
      apply(r, n, o) {
        throw v(
          "Cannot call module exports namespace, it is not a function"
        );
      },
      construct(r, n) {
        throw v(
          "Cannot construct module exports namespace, it is not a constructor"
        );
      }
    })
  });
}, Dn = (t, e, r, n) => {
  const { deferredExports: o } = e;
  if (!Lr(o, n)) {
    const a = tc();
    ie(
      r,
      a.exportsProxy,
      qi(t, n)
    ), $e(o, n, a);
  }
  return Ue(o, n);
}, rc = (t, e) => {
  const { sloppyGlobalsMode: r = !1, __moduleShimLexicals__: n = void 0 } = e;
  let o;
  if (n === void 0 && !r)
    ({ safeEvaluate: o } = t);
  else {
    let { globalTransforms: a } = t;
    const { globalObject: i } = t;
    let c;
    n !== void 0 && (a = void 0, c = Z(
      null,
      Ze(n)
    )), { safeEvaluate: o } = On({
      globalObject: i,
      moduleLexicals: c,
      globalTransforms: a,
      sloppyGlobalsMode: r
    });
  }
  return { safeEvaluate: o };
}, Ts = (t, e, r) => {
  if (typeof e != "string")
    throw v("first argument of evaluate() must be a string");
  const {
    transforms: n = [],
    __evadeHtmlCommentTest__: o = !1,
    __evadeImportExpressionTest__: a = !1,
    __rejectSomeDirectEvalExpressions__: i = !0
    // Note default on
  } = r, c = [...n];
  o === !0 && X(c, gs), a === !0 && X(c, _s), i === !0 && X(c, bs);
  const { safeEvaluate: l } = rc(
    t,
    r
  );
  return l(e, {
    localTransforms: c
  });
}, { quote: cr } = z, nc = (t, e, r, n, o, a) => {
  const { exportsProxy: i, exportsTarget: c, activate: l } = Dn(
    r,
    L(t, r),
    n,
    o
  ), u = Z(null);
  if (e.exports) {
    if (!Et(e.exports) || ua(e.exports, (f) => typeof f != "string"))
      throw v(
        `SES third-party static module record "exports" property must be an array of strings for module ${o}`
      );
    ut(e.exports, (f) => {
      let h = c[f];
      const p = [];
      M(c, f, {
        get: () => h,
        set: (S) => {
          h = S;
          for (const T of p)
            T(S);
        },
        enumerable: !0,
        configurable: !1
      }), u[f] = (S) => {
        X(p, S), S(h);
      };
    }), u["*"] = (f) => {
      f(c);
    };
  }
  const d = {
    activated: !1
  };
  return y({
    notifiers: u,
    exportsProxy: i,
    execute() {
      if (Ao(d, "errorFromExecute"))
        throw d.errorFromExecute;
      if (!d.activated) {
        l(), d.activated = !0;
        try {
          e.execute(
            c,
            r,
            a
          );
        } catch (f) {
          throw d.errorFromExecute = f, f;
        }
      }
    }
  });
}, oc = (t, e, r, n) => {
  const {
    compartment: o,
    moduleSpecifier: a,
    staticModuleRecord: i,
    importMeta: c
  } = r, {
    reexports: l = [],
    __syncModuleProgram__: u,
    __fixedExportMap__: d = {},
    __liveExportMap__: f = {},
    __reexportMap__: h = {},
    __needsImportMeta__: p = !1,
    __syncModuleFunctor__: m
  } = i, _ = L(t, o), { __shimTransforms__: S, importMetaHook: T } = _, { exportsProxy: N, exportsTarget: x, activate: D } = Dn(
    o,
    _,
    e,
    a
  ), G = Z(null), B = Z(null), K = Z(null), ze = Z(null), he = Z(null);
  c && $r(he, c), p && T && T(a, he);
  const Ge = Z(null), rt = Z(null);
  ut(re(d), ([me, [H]]) => {
    let V = Ge[H];
    if (!V) {
      let ee, te = !0, ce = [];
      const Y = () => {
        if (te)
          throw lt(`binding ${cr(H)} not yet initialized`);
        return ee;
      }, be = y((we) => {
        if (!te)
          throw v(
            `Internal: binding ${cr(H)} already initialized`
          );
        ee = we;
        const Bn = ce;
        ce = null, te = !1;
        for (const Se of Bn || [])
          Se(we);
        return we;
      });
      V = {
        get: Y,
        notify: (we) => {
          we !== be && (te ? X(ce || [], we) : we(ee));
        }
      }, Ge[H] = V, K[H] = be;
    }
    G[me] = {
      get: V.get,
      set: void 0,
      enumerable: !0,
      configurable: !1
    }, rt[me] = V.notify;
  }), ut(
    re(f),
    ([me, [H, V]]) => {
      let ee = Ge[H];
      if (!ee) {
        let te, ce = !0;
        const Y = [], be = () => {
          if (ce)
            throw lt(
              `binding ${cr(me)} not yet initialized`
            );
          return te;
        }, gt = y((Se) => {
          te = Se, ce = !1;
          for (const zr of Y)
            zr(Se);
        }), we = (Se) => {
          if (ce)
            throw lt(`binding ${cr(H)} not yet initialized`);
          te = Se;
          for (const zr of Y)
            zr(Se);
        };
        ee = {
          get: be,
          notify: (Se) => {
            Se !== gt && (X(Y, Se), ce || Se(te));
          }
        }, Ge[H] = ee, V && M(B, H, {
          get: be,
          set: we,
          enumerable: !0,
          configurable: !1
        }), ze[H] = gt;
      }
      G[me] = {
        get: ee.get,
        set: void 0,
        enumerable: !0,
        configurable: !1
      }, rt[me] = ee.notify;
    }
  );
  const Be = (me) => {
    me(x);
  };
  rt["*"] = Be;
  function ar(me) {
    const H = Z(null);
    H.default = !1;
    for (const [V, ee] of me) {
      const te = Ue(n, V);
      te.execute();
      const { notifiers: ce } = te;
      for (const [Y, be] of ee) {
        const gt = ce[Y];
        if (!gt)
          throw tr(
            `The requested module '${V}' does not provide an export named '${Y}'`
          );
        for (const we of be)
          gt(we);
      }
      if (Mr(l, V))
        for (const [Y, be] of re(
          ce
        ))
          H[Y] === void 0 ? H[Y] = be : H[Y] = !1;
      if (h[V])
        for (const [Y, be] of h[V])
          H[be] = ce[Y];
    }
    for (const [V, ee] of re(H))
      if (!rt[V] && ee !== !1) {
        rt[V] = ee;
        let te;
        ee((Y) => te = Y), G[V] = {
          get() {
            return te;
          },
          set: void 0,
          enumerable: !0,
          configurable: !1
        };
      }
    ut(
      Oo(Eo(G)),
      (V) => M(x, V, G[V])
    ), y(x), D();
  }
  let Ot;
  m !== void 0 ? Ot = m : Ot = Ts(_, u, {
    globalObject: o.globalThis,
    transforms: S,
    __moduleShimLexicals__: B
  });
  let zn = !1, Gn;
  function Gs() {
    if (Ot) {
      const me = Ot;
      Ot = null;
      try {
        me(
          y({
            imports: y(ar),
            onceVar: y(K),
            liveVar: y(ze),
            importMeta: he
          })
        );
      } catch (H) {
        zn = !0, Gn = H;
      }
    }
    if (zn)
      throw Gn;
  }
  return y({
    notifiers: rt,
    exportsProxy: N,
    execute: Gs
  });
}, { Fail: ct, quote: q } = z, As = (t, e, r, n) => {
  const { name: o, moduleRecords: a } = L(
    t,
    r
  ), i = Ue(a, n);
  if (i === void 0)
    throw lt(
      `Missing link to module ${q(n)} from compartment ${q(
        o
      )}`
    );
  return uc(t, e, i);
};
function sc(t) {
  return typeof t.__syncModuleProgram__ == "string";
}
function ac(t, e) {
  const { __fixedExportMap__: r, __liveExportMap__: n } = t;
  Ye(r) || ct`Property '__fixedExportMap__' of a precompiled module record must be an object, got ${q(
    r
  )}, for module ${q(e)}`, Ye(n) || ct`Property '__liveExportMap__' of a precompiled module record must be an object, got ${q(
    n
  )}, for module ${q(e)}`;
}
function ic(t) {
  return typeof t.execute == "function";
}
function cc(t, e) {
  const { exports: r } = t;
  Et(r) || ct`Property 'exports' of a third-party static module record must be an array, got ${q(
    r
  )}, for module ${q(e)}`;
}
function lc(t, e) {
  Ye(t) || ct`Static module records must be of type object, got ${q(
    t
  )}, for module ${q(e)}`;
  const { imports: r, exports: n, reexports: o = [] } = t;
  Et(r) || ct`Property 'imports' of a static module record must be an array, got ${q(
    r
  )}, for module ${q(e)}`, Et(n) || ct`Property 'exports' of a precompiled module record must be an array, got ${q(
    n
  )}, for module ${q(e)}`, Et(o) || ct`Property 'reexports' of a precompiled module record must be an array if present, got ${q(
    o
  )}, for module ${q(e)}`;
}
const uc = (t, e, r) => {
  const { compartment: n, moduleSpecifier: o, resolvedImports: a, staticModuleRecord: i } = r, { instances: c } = L(t, n);
  if (Lr(c, o))
    return Ue(c, o);
  lc(i, o);
  const l = new Pe();
  let u;
  if (sc(i))
    ac(i, o), u = oc(
      t,
      e,
      r,
      l
    );
  else if (ic(i))
    cc(i, o), u = nc(
      t,
      i,
      n,
      e,
      o,
      a
    );
  else
    throw v(
      `importHook must return a static module record, got ${q(
        i
      )}`
    );
  $e(c, o, u);
  for (const [d, f] of re(a)) {
    const h = As(
      t,
      e,
      n,
      f
    );
    $e(l, d, h);
  }
  return u;
}, { quote: Xr } = z, bt = new Me(), Ce = new Me(), lr = (t) => {
  const { importHook: e, resolveHook: r } = L(Ce, t);
  if (typeof e != "function" || typeof r != "function")
    throw v(
      "Compartment must be constructed with an importHook and a resolveHook for it to be able to load modules"
    );
}, Un = function(e = {}, r = {}, n = {}) {
  throw v(
    "Compartment.prototype.constructor is not a valid constructor."
  );
}, po = (t, e) => {
  const { execute: r, exportsProxy: n } = As(
    Ce,
    bt,
    t,
    e
  );
  return r(), n;
}, jn = {
  constructor: Un,
  get globalThis() {
    return L(Ce, this).globalObject;
  },
  get name() {
    return L(Ce, this).name;
  },
  /**
   * @param {string} source is a JavaScript program grammar construction.
   * @param {object} [options]
   * @param {Array<import('./lockdown-shim').Transform>} [options.transforms]
   * @param {boolean} [options.sloppyGlobalsMode]
   * @param {object} [options.__moduleShimLexicals__]
   * @param {boolean} [options.__evadeHtmlCommentTest__]
   * @param {boolean} [options.__evadeImportExpressionTest__]
   * @param {boolean} [options.__rejectSomeDirectEvalExpressions__]
   */
  evaluate(t, e = {}) {
    const r = L(Ce, this);
    return Ts(r, t, e);
  },
  module(t) {
    if (typeof t != "string")
      throw v("first argument of module() must be a string");
    lr(this);
    const { exportsProxy: e } = Dn(
      this,
      L(Ce, this),
      bt,
      t
    );
    return e;
  },
  async import(t) {
    if (typeof t != "string")
      throw v("first argument of import() must be a string");
    return lr(this), Uo(
      fo(Ce, bt, this, t),
      () => ({ namespace: po(
        /** @type {Compartment} */
        this,
        t
      ) })
    );
  },
  async load(t) {
    if (typeof t != "string")
      throw v("first argument of load() must be a string");
    return lr(this), fo(Ce, bt, this, t);
  },
  importNow(t) {
    if (typeof t != "string")
      throw v("first argument of importNow() must be a string");
    return lr(this), ec(Ce, bt, this, t), po(
      /** @type {Compartment} */
      this,
      t
    );
  }
};
F(jn, {
  [qe]: {
    value: "Compartment",
    writable: !1,
    enumerable: !1,
    configurable: !0
  }
});
F(Un, {
  prototype: { value: jn }
});
const dn = (t, e, r) => {
  function n(o = {}, a = {}, i = {}) {
    if (new.target === void 0)
      throw v(
        "Class constructor Compartment cannot be invoked without 'new'"
      );
    const {
      name: c = "<unknown>",
      transforms: l = [],
      __shimTransforms__: u = [],
      resolveHook: d,
      importHook: f,
      importNowHook: h,
      moduleMapHook: p,
      importMetaHook: m
    } = i, _ = [...l, ...u], S = new Pe(), T = new Pe(), N = new Pe();
    for (const [G, B] of re(a || {})) {
      if (typeof B == "string")
        throw v(
          `Cannot map module ${Xr(G)} to ${Xr(
            B
          )} in parent compartment`
        );
      if (L(bt, B) === void 0)
        throw lt(
          `Cannot map module ${Xr(
            G
          )} because it has no known compartment in this realm`
        );
    }
    const x = {};
    li(x), cs(x);
    const { safeEvaluate: D } = On({
      globalObject: x,
      globalTransforms: _,
      sloppyGlobalsMode: !1
    });
    ls(x, {
      intrinsics: e,
      newGlobalPropertyNames: rs,
      makeCompartmentConstructor: t,
      markVirtualizedNativeFunction: r
    }), ln(
      x,
      D,
      r
    ), $r(x, o), ie(Ce, this, {
      name: `${c}`,
      globalTransforms: _,
      globalObject: x,
      safeEvaluate: D,
      resolveHook: d,
      importHook: f,
      importNowHook: h,
      moduleMap: a,
      moduleMapHook: p,
      importMetaHook: m,
      moduleRecords: S,
      __shimTransforms__: u,
      deferredExports: N,
      instances: T
    });
  }
  return n.prototype = jn, n;
};
function Qr(t) {
  return j(t).constructor;
}
function dc() {
  return arguments;
}
const fc = () => {
  const t = ve.prototype.constructor, e = J(dc(), "callee"), r = e && e.get, n = _a(new pe()), o = j(n), a = Rr[Po] && ga(/./), i = a && j(a), c = da([]), l = j(c), u = j(Vs), d = ha(new Pe()), f = j(d), h = ma(new Ct()), p = j(h), m = j(l);
  function* _() {
  }
  const S = Qr(_), T = S.prototype;
  async function* N() {
  }
  const x = Qr(
    N
  ), D = x.prototype, G = D.prototype, B = j(G);
  async function K() {
  }
  const ze = Qr(K), he = {
    "%InertFunction%": t,
    "%ArrayIteratorPrototype%": l,
    "%InertAsyncFunction%": ze,
    "%AsyncGenerator%": D,
    "%InertAsyncGeneratorFunction%": x,
    "%AsyncGeneratorPrototype%": G,
    "%AsyncIteratorPrototype%": B,
    "%Generator%": T,
    "%InertGeneratorFunction%": S,
    "%IteratorPrototype%": m,
    "%MapIteratorPrototype%": f,
    "%RegExpStringIteratorPrototype%": i,
    "%SetIteratorPrototype%": p,
    "%StringIteratorPrototype%": o,
    "%ThrowTypeError%": r,
    "%TypedArray%": u,
    "%InertCompartment%": Un
  };
  return k.Iterator && (he["%IteratorHelperPrototype%"] = j(
    // eslint-disable-next-line @endo/no-polymorphic-call
    k.Iterator.from([]).take(0)
  ), he["%WrapForValidIteratorPrototype%"] = j(
    // eslint-disable-next-line @endo/no-polymorphic-call
    k.Iterator.from({ next() {
    } })
  )), k.AsyncIterator && (he["%AsyncIteratorHelperPrototype%"] = j(
    // eslint-disable-next-line @endo/no-polymorphic-call
    k.AsyncIterator.from([]).take(0)
  ), he["%WrapForValidAsyncIteratorPrototype%"] = j(
    // eslint-disable-next-line @endo/no-polymorphic-call
    k.AsyncIterator.from({ next() {
    } })
  )), he;
}, Is = (t, e) => {
  if (e !== "safe" && e !== "unsafe")
    throw v(`unrecognized fakeHardenOption ${e}`);
  if (e === "safe" || (Object.isExtensible = () => !1, Object.isFrozen = () => !0, Object.isSealed = () => !0, Reflect.isExtensible = () => !1, t.isFake))
    return t;
  const r = (n) => n;
  return r.isFake = !0, y(r);
};
y(Is);
const pc = () => {
  const t = St, e = t.prototype, r = Sa(St, void 0);
  F(e, {
    constructor: {
      value: r
      // leave other `constructor` attributes as is
    }
  });
  const n = re(
    Ze(t)
  ), o = mt(
    se(n, ([a, i]) => [
      a,
      { ...i, configurable: !0 }
    ])
  );
  return F(r, o), { "%SharedSymbol%": r };
}, hc = (t) => {
  try {
    return t(), !1;
  } catch {
    return !0;
  }
}, ho = (t, e, r) => {
  if (t === void 0)
    return !1;
  const n = J(t, e);
  if (!n || "value" in n)
    return !1;
  const { get: o, set: a } = n;
  if (typeof o != "function" || typeof a != "function" || o() !== r || ne(o, t, []) !== r)
    return !1;
  const i = "Seems to be a setter", c = { __proto__: null };
  if (ne(a, c, [i]), c[e] !== i)
    return !1;
  const l = { __proto__: t };
  return ne(a, l, [i]), l[e] !== i || !hc(() => ne(a, t, [r])) || "originalValue" in o || n.configurable === !1 ? !1 : (M(t, e, {
    value: r,
    writable: !0,
    enumerable: n.enumerable,
    configurable: !0
  }), !0);
}, mc = (t) => {
  ho(
    t["%IteratorPrototype%"],
    "constructor",
    t.Iterator
  ), ho(
    t["%IteratorPrototype%"],
    qe,
    "Iterator"
  );
}, { Fail: mo, details: go, quote: yo } = z;
let ur, dr;
const gc = Ga(), yc = () => {
  let t = !1;
  try {
    t = ve(
      "eval",
      "SES_changed",
      `        eval("SES_changed = true");
        return SES_changed;
      `
    )(jo, !1), t || delete k.SES_changed;
  } catch {
    t = !0;
  }
  if (!t)
    throw v(
      "SES cannot initialize unless 'eval' is the original intrinsic 'eval', suitable for direct-eval (dynamically scoped eval) (SES_DIRECT_EVAL)"
    );
}, Cs = (t = {}) => {
  const {
    errorTaming: e = le("LOCKDOWN_ERROR_TAMING", "safe"),
    errorTrapping: r = (
      /** @type {"platform" | "none" | "report" | "abort" | "exit" | undefined} */
      le("LOCKDOWN_ERROR_TRAPPING", "platform")
    ),
    unhandledRejectionTrapping: n = (
      /** @type {"none" | "report" | undefined} */
      le("LOCKDOWN_UNHANDLED_REJECTION_TRAPPING", "report")
    ),
    regExpTaming: o = le("LOCKDOWN_REGEXP_TAMING", "safe"),
    localeTaming: a = le("LOCKDOWN_LOCALE_TAMING", "safe"),
    consoleTaming: i = (
      /** @type {'unsafe' | 'safe' | undefined} */
      le("LOCKDOWN_CONSOLE_TAMING", "safe")
    ),
    overrideTaming: c = le("LOCKDOWN_OVERRIDE_TAMING", "moderate"),
    stackFiltering: l = le("LOCKDOWN_STACK_FILTERING", "concise"),
    domainTaming: u = le("LOCKDOWN_DOMAIN_TAMING", "safe"),
    evalTaming: d = le("LOCKDOWN_EVAL_TAMING", "safeEval"),
    overrideDebug: f = Ke(
      Tn(le("LOCKDOWN_OVERRIDE_DEBUG", ""), ","),
      /** @param {string} debugName */
      (Be) => Be !== ""
    ),
    __hardenTaming__: h = le("LOCKDOWN_HARDEN_TAMING", "safe"),
    dateTaming: p = "safe",
    // deprecated
    mathTaming: m = "safe",
    // deprecated
    ..._
  } = t;
  d === "unsafeEval" || d === "safeEval" || d === "noEval" || mo`lockdown(): non supported option evalTaming: ${yo(d)}`;
  const S = De(_);
  if (S.length === 0 || mo`lockdown(): non supported option ${yo(S)}`, ur === void 0 || // eslint-disable-next-line @endo/no-polymorphic-call
  z.fail(
    go`Already locked down at ${ur} (SES_ALREADY_LOCKED_DOWN)`,
    v
  ), ur = v("Prior lockdown (SES_ALREADY_LOCKED_DOWN)"), ur.stack, yc(), k.Function.prototype.constructor !== k.Function && // @ts-ignore harden is absent on globalThis type def.
  typeof k.harden == "function" && // @ts-ignore lockdown is absent on globalThis type def.
  typeof k.lockdown == "function" && k.Date.prototype.constructor !== k.Date && typeof k.Date.now == "function" && // @ts-ignore does not recognize that Date constructor is a special
  // Function.
  // eslint-disable-next-line @endo/no-polymorphic-call
  Nr(k.Date.prototype.constructor.now(), NaN))
    throw v(
      "Already locked down but not by this SES instance (SES_MULTIPLE_INSTANCES)"
    );
  Ei(u);
  const N = Es(), { addIntrinsics: x, completePrototypes: D, finalIntrinsics: G } = ss(), B = Is(gc, h);
  x({ harden: B }), x(Ya()), x(Ja(p)), x(Gi(e, l)), x(Xa(m)), x(Qa(o)), x(pc()), x(fc()), D();
  const K = G(), ze = { __proto__: null };
  typeof k.Buffer == "function" && (ze.Buffer = k.Buffer);
  let he;
  e !== "unsafe" && (he = K["%InitialGetStackString%"]);
  const Ge = Ti(
    i,
    r,
    n,
    he
  );
  if (k.console = /** @type {Console} */
  Ge.console, typeof /** @type {any} */
  Ge.console._times == "object" && (ze.SafeMap = j(
    // eslint-disable-next-line no-underscore-dangle
    /** @type {any} */
    Ge.console._times
  )), e === "unsafe" && k.assert === z && (k.assert = jr(void 0, !0)), ai(K, a), mc(K), Ka(K, N), cs(k), ls(k, {
    intrinsics: K,
    newGlobalPropertyNames: Jn,
    makeCompartmentConstructor: dn,
    markVirtualizedNativeFunction: N
  }), d === "noEval")
    ln(
      k,
      xa,
      N
    );
  else if (d === "safeEval") {
    const { safeEvaluate: Be } = On({ globalObject: k });
    ln(
      k,
      Be,
      N
    );
  }
  return () => {
    dr === void 0 || // eslint-disable-next-line @endo/no-polymorphic-call
    z.fail(
      go`Already locked down at ${dr} (SES_ALREADY_LOCKED_DOWN)`,
      v
    ), dr = v(
      "Prior lockdown (SES_ALREADY_LOCKED_DOWN)"
    ), dr.stack, ri(K, c, f);
    const Be = {
      intrinsics: K,
      hostIntrinsics: ze,
      globals: {
        // Harden evaluators
        Function: k.Function,
        eval: k.eval,
        // @ts-ignore Compartment does exist on globalThis
        Compartment: k.Compartment,
        // Harden Symbol
        Symbol: k.Symbol
      }
    };
    for (const ar of Dt(Jn))
      Be.globals[ar] = k[ar];
    return B(Be), B;
  };
};
k.lockdown = (t) => {
  const e = Cs(t);
  k.harden = e();
};
k.repairIntrinsics = (t) => {
  const e = Cs(t);
  k.hardenIntrinsics = () => {
    k.harden = e();
  };
};
const vc = Es();
k.Compartment = dn(
  dn,
  qa(k),
  vc
);
k.assert = z;
const _c = ks(br), bc = ta(
  "MAKE_CAUSAL_CONSOLE_FROM_LOGGER_KEY_FOR_SES_AVA"
);
k[bc] = _c;
const wc = (t, e) => {
  let r = { x: 0, y: 0 }, n = { x: 0, y: 0 }, o = { x: 0, y: 0 };
  const a = (l) => {
    const { clientX: u, clientY: d } = l, f = u - o.x + n.x, h = d - o.y + n.y;
    r = { x: f, y: h }, t.style.transform = `translate(${f}px, ${h}px)`, e == null || e();
  }, i = () => {
    document.removeEventListener("mousemove", a), document.removeEventListener("mouseup", i);
  }, c = (l) => {
    o = { x: l.clientX, y: l.clientY }, n = { x: r.x, y: r.y }, document.addEventListener("mousemove", a), document.addEventListener("mouseup", i);
  };
  return t.addEventListener("mousedown", c), i;
}, Sc = ":host{--spacing-4: .25rem;--spacing-8: calc(var(--spacing-4) * 2);--spacing-12: calc(var(--spacing-4) * 3);--spacing-16: calc(var(--spacing-4) * 4);--spacing-20: calc(var(--spacing-4) * 5);--spacing-24: calc(var(--spacing-4) * 6);--spacing-28: calc(var(--spacing-4) * 7);--spacing-32: calc(var(--spacing-4) * 8);--spacing-36: calc(var(--spacing-4) * 9);--spacing-40: calc(var(--spacing-4) * 10);--font-weight-regular: 400;--font-weight-bold: 500;--font-line-height-s: 1.2;--font-line-height-m: 1.4;--font-line-height-l: 1.5;--font-size-s: 12px;--font-size-m: 14px;--font-size-l: 16px}[data-theme]{background-color:var(--color-background-primary);color:var(--color-foreground-secondary)}.wrapper{box-sizing:border-box;display:flex;flex-direction:column;position:fixed;inset-block-start:var(--modal-block-start);inset-inline-end:var(--modal-inline-end);z-index:1000;padding:25px;border-radius:15px;border:2px solid var(--color-background-quaternary);box-shadow:0 0 10px #0000004d}.header{align-items:center;display:flex;justify-content:space-between;border-block-end:2px solid var(--color-background-quaternary);padding-block-end:var(--spacing-4)}button{background:transparent;border:0;cursor:pointer;padding:0}h1{font-size:var(--font-size-s);font-weight:var(--font-weight-bold);margin:0;margin-inline-end:var(--spacing-4);-webkit-user-select:none;user-select:none}iframe{border:none;inline-size:100%;block-size:100%}", Ec = `
<svg width="16"  height="16"xmlns="http://www.w3.org/2000/svg" fill="none"><g class="fills"><rect rx="0" ry="0" width="16" height="16" class="frame-background"/></g><g class="frame-children"><path d="M11.997 3.997 8 8l-3.997 4.003m-.006-8L8 8l4.003 3.997" class="fills"/><g class="strokes"><path d="M11.997 3.997 8 8l-3.997 4.003m-.006-8L8 8l4.003 3.997" style="fill: none; stroke-width: 1; stroke: rgb(143, 157, 163); stroke-opacity: 1; stroke-linecap: round;" class="stroke-shape"/></g></g></svg>`;
var de, er;
class xc extends HTMLElement {
  constructor() {
    super();
    Gr(this, de, null);
    Gr(this, er, null);
    this.attachShadow({ mode: "open" });
  }
  setTheme(r) {
    Ee(this, de) && Ee(this, de).setAttribute("data-theme", r);
  }
  disconnectedCallback() {
    var r;
    (r = Ee(this, er)) == null || r.call(this);
  }
  calculateZIndex() {
    const r = document.querySelectorAll("plugin-modal"), n = Array.from(r).filter((a) => a !== this).map((a) => Number(a.style.zIndex)), o = Math.max(...n, 0);
    this.style.zIndex = (o + 1).toString();
  }
  connectedCallback() {
    const r = this.getAttribute("title"), n = this.getAttribute("iframe-src"), o = Number(this.getAttribute("width") || "300"), a = Number(this.getAttribute("height") || "400");
    if (!r || !n)
      throw new Error("title and iframe-src attributes are required");
    if (!this.shadowRoot)
      throw new Error("Error creating shadow root");
    Br(this, de, document.createElement("div")), Ee(this, de).classList.add("wrapper"), Ee(this, de).style.inlineSize = `${o}px`, Ee(this, de).style.blockSize = `${a}px`, Br(this, er, wc(Ee(this, de), () => {
      this.calculateZIndex();
    }));
    const i = document.createElement("div");
    i.classList.add("header");
    const c = document.createElement("h1");
    c.textContent = r, i.appendChild(c);
    const l = document.createElement("button");
    l.setAttribute("type", "button"), l.innerHTML = `<div class="close">${Ec}</div>`, l.addEventListener("click", () => {
      this.shadowRoot && this.shadowRoot.dispatchEvent(
        new CustomEvent("close", {
          composed: !0,
          bubbles: !0
        })
      );
    }), i.appendChild(l);
    const u = document.createElement("iframe");
    u.src = n, u.allow = "", u.sandbox.add(
      "allow-scripts",
      "allow-forms",
      "allow-modals",
      "allow-popups",
      "allow-popups-to-escape-sandbox",
      "allow-storage-access-by-user-activation"
    ), this.addEventListener("message", (f) => {
      u.contentWindow && u.contentWindow.postMessage(f.detail, "*");
    }), this.shadowRoot.appendChild(Ee(this, de)), Ee(this, de).appendChild(i), Ee(this, de).appendChild(u);
    const d = document.createElement("style");
    d.textContent = Sc, this.shadowRoot.appendChild(d), this.calculateZIndex();
  }
}
de = new WeakMap(), er = new WeakMap();
customElements.define("plugin-modal", xc);
var O;
(function(t) {
  t.assertEqual = (o) => o;
  function e(o) {
  }
  t.assertIs = e;
  function r(o) {
    throw new Error();
  }
  t.assertNever = r, t.arrayToEnum = (o) => {
    const a = {};
    for (const i of o)
      a[i] = i;
    return a;
  }, t.getValidEnumValues = (o) => {
    const a = t.objectKeys(o).filter((c) => typeof o[o[c]] != "number"), i = {};
    for (const c of a)
      i[c] = o[c];
    return t.objectValues(i);
  }, t.objectValues = (o) => t.objectKeys(o).map(function(a) {
    return o[a];
  }), t.objectKeys = typeof Object.keys == "function" ? (o) => Object.keys(o) : (o) => {
    const a = [];
    for (const i in o)
      Object.prototype.hasOwnProperty.call(o, i) && a.push(i);
    return a;
  }, t.find = (o, a) => {
    for (const i of o)
      if (a(i))
        return i;
  }, t.isInteger = typeof Number.isInteger == "function" ? (o) => Number.isInteger(o) : (o) => typeof o == "number" && isFinite(o) && Math.floor(o) === o;
  function n(o, a = " | ") {
    return o.map((i) => typeof i == "string" ? `'${i}'` : i).join(a);
  }
  t.joinValues = n, t.jsonStringifyReplacer = (o, a) => typeof a == "bigint" ? a.toString() : a;
})(O || (O = {}));
var fn;
(function(t) {
  t.mergeShapes = (e, r) => ({
    ...e,
    ...r
    // second overwrites first
  });
})(fn || (fn = {}));
const w = O.arrayToEnum([
  "string",
  "nan",
  "number",
  "integer",
  "float",
  "boolean",
  "date",
  "bigint",
  "symbol",
  "function",
  "undefined",
  "null",
  "array",
  "object",
  "unknown",
  "promise",
  "void",
  "never",
  "map",
  "set"
]), Ve = (t) => {
  switch (typeof t) {
    case "undefined":
      return w.undefined;
    case "string":
      return w.string;
    case "number":
      return isNaN(t) ? w.nan : w.number;
    case "boolean":
      return w.boolean;
    case "function":
      return w.function;
    case "bigint":
      return w.bigint;
    case "symbol":
      return w.symbol;
    case "object":
      return Array.isArray(t) ? w.array : t === null ? w.null : t.then && typeof t.then == "function" && t.catch && typeof t.catch == "function" ? w.promise : typeof Map < "u" && t instanceof Map ? w.map : typeof Set < "u" && t instanceof Set ? w.set : typeof Date < "u" && t instanceof Date ? w.date : w.object;
    default:
      return w.unknown;
  }
}, g = O.arrayToEnum([
  "invalid_type",
  "invalid_literal",
  "custom",
  "invalid_union",
  "invalid_union_discriminator",
  "invalid_enum_value",
  "unrecognized_keys",
  "invalid_arguments",
  "invalid_return_type",
  "invalid_date",
  "invalid_string",
  "too_small",
  "too_big",
  "invalid_intersection_types",
  "not_multiple_of",
  "not_finite"
]), kc = (t) => JSON.stringify(t, null, 2).replace(/"([^"]+)":/g, "$1:");
class fe extends Error {
  constructor(e) {
    super(), this.issues = [], this.addIssue = (n) => {
      this.issues = [...this.issues, n];
    }, this.addIssues = (n = []) => {
      this.issues = [...this.issues, ...n];
    };
    const r = new.target.prototype;
    Object.setPrototypeOf ? Object.setPrototypeOf(this, r) : this.__proto__ = r, this.name = "ZodError", this.issues = e;
  }
  get errors() {
    return this.issues;
  }
  format(e) {
    const r = e || function(a) {
      return a.message;
    }, n = { _errors: [] }, o = (a) => {
      for (const i of a.issues)
        if (i.code === "invalid_union")
          i.unionErrors.map(o);
        else if (i.code === "invalid_return_type")
          o(i.returnTypeError);
        else if (i.code === "invalid_arguments")
          o(i.argumentsError);
        else if (i.path.length === 0)
          n._errors.push(r(i));
        else {
          let c = n, l = 0;
          for (; l < i.path.length; ) {
            const u = i.path[l];
            l === i.path.length - 1 ? (c[u] = c[u] || { _errors: [] }, c[u]._errors.push(r(i))) : c[u] = c[u] || { _errors: [] }, c = c[u], l++;
          }
        }
    };
    return o(this), n;
  }
  static assert(e) {
    if (!(e instanceof fe))
      throw new Error(`Not a ZodError: ${e}`);
  }
  toString() {
    return this.message;
  }
  get message() {
    return JSON.stringify(this.issues, O.jsonStringifyReplacer, 2);
  }
  get isEmpty() {
    return this.issues.length === 0;
  }
  flatten(e = (r) => r.message) {
    const r = {}, n = [];
    for (const o of this.issues)
      o.path.length > 0 ? (r[o.path[0]] = r[o.path[0]] || [], r[o.path[0]].push(e(o))) : n.push(e(o));
    return { formErrors: n, fieldErrors: r };
  }
  get formErrors() {
    return this.flatten();
  }
}
fe.create = (t) => new fe(t);
const Tt = (t, e) => {
  let r;
  switch (t.code) {
    case g.invalid_type:
      t.received === w.undefined ? r = "Required" : r = `Expected ${t.expected}, received ${t.received}`;
      break;
    case g.invalid_literal:
      r = `Invalid literal value, expected ${JSON.stringify(t.expected, O.jsonStringifyReplacer)}`;
      break;
    case g.unrecognized_keys:
      r = `Unrecognized key(s) in object: ${O.joinValues(t.keys, ", ")}`;
      break;
    case g.invalid_union:
      r = "Invalid input";
      break;
    case g.invalid_union_discriminator:
      r = `Invalid discriminator value. Expected ${O.joinValues(t.options)}`;
      break;
    case g.invalid_enum_value:
      r = `Invalid enum value. Expected ${O.joinValues(t.options)}, received '${t.received}'`;
      break;
    case g.invalid_arguments:
      r = "Invalid function arguments";
      break;
    case g.invalid_return_type:
      r = "Invalid function return type";
      break;
    case g.invalid_date:
      r = "Invalid date";
      break;
    case g.invalid_string:
      typeof t.validation == "object" ? "includes" in t.validation ? (r = `Invalid input: must include "${t.validation.includes}"`, typeof t.validation.position == "number" && (r = `${r} at one or more positions greater than or equal to ${t.validation.position}`)) : "startsWith" in t.validation ? r = `Invalid input: must start with "${t.validation.startsWith}"` : "endsWith" in t.validation ? r = `Invalid input: must end with "${t.validation.endsWith}"` : O.assertNever(t.validation) : t.validation !== "regex" ? r = `Invalid ${t.validation}` : r = "Invalid";
      break;
    case g.too_small:
      t.type === "array" ? r = `Array must contain ${t.exact ? "exactly" : t.inclusive ? "at least" : "more than"} ${t.minimum} element(s)` : t.type === "string" ? r = `String must contain ${t.exact ? "exactly" : t.inclusive ? "at least" : "over"} ${t.minimum} character(s)` : t.type === "number" ? r = `Number must be ${t.exact ? "exactly equal to " : t.inclusive ? "greater than or equal to " : "greater than "}${t.minimum}` : t.type === "date" ? r = `Date must be ${t.exact ? "exactly equal to " : t.inclusive ? "greater than or equal to " : "greater than "}${new Date(Number(t.minimum))}` : r = "Invalid input";
      break;
    case g.too_big:
      t.type === "array" ? r = `Array must contain ${t.exact ? "exactly" : t.inclusive ? "at most" : "less than"} ${t.maximum} element(s)` : t.type === "string" ? r = `String must contain ${t.exact ? "exactly" : t.inclusive ? "at most" : "under"} ${t.maximum} character(s)` : t.type === "number" ? r = `Number must be ${t.exact ? "exactly" : t.inclusive ? "less than or equal to" : "less than"} ${t.maximum}` : t.type === "bigint" ? r = `BigInt must be ${t.exact ? "exactly" : t.inclusive ? "less than or equal to" : "less than"} ${t.maximum}` : t.type === "date" ? r = `Date must be ${t.exact ? "exactly" : t.inclusive ? "smaller than or equal to" : "smaller than"} ${new Date(Number(t.maximum))}` : r = "Invalid input";
      break;
    case g.custom:
      r = "Invalid input";
      break;
    case g.invalid_intersection_types:
      r = "Intersection results could not be merged";
      break;
    case g.not_multiple_of:
      r = `Number must be a multiple of ${t.multipleOf}`;
      break;
    case g.not_finite:
      r = "Number must be finite";
      break;
    default:
      r = e.defaultError, O.assertNever(t);
  }
  return { message: r };
};
let $s = Tt;
function Pc(t) {
  $s = t;
}
function Er() {
  return $s;
}
const xr = (t) => {
  const { data: e, path: r, errorMaps: n, issueData: o } = t, a = [...r, ...o.path || []], i = {
    ...o,
    path: a
  };
  if (o.message !== void 0)
    return {
      ...o,
      path: a,
      message: o.message
    };
  let c = "";
  const l = n.filter((u) => !!u).slice().reverse();
  for (const u of l)
    c = u(i, { data: e, defaultError: c }).message;
  return {
    ...o,
    path: a,
    message: c
  };
}, Tc = [];
function b(t, e) {
  const r = Er(), n = xr({
    issueData: e,
    data: t.data,
    path: t.path,
    errorMaps: [
      t.common.contextualErrorMap,
      t.schemaErrorMap,
      r,
      r === Tt ? void 0 : Tt
      // then global default map
    ].filter((o) => !!o)
  });
  t.common.issues.push(n);
}
class Q {
  constructor() {
    this.value = "valid";
  }
  dirty() {
    this.value === "valid" && (this.value = "dirty");
  }
  abort() {
    this.value !== "aborted" && (this.value = "aborted");
  }
  static mergeArray(e, r) {
    const n = [];
    for (const o of r) {
      if (o.status === "aborted")
        return I;
      o.status === "dirty" && e.dirty(), n.push(o.value);
    }
    return { status: e.value, value: n };
  }
  static async mergeObjectAsync(e, r) {
    const n = [];
    for (const o of r) {
      const a = await o.key, i = await o.value;
      n.push({
        key: a,
        value: i
      });
    }
    return Q.mergeObjectSync(e, n);
  }
  static mergeObjectSync(e, r) {
    const n = {};
    for (const o of r) {
      const { key: a, value: i } = o;
      if (a.status === "aborted" || i.status === "aborted")
        return I;
      a.status === "dirty" && e.dirty(), i.status === "dirty" && e.dirty(), a.value !== "__proto__" && (typeof i.value < "u" || o.alwaysSet) && (n[a.value] = i.value);
    }
    return { status: e.value, value: n };
  }
}
const I = Object.freeze({
  status: "aborted"
}), wt = (t) => ({ status: "dirty", value: t }), ae = (t) => ({ status: "valid", value: t }), pn = (t) => t.status === "aborted", hn = (t) => t.status === "dirty", jt = (t) => t.status === "valid", Zt = (t) => typeof Promise < "u" && t instanceof Promise;
function kr(t, e, r, n) {
  if (typeof e == "function" ? t !== e || !n : !e.has(t))
    throw new TypeError("Cannot read private member from an object whose class did not declare it");
  return e.get(t);
}
function Ns(t, e, r, n, o) {
  if (typeof e == "function" ? t !== e || !o : !e.has(t))
    throw new TypeError("Cannot write private member to an object whose class did not declare it");
  return e.set(t, r), r;
}
var E;
(function(t) {
  t.errToObj = (e) => typeof e == "string" ? { message: e } : e || {}, t.toString = (e) => typeof e == "string" ? e : e == null ? void 0 : e.message;
})(E || (E = {}));
var Lt, Ft;
class Re {
  constructor(e, r, n, o) {
    this._cachedPath = [], this.parent = e, this.data = r, this._path = n, this._key = o;
  }
  get path() {
    return this._cachedPath.length || (this._key instanceof Array ? this._cachedPath.push(...this._path, ...this._key) : this._cachedPath.push(...this._path, this._key)), this._cachedPath;
  }
}
const vo = (t, e) => {
  if (jt(e))
    return { success: !0, data: e.value };
  if (!t.common.issues.length)
    throw new Error("Validation failed but no issues detected.");
  return {
    success: !1,
    get error() {
      if (this._error)
        return this._error;
      const r = new fe(t.common.issues);
      return this._error = r, this._error;
    }
  };
};
function C(t) {
  if (!t)
    return {};
  const { errorMap: e, invalid_type_error: r, required_error: n, description: o } = t;
  if (e && (r || n))
    throw new Error(`Can't use "invalid_type_error" or "required_error" in conjunction with custom error map.`);
  return e ? { errorMap: e, description: o } : { errorMap: (i, c) => {
    var l, u;
    const { message: d } = t;
    return i.code === "invalid_enum_value" ? { message: d ?? c.defaultError } : typeof c.data > "u" ? { message: (l = d ?? n) !== null && l !== void 0 ? l : c.defaultError } : i.code !== "invalid_type" ? { message: c.defaultError } : { message: (u = d ?? r) !== null && u !== void 0 ? u : c.defaultError };
  }, description: o };
}
class $ {
  constructor(e) {
    this.spa = this.safeParseAsync, this._def = e, this.parse = this.parse.bind(this), this.safeParse = this.safeParse.bind(this), this.parseAsync = this.parseAsync.bind(this), this.safeParseAsync = this.safeParseAsync.bind(this), this.spa = this.spa.bind(this), this.refine = this.refine.bind(this), this.refinement = this.refinement.bind(this), this.superRefine = this.superRefine.bind(this), this.optional = this.optional.bind(this), this.nullable = this.nullable.bind(this), this.nullish = this.nullish.bind(this), this.array = this.array.bind(this), this.promise = this.promise.bind(this), this.or = this.or.bind(this), this.and = this.and.bind(this), this.transform = this.transform.bind(this), this.brand = this.brand.bind(this), this.default = this.default.bind(this), this.catch = this.catch.bind(this), this.describe = this.describe.bind(this), this.pipe = this.pipe.bind(this), this.readonly = this.readonly.bind(this), this.isNullable = this.isNullable.bind(this), this.isOptional = this.isOptional.bind(this);
  }
  get description() {
    return this._def.description;
  }
  _getType(e) {
    return Ve(e.data);
  }
  _getOrReturnCtx(e, r) {
    return r || {
      common: e.parent.common,
      data: e.data,
      parsedType: Ve(e.data),
      schemaErrorMap: this._def.errorMap,
      path: e.path,
      parent: e.parent
    };
  }
  _processInputParams(e) {
    return {
      status: new Q(),
      ctx: {
        common: e.parent.common,
        data: e.data,
        parsedType: Ve(e.data),
        schemaErrorMap: this._def.errorMap,
        path: e.path,
        parent: e.parent
      }
    };
  }
  _parseSync(e) {
    const r = this._parse(e);
    if (Zt(r))
      throw new Error("Synchronous parse encountered promise.");
    return r;
  }
  _parseAsync(e) {
    const r = this._parse(e);
    return Promise.resolve(r);
  }
  parse(e, r) {
    const n = this.safeParse(e, r);
    if (n.success)
      return n.data;
    throw n.error;
  }
  safeParse(e, r) {
    var n;
    const o = {
      common: {
        issues: [],
        async: (n = r == null ? void 0 : r.async) !== null && n !== void 0 ? n : !1,
        contextualErrorMap: r == null ? void 0 : r.errorMap
      },
      path: (r == null ? void 0 : r.path) || [],
      schemaErrorMap: this._def.errorMap,
      parent: null,
      data: e,
      parsedType: Ve(e)
    }, a = this._parseSync({ data: e, path: o.path, parent: o });
    return vo(o, a);
  }
  async parseAsync(e, r) {
    const n = await this.safeParseAsync(e, r);
    if (n.success)
      return n.data;
    throw n.error;
  }
  async safeParseAsync(e, r) {
    const n = {
      common: {
        issues: [],
        contextualErrorMap: r == null ? void 0 : r.errorMap,
        async: !0
      },
      path: (r == null ? void 0 : r.path) || [],
      schemaErrorMap: this._def.errorMap,
      parent: null,
      data: e,
      parsedType: Ve(e)
    }, o = this._parse({ data: e, path: n.path, parent: n }), a = await (Zt(o) ? o : Promise.resolve(o));
    return vo(n, a);
  }
  refine(e, r) {
    const n = (o) => typeof r == "string" || typeof r > "u" ? { message: r } : typeof r == "function" ? r(o) : r;
    return this._refinement((o, a) => {
      const i = e(o), c = () => a.addIssue({
        code: g.custom,
        ...n(o)
      });
      return typeof Promise < "u" && i instanceof Promise ? i.then((l) => l ? !0 : (c(), !1)) : i ? !0 : (c(), !1);
    });
  }
  refinement(e, r) {
    return this._refinement((n, o) => e(n) ? !0 : (o.addIssue(typeof r == "function" ? r(n, o) : r), !1));
  }
  _refinement(e) {
    return new Ae({
      schema: this,
      typeName: A.ZodEffects,
      effect: { type: "refinement", refinement: e }
    });
  }
  superRefine(e) {
    return this._refinement(e);
  }
  optional() {
    return Ne.create(this, this._def);
  }
  nullable() {
    return tt.create(this, this._def);
  }
  nullish() {
    return this.nullable().optional();
  }
  array() {
    return Te.create(this, this._def);
  }
  promise() {
    return It.create(this, this._def);
  }
  or(e) {
    return Ht.create([this, e], this._def);
  }
  and(e) {
    return Vt.create(this, e, this._def);
  }
  transform(e) {
    return new Ae({
      ...C(this._def),
      schema: this,
      typeName: A.ZodEffects,
      effect: { type: "transform", transform: e }
    });
  }
  default(e) {
    const r = typeof e == "function" ? e : () => e;
    return new Jt({
      ...C(this._def),
      innerType: this,
      defaultValue: r,
      typeName: A.ZodDefault
    });
  }
  brand() {
    return new Zn({
      typeName: A.ZodBranded,
      type: this,
      ...C(this._def)
    });
  }
  catch(e) {
    const r = typeof e == "function" ? e : () => e;
    return new Xt({
      ...C(this._def),
      innerType: this,
      catchValue: r,
      typeName: A.ZodCatch
    });
  }
  describe(e) {
    const r = this.constructor;
    return new r({
      ...this._def,
      description: e
    });
  }
  pipe(e) {
    return sr.create(this, e);
  }
  readonly() {
    return Qt.create(this);
  }
  isOptional() {
    return this.safeParse(void 0).success;
  }
  isNullable() {
    return this.safeParse(null).success;
  }
}
const Ac = /^c[^\s-]{8,}$/i, Ic = /^[0-9a-z]+$/, Cc = /^[0-9A-HJKMNP-TV-Z]{26}$/, $c = /^[0-9a-fA-F]{8}\b-[0-9a-fA-F]{4}\b-[0-9a-fA-F]{4}\b-[0-9a-fA-F]{4}\b-[0-9a-fA-F]{12}$/i, Nc = /^[a-z0-9_-]{21}$/i, Rc = /^[-+]?P(?!$)(?:(?:[-+]?\d+Y)|(?:[-+]?\d+[.,]\d+Y$))?(?:(?:[-+]?\d+M)|(?:[-+]?\d+[.,]\d+M$))?(?:(?:[-+]?\d+W)|(?:[-+]?\d+[.,]\d+W$))?(?:(?:[-+]?\d+D)|(?:[-+]?\d+[.,]\d+D$))?(?:T(?=[\d+-])(?:(?:[-+]?\d+H)|(?:[-+]?\d+[.,]\d+H$))?(?:(?:[-+]?\d+M)|(?:[-+]?\d+[.,]\d+M$))?(?:[-+]?\d+(?:[.,]\d+)?S)?)??$/, Oc = /^(?!\.)(?!.*\.\.)([A-Z0-9_'+\-\.]*)[A-Z0-9_+-]@([A-Z0-9][A-Z0-9\-]*\.)+[A-Z]{2,}$/i, Mc = "^(\\p{Extended_Pictographic}|\\p{Emoji_Component})+$";
let en;
const Lc = /^(?:(?:25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9][0-9]|[0-9])\.){3}(?:25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9][0-9]|[0-9])$/, Fc = /^(([a-f0-9]{1,4}:){7}|::([a-f0-9]{1,4}:){0,6}|([a-f0-9]{1,4}:){1}:([a-f0-9]{1,4}:){0,5}|([a-f0-9]{1,4}:){2}:([a-f0-9]{1,4}:){0,4}|([a-f0-9]{1,4}:){3}:([a-f0-9]{1,4}:){0,3}|([a-f0-9]{1,4}:){4}:([a-f0-9]{1,4}:){0,2}|([a-f0-9]{1,4}:){5}:([a-f0-9]{1,4}:){0,1})([a-f0-9]{1,4}|(((25[0-5])|(2[0-4][0-9])|(1[0-9]{2})|([0-9]{1,2}))\.){3}((25[0-5])|(2[0-4][0-9])|(1[0-9]{2})|([0-9]{1,2})))$/, Dc = /^([0-9a-zA-Z+/]{4})*(([0-9a-zA-Z+/]{2}==)|([0-9a-zA-Z+/]{3}=))?$/, Rs = "((\\d\\d[2468][048]|\\d\\d[13579][26]|\\d\\d0[48]|[02468][048]00|[13579][26]00)-02-29|\\d{4}-((0[13578]|1[02])-(0[1-9]|[12]\\d|3[01])|(0[469]|11)-(0[1-9]|[12]\\d|30)|(02)-(0[1-9]|1\\d|2[0-8])))", Uc = new RegExp(`^${Rs}$`);
function Os(t) {
  let e = "([01]\\d|2[0-3]):[0-5]\\d:[0-5]\\d";
  return t.precision ? e = `${e}\\.\\d{${t.precision}}` : t.precision == null && (e = `${e}(\\.\\d+)?`), e;
}
function jc(t) {
  return new RegExp(`^${Os(t)}$`);
}
function Ms(t) {
  let e = `${Rs}T${Os(t)}`;
  const r = [];
  return r.push(t.local ? "Z?" : "Z"), t.offset && r.push("([+-]\\d{2}:?\\d{2})"), e = `${e}(${r.join("|")})`, new RegExp(`^${e}$`);
}
function Zc(t, e) {
  return !!((e === "v4" || !e) && Lc.test(t) || (e === "v6" || !e) && Fc.test(t));
}
class ke extends $ {
  _parse(e) {
    if (this._def.coerce && (e.data = String(e.data)), this._getType(e) !== w.string) {
      const a = this._getOrReturnCtx(e);
      return b(a, {
        code: g.invalid_type,
        expected: w.string,
        received: a.parsedType
      }), I;
    }
    const n = new Q();
    let o;
    for (const a of this._def.checks)
      if (a.kind === "min")
        e.data.length < a.value && (o = this._getOrReturnCtx(e, o), b(o, {
          code: g.too_small,
          minimum: a.value,
          type: "string",
          inclusive: !0,
          exact: !1,
          message: a.message
        }), n.dirty());
      else if (a.kind === "max")
        e.data.length > a.value && (o = this._getOrReturnCtx(e, o), b(o, {
          code: g.too_big,
          maximum: a.value,
          type: "string",
          inclusive: !0,
          exact: !1,
          message: a.message
        }), n.dirty());
      else if (a.kind === "length") {
        const i = e.data.length > a.value, c = e.data.length < a.value;
        (i || c) && (o = this._getOrReturnCtx(e, o), i ? b(o, {
          code: g.too_big,
          maximum: a.value,
          type: "string",
          inclusive: !0,
          exact: !0,
          message: a.message
        }) : c && b(o, {
          code: g.too_small,
          minimum: a.value,
          type: "string",
          inclusive: !0,
          exact: !0,
          message: a.message
        }), n.dirty());
      } else if (a.kind === "email")
        Oc.test(e.data) || (o = this._getOrReturnCtx(e, o), b(o, {
          validation: "email",
          code: g.invalid_string,
          message: a.message
        }), n.dirty());
      else if (a.kind === "emoji")
        en || (en = new RegExp(Mc, "u")), en.test(e.data) || (o = this._getOrReturnCtx(e, o), b(o, {
          validation: "emoji",
          code: g.invalid_string,
          message: a.message
        }), n.dirty());
      else if (a.kind === "uuid")
        $c.test(e.data) || (o = this._getOrReturnCtx(e, o), b(o, {
          validation: "uuid",
          code: g.invalid_string,
          message: a.message
        }), n.dirty());
      else if (a.kind === "nanoid")
        Nc.test(e.data) || (o = this._getOrReturnCtx(e, o), b(o, {
          validation: "nanoid",
          code: g.invalid_string,
          message: a.message
        }), n.dirty());
      else if (a.kind === "cuid")
        Ac.test(e.data) || (o = this._getOrReturnCtx(e, o), b(o, {
          validation: "cuid",
          code: g.invalid_string,
          message: a.message
        }), n.dirty());
      else if (a.kind === "cuid2")
        Ic.test(e.data) || (o = this._getOrReturnCtx(e, o), b(o, {
          validation: "cuid2",
          code: g.invalid_string,
          message: a.message
        }), n.dirty());
      else if (a.kind === "ulid")
        Cc.test(e.data) || (o = this._getOrReturnCtx(e, o), b(o, {
          validation: "ulid",
          code: g.invalid_string,
          message: a.message
        }), n.dirty());
      else if (a.kind === "url")
        try {
          new URL(e.data);
        } catch {
          o = this._getOrReturnCtx(e, o), b(o, {
            validation: "url",
            code: g.invalid_string,
            message: a.message
          }), n.dirty();
        }
      else
        a.kind === "regex" ? (a.regex.lastIndex = 0, a.regex.test(e.data) || (o = this._getOrReturnCtx(e, o), b(o, {
          validation: "regex",
          code: g.invalid_string,
          message: a.message
        }), n.dirty())) : a.kind === "trim" ? e.data = e.data.trim() : a.kind === "includes" ? e.data.includes(a.value, a.position) || (o = this._getOrReturnCtx(e, o), b(o, {
          code: g.invalid_string,
          validation: { includes: a.value, position: a.position },
          message: a.message
        }), n.dirty()) : a.kind === "toLowerCase" ? e.data = e.data.toLowerCase() : a.kind === "toUpperCase" ? e.data = e.data.toUpperCase() : a.kind === "startsWith" ? e.data.startsWith(a.value) || (o = this._getOrReturnCtx(e, o), b(o, {
          code: g.invalid_string,
          validation: { startsWith: a.value },
          message: a.message
        }), n.dirty()) : a.kind === "endsWith" ? e.data.endsWith(a.value) || (o = this._getOrReturnCtx(e, o), b(o, {
          code: g.invalid_string,
          validation: { endsWith: a.value },
          message: a.message
        }), n.dirty()) : a.kind === "datetime" ? Ms(a).test(e.data) || (o = this._getOrReturnCtx(e, o), b(o, {
          code: g.invalid_string,
          validation: "datetime",
          message: a.message
        }), n.dirty()) : a.kind === "date" ? Uc.test(e.data) || (o = this._getOrReturnCtx(e, o), b(o, {
          code: g.invalid_string,
          validation: "date",
          message: a.message
        }), n.dirty()) : a.kind === "time" ? jc(a).test(e.data) || (o = this._getOrReturnCtx(e, o), b(o, {
          code: g.invalid_string,
          validation: "time",
          message: a.message
        }), n.dirty()) : a.kind === "duration" ? Rc.test(e.data) || (o = this._getOrReturnCtx(e, o), b(o, {
          validation: "duration",
          code: g.invalid_string,
          message: a.message
        }), n.dirty()) : a.kind === "ip" ? Zc(e.data, a.version) || (o = this._getOrReturnCtx(e, o), b(o, {
          validation: "ip",
          code: g.invalid_string,
          message: a.message
        }), n.dirty()) : a.kind === "base64" ? Dc.test(e.data) || (o = this._getOrReturnCtx(e, o), b(o, {
          validation: "base64",
          code: g.invalid_string,
          message: a.message
        }), n.dirty()) : O.assertNever(a);
    return { status: n.value, value: e.data };
  }
  _regex(e, r, n) {
    return this.refinement((o) => e.test(o), {
      validation: r,
      code: g.invalid_string,
      ...E.errToObj(n)
    });
  }
  _addCheck(e) {
    return new ke({
      ...this._def,
      checks: [...this._def.checks, e]
    });
  }
  email(e) {
    return this._addCheck({ kind: "email", ...E.errToObj(e) });
  }
  url(e) {
    return this._addCheck({ kind: "url", ...E.errToObj(e) });
  }
  emoji(e) {
    return this._addCheck({ kind: "emoji", ...E.errToObj(e) });
  }
  uuid(e) {
    return this._addCheck({ kind: "uuid", ...E.errToObj(e) });
  }
  nanoid(e) {
    return this._addCheck({ kind: "nanoid", ...E.errToObj(e) });
  }
  cuid(e) {
    return this._addCheck({ kind: "cuid", ...E.errToObj(e) });
  }
  cuid2(e) {
    return this._addCheck({ kind: "cuid2", ...E.errToObj(e) });
  }
  ulid(e) {
    return this._addCheck({ kind: "ulid", ...E.errToObj(e) });
  }
  base64(e) {
    return this._addCheck({ kind: "base64", ...E.errToObj(e) });
  }
  ip(e) {
    return this._addCheck({ kind: "ip", ...E.errToObj(e) });
  }
  datetime(e) {
    var r, n;
    return typeof e == "string" ? this._addCheck({
      kind: "datetime",
      precision: null,
      offset: !1,
      local: !1,
      message: e
    }) : this._addCheck({
      kind: "datetime",
      precision: typeof (e == null ? void 0 : e.precision) > "u" ? null : e == null ? void 0 : e.precision,
      offset: (r = e == null ? void 0 : e.offset) !== null && r !== void 0 ? r : !1,
      local: (n = e == null ? void 0 : e.local) !== null && n !== void 0 ? n : !1,
      ...E.errToObj(e == null ? void 0 : e.message)
    });
  }
  date(e) {
    return this._addCheck({ kind: "date", message: e });
  }
  time(e) {
    return typeof e == "string" ? this._addCheck({
      kind: "time",
      precision: null,
      message: e
    }) : this._addCheck({
      kind: "time",
      precision: typeof (e == null ? void 0 : e.precision) > "u" ? null : e == null ? void 0 : e.precision,
      ...E.errToObj(e == null ? void 0 : e.message)
    });
  }
  duration(e) {
    return this._addCheck({ kind: "duration", ...E.errToObj(e) });
  }
  regex(e, r) {
    return this._addCheck({
      kind: "regex",
      regex: e,
      ...E.errToObj(r)
    });
  }
  includes(e, r) {
    return this._addCheck({
      kind: "includes",
      value: e,
      position: r == null ? void 0 : r.position,
      ...E.errToObj(r == null ? void 0 : r.message)
    });
  }
  startsWith(e, r) {
    return this._addCheck({
      kind: "startsWith",
      value: e,
      ...E.errToObj(r)
    });
  }
  endsWith(e, r) {
    return this._addCheck({
      kind: "endsWith",
      value: e,
      ...E.errToObj(r)
    });
  }
  min(e, r) {
    return this._addCheck({
      kind: "min",
      value: e,
      ...E.errToObj(r)
    });
  }
  max(e, r) {
    return this._addCheck({
      kind: "max",
      value: e,
      ...E.errToObj(r)
    });
  }
  length(e, r) {
    return this._addCheck({
      kind: "length",
      value: e,
      ...E.errToObj(r)
    });
  }
  /**
   * @deprecated Use z.string().min(1) instead.
   * @see {@link ZodString.min}
   */
  nonempty(e) {
    return this.min(1, E.errToObj(e));
  }
  trim() {
    return new ke({
      ...this._def,
      checks: [...this._def.checks, { kind: "trim" }]
    });
  }
  toLowerCase() {
    return new ke({
      ...this._def,
      checks: [...this._def.checks, { kind: "toLowerCase" }]
    });
  }
  toUpperCase() {
    return new ke({
      ...this._def,
      checks: [...this._def.checks, { kind: "toUpperCase" }]
    });
  }
  get isDatetime() {
    return !!this._def.checks.find((e) => e.kind === "datetime");
  }
  get isDate() {
    return !!this._def.checks.find((e) => e.kind === "date");
  }
  get isTime() {
    return !!this._def.checks.find((e) => e.kind === "time");
  }
  get isDuration() {
    return !!this._def.checks.find((e) => e.kind === "duration");
  }
  get isEmail() {
    return !!this._def.checks.find((e) => e.kind === "email");
  }
  get isURL() {
    return !!this._def.checks.find((e) => e.kind === "url");
  }
  get isEmoji() {
    return !!this._def.checks.find((e) => e.kind === "emoji");
  }
  get isUUID() {
    return !!this._def.checks.find((e) => e.kind === "uuid");
  }
  get isNANOID() {
    return !!this._def.checks.find((e) => e.kind === "nanoid");
  }
  get isCUID() {
    return !!this._def.checks.find((e) => e.kind === "cuid");
  }
  get isCUID2() {
    return !!this._def.checks.find((e) => e.kind === "cuid2");
  }
  get isULID() {
    return !!this._def.checks.find((e) => e.kind === "ulid");
  }
  get isIP() {
    return !!this._def.checks.find((e) => e.kind === "ip");
  }
  get isBase64() {
    return !!this._def.checks.find((e) => e.kind === "base64");
  }
  get minLength() {
    let e = null;
    for (const r of this._def.checks)
      r.kind === "min" && (e === null || r.value > e) && (e = r.value);
    return e;
  }
  get maxLength() {
    let e = null;
    for (const r of this._def.checks)
      r.kind === "max" && (e === null || r.value < e) && (e = r.value);
    return e;
  }
}
ke.create = (t) => {
  var e;
  return new ke({
    checks: [],
    typeName: A.ZodString,
    coerce: (e = t == null ? void 0 : t.coerce) !== null && e !== void 0 ? e : !1,
    ...C(t)
  });
};
function zc(t, e) {
  const r = (t.toString().split(".")[1] || "").length, n = (e.toString().split(".")[1] || "").length, o = r > n ? r : n, a = parseInt(t.toFixed(o).replace(".", "")), i = parseInt(e.toFixed(o).replace(".", ""));
  return a % i / Math.pow(10, o);
}
class Xe extends $ {
  constructor() {
    super(...arguments), this.min = this.gte, this.max = this.lte, this.step = this.multipleOf;
  }
  _parse(e) {
    if (this._def.coerce && (e.data = Number(e.data)), this._getType(e) !== w.number) {
      const a = this._getOrReturnCtx(e);
      return b(a, {
        code: g.invalid_type,
        expected: w.number,
        received: a.parsedType
      }), I;
    }
    let n;
    const o = new Q();
    for (const a of this._def.checks)
      a.kind === "int" ? O.isInteger(e.data) || (n = this._getOrReturnCtx(e, n), b(n, {
        code: g.invalid_type,
        expected: "integer",
        received: "float",
        message: a.message
      }), o.dirty()) : a.kind === "min" ? (a.inclusive ? e.data < a.value : e.data <= a.value) && (n = this._getOrReturnCtx(e, n), b(n, {
        code: g.too_small,
        minimum: a.value,
        type: "number",
        inclusive: a.inclusive,
        exact: !1,
        message: a.message
      }), o.dirty()) : a.kind === "max" ? (a.inclusive ? e.data > a.value : e.data >= a.value) && (n = this._getOrReturnCtx(e, n), b(n, {
        code: g.too_big,
        maximum: a.value,
        type: "number",
        inclusive: a.inclusive,
        exact: !1,
        message: a.message
      }), o.dirty()) : a.kind === "multipleOf" ? zc(e.data, a.value) !== 0 && (n = this._getOrReturnCtx(e, n), b(n, {
        code: g.not_multiple_of,
        multipleOf: a.value,
        message: a.message
      }), o.dirty()) : a.kind === "finite" ? Number.isFinite(e.data) || (n = this._getOrReturnCtx(e, n), b(n, {
        code: g.not_finite,
        message: a.message
      }), o.dirty()) : O.assertNever(a);
    return { status: o.value, value: e.data };
  }
  gte(e, r) {
    return this.setLimit("min", e, !0, E.toString(r));
  }
  gt(e, r) {
    return this.setLimit("min", e, !1, E.toString(r));
  }
  lte(e, r) {
    return this.setLimit("max", e, !0, E.toString(r));
  }
  lt(e, r) {
    return this.setLimit("max", e, !1, E.toString(r));
  }
  setLimit(e, r, n, o) {
    return new Xe({
      ...this._def,
      checks: [
        ...this._def.checks,
        {
          kind: e,
          value: r,
          inclusive: n,
          message: E.toString(o)
        }
      ]
    });
  }
  _addCheck(e) {
    return new Xe({
      ...this._def,
      checks: [...this._def.checks, e]
    });
  }
  int(e) {
    return this._addCheck({
      kind: "int",
      message: E.toString(e)
    });
  }
  positive(e) {
    return this._addCheck({
      kind: "min",
      value: 0,
      inclusive: !1,
      message: E.toString(e)
    });
  }
  negative(e) {
    return this._addCheck({
      kind: "max",
      value: 0,
      inclusive: !1,
      message: E.toString(e)
    });
  }
  nonpositive(e) {
    return this._addCheck({
      kind: "max",
      value: 0,
      inclusive: !0,
      message: E.toString(e)
    });
  }
  nonnegative(e) {
    return this._addCheck({
      kind: "min",
      value: 0,
      inclusive: !0,
      message: E.toString(e)
    });
  }
  multipleOf(e, r) {
    return this._addCheck({
      kind: "multipleOf",
      value: e,
      message: E.toString(r)
    });
  }
  finite(e) {
    return this._addCheck({
      kind: "finite",
      message: E.toString(e)
    });
  }
  safe(e) {
    return this._addCheck({
      kind: "min",
      inclusive: !0,
      value: Number.MIN_SAFE_INTEGER,
      message: E.toString(e)
    })._addCheck({
      kind: "max",
      inclusive: !0,
      value: Number.MAX_SAFE_INTEGER,
      message: E.toString(e)
    });
  }
  get minValue() {
    let e = null;
    for (const r of this._def.checks)
      r.kind === "min" && (e === null || r.value > e) && (e = r.value);
    return e;
  }
  get maxValue() {
    let e = null;
    for (const r of this._def.checks)
      r.kind === "max" && (e === null || r.value < e) && (e = r.value);
    return e;
  }
  get isInt() {
    return !!this._def.checks.find((e) => e.kind === "int" || e.kind === "multipleOf" && O.isInteger(e.value));
  }
  get isFinite() {
    let e = null, r = null;
    for (const n of this._def.checks) {
      if (n.kind === "finite" || n.kind === "int" || n.kind === "multipleOf")
        return !0;
      n.kind === "min" ? (r === null || n.value > r) && (r = n.value) : n.kind === "max" && (e === null || n.value < e) && (e = n.value);
    }
    return Number.isFinite(r) && Number.isFinite(e);
  }
}
Xe.create = (t) => new Xe({
  checks: [],
  typeName: A.ZodNumber,
  coerce: (t == null ? void 0 : t.coerce) || !1,
  ...C(t)
});
class Qe extends $ {
  constructor() {
    super(...arguments), this.min = this.gte, this.max = this.lte;
  }
  _parse(e) {
    if (this._def.coerce && (e.data = BigInt(e.data)), this._getType(e) !== w.bigint) {
      const a = this._getOrReturnCtx(e);
      return b(a, {
        code: g.invalid_type,
        expected: w.bigint,
        received: a.parsedType
      }), I;
    }
    let n;
    const o = new Q();
    for (const a of this._def.checks)
      a.kind === "min" ? (a.inclusive ? e.data < a.value : e.data <= a.value) && (n = this._getOrReturnCtx(e, n), b(n, {
        code: g.too_small,
        type: "bigint",
        minimum: a.value,
        inclusive: a.inclusive,
        message: a.message
      }), o.dirty()) : a.kind === "max" ? (a.inclusive ? e.data > a.value : e.data >= a.value) && (n = this._getOrReturnCtx(e, n), b(n, {
        code: g.too_big,
        type: "bigint",
        maximum: a.value,
        inclusive: a.inclusive,
        message: a.message
      }), o.dirty()) : a.kind === "multipleOf" ? e.data % a.value !== BigInt(0) && (n = this._getOrReturnCtx(e, n), b(n, {
        code: g.not_multiple_of,
        multipleOf: a.value,
        message: a.message
      }), o.dirty()) : O.assertNever(a);
    return { status: o.value, value: e.data };
  }
  gte(e, r) {
    return this.setLimit("min", e, !0, E.toString(r));
  }
  gt(e, r) {
    return this.setLimit("min", e, !1, E.toString(r));
  }
  lte(e, r) {
    return this.setLimit("max", e, !0, E.toString(r));
  }
  lt(e, r) {
    return this.setLimit("max", e, !1, E.toString(r));
  }
  setLimit(e, r, n, o) {
    return new Qe({
      ...this._def,
      checks: [
        ...this._def.checks,
        {
          kind: e,
          value: r,
          inclusive: n,
          message: E.toString(o)
        }
      ]
    });
  }
  _addCheck(e) {
    return new Qe({
      ...this._def,
      checks: [...this._def.checks, e]
    });
  }
  positive(e) {
    return this._addCheck({
      kind: "min",
      value: BigInt(0),
      inclusive: !1,
      message: E.toString(e)
    });
  }
  negative(e) {
    return this._addCheck({
      kind: "max",
      value: BigInt(0),
      inclusive: !1,
      message: E.toString(e)
    });
  }
  nonpositive(e) {
    return this._addCheck({
      kind: "max",
      value: BigInt(0),
      inclusive: !0,
      message: E.toString(e)
    });
  }
  nonnegative(e) {
    return this._addCheck({
      kind: "min",
      value: BigInt(0),
      inclusive: !0,
      message: E.toString(e)
    });
  }
  multipleOf(e, r) {
    return this._addCheck({
      kind: "multipleOf",
      value: e,
      message: E.toString(r)
    });
  }
  get minValue() {
    let e = null;
    for (const r of this._def.checks)
      r.kind === "min" && (e === null || r.value > e) && (e = r.value);
    return e;
  }
  get maxValue() {
    let e = null;
    for (const r of this._def.checks)
      r.kind === "max" && (e === null || r.value < e) && (e = r.value);
    return e;
  }
}
Qe.create = (t) => {
  var e;
  return new Qe({
    checks: [],
    typeName: A.ZodBigInt,
    coerce: (e = t == null ? void 0 : t.coerce) !== null && e !== void 0 ? e : !1,
    ...C(t)
  });
};
class zt extends $ {
  _parse(e) {
    if (this._def.coerce && (e.data = !!e.data), this._getType(e) !== w.boolean) {
      const n = this._getOrReturnCtx(e);
      return b(n, {
        code: g.invalid_type,
        expected: w.boolean,
        received: n.parsedType
      }), I;
    }
    return ae(e.data);
  }
}
zt.create = (t) => new zt({
  typeName: A.ZodBoolean,
  coerce: (t == null ? void 0 : t.coerce) || !1,
  ...C(t)
});
class pt extends $ {
  _parse(e) {
    if (this._def.coerce && (e.data = new Date(e.data)), this._getType(e) !== w.date) {
      const a = this._getOrReturnCtx(e);
      return b(a, {
        code: g.invalid_type,
        expected: w.date,
        received: a.parsedType
      }), I;
    }
    if (isNaN(e.data.getTime())) {
      const a = this._getOrReturnCtx(e);
      return b(a, {
        code: g.invalid_date
      }), I;
    }
    const n = new Q();
    let o;
    for (const a of this._def.checks)
      a.kind === "min" ? e.data.getTime() < a.value && (o = this._getOrReturnCtx(e, o), b(o, {
        code: g.too_small,
        message: a.message,
        inclusive: !0,
        exact: !1,
        minimum: a.value,
        type: "date"
      }), n.dirty()) : a.kind === "max" ? e.data.getTime() > a.value && (o = this._getOrReturnCtx(e, o), b(o, {
        code: g.too_big,
        message: a.message,
        inclusive: !0,
        exact: !1,
        maximum: a.value,
        type: "date"
      }), n.dirty()) : O.assertNever(a);
    return {
      status: n.value,
      value: new Date(e.data.getTime())
    };
  }
  _addCheck(e) {
    return new pt({
      ...this._def,
      checks: [...this._def.checks, e]
    });
  }
  min(e, r) {
    return this._addCheck({
      kind: "min",
      value: e.getTime(),
      message: E.toString(r)
    });
  }
  max(e, r) {
    return this._addCheck({
      kind: "max",
      value: e.getTime(),
      message: E.toString(r)
    });
  }
  get minDate() {
    let e = null;
    for (const r of this._def.checks)
      r.kind === "min" && (e === null || r.value > e) && (e = r.value);
    return e != null ? new Date(e) : null;
  }
  get maxDate() {
    let e = null;
    for (const r of this._def.checks)
      r.kind === "max" && (e === null || r.value < e) && (e = r.value);
    return e != null ? new Date(e) : null;
  }
}
pt.create = (t) => new pt({
  checks: [],
  coerce: (t == null ? void 0 : t.coerce) || !1,
  typeName: A.ZodDate,
  ...C(t)
});
class Pr extends $ {
  _parse(e) {
    if (this._getType(e) !== w.symbol) {
      const n = this._getOrReturnCtx(e);
      return b(n, {
        code: g.invalid_type,
        expected: w.symbol,
        received: n.parsedType
      }), I;
    }
    return ae(e.data);
  }
}
Pr.create = (t) => new Pr({
  typeName: A.ZodSymbol,
  ...C(t)
});
class Gt extends $ {
  _parse(e) {
    if (this._getType(e) !== w.undefined) {
      const n = this._getOrReturnCtx(e);
      return b(n, {
        code: g.invalid_type,
        expected: w.undefined,
        received: n.parsedType
      }), I;
    }
    return ae(e.data);
  }
}
Gt.create = (t) => new Gt({
  typeName: A.ZodUndefined,
  ...C(t)
});
class Bt extends $ {
  _parse(e) {
    if (this._getType(e) !== w.null) {
      const n = this._getOrReturnCtx(e);
      return b(n, {
        code: g.invalid_type,
        expected: w.null,
        received: n.parsedType
      }), I;
    }
    return ae(e.data);
  }
}
Bt.create = (t) => new Bt({
  typeName: A.ZodNull,
  ...C(t)
});
class At extends $ {
  constructor() {
    super(...arguments), this._any = !0;
  }
  _parse(e) {
    return ae(e.data);
  }
}
At.create = (t) => new At({
  typeName: A.ZodAny,
  ...C(t)
});
class dt extends $ {
  constructor() {
    super(...arguments), this._unknown = !0;
  }
  _parse(e) {
    return ae(e.data);
  }
}
dt.create = (t) => new dt({
  typeName: A.ZodUnknown,
  ...C(t)
});
class je extends $ {
  _parse(e) {
    const r = this._getOrReturnCtx(e);
    return b(r, {
      code: g.invalid_type,
      expected: w.never,
      received: r.parsedType
    }), I;
  }
}
je.create = (t) => new je({
  typeName: A.ZodNever,
  ...C(t)
});
class Tr extends $ {
  _parse(e) {
    if (this._getType(e) !== w.undefined) {
      const n = this._getOrReturnCtx(e);
      return b(n, {
        code: g.invalid_type,
        expected: w.void,
        received: n.parsedType
      }), I;
    }
    return ae(e.data);
  }
}
Tr.create = (t) => new Tr({
  typeName: A.ZodVoid,
  ...C(t)
});
class Te extends $ {
  _parse(e) {
    const { ctx: r, status: n } = this._processInputParams(e), o = this._def;
    if (r.parsedType !== w.array)
      return b(r, {
        code: g.invalid_type,
        expected: w.array,
        received: r.parsedType
      }), I;
    if (o.exactLength !== null) {
      const i = r.data.length > o.exactLength.value, c = r.data.length < o.exactLength.value;
      (i || c) && (b(r, {
        code: i ? g.too_big : g.too_small,
        minimum: c ? o.exactLength.value : void 0,
        maximum: i ? o.exactLength.value : void 0,
        type: "array",
        inclusive: !0,
        exact: !0,
        message: o.exactLength.message
      }), n.dirty());
    }
    if (o.minLength !== null && r.data.length < o.minLength.value && (b(r, {
      code: g.too_small,
      minimum: o.minLength.value,
      type: "array",
      inclusive: !0,
      exact: !1,
      message: o.minLength.message
    }), n.dirty()), o.maxLength !== null && r.data.length > o.maxLength.value && (b(r, {
      code: g.too_big,
      maximum: o.maxLength.value,
      type: "array",
      inclusive: !0,
      exact: !1,
      message: o.maxLength.message
    }), n.dirty()), r.common.async)
      return Promise.all([...r.data].map((i, c) => o.type._parseAsync(new Re(r, i, r.path, c)))).then((i) => Q.mergeArray(n, i));
    const a = [...r.data].map((i, c) => o.type._parseSync(new Re(r, i, r.path, c)));
    return Q.mergeArray(n, a);
  }
  get element() {
    return this._def.type;
  }
  min(e, r) {
    return new Te({
      ...this._def,
      minLength: { value: e, message: E.toString(r) }
    });
  }
  max(e, r) {
    return new Te({
      ...this._def,
      maxLength: { value: e, message: E.toString(r) }
    });
  }
  length(e, r) {
    return new Te({
      ...this._def,
      exactLength: { value: e, message: E.toString(r) }
    });
  }
  nonempty(e) {
    return this.min(1, e);
  }
}
Te.create = (t, e) => new Te({
  type: t,
  minLength: null,
  maxLength: null,
  exactLength: null,
  typeName: A.ZodArray,
  ...C(e)
});
function _t(t) {
  if (t instanceof U) {
    const e = {};
    for (const r in t.shape) {
      const n = t.shape[r];
      e[r] = Ne.create(_t(n));
    }
    return new U({
      ...t._def,
      shape: () => e
    });
  } else
    return t instanceof Te ? new Te({
      ...t._def,
      type: _t(t.element)
    }) : t instanceof Ne ? Ne.create(_t(t.unwrap())) : t instanceof tt ? tt.create(_t(t.unwrap())) : t instanceof Oe ? Oe.create(t.items.map((e) => _t(e))) : t;
}
class U extends $ {
  constructor() {
    super(...arguments), this._cached = null, this.nonstrict = this.passthrough, this.augment = this.extend;
  }
  _getCached() {
    if (this._cached !== null)
      return this._cached;
    const e = this._def.shape(), r = O.objectKeys(e);
    return this._cached = { shape: e, keys: r };
  }
  _parse(e) {
    if (this._getType(e) !== w.object) {
      const u = this._getOrReturnCtx(e);
      return b(u, {
        code: g.invalid_type,
        expected: w.object,
        received: u.parsedType
      }), I;
    }
    const { status: n, ctx: o } = this._processInputParams(e), { shape: a, keys: i } = this._getCached(), c = [];
    if (!(this._def.catchall instanceof je && this._def.unknownKeys === "strip"))
      for (const u in o.data)
        i.includes(u) || c.push(u);
    const l = [];
    for (const u of i) {
      const d = a[u], f = o.data[u];
      l.push({
        key: { status: "valid", value: u },
        value: d._parse(new Re(o, f, o.path, u)),
        alwaysSet: u in o.data
      });
    }
    if (this._def.catchall instanceof je) {
      const u = this._def.unknownKeys;
      if (u === "passthrough")
        for (const d of c)
          l.push({
            key: { status: "valid", value: d },
            value: { status: "valid", value: o.data[d] }
          });
      else if (u === "strict")
        c.length > 0 && (b(o, {
          code: g.unrecognized_keys,
          keys: c
        }), n.dirty());
      else if (u !== "strip")
        throw new Error("Internal ZodObject error: invalid unknownKeys value.");
    } else {
      const u = this._def.catchall;
      for (const d of c) {
        const f = o.data[d];
        l.push({
          key: { status: "valid", value: d },
          value: u._parse(
            new Re(o, f, o.path, d)
            //, ctx.child(key), value, getParsedType(value)
          ),
          alwaysSet: d in o.data
        });
      }
    }
    return o.common.async ? Promise.resolve().then(async () => {
      const u = [];
      for (const d of l) {
        const f = await d.key, h = await d.value;
        u.push({
          key: f,
          value: h,
          alwaysSet: d.alwaysSet
        });
      }
      return u;
    }).then((u) => Q.mergeObjectSync(n, u)) : Q.mergeObjectSync(n, l);
  }
  get shape() {
    return this._def.shape();
  }
  strict(e) {
    return E.errToObj, new U({
      ...this._def,
      unknownKeys: "strict",
      ...e !== void 0 ? {
        errorMap: (r, n) => {
          var o, a, i, c;
          const l = (i = (a = (o = this._def).errorMap) === null || a === void 0 ? void 0 : a.call(o, r, n).message) !== null && i !== void 0 ? i : n.defaultError;
          return r.code === "unrecognized_keys" ? {
            message: (c = E.errToObj(e).message) !== null && c !== void 0 ? c : l
          } : {
            message: l
          };
        }
      } : {}
    });
  }
  strip() {
    return new U({
      ...this._def,
      unknownKeys: "strip"
    });
  }
  passthrough() {
    return new U({
      ...this._def,
      unknownKeys: "passthrough"
    });
  }
  // const AugmentFactory =
  //   <Def extends ZodObjectDef>(def: Def) =>
  //   <Augmentation extends ZodRawShape>(
  //     augmentation: Augmentation
  //   ): ZodObject<
  //     extendShape<ReturnType<Def["shape"]>, Augmentation>,
  //     Def["unknownKeys"],
  //     Def["catchall"]
  //   > => {
  //     return new ZodObject({
  //       ...def,
  //       shape: () => ({
  //         ...def.shape(),
  //         ...augmentation,
  //       }),
  //     }) as any;
  //   };
  extend(e) {
    return new U({
      ...this._def,
      shape: () => ({
        ...this._def.shape(),
        ...e
      })
    });
  }
  /**
   * Prior to zod@1.0.12 there was a bug in the
   * inferred type of merged objects. Please
   * upgrade if you are experiencing issues.
   */
  merge(e) {
    return new U({
      unknownKeys: e._def.unknownKeys,
      catchall: e._def.catchall,
      shape: () => ({
        ...this._def.shape(),
        ...e._def.shape()
      }),
      typeName: A.ZodObject
    });
  }
  // merge<
  //   Incoming extends AnyZodObject,
  //   Augmentation extends Incoming["shape"],
  //   NewOutput extends {
  //     [k in keyof Augmentation | keyof Output]: k extends keyof Augmentation
  //       ? Augmentation[k]["_output"]
  //       : k extends keyof Output
  //       ? Output[k]
  //       : never;
  //   },
  //   NewInput extends {
  //     [k in keyof Augmentation | keyof Input]: k extends keyof Augmentation
  //       ? Augmentation[k]["_input"]
  //       : k extends keyof Input
  //       ? Input[k]
  //       : never;
  //   }
  // >(
  //   merging: Incoming
  // ): ZodObject<
  //   extendShape<T, ReturnType<Incoming["_def"]["shape"]>>,
  //   Incoming["_def"]["unknownKeys"],
  //   Incoming["_def"]["catchall"],
  //   NewOutput,
  //   NewInput
  // > {
  //   const merged: any = new ZodObject({
  //     unknownKeys: merging._def.unknownKeys,
  //     catchall: merging._def.catchall,
  //     shape: () =>
  //       objectUtil.mergeShapes(this._def.shape(), merging._def.shape()),
  //     typeName: ZodFirstPartyTypeKind.ZodObject,
  //   }) as any;
  //   return merged;
  // }
  setKey(e, r) {
    return this.augment({ [e]: r });
  }
  // merge<Incoming extends AnyZodObject>(
  //   merging: Incoming
  // ): //ZodObject<T & Incoming["_shape"], UnknownKeys, Catchall> = (merging) => {
  // ZodObject<
  //   extendShape<T, ReturnType<Incoming["_def"]["shape"]>>,
  //   Incoming["_def"]["unknownKeys"],
  //   Incoming["_def"]["catchall"]
  // > {
  //   // const mergedShape = objectUtil.mergeShapes(
  //   //   this._def.shape(),
  //   //   merging._def.shape()
  //   // );
  //   const merged: any = new ZodObject({
  //     unknownKeys: merging._def.unknownKeys,
  //     catchall: merging._def.catchall,
  //     shape: () =>
  //       objectUtil.mergeShapes(this._def.shape(), merging._def.shape()),
  //     typeName: ZodFirstPartyTypeKind.ZodObject,
  //   }) as any;
  //   return merged;
  // }
  catchall(e) {
    return new U({
      ...this._def,
      catchall: e
    });
  }
  pick(e) {
    const r = {};
    return O.objectKeys(e).forEach((n) => {
      e[n] && this.shape[n] && (r[n] = this.shape[n]);
    }), new U({
      ...this._def,
      shape: () => r
    });
  }
  omit(e) {
    const r = {};
    return O.objectKeys(this.shape).forEach((n) => {
      e[n] || (r[n] = this.shape[n]);
    }), new U({
      ...this._def,
      shape: () => r
    });
  }
  /**
   * @deprecated
   */
  deepPartial() {
    return _t(this);
  }
  partial(e) {
    const r = {};
    return O.objectKeys(this.shape).forEach((n) => {
      const o = this.shape[n];
      e && !e[n] ? r[n] = o : r[n] = o.optional();
    }), new U({
      ...this._def,
      shape: () => r
    });
  }
  required(e) {
    const r = {};
    return O.objectKeys(this.shape).forEach((n) => {
      if (e && !e[n])
        r[n] = this.shape[n];
      else {
        let a = this.shape[n];
        for (; a instanceof Ne; )
          a = a._def.innerType;
        r[n] = a;
      }
    }), new U({
      ...this._def,
      shape: () => r
    });
  }
  keyof() {
    return Ls(O.objectKeys(this.shape));
  }
}
U.create = (t, e) => new U({
  shape: () => t,
  unknownKeys: "strip",
  catchall: je.create(),
  typeName: A.ZodObject,
  ...C(e)
});
U.strictCreate = (t, e) => new U({
  shape: () => t,
  unknownKeys: "strict",
  catchall: je.create(),
  typeName: A.ZodObject,
  ...C(e)
});
U.lazycreate = (t, e) => new U({
  shape: t,
  unknownKeys: "strip",
  catchall: je.create(),
  typeName: A.ZodObject,
  ...C(e)
});
class Ht extends $ {
  _parse(e) {
    const { ctx: r } = this._processInputParams(e), n = this._def.options;
    function o(a) {
      for (const c of a)
        if (c.result.status === "valid")
          return c.result;
      for (const c of a)
        if (c.result.status === "dirty")
          return r.common.issues.push(...c.ctx.common.issues), c.result;
      const i = a.map((c) => new fe(c.ctx.common.issues));
      return b(r, {
        code: g.invalid_union,
        unionErrors: i
      }), I;
    }
    if (r.common.async)
      return Promise.all(n.map(async (a) => {
        const i = {
          ...r,
          common: {
            ...r.common,
            issues: []
          },
          parent: null
        };
        return {
          result: await a._parseAsync({
            data: r.data,
            path: r.path,
            parent: i
          }),
          ctx: i
        };
      })).then(o);
    {
      let a;
      const i = [];
      for (const l of n) {
        const u = {
          ...r,
          common: {
            ...r.common,
            issues: []
          },
          parent: null
        }, d = l._parseSync({
          data: r.data,
          path: r.path,
          parent: u
        });
        if (d.status === "valid")
          return d;
        d.status === "dirty" && !a && (a = { result: d, ctx: u }), u.common.issues.length && i.push(u.common.issues);
      }
      if (a)
        return r.common.issues.push(...a.ctx.common.issues), a.result;
      const c = i.map((l) => new fe(l));
      return b(r, {
        code: g.invalid_union,
        unionErrors: c
      }), I;
    }
  }
  get options() {
    return this._def.options;
  }
}
Ht.create = (t, e) => new Ht({
  options: t,
  typeName: A.ZodUnion,
  ...C(e)
});
const Fe = (t) => t instanceof qt ? Fe(t.schema) : t instanceof Ae ? Fe(t.innerType()) : t instanceof Kt ? [t.value] : t instanceof et ? t.options : t instanceof Yt ? O.objectValues(t.enum) : t instanceof Jt ? Fe(t._def.innerType) : t instanceof Gt ? [void 0] : t instanceof Bt ? [null] : t instanceof Ne ? [void 0, ...Fe(t.unwrap())] : t instanceof tt ? [null, ...Fe(t.unwrap())] : t instanceof Zn || t instanceof Qt ? Fe(t.unwrap()) : t instanceof Xt ? Fe(t._def.innerType) : [];
class Zr extends $ {
  _parse(e) {
    const { ctx: r } = this._processInputParams(e);
    if (r.parsedType !== w.object)
      return b(r, {
        code: g.invalid_type,
        expected: w.object,
        received: r.parsedType
      }), I;
    const n = this.discriminator, o = r.data[n], a = this.optionsMap.get(o);
    return a ? r.common.async ? a._parseAsync({
      data: r.data,
      path: r.path,
      parent: r
    }) : a._parseSync({
      data: r.data,
      path: r.path,
      parent: r
    }) : (b(r, {
      code: g.invalid_union_discriminator,
      options: Array.from(this.optionsMap.keys()),
      path: [n]
    }), I);
  }
  get discriminator() {
    return this._def.discriminator;
  }
  get options() {
    return this._def.options;
  }
  get optionsMap() {
    return this._def.optionsMap;
  }
  /**
   * The constructor of the discriminated union schema. Its behaviour is very similar to that of the normal z.union() constructor.
   * However, it only allows a union of objects, all of which need to share a discriminator property. This property must
   * have a different value for each object in the union.
   * @param discriminator the name of the discriminator property
   * @param types an array of object schemas
   * @param params
   */
  static create(e, r, n) {
    const o = /* @__PURE__ */ new Map();
    for (const a of r) {
      const i = Fe(a.shape[e]);
      if (!i.length)
        throw new Error(`A discriminator value for key \`${e}\` could not be extracted from all schema options`);
      for (const c of i) {
        if (o.has(c))
          throw new Error(`Discriminator property ${String(e)} has duplicate value ${String(c)}`);
        o.set(c, a);
      }
    }
    return new Zr({
      typeName: A.ZodDiscriminatedUnion,
      discriminator: e,
      options: r,
      optionsMap: o,
      ...C(n)
    });
  }
}
function mn(t, e) {
  const r = Ve(t), n = Ve(e);
  if (t === e)
    return { valid: !0, data: t };
  if (r === w.object && n === w.object) {
    const o = O.objectKeys(e), a = O.objectKeys(t).filter((c) => o.indexOf(c) !== -1), i = { ...t, ...e };
    for (const c of a) {
      const l = mn(t[c], e[c]);
      if (!l.valid)
        return { valid: !1 };
      i[c] = l.data;
    }
    return { valid: !0, data: i };
  } else if (r === w.array && n === w.array) {
    if (t.length !== e.length)
      return { valid: !1 };
    const o = [];
    for (let a = 0; a < t.length; a++) {
      const i = t[a], c = e[a], l = mn(i, c);
      if (!l.valid)
        return { valid: !1 };
      o.push(l.data);
    }
    return { valid: !0, data: o };
  } else
    return r === w.date && n === w.date && +t == +e ? { valid: !0, data: t } : { valid: !1 };
}
class Vt extends $ {
  _parse(e) {
    const { status: r, ctx: n } = this._processInputParams(e), o = (a, i) => {
      if (pn(a) || pn(i))
        return I;
      const c = mn(a.value, i.value);
      return c.valid ? ((hn(a) || hn(i)) && r.dirty(), { status: r.value, value: c.data }) : (b(n, {
        code: g.invalid_intersection_types
      }), I);
    };
    return n.common.async ? Promise.all([
      this._def.left._parseAsync({
        data: n.data,
        path: n.path,
        parent: n
      }),
      this._def.right._parseAsync({
        data: n.data,
        path: n.path,
        parent: n
      })
    ]).then(([a, i]) => o(a, i)) : o(this._def.left._parseSync({
      data: n.data,
      path: n.path,
      parent: n
    }), this._def.right._parseSync({
      data: n.data,
      path: n.path,
      parent: n
    }));
  }
}
Vt.create = (t, e, r) => new Vt({
  left: t,
  right: e,
  typeName: A.ZodIntersection,
  ...C(r)
});
class Oe extends $ {
  _parse(e) {
    const { status: r, ctx: n } = this._processInputParams(e);
    if (n.parsedType !== w.array)
      return b(n, {
        code: g.invalid_type,
        expected: w.array,
        received: n.parsedType
      }), I;
    if (n.data.length < this._def.items.length)
      return b(n, {
        code: g.too_small,
        minimum: this._def.items.length,
        inclusive: !0,
        exact: !1,
        type: "array"
      }), I;
    !this._def.rest && n.data.length > this._def.items.length && (b(n, {
      code: g.too_big,
      maximum: this._def.items.length,
      inclusive: !0,
      exact: !1,
      type: "array"
    }), r.dirty());
    const a = [...n.data].map((i, c) => {
      const l = this._def.items[c] || this._def.rest;
      return l ? l._parse(new Re(n, i, n.path, c)) : null;
    }).filter((i) => !!i);
    return n.common.async ? Promise.all(a).then((i) => Q.mergeArray(r, i)) : Q.mergeArray(r, a);
  }
  get items() {
    return this._def.items;
  }
  rest(e) {
    return new Oe({
      ...this._def,
      rest: e
    });
  }
}
Oe.create = (t, e) => {
  if (!Array.isArray(t))
    throw new Error("You must pass an array of schemas to z.tuple([ ... ])");
  return new Oe({
    items: t,
    typeName: A.ZodTuple,
    rest: null,
    ...C(e)
  });
};
class Wt extends $ {
  get keySchema() {
    return this._def.keyType;
  }
  get valueSchema() {
    return this._def.valueType;
  }
  _parse(e) {
    const { status: r, ctx: n } = this._processInputParams(e);
    if (n.parsedType !== w.object)
      return b(n, {
        code: g.invalid_type,
        expected: w.object,
        received: n.parsedType
      }), I;
    const o = [], a = this._def.keyType, i = this._def.valueType;
    for (const c in n.data)
      o.push({
        key: a._parse(new Re(n, c, n.path, c)),
        value: i._parse(new Re(n, n.data[c], n.path, c)),
        alwaysSet: c in n.data
      });
    return n.common.async ? Q.mergeObjectAsync(r, o) : Q.mergeObjectSync(r, o);
  }
  get element() {
    return this._def.valueType;
  }
  static create(e, r, n) {
    return r instanceof $ ? new Wt({
      keyType: e,
      valueType: r,
      typeName: A.ZodRecord,
      ...C(n)
    }) : new Wt({
      keyType: ke.create(),
      valueType: e,
      typeName: A.ZodRecord,
      ...C(r)
    });
  }
}
class Ar extends $ {
  get keySchema() {
    return this._def.keyType;
  }
  get valueSchema() {
    return this._def.valueType;
  }
  _parse(e) {
    const { status: r, ctx: n } = this._processInputParams(e);
    if (n.parsedType !== w.map)
      return b(n, {
        code: g.invalid_type,
        expected: w.map,
        received: n.parsedType
      }), I;
    const o = this._def.keyType, a = this._def.valueType, i = [...n.data.entries()].map(([c, l], u) => ({
      key: o._parse(new Re(n, c, n.path, [u, "key"])),
      value: a._parse(new Re(n, l, n.path, [u, "value"]))
    }));
    if (n.common.async) {
      const c = /* @__PURE__ */ new Map();
      return Promise.resolve().then(async () => {
        for (const l of i) {
          const u = await l.key, d = await l.value;
          if (u.status === "aborted" || d.status === "aborted")
            return I;
          (u.status === "dirty" || d.status === "dirty") && r.dirty(), c.set(u.value, d.value);
        }
        return { status: r.value, value: c };
      });
    } else {
      const c = /* @__PURE__ */ new Map();
      for (const l of i) {
        const u = l.key, d = l.value;
        if (u.status === "aborted" || d.status === "aborted")
          return I;
        (u.status === "dirty" || d.status === "dirty") && r.dirty(), c.set(u.value, d.value);
      }
      return { status: r.value, value: c };
    }
  }
}
Ar.create = (t, e, r) => new Ar({
  valueType: e,
  keyType: t,
  typeName: A.ZodMap,
  ...C(r)
});
class ht extends $ {
  _parse(e) {
    const { status: r, ctx: n } = this._processInputParams(e);
    if (n.parsedType !== w.set)
      return b(n, {
        code: g.invalid_type,
        expected: w.set,
        received: n.parsedType
      }), I;
    const o = this._def;
    o.minSize !== null && n.data.size < o.minSize.value && (b(n, {
      code: g.too_small,
      minimum: o.minSize.value,
      type: "set",
      inclusive: !0,
      exact: !1,
      message: o.minSize.message
    }), r.dirty()), o.maxSize !== null && n.data.size > o.maxSize.value && (b(n, {
      code: g.too_big,
      maximum: o.maxSize.value,
      type: "set",
      inclusive: !0,
      exact: !1,
      message: o.maxSize.message
    }), r.dirty());
    const a = this._def.valueType;
    function i(l) {
      const u = /* @__PURE__ */ new Set();
      for (const d of l) {
        if (d.status === "aborted")
          return I;
        d.status === "dirty" && r.dirty(), u.add(d.value);
      }
      return { status: r.value, value: u };
    }
    const c = [...n.data.values()].map((l, u) => a._parse(new Re(n, l, n.path, u)));
    return n.common.async ? Promise.all(c).then((l) => i(l)) : i(c);
  }
  min(e, r) {
    return new ht({
      ...this._def,
      minSize: { value: e, message: E.toString(r) }
    });
  }
  max(e, r) {
    return new ht({
      ...this._def,
      maxSize: { value: e, message: E.toString(r) }
    });
  }
  size(e, r) {
    return this.min(e, r).max(e, r);
  }
  nonempty(e) {
    return this.min(1, e);
  }
}
ht.create = (t, e) => new ht({
  valueType: t,
  minSize: null,
  maxSize: null,
  typeName: A.ZodSet,
  ...C(e)
});
class xt extends $ {
  constructor() {
    super(...arguments), this.validate = this.implement;
  }
  _parse(e) {
    const { ctx: r } = this._processInputParams(e);
    if (r.parsedType !== w.function)
      return b(r, {
        code: g.invalid_type,
        expected: w.function,
        received: r.parsedType
      }), I;
    function n(c, l) {
      return xr({
        data: c,
        path: r.path,
        errorMaps: [
          r.common.contextualErrorMap,
          r.schemaErrorMap,
          Er(),
          Tt
        ].filter((u) => !!u),
        issueData: {
          code: g.invalid_arguments,
          argumentsError: l
        }
      });
    }
    function o(c, l) {
      return xr({
        data: c,
        path: r.path,
        errorMaps: [
          r.common.contextualErrorMap,
          r.schemaErrorMap,
          Er(),
          Tt
        ].filter((u) => !!u),
        issueData: {
          code: g.invalid_return_type,
          returnTypeError: l
        }
      });
    }
    const a = { errorMap: r.common.contextualErrorMap }, i = r.data;
    if (this._def.returns instanceof It) {
      const c = this;
      return ae(async function(...l) {
        const u = new fe([]), d = await c._def.args.parseAsync(l, a).catch((p) => {
          throw u.addIssue(n(l, p)), u;
        }), f = await Reflect.apply(i, this, d);
        return await c._def.returns._def.type.parseAsync(f, a).catch((p) => {
          throw u.addIssue(o(f, p)), u;
        });
      });
    } else {
      const c = this;
      return ae(function(...l) {
        const u = c._def.args.safeParse(l, a);
        if (!u.success)
          throw new fe([n(l, u.error)]);
        const d = Reflect.apply(i, this, u.data), f = c._def.returns.safeParse(d, a);
        if (!f.success)
          throw new fe([o(d, f.error)]);
        return f.data;
      });
    }
  }
  parameters() {
    return this._def.args;
  }
  returnType() {
    return this._def.returns;
  }
  args(...e) {
    return new xt({
      ...this._def,
      args: Oe.create(e).rest(dt.create())
    });
  }
  returns(e) {
    return new xt({
      ...this._def,
      returns: e
    });
  }
  implement(e) {
    return this.parse(e);
  }
  strictImplement(e) {
    return this.parse(e);
  }
  static create(e, r, n) {
    return new xt({
      args: e || Oe.create([]).rest(dt.create()),
      returns: r || dt.create(),
      typeName: A.ZodFunction,
      ...C(n)
    });
  }
}
class qt extends $ {
  get schema() {
    return this._def.getter();
  }
  _parse(e) {
    const { ctx: r } = this._processInputParams(e);
    return this._def.getter()._parse({ data: r.data, path: r.path, parent: r });
  }
}
qt.create = (t, e) => new qt({
  getter: t,
  typeName: A.ZodLazy,
  ...C(e)
});
class Kt extends $ {
  _parse(e) {
    if (e.data !== this._def.value) {
      const r = this._getOrReturnCtx(e);
      return b(r, {
        received: r.data,
        code: g.invalid_literal,
        expected: this._def.value
      }), I;
    }
    return { status: "valid", value: e.data };
  }
  get value() {
    return this._def.value;
  }
}
Kt.create = (t, e) => new Kt({
  value: t,
  typeName: A.ZodLiteral,
  ...C(e)
});
function Ls(t, e) {
  return new et({
    values: t,
    typeName: A.ZodEnum,
    ...C(e)
  });
}
class et extends $ {
  constructor() {
    super(...arguments), Lt.set(this, void 0);
  }
  _parse(e) {
    if (typeof e.data != "string") {
      const r = this._getOrReturnCtx(e), n = this._def.values;
      return b(r, {
        expected: O.joinValues(n),
        received: r.parsedType,
        code: g.invalid_type
      }), I;
    }
    if (kr(this, Lt) || Ns(this, Lt, new Set(this._def.values)), !kr(this, Lt).has(e.data)) {
      const r = this._getOrReturnCtx(e), n = this._def.values;
      return b(r, {
        received: r.data,
        code: g.invalid_enum_value,
        options: n
      }), I;
    }
    return ae(e.data);
  }
  get options() {
    return this._def.values;
  }
  get enum() {
    const e = {};
    for (const r of this._def.values)
      e[r] = r;
    return e;
  }
  get Values() {
    const e = {};
    for (const r of this._def.values)
      e[r] = r;
    return e;
  }
  get Enum() {
    const e = {};
    for (const r of this._def.values)
      e[r] = r;
    return e;
  }
  extract(e, r = this._def) {
    return et.create(e, {
      ...this._def,
      ...r
    });
  }
  exclude(e, r = this._def) {
    return et.create(this.options.filter((n) => !e.includes(n)), {
      ...this._def,
      ...r
    });
  }
}
Lt = /* @__PURE__ */ new WeakMap();
et.create = Ls;
class Yt extends $ {
  constructor() {
    super(...arguments), Ft.set(this, void 0);
  }
  _parse(e) {
    const r = O.getValidEnumValues(this._def.values), n = this._getOrReturnCtx(e);
    if (n.parsedType !== w.string && n.parsedType !== w.number) {
      const o = O.objectValues(r);
      return b(n, {
        expected: O.joinValues(o),
        received: n.parsedType,
        code: g.invalid_type
      }), I;
    }
    if (kr(this, Ft) || Ns(this, Ft, new Set(O.getValidEnumValues(this._def.values))), !kr(this, Ft).has(e.data)) {
      const o = O.objectValues(r);
      return b(n, {
        received: n.data,
        code: g.invalid_enum_value,
        options: o
      }), I;
    }
    return ae(e.data);
  }
  get enum() {
    return this._def.values;
  }
}
Ft = /* @__PURE__ */ new WeakMap();
Yt.create = (t, e) => new Yt({
  values: t,
  typeName: A.ZodNativeEnum,
  ...C(e)
});
class It extends $ {
  unwrap() {
    return this._def.type;
  }
  _parse(e) {
    const { ctx: r } = this._processInputParams(e);
    if (r.parsedType !== w.promise && r.common.async === !1)
      return b(r, {
        code: g.invalid_type,
        expected: w.promise,
        received: r.parsedType
      }), I;
    const n = r.parsedType === w.promise ? r.data : Promise.resolve(r.data);
    return ae(n.then((o) => this._def.type.parseAsync(o, {
      path: r.path,
      errorMap: r.common.contextualErrorMap
    })));
  }
}
It.create = (t, e) => new It({
  type: t,
  typeName: A.ZodPromise,
  ...C(e)
});
class Ae extends $ {
  innerType() {
    return this._def.schema;
  }
  sourceType() {
    return this._def.schema._def.typeName === A.ZodEffects ? this._def.schema.sourceType() : this._def.schema;
  }
  _parse(e) {
    const { status: r, ctx: n } = this._processInputParams(e), o = this._def.effect || null, a = {
      addIssue: (i) => {
        b(n, i), i.fatal ? r.abort() : r.dirty();
      },
      get path() {
        return n.path;
      }
    };
    if (a.addIssue = a.addIssue.bind(a), o.type === "preprocess") {
      const i = o.transform(n.data, a);
      if (n.common.async)
        return Promise.resolve(i).then(async (c) => {
          if (r.value === "aborted")
            return I;
          const l = await this._def.schema._parseAsync({
            data: c,
            path: n.path,
            parent: n
          });
          return l.status === "aborted" ? I : l.status === "dirty" || r.value === "dirty" ? wt(l.value) : l;
        });
      {
        if (r.value === "aborted")
          return I;
        const c = this._def.schema._parseSync({
          data: i,
          path: n.path,
          parent: n
        });
        return c.status === "aborted" ? I : c.status === "dirty" || r.value === "dirty" ? wt(c.value) : c;
      }
    }
    if (o.type === "refinement") {
      const i = (c) => {
        const l = o.refinement(c, a);
        if (n.common.async)
          return Promise.resolve(l);
        if (l instanceof Promise)
          throw new Error("Async refinement encountered during synchronous parse operation. Use .parseAsync instead.");
        return c;
      };
      if (n.common.async === !1) {
        const c = this._def.schema._parseSync({
          data: n.data,
          path: n.path,
          parent: n
        });
        return c.status === "aborted" ? I : (c.status === "dirty" && r.dirty(), i(c.value), { status: r.value, value: c.value });
      } else
        return this._def.schema._parseAsync({ data: n.data, path: n.path, parent: n }).then((c) => c.status === "aborted" ? I : (c.status === "dirty" && r.dirty(), i(c.value).then(() => ({ status: r.value, value: c.value }))));
    }
    if (o.type === "transform")
      if (n.common.async === !1) {
        const i = this._def.schema._parseSync({
          data: n.data,
          path: n.path,
          parent: n
        });
        if (!jt(i))
          return i;
        const c = o.transform(i.value, a);
        if (c instanceof Promise)
          throw new Error("Asynchronous transform encountered during synchronous parse operation. Use .parseAsync instead.");
        return { status: r.value, value: c };
      } else
        return this._def.schema._parseAsync({ data: n.data, path: n.path, parent: n }).then((i) => jt(i) ? Promise.resolve(o.transform(i.value, a)).then((c) => ({ status: r.value, value: c })) : i);
    O.assertNever(o);
  }
}
Ae.create = (t, e, r) => new Ae({
  schema: t,
  typeName: A.ZodEffects,
  effect: e,
  ...C(r)
});
Ae.createWithPreprocess = (t, e, r) => new Ae({
  schema: e,
  effect: { type: "preprocess", transform: t },
  typeName: A.ZodEffects,
  ...C(r)
});
class Ne extends $ {
  _parse(e) {
    return this._getType(e) === w.undefined ? ae(void 0) : this._def.innerType._parse(e);
  }
  unwrap() {
    return this._def.innerType;
  }
}
Ne.create = (t, e) => new Ne({
  innerType: t,
  typeName: A.ZodOptional,
  ...C(e)
});
class tt extends $ {
  _parse(e) {
    return this._getType(e) === w.null ? ae(null) : this._def.innerType._parse(e);
  }
  unwrap() {
    return this._def.innerType;
  }
}
tt.create = (t, e) => new tt({
  innerType: t,
  typeName: A.ZodNullable,
  ...C(e)
});
class Jt extends $ {
  _parse(e) {
    const { ctx: r } = this._processInputParams(e);
    let n = r.data;
    return r.parsedType === w.undefined && (n = this._def.defaultValue()), this._def.innerType._parse({
      data: n,
      path: r.path,
      parent: r
    });
  }
  removeDefault() {
    return this._def.innerType;
  }
}
Jt.create = (t, e) => new Jt({
  innerType: t,
  typeName: A.ZodDefault,
  defaultValue: typeof e.default == "function" ? e.default : () => e.default,
  ...C(e)
});
class Xt extends $ {
  _parse(e) {
    const { ctx: r } = this._processInputParams(e), n = {
      ...r,
      common: {
        ...r.common,
        issues: []
      }
    }, o = this._def.innerType._parse({
      data: n.data,
      path: n.path,
      parent: {
        ...n
      }
    });
    return Zt(o) ? o.then((a) => ({
      status: "valid",
      value: a.status === "valid" ? a.value : this._def.catchValue({
        get error() {
          return new fe(n.common.issues);
        },
        input: n.data
      })
    })) : {
      status: "valid",
      value: o.status === "valid" ? o.value : this._def.catchValue({
        get error() {
          return new fe(n.common.issues);
        },
        input: n.data
      })
    };
  }
  removeCatch() {
    return this._def.innerType;
  }
}
Xt.create = (t, e) => new Xt({
  innerType: t,
  typeName: A.ZodCatch,
  catchValue: typeof e.catch == "function" ? e.catch : () => e.catch,
  ...C(e)
});
class Ir extends $ {
  _parse(e) {
    if (this._getType(e) !== w.nan) {
      const n = this._getOrReturnCtx(e);
      return b(n, {
        code: g.invalid_type,
        expected: w.nan,
        received: n.parsedType
      }), I;
    }
    return { status: "valid", value: e.data };
  }
}
Ir.create = (t) => new Ir({
  typeName: A.ZodNaN,
  ...C(t)
});
const Gc = Symbol("zod_brand");
class Zn extends $ {
  _parse(e) {
    const { ctx: r } = this._processInputParams(e), n = r.data;
    return this._def.type._parse({
      data: n,
      path: r.path,
      parent: r
    });
  }
  unwrap() {
    return this._def.type;
  }
}
class sr extends $ {
  _parse(e) {
    const { status: r, ctx: n } = this._processInputParams(e);
    if (n.common.async)
      return (async () => {
        const a = await this._def.in._parseAsync({
          data: n.data,
          path: n.path,
          parent: n
        });
        return a.status === "aborted" ? I : a.status === "dirty" ? (r.dirty(), wt(a.value)) : this._def.out._parseAsync({
          data: a.value,
          path: n.path,
          parent: n
        });
      })();
    {
      const o = this._def.in._parseSync({
        data: n.data,
        path: n.path,
        parent: n
      });
      return o.status === "aborted" ? I : o.status === "dirty" ? (r.dirty(), {
        status: "dirty",
        value: o.value
      }) : this._def.out._parseSync({
        data: o.value,
        path: n.path,
        parent: n
      });
    }
  }
  static create(e, r) {
    return new sr({
      in: e,
      out: r,
      typeName: A.ZodPipeline
    });
  }
}
class Qt extends $ {
  _parse(e) {
    const r = this._def.innerType._parse(e), n = (o) => (jt(o) && (o.value = Object.freeze(o.value)), o);
    return Zt(r) ? r.then((o) => n(o)) : n(r);
  }
  unwrap() {
    return this._def.innerType;
  }
}
Qt.create = (t, e) => new Qt({
  innerType: t,
  typeName: A.ZodReadonly,
  ...C(e)
});
function Fs(t, e = {}, r) {
  return t ? At.create().superRefine((n, o) => {
    var a, i;
    if (!t(n)) {
      const c = typeof e == "function" ? e(n) : typeof e == "string" ? { message: e } : e, l = (i = (a = c.fatal) !== null && a !== void 0 ? a : r) !== null && i !== void 0 ? i : !0, u = typeof c == "string" ? { message: c } : c;
      o.addIssue({ code: "custom", ...u, fatal: l });
    }
  }) : At.create();
}
const Bc = {
  object: U.lazycreate
};
var A;
(function(t) {
  t.ZodString = "ZodString", t.ZodNumber = "ZodNumber", t.ZodNaN = "ZodNaN", t.ZodBigInt = "ZodBigInt", t.ZodBoolean = "ZodBoolean", t.ZodDate = "ZodDate", t.ZodSymbol = "ZodSymbol", t.ZodUndefined = "ZodUndefined", t.ZodNull = "ZodNull", t.ZodAny = "ZodAny", t.ZodUnknown = "ZodUnknown", t.ZodNever = "ZodNever", t.ZodVoid = "ZodVoid", t.ZodArray = "ZodArray", t.ZodObject = "ZodObject", t.ZodUnion = "ZodUnion", t.ZodDiscriminatedUnion = "ZodDiscriminatedUnion", t.ZodIntersection = "ZodIntersection", t.ZodTuple = "ZodTuple", t.ZodRecord = "ZodRecord", t.ZodMap = "ZodMap", t.ZodSet = "ZodSet", t.ZodFunction = "ZodFunction", t.ZodLazy = "ZodLazy", t.ZodLiteral = "ZodLiteral", t.ZodEnum = "ZodEnum", t.ZodEffects = "ZodEffects", t.ZodNativeEnum = "ZodNativeEnum", t.ZodOptional = "ZodOptional", t.ZodNullable = "ZodNullable", t.ZodDefault = "ZodDefault", t.ZodCatch = "ZodCatch", t.ZodPromise = "ZodPromise", t.ZodBranded = "ZodBranded", t.ZodPipeline = "ZodPipeline", t.ZodReadonly = "ZodReadonly";
})(A || (A = {}));
const Hc = (t, e = {
  message: `Input not instance of ${t.name}`
}) => Fs((r) => r instanceof t, e), Ds = ke.create, Us = Xe.create, Vc = Ir.create, Wc = Qe.create, js = zt.create, qc = pt.create, Kc = Pr.create, Yc = Gt.create, Jc = Bt.create, Xc = At.create, Qc = dt.create, el = je.create, tl = Tr.create, rl = Te.create, nl = U.create, ol = U.strictCreate, sl = Ht.create, al = Zr.create, il = Vt.create, cl = Oe.create, ll = Wt.create, ul = Ar.create, dl = ht.create, fl = xt.create, pl = qt.create, hl = Kt.create, ml = et.create, gl = Yt.create, yl = It.create, _o = Ae.create, vl = Ne.create, _l = tt.create, bl = Ae.createWithPreprocess, wl = sr.create, Sl = () => Ds().optional(), El = () => Us().optional(), xl = () => js().optional(), kl = {
  string: (t) => ke.create({ ...t, coerce: !0 }),
  number: (t) => Xe.create({ ...t, coerce: !0 }),
  boolean: (t) => zt.create({
    ...t,
    coerce: !0
  }),
  bigint: (t) => Qe.create({ ...t, coerce: !0 }),
  date: (t) => pt.create({ ...t, coerce: !0 })
}, Pl = I;
var W = /* @__PURE__ */ Object.freeze({
  __proto__: null,
  defaultErrorMap: Tt,
  setErrorMap: Pc,
  getErrorMap: Er,
  makeIssue: xr,
  EMPTY_PATH: Tc,
  addIssueToContext: b,
  ParseStatus: Q,
  INVALID: I,
  DIRTY: wt,
  OK: ae,
  isAborted: pn,
  isDirty: hn,
  isValid: jt,
  isAsync: Zt,
  get util() {
    return O;
  },
  get objectUtil() {
    return fn;
  },
  ZodParsedType: w,
  getParsedType: Ve,
  ZodType: $,
  datetimeRegex: Ms,
  ZodString: ke,
  ZodNumber: Xe,
  ZodBigInt: Qe,
  ZodBoolean: zt,
  ZodDate: pt,
  ZodSymbol: Pr,
  ZodUndefined: Gt,
  ZodNull: Bt,
  ZodAny: At,
  ZodUnknown: dt,
  ZodNever: je,
  ZodVoid: Tr,
  ZodArray: Te,
  ZodObject: U,
  ZodUnion: Ht,
  ZodDiscriminatedUnion: Zr,
  ZodIntersection: Vt,
  ZodTuple: Oe,
  ZodRecord: Wt,
  ZodMap: Ar,
  ZodSet: ht,
  ZodFunction: xt,
  ZodLazy: qt,
  ZodLiteral: Kt,
  ZodEnum: et,
  ZodNativeEnum: Yt,
  ZodPromise: It,
  ZodEffects: Ae,
  ZodTransformer: Ae,
  ZodOptional: Ne,
  ZodNullable: tt,
  ZodDefault: Jt,
  ZodCatch: Xt,
  ZodNaN: Ir,
  BRAND: Gc,
  ZodBranded: Zn,
  ZodPipeline: sr,
  ZodReadonly: Qt,
  custom: Fs,
  Schema: $,
  ZodSchema: $,
  late: Bc,
  get ZodFirstPartyTypeKind() {
    return A;
  },
  coerce: kl,
  any: Xc,
  array: rl,
  bigint: Wc,
  boolean: js,
  date: qc,
  discriminatedUnion: al,
  effect: _o,
  enum: ml,
  function: fl,
  instanceof: Hc,
  intersection: il,
  lazy: pl,
  literal: hl,
  map: ul,
  nan: Vc,
  nativeEnum: gl,
  never: el,
  null: Jc,
  nullable: _l,
  number: Us,
  object: nl,
  oboolean: xl,
  onumber: El,
  optional: vl,
  ostring: Sl,
  pipeline: wl,
  preprocess: bl,
  promise: yl,
  record: ll,
  set: dl,
  strictObject: ol,
  string: Ds,
  symbol: Kc,
  transformer: _o,
  tuple: cl,
  undefined: Yc,
  union: sl,
  unknown: Qc,
  void: tl,
  NEVER: Pl,
  ZodIssueCode: g,
  quotelessJson: kc,
  ZodError: fe
});
const Tl = W.object({
  width: W.number().positive(),
  height: W.number().positive()
});
function Al(t, e, r, n) {
  const o = document.createElement("plugin-modal");
  o.setTheme(r);
  const a = 200, i = 200, c = 335, l = 590, u = {
    blockStart: 40,
    inlineEnd: 320
  };
  o.style.setProperty(
    "--modal-block-start",
    `${u.blockStart}px`
  ), o.style.setProperty(
    "--modal-inline-end",
    `${u.inlineEnd}px`
  );
  const d = window.innerWidth - u.inlineEnd, f = window.innerHeight - u.blockStart;
  let h = Math.min((n == null ? void 0 : n.width) || c, d), p = Math.min((n == null ? void 0 : n.height) || l, f);
  return h = Math.max(h, a), p = Math.max(p, i), o.setAttribute("title", t), o.setAttribute("iframe-src", e), o.setAttribute("width", String(h)), o.setAttribute("height", String(p)), document.body.appendChild(o), o;
}
const Il = W.function().args(
  W.string(),
  W.string(),
  W.enum(["dark", "light"]),
  Tl.optional()
).implement((t, e, r, n) => Al(t, e, r, n)), Cl = W.object({
  pluginId: W.string(),
  name: W.string(),
  host: W.string().url(),
  code: W.string(),
  icon: W.string().optional(),
  description: W.string().max(200).optional(),
  permissions: W.array(
    W.enum([
      "content:read",
      "content:write",
      "library:read",
      "library:write",
      "user:read"
    ])
  )
});
function Zs(t, e) {
  return new URL(e, t).toString();
}
function $l(t) {
  return fetch(t).then((e) => e.json()).then((e) => {
    if (!Cl.safeParse(e).success)
      throw new Error("Invalid plugin manifest");
    return e;
  }).catch((e) => {
    throw console.error(e), e;
  });
}
function Nl(t) {
  return fetch(Zs(t.host, t.code)).then((e) => {
    if (e.ok)
      return e.text();
    throw new Error("Failed to load plugin code");
  });
}
const Rl = [
  "finish",
  "pagechange",
  "filechange",
  "selectionchange",
  "themechange",
  "shapechange",
  "contentsave"
];
let gn = [], yn = /* @__PURE__ */ new Set([]), Mt = {};
window.addEventListener("message", (t) => {
  try {
    for (const e of gn)
      e(t.data);
  } catch (e) {
    console.error(e);
  }
});
function Ol(t) {
  yn.forEach((e) => {
    e.setTheme(t);
  });
}
function Ml(t, e) {
  let r = null;
  const n = () => {
    Object.entries(Mt).forEach(([, i]) => {
      i.forEach((c) => {
        t.removeListener(c);
      });
    }), r && (yn.delete(r), r.removeEventListener("close", n), r.remove()), gn = [], r = null;
  }, o = (i) => {
    if (!e.permissions.includes(i))
      throw new Error(`Permission ${i} is not granted`);
  };
  return {
    ui: {
      open: (i, c, l) => {
        const u = t.getTheme();
        r = Il(
          i,
          Zs(e.host, c),
          u,
          l
        ), r.setTheme(u), r.addEventListener("close", n, {
          once: !0
        }), yn.add(r);
      },
      sendMessage(i) {
        const c = new CustomEvent("message", {
          detail: i
        });
        r == null || r.dispatchEvent(c);
      },
      onMessage: (i) => {
        W.function().parse(i), gn.push(i);
      }
    },
    utils: {
      geometry: {
        center(i) {
          return window.app.plugins.public_utils.centerShapes(i);
        }
      },
      types: {
        isFrame(i) {
          return i.type === "frame";
        },
        isGroup(i) {
          return i.type === "group";
        },
        isMask(i) {
          return i.type === "group" && i.isMask();
        },
        isBool(i) {
          return i.type === "bool";
        },
        isRectangle(i) {
          return i.type === "rect";
        },
        isPath(i) {
          return i.type === "path";
        },
        isText(i) {
          return i.type === "text";
        },
        isEllipse(i) {
          return i.type === "circle";
        },
        isSVG(i) {
          return i.type === "svg-raw";
        }
      }
    },
    closePlugin: n,
    on(i, c, l) {
      W.enum(Rl).parse(i), W.function().parse(c), o("content:read");
      const u = t.addListener(i, c, l);
      return Mt[i] || (Mt[i] = /* @__PURE__ */ new Map()), Mt[i].set(c, u), u;
    },
    off(i, c) {
      let l;
      typeof i == "symbol" ? l = i : c && (l = Mt[i].get(c)), l && t.removeListener(l);
    },
    // Penpot State API
    get root() {
      return o("content:read"), t.root;
    },
    get currentPage() {
      return o("content:read"), t.currentPage;
    },
    get selection() {
      return o("content:read"), t.selection;
    },
    set selection(i) {
      o("content:read"), t.selection = i;
    },
    get viewport() {
      return t.viewport;
    },
    get history() {
      return t.history;
    },
    get library() {
      return o("library:read"), t.library;
    },
    get fonts() {
      return o("content:read"), t.fonts;
    },
    get currentUser() {
      return o("user:read"), t.currentUser;
    },
    get activeUsers() {
      return o("user:read"), t.activeUsers;
    },
    getFile() {
      return o("content:read"), t.getFile();
    },
    getPage() {
      return o("content:read"), t.getPage();
    },
    getSelected() {
      return o("content:read"), t.getSelected();
    },
    getSelectedShapes() {
      return o("content:read"), t.getSelectedShapes();
    },
    shapesColors(i) {
      return o("content:read"), t.shapesColors(i);
    },
    replaceColor(i, c, l) {
      return o("content:write"), t.replaceColor(i, c, l);
    },
    getTheme() {
      return t.getTheme();
    },
    createFrame() {
      return o("content:write"), t.createFrame();
    },
    createRectangle() {
      return o("content:write"), t.createRectangle();
    },
    createEllipse() {
      return o("content:write"), t.createEllipse();
    },
    createText(i) {
      return o("content:write"), t.createText(i);
    },
    createPath() {
      return o("content:write"), t.createPath();
    },
    createBoolean(i, c) {
      return o("content:write"), t.createBoolean(i, c);
    },
    createShapeFromSvg(i) {
      return o("content:write"), t.createShapeFromSvg(i);
    },
    group(i) {
      return o("content:write"), t.group(i);
    },
    ungroup(i, ...c) {
      o("content:write"), t.ungroup(i, ...c);
    },
    uploadMediaUrl(i, c) {
      return o("content:write"), t.uploadMediaUrl(i, c);
    },
    uploadMediaData(i, c, l) {
      return o("content:write"), t.uploadMediaData(i, c, l);
    },
    generateMarkup(i, c) {
      return o("content:read"), t.generateMarkup(i, c);
    },
    generateStyle(i, c) {
      return o("content:read"), t.generateStyle(i, c);
    },
    openViewer() {
      o("content:read"), t.openViewer();
    },
    createPage() {
      return o("content:write"), t.createPage();
    },
    openPage(i) {
      o("content:read"), t.openPage(i);
    }
  };
}
let bo = !1, fr = [];
const Ll = !1;
let vn = null;
function Fl(t) {
  vn = t;
}
const zs = async function(t) {
  try {
    const e = () => {
      fr.forEach((c) => {
        c.closePlugin();
      }), fr = [];
    }, r = vn && vn(t.pluginId);
    if (!r)
      return;
    r.addListener("themechange", (c) => Ol(c));
    const n = await Nl(t);
    bo || (bo = !0, hardenIntrinsics()), fr && !Ll && e();
    const o = Ml(r, t);
    fr.push(o), new Compartment({
      penpot: harden(o),
      fetch: harden((...c) => {
        const l = {
          ...c[1],
          credentials: "omit"
        };
        return fetch(c[0], l);
      }),
      console: harden(window.console),
      Math: harden(Math),
      setTimeout: harden(
        (...[c, l]) => setTimeout(() => {
          c();
        }, l)
      ),
      clearTimeout: harden((c) => {
        clearTimeout(c);
      })
    }).evaluate(n);
    const i = r.addListener("finish", () => {
      e(), r == null || r.removeListener(i);
    });
  } catch (e) {
    console.error(e);
  }
}, Dl = async function(t) {
  const e = await $l(t);
  zs(e);
};
console.log("%c[PLUGINS] Loading plugin system", "color: #008d7c");
repairIntrinsics({
  evalTaming: "unsafeEval",
  stackFiltering: "verbose",
  errorTaming: "unsafe",
  consoleTaming: "unsafe"
});
const wo = globalThis;
wo.initPluginsRuntime = (t) => {
  try {
    console.log("%c[PLUGINS] Initialize runtime", "color: #008d7c"), Fl(t), wo.ɵcontext = t("TEST"), globalThis.ɵloadPlugin = zs, globalThis.ɵloadPluginByUrl = Dl;
  } catch (e) {
    console.error(e);
  }
};
//# sourceMappingURL=index.js.map
