var An = (t, e, r) => {
  if (!e.has(t))
    throw TypeError("Cannot " + r);
};
var Se = (t, e, r) => (An(t, e, "read from private field"), r ? r.call(t) : e.get(t)), Fr = (t, e, r) => {
  if (e.has(t))
    throw TypeError("Cannot add the same private member more than once");
  e instanceof WeakSet ? e.add(t) : e.set(t, r);
}, Dr = (t, e, r, n) => (An(t, e, "write to private field"), n ? n.call(t, r) : e.set(t, r), r);
const x = globalThis, {
  Array: Ps,
  Date: ks,
  FinalizationRegistry: bt,
  Float32Array: Ts,
  JSON: Is,
  Map: Ce,
  Math: As,
  Number: io,
  Object: ln,
  Promise: Cs,
  Proxy: xr,
  Reflect: $s,
  RegExp: Be,
  Set: Pt,
  String: ie,
  Symbol: Ot,
  WeakMap: Te,
  WeakSet: kt
} = globalThis, {
  // The feral Error constructor is safe for internal use, but must not be
  // revealed to post-lockdown code in any compartment including the start
  // compartment since in V8 at least it bears stack inspection capabilities.
  Error: le,
  RangeError: Ns,
  ReferenceError: rt,
  SyntaxError: Kt,
  TypeError: v
} = globalThis, {
  assign: Pr,
  create: V,
  defineProperties: F,
  entries: te,
  freeze: g,
  getOwnPropertyDescriptor: de,
  getOwnPropertyDescriptors: Je,
  getOwnPropertyNames: Mt,
  getPrototypeOf: H,
  is: kr,
  isFrozen: sl,
  isSealed: al,
  isExtensible: il,
  keys: co,
  prototype: lo,
  seal: cl,
  preventExtensions: Os,
  setPrototypeOf: uo,
  values: fo,
  fromEntries: Tt
} = ln, {
  species: Cn,
  toStringTag: He,
  iterator: Yt,
  matchAll: po,
  unscopables: Rs,
  keyFor: Ms,
  for: ll
} = Ot, { isInteger: Ls } = io, { stringify: mo } = Is, { defineProperty: Fs } = ln, L = (t, e, r) => {
  const n = Fs(t, e, r);
  if (n !== t)
    throw v(
      `Please report that the original defineProperty silently failed to set ${mo(
        ie(e)
      )}. (SES_DEFINE_PROPERTY_FAILED_SILENTLY)`
    );
  return n;
}, {
  apply: oe,
  construct: lr,
  get: Ds,
  getOwnPropertyDescriptor: Us,
  has: ho,
  isExtensible: js,
  ownKeys: st,
  preventExtensions: Zs,
  set: yo
} = $s, { isArray: gt, prototype: Ie } = Ps, { prototype: It } = Ce, { prototype: Tr } = RegExp, { prototype: Jt } = Pt, { prototype: Re } = ie, { prototype: Ir } = Te, { prototype: go } = kt, { prototype: un } = Function, { prototype: vo } = Cs, zs = H(Uint8Array.prototype), { bind: $n } = un, k = $n.bind($n.call), se = k(lo.hasOwnProperty), Ve = k(Ie.filter), nt = k(Ie.forEach), Ar = k(Ie.includes), At = k(Ie.join), fe = (
  /** @type {any} */
  k(Ie.map)
), Hr = k(Ie.pop), ae = k(Ie.push), Gs = k(Ie.slice), Bs = k(Ie.some), _o = k(Ie.sort), Hs = k(Ie[Yt]), $e = k(It.set), De = k(It.get), Cr = k(It.has), Vs = k(It.delete), Ws = k(It.entries), qs = k(It[Yt]), $r = k(Jt.add);
k(Jt.delete);
const Nn = k(Jt.forEach), dn = k(Jt.has), Ks = k(Jt[Yt]), fn = k(Tr.test), pn = k(Tr.exec), Ys = k(Tr[po]), bo = k(Re.endsWith), Js = k(Re.includes), Xs = k(Re.indexOf);
k(Re.match);
const ur = (
  /** @type {any} */
  k(Re.replace)
), Qs = k(Re.search), mn = k(Re.slice), wo = k(Re.split), So = k(Re.startsWith), ea = k(Re[Yt]), ta = k(Ir.delete), M = k(Ir.get), hn = k(Ir.has), ee = k(Ir.set), Nr = k(go.add), Xt = k(go.has), ra = k(un.toString), na = k(vo.catch), yn = (
  /** @type {any} */
  k(vo.then)
), oa = bt && k(bt.prototype.register);
bt && k(bt.prototype.unregister);
const gn = g(V(null)), We = (t) => ln(t) === t, vn = (t) => t instanceof le, Eo = eval, ve = Function, sa = () => {
  throw v('Cannot eval with evalTaming set to "noEval" (SES_NO_EVAL)');
};
function aa() {
  return this;
}
if (aa())
  throw v("SES failed to initialize, sloppy mode (SES_NO_SLOPPY)");
const { freeze: et } = Object, { apply: ia } = Reflect, _n = (t) => (e, ...r) => ia(t, e, r), ca = _n(Array.prototype.push), On = _n(Array.prototype.includes), la = _n(String.prototype.split), Qe = JSON.stringify, tr = (t, ...e) => {
  let r = t[0];
  for (let n = 0; n < e.length; n += 1)
    r = `${r}${e[n]}${t[n + 1]}`;
  throw Error(r);
}, xo = (t, e = !1) => {
  const r = [], n = (c, u, l = void 0) => {
    typeof c == "string" || tr`Environment option name ${Qe(c)} must be a string.`, typeof u == "string" || tr`Environment option default setting ${Qe(
      u
    )} must be a string.`;
    let d = u;
    const f = t.process || void 0, m = typeof f == "object" && f.env || void 0;
    if (typeof m == "object" && c in m) {
      e || ca(r, c);
      const p = m[c];
      typeof p == "string" || tr`Environment option named ${Qe(
        c
      )}, if present, must have a corresponding string value, got ${Qe(
        p
      )}`, d = p;
    }
    return l === void 0 || d === u || On(l, d) || tr`Unrecognized ${Qe(c)} value ${Qe(
      d
    )}. Expected one of ${Qe([u, ...l])}`, d;
  };
  et(n);
  const a = (c) => {
    const u = n(c, "");
    return et(u === "" ? [] : la(u, ","));
  };
  et(a);
  const s = (c, u) => On(a(c), u), i = () => et([...r]);
  return et(i), et({
    getEnvironmentOption: n,
    getEnvironmentOptionsList: a,
    environmentOptionsListHas: s,
    getCapturedEnvironmentOptionNames: i
  });
};
et(xo);
const {
  getEnvironmentOption: he,
  getEnvironmentOptionsList: ul,
  environmentOptionsListHas: dl
} = xo(globalThis, !0), dr = (t) => (t = `${t}`, t.length >= 1 && Js("aeiouAEIOU", t[0]) ? `an ${t}` : `a ${t}`);
g(dr);
const Po = (t, e = void 0) => {
  const r = new Pt(), n = (a, s) => {
    switch (typeof s) {
      case "object": {
        if (s === null)
          return null;
        if (dn(r, s))
          return "[Seen]";
        if ($r(r, s), vn(s))
          return `[${s.name}: ${s.message}]`;
        if (He in s)
          return `[${s[He]}]`;
        if (gt(s))
          return s;
        const i = co(s);
        if (i.length < 2)
          return s;
        let c = !0;
        for (let l = 1; l < i.length; l += 1)
          if (i[l - 1] >= i[l]) {
            c = !1;
            break;
          }
        if (c)
          return s;
        _o(i);
        const u = fe(i, (l) => [l, s[l]]);
        return Tt(u);
      }
      case "function":
        return `[Function ${s.name || "<anon>"}]`;
      case "string":
        return So(s, "[") ? `[${s}]` : s;
      case "undefined":
      case "symbol":
        return `[${ie(s)}]`;
      case "bigint":
        return `[${s}n]`;
      case "number":
        return kr(s, NaN) ? "[NaN]" : s === 1 / 0 ? "[Infinity]" : s === -1 / 0 ? "[-Infinity]" : s;
      default:
        return s;
    }
  };
  try {
    return mo(t, n, e);
  } catch {
    return "[Something that failed to stringify]";
  }
};
g(Po);
const { isSafeInteger: ua } = Number, { freeze: mt } = Object, { toStringTag: da } = Symbol, Rn = (t) => {
  const r = {
    next: void 0,
    prev: void 0,
    data: t
  };
  return r.next = r, r.prev = r, r;
}, Mn = (t, e) => {
  if (t === e)
    throw TypeError("Cannot splice a cell into itself");
  if (e.next !== e || e.prev !== e)
    throw TypeError("Expected self-linked cell");
  const r = e, n = t.next;
  return r.prev = t, r.next = n, t.next = r, n.prev = r, r;
}, Ur = (t) => {
  const { prev: e, next: r } = t;
  e.next = r, r.prev = e, t.prev = t, t.next = t;
}, ko = (t) => {
  if (!ua(t) || t < 0)
    throw TypeError("keysBudget must be a safe non-negative integer number");
  const e = /* @__PURE__ */ new WeakMap();
  let r = 0;
  const n = Rn(void 0), a = (d) => {
    const f = e.get(d);
    if (!(f === void 0 || f.data === void 0))
      return Ur(f), Mn(n, f), f;
  }, s = (d) => a(d) !== void 0;
  mt(s);
  const i = (d) => {
    const f = a(d);
    return f && f.data && f.data.get(d);
  };
  mt(i);
  const c = (d, f) => {
    if (t < 1)
      return l;
    let m = a(d);
    if (m === void 0 && (m = Rn(void 0), Mn(n, m)), !m.data)
      for (r += 1, m.data = /* @__PURE__ */ new WeakMap(), e.set(d, m); r > t; ) {
        const p = n.prev;
        Ur(p), p.data = void 0, r -= 1;
      }
    return m.data.set(d, f), l;
  };
  mt(c);
  const u = (d) => {
    const f = e.get(d);
    return f === void 0 || (Ur(f), e.delete(d), f.data === void 0) ? !1 : (f.data = void 0, r -= 1, !0);
  };
  mt(u);
  const l = mt({
    has: s,
    get: i,
    set: c,
    delete: u,
    // eslint-disable-next-line jsdoc/check-types
    [
      /** @type {typeof Symbol.toStringTag} */
      da
    ]: "LRUCacheMap"
  });
  return l;
};
mt(ko);
const { freeze: ar } = Object, { isSafeInteger: fa } = Number, pa = 1e3, ma = 100, To = (t = pa, e = ma) => {
  if (!fa(e) || e < 1)
    throw TypeError(
      "argsPerErrorBudget must be a safe positive integer number"
    );
  const r = ko(t), n = (s, i) => {
    const c = r.get(s);
    c !== void 0 ? (c.length >= e && c.shift(), c.push(i)) : r.set(s, [i]);
  };
  ar(n);
  const a = (s) => {
    const i = r.get(s);
    return r.delete(s), i;
  };
  return ar(a), ar({
    addLogArgs: n,
    takeLogArgsArray: a
  });
};
ar(To);
const wt = new Te(), at = (t, e = void 0) => {
  const r = g({
    toString: g(() => Po(t, e))
  });
  return ee(wt, r, t), r;
};
g(at);
const ha = g(/^[\w:-]( ?[\w:-])*$/), Vr = (t, e = void 0) => {
  if (typeof t != "string" || !fn(ha, t))
    return at(t, e);
  const r = g({
    toString: g(() => t)
  });
  return ee(wt, r, t), r;
};
g(Vr);
const Or = new Te(), Io = ({ template: t, args: e }) => {
  const r = [t[0]];
  for (let n = 0; n < e.length; n += 1) {
    const a = e[n];
    let s;
    hn(wt, a) ? s = `${a}` : vn(a) ? s = `(${dr(a.name)})` : s = `(${dr(typeof a)})`, ae(r, s, t[n + 1]);
  }
  return At(r, "");
}, Ao = g({
  toString() {
    const t = M(Or, this);
    return t === void 0 ? "[Not a DetailsToken]" : Io(t);
  }
});
g(Ao.toString);
const St = (t, ...e) => {
  const r = g({ __proto__: Ao });
  return ee(Or, r, { template: t, args: e }), r;
};
g(St);
const Co = (t, ...e) => (e = fe(
  e,
  (r) => hn(wt, r) ? r : at(r)
), St(t, ...e));
g(Co);
const $o = ({ template: t, args: e }) => {
  const r = [t[0]];
  for (let n = 0; n < e.length; n += 1) {
    let a = e[n];
    hn(wt, a) && (a = M(wt, a));
    const s = ur(Hr(r) || "", / $/, "");
    s !== "" && ae(r, s);
    const i = ur(t[n + 1], /^ /, "");
    ae(r, a, i);
  }
  return r[r.length - 1] === "" && Hr(r), r;
}, ir = new Te();
let Wr = 0;
const Ln = new Te(), No = (t, e = t.name) => {
  let r = M(Ln, t);
  return r !== void 0 || (Wr += 1, r = `${e}#${Wr}`, ee(Ln, t, r)), r;
}, qr = (t = St`Assert failed`, e = x.Error, { errorName: r = void 0 } = {}) => {
  typeof t == "string" && (t = St([t]));
  const n = M(Or, t);
  if (n === void 0)
    throw v(`unrecognized details ${at(t)}`);
  const a = Io(n), s = new e(a);
  return ee(ir, s, $o(n)), r !== void 0 && No(s, r), s;
};
g(qr);
const { addLogArgs: ya, takeLogArgsArray: ga } = To(), Kr = new Te(), Oo = (t, e) => {
  typeof e == "string" && (e = St([e]));
  const r = M(Or, e);
  if (r === void 0)
    throw v(`unrecognized details ${at(e)}`);
  const n = $o(r), a = M(Kr, t);
  if (a !== void 0)
    for (const s of a)
      s(t, n);
  else
    ya(t, n);
};
g(Oo);
const va = (t) => {
  if (!("stack" in t))
    return "";
  const e = `${t.stack}`, r = Xs(e, `
`);
  return So(e, " ") || r === -1 ? e : mn(e, r + 1);
}, Yr = {
  getStackString: x.getStackString || va,
  tagError: (t) => No(t),
  resetErrorTagNum: () => {
    Wr = 0;
  },
  getMessageLogArgs: (t) => M(ir, t),
  takeMessageLogArgs: (t) => {
    const e = M(ir, t);
    return ta(ir, t), e;
  },
  takeNoteLogArgsArray: (t, e) => {
    const r = ga(t);
    if (e !== void 0) {
      const n = M(Kr, t);
      n ? ae(n, e) : ee(Kr, t, [e]);
    }
    return r || [];
  }
};
g(Yr);
const Rr = (t = void 0, e = !1) => {
  const r = e ? Co : St, n = r`Check failed`, a = (f = n, m = x.Error) => {
    const p = qr(f, m);
    throw t !== void 0 && t(p), p;
  };
  g(a);
  const s = (f, ...m) => a(r(f, ...m));
  function i(f, m = void 0, p = void 0) {
    f || a(m, p);
  }
  const c = (f, m, p = void 0, h = void 0) => {
    kr(f, m) || a(
      p || r`Expected ${f} is same as ${m}`,
      h || Ns
    );
  };
  g(c);
  const u = (f, m, p) => {
    if (typeof f !== m) {
      if (typeof m == "string" || s`${at(m)} must be a string`, p === void 0) {
        const h = dr(m);
        p = r`${f} must be ${Vr(h)}`;
      }
      a(p, v);
    }
  };
  g(u);
  const d = Pr(i, {
    error: qr,
    fail: a,
    equal: c,
    typeof: u,
    string: (f, m = void 0) => u(f, "string", m),
    note: Oo,
    details: r,
    Fail: s,
    quote: at,
    bare: Vr,
    makeAssert: Rr
  });
  return g(d);
};
g(Rr);
const z = Rr(), Ro = de(
  zs,
  He
);
z(Ro);
const Mo = Ro.get;
z(Mo);
const _a = (t) => oe(Mo, t, []) !== void 0, ba = (t) => {
  const e = +ie(t);
  return Ls(e) && ie(e) === t;
}, wa = (t) => {
  Os(t), nt(st(t), (e) => {
    const r = de(t, e);
    z(r), ba(e) || L(t, e, {
      ...r,
      writable: !1,
      configurable: !1
    });
  });
}, Sa = () => {
  if (typeof x.harden == "function")
    return x.harden;
  const t = new kt(), { harden: e } = {
    /**
     * @template T
     * @param {T} root
     * @returns {T}
     */
    harden(r) {
      const n = new Pt(), a = new Te();
      function s(d, f = void 0) {
        if (!We(d))
          return;
        const m = typeof d;
        if (m !== "object" && m !== "function")
          throw v(`Unexpected typeof: ${m}`);
        Xt(t, d) || dn(n, d) || ($r(n, d), ee(a, d, f));
      }
      function i(d) {
        _a(d) ? wa(d) : g(d);
        const f = M(a, d) || "unknown", m = Je(d), p = H(d);
        s(p, `${f}.__proto__`), nt(st(m), (h) => {
          const _ = `${f}.${ie(h)}`, w = m[
            /** @type {string} */
            h
          ];
          se(w, "value") ? s(w.value, `${_}`) : (s(w.get, `${_}(get)`), s(w.set, `${_}(set)`));
        });
      }
      function c() {
        Nn(n, i);
      }
      function u(d) {
        Nr(t, d);
      }
      function l() {
        Nn(n, u);
      }
      return s(r), c(), l(), r;
    }
  };
  return e;
}, Lo = {
  // *** Value Properties of the Global Object
  Infinity: 1 / 0,
  NaN: NaN,
  undefined: void 0
}, Fo = {
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
}, Fn = {
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
}, Do = {
  // *** Constructor Properties of the Global Object
  Date: "%SharedDate%",
  Error: "%SharedError%",
  RegExp: "%SharedRegExp%",
  Symbol: "%SharedSymbol%",
  // *** Other Properties of the Global Object
  Math: "%SharedMath%"
}, Ea = [
  EvalError,
  RangeError,
  ReferenceError,
  SyntaxError,
  TypeError,
  URIError
], Jr = {
  "[[Proto]]": "%FunctionPrototype%",
  length: "number",
  name: "string"
  // Do not specify "prototype" here, since only Function instances that can
  // be used as a constructor have a prototype property. For constructors,
  // since prototype properties are instance-specific, we define it there.
}, xa = {
  // This property is not mentioned in ECMA 262, but is present in V8 and
  // necessary for lockdown to succeed.
  "[[Proto]]": "%AsyncFunctionPrototype%"
}, o = Jr, Dn = xa, O = {
  get: o,
  set: "undefined"
}, Ae = {
  get: o,
  set: o
}, Un = (t) => t === O || t === Ae;
function dt(t) {
  return {
    // Properties of the NativeError Constructors
    "[[Proto]]": "%SharedError%",
    // NativeError.prototype
    prototype: t
  };
}
function ft(t) {
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
function ye(t) {
  return {
    // Properties of the TypedArray Constructors
    "[[Proto]]": "%TypedArray%",
    BYTES_PER_ELEMENT: "number",
    prototype: t
  };
}
function ge(t) {
  return {
    // Properties of the TypedArray Prototype Objects
    "[[Proto]]": "%TypedArrayPrototype%",
    BYTES_PER_ELEMENT: "number",
    constructor: t
  };
}
const jn = {
  E: "number",
  LN10: "number",
  LN2: "number",
  LOG10E: "number",
  LOG2E: "number",
  PI: "number",
  SQRT1_2: "number",
  SQRT2: "number",
  "@@toStringTag": "string",
  abs: o,
  acos: o,
  acosh: o,
  asin: o,
  asinh: o,
  atan: o,
  atanh: o,
  atan2: o,
  cbrt: o,
  ceil: o,
  clz32: o,
  cos: o,
  cosh: o,
  exp: o,
  expm1: o,
  floor: o,
  fround: o,
  hypot: o,
  imul: o,
  log: o,
  log1p: o,
  log10: o,
  log2: o,
  max: o,
  min: o,
  pow: o,
  round: o,
  sign: o,
  sin: o,
  sinh: o,
  sqrt: o,
  tan: o,
  tanh: o,
  trunc: o,
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
}, fr = {
  // ECMA https://tc39.es/ecma262
  // The intrinsics object has no prototype to avoid conflicts.
  "[[Proto]]": null,
  // %ThrowTypeError%
  "%ThrowTypeError%": o,
  // *** The Global Object
  // *** Value Properties of the Global Object
  Infinity: "number",
  NaN: "number",
  undefined: "undefined",
  // *** Function Properties of the Global Object
  // eval
  "%UniqueEval%": o,
  isFinite: o,
  isNaN: o,
  parseFloat: o,
  parseInt: o,
  decodeURI: o,
  decodeURIComponent: o,
  encodeURI: o,
  encodeURIComponent: o,
  // *** Fundamental Objects
  Object: {
    // Properties of the Object Constructor
    "[[Proto]]": "%FunctionPrototype%",
    assign: o,
    create: o,
    defineProperties: o,
    defineProperty: o,
    entries: o,
    freeze: o,
    fromEntries: o,
    getOwnPropertyDescriptor: o,
    getOwnPropertyDescriptors: o,
    getOwnPropertyNames: o,
    getOwnPropertySymbols: o,
    getPrototypeOf: o,
    hasOwn: o,
    is: o,
    isExtensible: o,
    isFrozen: o,
    isSealed: o,
    keys: o,
    preventExtensions: o,
    prototype: "%ObjectPrototype%",
    seal: o,
    setPrototypeOf: o,
    values: o,
    // https://github.com/tc39/proposal-array-grouping
    groupBy: o,
    // Seen on QuickJS
    __getClass: !1
  },
  "%ObjectPrototype%": {
    // Properties of the Object Prototype Object
    "[[Proto]]": null,
    constructor: "Object",
    hasOwnProperty: o,
    isPrototypeOf: o,
    propertyIsEnumerable: o,
    toLocaleString: o,
    toString: o,
    valueOf: o,
    // Annex B: Additional Properties of the Object.prototype Object
    // See note in header about the difference between [[Proto]] and --proto--
    // special notations.
    "--proto--": Ae,
    __defineGetter__: o,
    __defineSetter__: o,
    __lookupGetter__: o,
    __lookupSetter__: o
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
    apply: o,
    bind: o,
    call: o,
    constructor: "%InertFunction%",
    toString: o,
    "@@hasInstance": o,
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
    toString: o,
    valueOf: o
  },
  "%SharedSymbol%": {
    // Properties of the Symbol Constructor
    "[[Proto]]": "%FunctionPrototype%",
    asyncDispose: "symbol",
    asyncIterator: "symbol",
    dispose: "symbol",
    for: o,
    hasInstance: "symbol",
    isConcatSpreadable: "symbol",
    iterator: "symbol",
    keyFor: o,
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
    description: O,
    toString: o,
    valueOf: o,
    "@@toPrimitive": o,
    "@@toStringTag": "string"
  },
  "%InitialError%": {
    // Properties of the Error Constructor
    "[[Proto]]": "%FunctionPrototype%",
    prototype: "%ErrorPrototype%",
    // Non standard, v8 only, used by tap
    captureStackTrace: o,
    // Non standard, v8 only, used by tap, tamed to accessor
    stackTraceLimit: Ae,
    // Non standard, v8 only, used by several, tamed to accessor
    prepareStackTrace: Ae
  },
  "%SharedError%": {
    // Properties of the Error Constructor
    "[[Proto]]": "%FunctionPrototype%",
    prototype: "%ErrorPrototype%",
    // Non standard, v8 only, used by tap
    captureStackTrace: o,
    // Non standard, v8 only, used by tap, tamed to accessor
    stackTraceLimit: Ae,
    // Non standard, v8 only, used by several, tamed to accessor
    prepareStackTrace: Ae
  },
  "%ErrorPrototype%": {
    constructor: "%SharedError%",
    message: "string",
    name: "string",
    toString: o,
    // proposed de-facto, assumed TODO
    // Seen on FF Nightly 88.0a1
    at: !1,
    // Seen on FF and XS
    stack: Ae,
    // Superfluously present in some versions of V8.
    // https://github.com/tc39/notes/blob/master/meetings/2021-10/oct-26.md#:~:text=However%2C%20Chrome%2093,and%20node%2016.11.
    cause: !1
  },
  // NativeError
  EvalError: dt("%EvalErrorPrototype%"),
  RangeError: dt("%RangeErrorPrototype%"),
  ReferenceError: dt("%ReferenceErrorPrototype%"),
  SyntaxError: dt("%SyntaxErrorPrototype%"),
  TypeError: dt("%TypeErrorPrototype%"),
  URIError: dt("%URIErrorPrototype%"),
  "%EvalErrorPrototype%": ft("EvalError"),
  "%RangeErrorPrototype%": ft("RangeError"),
  "%ReferenceErrorPrototype%": ft("ReferenceError"),
  "%SyntaxErrorPrototype%": ft("SyntaxError"),
  "%TypeErrorPrototype%": ft("TypeError"),
  "%URIErrorPrototype%": ft("URIError"),
  // *** Numbers and Dates
  Number: {
    // Properties of the Number Constructor
    "[[Proto]]": "%FunctionPrototype%",
    EPSILON: "number",
    isFinite: o,
    isInteger: o,
    isNaN: o,
    isSafeInteger: o,
    MAX_SAFE_INTEGER: "number",
    MAX_VALUE: "number",
    MIN_SAFE_INTEGER: "number",
    MIN_VALUE: "number",
    NaN: "number",
    NEGATIVE_INFINITY: "number",
    parseFloat: o,
    parseInt: o,
    POSITIVE_INFINITY: "number",
    prototype: "%NumberPrototype%"
  },
  "%NumberPrototype%": {
    // Properties of the Number Prototype Object
    constructor: "Number",
    toExponential: o,
    toFixed: o,
    toLocaleString: o,
    toPrecision: o,
    toString: o,
    valueOf: o
  },
  BigInt: {
    // Properties of the BigInt Constructor
    "[[Proto]]": "%FunctionPrototype%",
    asIntN: o,
    asUintN: o,
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
    toLocaleString: o,
    toString: o,
    valueOf: o,
    "@@toStringTag": "string"
  },
  "%InitialMath%": {
    ...jn,
    // `%InitialMath%.random()` has the standard unsafe behavior
    random: o
  },
  "%SharedMath%": {
    ...jn,
    // `%SharedMath%.random()` is tamed to always throw
    random: o
  },
  "%InitialDate%": {
    // Properties of the Date Constructor
    "[[Proto]]": "%FunctionPrototype%",
    now: o,
    parse: o,
    prototype: "%DatePrototype%",
    UTC: o
  },
  "%SharedDate%": {
    // Properties of the Date Constructor
    "[[Proto]]": "%FunctionPrototype%",
    // `%SharedDate%.now()` is tamed to always throw
    now: o,
    parse: o,
    prototype: "%DatePrototype%",
    UTC: o
  },
  "%DatePrototype%": {
    constructor: "%SharedDate%",
    getDate: o,
    getDay: o,
    getFullYear: o,
    getHours: o,
    getMilliseconds: o,
    getMinutes: o,
    getMonth: o,
    getSeconds: o,
    getTime: o,
    getTimezoneOffset: o,
    getUTCDate: o,
    getUTCDay: o,
    getUTCFullYear: o,
    getUTCHours: o,
    getUTCMilliseconds: o,
    getUTCMinutes: o,
    getUTCMonth: o,
    getUTCSeconds: o,
    setDate: o,
    setFullYear: o,
    setHours: o,
    setMilliseconds: o,
    setMinutes: o,
    setMonth: o,
    setSeconds: o,
    setTime: o,
    setUTCDate: o,
    setUTCFullYear: o,
    setUTCHours: o,
    setUTCMilliseconds: o,
    setUTCMinutes: o,
    setUTCMonth: o,
    setUTCSeconds: o,
    toDateString: o,
    toISOString: o,
    toJSON: o,
    toLocaleDateString: o,
    toLocaleString: o,
    toLocaleTimeString: o,
    toString: o,
    toTimeString: o,
    toUTCString: o,
    valueOf: o,
    "@@toPrimitive": o,
    // Annex B: Additional Properties of the Date.prototype Object
    getYear: o,
    setYear: o,
    toGMTString: o
  },
  // Text Processing
  String: {
    // Properties of the String Constructor
    "[[Proto]]": "%FunctionPrototype%",
    fromCharCode: o,
    fromCodePoint: o,
    prototype: "%StringPrototype%",
    raw: o,
    // See https://github.com/Moddable-OpenSource/moddable/issues/523
    fromArrayBuffer: !1
  },
  "%StringPrototype%": {
    // Properties of the String Prototype Object
    length: "number",
    at: o,
    charAt: o,
    charCodeAt: o,
    codePointAt: o,
    concat: o,
    constructor: "String",
    endsWith: o,
    includes: o,
    indexOf: o,
    lastIndexOf: o,
    localeCompare: o,
    match: o,
    matchAll: o,
    normalize: o,
    padEnd: o,
    padStart: o,
    repeat: o,
    replace: o,
    replaceAll: o,
    // ES2021
    search: o,
    slice: o,
    split: o,
    startsWith: o,
    substring: o,
    toLocaleLowerCase: o,
    toLocaleUpperCase: o,
    toLowerCase: o,
    toString: o,
    toUpperCase: o,
    trim: o,
    trimEnd: o,
    trimStart: o,
    valueOf: o,
    "@@iterator": o,
    // Annex B: Additional Properties of the String.prototype Object
    substr: o,
    anchor: o,
    big: o,
    blink: o,
    bold: o,
    fixed: o,
    fontcolor: o,
    fontsize: o,
    italics: o,
    link: o,
    small: o,
    strike: o,
    sub: o,
    sup: o,
    trimLeft: o,
    trimRight: o,
    // See https://github.com/Moddable-OpenSource/moddable/issues/523
    compare: !1,
    // https://github.com/tc39/proposal-is-usv-string
    isWellFormed: o,
    toWellFormed: o,
    unicodeSets: o,
    // Seen on QuickJS
    __quote: !1
  },
  "%StringIteratorPrototype%": {
    "[[Proto]]": "%IteratorPrototype%",
    next: o,
    "@@toStringTag": "string"
  },
  "%InitialRegExp%": {
    // Properties of the RegExp Constructor
    "[[Proto]]": "%FunctionPrototype%",
    prototype: "%RegExpPrototype%",
    "@@species": O,
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
    "@@species": O
  },
  "%RegExpPrototype%": {
    // Properties of the RegExp Prototype Object
    constructor: "%SharedRegExp%",
    exec: o,
    dotAll: O,
    flags: O,
    global: O,
    hasIndices: O,
    ignoreCase: O,
    "@@match": o,
    "@@matchAll": o,
    multiline: O,
    "@@replace": o,
    "@@search": o,
    source: O,
    "@@split": o,
    sticky: O,
    test: o,
    toString: o,
    unicode: O,
    unicodeSets: O,
    // Annex B: Additional Properties of the RegExp.prototype Object
    compile: !1
    // UNSAFE and suppressed.
  },
  "%RegExpStringIteratorPrototype%": {
    // The %RegExpStringIteratorPrototype% Object
    "[[Proto]]": "%IteratorPrototype%",
    next: o,
    "@@toStringTag": "string"
  },
  // Indexed Collections
  Array: {
    // Properties of the Array Constructor
    "[[Proto]]": "%FunctionPrototype%",
    from: o,
    isArray: o,
    of: o,
    prototype: "%ArrayPrototype%",
    "@@species": O,
    // Stage 3:
    // https://tc39.es/proposal-relative-indexing-method/
    at: o,
    // https://tc39.es/proposal-array-from-async/
    fromAsync: o
  },
  "%ArrayPrototype%": {
    // Properties of the Array Prototype Object
    at: o,
    length: "number",
    concat: o,
    constructor: "Array",
    copyWithin: o,
    entries: o,
    every: o,
    fill: o,
    filter: o,
    find: o,
    findIndex: o,
    flat: o,
    flatMap: o,
    forEach: o,
    includes: o,
    indexOf: o,
    join: o,
    keys: o,
    lastIndexOf: o,
    map: o,
    pop: o,
    push: o,
    reduce: o,
    reduceRight: o,
    reverse: o,
    shift: o,
    slice: o,
    some: o,
    sort: o,
    splice: o,
    toLocaleString: o,
    toString: o,
    unshift: o,
    values: o,
    "@@iterator": o,
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
    findLast: o,
    findLastIndex: o,
    // https://github.com/tc39/proposal-change-array-by-copy
    toReversed: o,
    toSorted: o,
    toSpliced: o,
    with: o,
    // https://github.com/tc39/proposal-array-grouping
    group: o,
    // Not in proposal? Where?
    groupToMap: o,
    // Not in proposal? Where?
    groupBy: o
  },
  "%ArrayIteratorPrototype%": {
    // The %ArrayIteratorPrototype% Object
    "[[Proto]]": "%IteratorPrototype%",
    next: o,
    "@@toStringTag": "string"
  },
  // *** TypedArray Objects
  "%TypedArray%": {
    // Properties of the %TypedArray% Intrinsic Object
    "[[Proto]]": "%FunctionPrototype%",
    from: o,
    of: o,
    prototype: "%TypedArrayPrototype%",
    "@@species": O
  },
  "%TypedArrayPrototype%": {
    at: o,
    buffer: O,
    byteLength: O,
    byteOffset: O,
    constructor: "%TypedArray%",
    copyWithin: o,
    entries: o,
    every: o,
    fill: o,
    filter: o,
    find: o,
    findIndex: o,
    forEach: o,
    includes: o,
    indexOf: o,
    join: o,
    keys: o,
    lastIndexOf: o,
    length: O,
    map: o,
    reduce: o,
    reduceRight: o,
    reverse: o,
    set: o,
    slice: o,
    some: o,
    sort: o,
    subarray: o,
    toLocaleString: o,
    toString: o,
    values: o,
    "@@iterator": o,
    "@@toStringTag": O,
    // See https://github.com/tc39/proposal-array-find-from-last
    findLast: o,
    findLastIndex: o,
    // https://github.com/tc39/proposal-change-array-by-copy
    toReversed: o,
    toSorted: o,
    with: o
  },
  // The TypedArray Constructors
  BigInt64Array: ye("%BigInt64ArrayPrototype%"),
  BigUint64Array: ye("%BigUint64ArrayPrototype%"),
  // https://github.com/tc39/proposal-float16array
  Float16Array: ye("%Float16ArrayPrototype%"),
  Float32Array: ye("%Float32ArrayPrototype%"),
  Float64Array: ye("%Float64ArrayPrototype%"),
  Int16Array: ye("%Int16ArrayPrototype%"),
  Int32Array: ye("%Int32ArrayPrototype%"),
  Int8Array: ye("%Int8ArrayPrototype%"),
  Uint16Array: ye("%Uint16ArrayPrototype%"),
  Uint32Array: ye("%Uint32ArrayPrototype%"),
  Uint8Array: ye("%Uint8ArrayPrototype%"),
  Uint8ClampedArray: ye("%Uint8ClampedArrayPrototype%"),
  "%BigInt64ArrayPrototype%": ge("BigInt64Array"),
  "%BigUint64ArrayPrototype%": ge("BigUint64Array"),
  // https://github.com/tc39/proposal-float16array
  "%Float16ArrayPrototype%": ge("Float16Array"),
  "%Float32ArrayPrototype%": ge("Float32Array"),
  "%Float64ArrayPrototype%": ge("Float64Array"),
  "%Int16ArrayPrototype%": ge("Int16Array"),
  "%Int32ArrayPrototype%": ge("Int32Array"),
  "%Int8ArrayPrototype%": ge("Int8Array"),
  "%Uint16ArrayPrototype%": ge("Uint16Array"),
  "%Uint32ArrayPrototype%": ge("Uint32Array"),
  "%Uint8ArrayPrototype%": ge("Uint8Array"),
  "%Uint8ClampedArrayPrototype%": ge("Uint8ClampedArray"),
  // *** Keyed Collections
  Map: {
    // Properties of the Map Constructor
    "[[Proto]]": "%FunctionPrototype%",
    "@@species": O,
    prototype: "%MapPrototype%",
    // https://github.com/tc39/proposal-array-grouping
    groupBy: o
  },
  "%MapPrototype%": {
    clear: o,
    constructor: "Map",
    delete: o,
    entries: o,
    forEach: o,
    get: o,
    has: o,
    keys: o,
    set: o,
    size: O,
    values: o,
    "@@iterator": o,
    "@@toStringTag": "string"
  },
  "%MapIteratorPrototype%": {
    // The %MapIteratorPrototype% Object
    "[[Proto]]": "%IteratorPrototype%",
    next: o,
    "@@toStringTag": "string"
  },
  Set: {
    // Properties of the Set Constructor
    "[[Proto]]": "%FunctionPrototype%",
    prototype: "%SetPrototype%",
    "@@species": O,
    // Seen on QuickJS
    groupBy: !1
  },
  "%SetPrototype%": {
    add: o,
    clear: o,
    constructor: "Set",
    delete: o,
    entries: o,
    forEach: o,
    has: o,
    keys: o,
    size: O,
    values: o,
    "@@iterator": o,
    "@@toStringTag": "string",
    // See https://github.com/tc39/proposal-set-methods
    intersection: o,
    // See https://github.com/tc39/proposal-set-methods
    union: o,
    // See https://github.com/tc39/proposal-set-methods
    difference: o,
    // See https://github.com/tc39/proposal-set-methods
    symmetricDifference: o,
    // See https://github.com/tc39/proposal-set-methods
    isSubsetOf: o,
    // See https://github.com/tc39/proposal-set-methods
    isSupersetOf: o,
    // See https://github.com/tc39/proposal-set-methods
    isDisjointFrom: o
  },
  "%SetIteratorPrototype%": {
    // The %SetIteratorPrototype% Object
    "[[Proto]]": "%IteratorPrototype%",
    next: o,
    "@@toStringTag": "string"
  },
  WeakMap: {
    // Properties of the WeakMap Constructor
    "[[Proto]]": "%FunctionPrototype%",
    prototype: "%WeakMapPrototype%"
  },
  "%WeakMapPrototype%": {
    constructor: "WeakMap",
    delete: o,
    get: o,
    has: o,
    set: o,
    "@@toStringTag": "string"
  },
  WeakSet: {
    // Properties of the WeakSet Constructor
    "[[Proto]]": "%FunctionPrototype%",
    prototype: "%WeakSetPrototype%"
  },
  "%WeakSetPrototype%": {
    add: o,
    constructor: "WeakSet",
    delete: o,
    has: o,
    "@@toStringTag": "string"
  },
  // *** Structured Data
  ArrayBuffer: {
    // Properties of the ArrayBuffer Constructor
    "[[Proto]]": "%FunctionPrototype%",
    isView: o,
    prototype: "%ArrayBufferPrototype%",
    "@@species": O,
    // See https://github.com/Moddable-OpenSource/moddable/issues/523
    fromString: !1,
    // See https://github.com/Moddable-OpenSource/moddable/issues/523
    fromBigInt: !1
  },
  "%ArrayBufferPrototype%": {
    byteLength: O,
    constructor: "ArrayBuffer",
    slice: o,
    "@@toStringTag": "string",
    // See https://github.com/Moddable-OpenSource/moddable/issues/523
    concat: !1,
    // See https://github.com/tc39/proposal-resizablearraybuffer
    transfer: o,
    resize: o,
    resizable: O,
    maxByteLength: O,
    // https://github.com/tc39/proposal-arraybuffer-transfer
    transferToFixedLength: o,
    detached: O
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
    buffer: O,
    byteLength: O,
    byteOffset: O,
    constructor: "DataView",
    getBigInt64: o,
    getBigUint64: o,
    // https://github.com/tc39/proposal-float16array
    getFloat16: o,
    getFloat32: o,
    getFloat64: o,
    getInt8: o,
    getInt16: o,
    getInt32: o,
    getUint8: o,
    getUint16: o,
    getUint32: o,
    setBigInt64: o,
    setBigUint64: o,
    // https://github.com/tc39/proposal-float16array
    setFloat16: o,
    setFloat32: o,
    setFloat64: o,
    setInt8: o,
    setInt16: o,
    setInt32: o,
    setUint8: o,
    setUint16: o,
    setUint32: o,
    "@@toStringTag": "string"
  },
  // Atomics
  Atomics: !1,
  // UNSAFE and suppressed.
  JSON: {
    parse: o,
    stringify: o,
    "@@toStringTag": "string",
    // https://github.com/tc39/proposal-json-parse-with-source/
    rawJSON: o,
    isRawJSON: o
  },
  // *** Control Abstraction Objects
  // https://github.com/tc39/proposal-iterator-helpers
  Iterator: {
    // Properties of the Iterator Constructor
    "[[Proto]]": "%FunctionPrototype%",
    prototype: "%IteratorPrototype%",
    from: o
  },
  "%IteratorPrototype%": {
    // The %IteratorPrototype% Object
    "@@iterator": o,
    // https://github.com/tc39/proposal-iterator-helpers
    constructor: "Iterator",
    map: o,
    filter: o,
    take: o,
    drop: o,
    flatMap: o,
    reduce: o,
    toArray: o,
    forEach: o,
    some: o,
    every: o,
    find: o,
    "@@toStringTag": "string",
    // https://github.com/tc39/proposal-async-iterator-helpers
    toAsync: o,
    // See https://github.com/Moddable-OpenSource/moddable/issues/523#issuecomment-1942904505
    "@@dispose": !1
  },
  // https://github.com/tc39/proposal-iterator-helpers
  "%WrapForValidIteratorPrototype%": {
    "[[Proto]]": "%IteratorPrototype%",
    next: o,
    return: o
  },
  // https://github.com/tc39/proposal-iterator-helpers
  "%IteratorHelperPrototype%": {
    "[[Proto]]": "%IteratorPrototype%",
    next: o,
    return: o,
    "@@toStringTag": "string"
  },
  // https://github.com/tc39/proposal-async-iterator-helpers
  AsyncIterator: {
    // Properties of the Iterator Constructor
    "[[Proto]]": "%FunctionPrototype%",
    prototype: "%AsyncIteratorPrototype%",
    from: o
  },
  "%AsyncIteratorPrototype%": {
    // The %AsyncIteratorPrototype% Object
    "@@asyncIterator": o,
    // https://github.com/tc39/proposal-async-iterator-helpers
    constructor: "AsyncIterator",
    map: o,
    filter: o,
    take: o,
    drop: o,
    flatMap: o,
    reduce: o,
    toArray: o,
    forEach: o,
    some: o,
    every: o,
    find: o,
    "@@toStringTag": "string",
    // See https://github.com/Moddable-OpenSource/moddable/issues/523#issuecomment-1942904505
    "@@asyncDispose": !1
  },
  // https://github.com/tc39/proposal-async-iterator-helpers
  "%WrapForValidAsyncIteratorPrototype%": {
    "[[Proto]]": "%AsyncIteratorPrototype%",
    next: o,
    return: o
  },
  // https://github.com/tc39/proposal-async-iterator-helpers
  "%AsyncIteratorHelperPrototype%": {
    "[[Proto]]": "%AsyncIteratorPrototype%",
    next: o,
    return: o,
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
    next: o,
    return: o,
    throw: o,
    "@@toStringTag": "string"
  },
  "%AsyncGeneratorPrototype%": {
    // Properties of the AsyncGenerator Prototype Object
    "[[Proto]]": "%AsyncIteratorPrototype%",
    constructor: "%AsyncGenerator%",
    next: o,
    return: o,
    throw: o,
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
    applyFunction: o,
    applyFunctionSendOnly: o,
    applyMethod: o,
    applyMethodSendOnly: o,
    get: o,
    getSendOnly: o,
    prototype: "%PromisePrototype%",
    resolve: o
  },
  Promise: {
    // Properties of the Promise Constructor
    "[[Proto]]": "%FunctionPrototype%",
    all: o,
    allSettled: o,
    // To transition from `false` to `fn` once we also have `AggregateError`
    // TODO https://github.com/Agoric/SES-shim/issues/550
    any: !1,
    // ES2021
    prototype: "%PromisePrototype%",
    race: o,
    reject: o,
    resolve: o,
    // https://github.com/tc39/proposal-promise-with-resolvers
    withResolvers: o,
    "@@species": O
  },
  "%PromisePrototype%": {
    // Properties of the Promise Prototype Object
    catch: o,
    constructor: "Promise",
    finally: o,
    then: o,
    "@@toStringTag": "string",
    // Non-standard, used in node to prevent async_hooks from breaking
    "UniqueSymbol(async_id_symbol)": Ae,
    "UniqueSymbol(trigger_async_id_symbol)": Ae,
    "UniqueSymbol(destroyed)": Ae
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
    apply: o,
    construct: o,
    defineProperty: o,
    deleteProperty: o,
    get: o,
    getOwnPropertyDescriptor: o,
    getPrototypeOf: o,
    has: o,
    isExtensible: o,
    ownKeys: o,
    preventExtensions: o,
    set: o,
    setPrototypeOf: o,
    "@@toStringTag": "string"
  },
  Proxy: {
    // Properties of the Proxy Constructor
    "[[Proto]]": "%FunctionPrototype%",
    revocable: o
  },
  // Appendix B
  // Annex B: Additional Properties of the Global Object
  escape: o,
  unescape: o,
  // Proposed
  "%UniqueCompartment%": {
    "[[Proto]]": "%FunctionPrototype%",
    prototype: "%CompartmentPrototype%",
    toString: o
  },
  "%InertCompartment%": {
    "[[Proto]]": "%FunctionPrototype%",
    prototype: "%CompartmentPrototype%",
    toString: o
  },
  "%CompartmentPrototype%": {
    constructor: "%InertCompartment%",
    evaluate: o,
    globalThis: O,
    name: O,
    import: Dn,
    load: Dn,
    importNow: o,
    module: o,
    "@@toStringTag": "string"
  },
  lockdown: o,
  harden: { ...o, isFake: "boolean" },
  "%InitialGetStackString%": o
}, Pa = (t) => typeof t == "function";
function ka(t, e, r) {
  if (se(t, e)) {
    const n = de(t, e);
    if (!n || !kr(n.value, r.value) || n.get !== r.get || n.set !== r.set || n.writable !== r.writable || n.enumerable !== r.enumerable || n.configurable !== r.configurable)
      throw v(`Conflicting definitions of ${e}`);
  }
  L(t, e, r);
}
function Ta(t, e) {
  for (const [r, n] of te(e))
    ka(t, r, n);
}
function Uo(t, e) {
  const r = { __proto__: null };
  for (const [n, a] of te(e))
    se(t, n) && (r[a] = t[n]);
  return r;
}
const jo = () => {
  const t = V(null);
  let e;
  const r = (c) => {
    Ta(t, Je(c));
  };
  g(r);
  const n = () => {
    for (const [c, u] of te(t)) {
      if (!We(u) || !se(u, "prototype"))
        continue;
      const l = fr[c];
      if (typeof l != "object")
        throw v(`Expected permit object at whitelist.${c}`);
      const d = l.prototype;
      if (!d)
        throw v(`${c}.prototype property not whitelisted`);
      if (typeof d != "string" || !se(fr, d))
        throw v(`Unrecognized ${c}.prototype whitelist entry`);
      const f = u.prototype;
      if (se(t, d)) {
        if (t[d] !== f)
          throw v(`Conflicting bindings of ${d}`);
        continue;
      }
      t[d] = f;
    }
  };
  g(n);
  const a = () => (g(t), e = new kt(Ve(fo(t), Pa)), t);
  g(a);
  const s = (c) => {
    if (!e)
      throw v(
        "isPseudoNative can only be called after finalIntrinsics"
      );
    return Xt(e, c);
  };
  g(s);
  const i = {
    addIntrinsics: r,
    completePrototypes: n,
    finalIntrinsics: a,
    isPseudoNative: s
  };
  return g(i), r(Lo), r(Uo(x, Fo)), i;
}, Ia = (t) => {
  const { addIntrinsics: e, finalIntrinsics: r } = jo();
  return e(Uo(t, Do)), r();
};
function Aa(t, e) {
  let r = !1;
  const n = (m, ...p) => (r || (console.groupCollapsed("Removing unpermitted intrinsics"), r = !0), console[m](...p)), a = ["undefined", "boolean", "number", "string", "symbol"], s = new Ce(
    Ot ? fe(
      Ve(
        te(fr["%SharedSymbol%"]),
        ([m, p]) => p === "symbol" && typeof Ot[m] == "symbol"
      ),
      ([m]) => [Ot[m], `@@${m}`]
    ) : []
  );
  function i(m, p) {
    if (typeof p == "string")
      return p;
    const h = De(s, p);
    if (typeof p == "symbol") {
      if (h)
        return h;
      {
        const _ = Ms(p);
        return _ !== void 0 ? `RegisteredSymbol(${_})` : `Unique${ie(p)}`;
      }
    }
    throw v(`Unexpected property name type ${m} ${p}`);
  }
  function c(m, p, h) {
    if (!We(p))
      throw v(`Object expected: ${m}, ${p}, ${h}`);
    const _ = H(p);
    if (!(_ === null && h === null)) {
      if (h !== void 0 && typeof h != "string")
        throw v(`Malformed whitelist permit ${m}.__proto__`);
      if (_ !== t[h || "%ObjectPrototype%"])
        throw v(`Unexpected intrinsic ${m}.__proto__ at ${h}`);
    }
  }
  function u(m, p, h, _) {
    if (typeof _ == "object")
      return f(m, p, _), !0;
    if (_ === !1)
      return !1;
    if (typeof _ == "string") {
      if (h === "prototype" || h === "constructor") {
        if (se(t, _)) {
          if (p !== t[_])
            throw v(`Does not match whitelist ${m}`);
          return !0;
        }
      } else if (Ar(a, _)) {
        if (typeof p !== _)
          throw v(
            `At ${m} expected ${_} not ${typeof p}`
          );
        return !0;
      }
    }
    throw v(`Unexpected whitelist permit ${_} at ${m}`);
  }
  function l(m, p, h, _) {
    const w = de(p, h);
    if (!w)
      throw v(`Property ${h} not found at ${m}`);
    if (se(w, "value")) {
      if (Un(_))
        throw v(`Accessor expected at ${m}`);
      return u(m, w.value, h, _);
    }
    if (!Un(_))
      throw v(`Accessor not expected at ${m}`);
    return u(`${m}<get>`, w.get, h, _.get) && u(`${m}<set>`, w.set, h, _.set);
  }
  function d(m, p, h) {
    const _ = h === "__proto__" ? "--proto--" : h;
    if (se(p, _))
      return p[_];
    if (typeof m == "function" && se(Jr, _))
      return Jr[_];
  }
  function f(m, p, h) {
    if (p == null)
      return;
    const _ = h["[[Proto]]"];
    c(m, p, _), typeof p == "function" && e(p);
    for (const w of st(p)) {
      const I = i(m, w), $ = `${m}.${I}`, T = d(p, h, I);
      if (!T || !l($, p, w, T)) {
        T !== !1 && n("warn", `Removing ${$}`);
        try {
          delete p[w];
        } catch (D) {
          if (w in p) {
            if (typeof p == "function" && w === "prototype" && (p.prototype = void 0, p.prototype === void 0)) {
              n(
                "warn",
                `Tolerating undeletable ${$} === undefined`
              );
              continue;
            }
            n("error", `failed to delete ${$}`, D);
          } else
            n("error", `deleting ${$} threw`, D);
          throw D;
        }
      }
    }
  }
  try {
    f("intrinsics", t, fr);
  } finally {
    r && console.groupEnd();
  }
}
function Ca() {
  try {
    ve.prototype.constructor("return 1");
  } catch {
    return g({});
  }
  const t = {};
  function e(r, n, a) {
    let s;
    try {
      s = (0, eval)(a);
    } catch (u) {
      if (u instanceof Kt)
        return;
      throw u;
    }
    const i = H(s), c = function() {
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
    }), c !== ve.prototype.constructor && uo(c, ve.prototype.constructor), t[n] = c;
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
function $a(t = "safe") {
  if (t !== "safe" && t !== "unsafe")
    throw v(`unrecognized dateTaming ${t}`);
  const e = ks, r = e.prototype, n = {
    /**
     * `%SharedDate%.now()` throw a `TypeError` starting with "secure mode".
     * See https://github.com/endojs/endo/issues/910#issuecomment-1581855420
     */
    now() {
      throw v("secure mode Calling %SharedDate%.now() throws");
    }
  }, a = ({ powers: c = "none" } = {}) => {
    let u;
    return c === "original" ? u = function(...d) {
      return new.target === void 0 ? oe(e, void 0, d) : lr(e, d, new.target);
    } : u = function(...d) {
      if (new.target === void 0)
        throw v(
          "secure mode Calling %SharedDate% constructor as a function throws"
        );
      if (d.length === 0)
        throw v(
          "secure mode Calling new %SharedDate%() with no arguments throws"
        );
      return lr(e, d, new.target);
    }, F(u, {
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
    }), u;
  }, s = a({ powers: "original" }), i = a({ powers: "none" });
  return F(s, {
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
    "%InitialDate%": s,
    "%SharedDate%": i
  };
}
function Na(t = "safe") {
  if (t !== "safe" && t !== "unsafe")
    throw v(`unrecognized mathTaming ${t}`);
  const e = As, r = e, { random: n, ...a } = Je(e), i = V(lo, {
    ...a,
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
function Oa(t = "safe") {
  if (t !== "safe" && t !== "unsafe")
    throw v(`unrecognized regExpTaming ${t}`);
  const e = Be.prototype, r = (s = {}) => {
    const i = function(...l) {
      return new.target === void 0 ? Be(...l) : lr(Be, l, new.target);
    }, c = de(Be, Cn);
    if (!c)
      throw v("no RegExp[Symbol.species] descriptor");
    return F(i, {
      length: { value: 2 },
      prototype: {
        value: e,
        writable: !1,
        enumerable: !1,
        configurable: !1
      },
      [Cn]: c
    }), i;
  }, n = r(), a = r();
  return t !== "unsafe" && delete e.compile, F(e, {
    constructor: { value: a }
  }), {
    "%InitialRegExp%": n,
    "%SharedRegExp%": a
  };
}
const Ra = {
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
    [He]: !0
  }
}, Zo = {
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
    [Yt]: !0
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
    [He]: !0
  }
}, Ma = {
  ...Zo,
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
function La(t, e, r = []) {
  const n = new Pt(r);
  function a(l, d, f, m) {
    if ("value" in m && m.configurable) {
      const { value: p } = m, h = dn(n, f), { get: _, set: w } = de(
        {
          get [f]() {
            return p;
          },
          set [f](I) {
            if (d === this)
              throw v(
                `Cannot assign to read only property '${ie(
                  f
                )}' of '${l}'`
              );
            se(this, f) ? this[f] = I : (h && console.error(v(`Override property ${f}`)), L(this, f, {
              value: I,
              writable: !0,
              enumerable: !0,
              configurable: !0
            }));
          }
        },
        f
      );
      L(_, "originalValue", {
        value: p,
        writable: !1,
        enumerable: !1,
        configurable: !1
      }), L(d, f, {
        get: _,
        set: w,
        enumerable: m.enumerable,
        configurable: m.configurable
      });
    }
  }
  function s(l, d, f) {
    const m = de(d, f);
    m && a(l, d, f, m);
  }
  function i(l, d) {
    const f = Je(d);
    f && nt(st(f), (m) => a(l, d, m, f[m]));
  }
  function c(l, d, f) {
    for (const m of st(f)) {
      const p = de(d, m);
      if (!p || p.get || p.set)
        continue;
      const h = `${l}.${ie(m)}`, _ = f[m];
      if (_ === !0)
        s(h, d, m);
      else if (_ === "*")
        i(h, p.value);
      else if (We(_))
        c(h, p.value, _);
      else
        throw v(`Unexpected override enablement plan ${h}`);
    }
  }
  let u;
  switch (e) {
    case "min": {
      u = Ra;
      break;
    }
    case "moderate": {
      u = Zo;
      break;
    }
    case "severe": {
      u = Ma;
      break;
    }
    default:
      throw v(`unrecognized overrideTaming ${e}`);
  }
  c("root", t, u);
}
const { Fail: Xr, quote: pr } = z, Fa = /^(\w*[a-z])Locale([A-Z]\w*)$/, zo = {
  // See https://tc39.es/ecma262/#sec-string.prototype.localecompare
  localeCompare(t) {
    if (this === null || this === void 0)
      throw v(
        'Cannot localeCompare with null or undefined "this" value'
      );
    const e = `${this}`, r = `${t}`;
    return e < r ? -1 : e > r ? 1 : (e === r || Xr`expected ${pr(e)} and ${pr(r)} to compare`, 0);
  },
  toString() {
    return `${this}`;
  }
}, Da = zo.localeCompare, Ua = zo.toString;
function ja(t, e = "safe") {
  if (e !== "safe" && e !== "unsafe")
    throw v(`unrecognized localeTaming ${e}`);
  if (e !== "unsafe") {
    L(ie.prototype, "localeCompare", {
      value: Da
    });
    for (const r of Mt(t)) {
      const n = t[r];
      if (We(n))
        for (const a of Mt(n)) {
          const s = pn(Fa, a);
          if (s) {
            typeof n[a] == "function" || Xr`expected ${pr(a)} to be a function`;
            const i = `${s[1]}${s[2]}`, c = n[i];
            typeof c == "function" || Xr`function ${pr(i)} not found`, L(n, a, { value: c });
          }
        }
    }
    L(io.prototype, "toLocaleString", {
      value: Ua
    });
  }
}
const Za = (t) => ({
  eval(r) {
    return typeof r != "string" ? r : t(r);
  }
}).eval, { Fail: Zn } = z, za = (t) => {
  const e = function(n) {
    const a = `${Hr(arguments) || ""}`, s = `${At(arguments, ",")}`;
    new ve(s, ""), new ve(a);
    const i = `(function anonymous(${s}
) {
${a}
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
  }), H(ve) === ve.prototype || Zn`Function prototype is the same accross compartments`, H(e) === ve.prototype || Zn`Function constructor prototype is the same accross compartments`, e;
}, Ga = (t) => {
  L(
    t,
    Rs,
    g(
      Pr(V(null), {
        set: g(() => {
          throw v(
            "Cannot set Symbol.unscopables of a Compartment's globalThis"
          );
        }),
        enumerable: !1,
        configurable: !1
      })
    )
  );
}, Go = (t) => {
  for (const [e, r] of te(Lo))
    L(t, e, {
      value: r,
      writable: !1,
      enumerable: !1,
      configurable: !1
    });
}, Bo = (t, {
  intrinsics: e,
  newGlobalPropertyNames: r,
  makeCompartmentConstructor: n,
  markVirtualizedNativeFunction: a
}) => {
  for (const [i, c] of te(Fo))
    se(e, c) && L(t, i, {
      value: e[c],
      writable: !0,
      enumerable: !1,
      configurable: !0
    });
  for (const [i, c] of te(r))
    se(e, c) && L(t, i, {
      value: e[c],
      writable: !0,
      enumerable: !1,
      configurable: !0
    });
  const s = {
    globalThis: t
  };
  s.Compartment = g(
    n(
      n,
      e,
      a
    )
  );
  for (const [i, c] of te(s))
    L(t, i, {
      value: c,
      writable: !0,
      enumerable: !1,
      configurable: !0
    }), typeof c == "function" && a(c);
}, Qr = (t, e, r) => {
  {
    const n = g(Za(e));
    r(n), L(t, "eval", {
      value: n,
      writable: !0,
      enumerable: !1,
      configurable: !0
    });
  }
  {
    const n = g(za(e));
    r(n), L(t, "Function", {
      value: n,
      writable: !0,
      enumerable: !1,
      configurable: !0
    });
  }
}, { Fail: Ba, quote: Ho } = z, Vo = new xr(
  gn,
  g({
    get(t, e) {
      Ba`Please report unexpected scope handler trap: ${Ho(ie(e))}`;
    }
  })
), Ha = {
  get(t, e) {
  },
  set(t, e, r) {
    throw rt(`${ie(e)} is not defined`);
  },
  has(t, e) {
    return e in x;
  },
  // note: this is likely a bug of safari
  // https://bugs.webkit.org/show_bug.cgi?id=195534
  getPrototypeOf(t) {
    return null;
  },
  // See https://github.com/endojs/endo/issues/1510
  // TODO: report as bug to v8 or Chrome, and record issue link here.
  getOwnPropertyDescriptor(t, e) {
    const r = Ho(ie(e));
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
}, Wo = g(
  V(
    Vo,
    Je(Ha)
  )
), Va = new xr(
  gn,
  Wo
), qo = (t) => {
  const e = {
    // inherit scopeTerminator behavior
    ...Wo,
    // Redirect set properties to the globalObject.
    set(a, s, i) {
      return yo(t, s, i);
    },
    // Always claim to have a potential property in order to be the recipient of a set
    has(a, s) {
      return !0;
    }
  }, r = g(
    V(
      Vo,
      Je(e)
    )
  );
  return new xr(
    gn,
    r
  );
};
g(qo);
const { Fail: Wa } = z, qa = () => {
  const t = V(null), e = g({
    eval: {
      get() {
        return delete t.eval, Eo;
      },
      enumerable: !1,
      configurable: !0
    }
  }), r = {
    evalScope: t,
    allowNextEvalToBeUnsafe() {
      const { revoked: n } = r;
      n !== null && Wa`a handler did not reset allowNextEvalToBeUnsafe ${n.err}`, F(t, e);
    },
    /** @type {null | { err: any }} */
    revoked: null
  };
  return r;
}, zn = "\\s*[@#]\\s*([a-zA-Z][a-zA-Z0-9]*)\\s*=\\s*([^\\s\\*]*)", Ka = new Be(
  `(?:\\s*//${zn}|/\\*${zn}\\s*\\*/)\\s*$`
), bn = (t) => {
  let e = "<unknown>";
  for (; t.length > 0; ) {
    const r = pn(Ka, t);
    if (r === null)
      break;
    t = mn(t, 0, t.length - r[0].length), r[3] === "sourceURL" ? e = r[4] : r[1] === "sourceURL" && (e = r[2]);
  }
  return e;
};
function wn(t, e) {
  const r = Qs(t, e);
  if (r < 0)
    return -1;
  const n = t[r] === `
` ? 1 : 0;
  return wo(mn(t, 0, r), `
`).length + n;
}
const Ko = new Be("(?:<!--|-->)", "g"), Yo = (t) => {
  const e = wn(t, Ko);
  if (e < 0)
    return t;
  const r = bn(t);
  throw Kt(
    `Possible HTML comment rejected at ${r}:${e}. (SES_HTML_COMMENT_REJECTED)`
  );
}, Jo = (t) => ur(t, Ko, (r) => r[0] === "<" ? "< ! --" : "-- >"), Xo = new Be(
  "(^|[^.]|\\.\\.\\.)\\bimport(\\s*(?:\\(|/[/*]))",
  "g"
), Qo = (t) => {
  const e = wn(t, Xo);
  if (e < 0)
    return t;
  const r = bn(t);
  throw Kt(
    `Possible import expression rejected at ${r}:${e}. (SES_IMPORT_REJECTED)`
  );
}, es = (t) => ur(t, Xo, (r, n, a) => `${n}__import__${a}`), Ya = new Be(
  "(^|[^.])\\beval(\\s*\\()",
  "g"
), ts = (t) => {
  const e = wn(t, Ya);
  if (e < 0)
    return t;
  const r = bn(t);
  throw Kt(
    `Possible direct eval expression rejected at ${r}:${e}. (SES_EVAL_REJECTED)`
  );
}, rs = (t) => (t = Yo(t), t = Qo(t), t), ns = (t, e) => {
  for (const r of e)
    t = r(t);
  return t;
};
g({
  rejectHtmlComments: g(Yo),
  evadeHtmlCommentTest: g(Jo),
  rejectImportExpressions: g(Qo),
  evadeImportExpressionTest: g(es),
  rejectSomeDirectEvalExpressions: g(ts),
  mandatoryTransforms: g(rs),
  applyTransforms: g(ns)
});
const Ja = [
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
], Xa = /^[a-zA-Z_$][\w$]*$/, Gn = (t) => t !== "eval" && !Ar(Ja, t) && fn(Xa, t);
function Bn(t, e) {
  const r = de(t, e);
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
  se(r, "value");
}
const Qa = (t, e = {}) => {
  const r = Mt(t), n = Mt(e), a = Ve(
    n,
    (i) => Gn(i) && Bn(e, i)
  );
  return {
    globalObjectConstants: Ve(
      r,
      (i) => (
        // Can't define a constant: it would prevent a
        // lookup on the endowments.
        !Ar(n, i) && Gn(i) && Bn(t, i)
      )
    ),
    moduleLexicalConstants: a
  };
};
function Hn(t, e) {
  return t.length === 0 ? "" : `const {${At(t, ",")}} = this.${e};`;
}
const ei = (t) => {
  const { globalObjectConstants: e, moduleLexicalConstants: r } = Qa(
    t.globalObject,
    t.moduleLexicals
  ), n = Hn(
    e,
    "globalObject"
  ), a = Hn(
    r,
    "moduleLexicals"
  ), s = ve(`
    with (this.scopeTerminator) {
      with (this.globalObject) {
        with (this.moduleLexicals) {
          with (this.evalScope) {
            ${n}
            ${a}
            return function() {
              'use strict';
              return eval(arguments[0]);
            };
          }
        }
      }
    }
  `);
  return oe(s, t, []);
}, { Fail: ti } = z, Sn = ({
  globalObject: t,
  moduleLexicals: e = {},
  globalTransforms: r = [],
  sloppyGlobalsMode: n = !1
}) => {
  const a = n ? qo(t) : Va, s = qa(), { evalScope: i } = s, c = g({
    evalScope: i,
    moduleLexicals: e,
    globalObject: t,
    scopeTerminator: a
  });
  let u;
  const l = () => {
    u || (u = ei(c));
  };
  return { safeEvaluate: (f, m) => {
    const { localTransforms: p = [] } = m || {};
    l(), f = ns(f, [
      ...p,
      ...r,
      rs
    ]);
    let h;
    try {
      return s.allowNextEvalToBeUnsafe(), oe(u, t, [f]);
    } catch (_) {
      throw h = _, _;
    } finally {
      const _ = "eval" in i;
      delete i.eval, _ && (s.revoked = { err: h }, ti`handler did not reset allowNextEvalToBeUnsafe ${h}`);
    }
  } };
}, ri = ") { [native code] }";
let jr;
const os = () => {
  if (jr === void 0) {
    const t = new kt();
    L(un, "toString", {
      value: {
        toString() {
          const r = ra(this);
          return bo(r, ri) || !Xt(t, this) ? r : `function ${this.name}() { [native code] }`;
        }
      }.toString
    }), jr = g(
      (r) => Nr(t, r)
    );
  }
  return jr;
};
function ni(t = "safe") {
  if (t !== "safe" && t !== "unsafe")
    throw v(`unrecognized domainTaming ${t}`);
  if (t === "unsafe")
    return;
  const e = x.process || void 0;
  if (typeof e == "object") {
    const r = de(e, "domain");
    if (r !== void 0 && r.get !== void 0)
      throw v(
        "SES failed to lockdown, Node.js domains have been initialized (SES_NO_DOMAINS)"
      );
    L(e, "domain", {
      value: null,
      configurable: !1,
      writable: !1,
      enumerable: !1
    });
  }
}
const ss = g([
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
  // (fmt?, ...args)
  ["group", "log"],
  // (fmt?, ...args)
  ["groupCollapsed", "log"]
  // (fmt?, ...args)
]), as = g([
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
]), is = g([
  ...ss,
  ...as
]), oi = (t, { shouldResetForDebugging: e = !1 } = {}) => {
  e && t.resetErrorTagNum();
  let r = [];
  const n = Tt(
    fe(is, ([i, c]) => {
      const u = (...l) => {
        ae(r, [i, ...l]);
      };
      return L(u, "name", { value: i }), [i, g(u)];
    })
  );
  g(n);
  const a = () => {
    const i = g(r);
    return r = [], i;
  };
  return g(a), g({ loggingConsole: (
    /** @type {VirtualConsole} */
    n
  ), takeLog: a });
};
g(oi);
const $t = {
  NOTE: "ERROR_NOTE:",
  MESSAGE: "ERROR_MESSAGE:"
};
g($t);
const cs = (t, e) => {
  if (!t)
    return;
  const { getStackString: r, tagError: n, takeMessageLogArgs: a, takeNoteLogArgsArray: s } = e, i = (w, I) => fe(w, (T) => vn(T) ? (ae(I, T), `(${n(T)})`) : T), c = (w, I, $, T, D) => {
    const Z = n(I), q = $ === $t.MESSAGE ? `${Z}:` : `${Z} ${$}`, K = i(T, D);
    t[w](q, ...K);
  }, u = (w, I, $ = void 0) => {
    if (I.length === 0)
      return;
    if (I.length === 1 && $ === void 0) {
      f(w, I[0]);
      return;
    }
    let T;
    I.length === 1 ? T = "Nested error" : T = `Nested ${I.length} errors`, $ !== void 0 && (T = `${T} under ${$}`), t.group(T);
    try {
      for (const D of I)
        f(w, D);
    } finally {
      t.groupEnd();
    }
  }, l = new kt(), d = (w) => (I, $) => {
    const T = [];
    c(w, I, $t.NOTE, $, T), u(w, T, n(I));
  }, f = (w, I) => {
    if (Xt(l, I))
      return;
    const $ = n(I);
    Nr(l, I);
    const T = [], D = a(I), Z = s(
      I,
      d(w)
    );
    D === void 0 ? t[w](`${$}:`, I.message) : c(
      w,
      I,
      $t.MESSAGE,
      D,
      T
    );
    let q = r(I);
    typeof q == "string" && q.length >= 1 && !bo(q, `
`) && (q += `
`), t[w](q);
    for (const K of Z)
      c(w, I, $t.NOTE, K, T);
    u(w, T, $);
  }, m = fe(ss, ([w, I]) => {
    const $ = (...T) => {
      const D = [], Z = i(T, D);
      t[w](...Z), u(w, D);
    };
    return L($, "name", { value: w }), [w, g($)];
  }), p = Ve(
    as,
    ([w, I]) => w in t
  ), h = fe(p, ([w, I]) => {
    const $ = (...T) => {
      t[w](...T);
    };
    return L($, "name", { value: w }), [w, g($)];
  }), _ = Tt([...m, ...h]);
  return (
    /** @type {VirtualConsole} */
    g(_)
  );
};
g(cs);
const si = (t, e, r = void 0) => {
  const n = Ve(
    is,
    ([i, c]) => i in t
  ), a = fe(n, ([i, c]) => [i, g((...l) => {
    (c === void 0 || e.canLog(c)) && t[i](...l);
  })]), s = Tt(a);
  return (
    /** @type {VirtualConsole} */
    g(s)
  );
};
g(si);
const Vn = (t) => {
  if (bt === void 0)
    return;
  let e = 0;
  const r = new Ce(), n = (d) => {
    Vs(r, d);
  }, a = new Te(), s = (d) => {
    if (Cr(r, d)) {
      const f = De(r, d);
      n(d), t(f);
    }
  }, i = new bt(s);
  return {
    rejectionHandledHandler: (d) => {
      const f = M(a, d);
      n(f);
    },
    unhandledRejectionHandler: (d, f) => {
      e += 1;
      const m = e;
      $e(r, m, d), ee(a, f, m), oa(i, f, m, f);
    },
    processTerminationHandler: () => {
      for (const [d, f] of Ws(r))
        n(d), t(f);
    }
  };
}, Zr = (t) => {
  throw v(t);
}, Wn = (t, e) => g((...r) => oe(t, e, r)), ai = (t = "safe", e = "platform", r = "report", n = void 0) => {
  t === "safe" || t === "unsafe" || Zr(`unrecognized consoleTaming ${t}`);
  let a;
  n === void 0 ? a = Yr : a = {
    ...Yr,
    getStackString: n
  };
  const s = (
    /** @type {VirtualConsole} */
    // eslint-disable-next-line no-nested-ternary
    typeof x.console < "u" ? x.console : typeof x.print == "function" ? (
      // Make a good-enough console for eshost (including only functions that
      // log at a specific level with no special argument interpretation).
      // https://console.spec.whatwg.org/#logging
      ((l) => g({ debug: l, log: l, info: l, warn: l, error: l }))(
        // eslint-disable-next-line no-undef
        Wn(x.print)
      )
    ) : void 0
  );
  if (s && s.log)
    for (const l of ["warn", "error"])
      s[l] || L(s, l, {
        value: Wn(s.log, s)
      });
  const i = (
    /** @type {VirtualConsole} */
    t === "unsafe" ? s : cs(s, a)
  ), c = x.process || void 0;
  if (e !== "none" && typeof c == "object" && typeof c.on == "function") {
    let l;
    if (e === "platform" || e === "exit") {
      const { exit: d } = c;
      typeof d == "function" || Zr("missing process.exit"), l = () => d(c.exitCode || -1);
    } else
      e === "abort" && (l = c.abort, typeof l == "function" || Zr("missing process.abort"));
    c.on("uncaughtException", (d) => {
      i.error(d), l && l();
    });
  }
  if (r !== "none" && typeof c == "object" && typeof c.on == "function") {
    const d = Vn((f) => {
      i.error("SES_UNHANDLED_REJECTION:", f);
    });
    d && (c.on("unhandledRejection", d.unhandledRejectionHandler), c.on("rejectionHandled", d.rejectionHandledHandler), c.on("exit", d.processTerminationHandler));
  }
  const u = x.window || void 0;
  if (e !== "none" && typeof u == "object" && typeof u.addEventListener == "function" && u.addEventListener("error", (l) => {
    l.preventDefault(), i.error(l.error), (e === "exit" || e === "abort") && (u.location.href = "about:blank");
  }), r !== "none" && typeof u == "object" && typeof u.addEventListener == "function") {
    const d = Vn((f) => {
      i.error("SES_UNHANDLED_REJECTION:", f);
    });
    d && (u.addEventListener("unhandledrejection", (f) => {
      f.preventDefault(), d.unhandledRejectionHandler(f.reason, f.promise);
    }), u.addEventListener("rejectionhandled", (f) => {
      f.preventDefault(), d.rejectionHandledHandler(f.promise);
    }), u.addEventListener("beforeunload", (f) => {
      d.processTerminationHandler();
    }));
  }
  return { console: i };
}, ii = [
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
], ci = (t) => {
  const r = Tt(fe(ii, (n) => {
    const a = t[n];
    return [n, () => oe(a, t, [])];
  }));
  return V(r, {});
}, li = (t) => fe(t, ci), ui = /\/node_modules\//, di = /^(?:node:)?internal\//, fi = /\/packages\/ses\/src\/error\/assert.js$/, pi = /\/packages\/eventual-send\/src\//, mi = [
  ui,
  di,
  fi,
  pi
], hi = (t) => {
  if (!t)
    return !0;
  for (const e of mi)
    if (fn(e, t))
      return !1;
  return !0;
}, yi = /^((?:.*[( ])?)[:/\w_-]*\/\.\.\.\/(.+)$/, gi = /^((?:.*[( ])?)[:/\w_-]*\/(packages\/.+)$/, vi = [
  yi,
  gi
], _i = (t) => {
  for (const e of vi) {
    const r = pn(e, t);
    if (r)
      return At(Gs(r, 1), "");
  }
  return t;
}, bi = (t, e, r, n) => {
  const a = t.captureStackTrace, s = (p) => n === "verbose" ? !0 : hi(p.getFileName()), i = (p) => {
    let h = `${p}`;
    return n === "concise" && (h = _i(h)), `
  at ${h}`;
  }, c = (p, h) => At(
    fe(Ve(h, s), i),
    ""
  ), u = new Te(), l = {
    // The optional `optFn` argument is for cutting off the bottom of
    // the stack --- for capturing the stack only above the topmost
    // call to that function. Since this isn't the "real" captureStackTrace
    // but instead calls the real one, if no other cutoff is provided,
    // we cut this one off.
    captureStackTrace(p, h = l.captureStackTrace) {
      if (typeof a == "function") {
        oe(a, t, [p, h]);
        return;
      }
      yo(p, "stack", "");
    },
    // Shim of proposed special power, to reside by default only
    // in the start compartment, for getting the stack traceback
    // string associated with an error.
    // See https://tc39.es/proposal-error-stacks/
    getStackString(p) {
      let h = M(u, p);
      if (h === void 0 && (p.stack, h = M(u, p), h || (h = { stackString: "" }, ee(u, p, h))), h.stackString !== void 0)
        return h.stackString;
      const _ = c(p, h.callSites);
      return ee(u, p, { stackString: _ }), _;
    },
    prepareStackTrace(p, h) {
      if (r === "unsafe") {
        const _ = c(p, h);
        return ee(u, p, { stackString: _ }), `${p}${_}`;
      } else
        return ee(u, p, { callSites: h }), "";
    }
  }, d = l.prepareStackTrace;
  t.prepareStackTrace = d;
  const f = new kt([d]), m = (p) => {
    if (Xt(f, p))
      return p;
    const h = {
      prepareStackTrace(_, w) {
        return ee(u, _, { callSites: w }), p(_, li(w));
      }
    };
    return Nr(f, h.prepareStackTrace), h.prepareStackTrace;
  };
  return F(e, {
    captureStackTrace: {
      value: l.captureStackTrace,
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
          const h = m(p);
          t.prepareStackTrace = h;
        } else
          t.prepareStackTrace = d;
      },
      enumerable: !1,
      configurable: !0
    }
  }), l.getStackString;
}, qn = de(le.prototype, "stack"), Kn = qn && qn.get, wi = {
  getStackString(t) {
    return typeof Kn == "function" ? oe(Kn, t, []) : "stack" in t ? `${t.stack}` : "";
  }
};
function Si(t = "safe", e = "concise") {
  if (t !== "safe" && t !== "unsafe")
    throw v(`unrecognized errorTaming ${t}`);
  if (e !== "concise" && e !== "verbose")
    throw v(`unrecognized stackFiltering ${e}`);
  const r = le.prototype, n = typeof le.captureStackTrace == "function" ? "v8" : "unknown", { captureStackTrace: a } = le, s = (l = {}) => {
    const d = function(...m) {
      let p;
      return new.target === void 0 ? p = oe(le, this, m) : p = lr(le, m, new.target), n === "v8" && oe(a, le, [p, d]), p;
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
  }, i = s({ powers: "original" }), c = s({ powers: "none" });
  F(r, {
    constructor: { value: c }
  });
  for (const l of Ea)
    uo(l, c);
  F(i, {
    stackTraceLimit: {
      get() {
        if (typeof le.stackTraceLimit == "number")
          return le.stackTraceLimit;
      },
      set(l) {
        if (typeof l == "number" && typeof le.stackTraceLimit == "number") {
          le.stackTraceLimit = l;
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
      set(l) {
      },
      enumerable: !1,
      configurable: !0
    }
  }), n === "v8" && F(c, {
    prepareStackTrace: {
      get() {
        return () => "";
      },
      set(l) {
      },
      enumerable: !1,
      configurable: !0
    },
    captureStackTrace: {
      value: (l, d) => {
        L(l, "stack", {
          value: ""
        });
      },
      writable: !1,
      enumerable: !1,
      configurable: !0
    }
  });
  let u = wi.getStackString;
  return n === "v8" ? u = bi(
    le,
    i,
    t,
    e
  ) : t === "unsafe" ? F(r, {
    stack: {
      get() {
        return u(this);
      },
      set(l) {
        F(this, {
          stack: {
            value: l,
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
      set(l) {
        F(this, {
          stack: {
            value: l,
            writable: !0,
            enumerable: !0,
            configurable: !0
          }
        });
      }
    }
  }), {
    "%InitialGetStackString%": u,
    "%InitialError%": i,
    "%SharedError%": c
  };
}
const { Fail: Ei, details: en, quote: Le } = z, ls = () => {
}, xi = (t, e) => g({
  compartment: t,
  specifier: e
}), Pi = (t, e, r) => {
  const n = V(null);
  for (const a of t) {
    const s = e(a, r);
    n[a] = s;
  }
  return g(n);
}, Yn = (t, e, r, n, a, s, i, c, u) => {
  const { resolveHook: l, moduleRecords: d } = M(
    t,
    r
  ), f = Pi(
    a.imports,
    l,
    n
  ), m = g({
    compartment: r,
    staticModuleRecord: a,
    moduleSpecifier: n,
    resolvedImports: f,
    importMeta: u
  });
  for (const p of fo(f)) {
    const h = mr(
      t,
      e,
      r,
      p,
      s,
      i,
      c
    );
    $r(
      s,
      yn(h, ls, (_) => {
        ae(c, _);
      })
    );
  }
  return $e(d, n, m), m;
}, ki = async (t, e, r, n, a, s, i) => {
  const { importHook: c, moduleMap: u, moduleMapHook: l, moduleRecords: d } = M(
    t,
    r
  );
  let f = u[n];
  if (f === void 0 && l !== void 0 && (f = l(n)), typeof f == "string")
    z.fail(
      en`Cannot map module ${Le(n)} to ${Le(
        f
      )} in parent compartment, not yet implemented`,
      v
    );
  else if (f !== void 0) {
    const p = M(e, f);
    p === void 0 && z.fail(
      en`Cannot map module ${Le(
        n
      )} because the value is not a module exports namespace, or is from another realm`,
      rt
    );
    const h = await mr(
      t,
      e,
      p.compartment,
      p.specifier,
      a,
      s,
      i
    );
    return $e(d, n, h), h;
  }
  if (Cr(d, n))
    return De(d, n);
  const m = await c(n);
  if ((m === null || typeof m != "object") && Ei`importHook must return a promise for an object, for module ${Le(
    n
  )} in compartment ${Le(r.name)}`, m.specifier !== void 0) {
    if (m.record !== void 0) {
      if (m.compartment !== void 0)
        throw v(
          "Cannot redirect to an explicit record with a specified compartment"
        );
      const {
        compartment: p = r,
        specifier: h = n,
        record: _,
        importMeta: w
      } = m, I = Yn(
        t,
        e,
        p,
        h,
        _,
        a,
        s,
        i,
        w
      );
      return $e(d, n, I), I;
    }
    if (m.compartment !== void 0) {
      if (m.importMeta !== void 0)
        throw v(
          "Cannot redirect to an implicit record with a specified importMeta"
        );
      const p = await mr(
        t,
        e,
        m.compartment,
        m.specifier,
        a,
        s,
        i
      );
      return $e(d, n, p), p;
    }
    throw v("Unnexpected RedirectStaticModuleInterface record shape");
  }
  return Yn(
    t,
    e,
    r,
    n,
    m,
    a,
    s,
    i
  );
}, mr = async (t, e, r, n, a, s, i) => {
  const { name: c } = M(
    t,
    r
  );
  let u = De(s, r);
  u === void 0 && (u = new Ce(), $e(s, r, u));
  let l = De(u, n);
  return l !== void 0 || (l = na(
    ki(
      t,
      e,
      r,
      n,
      a,
      s,
      i
    ),
    (d) => {
      throw z.note(
        d,
        en`${d.message}, loading ${Le(n)} in compartment ${Le(
          c
        )}`
      ), d;
    }
  ), $e(u, n, l)), l;
}, Jn = async (t, e, r, n) => {
  const { name: a } = M(
    t,
    r
  ), s = new Pt(), i = new Ce(), c = [], u = mr(
    t,
    e,
    r,
    n,
    s,
    i,
    c
  );
  $r(
    s,
    yn(u, ls, (l) => {
      ae(c, l);
    })
  );
  for (const l of s)
    await l;
  if (c.length > 0)
    throw v(
      `Failed to load module ${Le(n)} in package ${Le(
        a
      )} (${c.length} underlying failures: ${At(
        fe(c, (l) => l.message),
        ", "
      )}`
    );
}, { quote: pt } = z, Ti = () => {
  let t = !1;
  const e = V(null, {
    // Make this appear like an ESM module namespace object.
    [He]: {
      value: "Module",
      writable: !1,
      enumerable: !1,
      configurable: !1
    }
  });
  return g({
    activate() {
      t = !0;
    },
    exportsTarget: e,
    exportsProxy: new xr(e, {
      get(r, n, a) {
        if (!t)
          throw v(
            `Cannot get property ${pt(
              n
            )} of module exports namespace, the module has not yet begun to execute`
          );
        return Ds(e, n, a);
      },
      set(r, n, a) {
        throw v(
          `Cannot set property ${pt(n)} of module exports namespace`
        );
      },
      has(r, n) {
        if (!t)
          throw v(
            `Cannot check property ${pt(
              n
            )}, the module has not yet begun to execute`
          );
        return ho(e, n);
      },
      deleteProperty(r, n) {
        throw v(
          `Cannot delete property ${pt(n)}s of module exports namespace`
        );
      },
      ownKeys(r) {
        if (!t)
          throw v(
            "Cannot enumerate keys, the module has not yet begun to execute"
          );
        return st(e);
      },
      getOwnPropertyDescriptor(r, n) {
        if (!t)
          throw v(
            `Cannot get own property descriptor ${pt(
              n
            )}, the module has not yet begun to execute`
          );
        return Us(e, n);
      },
      preventExtensions(r) {
        if (!t)
          throw v(
            "Cannot prevent extensions of module exports namespace, the module has not yet begun to execute"
          );
        return Zs(e);
      },
      isExtensible() {
        if (!t)
          throw v(
            "Cannot check extensibility of module exports namespace, the module has not yet begun to execute"
          );
        return js(e);
      },
      getPrototypeOf(r) {
        return null;
      },
      setPrototypeOf(r, n) {
        throw v("Cannot set prototype of module exports namespace");
      },
      defineProperty(r, n, a) {
        throw v(
          `Cannot define property ${pt(n)} of module exports namespace`
        );
      },
      apply(r, n, a) {
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
}, En = (t, e, r, n) => {
  const { deferredExports: a } = e;
  if (!Cr(a, n)) {
    const s = Ti();
    ee(
      r,
      s.exportsProxy,
      xi(t, n)
    ), $e(a, n, s);
  }
  return De(a, n);
}, Ii = (t, e) => {
  const { sloppyGlobalsMode: r = !1, __moduleShimLexicals__: n = void 0 } = e;
  let a;
  if (n === void 0 && !r)
    ({ safeEvaluate: a } = t);
  else {
    let { globalTransforms: s } = t;
    const { globalObject: i } = t;
    let c;
    n !== void 0 && (s = void 0, c = V(
      null,
      Je(n)
    )), { safeEvaluate: a } = Sn({
      globalObject: i,
      moduleLexicals: c,
      globalTransforms: s,
      sloppyGlobalsMode: r
    });
  }
  return { safeEvaluate: a };
}, us = (t, e, r) => {
  if (typeof e != "string")
    throw v("first argument of evaluate() must be a string");
  const {
    transforms: n = [],
    __evadeHtmlCommentTest__: a = !1,
    __evadeImportExpressionTest__: s = !1,
    __rejectSomeDirectEvalExpressions__: i = !0
    // Note default on
  } = r, c = [...n];
  a === !0 && ae(c, Jo), s === !0 && ae(c, es), i === !0 && ae(c, ts);
  const { safeEvaluate: u } = Ii(
    t,
    r
  );
  return u(e, {
    localTransforms: c
  });
}, { quote: rr } = z, Ai = (t, e, r, n, a, s) => {
  const { exportsProxy: i, exportsTarget: c, activate: u } = En(
    r,
    M(t, r),
    n,
    a
  ), l = V(null);
  if (e.exports) {
    if (!gt(e.exports) || Bs(e.exports, (f) => typeof f != "string"))
      throw v(
        `SES third-party static module record "exports" property must be an array of strings for module ${a}`
      );
    nt(e.exports, (f) => {
      let m = c[f];
      const p = [];
      L(c, f, {
        get: () => m,
        set: (w) => {
          m = w;
          for (const I of p)
            I(w);
        },
        enumerable: !0,
        configurable: !1
      }), l[f] = (w) => {
        ae(p, w), w(m);
      };
    }), l["*"] = (f) => {
      f(c);
    };
  }
  const d = {
    activated: !1
  };
  return g({
    notifiers: l,
    exportsProxy: i,
    execute() {
      if (ho(d, "errorFromExecute"))
        throw d.errorFromExecute;
      if (!d.activated) {
        u(), d.activated = !0;
        try {
          e.execute(
            c,
            r,
            s
          );
        } catch (f) {
          throw d.errorFromExecute = f, f;
        }
      }
    }
  });
}, Ci = (t, e, r, n) => {
  const {
    compartment: a,
    moduleSpecifier: s,
    staticModuleRecord: i,
    importMeta: c
  } = r, {
    reexports: u = [],
    __syncModuleProgram__: l,
    __fixedExportMap__: d = {},
    __liveExportMap__: f = {},
    __reexportMap__: m = {},
    __needsImportMeta__: p = !1,
    __syncModuleFunctor__: h
  } = i, _ = M(t, a), { __shimTransforms__: w, importMetaHook: I } = _, { exportsProxy: $, exportsTarget: T, activate: D } = En(
    a,
    _,
    e,
    s
  ), Z = V(null), q = V(null), K = V(null), je = V(null), pe = V(null);
  c && Pr(pe, c), p && I && I(s, pe);
  const Ze = V(null), Xe = V(null);
  nt(te(d), ([me, [G]]) => {
    let B = Ze[G];
    if (!B) {
      let X, Q = !0, ce = [];
      const Y = () => {
        if (Q)
          throw rt(`binding ${rr(G)} not yet initialized`);
        return X;
      }, _e = g((be) => {
        if (!Q)
          throw v(
            `Internal: binding ${rr(G)} already initialized`
          );
        X = be;
        const In = ce;
        ce = null, Q = !1;
        for (const we of In || [])
          we(be);
        return be;
      });
      B = {
        get: Y,
        notify: (be) => {
          be !== _e && (Q ? ae(ce || [], be) : be(X));
        }
      }, Ze[G] = B, K[G] = _e;
    }
    Z[me] = {
      get: B.get,
      set: void 0,
      enumerable: !0,
      configurable: !1
    }, Xe[me] = B.notify;
  }), nt(
    te(f),
    ([me, [G, B]]) => {
      let X = Ze[G];
      if (!X) {
        let Q, ce = !0;
        const Y = [], _e = () => {
          if (ce)
            throw rt(
              `binding ${rr(me)} not yet initialized`
            );
          return Q;
        }, ut = g((we) => {
          Q = we, ce = !1;
          for (const Lr of Y)
            Lr(we);
        }), be = (we) => {
          if (ce)
            throw rt(`binding ${rr(G)} not yet initialized`);
          Q = we;
          for (const Lr of Y)
            Lr(we);
        };
        X = {
          get: _e,
          notify: (we) => {
            we !== ut && (ae(Y, we), ce || we(Q));
          }
        }, Ze[G] = X, B && L(q, G, {
          get: _e,
          set: be,
          enumerable: !0,
          configurable: !1
        }), je[G] = ut;
      }
      Z[me] = {
        get: X.get,
        set: void 0,
        enumerable: !0,
        configurable: !1
      }, Xe[me] = X.notify;
    }
  );
  const ze = (me) => {
    me(T);
  };
  Xe["*"] = ze;
  function er(me) {
    const G = V(null);
    G.default = !1;
    for (const [B, X] of me) {
      const Q = De(n, B);
      Q.execute();
      const { notifiers: ce } = Q;
      for (const [Y, _e] of X) {
        const ut = ce[Y];
        if (!ut)
          throw Kt(
            `The requested module '${B}' does not provide an export named '${Y}'`
          );
        for (const be of _e)
          ut(be);
      }
      if (Ar(u, B))
        for (const [Y, _e] of te(
          ce
        ))
          G[Y] === void 0 ? G[Y] = _e : G[Y] = !1;
      if (m[B])
        for (const [Y, _e] of m[B])
          G[_e] = ce[Y];
    }
    for (const [B, X] of te(G))
      if (!Xe[B] && X !== !1) {
        Xe[B] = X;
        let Q;
        X((Y) => Q = Y), Z[B] = {
          get() {
            return Q;
          },
          set: void 0,
          enumerable: !0,
          configurable: !1
        };
      }
    nt(
      _o(co(Z)),
      (B) => L(T, B, Z[B])
    ), g(T), D();
  }
  let Ct;
  h !== void 0 ? Ct = h : Ct = us(_, l, {
    globalObject: a.globalThis,
    transforms: w,
    __moduleShimLexicals__: q
  });
  let kn = !1, Tn;
  function xs() {
    if (Ct) {
      const me = Ct;
      Ct = null;
      try {
        me(
          g({
            imports: g(er),
            onceVar: g(K),
            liveVar: g(je),
            importMeta: pe
          })
        );
      } catch (G) {
        kn = !0, Tn = G;
      }
    }
    if (kn)
      throw Tn;
  }
  return g({
    notifiers: Xe,
    exportsProxy: $,
    execute: xs
  });
}, { Fail: tt, quote: W } = z, ds = (t, e, r, n) => {
  const { name: a, moduleRecords: s } = M(
    t,
    r
  ), i = De(s, n);
  if (i === void 0)
    throw rt(
      `Missing link to module ${W(n)} from compartment ${W(
        a
      )}`
    );
  return Li(t, e, i);
};
function $i(t) {
  return typeof t.__syncModuleProgram__ == "string";
}
function Ni(t, e) {
  const { __fixedExportMap__: r, __liveExportMap__: n } = t;
  We(r) || tt`Property '__fixedExportMap__' of a precompiled module record must be an object, got ${W(
    r
  )}, for module ${W(e)}`, We(n) || tt`Property '__liveExportMap__' of a precompiled module record must be an object, got ${W(
    n
  )}, for module ${W(e)}`;
}
function Oi(t) {
  return typeof t.execute == "function";
}
function Ri(t, e) {
  const { exports: r } = t;
  gt(r) || tt`Property 'exports' of a third-party static module record must be an array, got ${W(
    r
  )}, for module ${W(e)}`;
}
function Mi(t, e) {
  We(t) || tt`Static module records must be of type object, got ${W(
    t
  )}, for module ${W(e)}`;
  const { imports: r, exports: n, reexports: a = [] } = t;
  gt(r) || tt`Property 'imports' of a static module record must be an array, got ${W(
    r
  )}, for module ${W(e)}`, gt(n) || tt`Property 'exports' of a precompiled module record must be an array, got ${W(
    n
  )}, for module ${W(e)}`, gt(a) || tt`Property 'reexports' of a precompiled module record must be an array if present, got ${W(
    a
  )}, for module ${W(e)}`;
}
const Li = (t, e, r) => {
  const { compartment: n, moduleSpecifier: a, resolvedImports: s, staticModuleRecord: i } = r, { instances: c } = M(t, n);
  if (Cr(c, a))
    return De(c, a);
  Mi(i, a);
  const u = new Ce();
  let l;
  if ($i(i))
    Ni(i, a), l = Ci(
      t,
      e,
      r,
      u
    );
  else if (Oi(i))
    Ri(i, a), l = Ai(
      t,
      i,
      n,
      e,
      a,
      s
    );
  else
    throw v(
      `importHook must return a static module record, got ${W(
        i
      )}`
    );
  $e(c, a, l);
  for (const [d, f] of te(s)) {
    const m = ds(
      t,
      e,
      n,
      f
    );
    $e(u, d, m);
  }
  return l;
}, { quote: zr } = z, Rt = new Te(), Me = new Te(), nr = (t) => {
  const { importHook: e, resolveHook: r } = M(Me, t);
  if (typeof e != "function" || typeof r != "function")
    throw v(
      "Compartment must be constructed with an importHook and a resolveHook for it to be able to load modules"
    );
}, xn = function(e = {}, r = {}, n = {}) {
  throw v(
    "Compartment.prototype.constructor is not a valid constructor."
  );
}, Xn = (t, e) => {
  const { execute: r, exportsProxy: n } = ds(
    Me,
    Rt,
    t,
    e
  );
  return r(), n;
}, Pn = {
  constructor: xn,
  get globalThis() {
    return M(Me, this).globalObject;
  },
  get name() {
    return M(Me, this).name;
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
    const r = M(Me, this);
    return us(r, t, e);
  },
  module(t) {
    if (typeof t != "string")
      throw v("first argument of module() must be a string");
    nr(this);
    const { exportsProxy: e } = En(
      this,
      M(Me, this),
      Rt,
      t
    );
    return e;
  },
  async import(t) {
    if (typeof t != "string")
      throw v("first argument of import() must be a string");
    return nr(this), yn(
      Jn(Me, Rt, this, t),
      () => ({ namespace: Xn(
        /** @type {Compartment} */
        this,
        t
      ) })
    );
  },
  async load(t) {
    if (typeof t != "string")
      throw v("first argument of load() must be a string");
    return nr(this), Jn(Me, Rt, this, t);
  },
  importNow(t) {
    if (typeof t != "string")
      throw v("first argument of importNow() must be a string");
    return nr(this), Xn(
      /** @type {Compartment} */
      this,
      t
    );
  }
};
F(Pn, {
  [He]: {
    value: "Compartment",
    writable: !1,
    enumerable: !1,
    configurable: !0
  }
});
F(xn, {
  prototype: { value: Pn }
});
const tn = (t, e, r) => {
  function n(a = {}, s = {}, i = {}) {
    if (new.target === void 0)
      throw v(
        "Class constructor Compartment cannot be invoked without 'new'"
      );
    const {
      name: c = "<unknown>",
      transforms: u = [],
      __shimTransforms__: l = [],
      resolveHook: d,
      importHook: f,
      moduleMapHook: m,
      importMetaHook: p
    } = i, h = [...u, ...l], _ = new Ce(), w = new Ce(), I = new Ce();
    for (const [D, Z] of te(s || {})) {
      if (typeof Z == "string")
        throw v(
          `Cannot map module ${zr(D)} to ${zr(
            Z
          )} in parent compartment`
        );
      if (M(Rt, Z) === void 0)
        throw rt(
          `Cannot map module ${zr(
            D
          )} because it has no known compartment in this realm`
        );
    }
    const $ = {};
    Ga($), Go($);
    const { safeEvaluate: T } = Sn({
      globalObject: $,
      globalTransforms: h,
      sloppyGlobalsMode: !1
    });
    Bo($, {
      intrinsics: e,
      newGlobalPropertyNames: Do,
      makeCompartmentConstructor: t,
      markVirtualizedNativeFunction: r
    }), Qr(
      $,
      T,
      r
    ), Pr($, a), ee(Me, this, {
      name: `${c}`,
      globalTransforms: h,
      globalObject: $,
      safeEvaluate: T,
      resolveHook: d,
      importHook: f,
      moduleMap: s,
      moduleMapHook: m,
      importMetaHook: p,
      moduleRecords: _,
      __shimTransforms__: l,
      deferredExports: I,
      instances: w
    });
  }
  return n.prototype = Pn, n;
};
function Gr(t) {
  return H(t).constructor;
}
function Fi() {
  return arguments;
}
const Di = () => {
  const t = ve.prototype.constructor, e = de(Fi(), "callee"), r = e && e.get, n = ea(new ie()), a = H(n), s = Tr[po] && Ys(/./), i = s && H(s), c = Hs([]), u = H(c), l = H(Ts), d = qs(new Ce()), f = H(d), m = Ks(new Pt()), p = H(m), h = H(u);
  function* _() {
  }
  const w = Gr(_), I = w.prototype;
  async function* $() {
  }
  const T = Gr(
    $
  ), D = T.prototype, Z = D.prototype, q = H(Z);
  async function K() {
  }
  const je = Gr(K), pe = {
    "%InertFunction%": t,
    "%ArrayIteratorPrototype%": u,
    "%InertAsyncFunction%": je,
    "%AsyncGenerator%": D,
    "%InertAsyncGeneratorFunction%": T,
    "%AsyncGeneratorPrototype%": Z,
    "%AsyncIteratorPrototype%": q,
    "%Generator%": I,
    "%InertGeneratorFunction%": w,
    "%IteratorPrototype%": h,
    "%MapIteratorPrototype%": f,
    "%RegExpStringIteratorPrototype%": i,
    "%SetIteratorPrototype%": p,
    "%StringIteratorPrototype%": a,
    "%ThrowTypeError%": r,
    "%TypedArray%": l,
    "%InertCompartment%": xn
  };
  return x.Iterator && (pe["%IteratorHelperPrototype%"] = H(
    // eslint-disable-next-line @endo/no-polymorphic-call
    x.Iterator.from([]).take(0)
  ), pe["%WrapForValidIteratorPrototype%"] = H(
    // eslint-disable-next-line @endo/no-polymorphic-call
    x.Iterator.from({ next() {
    } })
  )), x.AsyncIterator && (pe["%AsyncIteratorHelperPrototype%"] = H(
    // eslint-disable-next-line @endo/no-polymorphic-call
    x.AsyncIterator.from([]).take(0)
  ), pe["%WrapForValidAsyncIteratorPrototype%"] = H(
    // eslint-disable-next-line @endo/no-polymorphic-call
    x.AsyncIterator.from({ next() {
    } })
  )), pe;
}, fs = (t, e) => {
  if (e !== "safe" && e !== "unsafe")
    throw v(`unrecognized fakeHardenOption ${e}`);
  if (e === "safe" || (Object.isExtensible = () => !1, Object.isFrozen = () => !0, Object.isSealed = () => !0, Reflect.isExtensible = () => !1, t.isFake))
    return t;
  const r = (n) => n;
  return r.isFake = !0, g(r);
};
g(fs);
const Ui = () => {
  const t = Ot, e = t.prototype, r = {
    Symbol(s) {
      return t(s);
    }
  }.Symbol;
  F(e, {
    constructor: {
      value: r
      // leave other `constructor` attributes as is
    }
  });
  const n = te(
    Je(t)
  ), a = Tt(
    fe(n, ([s, i]) => [
      s,
      { ...i, configurable: !0 }
    ])
  );
  return F(r, a), { "%SharedSymbol%": r };
}, ji = (t) => {
  try {
    return t(), !1;
  } catch {
    return !0;
  }
}, Qn = (t, e, r) => {
  if (t === void 0)
    return !1;
  const n = de(t, e);
  if (!n || "value" in n)
    return !1;
  const { get: a, set: s } = n;
  if (typeof a != "function" || typeof s != "function" || a() !== r || oe(a, t, []) !== r)
    return !1;
  const i = "Seems to be a setter", c = { __proto__: null };
  if (oe(s, c, [i]), c[e] !== i)
    return !1;
  const u = { __proto__: t };
  return oe(s, u, [i]), u[e] !== i || !ji(() => oe(s, t, [r])) || "originalValue" in a || n.configurable === !1 ? !1 : (L(t, e, {
    value: r,
    writable: !0,
    enumerable: n.enumerable,
    configurable: !0
  }), !0);
}, Zi = (t) => {
  Qn(
    t["%IteratorPrototype%"],
    "constructor",
    t.Iterator
  ), Qn(
    t["%IteratorPrototype%"],
    He,
    "Iterator"
  );
}, { Fail: eo, details: to, quote: ro } = z;
let or, sr;
const zi = Sa(), Gi = () => {
  let t = !1;
  try {
    t = ve(
      "eval",
      "SES_changed",
      `        eval("SES_changed = true");
        return SES_changed;
      `
    )(Eo, !1), t || delete x.SES_changed;
  } catch {
    t = !0;
  }
  if (!t)
    throw v(
      "SES cannot initialize unless 'eval' is the original intrinsic 'eval', suitable for direct-eval (dynamically scoped eval) (SES_DIRECT_EVAL)"
    );
}, ps = (t = {}) => {
  const {
    errorTaming: e = he("LOCKDOWN_ERROR_TAMING", "safe"),
    errorTrapping: r = (
      /** @type {"platform" | "none" | "report" | "abort" | "exit" | undefined} */
      he("LOCKDOWN_ERROR_TRAPPING", "platform")
    ),
    unhandledRejectionTrapping: n = (
      /** @type {"none" | "report" | undefined} */
      he("LOCKDOWN_UNHANDLED_REJECTION_TRAPPING", "report")
    ),
    regExpTaming: a = he("LOCKDOWN_REGEXP_TAMING", "safe"),
    localeTaming: s = he("LOCKDOWN_LOCALE_TAMING", "safe"),
    consoleTaming: i = (
      /** @type {'unsafe' | 'safe' | undefined} */
      he("LOCKDOWN_CONSOLE_TAMING", "safe")
    ),
    overrideTaming: c = he("LOCKDOWN_OVERRIDE_TAMING", "moderate"),
    stackFiltering: u = he("LOCKDOWN_STACK_FILTERING", "concise"),
    domainTaming: l = he("LOCKDOWN_DOMAIN_TAMING", "safe"),
    evalTaming: d = he("LOCKDOWN_EVAL_TAMING", "safeEval"),
    overrideDebug: f = Ve(
      wo(he("LOCKDOWN_OVERRIDE_DEBUG", ""), ","),
      /** @param {string} debugName */
      (ze) => ze !== ""
    ),
    __hardenTaming__: m = he("LOCKDOWN_HARDEN_TAMING", "safe"),
    dateTaming: p = "safe",
    // deprecated
    mathTaming: h = "safe",
    // deprecated
    ..._
  } = t;
  d === "unsafeEval" || d === "safeEval" || d === "noEval" || eo`lockdown(): non supported option evalTaming: ${ro(d)}`;
  const w = st(_);
  if (w.length === 0 || eo`lockdown(): non supported option ${ro(w)}`, or === void 0 || // eslint-disable-next-line @endo/no-polymorphic-call
  z.fail(
    to`Already locked down at ${or} (SES_ALREADY_LOCKED_DOWN)`,
    v
  ), or = v("Prior lockdown (SES_ALREADY_LOCKED_DOWN)"), or.stack, Gi(), x.Function.prototype.constructor !== x.Function && // @ts-ignore harden is absent on globalThis type def.
  typeof x.harden == "function" && // @ts-ignore lockdown is absent on globalThis type def.
  typeof x.lockdown == "function" && x.Date.prototype.constructor !== x.Date && typeof x.Date.now == "function" && // @ts-ignore does not recognize that Date constructor is a special
  // Function.
  // eslint-disable-next-line @endo/no-polymorphic-call
  kr(x.Date.prototype.constructor.now(), NaN))
    throw v(
      "Already locked down but not by this SES instance (SES_MULTIPLE_INSTANCES)"
    );
  ni(l);
  const $ = os(), { addIntrinsics: T, completePrototypes: D, finalIntrinsics: Z } = jo(), q = fs(zi, m);
  T({ harden: q }), T(Ca()), T($a(p)), T(Si(e, u)), T(Na(h)), T(Oa(a)), T(Ui()), T(Di()), D();
  const K = Z(), je = { __proto__: null };
  typeof x.Buffer == "function" && (je.Buffer = x.Buffer);
  let pe;
  e !== "unsafe" && (pe = K["%InitialGetStackString%"]);
  const Ze = ai(
    i,
    r,
    n,
    pe
  );
  if (x.console = /** @type {Console} */
  Ze.console, typeof /** @type {any} */
  Ze.console._times == "object" && (je.SafeMap = H(
    // eslint-disable-next-line no-underscore-dangle
    /** @type {any} */
    Ze.console._times
  )), e === "unsafe" && x.assert === z && (x.assert = Rr(void 0, !0)), ja(K, s), Zi(K), Aa(K, $), Go(x), Bo(x, {
    intrinsics: K,
    newGlobalPropertyNames: Fn,
    makeCompartmentConstructor: tn,
    markVirtualizedNativeFunction: $
  }), d === "noEval")
    Qr(
      x,
      sa,
      $
    );
  else if (d === "safeEval") {
    const { safeEvaluate: ze } = Sn({ globalObject: x });
    Qr(
      x,
      ze,
      $
    );
  }
  return () => {
    sr === void 0 || // eslint-disable-next-line @endo/no-polymorphic-call
    z.fail(
      to`Already locked down at ${sr} (SES_ALREADY_LOCKED_DOWN)`,
      v
    ), sr = v(
      "Prior lockdown (SES_ALREADY_LOCKED_DOWN)"
    ), sr.stack, La(K, c, f);
    const ze = {
      intrinsics: K,
      hostIntrinsics: je,
      globals: {
        // Harden evaluators
        Function: x.Function,
        eval: x.eval,
        // @ts-ignore Compartment does exist on globalThis
        Compartment: x.Compartment,
        // Harden Symbol
        Symbol: x.Symbol
      }
    };
    for (const er of Mt(Fn))
      ze.globals[er] = x[er];
    return q(ze), q;
  };
};
x.lockdown = (t) => {
  const e = ps(t);
  x.harden = e();
};
x.repairIntrinsics = (t) => {
  const e = ps(t);
  x.hardenIntrinsics = () => {
    x.harden = e();
  };
};
const Bi = os();
x.Compartment = tn(
  tn,
  Ia(x),
  Bi
);
x.assert = z;
const Hi = (t) => {
  let e = { x: 0, y: 0 }, r = { x: 0, y: 0 }, n = { x: 0, y: 0 };
  const a = (c) => {
    const { clientX: u, clientY: l } = c, d = u - n.x + r.x, f = l - n.y + r.y;
    e = { x: d, y: f }, t.style.transform = `translate(${d}px, ${f}px)`;
  }, s = () => {
    document.removeEventListener("mousemove", a), document.removeEventListener("mouseup", s);
  }, i = (c) => {
    n = { x: c.clientX, y: c.clientY }, r = { x: e.x, y: e.y }, document.addEventListener("mousemove", a), document.addEventListener("mouseup", s);
  };
  return t.addEventListener("mousedown", i), s;
}, Vi = ":host{--spacing-4: .25rem;--spacing-8: calc(var(--spacing-4) * 2);--spacing-12: calc(var(--spacing-4) * 3);--spacing-16: calc(var(--spacing-4) * 4);--spacing-20: calc(var(--spacing-4) * 5);--spacing-24: calc(var(--spacing-4) * 6);--spacing-28: calc(var(--spacing-4) * 7);--spacing-32: calc(var(--spacing-4) * 8);--spacing-36: calc(var(--spacing-4) * 9);--spacing-40: calc(var(--spacing-4) * 10);--font-weight-regular: 400;--font-weight-bold: 500;--font-line-height-s: 1.2;--font-line-height-m: 1.4;--font-line-height-l: 1.5;--font-size-s: 12px;--font-size-m: 14px;--font-size-l: 16px}[data-theme]{background-color:var(--color-background-primary);color:var(--color-foreground-secondary)}.wrapper{display:flex;flex-direction:column;position:fixed;inset-block-end:10px;inset-inline-start:10px;z-index:1000;padding:25px;border-radius:15px;box-shadow:0 0 10px #0000004d}.header{align-items:center;display:flex;justify-content:space-between;border-block-end:2px solid var(--color-background-quaternary);padding-block-end:var(--spacing-4)}button{background:transparent;border:0;cursor:pointer;padding:0}h1{font-size:var(--font-size-s);font-weight:var(--font-weight-bold);margin:0;margin-inline-end:var(--spacing-4);-webkit-user-select:none;user-select:none}iframe{border:none;inline-size:100%;block-size:100%}", Wi = `
<svg width="16"  height="16"xmlns="http://www.w3.org/2000/svg" fill="none"><g class="fills"><rect rx="0" ry="0" width="16" height="16" class="frame-background"/></g><g class="frame-children"><path d="M11.997 3.997 8 8l-3.997 4.003m-.006-8L8 8l4.003 3.997" class="fills"/><g class="strokes"><path d="M11.997 3.997 8 8l-3.997 4.003m-.006-8L8 8l4.003 3.997" style="fill: none; stroke-width: 1; stroke: rgb(143, 157, 163); stroke-opacity: 1; stroke-linecap: round;" class="stroke-shape"/></g></g></svg>`;
var ue, qt;
class qi extends HTMLElement {
  constructor() {
    super();
    Fr(this, ue, null);
    Fr(this, qt, null);
    this.attachShadow({ mode: "open" });
  }
  setTheme(r) {
    Se(this, ue) && Se(this, ue).setAttribute("data-theme", r);
  }
  disconnectedCallback() {
    var r;
    (r = Se(this, qt)) == null || r.call(this);
  }
  connectedCallback() {
    const r = this.getAttribute("title"), n = this.getAttribute("iframe-src"), a = Number(this.getAttribute("width") || "300"), s = Number(this.getAttribute("height") || "400");
    if (!r || !n)
      throw new Error("title and iframe-src attributes are required");
    if (!this.shadowRoot)
      throw new Error("Error creating shadow root");
    Dr(this, ue, document.createElement("div")), Se(this, ue).classList.add("wrapper"), Se(this, ue).style.inlineSize = `${a}px`, Se(this, ue).style.blockSize = `${s}px`, Dr(this, qt, Hi(Se(this, ue)));
    const i = document.createElement("div");
    i.classList.add("header");
    const c = document.createElement("h1");
    c.textContent = r, i.appendChild(c);
    const u = document.createElement("button");
    u.setAttribute("type", "button"), u.innerHTML = `<div class="close">${Wi}</div>`, u.addEventListener("click", () => {
      this.shadowRoot && this.shadowRoot.dispatchEvent(
        new CustomEvent("close", {
          composed: !0,
          bubbles: !0
        })
      );
    }), i.appendChild(u);
    const l = document.createElement("iframe");
    l.src = n, l.allow = "", l.sandbox.add(
      "allow-scripts",
      "allow-forms",
      "allow-modals",
      "allow-popups",
      "allow-popups-to-escape-sandbox",
      "allow-storage-access-by-user-activation"
    ), this.addEventListener("message", (f) => {
      l.contentWindow && l.contentWindow.postMessage(f.detail, "*");
    }), this.shadowRoot.appendChild(Se(this, ue)), Se(this, ue).appendChild(i), Se(this, ue).appendChild(l);
    const d = document.createElement("style");
    d.textContent = Vi, this.shadowRoot.appendChild(d);
  }
}
ue = new WeakMap(), qt = new WeakMap();
customElements.define("plugin-modal", qi);
var R;
(function(t) {
  t.assertEqual = (a) => a;
  function e(a) {
  }
  t.assertIs = e;
  function r(a) {
    throw new Error();
  }
  t.assertNever = r, t.arrayToEnum = (a) => {
    const s = {};
    for (const i of a)
      s[i] = i;
    return s;
  }, t.getValidEnumValues = (a) => {
    const s = t.objectKeys(a).filter((c) => typeof a[a[c]] != "number"), i = {};
    for (const c of s)
      i[c] = a[c];
    return t.objectValues(i);
  }, t.objectValues = (a) => t.objectKeys(a).map(function(s) {
    return a[s];
  }), t.objectKeys = typeof Object.keys == "function" ? (a) => Object.keys(a) : (a) => {
    const s = [];
    for (const i in a)
      Object.prototype.hasOwnProperty.call(a, i) && s.push(i);
    return s;
  }, t.find = (a, s) => {
    for (const i of a)
      if (s(i))
        return i;
  }, t.isInteger = typeof Number.isInteger == "function" ? (a) => Number.isInteger(a) : (a) => typeof a == "number" && isFinite(a) && Math.floor(a) === a;
  function n(a, s = " | ") {
    return a.map((i) => typeof i == "string" ? `'${i}'` : i).join(s);
  }
  t.joinValues = n, t.jsonStringifyReplacer = (a, s) => typeof s == "bigint" ? s.toString() : s;
})(R || (R = {}));
var rn;
(function(t) {
  t.mergeShapes = (e, r) => ({
    ...e,
    ...r
    // second overwrites first
  });
})(rn || (rn = {}));
const b = R.arrayToEnum([
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
]), Ge = (t) => {
  switch (typeof t) {
    case "undefined":
      return b.undefined;
    case "string":
      return b.string;
    case "number":
      return isNaN(t) ? b.nan : b.number;
    case "boolean":
      return b.boolean;
    case "function":
      return b.function;
    case "bigint":
      return b.bigint;
    case "symbol":
      return b.symbol;
    case "object":
      return Array.isArray(t) ? b.array : t === null ? b.null : t.then && typeof t.then == "function" && t.catch && typeof t.catch == "function" ? b.promise : typeof Map < "u" && t instanceof Map ? b.map : typeof Set < "u" && t instanceof Set ? b.set : typeof Date < "u" && t instanceof Date ? b.date : b.object;
    default:
      return b.unknown;
  }
}, y = R.arrayToEnum([
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
]), Ki = (t) => JSON.stringify(t, null, 2).replace(/"([^"]+)":/g, "$1:");
class xe extends Error {
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
    const r = e || function(s) {
      return s.message;
    }, n = { _errors: [] }, a = (s) => {
      for (const i of s.issues)
        if (i.code === "invalid_union")
          i.unionErrors.map(a);
        else if (i.code === "invalid_return_type")
          a(i.returnTypeError);
        else if (i.code === "invalid_arguments")
          a(i.argumentsError);
        else if (i.path.length === 0)
          n._errors.push(r(i));
        else {
          let c = n, u = 0;
          for (; u < i.path.length; ) {
            const l = i.path[u];
            u === i.path.length - 1 ? (c[l] = c[l] || { _errors: [] }, c[l]._errors.push(r(i))) : c[l] = c[l] || { _errors: [] }, c = c[l], u++;
          }
        }
    };
    return a(this), n;
  }
  toString() {
    return this.message;
  }
  get message() {
    return JSON.stringify(this.issues, R.jsonStringifyReplacer, 2);
  }
  get isEmpty() {
    return this.issues.length === 0;
  }
  flatten(e = (r) => r.message) {
    const r = {}, n = [];
    for (const a of this.issues)
      a.path.length > 0 ? (r[a.path[0]] = r[a.path[0]] || [], r[a.path[0]].push(e(a))) : n.push(e(a));
    return { formErrors: n, fieldErrors: r };
  }
  get formErrors() {
    return this.flatten();
  }
}
xe.create = (t) => new xe(t);
const Lt = (t, e) => {
  let r;
  switch (t.code) {
    case y.invalid_type:
      t.received === b.undefined ? r = "Required" : r = `Expected ${t.expected}, received ${t.received}`;
      break;
    case y.invalid_literal:
      r = `Invalid literal value, expected ${JSON.stringify(t.expected, R.jsonStringifyReplacer)}`;
      break;
    case y.unrecognized_keys:
      r = `Unrecognized key(s) in object: ${R.joinValues(t.keys, ", ")}`;
      break;
    case y.invalid_union:
      r = "Invalid input";
      break;
    case y.invalid_union_discriminator:
      r = `Invalid discriminator value. Expected ${R.joinValues(t.options)}`;
      break;
    case y.invalid_enum_value:
      r = `Invalid enum value. Expected ${R.joinValues(t.options)}, received '${t.received}'`;
      break;
    case y.invalid_arguments:
      r = "Invalid function arguments";
      break;
    case y.invalid_return_type:
      r = "Invalid function return type";
      break;
    case y.invalid_date:
      r = "Invalid date";
      break;
    case y.invalid_string:
      typeof t.validation == "object" ? "includes" in t.validation ? (r = `Invalid input: must include "${t.validation.includes}"`, typeof t.validation.position == "number" && (r = `${r} at one or more positions greater than or equal to ${t.validation.position}`)) : "startsWith" in t.validation ? r = `Invalid input: must start with "${t.validation.startsWith}"` : "endsWith" in t.validation ? r = `Invalid input: must end with "${t.validation.endsWith}"` : R.assertNever(t.validation) : t.validation !== "regex" ? r = `Invalid ${t.validation}` : r = "Invalid";
      break;
    case y.too_small:
      t.type === "array" ? r = `Array must contain ${t.exact ? "exactly" : t.inclusive ? "at least" : "more than"} ${t.minimum} element(s)` : t.type === "string" ? r = `String must contain ${t.exact ? "exactly" : t.inclusive ? "at least" : "over"} ${t.minimum} character(s)` : t.type === "number" ? r = `Number must be ${t.exact ? "exactly equal to " : t.inclusive ? "greater than or equal to " : "greater than "}${t.minimum}` : t.type === "date" ? r = `Date must be ${t.exact ? "exactly equal to " : t.inclusive ? "greater than or equal to " : "greater than "}${new Date(Number(t.minimum))}` : r = "Invalid input";
      break;
    case y.too_big:
      t.type === "array" ? r = `Array must contain ${t.exact ? "exactly" : t.inclusive ? "at most" : "less than"} ${t.maximum} element(s)` : t.type === "string" ? r = `String must contain ${t.exact ? "exactly" : t.inclusive ? "at most" : "under"} ${t.maximum} character(s)` : t.type === "number" ? r = `Number must be ${t.exact ? "exactly" : t.inclusive ? "less than or equal to" : "less than"} ${t.maximum}` : t.type === "bigint" ? r = `BigInt must be ${t.exact ? "exactly" : t.inclusive ? "less than or equal to" : "less than"} ${t.maximum}` : t.type === "date" ? r = `Date must be ${t.exact ? "exactly" : t.inclusive ? "smaller than or equal to" : "smaller than"} ${new Date(Number(t.maximum))}` : r = "Invalid input";
      break;
    case y.custom:
      r = "Invalid input";
      break;
    case y.invalid_intersection_types:
      r = "Intersection results could not be merged";
      break;
    case y.not_multiple_of:
      r = `Number must be a multiple of ${t.multipleOf}`;
      break;
    case y.not_finite:
      r = "Number must be finite";
      break;
    default:
      r = e.defaultError, R.assertNever(t);
  }
  return { message: r };
};
let ms = Lt;
function Yi(t) {
  ms = t;
}
function hr() {
  return ms;
}
const yr = (t) => {
  const { data: e, path: r, errorMaps: n, issueData: a } = t, s = [...r, ...a.path || []], i = {
    ...a,
    path: s
  };
  let c = "";
  const u = n.filter((l) => !!l).slice().reverse();
  for (const l of u)
    c = l(i, { data: e, defaultError: c }).message;
  return {
    ...a,
    path: s,
    message: a.message || c
  };
}, Ji = [];
function S(t, e) {
  const r = yr({
    issueData: e,
    data: t.data,
    path: t.path,
    errorMaps: [
      t.common.contextualErrorMap,
      t.schemaErrorMap,
      hr(),
      Lt
      // then global default map
    ].filter((n) => !!n)
  });
  t.common.issues.push(r);
}
class J {
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
    for (const a of r) {
      if (a.status === "aborted")
        return A;
      a.status === "dirty" && e.dirty(), n.push(a.value);
    }
    return { status: e.value, value: n };
  }
  static async mergeObjectAsync(e, r) {
    const n = [];
    for (const a of r)
      n.push({
        key: await a.key,
        value: await a.value
      });
    return J.mergeObjectSync(e, n);
  }
  static mergeObjectSync(e, r) {
    const n = {};
    for (const a of r) {
      const { key: s, value: i } = a;
      if (s.status === "aborted" || i.status === "aborted")
        return A;
      s.status === "dirty" && e.dirty(), i.status === "dirty" && e.dirty(), s.value !== "__proto__" && (typeof i.value < "u" || a.alwaysSet) && (n[s.value] = i.value);
    }
    return { status: e.value, value: n };
  }
}
const A = Object.freeze({
  status: "aborted"
}), hs = (t) => ({ status: "dirty", value: t }), re = (t) => ({ status: "valid", value: t }), nn = (t) => t.status === "aborted", on = (t) => t.status === "dirty", Ft = (t) => t.status === "valid", gr = (t) => typeof Promise < "u" && t instanceof Promise;
var E;
(function(t) {
  t.errToObj = (e) => typeof e == "string" ? { message: e } : e || {}, t.toString = (e) => typeof e == "string" ? e : e == null ? void 0 : e.message;
})(E || (E = {}));
class Ne {
  constructor(e, r, n, a) {
    this._cachedPath = [], this.parent = e, this.data = r, this._path = n, this._key = a;
  }
  get path() {
    return this._cachedPath.length || (this._key instanceof Array ? this._cachedPath.push(...this._path, ...this._key) : this._cachedPath.push(...this._path, this._key)), this._cachedPath;
  }
}
const no = (t, e) => {
  if (Ft(e))
    return { success: !0, data: e.value };
  if (!t.common.issues.length)
    throw new Error("Validation failed but no issues detected.");
  return {
    success: !1,
    get error() {
      if (this._error)
        return this._error;
      const r = new xe(t.common.issues);
      return this._error = r, this._error;
    }
  };
};
function C(t) {
  if (!t)
    return {};
  const { errorMap: e, invalid_type_error: r, required_error: n, description: a } = t;
  if (e && (r || n))
    throw new Error(`Can't use "invalid_type_error" or "required_error" in conjunction with custom error map.`);
  return e ? { errorMap: e, description: a } : { errorMap: (i, c) => i.code !== "invalid_type" ? { message: c.defaultError } : typeof c.data > "u" ? { message: n ?? c.defaultError } : { message: r ?? c.defaultError }, description: a };
}
class N {
  constructor(e) {
    this.spa = this.safeParseAsync, this._def = e, this.parse = this.parse.bind(this), this.safeParse = this.safeParse.bind(this), this.parseAsync = this.parseAsync.bind(this), this.safeParseAsync = this.safeParseAsync.bind(this), this.spa = this.spa.bind(this), this.refine = this.refine.bind(this), this.refinement = this.refinement.bind(this), this.superRefine = this.superRefine.bind(this), this.optional = this.optional.bind(this), this.nullable = this.nullable.bind(this), this.nullish = this.nullish.bind(this), this.array = this.array.bind(this), this.promise = this.promise.bind(this), this.or = this.or.bind(this), this.and = this.and.bind(this), this.transform = this.transform.bind(this), this.brand = this.brand.bind(this), this.default = this.default.bind(this), this.catch = this.catch.bind(this), this.describe = this.describe.bind(this), this.pipe = this.pipe.bind(this), this.readonly = this.readonly.bind(this), this.isNullable = this.isNullable.bind(this), this.isOptional = this.isOptional.bind(this);
  }
  get description() {
    return this._def.description;
  }
  _getType(e) {
    return Ge(e.data);
  }
  _getOrReturnCtx(e, r) {
    return r || {
      common: e.parent.common,
      data: e.data,
      parsedType: Ge(e.data),
      schemaErrorMap: this._def.errorMap,
      path: e.path,
      parent: e.parent
    };
  }
  _processInputParams(e) {
    return {
      status: new J(),
      ctx: {
        common: e.parent.common,
        data: e.data,
        parsedType: Ge(e.data),
        schemaErrorMap: this._def.errorMap,
        path: e.path,
        parent: e.parent
      }
    };
  }
  _parseSync(e) {
    const r = this._parse(e);
    if (gr(r))
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
    const a = {
      common: {
        issues: [],
        async: (n = r == null ? void 0 : r.async) !== null && n !== void 0 ? n : !1,
        contextualErrorMap: r == null ? void 0 : r.errorMap
      },
      path: (r == null ? void 0 : r.path) || [],
      schemaErrorMap: this._def.errorMap,
      parent: null,
      data: e,
      parsedType: Ge(e)
    }, s = this._parseSync({ data: e, path: a.path, parent: a });
    return no(a, s);
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
      parsedType: Ge(e)
    }, a = this._parse({ data: e, path: n.path, parent: n }), s = await (gr(a) ? a : Promise.resolve(a));
    return no(n, s);
  }
  refine(e, r) {
    const n = (a) => typeof r == "string" || typeof r > "u" ? { message: r } : typeof r == "function" ? r(a) : r;
    return this._refinement((a, s) => {
      const i = e(a), c = () => s.addIssue({
        code: y.custom,
        ...n(a)
      });
      return typeof Promise < "u" && i instanceof Promise ? i.then((u) => u ? !0 : (c(), !1)) : i ? !0 : (c(), !1);
    });
  }
  refinement(e, r) {
    return this._refinement((n, a) => e(n) ? !0 : (a.addIssue(typeof r == "function" ? r(n, a) : r), !1));
  }
  _refinement(e) {
    return new ke({
      schema: this,
      typeName: P.ZodEffects,
      effect: { type: "refinement", refinement: e }
    });
  }
  superRefine(e) {
    return this._refinement(e);
  }
  optional() {
    return Fe.create(this, this._def);
  }
  nullable() {
    return lt.create(this, this._def);
  }
  nullish() {
    return this.nullable().optional();
  }
  array() {
    return Pe.create(this, this._def);
  }
  promise() {
    return xt.create(this, this._def);
  }
  or(e) {
    return Zt.create([this, e], this._def);
  }
  and(e) {
    return zt.create(this, e, this._def);
  }
  transform(e) {
    return new ke({
      ...C(this._def),
      schema: this,
      typeName: P.ZodEffects,
      effect: { type: "transform", transform: e }
    });
  }
  default(e) {
    const r = typeof e == "function" ? e : () => e;
    return new Wt({
      ...C(this._def),
      innerType: this,
      defaultValue: r,
      typeName: P.ZodDefault
    });
  }
  brand() {
    return new gs({
      typeName: P.ZodBranded,
      type: this,
      ...C(this._def)
    });
  }
  catch(e) {
    const r = typeof e == "function" ? e : () => e;
    return new wr({
      ...C(this._def),
      innerType: this,
      catchValue: r,
      typeName: P.ZodCatch
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
    return Qt.create(this, e);
  }
  readonly() {
    return Er.create(this);
  }
  isOptional() {
    return this.safeParse(void 0).success;
  }
  isNullable() {
    return this.safeParse(null).success;
  }
}
const Xi = /^c[^\s-]{8,}$/i, Qi = /^[a-z][a-z0-9]*$/, ec = /^[0-9A-HJKMNP-TV-Z]{26}$/, tc = /^[0-9a-fA-F]{8}\b-[0-9a-fA-F]{4}\b-[0-9a-fA-F]{4}\b-[0-9a-fA-F]{4}\b-[0-9a-fA-F]{12}$/i, rc = /^(?!\.)(?!.*\.\.)([A-Z0-9_+-\.]*)[A-Z0-9_+-]@([A-Z0-9][A-Z0-9\-]*\.)+[A-Z]{2,}$/i, nc = "^(\\p{Extended_Pictographic}|\\p{Emoji_Component})+$";
let Br;
const oc = /^(((25[0-5])|(2[0-4][0-9])|(1[0-9]{2})|([0-9]{1,2}))\.){3}((25[0-5])|(2[0-4][0-9])|(1[0-9]{2})|([0-9]{1,2}))$/, sc = /^(([a-f0-9]{1,4}:){7}|::([a-f0-9]{1,4}:){0,6}|([a-f0-9]{1,4}:){1}:([a-f0-9]{1,4}:){0,5}|([a-f0-9]{1,4}:){2}:([a-f0-9]{1,4}:){0,4}|([a-f0-9]{1,4}:){3}:([a-f0-9]{1,4}:){0,3}|([a-f0-9]{1,4}:){4}:([a-f0-9]{1,4}:){0,2}|([a-f0-9]{1,4}:){5}:([a-f0-9]{1,4}:){0,1})([a-f0-9]{1,4}|(((25[0-5])|(2[0-4][0-9])|(1[0-9]{2})|([0-9]{1,2}))\.){3}((25[0-5])|(2[0-4][0-9])|(1[0-9]{2})|([0-9]{1,2})))$/, ac = (t) => t.precision ? t.offset ? new RegExp(`^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{${t.precision}}(([+-]\\d{2}(:?\\d{2})?)|Z)$`) : new RegExp(`^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{${t.precision}}Z$`) : t.precision === 0 ? t.offset ? new RegExp("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(([+-]\\d{2}(:?\\d{2})?)|Z)$") : new RegExp("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$") : t.offset ? new RegExp("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(([+-]\\d{2}(:?\\d{2})?)|Z)$") : new RegExp("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z$");
function ic(t, e) {
  return !!((e === "v4" || !e) && oc.test(t) || (e === "v6" || !e) && sc.test(t));
}
class Ee extends N {
  _parse(e) {
    if (this._def.coerce && (e.data = String(e.data)), this._getType(e) !== b.string) {
      const s = this._getOrReturnCtx(e);
      return S(
        s,
        {
          code: y.invalid_type,
          expected: b.string,
          received: s.parsedType
        }
        //
      ), A;
    }
    const n = new J();
    let a;
    for (const s of this._def.checks)
      if (s.kind === "min")
        e.data.length < s.value && (a = this._getOrReturnCtx(e, a), S(a, {
          code: y.too_small,
          minimum: s.value,
          type: "string",
          inclusive: !0,
          exact: !1,
          message: s.message
        }), n.dirty());
      else if (s.kind === "max")
        e.data.length > s.value && (a = this._getOrReturnCtx(e, a), S(a, {
          code: y.too_big,
          maximum: s.value,
          type: "string",
          inclusive: !0,
          exact: !1,
          message: s.message
        }), n.dirty());
      else if (s.kind === "length") {
        const i = e.data.length > s.value, c = e.data.length < s.value;
        (i || c) && (a = this._getOrReturnCtx(e, a), i ? S(a, {
          code: y.too_big,
          maximum: s.value,
          type: "string",
          inclusive: !0,
          exact: !0,
          message: s.message
        }) : c && S(a, {
          code: y.too_small,
          minimum: s.value,
          type: "string",
          inclusive: !0,
          exact: !0,
          message: s.message
        }), n.dirty());
      } else if (s.kind === "email")
        rc.test(e.data) || (a = this._getOrReturnCtx(e, a), S(a, {
          validation: "email",
          code: y.invalid_string,
          message: s.message
        }), n.dirty());
      else if (s.kind === "emoji")
        Br || (Br = new RegExp(nc, "u")), Br.test(e.data) || (a = this._getOrReturnCtx(e, a), S(a, {
          validation: "emoji",
          code: y.invalid_string,
          message: s.message
        }), n.dirty());
      else if (s.kind === "uuid")
        tc.test(e.data) || (a = this._getOrReturnCtx(e, a), S(a, {
          validation: "uuid",
          code: y.invalid_string,
          message: s.message
        }), n.dirty());
      else if (s.kind === "cuid")
        Xi.test(e.data) || (a = this._getOrReturnCtx(e, a), S(a, {
          validation: "cuid",
          code: y.invalid_string,
          message: s.message
        }), n.dirty());
      else if (s.kind === "cuid2")
        Qi.test(e.data) || (a = this._getOrReturnCtx(e, a), S(a, {
          validation: "cuid2",
          code: y.invalid_string,
          message: s.message
        }), n.dirty());
      else if (s.kind === "ulid")
        ec.test(e.data) || (a = this._getOrReturnCtx(e, a), S(a, {
          validation: "ulid",
          code: y.invalid_string,
          message: s.message
        }), n.dirty());
      else if (s.kind === "url")
        try {
          new URL(e.data);
        } catch {
          a = this._getOrReturnCtx(e, a), S(a, {
            validation: "url",
            code: y.invalid_string,
            message: s.message
          }), n.dirty();
        }
      else
        s.kind === "regex" ? (s.regex.lastIndex = 0, s.regex.test(e.data) || (a = this._getOrReturnCtx(e, a), S(a, {
          validation: "regex",
          code: y.invalid_string,
          message: s.message
        }), n.dirty())) : s.kind === "trim" ? e.data = e.data.trim() : s.kind === "includes" ? e.data.includes(s.value, s.position) || (a = this._getOrReturnCtx(e, a), S(a, {
          code: y.invalid_string,
          validation: { includes: s.value, position: s.position },
          message: s.message
        }), n.dirty()) : s.kind === "toLowerCase" ? e.data = e.data.toLowerCase() : s.kind === "toUpperCase" ? e.data = e.data.toUpperCase() : s.kind === "startsWith" ? e.data.startsWith(s.value) || (a = this._getOrReturnCtx(e, a), S(a, {
          code: y.invalid_string,
          validation: { startsWith: s.value },
          message: s.message
        }), n.dirty()) : s.kind === "endsWith" ? e.data.endsWith(s.value) || (a = this._getOrReturnCtx(e, a), S(a, {
          code: y.invalid_string,
          validation: { endsWith: s.value },
          message: s.message
        }), n.dirty()) : s.kind === "datetime" ? ac(s).test(e.data) || (a = this._getOrReturnCtx(e, a), S(a, {
          code: y.invalid_string,
          validation: "datetime",
          message: s.message
        }), n.dirty()) : s.kind === "ip" ? ic(e.data, s.version) || (a = this._getOrReturnCtx(e, a), S(a, {
          validation: "ip",
          code: y.invalid_string,
          message: s.message
        }), n.dirty()) : R.assertNever(s);
    return { status: n.value, value: e.data };
  }
  _regex(e, r, n) {
    return this.refinement((a) => e.test(a), {
      validation: r,
      code: y.invalid_string,
      ...E.errToObj(n)
    });
  }
  _addCheck(e) {
    return new Ee({
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
  cuid(e) {
    return this._addCheck({ kind: "cuid", ...E.errToObj(e) });
  }
  cuid2(e) {
    return this._addCheck({ kind: "cuid2", ...E.errToObj(e) });
  }
  ulid(e) {
    return this._addCheck({ kind: "ulid", ...E.errToObj(e) });
  }
  ip(e) {
    return this._addCheck({ kind: "ip", ...E.errToObj(e) });
  }
  datetime(e) {
    var r;
    return typeof e == "string" ? this._addCheck({
      kind: "datetime",
      precision: null,
      offset: !1,
      message: e
    }) : this._addCheck({
      kind: "datetime",
      precision: typeof (e == null ? void 0 : e.precision) > "u" ? null : e == null ? void 0 : e.precision,
      offset: (r = e == null ? void 0 : e.offset) !== null && r !== void 0 ? r : !1,
      ...E.errToObj(e == null ? void 0 : e.message)
    });
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
    return new Ee({
      ...this._def,
      checks: [...this._def.checks, { kind: "trim" }]
    });
  }
  toLowerCase() {
    return new Ee({
      ...this._def,
      checks: [...this._def.checks, { kind: "toLowerCase" }]
    });
  }
  toUpperCase() {
    return new Ee({
      ...this._def,
      checks: [...this._def.checks, { kind: "toUpperCase" }]
    });
  }
  get isDatetime() {
    return !!this._def.checks.find((e) => e.kind === "datetime");
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
Ee.create = (t) => {
  var e;
  return new Ee({
    checks: [],
    typeName: P.ZodString,
    coerce: (e = t == null ? void 0 : t.coerce) !== null && e !== void 0 ? e : !1,
    ...C(t)
  });
};
function cc(t, e) {
  const r = (t.toString().split(".")[1] || "").length, n = (e.toString().split(".")[1] || "").length, a = r > n ? r : n, s = parseInt(t.toFixed(a).replace(".", "")), i = parseInt(e.toFixed(a).replace(".", ""));
  return s % i / Math.pow(10, a);
}
class qe extends N {
  constructor() {
    super(...arguments), this.min = this.gte, this.max = this.lte, this.step = this.multipleOf;
  }
  _parse(e) {
    if (this._def.coerce && (e.data = Number(e.data)), this._getType(e) !== b.number) {
      const s = this._getOrReturnCtx(e);
      return S(s, {
        code: y.invalid_type,
        expected: b.number,
        received: s.parsedType
      }), A;
    }
    let n;
    const a = new J();
    for (const s of this._def.checks)
      s.kind === "int" ? R.isInteger(e.data) || (n = this._getOrReturnCtx(e, n), S(n, {
        code: y.invalid_type,
        expected: "integer",
        received: "float",
        message: s.message
      }), a.dirty()) : s.kind === "min" ? (s.inclusive ? e.data < s.value : e.data <= s.value) && (n = this._getOrReturnCtx(e, n), S(n, {
        code: y.too_small,
        minimum: s.value,
        type: "number",
        inclusive: s.inclusive,
        exact: !1,
        message: s.message
      }), a.dirty()) : s.kind === "max" ? (s.inclusive ? e.data > s.value : e.data >= s.value) && (n = this._getOrReturnCtx(e, n), S(n, {
        code: y.too_big,
        maximum: s.value,
        type: "number",
        inclusive: s.inclusive,
        exact: !1,
        message: s.message
      }), a.dirty()) : s.kind === "multipleOf" ? cc(e.data, s.value) !== 0 && (n = this._getOrReturnCtx(e, n), S(n, {
        code: y.not_multiple_of,
        multipleOf: s.value,
        message: s.message
      }), a.dirty()) : s.kind === "finite" ? Number.isFinite(e.data) || (n = this._getOrReturnCtx(e, n), S(n, {
        code: y.not_finite,
        message: s.message
      }), a.dirty()) : R.assertNever(s);
    return { status: a.value, value: e.data };
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
  setLimit(e, r, n, a) {
    return new qe({
      ...this._def,
      checks: [
        ...this._def.checks,
        {
          kind: e,
          value: r,
          inclusive: n,
          message: E.toString(a)
        }
      ]
    });
  }
  _addCheck(e) {
    return new qe({
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
    return !!this._def.checks.find((e) => e.kind === "int" || e.kind === "multipleOf" && R.isInteger(e.value));
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
qe.create = (t) => new qe({
  checks: [],
  typeName: P.ZodNumber,
  coerce: (t == null ? void 0 : t.coerce) || !1,
  ...C(t)
});
class Ke extends N {
  constructor() {
    super(...arguments), this.min = this.gte, this.max = this.lte;
  }
  _parse(e) {
    if (this._def.coerce && (e.data = BigInt(e.data)), this._getType(e) !== b.bigint) {
      const s = this._getOrReturnCtx(e);
      return S(s, {
        code: y.invalid_type,
        expected: b.bigint,
        received: s.parsedType
      }), A;
    }
    let n;
    const a = new J();
    for (const s of this._def.checks)
      s.kind === "min" ? (s.inclusive ? e.data < s.value : e.data <= s.value) && (n = this._getOrReturnCtx(e, n), S(n, {
        code: y.too_small,
        type: "bigint",
        minimum: s.value,
        inclusive: s.inclusive,
        message: s.message
      }), a.dirty()) : s.kind === "max" ? (s.inclusive ? e.data > s.value : e.data >= s.value) && (n = this._getOrReturnCtx(e, n), S(n, {
        code: y.too_big,
        type: "bigint",
        maximum: s.value,
        inclusive: s.inclusive,
        message: s.message
      }), a.dirty()) : s.kind === "multipleOf" ? e.data % s.value !== BigInt(0) && (n = this._getOrReturnCtx(e, n), S(n, {
        code: y.not_multiple_of,
        multipleOf: s.value,
        message: s.message
      }), a.dirty()) : R.assertNever(s);
    return { status: a.value, value: e.data };
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
  setLimit(e, r, n, a) {
    return new Ke({
      ...this._def,
      checks: [
        ...this._def.checks,
        {
          kind: e,
          value: r,
          inclusive: n,
          message: E.toString(a)
        }
      ]
    });
  }
  _addCheck(e) {
    return new Ke({
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
Ke.create = (t) => {
  var e;
  return new Ke({
    checks: [],
    typeName: P.ZodBigInt,
    coerce: (e = t == null ? void 0 : t.coerce) !== null && e !== void 0 ? e : !1,
    ...C(t)
  });
};
class Dt extends N {
  _parse(e) {
    if (this._def.coerce && (e.data = !!e.data), this._getType(e) !== b.boolean) {
      const n = this._getOrReturnCtx(e);
      return S(n, {
        code: y.invalid_type,
        expected: b.boolean,
        received: n.parsedType
      }), A;
    }
    return re(e.data);
  }
}
Dt.create = (t) => new Dt({
  typeName: P.ZodBoolean,
  coerce: (t == null ? void 0 : t.coerce) || !1,
  ...C(t)
});
class it extends N {
  _parse(e) {
    if (this._def.coerce && (e.data = new Date(e.data)), this._getType(e) !== b.date) {
      const s = this._getOrReturnCtx(e);
      return S(s, {
        code: y.invalid_type,
        expected: b.date,
        received: s.parsedType
      }), A;
    }
    if (isNaN(e.data.getTime())) {
      const s = this._getOrReturnCtx(e);
      return S(s, {
        code: y.invalid_date
      }), A;
    }
    const n = new J();
    let a;
    for (const s of this._def.checks)
      s.kind === "min" ? e.data.getTime() < s.value && (a = this._getOrReturnCtx(e, a), S(a, {
        code: y.too_small,
        message: s.message,
        inclusive: !0,
        exact: !1,
        minimum: s.value,
        type: "date"
      }), n.dirty()) : s.kind === "max" ? e.data.getTime() > s.value && (a = this._getOrReturnCtx(e, a), S(a, {
        code: y.too_big,
        message: s.message,
        inclusive: !0,
        exact: !1,
        maximum: s.value,
        type: "date"
      }), n.dirty()) : R.assertNever(s);
    return {
      status: n.value,
      value: new Date(e.data.getTime())
    };
  }
  _addCheck(e) {
    return new it({
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
it.create = (t) => new it({
  checks: [],
  coerce: (t == null ? void 0 : t.coerce) || !1,
  typeName: P.ZodDate,
  ...C(t)
});
class vr extends N {
  _parse(e) {
    if (this._getType(e) !== b.symbol) {
      const n = this._getOrReturnCtx(e);
      return S(n, {
        code: y.invalid_type,
        expected: b.symbol,
        received: n.parsedType
      }), A;
    }
    return re(e.data);
  }
}
vr.create = (t) => new vr({
  typeName: P.ZodSymbol,
  ...C(t)
});
class Ut extends N {
  _parse(e) {
    if (this._getType(e) !== b.undefined) {
      const n = this._getOrReturnCtx(e);
      return S(n, {
        code: y.invalid_type,
        expected: b.undefined,
        received: n.parsedType
      }), A;
    }
    return re(e.data);
  }
}
Ut.create = (t) => new Ut({
  typeName: P.ZodUndefined,
  ...C(t)
});
class jt extends N {
  _parse(e) {
    if (this._getType(e) !== b.null) {
      const n = this._getOrReturnCtx(e);
      return S(n, {
        code: y.invalid_type,
        expected: b.null,
        received: n.parsedType
      }), A;
    }
    return re(e.data);
  }
}
jt.create = (t) => new jt({
  typeName: P.ZodNull,
  ...C(t)
});
class Et extends N {
  constructor() {
    super(...arguments), this._any = !0;
  }
  _parse(e) {
    return re(e.data);
  }
}
Et.create = (t) => new Et({
  typeName: P.ZodAny,
  ...C(t)
});
class ot extends N {
  constructor() {
    super(...arguments), this._unknown = !0;
  }
  _parse(e) {
    return re(e.data);
  }
}
ot.create = (t) => new ot({
  typeName: P.ZodUnknown,
  ...C(t)
});
class Ue extends N {
  _parse(e) {
    const r = this._getOrReturnCtx(e);
    return S(r, {
      code: y.invalid_type,
      expected: b.never,
      received: r.parsedType
    }), A;
  }
}
Ue.create = (t) => new Ue({
  typeName: P.ZodNever,
  ...C(t)
});
class _r extends N {
  _parse(e) {
    if (this._getType(e) !== b.undefined) {
      const n = this._getOrReturnCtx(e);
      return S(n, {
        code: y.invalid_type,
        expected: b.void,
        received: n.parsedType
      }), A;
    }
    return re(e.data);
  }
}
_r.create = (t) => new _r({
  typeName: P.ZodVoid,
  ...C(t)
});
class Pe extends N {
  _parse(e) {
    const { ctx: r, status: n } = this._processInputParams(e), a = this._def;
    if (r.parsedType !== b.array)
      return S(r, {
        code: y.invalid_type,
        expected: b.array,
        received: r.parsedType
      }), A;
    if (a.exactLength !== null) {
      const i = r.data.length > a.exactLength.value, c = r.data.length < a.exactLength.value;
      (i || c) && (S(r, {
        code: i ? y.too_big : y.too_small,
        minimum: c ? a.exactLength.value : void 0,
        maximum: i ? a.exactLength.value : void 0,
        type: "array",
        inclusive: !0,
        exact: !0,
        message: a.exactLength.message
      }), n.dirty());
    }
    if (a.minLength !== null && r.data.length < a.minLength.value && (S(r, {
      code: y.too_small,
      minimum: a.minLength.value,
      type: "array",
      inclusive: !0,
      exact: !1,
      message: a.minLength.message
    }), n.dirty()), a.maxLength !== null && r.data.length > a.maxLength.value && (S(r, {
      code: y.too_big,
      maximum: a.maxLength.value,
      type: "array",
      inclusive: !0,
      exact: !1,
      message: a.maxLength.message
    }), n.dirty()), r.common.async)
      return Promise.all([...r.data].map((i, c) => a.type._parseAsync(new Ne(r, i, r.path, c)))).then((i) => J.mergeArray(n, i));
    const s = [...r.data].map((i, c) => a.type._parseSync(new Ne(r, i, r.path, c)));
    return J.mergeArray(n, s);
  }
  get element() {
    return this._def.type;
  }
  min(e, r) {
    return new Pe({
      ...this._def,
      minLength: { value: e, message: E.toString(r) }
    });
  }
  max(e, r) {
    return new Pe({
      ...this._def,
      maxLength: { value: e, message: E.toString(r) }
    });
  }
  length(e, r) {
    return new Pe({
      ...this._def,
      exactLength: { value: e, message: E.toString(r) }
    });
  }
  nonempty(e) {
    return this.min(1, e);
  }
}
Pe.create = (t, e) => new Pe({
  type: t,
  minLength: null,
  maxLength: null,
  exactLength: null,
  typeName: P.ZodArray,
  ...C(e)
});
function ht(t) {
  if (t instanceof U) {
    const e = {};
    for (const r in t.shape) {
      const n = t.shape[r];
      e[r] = Fe.create(ht(n));
    }
    return new U({
      ...t._def,
      shape: () => e
    });
  } else
    return t instanceof Pe ? new Pe({
      ...t._def,
      type: ht(t.element)
    }) : t instanceof Fe ? Fe.create(ht(t.unwrap())) : t instanceof lt ? lt.create(ht(t.unwrap())) : t instanceof Oe ? Oe.create(t.items.map((e) => ht(e))) : t;
}
class U extends N {
  constructor() {
    super(...arguments), this._cached = null, this.nonstrict = this.passthrough, this.augment = this.extend;
  }
  _getCached() {
    if (this._cached !== null)
      return this._cached;
    const e = this._def.shape(), r = R.objectKeys(e);
    return this._cached = { shape: e, keys: r };
  }
  _parse(e) {
    if (this._getType(e) !== b.object) {
      const l = this._getOrReturnCtx(e);
      return S(l, {
        code: y.invalid_type,
        expected: b.object,
        received: l.parsedType
      }), A;
    }
    const { status: n, ctx: a } = this._processInputParams(e), { shape: s, keys: i } = this._getCached(), c = [];
    if (!(this._def.catchall instanceof Ue && this._def.unknownKeys === "strip"))
      for (const l in a.data)
        i.includes(l) || c.push(l);
    const u = [];
    for (const l of i) {
      const d = s[l], f = a.data[l];
      u.push({
        key: { status: "valid", value: l },
        value: d._parse(new Ne(a, f, a.path, l)),
        alwaysSet: l in a.data
      });
    }
    if (this._def.catchall instanceof Ue) {
      const l = this._def.unknownKeys;
      if (l === "passthrough")
        for (const d of c)
          u.push({
            key: { status: "valid", value: d },
            value: { status: "valid", value: a.data[d] }
          });
      else if (l === "strict")
        c.length > 0 && (S(a, {
          code: y.unrecognized_keys,
          keys: c
        }), n.dirty());
      else if (l !== "strip")
        throw new Error("Internal ZodObject error: invalid unknownKeys value.");
    } else {
      const l = this._def.catchall;
      for (const d of c) {
        const f = a.data[d];
        u.push({
          key: { status: "valid", value: d },
          value: l._parse(
            new Ne(a, f, a.path, d)
            //, ctx.child(key), value, getParsedType(value)
          ),
          alwaysSet: d in a.data
        });
      }
    }
    return a.common.async ? Promise.resolve().then(async () => {
      const l = [];
      for (const d of u) {
        const f = await d.key;
        l.push({
          key: f,
          value: await d.value,
          alwaysSet: d.alwaysSet
        });
      }
      return l;
    }).then((l) => J.mergeObjectSync(n, l)) : J.mergeObjectSync(n, u);
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
          var a, s, i, c;
          const u = (i = (s = (a = this._def).errorMap) === null || s === void 0 ? void 0 : s.call(a, r, n).message) !== null && i !== void 0 ? i : n.defaultError;
          return r.code === "unrecognized_keys" ? {
            message: (c = E.errToObj(e).message) !== null && c !== void 0 ? c : u
          } : {
            message: u
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
      typeName: P.ZodObject
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
    return R.objectKeys(e).forEach((n) => {
      e[n] && this.shape[n] && (r[n] = this.shape[n]);
    }), new U({
      ...this._def,
      shape: () => r
    });
  }
  omit(e) {
    const r = {};
    return R.objectKeys(this.shape).forEach((n) => {
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
    return ht(this);
  }
  partial(e) {
    const r = {};
    return R.objectKeys(this.shape).forEach((n) => {
      const a = this.shape[n];
      e && !e[n] ? r[n] = a : r[n] = a.optional();
    }), new U({
      ...this._def,
      shape: () => r
    });
  }
  required(e) {
    const r = {};
    return R.objectKeys(this.shape).forEach((n) => {
      if (e && !e[n])
        r[n] = this.shape[n];
      else {
        let s = this.shape[n];
        for (; s instanceof Fe; )
          s = s._def.innerType;
        r[n] = s;
      }
    }), new U({
      ...this._def,
      shape: () => r
    });
  }
  keyof() {
    return ys(R.objectKeys(this.shape));
  }
}
U.create = (t, e) => new U({
  shape: () => t,
  unknownKeys: "strip",
  catchall: Ue.create(),
  typeName: P.ZodObject,
  ...C(e)
});
U.strictCreate = (t, e) => new U({
  shape: () => t,
  unknownKeys: "strict",
  catchall: Ue.create(),
  typeName: P.ZodObject,
  ...C(e)
});
U.lazycreate = (t, e) => new U({
  shape: t,
  unknownKeys: "strip",
  catchall: Ue.create(),
  typeName: P.ZodObject,
  ...C(e)
});
class Zt extends N {
  _parse(e) {
    const { ctx: r } = this._processInputParams(e), n = this._def.options;
    function a(s) {
      for (const c of s)
        if (c.result.status === "valid")
          return c.result;
      for (const c of s)
        if (c.result.status === "dirty")
          return r.common.issues.push(...c.ctx.common.issues), c.result;
      const i = s.map((c) => new xe(c.ctx.common.issues));
      return S(r, {
        code: y.invalid_union,
        unionErrors: i
      }), A;
    }
    if (r.common.async)
      return Promise.all(n.map(async (s) => {
        const i = {
          ...r,
          common: {
            ...r.common,
            issues: []
          },
          parent: null
        };
        return {
          result: await s._parseAsync({
            data: r.data,
            path: r.path,
            parent: i
          }),
          ctx: i
        };
      })).then(a);
    {
      let s;
      const i = [];
      for (const u of n) {
        const l = {
          ...r,
          common: {
            ...r.common,
            issues: []
          },
          parent: null
        }, d = u._parseSync({
          data: r.data,
          path: r.path,
          parent: l
        });
        if (d.status === "valid")
          return d;
        d.status === "dirty" && !s && (s = { result: d, ctx: l }), l.common.issues.length && i.push(l.common.issues);
      }
      if (s)
        return r.common.issues.push(...s.ctx.common.issues), s.result;
      const c = i.map((u) => new xe(u));
      return S(r, {
        code: y.invalid_union,
        unionErrors: c
      }), A;
    }
  }
  get options() {
    return this._def.options;
  }
}
Zt.create = (t, e) => new Zt({
  options: t,
  typeName: P.ZodUnion,
  ...C(e)
});
const cr = (t) => t instanceof Bt ? cr(t.schema) : t instanceof ke ? cr(t.innerType()) : t instanceof Ht ? [t.value] : t instanceof Ye ? t.options : t instanceof Vt ? Object.keys(t.enum) : t instanceof Wt ? cr(t._def.innerType) : t instanceof Ut ? [void 0] : t instanceof jt ? [null] : null;
class Mr extends N {
  _parse(e) {
    const { ctx: r } = this._processInputParams(e);
    if (r.parsedType !== b.object)
      return S(r, {
        code: y.invalid_type,
        expected: b.object,
        received: r.parsedType
      }), A;
    const n = this.discriminator, a = r.data[n], s = this.optionsMap.get(a);
    return s ? r.common.async ? s._parseAsync({
      data: r.data,
      path: r.path,
      parent: r
    }) : s._parseSync({
      data: r.data,
      path: r.path,
      parent: r
    }) : (S(r, {
      code: y.invalid_union_discriminator,
      options: Array.from(this.optionsMap.keys()),
      path: [n]
    }), A);
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
    const a = /* @__PURE__ */ new Map();
    for (const s of r) {
      const i = cr(s.shape[e]);
      if (!i)
        throw new Error(`A discriminator value for key \`${e}\` could not be extracted from all schema options`);
      for (const c of i) {
        if (a.has(c))
          throw new Error(`Discriminator property ${String(e)} has duplicate value ${String(c)}`);
        a.set(c, s);
      }
    }
    return new Mr({
      typeName: P.ZodDiscriminatedUnion,
      discriminator: e,
      options: r,
      optionsMap: a,
      ...C(n)
    });
  }
}
function sn(t, e) {
  const r = Ge(t), n = Ge(e);
  if (t === e)
    return { valid: !0, data: t };
  if (r === b.object && n === b.object) {
    const a = R.objectKeys(e), s = R.objectKeys(t).filter((c) => a.indexOf(c) !== -1), i = { ...t, ...e };
    for (const c of s) {
      const u = sn(t[c], e[c]);
      if (!u.valid)
        return { valid: !1 };
      i[c] = u.data;
    }
    return { valid: !0, data: i };
  } else if (r === b.array && n === b.array) {
    if (t.length !== e.length)
      return { valid: !1 };
    const a = [];
    for (let s = 0; s < t.length; s++) {
      const i = t[s], c = e[s], u = sn(i, c);
      if (!u.valid)
        return { valid: !1 };
      a.push(u.data);
    }
    return { valid: !0, data: a };
  } else
    return r === b.date && n === b.date && +t == +e ? { valid: !0, data: t } : { valid: !1 };
}
class zt extends N {
  _parse(e) {
    const { status: r, ctx: n } = this._processInputParams(e), a = (s, i) => {
      if (nn(s) || nn(i))
        return A;
      const c = sn(s.value, i.value);
      return c.valid ? ((on(s) || on(i)) && r.dirty(), { status: r.value, value: c.data }) : (S(n, {
        code: y.invalid_intersection_types
      }), A);
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
    ]).then(([s, i]) => a(s, i)) : a(this._def.left._parseSync({
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
zt.create = (t, e, r) => new zt({
  left: t,
  right: e,
  typeName: P.ZodIntersection,
  ...C(r)
});
class Oe extends N {
  _parse(e) {
    const { status: r, ctx: n } = this._processInputParams(e);
    if (n.parsedType !== b.array)
      return S(n, {
        code: y.invalid_type,
        expected: b.array,
        received: n.parsedType
      }), A;
    if (n.data.length < this._def.items.length)
      return S(n, {
        code: y.too_small,
        minimum: this._def.items.length,
        inclusive: !0,
        exact: !1,
        type: "array"
      }), A;
    !this._def.rest && n.data.length > this._def.items.length && (S(n, {
      code: y.too_big,
      maximum: this._def.items.length,
      inclusive: !0,
      exact: !1,
      type: "array"
    }), r.dirty());
    const s = [...n.data].map((i, c) => {
      const u = this._def.items[c] || this._def.rest;
      return u ? u._parse(new Ne(n, i, n.path, c)) : null;
    }).filter((i) => !!i);
    return n.common.async ? Promise.all(s).then((i) => J.mergeArray(r, i)) : J.mergeArray(r, s);
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
    typeName: P.ZodTuple,
    rest: null,
    ...C(e)
  });
};
class Gt extends N {
  get keySchema() {
    return this._def.keyType;
  }
  get valueSchema() {
    return this._def.valueType;
  }
  _parse(e) {
    const { status: r, ctx: n } = this._processInputParams(e);
    if (n.parsedType !== b.object)
      return S(n, {
        code: y.invalid_type,
        expected: b.object,
        received: n.parsedType
      }), A;
    const a = [], s = this._def.keyType, i = this._def.valueType;
    for (const c in n.data)
      a.push({
        key: s._parse(new Ne(n, c, n.path, c)),
        value: i._parse(new Ne(n, n.data[c], n.path, c))
      });
    return n.common.async ? J.mergeObjectAsync(r, a) : J.mergeObjectSync(r, a);
  }
  get element() {
    return this._def.valueType;
  }
  static create(e, r, n) {
    return r instanceof N ? new Gt({
      keyType: e,
      valueType: r,
      typeName: P.ZodRecord,
      ...C(n)
    }) : new Gt({
      keyType: Ee.create(),
      valueType: e,
      typeName: P.ZodRecord,
      ...C(r)
    });
  }
}
class br extends N {
  get keySchema() {
    return this._def.keyType;
  }
  get valueSchema() {
    return this._def.valueType;
  }
  _parse(e) {
    const { status: r, ctx: n } = this._processInputParams(e);
    if (n.parsedType !== b.map)
      return S(n, {
        code: y.invalid_type,
        expected: b.map,
        received: n.parsedType
      }), A;
    const a = this._def.keyType, s = this._def.valueType, i = [...n.data.entries()].map(([c, u], l) => ({
      key: a._parse(new Ne(n, c, n.path, [l, "key"])),
      value: s._parse(new Ne(n, u, n.path, [l, "value"]))
    }));
    if (n.common.async) {
      const c = /* @__PURE__ */ new Map();
      return Promise.resolve().then(async () => {
        for (const u of i) {
          const l = await u.key, d = await u.value;
          if (l.status === "aborted" || d.status === "aborted")
            return A;
          (l.status === "dirty" || d.status === "dirty") && r.dirty(), c.set(l.value, d.value);
        }
        return { status: r.value, value: c };
      });
    } else {
      const c = /* @__PURE__ */ new Map();
      for (const u of i) {
        const l = u.key, d = u.value;
        if (l.status === "aborted" || d.status === "aborted")
          return A;
        (l.status === "dirty" || d.status === "dirty") && r.dirty(), c.set(l.value, d.value);
      }
      return { status: r.value, value: c };
    }
  }
}
br.create = (t, e, r) => new br({
  valueType: e,
  keyType: t,
  typeName: P.ZodMap,
  ...C(r)
});
class ct extends N {
  _parse(e) {
    const { status: r, ctx: n } = this._processInputParams(e);
    if (n.parsedType !== b.set)
      return S(n, {
        code: y.invalid_type,
        expected: b.set,
        received: n.parsedType
      }), A;
    const a = this._def;
    a.minSize !== null && n.data.size < a.minSize.value && (S(n, {
      code: y.too_small,
      minimum: a.minSize.value,
      type: "set",
      inclusive: !0,
      exact: !1,
      message: a.minSize.message
    }), r.dirty()), a.maxSize !== null && n.data.size > a.maxSize.value && (S(n, {
      code: y.too_big,
      maximum: a.maxSize.value,
      type: "set",
      inclusive: !0,
      exact: !1,
      message: a.maxSize.message
    }), r.dirty());
    const s = this._def.valueType;
    function i(u) {
      const l = /* @__PURE__ */ new Set();
      for (const d of u) {
        if (d.status === "aborted")
          return A;
        d.status === "dirty" && r.dirty(), l.add(d.value);
      }
      return { status: r.value, value: l };
    }
    const c = [...n.data.values()].map((u, l) => s._parse(new Ne(n, u, n.path, l)));
    return n.common.async ? Promise.all(c).then((u) => i(u)) : i(c);
  }
  min(e, r) {
    return new ct({
      ...this._def,
      minSize: { value: e, message: E.toString(r) }
    });
  }
  max(e, r) {
    return new ct({
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
ct.create = (t, e) => new ct({
  valueType: t,
  minSize: null,
  maxSize: null,
  typeName: P.ZodSet,
  ...C(e)
});
class vt extends N {
  constructor() {
    super(...arguments), this.validate = this.implement;
  }
  _parse(e) {
    const { ctx: r } = this._processInputParams(e);
    if (r.parsedType !== b.function)
      return S(r, {
        code: y.invalid_type,
        expected: b.function,
        received: r.parsedType
      }), A;
    function n(c, u) {
      return yr({
        data: c,
        path: r.path,
        errorMaps: [
          r.common.contextualErrorMap,
          r.schemaErrorMap,
          hr(),
          Lt
        ].filter((l) => !!l),
        issueData: {
          code: y.invalid_arguments,
          argumentsError: u
        }
      });
    }
    function a(c, u) {
      return yr({
        data: c,
        path: r.path,
        errorMaps: [
          r.common.contextualErrorMap,
          r.schemaErrorMap,
          hr(),
          Lt
        ].filter((l) => !!l),
        issueData: {
          code: y.invalid_return_type,
          returnTypeError: u
        }
      });
    }
    const s = { errorMap: r.common.contextualErrorMap }, i = r.data;
    if (this._def.returns instanceof xt) {
      const c = this;
      return re(async function(...u) {
        const l = new xe([]), d = await c._def.args.parseAsync(u, s).catch((p) => {
          throw l.addIssue(n(u, p)), l;
        }), f = await Reflect.apply(i, this, d);
        return await c._def.returns._def.type.parseAsync(f, s).catch((p) => {
          throw l.addIssue(a(f, p)), l;
        });
      });
    } else {
      const c = this;
      return re(function(...u) {
        const l = c._def.args.safeParse(u, s);
        if (!l.success)
          throw new xe([n(u, l.error)]);
        const d = Reflect.apply(i, this, l.data), f = c._def.returns.safeParse(d, s);
        if (!f.success)
          throw new xe([a(d, f.error)]);
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
    return new vt({
      ...this._def,
      args: Oe.create(e).rest(ot.create())
    });
  }
  returns(e) {
    return new vt({
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
    return new vt({
      args: e || Oe.create([]).rest(ot.create()),
      returns: r || ot.create(),
      typeName: P.ZodFunction,
      ...C(n)
    });
  }
}
class Bt extends N {
  get schema() {
    return this._def.getter();
  }
  _parse(e) {
    const { ctx: r } = this._processInputParams(e);
    return this._def.getter()._parse({ data: r.data, path: r.path, parent: r });
  }
}
Bt.create = (t, e) => new Bt({
  getter: t,
  typeName: P.ZodLazy,
  ...C(e)
});
class Ht extends N {
  _parse(e) {
    if (e.data !== this._def.value) {
      const r = this._getOrReturnCtx(e);
      return S(r, {
        received: r.data,
        code: y.invalid_literal,
        expected: this._def.value
      }), A;
    }
    return { status: "valid", value: e.data };
  }
  get value() {
    return this._def.value;
  }
}
Ht.create = (t, e) => new Ht({
  value: t,
  typeName: P.ZodLiteral,
  ...C(e)
});
function ys(t, e) {
  return new Ye({
    values: t,
    typeName: P.ZodEnum,
    ...C(e)
  });
}
class Ye extends N {
  _parse(e) {
    if (typeof e.data != "string") {
      const r = this._getOrReturnCtx(e), n = this._def.values;
      return S(r, {
        expected: R.joinValues(n),
        received: r.parsedType,
        code: y.invalid_type
      }), A;
    }
    if (this._def.values.indexOf(e.data) === -1) {
      const r = this._getOrReturnCtx(e), n = this._def.values;
      return S(r, {
        received: r.data,
        code: y.invalid_enum_value,
        options: n
      }), A;
    }
    return re(e.data);
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
  extract(e) {
    return Ye.create(e);
  }
  exclude(e) {
    return Ye.create(this.options.filter((r) => !e.includes(r)));
  }
}
Ye.create = ys;
class Vt extends N {
  _parse(e) {
    const r = R.getValidEnumValues(this._def.values), n = this._getOrReturnCtx(e);
    if (n.parsedType !== b.string && n.parsedType !== b.number) {
      const a = R.objectValues(r);
      return S(n, {
        expected: R.joinValues(a),
        received: n.parsedType,
        code: y.invalid_type
      }), A;
    }
    if (r.indexOf(e.data) === -1) {
      const a = R.objectValues(r);
      return S(n, {
        received: n.data,
        code: y.invalid_enum_value,
        options: a
      }), A;
    }
    return re(e.data);
  }
  get enum() {
    return this._def.values;
  }
}
Vt.create = (t, e) => new Vt({
  values: t,
  typeName: P.ZodNativeEnum,
  ...C(e)
});
class xt extends N {
  unwrap() {
    return this._def.type;
  }
  _parse(e) {
    const { ctx: r } = this._processInputParams(e);
    if (r.parsedType !== b.promise && r.common.async === !1)
      return S(r, {
        code: y.invalid_type,
        expected: b.promise,
        received: r.parsedType
      }), A;
    const n = r.parsedType === b.promise ? r.data : Promise.resolve(r.data);
    return re(n.then((a) => this._def.type.parseAsync(a, {
      path: r.path,
      errorMap: r.common.contextualErrorMap
    })));
  }
}
xt.create = (t, e) => new xt({
  type: t,
  typeName: P.ZodPromise,
  ...C(e)
});
class ke extends N {
  innerType() {
    return this._def.schema;
  }
  sourceType() {
    return this._def.schema._def.typeName === P.ZodEffects ? this._def.schema.sourceType() : this._def.schema;
  }
  _parse(e) {
    const { status: r, ctx: n } = this._processInputParams(e), a = this._def.effect || null, s = {
      addIssue: (i) => {
        S(n, i), i.fatal ? r.abort() : r.dirty();
      },
      get path() {
        return n.path;
      }
    };
    if (s.addIssue = s.addIssue.bind(s), a.type === "preprocess") {
      const i = a.transform(n.data, s);
      return n.common.issues.length ? {
        status: "dirty",
        value: n.data
      } : n.common.async ? Promise.resolve(i).then((c) => this._def.schema._parseAsync({
        data: c,
        path: n.path,
        parent: n
      })) : this._def.schema._parseSync({
        data: i,
        path: n.path,
        parent: n
      });
    }
    if (a.type === "refinement") {
      const i = (c) => {
        const u = a.refinement(c, s);
        if (n.common.async)
          return Promise.resolve(u);
        if (u instanceof Promise)
          throw new Error("Async refinement encountered during synchronous parse operation. Use .parseAsync instead.");
        return c;
      };
      if (n.common.async === !1) {
        const c = this._def.schema._parseSync({
          data: n.data,
          path: n.path,
          parent: n
        });
        return c.status === "aborted" ? A : (c.status === "dirty" && r.dirty(), i(c.value), { status: r.value, value: c.value });
      } else
        return this._def.schema._parseAsync({ data: n.data, path: n.path, parent: n }).then((c) => c.status === "aborted" ? A : (c.status === "dirty" && r.dirty(), i(c.value).then(() => ({ status: r.value, value: c.value }))));
    }
    if (a.type === "transform")
      if (n.common.async === !1) {
        const i = this._def.schema._parseSync({
          data: n.data,
          path: n.path,
          parent: n
        });
        if (!Ft(i))
          return i;
        const c = a.transform(i.value, s);
        if (c instanceof Promise)
          throw new Error("Asynchronous transform encountered during synchronous parse operation. Use .parseAsync instead.");
        return { status: r.value, value: c };
      } else
        return this._def.schema._parseAsync({ data: n.data, path: n.path, parent: n }).then((i) => Ft(i) ? Promise.resolve(a.transform(i.value, s)).then((c) => ({ status: r.value, value: c })) : i);
    R.assertNever(a);
  }
}
ke.create = (t, e, r) => new ke({
  schema: t,
  typeName: P.ZodEffects,
  effect: e,
  ...C(r)
});
ke.createWithPreprocess = (t, e, r) => new ke({
  schema: e,
  effect: { type: "preprocess", transform: t },
  typeName: P.ZodEffects,
  ...C(r)
});
class Fe extends N {
  _parse(e) {
    return this._getType(e) === b.undefined ? re(void 0) : this._def.innerType._parse(e);
  }
  unwrap() {
    return this._def.innerType;
  }
}
Fe.create = (t, e) => new Fe({
  innerType: t,
  typeName: P.ZodOptional,
  ...C(e)
});
class lt extends N {
  _parse(e) {
    return this._getType(e) === b.null ? re(null) : this._def.innerType._parse(e);
  }
  unwrap() {
    return this._def.innerType;
  }
}
lt.create = (t, e) => new lt({
  innerType: t,
  typeName: P.ZodNullable,
  ...C(e)
});
class Wt extends N {
  _parse(e) {
    const { ctx: r } = this._processInputParams(e);
    let n = r.data;
    return r.parsedType === b.undefined && (n = this._def.defaultValue()), this._def.innerType._parse({
      data: n,
      path: r.path,
      parent: r
    });
  }
  removeDefault() {
    return this._def.innerType;
  }
}
Wt.create = (t, e) => new Wt({
  innerType: t,
  typeName: P.ZodDefault,
  defaultValue: typeof e.default == "function" ? e.default : () => e.default,
  ...C(e)
});
class wr extends N {
  _parse(e) {
    const { ctx: r } = this._processInputParams(e), n = {
      ...r,
      common: {
        ...r.common,
        issues: []
      }
    }, a = this._def.innerType._parse({
      data: n.data,
      path: n.path,
      parent: {
        ...n
      }
    });
    return gr(a) ? a.then((s) => ({
      status: "valid",
      value: s.status === "valid" ? s.value : this._def.catchValue({
        get error() {
          return new xe(n.common.issues);
        },
        input: n.data
      })
    })) : {
      status: "valid",
      value: a.status === "valid" ? a.value : this._def.catchValue({
        get error() {
          return new xe(n.common.issues);
        },
        input: n.data
      })
    };
  }
  removeCatch() {
    return this._def.innerType;
  }
}
wr.create = (t, e) => new wr({
  innerType: t,
  typeName: P.ZodCatch,
  catchValue: typeof e.catch == "function" ? e.catch : () => e.catch,
  ...C(e)
});
class Sr extends N {
  _parse(e) {
    if (this._getType(e) !== b.nan) {
      const n = this._getOrReturnCtx(e);
      return S(n, {
        code: y.invalid_type,
        expected: b.nan,
        received: n.parsedType
      }), A;
    }
    return { status: "valid", value: e.data };
  }
}
Sr.create = (t) => new Sr({
  typeName: P.ZodNaN,
  ...C(t)
});
const lc = Symbol("zod_brand");
class gs extends N {
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
class Qt extends N {
  _parse(e) {
    const { status: r, ctx: n } = this._processInputParams(e);
    if (n.common.async)
      return (async () => {
        const s = await this._def.in._parseAsync({
          data: n.data,
          path: n.path,
          parent: n
        });
        return s.status === "aborted" ? A : s.status === "dirty" ? (r.dirty(), hs(s.value)) : this._def.out._parseAsync({
          data: s.value,
          path: n.path,
          parent: n
        });
      })();
    {
      const a = this._def.in._parseSync({
        data: n.data,
        path: n.path,
        parent: n
      });
      return a.status === "aborted" ? A : a.status === "dirty" ? (r.dirty(), {
        status: "dirty",
        value: a.value
      }) : this._def.out._parseSync({
        data: a.value,
        path: n.path,
        parent: n
      });
    }
  }
  static create(e, r) {
    return new Qt({
      in: e,
      out: r,
      typeName: P.ZodPipeline
    });
  }
}
class Er extends N {
  _parse(e) {
    const r = this._def.innerType._parse(e);
    return Ft(r) && (r.value = Object.freeze(r.value)), r;
  }
}
Er.create = (t, e) => new Er({
  innerType: t,
  typeName: P.ZodReadonly,
  ...C(e)
});
const vs = (t, e = {}, r) => t ? Et.create().superRefine((n, a) => {
  var s, i;
  if (!t(n)) {
    const c = typeof e == "function" ? e(n) : typeof e == "string" ? { message: e } : e, u = (i = (s = c.fatal) !== null && s !== void 0 ? s : r) !== null && i !== void 0 ? i : !0, l = typeof c == "string" ? { message: c } : c;
    a.addIssue({ code: "custom", ...l, fatal: u });
  }
}) : Et.create(), uc = {
  object: U.lazycreate
};
var P;
(function(t) {
  t.ZodString = "ZodString", t.ZodNumber = "ZodNumber", t.ZodNaN = "ZodNaN", t.ZodBigInt = "ZodBigInt", t.ZodBoolean = "ZodBoolean", t.ZodDate = "ZodDate", t.ZodSymbol = "ZodSymbol", t.ZodUndefined = "ZodUndefined", t.ZodNull = "ZodNull", t.ZodAny = "ZodAny", t.ZodUnknown = "ZodUnknown", t.ZodNever = "ZodNever", t.ZodVoid = "ZodVoid", t.ZodArray = "ZodArray", t.ZodObject = "ZodObject", t.ZodUnion = "ZodUnion", t.ZodDiscriminatedUnion = "ZodDiscriminatedUnion", t.ZodIntersection = "ZodIntersection", t.ZodTuple = "ZodTuple", t.ZodRecord = "ZodRecord", t.ZodMap = "ZodMap", t.ZodSet = "ZodSet", t.ZodFunction = "ZodFunction", t.ZodLazy = "ZodLazy", t.ZodLiteral = "ZodLiteral", t.ZodEnum = "ZodEnum", t.ZodEffects = "ZodEffects", t.ZodNativeEnum = "ZodNativeEnum", t.ZodOptional = "ZodOptional", t.ZodNullable = "ZodNullable", t.ZodDefault = "ZodDefault", t.ZodCatch = "ZodCatch", t.ZodPromise = "ZodPromise", t.ZodBranded = "ZodBranded", t.ZodPipeline = "ZodPipeline", t.ZodReadonly = "ZodReadonly";
})(P || (P = {}));
const dc = (t, e = {
  message: `Input not instance of ${t.name}`
}) => vs((r) => r instanceof t, e), _s = Ee.create, bs = qe.create, fc = Sr.create, pc = Ke.create, ws = Dt.create, mc = it.create, hc = vr.create, yc = Ut.create, gc = jt.create, vc = Et.create, _c = ot.create, bc = Ue.create, wc = _r.create, Sc = Pe.create, Ec = U.create, xc = U.strictCreate, Pc = Zt.create, kc = Mr.create, Tc = zt.create, Ic = Oe.create, Ac = Gt.create, Cc = br.create, $c = ct.create, Nc = vt.create, Oc = Bt.create, Rc = Ht.create, Mc = Ye.create, Lc = Vt.create, Fc = xt.create, oo = ke.create, Dc = Fe.create, Uc = lt.create, jc = ke.createWithPreprocess, Zc = Qt.create, zc = () => _s().optional(), Gc = () => bs().optional(), Bc = () => ws().optional(), Hc = {
  string: (t) => Ee.create({ ...t, coerce: !0 }),
  number: (t) => qe.create({ ...t, coerce: !0 }),
  boolean: (t) => Dt.create({
    ...t,
    coerce: !0
  }),
  bigint: (t) => Ke.create({ ...t, coerce: !0 }),
  date: (t) => it.create({ ...t, coerce: !0 })
}, Vc = A;
var j = /* @__PURE__ */ Object.freeze({
  __proto__: null,
  defaultErrorMap: Lt,
  setErrorMap: Yi,
  getErrorMap: hr,
  makeIssue: yr,
  EMPTY_PATH: Ji,
  addIssueToContext: S,
  ParseStatus: J,
  INVALID: A,
  DIRTY: hs,
  OK: re,
  isAborted: nn,
  isDirty: on,
  isValid: Ft,
  isAsync: gr,
  get util() {
    return R;
  },
  get objectUtil() {
    return rn;
  },
  ZodParsedType: b,
  getParsedType: Ge,
  ZodType: N,
  ZodString: Ee,
  ZodNumber: qe,
  ZodBigInt: Ke,
  ZodBoolean: Dt,
  ZodDate: it,
  ZodSymbol: vr,
  ZodUndefined: Ut,
  ZodNull: jt,
  ZodAny: Et,
  ZodUnknown: ot,
  ZodNever: Ue,
  ZodVoid: _r,
  ZodArray: Pe,
  ZodObject: U,
  ZodUnion: Zt,
  ZodDiscriminatedUnion: Mr,
  ZodIntersection: zt,
  ZodTuple: Oe,
  ZodRecord: Gt,
  ZodMap: br,
  ZodSet: ct,
  ZodFunction: vt,
  ZodLazy: Bt,
  ZodLiteral: Ht,
  ZodEnum: Ye,
  ZodNativeEnum: Vt,
  ZodPromise: xt,
  ZodEffects: ke,
  ZodTransformer: ke,
  ZodOptional: Fe,
  ZodNullable: lt,
  ZodDefault: Wt,
  ZodCatch: wr,
  ZodNaN: Sr,
  BRAND: lc,
  ZodBranded: gs,
  ZodPipeline: Qt,
  ZodReadonly: Er,
  custom: vs,
  Schema: N,
  ZodSchema: N,
  late: uc,
  get ZodFirstPartyTypeKind() {
    return P;
  },
  coerce: Hc,
  any: vc,
  array: Sc,
  bigint: pc,
  boolean: ws,
  date: mc,
  discriminatedUnion: kc,
  effect: oo,
  enum: Mc,
  function: Nc,
  instanceof: dc,
  intersection: Tc,
  lazy: Oc,
  literal: Rc,
  map: Cc,
  nan: fc,
  nativeEnum: Lc,
  never: bc,
  null: gc,
  nullable: Uc,
  number: bs,
  object: Ec,
  oboolean: Bc,
  onumber: Gc,
  optional: Dc,
  ostring: zc,
  pipeline: Zc,
  preprocess: jc,
  promise: Fc,
  record: Ac,
  set: $c,
  strictObject: xc,
  string: _s,
  symbol: hc,
  transformer: oo,
  tuple: Ic,
  undefined: yc,
  union: Pc,
  unknown: _c,
  void: wc,
  NEVER: Vc,
  ZodIssueCode: y,
  quotelessJson: Ki,
  ZodError: xe
});
const Wc = j.object({
  width: j.number().positive(),
  height: j.number().positive()
});
function qc(t, e, r, n) {
  const a = document.createElement("plugin-modal");
  return a.setTheme(r), a.setAttribute("title", t), a.setAttribute("iframe-src", e), a.setAttribute("width", String((n == null ? void 0 : n.width) || 285)), a.setAttribute("height", String((n == null ? void 0 : n.height) || 540)), document.body.appendChild(a), a;
}
const Kc = j.function().args(
  j.string(),
  j.string(),
  j.enum(["dark", "light"]),
  Wc.optional()
).implement((t, e, r, n) => qc(t, e, r, n)), Yc = j.object({
  name: j.string(),
  host: j.string().url(),
  code: j.string(),
  icon: j.string().optional(),
  description: j.string().max(200).optional(),
  permissions: j.array(
    j.enum([
      "page:read",
      "page:write",
      "file:read",
      "file:write",
      "selection:read"
    ])
  )
});
function Ss(t, e) {
  return new URL(e, t).toString();
}
function Jc(t) {
  return fetch(t).then((e) => e.json()).then((e) => {
    if (!Yc.safeParse(e).success)
      throw new Error("Invalid plugin manifest");
    return e;
  }).catch((e) => {
    throw console.error(e), e;
  });
}
function Xc(t) {
  return fetch(Ss(t.host, t.code)).then((e) => {
    if (e.ok)
      return e.text();
    throw new Error("Failed to load plugin code");
  });
}
const an = [
  "finish",
  "pagechange",
  "filechange",
  "selectionchange",
  "themechange"
];
let cn = [], ne = null;
const Nt = /* @__PURE__ */ new Map();
window.addEventListener("message", (t) => {
  for (const e of cn)
    e(t.data);
});
function Qc(t, e) {
  t === "themechange" && ne && ne.setTheme(e), (Nt.get(t) || []).forEach((n) => n(e));
}
function el(t, e) {
  const r = () => {
    ne == null || ne.removeEventListener("close", r), ne && ne.remove(), cn = [], ne = null;
  }, n = (s) => {
    if (!e.permissions.includes(s))
      throw new Error(`Permission ${s} is not granted`);
  };
  return {
    ui: {
      open: (s, i, c) => {
        const u = t.getTheme();
        ne = Kc(
          s,
          Ss(e.host, i),
          u,
          c
        ), ne.setTheme(u), ne.addEventListener("close", r, {
          once: !0
        });
      },
      sendMessage(s) {
        const i = new CustomEvent("message", {
          detail: s
        });
        ne == null || ne.dispatchEvent(i);
      },
      onMessage: (s) => {
        j.function().parse(s), cn.push(s);
      }
    },
    utils: {
      types: {
        isText(s) {
          return s.type === "text";
        },
        isRectangle(s) {
          return s.type === "rect";
        },
        isFrame(s) {
          return s.type === "frame";
        }
      }
    },
    setTimeout: j.function().args(j.function(), j.number()).implement((s, i) => {
      setTimeout(s, i);
    }),
    closePlugin: r,
    on(s, i) {
      j.enum(an).parse(s), j.function().parse(i), s === "pagechange" ? n("page:read") : s === "filechange" ? n("file:read") : s === "selectionchange" && n("selection:read");
      const c = Nt.get(s) || [];
      c.push(i), Nt.set(s, c);
    },
    off(s, i) {
      j.enum(an).parse(s), j.function().parse(i);
      const c = Nt.get(s) || [];
      Nt.set(
        s,
        c.filter((u) => u !== i)
      );
    },
    // Penpot State API
    get root() {
      return n("page:read"), t.root;
    },
    get currentPage() {
      return n("page:read"), t.currentPage;
    },
    get selection() {
      return n("selection:read"), t.selection;
    },
    get viewport() {
      return t.viewport;
    },
    get library() {
      return t.library;
    },
    getFile() {
      return n("file:read"), t.getFile();
    },
    getPage() {
      return n("page:read"), t.getPage();
    },
    getSelected() {
      return n("selection:read"), t.getSelected();
    },
    getSelectedShapes() {
      return n("selection:read"), t.getSelectedShapes();
    },
    getTheme() {
      return t.getTheme();
    },
    createFrame() {
      return t.createFrame();
    },
    createRectangle() {
      return t.createRectangle();
    },
    createText(s) {
      return t.createText(s);
    },
    createShapeFromSvg(s) {
      return t.createShapeFromSvg(s);
    },
    group(s) {
      return t.group(s);
    },
    ungroup(s, ...i) {
      t.ungroup(s, ...i);
    },
    uploadMediaUrl(s, i) {
      return t.uploadMediaUrl(s, i);
    }
  };
}
let so = !1, yt, _t = null;
function tl(t) {
  _t = t;
}
function rl(t) {
  return () => {
    yt && _t && (yt.closePlugin(), t.id && _t.removeListener(t.id));
  };
}
const Es = async function(t) {
  try {
    const e = await Xc(t);
    if (so || (so = !0, hardenIntrinsics()), yt && yt.closePlugin(), _t) {
      yt = el(_t, t), new Compartment({
        penpot: harden(yt),
        fetch: window.fetch.bind(window),
        console: harden(window.console),
        Math: harden(Math)
      }).evaluate(e);
      let n = {};
      n.id = _t.addListener("finish", rl(n));
    } else
      console.error("Cannot find Penpot Context");
  } catch (e) {
    console.error(e);
  }
}, nl = async function(t) {
  const e = await Jc(t);
  Es(e);
};
console.log("%c[PLUGINS] Loading plugin system", "color: #008d7c");
repairIntrinsics({
  evalTaming: "unsafeEval",
  stackFiltering: "verbose",
  errorTaming: "unsafe",
  consoleTaming: "unsafe"
});
const ao = globalThis;
ao.initPluginsRuntime = (t) => {
  if (t) {
    console.log("%c[PLUGINS] Initialize context", "color: #008d7c"), ao.ɵcontext = t, globalThis.ɵloadPlugin = Es, globalThis.ɵloadPluginByUrl = nl, tl(t);
    for (const e of an)
      t.addListener(e, Qc.bind(null, e));
  }
};
//# sourceMappingURL=index.js.map
