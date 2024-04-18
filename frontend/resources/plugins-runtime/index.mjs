const E = globalThis, {
  Array: bs,
  Date: ws,
  FinalizationRegistry: gt,
  Float32Array: xs,
  JSON: Ss,
  Map: Ie,
  Math: Es,
  Number: ro,
  Object: nn,
  Promise: Ps,
  Proxy: br,
  Reflect: ks,
  RegExp: ze,
  Set: wt,
  String: ie,
  Symbol: At,
  WeakMap: Pe,
  WeakSet: xt
} = globalThis, {
  // The feral Error constructor is safe for internal use, but must not be
  // revealed to post-lockdown code in any compartment including the start
  // compartment since in V8 at least it bears stack inspection capabilities.
  Error: le,
  RangeError: Ts,
  ReferenceError: et,
  SyntaxError: Gt,
  TypeError: v
} = globalThis, {
  assign: wr,
  create: H,
  defineProperties: F,
  entries: te,
  freeze: y,
  getOwnPropertyDescriptor: ue,
  getOwnPropertyDescriptors: Ke,
  getOwnPropertyNames: Nt,
  getPrototypeOf: G,
  is: xr,
  isFrozen: Xc,
  isSealed: Qc,
  isExtensible: el,
  keys: no,
  prototype: oo,
  seal: tl,
  preventExtensions: Is,
  setPrototypeOf: so,
  values: ao,
  fromEntries: St
} = nn, {
  species: Pn,
  toStringTag: Be,
  iterator: Ht,
  matchAll: io,
  unscopables: As,
  keyFor: Cs,
  for: rl
} = At, { isInteger: Ns } = ro, { stringify: co } = Ss, { defineProperty: $s } = nn, L = (t, e, r) => {
  const n = $s(t, e, r);
  if (n !== t)
    throw v(
      `Please report that the original defineProperty silently failed to set ${co(
        ie(e)
      )}. (SES_DEFINE_PROPERTY_FAILED_SILENTLY)`
    );
  return n;
}, {
  apply: oe,
  construct: sr,
  get: Os,
  getOwnPropertyDescriptor: Rs,
  has: lo,
  isExtensible: Ms,
  ownKeys: nt,
  preventExtensions: Ls,
  set: uo
} = ks, { isArray: mt, prototype: ke } = bs, { prototype: Et } = Ie, { prototype: Sr } = RegExp, { prototype: Vt } = wt, { prototype: $e } = ie, { prototype: Er } = Pe, { prototype: fo } = xt, { prototype: on } = Function, { prototype: po } = Ps, Fs = G(Uint8Array.prototype), { bind: kn } = on, k = kn.bind(kn.call), se = k(oo.hasOwnProperty), Ge = k(ke.filter), tt = k(ke.forEach), Pr = k(ke.includes), Pt = k(ke.join), de = (
  /** @type {any} */
  k(ke.map)
), jr = k(ke.pop), ae = k(ke.push), Ds = k(ke.slice), js = k(ke.some), mo = k(ke.sort), Us = k(ke[Ht]), Ae = k(Et.set), Le = k(Et.get), kr = k(Et.has), Zs = k(Et.delete), zs = k(Et.entries), Bs = k(Et[Ht]), Tr = k(Vt.add);
k(Vt.delete);
const Tn = k(Vt.forEach), sn = k(Vt.has), Gs = k(Vt[Ht]), an = k(Sr.test), cn = k(Sr.exec), Hs = k(Sr[io]), ho = k($e.endsWith), Vs = k($e.includes), Ws = k($e.indexOf);
k($e.match);
const ar = (
  /** @type {any} */
  k($e.replace)
), qs = k($e.search), ln = k($e.slice), go = k($e.split), yo = k($e.startsWith), Ks = k($e[Ht]), Js = k(Er.delete), M = k(Er.get), un = k(Er.has), ee = k(Er.set), Ir = k(fo.add), Wt = k(fo.has), Ys = k(on.toString), Xs = k(po.catch), dn = (
  /** @type {any} */
  k(po.then)
), Qs = gt && k(gt.prototype.register);
gt && k(gt.prototype.unregister);
const fn = y(H(null)), He = (t) => nn(t) === t, pn = (t) => t instanceof le, vo = eval, ye = Function, ea = () => {
  throw v('Cannot eval with evalTaming set to "noEval" (SES_NO_EVAL)');
};
function ta() {
  return this;
}
if (ta())
  throw v("SES failed to initialize, sloppy mode (SES_NO_SLOPPY)");
const { freeze: Xe } = Object, { apply: ra } = Reflect, mn = (t) => (e, ...r) => ra(t, e, r), na = mn(Array.prototype.push), In = mn(Array.prototype.includes), oa = mn(String.prototype.split), Ye = JSON.stringify, Jt = (t, ...e) => {
  let r = t[0];
  for (let n = 0; n < e.length; n += 1)
    r = `${r}${e[n]}${t[n + 1]}`;
  throw Error(r);
}, _o = (t, e = !1) => {
  const r = [], n = (c, u, l = void 0) => {
    typeof c == "string" || Jt`Environment option name ${Ye(c)} must be a string.`, typeof u == "string" || Jt`Environment option default setting ${Ye(
      u
    )} must be a string.`;
    let d = u;
    const f = t.process || void 0, m = typeof f == "object" && f.env || void 0;
    if (typeof m == "object" && c in m) {
      e || na(r, c);
      const p = m[c];
      typeof p == "string" || Jt`Environment option named ${Ye(
        c
      )}, if present, must have a corresponding string value, got ${Ye(
        p
      )}`, d = p;
    }
    return l === void 0 || d === u || In(l, d) || Jt`Unrecognized ${Ye(c)} value ${Ye(
      d
    )}. Expected one of ${Ye([u, ...l])}`, d;
  };
  Xe(n);
  const a = (c) => {
    const u = n(c, "");
    return Xe(u === "" ? [] : oa(u, ","));
  };
  Xe(a);
  const s = (c, u) => In(a(c), u), i = () => Xe([...r]);
  return Xe(i), Xe({
    getEnvironmentOption: n,
    getEnvironmentOptionsList: a,
    environmentOptionsListHas: s,
    getCapturedEnvironmentOptionNames: i
  });
};
Xe(_o);
const {
  getEnvironmentOption: me,
  getEnvironmentOptionsList: nl,
  environmentOptionsListHas: ol
} = _o(globalThis, !0), ir = (t) => (t = `${t}`, t.length >= 1 && Vs("aeiouAEIOU", t[0]) ? `an ${t}` : `a ${t}`);
y(ir);
const bo = (t, e = void 0) => {
  const r = new wt(), n = (a, s) => {
    switch (typeof s) {
      case "object": {
        if (s === null)
          return null;
        if (sn(r, s))
          return "[Seen]";
        if (Tr(r, s), pn(s))
          return `[${s.name}: ${s.message}]`;
        if (Be in s)
          return `[${s[Be]}]`;
        if (mt(s))
          return s;
        const i = no(s);
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
        mo(i);
        const u = de(i, (l) => [l, s[l]]);
        return St(u);
      }
      case "function":
        return `[Function ${s.name || "<anon>"}]`;
      case "string":
        return yo(s, "[") ? `[${s}]` : s;
      case "undefined":
      case "symbol":
        return `[${ie(s)}]`;
      case "bigint":
        return `[${s}n]`;
      case "number":
        return xr(s, NaN) ? "[NaN]" : s === 1 / 0 ? "[Infinity]" : s === -1 / 0 ? "[-Infinity]" : s;
      default:
        return s;
    }
  };
  try {
    return co(t, n, e);
  } catch {
    return "[Something that failed to stringify]";
  }
};
y(bo);
const { isSafeInteger: sa } = Number, { freeze: ft } = Object, { toStringTag: aa } = Symbol, An = (t) => {
  const r = {
    next: void 0,
    prev: void 0,
    data: t
  };
  return r.next = r, r.prev = r, r;
}, Cn = (t, e) => {
  if (t === e)
    throw TypeError("Cannot splice a cell into itself");
  if (e.next !== e || e.prev !== e)
    throw TypeError("Expected self-linked cell");
  const r = e, n = t.next;
  return r.prev = t, r.next = n, t.next = r, n.prev = r, r;
}, Or = (t) => {
  const { prev: e, next: r } = t;
  e.next = r, r.prev = e, t.prev = t, t.next = t;
}, wo = (t) => {
  if (!sa(t) || t < 0)
    throw TypeError("keysBudget must be a safe non-negative integer number");
  const e = /* @__PURE__ */ new WeakMap();
  let r = 0;
  const n = An(void 0), a = (d) => {
    const f = e.get(d);
    if (!(f === void 0 || f.data === void 0))
      return Or(f), Cn(n, f), f;
  }, s = (d) => a(d) !== void 0;
  ft(s);
  const i = (d) => {
    const f = a(d);
    return f && f.data && f.data.get(d);
  };
  ft(i);
  const c = (d, f) => {
    if (t < 1)
      return l;
    let m = a(d);
    if (m === void 0 && (m = An(void 0), Cn(n, m)), !m.data)
      for (r += 1, m.data = /* @__PURE__ */ new WeakMap(), e.set(d, m); r > t; ) {
        const p = n.prev;
        Or(p), p.data = void 0, r -= 1;
      }
    return m.data.set(d, f), l;
  };
  ft(c);
  const u = (d) => {
    const f = e.get(d);
    return f === void 0 || (Or(f), e.delete(d), f.data === void 0) ? !1 : (f.data = void 0, r -= 1, !0);
  };
  ft(u);
  const l = ft({
    has: s,
    get: i,
    set: c,
    delete: u,
    // eslint-disable-next-line jsdoc/check-types
    [
      /** @type {typeof Symbol.toStringTag} */
      aa
    ]: "LRUCacheMap"
  });
  return l;
};
ft(wo);
const { freeze: rr } = Object, { isSafeInteger: ia } = Number, ca = 1e3, la = 100, xo = (t = ca, e = la) => {
  if (!ia(e) || e < 1)
    throw TypeError(
      "argsPerErrorBudget must be a safe positive integer number"
    );
  const r = wo(t), n = (s, i) => {
    const c = r.get(s);
    c !== void 0 ? (c.length >= e && c.shift(), c.push(i)) : r.set(s, [i]);
  };
  rr(n);
  const a = (s) => {
    const i = r.get(s);
    return r.delete(s), i;
  };
  return rr(a), rr({
    addLogArgs: n,
    takeLogArgsArray: a
  });
};
rr(xo);
const yt = new Pe(), ot = (t, e = void 0) => {
  const r = y({
    toString: y(() => bo(t, e))
  });
  return ee(yt, r, t), r;
};
y(ot);
const ua = y(/^[\w:-]( ?[\w:-])*$/), Ur = (t, e = void 0) => {
  if (typeof t != "string" || !an(ua, t))
    return ot(t, e);
  const r = y({
    toString: y(() => t)
  });
  return ee(yt, r, t), r;
};
y(Ur);
const Ar = new Pe(), So = ({ template: t, args: e }) => {
  const r = [t[0]];
  for (let n = 0; n < e.length; n += 1) {
    const a = e[n];
    let s;
    un(yt, a) ? s = `${a}` : pn(a) ? s = `(${ir(a.name)})` : s = `(${ir(typeof a)})`, ae(r, s, t[n + 1]);
  }
  return Pt(r, "");
}, Eo = y({
  toString() {
    const t = M(Ar, this);
    return t === void 0 ? "[Not a DetailsToken]" : So(t);
  }
});
y(Eo.toString);
const vt = (t, ...e) => {
  const r = y({ __proto__: Eo });
  return ee(Ar, r, { template: t, args: e }), r;
};
y(vt);
const Po = (t, ...e) => (e = de(
  e,
  (r) => un(yt, r) ? r : ot(r)
), vt(t, ...e));
y(Po);
const ko = ({ template: t, args: e }) => {
  const r = [t[0]];
  for (let n = 0; n < e.length; n += 1) {
    let a = e[n];
    un(yt, a) && (a = M(yt, a));
    const s = ar(jr(r) || "", / $/, "");
    s !== "" && ae(r, s);
    const i = ar(t[n + 1], /^ /, "");
    ae(r, a, i);
  }
  return r[r.length - 1] === "" && jr(r), r;
}, nr = new Pe();
let Zr = 0;
const Nn = new Pe(), To = (t, e = t.name) => {
  let r = M(Nn, t);
  return r !== void 0 || (Zr += 1, r = `${e}#${Zr}`, ee(Nn, t, r)), r;
}, zr = (t = vt`Assert failed`, e = E.Error, { errorName: r = void 0 } = {}) => {
  typeof t == "string" && (t = vt([t]));
  const n = M(Ar, t);
  if (n === void 0)
    throw v(`unrecognized details ${ot(t)}`);
  const a = So(n), s = new e(a);
  return ee(nr, s, ko(n)), r !== void 0 && To(s, r), s;
};
y(zr);
const { addLogArgs: da, takeLogArgsArray: fa } = xo(), Br = new Pe(), Io = (t, e) => {
  typeof e == "string" && (e = vt([e]));
  const r = M(Ar, e);
  if (r === void 0)
    throw v(`unrecognized details ${ot(e)}`);
  const n = ko(r), a = M(Br, t);
  if (a !== void 0)
    for (const s of a)
      s(t, n);
  else
    da(t, n);
};
y(Io);
const pa = (t) => {
  if (!("stack" in t))
    return "";
  const e = `${t.stack}`, r = Ws(e, `
`);
  return yo(e, " ") || r === -1 ? e : ln(e, r + 1);
}, Gr = {
  getStackString: E.getStackString || pa,
  tagError: (t) => To(t),
  resetErrorTagNum: () => {
    Zr = 0;
  },
  getMessageLogArgs: (t) => M(nr, t),
  takeMessageLogArgs: (t) => {
    const e = M(nr, t);
    return Js(nr, t), e;
  },
  takeNoteLogArgsArray: (t, e) => {
    const r = fa(t);
    if (e !== void 0) {
      const n = M(Br, t);
      n ? ae(n, e) : ee(Br, t, [e]);
    }
    return r || [];
  }
};
y(Gr);
const Cr = (t = void 0, e = !1) => {
  const r = e ? Po : vt, n = r`Check failed`, a = (f = n, m = E.Error) => {
    const p = zr(f, m);
    throw t !== void 0 && t(p), p;
  };
  y(a);
  const s = (f, ...m) => a(r(f, ...m));
  function i(f, m = void 0, p = void 0) {
    f || a(m, p);
  }
  const c = (f, m, p = void 0, h = void 0) => {
    xr(f, m) || a(
      p || r`Expected ${f} is same as ${m}`,
      h || Ts
    );
  };
  y(c);
  const u = (f, m, p) => {
    if (typeof f !== m) {
      if (typeof m == "string" || s`${ot(m)} must be a string`, p === void 0) {
        const h = ir(m);
        p = r`${f} must be ${Ur(h)}`;
      }
      a(p, v);
    }
  };
  y(u);
  const d = wr(i, {
    error: zr,
    fail: a,
    equal: c,
    typeof: u,
    string: (f, m = void 0) => u(f, "string", m),
    note: Io,
    details: r,
    Fail: s,
    quote: ot,
    bare: Ur,
    makeAssert: Cr
  });
  return y(d);
};
y(Cr);
const Z = Cr(), Ao = ue(
  Fs,
  Be
);
Z(Ao);
const Co = Ao.get;
Z(Co);
const ma = (t) => oe(Co, t, []) !== void 0, ha = (t) => {
  const e = +ie(t);
  return Ns(e) && ie(e) === t;
}, ga = (t) => {
  Is(t), tt(nt(t), (e) => {
    const r = ue(t, e);
    Z(r), ha(e) || L(t, e, {
      ...r,
      writable: !1,
      configurable: !1
    });
  });
}, ya = () => {
  if (typeof E.harden == "function")
    return E.harden;
  const t = new xt(), { harden: e } = {
    /**
     * @template T
     * @param {T} root
     * @returns {T}
     */
    harden(r) {
      const n = new wt(), a = new Pe();
      function s(d, f = void 0) {
        if (!He(d))
          return;
        const m = typeof d;
        if (m !== "object" && m !== "function")
          throw v(`Unexpected typeof: ${m}`);
        Wt(t, d) || sn(n, d) || (Tr(n, d), ee(a, d, f));
      }
      function i(d) {
        ma(d) ? ga(d) : y(d);
        const f = M(a, d) || "unknown", m = Ke(d), p = G(d);
        s(p, `${f}.__proto__`), tt(nt(m), (h) => {
          const _ = `${f}.${ie(h)}`, w = m[
            /** @type {string} */
            h
          ];
          se(w, "value") ? s(w.value, `${_}`) : (s(w.get, `${_}(get)`), s(w.set, `${_}(set)`));
        });
      }
      function c() {
        Tn(n, i);
      }
      function u(d) {
        Ir(t, d);
      }
      function l() {
        Tn(n, u);
      }
      return s(r), c(), l(), r;
    }
  };
  return e;
}, No = {
  // *** Value Properties of the Global Object
  Infinity: 1 / 0,
  NaN: NaN,
  undefined: void 0
}, $o = {
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
}, $n = {
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
}, Oo = {
  // *** Constructor Properties of the Global Object
  Date: "%SharedDate%",
  Error: "%SharedError%",
  RegExp: "%SharedRegExp%",
  Symbol: "%SharedSymbol%",
  // *** Other Properties of the Global Object
  Math: "%SharedMath%"
}, va = [
  EvalError,
  RangeError,
  ReferenceError,
  SyntaxError,
  TypeError,
  URIError
], Hr = {
  "[[Proto]]": "%FunctionPrototype%",
  length: "number",
  name: "string"
  // Do not specify "prototype" here, since only Function instances that can
  // be used as a constructor have a prototype property. For constructors,
  // since prototype properties are instance-specific, we define it there.
}, _a = {
  // This property is not mentioned in ECMA 262, but is present in V8 and
  // necessary for lockdown to succeed.
  "[[Proto]]": "%AsyncFunctionPrototype%"
}, o = Hr, On = _a, O = {
  get: o,
  set: "undefined"
}, Te = {
  get: o,
  set: o
}, Rn = (t) => t === O || t === Te;
function lt(t) {
  return {
    // Properties of the NativeError Constructors
    "[[Proto]]": "%SharedError%",
    // NativeError.prototype
    prototype: t
  };
}
function ut(t) {
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
function he(t) {
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
const Mn = {
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
}, cr = {
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
    "--proto--": Te,
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
    stackTraceLimit: Te,
    // Non standard, v8 only, used by several, tamed to accessor
    prepareStackTrace: Te
  },
  "%SharedError%": {
    // Properties of the Error Constructor
    "[[Proto]]": "%FunctionPrototype%",
    prototype: "%ErrorPrototype%",
    // Non standard, v8 only, used by tap
    captureStackTrace: o,
    // Non standard, v8 only, used by tap, tamed to accessor
    stackTraceLimit: Te,
    // Non standard, v8 only, used by several, tamed to accessor
    prepareStackTrace: Te
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
    stack: Te,
    // Superfluously present in some versions of V8.
    // https://github.com/tc39/notes/blob/master/meetings/2021-10/oct-26.md#:~:text=However%2C%20Chrome%2093,and%20node%2016.11.
    cause: !1
  },
  // NativeError
  EvalError: lt("%EvalErrorPrototype%"),
  RangeError: lt("%RangeErrorPrototype%"),
  ReferenceError: lt("%ReferenceErrorPrototype%"),
  SyntaxError: lt("%SyntaxErrorPrototype%"),
  TypeError: lt("%TypeErrorPrototype%"),
  URIError: lt("%URIErrorPrototype%"),
  "%EvalErrorPrototype%": ut("EvalError"),
  "%RangeErrorPrototype%": ut("RangeError"),
  "%ReferenceErrorPrototype%": ut("ReferenceError"),
  "%SyntaxErrorPrototype%": ut("SyntaxError"),
  "%TypeErrorPrototype%": ut("TypeError"),
  "%URIErrorPrototype%": ut("URIError"),
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
    ...Mn,
    // `%InitialMath%.random()` has the standard unsafe behavior
    random: o
  },
  "%SharedMath%": {
    ...Mn,
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
  BigInt64Array: he("%BigInt64ArrayPrototype%"),
  BigUint64Array: he("%BigUint64ArrayPrototype%"),
  // https://github.com/tc39/proposal-float16array
  Float16Array: he("%Float16ArrayPrototype%"),
  Float32Array: he("%Float32ArrayPrototype%"),
  Float64Array: he("%Float64ArrayPrototype%"),
  Int16Array: he("%Int16ArrayPrototype%"),
  Int32Array: he("%Int32ArrayPrototype%"),
  Int8Array: he("%Int8ArrayPrototype%"),
  Uint16Array: he("%Uint16ArrayPrototype%"),
  Uint32Array: he("%Uint32ArrayPrototype%"),
  Uint8Array: he("%Uint8ArrayPrototype%"),
  Uint8ClampedArray: he("%Uint8ClampedArrayPrototype%"),
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
    "UniqueSymbol(async_id_symbol)": Te,
    "UniqueSymbol(trigger_async_id_symbol)": Te,
    "UniqueSymbol(destroyed)": Te
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
    import: On,
    load: On,
    importNow: o,
    module: o,
    "@@toStringTag": "string"
  },
  lockdown: o,
  harden: { ...o, isFake: "boolean" },
  "%InitialGetStackString%": o
}, ba = (t) => typeof t == "function";
function wa(t, e, r) {
  if (se(t, e)) {
    const n = ue(t, e);
    if (!n || !xr(n.value, r.value) || n.get !== r.get || n.set !== r.set || n.writable !== r.writable || n.enumerable !== r.enumerable || n.configurable !== r.configurable)
      throw v(`Conflicting definitions of ${e}`);
  }
  L(t, e, r);
}
function xa(t, e) {
  for (const [r, n] of te(e))
    wa(t, r, n);
}
function Ro(t, e) {
  const r = { __proto__: null };
  for (const [n, a] of te(e))
    se(t, n) && (r[a] = t[n]);
  return r;
}
const Mo = () => {
  const t = H(null);
  let e;
  const r = (c) => {
    xa(t, Ke(c));
  };
  y(r);
  const n = () => {
    for (const [c, u] of te(t)) {
      if (!He(u) || !se(u, "prototype"))
        continue;
      const l = cr[c];
      if (typeof l != "object")
        throw v(`Expected permit object at whitelist.${c}`);
      const d = l.prototype;
      if (!d)
        throw v(`${c}.prototype property not whitelisted`);
      if (typeof d != "string" || !se(cr, d))
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
  y(n);
  const a = () => (y(t), e = new xt(Ge(ao(t), ba)), t);
  y(a);
  const s = (c) => {
    if (!e)
      throw v(
        "isPseudoNative can only be called after finalIntrinsics"
      );
    return Wt(e, c);
  };
  y(s);
  const i = {
    addIntrinsics: r,
    completePrototypes: n,
    finalIntrinsics: a,
    isPseudoNative: s
  };
  return y(i), r(No), r(Ro(E, $o)), i;
}, Sa = (t) => {
  const { addIntrinsics: e, finalIntrinsics: r } = Mo();
  return e(Ro(t, Oo)), r();
};
function Ea(t, e) {
  let r = !1;
  const n = (m, ...p) => (r || (console.groupCollapsed("Removing unpermitted intrinsics"), r = !0), console[m](...p)), a = ["undefined", "boolean", "number", "string", "symbol"], s = new Ie(
    At ? de(
      Ge(
        te(cr["%SharedSymbol%"]),
        ([m, p]) => p === "symbol" && typeof At[m] == "symbol"
      ),
      ([m]) => [At[m], `@@${m}`]
    ) : []
  );
  function i(m, p) {
    if (typeof p == "string")
      return p;
    const h = Le(s, p);
    if (typeof p == "symbol") {
      if (h)
        return h;
      {
        const _ = Cs(p);
        return _ !== void 0 ? `RegisteredSymbol(${_})` : `Unique${ie(p)}`;
      }
    }
    throw v(`Unexpected property name type ${m} ${p}`);
  }
  function c(m, p, h) {
    if (!He(p))
      throw v(`Object expected: ${m}, ${p}, ${h}`);
    const _ = G(p);
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
      } else if (Pr(a, _)) {
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
    const w = ue(p, h);
    if (!w)
      throw v(`Property ${h} not found at ${m}`);
    if (se(w, "value")) {
      if (Rn(_))
        throw v(`Accessor expected at ${m}`);
      return u(m, w.value, h, _);
    }
    if (!Rn(_))
      throw v(`Accessor not expected at ${m}`);
    return u(`${m}<get>`, w.get, h, _.get) && u(`${m}<set>`, w.set, h, _.set);
  }
  function d(m, p, h) {
    const _ = h === "__proto__" ? "--proto--" : h;
    if (se(p, _))
      return p[_];
    if (typeof m == "function" && se(Hr, _))
      return Hr[_];
  }
  function f(m, p, h) {
    if (p == null)
      return;
    const _ = h["[[Proto]]"];
    c(m, p, _), typeof p == "function" && e(p);
    for (const w of nt(p)) {
      const I = i(m, w), N = `${m}.${I}`, T = d(p, h, I);
      if (!T || !l(N, p, w, T)) {
        T !== !1 && n("warn", `Removing ${N}`);
        try {
          delete p[w];
        } catch (D) {
          if (w in p) {
            if (typeof p == "function" && w === "prototype" && (p.prototype = void 0, p.prototype === void 0)) {
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
    f("intrinsics", t, cr);
  } finally {
    r && console.groupEnd();
  }
}
function Pa() {
  try {
    ye.prototype.constructor("return 1");
  } catch {
    return y({});
  }
  const t = {};
  function e(r, n, a) {
    let s;
    try {
      s = (0, eval)(a);
    } catch (u) {
      if (u instanceof Gt)
        return;
      throw u;
    }
    const i = G(s), c = function() {
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
    }), c !== ye.prototype.constructor && so(c, ye.prototype.constructor), t[n] = c;
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
function ka(t = "safe") {
  if (t !== "safe" && t !== "unsafe")
    throw v(`unrecognized dateTaming ${t}`);
  const e = ws, r = e.prototype, n = {
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
      return new.target === void 0 ? oe(e, void 0, d) : sr(e, d, new.target);
    } : u = function(...d) {
      if (new.target === void 0)
        throw v(
          "secure mode Calling %SharedDate% constructor as a function throws"
        );
      if (d.length === 0)
        throw v(
          "secure mode Calling new %SharedDate%() with no arguments throws"
        );
      return sr(e, d, new.target);
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
function Ta(t = "safe") {
  if (t !== "safe" && t !== "unsafe")
    throw v(`unrecognized mathTaming ${t}`);
  const e = Es, r = e, { random: n, ...a } = Ke(e), i = H(oo, {
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
function Ia(t = "safe") {
  if (t !== "safe" && t !== "unsafe")
    throw v(`unrecognized regExpTaming ${t}`);
  const e = ze.prototype, r = (s = {}) => {
    const i = function(...l) {
      return new.target === void 0 ? ze(...l) : sr(ze, l, new.target);
    }, c = ue(ze, Pn);
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
      [Pn]: c
    }), i;
  }, n = r(), a = r();
  return t !== "unsafe" && delete e.compile, F(e, {
    constructor: { value: a }
  }), {
    "%InitialRegExp%": n,
    "%SharedRegExp%": a
  };
}
const Aa = {
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
    [Be]: !0
  }
}, Lo = {
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
    [Ht]: !0
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
    [Be]: !0
  }
}, Ca = {
  ...Lo,
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
function Na(t, e, r = []) {
  const n = new wt(r);
  function a(l, d, f, m) {
    if ("value" in m && m.configurable) {
      const { value: p } = m, h = sn(n, f), { get: _, set: w } = ue(
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
    const m = ue(d, f);
    m && a(l, d, f, m);
  }
  function i(l, d) {
    const f = Ke(d);
    f && tt(nt(f), (m) => a(l, d, m, f[m]));
  }
  function c(l, d, f) {
    for (const m of nt(f)) {
      const p = ue(d, m);
      if (!p || p.get || p.set)
        continue;
      const h = `${l}.${ie(m)}`, _ = f[m];
      if (_ === !0)
        s(h, d, m);
      else if (_ === "*")
        i(h, p.value);
      else if (He(_))
        c(h, p.value, _);
      else
        throw v(`Unexpected override enablement plan ${h}`);
    }
  }
  let u;
  switch (e) {
    case "min": {
      u = Aa;
      break;
    }
    case "moderate": {
      u = Lo;
      break;
    }
    case "severe": {
      u = Ca;
      break;
    }
    default:
      throw v(`unrecognized overrideTaming ${e}`);
  }
  c("root", t, u);
}
const { Fail: Vr, quote: lr } = Z, $a = /^(\w*[a-z])Locale([A-Z]\w*)$/, Fo = {
  // See https://tc39.es/ecma262/#sec-string.prototype.localecompare
  localeCompare(t) {
    if (this === null || this === void 0)
      throw v(
        'Cannot localeCompare with null or undefined "this" value'
      );
    const e = `${this}`, r = `${t}`;
    return e < r ? -1 : e > r ? 1 : (e === r || Vr`expected ${lr(e)} and ${lr(r)} to compare`, 0);
  },
  toString() {
    return `${this}`;
  }
}, Oa = Fo.localeCompare, Ra = Fo.toString;
function Ma(t, e = "safe") {
  if (e !== "safe" && e !== "unsafe")
    throw v(`unrecognized localeTaming ${e}`);
  if (e !== "unsafe") {
    L(ie.prototype, "localeCompare", {
      value: Oa
    });
    for (const r of Nt(t)) {
      const n = t[r];
      if (He(n))
        for (const a of Nt(n)) {
          const s = cn($a, a);
          if (s) {
            typeof n[a] == "function" || Vr`expected ${lr(a)} to be a function`;
            const i = `${s[1]}${s[2]}`, c = n[i];
            typeof c == "function" || Vr`function ${lr(i)} not found`, L(n, a, { value: c });
          }
        }
    }
    L(ro.prototype, "toLocaleString", {
      value: Ra
    });
  }
}
const La = (t) => ({
  eval(r) {
    return typeof r != "string" ? r : t(r);
  }
}).eval, { Fail: Ln } = Z, Fa = (t) => {
  const e = function(n) {
    const a = `${jr(arguments) || ""}`, s = `${Pt(arguments, ",")}`;
    new ye(s, ""), new ye(a);
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
      value: ye.prototype,
      writable: !1,
      enumerable: !1,
      configurable: !1
    }
  }), G(ye) === ye.prototype || Ln`Function prototype is the same accross compartments`, G(e) === ye.prototype || Ln`Function constructor prototype is the same accross compartments`, e;
}, Da = (t) => {
  L(
    t,
    As,
    y(
      wr(H(null), {
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
}, Do = (t) => {
  for (const [e, r] of te(No))
    L(t, e, {
      value: r,
      writable: !1,
      enumerable: !1,
      configurable: !1
    });
}, jo = (t, {
  intrinsics: e,
  newGlobalPropertyNames: r,
  makeCompartmentConstructor: n,
  markVirtualizedNativeFunction: a
}) => {
  for (const [i, c] of te($o))
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
  s.Compartment = y(
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
}, Wr = (t, e, r) => {
  {
    const n = y(La(e));
    r(n), L(t, "eval", {
      value: n,
      writable: !0,
      enumerable: !1,
      configurable: !0
    });
  }
  {
    const n = y(Fa(e));
    r(n), L(t, "Function", {
      value: n,
      writable: !0,
      enumerable: !1,
      configurable: !0
    });
  }
}, { Fail: ja, quote: Uo } = Z, Zo = new br(
  fn,
  y({
    get(t, e) {
      ja`Please report unexpected scope handler trap: ${Uo(ie(e))}`;
    }
  })
), Ua = {
  get(t, e) {
  },
  set(t, e, r) {
    throw et(`${ie(e)} is not defined`);
  },
  has(t, e) {
    return e in E;
  },
  // note: this is likely a bug of safari
  // https://bugs.webkit.org/show_bug.cgi?id=195534
  getPrototypeOf(t) {
    return null;
  },
  // See https://github.com/endojs/endo/issues/1510
  // TODO: report as bug to v8 or Chrome, and record issue link here.
  getOwnPropertyDescriptor(t, e) {
    const r = Uo(ie(e));
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
}, zo = y(
  H(
    Zo,
    Ke(Ua)
  )
), Za = new br(
  fn,
  zo
), Bo = (t) => {
  const e = {
    // inherit scopeTerminator behavior
    ...zo,
    // Redirect set properties to the globalObject.
    set(a, s, i) {
      return uo(t, s, i);
    },
    // Always claim to have a potential property in order to be the recipient of a set
    has(a, s) {
      return !0;
    }
  }, r = y(
    H(
      Zo,
      Ke(e)
    )
  );
  return new br(
    fn,
    r
  );
};
y(Bo);
const { Fail: za } = Z, Ba = () => {
  const t = H(null), e = y({
    eval: {
      get() {
        return delete t.eval, vo;
      },
      enumerable: !1,
      configurable: !0
    }
  }), r = {
    evalScope: t,
    allowNextEvalToBeUnsafe() {
      const { revoked: n } = r;
      n !== null && za`a handler did not reset allowNextEvalToBeUnsafe ${n.err}`, F(t, e);
    },
    /** @type {null | { err: any }} */
    revoked: null
  };
  return r;
}, Fn = "\\s*[@#]\\s*([a-zA-Z][a-zA-Z0-9]*)\\s*=\\s*([^\\s\\*]*)", Ga = new ze(
  `(?:\\s*//${Fn}|/\\*${Fn}\\s*\\*/)\\s*$`
), hn = (t) => {
  let e = "<unknown>";
  for (; t.length > 0; ) {
    const r = cn(Ga, t);
    if (r === null)
      break;
    t = ln(t, 0, t.length - r[0].length), r[3] === "sourceURL" ? e = r[4] : r[1] === "sourceURL" && (e = r[2]);
  }
  return e;
};
function gn(t, e) {
  const r = qs(t, e);
  if (r < 0)
    return -1;
  const n = t[r] === `
` ? 1 : 0;
  return go(ln(t, 0, r), `
`).length + n;
}
const Go = new ze("(?:<!--|-->)", "g"), Ho = (t) => {
  const e = gn(t, Go);
  if (e < 0)
    return t;
  const r = hn(t);
  throw Gt(
    `Possible HTML comment rejected at ${r}:${e}. (SES_HTML_COMMENT_REJECTED)`
  );
}, Vo = (t) => ar(t, Go, (r) => r[0] === "<" ? "< ! --" : "-- >"), Wo = new ze(
  "(^|[^.]|\\.\\.\\.)\\bimport(\\s*(?:\\(|/[/*]))",
  "g"
), qo = (t) => {
  const e = gn(t, Wo);
  if (e < 0)
    return t;
  const r = hn(t);
  throw Gt(
    `Possible import expression rejected at ${r}:${e}. (SES_IMPORT_REJECTED)`
  );
}, Ko = (t) => ar(t, Wo, (r, n, a) => `${n}__import__${a}`), Ha = new ze(
  "(^|[^.])\\beval(\\s*\\()",
  "g"
), Jo = (t) => {
  const e = gn(t, Ha);
  if (e < 0)
    return t;
  const r = hn(t);
  throw Gt(
    `Possible direct eval expression rejected at ${r}:${e}. (SES_EVAL_REJECTED)`
  );
}, Yo = (t) => (t = Ho(t), t = qo(t), t), Xo = (t, e) => {
  for (const r of e)
    t = r(t);
  return t;
};
y({
  rejectHtmlComments: y(Ho),
  evadeHtmlCommentTest: y(Vo),
  rejectImportExpressions: y(qo),
  evadeImportExpressionTest: y(Ko),
  rejectSomeDirectEvalExpressions: y(Jo),
  mandatoryTransforms: y(Yo),
  applyTransforms: y(Xo)
});
const Va = [
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
], Wa = /^[a-zA-Z_$][\w$]*$/, Dn = (t) => t !== "eval" && !Pr(Va, t) && an(Wa, t);
function jn(t, e) {
  const r = ue(t, e);
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
const qa = (t, e = {}) => {
  const r = Nt(t), n = Nt(e), a = Ge(
    n,
    (i) => Dn(i) && jn(e, i)
  );
  return {
    globalObjectConstants: Ge(
      r,
      (i) => (
        // Can't define a constant: it would prevent a
        // lookup on the endowments.
        !Pr(n, i) && Dn(i) && jn(t, i)
      )
    ),
    moduleLexicalConstants: a
  };
};
function Un(t, e) {
  return t.length === 0 ? "" : `const {${Pt(t, ",")}} = this.${e};`;
}
const Ka = (t) => {
  const { globalObjectConstants: e, moduleLexicalConstants: r } = qa(
    t.globalObject,
    t.moduleLexicals
  ), n = Un(
    e,
    "globalObject"
  ), a = Un(
    r,
    "moduleLexicals"
  ), s = ye(`
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
}, { Fail: Ja } = Z, yn = ({
  globalObject: t,
  moduleLexicals: e = {},
  globalTransforms: r = [],
  sloppyGlobalsMode: n = !1
}) => {
  const a = n ? Bo(t) : Za, s = Ba(), { evalScope: i } = s, c = y({
    evalScope: i,
    moduleLexicals: e,
    globalObject: t,
    scopeTerminator: a
  });
  let u;
  const l = () => {
    u || (u = Ka(c));
  };
  return { safeEvaluate: (f, m) => {
    const { localTransforms: p = [] } = m || {};
    l(), f = Xo(f, [
      ...p,
      ...r,
      Yo
    ]);
    let h;
    try {
      return s.allowNextEvalToBeUnsafe(), oe(u, t, [f]);
    } catch (_) {
      throw h = _, _;
    } finally {
      const _ = "eval" in i;
      delete i.eval, _ && (s.revoked = { err: h }, Ja`handler did not reset allowNextEvalToBeUnsafe ${h}`);
    }
  } };
}, Ya = ") { [native code] }";
let Rr;
const Qo = () => {
  if (Rr === void 0) {
    const t = new xt();
    L(on, "toString", {
      value: {
        toString() {
          const r = Ys(this);
          return ho(r, Ya) || !Wt(t, this) ? r : `function ${this.name}() { [native code] }`;
        }
      }.toString
    }), Rr = y(
      (r) => Ir(t, r)
    );
  }
  return Rr;
};
function Xa(t = "safe") {
  if (t !== "safe" && t !== "unsafe")
    throw v(`unrecognized domainTaming ${t}`);
  if (t === "unsafe")
    return;
  const e = E.process || void 0;
  if (typeof e == "object") {
    const r = ue(e, "domain");
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
const es = y([
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
]), ts = y([
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
]), rs = y([
  ...es,
  ...ts
]), Qa = (t, { shouldResetForDebugging: e = !1 } = {}) => {
  e && t.resetErrorTagNum();
  let r = [];
  const n = St(
    de(rs, ([i, c]) => {
      const u = (...l) => {
        ae(r, [i, ...l]);
      };
      return L(u, "name", { value: i }), [i, y(u)];
    })
  );
  y(n);
  const a = () => {
    const i = y(r);
    return r = [], i;
  };
  return y(a), y({ loggingConsole: (
    /** @type {VirtualConsole} */
    n
  ), takeLog: a });
};
y(Qa);
const Tt = {
  NOTE: "ERROR_NOTE:",
  MESSAGE: "ERROR_MESSAGE:"
};
y(Tt);
const ns = (t, e) => {
  if (!t)
    return;
  const { getStackString: r, tagError: n, takeMessageLogArgs: a, takeNoteLogArgsArray: s } = e, i = (w, I) => de(w, (T) => pn(T) ? (ae(I, T), `(${n(T)})`) : T), c = (w, I, N, T, D) => {
    const U = n(I), q = N === Tt.MESSAGE ? `${U}:` : `${U} ${N}`, K = i(T, D);
    t[w](q, ...K);
  }, u = (w, I, N = void 0) => {
    if (I.length === 0)
      return;
    if (I.length === 1 && N === void 0) {
      f(w, I[0]);
      return;
    }
    let T;
    I.length === 1 ? T = "Nested error" : T = `Nested ${I.length} errors`, N !== void 0 && (T = `${T} under ${N}`), t.group(T);
    try {
      for (const D of I)
        f(w, D);
    } finally {
      t.groupEnd();
    }
  }, l = new xt(), d = (w) => (I, N) => {
    const T = [];
    c(w, I, Tt.NOTE, N, T), u(w, T, n(I));
  }, f = (w, I) => {
    if (Wt(l, I))
      return;
    const N = n(I);
    Ir(l, I);
    const T = [], D = a(I), U = s(
      I,
      d(w)
    );
    D === void 0 ? t[w](`${N}:`, I.message) : c(
      w,
      I,
      Tt.MESSAGE,
      D,
      T
    );
    let q = r(I);
    typeof q == "string" && q.length >= 1 && !ho(q, `
`) && (q += `
`), t[w](q);
    for (const K of U)
      c(w, I, Tt.NOTE, K, T);
    u(w, T, N);
  }, m = de(es, ([w, I]) => {
    const N = (...T) => {
      const D = [], U = i(T, D);
      t[w](...U), u(w, D);
    };
    return L(N, "name", { value: w }), [w, y(N)];
  }), p = Ge(
    ts,
    ([w, I]) => w in t
  ), h = de(p, ([w, I]) => {
    const N = (...T) => {
      t[w](...T);
    };
    return L(N, "name", { value: w }), [w, y(N)];
  }), _ = St([...m, ...h]);
  return (
    /** @type {VirtualConsole} */
    y(_)
  );
};
y(ns);
const ei = (t, e, r = void 0) => {
  const n = Ge(
    rs,
    ([i, c]) => i in t
  ), a = de(n, ([i, c]) => [i, y((...l) => {
    (c === void 0 || e.canLog(c)) && t[i](...l);
  })]), s = St(a);
  return (
    /** @type {VirtualConsole} */
    y(s)
  );
};
y(ei);
const Zn = (t) => {
  if (gt === void 0)
    return;
  let e = 0;
  const r = new Ie(), n = (d) => {
    Zs(r, d);
  }, a = new Pe(), s = (d) => {
    if (kr(r, d)) {
      const f = Le(r, d);
      n(d), t(f);
    }
  }, i = new gt(s);
  return {
    rejectionHandledHandler: (d) => {
      const f = M(a, d);
      n(f);
    },
    unhandledRejectionHandler: (d, f) => {
      e += 1;
      const m = e;
      Ae(r, m, d), ee(a, f, m), Qs(i, f, m, f);
    },
    processTerminationHandler: () => {
      for (const [d, f] of zs(r))
        n(d), t(f);
    }
  };
}, Mr = (t) => {
  throw v(t);
}, zn = (t, e) => y((...r) => oe(t, e, r)), ti = (t = "safe", e = "platform", r = "report", n = void 0) => {
  t === "safe" || t === "unsafe" || Mr(`unrecognized consoleTaming ${t}`);
  let a;
  n === void 0 ? a = Gr : a = {
    ...Gr,
    getStackString: n
  };
  const s = (
    /** @type {VirtualConsole} */
    // eslint-disable-next-line no-nested-ternary
    typeof E.console < "u" ? E.console : typeof E.print == "function" ? (
      // Make a good-enough console for eshost (including only functions that
      // log at a specific level with no special argument interpretation).
      // https://console.spec.whatwg.org/#logging
      ((l) => y({ debug: l, log: l, info: l, warn: l, error: l }))(
        // eslint-disable-next-line no-undef
        zn(E.print)
      )
    ) : void 0
  );
  if (s && s.log)
    for (const l of ["warn", "error"])
      s[l] || L(s, l, {
        value: zn(s.log, s)
      });
  const i = (
    /** @type {VirtualConsole} */
    t === "unsafe" ? s : ns(s, a)
  ), c = E.process || void 0;
  if (e !== "none" && typeof c == "object" && typeof c.on == "function") {
    let l;
    if (e === "platform" || e === "exit") {
      const { exit: d } = c;
      typeof d == "function" || Mr("missing process.exit"), l = () => d(c.exitCode || -1);
    } else
      e === "abort" && (l = c.abort, typeof l == "function" || Mr("missing process.abort"));
    c.on("uncaughtException", (d) => {
      i.error(d), l && l();
    });
  }
  if (r !== "none" && typeof c == "object" && typeof c.on == "function") {
    const d = Zn((f) => {
      i.error("SES_UNHANDLED_REJECTION:", f);
    });
    d && (c.on("unhandledRejection", d.unhandledRejectionHandler), c.on("rejectionHandled", d.rejectionHandledHandler), c.on("exit", d.processTerminationHandler));
  }
  const u = E.window || void 0;
  if (e !== "none" && typeof u == "object" && typeof u.addEventListener == "function" && u.addEventListener("error", (l) => {
    l.preventDefault(), i.error(l.error), (e === "exit" || e === "abort") && (u.location.href = "about:blank");
  }), r !== "none" && typeof u == "object" && typeof u.addEventListener == "function") {
    const d = Zn((f) => {
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
}, ri = [
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
], ni = (t) => {
  const r = St(de(ri, (n) => {
    const a = t[n];
    return [n, () => oe(a, t, [])];
  }));
  return H(r, {});
}, oi = (t) => de(t, ni), si = /\/node_modules\//, ai = /^(?:node:)?internal\//, ii = /\/packages\/ses\/src\/error\/assert.js$/, ci = /\/packages\/eventual-send\/src\//, li = [
  si,
  ai,
  ii,
  ci
], ui = (t) => {
  if (!t)
    return !0;
  for (const e of li)
    if (an(e, t))
      return !1;
  return !0;
}, di = /^((?:.*[( ])?)[:/\w_-]*\/\.\.\.\/(.+)$/, fi = /^((?:.*[( ])?)[:/\w_-]*\/(packages\/.+)$/, pi = [
  di,
  fi
], mi = (t) => {
  for (const e of pi) {
    const r = cn(e, t);
    if (r)
      return Pt(Ds(r, 1), "");
  }
  return t;
}, hi = (t, e, r, n) => {
  const a = t.captureStackTrace, s = (p) => n === "verbose" ? !0 : ui(p.getFileName()), i = (p) => {
    let h = `${p}`;
    return n === "concise" && (h = mi(h)), `
  at ${h}`;
  }, c = (p, h) => Pt(
    de(Ge(h, s), i),
    ""
  ), u = new Pe(), l = {
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
      uo(p, "stack", "");
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
  const f = new xt([d]), m = (p) => {
    if (Wt(f, p))
      return p;
    const h = {
      prepareStackTrace(_, w) {
        return ee(u, _, { callSites: w }), p(_, oi(w));
      }
    };
    return Ir(f, h.prepareStackTrace), h.prepareStackTrace;
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
}, Bn = ue(le.prototype, "stack"), Gn = Bn && Bn.get, gi = {
  getStackString(t) {
    return typeof Gn == "function" ? oe(Gn, t, []) : "stack" in t ? `${t.stack}` : "";
  }
};
function yi(t = "safe", e = "concise") {
  if (t !== "safe" && t !== "unsafe")
    throw v(`unrecognized errorTaming ${t}`);
  if (e !== "concise" && e !== "verbose")
    throw v(`unrecognized stackFiltering ${e}`);
  const r = le.prototype, n = typeof le.captureStackTrace == "function" ? "v8" : "unknown", { captureStackTrace: a } = le, s = (l = {}) => {
    const d = function(...m) {
      let p;
      return new.target === void 0 ? p = oe(le, this, m) : p = sr(le, m, new.target), n === "v8" && oe(a, le, [p, d]), p;
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
  for (const l of va)
    so(l, c);
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
  let u = gi.getStackString;
  return n === "v8" ? u = hi(
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
const { Fail: vi, details: qr, quote: Re } = Z, os = () => {
}, _i = (t, e) => y({
  compartment: t,
  specifier: e
}), bi = (t, e, r) => {
  const n = H(null);
  for (const a of t) {
    const s = e(a, r);
    n[a] = s;
  }
  return y(n);
}, Hn = (t, e, r, n, a, s, i, c, u) => {
  const { resolveHook: l, moduleRecords: d } = M(
    t,
    r
  ), f = bi(
    a.imports,
    l,
    n
  ), m = y({
    compartment: r,
    staticModuleRecord: a,
    moduleSpecifier: n,
    resolvedImports: f,
    importMeta: u
  });
  for (const p of ao(f)) {
    const h = ur(
      t,
      e,
      r,
      p,
      s,
      i,
      c
    );
    Tr(
      s,
      dn(h, os, (_) => {
        ae(c, _);
      })
    );
  }
  return Ae(d, n, m), m;
}, wi = async (t, e, r, n, a, s, i) => {
  const { importHook: c, moduleMap: u, moduleMapHook: l, moduleRecords: d } = M(
    t,
    r
  );
  let f = u[n];
  if (f === void 0 && l !== void 0 && (f = l(n)), typeof f == "string")
    Z.fail(
      qr`Cannot map module ${Re(n)} to ${Re(
        f
      )} in parent compartment, not yet implemented`,
      v
    );
  else if (f !== void 0) {
    const p = M(e, f);
    p === void 0 && Z.fail(
      qr`Cannot map module ${Re(
        n
      )} because the value is not a module exports namespace, or is from another realm`,
      et
    );
    const h = await ur(
      t,
      e,
      p.compartment,
      p.specifier,
      a,
      s,
      i
    );
    return Ae(d, n, h), h;
  }
  if (kr(d, n))
    return Le(d, n);
  const m = await c(n);
  if ((m === null || typeof m != "object") && vi`importHook must return a promise for an object, for module ${Re(
    n
  )} in compartment ${Re(r.name)}`, m.specifier !== void 0) {
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
      } = m, I = Hn(
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
      return Ae(d, n, I), I;
    }
    if (m.compartment !== void 0) {
      if (m.importMeta !== void 0)
        throw v(
          "Cannot redirect to an implicit record with a specified importMeta"
        );
      const p = await ur(
        t,
        e,
        m.compartment,
        m.specifier,
        a,
        s,
        i
      );
      return Ae(d, n, p), p;
    }
    throw v("Unnexpected RedirectStaticModuleInterface record shape");
  }
  return Hn(
    t,
    e,
    r,
    n,
    m,
    a,
    s,
    i
  );
}, ur = async (t, e, r, n, a, s, i) => {
  const { name: c } = M(
    t,
    r
  );
  let u = Le(s, r);
  u === void 0 && (u = new Ie(), Ae(s, r, u));
  let l = Le(u, n);
  return l !== void 0 || (l = Xs(
    wi(
      t,
      e,
      r,
      n,
      a,
      s,
      i
    ),
    (d) => {
      throw Z.note(
        d,
        qr`${d.message}, loading ${Re(n)} in compartment ${Re(
          c
        )}`
      ), d;
    }
  ), Ae(u, n, l)), l;
}, Vn = async (t, e, r, n) => {
  const { name: a } = M(
    t,
    r
  ), s = new wt(), i = new Ie(), c = [], u = ur(
    t,
    e,
    r,
    n,
    s,
    i,
    c
  );
  Tr(
    s,
    dn(u, os, (l) => {
      ae(c, l);
    })
  );
  for (const l of s)
    await l;
  if (c.length > 0)
    throw v(
      `Failed to load module ${Re(n)} in package ${Re(
        a
      )} (${c.length} underlying failures: ${Pt(
        de(c, (l) => l.message),
        ", "
      )}`
    );
}, { quote: dt } = Z, xi = () => {
  let t = !1;
  const e = H(null, {
    // Make this appear like an ESM module namespace object.
    [Be]: {
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
    exportsProxy: new br(e, {
      get(r, n, a) {
        if (!t)
          throw v(
            `Cannot get property ${dt(
              n
            )} of module exports namespace, the module has not yet begun to execute`
          );
        return Os(e, n, a);
      },
      set(r, n, a) {
        throw v(
          `Cannot set property ${dt(n)} of module exports namespace`
        );
      },
      has(r, n) {
        if (!t)
          throw v(
            `Cannot check property ${dt(
              n
            )}, the module has not yet begun to execute`
          );
        return lo(e, n);
      },
      deleteProperty(r, n) {
        throw v(
          `Cannot delete property ${dt(n)}s of module exports namespace`
        );
      },
      ownKeys(r) {
        if (!t)
          throw v(
            "Cannot enumerate keys, the module has not yet begun to execute"
          );
        return nt(e);
      },
      getOwnPropertyDescriptor(r, n) {
        if (!t)
          throw v(
            `Cannot get own property descriptor ${dt(
              n
            )}, the module has not yet begun to execute`
          );
        return Rs(e, n);
      },
      preventExtensions(r) {
        if (!t)
          throw v(
            "Cannot prevent extensions of module exports namespace, the module has not yet begun to execute"
          );
        return Ls(e);
      },
      isExtensible() {
        if (!t)
          throw v(
            "Cannot check extensibility of module exports namespace, the module has not yet begun to execute"
          );
        return Ms(e);
      },
      getPrototypeOf(r) {
        return null;
      },
      setPrototypeOf(r, n) {
        throw v("Cannot set prototype of module exports namespace");
      },
      defineProperty(r, n, a) {
        throw v(
          `Cannot define property ${dt(n)} of module exports namespace`
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
}, vn = (t, e, r, n) => {
  const { deferredExports: a } = e;
  if (!kr(a, n)) {
    const s = xi();
    ee(
      r,
      s.exportsProxy,
      _i(t, n)
    ), Ae(a, n, s);
  }
  return Le(a, n);
}, Si = (t, e) => {
  const { sloppyGlobalsMode: r = !1, __moduleShimLexicals__: n = void 0 } = e;
  let a;
  if (n === void 0 && !r)
    ({ safeEvaluate: a } = t);
  else {
    let { globalTransforms: s } = t;
    const { globalObject: i } = t;
    let c;
    n !== void 0 && (s = void 0, c = H(
      null,
      Ke(n)
    )), { safeEvaluate: a } = yn({
      globalObject: i,
      moduleLexicals: c,
      globalTransforms: s,
      sloppyGlobalsMode: r
    });
  }
  return { safeEvaluate: a };
}, ss = (t, e, r) => {
  if (typeof e != "string")
    throw v("first argument of evaluate() must be a string");
  const {
    transforms: n = [],
    __evadeHtmlCommentTest__: a = !1,
    __evadeImportExpressionTest__: s = !1,
    __rejectSomeDirectEvalExpressions__: i = !0
    // Note default on
  } = r, c = [...n];
  a === !0 && ae(c, Vo), s === !0 && ae(c, Ko), i === !0 && ae(c, Jo);
  const { safeEvaluate: u } = Si(
    t,
    r
  );
  return u(e, {
    localTransforms: c
  });
}, { quote: Yt } = Z, Ei = (t, e, r, n, a, s) => {
  const { exportsProxy: i, exportsTarget: c, activate: u } = vn(
    r,
    M(t, r),
    n,
    a
  ), l = H(null);
  if (e.exports) {
    if (!mt(e.exports) || js(e.exports, (f) => typeof f != "string"))
      throw v(
        `SES third-party static module record "exports" property must be an array of strings for module ${a}`
      );
    tt(e.exports, (f) => {
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
  return y({
    notifiers: l,
    exportsProxy: i,
    execute() {
      if (lo(d, "errorFromExecute"))
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
}, Pi = (t, e, r, n) => {
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
  } = i, _ = M(t, a), { __shimTransforms__: w, importMetaHook: I } = _, { exportsProxy: N, exportsTarget: T, activate: D } = vn(
    a,
    _,
    e,
    s
  ), U = H(null), q = H(null), K = H(null), De = H(null), fe = H(null);
  c && wr(fe, c), p && I && I(s, fe);
  const je = H(null), Je = H(null);
  tt(te(d), ([pe, [z]]) => {
    let B = je[z];
    if (!B) {
      let X, Q = !0, ce = [];
      const J = () => {
        if (Q)
          throw et(`binding ${Yt(z)} not yet initialized`);
        return X;
      }, ve = y((_e) => {
        if (!Q)
          throw v(
            `Internal: binding ${Yt(z)} already initialized`
          );
        X = _e;
        const En = ce;
        ce = null, Q = !1;
        for (const be of En || [])
          be(_e);
        return _e;
      });
      B = {
        get: J,
        notify: (_e) => {
          _e !== ve && (Q ? ae(ce || [], _e) : _e(X));
        }
      }, je[z] = B, K[z] = ve;
    }
    U[pe] = {
      get: B.get,
      set: void 0,
      enumerable: !0,
      configurable: !1
    }, Je[pe] = B.notify;
  }), tt(
    te(f),
    ([pe, [z, B]]) => {
      let X = je[z];
      if (!X) {
        let Q, ce = !0;
        const J = [], ve = () => {
          if (ce)
            throw et(
              `binding ${Yt(pe)} not yet initialized`
            );
          return Q;
        }, ct = y((be) => {
          Q = be, ce = !1;
          for (const $r of J)
            $r(be);
        }), _e = (be) => {
          if (ce)
            throw et(`binding ${Yt(z)} not yet initialized`);
          Q = be;
          for (const $r of J)
            $r(be);
        };
        X = {
          get: ve,
          notify: (be) => {
            be !== ct && (ae(J, be), ce || be(Q));
          }
        }, je[z] = X, B && L(q, z, {
          get: ve,
          set: _e,
          enumerable: !0,
          configurable: !1
        }), De[z] = ct;
      }
      U[pe] = {
        get: X.get,
        set: void 0,
        enumerable: !0,
        configurable: !1
      }, Je[pe] = X.notify;
    }
  );
  const Ue = (pe) => {
    pe(T);
  };
  Je["*"] = Ue;
  function Kt(pe) {
    const z = H(null);
    z.default = !1;
    for (const [B, X] of pe) {
      const Q = Le(n, B);
      Q.execute();
      const { notifiers: ce } = Q;
      for (const [J, ve] of X) {
        const ct = ce[J];
        if (!ct)
          throw Gt(
            `The requested module '${B}' does not provide an export named '${J}'`
          );
        for (const _e of ve)
          ct(_e);
      }
      if (Pr(u, B))
        for (const [J, ve] of te(
          ce
        ))
          z[J] === void 0 ? z[J] = ve : z[J] = !1;
      if (m[B])
        for (const [J, ve] of m[B])
          z[ve] = ce[J];
    }
    for (const [B, X] of te(z))
      if (!Je[B] && X !== !1) {
        Je[B] = X;
        let Q;
        X((J) => Q = J), U[B] = {
          get() {
            return Q;
          },
          set: void 0,
          enumerable: !0,
          configurable: !1
        };
      }
    tt(
      mo(no(U)),
      (B) => L(T, B, U[B])
    ), y(T), D();
  }
  let kt;
  h !== void 0 ? kt = h : kt = ss(_, l, {
    globalObject: a.globalThis,
    transforms: w,
    __moduleShimLexicals__: q
  });
  let xn = !1, Sn;
  function _s() {
    if (kt) {
      const pe = kt;
      kt = null;
      try {
        pe(
          y({
            imports: y(Kt),
            onceVar: y(K),
            liveVar: y(De),
            importMeta: fe
          })
        );
      } catch (z) {
        xn = !0, Sn = z;
      }
    }
    if (xn)
      throw Sn;
  }
  return y({
    notifiers: Je,
    exportsProxy: N,
    execute: _s
  });
}, { Fail: Qe, quote: W } = Z, as = (t, e, r, n) => {
  const { name: a, moduleRecords: s } = M(
    t,
    r
  ), i = Le(s, n);
  if (i === void 0)
    throw et(
      `Missing link to module ${W(n)} from compartment ${W(
        a
      )}`
    );
  return Ni(t, e, i);
};
function ki(t) {
  return typeof t.__syncModuleProgram__ == "string";
}
function Ti(t, e) {
  const { __fixedExportMap__: r, __liveExportMap__: n } = t;
  He(r) || Qe`Property '__fixedExportMap__' of a precompiled module record must be an object, got ${W(
    r
  )}, for module ${W(e)}`, He(n) || Qe`Property '__liveExportMap__' of a precompiled module record must be an object, got ${W(
    n
  )}, for module ${W(e)}`;
}
function Ii(t) {
  return typeof t.execute == "function";
}
function Ai(t, e) {
  const { exports: r } = t;
  mt(r) || Qe`Property 'exports' of a third-party static module record must be an array, got ${W(
    r
  )}, for module ${W(e)}`;
}
function Ci(t, e) {
  He(t) || Qe`Static module records must be of type object, got ${W(
    t
  )}, for module ${W(e)}`;
  const { imports: r, exports: n, reexports: a = [] } = t;
  mt(r) || Qe`Property 'imports' of a static module record must be an array, got ${W(
    r
  )}, for module ${W(e)}`, mt(n) || Qe`Property 'exports' of a precompiled module record must be an array, got ${W(
    n
  )}, for module ${W(e)}`, mt(a) || Qe`Property 'reexports' of a precompiled module record must be an array if present, got ${W(
    a
  )}, for module ${W(e)}`;
}
const Ni = (t, e, r) => {
  const { compartment: n, moduleSpecifier: a, resolvedImports: s, staticModuleRecord: i } = r, { instances: c } = M(t, n);
  if (kr(c, a))
    return Le(c, a);
  Ci(i, a);
  const u = new Ie();
  let l;
  if (ki(i))
    Ti(i, a), l = Pi(
      t,
      e,
      r,
      u
    );
  else if (Ii(i))
    Ai(i, a), l = Ei(
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
  Ae(c, a, l);
  for (const [d, f] of te(s)) {
    const m = as(
      t,
      e,
      n,
      f
    );
    Ae(u, d, m);
  }
  return l;
}, { quote: Lr } = Z, Ct = new Pe(), Oe = new Pe(), Xt = (t) => {
  const { importHook: e, resolveHook: r } = M(Oe, t);
  if (typeof e != "function" || typeof r != "function")
    throw v(
      "Compartment must be constructed with an importHook and a resolveHook for it to be able to load modules"
    );
}, _n = function(e = {}, r = {}, n = {}) {
  throw v(
    "Compartment.prototype.constructor is not a valid constructor."
  );
}, Wn = (t, e) => {
  const { execute: r, exportsProxy: n } = as(
    Oe,
    Ct,
    t,
    e
  );
  return r(), n;
}, bn = {
  constructor: _n,
  get globalThis() {
    return M(Oe, this).globalObject;
  },
  get name() {
    return M(Oe, this).name;
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
    const r = M(Oe, this);
    return ss(r, t, e);
  },
  module(t) {
    if (typeof t != "string")
      throw v("first argument of module() must be a string");
    Xt(this);
    const { exportsProxy: e } = vn(
      this,
      M(Oe, this),
      Ct,
      t
    );
    return e;
  },
  async import(t) {
    if (typeof t != "string")
      throw v("first argument of import() must be a string");
    return Xt(this), dn(
      Vn(Oe, Ct, this, t),
      () => ({ namespace: Wn(
        /** @type {Compartment} */
        this,
        t
      ) })
    );
  },
  async load(t) {
    if (typeof t != "string")
      throw v("first argument of load() must be a string");
    return Xt(this), Vn(Oe, Ct, this, t);
  },
  importNow(t) {
    if (typeof t != "string")
      throw v("first argument of importNow() must be a string");
    return Xt(this), Wn(
      /** @type {Compartment} */
      this,
      t
    );
  }
};
F(bn, {
  [Be]: {
    value: "Compartment",
    writable: !1,
    enumerable: !1,
    configurable: !0
  }
});
F(_n, {
  prototype: { value: bn }
});
const Kr = (t, e, r) => {
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
    } = i, h = [...u, ...l], _ = new Ie(), w = new Ie(), I = new Ie();
    for (const [D, U] of te(s || {})) {
      if (typeof U == "string")
        throw v(
          `Cannot map module ${Lr(D)} to ${Lr(
            U
          )} in parent compartment`
        );
      if (M(Ct, U) === void 0)
        throw et(
          `Cannot map module ${Lr(
            D
          )} because it has no known compartment in this realm`
        );
    }
    const N = {};
    Da(N), Do(N);
    const { safeEvaluate: T } = yn({
      globalObject: N,
      globalTransforms: h,
      sloppyGlobalsMode: !1
    });
    jo(N, {
      intrinsics: e,
      newGlobalPropertyNames: Oo,
      makeCompartmentConstructor: t,
      markVirtualizedNativeFunction: r
    }), Wr(
      N,
      T,
      r
    ), wr(N, a), ee(Oe, this, {
      name: `${c}`,
      globalTransforms: h,
      globalObject: N,
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
  return n.prototype = bn, n;
};
function Fr(t) {
  return G(t).constructor;
}
function $i() {
  return arguments;
}
const Oi = () => {
  const t = ye.prototype.constructor, e = ue($i(), "callee"), r = e && e.get, n = Ks(new ie()), a = G(n), s = Sr[io] && Hs(/./), i = s && G(s), c = Us([]), u = G(c), l = G(xs), d = Bs(new Ie()), f = G(d), m = Gs(new wt()), p = G(m), h = G(u);
  function* _() {
  }
  const w = Fr(_), I = w.prototype;
  async function* N() {
  }
  const T = Fr(
    N
  ), D = T.prototype, U = D.prototype, q = G(U);
  async function K() {
  }
  const De = Fr(K), fe = {
    "%InertFunction%": t,
    "%ArrayIteratorPrototype%": u,
    "%InertAsyncFunction%": De,
    "%AsyncGenerator%": D,
    "%InertAsyncGeneratorFunction%": T,
    "%AsyncGeneratorPrototype%": U,
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
    "%InertCompartment%": _n
  };
  return E.Iterator && (fe["%IteratorHelperPrototype%"] = G(
    // eslint-disable-next-line @endo/no-polymorphic-call
    E.Iterator.from([]).take(0)
  ), fe["%WrapForValidIteratorPrototype%"] = G(
    // eslint-disable-next-line @endo/no-polymorphic-call
    E.Iterator.from({ next() {
    } })
  )), E.AsyncIterator && (fe["%AsyncIteratorHelperPrototype%"] = G(
    // eslint-disable-next-line @endo/no-polymorphic-call
    E.AsyncIterator.from([]).take(0)
  ), fe["%WrapForValidAsyncIteratorPrototype%"] = G(
    // eslint-disable-next-line @endo/no-polymorphic-call
    E.AsyncIterator.from({ next() {
    } })
  )), fe;
}, is = (t, e) => {
  if (e !== "safe" && e !== "unsafe")
    throw v(`unrecognized fakeHardenOption ${e}`);
  if (e === "safe" || (Object.isExtensible = () => !1, Object.isFrozen = () => !0, Object.isSealed = () => !0, Reflect.isExtensible = () => !1, t.isFake))
    return t;
  const r = (n) => n;
  return r.isFake = !0, y(r);
};
y(is);
const Ri = () => {
  const t = At, e = t.prototype, r = {
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
    Ke(t)
  ), a = St(
    de(n, ([s, i]) => [
      s,
      { ...i, configurable: !0 }
    ])
  );
  return F(r, a), { "%SharedSymbol%": r };
}, Mi = (t) => {
  try {
    return t(), !1;
  } catch {
    return !0;
  }
}, qn = (t, e, r) => {
  if (t === void 0)
    return !1;
  const n = ue(t, e);
  if (!n || "value" in n)
    return !1;
  const { get: a, set: s } = n;
  if (typeof a != "function" || typeof s != "function" || a() !== r || oe(a, t, []) !== r)
    return !1;
  const i = "Seems to be a setter", c = { __proto__: null };
  if (oe(s, c, [i]), c[e] !== i)
    return !1;
  const u = { __proto__: t };
  return oe(s, u, [i]), u[e] !== i || !Mi(() => oe(s, t, [r])) || "originalValue" in a || n.configurable === !1 ? !1 : (L(t, e, {
    value: r,
    writable: !0,
    enumerable: n.enumerable,
    configurable: !0
  }), !0);
}, Li = (t) => {
  qn(
    t["%IteratorPrototype%"],
    "constructor",
    t.Iterator
  ), qn(
    t["%IteratorPrototype%"],
    Be,
    "Iterator"
  );
}, { Fail: Kn, details: Jn, quote: Yn } = Z;
let Qt, er;
const Fi = ya(), Di = () => {
  let t = !1;
  try {
    t = ye(
      "eval",
      "SES_changed",
      `        eval("SES_changed = true");
        return SES_changed;
      `
    )(vo, !1), t || delete E.SES_changed;
  } catch {
    t = !0;
  }
  if (!t)
    throw v(
      "SES cannot initialize unless 'eval' is the original intrinsic 'eval', suitable for direct-eval (dynamically scoped eval) (SES_DIRECT_EVAL)"
    );
}, cs = (t = {}) => {
  const {
    errorTaming: e = me("LOCKDOWN_ERROR_TAMING", "safe"),
    errorTrapping: r = (
      /** @type {"platform" | "none" | "report" | "abort" | "exit" | undefined} */
      me("LOCKDOWN_ERROR_TRAPPING", "platform")
    ),
    unhandledRejectionTrapping: n = (
      /** @type {"none" | "report" | undefined} */
      me("LOCKDOWN_UNHANDLED_REJECTION_TRAPPING", "report")
    ),
    regExpTaming: a = me("LOCKDOWN_REGEXP_TAMING", "safe"),
    localeTaming: s = me("LOCKDOWN_LOCALE_TAMING", "safe"),
    consoleTaming: i = (
      /** @type {'unsafe' | 'safe' | undefined} */
      me("LOCKDOWN_CONSOLE_TAMING", "safe")
    ),
    overrideTaming: c = me("LOCKDOWN_OVERRIDE_TAMING", "moderate"),
    stackFiltering: u = me("LOCKDOWN_STACK_FILTERING", "concise"),
    domainTaming: l = me("LOCKDOWN_DOMAIN_TAMING", "safe"),
    evalTaming: d = me("LOCKDOWN_EVAL_TAMING", "safeEval"),
    overrideDebug: f = Ge(
      go(me("LOCKDOWN_OVERRIDE_DEBUG", ""), ","),
      /** @param {string} debugName */
      (Ue) => Ue !== ""
    ),
    __hardenTaming__: m = me("LOCKDOWN_HARDEN_TAMING", "safe"),
    dateTaming: p = "safe",
    // deprecated
    mathTaming: h = "safe",
    // deprecated
    ..._
  } = t;
  d === "unsafeEval" || d === "safeEval" || d === "noEval" || Kn`lockdown(): non supported option evalTaming: ${Yn(d)}`;
  const w = nt(_);
  if (w.length === 0 || Kn`lockdown(): non supported option ${Yn(w)}`, Qt === void 0 || // eslint-disable-next-line @endo/no-polymorphic-call
  Z.fail(
    Jn`Already locked down at ${Qt} (SES_ALREADY_LOCKED_DOWN)`,
    v
  ), Qt = v("Prior lockdown (SES_ALREADY_LOCKED_DOWN)"), Qt.stack, Di(), E.Function.prototype.constructor !== E.Function && // @ts-ignore harden is absent on globalThis type def.
  typeof E.harden == "function" && // @ts-ignore lockdown is absent on globalThis type def.
  typeof E.lockdown == "function" && E.Date.prototype.constructor !== E.Date && typeof E.Date.now == "function" && // @ts-ignore does not recognize that Date constructor is a special
  // Function.
  // eslint-disable-next-line @endo/no-polymorphic-call
  xr(E.Date.prototype.constructor.now(), NaN))
    throw v(
      "Already locked down but not by this SES instance (SES_MULTIPLE_INSTANCES)"
    );
  Xa(l);
  const N = Qo(), { addIntrinsics: T, completePrototypes: D, finalIntrinsics: U } = Mo(), q = is(Fi, m);
  T({ harden: q }), T(Pa()), T(ka(p)), T(yi(e, u)), T(Ta(h)), T(Ia(a)), T(Ri()), T(Oi()), D();
  const K = U(), De = { __proto__: null };
  typeof E.Buffer == "function" && (De.Buffer = E.Buffer);
  let fe;
  e !== "unsafe" && (fe = K["%InitialGetStackString%"]);
  const je = ti(
    i,
    r,
    n,
    fe
  );
  if (E.console = /** @type {Console} */
  je.console, typeof /** @type {any} */
  je.console._times == "object" && (De.SafeMap = G(
    // eslint-disable-next-line no-underscore-dangle
    /** @type {any} */
    je.console._times
  )), e === "unsafe" && E.assert === Z && (E.assert = Cr(void 0, !0)), Ma(K, s), Li(K), Ea(K, N), Do(E), jo(E, {
    intrinsics: K,
    newGlobalPropertyNames: $n,
    makeCompartmentConstructor: Kr,
    markVirtualizedNativeFunction: N
  }), d === "noEval")
    Wr(
      E,
      ea,
      N
    );
  else if (d === "safeEval") {
    const { safeEvaluate: Ue } = yn({ globalObject: E });
    Wr(
      E,
      Ue,
      N
    );
  }
  return () => {
    er === void 0 || // eslint-disable-next-line @endo/no-polymorphic-call
    Z.fail(
      Jn`Already locked down at ${er} (SES_ALREADY_LOCKED_DOWN)`,
      v
    ), er = v(
      "Prior lockdown (SES_ALREADY_LOCKED_DOWN)"
    ), er.stack, Na(K, c, f);
    const Ue = {
      intrinsics: K,
      hostIntrinsics: De,
      globals: {
        // Harden evaluators
        Function: E.Function,
        eval: E.eval,
        // @ts-ignore Compartment does exist on globalThis
        Compartment: E.Compartment,
        // Harden Symbol
        Symbol: E.Symbol
      }
    };
    for (const Kt of Nt($n))
      Ue.globals[Kt] = E[Kt];
    return q(Ue), q;
  };
};
E.lockdown = (t) => {
  const e = cs(t);
  E.harden = e();
};
E.repairIntrinsics = (t) => {
  const e = cs(t);
  E.hardenIntrinsics = () => {
    E.harden = e();
  };
};
const ji = Qo();
E.Compartment = Kr(
  Kr,
  Sa(E),
  ji
);
E.assert = Z;
const Ui = `
<svg width="16"  height="16"xmlns="http://www.w3.org/2000/svg" fill="none"><g class="fills"><rect rx="0" ry="0" width="16" height="16" class="frame-background"/></g><g class="frame-children"><path d="M11.997 3.997 8 8l-3.997 4.003m-.006-8L8 8l4.003 3.997" class="fills"/><g class="strokes"><path d="M11.997 3.997 8 8l-3.997 4.003m-.006-8L8 8l4.003 3.997" style="fill: none; stroke-width: 1; stroke: rgb(143, 157, 163); stroke-opacity: 1; stroke-linecap: round;" class="stroke-shape"/></g></g></svg>`;
class Zi extends HTMLElement {
  constructor() {
    super(), this.attachShadow({ mode: "open" });
  }
  connectedCallback() {
    const e = this.getAttribute("title"), r = this.getAttribute("iframe-src"), n = Number(this.getAttribute("width") || "300"), a = Number(this.getAttribute("height") || "400");
    if (!e || !r)
      throw new Error("title and iframe-src attributes are required");
    if (!this.shadowRoot)
      throw new Error("Error creating shadow root");
    const s = document.createElement("div");
    s.classList.add("header");
    const i = document.createElement("h1");
    i.textContent = e, s.appendChild(i);
    const c = document.createElement("button");
    c.setAttribute("type", "button"), c.innerHTML = `<div class="close">${Ui}</div>`, c.addEventListener("click", () => {
      this.shadowRoot && this.shadowRoot.dispatchEvent(
        new CustomEvent("close", {
          composed: !0,
          bubbles: !0
        })
      );
    }), s.appendChild(c);
    const u = document.createElement("iframe");
    u.src = r, u.allow = "", u.sandbox.add(
      "allow-scripts",
      "allow-forms",
      "allow-modals",
      "allow-popups",
      "allow-popups-to-escape-sandbox",
      "allow-storage-access-by-user-activation"
    ), this.addEventListener("message", (d) => {
      u.contentWindow && u.contentWindow.postMessage(d.detail, "*");
    }), this.shadowRoot.appendChild(s), this.shadowRoot.appendChild(u);
    const l = document.createElement("style");
    l.textContent = `
        :host {
          display: flex;
          flex-direction: column;
          position: fixed;
          inset-block-end: 10px;
          inset-inline-start: 10px;
          z-index: 1000;
          padding: 20px;
          border-radius: 20px;
          box-shadow: 0 4px 8px rgba(0,0,0,0.1);
          inline-size: ${n}px;
          block-size: ${a}px;
        }

        :host([data-theme="dark"]) {
          background: #2e3434;
          border: 1px solid #2e3434;
          color: #ffffff;
        }

        :host([data-theme="light"]) {
          background: #ffffff;
          border: 1px solid #eef0f2;
          color: #18181a;
        }

        .header {
          display: flex;
          justify-content: space-between;
        }

        button {
          background: transparent;
          border: 0;
          cursor: pointer;
        }

        h1 {
          font-family: Arial, sans-serif;
          margin: 0;
          margin-block-end: 10px;
        }

        iframe {
          border: none;
          inline-size: 100%;
          block-size: 100%;
        }
    `, this.shadowRoot.appendChild(l);
  }
}
customElements.define("plugin-modal", Zi);
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
var Jr;
(function(t) {
  t.mergeShapes = (e, r) => ({
    ...e,
    ...r
    // second overwrites first
  });
})(Jr || (Jr = {}));
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
]), Ze = (t) => {
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
}, g = R.arrayToEnum([
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
]), zi = (t) => JSON.stringify(t, null, 2).replace(/"([^"]+)":/g, "$1:");
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
const $t = (t, e) => {
  let r;
  switch (t.code) {
    case g.invalid_type:
      t.received === b.undefined ? r = "Required" : r = `Expected ${t.expected}, received ${t.received}`;
      break;
    case g.invalid_literal:
      r = `Invalid literal value, expected ${JSON.stringify(t.expected, R.jsonStringifyReplacer)}`;
      break;
    case g.unrecognized_keys:
      r = `Unrecognized key(s) in object: ${R.joinValues(t.keys, ", ")}`;
      break;
    case g.invalid_union:
      r = "Invalid input";
      break;
    case g.invalid_union_discriminator:
      r = `Invalid discriminator value. Expected ${R.joinValues(t.options)}`;
      break;
    case g.invalid_enum_value:
      r = `Invalid enum value. Expected ${R.joinValues(t.options)}, received '${t.received}'`;
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
      typeof t.validation == "object" ? "includes" in t.validation ? (r = `Invalid input: must include "${t.validation.includes}"`, typeof t.validation.position == "number" && (r = `${r} at one or more positions greater than or equal to ${t.validation.position}`)) : "startsWith" in t.validation ? r = `Invalid input: must start with "${t.validation.startsWith}"` : "endsWith" in t.validation ? r = `Invalid input: must end with "${t.validation.endsWith}"` : R.assertNever(t.validation) : t.validation !== "regex" ? r = `Invalid ${t.validation}` : r = "Invalid";
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
      r = e.defaultError, R.assertNever(t);
  }
  return { message: r };
};
let ls = $t;
function Bi(t) {
  ls = t;
}
function dr() {
  return ls;
}
const fr = (t) => {
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
}, Gi = [];
function x(t, e) {
  const r = fr({
    issueData: e,
    data: t.data,
    path: t.path,
    errorMaps: [
      t.common.contextualErrorMap,
      t.schemaErrorMap,
      dr(),
      $t
      // then global default map
    ].filter((n) => !!n)
  });
  t.common.issues.push(r);
}
class Y {
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
    return Y.mergeObjectSync(e, n);
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
}), us = (t) => ({ status: "dirty", value: t }), re = (t) => ({ status: "valid", value: t }), Yr = (t) => t.status === "aborted", Xr = (t) => t.status === "dirty", Ot = (t) => t.status === "valid", pr = (t) => typeof Promise < "u" && t instanceof Promise;
var S;
(function(t) {
  t.errToObj = (e) => typeof e == "string" ? { message: e } : e || {}, t.toString = (e) => typeof e == "string" ? e : e == null ? void 0 : e.message;
})(S || (S = {}));
class Ce {
  constructor(e, r, n, a) {
    this._cachedPath = [], this.parent = e, this.data = r, this._path = n, this._key = a;
  }
  get path() {
    return this._cachedPath.length || (this._key instanceof Array ? this._cachedPath.push(...this._path, ...this._key) : this._cachedPath.push(...this._path, this._key)), this._cachedPath;
  }
}
const Xn = (t, e) => {
  if (Ot(e))
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
class $ {
  constructor(e) {
    this.spa = this.safeParseAsync, this._def = e, this.parse = this.parse.bind(this), this.safeParse = this.safeParse.bind(this), this.parseAsync = this.parseAsync.bind(this), this.safeParseAsync = this.safeParseAsync.bind(this), this.spa = this.spa.bind(this), this.refine = this.refine.bind(this), this.refinement = this.refinement.bind(this), this.superRefine = this.superRefine.bind(this), this.optional = this.optional.bind(this), this.nullable = this.nullable.bind(this), this.nullish = this.nullish.bind(this), this.array = this.array.bind(this), this.promise = this.promise.bind(this), this.or = this.or.bind(this), this.and = this.and.bind(this), this.transform = this.transform.bind(this), this.brand = this.brand.bind(this), this.default = this.default.bind(this), this.catch = this.catch.bind(this), this.describe = this.describe.bind(this), this.pipe = this.pipe.bind(this), this.readonly = this.readonly.bind(this), this.isNullable = this.isNullable.bind(this), this.isOptional = this.isOptional.bind(this);
  }
  get description() {
    return this._def.description;
  }
  _getType(e) {
    return Ze(e.data);
  }
  _getOrReturnCtx(e, r) {
    return r || {
      common: e.parent.common,
      data: e.data,
      parsedType: Ze(e.data),
      schemaErrorMap: this._def.errorMap,
      path: e.path,
      parent: e.parent
    };
  }
  _processInputParams(e) {
    return {
      status: new Y(),
      ctx: {
        common: e.parent.common,
        data: e.data,
        parsedType: Ze(e.data),
        schemaErrorMap: this._def.errorMap,
        path: e.path,
        parent: e.parent
      }
    };
  }
  _parseSync(e) {
    const r = this._parse(e);
    if (pr(r))
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
      parsedType: Ze(e)
    }, s = this._parseSync({ data: e, path: a.path, parent: a });
    return Xn(a, s);
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
      parsedType: Ze(e)
    }, a = this._parse({ data: e, path: n.path, parent: n }), s = await (pr(a) ? a : Promise.resolve(a));
    return Xn(n, s);
  }
  refine(e, r) {
    const n = (a) => typeof r == "string" || typeof r > "u" ? { message: r } : typeof r == "function" ? r(a) : r;
    return this._refinement((a, s) => {
      const i = e(a), c = () => s.addIssue({
        code: g.custom,
        ...n(a)
      });
      return typeof Promise < "u" && i instanceof Promise ? i.then((u) => u ? !0 : (c(), !1)) : i ? !0 : (c(), !1);
    });
  }
  refinement(e, r) {
    return this._refinement((n, a) => e(n) ? !0 : (a.addIssue(typeof r == "function" ? r(n, a) : r), !1));
  }
  _refinement(e) {
    return new Ee({
      schema: this,
      typeName: P.ZodEffects,
      effect: { type: "refinement", refinement: e }
    });
  }
  superRefine(e) {
    return this._refinement(e);
  }
  optional() {
    return Me.create(this, this._def);
  }
  nullable() {
    return it.create(this, this._def);
  }
  nullish() {
    return this.nullable().optional();
  }
  array() {
    return Se.create(this, this._def);
  }
  promise() {
    return bt.create(this, this._def);
  }
  or(e) {
    return Ft.create([this, e], this._def);
  }
  and(e) {
    return Dt.create(this, e, this._def);
  }
  transform(e) {
    return new Ee({
      ...C(this._def),
      schema: this,
      typeName: P.ZodEffects,
      effect: { type: "transform", transform: e }
    });
  }
  default(e) {
    const r = typeof e == "function" ? e : () => e;
    return new Bt({
      ...C(this._def),
      innerType: this,
      defaultValue: r,
      typeName: P.ZodDefault
    });
  }
  brand() {
    return new fs({
      typeName: P.ZodBranded,
      type: this,
      ...C(this._def)
    });
  }
  catch(e) {
    const r = typeof e == "function" ? e : () => e;
    return new yr({
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
    return qt.create(this, e);
  }
  readonly() {
    return _r.create(this);
  }
  isOptional() {
    return this.safeParse(void 0).success;
  }
  isNullable() {
    return this.safeParse(null).success;
  }
}
const Hi = /^c[^\s-]{8,}$/i, Vi = /^[a-z][a-z0-9]*$/, Wi = /^[0-9A-HJKMNP-TV-Z]{26}$/, qi = /^[0-9a-fA-F]{8}\b-[0-9a-fA-F]{4}\b-[0-9a-fA-F]{4}\b-[0-9a-fA-F]{4}\b-[0-9a-fA-F]{12}$/i, Ki = /^(?!\.)(?!.*\.\.)([A-Z0-9_+-\.]*)[A-Z0-9_+-]@([A-Z0-9][A-Z0-9\-]*\.)+[A-Z]{2,}$/i, Ji = "^(\\p{Extended_Pictographic}|\\p{Emoji_Component})+$";
let Dr;
const Yi = /^(((25[0-5])|(2[0-4][0-9])|(1[0-9]{2})|([0-9]{1,2}))\.){3}((25[0-5])|(2[0-4][0-9])|(1[0-9]{2})|([0-9]{1,2}))$/, Xi = /^(([a-f0-9]{1,4}:){7}|::([a-f0-9]{1,4}:){0,6}|([a-f0-9]{1,4}:){1}:([a-f0-9]{1,4}:){0,5}|([a-f0-9]{1,4}:){2}:([a-f0-9]{1,4}:){0,4}|([a-f0-9]{1,4}:){3}:([a-f0-9]{1,4}:){0,3}|([a-f0-9]{1,4}:){4}:([a-f0-9]{1,4}:){0,2}|([a-f0-9]{1,4}:){5}:([a-f0-9]{1,4}:){0,1})([a-f0-9]{1,4}|(((25[0-5])|(2[0-4][0-9])|(1[0-9]{2})|([0-9]{1,2}))\.){3}((25[0-5])|(2[0-4][0-9])|(1[0-9]{2})|([0-9]{1,2})))$/, Qi = (t) => t.precision ? t.offset ? new RegExp(`^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{${t.precision}}(([+-]\\d{2}(:?\\d{2})?)|Z)$`) : new RegExp(`^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{${t.precision}}Z$`) : t.precision === 0 ? t.offset ? new RegExp("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(([+-]\\d{2}(:?\\d{2})?)|Z)$") : new RegExp("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$") : t.offset ? new RegExp("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(([+-]\\d{2}(:?\\d{2})?)|Z)$") : new RegExp("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z$");
function ec(t, e) {
  return !!((e === "v4" || !e) && Yi.test(t) || (e === "v6" || !e) && Xi.test(t));
}
class we extends $ {
  _parse(e) {
    if (this._def.coerce && (e.data = String(e.data)), this._getType(e) !== b.string) {
      const s = this._getOrReturnCtx(e);
      return x(
        s,
        {
          code: g.invalid_type,
          expected: b.string,
          received: s.parsedType
        }
        //
      ), A;
    }
    const n = new Y();
    let a;
    for (const s of this._def.checks)
      if (s.kind === "min")
        e.data.length < s.value && (a = this._getOrReturnCtx(e, a), x(a, {
          code: g.too_small,
          minimum: s.value,
          type: "string",
          inclusive: !0,
          exact: !1,
          message: s.message
        }), n.dirty());
      else if (s.kind === "max")
        e.data.length > s.value && (a = this._getOrReturnCtx(e, a), x(a, {
          code: g.too_big,
          maximum: s.value,
          type: "string",
          inclusive: !0,
          exact: !1,
          message: s.message
        }), n.dirty());
      else if (s.kind === "length") {
        const i = e.data.length > s.value, c = e.data.length < s.value;
        (i || c) && (a = this._getOrReturnCtx(e, a), i ? x(a, {
          code: g.too_big,
          maximum: s.value,
          type: "string",
          inclusive: !0,
          exact: !0,
          message: s.message
        }) : c && x(a, {
          code: g.too_small,
          minimum: s.value,
          type: "string",
          inclusive: !0,
          exact: !0,
          message: s.message
        }), n.dirty());
      } else if (s.kind === "email")
        Ki.test(e.data) || (a = this._getOrReturnCtx(e, a), x(a, {
          validation: "email",
          code: g.invalid_string,
          message: s.message
        }), n.dirty());
      else if (s.kind === "emoji")
        Dr || (Dr = new RegExp(Ji, "u")), Dr.test(e.data) || (a = this._getOrReturnCtx(e, a), x(a, {
          validation: "emoji",
          code: g.invalid_string,
          message: s.message
        }), n.dirty());
      else if (s.kind === "uuid")
        qi.test(e.data) || (a = this._getOrReturnCtx(e, a), x(a, {
          validation: "uuid",
          code: g.invalid_string,
          message: s.message
        }), n.dirty());
      else if (s.kind === "cuid")
        Hi.test(e.data) || (a = this._getOrReturnCtx(e, a), x(a, {
          validation: "cuid",
          code: g.invalid_string,
          message: s.message
        }), n.dirty());
      else if (s.kind === "cuid2")
        Vi.test(e.data) || (a = this._getOrReturnCtx(e, a), x(a, {
          validation: "cuid2",
          code: g.invalid_string,
          message: s.message
        }), n.dirty());
      else if (s.kind === "ulid")
        Wi.test(e.data) || (a = this._getOrReturnCtx(e, a), x(a, {
          validation: "ulid",
          code: g.invalid_string,
          message: s.message
        }), n.dirty());
      else if (s.kind === "url")
        try {
          new URL(e.data);
        } catch {
          a = this._getOrReturnCtx(e, a), x(a, {
            validation: "url",
            code: g.invalid_string,
            message: s.message
          }), n.dirty();
        }
      else
        s.kind === "regex" ? (s.regex.lastIndex = 0, s.regex.test(e.data) || (a = this._getOrReturnCtx(e, a), x(a, {
          validation: "regex",
          code: g.invalid_string,
          message: s.message
        }), n.dirty())) : s.kind === "trim" ? e.data = e.data.trim() : s.kind === "includes" ? e.data.includes(s.value, s.position) || (a = this._getOrReturnCtx(e, a), x(a, {
          code: g.invalid_string,
          validation: { includes: s.value, position: s.position },
          message: s.message
        }), n.dirty()) : s.kind === "toLowerCase" ? e.data = e.data.toLowerCase() : s.kind === "toUpperCase" ? e.data = e.data.toUpperCase() : s.kind === "startsWith" ? e.data.startsWith(s.value) || (a = this._getOrReturnCtx(e, a), x(a, {
          code: g.invalid_string,
          validation: { startsWith: s.value },
          message: s.message
        }), n.dirty()) : s.kind === "endsWith" ? e.data.endsWith(s.value) || (a = this._getOrReturnCtx(e, a), x(a, {
          code: g.invalid_string,
          validation: { endsWith: s.value },
          message: s.message
        }), n.dirty()) : s.kind === "datetime" ? Qi(s).test(e.data) || (a = this._getOrReturnCtx(e, a), x(a, {
          code: g.invalid_string,
          validation: "datetime",
          message: s.message
        }), n.dirty()) : s.kind === "ip" ? ec(e.data, s.version) || (a = this._getOrReturnCtx(e, a), x(a, {
          validation: "ip",
          code: g.invalid_string,
          message: s.message
        }), n.dirty()) : R.assertNever(s);
    return { status: n.value, value: e.data };
  }
  _regex(e, r, n) {
    return this.refinement((a) => e.test(a), {
      validation: r,
      code: g.invalid_string,
      ...S.errToObj(n)
    });
  }
  _addCheck(e) {
    return new we({
      ...this._def,
      checks: [...this._def.checks, e]
    });
  }
  email(e) {
    return this._addCheck({ kind: "email", ...S.errToObj(e) });
  }
  url(e) {
    return this._addCheck({ kind: "url", ...S.errToObj(e) });
  }
  emoji(e) {
    return this._addCheck({ kind: "emoji", ...S.errToObj(e) });
  }
  uuid(e) {
    return this._addCheck({ kind: "uuid", ...S.errToObj(e) });
  }
  cuid(e) {
    return this._addCheck({ kind: "cuid", ...S.errToObj(e) });
  }
  cuid2(e) {
    return this._addCheck({ kind: "cuid2", ...S.errToObj(e) });
  }
  ulid(e) {
    return this._addCheck({ kind: "ulid", ...S.errToObj(e) });
  }
  ip(e) {
    return this._addCheck({ kind: "ip", ...S.errToObj(e) });
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
      ...S.errToObj(e == null ? void 0 : e.message)
    });
  }
  regex(e, r) {
    return this._addCheck({
      kind: "regex",
      regex: e,
      ...S.errToObj(r)
    });
  }
  includes(e, r) {
    return this._addCheck({
      kind: "includes",
      value: e,
      position: r == null ? void 0 : r.position,
      ...S.errToObj(r == null ? void 0 : r.message)
    });
  }
  startsWith(e, r) {
    return this._addCheck({
      kind: "startsWith",
      value: e,
      ...S.errToObj(r)
    });
  }
  endsWith(e, r) {
    return this._addCheck({
      kind: "endsWith",
      value: e,
      ...S.errToObj(r)
    });
  }
  min(e, r) {
    return this._addCheck({
      kind: "min",
      value: e,
      ...S.errToObj(r)
    });
  }
  max(e, r) {
    return this._addCheck({
      kind: "max",
      value: e,
      ...S.errToObj(r)
    });
  }
  length(e, r) {
    return this._addCheck({
      kind: "length",
      value: e,
      ...S.errToObj(r)
    });
  }
  /**
   * @deprecated Use z.string().min(1) instead.
   * @see {@link ZodString.min}
   */
  nonempty(e) {
    return this.min(1, S.errToObj(e));
  }
  trim() {
    return new we({
      ...this._def,
      checks: [...this._def.checks, { kind: "trim" }]
    });
  }
  toLowerCase() {
    return new we({
      ...this._def,
      checks: [...this._def.checks, { kind: "toLowerCase" }]
    });
  }
  toUpperCase() {
    return new we({
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
we.create = (t) => {
  var e;
  return new we({
    checks: [],
    typeName: P.ZodString,
    coerce: (e = t == null ? void 0 : t.coerce) !== null && e !== void 0 ? e : !1,
    ...C(t)
  });
};
function tc(t, e) {
  const r = (t.toString().split(".")[1] || "").length, n = (e.toString().split(".")[1] || "").length, a = r > n ? r : n, s = parseInt(t.toFixed(a).replace(".", "")), i = parseInt(e.toFixed(a).replace(".", ""));
  return s % i / Math.pow(10, a);
}
class Ve extends $ {
  constructor() {
    super(...arguments), this.min = this.gte, this.max = this.lte, this.step = this.multipleOf;
  }
  _parse(e) {
    if (this._def.coerce && (e.data = Number(e.data)), this._getType(e) !== b.number) {
      const s = this._getOrReturnCtx(e);
      return x(s, {
        code: g.invalid_type,
        expected: b.number,
        received: s.parsedType
      }), A;
    }
    let n;
    const a = new Y();
    for (const s of this._def.checks)
      s.kind === "int" ? R.isInteger(e.data) || (n = this._getOrReturnCtx(e, n), x(n, {
        code: g.invalid_type,
        expected: "integer",
        received: "float",
        message: s.message
      }), a.dirty()) : s.kind === "min" ? (s.inclusive ? e.data < s.value : e.data <= s.value) && (n = this._getOrReturnCtx(e, n), x(n, {
        code: g.too_small,
        minimum: s.value,
        type: "number",
        inclusive: s.inclusive,
        exact: !1,
        message: s.message
      }), a.dirty()) : s.kind === "max" ? (s.inclusive ? e.data > s.value : e.data >= s.value) && (n = this._getOrReturnCtx(e, n), x(n, {
        code: g.too_big,
        maximum: s.value,
        type: "number",
        inclusive: s.inclusive,
        exact: !1,
        message: s.message
      }), a.dirty()) : s.kind === "multipleOf" ? tc(e.data, s.value) !== 0 && (n = this._getOrReturnCtx(e, n), x(n, {
        code: g.not_multiple_of,
        multipleOf: s.value,
        message: s.message
      }), a.dirty()) : s.kind === "finite" ? Number.isFinite(e.data) || (n = this._getOrReturnCtx(e, n), x(n, {
        code: g.not_finite,
        message: s.message
      }), a.dirty()) : R.assertNever(s);
    return { status: a.value, value: e.data };
  }
  gte(e, r) {
    return this.setLimit("min", e, !0, S.toString(r));
  }
  gt(e, r) {
    return this.setLimit("min", e, !1, S.toString(r));
  }
  lte(e, r) {
    return this.setLimit("max", e, !0, S.toString(r));
  }
  lt(e, r) {
    return this.setLimit("max", e, !1, S.toString(r));
  }
  setLimit(e, r, n, a) {
    return new Ve({
      ...this._def,
      checks: [
        ...this._def.checks,
        {
          kind: e,
          value: r,
          inclusive: n,
          message: S.toString(a)
        }
      ]
    });
  }
  _addCheck(e) {
    return new Ve({
      ...this._def,
      checks: [...this._def.checks, e]
    });
  }
  int(e) {
    return this._addCheck({
      kind: "int",
      message: S.toString(e)
    });
  }
  positive(e) {
    return this._addCheck({
      kind: "min",
      value: 0,
      inclusive: !1,
      message: S.toString(e)
    });
  }
  negative(e) {
    return this._addCheck({
      kind: "max",
      value: 0,
      inclusive: !1,
      message: S.toString(e)
    });
  }
  nonpositive(e) {
    return this._addCheck({
      kind: "max",
      value: 0,
      inclusive: !0,
      message: S.toString(e)
    });
  }
  nonnegative(e) {
    return this._addCheck({
      kind: "min",
      value: 0,
      inclusive: !0,
      message: S.toString(e)
    });
  }
  multipleOf(e, r) {
    return this._addCheck({
      kind: "multipleOf",
      value: e,
      message: S.toString(r)
    });
  }
  finite(e) {
    return this._addCheck({
      kind: "finite",
      message: S.toString(e)
    });
  }
  safe(e) {
    return this._addCheck({
      kind: "min",
      inclusive: !0,
      value: Number.MIN_SAFE_INTEGER,
      message: S.toString(e)
    })._addCheck({
      kind: "max",
      inclusive: !0,
      value: Number.MAX_SAFE_INTEGER,
      message: S.toString(e)
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
Ve.create = (t) => new Ve({
  checks: [],
  typeName: P.ZodNumber,
  coerce: (t == null ? void 0 : t.coerce) || !1,
  ...C(t)
});
class We extends $ {
  constructor() {
    super(...arguments), this.min = this.gte, this.max = this.lte;
  }
  _parse(e) {
    if (this._def.coerce && (e.data = BigInt(e.data)), this._getType(e) !== b.bigint) {
      const s = this._getOrReturnCtx(e);
      return x(s, {
        code: g.invalid_type,
        expected: b.bigint,
        received: s.parsedType
      }), A;
    }
    let n;
    const a = new Y();
    for (const s of this._def.checks)
      s.kind === "min" ? (s.inclusive ? e.data < s.value : e.data <= s.value) && (n = this._getOrReturnCtx(e, n), x(n, {
        code: g.too_small,
        type: "bigint",
        minimum: s.value,
        inclusive: s.inclusive,
        message: s.message
      }), a.dirty()) : s.kind === "max" ? (s.inclusive ? e.data > s.value : e.data >= s.value) && (n = this._getOrReturnCtx(e, n), x(n, {
        code: g.too_big,
        type: "bigint",
        maximum: s.value,
        inclusive: s.inclusive,
        message: s.message
      }), a.dirty()) : s.kind === "multipleOf" ? e.data % s.value !== BigInt(0) && (n = this._getOrReturnCtx(e, n), x(n, {
        code: g.not_multiple_of,
        multipleOf: s.value,
        message: s.message
      }), a.dirty()) : R.assertNever(s);
    return { status: a.value, value: e.data };
  }
  gte(e, r) {
    return this.setLimit("min", e, !0, S.toString(r));
  }
  gt(e, r) {
    return this.setLimit("min", e, !1, S.toString(r));
  }
  lte(e, r) {
    return this.setLimit("max", e, !0, S.toString(r));
  }
  lt(e, r) {
    return this.setLimit("max", e, !1, S.toString(r));
  }
  setLimit(e, r, n, a) {
    return new We({
      ...this._def,
      checks: [
        ...this._def.checks,
        {
          kind: e,
          value: r,
          inclusive: n,
          message: S.toString(a)
        }
      ]
    });
  }
  _addCheck(e) {
    return new We({
      ...this._def,
      checks: [...this._def.checks, e]
    });
  }
  positive(e) {
    return this._addCheck({
      kind: "min",
      value: BigInt(0),
      inclusive: !1,
      message: S.toString(e)
    });
  }
  negative(e) {
    return this._addCheck({
      kind: "max",
      value: BigInt(0),
      inclusive: !1,
      message: S.toString(e)
    });
  }
  nonpositive(e) {
    return this._addCheck({
      kind: "max",
      value: BigInt(0),
      inclusive: !0,
      message: S.toString(e)
    });
  }
  nonnegative(e) {
    return this._addCheck({
      kind: "min",
      value: BigInt(0),
      inclusive: !0,
      message: S.toString(e)
    });
  }
  multipleOf(e, r) {
    return this._addCheck({
      kind: "multipleOf",
      value: e,
      message: S.toString(r)
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
We.create = (t) => {
  var e;
  return new We({
    checks: [],
    typeName: P.ZodBigInt,
    coerce: (e = t == null ? void 0 : t.coerce) !== null && e !== void 0 ? e : !1,
    ...C(t)
  });
};
class Rt extends $ {
  _parse(e) {
    if (this._def.coerce && (e.data = !!e.data), this._getType(e) !== b.boolean) {
      const n = this._getOrReturnCtx(e);
      return x(n, {
        code: g.invalid_type,
        expected: b.boolean,
        received: n.parsedType
      }), A;
    }
    return re(e.data);
  }
}
Rt.create = (t) => new Rt({
  typeName: P.ZodBoolean,
  coerce: (t == null ? void 0 : t.coerce) || !1,
  ...C(t)
});
class st extends $ {
  _parse(e) {
    if (this._def.coerce && (e.data = new Date(e.data)), this._getType(e) !== b.date) {
      const s = this._getOrReturnCtx(e);
      return x(s, {
        code: g.invalid_type,
        expected: b.date,
        received: s.parsedType
      }), A;
    }
    if (isNaN(e.data.getTime())) {
      const s = this._getOrReturnCtx(e);
      return x(s, {
        code: g.invalid_date
      }), A;
    }
    const n = new Y();
    let a;
    for (const s of this._def.checks)
      s.kind === "min" ? e.data.getTime() < s.value && (a = this._getOrReturnCtx(e, a), x(a, {
        code: g.too_small,
        message: s.message,
        inclusive: !0,
        exact: !1,
        minimum: s.value,
        type: "date"
      }), n.dirty()) : s.kind === "max" ? e.data.getTime() > s.value && (a = this._getOrReturnCtx(e, a), x(a, {
        code: g.too_big,
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
    return new st({
      ...this._def,
      checks: [...this._def.checks, e]
    });
  }
  min(e, r) {
    return this._addCheck({
      kind: "min",
      value: e.getTime(),
      message: S.toString(r)
    });
  }
  max(e, r) {
    return this._addCheck({
      kind: "max",
      value: e.getTime(),
      message: S.toString(r)
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
st.create = (t) => new st({
  checks: [],
  coerce: (t == null ? void 0 : t.coerce) || !1,
  typeName: P.ZodDate,
  ...C(t)
});
class mr extends $ {
  _parse(e) {
    if (this._getType(e) !== b.symbol) {
      const n = this._getOrReturnCtx(e);
      return x(n, {
        code: g.invalid_type,
        expected: b.symbol,
        received: n.parsedType
      }), A;
    }
    return re(e.data);
  }
}
mr.create = (t) => new mr({
  typeName: P.ZodSymbol,
  ...C(t)
});
class Mt extends $ {
  _parse(e) {
    if (this._getType(e) !== b.undefined) {
      const n = this._getOrReturnCtx(e);
      return x(n, {
        code: g.invalid_type,
        expected: b.undefined,
        received: n.parsedType
      }), A;
    }
    return re(e.data);
  }
}
Mt.create = (t) => new Mt({
  typeName: P.ZodUndefined,
  ...C(t)
});
class Lt extends $ {
  _parse(e) {
    if (this._getType(e) !== b.null) {
      const n = this._getOrReturnCtx(e);
      return x(n, {
        code: g.invalid_type,
        expected: b.null,
        received: n.parsedType
      }), A;
    }
    return re(e.data);
  }
}
Lt.create = (t) => new Lt({
  typeName: P.ZodNull,
  ...C(t)
});
class _t extends $ {
  constructor() {
    super(...arguments), this._any = !0;
  }
  _parse(e) {
    return re(e.data);
  }
}
_t.create = (t) => new _t({
  typeName: P.ZodAny,
  ...C(t)
});
class rt extends $ {
  constructor() {
    super(...arguments), this._unknown = !0;
  }
  _parse(e) {
    return re(e.data);
  }
}
rt.create = (t) => new rt({
  typeName: P.ZodUnknown,
  ...C(t)
});
class Fe extends $ {
  _parse(e) {
    const r = this._getOrReturnCtx(e);
    return x(r, {
      code: g.invalid_type,
      expected: b.never,
      received: r.parsedType
    }), A;
  }
}
Fe.create = (t) => new Fe({
  typeName: P.ZodNever,
  ...C(t)
});
class hr extends $ {
  _parse(e) {
    if (this._getType(e) !== b.undefined) {
      const n = this._getOrReturnCtx(e);
      return x(n, {
        code: g.invalid_type,
        expected: b.void,
        received: n.parsedType
      }), A;
    }
    return re(e.data);
  }
}
hr.create = (t) => new hr({
  typeName: P.ZodVoid,
  ...C(t)
});
class Se extends $ {
  _parse(e) {
    const { ctx: r, status: n } = this._processInputParams(e), a = this._def;
    if (r.parsedType !== b.array)
      return x(r, {
        code: g.invalid_type,
        expected: b.array,
        received: r.parsedType
      }), A;
    if (a.exactLength !== null) {
      const i = r.data.length > a.exactLength.value, c = r.data.length < a.exactLength.value;
      (i || c) && (x(r, {
        code: i ? g.too_big : g.too_small,
        minimum: c ? a.exactLength.value : void 0,
        maximum: i ? a.exactLength.value : void 0,
        type: "array",
        inclusive: !0,
        exact: !0,
        message: a.exactLength.message
      }), n.dirty());
    }
    if (a.minLength !== null && r.data.length < a.minLength.value && (x(r, {
      code: g.too_small,
      minimum: a.minLength.value,
      type: "array",
      inclusive: !0,
      exact: !1,
      message: a.minLength.message
    }), n.dirty()), a.maxLength !== null && r.data.length > a.maxLength.value && (x(r, {
      code: g.too_big,
      maximum: a.maxLength.value,
      type: "array",
      inclusive: !0,
      exact: !1,
      message: a.maxLength.message
    }), n.dirty()), r.common.async)
      return Promise.all([...r.data].map((i, c) => a.type._parseAsync(new Ce(r, i, r.path, c)))).then((i) => Y.mergeArray(n, i));
    const s = [...r.data].map((i, c) => a.type._parseSync(new Ce(r, i, r.path, c)));
    return Y.mergeArray(n, s);
  }
  get element() {
    return this._def.type;
  }
  min(e, r) {
    return new Se({
      ...this._def,
      minLength: { value: e, message: S.toString(r) }
    });
  }
  max(e, r) {
    return new Se({
      ...this._def,
      maxLength: { value: e, message: S.toString(r) }
    });
  }
  length(e, r) {
    return new Se({
      ...this._def,
      exactLength: { value: e, message: S.toString(r) }
    });
  }
  nonempty(e) {
    return this.min(1, e);
  }
}
Se.create = (t, e) => new Se({
  type: t,
  minLength: null,
  maxLength: null,
  exactLength: null,
  typeName: P.ZodArray,
  ...C(e)
});
function pt(t) {
  if (t instanceof j) {
    const e = {};
    for (const r in t.shape) {
      const n = t.shape[r];
      e[r] = Me.create(pt(n));
    }
    return new j({
      ...t._def,
      shape: () => e
    });
  } else
    return t instanceof Se ? new Se({
      ...t._def,
      type: pt(t.element)
    }) : t instanceof Me ? Me.create(pt(t.unwrap())) : t instanceof it ? it.create(pt(t.unwrap())) : t instanceof Ne ? Ne.create(t.items.map((e) => pt(e))) : t;
}
class j extends $ {
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
      return x(l, {
        code: g.invalid_type,
        expected: b.object,
        received: l.parsedType
      }), A;
    }
    const { status: n, ctx: a } = this._processInputParams(e), { shape: s, keys: i } = this._getCached(), c = [];
    if (!(this._def.catchall instanceof Fe && this._def.unknownKeys === "strip"))
      for (const l in a.data)
        i.includes(l) || c.push(l);
    const u = [];
    for (const l of i) {
      const d = s[l], f = a.data[l];
      u.push({
        key: { status: "valid", value: l },
        value: d._parse(new Ce(a, f, a.path, l)),
        alwaysSet: l in a.data
      });
    }
    if (this._def.catchall instanceof Fe) {
      const l = this._def.unknownKeys;
      if (l === "passthrough")
        for (const d of c)
          u.push({
            key: { status: "valid", value: d },
            value: { status: "valid", value: a.data[d] }
          });
      else if (l === "strict")
        c.length > 0 && (x(a, {
          code: g.unrecognized_keys,
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
            new Ce(a, f, a.path, d)
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
    }).then((l) => Y.mergeObjectSync(n, l)) : Y.mergeObjectSync(n, u);
  }
  get shape() {
    return this._def.shape();
  }
  strict(e) {
    return S.errToObj, new j({
      ...this._def,
      unknownKeys: "strict",
      ...e !== void 0 ? {
        errorMap: (r, n) => {
          var a, s, i, c;
          const u = (i = (s = (a = this._def).errorMap) === null || s === void 0 ? void 0 : s.call(a, r, n).message) !== null && i !== void 0 ? i : n.defaultError;
          return r.code === "unrecognized_keys" ? {
            message: (c = S.errToObj(e).message) !== null && c !== void 0 ? c : u
          } : {
            message: u
          };
        }
      } : {}
    });
  }
  strip() {
    return new j({
      ...this._def,
      unknownKeys: "strip"
    });
  }
  passthrough() {
    return new j({
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
    return new j({
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
    return new j({
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
    return new j({
      ...this._def,
      catchall: e
    });
  }
  pick(e) {
    const r = {};
    return R.objectKeys(e).forEach((n) => {
      e[n] && this.shape[n] && (r[n] = this.shape[n]);
    }), new j({
      ...this._def,
      shape: () => r
    });
  }
  omit(e) {
    const r = {};
    return R.objectKeys(this.shape).forEach((n) => {
      e[n] || (r[n] = this.shape[n]);
    }), new j({
      ...this._def,
      shape: () => r
    });
  }
  /**
   * @deprecated
   */
  deepPartial() {
    return pt(this);
  }
  partial(e) {
    const r = {};
    return R.objectKeys(this.shape).forEach((n) => {
      const a = this.shape[n];
      e && !e[n] ? r[n] = a : r[n] = a.optional();
    }), new j({
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
        for (; s instanceof Me; )
          s = s._def.innerType;
        r[n] = s;
      }
    }), new j({
      ...this._def,
      shape: () => r
    });
  }
  keyof() {
    return ds(R.objectKeys(this.shape));
  }
}
j.create = (t, e) => new j({
  shape: () => t,
  unknownKeys: "strip",
  catchall: Fe.create(),
  typeName: P.ZodObject,
  ...C(e)
});
j.strictCreate = (t, e) => new j({
  shape: () => t,
  unknownKeys: "strict",
  catchall: Fe.create(),
  typeName: P.ZodObject,
  ...C(e)
});
j.lazycreate = (t, e) => new j({
  shape: t,
  unknownKeys: "strip",
  catchall: Fe.create(),
  typeName: P.ZodObject,
  ...C(e)
});
class Ft extends $ {
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
      return x(r, {
        code: g.invalid_union,
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
      return x(r, {
        code: g.invalid_union,
        unionErrors: c
      }), A;
    }
  }
  get options() {
    return this._def.options;
  }
}
Ft.create = (t, e) => new Ft({
  options: t,
  typeName: P.ZodUnion,
  ...C(e)
});
const or = (t) => t instanceof Ut ? or(t.schema) : t instanceof Ee ? or(t.innerType()) : t instanceof Zt ? [t.value] : t instanceof qe ? t.options : t instanceof zt ? Object.keys(t.enum) : t instanceof Bt ? or(t._def.innerType) : t instanceof Mt ? [void 0] : t instanceof Lt ? [null] : null;
class Nr extends $ {
  _parse(e) {
    const { ctx: r } = this._processInputParams(e);
    if (r.parsedType !== b.object)
      return x(r, {
        code: g.invalid_type,
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
    }) : (x(r, {
      code: g.invalid_union_discriminator,
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
      const i = or(s.shape[e]);
      if (!i)
        throw new Error(`A discriminator value for key \`${e}\` could not be extracted from all schema options`);
      for (const c of i) {
        if (a.has(c))
          throw new Error(`Discriminator property ${String(e)} has duplicate value ${String(c)}`);
        a.set(c, s);
      }
    }
    return new Nr({
      typeName: P.ZodDiscriminatedUnion,
      discriminator: e,
      options: r,
      optionsMap: a,
      ...C(n)
    });
  }
}
function Qr(t, e) {
  const r = Ze(t), n = Ze(e);
  if (t === e)
    return { valid: !0, data: t };
  if (r === b.object && n === b.object) {
    const a = R.objectKeys(e), s = R.objectKeys(t).filter((c) => a.indexOf(c) !== -1), i = { ...t, ...e };
    for (const c of s) {
      const u = Qr(t[c], e[c]);
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
      const i = t[s], c = e[s], u = Qr(i, c);
      if (!u.valid)
        return { valid: !1 };
      a.push(u.data);
    }
    return { valid: !0, data: a };
  } else
    return r === b.date && n === b.date && +t == +e ? { valid: !0, data: t } : { valid: !1 };
}
class Dt extends $ {
  _parse(e) {
    const { status: r, ctx: n } = this._processInputParams(e), a = (s, i) => {
      if (Yr(s) || Yr(i))
        return A;
      const c = Qr(s.value, i.value);
      return c.valid ? ((Xr(s) || Xr(i)) && r.dirty(), { status: r.value, value: c.data }) : (x(n, {
        code: g.invalid_intersection_types
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
Dt.create = (t, e, r) => new Dt({
  left: t,
  right: e,
  typeName: P.ZodIntersection,
  ...C(r)
});
class Ne extends $ {
  _parse(e) {
    const { status: r, ctx: n } = this._processInputParams(e);
    if (n.parsedType !== b.array)
      return x(n, {
        code: g.invalid_type,
        expected: b.array,
        received: n.parsedType
      }), A;
    if (n.data.length < this._def.items.length)
      return x(n, {
        code: g.too_small,
        minimum: this._def.items.length,
        inclusive: !0,
        exact: !1,
        type: "array"
      }), A;
    !this._def.rest && n.data.length > this._def.items.length && (x(n, {
      code: g.too_big,
      maximum: this._def.items.length,
      inclusive: !0,
      exact: !1,
      type: "array"
    }), r.dirty());
    const s = [...n.data].map((i, c) => {
      const u = this._def.items[c] || this._def.rest;
      return u ? u._parse(new Ce(n, i, n.path, c)) : null;
    }).filter((i) => !!i);
    return n.common.async ? Promise.all(s).then((i) => Y.mergeArray(r, i)) : Y.mergeArray(r, s);
  }
  get items() {
    return this._def.items;
  }
  rest(e) {
    return new Ne({
      ...this._def,
      rest: e
    });
  }
}
Ne.create = (t, e) => {
  if (!Array.isArray(t))
    throw new Error("You must pass an array of schemas to z.tuple([ ... ])");
  return new Ne({
    items: t,
    typeName: P.ZodTuple,
    rest: null,
    ...C(e)
  });
};
class jt extends $ {
  get keySchema() {
    return this._def.keyType;
  }
  get valueSchema() {
    return this._def.valueType;
  }
  _parse(e) {
    const { status: r, ctx: n } = this._processInputParams(e);
    if (n.parsedType !== b.object)
      return x(n, {
        code: g.invalid_type,
        expected: b.object,
        received: n.parsedType
      }), A;
    const a = [], s = this._def.keyType, i = this._def.valueType;
    for (const c in n.data)
      a.push({
        key: s._parse(new Ce(n, c, n.path, c)),
        value: i._parse(new Ce(n, n.data[c], n.path, c))
      });
    return n.common.async ? Y.mergeObjectAsync(r, a) : Y.mergeObjectSync(r, a);
  }
  get element() {
    return this._def.valueType;
  }
  static create(e, r, n) {
    return r instanceof $ ? new jt({
      keyType: e,
      valueType: r,
      typeName: P.ZodRecord,
      ...C(n)
    }) : new jt({
      keyType: we.create(),
      valueType: e,
      typeName: P.ZodRecord,
      ...C(r)
    });
  }
}
class gr extends $ {
  get keySchema() {
    return this._def.keyType;
  }
  get valueSchema() {
    return this._def.valueType;
  }
  _parse(e) {
    const { status: r, ctx: n } = this._processInputParams(e);
    if (n.parsedType !== b.map)
      return x(n, {
        code: g.invalid_type,
        expected: b.map,
        received: n.parsedType
      }), A;
    const a = this._def.keyType, s = this._def.valueType, i = [...n.data.entries()].map(([c, u], l) => ({
      key: a._parse(new Ce(n, c, n.path, [l, "key"])),
      value: s._parse(new Ce(n, u, n.path, [l, "value"]))
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
gr.create = (t, e, r) => new gr({
  valueType: e,
  keyType: t,
  typeName: P.ZodMap,
  ...C(r)
});
class at extends $ {
  _parse(e) {
    const { status: r, ctx: n } = this._processInputParams(e);
    if (n.parsedType !== b.set)
      return x(n, {
        code: g.invalid_type,
        expected: b.set,
        received: n.parsedType
      }), A;
    const a = this._def;
    a.minSize !== null && n.data.size < a.minSize.value && (x(n, {
      code: g.too_small,
      minimum: a.minSize.value,
      type: "set",
      inclusive: !0,
      exact: !1,
      message: a.minSize.message
    }), r.dirty()), a.maxSize !== null && n.data.size > a.maxSize.value && (x(n, {
      code: g.too_big,
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
    const c = [...n.data.values()].map((u, l) => s._parse(new Ce(n, u, n.path, l)));
    return n.common.async ? Promise.all(c).then((u) => i(u)) : i(c);
  }
  min(e, r) {
    return new at({
      ...this._def,
      minSize: { value: e, message: S.toString(r) }
    });
  }
  max(e, r) {
    return new at({
      ...this._def,
      maxSize: { value: e, message: S.toString(r) }
    });
  }
  size(e, r) {
    return this.min(e, r).max(e, r);
  }
  nonempty(e) {
    return this.min(1, e);
  }
}
at.create = (t, e) => new at({
  valueType: t,
  minSize: null,
  maxSize: null,
  typeName: P.ZodSet,
  ...C(e)
});
class ht extends $ {
  constructor() {
    super(...arguments), this.validate = this.implement;
  }
  _parse(e) {
    const { ctx: r } = this._processInputParams(e);
    if (r.parsedType !== b.function)
      return x(r, {
        code: g.invalid_type,
        expected: b.function,
        received: r.parsedType
      }), A;
    function n(c, u) {
      return fr({
        data: c,
        path: r.path,
        errorMaps: [
          r.common.contextualErrorMap,
          r.schemaErrorMap,
          dr(),
          $t
        ].filter((l) => !!l),
        issueData: {
          code: g.invalid_arguments,
          argumentsError: u
        }
      });
    }
    function a(c, u) {
      return fr({
        data: c,
        path: r.path,
        errorMaps: [
          r.common.contextualErrorMap,
          r.schemaErrorMap,
          dr(),
          $t
        ].filter((l) => !!l),
        issueData: {
          code: g.invalid_return_type,
          returnTypeError: u
        }
      });
    }
    const s = { errorMap: r.common.contextualErrorMap }, i = r.data;
    if (this._def.returns instanceof bt) {
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
    return new ht({
      ...this._def,
      args: Ne.create(e).rest(rt.create())
    });
  }
  returns(e) {
    return new ht({
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
    return new ht({
      args: e || Ne.create([]).rest(rt.create()),
      returns: r || rt.create(),
      typeName: P.ZodFunction,
      ...C(n)
    });
  }
}
class Ut extends $ {
  get schema() {
    return this._def.getter();
  }
  _parse(e) {
    const { ctx: r } = this._processInputParams(e);
    return this._def.getter()._parse({ data: r.data, path: r.path, parent: r });
  }
}
Ut.create = (t, e) => new Ut({
  getter: t,
  typeName: P.ZodLazy,
  ...C(e)
});
class Zt extends $ {
  _parse(e) {
    if (e.data !== this._def.value) {
      const r = this._getOrReturnCtx(e);
      return x(r, {
        received: r.data,
        code: g.invalid_literal,
        expected: this._def.value
      }), A;
    }
    return { status: "valid", value: e.data };
  }
  get value() {
    return this._def.value;
  }
}
Zt.create = (t, e) => new Zt({
  value: t,
  typeName: P.ZodLiteral,
  ...C(e)
});
function ds(t, e) {
  return new qe({
    values: t,
    typeName: P.ZodEnum,
    ...C(e)
  });
}
class qe extends $ {
  _parse(e) {
    if (typeof e.data != "string") {
      const r = this._getOrReturnCtx(e), n = this._def.values;
      return x(r, {
        expected: R.joinValues(n),
        received: r.parsedType,
        code: g.invalid_type
      }), A;
    }
    if (this._def.values.indexOf(e.data) === -1) {
      const r = this._getOrReturnCtx(e), n = this._def.values;
      return x(r, {
        received: r.data,
        code: g.invalid_enum_value,
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
    return qe.create(e);
  }
  exclude(e) {
    return qe.create(this.options.filter((r) => !e.includes(r)));
  }
}
qe.create = ds;
class zt extends $ {
  _parse(e) {
    const r = R.getValidEnumValues(this._def.values), n = this._getOrReturnCtx(e);
    if (n.parsedType !== b.string && n.parsedType !== b.number) {
      const a = R.objectValues(r);
      return x(n, {
        expected: R.joinValues(a),
        received: n.parsedType,
        code: g.invalid_type
      }), A;
    }
    if (r.indexOf(e.data) === -1) {
      const a = R.objectValues(r);
      return x(n, {
        received: n.data,
        code: g.invalid_enum_value,
        options: a
      }), A;
    }
    return re(e.data);
  }
  get enum() {
    return this._def.values;
  }
}
zt.create = (t, e) => new zt({
  values: t,
  typeName: P.ZodNativeEnum,
  ...C(e)
});
class bt extends $ {
  unwrap() {
    return this._def.type;
  }
  _parse(e) {
    const { ctx: r } = this._processInputParams(e);
    if (r.parsedType !== b.promise && r.common.async === !1)
      return x(r, {
        code: g.invalid_type,
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
bt.create = (t, e) => new bt({
  type: t,
  typeName: P.ZodPromise,
  ...C(e)
});
class Ee extends $ {
  innerType() {
    return this._def.schema;
  }
  sourceType() {
    return this._def.schema._def.typeName === P.ZodEffects ? this._def.schema.sourceType() : this._def.schema;
  }
  _parse(e) {
    const { status: r, ctx: n } = this._processInputParams(e), a = this._def.effect || null, s = {
      addIssue: (i) => {
        x(n, i), i.fatal ? r.abort() : r.dirty();
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
        if (!Ot(i))
          return i;
        const c = a.transform(i.value, s);
        if (c instanceof Promise)
          throw new Error("Asynchronous transform encountered during synchronous parse operation. Use .parseAsync instead.");
        return { status: r.value, value: c };
      } else
        return this._def.schema._parseAsync({ data: n.data, path: n.path, parent: n }).then((i) => Ot(i) ? Promise.resolve(a.transform(i.value, s)).then((c) => ({ status: r.value, value: c })) : i);
    R.assertNever(a);
  }
}
Ee.create = (t, e, r) => new Ee({
  schema: t,
  typeName: P.ZodEffects,
  effect: e,
  ...C(r)
});
Ee.createWithPreprocess = (t, e, r) => new Ee({
  schema: e,
  effect: { type: "preprocess", transform: t },
  typeName: P.ZodEffects,
  ...C(r)
});
class Me extends $ {
  _parse(e) {
    return this._getType(e) === b.undefined ? re(void 0) : this._def.innerType._parse(e);
  }
  unwrap() {
    return this._def.innerType;
  }
}
Me.create = (t, e) => new Me({
  innerType: t,
  typeName: P.ZodOptional,
  ...C(e)
});
class it extends $ {
  _parse(e) {
    return this._getType(e) === b.null ? re(null) : this._def.innerType._parse(e);
  }
  unwrap() {
    return this._def.innerType;
  }
}
it.create = (t, e) => new it({
  innerType: t,
  typeName: P.ZodNullable,
  ...C(e)
});
class Bt extends $ {
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
Bt.create = (t, e) => new Bt({
  innerType: t,
  typeName: P.ZodDefault,
  defaultValue: typeof e.default == "function" ? e.default : () => e.default,
  ...C(e)
});
class yr extends $ {
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
    return pr(a) ? a.then((s) => ({
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
yr.create = (t, e) => new yr({
  innerType: t,
  typeName: P.ZodCatch,
  catchValue: typeof e.catch == "function" ? e.catch : () => e.catch,
  ...C(e)
});
class vr extends $ {
  _parse(e) {
    if (this._getType(e) !== b.nan) {
      const n = this._getOrReturnCtx(e);
      return x(n, {
        code: g.invalid_type,
        expected: b.nan,
        received: n.parsedType
      }), A;
    }
    return { status: "valid", value: e.data };
  }
}
vr.create = (t) => new vr({
  typeName: P.ZodNaN,
  ...C(t)
});
const rc = Symbol("zod_brand");
class fs extends $ {
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
class qt extends $ {
  _parse(e) {
    const { status: r, ctx: n } = this._processInputParams(e);
    if (n.common.async)
      return (async () => {
        const s = await this._def.in._parseAsync({
          data: n.data,
          path: n.path,
          parent: n
        });
        return s.status === "aborted" ? A : s.status === "dirty" ? (r.dirty(), us(s.value)) : this._def.out._parseAsync({
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
    return new qt({
      in: e,
      out: r,
      typeName: P.ZodPipeline
    });
  }
}
class _r extends $ {
  _parse(e) {
    const r = this._def.innerType._parse(e);
    return Ot(r) && (r.value = Object.freeze(r.value)), r;
  }
}
_r.create = (t, e) => new _r({
  innerType: t,
  typeName: P.ZodReadonly,
  ...C(e)
});
const ps = (t, e = {}, r) => t ? _t.create().superRefine((n, a) => {
  var s, i;
  if (!t(n)) {
    const c = typeof e == "function" ? e(n) : typeof e == "string" ? { message: e } : e, u = (i = (s = c.fatal) !== null && s !== void 0 ? s : r) !== null && i !== void 0 ? i : !0, l = typeof c == "string" ? { message: c } : c;
    a.addIssue({ code: "custom", ...l, fatal: u });
  }
}) : _t.create(), nc = {
  object: j.lazycreate
};
var P;
(function(t) {
  t.ZodString = "ZodString", t.ZodNumber = "ZodNumber", t.ZodNaN = "ZodNaN", t.ZodBigInt = "ZodBigInt", t.ZodBoolean = "ZodBoolean", t.ZodDate = "ZodDate", t.ZodSymbol = "ZodSymbol", t.ZodUndefined = "ZodUndefined", t.ZodNull = "ZodNull", t.ZodAny = "ZodAny", t.ZodUnknown = "ZodUnknown", t.ZodNever = "ZodNever", t.ZodVoid = "ZodVoid", t.ZodArray = "ZodArray", t.ZodObject = "ZodObject", t.ZodUnion = "ZodUnion", t.ZodDiscriminatedUnion = "ZodDiscriminatedUnion", t.ZodIntersection = "ZodIntersection", t.ZodTuple = "ZodTuple", t.ZodRecord = "ZodRecord", t.ZodMap = "ZodMap", t.ZodSet = "ZodSet", t.ZodFunction = "ZodFunction", t.ZodLazy = "ZodLazy", t.ZodLiteral = "ZodLiteral", t.ZodEnum = "ZodEnum", t.ZodEffects = "ZodEffects", t.ZodNativeEnum = "ZodNativeEnum", t.ZodOptional = "ZodOptional", t.ZodNullable = "ZodNullable", t.ZodDefault = "ZodDefault", t.ZodCatch = "ZodCatch", t.ZodPromise = "ZodPromise", t.ZodBranded = "ZodBranded", t.ZodPipeline = "ZodPipeline", t.ZodReadonly = "ZodReadonly";
})(P || (P = {}));
const oc = (t, e = {
  message: `Input not instance of ${t.name}`
}) => ps((r) => r instanceof t, e), ms = we.create, hs = Ve.create, sc = vr.create, ac = We.create, gs = Rt.create, ic = st.create, cc = mr.create, lc = Mt.create, uc = Lt.create, dc = _t.create, fc = rt.create, pc = Fe.create, mc = hr.create, hc = Se.create, gc = j.create, yc = j.strictCreate, vc = Ft.create, _c = Nr.create, bc = Dt.create, wc = Ne.create, xc = jt.create, Sc = gr.create, Ec = at.create, Pc = ht.create, kc = Ut.create, Tc = Zt.create, Ic = qe.create, Ac = zt.create, Cc = bt.create, Qn = Ee.create, Nc = Me.create, $c = it.create, Oc = Ee.createWithPreprocess, Rc = qt.create, Mc = () => ms().optional(), Lc = () => hs().optional(), Fc = () => gs().optional(), Dc = {
  string: (t) => we.create({ ...t, coerce: !0 }),
  number: (t) => Ve.create({ ...t, coerce: !0 }),
  boolean: (t) => Rt.create({
    ...t,
    coerce: !0
  }),
  bigint: (t) => We.create({ ...t, coerce: !0 }),
  date: (t) => st.create({ ...t, coerce: !0 })
}, jc = A;
var V = /* @__PURE__ */ Object.freeze({
  __proto__: null,
  defaultErrorMap: $t,
  setErrorMap: Bi,
  getErrorMap: dr,
  makeIssue: fr,
  EMPTY_PATH: Gi,
  addIssueToContext: x,
  ParseStatus: Y,
  INVALID: A,
  DIRTY: us,
  OK: re,
  isAborted: Yr,
  isDirty: Xr,
  isValid: Ot,
  isAsync: pr,
  get util() {
    return R;
  },
  get objectUtil() {
    return Jr;
  },
  ZodParsedType: b,
  getParsedType: Ze,
  ZodType: $,
  ZodString: we,
  ZodNumber: Ve,
  ZodBigInt: We,
  ZodBoolean: Rt,
  ZodDate: st,
  ZodSymbol: mr,
  ZodUndefined: Mt,
  ZodNull: Lt,
  ZodAny: _t,
  ZodUnknown: rt,
  ZodNever: Fe,
  ZodVoid: hr,
  ZodArray: Se,
  ZodObject: j,
  ZodUnion: Ft,
  ZodDiscriminatedUnion: Nr,
  ZodIntersection: Dt,
  ZodTuple: Ne,
  ZodRecord: jt,
  ZodMap: gr,
  ZodSet: at,
  ZodFunction: ht,
  ZodLazy: Ut,
  ZodLiteral: Zt,
  ZodEnum: qe,
  ZodNativeEnum: zt,
  ZodPromise: bt,
  ZodEffects: Ee,
  ZodTransformer: Ee,
  ZodOptional: Me,
  ZodNullable: it,
  ZodDefault: Bt,
  ZodCatch: yr,
  ZodNaN: vr,
  BRAND: rc,
  ZodBranded: fs,
  ZodPipeline: qt,
  ZodReadonly: _r,
  custom: ps,
  Schema: $,
  ZodSchema: $,
  late: nc,
  get ZodFirstPartyTypeKind() {
    return P;
  },
  coerce: Dc,
  any: dc,
  array: hc,
  bigint: ac,
  boolean: gs,
  date: ic,
  discriminatedUnion: _c,
  effect: Qn,
  enum: Ic,
  function: Pc,
  instanceof: oc,
  intersection: bc,
  lazy: kc,
  literal: Tc,
  map: Sc,
  nan: sc,
  nativeEnum: Ac,
  never: pc,
  null: uc,
  nullable: $c,
  number: hs,
  object: gc,
  oboolean: Fc,
  onumber: Lc,
  optional: Nc,
  ostring: Mc,
  pipeline: Rc,
  preprocess: Oc,
  promise: Cc,
  record: xc,
  set: Ec,
  strictObject: yc,
  string: ms,
  symbol: cc,
  transformer: Qn,
  tuple: wc,
  undefined: lc,
  union: vc,
  unknown: fc,
  void: mc,
  NEVER: jc,
  ZodIssueCode: g,
  quotelessJson: zi,
  ZodError: xe
});
const Uc = V.object({
  name: V.string(),
  code: V.string().url(),
  permissions: V.array(
    V.enum([
      "page:read",
      "page:write",
      "file:read",
      "file:write",
      "selection:read"
    ])
  )
});
function ys(t) {
  return fetch(t).then((e) => e.json()).then((e) => {
    if (!Uc.safeParse(e).success)
      throw new Error("Invalid plugin manifest");
    return e;
  }).catch((e) => {
    throw console.error(e), e;
  });
}
function Zc(t) {
  return fetch(t).then((e) => e.text());
}
async function zc(t) {
  const e = await ys(t.manifest), r = await Zc(e.code);
  return {
    manifest: e,
    code: r
  };
}
function wn(t, e) {
  t.setAttribute("data-theme", e);
}
function Bc(t, e, r, n) {
  const a = document.createElement("plugin-modal");
  return wn(a, r), a.setAttribute("title", t), a.setAttribute("iframe-src", e), a.setAttribute("width", String(n.width || 300)), a.setAttribute("height", String(n.height || 400)), document.body.appendChild(a), a;
}
const Gc = V.object({
  width: V.number().positive(),
  height: V.number().positive()
}), Hc = V.function().args(V.string(), V.string(), V.enum(["dark", "light"]), Gc).implement((t, e, r, n) => Bc(t, e, r, n)), en = [
  "pagechange",
  "filechange",
  "selectionchange",
  "themechange"
];
let tn = [], ne = null;
const It = /* @__PURE__ */ new Map();
window.addEventListener("message", (t) => {
  for (const e of tn)
    e(t.data);
});
function Vc(t, e) {
  t === "themechange" && ne && wn(ne, e), (It.get(t) || []).forEach((n) => n(e));
}
function Wc(t, e) {
  const r = () => {
    ne == null || ne.removeEventListener("close", r), ne && ne.remove(), tn = [], ne = null;
  }, n = (s) => {
    if (!e.permissions.includes(s))
      throw new Error(`Permission ${s} is not granted`);
  };
  return {
    ui: {
      open: (s, i, c) => {
        const u = t.getTheme();
        ne = Hc(s, i, u, c), wn(ne, u), ne.addEventListener("close", r, {
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
        V.function().parse(s), tn.push(s);
      }
    },
    log: console.log,
    setTimeout: V.function().args(V.function(), V.number()).implement((s, i) => {
      setTimeout(s, i);
    }),
    closePlugin: r,
    on(s, i) {
      V.enum(en).parse(s), V.function().parse(i), s === "pagechange" ? n("page:read") : s === "filechange" ? n("file:read") : s === "selectionchange" && n("selection:read");
      const c = It.get(s) || [];
      c.push(i), It.set(s, c);
    },
    off(s, i) {
      V.enum(en).parse(s), V.function().parse(i);
      const c = It.get(s) || [];
      It.set(
        s,
        c.filter((u) => u !== i)
      );
    },
    // Penpot State API
    getFile() {
      return n("file:read"), t.getFile();
    },
    getCurrentPage() {
      return n("page:read"), t.getCurrentPage();
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
    fetch
  };
}
let eo = !1, tr, rn = null;
function qc(t) {
  rn = t;
}
const vs = async function(t) {
  const { code: e, manifest: r } = await zc(t);
  try {
    eo || (eo = !0, hardenIntrinsics()), tr && tr.closePlugin(), rn ? (tr = Wc(rn, r), new Compartment({
      penpot: harden(tr)
    }).evaluate(e)) : console.error("Cannot find Penpot Context");
  } catch (n) {
    console.error(n);
  }
}, Kc = `
<svg width="16"  height="16"xmlns="http://www.w3.org/2000/svg" fill="none"><g class="fills"><rect rx="0" ry="0" width="16" height="16" class="frame-background"/></g><g class="frame-children"><path d="M11.997 3.997 8 8l-3.997 4.003m-.006-8L8 8l4.003 3.997" class="fills"/><g class="strokes"><path d="M11.997 3.997 8 8l-3.997 4.003m-.006-8L8 8l4.003 3.997" style="fill: none; stroke-width: 1; stroke: rgb(143, 157, 163); stroke-opacity: 1; stroke-linecap: round;" class="stroke-shape"/></g></g></svg>`, to = (t) => {
  t.target.tagName === "INSTALLER-MODAL" && t.stopImmediatePropagation();
};
class Jc extends HTMLElement {
  constructor() {
    super(), this.dialog = null, this.attachShadow({ mode: "open" });
  }
  createPlugin(e, r) {
    var c, u;
    const n = document.createElement("li");
    n.classList.add("plugin"), n.textContent = e;
    const a = document.createElement("div");
    a.classList.add("actions");
    const s = document.createElement("button");
    s.classList.add("button"), s.textContent = "Open", s.type = "button", s.addEventListener("click", () => {
      this.closeModal(), vs({
        manifest: r
      });
    }), a.appendChild(s);
    const i = document.createElement("button");
    i.classList.add("button", "remove"), i.textContent = "Remove", i.type = "button", i.addEventListener("click", () => {
      n.remove();
      const d = this.getPlugins().filter((f) => f.url !== r);
      this.savePlugins(d);
    }), a.appendChild(i), n.appendChild(a), (u = (c = this.dialog) == null ? void 0 : c.querySelector(".plugins-list")) == null || u.prepend(n);
  }
  loadPluginList() {
    const e = this.getPlugins();
    for (const r of e)
      this.createPlugin(r.name, r.url);
  }
  getPlugins() {
    const e = localStorage.getItem("plugins");
    return e ? JSON.parse(e) : [];
  }
  savePlugins(e) {
    localStorage.setItem("plugins", JSON.stringify(e));
  }
  submitNewPlugin(e) {
    e.preventDefault();
    const n = e.target.querySelector("input");
    if (!n)
      return;
    const a = n.value;
    n.value = "", ys(a).then((s) => {
      if (this.createPlugin(s.name, a), !localStorage.getItem("plugins"))
        localStorage.setItem(
          "plugins",
          JSON.stringify([{ name: s.name, url: a }])
        );
      else {
        const c = this.getPlugins();
        c.push({ name: s.name, url: a }), this.savePlugins(c);
      }
      this.error(!1);
    }).catch((s) => {
      console.error(s), this.error(!0);
    });
  }
  error(e) {
    var r, n;
    (n = (r = this.dialog) == null ? void 0 : r.querySelector(".error")) == null || n.classList.toggle("show", e);
  }
  connectedCallback() {
    var r;
    if (!this.shadowRoot)
      throw new Error("Error creating shadow root");
    this.dialog = document.createElement("dialog"), this.dialog.innerHTML = `
      <div class="header">
        <h1>Plugins</h1>
        <button type="button" class="close">${Kc}</button>
      </div>
      <form>
        <input class="input url-input" placeholder="Plugin url" autofocus type="url" />
        <button class="button" type="submit">Install</button>
      </form>
      <div class="error">
        Error instaling plugin
      </div>

      <ul class="plugins-list"></ul>
    `, (r = this.dialog.querySelector(".close")) == null || r.addEventListener("click", () => {
      this.closeModal();
    }), this.shadowRoot.appendChild(this.dialog), this.dialog.addEventListener("submit", (n) => {
      this.submitNewPlugin(n);
    }), this.loadPluginList();
    const e = document.createElement("style");
    e.textContent = `
    * {
      font-family worksans, sans-serif
    }

    ::backdrop {
      background-color: rgba(0, 0, 0, 0.8);
    }

    dialog {
      border: 0;
      width: 700px;
      height: 500px;
      padding: 20px;
      background-color: white;
      border-radius: 10px;
      flex-direction: column;
      display: none;
    }

    dialog[open] {
      display: flex;
    }

    .header {
      display: flex;
      justify-content: space-between;
    }

    h1 {
      margin: 0;
      margin-block-end: 10px;
    }

    ul {
      padding: 0;
    }

    li {
      list-style: none;
    }

    .input {
      display: flex;
      border: 1px solid;
      border-radius: calc( 0.25rem * 2);
      font-size: 12px;
      font-weight: 400;
      line-height: 1.4;
      outline: none;
      padding-block: calc( 0.25rem * 2);
      padding-inline: calc( 0.25rem * 2);
      background-color: #f3f4f6;
      border-color: #f3f4f6;
      color: #000;

      &:hover {
        background-color: #eef0f2;
        border-color: #eef0f2;
      }

      &:focus {
        background-color: #ffffff
        border-color: ##6911d4;
      }
    }

    button {
      background: transparent;
      border: 0;
      cursor: pointer;
    }

    .button {
      border: 1px solid transparent;
      font-weight: 500;
      font-size: 12px;
      border-radius: 8px;
      line-height: 1.2;
      padding: 8px 24px 8px 24px;
      text-transform: uppercase;
      background-color: #7EFFF5;
      border: 1px solid 7EFFF5;
      outline: 2px solid transparent;

      &:hover:not(:disabled) {
          cursor: pointer;
      }

      &:focus-visible {
          outline: none;
      }
    }

    .remove {
      background-color: #ff3277;
      border: 1px solid #ff3277;
      outline: 2px solid transparent;
    }

    form {
      display: flex;
      gap: 10px;
      margin-block-end: 20px;
    }

    .url-input {
      inline-size: 400px;
    }

    .plugins-list {
      display: flex;
      flex-direction: column;
      gap: 10px;
    }

    .plugin {
      display: flex;
      justify-content: space-between;
    }

    .actions {
      display: flex;
      gap: 10px;
    }

    .error {
      display: none;
      color: red;

      &.show {
        display: block;
      }
    }
    `, this.shadowRoot.appendChild(e);
  }
  closeModal() {
    var e, r;
    (r = (e = this.shadowRoot) == null ? void 0 : e.querySelector("dialog")) == null || r.close(), window.removeEventListener("paste", to, !0);
  }
  openModal() {
    var e, r;
    (r = (e = this.shadowRoot) == null ? void 0 : e.querySelector("dialog")) == null || r.showModal(), window.addEventListener("paste", to, !0);
  }
}
function Yc() {
  customElements.define("installer-modal", Jc);
  const t = document.createElement("installer-modal");
  document.body.appendChild(t), document.addEventListener("keydown", (e) => {
    var r;
    e.key.toUpperCase() === "I" && e.ctrlKey && ((r = document.querySelector("installer-modal")) == null || r.openModal());
  });
}
console.log("Loading plugin system");
repairIntrinsics({
  evalTaming: "unsafeEval"
});
globalThis.initPluginsRuntime = (t) => {
  if (t) {
    console.log("Initialize context"), globalThis.ɵcontext = t, globalThis.ɵloadPlugin = vs, Yc(), qc(t);
    for (const e of en)
      t.addListener(e, Vc.bind(null, e));
  }
};
//# sourceMappingURL=index.mjs.map
