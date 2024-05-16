var An = (t, e, r) => {
  if (!e.has(t))
    throw TypeError("Cannot " + r);
};
var Re = (t, e, r) => (An(t, e, "read from private field"), r ? r.call(t) : e.get(t)), Mr = (t, e, r) => {
  if (e.has(t))
    throw TypeError("Cannot add the same private member more than once");
  e instanceof WeakSet ? e.add(t) : e.set(t, r);
}, Fr = (t, e, r, n) => (An(t, e, "write to private field"), n ? n.call(t, r) : e.set(t, r), r);
const E = globalThis, {
  Array: Ps,
  Date: ks,
  FinalizationRegistry: vt,
  Float32Array: Ts,
  JSON: Is,
  Map: Ae,
  Math: As,
  Number: io,
  Object: ln,
  Promise: Cs,
  Proxy: Sr,
  Reflect: Ns,
  RegExp: Be,
  Set: St,
  String: ie,
  Symbol: Nt,
  WeakMap: ke,
  WeakSet: Et
} = globalThis, {
  // The feral Error constructor is safe for internal use, but must not be
  // revealed to post-lockdown code in any compartment including the start
  // compartment since in V8 at least it bears stack inspection capabilities.
  Error: le,
  RangeError: $s,
  ReferenceError: rt,
  SyntaxError: Wt,
  TypeError: v
} = globalThis, {
  assign: Er,
  create: H,
  defineProperties: F,
  entries: te,
  freeze: y,
  getOwnPropertyDescriptor: ue,
  getOwnPropertyDescriptors: Je,
  getOwnPropertyNames: Ot,
  getPrototypeOf: B,
  is: Pr,
  isFrozen: sl,
  isSealed: al,
  isExtensible: il,
  keys: co,
  prototype: lo,
  seal: cl,
  preventExtensions: Os,
  setPrototypeOf: uo,
  values: fo,
  fromEntries: Pt
} = ln, {
  species: Cn,
  toStringTag: He,
  iterator: qt,
  matchAll: po,
  unscopables: Rs,
  keyFor: Ls,
  for: ll
} = Nt, { isInteger: Ms } = io, { stringify: mo } = Is, { defineProperty: Fs } = ln, M = (t, e, r) => {
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
  construct: cr,
  get: Ds,
  getOwnPropertyDescriptor: Us,
  has: ho,
  isExtensible: js,
  ownKeys: st,
  preventExtensions: Zs,
  set: go
} = Ns, { isArray: gt, prototype: Te } = Ps, { prototype: kt } = Ae, { prototype: kr } = RegExp, { prototype: Kt } = St, { prototype: Oe } = ie, { prototype: Tr } = ke, { prototype: yo } = Et, { prototype: un } = Function, { prototype: vo } = Cs, zs = B(Uint8Array.prototype), { bind: Nn } = un, k = Nn.bind(Nn.call), se = k(lo.hasOwnProperty), Ve = k(Te.filter), nt = k(Te.forEach), Ir = k(Te.includes), Tt = k(Te.join), de = (
  /** @type {any} */
  k(Te.map)
), Br = k(Te.pop), ae = k(Te.push), Gs = k(Te.slice), Bs = k(Te.some), _o = k(Te.sort), Hs = k(Te[qt]), Ce = k(kt.set), De = k(kt.get), Ar = k(kt.has), Vs = k(kt.delete), Ws = k(kt.entries), qs = k(kt[qt]), Cr = k(Kt.add);
k(Kt.delete);
const $n = k(Kt.forEach), dn = k(Kt.has), Ks = k(Kt[qt]), fn = k(kr.test), pn = k(kr.exec), Ys = k(kr[po]), bo = k(Oe.endsWith), Js = k(Oe.includes), Xs = k(Oe.indexOf);
k(Oe.match);
const lr = (
  /** @type {any} */
  k(Oe.replace)
), Qs = k(Oe.search), mn = k(Oe.slice), wo = k(Oe.split), xo = k(Oe.startsWith), ea = k(Oe[qt]), ta = k(Tr.delete), L = k(Tr.get), hn = k(Tr.has), ee = k(Tr.set), Nr = k(yo.add), Yt = k(yo.has), ra = k(un.toString), na = k(vo.catch), gn = (
  /** @type {any} */
  k(vo.then)
), oa = vt && k(vt.prototype.register);
vt && k(vt.prototype.unregister);
const yn = y(H(null)), We = (t) => ln(t) === t, vn = (t) => t instanceof le, So = eval, ye = Function, sa = () => {
  throw v('Cannot eval with evalTaming set to "noEval" (SES_NO_EVAL)');
};
function aa() {
  return this;
}
if (aa())
  throw v("SES failed to initialize, sloppy mode (SES_NO_SLOPPY)");
const { freeze: et } = Object, { apply: ia } = Reflect, _n = (t) => (e, ...r) => ia(t, e, r), ca = _n(Array.prototype.push), On = _n(Array.prototype.includes), la = _n(String.prototype.split), Qe = JSON.stringify, Qt = (t, ...e) => {
  let r = t[0];
  for (let n = 0; n < e.length; n += 1)
    r = `${r}${e[n]}${t[n + 1]}`;
  throw Error(r);
}, Eo = (t, e = !1) => {
  const r = [], n = (c, u, l = void 0) => {
    typeof c == "string" || Qt`Environment option name ${Qe(c)} must be a string.`, typeof u == "string" || Qt`Environment option default setting ${Qe(
      u
    )} must be a string.`;
    let d = u;
    const f = t.process || void 0, m = typeof f == "object" && f.env || void 0;
    if (typeof m == "object" && c in m) {
      e || ca(r, c);
      const p = m[c];
      typeof p == "string" || Qt`Environment option named ${Qe(
        c
      )}, if present, must have a corresponding string value, got ${Qe(
        p
      )}`, d = p;
    }
    return l === void 0 || d === u || On(l, d) || Qt`Unrecognized ${Qe(c)} value ${Qe(
      d
    )}. Expected one of ${Qe([u, ...l])}`, d;
  };
  et(n);
  const a = (c) => {
    const u = n(c, "");
    return et(u === "" ? [] : la(u, ","));
  };
  et(a);
  const o = (c, u) => On(a(c), u), i = () => et([...r]);
  return et(i), et({
    getEnvironmentOption: n,
    getEnvironmentOptionsList: a,
    environmentOptionsListHas: o,
    getCapturedEnvironmentOptionNames: i
  });
};
et(Eo);
const {
  getEnvironmentOption: me,
  getEnvironmentOptionsList: ul,
  environmentOptionsListHas: dl
} = Eo(globalThis, !0), ur = (t) => (t = `${t}`, t.length >= 1 && Js("aeiouAEIOU", t[0]) ? `an ${t}` : `a ${t}`);
y(ur);
const Po = (t, e = void 0) => {
  const r = new St(), n = (a, o) => {
    switch (typeof o) {
      case "object": {
        if (o === null)
          return null;
        if (dn(r, o))
          return "[Seen]";
        if (Cr(r, o), vn(o))
          return `[${o.name}: ${o.message}]`;
        if (He in o)
          return `[${o[He]}]`;
        if (gt(o))
          return o;
        const i = co(o);
        if (i.length < 2)
          return o;
        let c = !0;
        for (let l = 1; l < i.length; l += 1)
          if (i[l - 1] >= i[l]) {
            c = !1;
            break;
          }
        if (c)
          return o;
        _o(i);
        const u = de(i, (l) => [l, o[l]]);
        return Pt(u);
      }
      case "function":
        return `[Function ${o.name || "<anon>"}]`;
      case "string":
        return xo(o, "[") ? `[${o}]` : o;
      case "undefined":
      case "symbol":
        return `[${ie(o)}]`;
      case "bigint":
        return `[${o}n]`;
      case "number":
        return Pr(o, NaN) ? "[NaN]" : o === 1 / 0 ? "[Infinity]" : o === -1 / 0 ? "[-Infinity]" : o;
      default:
        return o;
    }
  };
  try {
    return mo(t, n, e);
  } catch {
    return "[Something that failed to stringify]";
  }
};
y(Po);
const { isSafeInteger: ua } = Number, { freeze: mt } = Object, { toStringTag: da } = Symbol, Rn = (t) => {
  const r = {
    next: void 0,
    prev: void 0,
    data: t
  };
  return r.next = r, r.prev = r, r;
}, Ln = (t, e) => {
  if (t === e)
    throw TypeError("Cannot splice a cell into itself");
  if (e.next !== e || e.prev !== e)
    throw TypeError("Expected self-linked cell");
  const r = e, n = t.next;
  return r.prev = t, r.next = n, t.next = r, n.prev = r, r;
}, Dr = (t) => {
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
      return Dr(f), Ln(n, f), f;
  }, o = (d) => a(d) !== void 0;
  mt(o);
  const i = (d) => {
    const f = a(d);
    return f && f.data && f.data.get(d);
  };
  mt(i);
  const c = (d, f) => {
    if (t < 1)
      return l;
    let m = a(d);
    if (m === void 0 && (m = Rn(void 0), Ln(n, m)), !m.data)
      for (r += 1, m.data = /* @__PURE__ */ new WeakMap(), e.set(d, m); r > t; ) {
        const p = n.prev;
        Dr(p), p.data = void 0, r -= 1;
      }
    return m.data.set(d, f), l;
  };
  mt(c);
  const u = (d) => {
    const f = e.get(d);
    return f === void 0 || (Dr(f), e.delete(d), f.data === void 0) ? !1 : (f.data = void 0, r -= 1, !0);
  };
  mt(u);
  const l = mt({
    has: o,
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
const { freeze: sr } = Object, { isSafeInteger: fa } = Number, pa = 1e3, ma = 100, To = (t = pa, e = ma) => {
  if (!fa(e) || e < 1)
    throw TypeError(
      "argsPerErrorBudget must be a safe positive integer number"
    );
  const r = ko(t), n = (o, i) => {
    const c = r.get(o);
    c !== void 0 ? (c.length >= e && c.shift(), c.push(i)) : r.set(o, [i]);
  };
  sr(n);
  const a = (o) => {
    const i = r.get(o);
    return r.delete(o), i;
  };
  return sr(a), sr({
    addLogArgs: n,
    takeLogArgsArray: a
  });
};
sr(To);
const _t = new ke(), at = (t, e = void 0) => {
  const r = y({
    toString: y(() => Po(t, e))
  });
  return ee(_t, r, t), r;
};
y(at);
const ha = y(/^[\w:-]( ?[\w:-])*$/), Hr = (t, e = void 0) => {
  if (typeof t != "string" || !fn(ha, t))
    return at(t, e);
  const r = y({
    toString: y(() => t)
  });
  return ee(_t, r, t), r;
};
y(Hr);
const $r = new ke(), Io = ({ template: t, args: e }) => {
  const r = [t[0]];
  for (let n = 0; n < e.length; n += 1) {
    const a = e[n];
    let o;
    hn(_t, a) ? o = `${a}` : vn(a) ? o = `(${ur(a.name)})` : o = `(${ur(typeof a)})`, ae(r, o, t[n + 1]);
  }
  return Tt(r, "");
}, Ao = y({
  toString() {
    const t = L($r, this);
    return t === void 0 ? "[Not a DetailsToken]" : Io(t);
  }
});
y(Ao.toString);
const bt = (t, ...e) => {
  const r = y({ __proto__: Ao });
  return ee($r, r, { template: t, args: e }), r;
};
y(bt);
const Co = (t, ...e) => (e = de(
  e,
  (r) => hn(_t, r) ? r : at(r)
), bt(t, ...e));
y(Co);
const No = ({ template: t, args: e }) => {
  const r = [t[0]];
  for (let n = 0; n < e.length; n += 1) {
    let a = e[n];
    hn(_t, a) && (a = L(_t, a));
    const o = lr(Br(r) || "", / $/, "");
    o !== "" && ae(r, o);
    const i = lr(t[n + 1], /^ /, "");
    ae(r, a, i);
  }
  return r[r.length - 1] === "" && Br(r), r;
}, ar = new ke();
let Vr = 0;
const Mn = new ke(), $o = (t, e = t.name) => {
  let r = L(Mn, t);
  return r !== void 0 || (Vr += 1, r = `${e}#${Vr}`, ee(Mn, t, r)), r;
}, Wr = (t = bt`Assert failed`, e = E.Error, { errorName: r = void 0 } = {}) => {
  typeof t == "string" && (t = bt([t]));
  const n = L($r, t);
  if (n === void 0)
    throw v(`unrecognized details ${at(t)}`);
  const a = Io(n), o = new e(a);
  return ee(ar, o, No(n)), r !== void 0 && $o(o, r), o;
};
y(Wr);
const { addLogArgs: ga, takeLogArgsArray: ya } = To(), qr = new ke(), Oo = (t, e) => {
  typeof e == "string" && (e = bt([e]));
  const r = L($r, e);
  if (r === void 0)
    throw v(`unrecognized details ${at(e)}`);
  const n = No(r), a = L(qr, t);
  if (a !== void 0)
    for (const o of a)
      o(t, n);
  else
    ga(t, n);
};
y(Oo);
const va = (t) => {
  if (!("stack" in t))
    return "";
  const e = `${t.stack}`, r = Xs(e, `
`);
  return xo(e, " ") || r === -1 ? e : mn(e, r + 1);
}, Kr = {
  getStackString: E.getStackString || va,
  tagError: (t) => $o(t),
  resetErrorTagNum: () => {
    Vr = 0;
  },
  getMessageLogArgs: (t) => L(ar, t),
  takeMessageLogArgs: (t) => {
    const e = L(ar, t);
    return ta(ar, t), e;
  },
  takeNoteLogArgsArray: (t, e) => {
    const r = ya(t);
    if (e !== void 0) {
      const n = L(qr, t);
      n ? ae(n, e) : ee(qr, t, [e]);
    }
    return r || [];
  }
};
y(Kr);
const Or = (t = void 0, e = !1) => {
  const r = e ? Co : bt, n = r`Check failed`, a = (f = n, m = E.Error) => {
    const p = Wr(f, m);
    throw t !== void 0 && t(p), p;
  };
  y(a);
  const o = (f, ...m) => a(r(f, ...m));
  function i(f, m = void 0, p = void 0) {
    f || a(m, p);
  }
  const c = (f, m, p = void 0, h = void 0) => {
    Pr(f, m) || a(
      p || r`Expected ${f} is same as ${m}`,
      h || $s
    );
  };
  y(c);
  const u = (f, m, p) => {
    if (typeof f !== m) {
      if (typeof m == "string" || o`${at(m)} must be a string`, p === void 0) {
        const h = ur(m);
        p = r`${f} must be ${Hr(h)}`;
      }
      a(p, v);
    }
  };
  y(u);
  const d = Er(i, {
    error: Wr,
    fail: a,
    equal: c,
    typeof: u,
    string: (f, m = void 0) => u(f, "string", m),
    note: Oo,
    details: r,
    Fail: o,
    quote: at,
    bare: Hr,
    makeAssert: Or
  });
  return y(d);
};
y(Or);
const Z = Or(), Ro = ue(
  zs,
  He
);
Z(Ro);
const Lo = Ro.get;
Z(Lo);
const _a = (t) => oe(Lo, t, []) !== void 0, ba = (t) => {
  const e = +ie(t);
  return Ms(e) && ie(e) === t;
}, wa = (t) => {
  Os(t), nt(st(t), (e) => {
    const r = ue(t, e);
    Z(r), ba(e) || M(t, e, {
      ...r,
      writable: !1,
      configurable: !1
    });
  });
}, xa = () => {
  if (typeof E.harden == "function")
    return E.harden;
  const t = new Et(), { harden: e } = {
    /**
     * @template T
     * @param {T} root
     * @returns {T}
     */
    harden(r) {
      const n = new St(), a = new ke();
      function o(d, f = void 0) {
        if (!We(d))
          return;
        const m = typeof d;
        if (m !== "object" && m !== "function")
          throw v(`Unexpected typeof: ${m}`);
        Yt(t, d) || dn(n, d) || (Cr(n, d), ee(a, d, f));
      }
      function i(d) {
        _a(d) ? wa(d) : y(d);
        const f = L(a, d) || "unknown", m = Je(d), p = B(d);
        o(p, `${f}.__proto__`), nt(st(m), (h) => {
          const _ = `${f}.${ie(h)}`, w = m[
            /** @type {string} */
            h
          ];
          se(w, "value") ? o(w.value, `${_}`) : (o(w.get, `${_}(get)`), o(w.set, `${_}(set)`));
        });
      }
      function c() {
        $n(n, i);
      }
      function u(d) {
        Nr(t, d);
      }
      function l() {
        $n(n, u);
      }
      return o(r), c(), l(), r;
    }
  };
  return e;
}, Mo = {
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
}, Sa = [
  EvalError,
  RangeError,
  ReferenceError,
  SyntaxError,
  TypeError,
  URIError
], Yr = {
  "[[Proto]]": "%FunctionPrototype%",
  length: "number",
  name: "string"
  // Do not specify "prototype" here, since only Function instances that can
  // be used as a constructor have a prototype property. For constructors,
  // since prototype properties are instance-specific, we define it there.
}, Ea = {
  // This property is not mentioned in ECMA 262, but is present in V8 and
  // necessary for lockdown to succeed.
  "[[Proto]]": "%AsyncFunctionPrototype%"
}, s = Yr, Dn = Ea, O = {
  get: s,
  set: "undefined"
}, Ie = {
  get: s,
  set: s
}, Un = (t) => t === O || t === Ie;
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
}, dr = {
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
    description: O,
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
    ...jn,
    // `%InitialMath%.random()` has the standard unsafe behavior
    random: s
  },
  "%SharedMath%": {
    ...jn,
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
    exec: s,
    dotAll: O,
    flags: O,
    global: O,
    hasIndices: O,
    ignoreCase: O,
    "@@match": s,
    "@@matchAll": s,
    multiline: O,
    "@@replace": s,
    "@@search": s,
    source: O,
    "@@split": s,
    sticky: O,
    test: s,
    toString: s,
    unicode: O,
    unicodeSets: O,
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
    "@@species": O,
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
    "@@species": O
  },
  "%TypedArrayPrototype%": {
    at: s,
    buffer: O,
    byteLength: O,
    byteOffset: O,
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
    length: O,
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
    "@@toStringTag": O,
    // See https://github.com/tc39/proposal-array-find-from-last
    findLast: s,
    findLastIndex: s,
    // https://github.com/tc39/proposal-change-array-by-copy
    toReversed: s,
    toSorted: s,
    with: s
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
    size: O,
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
    "@@species": O,
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
    size: O,
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
    "@@species": O,
    // See https://github.com/Moddable-OpenSource/moddable/issues/523
    fromString: !1,
    // See https://github.com/Moddable-OpenSource/moddable/issues/523
    fromBigInt: !1
  },
  "%ArrayBufferPrototype%": {
    byteLength: O,
    constructor: "ArrayBuffer",
    slice: s,
    "@@toStringTag": "string",
    // See https://github.com/Moddable-OpenSource/moddable/issues/523
    concat: !1,
    // See https://github.com/tc39/proposal-resizablearraybuffer
    transfer: s,
    resize: s,
    resizable: O,
    maxByteLength: O,
    // https://github.com/tc39/proposal-arraybuffer-transfer
    transferToFixedLength: s,
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
    // To transition from `false` to `fn` once we also have `AggregateError`
    // TODO https://github.com/Agoric/SES-shim/issues/550
    any: !1,
    // ES2021
    prototype: "%PromisePrototype%",
    race: s,
    reject: s,
    resolve: s,
    // https://github.com/tc39/proposal-promise-with-resolvers
    withResolvers: s,
    "@@species": O
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
    globalThis: O,
    name: O,
    import: Dn,
    load: Dn,
    importNow: s,
    module: s,
    "@@toStringTag": "string"
  },
  lockdown: s,
  harden: { ...s, isFake: "boolean" },
  "%InitialGetStackString%": s
}, Pa = (t) => typeof t == "function";
function ka(t, e, r) {
  if (se(t, e)) {
    const n = ue(t, e);
    if (!n || !Pr(n.value, r.value) || n.get !== r.get || n.set !== r.set || n.writable !== r.writable || n.enumerable !== r.enumerable || n.configurable !== r.configurable)
      throw v(`Conflicting definitions of ${e}`);
  }
  M(t, e, r);
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
  const t = H(null);
  let e;
  const r = (c) => {
    Ta(t, Je(c));
  };
  y(r);
  const n = () => {
    for (const [c, u] of te(t)) {
      if (!We(u) || !se(u, "prototype"))
        continue;
      const l = dr[c];
      if (typeof l != "object")
        throw v(`Expected permit object at whitelist.${c}`);
      const d = l.prototype;
      if (!d)
        throw v(`${c}.prototype property not whitelisted`);
      if (typeof d != "string" || !se(dr, d))
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
  const a = () => (y(t), e = new Et(Ve(fo(t), Pa)), t);
  y(a);
  const o = (c) => {
    if (!e)
      throw v(
        "isPseudoNative can only be called after finalIntrinsics"
      );
    return Yt(e, c);
  };
  y(o);
  const i = {
    addIntrinsics: r,
    completePrototypes: n,
    finalIntrinsics: a,
    isPseudoNative: o
  };
  return y(i), r(Mo), r(Uo(E, Fo)), i;
}, Ia = (t) => {
  const { addIntrinsics: e, finalIntrinsics: r } = jo();
  return e(Uo(t, Do)), r();
};
function Aa(t, e) {
  let r = !1;
  const n = (m, ...p) => (r || (console.groupCollapsed("Removing unpermitted intrinsics"), r = !0), console[m](...p)), a = ["undefined", "boolean", "number", "string", "symbol"], o = new Ae(
    Nt ? de(
      Ve(
        te(dr["%SharedSymbol%"]),
        ([m, p]) => p === "symbol" && typeof Nt[m] == "symbol"
      ),
      ([m]) => [Nt[m], `@@${m}`]
    ) : []
  );
  function i(m, p) {
    if (typeof p == "string")
      return p;
    const h = De(o, p);
    if (typeof p == "symbol") {
      if (h)
        return h;
      {
        const _ = Ls(p);
        return _ !== void 0 ? `RegisteredSymbol(${_})` : `Unique${ie(p)}`;
      }
    }
    throw v(`Unexpected property name type ${m} ${p}`);
  }
  function c(m, p, h) {
    if (!We(p))
      throw v(`Object expected: ${m}, ${p}, ${h}`);
    const _ = B(p);
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
      } else if (Ir(a, _)) {
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
    if (typeof m == "function" && se(Yr, _))
      return Yr[_];
  }
  function f(m, p, h) {
    if (p == null)
      return;
    const _ = h["[[Proto]]"];
    c(m, p, _), typeof p == "function" && e(p);
    for (const w of st(p)) {
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
    f("intrinsics", t, dr);
  } finally {
    r && console.groupEnd();
  }
}
function Ca() {
  try {
    ye.prototype.constructor("return 1");
  } catch {
    return y({});
  }
  const t = {};
  function e(r, n, a) {
    let o;
    try {
      o = (0, eval)(a);
    } catch (u) {
      if (u instanceof Wt)
        return;
      throw u;
    }
    const i = B(o), c = function() {
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
    }), c !== ye.prototype.constructor && uo(c, ye.prototype.constructor), t[n] = c;
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
function Na(t = "safe") {
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
      return new.target === void 0 ? oe(e, void 0, d) : cr(e, d, new.target);
    } : u = function(...d) {
      if (new.target === void 0)
        throw v(
          "secure mode Calling %SharedDate% constructor as a function throws"
        );
      if (d.length === 0)
        throw v(
          "secure mode Calling new %SharedDate%() with no arguments throws"
        );
      return cr(e, d, new.target);
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
  }, o = a({ powers: "original" }), i = a({ powers: "none" });
  return F(o, {
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
    "%InitialDate%": o,
    "%SharedDate%": i
  };
}
function $a(t = "safe") {
  if (t !== "safe" && t !== "unsafe")
    throw v(`unrecognized mathTaming ${t}`);
  const e = As, r = e, { random: n, ...a } = Je(e), i = H(lo, {
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
  const e = Be.prototype, r = (o = {}) => {
    const i = function(...l) {
      return new.target === void 0 ? Be(...l) : cr(Be, l, new.target);
    }, c = ue(Be, Cn);
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
    [qt]: !0
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
}, La = {
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
function Ma(t, e, r = []) {
  const n = new St(r);
  function a(l, d, f, m) {
    if ("value" in m && m.configurable) {
      const { value: p } = m, h = dn(n, f), { get: _, set: w } = ue(
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
            se(this, f) ? this[f] = I : (h && console.error(v(`Override property ${f}`)), M(this, f, {
              value: I,
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
        set: w,
        enumerable: m.enumerable,
        configurable: m.configurable
      });
    }
  }
  function o(l, d, f) {
    const m = ue(d, f);
    m && a(l, d, f, m);
  }
  function i(l, d) {
    const f = Je(d);
    f && nt(st(f), (m) => a(l, d, m, f[m]));
  }
  function c(l, d, f) {
    for (const m of st(f)) {
      const p = ue(d, m);
      if (!p || p.get || p.set)
        continue;
      const h = `${l}.${ie(m)}`, _ = f[m];
      if (_ === !0)
        o(h, d, m);
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
      u = La;
      break;
    }
    default:
      throw v(`unrecognized overrideTaming ${e}`);
  }
  c("root", t, u);
}
const { Fail: Jr, quote: fr } = Z, Fa = /^(\w*[a-z])Locale([A-Z]\w*)$/, zo = {
  // See https://tc39.es/ecma262/#sec-string.prototype.localecompare
  localeCompare(t) {
    if (this === null || this === void 0)
      throw v(
        'Cannot localeCompare with null or undefined "this" value'
      );
    const e = `${this}`, r = `${t}`;
    return e < r ? -1 : e > r ? 1 : (e === r || Jr`expected ${fr(e)} and ${fr(r)} to compare`, 0);
  },
  toString() {
    return `${this}`;
  }
}, Da = zo.localeCompare, Ua = zo.toString;
function ja(t, e = "safe") {
  if (e !== "safe" && e !== "unsafe")
    throw v(`unrecognized localeTaming ${e}`);
  if (e !== "unsafe") {
    M(ie.prototype, "localeCompare", {
      value: Da
    });
    for (const r of Ot(t)) {
      const n = t[r];
      if (We(n))
        for (const a of Ot(n)) {
          const o = pn(Fa, a);
          if (o) {
            typeof n[a] == "function" || Jr`expected ${fr(a)} to be a function`;
            const i = `${o[1]}${o[2]}`, c = n[i];
            typeof c == "function" || Jr`function ${fr(i)} not found`, M(n, a, { value: c });
          }
        }
    }
    M(io.prototype, "toLocaleString", {
      value: Ua
    });
  }
}
const Za = (t) => ({
  eval(r) {
    return typeof r != "string" ? r : t(r);
  }
}).eval, { Fail: Zn } = Z, za = (t) => {
  const e = function(n) {
    const a = `${Br(arguments) || ""}`, o = `${Tt(arguments, ",")}`;
    new ye(o, ""), new ye(a);
    const i = `(function anonymous(${o}
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
  }), B(ye) === ye.prototype || Zn`Function prototype is the same accross compartments`, B(e) === ye.prototype || Zn`Function constructor prototype is the same accross compartments`, e;
}, Ga = (t) => {
  M(
    t,
    Rs,
    y(
      Er(H(null), {
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
}, Go = (t) => {
  for (const [e, r] of te(Mo))
    M(t, e, {
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
    se(e, c) && M(t, i, {
      value: e[c],
      writable: !0,
      enumerable: !1,
      configurable: !0
    });
  for (const [i, c] of te(r))
    se(e, c) && M(t, i, {
      value: e[c],
      writable: !0,
      enumerable: !1,
      configurable: !0
    });
  const o = {
    globalThis: t
  };
  o.Compartment = y(
    n(
      n,
      e,
      a
    )
  );
  for (const [i, c] of te(o))
    M(t, i, {
      value: c,
      writable: !0,
      enumerable: !1,
      configurable: !0
    }), typeof c == "function" && a(c);
}, Xr = (t, e, r) => {
  {
    const n = y(Za(e));
    r(n), M(t, "eval", {
      value: n,
      writable: !0,
      enumerable: !1,
      configurable: !0
    });
  }
  {
    const n = y(za(e));
    r(n), M(t, "Function", {
      value: n,
      writable: !0,
      enumerable: !1,
      configurable: !0
    });
  }
}, { Fail: Ba, quote: Ho } = Z, Vo = new Sr(
  yn,
  y({
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
}, Wo = y(
  H(
    Vo,
    Je(Ha)
  )
), Va = new Sr(
  yn,
  Wo
), qo = (t) => {
  const e = {
    // inherit scopeTerminator behavior
    ...Wo,
    // Redirect set properties to the globalObject.
    set(a, o, i) {
      return go(t, o, i);
    },
    // Always claim to have a potential property in order to be the recipient of a set
    has(a, o) {
      return !0;
    }
  }, r = y(
    H(
      Vo,
      Je(e)
    )
  );
  return new Sr(
    yn,
    r
  );
};
y(qo);
const { Fail: Wa } = Z, qa = () => {
  const t = H(null), e = y({
    eval: {
      get() {
        return delete t.eval, So;
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
  throw Wt(
    `Possible HTML comment rejected at ${r}:${e}. (SES_HTML_COMMENT_REJECTED)`
  );
}, Jo = (t) => lr(t, Ko, (r) => r[0] === "<" ? "< ! --" : "-- >"), Xo = new Be(
  "(^|[^.]|\\.\\.\\.)\\bimport(\\s*(?:\\(|/[/*]))",
  "g"
), Qo = (t) => {
  const e = wn(t, Xo);
  if (e < 0)
    return t;
  const r = bn(t);
  throw Wt(
    `Possible import expression rejected at ${r}:${e}. (SES_IMPORT_REJECTED)`
  );
}, es = (t) => lr(t, Xo, (r, n, a) => `${n}__import__${a}`), Ya = new Be(
  "(^|[^.])\\beval(\\s*\\()",
  "g"
), ts = (t) => {
  const e = wn(t, Ya);
  if (e < 0)
    return t;
  const r = bn(t);
  throw Wt(
    `Possible direct eval expression rejected at ${r}:${e}. (SES_EVAL_REJECTED)`
  );
}, rs = (t) => (t = Yo(t), t = Qo(t), t), ns = (t, e) => {
  for (const r of e)
    t = r(t);
  return t;
};
y({
  rejectHtmlComments: y(Yo),
  evadeHtmlCommentTest: y(Jo),
  rejectImportExpressions: y(Qo),
  evadeImportExpressionTest: y(es),
  rejectSomeDirectEvalExpressions: y(ts),
  mandatoryTransforms: y(rs),
  applyTransforms: y(ns)
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
], Xa = /^[a-zA-Z_$][\w$]*$/, Gn = (t) => t !== "eval" && !Ir(Ja, t) && fn(Xa, t);
function Bn(t, e) {
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
const Qa = (t, e = {}) => {
  const r = Ot(t), n = Ot(e), a = Ve(
    n,
    (i) => Gn(i) && Bn(e, i)
  );
  return {
    globalObjectConstants: Ve(
      r,
      (i) => (
        // Can't define a constant: it would prevent a
        // lookup on the endowments.
        !Ir(n, i) && Gn(i) && Bn(t, i)
      )
    ),
    moduleLexicalConstants: a
  };
};
function Hn(t, e) {
  return t.length === 0 ? "" : `const {${Tt(t, ",")}} = this.${e};`;
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
  ), o = ye(`
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
  return oe(o, t, []);
}, { Fail: ti } = Z, xn = ({
  globalObject: t,
  moduleLexicals: e = {},
  globalTransforms: r = [],
  sloppyGlobalsMode: n = !1
}) => {
  const a = n ? qo(t) : Va, o = qa(), { evalScope: i } = o, c = y({
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
      return o.allowNextEvalToBeUnsafe(), oe(u, t, [f]);
    } catch (_) {
      throw h = _, _;
    } finally {
      const _ = "eval" in i;
      delete i.eval, _ && (o.revoked = { err: h }, ti`handler did not reset allowNextEvalToBeUnsafe ${h}`);
    }
  } };
}, ri = ") { [native code] }";
let Ur;
const os = () => {
  if (Ur === void 0) {
    const t = new Et();
    M(un, "toString", {
      value: {
        toString() {
          const r = ra(this);
          return bo(r, ri) || !Yt(t, this) ? r : `function ${this.name}() { [native code] }`;
        }
      }.toString
    }), Ur = y(
      (r) => Nr(t, r)
    );
  }
  return Ur;
};
function ni(t = "safe") {
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
    M(e, "domain", {
      value: null,
      configurable: !1,
      writable: !1,
      enumerable: !1
    });
  }
}
const ss = y([
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
]), as = y([
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
]), is = y([
  ...ss,
  ...as
]), oi = (t, { shouldResetForDebugging: e = !1 } = {}) => {
  e && t.resetErrorTagNum();
  let r = [];
  const n = Pt(
    de(is, ([i, c]) => {
      const u = (...l) => {
        ae(r, [i, ...l]);
      };
      return M(u, "name", { value: i }), [i, y(u)];
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
y(oi);
const At = {
  NOTE: "ERROR_NOTE:",
  MESSAGE: "ERROR_MESSAGE:"
};
y(At);
const cs = (t, e) => {
  if (!t)
    return;
  const { getStackString: r, tagError: n, takeMessageLogArgs: a, takeNoteLogArgsArray: o } = e, i = (w, I) => de(w, (T) => vn(T) ? (ae(I, T), `(${n(T)})`) : T), c = (w, I, N, T, D) => {
    const j = n(I), q = N === At.MESSAGE ? `${j}:` : `${j} ${N}`, K = i(T, D);
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
  }, l = new Et(), d = (w) => (I, N) => {
    const T = [];
    c(w, I, At.NOTE, N, T), u(w, T, n(I));
  }, f = (w, I) => {
    if (Yt(l, I))
      return;
    const N = n(I);
    Nr(l, I);
    const T = [], D = a(I), j = o(
      I,
      d(w)
    );
    D === void 0 ? t[w](`${N}:`, I.message) : c(
      w,
      I,
      At.MESSAGE,
      D,
      T
    );
    let q = r(I);
    typeof q == "string" && q.length >= 1 && !bo(q, `
`) && (q += `
`), t[w](q);
    for (const K of j)
      c(w, I, At.NOTE, K, T);
    u(w, T, N);
  }, m = de(ss, ([w, I]) => {
    const N = (...T) => {
      const D = [], j = i(T, D);
      t[w](...j), u(w, D);
    };
    return M(N, "name", { value: w }), [w, y(N)];
  }), p = Ve(
    as,
    ([w, I]) => w in t
  ), h = de(p, ([w, I]) => {
    const N = (...T) => {
      t[w](...T);
    };
    return M(N, "name", { value: w }), [w, y(N)];
  }), _ = Pt([...m, ...h]);
  return (
    /** @type {VirtualConsole} */
    y(_)
  );
};
y(cs);
const si = (t, e, r = void 0) => {
  const n = Ve(
    is,
    ([i, c]) => i in t
  ), a = de(n, ([i, c]) => [i, y((...l) => {
    (c === void 0 || e.canLog(c)) && t[i](...l);
  })]), o = Pt(a);
  return (
    /** @type {VirtualConsole} */
    y(o)
  );
};
y(si);
const Vn = (t) => {
  if (vt === void 0)
    return;
  let e = 0;
  const r = new Ae(), n = (d) => {
    Vs(r, d);
  }, a = new ke(), o = (d) => {
    if (Ar(r, d)) {
      const f = De(r, d);
      n(d), t(f);
    }
  }, i = new vt(o);
  return {
    rejectionHandledHandler: (d) => {
      const f = L(a, d);
      n(f);
    },
    unhandledRejectionHandler: (d, f) => {
      e += 1;
      const m = e;
      Ce(r, m, d), ee(a, f, m), oa(i, f, m, f);
    },
    processTerminationHandler: () => {
      for (const [d, f] of Ws(r))
        n(d), t(f);
    }
  };
}, jr = (t) => {
  throw v(t);
}, Wn = (t, e) => y((...r) => oe(t, e, r)), ai = (t = "safe", e = "platform", r = "report", n = void 0) => {
  t === "safe" || t === "unsafe" || jr(`unrecognized consoleTaming ${t}`);
  let a;
  n === void 0 ? a = Kr : a = {
    ...Kr,
    getStackString: n
  };
  const o = (
    /** @type {VirtualConsole} */
    // eslint-disable-next-line no-nested-ternary
    typeof E.console < "u" ? E.console : typeof E.print == "function" ? (
      // Make a good-enough console for eshost (including only functions that
      // log at a specific level with no special argument interpretation).
      // https://console.spec.whatwg.org/#logging
      ((l) => y({ debug: l, log: l, info: l, warn: l, error: l }))(
        // eslint-disable-next-line no-undef
        Wn(E.print)
      )
    ) : void 0
  );
  if (o && o.log)
    for (const l of ["warn", "error"])
      o[l] || M(o, l, {
        value: Wn(o.log, o)
      });
  const i = (
    /** @type {VirtualConsole} */
    t === "unsafe" ? o : cs(o, a)
  ), c = E.process || void 0;
  if (e !== "none" && typeof c == "object" && typeof c.on == "function") {
    let l;
    if (e === "platform" || e === "exit") {
      const { exit: d } = c;
      typeof d == "function" || jr("missing process.exit"), l = () => d(c.exitCode || -1);
    } else
      e === "abort" && (l = c.abort, typeof l == "function" || jr("missing process.abort"));
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
  const u = E.window || void 0;
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
  const r = Pt(de(ii, (n) => {
    const a = t[n];
    return [n, () => oe(a, t, [])];
  }));
  return H(r, {});
}, li = (t) => de(t, ci), ui = /\/node_modules\//, di = /^(?:node:)?internal\//, fi = /\/packages\/ses\/src\/error\/assert.js$/, pi = /\/packages\/eventual-send\/src\//, mi = [
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
}, gi = /^((?:.*[( ])?)[:/\w_-]*\/\.\.\.\/(.+)$/, yi = /^((?:.*[( ])?)[:/\w_-]*\/(packages\/.+)$/, vi = [
  gi,
  yi
], _i = (t) => {
  for (const e of vi) {
    const r = pn(e, t);
    if (r)
      return Tt(Gs(r, 1), "");
  }
  return t;
}, bi = (t, e, r, n) => {
  const a = t.captureStackTrace, o = (p) => n === "verbose" ? !0 : hi(p.getFileName()), i = (p) => {
    let h = `${p}`;
    return n === "concise" && (h = _i(h)), `
  at ${h}`;
  }, c = (p, h) => Tt(
    de(Ve(h, o), i),
    ""
  ), u = new ke(), l = {
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
      go(p, "stack", "");
    },
    // Shim of proposed special power, to reside by default only
    // in the start compartment, for getting the stack traceback
    // string associated with an error.
    // See https://tc39.es/proposal-error-stacks/
    getStackString(p) {
      let h = L(u, p);
      if (h === void 0 && (p.stack, h = L(u, p), h || (h = { stackString: "" }, ee(u, p, h))), h.stackString !== void 0)
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
  const f = new Et([d]), m = (p) => {
    if (Yt(f, p))
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
}, qn = ue(le.prototype, "stack"), Kn = qn && qn.get, wi = {
  getStackString(t) {
    return typeof Kn == "function" ? oe(Kn, t, []) : "stack" in t ? `${t.stack}` : "";
  }
};
function xi(t = "safe", e = "concise") {
  if (t !== "safe" && t !== "unsafe")
    throw v(`unrecognized errorTaming ${t}`);
  if (e !== "concise" && e !== "verbose")
    throw v(`unrecognized stackFiltering ${e}`);
  const r = le.prototype, n = typeof le.captureStackTrace == "function" ? "v8" : "unknown", { captureStackTrace: a } = le, o = (l = {}) => {
    const d = function(...m) {
      let p;
      return new.target === void 0 ? p = oe(le, this, m) : p = cr(le, m, new.target), n === "v8" && oe(a, le, [p, d]), p;
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
  }, i = o({ powers: "original" }), c = o({ powers: "none" });
  F(r, {
    constructor: { value: c }
  });
  for (const l of Sa)
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
        M(l, "stack", {
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
const { Fail: Si, details: Qr, quote: Me } = Z, ls = () => {
}, Ei = (t, e) => y({
  compartment: t,
  specifier: e
}), Pi = (t, e, r) => {
  const n = H(null);
  for (const a of t) {
    const o = e(a, r);
    n[a] = o;
  }
  return y(n);
}, Yn = (t, e, r, n, a, o, i, c, u) => {
  const { resolveHook: l, moduleRecords: d } = L(
    t,
    r
  ), f = Pi(
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
  for (const p of fo(f)) {
    const h = pr(
      t,
      e,
      r,
      p,
      o,
      i,
      c
    );
    Cr(
      o,
      gn(h, ls, (_) => {
        ae(c, _);
      })
    );
  }
  return Ce(d, n, m), m;
}, ki = async (t, e, r, n, a, o, i) => {
  const { importHook: c, moduleMap: u, moduleMapHook: l, moduleRecords: d } = L(
    t,
    r
  );
  let f = u[n];
  if (f === void 0 && l !== void 0 && (f = l(n)), typeof f == "string")
    Z.fail(
      Qr`Cannot map module ${Me(n)} to ${Me(
        f
      )} in parent compartment, not yet implemented`,
      v
    );
  else if (f !== void 0) {
    const p = L(e, f);
    p === void 0 && Z.fail(
      Qr`Cannot map module ${Me(
        n
      )} because the value is not a module exports namespace, or is from another realm`,
      rt
    );
    const h = await pr(
      t,
      e,
      p.compartment,
      p.specifier,
      a,
      o,
      i
    );
    return Ce(d, n, h), h;
  }
  if (Ar(d, n))
    return De(d, n);
  const m = await c(n);
  if ((m === null || typeof m != "object") && Si`importHook must return a promise for an object, for module ${Me(
    n
  )} in compartment ${Me(r.name)}`, m.specifier !== void 0) {
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
        o,
        i,
        w
      );
      return Ce(d, n, I), I;
    }
    if (m.compartment !== void 0) {
      if (m.importMeta !== void 0)
        throw v(
          "Cannot redirect to an implicit record with a specified importMeta"
        );
      const p = await pr(
        t,
        e,
        m.compartment,
        m.specifier,
        a,
        o,
        i
      );
      return Ce(d, n, p), p;
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
    o,
    i
  );
}, pr = async (t, e, r, n, a, o, i) => {
  const { name: c } = L(
    t,
    r
  );
  let u = De(o, r);
  u === void 0 && (u = new Ae(), Ce(o, r, u));
  let l = De(u, n);
  return l !== void 0 || (l = na(
    ki(
      t,
      e,
      r,
      n,
      a,
      o,
      i
    ),
    (d) => {
      throw Z.note(
        d,
        Qr`${d.message}, loading ${Me(n)} in compartment ${Me(
          c
        )}`
      ), d;
    }
  ), Ce(u, n, l)), l;
}, Jn = async (t, e, r, n) => {
  const { name: a } = L(
    t,
    r
  ), o = new St(), i = new Ae(), c = [], u = pr(
    t,
    e,
    r,
    n,
    o,
    i,
    c
  );
  Cr(
    o,
    gn(u, ls, (l) => {
      ae(c, l);
    })
  );
  for (const l of o)
    await l;
  if (c.length > 0)
    throw v(
      `Failed to load module ${Me(n)} in package ${Me(
        a
      )} (${c.length} underlying failures: ${Tt(
        de(c, (l) => l.message),
        ", "
      )}`
    );
}, { quote: pt } = Z, Ti = () => {
  let t = !1;
  const e = H(null, {
    // Make this appear like an ESM module namespace object.
    [He]: {
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
    exportsProxy: new Sr(e, {
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
}, Sn = (t, e, r, n) => {
  const { deferredExports: a } = e;
  if (!Ar(a, n)) {
    const o = Ti();
    ee(
      r,
      o.exportsProxy,
      Ei(t, n)
    ), Ce(a, n, o);
  }
  return De(a, n);
}, Ii = (t, e) => {
  const { sloppyGlobalsMode: r = !1, __moduleShimLexicals__: n = void 0 } = e;
  let a;
  if (n === void 0 && !r)
    ({ safeEvaluate: a } = t);
  else {
    let { globalTransforms: o } = t;
    const { globalObject: i } = t;
    let c;
    n !== void 0 && (o = void 0, c = H(
      null,
      Je(n)
    )), { safeEvaluate: a } = xn({
      globalObject: i,
      moduleLexicals: c,
      globalTransforms: o,
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
    __evadeImportExpressionTest__: o = !1,
    __rejectSomeDirectEvalExpressions__: i = !0
    // Note default on
  } = r, c = [...n];
  a === !0 && ae(c, Jo), o === !0 && ae(c, es), i === !0 && ae(c, ts);
  const { safeEvaluate: u } = Ii(
    t,
    r
  );
  return u(e, {
    localTransforms: c
  });
}, { quote: er } = Z, Ai = (t, e, r, n, a, o) => {
  const { exportsProxy: i, exportsTarget: c, activate: u } = Sn(
    r,
    L(t, r),
    n,
    a
  ), l = H(null);
  if (e.exports) {
    if (!gt(e.exports) || Bs(e.exports, (f) => typeof f != "string"))
      throw v(
        `SES third-party static module record "exports" property must be an array of strings for module ${a}`
      );
    nt(e.exports, (f) => {
      let m = c[f];
      const p = [];
      M(c, f, {
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
      if (ho(d, "errorFromExecute"))
        throw d.errorFromExecute;
      if (!d.activated) {
        u(), d.activated = !0;
        try {
          e.execute(
            c,
            r,
            o
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
    moduleSpecifier: o,
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
  } = i, _ = L(t, a), { __shimTransforms__: w, importMetaHook: I } = _, { exportsProxy: N, exportsTarget: T, activate: D } = Sn(
    a,
    _,
    e,
    o
  ), j = H(null), q = H(null), K = H(null), je = H(null), fe = H(null);
  c && Er(fe, c), p && I && I(o, fe);
  const Ze = H(null), Xe = H(null);
  nt(te(d), ([pe, [z]]) => {
    let G = Ze[z];
    if (!G) {
      let X, Q = !0, ce = [];
      const Y = () => {
        if (Q)
          throw rt(`binding ${er(z)} not yet initialized`);
        return X;
      }, ve = y((_e) => {
        if (!Q)
          throw v(
            `Internal: binding ${er(z)} already initialized`
          );
        X = _e;
        const In = ce;
        ce = null, Q = !1;
        for (const be of In || [])
          be(_e);
        return _e;
      });
      G = {
        get: Y,
        notify: (_e) => {
          _e !== ve && (Q ? ae(ce || [], _e) : _e(X));
        }
      }, Ze[z] = G, K[z] = ve;
    }
    j[pe] = {
      get: G.get,
      set: void 0,
      enumerable: !0,
      configurable: !1
    }, Xe[pe] = G.notify;
  }), nt(
    te(f),
    ([pe, [z, G]]) => {
      let X = Ze[z];
      if (!X) {
        let Q, ce = !0;
        const Y = [], ve = () => {
          if (ce)
            throw rt(
              `binding ${er(pe)} not yet initialized`
            );
          return Q;
        }, ut = y((be) => {
          Q = be, ce = !1;
          for (const Lr of Y)
            Lr(be);
        }), _e = (be) => {
          if (ce)
            throw rt(`binding ${er(z)} not yet initialized`);
          Q = be;
          for (const Lr of Y)
            Lr(be);
        };
        X = {
          get: ve,
          notify: (be) => {
            be !== ut && (ae(Y, be), ce || be(Q));
          }
        }, Ze[z] = X, G && M(q, z, {
          get: ve,
          set: _e,
          enumerable: !0,
          configurable: !1
        }), je[z] = ut;
      }
      j[pe] = {
        get: X.get,
        set: void 0,
        enumerable: !0,
        configurable: !1
      }, Xe[pe] = X.notify;
    }
  );
  const ze = (pe) => {
    pe(T);
  };
  Xe["*"] = ze;
  function Xt(pe) {
    const z = H(null);
    z.default = !1;
    for (const [G, X] of pe) {
      const Q = De(n, G);
      Q.execute();
      const { notifiers: ce } = Q;
      for (const [Y, ve] of X) {
        const ut = ce[Y];
        if (!ut)
          throw Wt(
            `The requested module '${G}' does not provide an export named '${Y}'`
          );
        for (const _e of ve)
          ut(_e);
      }
      if (Ir(u, G))
        for (const [Y, ve] of te(
          ce
        ))
          z[Y] === void 0 ? z[Y] = ve : z[Y] = !1;
      if (m[G])
        for (const [Y, ve] of m[G])
          z[ve] = ce[Y];
    }
    for (const [G, X] of te(z))
      if (!Xe[G] && X !== !1) {
        Xe[G] = X;
        let Q;
        X((Y) => Q = Y), j[G] = {
          get() {
            return Q;
          },
          set: void 0,
          enumerable: !0,
          configurable: !1
        };
      }
    nt(
      _o(co(j)),
      (G) => M(T, G, j[G])
    ), y(T), D();
  }
  let It;
  h !== void 0 ? It = h : It = us(_, l, {
    globalObject: a.globalThis,
    transforms: w,
    __moduleShimLexicals__: q
  });
  let kn = !1, Tn;
  function Es() {
    if (It) {
      const pe = It;
      It = null;
      try {
        pe(
          y({
            imports: y(Xt),
            onceVar: y(K),
            liveVar: y(je),
            importMeta: fe
          })
        );
      } catch (z) {
        kn = !0, Tn = z;
      }
    }
    if (kn)
      throw Tn;
  }
  return y({
    notifiers: Xe,
    exportsProxy: N,
    execute: Es
  });
}, { Fail: tt, quote: W } = Z, ds = (t, e, r, n) => {
  const { name: a, moduleRecords: o } = L(
    t,
    r
  ), i = De(o, n);
  if (i === void 0)
    throw rt(
      `Missing link to module ${W(n)} from compartment ${W(
        a
      )}`
    );
  return Mi(t, e, i);
};
function Ni(t) {
  return typeof t.__syncModuleProgram__ == "string";
}
function $i(t, e) {
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
function Li(t, e) {
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
const Mi = (t, e, r) => {
  const { compartment: n, moduleSpecifier: a, resolvedImports: o, staticModuleRecord: i } = r, { instances: c } = L(t, n);
  if (Ar(c, a))
    return De(c, a);
  Li(i, a);
  const u = new Ae();
  let l;
  if (Ni(i))
    $i(i, a), l = Ci(
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
      o
    );
  else
    throw v(
      `importHook must return a static module record, got ${W(
        i
      )}`
    );
  Ce(c, a, l);
  for (const [d, f] of te(o)) {
    const m = ds(
      t,
      e,
      n,
      f
    );
    Ce(u, d, m);
  }
  return l;
}, { quote: Zr } = Z, $t = new ke(), Le = new ke(), tr = (t) => {
  const { importHook: e, resolveHook: r } = L(Le, t);
  if (typeof e != "function" || typeof r != "function")
    throw v(
      "Compartment must be constructed with an importHook and a resolveHook for it to be able to load modules"
    );
}, En = function(e = {}, r = {}, n = {}) {
  throw v(
    "Compartment.prototype.constructor is not a valid constructor."
  );
}, Xn = (t, e) => {
  const { execute: r, exportsProxy: n } = ds(
    Le,
    $t,
    t,
    e
  );
  return r(), n;
}, Pn = {
  constructor: En,
  get globalThis() {
    return L(Le, this).globalObject;
  },
  get name() {
    return L(Le, this).name;
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
    const r = L(Le, this);
    return us(r, t, e);
  },
  module(t) {
    if (typeof t != "string")
      throw v("first argument of module() must be a string");
    tr(this);
    const { exportsProxy: e } = Sn(
      this,
      L(Le, this),
      $t,
      t
    );
    return e;
  },
  async import(t) {
    if (typeof t != "string")
      throw v("first argument of import() must be a string");
    return tr(this), gn(
      Jn(Le, $t, this, t),
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
    return tr(this), Jn(Le, $t, this, t);
  },
  importNow(t) {
    if (typeof t != "string")
      throw v("first argument of importNow() must be a string");
    return tr(this), Xn(
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
F(En, {
  prototype: { value: Pn }
});
const en = (t, e, r) => {
  function n(a = {}, o = {}, i = {}) {
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
    } = i, h = [...u, ...l], _ = new Ae(), w = new Ae(), I = new Ae();
    for (const [D, j] of te(o || {})) {
      if (typeof j == "string")
        throw v(
          `Cannot map module ${Zr(D)} to ${Zr(
            j
          )} in parent compartment`
        );
      if (L($t, j) === void 0)
        throw rt(
          `Cannot map module ${Zr(
            D
          )} because it has no known compartment in this realm`
        );
    }
    const N = {};
    Ga(N), Go(N);
    const { safeEvaluate: T } = xn({
      globalObject: N,
      globalTransforms: h,
      sloppyGlobalsMode: !1
    });
    Bo(N, {
      intrinsics: e,
      newGlobalPropertyNames: Do,
      makeCompartmentConstructor: t,
      markVirtualizedNativeFunction: r
    }), Xr(
      N,
      T,
      r
    ), Er(N, a), ee(Le, this, {
      name: `${c}`,
      globalTransforms: h,
      globalObject: N,
      safeEvaluate: T,
      resolveHook: d,
      importHook: f,
      moduleMap: o,
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
function zr(t) {
  return B(t).constructor;
}
function Fi() {
  return arguments;
}
const Di = () => {
  const t = ye.prototype.constructor, e = ue(Fi(), "callee"), r = e && e.get, n = ea(new ie()), a = B(n), o = kr[po] && Ys(/./), i = o && B(o), c = Hs([]), u = B(c), l = B(Ts), d = qs(new Ae()), f = B(d), m = Ks(new St()), p = B(m), h = B(u);
  function* _() {
  }
  const w = zr(_), I = w.prototype;
  async function* N() {
  }
  const T = zr(
    N
  ), D = T.prototype, j = D.prototype, q = B(j);
  async function K() {
  }
  const je = zr(K), fe = {
    "%InertFunction%": t,
    "%ArrayIteratorPrototype%": u,
    "%InertAsyncFunction%": je,
    "%AsyncGenerator%": D,
    "%InertAsyncGeneratorFunction%": T,
    "%AsyncGeneratorPrototype%": j,
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
    "%InertCompartment%": En
  };
  return E.Iterator && (fe["%IteratorHelperPrototype%"] = B(
    // eslint-disable-next-line @endo/no-polymorphic-call
    E.Iterator.from([]).take(0)
  ), fe["%WrapForValidIteratorPrototype%"] = B(
    // eslint-disable-next-line @endo/no-polymorphic-call
    E.Iterator.from({ next() {
    } })
  )), E.AsyncIterator && (fe["%AsyncIteratorHelperPrototype%"] = B(
    // eslint-disable-next-line @endo/no-polymorphic-call
    E.AsyncIterator.from([]).take(0)
  ), fe["%WrapForValidAsyncIteratorPrototype%"] = B(
    // eslint-disable-next-line @endo/no-polymorphic-call
    E.AsyncIterator.from({ next() {
    } })
  )), fe;
}, fs = (t, e) => {
  if (e !== "safe" && e !== "unsafe")
    throw v(`unrecognized fakeHardenOption ${e}`);
  if (e === "safe" || (Object.isExtensible = () => !1, Object.isFrozen = () => !0, Object.isSealed = () => !0, Reflect.isExtensible = () => !1, t.isFake))
    return t;
  const r = (n) => n;
  return r.isFake = !0, y(r);
};
y(fs);
const Ui = () => {
  const t = Nt, e = t.prototype, r = {
    Symbol(o) {
      return t(o);
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
  ), a = Pt(
    de(n, ([o, i]) => [
      o,
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
  const n = ue(t, e);
  if (!n || "value" in n)
    return !1;
  const { get: a, set: o } = n;
  if (typeof a != "function" || typeof o != "function" || a() !== r || oe(a, t, []) !== r)
    return !1;
  const i = "Seems to be a setter", c = { __proto__: null };
  if (oe(o, c, [i]), c[e] !== i)
    return !1;
  const u = { __proto__: t };
  return oe(o, u, [i]), u[e] !== i || !ji(() => oe(o, t, [r])) || "originalValue" in a || n.configurable === !1 ? !1 : (M(t, e, {
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
}, { Fail: eo, details: to, quote: ro } = Z;
let rr, nr;
const zi = xa(), Gi = () => {
  let t = !1;
  try {
    t = ye(
      "eval",
      "SES_changed",
      `        eval("SES_changed = true");
        return SES_changed;
      `
    )(So, !1), t || delete E.SES_changed;
  } catch {
    t = !0;
  }
  if (!t)
    throw v(
      "SES cannot initialize unless 'eval' is the original intrinsic 'eval', suitable for direct-eval (dynamically scoped eval) (SES_DIRECT_EVAL)"
    );
}, ps = (t = {}) => {
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
    localeTaming: o = me("LOCKDOWN_LOCALE_TAMING", "safe"),
    consoleTaming: i = (
      /** @type {'unsafe' | 'safe' | undefined} */
      me("LOCKDOWN_CONSOLE_TAMING", "safe")
    ),
    overrideTaming: c = me("LOCKDOWN_OVERRIDE_TAMING", "moderate"),
    stackFiltering: u = me("LOCKDOWN_STACK_FILTERING", "concise"),
    domainTaming: l = me("LOCKDOWN_DOMAIN_TAMING", "safe"),
    evalTaming: d = me("LOCKDOWN_EVAL_TAMING", "safeEval"),
    overrideDebug: f = Ve(
      wo(me("LOCKDOWN_OVERRIDE_DEBUG", ""), ","),
      /** @param {string} debugName */
      (ze) => ze !== ""
    ),
    __hardenTaming__: m = me("LOCKDOWN_HARDEN_TAMING", "safe"),
    dateTaming: p = "safe",
    // deprecated
    mathTaming: h = "safe",
    // deprecated
    ..._
  } = t;
  d === "unsafeEval" || d === "safeEval" || d === "noEval" || eo`lockdown(): non supported option evalTaming: ${ro(d)}`;
  const w = st(_);
  if (w.length === 0 || eo`lockdown(): non supported option ${ro(w)}`, rr === void 0 || // eslint-disable-next-line @endo/no-polymorphic-call
  Z.fail(
    to`Already locked down at ${rr} (SES_ALREADY_LOCKED_DOWN)`,
    v
  ), rr = v("Prior lockdown (SES_ALREADY_LOCKED_DOWN)"), rr.stack, Gi(), E.Function.prototype.constructor !== E.Function && // @ts-ignore harden is absent on globalThis type def.
  typeof E.harden == "function" && // @ts-ignore lockdown is absent on globalThis type def.
  typeof E.lockdown == "function" && E.Date.prototype.constructor !== E.Date && typeof E.Date.now == "function" && // @ts-ignore does not recognize that Date constructor is a special
  // Function.
  // eslint-disable-next-line @endo/no-polymorphic-call
  Pr(E.Date.prototype.constructor.now(), NaN))
    throw v(
      "Already locked down but not by this SES instance (SES_MULTIPLE_INSTANCES)"
    );
  ni(l);
  const N = os(), { addIntrinsics: T, completePrototypes: D, finalIntrinsics: j } = jo(), q = fs(zi, m);
  T({ harden: q }), T(Ca()), T(Na(p)), T(xi(e, u)), T($a(h)), T(Oa(a)), T(Ui()), T(Di()), D();
  const K = j(), je = { __proto__: null };
  typeof E.Buffer == "function" && (je.Buffer = E.Buffer);
  let fe;
  e !== "unsafe" && (fe = K["%InitialGetStackString%"]);
  const Ze = ai(
    i,
    r,
    n,
    fe
  );
  if (E.console = /** @type {Console} */
  Ze.console, typeof /** @type {any} */
  Ze.console._times == "object" && (je.SafeMap = B(
    // eslint-disable-next-line no-underscore-dangle
    /** @type {any} */
    Ze.console._times
  )), e === "unsafe" && E.assert === Z && (E.assert = Or(void 0, !0)), ja(K, o), Zi(K), Aa(K, N), Go(E), Bo(E, {
    intrinsics: K,
    newGlobalPropertyNames: Fn,
    makeCompartmentConstructor: en,
    markVirtualizedNativeFunction: N
  }), d === "noEval")
    Xr(
      E,
      sa,
      N
    );
  else if (d === "safeEval") {
    const { safeEvaluate: ze } = xn({ globalObject: E });
    Xr(
      E,
      ze,
      N
    );
  }
  return () => {
    nr === void 0 || // eslint-disable-next-line @endo/no-polymorphic-call
    Z.fail(
      to`Already locked down at ${nr} (SES_ALREADY_LOCKED_DOWN)`,
      v
    ), nr = v(
      "Prior lockdown (SES_ALREADY_LOCKED_DOWN)"
    ), nr.stack, Ma(K, c, f);
    const ze = {
      intrinsics: K,
      hostIntrinsics: je,
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
    for (const Xt of Ot(Fn))
      ze.globals[Xt] = E[Xt];
    return q(ze), q;
  };
};
E.lockdown = (t) => {
  const e = ps(t);
  E.harden = e();
};
E.repairIntrinsics = (t) => {
  const e = ps(t);
  E.hardenIntrinsics = () => {
    E.harden = e();
  };
};
const Bi = os();
E.Compartment = en(
  en,
  Ia(E),
  Bi
);
E.assert = Z;
const Hi = (t) => {
  let e = { x: 0, y: 0 }, r = { x: 0, y: 0 }, n = { x: 0, y: 0 };
  const a = (c) => {
    const { clientX: u, clientY: l } = c, d = u - n.x + r.x, f = l - n.y + r.y;
    e = { x: d, y: f }, t.style.transform = `translate(${d}px, ${f}px)`;
  }, o = () => {
    document.removeEventListener("mousemove", a), document.removeEventListener("mouseup", o);
  }, i = (c) => {
    n = { x: c.clientX, y: c.clientY }, r = { x: e.x, y: e.y }, document.addEventListener("mousemove", a), document.addEventListener("mouseup", o);
  };
  return t.addEventListener("mousedown", i), o;
}, Vi = `
<svg width="16"  height="16"xmlns="http://www.w3.org/2000/svg" fill="none"><g class="fills"><rect rx="0" ry="0" width="16" height="16" class="frame-background"/></g><g class="frame-children"><path d="M11.997 3.997 8 8l-3.997 4.003m-.006-8L8 8l4.003 3.997" class="fills"/><g class="strokes"><path d="M11.997 3.997 8 8l-3.997 4.003m-.006-8L8 8l4.003 3.997" style="fill: none; stroke-width: 1; stroke: rgb(143, 157, 163); stroke-opacity: 1; stroke-linecap: round;" class="stroke-shape"/></g></g></svg>`;
var we, Vt;
class Wi extends HTMLElement {
  constructor() {
    super();
    Mr(this, we, null);
    Mr(this, Vt, null);
    this.attachShadow({ mode: "open" });
  }
  setTheme(r) {
    Re(this, we) && Re(this, we).setAttribute("data-theme", r);
  }
  disconnectedCallback() {
    var r;
    (r = Re(this, Vt)) == null || r.call(this);
  }
  connectedCallback() {
    const r = this.getAttribute("title"), n = this.getAttribute("iframe-src"), a = Number(this.getAttribute("width") || "300"), o = Number(this.getAttribute("height") || "400");
    if (!r || !n)
      throw new Error("title and iframe-src attributes are required");
    if (!this.shadowRoot)
      throw new Error("Error creating shadow root");
    Fr(this, we, document.createElement("div")), Re(this, we).classList.add("wrapper"), Fr(this, Vt, Hi(Re(this, we)));
    const i = document.createElement("div");
    i.classList.add("header");
    const c = document.createElement("h1");
    c.textContent = r, i.appendChild(c);
    const u = document.createElement("button");
    u.setAttribute("type", "button"), u.innerHTML = `<div class="close">${Vi}</div>`, u.addEventListener("click", () => {
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
    }), this.shadowRoot.appendChild(Re(this, we)), Re(this, we).appendChild(i), Re(this, we).appendChild(l);
    const d = document.createElement("style");
    d.textContent = `
        :host {
          --spacing-4: 0.25rem;
          --spacing-8: calc(var(--spacing-4) * 2);
          --spacing-12: calc(var(--spacing-4) * 3);
          --spacing-16: calc(var(--spacing-4) * 4);
          --spacing-20: calc(var(--spacing-4) * 5);
          --spacing-24: calc(var(--spacing-4) * 6);
          --spacing-28: calc(var(--spacing-4) * 7);
          --spacing-32: calc(var(--spacing-4) * 8);
          --spacing-36: calc(var(--spacing-4) * 9);
          --spacing-40: calc(var(--spacing-4) * 10);

          --font-weight-regular: 400;
          --font-weight-bold: 500;
          --font-line-height-s: 1.2;
          --font-line-height-m: 1.4;
          --font-line-height-l: 1.5;
          --font-size-s: 12px;
          --font-size-m: 14px;
          --font-size-l: 16px;
        }

        [data-theme] {
          background-color: var(--color-background-primary);
          color: var(--color-foreground-secondary);
        }

        .wrapper {
          display: flex;
          flex-direction: column;
          position: fixed;
          inset-block-end: 10px;
          inset-inline-start: 10px;
          z-index: 1000;
          padding: 25px;
          border-radius: 15px;
          box-shadow: 0px 0px 10px 0px rgba(0, 0, 0, 0.3);
          inline-size: ${a}px;
          block-size: ${o}px;
        }

        .header {
          align-items: center;
          display: flex;
          justify-content: space-between;
          border-block-end: 2px solid var(--color-background-quaternary);
          padding-block-end: var(--spacing-4);
          margin-block-end: var(--spacing-20);
        }

        button {
          background: transparent;
          border: 0;
          cursor: pointer;
          padding: 0;
        }

        h1 {
          font-size: var(--font-size-s);
          font-weight: var(--font-weight-bold);
          margin: 0;
          margin-inline-end: var(--spacing-4);
          user-select: none;
        }

        iframe {
          border: none;
          inline-size: 100%;
          block-size: 100%;
        }
    `, this.shadowRoot.appendChild(d);
  }
}
we = new WeakMap(), Vt = new WeakMap();
customElements.define("plugin-modal", Wi);
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
    const o = {};
    for (const i of a)
      o[i] = i;
    return o;
  }, t.getValidEnumValues = (a) => {
    const o = t.objectKeys(a).filter((c) => typeof a[a[c]] != "number"), i = {};
    for (const c of o)
      i[c] = a[c];
    return t.objectValues(i);
  }, t.objectValues = (a) => t.objectKeys(a).map(function(o) {
    return a[o];
  }), t.objectKeys = typeof Object.keys == "function" ? (a) => Object.keys(a) : (a) => {
    const o = [];
    for (const i in a)
      Object.prototype.hasOwnProperty.call(a, i) && o.push(i);
    return o;
  }, t.find = (a, o) => {
    for (const i of a)
      if (o(i))
        return i;
  }, t.isInteger = typeof Number.isInteger == "function" ? (a) => Number.isInteger(a) : (a) => typeof a == "number" && isFinite(a) && Math.floor(a) === a;
  function n(a, o = " | ") {
    return a.map((i) => typeof i == "string" ? `'${i}'` : i).join(o);
  }
  t.joinValues = n, t.jsonStringifyReplacer = (a, o) => typeof o == "bigint" ? o.toString() : o;
})(R || (R = {}));
var tn;
(function(t) {
  t.mergeShapes = (e, r) => ({
    ...e,
    ...r
    // second overwrites first
  });
})(tn || (tn = {}));
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
]), qi = (t) => JSON.stringify(t, null, 2).replace(/"([^"]+)":/g, "$1:");
class Se extends Error {
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
    const r = e || function(o) {
      return o.message;
    }, n = { _errors: [] }, a = (o) => {
      for (const i of o.issues)
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
Se.create = (t) => new Se(t);
const Rt = (t, e) => {
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
let ms = Rt;
function Ki(t) {
  ms = t;
}
function mr() {
  return ms;
}
const hr = (t) => {
  const { data: e, path: r, errorMaps: n, issueData: a } = t, o = [...r, ...a.path || []], i = {
    ...a,
    path: o
  };
  let c = "";
  const u = n.filter((l) => !!l).slice().reverse();
  for (const l of u)
    c = l(i, { data: e, defaultError: c }).message;
  return {
    ...a,
    path: o,
    message: a.message || c
  };
}, Yi = [];
function x(t, e) {
  const r = hr({
    issueData: e,
    data: t.data,
    path: t.path,
    errorMaps: [
      t.common.contextualErrorMap,
      t.schemaErrorMap,
      mr(),
      Rt
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
      const { key: o, value: i } = a;
      if (o.status === "aborted" || i.status === "aborted")
        return A;
      o.status === "dirty" && e.dirty(), i.status === "dirty" && e.dirty(), o.value !== "__proto__" && (typeof i.value < "u" || a.alwaysSet) && (n[o.value] = i.value);
    }
    return { status: e.value, value: n };
  }
}
const A = Object.freeze({
  status: "aborted"
}), hs = (t) => ({ status: "dirty", value: t }), re = (t) => ({ status: "valid", value: t }), rn = (t) => t.status === "aborted", nn = (t) => t.status === "dirty", Lt = (t) => t.status === "valid", gr = (t) => typeof Promise < "u" && t instanceof Promise;
var S;
(function(t) {
  t.errToObj = (e) => typeof e == "string" ? { message: e } : e || {}, t.toString = (e) => typeof e == "string" ? e : e == null ? void 0 : e.message;
})(S || (S = {}));
class Ne {
  constructor(e, r, n, a) {
    this._cachedPath = [], this.parent = e, this.data = r, this._path = n, this._key = a;
  }
  get path() {
    return this._cachedPath.length || (this._key instanceof Array ? this._cachedPath.push(...this._path, ...this._key) : this._cachedPath.push(...this._path, this._key)), this._cachedPath;
  }
}
const no = (t, e) => {
  if (Lt(e))
    return { success: !0, data: e.value };
  if (!t.common.issues.length)
    throw new Error("Validation failed but no issues detected.");
  return {
    success: !1,
    get error() {
      if (this._error)
        return this._error;
      const r = new Se(t.common.issues);
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
    }, o = this._parseSync({ data: e, path: a.path, parent: a });
    return no(a, o);
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
    }, a = this._parse({ data: e, path: n.path, parent: n }), o = await (gr(a) ? a : Promise.resolve(a));
    return no(n, o);
  }
  refine(e, r) {
    const n = (a) => typeof r == "string" || typeof r > "u" ? { message: r } : typeof r == "function" ? r(a) : r;
    return this._refinement((a, o) => {
      const i = e(a), c = () => o.addIssue({
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
    return new Pe({
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
    return Ee.create(this, this._def);
  }
  promise() {
    return xt.create(this, this._def);
  }
  or(e) {
    return Ut.create([this, e], this._def);
  }
  and(e) {
    return jt.create(this, e, this._def);
  }
  transform(e) {
    return new Pe({
      ...C(this._def),
      schema: this,
      typeName: P.ZodEffects,
      effect: { type: "transform", transform: e }
    });
  }
  default(e) {
    const r = typeof e == "function" ? e : () => e;
    return new Ht({
      ...C(this._def),
      innerType: this,
      defaultValue: r,
      typeName: P.ZodDefault
    });
  }
  brand() {
    return new ys({
      typeName: P.ZodBranded,
      type: this,
      ...C(this._def)
    });
  }
  catch(e) {
    const r = typeof e == "function" ? e : () => e;
    return new br({
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
    return Jt.create(this, e);
  }
  readonly() {
    return xr.create(this);
  }
  isOptional() {
    return this.safeParse(void 0).success;
  }
  isNullable() {
    return this.safeParse(null).success;
  }
}
const Ji = /^c[^\s-]{8,}$/i, Xi = /^[a-z][a-z0-9]*$/, Qi = /^[0-9A-HJKMNP-TV-Z]{26}$/, ec = /^[0-9a-fA-F]{8}\b-[0-9a-fA-F]{4}\b-[0-9a-fA-F]{4}\b-[0-9a-fA-F]{4}\b-[0-9a-fA-F]{12}$/i, tc = /^(?!\.)(?!.*\.\.)([A-Z0-9_+-\.]*)[A-Z0-9_+-]@([A-Z0-9][A-Z0-9\-]*\.)+[A-Z]{2,}$/i, rc = "^(\\p{Extended_Pictographic}|\\p{Emoji_Component})+$";
let Gr;
const nc = /^(((25[0-5])|(2[0-4][0-9])|(1[0-9]{2})|([0-9]{1,2}))\.){3}((25[0-5])|(2[0-4][0-9])|(1[0-9]{2})|([0-9]{1,2}))$/, oc = /^(([a-f0-9]{1,4}:){7}|::([a-f0-9]{1,4}:){0,6}|([a-f0-9]{1,4}:){1}:([a-f0-9]{1,4}:){0,5}|([a-f0-9]{1,4}:){2}:([a-f0-9]{1,4}:){0,4}|([a-f0-9]{1,4}:){3}:([a-f0-9]{1,4}:){0,3}|([a-f0-9]{1,4}:){4}:([a-f0-9]{1,4}:){0,2}|([a-f0-9]{1,4}:){5}:([a-f0-9]{1,4}:){0,1})([a-f0-9]{1,4}|(((25[0-5])|(2[0-4][0-9])|(1[0-9]{2})|([0-9]{1,2}))\.){3}((25[0-5])|(2[0-4][0-9])|(1[0-9]{2})|([0-9]{1,2})))$/, sc = (t) => t.precision ? t.offset ? new RegExp(`^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{${t.precision}}(([+-]\\d{2}(:?\\d{2})?)|Z)$`) : new RegExp(`^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{${t.precision}}Z$`) : t.precision === 0 ? t.offset ? new RegExp("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(([+-]\\d{2}(:?\\d{2})?)|Z)$") : new RegExp("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$") : t.offset ? new RegExp("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(([+-]\\d{2}(:?\\d{2})?)|Z)$") : new RegExp("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z$");
function ac(t, e) {
  return !!((e === "v4" || !e) && nc.test(t) || (e === "v6" || !e) && oc.test(t));
}
class xe extends $ {
  _parse(e) {
    if (this._def.coerce && (e.data = String(e.data)), this._getType(e) !== b.string) {
      const o = this._getOrReturnCtx(e);
      return x(
        o,
        {
          code: g.invalid_type,
          expected: b.string,
          received: o.parsedType
        }
        //
      ), A;
    }
    const n = new J();
    let a;
    for (const o of this._def.checks)
      if (o.kind === "min")
        e.data.length < o.value && (a = this._getOrReturnCtx(e, a), x(a, {
          code: g.too_small,
          minimum: o.value,
          type: "string",
          inclusive: !0,
          exact: !1,
          message: o.message
        }), n.dirty());
      else if (o.kind === "max")
        e.data.length > o.value && (a = this._getOrReturnCtx(e, a), x(a, {
          code: g.too_big,
          maximum: o.value,
          type: "string",
          inclusive: !0,
          exact: !1,
          message: o.message
        }), n.dirty());
      else if (o.kind === "length") {
        const i = e.data.length > o.value, c = e.data.length < o.value;
        (i || c) && (a = this._getOrReturnCtx(e, a), i ? x(a, {
          code: g.too_big,
          maximum: o.value,
          type: "string",
          inclusive: !0,
          exact: !0,
          message: o.message
        }) : c && x(a, {
          code: g.too_small,
          minimum: o.value,
          type: "string",
          inclusive: !0,
          exact: !0,
          message: o.message
        }), n.dirty());
      } else if (o.kind === "email")
        tc.test(e.data) || (a = this._getOrReturnCtx(e, a), x(a, {
          validation: "email",
          code: g.invalid_string,
          message: o.message
        }), n.dirty());
      else if (o.kind === "emoji")
        Gr || (Gr = new RegExp(rc, "u")), Gr.test(e.data) || (a = this._getOrReturnCtx(e, a), x(a, {
          validation: "emoji",
          code: g.invalid_string,
          message: o.message
        }), n.dirty());
      else if (o.kind === "uuid")
        ec.test(e.data) || (a = this._getOrReturnCtx(e, a), x(a, {
          validation: "uuid",
          code: g.invalid_string,
          message: o.message
        }), n.dirty());
      else if (o.kind === "cuid")
        Ji.test(e.data) || (a = this._getOrReturnCtx(e, a), x(a, {
          validation: "cuid",
          code: g.invalid_string,
          message: o.message
        }), n.dirty());
      else if (o.kind === "cuid2")
        Xi.test(e.data) || (a = this._getOrReturnCtx(e, a), x(a, {
          validation: "cuid2",
          code: g.invalid_string,
          message: o.message
        }), n.dirty());
      else if (o.kind === "ulid")
        Qi.test(e.data) || (a = this._getOrReturnCtx(e, a), x(a, {
          validation: "ulid",
          code: g.invalid_string,
          message: o.message
        }), n.dirty());
      else if (o.kind === "url")
        try {
          new URL(e.data);
        } catch {
          a = this._getOrReturnCtx(e, a), x(a, {
            validation: "url",
            code: g.invalid_string,
            message: o.message
          }), n.dirty();
        }
      else
        o.kind === "regex" ? (o.regex.lastIndex = 0, o.regex.test(e.data) || (a = this._getOrReturnCtx(e, a), x(a, {
          validation: "regex",
          code: g.invalid_string,
          message: o.message
        }), n.dirty())) : o.kind === "trim" ? e.data = e.data.trim() : o.kind === "includes" ? e.data.includes(o.value, o.position) || (a = this._getOrReturnCtx(e, a), x(a, {
          code: g.invalid_string,
          validation: { includes: o.value, position: o.position },
          message: o.message
        }), n.dirty()) : o.kind === "toLowerCase" ? e.data = e.data.toLowerCase() : o.kind === "toUpperCase" ? e.data = e.data.toUpperCase() : o.kind === "startsWith" ? e.data.startsWith(o.value) || (a = this._getOrReturnCtx(e, a), x(a, {
          code: g.invalid_string,
          validation: { startsWith: o.value },
          message: o.message
        }), n.dirty()) : o.kind === "endsWith" ? e.data.endsWith(o.value) || (a = this._getOrReturnCtx(e, a), x(a, {
          code: g.invalid_string,
          validation: { endsWith: o.value },
          message: o.message
        }), n.dirty()) : o.kind === "datetime" ? sc(o).test(e.data) || (a = this._getOrReturnCtx(e, a), x(a, {
          code: g.invalid_string,
          validation: "datetime",
          message: o.message
        }), n.dirty()) : o.kind === "ip" ? ac(e.data, o.version) || (a = this._getOrReturnCtx(e, a), x(a, {
          validation: "ip",
          code: g.invalid_string,
          message: o.message
        }), n.dirty()) : R.assertNever(o);
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
    return new xe({
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
    return new xe({
      ...this._def,
      checks: [...this._def.checks, { kind: "trim" }]
    });
  }
  toLowerCase() {
    return new xe({
      ...this._def,
      checks: [...this._def.checks, { kind: "toLowerCase" }]
    });
  }
  toUpperCase() {
    return new xe({
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
xe.create = (t) => {
  var e;
  return new xe({
    checks: [],
    typeName: P.ZodString,
    coerce: (e = t == null ? void 0 : t.coerce) !== null && e !== void 0 ? e : !1,
    ...C(t)
  });
};
function ic(t, e) {
  const r = (t.toString().split(".")[1] || "").length, n = (e.toString().split(".")[1] || "").length, a = r > n ? r : n, o = parseInt(t.toFixed(a).replace(".", "")), i = parseInt(e.toFixed(a).replace(".", ""));
  return o % i / Math.pow(10, a);
}
class qe extends $ {
  constructor() {
    super(...arguments), this.min = this.gte, this.max = this.lte, this.step = this.multipleOf;
  }
  _parse(e) {
    if (this._def.coerce && (e.data = Number(e.data)), this._getType(e) !== b.number) {
      const o = this._getOrReturnCtx(e);
      return x(o, {
        code: g.invalid_type,
        expected: b.number,
        received: o.parsedType
      }), A;
    }
    let n;
    const a = new J();
    for (const o of this._def.checks)
      o.kind === "int" ? R.isInteger(e.data) || (n = this._getOrReturnCtx(e, n), x(n, {
        code: g.invalid_type,
        expected: "integer",
        received: "float",
        message: o.message
      }), a.dirty()) : o.kind === "min" ? (o.inclusive ? e.data < o.value : e.data <= o.value) && (n = this._getOrReturnCtx(e, n), x(n, {
        code: g.too_small,
        minimum: o.value,
        type: "number",
        inclusive: o.inclusive,
        exact: !1,
        message: o.message
      }), a.dirty()) : o.kind === "max" ? (o.inclusive ? e.data > o.value : e.data >= o.value) && (n = this._getOrReturnCtx(e, n), x(n, {
        code: g.too_big,
        maximum: o.value,
        type: "number",
        inclusive: o.inclusive,
        exact: !1,
        message: o.message
      }), a.dirty()) : o.kind === "multipleOf" ? ic(e.data, o.value) !== 0 && (n = this._getOrReturnCtx(e, n), x(n, {
        code: g.not_multiple_of,
        multipleOf: o.value,
        message: o.message
      }), a.dirty()) : o.kind === "finite" ? Number.isFinite(e.data) || (n = this._getOrReturnCtx(e, n), x(n, {
        code: g.not_finite,
        message: o.message
      }), a.dirty()) : R.assertNever(o);
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
    return new qe({
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
    return new qe({
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
qe.create = (t) => new qe({
  checks: [],
  typeName: P.ZodNumber,
  coerce: (t == null ? void 0 : t.coerce) || !1,
  ...C(t)
});
class Ke extends $ {
  constructor() {
    super(...arguments), this.min = this.gte, this.max = this.lte;
  }
  _parse(e) {
    if (this._def.coerce && (e.data = BigInt(e.data)), this._getType(e) !== b.bigint) {
      const o = this._getOrReturnCtx(e);
      return x(o, {
        code: g.invalid_type,
        expected: b.bigint,
        received: o.parsedType
      }), A;
    }
    let n;
    const a = new J();
    for (const o of this._def.checks)
      o.kind === "min" ? (o.inclusive ? e.data < o.value : e.data <= o.value) && (n = this._getOrReturnCtx(e, n), x(n, {
        code: g.too_small,
        type: "bigint",
        minimum: o.value,
        inclusive: o.inclusive,
        message: o.message
      }), a.dirty()) : o.kind === "max" ? (o.inclusive ? e.data > o.value : e.data >= o.value) && (n = this._getOrReturnCtx(e, n), x(n, {
        code: g.too_big,
        type: "bigint",
        maximum: o.value,
        inclusive: o.inclusive,
        message: o.message
      }), a.dirty()) : o.kind === "multipleOf" ? e.data % o.value !== BigInt(0) && (n = this._getOrReturnCtx(e, n), x(n, {
        code: g.not_multiple_of,
        multipleOf: o.value,
        message: o.message
      }), a.dirty()) : R.assertNever(o);
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
    return new Ke({
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
Ke.create = (t) => {
  var e;
  return new Ke({
    checks: [],
    typeName: P.ZodBigInt,
    coerce: (e = t == null ? void 0 : t.coerce) !== null && e !== void 0 ? e : !1,
    ...C(t)
  });
};
class Mt extends $ {
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
Mt.create = (t) => new Mt({
  typeName: P.ZodBoolean,
  coerce: (t == null ? void 0 : t.coerce) || !1,
  ...C(t)
});
class it extends $ {
  _parse(e) {
    if (this._def.coerce && (e.data = new Date(e.data)), this._getType(e) !== b.date) {
      const o = this._getOrReturnCtx(e);
      return x(o, {
        code: g.invalid_type,
        expected: b.date,
        received: o.parsedType
      }), A;
    }
    if (isNaN(e.data.getTime())) {
      const o = this._getOrReturnCtx(e);
      return x(o, {
        code: g.invalid_date
      }), A;
    }
    const n = new J();
    let a;
    for (const o of this._def.checks)
      o.kind === "min" ? e.data.getTime() < o.value && (a = this._getOrReturnCtx(e, a), x(a, {
        code: g.too_small,
        message: o.message,
        inclusive: !0,
        exact: !1,
        minimum: o.value,
        type: "date"
      }), n.dirty()) : o.kind === "max" ? e.data.getTime() > o.value && (a = this._getOrReturnCtx(e, a), x(a, {
        code: g.too_big,
        message: o.message,
        inclusive: !0,
        exact: !1,
        maximum: o.value,
        type: "date"
      }), n.dirty()) : R.assertNever(o);
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
it.create = (t) => new it({
  checks: [],
  coerce: (t == null ? void 0 : t.coerce) || !1,
  typeName: P.ZodDate,
  ...C(t)
});
class yr extends $ {
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
yr.create = (t) => new yr({
  typeName: P.ZodSymbol,
  ...C(t)
});
class Ft extends $ {
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
Ft.create = (t) => new Ft({
  typeName: P.ZodUndefined,
  ...C(t)
});
class Dt extends $ {
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
Dt.create = (t) => new Dt({
  typeName: P.ZodNull,
  ...C(t)
});
class wt extends $ {
  constructor() {
    super(...arguments), this._any = !0;
  }
  _parse(e) {
    return re(e.data);
  }
}
wt.create = (t) => new wt({
  typeName: P.ZodAny,
  ...C(t)
});
class ot extends $ {
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
class Ue extends $ {
  _parse(e) {
    const r = this._getOrReturnCtx(e);
    return x(r, {
      code: g.invalid_type,
      expected: b.never,
      received: r.parsedType
    }), A;
  }
}
Ue.create = (t) => new Ue({
  typeName: P.ZodNever,
  ...C(t)
});
class vr extends $ {
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
vr.create = (t) => new vr({
  typeName: P.ZodVoid,
  ...C(t)
});
class Ee extends $ {
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
      return Promise.all([...r.data].map((i, c) => a.type._parseAsync(new Ne(r, i, r.path, c)))).then((i) => J.mergeArray(n, i));
    const o = [...r.data].map((i, c) => a.type._parseSync(new Ne(r, i, r.path, c)));
    return J.mergeArray(n, o);
  }
  get element() {
    return this._def.type;
  }
  min(e, r) {
    return new Ee({
      ...this._def,
      minLength: { value: e, message: S.toString(r) }
    });
  }
  max(e, r) {
    return new Ee({
      ...this._def,
      maxLength: { value: e, message: S.toString(r) }
    });
  }
  length(e, r) {
    return new Ee({
      ...this._def,
      exactLength: { value: e, message: S.toString(r) }
    });
  }
  nonempty(e) {
    return this.min(1, e);
  }
}
Ee.create = (t, e) => new Ee({
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
    return t instanceof Ee ? new Ee({
      ...t._def,
      type: ht(t.element)
    }) : t instanceof Fe ? Fe.create(ht(t.unwrap())) : t instanceof lt ? lt.create(ht(t.unwrap())) : t instanceof $e ? $e.create(t.items.map((e) => ht(e))) : t;
}
class U extends $ {
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
    const { status: n, ctx: a } = this._processInputParams(e), { shape: o, keys: i } = this._getCached(), c = [];
    if (!(this._def.catchall instanceof Ue && this._def.unknownKeys === "strip"))
      for (const l in a.data)
        i.includes(l) || c.push(l);
    const u = [];
    for (const l of i) {
      const d = o[l], f = a.data[l];
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
    return S.errToObj, new U({
      ...this._def,
      unknownKeys: "strict",
      ...e !== void 0 ? {
        errorMap: (r, n) => {
          var a, o, i, c;
          const u = (i = (o = (a = this._def).errorMap) === null || o === void 0 ? void 0 : o.call(a, r, n).message) !== null && i !== void 0 ? i : n.defaultError;
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
        let o = this.shape[n];
        for (; o instanceof Fe; )
          o = o._def.innerType;
        r[n] = o;
      }
    }), new U({
      ...this._def,
      shape: () => r
    });
  }
  keyof() {
    return gs(R.objectKeys(this.shape));
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
class Ut extends $ {
  _parse(e) {
    const { ctx: r } = this._processInputParams(e), n = this._def.options;
    function a(o) {
      for (const c of o)
        if (c.result.status === "valid")
          return c.result;
      for (const c of o)
        if (c.result.status === "dirty")
          return r.common.issues.push(...c.ctx.common.issues), c.result;
      const i = o.map((c) => new Se(c.ctx.common.issues));
      return x(r, {
        code: g.invalid_union,
        unionErrors: i
      }), A;
    }
    if (r.common.async)
      return Promise.all(n.map(async (o) => {
        const i = {
          ...r,
          common: {
            ...r.common,
            issues: []
          },
          parent: null
        };
        return {
          result: await o._parseAsync({
            data: r.data,
            path: r.path,
            parent: i
          }),
          ctx: i
        };
      })).then(a);
    {
      let o;
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
        d.status === "dirty" && !o && (o = { result: d, ctx: l }), l.common.issues.length && i.push(l.common.issues);
      }
      if (o)
        return r.common.issues.push(...o.ctx.common.issues), o.result;
      const c = i.map((u) => new Se(u));
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
Ut.create = (t, e) => new Ut({
  options: t,
  typeName: P.ZodUnion,
  ...C(e)
});
const ir = (t) => t instanceof zt ? ir(t.schema) : t instanceof Pe ? ir(t.innerType()) : t instanceof Gt ? [t.value] : t instanceof Ye ? t.options : t instanceof Bt ? Object.keys(t.enum) : t instanceof Ht ? ir(t._def.innerType) : t instanceof Ft ? [void 0] : t instanceof Dt ? [null] : null;
class Rr extends $ {
  _parse(e) {
    const { ctx: r } = this._processInputParams(e);
    if (r.parsedType !== b.object)
      return x(r, {
        code: g.invalid_type,
        expected: b.object,
        received: r.parsedType
      }), A;
    const n = this.discriminator, a = r.data[n], o = this.optionsMap.get(a);
    return o ? r.common.async ? o._parseAsync({
      data: r.data,
      path: r.path,
      parent: r
    }) : o._parseSync({
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
    for (const o of r) {
      const i = ir(o.shape[e]);
      if (!i)
        throw new Error(`A discriminator value for key \`${e}\` could not be extracted from all schema options`);
      for (const c of i) {
        if (a.has(c))
          throw new Error(`Discriminator property ${String(e)} has duplicate value ${String(c)}`);
        a.set(c, o);
      }
    }
    return new Rr({
      typeName: P.ZodDiscriminatedUnion,
      discriminator: e,
      options: r,
      optionsMap: a,
      ...C(n)
    });
  }
}
function on(t, e) {
  const r = Ge(t), n = Ge(e);
  if (t === e)
    return { valid: !0, data: t };
  if (r === b.object && n === b.object) {
    const a = R.objectKeys(e), o = R.objectKeys(t).filter((c) => a.indexOf(c) !== -1), i = { ...t, ...e };
    for (const c of o) {
      const u = on(t[c], e[c]);
      if (!u.valid)
        return { valid: !1 };
      i[c] = u.data;
    }
    return { valid: !0, data: i };
  } else if (r === b.array && n === b.array) {
    if (t.length !== e.length)
      return { valid: !1 };
    const a = [];
    for (let o = 0; o < t.length; o++) {
      const i = t[o], c = e[o], u = on(i, c);
      if (!u.valid)
        return { valid: !1 };
      a.push(u.data);
    }
    return { valid: !0, data: a };
  } else
    return r === b.date && n === b.date && +t == +e ? { valid: !0, data: t } : { valid: !1 };
}
class jt extends $ {
  _parse(e) {
    const { status: r, ctx: n } = this._processInputParams(e), a = (o, i) => {
      if (rn(o) || rn(i))
        return A;
      const c = on(o.value, i.value);
      return c.valid ? ((nn(o) || nn(i)) && r.dirty(), { status: r.value, value: c.data }) : (x(n, {
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
    ]).then(([o, i]) => a(o, i)) : a(this._def.left._parseSync({
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
jt.create = (t, e, r) => new jt({
  left: t,
  right: e,
  typeName: P.ZodIntersection,
  ...C(r)
});
class $e extends $ {
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
    const o = [...n.data].map((i, c) => {
      const u = this._def.items[c] || this._def.rest;
      return u ? u._parse(new Ne(n, i, n.path, c)) : null;
    }).filter((i) => !!i);
    return n.common.async ? Promise.all(o).then((i) => J.mergeArray(r, i)) : J.mergeArray(r, o);
  }
  get items() {
    return this._def.items;
  }
  rest(e) {
    return new $e({
      ...this._def,
      rest: e
    });
  }
}
$e.create = (t, e) => {
  if (!Array.isArray(t))
    throw new Error("You must pass an array of schemas to z.tuple([ ... ])");
  return new $e({
    items: t,
    typeName: P.ZodTuple,
    rest: null,
    ...C(e)
  });
};
class Zt extends $ {
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
    const a = [], o = this._def.keyType, i = this._def.valueType;
    for (const c in n.data)
      a.push({
        key: o._parse(new Ne(n, c, n.path, c)),
        value: i._parse(new Ne(n, n.data[c], n.path, c))
      });
    return n.common.async ? J.mergeObjectAsync(r, a) : J.mergeObjectSync(r, a);
  }
  get element() {
    return this._def.valueType;
  }
  static create(e, r, n) {
    return r instanceof $ ? new Zt({
      keyType: e,
      valueType: r,
      typeName: P.ZodRecord,
      ...C(n)
    }) : new Zt({
      keyType: xe.create(),
      valueType: e,
      typeName: P.ZodRecord,
      ...C(r)
    });
  }
}
class _r extends $ {
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
    const a = this._def.keyType, o = this._def.valueType, i = [...n.data.entries()].map(([c, u], l) => ({
      key: a._parse(new Ne(n, c, n.path, [l, "key"])),
      value: o._parse(new Ne(n, u, n.path, [l, "value"]))
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
_r.create = (t, e, r) => new _r({
  valueType: e,
  keyType: t,
  typeName: P.ZodMap,
  ...C(r)
});
class ct extends $ {
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
    const o = this._def.valueType;
    function i(u) {
      const l = /* @__PURE__ */ new Set();
      for (const d of u) {
        if (d.status === "aborted")
          return A;
        d.status === "dirty" && r.dirty(), l.add(d.value);
      }
      return { status: r.value, value: l };
    }
    const c = [...n.data.values()].map((u, l) => o._parse(new Ne(n, u, n.path, l)));
    return n.common.async ? Promise.all(c).then((u) => i(u)) : i(c);
  }
  min(e, r) {
    return new ct({
      ...this._def,
      minSize: { value: e, message: S.toString(r) }
    });
  }
  max(e, r) {
    return new ct({
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
ct.create = (t, e) => new ct({
  valueType: t,
  minSize: null,
  maxSize: null,
  typeName: P.ZodSet,
  ...C(e)
});
class yt extends $ {
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
      return hr({
        data: c,
        path: r.path,
        errorMaps: [
          r.common.contextualErrorMap,
          r.schemaErrorMap,
          mr(),
          Rt
        ].filter((l) => !!l),
        issueData: {
          code: g.invalid_arguments,
          argumentsError: u
        }
      });
    }
    function a(c, u) {
      return hr({
        data: c,
        path: r.path,
        errorMaps: [
          r.common.contextualErrorMap,
          r.schemaErrorMap,
          mr(),
          Rt
        ].filter((l) => !!l),
        issueData: {
          code: g.invalid_return_type,
          returnTypeError: u
        }
      });
    }
    const o = { errorMap: r.common.contextualErrorMap }, i = r.data;
    if (this._def.returns instanceof xt) {
      const c = this;
      return re(async function(...u) {
        const l = new Se([]), d = await c._def.args.parseAsync(u, o).catch((p) => {
          throw l.addIssue(n(u, p)), l;
        }), f = await Reflect.apply(i, this, d);
        return await c._def.returns._def.type.parseAsync(f, o).catch((p) => {
          throw l.addIssue(a(f, p)), l;
        });
      });
    } else {
      const c = this;
      return re(function(...u) {
        const l = c._def.args.safeParse(u, o);
        if (!l.success)
          throw new Se([n(u, l.error)]);
        const d = Reflect.apply(i, this, l.data), f = c._def.returns.safeParse(d, o);
        if (!f.success)
          throw new Se([a(d, f.error)]);
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
    return new yt({
      ...this._def,
      args: $e.create(e).rest(ot.create())
    });
  }
  returns(e) {
    return new yt({
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
    return new yt({
      args: e || $e.create([]).rest(ot.create()),
      returns: r || ot.create(),
      typeName: P.ZodFunction,
      ...C(n)
    });
  }
}
class zt extends $ {
  get schema() {
    return this._def.getter();
  }
  _parse(e) {
    const { ctx: r } = this._processInputParams(e);
    return this._def.getter()._parse({ data: r.data, path: r.path, parent: r });
  }
}
zt.create = (t, e) => new zt({
  getter: t,
  typeName: P.ZodLazy,
  ...C(e)
});
class Gt extends $ {
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
Gt.create = (t, e) => new Gt({
  value: t,
  typeName: P.ZodLiteral,
  ...C(e)
});
function gs(t, e) {
  return new Ye({
    values: t,
    typeName: P.ZodEnum,
    ...C(e)
  });
}
class Ye extends $ {
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
    return Ye.create(e);
  }
  exclude(e) {
    return Ye.create(this.options.filter((r) => !e.includes(r)));
  }
}
Ye.create = gs;
class Bt extends $ {
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
Bt.create = (t, e) => new Bt({
  values: t,
  typeName: P.ZodNativeEnum,
  ...C(e)
});
class xt extends $ {
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
xt.create = (t, e) => new xt({
  type: t,
  typeName: P.ZodPromise,
  ...C(e)
});
class Pe extends $ {
  innerType() {
    return this._def.schema;
  }
  sourceType() {
    return this._def.schema._def.typeName === P.ZodEffects ? this._def.schema.sourceType() : this._def.schema;
  }
  _parse(e) {
    const { status: r, ctx: n } = this._processInputParams(e), a = this._def.effect || null, o = {
      addIssue: (i) => {
        x(n, i), i.fatal ? r.abort() : r.dirty();
      },
      get path() {
        return n.path;
      }
    };
    if (o.addIssue = o.addIssue.bind(o), a.type === "preprocess") {
      const i = a.transform(n.data, o);
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
        const u = a.refinement(c, o);
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
        if (!Lt(i))
          return i;
        const c = a.transform(i.value, o);
        if (c instanceof Promise)
          throw new Error("Asynchronous transform encountered during synchronous parse operation. Use .parseAsync instead.");
        return { status: r.value, value: c };
      } else
        return this._def.schema._parseAsync({ data: n.data, path: n.path, parent: n }).then((i) => Lt(i) ? Promise.resolve(a.transform(i.value, o)).then((c) => ({ status: r.value, value: c })) : i);
    R.assertNever(a);
  }
}
Pe.create = (t, e, r) => new Pe({
  schema: t,
  typeName: P.ZodEffects,
  effect: e,
  ...C(r)
});
Pe.createWithPreprocess = (t, e, r) => new Pe({
  schema: e,
  effect: { type: "preprocess", transform: t },
  typeName: P.ZodEffects,
  ...C(r)
});
class Fe extends $ {
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
class lt extends $ {
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
class Ht extends $ {
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
Ht.create = (t, e) => new Ht({
  innerType: t,
  typeName: P.ZodDefault,
  defaultValue: typeof e.default == "function" ? e.default : () => e.default,
  ...C(e)
});
class br extends $ {
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
    return gr(a) ? a.then((o) => ({
      status: "valid",
      value: o.status === "valid" ? o.value : this._def.catchValue({
        get error() {
          return new Se(n.common.issues);
        },
        input: n.data
      })
    })) : {
      status: "valid",
      value: a.status === "valid" ? a.value : this._def.catchValue({
        get error() {
          return new Se(n.common.issues);
        },
        input: n.data
      })
    };
  }
  removeCatch() {
    return this._def.innerType;
  }
}
br.create = (t, e) => new br({
  innerType: t,
  typeName: P.ZodCatch,
  catchValue: typeof e.catch == "function" ? e.catch : () => e.catch,
  ...C(e)
});
class wr extends $ {
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
wr.create = (t) => new wr({
  typeName: P.ZodNaN,
  ...C(t)
});
const cc = Symbol("zod_brand");
class ys extends $ {
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
class Jt extends $ {
  _parse(e) {
    const { status: r, ctx: n } = this._processInputParams(e);
    if (n.common.async)
      return (async () => {
        const o = await this._def.in._parseAsync({
          data: n.data,
          path: n.path,
          parent: n
        });
        return o.status === "aborted" ? A : o.status === "dirty" ? (r.dirty(), hs(o.value)) : this._def.out._parseAsync({
          data: o.value,
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
    return new Jt({
      in: e,
      out: r,
      typeName: P.ZodPipeline
    });
  }
}
class xr extends $ {
  _parse(e) {
    const r = this._def.innerType._parse(e);
    return Lt(r) && (r.value = Object.freeze(r.value)), r;
  }
}
xr.create = (t, e) => new xr({
  innerType: t,
  typeName: P.ZodReadonly,
  ...C(e)
});
const vs = (t, e = {}, r) => t ? wt.create().superRefine((n, a) => {
  var o, i;
  if (!t(n)) {
    const c = typeof e == "function" ? e(n) : typeof e == "string" ? { message: e } : e, u = (i = (o = c.fatal) !== null && o !== void 0 ? o : r) !== null && i !== void 0 ? i : !0, l = typeof c == "string" ? { message: c } : c;
    a.addIssue({ code: "custom", ...l, fatal: u });
  }
}) : wt.create(), lc = {
  object: U.lazycreate
};
var P;
(function(t) {
  t.ZodString = "ZodString", t.ZodNumber = "ZodNumber", t.ZodNaN = "ZodNaN", t.ZodBigInt = "ZodBigInt", t.ZodBoolean = "ZodBoolean", t.ZodDate = "ZodDate", t.ZodSymbol = "ZodSymbol", t.ZodUndefined = "ZodUndefined", t.ZodNull = "ZodNull", t.ZodAny = "ZodAny", t.ZodUnknown = "ZodUnknown", t.ZodNever = "ZodNever", t.ZodVoid = "ZodVoid", t.ZodArray = "ZodArray", t.ZodObject = "ZodObject", t.ZodUnion = "ZodUnion", t.ZodDiscriminatedUnion = "ZodDiscriminatedUnion", t.ZodIntersection = "ZodIntersection", t.ZodTuple = "ZodTuple", t.ZodRecord = "ZodRecord", t.ZodMap = "ZodMap", t.ZodSet = "ZodSet", t.ZodFunction = "ZodFunction", t.ZodLazy = "ZodLazy", t.ZodLiteral = "ZodLiteral", t.ZodEnum = "ZodEnum", t.ZodEffects = "ZodEffects", t.ZodNativeEnum = "ZodNativeEnum", t.ZodOptional = "ZodOptional", t.ZodNullable = "ZodNullable", t.ZodDefault = "ZodDefault", t.ZodCatch = "ZodCatch", t.ZodPromise = "ZodPromise", t.ZodBranded = "ZodBranded", t.ZodPipeline = "ZodPipeline", t.ZodReadonly = "ZodReadonly";
})(P || (P = {}));
const uc = (t, e = {
  message: `Input not instance of ${t.name}`
}) => vs((r) => r instanceof t, e), _s = xe.create, bs = qe.create, dc = wr.create, fc = Ke.create, ws = Mt.create, pc = it.create, mc = yr.create, hc = Ft.create, gc = Dt.create, yc = wt.create, vc = ot.create, _c = Ue.create, bc = vr.create, wc = Ee.create, xc = U.create, Sc = U.strictCreate, Ec = Ut.create, Pc = Rr.create, kc = jt.create, Tc = $e.create, Ic = Zt.create, Ac = _r.create, Cc = ct.create, Nc = yt.create, $c = zt.create, Oc = Gt.create, Rc = Ye.create, Lc = Bt.create, Mc = xt.create, oo = Pe.create, Fc = Fe.create, Dc = lt.create, Uc = Pe.createWithPreprocess, jc = Jt.create, Zc = () => _s().optional(), zc = () => bs().optional(), Gc = () => ws().optional(), Bc = {
  string: (t) => xe.create({ ...t, coerce: !0 }),
  number: (t) => qe.create({ ...t, coerce: !0 }),
  boolean: (t) => Mt.create({
    ...t,
    coerce: !0
  }),
  bigint: (t) => Ke.create({ ...t, coerce: !0 }),
  date: (t) => it.create({ ...t, coerce: !0 })
}, Hc = A;
var V = /* @__PURE__ */ Object.freeze({
  __proto__: null,
  defaultErrorMap: Rt,
  setErrorMap: Ki,
  getErrorMap: mr,
  makeIssue: hr,
  EMPTY_PATH: Yi,
  addIssueToContext: x,
  ParseStatus: J,
  INVALID: A,
  DIRTY: hs,
  OK: re,
  isAborted: rn,
  isDirty: nn,
  isValid: Lt,
  isAsync: gr,
  get util() {
    return R;
  },
  get objectUtil() {
    return tn;
  },
  ZodParsedType: b,
  getParsedType: Ge,
  ZodType: $,
  ZodString: xe,
  ZodNumber: qe,
  ZodBigInt: Ke,
  ZodBoolean: Mt,
  ZodDate: it,
  ZodSymbol: yr,
  ZodUndefined: Ft,
  ZodNull: Dt,
  ZodAny: wt,
  ZodUnknown: ot,
  ZodNever: Ue,
  ZodVoid: vr,
  ZodArray: Ee,
  ZodObject: U,
  ZodUnion: Ut,
  ZodDiscriminatedUnion: Rr,
  ZodIntersection: jt,
  ZodTuple: $e,
  ZodRecord: Zt,
  ZodMap: _r,
  ZodSet: ct,
  ZodFunction: yt,
  ZodLazy: zt,
  ZodLiteral: Gt,
  ZodEnum: Ye,
  ZodNativeEnum: Bt,
  ZodPromise: xt,
  ZodEffects: Pe,
  ZodTransformer: Pe,
  ZodOptional: Fe,
  ZodNullable: lt,
  ZodDefault: Ht,
  ZodCatch: br,
  ZodNaN: wr,
  BRAND: cc,
  ZodBranded: ys,
  ZodPipeline: Jt,
  ZodReadonly: xr,
  custom: vs,
  Schema: $,
  ZodSchema: $,
  late: lc,
  get ZodFirstPartyTypeKind() {
    return P;
  },
  coerce: Bc,
  any: yc,
  array: wc,
  bigint: fc,
  boolean: ws,
  date: pc,
  discriminatedUnion: Pc,
  effect: oo,
  enum: Rc,
  function: Nc,
  instanceof: uc,
  intersection: kc,
  lazy: $c,
  literal: Oc,
  map: Ac,
  nan: dc,
  nativeEnum: Lc,
  never: _c,
  null: gc,
  nullable: Dc,
  number: bs,
  object: xc,
  oboolean: Gc,
  onumber: zc,
  optional: Fc,
  ostring: Zc,
  pipeline: jc,
  preprocess: Uc,
  promise: Mc,
  record: Ic,
  set: Cc,
  strictObject: Sc,
  string: _s,
  symbol: mc,
  transformer: oo,
  tuple: Tc,
  undefined: hc,
  union: Ec,
  unknown: vc,
  void: bc,
  NEVER: Hc,
  ZodIssueCode: g,
  quotelessJson: qi,
  ZodError: Se
});
const Vc = V.object({
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
function xs(t) {
  return fetch(t).then((e) => e.json()).then((e) => {
    if (!Vc.safeParse(e).success)
      throw new Error("Invalid plugin manifest");
    return e;
  }).catch((e) => {
    throw console.error(e), e;
  });
}
function Wc(t) {
  return fetch(t).then((e) => e.text());
}
async function qc(t) {
  const e = await xs(t.manifest), r = await Wc(e.code);
  return {
    manifest: e,
    code: r
  };
}
const Kc = V.object({
  width: V.number().positive(),
  height: V.number().positive()
});
function Yc(t, e, r, n) {
  const a = document.createElement("plugin-modal");
  return a.setTheme(r), a.setAttribute("title", t), a.setAttribute("iframe-src", e), a.setAttribute("width", String(n.width || 285)), a.setAttribute("height", String(n.height || 540)), document.body.appendChild(a), a;
}
const Jc = V.function().args(V.string(), V.string(), V.enum(["dark", "light"]), Kc).implement((t, e, r, n) => Yc(t, e, r, n)), sn = [
  "pagechange",
  "filechange",
  "selectionchange",
  "themechange"
];
let an = [], ne = null;
const Ct = /* @__PURE__ */ new Map();
window.addEventListener("message", (t) => {
  for (const e of an)
    e(t.data);
});
function Xc(t, e) {
  t === "themechange" && ne && ne.setTheme(e), (Ct.get(t) || []).forEach((n) => n(e));
}
function Qc(t, e) {
  const r = () => {
    ne == null || ne.removeEventListener("close", r), ne && ne.remove(), an = [], ne = null;
  }, n = (o) => {
    if (!e.permissions.includes(o))
      throw new Error(`Permission ${o} is not granted`);
  };
  return {
    ui: {
      open: (o, i, c) => {
        const u = t.getTheme();
        ne = Jc(o, i, u, c), ne.setTheme(u), ne.addEventListener("close", r, {
          once: !0
        });
      },
      sendMessage(o) {
        const i = new CustomEvent("message", {
          detail: o
        });
        ne == null || ne.dispatchEvent(i);
      },
      onMessage: (o) => {
        V.function().parse(o), an.push(o);
      }
    },
    utils: {
      types: {
        isText(o) {
          return o.type === "text";
        },
        isRectangle(o) {
          return o.type === "rect";
        },
        isFrame(o) {
          return o.type === "frame";
        }
      }
    },
    setTimeout: V.function().args(V.function(), V.number()).implement((o, i) => {
      setTimeout(o, i);
    }),
    closePlugin: r,
    on(o, i) {
      V.enum(sn).parse(o), V.function().parse(i), o === "pagechange" ? n("page:read") : o === "filechange" ? n("file:read") : o === "selectionchange" && n("selection:read");
      const c = Ct.get(o) || [];
      c.push(i), Ct.set(o, c);
    },
    off(o, i) {
      V.enum(sn).parse(o), V.function().parse(i);
      const c = Ct.get(o) || [];
      Ct.set(
        o,
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
      return n("selection:read"), t.viewport;
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
    createText(o) {
      return t.createText(o);
    },
    createShapeFromSvg(o) {
      return t.createShapeFromSvg(o);
    },
    uploadMediaUrl(o, i) {
      return t.uploadMediaUrl(o, i);
    }
  };
}
let so = !1, or, cn = null;
function el(t) {
  cn = t;
}
const Ss = async function(t) {
  const { code: e, manifest: r } = await qc(t);
  try {
    so || (so = !0, hardenIntrinsics()), or && or.closePlugin(), cn ? (or = Qc(cn, r), new Compartment({
      penpot: harden(or),
      fetch: window.fetch.bind(window),
      console: harden(window.console)
    }).evaluate(e)) : console.error("Cannot find Penpot Context");
  } catch (n) {
    console.error(n);
  }
}, tl = `
<svg width="16"  height="16"xmlns="http://www.w3.org/2000/svg" fill="none"><g class="fills"><rect rx="0" ry="0" width="16" height="16" class="frame-background"/></g><g class="frame-children"><path d="M11.997 3.997 8 8l-3.997 4.003m-.006-8L8 8l4.003 3.997" class="fills"/><g class="strokes"><path d="M11.997 3.997 8 8l-3.997 4.003m-.006-8L8 8l4.003 3.997" style="fill: none; stroke-width: 1; stroke: rgb(143, 157, 163); stroke-opacity: 1; stroke-linecap: round;" class="stroke-shape"/></g></g></svg>`, ao = (t) => {
  t.target.tagName === "INSTALLER-MODAL" && t.stopImmediatePropagation();
};
class rl extends HTMLElement {
  constructor() {
    super(), this.dialog = null, this.attachShadow({ mode: "open" });
  }
  createPlugin(e, r) {
    var c, u;
    const n = document.createElement("li");
    n.classList.add("plugin"), n.textContent = e;
    const a = document.createElement("div");
    a.classList.add("actions");
    const o = document.createElement("button");
    o.classList.add("button"), o.textContent = "Open", o.type = "button", o.addEventListener("click", () => {
      this.closeModal(), Ss({
        manifest: r
      });
    }), a.appendChild(o);
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
    n.value = "", xs(a).then((o) => {
      if (this.createPlugin(o.name, a), !localStorage.getItem("plugins"))
        localStorage.setItem(
          "plugins",
          JSON.stringify([{ name: o.name, url: a }])
        );
      else {
        const c = this.getPlugins();
        c.push({ name: o.name, url: a }), this.savePlugins(c);
      }
      this.error(!1);
    }).catch((o) => {
      console.error(o), this.error(!0);
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
        <button type="button" class="close">${tl}</button>
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
    (r = (e = this.shadowRoot) == null ? void 0 : e.querySelector("dialog")) == null || r.close(), window.removeEventListener("paste", ao, !0);
  }
  openModal() {
    var e, r;
    (r = (e = this.shadowRoot) == null ? void 0 : e.querySelector("dialog")) == null || r.showModal(), window.addEventListener("paste", ao, !0);
  }
}
function nl() {
  customElements.define("installer-modal", rl);
  const t = document.createElement("installer-modal");
  document.body.appendChild(t), document.addEventListener("keydown", (e) => {
    var r;
    e.key.toUpperCase() === "I" && e.ctrlKey && ((r = document.querySelector("installer-modal")) == null || r.openModal());
  });
}
console.log("%c[PLUGINS] Loading plugin system", "color: #008d7c");
repairIntrinsics({
  evalTaming: "unsafeEval",
  stackFiltering: "verbose",
  errorTaming: "unsafe",
  consoleTaming: "unsafe"
});
globalThis.initPluginsRuntime = (t) => {
  if (t) {
    console.log("%c[PLUGINS] Initialize context", "color: #008d7c"), globalThis.ɵcontext = t, globalThis.ɵloadPlugin = Ss, nl(), el(t);
    for (const e of sn)
      t.addListener(e, Xc.bind(null, e));
  }
};
//# sourceMappingURL=index.js.map
