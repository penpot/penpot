var qn = (t) => {
  throw TypeError(t);
};
var Kn = (t, e, r) => e.has(t) || qn("Cannot " + r);
var at = (t, e, r) => (Kn(t, e, "read from private field"), r ? r.call(t) : e.get(t)), tn = (t, e, r) => e.has(t) ? qn("Cannot add the same private member more than once") : e instanceof WeakSet ? e.add(t) : e.set(t, r), fr = (t, e, r, n) => (Kn(t, e, "write to private field"), n ? n.call(t, r) : e.set(t, r), r);
const x = globalThis, {
  Array: na,
  ArrayBuffer: To,
  Date: oa,
  FinalizationRegistry: $t,
  Float32Array: sa,
  JSON: aa,
  Map: $e,
  Math: ia,
  Number: Io,
  Object: Tn,
  Promise: ca,
  Proxy: Ur,
  Reflect: la,
  RegExp: Xe,
  Set: Dt,
  String: _e,
  Symbol: At,
  Uint8Array: mn,
  WeakMap: ze,
  WeakSet: Ut
} = globalThis, {
  // The feral Error constructor is safe for internal use, but must not be
  // revealed to post-lockdown code in any compartment including the start
  // compartment since in V8 at least it bears stack inspection capabilities.
  Error: le,
  RangeError: ua,
  ReferenceError: Wt,
  SyntaxError: ir,
  TypeError: _,
  AggregateError: rn
} = globalThis, {
  assign: jr,
  create: H,
  defineProperties: B,
  entries: me,
  freeze: y,
  getOwnPropertyDescriptor: ee,
  getOwnPropertyDescriptors: Be,
  getOwnPropertyNames: Nt,
  getPrototypeOf: G,
  is: Zr,
  isFrozen: uu,
  isSealed: du,
  isExtensible: fu,
  keys: Co,
  prototype: zr,
  seal: pu,
  preventExtensions: da,
  setPrototypeOf: wr,
  values: Ro,
  fromEntries: bt
} = Tn, {
  species: nn,
  toStringTag: Qe,
  iterator: De,
  matchAll: $o,
  unscopables: fa,
  keyFor: pa,
  for: ha
} = At, { isInteger: ma } = Io, { stringify: No } = aa, { defineProperty: ga } = Tn, D = (t, e, r) => {
  const n = ga(t, e, r);
  if (n !== t)
    throw _(
      `Please report that the original defineProperty silently failed to set ${No(
        _e(e)
      )}. (SES_DEFINE_PROPERTY_FAILED_SILENTLY)`
    );
  return n;
}, {
  apply: ue,
  construct: Sr,
  get: ya,
  getOwnPropertyDescriptor: _a,
  has: Oo,
  isExtensible: va,
  ownKeys: qe,
  preventExtensions: ba,
  set: Mo
} = la, { isArray: pt, prototype: ve } = na, { prototype: xr } = To, { prototype: jt } = $e, { prototype: Br } = RegExp, { prototype: cr } = Dt, { prototype: Ge } = _e, { prototype: Gr } = ze, { prototype: Lo } = Ut, { prototype: Vr } = Function, { prototype: Fo } = ca, { prototype: Do } = G(
  // eslint-disable-next-line no-empty-function, func-names
  function* () {
  }
), on = G(
  // eslint-disable-next-line @endo/no-polymorphic-call
  G(ve.values())
), Uo = G(mn.prototype), { bind: gn } = Vr, P = gn.bind(gn.call), Q = P(zr.hasOwnProperty), et = P(ve.filter), ht = P(ve.forEach), Hr = P(ve.includes), Zt = P(ve.join), de = (
  /** @type {any} */
  P(ve.map)
), jo = (
  /** @type {any} */
  P(ve.flatMap)
), Er = P(ve.pop), ne = P(ve.push), wa = P(ve.slice), Zo = P(ve.some), zo = P(ve.sort), Sa = P(ve[De]), xa = P(xr.slice), Ea = P(
  // @ts-expect-error we know it is there on all conforming platforms
  ee(xr, "byteLength").get
), ka = P(Uo.set), pe = P(jt.set), Ke = P(jt.get), Wr = P(jt.has), Pa = P(jt.delete), Aa = P(jt.entries), Ta = P(jt[De]), In = P(cr.add);
P(cr.delete);
const Yn = P(cr.forEach), Cn = P(cr.has), Ia = P(cr[De]), Rn = P(Br.test), $n = P(Br.exec), Ca = P(Br[$o]), Bo = P(Ge.endsWith), Go = P(Ge.includes), Ra = P(Ge.indexOf);
P(Ge.match);
const kr = P(Do.next), Vo = P(Do.throw), Pr = (
  /** @type {any} */
  P(Ge.replace)
), $a = P(Ge.search), Nn = P(Ge.slice), On = (
  /** @type {(thisArg: string, splitter: string | RegExp | { [Symbol.split](string: string, limit?: number): string[]; }, limit?: number) => string[]} */
  P(Ge.split)
), Ho = P(Ge.startsWith), Na = P(Ge[De]), Oa = P(Gr.delete), z = P(Gr.get), Tt = P(Gr.has), he = P(Gr.set), qr = P(Lo.add), lr = P(Lo.has), Ma = P(Vr.toString), Wo = P(gn);
P(Fo.catch);
const qo = (
  /** @type {any} */
  P(Fo.then)
), La = $t && P($t.prototype.register);
$t && P($t.prototype.unregister);
const Mn = y(H(null)), ke = (t) => Tn(t) === t, Kr = (t) => t instanceof le, Ko = eval, Ee = Function, Fa = () => {
  throw _('Cannot eval with evalTaming set to "noEval" (SES_NO_EVAL)');
}, Je = ee(Error("er1"), "stack"), sn = ee(_("er2"), "stack");
let Yo, Jo;
if (Je && sn && Je.get)
  if (
    // In the v8 case as we understand it, all errors have an own stack
    // accessor property, but within the same realm, all these accessor
    // properties have the same getter and have the same setter.
    // This is therefore the case that we repair.
    typeof Je.get == "function" && Je.get === sn.get && typeof Je.set == "function" && Je.set === sn.set
  )
    Yo = y(Je.get), Jo = y(Je.set);
  else
    throw _(
      "Unexpected Error own stack accessor functions (SES_UNEXPECTED_ERROR_OWN_STACK_ACCESSOR)"
    );
const an = Yo, Da = Jo;
function Ua() {
  return this;
}
if (Ua())
  throw _("SES failed to initialize, sloppy mode (SES_NO_SLOPPY)");
const { freeze: ut } = Object, { apply: ja } = Reflect, Ln = (t) => (e, ...r) => ja(t, e, r), Za = Ln(Array.prototype.push), Jn = Ln(Array.prototype.includes), za = Ln(String.prototype.split), it = JSON.stringify, pr = (t, ...e) => {
  let r = t[0];
  for (let n = 0; n < e.length; n += 1)
    r = `${r}${e[n]}${t[n + 1]}`;
  throw Error(r);
}, Xo = (t, e = !1) => {
  const r = [], n = (c, l, u = void 0) => {
    typeof c == "string" || pr`Environment option name ${it(c)} must be a string.`, typeof l == "string" || pr`Environment option default setting ${it(
      l
    )} must be a string.`;
    let d = l;
    const f = t.process || void 0, h = typeof f == "object" && f.env || void 0;
    if (typeof h == "object" && c in h) {
      e || Za(r, c);
      const p = h[c];
      typeof p == "string" || pr`Environment option named ${it(
        c
      )}, if present, must have a corresponding string value, got ${it(
        p
      )}`, d = p;
    }
    return u === void 0 || d === l || Jn(u, d) || pr`Unrecognized ${it(c)} value ${it(
      d
    )}. Expected one of ${it([l, ...u])}`, d;
  };
  ut(n);
  const o = (c) => {
    const l = n(c, "");
    return ut(l === "" ? [] : za(l, ","));
  };
  ut(o);
  const s = (c, l) => Jn(o(c), l), i = () => ut([...r]);
  return ut(i), ut({
    getEnvironmentOption: n,
    getEnvironmentOptionsList: o,
    environmentOptionsListHas: s,
    getCapturedEnvironmentOptionNames: i
  });
};
ut(Xo);
const {
  getEnvironmentOption: ce,
  getEnvironmentOptionsList: hu,
  environmentOptionsListHas: mu
} = Xo(globalThis, !0), Ar = (t) => (t = `${t}`, t.length >= 1 && Go("aeiouAEIOU", t[0]) ? `an ${t}` : `a ${t}`);
y(Ar);
const Qo = (t, e = void 0) => {
  const r = new Dt(), n = (o, s) => {
    switch (typeof s) {
      case "object": {
        if (s === null)
          return null;
        if (Cn(r, s))
          return "[Seen]";
        if (In(r, s), Kr(s))
          return `[${s.name}: ${s.message}]`;
        if (Qe in s)
          return `[${s[Qe]}]`;
        if (pt(s))
          return s;
        const i = Co(s);
        if (i.length < 2)
          return s;
        let c = !0;
        for (let u = 1; u < i.length; u += 1)
          if (i[u - 1] >= i[u]) {
            c = !1;
            break;
          }
        if (c)
          return s;
        zo(i);
        const l = de(i, (u) => [u, s[u]]);
        return bt(l);
      }
      case "function":
        return `[Function ${s.name || "<anon>"}]`;
      case "string":
        return Ho(s, "[") ? `[${s}]` : s;
      case "undefined":
      case "symbol":
        return `[${_e(s)}]`;
      case "bigint":
        return `[${s}n]`;
      case "number":
        return Zr(s, NaN) ? "[NaN]" : s === 1 / 0 ? "[Infinity]" : s === -1 / 0 ? "[-Infinity]" : s;
      default:
        return s;
    }
  };
  try {
    return No(t, n, e);
  } catch {
    return "[Something that failed to stringify]";
  }
};
y(Qo);
const { isSafeInteger: Ba } = Number, { freeze: Et } = Object, { toStringTag: Ga } = Symbol, Xn = (t) => {
  const r = {
    next: void 0,
    prev: void 0,
    data: t
  };
  return r.next = r, r.prev = r, r;
}, Qn = (t, e) => {
  if (t === e)
    throw TypeError("Cannot splice a cell into itself");
  if (e.next !== e || e.prev !== e)
    throw TypeError("Expected self-linked cell");
  const r = e, n = t.next;
  return r.prev = t, r.next = n, t.next = r, n.prev = r, r;
}, cn = (t) => {
  const { prev: e, next: r } = t;
  e.next = r, r.prev = e, t.prev = t, t.next = t;
}, es = (t) => {
  if (!Ba(t) || t < 0)
    throw TypeError("keysBudget must be a safe non-negative integer number");
  const e = /* @__PURE__ */ new WeakMap();
  let r = 0;
  const n = Xn(void 0), o = (d) => {
    const f = e.get(d);
    if (!(f === void 0 || f.data === void 0))
      return cn(f), Qn(n, f), f;
  }, s = (d) => o(d) !== void 0;
  Et(s);
  const i = (d) => {
    const f = o(d);
    return f && f.data && f.data.get(d);
  };
  Et(i);
  const c = (d, f) => {
    if (t < 1)
      return u;
    let h = o(d);
    if (h === void 0 && (h = Xn(void 0), Qn(n, h)), !h.data)
      for (r += 1, h.data = /* @__PURE__ */ new WeakMap(), e.set(d, h); r > t; ) {
        const p = n.prev;
        cn(p), p.data = void 0, r -= 1;
      }
    return h.data.set(d, f), u;
  };
  Et(c);
  const l = (d) => {
    const f = e.get(d);
    return f === void 0 || (cn(f), e.delete(d), f.data === void 0) ? !1 : (f.data = void 0, r -= 1, !0);
  };
  Et(l);
  const u = Et({
    has: s,
    get: i,
    set: c,
    delete: l,
    // eslint-disable-next-line jsdoc/check-types
    [
      /** @type {typeof Symbol.toStringTag} */
      Ga
    ]: "LRUCacheMap"
  });
  return u;
};
Et(es);
const { freeze: vr } = Object, { isSafeInteger: Va } = Number, Ha = 1e3, Wa = 100, ts = (t = Ha, e = Wa) => {
  if (!Va(e) || e < 1)
    throw TypeError(
      "argsPerErrorBudget must be a safe positive integer number"
    );
  const r = es(t), n = (s, i) => {
    const c = r.get(s);
    c !== void 0 ? (c.length >= e && c.shift(), c.push(i)) : r.set(s, [i]);
  };
  vr(n);
  const o = (s) => {
    const i = r.get(s);
    return r.delete(s), i;
  };
  return vr(o), vr({
    addLogArgs: n,
    takeLogArgsArray: o
  });
};
vr(ts);
const Ot = new ze(), U = (t, e = void 0) => {
  const r = y({
    toString: y(() => Qo(t, e))
  });
  return he(Ot, r, t), r;
};
y(U);
const qa = y(/^[\w:-]( ?[\w:-])*$/), Tr = (t, e = void 0) => {
  if (typeof t != "string" || !Rn(qa, t))
    return U(t, e);
  const r = y({
    toString: y(() => t)
  });
  return he(Ot, r, t), r;
};
y(Tr);
const Yr = new ze(), rs = ({ template: t, args: e }) => {
  const r = [t[0]];
  for (let n = 0; n < e.length; n += 1) {
    const o = e[n];
    let s;
    Tt(Ot, o) ? s = `${o}` : Kr(o) ? s = `(${Ar(o.name)})` : s = `(${Ar(typeof o)})`, ne(r, s, t[n + 1]);
  }
  return Zt(r, "");
}, ns = y({
  toString() {
    const t = z(Yr, this);
    return t === void 0 ? "[Not a DetailsToken]" : rs(t);
  }
});
y(ns.toString);
const re = (t, ...e) => {
  const r = y({ __proto__: ns });
  return he(Yr, r, { template: t, args: e }), /** @type {DetailsToken} */
  /** @type {unknown} */
  r;
};
y(re);
const os = (t, ...e) => (e = de(
  e,
  (r) => Tt(Ot, r) ? r : U(r)
), re(t, ...e));
y(os);
const ss = ({ template: t, args: e }) => {
  const r = [t[0]];
  for (let n = 0; n < e.length; n += 1) {
    let o = e[n];
    Tt(Ot, o) && (o = z(Ot, o));
    const s = Pr(Er(r) || "", / $/, "");
    s !== "" && ne(r, s);
    const i = Pr(t[n + 1], /^ /, "");
    ne(r, o, i);
  }
  return r[r.length - 1] === "" && Er(r), r;
}, br = new ze();
let yn = 0;
const eo = new ze(), as = (t, e = t.name) => {
  let r = z(eo, t);
  return r !== void 0 || (yn += 1, r = `${e}#${yn}`, he(eo, t, r)), r;
}, Ka = (t) => {
  const e = Be(t), {
    name: r,
    message: n,
    errors: o = void 0,
    cause: s = void 0,
    stack: i = void 0,
    ...c
  } = e, l = qe(c);
  if (l.length >= 1) {
    for (const d of l)
      delete t[d];
    const u = H(zr, c);
    Jr(
      t,
      re`originally with properties ${U(u)}`
    );
  }
  for (const u of qe(t)) {
    const d = e[u];
    d && Q(d, "get") && D(t, u, {
      value: t[u]
      // invoke the getter to convert to data property
    });
  }
  y(t);
}, Ce = (t = re`Assert failed`, e = x.Error, {
  errorName: r = void 0,
  cause: n = void 0,
  errors: o = void 0,
  sanitize: s = !0
} = {}) => {
  typeof t == "string" && (t = re([t]));
  const i = z(Yr, t);
  if (i === void 0)
    throw _(`unrecognized details ${U(t)}`);
  const c = rs(i), l = n && { cause: n };
  let u;
  return typeof rn < "u" && e === rn ? u = rn(o || [], c, l) : (u = /** @type {ErrorConstructor} */
  e(
    c,
    l
  ), o !== void 0 && D(u, "errors", {
    value: o,
    writable: !0,
    enumerable: !1,
    configurable: !0
  })), he(br, u, ss(i)), r !== void 0 && as(u, r), s && Ka(u), u;
};
y(Ce);
const { addLogArgs: Ya, takeLogArgsArray: Ja } = ts(), _n = new ze(), Jr = (t, e) => {
  typeof e == "string" && (e = re([e]));
  const r = z(Yr, e);
  if (r === void 0)
    throw _(`unrecognized details ${U(e)}`);
  const n = ss(r), o = z(_n, t);
  if (o !== void 0)
    for (const s of o)
      s(t, n);
  else
    Ya(t, n);
};
y(Jr);
const Xa = (t) => {
  if (!("stack" in t))
    return "";
  const e = `${t.stack}`, r = Ra(e, `
`);
  return Ho(e, " ") || r === -1 ? e : Nn(e, r + 1);
}, Ir = {
  getStackString: x.getStackString || Xa,
  tagError: (t) => as(t),
  resetErrorTagNum: () => {
    yn = 0;
  },
  getMessageLogArgs: (t) => z(br, t),
  takeMessageLogArgs: (t) => {
    const e = z(br, t);
    return Oa(br, t), e;
  },
  takeNoteLogArgsArray: (t, e) => {
    const r = Ja(t);
    if (e !== void 0) {
      const n = z(_n, t);
      n ? ne(n, e) : he(_n, t, [e]);
    }
    return r || [];
  }
};
y(Ir);
const Xr = (t = void 0, e = !1) => {
  const r = e ? os : re, n = r`Check failed`, o = (f = n, h = void 0, p = void 0) => {
    const m = Ce(f, h, p);
    throw t !== void 0 && t(m), m;
  };
  y(o);
  const s = (f, ...h) => o(r(f, ...h));
  function i(f, h = void 0, p = void 0, m = void 0) {
    f || o(h, p, m);
  }
  const c = (f, h, p = void 0, m = void 0, A = void 0) => {
    Zr(f, h) || o(
      p || r`Expected ${f} is same as ${h}`,
      m || ua,
      A
    );
  };
  y(c);
  const l = (f, h, p) => {
    if (typeof f !== h) {
      if (typeof h == "string" || s`${U(h)} must be a string`, p === void 0) {
        const m = Ar(h);
        p = r`${f} must be ${Tr(m)}`;
      }
      o(p, _);
    }
  };
  y(l);
  const d = jr(i, {
    error: Ce,
    fail: o,
    equal: c,
    typeof: l,
    string: (f, h = void 0) => l(f, "string", h),
    note: Jr,
    details: r,
    Fail: s,
    quote: U,
    bare: Tr,
    makeAssert: Xr
  });
  return y(d);
};
y(Xr);
const Y = Xr(), to = Y.equal, is = ee(
  Uo,
  Qe
);
Y(is);
const cs = is.get;
Y(cs);
const Qa = (t) => ue(cs, t, []) !== void 0, ei = (t) => {
  const e = +_e(t);
  return ma(e) && _e(e) === t;
}, ti = (t) => {
  da(t), ht(qe(t), (e) => {
    const r = ee(t, e);
    Y(r), ei(e) || D(t, e, {
      ...r,
      writable: !1,
      configurable: !1
    });
  });
}, ri = () => {
  if (typeof x.harden == "function")
    return x.harden;
  const t = new Ut(), { harden: e } = {
    /**
     * @template T
     * @param {T} root
     * @returns {T}
     */
    harden(r) {
      const n = new Dt();
      function o(d) {
        if (!ke(d))
          return;
        const f = typeof d;
        if (f !== "object" && f !== "function")
          throw _(`Unexpected typeof: ${f}`);
        lr(t, d) || Cn(n, d) || In(n, d);
      }
      const s = (d) => {
        Qa(d) ? ti(d) : y(d);
        const f = Be(d), h = G(d);
        o(h), ht(qe(f), (p) => {
          const m = f[
            /** @type {string} */
            p
          ];
          Q(m, "value") ? o(m.value) : (o(m.get), o(m.set));
        });
      }, i = an === void 0 && Da === void 0 ? (
        // On platforms without v8's error own stack accessor problem,
        // don't pay for any extra overhead.
        s
      ) : (d) => {
        if (Kr(d)) {
          const f = ee(d, "stack");
          f && f.get === an && f.configurable && D(d, "stack", {
            // NOTE: Calls getter during harden, which seems dangerous.
            // But we're only calling the problematic getter whose
            // hazards we think we understand.
            // @ts-expect-error TS should know FERAL_STACK_GETTER
            // cannot be `undefined` here.
            // See https://github.com/endojs/endo/pull/2232#discussion_r1575179471
            value: ue(an, d, [])
          });
        }
        return s(d);
      }, c = () => {
        Yn(n, i);
      }, l = (d) => {
        qr(t, d);
      }, u = () => {
        Yn(n, l);
      };
      return o(r), c(), u(), r;
    }
  };
  return e;
}, ls = (t, e, r, n, { warn: o, error: s }) => {
  r || o(`Removing ${n}`);
  try {
    delete t[e];
  } catch (i) {
    if (Q(t, e)) {
      if (typeof t == "function" && e === "prototype" && (t.prototype = void 0, t.prototype === void 0)) {
        o(`Tolerating undeletable ${n} === undefined`);
        return;
      }
      s(`failed to delete ${n}`, i);
    } else
      s(`deleting ${n} threw`, i);
    throw i;
  }
}, us = {
  // *** Value Properties of the Global Object
  Infinity: 1 / 0,
  NaN: NaN,
  undefined: void 0
}, ds = {
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
  // https://github.com/tc39/proposal-source-phase-imports?tab=readme-ov-file#js-module-source
  ModuleSource: "ModuleSource",
  lockdown: "lockdown",
  harden: "harden",
  HandledPromise: "HandledPromise"
  // TODO: Until Promise.delegate (see below).
}, ro = {
  // *** Constructor Properties of the Global Object
  Date: "%InitialDate%",
  Error: "%InitialError%",
  RegExp: "%InitialRegExp%",
  // Omit `Symbol`, because we want the original to appear on the
  // start compartment without passing through the permits mechanism, since
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
}, fs = {
  // *** Constructor Properties of the Global Object
  Date: "%SharedDate%",
  Error: "%SharedError%",
  RegExp: "%SharedRegExp%",
  Symbol: "%SharedSymbol%",
  // *** Other Properties of the Global Object
  Math: "%SharedMath%"
}, ps = [
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
typeof AggregateError < "u" && ne(ps, AggregateError);
const vn = {
  "[[Proto]]": "%FunctionPrototype%",
  length: "number",
  name: "string"
  // Do not specify "prototype" here, since only Function instances that can
  // be used as a constructor have a prototype property. For constructors,
  // since prototype properties are instance-specific, we define it there.
}, ni = {
  // This property is not mentioned in ECMA 262, but is present in V8 and
  // necessary for lockdown to succeed.
  "[[Proto]]": "%AsyncFunctionPrototype%"
}, a = vn, no = ni, M = {
  get: a,
  set: "undefined"
}, Le = {
  get: a,
  set: a
}, oo = (t) => t === M || t === Le;
function ct(t) {
  return {
    // Properties of the NativeError Constructors
    "[[Proto]]": "%SharedError%",
    // NativeError.prototype
    prototype: t
  };
}
function lt(t) {
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
function Se(t) {
  return {
    // Properties of the TypedArray Constructors
    "[[Proto]]": "%TypedArray%",
    BYTES_PER_ELEMENT: "number",
    prototype: t
  };
}
function xe(t) {
  return {
    // Properties of the TypedArray Prototype Objects
    "[[Proto]]": "%TypedArrayPrototype%",
    BYTES_PER_ELEMENT: "number",
    constructor: t
  };
}
const so = {
  E: "number",
  LN10: "number",
  LN2: "number",
  LOG10E: "number",
  LOG2E: "number",
  PI: "number",
  SQRT1_2: "number",
  SQRT2: "number",
  "@@toStringTag": "string",
  abs: a,
  acos: a,
  acosh: a,
  asin: a,
  asinh: a,
  atan: a,
  atanh: a,
  atan2: a,
  cbrt: a,
  ceil: a,
  clz32: a,
  cos: a,
  cosh: a,
  exp: a,
  expm1: a,
  floor: a,
  fround: a,
  hypot: a,
  imul: a,
  log: a,
  log1p: a,
  log10: a,
  log2: a,
  max: a,
  min: a,
  pow: a,
  round: a,
  sign: a,
  sin: a,
  sinh: a,
  sqrt: a,
  tan: a,
  tanh: a,
  trunc: a,
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
}, Cr = {
  // ECMA https://tc39.es/ecma262
  // The intrinsics object has no prototype to avoid conflicts.
  "[[Proto]]": null,
  // %ThrowTypeError%
  "%ThrowTypeError%": a,
  // *** The Global Object
  // *** Value Properties of the Global Object
  Infinity: "number",
  NaN: "number",
  undefined: "undefined",
  // *** Function Properties of the Global Object
  // eval
  "%UniqueEval%": a,
  isFinite: a,
  isNaN: a,
  parseFloat: a,
  parseInt: a,
  decodeURI: a,
  decodeURIComponent: a,
  encodeURI: a,
  encodeURIComponent: a,
  // *** Fundamental Objects
  Object: {
    // Properties of the Object Constructor
    "[[Proto]]": "%FunctionPrototype%",
    assign: a,
    create: a,
    defineProperties: a,
    defineProperty: a,
    entries: a,
    freeze: a,
    fromEntries: a,
    getOwnPropertyDescriptor: a,
    getOwnPropertyDescriptors: a,
    getOwnPropertyNames: a,
    getOwnPropertySymbols: a,
    getPrototypeOf: a,
    hasOwn: a,
    is: a,
    isExtensible: a,
    isFrozen: a,
    isSealed: a,
    keys: a,
    preventExtensions: a,
    prototype: "%ObjectPrototype%",
    seal: a,
    setPrototypeOf: a,
    values: a,
    // https://github.com/tc39/proposal-array-grouping
    groupBy: a,
    // Seen on QuickJS
    __getClass: !1
  },
  "%ObjectPrototype%": {
    // Properties of the Object Prototype Object
    "[[Proto]]": null,
    constructor: "Object",
    hasOwnProperty: a,
    isPrototypeOf: a,
    propertyIsEnumerable: a,
    toLocaleString: a,
    toString: a,
    valueOf: a,
    // Annex B: Additional Properties of the Object.prototype Object
    // See note in header about the difference between [[Proto]] and --proto--
    // special notations.
    "--proto--": Le,
    __defineGetter__: a,
    __defineSetter__: a,
    __lookupGetter__: a,
    __lookupSetter__: a
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
    apply: a,
    bind: a,
    call: a,
    constructor: "%InertFunction%",
    toString: a,
    "@@hasInstance": a,
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
    toString: a,
    valueOf: a
  },
  "%SharedSymbol%": {
    // Properties of the Symbol Constructor
    "[[Proto]]": "%FunctionPrototype%",
    asyncDispose: "symbol",
    asyncIterator: "symbol",
    dispose: "symbol",
    for: a,
    hasInstance: "symbol",
    isConcatSpreadable: "symbol",
    iterator: "symbol",
    keyFor: a,
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
    description: M,
    toString: a,
    valueOf: a,
    "@@toPrimitive": a,
    "@@toStringTag": "string"
  },
  "%InitialError%": {
    // Properties of the Error Constructor
    "[[Proto]]": "%FunctionPrototype%",
    prototype: "%ErrorPrototype%",
    // Non standard, v8 only, used by tap
    captureStackTrace: a,
    // Non standard, v8 only, used by tap, tamed to accessor
    stackTraceLimit: Le,
    // Non standard, v8 only, used by several, tamed to accessor
    prepareStackTrace: Le
  },
  "%SharedError%": {
    // Properties of the Error Constructor
    "[[Proto]]": "%FunctionPrototype%",
    prototype: "%ErrorPrototype%",
    // Non standard, v8 only, used by tap
    captureStackTrace: a,
    // Non standard, v8 only, used by tap, tamed to accessor
    stackTraceLimit: Le,
    // Non standard, v8 only, used by several, tamed to accessor
    prepareStackTrace: Le
  },
  "%ErrorPrototype%": {
    constructor: "%SharedError%",
    message: "string",
    name: "string",
    toString: a,
    // proposed de-facto, assumed TODO
    // Seen on FF Nightly 88.0a1
    at: !1,
    // Seen on FF and XS
    stack: Le,
    // Superfluously present in some versions of V8.
    // https://github.com/tc39/notes/blob/master/meetings/2021-10/oct-26.md#:~:text=However%2C%20Chrome%2093,and%20node%2016.11.
    cause: !1
  },
  // NativeError
  EvalError: ct("%EvalErrorPrototype%"),
  RangeError: ct("%RangeErrorPrototype%"),
  ReferenceError: ct("%ReferenceErrorPrototype%"),
  SyntaxError: ct("%SyntaxErrorPrototype%"),
  TypeError: ct("%TypeErrorPrototype%"),
  URIError: ct("%URIErrorPrototype%"),
  // https://github.com/endojs/endo/issues/550
  AggregateError: ct("%AggregateErrorPrototype%"),
  "%EvalErrorPrototype%": lt("EvalError"),
  "%RangeErrorPrototype%": lt("RangeError"),
  "%ReferenceErrorPrototype%": lt("ReferenceError"),
  "%SyntaxErrorPrototype%": lt("SyntaxError"),
  "%TypeErrorPrototype%": lt("TypeError"),
  "%URIErrorPrototype%": lt("URIError"),
  // https://github.com/endojs/endo/issues/550
  "%AggregateErrorPrototype%": lt("AggregateError"),
  // *** Numbers and Dates
  Number: {
    // Properties of the Number Constructor
    "[[Proto]]": "%FunctionPrototype%",
    EPSILON: "number",
    isFinite: a,
    isInteger: a,
    isNaN: a,
    isSafeInteger: a,
    MAX_SAFE_INTEGER: "number",
    MAX_VALUE: "number",
    MIN_SAFE_INTEGER: "number",
    MIN_VALUE: "number",
    NaN: "number",
    NEGATIVE_INFINITY: "number",
    parseFloat: a,
    parseInt: a,
    POSITIVE_INFINITY: "number",
    prototype: "%NumberPrototype%"
  },
  "%NumberPrototype%": {
    // Properties of the Number Prototype Object
    constructor: "Number",
    toExponential: a,
    toFixed: a,
    toLocaleString: a,
    toPrecision: a,
    toString: a,
    valueOf: a
  },
  BigInt: {
    // Properties of the BigInt Constructor
    "[[Proto]]": "%FunctionPrototype%",
    asIntN: a,
    asUintN: a,
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
    toLocaleString: a,
    toString: a,
    valueOf: a,
    "@@toStringTag": "string"
  },
  "%InitialMath%": {
    ...so,
    // `%InitialMath%.random()` has the standard unsafe behavior
    random: a
  },
  "%SharedMath%": {
    ...so,
    // `%SharedMath%.random()` is tamed to always throw
    random: a
  },
  "%InitialDate%": {
    // Properties of the Date Constructor
    "[[Proto]]": "%FunctionPrototype%",
    now: a,
    parse: a,
    prototype: "%DatePrototype%",
    UTC: a
  },
  "%SharedDate%": {
    // Properties of the Date Constructor
    "[[Proto]]": "%FunctionPrototype%",
    // `%SharedDate%.now()` is tamed to always throw
    now: a,
    parse: a,
    prototype: "%DatePrototype%",
    UTC: a
  },
  "%DatePrototype%": {
    constructor: "%SharedDate%",
    getDate: a,
    getDay: a,
    getFullYear: a,
    getHours: a,
    getMilliseconds: a,
    getMinutes: a,
    getMonth: a,
    getSeconds: a,
    getTime: a,
    getTimezoneOffset: a,
    getUTCDate: a,
    getUTCDay: a,
    getUTCFullYear: a,
    getUTCHours: a,
    getUTCMilliseconds: a,
    getUTCMinutes: a,
    getUTCMonth: a,
    getUTCSeconds: a,
    setDate: a,
    setFullYear: a,
    setHours: a,
    setMilliseconds: a,
    setMinutes: a,
    setMonth: a,
    setSeconds: a,
    setTime: a,
    setUTCDate: a,
    setUTCFullYear: a,
    setUTCHours: a,
    setUTCMilliseconds: a,
    setUTCMinutes: a,
    setUTCMonth: a,
    setUTCSeconds: a,
    toDateString: a,
    toISOString: a,
    toJSON: a,
    toLocaleDateString: a,
    toLocaleString: a,
    toLocaleTimeString: a,
    toString: a,
    toTimeString: a,
    toUTCString: a,
    valueOf: a,
    "@@toPrimitive": a,
    // Annex B: Additional Properties of the Date.prototype Object
    getYear: a,
    setYear: a,
    toGMTString: a
  },
  // Text Processing
  String: {
    // Properties of the String Constructor
    "[[Proto]]": "%FunctionPrototype%",
    fromCharCode: a,
    fromCodePoint: a,
    prototype: "%StringPrototype%",
    raw: a,
    // See https://github.com/Moddable-OpenSource/moddable/issues/523
    fromArrayBuffer: !1
  },
  "%StringPrototype%": {
    // Properties of the String Prototype Object
    length: "number",
    at: a,
    charAt: a,
    charCodeAt: a,
    codePointAt: a,
    concat: a,
    constructor: "String",
    endsWith: a,
    includes: a,
    indexOf: a,
    lastIndexOf: a,
    localeCompare: a,
    match: a,
    matchAll: a,
    normalize: a,
    padEnd: a,
    padStart: a,
    repeat: a,
    replace: a,
    replaceAll: a,
    // ES2021
    search: a,
    slice: a,
    split: a,
    startsWith: a,
    substring: a,
    toLocaleLowerCase: a,
    toLocaleUpperCase: a,
    toLowerCase: a,
    toString: a,
    toUpperCase: a,
    trim: a,
    trimEnd: a,
    trimStart: a,
    valueOf: a,
    "@@iterator": a,
    // Annex B: Additional Properties of the String.prototype Object
    substr: a,
    anchor: a,
    big: a,
    blink: a,
    bold: a,
    fixed: a,
    fontcolor: a,
    fontsize: a,
    italics: a,
    link: a,
    small: a,
    strike: a,
    sub: a,
    sup: a,
    trimLeft: a,
    trimRight: a,
    // See https://github.com/Moddable-OpenSource/moddable/issues/523
    compare: !1,
    // https://github.com/tc39/proposal-is-usv-string
    isWellFormed: a,
    toWellFormed: a,
    unicodeSets: a,
    // Seen on QuickJS
    __quote: !1
  },
  "%StringIteratorPrototype%": {
    "[[Proto]]": "%IteratorPrototype%",
    next: a,
    "@@toStringTag": "string"
  },
  "%InitialRegExp%": {
    // Properties of the RegExp Constructor
    "[[Proto]]": "%FunctionPrototype%",
    prototype: "%RegExpPrototype%",
    "@@species": M,
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
    "@@species": M
  },
  "%RegExpPrototype%": {
    // Properties of the RegExp Prototype Object
    constructor: "%SharedRegExp%",
    exec: a,
    dotAll: M,
    flags: M,
    global: M,
    hasIndices: M,
    ignoreCase: M,
    "@@match": a,
    "@@matchAll": a,
    multiline: M,
    "@@replace": a,
    "@@search": a,
    source: M,
    "@@split": a,
    sticky: M,
    test: a,
    toString: a,
    unicode: M,
    unicodeSets: M,
    // Annex B: Additional Properties of the RegExp.prototype Object
    compile: !1
    // UNSAFE and suppressed.
  },
  "%RegExpStringIteratorPrototype%": {
    // The %RegExpStringIteratorPrototype% Object
    "[[Proto]]": "%IteratorPrototype%",
    next: a,
    "@@toStringTag": "string"
  },
  // Indexed Collections
  Array: {
    // Properties of the Array Constructor
    "[[Proto]]": "%FunctionPrototype%",
    from: a,
    isArray: a,
    of: a,
    prototype: "%ArrayPrototype%",
    "@@species": M,
    // Stage 3:
    // https://tc39.es/proposal-relative-indexing-method/
    at: a,
    // https://tc39.es/proposal-array-from-async/
    fromAsync: a
  },
  "%ArrayPrototype%": {
    // Properties of the Array Prototype Object
    at: a,
    length: "number",
    concat: a,
    constructor: "Array",
    copyWithin: a,
    entries: a,
    every: a,
    fill: a,
    filter: a,
    find: a,
    findIndex: a,
    flat: a,
    flatMap: a,
    forEach: a,
    includes: a,
    indexOf: a,
    join: a,
    keys: a,
    lastIndexOf: a,
    map: a,
    pop: a,
    push: a,
    reduce: a,
    reduceRight: a,
    reverse: a,
    shift: a,
    slice: a,
    some: a,
    sort: a,
    splice: a,
    toLocaleString: a,
    toString: a,
    unshift: a,
    values: a,
    "@@iterator": a,
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
    findLast: a,
    findLastIndex: a,
    // https://github.com/tc39/proposal-change-array-by-copy
    toReversed: a,
    toSorted: a,
    toSpliced: a,
    with: a,
    // https://github.com/tc39/proposal-array-grouping
    group: a,
    // Not in proposal? Where?
    groupToMap: a,
    // Not in proposal? Where?
    groupBy: a
  },
  "%ArrayIteratorPrototype%": {
    // The %ArrayIteratorPrototype% Object
    "[[Proto]]": "%IteratorPrototype%",
    next: a,
    "@@toStringTag": "string"
  },
  // *** TypedArray Objects
  "%TypedArray%": {
    // Properties of the %TypedArray% Intrinsic Object
    "[[Proto]]": "%FunctionPrototype%",
    from: a,
    of: a,
    prototype: "%TypedArrayPrototype%",
    "@@species": M
  },
  "%TypedArrayPrototype%": {
    at: a,
    buffer: M,
    byteLength: M,
    byteOffset: M,
    constructor: "%TypedArray%",
    copyWithin: a,
    entries: a,
    every: a,
    fill: a,
    filter: a,
    find: a,
    findIndex: a,
    forEach: a,
    includes: a,
    indexOf: a,
    join: a,
    keys: a,
    lastIndexOf: a,
    length: M,
    map: a,
    reduce: a,
    reduceRight: a,
    reverse: a,
    set: a,
    slice: a,
    some: a,
    sort: a,
    subarray: a,
    toLocaleString: a,
    toString: a,
    values: a,
    "@@iterator": a,
    "@@toStringTag": M,
    // See https://github.com/tc39/proposal-array-find-from-last
    findLast: a,
    findLastIndex: a,
    // https://github.com/tc39/proposal-change-array-by-copy
    toReversed: a,
    toSorted: a,
    with: a
  },
  // The TypedArray Constructors
  BigInt64Array: Se("%BigInt64ArrayPrototype%"),
  BigUint64Array: Se("%BigUint64ArrayPrototype%"),
  // https://github.com/tc39/proposal-float16array
  Float16Array: Se("%Float16ArrayPrototype%"),
  Float32Array: Se("%Float32ArrayPrototype%"),
  Float64Array: Se("%Float64ArrayPrototype%"),
  Int16Array: Se("%Int16ArrayPrototype%"),
  Int32Array: Se("%Int32ArrayPrototype%"),
  Int8Array: Se("%Int8ArrayPrototype%"),
  Uint16Array: Se("%Uint16ArrayPrototype%"),
  Uint32Array: Se("%Uint32ArrayPrototype%"),
  Uint8ClampedArray: Se("%Uint8ClampedArrayPrototype%"),
  Uint8Array: {
    ...Se("%Uint8ArrayPrototype%"),
    // https://github.com/tc39/proposal-arraybuffer-base64
    fromBase64: a,
    // https://github.com/tc39/proposal-arraybuffer-base64
    fromHex: a
  },
  "%BigInt64ArrayPrototype%": xe("BigInt64Array"),
  "%BigUint64ArrayPrototype%": xe("BigUint64Array"),
  // https://github.com/tc39/proposal-float16array
  "%Float16ArrayPrototype%": xe("Float16Array"),
  "%Float32ArrayPrototype%": xe("Float32Array"),
  "%Float64ArrayPrototype%": xe("Float64Array"),
  "%Int16ArrayPrototype%": xe("Int16Array"),
  "%Int32ArrayPrototype%": xe("Int32Array"),
  "%Int8ArrayPrototype%": xe("Int8Array"),
  "%Uint16ArrayPrototype%": xe("Uint16Array"),
  "%Uint32ArrayPrototype%": xe("Uint32Array"),
  "%Uint8ClampedArrayPrototype%": xe("Uint8ClampedArray"),
  "%Uint8ArrayPrototype%": {
    ...xe("Uint8Array"),
    // https://github.com/tc39/proposal-arraybuffer-base64
    setFromBase64: a,
    // https://github.com/tc39/proposal-arraybuffer-base64
    setFromHex: a,
    // https://github.com/tc39/proposal-arraybuffer-base64
    toBase64: a,
    // https://github.com/tc39/proposal-arraybuffer-base64
    toHex: a
  },
  // *** Keyed Collections
  Map: {
    // Properties of the Map Constructor
    "[[Proto]]": "%FunctionPrototype%",
    "@@species": M,
    prototype: "%MapPrototype%",
    // https://github.com/tc39/proposal-array-grouping
    groupBy: a
  },
  "%MapPrototype%": {
    clear: a,
    constructor: "Map",
    delete: a,
    entries: a,
    forEach: a,
    get: a,
    has: a,
    keys: a,
    set: a,
    size: M,
    values: a,
    "@@iterator": a,
    "@@toStringTag": "string"
  },
  "%MapIteratorPrototype%": {
    // The %MapIteratorPrototype% Object
    "[[Proto]]": "%IteratorPrototype%",
    next: a,
    "@@toStringTag": "string"
  },
  Set: {
    // Properties of the Set Constructor
    "[[Proto]]": "%FunctionPrototype%",
    prototype: "%SetPrototype%",
    "@@species": M,
    // Seen on QuickJS
    groupBy: !1
  },
  "%SetPrototype%": {
    add: a,
    clear: a,
    constructor: "Set",
    delete: a,
    entries: a,
    forEach: a,
    has: a,
    keys: a,
    size: M,
    values: a,
    "@@iterator": a,
    "@@toStringTag": "string",
    // See https://github.com/tc39/proposal-set-methods
    intersection: a,
    // See https://github.com/tc39/proposal-set-methods
    union: a,
    // See https://github.com/tc39/proposal-set-methods
    difference: a,
    // See https://github.com/tc39/proposal-set-methods
    symmetricDifference: a,
    // See https://github.com/tc39/proposal-set-methods
    isSubsetOf: a,
    // See https://github.com/tc39/proposal-set-methods
    isSupersetOf: a,
    // See https://github.com/tc39/proposal-set-methods
    isDisjointFrom: a
  },
  "%SetIteratorPrototype%": {
    // The %SetIteratorPrototype% Object
    "[[Proto]]": "%IteratorPrototype%",
    next: a,
    "@@toStringTag": "string"
  },
  WeakMap: {
    // Properties of the WeakMap Constructor
    "[[Proto]]": "%FunctionPrototype%",
    prototype: "%WeakMapPrototype%"
  },
  "%WeakMapPrototype%": {
    constructor: "WeakMap",
    delete: a,
    get: a,
    has: a,
    set: a,
    "@@toStringTag": "string"
  },
  WeakSet: {
    // Properties of the WeakSet Constructor
    "[[Proto]]": "%FunctionPrototype%",
    prototype: "%WeakSetPrototype%"
  },
  "%WeakSetPrototype%": {
    add: a,
    constructor: "WeakSet",
    delete: a,
    has: a,
    "@@toStringTag": "string"
  },
  // *** Structured Data
  ArrayBuffer: {
    // Properties of the ArrayBuffer Constructor
    "[[Proto]]": "%FunctionPrototype%",
    isView: a,
    prototype: "%ArrayBufferPrototype%",
    "@@species": M,
    // See https://github.com/Moddable-OpenSource/moddable/issues/523
    fromString: !1,
    // See https://github.com/Moddable-OpenSource/moddable/issues/523
    fromBigInt: !1
  },
  "%ArrayBufferPrototype%": {
    byteLength: M,
    constructor: "ArrayBuffer",
    slice: a,
    "@@toStringTag": "string",
    // See https://github.com/Moddable-OpenSource/moddable/issues/523
    concat: !1,
    // See https://github.com/tc39/proposal-resizablearraybuffer
    transfer: a,
    resize: a,
    resizable: M,
    maxByteLength: M,
    // https://github.com/tc39/proposal-arraybuffer-transfer
    transferToFixedLength: a,
    detached: M
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
    buffer: M,
    byteLength: M,
    byteOffset: M,
    constructor: "DataView",
    getBigInt64: a,
    getBigUint64: a,
    // https://github.com/tc39/proposal-float16array
    getFloat16: a,
    getFloat32: a,
    getFloat64: a,
    getInt8: a,
    getInt16: a,
    getInt32: a,
    getUint8: a,
    getUint16: a,
    getUint32: a,
    setBigInt64: a,
    setBigUint64: a,
    // https://github.com/tc39/proposal-float16array
    setFloat16: a,
    setFloat32: a,
    setFloat64: a,
    setInt8: a,
    setInt16: a,
    setInt32: a,
    setUint8: a,
    setUint16: a,
    setUint32: a,
    "@@toStringTag": "string"
  },
  // Atomics
  Atomics: !1,
  // UNSAFE and suppressed.
  JSON: {
    parse: a,
    stringify: a,
    "@@toStringTag": "string",
    // https://github.com/tc39/proposal-json-parse-with-source/
    rawJSON: a,
    isRawJSON: a
  },
  // *** Control Abstraction Objects
  // https://github.com/tc39/proposal-iterator-helpers
  Iterator: {
    // Properties of the Iterator Constructor
    "[[Proto]]": "%FunctionPrototype%",
    prototype: "%IteratorPrototype%",
    from: a
  },
  "%IteratorPrototype%": {
    // The %IteratorPrototype% Object
    "@@iterator": a,
    // https://github.com/tc39/proposal-iterator-helpers
    constructor: "Iterator",
    map: a,
    filter: a,
    take: a,
    drop: a,
    flatMap: a,
    reduce: a,
    toArray: a,
    forEach: a,
    some: a,
    every: a,
    find: a,
    "@@toStringTag": "string",
    // https://github.com/tc39/proposal-async-iterator-helpers
    toAsync: a,
    // See https://github.com/Moddable-OpenSource/moddable/issues/523#issuecomment-1942904505
    "@@dispose": !1
  },
  // https://github.com/tc39/proposal-iterator-helpers
  "%WrapForValidIteratorPrototype%": {
    "[[Proto]]": "%IteratorPrototype%",
    next: a,
    return: a
  },
  // https://github.com/tc39/proposal-iterator-helpers
  "%IteratorHelperPrototype%": {
    "[[Proto]]": "%IteratorPrototype%",
    next: a,
    return: a,
    "@@toStringTag": "string"
  },
  // https://github.com/tc39/proposal-async-iterator-helpers
  AsyncIterator: {
    // Properties of the Iterator Constructor
    "[[Proto]]": "%FunctionPrototype%",
    prototype: "%AsyncIteratorPrototype%",
    from: a
  },
  "%AsyncIteratorPrototype%": {
    // The %AsyncIteratorPrototype% Object
    "@@asyncIterator": a,
    // https://github.com/tc39/proposal-async-iterator-helpers
    constructor: "AsyncIterator",
    map: a,
    filter: a,
    take: a,
    drop: a,
    flatMap: a,
    reduce: a,
    toArray: a,
    forEach: a,
    some: a,
    every: a,
    find: a,
    "@@toStringTag": "string",
    // See https://github.com/Moddable-OpenSource/moddable/issues/523#issuecomment-1942904505
    "@@asyncDispose": !1
  },
  // https://github.com/tc39/proposal-async-iterator-helpers
  "%WrapForValidAsyncIteratorPrototype%": {
    "[[Proto]]": "%AsyncIteratorPrototype%",
    next: a,
    return: a
  },
  // https://github.com/tc39/proposal-async-iterator-helpers
  "%AsyncIteratorHelperPrototype%": {
    "[[Proto]]": "%AsyncIteratorPrototype%",
    next: a,
    return: a,
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
    next: a,
    return: a,
    throw: a,
    "@@toStringTag": "string"
  },
  "%AsyncGeneratorPrototype%": {
    // Properties of the AsyncGenerator Prototype Object
    "[[Proto]]": "%AsyncIteratorPrototype%",
    constructor: "%AsyncGenerator%",
    next: a,
    return: a,
    throw: a,
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
  // another permits change to update to the current proposed standard.
  HandledPromise: {
    "[[Proto]]": "Promise",
    applyFunction: a,
    applyFunctionSendOnly: a,
    applyMethod: a,
    applyMethodSendOnly: a,
    get: a,
    getSendOnly: a,
    prototype: "%PromisePrototype%",
    resolve: a
  },
  // https://github.com/tc39/proposal-source-phase-imports?tab=readme-ov-file#js-module-source
  ModuleSource: {
    "[[Proto]]": "%AbstractModuleSource%",
    prototype: "%ModuleSourcePrototype%"
  },
  "%ModuleSourcePrototype%": {
    "[[Proto]]": "%AbstractModuleSourcePrototype%",
    constructor: "ModuleSource",
    "@@toStringTag": "string",
    // https://github.com/tc39/proposal-compartments
    bindings: M,
    needsImport: M,
    needsImportMeta: M
  },
  "%AbstractModuleSource%": {
    "[[Proto]]": "%FunctionPrototype%",
    prototype: "%AbstractModuleSourcePrototype%"
  },
  "%AbstractModuleSourcePrototype%": {
    constructor: "%AbstractModuleSource%"
  },
  Promise: {
    // Properties of the Promise Constructor
    "[[Proto]]": "%FunctionPrototype%",
    all: a,
    allSettled: a,
    // https://github.com/Agoric/SES-shim/issues/550
    any: a,
    prototype: "%PromisePrototype%",
    race: a,
    reject: a,
    resolve: a,
    // https://github.com/tc39/proposal-promise-with-resolvers
    withResolvers: a,
    "@@species": M,
    // https://github.com/tc39/proposal-promise-try
    try: a
  },
  "%PromisePrototype%": {
    // Properties of the Promise Prototype Object
    catch: a,
    constructor: "Promise",
    finally: a,
    then: a,
    "@@toStringTag": "string",
    // Non-standard, used in node to prevent async_hooks from breaking
    "UniqueSymbol(async_id_symbol)": Le,
    "UniqueSymbol(trigger_async_id_symbol)": Le,
    "UniqueSymbol(destroyed)": Le
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
    apply: a,
    construct: a,
    defineProperty: a,
    deleteProperty: a,
    get: a,
    getOwnPropertyDescriptor: a,
    getPrototypeOf: a,
    has: a,
    isExtensible: a,
    ownKeys: a,
    preventExtensions: a,
    set: a,
    setPrototypeOf: a,
    "@@toStringTag": "string"
  },
  Proxy: {
    // Properties of the Proxy Constructor
    "[[Proto]]": "%FunctionPrototype%",
    revocable: a
  },
  // Appendix B
  // Annex B: Additional Properties of the Global Object
  escape: a,
  unescape: a,
  // Proposed
  "%UniqueCompartment%": {
    "[[Proto]]": "%FunctionPrototype%",
    prototype: "%CompartmentPrototype%",
    toString: a
  },
  "%InertCompartment%": {
    "[[Proto]]": "%FunctionPrototype%",
    prototype: "%CompartmentPrototype%",
    toString: a
  },
  "%CompartmentPrototype%": {
    constructor: "%InertCompartment%",
    evaluate: a,
    globalThis: M,
    name: M,
    import: no,
    load: no,
    importNow: a,
    module: a,
    "@@toStringTag": "string"
  },
  lockdown: a,
  harden: { ...a, isFake: "boolean" },
  "%InitialGetStackString%": a
}, oi = (t) => typeof t == "function";
function si(t, e, r) {
  if (Q(t, e)) {
    const n = ee(t, e);
    if (!n || !Zr(n.value, r.value) || n.get !== r.get || n.set !== r.set || n.writable !== r.writable || n.enumerable !== r.enumerable || n.configurable !== r.configurable)
      throw _(`Conflicting definitions of ${e}`);
  }
  D(t, e, r);
}
function ai(t, e) {
  for (const [r, n] of me(e))
    si(t, r, n);
}
function hs(t, e) {
  const r = { __proto__: null };
  for (const [n, o] of me(e))
    Q(t, n) && (r[o] = t[n]);
  return r;
}
const ms = (t) => {
  const e = H(null);
  let r;
  const n = (l) => {
    ai(e, Be(l));
  };
  y(n);
  const o = () => {
    for (const [l, u] of me(e)) {
      if (!ke(u) || !Q(u, "prototype"))
        continue;
      const d = Cr[l];
      if (typeof d != "object")
        throw _(`Expected permit object at permits.${l}`);
      const f = d.prototype;
      if (!f) {
        ls(
          u,
          "prototype",
          !1,
          `${l}.prototype`,
          t
        );
        continue;
      }
      if (typeof f != "string" || !Q(Cr, f))
        throw _(`Unrecognized ${l}.prototype permits entry`);
      const h = u.prototype;
      if (Q(e, f)) {
        if (e[f] !== h)
          throw _(`Conflicting bindings of ${f}`);
        continue;
      }
      e[f] = h;
    }
  };
  y(o);
  const s = () => (y(e), r = new Ut(et(Ro(e), oi)), e);
  y(s);
  const i = (l) => {
    if (!r)
      throw _(
        "isPseudoNative can only be called after finalIntrinsics"
      );
    return lr(r, l);
  };
  y(i);
  const c = {
    addIntrinsics: n,
    completePrototypes: o,
    finalIntrinsics: s,
    isPseudoNative: i
  };
  return y(c), n(us), n(hs(x, ds)), c;
}, ii = (t, e) => {
  const { addIntrinsics: r, finalIntrinsics: n } = ms(e);
  return r(hs(t, fs)), n();
};
function ci(t, e, r) {
  const n = ["undefined", "boolean", "number", "string", "symbol"], o = new $e(
    At ? de(
      et(
        me(Cr["%SharedSymbol%"]),
        ([f, h]) => h === "symbol" && typeof At[f] == "symbol"
      ),
      ([f]) => [At[f], `@@${f}`]
    ) : []
  );
  function s(f, h) {
    if (typeof h == "string")
      return h;
    const p = Ke(o, h);
    if (typeof h == "symbol") {
      if (p)
        return p;
      {
        const m = pa(h);
        return m !== void 0 ? `RegisteredSymbol(${m})` : `Unique${_e(h)}`;
      }
    }
    throw _(`Unexpected property name type ${f} ${h}`);
  }
  function i(f, h, p) {
    if (!ke(h))
      throw _(`Object expected: ${f}, ${h}, ${p}`);
    const m = G(h);
    if (!(m === null && p === null)) {
      if (p !== void 0 && typeof p != "string")
        throw _(`Malformed permit ${f}.__proto__`);
      if (m !== t[p || "%ObjectPrototype%"])
        throw _(
          `Unexpected [[Prototype]] at ${f}.__proto__ (expected ${p || "%ObjectPrototype%"})`
        );
    }
  }
  function c(f, h, p, m) {
    if (typeof m == "object")
      return d(f, h, m), !0;
    if (m === !1)
      return !1;
    if (typeof m == "string") {
      if (p === "prototype" || p === "constructor") {
        if (Q(t, m)) {
          if (h !== t[m])
            throw _(`Does not match permit for ${f}`);
          return !0;
        }
      } else if (Hr(n, m)) {
        if (typeof h !== m)
          throw _(
            `At ${f} expected ${m} not ${typeof h}`
          );
        return !0;
      }
    }
    throw _(
      `Unexpected property ${p} with permit ${m} at ${f}`
    );
  }
  function l(f, h, p, m) {
    const A = ee(h, p);
    if (!A)
      throw _(`Property ${p} not found at ${f}`);
    if (Q(A, "value")) {
      if (oo(m))
        throw _(`Accessor expected at ${f}`);
      return c(f, A.value, p, m);
    }
    if (!oo(m))
      throw _(`Accessor not expected at ${f}`);
    return c(`${f}<get>`, A.get, p, m.get) && c(`${f}<set>`, A.set, p, m.set);
  }
  function u(f, h, p) {
    const m = p === "__proto__" ? "--proto--" : p;
    if (Q(h, m))
      return h[m];
    if (typeof f == "function" && Q(vn, m))
      return vn[m];
  }
  function d(f, h, p) {
    if (h == null)
      return;
    const m = p["[[Proto]]"];
    i(f, h, m), typeof h == "function" && e(h);
    for (const A of qe(h)) {
      const S = s(f, A), w = `${f}.${S}`, R = u(h, p, S);
      (!R || !l(w, h, A, R)) && ls(h, A, R === !1, w, r);
    }
  }
  d("intrinsics", t, Cr);
}
function li() {
  try {
    Ee.prototype.constructor("return 1");
  } catch {
    return y({});
  }
  const t = {};
  function e(r, n, o) {
    let s;
    try {
      s = (0, eval)(o);
    } catch (l) {
      if (l instanceof ir)
        return;
      throw l;
    }
    const i = G(s), c = function() {
      throw _(
        "Function.prototype.constructor is not a valid constructor."
      );
    };
    B(c, {
      prototype: { value: i },
      name: {
        value: r,
        writable: !1,
        enumerable: !1,
        configurable: !0
      }
    }), B(i, {
      constructor: { value: c }
    }), c !== Ee.prototype.constructor && wr(c, Ee.prototype.constructor), t[n] = c;
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
function ui(t = "safe") {
  if (t !== "safe" && t !== "unsafe")
    throw _(`unrecognized dateTaming ${t}`);
  const e = oa, r = e.prototype, n = {
    /**
     * `%SharedDate%.now()` throw a `TypeError` starting with "secure mode".
     * See https://github.com/endojs/endo/issues/910#issuecomment-1581855420
     */
    now() {
      throw _("secure mode Calling %SharedDate%.now() throws");
    }
  }, o = ({ powers: c = "none" } = {}) => {
    let l;
    return c === "original" ? l = function(...d) {
      return new.target === void 0 ? ue(e, void 0, d) : Sr(e, d, new.target);
    } : l = function(...d) {
      if (new.target === void 0)
        throw _(
          "secure mode Calling %SharedDate% constructor as a function throws"
        );
      if (d.length === 0)
        throw _(
          "secure mode Calling new %SharedDate%() with no arguments throws"
        );
      return Sr(e, d, new.target);
    }, B(l, {
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
  }, s = o({ powers: "original" }), i = o({ powers: "none" });
  return B(s, {
    now: {
      value: e.now,
      writable: !0,
      enumerable: !1,
      configurable: !0
    }
  }), B(i, {
    now: {
      value: n.now,
      writable: !0,
      enumerable: !1,
      configurable: !0
    }
  }), B(r, {
    constructor: { value: i }
  }), {
    "%InitialDate%": s,
    "%SharedDate%": i
  };
}
function di(t = "safe") {
  if (t !== "safe" && t !== "unsafe")
    throw _(`unrecognized mathTaming ${t}`);
  const e = ia, r = e, { random: n, ...o } = Be(e), i = H(zr, {
    ...o,
    random: {
      value: {
        /**
         * `%SharedMath%.random()` throws a TypeError starting with "secure mode".
         * See https://github.com/endojs/endo/issues/910#issuecomment-1581855420
         */
        random() {
          throw _("secure mode %SharedMath%.random() throws");
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
function fi(t = "safe") {
  if (t !== "safe" && t !== "unsafe")
    throw _(`unrecognized regExpTaming ${t}`);
  const e = Xe.prototype, r = (s = {}) => {
    const i = function(...l) {
      return new.target === void 0 ? Xe(...l) : Sr(Xe, l, new.target);
    };
    if (B(i, {
      length: { value: 2 },
      prototype: {
        value: e,
        writable: !1,
        enumerable: !1,
        configurable: !1
      }
    }), nn) {
      const c = ee(
        Xe,
        nn
      );
      if (!c)
        throw _("no RegExp[Symbol.species] descriptor");
      B(i, {
        [nn]: c
      });
    }
    return i;
  }, n = r(), o = r();
  return t !== "unsafe" && delete e.compile, B(e, {
    constructor: { value: o }
  }), {
    "%InitialRegExp%": n,
    "%SharedRegExp%": o
  };
}
const pi = {
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
    [Qe]: !0
  }
}, gs = {
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
    [De]: !0
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
    [Qe]: !0
  }
}, hi = {
  ...gs,
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
function mi(t, e, { warn: r }, n = []) {
  const o = new Dt(n);
  function s(d, f, h, p) {
    if ("value" in p && p.configurable) {
      const { value: m } = p, A = Cn(o, h), { get: S, set: w } = ee(
        {
          get [h]() {
            return m;
          },
          set [h](R) {
            if (f === this)
              throw _(
                `Cannot assign to read only property '${_e(
                  h
                )}' of '${d}'`
              );
            Q(this, h) ? this[h] = R : (A && r(_(`Override property ${h}`)), D(this, h, {
              value: R,
              writable: !0,
              enumerable: !0,
              configurable: !0
            }));
          }
        },
        h
      );
      D(S, "originalValue", {
        value: m,
        writable: !1,
        enumerable: !1,
        configurable: !1
      }), D(f, h, {
        get: S,
        set: w,
        enumerable: p.enumerable,
        configurable: p.configurable
      });
    }
  }
  function i(d, f, h) {
    const p = ee(f, h);
    p && s(d, f, h, p);
  }
  function c(d, f) {
    const h = Be(f);
    h && ht(qe(h), (p) => s(d, f, p, h[p]));
  }
  function l(d, f, h) {
    for (const p of qe(h)) {
      const m = ee(f, p);
      if (!m || m.get || m.set)
        continue;
      const A = `${d}.${_e(p)}`, S = h[p];
      if (S === !0)
        i(A, f, p);
      else if (S === "*")
        c(A, m.value);
      else if (ke(S))
        l(A, m.value, S);
      else
        throw _(`Unexpected override enablement plan ${A}`);
    }
  }
  let u;
  switch (e) {
    case "min": {
      u = pi;
      break;
    }
    case "moderate": {
      u = gs;
      break;
    }
    case "severe": {
      u = hi;
      break;
    }
    default:
      throw _(`unrecognized overrideTaming ${e}`);
  }
  l("root", t, u);
}
const { Fail: bn, quote: Rr } = Y, gi = /^(\w*[a-z])Locale([A-Z]\w*)$/, ys = {
  // See https://tc39.es/ecma262/#sec-string.prototype.localecompare
  localeCompare(t) {
    if (this === null || this === void 0)
      throw _(
        'Cannot localeCompare with null or undefined "this" value'
      );
    const e = `${this}`, r = `${t}`;
    return e < r ? -1 : e > r ? 1 : (e === r || bn`expected ${Rr(e)} and ${Rr(r)} to compare`, 0);
  },
  toString() {
    return `${this}`;
  }
}, yi = ys.localeCompare, _i = ys.toString;
function vi(t, e = "safe") {
  if (e !== "safe" && e !== "unsafe")
    throw _(`unrecognized localeTaming ${e}`);
  if (e !== "unsafe") {
    D(_e.prototype, "localeCompare", {
      value: yi
    });
    for (const r of Nt(t)) {
      const n = t[r];
      if (ke(n))
        for (const o of Nt(n)) {
          const s = $n(gi, o);
          if (s) {
            typeof n[o] == "function" || bn`expected ${Rr(o)} to be a function`;
            const i = `${s[1]}${s[2]}`, c = n[i];
            typeof c == "function" || bn`function ${Rr(i)} not found`, D(n, o, { value: c });
          }
        }
    }
    D(Io.prototype, "toLocaleString", {
      value: _i
    });
  }
}
const bi = (t) => ({
  eval(r) {
    return typeof r != "string" ? r : t(r);
  }
}).eval, { Fail: ao } = Y, wi = (t) => {
  const e = function(n) {
    const o = `${Er(arguments) || ""}`, s = `${Zt(arguments, ",")}`;
    new Ee(s, ""), new Ee(o);
    const i = `(function anonymous(${s}
) {
${o}
})`;
    return t(i);
  };
  return B(e, {
    // Ensure that any function created in any evaluator in a realm is an
    // instance of Function in any evaluator of the same realm.
    prototype: {
      value: Ee.prototype,
      writable: !1,
      enumerable: !1,
      configurable: !1
    }
  }), G(Ee) === Ee.prototype || ao`Function prototype is the same accross compartments`, G(e) === Ee.prototype || ao`Function constructor prototype is the same accross compartments`, e;
}, Si = (t) => {
  D(
    t,
    fa,
    y(
      jr(H(null), {
        set: y(() => {
          throw _(
            "Cannot set Symbol.unscopables of a Compartment's globalThis"
          );
        }),
        enumerable: !1,
        configurable: !1
      })
    )
  );
}, _s = (t) => {
  for (const [e, r] of me(us))
    D(t, e, {
      value: r,
      writable: !1,
      enumerable: !1,
      configurable: !1
    });
}, vs = (t, {
  intrinsics: e,
  newGlobalPropertyNames: r,
  makeCompartmentConstructor: n,
  markVirtualizedNativeFunction: o,
  parentCompartment: s
}) => {
  for (const [c, l] of me(ds))
    Q(e, l) && D(t, c, {
      value: e[l],
      writable: !0,
      enumerable: !1,
      configurable: !0
    });
  for (const [c, l] of me(r))
    Q(e, l) && D(t, c, {
      value: e[l],
      writable: !0,
      enumerable: !1,
      configurable: !0
    });
  const i = {
    globalThis: t
  };
  i.Compartment = y(
    n(
      n,
      e,
      o,
      s
    )
  );
  for (const [c, l] of me(i))
    D(t, c, {
      value: l,
      writable: !0,
      enumerable: !1,
      configurable: !0
    }), typeof l == "function" && o(l);
}, wn = (t, e, r) => {
  {
    const n = y(bi(e));
    r(n), D(t, "eval", {
      value: n,
      writable: !0,
      enumerable: !1,
      configurable: !0
    });
  }
  {
    const n = y(wi(e));
    r(n), D(t, "Function", {
      value: n,
      writable: !0,
      enumerable: !1,
      configurable: !0
    });
  }
}, { Fail: xi, quote: bs } = Y, ws = new Ur(
  Mn,
  y({
    get(t, e) {
      xi`Please report unexpected scope handler trap: ${bs(_e(e))}`;
    }
  })
), Ei = {
  get(t, e) {
  },
  set(t, e, r) {
    throw Wt(`${_e(e)} is not defined`);
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
    const r = bs(_e(e));
    console.warn(
      `getOwnPropertyDescriptor trap on scopeTerminatorHandler for ${r}`,
      _().stack
    );
  },
  // See https://github.com/endojs/endo/issues/1490
  // TODO Report bug to JSC or Safari
  ownKeys(t) {
    return [];
  }
}, Ss = y(
  H(
    ws,
    Be(Ei)
  )
), ki = new Ur(
  Mn,
  Ss
), xs = (t) => {
  const e = {
    // inherit scopeTerminator behavior
    ...Ss,
    // Redirect set properties to the globalObject.
    set(o, s, i) {
      return Mo(t, s, i);
    },
    // Always claim to have a potential property in order to be the recipient of a set
    has(o, s) {
      return !0;
    }
  }, r = y(
    H(
      ws,
      Be(e)
    )
  );
  return new Ur(
    Mn,
    r
  );
};
y(xs);
const { Fail: Pi } = Y, Ai = () => {
  const t = H(null), e = y({
    eval: {
      get() {
        return delete t.eval, Ko;
      },
      enumerable: !1,
      configurable: !0
    }
  }), r = {
    evalScope: t,
    allowNextEvalToBeUnsafe() {
      const { revoked: n } = r;
      n !== null && Pi`a handler did not reset allowNextEvalToBeUnsafe ${n.err}`, B(t, e);
    },
    /** @type {null | { err: any }} */
    revoked: null
  };
  return r;
}, io = "\\s*[@#]\\s*([a-zA-Z][a-zA-Z0-9]*)\\s*=\\s*([^\\s\\*]*)", Ti = new Xe(
  `(?:\\s*//${io}|/\\*${io}\\s*\\*/)\\s*$`
), Fn = (t) => {
  let e = "<unknown>";
  for (; t.length > 0; ) {
    const r = $n(Ti, t);
    if (r === null)
      break;
    t = Nn(t, 0, t.length - r[0].length), r[3] === "sourceURL" ? e = r[4] : r[1] === "sourceURL" && (e = r[2]);
  }
  return e;
};
function Dn(t, e) {
  const r = $a(t, e);
  if (r < 0)
    return -1;
  const n = t[r] === `
` ? 1 : 0;
  return On(Nn(t, 0, r), `
`).length + n;
}
const Es = new Xe("(?:<!--|-->)", "g"), ks = (t) => {
  const e = Dn(t, Es);
  if (e < 0)
    return t;
  const r = Fn(t);
  throw ir(
    `Possible HTML comment rejected at ${r}:${e}. (SES_HTML_COMMENT_REJECTED)`
  );
}, Ps = (t) => Pr(t, Es, (r) => r[0] === "<" ? "< ! --" : "-- >"), As = new Xe(
  "(^|[^.]|\\.\\.\\.)\\bimport(\\s*(?:\\(|/[/*]))",
  "g"
), Ts = (t) => {
  const e = Dn(t, As);
  if (e < 0)
    return t;
  const r = Fn(t);
  throw ir(
    `Possible import expression rejected at ${r}:${e}. (SES_IMPORT_REJECTED)`
  );
}, Is = (t) => Pr(t, As, (r, n, o) => `${n}__import__${o}`), Ii = new Xe(
  "(^|[^.])\\beval(\\s*\\()",
  "g"
), Cs = (t) => {
  const e = Dn(t, Ii);
  if (e < 0)
    return t;
  const r = Fn(t);
  throw ir(
    `Possible direct eval expression rejected at ${r}:${e}. (SES_EVAL_REJECTED)`
  );
}, Rs = (t) => (t = ks(t), t = Ts(t), t), $s = (t, e) => {
  for (const r of e)
    t = r(t);
  return t;
};
y({
  rejectHtmlComments: y(ks),
  evadeHtmlCommentTest: y(Ps),
  rejectImportExpressions: y(Ts),
  evadeImportExpressionTest: y(Is),
  rejectSomeDirectEvalExpressions: y(Cs),
  mandatoryTransforms: y(Rs),
  applyTransforms: y($s)
});
const Ci = [
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
], Ri = /^[a-zA-Z_$][\w$]*$/, co = (t) => t !== "eval" && !Hr(Ci, t) && Rn(Ri, t);
function lo(t, e) {
  const r = ee(t, e);
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
  Q(r, "value");
}
const $i = (t, e = {}) => {
  const r = Nt(t), n = Nt(e), o = et(
    n,
    (i) => co(i) && lo(e, i)
  );
  return {
    globalObjectConstants: et(
      r,
      (i) => (
        // Can't define a constant: it would prevent a
        // lookup on the endowments.
        !Hr(n, i) && co(i) && lo(t, i)
      )
    ),
    moduleLexicalConstants: o
  };
};
function uo(t, e) {
  return t.length === 0 ? "" : `const {${Zt(t, ",")}} = this.${e};`;
}
const Ni = (t) => {
  const { globalObjectConstants: e, moduleLexicalConstants: r } = $i(
    t.globalObject,
    t.moduleLexicals
  ), n = uo(
    e,
    "globalObject"
  ), o = uo(
    r,
    "moduleLexicals"
  ), s = Ee(`
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
  return ue(s, t, []);
}, { Fail: Oi } = Y, Un = ({
  globalObject: t,
  moduleLexicals: e = {},
  globalTransforms: r = [],
  sloppyGlobalsMode: n = !1
}) => {
  const o = n ? xs(t) : ki, s = Ai(), { evalScope: i } = s, c = y({
    evalScope: i,
    moduleLexicals: e,
    globalObject: t,
    scopeTerminator: o
  });
  let l;
  const u = () => {
    l || (l = Ni(c));
  };
  return { safeEvaluate: (f, h) => {
    const { localTransforms: p = [] } = h || {};
    u(), f = $s(f, [
      ...p,
      ...r,
      Rs
    ]);
    let m;
    try {
      return s.allowNextEvalToBeUnsafe(), ue(l, t, [f]);
    } catch (A) {
      throw m = A, A;
    } finally {
      const A = "eval" in i;
      delete i.eval, A && (s.revoked = { err: m }, Oi`handler did not reset allowNextEvalToBeUnsafe ${m}`);
    }
  } };
}, Mi = ") { [native code] }";
let ln;
const Ns = () => {
  if (ln === void 0) {
    const t = new Ut();
    D(Vr, "toString", {
      value: {
        toString() {
          const r = Ma(this);
          return Bo(r, Mi) || !lr(t, this) ? r : `function ${this.name}() { [native code] }`;
        }
      }.toString
    }), ln = y(
      (r) => qr(t, r)
    );
  }
  return ln;
};
function Li(t = "safe") {
  if (t !== "safe" && t !== "unsafe")
    throw _(`unrecognized domainTaming ${t}`);
  if (t === "unsafe")
    return;
  const e = x.process || void 0;
  if (typeof e == "object") {
    const r = ee(e, "domain");
    if (r !== void 0 && r.get !== void 0)
      throw _(
        "SES failed to lockdown, Node.js domains have been initialized (SES_NO_DOMAINS)"
      );
    D(e, "domain", {
      value: null,
      configurable: !1,
      writable: !1,
      enumerable: !1
    });
  }
}
const Fi = () => {
  const t = {}, e = x.ModuleSource;
  if (e !== void 0) {
    let n = function() {
    };
    var r = n;
    t.ModuleSource = e;
    const o = G(e);
    o === Vr ? (wr(e, n), t["%AbstractModuleSource%"] = n, t["%AbstractModuleSourcePrototype%"] = n.prototype) : (t["%AbstractModuleSource%"] = o, t["%AbstractModuleSourcePrototype%"] = o.prototype);
    const s = e.prototype;
    s !== void 0 && (t["%ModuleSourcePrototype%"] = s, G(s) === zr && wr(e.prototype, n.prototype));
  }
  return t;
}, jn = y([
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
]), Zn = y([
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
]), Os = y([
  ...jn,
  ...Zn
]), Di = (t, { shouldResetForDebugging: e = !1 } = {}) => {
  e && t.resetErrorTagNum();
  let r = [];
  const n = bt(
    de(Os, ([i, c]) => {
      const l = (...u) => {
        ne(r, [i, ...u]);
      };
      return D(l, "name", { value: i }), [i, y(l)];
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
y(Di);
const dt = {
  NOTE: "ERROR_NOTE:",
  MESSAGE: "ERROR_MESSAGE:",
  CAUSE: "cause:",
  ERRORS: "errors:"
};
y(dt);
const zn = (t, e) => {
  if (!t)
    return;
  const { getStackString: r, tagError: n, takeMessageLogArgs: o, takeNoteLogArgsArray: s } = e, i = (S, w) => de(S, (T) => Kr(T) ? (ne(w, T), `(${n(T)})`) : T), c = (S, w, R, T, j) => {
    const I = n(w), L = R === dt.MESSAGE ? `${I}:` : `${I} ${R}`, Z = i(T, j);
    t[S](L, ...Z);
  }, l = (S, w, R = void 0) => {
    if (w.length === 0)
      return;
    if (w.length === 1 && R === void 0) {
      f(S, w[0]);
      return;
    }
    let T;
    w.length === 1 ? T = "Nested error" : T = `Nested ${w.length} errors`, R !== void 0 && (T = `${T} under ${R}`), t.group(T);
    try {
      for (const j of w)
        f(S, j);
    } finally {
      t.groupEnd();
    }
  }, u = new Ut(), d = (S) => (w, R) => {
    const T = [];
    c(S, w, dt.NOTE, R, T), l(S, T, n(w));
  }, f = (S, w) => {
    if (lr(u, w))
      return;
    const R = n(w);
    qr(u, w);
    const T = [], j = o(w), I = s(
      w,
      d(S)
    );
    j === void 0 ? t[S](`${R}:`, w.message) : c(
      S,
      w,
      dt.MESSAGE,
      j,
      T
    );
    let L = r(w);
    typeof L == "string" && L.length >= 1 && !Bo(L, `
`) && (L += `
`), t[S](L), w.cause && c(S, w, dt.CAUSE, [w.cause], T), w.errors && c(S, w, dt.ERRORS, w.errors, T);
    for (const Z of I)
      c(S, w, dt.NOTE, Z, T);
    l(S, T, R);
  }, h = de(jn, ([S, w]) => {
    const R = (...T) => {
      const j = [], I = i(T, j);
      t[S] && t[S](...I), l(S, j);
    };
    return D(R, "name", { value: S }), [S, y(R)];
  }), p = et(
    Zn,
    ([S, w]) => S in t
  ), m = de(p, ([S, w]) => {
    const R = (...T) => {
      t[S](...T);
    };
    return D(R, "name", { value: S }), [S, y(R)];
  }), A = bt([...h, ...m]);
  return (
    /** @type {VirtualConsole} */
    y(A)
  );
};
y(zn);
const Ui = (t, e, r) => {
  const [n, ...o] = On(t, e), s = jo(o, (i) => [e, ...r, i]);
  return ["", n, ...s];
}, Ms = (t) => y((r) => {
  const n = [], o = (...l) => (n.length > 0 && (l = jo(
    l,
    (u) => typeof u == "string" && Go(u, `
`) ? Ui(u, `
`, n) : [u]
  ), l = [...n, ...l]), r(...l)), s = (l, u) => ({ [l]: (...d) => u(...d) })[l], i = bt([
    ...de(jn, ([l]) => [
      l,
      s(l, o)
    ]),
    ...de(Zn, ([l]) => [
      l,
      s(l, (...u) => o(l, ...u))
    ])
  ]);
  for (const l of ["group", "groupCollapsed"])
    i[l] ? i[l] = s(l, (...u) => {
      u.length >= 1 && o(...u), ne(n, " ");
    }) : i[l] = () => {
    };
  return i.groupEnd ? i.groupEnd = s("groupEnd", (...l) => {
    Er(n);
  }) : i.groupEnd = () => {
  }, harden(i), zn(
    /** @type {VirtualConsole} */
    i,
    t
  );
});
y(Ms);
const ji = (t, e, r = void 0) => {
  const n = et(
    Os,
    ([i, c]) => i in t
  ), o = de(n, ([i, c]) => [i, y((...u) => {
    (c === void 0 || e.canLog(c)) && t[i](...u);
  })]), s = bt(o);
  return (
    /** @type {VirtualConsole} */
    y(s)
  );
};
y(ji);
const fo = (t) => {
  if ($t === void 0)
    return;
  let e = 0;
  const r = new $e(), n = (d) => {
    Pa(r, d);
  }, o = new ze(), s = (d) => {
    if (Wr(r, d)) {
      const f = Ke(r, d);
      n(d), t(f);
    }
  }, i = new $t(s);
  return {
    rejectionHandledHandler: (d) => {
      const f = z(o, d);
      n(f);
    },
    unhandledRejectionHandler: (d, f) => {
      e += 1;
      const h = e;
      pe(r, h, d), he(o, f, h), La(i, f, h, f);
    },
    processTerminationHandler: () => {
      for (const [d, f] of Aa(r))
        n(d), t(f);
    }
  };
}, un = (t) => {
  throw _(t);
}, po = (t, e) => y((...r) => ue(t, e, r)), Zi = (t = "safe", e = "platform", r = "report", n = void 0) => {
  t === "safe" || t === "unsafe" || un(`unrecognized consoleTaming ${t}`);
  let o;
  n === void 0 ? o = Ir : o = {
    ...Ir,
    getStackString: n
  };
  const s = (
    /** @type {VirtualConsole} */
    // eslint-disable-next-line no-nested-ternary
    typeof x.console < "u" ? x.console : typeof x.print == "function" ? (
      // Make a good-enough console for eshost (including only functions that
      // log at a specific level with no special argument interpretation).
      // https://console.spec.whatwg.org/#logging
      ((u) => y({ debug: u, log: u, info: u, warn: u, error: u }))(
        // eslint-disable-next-line no-undef
        po(x.print)
      )
    ) : void 0
  );
  if (s && s.log)
    for (const u of ["warn", "error"])
      s[u] || D(s, u, {
        value: po(s.log, s)
      });
  const i = (
    /** @type {VirtualConsole} */
    t === "unsafe" ? s : zn(s, o)
  ), c = x.process || void 0;
  if (e !== "none" && typeof c == "object" && typeof c.on == "function") {
    let u;
    if (e === "platform" || e === "exit") {
      const { exit: d } = c;
      typeof d == "function" || un("missing process.exit"), u = () => d(c.exitCode || -1);
    } else e === "abort" && (u = c.abort, typeof u == "function" || un("missing process.abort"));
    c.on("uncaughtException", (d) => {
      i.error("SES_UNCAUGHT_EXCEPTION:", d), u && u();
    });
  }
  if (r !== "none" && typeof c == "object" && typeof c.on == "function") {
    const d = fo((f) => {
      i.error("SES_UNHANDLED_REJECTION:", f);
    });
    d && (c.on("unhandledRejection", d.unhandledRejectionHandler), c.on("rejectionHandled", d.rejectionHandledHandler), c.on("exit", d.processTerminationHandler));
  }
  const l = x.window || void 0;
  if (e !== "none" && typeof l == "object" && typeof l.addEventListener == "function" && l.addEventListener("error", (u) => {
    u.preventDefault(), i.error("SES_UNCAUGHT_EXCEPTION:", u.error), (e === "exit" || e === "abort") && (l.location.href = "about:blank");
  }), r !== "none" && typeof l == "object" && typeof l.addEventListener == "function") {
    const d = fo((f) => {
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
}, zi = [
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
  // TODO replace to use only permitted info
], Bi = (t) => {
  const r = bt(de(zi, (n) => {
    const o = t[n];
    return [n, () => ue(o, t, [])];
  }));
  return H(r, {});
}, Gi = (t) => de(t, Bi), Vi = /\/node_modules\//, Hi = /^(?:node:)?internal\//, Wi = /\/packages\/ses\/src\/error\/assert.js$/, qi = /\/packages\/eventual-send\/src\//, Ki = [
  Vi,
  Hi,
  Wi,
  qi
], Yi = (t) => {
  if (!t)
    return !0;
  for (const e of Ki)
    if (Rn(e, t))
      return !1;
  return !0;
}, Ji = /^((?:.*[( ])?)[:/\w_-]*\/\.\.\.\/(.+)$/, Xi = /^((?:.*[( ])?)[:/\w_-]*\/(packages\/.+)$/, Qi = [
  Ji,
  Xi
], ec = (t) => {
  for (const e of Qi) {
    const r = $n(e, t);
    if (r)
      return Zt(wa(r, 1), "");
  }
  return t;
}, tc = (t, e, r, n) => {
  if (r === "unsafe-debug")
    throw _(
      "internal: v8+unsafe-debug special case should already be done"
    );
  const o = t.captureStackTrace, s = (p) => n === "verbose" ? !0 : Yi(p.getFileName()), i = (p) => {
    let m = `${p}`;
    return n === "concise" && (m = ec(m)), `
  at ${m}`;
  }, c = (p, m) => Zt(
    de(et(m, s), i),
    ""
  ), l = new ze(), u = {
    // The optional `optFn` argument is for cutting off the bottom of
    // the stack --- for capturing the stack only above the topmost
    // call to that function. Since this isn't the "real" captureStackTrace
    // but instead calls the real one, if no other cutoff is provided,
    // we cut this one off.
    captureStackTrace(p, m = u.captureStackTrace) {
      if (typeof o == "function") {
        ue(o, t, [p, m]);
        return;
      }
      Mo(p, "stack", "");
    },
    // Shim of proposed special power, to reside by default only
    // in the start compartment, for getting the stack traceback
    // string associated with an error.
    // See https://tc39.es/proposal-error-stacks/
    getStackString(p) {
      let m = z(l, p);
      if (m === void 0 && (p.stack, m = z(l, p), m || (m = { stackString: "" }, he(l, p, m))), m.stackString !== void 0)
        return m.stackString;
      const A = c(p, m.callSites);
      return he(l, p, { stackString: A }), A;
    },
    prepareStackTrace(p, m) {
      if (r === "unsafe") {
        const A = c(p, m);
        return he(l, p, { stackString: A }), `${p}${A}`;
      } else
        return he(l, p, { callSites: m }), "";
    }
  }, d = u.prepareStackTrace;
  t.prepareStackTrace = d;
  const f = new Ut([d]), h = (p) => {
    if (lr(f, p))
      return p;
    const m = {
      prepareStackTrace(A, S) {
        return he(l, A, { callSites: S }), p(A, Gi(S));
      }
    };
    return qr(f, m.prepareStackTrace), m.prepareStackTrace;
  };
  return B(e, {
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
}, ho = ee(le.prototype, "stack"), mo = ho && ho.get, rc = {
  getStackString(t) {
    return typeof mo == "function" ? ue(mo, t, []) : "stack" in t ? `${t.stack}` : "";
  }
};
let hr = rc.getStackString;
function nc(t = "safe", e = "concise") {
  if (t !== "safe" && t !== "unsafe" && t !== "unsafe-debug")
    throw _(`unrecognized errorTaming ${t}`);
  if (e !== "concise" && e !== "verbose")
    throw _(`unrecognized stackFiltering ${e}`);
  const r = le.prototype, { captureStackTrace: n } = le, o = typeof n == "function" ? "v8" : "unknown", s = (l = {}) => {
    const u = function(...f) {
      let h;
      return new.target === void 0 ? h = ue(le, this, f) : h = Sr(le, f, new.target), o === "v8" && ue(n, le, [h, u]), h;
    };
    return B(u, {
      length: { value: 1 },
      prototype: {
        value: r,
        writable: !1,
        enumerable: !1,
        configurable: !1
      }
    }), u;
  }, i = s({ powers: "original" }), c = s({ powers: "none" });
  B(r, {
    constructor: { value: c }
  });
  for (const l of ps)
    wr(l, c);
  if (B(i, {
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
  }), t === "unsafe-debug" && o === "v8") {
    B(i, {
      prepareStackTrace: {
        get() {
          return le.prepareStackTrace;
        },
        set(u) {
          le.prepareStackTrace = u;
        },
        enumerable: !1,
        configurable: !0
      },
      captureStackTrace: {
        value: le.captureStackTrace,
        writable: !0,
        enumerable: !1,
        configurable: !0
      }
    });
    const l = Be(i);
    return B(c, {
      stackTraceLimit: l.stackTraceLimit,
      prepareStackTrace: l.prepareStackTrace,
      captureStackTrace: l.captureStackTrace
    }), {
      "%InitialGetStackString%": hr,
      "%InitialError%": i,
      "%SharedError%": c
    };
  }
  return B(c, {
    stackTraceLimit: {
      get() {
      },
      set(l) {
      },
      enumerable: !1,
      configurable: !0
    }
  }), o === "v8" && B(c, {
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
      value: (l, u) => {
        D(l, "stack", {
          value: ""
        });
      },
      writable: !1,
      enumerable: !1,
      configurable: !0
    }
  }), o === "v8" ? hr = tc(
    le,
    i,
    t,
    e
  ) : t === "unsafe" || t === "unsafe-debug" ? B(r, {
    stack: {
      get() {
        return hr(this);
      },
      set(l) {
        B(this, {
          stack: {
            value: l,
            writable: !0,
            enumerable: !0,
            configurable: !0
          }
        });
      }
    }
  }) : B(r, {
    stack: {
      get() {
        return `${this}`;
      },
      set(l) {
        B(this, {
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
    "%InitialGetStackString%": hr,
    "%InitialError%": i,
    "%SharedError%": c
  };
}
const oc = () => {
}, sc = async (t, e, r) => {
  await null;
  const n = t(...e);
  let o = kr(n);
  for (; !o.done; )
    try {
      const s = await o.value;
      o = kr(n, s);
    } catch (s) {
      o = Vo(n, r(s));
    }
  return o.value;
}, ac = (t, e) => {
  const r = t(...e);
  let n = kr(r);
  for (; !n.done; )
    try {
      n = kr(r, n.value);
    } catch (o) {
      n = Vo(r, o);
    }
  return n.value;
}, ic = (t, e) => y({ compartment: t, specifier: e }), cc = (t, e, r) => {
  const n = H(null);
  for (const o of t) {
    const s = e(o, r);
    n[o] = s;
  }
  return y(n);
}, Bt = (t, e, r, n, o, s, i, c, l) => {
  const { resolveHook: u, name: d } = z(
    t,
    r
  ), { imports: f } = o;
  if (!pt(f) || Zo(f, (m) => typeof m != "string"))
    throw Ce(
      re`Invalid module source: 'imports' must be an array of strings, got ${f} for module ${U(n)} of compartment ${U(d)}`
    );
  const h = cc(f, u, n), p = y({
    compartment: r,
    moduleSource: o,
    moduleSpecifier: n,
    resolvedImports: h,
    importMeta: l
  });
  for (const m of Ro(h))
    s(It, [
      t,
      e,
      r,
      m,
      s,
      i,
      c
    ]);
  return p;
};
function* lc(t, e, r, n, o, s, i) {
  const {
    importHook: c,
    importNowHook: l,
    moduleMap: u,
    moduleMapHook: d,
    moduleRecords: f,
    parentCompartment: h
  } = z(t, r);
  if (Wr(f, n))
    return Ke(f, n);
  let p = u[n];
  if (p === void 0 && d !== void 0 && (p = d(n)), p === void 0) {
    const m = s(c, l);
    if (m === void 0) {
      const A = s(
        "importHook",
        "importNowHook"
      );
      throw Ce(
        re`${Tr(A)} needed to load module ${U(
          n
        )} in compartment ${U(r.name)}`
      );
    }
    p = m(n), Tt(e, p) || (p = yield p);
  }
  if (typeof p == "string")
    throw Ce(
      re`Cannot map module ${U(n)} to ${U(
        p
      )} in parent compartment, use {source} module descriptor`,
      _
    );
  if (ke(p)) {
    let m = z(e, p);
    if (m !== void 0 && (p = m), p.namespace !== void 0) {
      if (typeof p.namespace == "string") {
        const {
          compartment: w = h,
          namespace: R
        } = p;
        if (!ke(w) || !Tt(t, w))
          throw Ce(
            re`Invalid compartment in module descriptor for specifier ${U(n)} in compartment ${U(r.name)}`
          );
        const T = yield It(
          t,
          e,
          w,
          R,
          o,
          s,
          i
        );
        return pe(f, n, T), T;
      }
      if (ke(p.namespace)) {
        const { namespace: w } = p;
        if (m = z(e, w), m !== void 0)
          p = m;
        else {
          const R = Nt(w), I = Bt(
            t,
            e,
            r,
            n,
            {
              imports: [],
              exports: R,
              execute(L) {
                for (const Z of R)
                  L[Z] = w[Z];
              }
            },
            o,
            s,
            i,
            void 0
          );
          return pe(f, n, I), I;
        }
      } else
        throw Ce(
          re`Invalid compartment in module descriptor for specifier ${U(n)} in compartment ${U(r.name)}`
        );
    }
    if (p.source !== void 0)
      if (typeof p.source == "string") {
        const {
          source: w,
          specifier: R = n,
          compartment: T = h,
          importMeta: j = void 0
        } = p, I = yield It(
          t,
          e,
          T,
          w,
          o,
          s,
          i
        ), { moduleSource: L } = I, Z = Bt(
          t,
          e,
          r,
          R,
          L,
          o,
          s,
          i,
          j
        );
        return pe(f, n, Z), Z;
      } else {
        const {
          source: w,
          specifier: R = n,
          importMeta: T
        } = p, j = Bt(
          t,
          e,
          r,
          R,
          w,
          o,
          s,
          i,
          T
        );
        return pe(f, n, j), j;
      }
    if (p.archive !== void 0)
      throw Ce(
        re`Unsupported archive module descriptor for specifier ${U(n)} in compartment ${U(r.name)}`
      );
    if (p.record !== void 0) {
      const {
        compartment: w = r,
        specifier: R = n,
        record: T,
        importMeta: j
      } = p, I = Bt(
        t,
        e,
        w,
        R,
        T,
        o,
        s,
        i,
        j
      );
      return pe(f, n, I), pe(f, R, I), I;
    }
    if (p.compartment !== void 0 && p.specifier !== void 0) {
      if (!ke(p.compartment) || !Tt(t, p.compartment) || typeof p.specifier != "string")
        throw Ce(
          re`Invalid compartment in module descriptor for specifier ${U(n)} in compartment ${U(r.name)}`
        );
      const w = yield It(
        t,
        e,
        p.compartment,
        p.specifier,
        o,
        s,
        i
      );
      return pe(f, n, w), w;
    }
    const S = Bt(
      t,
      e,
      r,
      n,
      p,
      o,
      s,
      i
    );
    return pe(f, n, S), S;
  } else
    throw Ce(
      re`module descriptor must be a string or object for specifier ${U(
        n
      )} in compartment ${U(r.name)}`
    );
}
const It = (t, e, r, n, o, s, i) => {
  const { name: c } = z(
    t,
    r
  );
  let l = Ke(i, r);
  l === void 0 && (l = new $e(), pe(i, r, l));
  let u = Ke(l, n);
  return u !== void 0 || (u = s(sc, ac)(
    lc,
    [
      t,
      e,
      r,
      n,
      o,
      s,
      i
    ],
    (d) => {
      throw Jr(
        d,
        re`${d.message}, loading ${U(n)} in compartment ${U(
          c
        )}`
      ), d;
    }
  ), pe(l, n, u)), u;
}, uc = () => {
  const t = new Dt(), e = [];
  return { enqueueJob: (o, s) => {
    In(
      t,
      qo(o(...s), oc, (i) => {
        ne(e, i);
      })
    );
  }, drainQueue: async () => {
    await null;
    for (const o of t)
      await o;
    return e;
  } };
}, Ls = ({ errors: t, errorPrefix: e }) => {
  if (t.length > 0) {
    const r = ce("COMPARTMENT_LOAD_ERRORS", "", ["verbose"]) === "verbose";
    throw _(
      `${e} (${t.length} underlying failures: ${Zt(
        de(t, (n) => n.message + (r ? n.stack : "")),
        ", "
      )}`
    );
  }
}, dc = (t, e) => e, fc = (t, e) => t, go = async (t, e, r, n) => {
  const { name: o } = z(
    t,
    r
  ), s = new $e(), { enqueueJob: i, drainQueue: c } = uc();
  i(It, [
    t,
    e,
    r,
    n,
    i,
    fc,
    s
  ]);
  const l = await c();
  Ls({
    errors: l,
    errorPrefix: `Failed to load module ${U(n)} in package ${U(
      o
    )}`
  });
}, pc = (t, e, r, n) => {
  const { name: o } = z(
    t,
    r
  ), s = new $e(), i = [], c = (l, u) => {
    try {
      l(...u);
    } catch (d) {
      ne(i, d);
    }
  };
  c(It, [
    t,
    e,
    r,
    n,
    c,
    dc,
    s
  ]), Ls({
    errors: i,
    errorPrefix: `Failed to load module ${U(n)} in package ${U(
      o
    )}`
  });
}, { quote: xt } = Y, hc = () => {
  let t = !1;
  const e = H(null, {
    // Make this appear like an ESM module namespace object.
    [Qe]: {
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
    exportsProxy: new Ur(e, {
      get(r, n, o) {
        if (!t)
          throw _(
            `Cannot get property ${xt(
              n
            )} of module exports namespace, the module has not yet begun to execute`
          );
        return ya(e, n, o);
      },
      set(r, n, o) {
        throw _(
          `Cannot set property ${xt(n)} of module exports namespace`
        );
      },
      has(r, n) {
        if (!t)
          throw _(
            `Cannot check property ${xt(
              n
            )}, the module has not yet begun to execute`
          );
        return Oo(e, n);
      },
      deleteProperty(r, n) {
        throw _(
          `Cannot delete property ${xt(n)}s of module exports namespace`
        );
      },
      ownKeys(r) {
        if (!t)
          throw _(
            "Cannot enumerate keys, the module has not yet begun to execute"
          );
        return qe(e);
      },
      getOwnPropertyDescriptor(r, n) {
        if (!t)
          throw _(
            `Cannot get own property descriptor ${xt(
              n
            )}, the module has not yet begun to execute`
          );
        return _a(e, n);
      },
      preventExtensions(r) {
        if (!t)
          throw _(
            "Cannot prevent extensions of module exports namespace, the module has not yet begun to execute"
          );
        return ba(e);
      },
      isExtensible() {
        if (!t)
          throw _(
            "Cannot check extensibility of module exports namespace, the module has not yet begun to execute"
          );
        return va(e);
      },
      getPrototypeOf(r) {
        return null;
      },
      setPrototypeOf(r, n) {
        throw _("Cannot set prototype of module exports namespace");
      },
      defineProperty(r, n, o) {
        throw _(
          `Cannot define property ${xt(n)} of module exports namespace`
        );
      },
      apply(r, n, o) {
        throw _(
          "Cannot call module exports namespace, it is not a function"
        );
      },
      construct(r, n) {
        throw _(
          "Cannot construct module exports namespace, it is not a constructor"
        );
      }
    })
  });
}, Bn = (t, e, r, n) => {
  const { deferredExports: o } = e;
  if (!Wr(o, n)) {
    const s = hc();
    he(
      r,
      s.exportsProxy,
      ic(t, n)
    ), pe(o, n, s);
  }
  return Ke(o, n);
}, mc = (t, e) => {
  const { sloppyGlobalsMode: r = !1, __moduleShimLexicals__: n = void 0 } = e;
  let o;
  if (n === void 0 && !r)
    ({ safeEvaluate: o } = t);
  else {
    let { globalTransforms: s } = t;
    const { globalObject: i } = t;
    let c;
    n !== void 0 && (s = void 0, c = H(
      null,
      Be(n)
    )), { safeEvaluate: o } = Un({
      globalObject: i,
      moduleLexicals: c,
      globalTransforms: s,
      sloppyGlobalsMode: r
    });
  }
  return { safeEvaluate: o };
}, Fs = (t, e, r) => {
  if (typeof e != "string")
    throw _("first argument of evaluate() must be a string");
  const {
    transforms: n = [],
    __evadeHtmlCommentTest__: o = !1,
    __evadeImportExpressionTest__: s = !1,
    __rejectSomeDirectEvalExpressions__: i = !0
    // Note default on
  } = r, c = [...n];
  o === !0 && ne(c, Ps), s === !0 && ne(c, Is), i === !0 && ne(c, Cs);
  const { safeEvaluate: l } = mc(
    t,
    r
  );
  return l(e, {
    localTransforms: c
  });
}, { quote: mr } = Y, gc = (t, e, r, n, o, s) => {
  const { exportsProxy: i, exportsTarget: c, activate: l } = Bn(
    r,
    z(t, r),
    n,
    o
  ), u = H(null);
  if (e.exports) {
    if (!pt(e.exports) || Zo(e.exports, (f) => typeof f != "string"))
      throw _(
        `SES virtual module source "exports" property must be an array of strings for module ${o}`
      );
    ht(e.exports, (f) => {
      let h = c[f];
      const p = [];
      D(c, f, {
        get: () => h,
        set: (S) => {
          h = S;
          for (const w of p)
            w(S);
        },
        enumerable: !0,
        configurable: !1
      }), u[f] = (S) => {
        ne(p, S), S(h);
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
      if (Oo(d, "errorFromExecute"))
        throw d.errorFromExecute;
      if (!d.activated) {
        l(), d.activated = !0;
        try {
          e.execute(c, r, s);
        } catch (f) {
          throw d.errorFromExecute = f, f;
        }
      }
    }
  });
}, yc = (t, e, r, n) => {
  const {
    compartment: o,
    moduleSpecifier: s,
    moduleSource: i,
    importMeta: c
  } = r, {
    reexports: l = [],
    __syncModuleProgram__: u,
    __fixedExportMap__: d = {},
    __liveExportMap__: f = {},
    __reexportMap__: h = {},
    __needsImportMeta__: p = !1,
    __syncModuleFunctor__: m
  } = i, A = z(t, o), { __shimTransforms__: S, importMetaHook: w } = A, { exportsProxy: R, exportsTarget: T, activate: j } = Bn(
    o,
    A,
    e,
    s
  ), I = H(null), L = H(null), Z = H(null), se = H(null), J = H(null);
  c && jr(J, c), p && w && w(s, J);
  const be = H(null), Me = H(null);
  ht(me(d), ([we, [W]]) => {
    let q = be[W];
    if (!q) {
      let ae, ie = !0, ge = [];
      const te = () => {
        if (ie)
          throw Wt(`binding ${mr(W)} not yet initialized`);
        return ae;
      }, Ae = y((Te) => {
        if (!ie)
          throw _(
            `Internal: binding ${mr(W)} already initialized`
          );
        ae = Te;
        const Wn = ge;
        ge = null, ie = !1;
        for (const Ie of Wn || [])
          Ie(Te);
        return Te;
      });
      q = {
        get: te,
        notify: (Te) => {
          Te !== Ae && (ie ? ne(ge || [], Te) : Te(ae));
        }
      }, be[W] = q, Z[W] = Ae;
    }
    I[we] = {
      get: q.get,
      set: void 0,
      enumerable: !0,
      configurable: !1
    }, Me[we] = q.notify;
  }), ht(
    me(f),
    ([we, [W, q]]) => {
      let ae = be[W];
      if (!ae) {
        let ie, ge = !0;
        const te = [], Ae = () => {
          if (ge)
            throw Wt(
              `binding ${mr(we)} not yet initialized`
            );
          return ie;
        }, St = y((Ie) => {
          ie = Ie, ge = !1;
          for (const en of te)
            en(Ie);
        }), Te = (Ie) => {
          if (ge)
            throw Wt(`binding ${mr(W)} not yet initialized`);
          ie = Ie;
          for (const en of te)
            en(Ie);
        };
        ae = {
          get: Ae,
          notify: (Ie) => {
            Ie !== St && (ne(te, Ie), ge || Ie(ie));
          }
        }, be[W] = ae, q && D(L, W, {
          get: Ae,
          set: Te,
          enumerable: !0,
          configurable: !1
        }), se[W] = St;
      }
      I[we] = {
        get: ae.get,
        set: void 0,
        enumerable: !0,
        configurable: !1
      }, Me[we] = ae.notify;
    }
  );
  const dr = (we) => {
    we(T);
  };
  Me["*"] = dr;
  function zt(we) {
    const W = H(null);
    W.default = !1;
    for (const [q, ae] of we) {
      const ie = Ke(n, q);
      ie.execute();
      const { notifiers: ge } = ie;
      for (const [te, Ae] of ae) {
        const St = ge[te];
        if (!St)
          throw ir(
            `The requested module '${q}' does not provide an export named '${te}'`
          );
        for (const Te of Ae)
          St(Te);
      }
      if (Hr(l, q))
        for (const [te, Ae] of me(
          ge
        ))
          W[te] === void 0 ? W[te] = Ae : W[te] = !1;
      if (h[q])
        for (const [te, Ae] of h[q])
          W[Ae] = ge[te];
    }
    for (const [q, ae] of me(W))
      if (!Me[q] && ae !== !1) {
        Me[q] = ae;
        let ie;
        ae((te) => ie = te), I[q] = {
          get() {
            return ie;
          },
          set: void 0,
          enumerable: !0,
          configurable: !1
        };
      }
    ht(
      zo(Co(I)),
      (q) => D(T, q, I[q])
    ), y(T), j();
  }
  let wt;
  m !== void 0 ? wt = m : wt = Fs(A, u, {
    globalObject: o.globalThis,
    transforms: S,
    __moduleShimLexicals__: L
  });
  let Pe = !1, st;
  function ra() {
    if (wt) {
      const we = wt;
      wt = null;
      try {
        we(
          y({
            imports: y(zt),
            onceVar: y(Z),
            liveVar: y(se),
            importMeta: J
          })
        );
      } catch (W) {
        Pe = !0, st = W;
      }
    }
    if (Pe)
      throw st;
  }
  return y({
    notifiers: Me,
    exportsProxy: R,
    execute: ra
  });
}, { Fail: ft, quote: X } = Y, Ds = (t, e, r, n) => {
  const { name: o, moduleRecords: s } = z(
    t,
    r
  ), i = Ke(s, n);
  if (i === void 0)
    throw Wt(
      `Missing link to module ${X(n)} from compartment ${X(
        o
      )}`
    );
  return xc(t, e, i);
};
function _c(t) {
  return typeof t.__syncModuleProgram__ == "string";
}
function vc(t, e) {
  const { __fixedExportMap__: r, __liveExportMap__: n } = t;
  ke(r) || ft`Property '__fixedExportMap__' of a precompiled module source must be an object, got ${X(
    r
  )}, for module ${X(e)}`, ke(n) || ft`Property '__liveExportMap__' of a precompiled module source must be an object, got ${X(
    n
  )}, for module ${X(e)}`;
}
function bc(t) {
  return typeof t.execute == "function";
}
function wc(t, e) {
  const { exports: r } = t;
  pt(r) || ft`Invalid module source: 'exports' of a virtual module source must be an array, got ${X(
    r
  )}, for module ${X(e)}`;
}
function Sc(t, e) {
  ke(t) || ft`Invalid module source: must be of type object, got ${X(
    t
  )}, for module ${X(e)}`;
  const { imports: r, exports: n, reexports: o = [] } = t;
  pt(r) || ft`Invalid module source: 'imports' must be an array, got ${X(
    r
  )}, for module ${X(e)}`, pt(n) || ft`Invalid module source: 'exports' must be an array, got ${X(
    n
  )}, for module ${X(e)}`, pt(o) || ft`Invalid module source: 'reexports' must be an array if present, got ${X(
    o
  )}, for module ${X(e)}`;
}
const xc = (t, e, r) => {
  const { compartment: n, moduleSpecifier: o, resolvedImports: s, moduleSource: i } = r, { instances: c } = z(t, n);
  if (Wr(c, o))
    return Ke(c, o);
  Sc(i, o);
  const l = new $e();
  let u;
  if (_c(i))
    vc(i, o), u = yc(
      t,
      e,
      r,
      l
    );
  else if (bc(i))
    wc(i, o), u = gc(
      t,
      i,
      n,
      e,
      o,
      s
    );
  else
    throw _(`Invalid module source, got ${X(i)}`);
  pe(c, o, u);
  for (const [d, f] of me(s)) {
    const h = Ds(
      t,
      e,
      n,
      f
    );
    pe(l, d, h);
  }
  return u;
}, Gt = new ze(), Fe = new ze(), Gn = function(e = {}, r = {}, n = {}) {
  throw _(
    "Compartment.prototype.constructor is not a valid constructor."
  );
}, yo = (t, e) => {
  const { execute: r, exportsProxy: n } = Ds(
    Fe,
    Gt,
    t,
    e
  );
  return r(), n;
}, Vn = {
  constructor: Gn,
  get globalThis() {
    return z(Fe, this).globalObject;
  },
  get name() {
    return z(Fe, this).name;
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
    const r = z(Fe, this);
    return Fs(r, t, e);
  },
  module(t) {
    if (typeof t != "string")
      throw _("first argument of module() must be a string");
    const { exportsProxy: e } = Bn(
      this,
      z(Fe, this),
      Gt,
      t
    );
    return e;
  },
  async import(t) {
    const { noNamespaceBox: e } = z(Fe, this);
    if (typeof t != "string")
      throw _("first argument of import() must be a string");
    return qo(
      go(Fe, Gt, this, t),
      () => {
        const r = yo(
          /** @type {Compartment} */
          this,
          t
        );
        return e ? r : { namespace: r };
      }
    );
  },
  async load(t) {
    if (typeof t != "string")
      throw _("first argument of load() must be a string");
    return go(Fe, Gt, this, t);
  },
  importNow(t) {
    if (typeof t != "string")
      throw _("first argument of importNow() must be a string");
    return pc(Fe, Gt, this, t), yo(
      /** @type {Compartment} */
      this,
      t
    );
  }
};
B(Vn, {
  [Qe]: {
    value: "Compartment",
    writable: !1,
    enumerable: !1,
    configurable: !0
  }
});
B(Gn, {
  prototype: { value: Vn }
});
const Ec = (...t) => {
  if (t.length === 0)
    return {};
  if (t.length === 1 && typeof t[0] == "object" && t[0] !== null && "__options__" in t[0]) {
    const { __options__: e, ...r } = t[0];
    return Y(
      e === !0,
      `Compartment constructor only supports true __options__ sigil, got ${e}`
    ), r;
  } else {
    const [
      e = (
        /** @type {Map<string, any>} */
        {}
      ),
      r = (
        /** @type {Map<string, ModuleDescriptor>} */
        {}
      ),
      n = {}
    ] = t;
    return to(
      n.modules,
      void 0,
      "Compartment constructor must receive either a module map argument or modules option, not both"
    ), to(
      n.globals,
      void 0,
      "Compartment constructor must receive either globals argument or option, not both"
    ), {
      ...n,
      globals: e,
      modules: r
    };
  }
}, Sn = (t, e, r, n = void 0) => {
  function o(...s) {
    if (new.target === void 0)
      throw _(
        "Class constructor Compartment cannot be invoked without 'new'"
      );
    const {
      name: i = "<unknown>",
      transforms: c = [],
      __shimTransforms__: l = [],
      globals: u = {},
      modules: d = {},
      resolveHook: f,
      importHook: h,
      importNowHook: p,
      moduleMapHook: m,
      importMetaHook: A,
      __noNamespaceBox__: S = !1
    } = Ec(...s), w = [...c, ...l], R = { __proto__: null, ...u }, T = { __proto__: null, ...d }, j = new $e(), I = new $e(), L = new $e(), Z = {};
    Si(Z), _s(Z);
    const { safeEvaluate: se } = Un({
      globalObject: Z,
      globalTransforms: w,
      sloppyGlobalsMode: !1
    });
    vs(Z, {
      intrinsics: e,
      newGlobalPropertyNames: fs,
      makeCompartmentConstructor: t,
      parentCompartment: this,
      markVirtualizedNativeFunction: r
    }), wn(
      Z,
      se,
      r
    ), jr(Z, R), he(Fe, this, {
      name: `${i}`,
      globalTransforms: w,
      globalObject: Z,
      safeEvaluate: se,
      resolveHook: f,
      importHook: h,
      importNowHook: p,
      moduleMap: T,
      moduleMapHook: m,
      importMetaHook: A,
      moduleRecords: j,
      __shimTransforms__: l,
      deferredExports: L,
      instances: I,
      parentCompartment: n,
      noNamespaceBox: S
    });
  }
  return o.prototype = Vn, o;
};
function dn(t) {
  return G(t).constructor;
}
function kc() {
  return arguments;
}
const Pc = () => {
  const t = Ee.prototype.constructor, e = ee(kc(), "callee"), r = e && e.get, n = Na(new _e()), o = G(n), s = Br[$o] && Ca(/./), i = s && G(s), c = Sa([]), l = G(c), u = G(sa), d = Ta(new $e()), f = G(d), h = Ia(new Dt()), p = G(h), m = G(l);
  function* A() {
  }
  const S = dn(A), w = S.prototype;
  async function* R() {
  }
  const T = dn(
    R
  ), j = T.prototype, I = j.prototype, L = G(I);
  async function Z() {
  }
  const se = dn(Z), J = {
    "%InertFunction%": t,
    "%ArrayIteratorPrototype%": l,
    "%InertAsyncFunction%": se,
    "%AsyncGenerator%": j,
    "%InertAsyncGeneratorFunction%": T,
    "%AsyncGeneratorPrototype%": I,
    "%AsyncIteratorPrototype%": L,
    "%Generator%": w,
    "%InertGeneratorFunction%": S,
    "%IteratorPrototype%": m,
    "%MapIteratorPrototype%": f,
    "%RegExpStringIteratorPrototype%": i,
    "%SetIteratorPrototype%": p,
    "%StringIteratorPrototype%": o,
    "%ThrowTypeError%": r,
    "%TypedArray%": u,
    "%InertCompartment%": Gn
  };
  return x.Iterator && (J["%IteratorHelperPrototype%"] = G(
    // eslint-disable-next-line @endo/no-polymorphic-call
    x.Iterator.from([]).take(0)
  ), J["%WrapForValidIteratorPrototype%"] = G(
    // eslint-disable-next-line @endo/no-polymorphic-call
    x.Iterator.from({
      next() {
        return { value: void 0 };
      }
    })
  )), x.AsyncIterator && (J["%AsyncIteratorHelperPrototype%"] = G(
    // eslint-disable-next-line @endo/no-polymorphic-call
    x.AsyncIterator.from([]).take(0)
  ), J["%WrapForValidAsyncIteratorPrototype%"] = G(
    // eslint-disable-next-line @endo/no-polymorphic-call
    x.AsyncIterator.from({ next() {
    } })
  )), J;
}, Us = (t, e) => {
  if (e !== "safe" && e !== "unsafe")
    throw _(`unrecognized fakeHardenOption ${e}`);
  if (e === "safe" || (Object.isExtensible = () => !1, Object.isFrozen = () => !0, Object.isSealed = () => !0, Reflect.isExtensible = () => !1, t.isFake))
    return t;
  const r = (n) => n;
  return r.isFake = !0, y(r);
};
y(Us);
const Ac = () => {
  const t = At, e = t.prototype, r = Wo(At, void 0);
  B(e, {
    constructor: {
      value: r
      // leave other `constructor` attributes as is
    }
  });
  const n = me(
    Be(t)
  ), o = bt(
    de(n, ([s, i]) => [
      s,
      { ...i, configurable: !0 }
    ])
  );
  return B(r, o), { "%SharedSymbol%": r };
}, Tc = (t) => {
  try {
    return t(), !1;
  } catch {
    return !0;
  }
}, _o = (t, e, r) => {
  if (t === void 0)
    return !1;
  const n = ee(t, e);
  if (!n || "value" in n)
    return !1;
  const { get: o, set: s } = n;
  if (typeof o != "function" || typeof s != "function" || o() !== r || ue(o, t, []) !== r)
    return !1;
  const i = "Seems to be a setter", c = { __proto__: null };
  if (ue(s, c, [i]), c[e] !== i)
    return !1;
  const l = { __proto__: t };
  return ue(s, l, [i]), l[e] !== i || !Tc(() => ue(s, t, [r])) || "originalValue" in o || n.configurable === !1 ? !1 : (D(t, e, {
    value: r,
    writable: !0,
    enumerable: n.enumerable,
    configurable: !0
  }), !0);
}, Ic = (t) => {
  _o(
    t["%IteratorPrototype%"],
    "constructor",
    t.Iterator
  ), _o(
    t["%IteratorPrototype%"],
    Qe,
    "Iterator"
  );
}, Cc = () => {
  const t = on[De];
  D(on, De, {
    configurable: !0,
    get() {
      return t;
    },
    set(e) {
      this !== on && (Q(this, De) && (this[De] = e), D(this, De, {
        value: e,
        writable: !0,
        enumerable: !0,
        configurable: !0
      }));
    }
  });
}, Rc = () => {
  if (typeof xr.transfer == "function")
    return {};
  const t = x.structuredClone;
  return typeof t != "function" ? {} : (D(xr, "transfer", {
    // @ts-expect-error
    value: {
      /**
       * @param {number} [newLength]
       */
      transfer(r = void 0) {
        const n = Ea(this);
        if (r === void 0 || r === n)
          return t(this, { transfer: [this] });
        if (typeof r != "number")
          throw _("transfer newLength if provided must be a number");
        if (r > n) {
          const o = new To(r), s = new mn(this), i = new mn(o);
          return ka(i, s), t(this, { transfer: [this] }), o;
        } else {
          const o = xa(this, 0, r);
          return t(this, { transfer: [this] }), o;
        }
      }
    }.transfer,
    writable: !0,
    enumerable: !1,
    configurable: !0
  }), {});
}, gr = (t) => {
  let e = !1;
  const r = (...n) => {
    e ? t(" ", ...n) : t(...n);
  };
  return (
    /** @type {GroupReporter} */
    {
      warn(...n) {
        r(...n);
      },
      error(...n) {
        r(...n);
      },
      groupCollapsed(...n) {
        Y(!e), t(...n), e = !0;
      },
      groupEnd() {
        e = !1;
      }
    }
  );
}, vo = () => {
}, js = (t) => {
  if (t === "none")
    return gr(vo);
  if (t !== "platform" && t !== "console")
    throw new _(`Invalid lockdown reporting option: ${t}`);
  if (t === "console" || x.window === x || x.importScripts !== void 0)
    return console;
  if (x.console !== void 0) {
    const e = x.console, r = Wo(e.error, e);
    return gr(r);
  }
  return x.print !== void 0 ? gr(x.print) : gr(vo);
}, bo = (t, e, r) => {
  const { warn: n, error: o, groupCollapsed: s, groupEnd: i } = e;
  let c = !1;
  try {
    return r({
      warn(...l) {
        c || (s(t), c = !0), n(...l);
      },
      error(...l) {
        c || (s(t), c = !0), o(...l);
      }
    });
  } finally {
    c && i();
  }
}, { Fail: fn, details: wo, quote: pn } = Y;
let yr, _r;
const $c = ri(), Nc = () => {
  let t = !1;
  try {
    t = Ee(
      "eval",
      "SES_changed",
      `        eval("SES_changed = true");
        return SES_changed;
      `
    )(Ko, !1), t || delete x.SES_changed;
  } catch {
    t = !0;
  }
  if (!t)
    throw _(
      "SES cannot initialize unless 'eval' is the original intrinsic 'eval', suitable for direct-eval (dynamically scoped eval) (SES_DIRECT_EVAL)"
    );
}, Zs = (t = {}) => {
  const {
    errorTaming: e = ce("LOCKDOWN_ERROR_TAMING", "safe"),
    errorTrapping: r = (
      /** @type {"platform" | "none" | "report" | "abort" | "exit"} */
      ce("LOCKDOWN_ERROR_TRAPPING", "platform")
    ),
    reporting: n = (
      /** @type {"platform" | "console" | "none"} */
      ce("LOCKDOWN_REPORTING", "platform")
    ),
    unhandledRejectionTrapping: o = (
      /** @type {"none" | "report"} */
      ce("LOCKDOWN_UNHANDLED_REJECTION_TRAPPING", "report")
    ),
    regExpTaming: s = ce("LOCKDOWN_REGEXP_TAMING", "safe"),
    localeTaming: i = ce("LOCKDOWN_LOCALE_TAMING", "safe"),
    consoleTaming: c = (
      /** @type {'unsafe' | 'safe'} */
      ce("LOCKDOWN_CONSOLE_TAMING", "safe")
    ),
    overrideTaming: l = (
      /** @type {'moderate' | 'min' | 'severe'} */
      ce("LOCKDOWN_OVERRIDE_TAMING", "moderate")
    ),
    stackFiltering: u = ce("LOCKDOWN_STACK_FILTERING", "concise"),
    domainTaming: d = ce("LOCKDOWN_DOMAIN_TAMING", "safe"),
    evalTaming: f = ce("LOCKDOWN_EVAL_TAMING", "safeEval"),
    overrideDebug: h = et(
      On(ce("LOCKDOWN_OVERRIDE_DEBUG", ""), ","),
      /** @param {string} debugName */
      (Pe) => Pe !== ""
    ),
    legacyRegeneratorRuntimeTaming: p = ce(
      "LOCKDOWN_LEGACY_REGENERATOR_RUNTIME_TAMING",
      "safe"
    ),
    __hardenTaming__: m = ce("LOCKDOWN_HARDEN_TAMING", "safe"),
    dateTaming: A = "safe",
    // deprecated
    mathTaming: S = "safe",
    // deprecated
    ...w
  } = t;
  p === "safe" || p === "unsafe-ignore" || fn`lockdown(): non supported option legacyRegeneratorRuntimeTaming: ${pn(p)}`, f === "unsafeEval" || f === "safeEval" || f === "noEval" || fn`lockdown(): non supported option evalTaming: ${pn(f)}`;
  const R = qe(w);
  R.length === 0 || fn`lockdown(): non supported option ${pn(R)}`;
  const T = js(n);
  if (yr === void 0 || // eslint-disable-next-line @endo/no-polymorphic-call
  Y.fail(
    wo`Already locked down at ${yr} (SES_ALREADY_LOCKED_DOWN)`,
    _
  ), yr = _("Prior lockdown (SES_ALREADY_LOCKED_DOWN)"), yr.stack, Nc(), x.Function.prototype.constructor !== x.Function && // @ts-ignore harden is absent on globalThis type def.
  typeof x.harden == "function" && // @ts-ignore lockdown is absent on globalThis type def.
  typeof x.lockdown == "function" && x.Date.prototype.constructor !== x.Date && typeof x.Date.now == "function" && // @ts-ignore does not recognize that Date constructor is a special
  // Function.
  // eslint-disable-next-line @endo/no-polymorphic-call
  Zr(x.Date.prototype.constructor.now(), NaN))
    throw _(
      "Already locked down but not by this SES instance (SES_MULTIPLE_INSTANCES)"
    );
  Li(d);
  const I = Ns(), { addIntrinsics: L, completePrototypes: Z, finalIntrinsics: se } = ms(T), J = Us($c, m);
  L({ harden: J }), L(li()), L(ui(A)), L(nc(e, u)), L(di(S)), L(fi(s)), L(Ac()), L(Rc()), L(Fi()), L(Pc()), Z();
  const be = se(), Me = { __proto__: null };
  typeof x.Buffer == "function" && (Me.Buffer = x.Buffer);
  let dr;
  e === "safe" && (dr = be["%InitialGetStackString%"]);
  const zt = Zi(
    c,
    r,
    o,
    dr
  );
  if (x.console = /** @type {Console} */
  zt.console, typeof /** @type {any} */
  zt.console._times == "object" && (Me.SafeMap = G(
    // eslint-disable-next-line no-underscore-dangle
    /** @type {any} */
    zt.console._times
  )), (e === "unsafe" || e === "unsafe-debug") && x.assert === Y && (x.assert = Xr(void 0, !0)), vi(be, i), Ic(be), bo(
    "SES Removing unpermitted intrinsics",
    T,
    (Pe) => ci(
      be,
      I,
      Pe
    )
  ), _s(x), vs(x, {
    intrinsics: be,
    newGlobalPropertyNames: ro,
    makeCompartmentConstructor: Sn,
    markVirtualizedNativeFunction: I
  }), f === "noEval")
    wn(
      x,
      Fa,
      I
    );
  else if (f === "safeEval") {
    const { safeEvaluate: Pe } = Un({ globalObject: x });
    wn(
      x,
      Pe,
      I
    );
  }
  return () => {
    _r === void 0 || // eslint-disable-next-line @endo/no-polymorphic-call
    Y.fail(
      wo`Already locked down at ${_r} (SES_ALREADY_LOCKED_DOWN)`,
      _
    ), _r = _(
      "Prior lockdown (SES_ALREADY_LOCKED_DOWN)"
    ), _r.stack, bo(
      "SES Enabling property overrides",
      T,
      (st) => mi(
        be,
        l,
        st,
        h
      )
    ), p === "unsafe-ignore" && Cc();
    const Pe = {
      intrinsics: be,
      hostIntrinsics: Me,
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
    for (const st of Nt(ro))
      Pe.globals[st] = x[st];
    return J(Pe), J;
  };
};
x.lockdown = (t) => {
  const e = Zs(t);
  x.harden = e();
};
x.repairIntrinsics = (t) => {
  const e = Zs(t);
  x.hardenIntrinsics = () => {
    x.harden = e();
  };
};
const Oc = Ns(), Mc = js("none");
x.Compartment = Sn(
  Sn,
  // Any reporting that would need to be done should have already been done
  // during `lockdown()`.
  // See https://github.com/endojs/endo/pull/2624#discussion_r1840979770
  ii(x, Mc),
  Oc
);
x.assert = Y;
const Lc = Ms(Ir), Fc = ha(
  "MAKE_CAUSAL_CONSOLE_FROM_LOGGER_KEY_FOR_SES_AVA"
);
x[Fc] = Lc;
const Dc = (t, e = t, r) => {
  let n = { x: 0, y: 0 }, o = { x: 0, y: 0 }, s = { x: 0, y: 0 };
  const i = (u) => {
    const { clientX: d, clientY: f } = u, h = d - s.x + o.x, p = f - s.y + o.y;
    n = { x: h, y: p }, e.style.transform = `translate(${h}px, ${p}px)`, r == null || r();
  }, c = () => {
    document.removeEventListener("mousemove", i), document.removeEventListener("mouseup", c);
  }, l = (u) => {
    s = { x: u.clientX, y: u.clientY }, o = { x: n.x, y: n.y }, document.addEventListener("mousemove", i), document.addEventListener("mouseup", c);
  };
  return t.addEventListener("mousedown", l), c;
}, Uc = `:host{--spacing-4: .25rem;--spacing-8: calc(var(--spacing-4) * 2);--spacing-12: calc(var(--spacing-4) * 3);--spacing-16: calc(var(--spacing-4) * 4);--spacing-20: calc(var(--spacing-4) * 5);--spacing-24: calc(var(--spacing-4) * 6);--spacing-28: calc(var(--spacing-4) * 7);--spacing-32: calc(var(--spacing-4) * 8);--spacing-36: calc(var(--spacing-4) * 9);--spacing-40: calc(var(--spacing-4) * 10);--font-weight-regular: 400;--font-weight-bold: 500;--font-line-height-s: 1.2;--font-line-height-m: 1.4;--font-line-height-l: 1.5;--font-size-s: 12px;--font-size-m: 14px;--font-size-l: 16px}[data-theme]{background-color:var(--color-background-primary);color:var(--color-foreground-secondary)}::-webkit-resizer{display:none}.wrapper{position:absolute;inset-block-start:var(--modal-block-start);inset-inline-start:var(--modal-inline-start);z-index:1000;padding:10px;border-radius:15px;border:2px solid var(--color-background-quaternary);box-shadow:0 0 10px #0000004d;overflow:hidden;min-inline-size:25px;min-block-size:200px;resize:both}.wrapper:after{content:"";cursor:se-resize;inline-size:1rem;block-size:1rem;background-image:url("data:image/svg+xml,%3csvg%20width='16.022'%20xmlns='http://www.w3.org/2000/svg'%20height='16.022'%20viewBox='-0.011%20-0.011%2016.022%2016.022'%20fill='none'%3e%3cg%20data-testid='Group'%3e%3cg%20data-testid='Path'%3e%3cpath%20d='M.011%2015.917%2015.937-.011'%20class='fills'/%3e%3cg%20class='strokes'%3e%3cpath%20d='M.011%2015.917%2015.937-.011'%20style='fill:%20none;%20stroke-width:%201;%20stroke:%20rgb(111,%20111,%20111);%20stroke-opacity:%201;%20stroke-linecap:%20round;'%20class='stroke-shape'/%3e%3c/g%3e%3c/g%3e%3cg%20data-testid='Path'%3e%3cpath%20d='m11.207%2014.601%203.361-3.401'%20class='fills'/%3e%3cg%20class='strokes'%3e%3cpath%20d='m11.207%2014.601%203.361-3.401'%20style='fill:%20none;%20stroke-width:%201;%20stroke:%20rgb(111,%20111,%20111);%20stroke-opacity:%201;%20stroke-linecap:%20round;'%20class='stroke-shape'/%3e%3c/g%3e%3c/g%3e%3cg%20data-testid='Path'%3e%3cpath%20d='m4.884%2016.004%2011.112-11.17'%20class='fills'/%3e%3cg%20class='strokes'%3e%3cpath%20d='m4.884%2016.004%2011.112-11.17'%20style='fill:%20none;%20stroke-width:%201;%20stroke:%20rgb(111,%20111,%20111);%20stroke-opacity:%201;%20stroke-linecap:%20round;'%20class='stroke-shape'/%3e%3c/g%3e%3c/g%3e%3c/g%3e%3c/svg%3e");background-position:center;right:5px;bottom:5px;pointer-events:none;position:absolute}.inner{padding:10px;cursor:grab;box-sizing:border-box;display:flex;flex-direction:column;overflow:hidden;block-size:100%}.inner>*{flex:1}.inner>.header{flex:0}.header{align-items:center;display:flex;justify-content:space-between;border-block-end:2px solid var(--color-background-quaternary);padding-block-end:var(--spacing-4)}button{background:transparent;border:0;cursor:pointer;padding:0}h1{font-size:var(--font-size-s);font-weight:var(--font-weight-bold);margin:0;margin-inline-end:var(--spacing-4);-webkit-user-select:none;user-select:none}iframe{border:none;inline-size:100%;block-size:100%}`;
function jc(t, e, r, n, o) {
  const s = document.createElement("plugin-modal");
  s.setTheme(r);
  const { width: i } = zs(s, n == null ? void 0 : n.width, n == null ? void 0 : n.height), c = {
    blockStart: 40,
    // To be able to resize the element as expected the position must be absolute from the right.
    // This value is the length of the window minus the width of the element plus the width of the design tab.
    inlineStart: window.innerWidth - i - 290
  };
  return s.style.setProperty(
    "--modal-block-start",
    `${c.blockStart}px`
  ), s.style.setProperty(
    "--modal-inline-start",
    `${c.inlineStart}px`
  ), s.setAttribute("title", t), s.setAttribute("iframe-src", e), o && s.setAttribute("allow-downloads", "true"), document.body.appendChild(s), s;
}
function zs(t, e = 335, r = 590) {
  const s = e > window.innerWidth ? window.innerWidth - 290 : e, i = parseInt(
    t.style.getPropertyValue("--modal-block-start") || "40",
    10
  ), c = window.innerHeight - i;
  return e = Math.min(e, s), r = Math.min(r, c), e = Math.max(e, 200), r = Math.max(r, 200), t.wrapper.style.width = `${e}px`, t.wrapper.style.minWidth = `${e}px`, t.wrapper.style.height = `${r}px`, t.wrapper.style.minHeight = `${r}px`, { width: e, height: r };
}
const Zc = `
<svg width="16"  height="16"xmlns="http://www.w3.org/2000/svg" fill="none"><g class="fills"><rect rx="0" ry="0" width="16" height="16" class="frame-background"/></g><g class="frame-children"><path d="M11.997 3.997 8 8l-3.997 4.003m-.006-8L8 8l4.003 3.997" class="fills"/><g class="strokes"><path d="M11.997 3.997 8 8l-3.997 4.003m-.006-8L8 8l4.003 3.997" style="fill: none; stroke-width: 1; stroke: rgb(143, 157, 163); stroke-opacity: 1; stroke-linecap: round;" class="stroke-shape"/></g></g></svg>`;
var We, Rt;
class zc extends HTMLElement {
  constructor() {
    super();
    tn(this, We);
    tn(this, Rt);
    this.wrapper = document.createElement("div"), fr(this, We, document.createElement("div")), fr(this, Rt, null), this.attachShadow({ mode: "open" });
  }
  setTheme(r) {
    this.wrapper && this.wrapper.setAttribute("data-theme", r);
  }
  resize(r, n) {
    this.wrapper && zs(this, r, n);
  }
  disconnectedCallback() {
    var r;
    (r = at(this, Rt)) == null || r.call(this);
  }
  calculateZIndex() {
    const r = document.querySelectorAll("plugin-modal"), n = Array.from(r).filter((s) => s !== this).map((s) => Number(s.style.zIndex)), o = Math.max(...n, 0);
    this.style.zIndex = (o + 1).toString();
  }
  connectedCallback() {
    const r = this.getAttribute("title"), n = this.getAttribute("iframe-src"), o = this.getAttribute("allow-downloads") || !1;
    if (!r || !n)
      throw new Error("title and iframe-src attributes are required");
    if (!this.shadowRoot)
      throw new Error("Error creating shadow root");
    at(this, We).classList.add("inner"), this.wrapper.classList.add("wrapper"), this.wrapper.style.maxInlineSize = "90vw", this.wrapper.style.maxBlockSize = "90vh", fr(this, Rt, Dc(at(this, We), this.wrapper, () => {
      this.calculateZIndex();
    }));
    const s = document.createElement("div");
    s.classList.add("header");
    const i = document.createElement("h1");
    i.textContent = r, s.appendChild(i);
    const c = document.createElement("button");
    c.setAttribute("type", "button"), c.innerHTML = `<div class="close">${Zc}</div>`, c.addEventListener("click", () => {
      this.shadowRoot && this.shadowRoot.dispatchEvent(
        new CustomEvent("close", {
          composed: !0,
          bubbles: !0
        })
      );
    }), s.appendChild(c);
    const l = document.createElement("iframe");
    l.src = n, l.allow = "", l.sandbox.add(
      "allow-scripts",
      "allow-forms",
      "allow-modals",
      "allow-popups",
      "allow-popups-to-escape-sandbox",
      "allow-storage-access-by-user-activation"
    ), o && l.sandbox.add("allow-downloads"), l.addEventListener("load", () => {
      var d;
      (d = this.shadowRoot) == null || d.dispatchEvent(
        new CustomEvent("load", {
          composed: !0,
          bubbles: !0
        })
      );
    }), this.addEventListener("message", (d) => {
      l.contentWindow && l.contentWindow.postMessage(d.detail, "*");
    }), this.shadowRoot.appendChild(this.wrapper), this.wrapper.appendChild(at(this, We)), at(this, We).appendChild(s), at(this, We).appendChild(l);
    const u = document.createElement("style");
    u.textContent = Uc, this.shadowRoot.appendChild(u), this.calculateZIndex();
  }
  size() {
    const r = Number(this.wrapper.style.width.replace("px", "") || "300"), n = Number(this.wrapper.style.height.replace("px", "") || "400");
    return { width: r, height: n };
  }
}
We = new WeakMap(), Rt = new WeakMap();
customElements.define("plugin-modal", zc);
var F;
(function(t) {
  t.assertEqual = (o) => o;
  function e(o) {
  }
  t.assertIs = e;
  function r(o) {
    throw new Error();
  }
  t.assertNever = r, t.arrayToEnum = (o) => {
    const s = {};
    for (const i of o)
      s[i] = i;
    return s;
  }, t.getValidEnumValues = (o) => {
    const s = t.objectKeys(o).filter((c) => typeof o[o[c]] != "number"), i = {};
    for (const c of s)
      i[c] = o[c];
    return t.objectValues(i);
  }, t.objectValues = (o) => t.objectKeys(o).map(function(s) {
    return o[s];
  }), t.objectKeys = typeof Object.keys == "function" ? (o) => Object.keys(o) : (o) => {
    const s = [];
    for (const i in o)
      Object.prototype.hasOwnProperty.call(o, i) && s.push(i);
    return s;
  }, t.find = (o, s) => {
    for (const i of o)
      if (s(i))
        return i;
  }, t.isInteger = typeof Number.isInteger == "function" ? (o) => Number.isInteger(o) : (o) => typeof o == "number" && isFinite(o) && Math.floor(o) === o;
  function n(o, s = " | ") {
    return o.map((i) => typeof i == "string" ? `'${i}'` : i).join(s);
  }
  t.joinValues = n, t.jsonStringifyReplacer = (o, s) => typeof s == "bigint" ? s.toString() : s;
})(F || (F = {}));
var xn;
(function(t) {
  t.mergeShapes = (e, r) => ({
    ...e,
    ...r
    // second overwrites first
  });
})(xn || (xn = {}));
const b = F.arrayToEnum([
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
]), He = (t) => {
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
}, g = F.arrayToEnum([
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
]), Bc = (t) => JSON.stringify(t, null, 2).replace(/"([^"]+)":/g, "$1:");
class ye extends Error {
  get errors() {
    return this.issues;
  }
  constructor(e) {
    super(), this.issues = [], this.addIssue = (n) => {
      this.issues = [...this.issues, n];
    }, this.addIssues = (n = []) => {
      this.issues = [...this.issues, ...n];
    };
    const r = new.target.prototype;
    Object.setPrototypeOf ? Object.setPrototypeOf(this, r) : this.__proto__ = r, this.name = "ZodError", this.issues = e;
  }
  format(e) {
    const r = e || function(s) {
      return s.message;
    }, n = { _errors: [] }, o = (s) => {
      for (const i of s.issues)
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
    if (!(e instanceof ye))
      throw new Error(`Not a ZodError: ${e}`);
  }
  toString() {
    return this.message;
  }
  get message() {
    return JSON.stringify(this.issues, F.jsonStringifyReplacer, 2);
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
ye.create = (t) => new ye(t);
const Mt = (t, e) => {
  let r;
  switch (t.code) {
    case g.invalid_type:
      t.received === b.undefined ? r = "Required" : r = `Expected ${t.expected}, received ${t.received}`;
      break;
    case g.invalid_literal:
      r = `Invalid literal value, expected ${JSON.stringify(t.expected, F.jsonStringifyReplacer)}`;
      break;
    case g.unrecognized_keys:
      r = `Unrecognized key(s) in object: ${F.joinValues(t.keys, ", ")}`;
      break;
    case g.invalid_union:
      r = "Invalid input";
      break;
    case g.invalid_union_discriminator:
      r = `Invalid discriminator value. Expected ${F.joinValues(t.options)}`;
      break;
    case g.invalid_enum_value:
      r = `Invalid enum value. Expected ${F.joinValues(t.options)}, received '${t.received}'`;
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
      typeof t.validation == "object" ? "includes" in t.validation ? (r = `Invalid input: must include "${t.validation.includes}"`, typeof t.validation.position == "number" && (r = `${r} at one or more positions greater than or equal to ${t.validation.position}`)) : "startsWith" in t.validation ? r = `Invalid input: must start with "${t.validation.startsWith}"` : "endsWith" in t.validation ? r = `Invalid input: must end with "${t.validation.endsWith}"` : F.assertNever(t.validation) : t.validation !== "regex" ? r = `Invalid ${t.validation}` : r = "Invalid";
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
      r = e.defaultError, F.assertNever(t);
  }
  return { message: r };
};
let Bs = Mt;
function Gc(t) {
  Bs = t;
}
function $r() {
  return Bs;
}
const Nr = (t) => {
  const { data: e, path: r, errorMaps: n, issueData: o } = t, s = [...r, ...o.path || []], i = {
    ...o,
    path: s
  };
  if (o.message !== void 0)
    return {
      ...o,
      path: s,
      message: o.message
    };
  let c = "";
  const l = n.filter((u) => !!u).slice().reverse();
  for (const u of l)
    c = u(i, { data: e, defaultError: c }).message;
  return {
    ...o,
    path: s,
    message: c
  };
}, Vc = [];
function v(t, e) {
  const r = $r(), n = Nr({
    issueData: e,
    data: t.data,
    path: t.path,
    errorMaps: [
      t.common.contextualErrorMap,
      // contextual error map is first priority
      t.schemaErrorMap,
      // then schema-bound map if available
      r,
      // then global override map
      r === Mt ? void 0 : Mt
      // then global default map
    ].filter((o) => !!o)
  });
  t.common.issues.push(n);
}
class oe {
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
        return $;
      o.status === "dirty" && e.dirty(), n.push(o.value);
    }
    return { status: e.value, value: n };
  }
  static async mergeObjectAsync(e, r) {
    const n = [];
    for (const o of r) {
      const s = await o.key, i = await o.value;
      n.push({
        key: s,
        value: i
      });
    }
    return oe.mergeObjectSync(e, n);
  }
  static mergeObjectSync(e, r) {
    const n = {};
    for (const o of r) {
      const { key: s, value: i } = o;
      if (s.status === "aborted" || i.status === "aborted")
        return $;
      s.status === "dirty" && e.dirty(), i.status === "dirty" && e.dirty(), s.value !== "__proto__" && (typeof i.value < "u" || o.alwaysSet) && (n[s.value] = i.value);
    }
    return { status: e.value, value: n };
  }
}
const $ = Object.freeze({
  status: "aborted"
}), Pt = (t) => ({ status: "dirty", value: t }), fe = (t) => ({ status: "valid", value: t }), En = (t) => t.status === "aborted", kn = (t) => t.status === "dirty", yt = (t) => t.status === "valid", qt = (t) => typeof Promise < "u" && t instanceof Promise;
function Or(t, e, r, n) {
  if (typeof e == "function" ? t !== e || !0 : !e.has(t)) throw new TypeError("Cannot read private member from an object whose class did not declare it");
  return e.get(t);
}
function Gs(t, e, r, n, o) {
  if (typeof e == "function" ? t !== e || !0 : !e.has(t)) throw new TypeError("Cannot write private member to an object whose class did not declare it");
  return e.set(t, r), r;
}
var E;
(function(t) {
  t.errToObj = (e) => typeof e == "string" ? { message: e } : e || {}, t.toString = (e) => typeof e == "string" ? e : e == null ? void 0 : e.message;
})(E || (E = {}));
var Vt, Ht;
class je {
  constructor(e, r, n, o) {
    this._cachedPath = [], this.parent = e, this.data = r, this._path = n, this._key = o;
  }
  get path() {
    return this._cachedPath.length || (this._key instanceof Array ? this._cachedPath.push(...this._path, ...this._key) : this._cachedPath.push(...this._path, this._key)), this._cachedPath;
  }
}
const So = (t, e) => {
  if (yt(e))
    return { success: !0, data: e.value };
  if (!t.common.issues.length)
    throw new Error("Validation failed but no issues detected.");
  return {
    success: !1,
    get error() {
      if (this._error)
        return this._error;
      const r = new ye(t.common.issues);
      return this._error = r, this._error;
    }
  };
};
function N(t) {
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
class O {
  get description() {
    return this._def.description;
  }
  _getType(e) {
    return He(e.data);
  }
  _getOrReturnCtx(e, r) {
    return r || {
      common: e.parent.common,
      data: e.data,
      parsedType: He(e.data),
      schemaErrorMap: this._def.errorMap,
      path: e.path,
      parent: e.parent
    };
  }
  _processInputParams(e) {
    return {
      status: new oe(),
      ctx: {
        common: e.parent.common,
        data: e.data,
        parsedType: He(e.data),
        schemaErrorMap: this._def.errorMap,
        path: e.path,
        parent: e.parent
      }
    };
  }
  _parseSync(e) {
    const r = this._parse(e);
    if (qt(r))
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
      parsedType: He(e)
    }, s = this._parseSync({ data: e, path: o.path, parent: o });
    return So(o, s);
  }
  "~validate"(e) {
    var r, n;
    const o = {
      common: {
        issues: [],
        async: !!this["~standard"].async
      },
      path: [],
      schemaErrorMap: this._def.errorMap,
      parent: null,
      data: e,
      parsedType: He(e)
    };
    if (!this["~standard"].async)
      try {
        const s = this._parseSync({ data: e, path: [], parent: o });
        return yt(s) ? {
          value: s.value
        } : {
          issues: o.common.issues
        };
      } catch (s) {
        !((n = (r = s == null ? void 0 : s.message) === null || r === void 0 ? void 0 : r.toLowerCase()) === null || n === void 0) && n.includes("encountered") && (this["~standard"].async = !0), o.common = {
          issues: [],
          async: !0
        };
      }
    return this._parseAsync({ data: e, path: [], parent: o }).then((s) => yt(s) ? {
      value: s.value
    } : {
      issues: o.common.issues
    });
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
      parsedType: He(e)
    }, o = this._parse({ data: e, path: n.path, parent: n }), s = await (qt(o) ? o : Promise.resolve(o));
    return So(n, s);
  }
  refine(e, r) {
    const n = (o) => typeof r == "string" || typeof r > "u" ? { message: r } : typeof r == "function" ? r(o) : r;
    return this._refinement((o, s) => {
      const i = e(o), c = () => s.addIssue({
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
    return new Oe({
      schema: this,
      typeName: C.ZodEffects,
      effect: { type: "refinement", refinement: e }
    });
  }
  superRefine(e) {
    return this._refinement(e);
  }
  constructor(e) {
    this.spa = this.safeParseAsync, this._def = e, this.parse = this.parse.bind(this), this.safeParse = this.safeParse.bind(this), this.parseAsync = this.parseAsync.bind(this), this.safeParseAsync = this.safeParseAsync.bind(this), this.spa = this.spa.bind(this), this.refine = this.refine.bind(this), this.refinement = this.refinement.bind(this), this.superRefine = this.superRefine.bind(this), this.optional = this.optional.bind(this), this.nullable = this.nullable.bind(this), this.nullish = this.nullish.bind(this), this.array = this.array.bind(this), this.promise = this.promise.bind(this), this.or = this.or.bind(this), this.and = this.and.bind(this), this.transform = this.transform.bind(this), this.brand = this.brand.bind(this), this.default = this.default.bind(this), this.catch = this.catch.bind(this), this.describe = this.describe.bind(this), this.pipe = this.pipe.bind(this), this.readonly = this.readonly.bind(this), this.isNullable = this.isNullable.bind(this), this.isOptional = this.isOptional.bind(this), this["~standard"] = {
      version: 1,
      vendor: "zod",
      validate: (r) => this["~validate"](r)
    };
  }
  optional() {
    return Ue.create(this, this._def);
  }
  nullable() {
    return ot.create(this, this._def);
  }
  nullish() {
    return this.nullable().optional();
  }
  array() {
    return Ne.create(this);
  }
  promise() {
    return Ft.create(this, this._def);
  }
  or(e) {
    return Xt.create([this, e], this._def);
  }
  and(e) {
    return Qt.create(this, e, this._def);
  }
  transform(e) {
    return new Oe({
      ...N(this._def),
      schema: this,
      typeName: C.ZodEffects,
      effect: { type: "transform", transform: e }
    });
  }
  default(e) {
    const r = typeof e == "function" ? e : () => e;
    return new or({
      ...N(this._def),
      innerType: this,
      defaultValue: r,
      typeName: C.ZodDefault
    });
  }
  brand() {
    return new Hn({
      typeName: C.ZodBranded,
      type: this,
      ...N(this._def)
    });
  }
  catch(e) {
    const r = typeof e == "function" ? e : () => e;
    return new sr({
      ...N(this._def),
      innerType: this,
      catchValue: r,
      typeName: C.ZodCatch
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
    return ur.create(this, e);
  }
  readonly() {
    return ar.create(this);
  }
  isOptional() {
    return this.safeParse(void 0).success;
  }
  isNullable() {
    return this.safeParse(null).success;
  }
}
const Hc = /^c[^\s-]{8,}$/i, Wc = /^[0-9a-z]+$/, qc = /^[0-9A-HJKMNP-TV-Z]{26}$/i, Kc = /^[0-9a-fA-F]{8}\b-[0-9a-fA-F]{4}\b-[0-9a-fA-F]{4}\b-[0-9a-fA-F]{4}\b-[0-9a-fA-F]{12}$/i, Yc = /^[a-z0-9_-]{21}$/i, Jc = /^[A-Za-z0-9-_]+\.[A-Za-z0-9-_]+\.[A-Za-z0-9-_]*$/, Xc = /^[-+]?P(?!$)(?:(?:[-+]?\d+Y)|(?:[-+]?\d+[.,]\d+Y$))?(?:(?:[-+]?\d+M)|(?:[-+]?\d+[.,]\d+M$))?(?:(?:[-+]?\d+W)|(?:[-+]?\d+[.,]\d+W$))?(?:(?:[-+]?\d+D)|(?:[-+]?\d+[.,]\d+D$))?(?:T(?=[\d+-])(?:(?:[-+]?\d+H)|(?:[-+]?\d+[.,]\d+H$))?(?:(?:[-+]?\d+M)|(?:[-+]?\d+[.,]\d+M$))?(?:[-+]?\d+(?:[.,]\d+)?S)?)??$/, Qc = /^(?!\.)(?!.*\.\.)([A-Z0-9_'+\-\.]*)[A-Z0-9_+-]@([A-Z0-9][A-Z0-9\-]*\.)+[A-Z]{2,}$/i, el = "^(\\p{Extended_Pictographic}|\\p{Emoji_Component})+$";
let hn;
const tl = /^(?:(?:25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9][0-9]|[0-9])\.){3}(?:25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9][0-9]|[0-9])$/, rl = /^(?:(?:25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9][0-9]|[0-9])\.){3}(?:25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9][0-9]|[0-9])\/(3[0-2]|[12]?[0-9])$/, nl = /^(([0-9a-fA-F]{1,4}:){7,7}[0-9a-fA-F]{1,4}|([0-9a-fA-F]{1,4}:){1,7}:|([0-9a-fA-F]{1,4}:){1,6}:[0-9a-fA-F]{1,4}|([0-9a-fA-F]{1,4}:){1,5}(:[0-9a-fA-F]{1,4}){1,2}|([0-9a-fA-F]{1,4}:){1,4}(:[0-9a-fA-F]{1,4}){1,3}|([0-9a-fA-F]{1,4}:){1,3}(:[0-9a-fA-F]{1,4}){1,4}|([0-9a-fA-F]{1,4}:){1,2}(:[0-9a-fA-F]{1,4}){1,5}|[0-9a-fA-F]{1,4}:((:[0-9a-fA-F]{1,4}){1,6})|:((:[0-9a-fA-F]{1,4}){1,7}|:)|fe80:(:[0-9a-fA-F]{0,4}){0,4}%[0-9a-zA-Z]{1,}|::(ffff(:0{1,4}){0,1}:){0,1}((25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9])\.){3,3}(25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9])|([0-9a-fA-F]{1,4}:){1,4}:((25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9])\.){3,3}(25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9]))$/, ol = /^(([0-9a-fA-F]{1,4}:){7,7}[0-9a-fA-F]{1,4}|([0-9a-fA-F]{1,4}:){1,7}:|([0-9a-fA-F]{1,4}:){1,6}:[0-9a-fA-F]{1,4}|([0-9a-fA-F]{1,4}:){1,5}(:[0-9a-fA-F]{1,4}){1,2}|([0-9a-fA-F]{1,4}:){1,4}(:[0-9a-fA-F]{1,4}){1,3}|([0-9a-fA-F]{1,4}:){1,3}(:[0-9a-fA-F]{1,4}){1,4}|([0-9a-fA-F]{1,4}:){1,2}(:[0-9a-fA-F]{1,4}){1,5}|[0-9a-fA-F]{1,4}:((:[0-9a-fA-F]{1,4}){1,6})|:((:[0-9a-fA-F]{1,4}){1,7}|:)|fe80:(:[0-9a-fA-F]{0,4}){0,4}%[0-9a-zA-Z]{1,}|::(ffff(:0{1,4}){0,1}:){0,1}((25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9])\.){3,3}(25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9])|([0-9a-fA-F]{1,4}:){1,4}:((25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9])\.){3,3}(25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9]))\/(12[0-8]|1[01][0-9]|[1-9]?[0-9])$/, sl = /^([0-9a-zA-Z+/]{4})*(([0-9a-zA-Z+/]{2}==)|([0-9a-zA-Z+/]{3}=))?$/, al = /^([0-9a-zA-Z-_]{4})*(([0-9a-zA-Z-_]{2}(==)?)|([0-9a-zA-Z-_]{3}(=)?))?$/, Vs = "((\\d\\d[2468][048]|\\d\\d[13579][26]|\\d\\d0[48]|[02468][048]00|[13579][26]00)-02-29|\\d{4}-((0[13578]|1[02])-(0[1-9]|[12]\\d|3[01])|(0[469]|11)-(0[1-9]|[12]\\d|30)|(02)-(0[1-9]|1\\d|2[0-8])))", il = new RegExp(`^${Vs}$`);
function Hs(t) {
  let e = "([01]\\d|2[0-3]):[0-5]\\d:[0-5]\\d";
  return t.precision ? e = `${e}\\.\\d{${t.precision}}` : t.precision == null && (e = `${e}(\\.\\d+)?`), e;
}
function cl(t) {
  return new RegExp(`^${Hs(t)}$`);
}
function Ws(t) {
  let e = `${Vs}T${Hs(t)}`;
  const r = [];
  return r.push(t.local ? "Z?" : "Z"), t.offset && r.push("([+-]\\d{2}:?\\d{2})"), e = `${e}(${r.join("|")})`, new RegExp(`^${e}$`);
}
function ll(t, e) {
  return !!((e === "v4" || !e) && tl.test(t) || (e === "v6" || !e) && nl.test(t));
}
function ul(t, e) {
  if (!Jc.test(t))
    return !1;
  try {
    const [r] = t.split("."), n = r.replace(/-/g, "+").replace(/_/g, "/").padEnd(r.length + (4 - r.length % 4) % 4, "="), o = JSON.parse(atob(n));
    return !(typeof o != "object" || o === null || !o.typ || !o.alg || e && o.alg !== e);
  } catch {
    return !1;
  }
}
function dl(t, e) {
  return !!((e === "v4" || !e) && rl.test(t) || (e === "v6" || !e) && ol.test(t));
}
class Re extends O {
  _parse(e) {
    if (this._def.coerce && (e.data = String(e.data)), this._getType(e) !== b.string) {
      const s = this._getOrReturnCtx(e);
      return v(s, {
        code: g.invalid_type,
        expected: b.string,
        received: s.parsedType
      }), $;
    }
    const n = new oe();
    let o;
    for (const s of this._def.checks)
      if (s.kind === "min")
        e.data.length < s.value && (o = this._getOrReturnCtx(e, o), v(o, {
          code: g.too_small,
          minimum: s.value,
          type: "string",
          inclusive: !0,
          exact: !1,
          message: s.message
        }), n.dirty());
      else if (s.kind === "max")
        e.data.length > s.value && (o = this._getOrReturnCtx(e, o), v(o, {
          code: g.too_big,
          maximum: s.value,
          type: "string",
          inclusive: !0,
          exact: !1,
          message: s.message
        }), n.dirty());
      else if (s.kind === "length") {
        const i = e.data.length > s.value, c = e.data.length < s.value;
        (i || c) && (o = this._getOrReturnCtx(e, o), i ? v(o, {
          code: g.too_big,
          maximum: s.value,
          type: "string",
          inclusive: !0,
          exact: !0,
          message: s.message
        }) : c && v(o, {
          code: g.too_small,
          minimum: s.value,
          type: "string",
          inclusive: !0,
          exact: !0,
          message: s.message
        }), n.dirty());
      } else if (s.kind === "email")
        Qc.test(e.data) || (o = this._getOrReturnCtx(e, o), v(o, {
          validation: "email",
          code: g.invalid_string,
          message: s.message
        }), n.dirty());
      else if (s.kind === "emoji")
        hn || (hn = new RegExp(el, "u")), hn.test(e.data) || (o = this._getOrReturnCtx(e, o), v(o, {
          validation: "emoji",
          code: g.invalid_string,
          message: s.message
        }), n.dirty());
      else if (s.kind === "uuid")
        Kc.test(e.data) || (o = this._getOrReturnCtx(e, o), v(o, {
          validation: "uuid",
          code: g.invalid_string,
          message: s.message
        }), n.dirty());
      else if (s.kind === "nanoid")
        Yc.test(e.data) || (o = this._getOrReturnCtx(e, o), v(o, {
          validation: "nanoid",
          code: g.invalid_string,
          message: s.message
        }), n.dirty());
      else if (s.kind === "cuid")
        Hc.test(e.data) || (o = this._getOrReturnCtx(e, o), v(o, {
          validation: "cuid",
          code: g.invalid_string,
          message: s.message
        }), n.dirty());
      else if (s.kind === "cuid2")
        Wc.test(e.data) || (o = this._getOrReturnCtx(e, o), v(o, {
          validation: "cuid2",
          code: g.invalid_string,
          message: s.message
        }), n.dirty());
      else if (s.kind === "ulid")
        qc.test(e.data) || (o = this._getOrReturnCtx(e, o), v(o, {
          validation: "ulid",
          code: g.invalid_string,
          message: s.message
        }), n.dirty());
      else if (s.kind === "url")
        try {
          new URL(e.data);
        } catch {
          o = this._getOrReturnCtx(e, o), v(o, {
            validation: "url",
            code: g.invalid_string,
            message: s.message
          }), n.dirty();
        }
      else s.kind === "regex" ? (s.regex.lastIndex = 0, s.regex.test(e.data) || (o = this._getOrReturnCtx(e, o), v(o, {
        validation: "regex",
        code: g.invalid_string,
        message: s.message
      }), n.dirty())) : s.kind === "trim" ? e.data = e.data.trim() : s.kind === "includes" ? e.data.includes(s.value, s.position) || (o = this._getOrReturnCtx(e, o), v(o, {
        code: g.invalid_string,
        validation: { includes: s.value, position: s.position },
        message: s.message
      }), n.dirty()) : s.kind === "toLowerCase" ? e.data = e.data.toLowerCase() : s.kind === "toUpperCase" ? e.data = e.data.toUpperCase() : s.kind === "startsWith" ? e.data.startsWith(s.value) || (o = this._getOrReturnCtx(e, o), v(o, {
        code: g.invalid_string,
        validation: { startsWith: s.value },
        message: s.message
      }), n.dirty()) : s.kind === "endsWith" ? e.data.endsWith(s.value) || (o = this._getOrReturnCtx(e, o), v(o, {
        code: g.invalid_string,
        validation: { endsWith: s.value },
        message: s.message
      }), n.dirty()) : s.kind === "datetime" ? Ws(s).test(e.data) || (o = this._getOrReturnCtx(e, o), v(o, {
        code: g.invalid_string,
        validation: "datetime",
        message: s.message
      }), n.dirty()) : s.kind === "date" ? il.test(e.data) || (o = this._getOrReturnCtx(e, o), v(o, {
        code: g.invalid_string,
        validation: "date",
        message: s.message
      }), n.dirty()) : s.kind === "time" ? cl(s).test(e.data) || (o = this._getOrReturnCtx(e, o), v(o, {
        code: g.invalid_string,
        validation: "time",
        message: s.message
      }), n.dirty()) : s.kind === "duration" ? Xc.test(e.data) || (o = this._getOrReturnCtx(e, o), v(o, {
        validation: "duration",
        code: g.invalid_string,
        message: s.message
      }), n.dirty()) : s.kind === "ip" ? ll(e.data, s.version) || (o = this._getOrReturnCtx(e, o), v(o, {
        validation: "ip",
        code: g.invalid_string,
        message: s.message
      }), n.dirty()) : s.kind === "jwt" ? ul(e.data, s.alg) || (o = this._getOrReturnCtx(e, o), v(o, {
        validation: "jwt",
        code: g.invalid_string,
        message: s.message
      }), n.dirty()) : s.kind === "cidr" ? dl(e.data, s.version) || (o = this._getOrReturnCtx(e, o), v(o, {
        validation: "cidr",
        code: g.invalid_string,
        message: s.message
      }), n.dirty()) : s.kind === "base64" ? sl.test(e.data) || (o = this._getOrReturnCtx(e, o), v(o, {
        validation: "base64",
        code: g.invalid_string,
        message: s.message
      }), n.dirty()) : s.kind === "base64url" ? al.test(e.data) || (o = this._getOrReturnCtx(e, o), v(o, {
        validation: "base64url",
        code: g.invalid_string,
        message: s.message
      }), n.dirty()) : F.assertNever(s);
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
    return new Re({
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
  base64url(e) {
    return this._addCheck({
      kind: "base64url",
      ...E.errToObj(e)
    });
  }
  jwt(e) {
    return this._addCheck({ kind: "jwt", ...E.errToObj(e) });
  }
  ip(e) {
    return this._addCheck({ kind: "ip", ...E.errToObj(e) });
  }
  cidr(e) {
    return this._addCheck({ kind: "cidr", ...E.errToObj(e) });
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
   * Equivalent to `.min(1)`
   */
  nonempty(e) {
    return this.min(1, E.errToObj(e));
  }
  trim() {
    return new Re({
      ...this._def,
      checks: [...this._def.checks, { kind: "trim" }]
    });
  }
  toLowerCase() {
    return new Re({
      ...this._def,
      checks: [...this._def.checks, { kind: "toLowerCase" }]
    });
  }
  toUpperCase() {
    return new Re({
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
  get isCIDR() {
    return !!this._def.checks.find((e) => e.kind === "cidr");
  }
  get isBase64() {
    return !!this._def.checks.find((e) => e.kind === "base64");
  }
  get isBase64url() {
    return !!this._def.checks.find((e) => e.kind === "base64url");
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
Re.create = (t) => {
  var e;
  return new Re({
    checks: [],
    typeName: C.ZodString,
    coerce: (e = t == null ? void 0 : t.coerce) !== null && e !== void 0 ? e : !1,
    ...N(t)
  });
};
function fl(t, e) {
  const r = (t.toString().split(".")[1] || "").length, n = (e.toString().split(".")[1] || "").length, o = r > n ? r : n, s = parseInt(t.toFixed(o).replace(".", "")), i = parseInt(e.toFixed(o).replace(".", ""));
  return s % i / Math.pow(10, o);
}
class tt extends O {
  constructor() {
    super(...arguments), this.min = this.gte, this.max = this.lte, this.step = this.multipleOf;
  }
  _parse(e) {
    if (this._def.coerce && (e.data = Number(e.data)), this._getType(e) !== b.number) {
      const s = this._getOrReturnCtx(e);
      return v(s, {
        code: g.invalid_type,
        expected: b.number,
        received: s.parsedType
      }), $;
    }
    let n;
    const o = new oe();
    for (const s of this._def.checks)
      s.kind === "int" ? F.isInteger(e.data) || (n = this._getOrReturnCtx(e, n), v(n, {
        code: g.invalid_type,
        expected: "integer",
        received: "float",
        message: s.message
      }), o.dirty()) : s.kind === "min" ? (s.inclusive ? e.data < s.value : e.data <= s.value) && (n = this._getOrReturnCtx(e, n), v(n, {
        code: g.too_small,
        minimum: s.value,
        type: "number",
        inclusive: s.inclusive,
        exact: !1,
        message: s.message
      }), o.dirty()) : s.kind === "max" ? (s.inclusive ? e.data > s.value : e.data >= s.value) && (n = this._getOrReturnCtx(e, n), v(n, {
        code: g.too_big,
        maximum: s.value,
        type: "number",
        inclusive: s.inclusive,
        exact: !1,
        message: s.message
      }), o.dirty()) : s.kind === "multipleOf" ? fl(e.data, s.value) !== 0 && (n = this._getOrReturnCtx(e, n), v(n, {
        code: g.not_multiple_of,
        multipleOf: s.value,
        message: s.message
      }), o.dirty()) : s.kind === "finite" ? Number.isFinite(e.data) || (n = this._getOrReturnCtx(e, n), v(n, {
        code: g.not_finite,
        message: s.message
      }), o.dirty()) : F.assertNever(s);
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
    return new tt({
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
    return new tt({
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
    return !!this._def.checks.find((e) => e.kind === "int" || e.kind === "multipleOf" && F.isInteger(e.value));
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
tt.create = (t) => new tt({
  checks: [],
  typeName: C.ZodNumber,
  coerce: (t == null ? void 0 : t.coerce) || !1,
  ...N(t)
});
class rt extends O {
  constructor() {
    super(...arguments), this.min = this.gte, this.max = this.lte;
  }
  _parse(e) {
    if (this._def.coerce)
      try {
        e.data = BigInt(e.data);
      } catch {
        return this._getInvalidInput(e);
      }
    if (this._getType(e) !== b.bigint)
      return this._getInvalidInput(e);
    let n;
    const o = new oe();
    for (const s of this._def.checks)
      s.kind === "min" ? (s.inclusive ? e.data < s.value : e.data <= s.value) && (n = this._getOrReturnCtx(e, n), v(n, {
        code: g.too_small,
        type: "bigint",
        minimum: s.value,
        inclusive: s.inclusive,
        message: s.message
      }), o.dirty()) : s.kind === "max" ? (s.inclusive ? e.data > s.value : e.data >= s.value) && (n = this._getOrReturnCtx(e, n), v(n, {
        code: g.too_big,
        type: "bigint",
        maximum: s.value,
        inclusive: s.inclusive,
        message: s.message
      }), o.dirty()) : s.kind === "multipleOf" ? e.data % s.value !== BigInt(0) && (n = this._getOrReturnCtx(e, n), v(n, {
        code: g.not_multiple_of,
        multipleOf: s.value,
        message: s.message
      }), o.dirty()) : F.assertNever(s);
    return { status: o.value, value: e.data };
  }
  _getInvalidInput(e) {
    const r = this._getOrReturnCtx(e);
    return v(r, {
      code: g.invalid_type,
      expected: b.bigint,
      received: r.parsedType
    }), $;
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
    return new rt({
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
    return new rt({
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
rt.create = (t) => {
  var e;
  return new rt({
    checks: [],
    typeName: C.ZodBigInt,
    coerce: (e = t == null ? void 0 : t.coerce) !== null && e !== void 0 ? e : !1,
    ...N(t)
  });
};
class Kt extends O {
  _parse(e) {
    if (this._def.coerce && (e.data = !!e.data), this._getType(e) !== b.boolean) {
      const n = this._getOrReturnCtx(e);
      return v(n, {
        code: g.invalid_type,
        expected: b.boolean,
        received: n.parsedType
      }), $;
    }
    return fe(e.data);
  }
}
Kt.create = (t) => new Kt({
  typeName: C.ZodBoolean,
  coerce: (t == null ? void 0 : t.coerce) || !1,
  ...N(t)
});
class _t extends O {
  _parse(e) {
    if (this._def.coerce && (e.data = new Date(e.data)), this._getType(e) !== b.date) {
      const s = this._getOrReturnCtx(e);
      return v(s, {
        code: g.invalid_type,
        expected: b.date,
        received: s.parsedType
      }), $;
    }
    if (isNaN(e.data.getTime())) {
      const s = this._getOrReturnCtx(e);
      return v(s, {
        code: g.invalid_date
      }), $;
    }
    const n = new oe();
    let o;
    for (const s of this._def.checks)
      s.kind === "min" ? e.data.getTime() < s.value && (o = this._getOrReturnCtx(e, o), v(o, {
        code: g.too_small,
        message: s.message,
        inclusive: !0,
        exact: !1,
        minimum: s.value,
        type: "date"
      }), n.dirty()) : s.kind === "max" ? e.data.getTime() > s.value && (o = this._getOrReturnCtx(e, o), v(o, {
        code: g.too_big,
        message: s.message,
        inclusive: !0,
        exact: !1,
        maximum: s.value,
        type: "date"
      }), n.dirty()) : F.assertNever(s);
    return {
      status: n.value,
      value: new Date(e.data.getTime())
    };
  }
  _addCheck(e) {
    return new _t({
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
_t.create = (t) => new _t({
  checks: [],
  coerce: (t == null ? void 0 : t.coerce) || !1,
  typeName: C.ZodDate,
  ...N(t)
});
class Mr extends O {
  _parse(e) {
    if (this._getType(e) !== b.symbol) {
      const n = this._getOrReturnCtx(e);
      return v(n, {
        code: g.invalid_type,
        expected: b.symbol,
        received: n.parsedType
      }), $;
    }
    return fe(e.data);
  }
}
Mr.create = (t) => new Mr({
  typeName: C.ZodSymbol,
  ...N(t)
});
class Yt extends O {
  _parse(e) {
    if (this._getType(e) !== b.undefined) {
      const n = this._getOrReturnCtx(e);
      return v(n, {
        code: g.invalid_type,
        expected: b.undefined,
        received: n.parsedType
      }), $;
    }
    return fe(e.data);
  }
}
Yt.create = (t) => new Yt({
  typeName: C.ZodUndefined,
  ...N(t)
});
class Jt extends O {
  _parse(e) {
    if (this._getType(e) !== b.null) {
      const n = this._getOrReturnCtx(e);
      return v(n, {
        code: g.invalid_type,
        expected: b.null,
        received: n.parsedType
      }), $;
    }
    return fe(e.data);
  }
}
Jt.create = (t) => new Jt({
  typeName: C.ZodNull,
  ...N(t)
});
class Lt extends O {
  constructor() {
    super(...arguments), this._any = !0;
  }
  _parse(e) {
    return fe(e.data);
  }
}
Lt.create = (t) => new Lt({
  typeName: C.ZodAny,
  ...N(t)
});
class mt extends O {
  constructor() {
    super(...arguments), this._unknown = !0;
  }
  _parse(e) {
    return fe(e.data);
  }
}
mt.create = (t) => new mt({
  typeName: C.ZodUnknown,
  ...N(t)
});
class Ye extends O {
  _parse(e) {
    const r = this._getOrReturnCtx(e);
    return v(r, {
      code: g.invalid_type,
      expected: b.never,
      received: r.parsedType
    }), $;
  }
}
Ye.create = (t) => new Ye({
  typeName: C.ZodNever,
  ...N(t)
});
class Lr extends O {
  _parse(e) {
    if (this._getType(e) !== b.undefined) {
      const n = this._getOrReturnCtx(e);
      return v(n, {
        code: g.invalid_type,
        expected: b.void,
        received: n.parsedType
      }), $;
    }
    return fe(e.data);
  }
}
Lr.create = (t) => new Lr({
  typeName: C.ZodVoid,
  ...N(t)
});
class Ne extends O {
  _parse(e) {
    const { ctx: r, status: n } = this._processInputParams(e), o = this._def;
    if (r.parsedType !== b.array)
      return v(r, {
        code: g.invalid_type,
        expected: b.array,
        received: r.parsedType
      }), $;
    if (o.exactLength !== null) {
      const i = r.data.length > o.exactLength.value, c = r.data.length < o.exactLength.value;
      (i || c) && (v(r, {
        code: i ? g.too_big : g.too_small,
        minimum: c ? o.exactLength.value : void 0,
        maximum: i ? o.exactLength.value : void 0,
        type: "array",
        inclusive: !0,
        exact: !0,
        message: o.exactLength.message
      }), n.dirty());
    }
    if (o.minLength !== null && r.data.length < o.minLength.value && (v(r, {
      code: g.too_small,
      minimum: o.minLength.value,
      type: "array",
      inclusive: !0,
      exact: !1,
      message: o.minLength.message
    }), n.dirty()), o.maxLength !== null && r.data.length > o.maxLength.value && (v(r, {
      code: g.too_big,
      maximum: o.maxLength.value,
      type: "array",
      inclusive: !0,
      exact: !1,
      message: o.maxLength.message
    }), n.dirty()), r.common.async)
      return Promise.all([...r.data].map((i, c) => o.type._parseAsync(new je(r, i, r.path, c)))).then((i) => oe.mergeArray(n, i));
    const s = [...r.data].map((i, c) => o.type._parseSync(new je(r, i, r.path, c)));
    return oe.mergeArray(n, s);
  }
  get element() {
    return this._def.type;
  }
  min(e, r) {
    return new Ne({
      ...this._def,
      minLength: { value: e, message: E.toString(r) }
    });
  }
  max(e, r) {
    return new Ne({
      ...this._def,
      maxLength: { value: e, message: E.toString(r) }
    });
  }
  length(e, r) {
    return new Ne({
      ...this._def,
      exactLength: { value: e, message: E.toString(r) }
    });
  }
  nonempty(e) {
    return this.min(1, e);
  }
}
Ne.create = (t, e) => new Ne({
  type: t,
  minLength: null,
  maxLength: null,
  exactLength: null,
  typeName: C.ZodArray,
  ...N(e)
});
function kt(t) {
  if (t instanceof V) {
    const e = {};
    for (const r in t.shape) {
      const n = t.shape[r];
      e[r] = Ue.create(kt(n));
    }
    return new V({
      ...t._def,
      shape: () => e
    });
  } else return t instanceof Ne ? new Ne({
    ...t._def,
    type: kt(t.element)
  }) : t instanceof Ue ? Ue.create(kt(t.unwrap())) : t instanceof ot ? ot.create(kt(t.unwrap())) : t instanceof Ze ? Ze.create(t.items.map((e) => kt(e))) : t;
}
class V extends O {
  constructor() {
    super(...arguments), this._cached = null, this.nonstrict = this.passthrough, this.augment = this.extend;
  }
  _getCached() {
    if (this._cached !== null)
      return this._cached;
    const e = this._def.shape(), r = F.objectKeys(e);
    return this._cached = { shape: e, keys: r };
  }
  _parse(e) {
    if (this._getType(e) !== b.object) {
      const u = this._getOrReturnCtx(e);
      return v(u, {
        code: g.invalid_type,
        expected: b.object,
        received: u.parsedType
      }), $;
    }
    const { status: n, ctx: o } = this._processInputParams(e), { shape: s, keys: i } = this._getCached(), c = [];
    if (!(this._def.catchall instanceof Ye && this._def.unknownKeys === "strip"))
      for (const u in o.data)
        i.includes(u) || c.push(u);
    const l = [];
    for (const u of i) {
      const d = s[u], f = o.data[u];
      l.push({
        key: { status: "valid", value: u },
        value: d._parse(new je(o, f, o.path, u)),
        alwaysSet: u in o.data
      });
    }
    if (this._def.catchall instanceof Ye) {
      const u = this._def.unknownKeys;
      if (u === "passthrough")
        for (const d of c)
          l.push({
            key: { status: "valid", value: d },
            value: { status: "valid", value: o.data[d] }
          });
      else if (u === "strict")
        c.length > 0 && (v(o, {
          code: g.unrecognized_keys,
          keys: c
        }), n.dirty());
      else if (u !== "strip") throw new Error("Internal ZodObject error: invalid unknownKeys value.");
    } else {
      const u = this._def.catchall;
      for (const d of c) {
        const f = o.data[d];
        l.push({
          key: { status: "valid", value: d },
          value: u._parse(
            new je(o, f, o.path, d)
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
    }).then((u) => oe.mergeObjectSync(n, u)) : oe.mergeObjectSync(n, l);
  }
  get shape() {
    return this._def.shape();
  }
  strict(e) {
    return E.errToObj, new V({
      ...this._def,
      unknownKeys: "strict",
      ...e !== void 0 ? {
        errorMap: (r, n) => {
          var o, s, i, c;
          const l = (i = (s = (o = this._def).errorMap) === null || s === void 0 ? void 0 : s.call(o, r, n).message) !== null && i !== void 0 ? i : n.defaultError;
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
    return new V({
      ...this._def,
      unknownKeys: "strip"
    });
  }
  passthrough() {
    return new V({
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
    return new V({
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
    return new V({
      unknownKeys: e._def.unknownKeys,
      catchall: e._def.catchall,
      shape: () => ({
        ...this._def.shape(),
        ...e._def.shape()
      }),
      typeName: C.ZodObject
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
    return new V({
      ...this._def,
      catchall: e
    });
  }
  pick(e) {
    const r = {};
    return F.objectKeys(e).forEach((n) => {
      e[n] && this.shape[n] && (r[n] = this.shape[n]);
    }), new V({
      ...this._def,
      shape: () => r
    });
  }
  omit(e) {
    const r = {};
    return F.objectKeys(this.shape).forEach((n) => {
      e[n] || (r[n] = this.shape[n]);
    }), new V({
      ...this._def,
      shape: () => r
    });
  }
  /**
   * @deprecated
   */
  deepPartial() {
    return kt(this);
  }
  partial(e) {
    const r = {};
    return F.objectKeys(this.shape).forEach((n) => {
      const o = this.shape[n];
      e && !e[n] ? r[n] = o : r[n] = o.optional();
    }), new V({
      ...this._def,
      shape: () => r
    });
  }
  required(e) {
    const r = {};
    return F.objectKeys(this.shape).forEach((n) => {
      if (e && !e[n])
        r[n] = this.shape[n];
      else {
        let s = this.shape[n];
        for (; s instanceof Ue; )
          s = s._def.innerType;
        r[n] = s;
      }
    }), new V({
      ...this._def,
      shape: () => r
    });
  }
  keyof() {
    return qs(F.objectKeys(this.shape));
  }
}
V.create = (t, e) => new V({
  shape: () => t,
  unknownKeys: "strip",
  catchall: Ye.create(),
  typeName: C.ZodObject,
  ...N(e)
});
V.strictCreate = (t, e) => new V({
  shape: () => t,
  unknownKeys: "strict",
  catchall: Ye.create(),
  typeName: C.ZodObject,
  ...N(e)
});
V.lazycreate = (t, e) => new V({
  shape: t,
  unknownKeys: "strip",
  catchall: Ye.create(),
  typeName: C.ZodObject,
  ...N(e)
});
class Xt extends O {
  _parse(e) {
    const { ctx: r } = this._processInputParams(e), n = this._def.options;
    function o(s) {
      for (const c of s)
        if (c.result.status === "valid")
          return c.result;
      for (const c of s)
        if (c.result.status === "dirty")
          return r.common.issues.push(...c.ctx.common.issues), c.result;
      const i = s.map((c) => new ye(c.ctx.common.issues));
      return v(r, {
        code: g.invalid_union,
        unionErrors: i
      }), $;
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
      })).then(o);
    {
      let s;
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
        d.status === "dirty" && !s && (s = { result: d, ctx: u }), u.common.issues.length && i.push(u.common.issues);
      }
      if (s)
        return r.common.issues.push(...s.ctx.common.issues), s.result;
      const c = i.map((l) => new ye(l));
      return v(r, {
        code: g.invalid_union,
        unionErrors: c
      }), $;
    }
  }
  get options() {
    return this._def.options;
  }
}
Xt.create = (t, e) => new Xt({
  options: t,
  typeName: C.ZodUnion,
  ...N(e)
});
const Ve = (t) => t instanceof tr ? Ve(t.schema) : t instanceof Oe ? Ve(t.innerType()) : t instanceof rr ? [t.value] : t instanceof nt ? t.options : t instanceof nr ? F.objectValues(t.enum) : t instanceof or ? Ve(t._def.innerType) : t instanceof Yt ? [void 0] : t instanceof Jt ? [null] : t instanceof Ue ? [void 0, ...Ve(t.unwrap())] : t instanceof ot ? [null, ...Ve(t.unwrap())] : t instanceof Hn || t instanceof ar ? Ve(t.unwrap()) : t instanceof sr ? Ve(t._def.innerType) : [];
class Qr extends O {
  _parse(e) {
    const { ctx: r } = this._processInputParams(e);
    if (r.parsedType !== b.object)
      return v(r, {
        code: g.invalid_type,
        expected: b.object,
        received: r.parsedType
      }), $;
    const n = this.discriminator, o = r.data[n], s = this.optionsMap.get(o);
    return s ? r.common.async ? s._parseAsync({
      data: r.data,
      path: r.path,
      parent: r
    }) : s._parseSync({
      data: r.data,
      path: r.path,
      parent: r
    }) : (v(r, {
      code: g.invalid_union_discriminator,
      options: Array.from(this.optionsMap.keys()),
      path: [n]
    }), $);
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
    for (const s of r) {
      const i = Ve(s.shape[e]);
      if (!i.length)
        throw new Error(`A discriminator value for key \`${e}\` could not be extracted from all schema options`);
      for (const c of i) {
        if (o.has(c))
          throw new Error(`Discriminator property ${String(e)} has duplicate value ${String(c)}`);
        o.set(c, s);
      }
    }
    return new Qr({
      typeName: C.ZodDiscriminatedUnion,
      discriminator: e,
      options: r,
      optionsMap: o,
      ...N(n)
    });
  }
}
function Pn(t, e) {
  const r = He(t), n = He(e);
  if (t === e)
    return { valid: !0, data: t };
  if (r === b.object && n === b.object) {
    const o = F.objectKeys(e), s = F.objectKeys(t).filter((c) => o.indexOf(c) !== -1), i = { ...t, ...e };
    for (const c of s) {
      const l = Pn(t[c], e[c]);
      if (!l.valid)
        return { valid: !1 };
      i[c] = l.data;
    }
    return { valid: !0, data: i };
  } else if (r === b.array && n === b.array) {
    if (t.length !== e.length)
      return { valid: !1 };
    const o = [];
    for (let s = 0; s < t.length; s++) {
      const i = t[s], c = e[s], l = Pn(i, c);
      if (!l.valid)
        return { valid: !1 };
      o.push(l.data);
    }
    return { valid: !0, data: o };
  } else return r === b.date && n === b.date && +t == +e ? { valid: !0, data: t } : { valid: !1 };
}
class Qt extends O {
  _parse(e) {
    const { status: r, ctx: n } = this._processInputParams(e), o = (s, i) => {
      if (En(s) || En(i))
        return $;
      const c = Pn(s.value, i.value);
      return c.valid ? ((kn(s) || kn(i)) && r.dirty(), { status: r.value, value: c.data }) : (v(n, {
        code: g.invalid_intersection_types
      }), $);
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
    ]).then(([s, i]) => o(s, i)) : o(this._def.left._parseSync({
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
Qt.create = (t, e, r) => new Qt({
  left: t,
  right: e,
  typeName: C.ZodIntersection,
  ...N(r)
});
class Ze extends O {
  _parse(e) {
    const { status: r, ctx: n } = this._processInputParams(e);
    if (n.parsedType !== b.array)
      return v(n, {
        code: g.invalid_type,
        expected: b.array,
        received: n.parsedType
      }), $;
    if (n.data.length < this._def.items.length)
      return v(n, {
        code: g.too_small,
        minimum: this._def.items.length,
        inclusive: !0,
        exact: !1,
        type: "array"
      }), $;
    !this._def.rest && n.data.length > this._def.items.length && (v(n, {
      code: g.too_big,
      maximum: this._def.items.length,
      inclusive: !0,
      exact: !1,
      type: "array"
    }), r.dirty());
    const s = [...n.data].map((i, c) => {
      const l = this._def.items[c] || this._def.rest;
      return l ? l._parse(new je(n, i, n.path, c)) : null;
    }).filter((i) => !!i);
    return n.common.async ? Promise.all(s).then((i) => oe.mergeArray(r, i)) : oe.mergeArray(r, s);
  }
  get items() {
    return this._def.items;
  }
  rest(e) {
    return new Ze({
      ...this._def,
      rest: e
    });
  }
}
Ze.create = (t, e) => {
  if (!Array.isArray(t))
    throw new Error("You must pass an array of schemas to z.tuple([ ... ])");
  return new Ze({
    items: t,
    typeName: C.ZodTuple,
    rest: null,
    ...N(e)
  });
};
class er extends O {
  get keySchema() {
    return this._def.keyType;
  }
  get valueSchema() {
    return this._def.valueType;
  }
  _parse(e) {
    const { status: r, ctx: n } = this._processInputParams(e);
    if (n.parsedType !== b.object)
      return v(n, {
        code: g.invalid_type,
        expected: b.object,
        received: n.parsedType
      }), $;
    const o = [], s = this._def.keyType, i = this._def.valueType;
    for (const c in n.data)
      o.push({
        key: s._parse(new je(n, c, n.path, c)),
        value: i._parse(new je(n, n.data[c], n.path, c)),
        alwaysSet: c in n.data
      });
    return n.common.async ? oe.mergeObjectAsync(r, o) : oe.mergeObjectSync(r, o);
  }
  get element() {
    return this._def.valueType;
  }
  static create(e, r, n) {
    return r instanceof O ? new er({
      keyType: e,
      valueType: r,
      typeName: C.ZodRecord,
      ...N(n)
    }) : new er({
      keyType: Re.create(),
      valueType: e,
      typeName: C.ZodRecord,
      ...N(r)
    });
  }
}
class Fr extends O {
  get keySchema() {
    return this._def.keyType;
  }
  get valueSchema() {
    return this._def.valueType;
  }
  _parse(e) {
    const { status: r, ctx: n } = this._processInputParams(e);
    if (n.parsedType !== b.map)
      return v(n, {
        code: g.invalid_type,
        expected: b.map,
        received: n.parsedType
      }), $;
    const o = this._def.keyType, s = this._def.valueType, i = [...n.data.entries()].map(([c, l], u) => ({
      key: o._parse(new je(n, c, n.path, [u, "key"])),
      value: s._parse(new je(n, l, n.path, [u, "value"]))
    }));
    if (n.common.async) {
      const c = /* @__PURE__ */ new Map();
      return Promise.resolve().then(async () => {
        for (const l of i) {
          const u = await l.key, d = await l.value;
          if (u.status === "aborted" || d.status === "aborted")
            return $;
          (u.status === "dirty" || d.status === "dirty") && r.dirty(), c.set(u.value, d.value);
        }
        return { status: r.value, value: c };
      });
    } else {
      const c = /* @__PURE__ */ new Map();
      for (const l of i) {
        const u = l.key, d = l.value;
        if (u.status === "aborted" || d.status === "aborted")
          return $;
        (u.status === "dirty" || d.status === "dirty") && r.dirty(), c.set(u.value, d.value);
      }
      return { status: r.value, value: c };
    }
  }
}
Fr.create = (t, e, r) => new Fr({
  valueType: e,
  keyType: t,
  typeName: C.ZodMap,
  ...N(r)
});
class vt extends O {
  _parse(e) {
    const { status: r, ctx: n } = this._processInputParams(e);
    if (n.parsedType !== b.set)
      return v(n, {
        code: g.invalid_type,
        expected: b.set,
        received: n.parsedType
      }), $;
    const o = this._def;
    o.minSize !== null && n.data.size < o.minSize.value && (v(n, {
      code: g.too_small,
      minimum: o.minSize.value,
      type: "set",
      inclusive: !0,
      exact: !1,
      message: o.minSize.message
    }), r.dirty()), o.maxSize !== null && n.data.size > o.maxSize.value && (v(n, {
      code: g.too_big,
      maximum: o.maxSize.value,
      type: "set",
      inclusive: !0,
      exact: !1,
      message: o.maxSize.message
    }), r.dirty());
    const s = this._def.valueType;
    function i(l) {
      const u = /* @__PURE__ */ new Set();
      for (const d of l) {
        if (d.status === "aborted")
          return $;
        d.status === "dirty" && r.dirty(), u.add(d.value);
      }
      return { status: r.value, value: u };
    }
    const c = [...n.data.values()].map((l, u) => s._parse(new je(n, l, n.path, u)));
    return n.common.async ? Promise.all(c).then((l) => i(l)) : i(c);
  }
  min(e, r) {
    return new vt({
      ...this._def,
      minSize: { value: e, message: E.toString(r) }
    });
  }
  max(e, r) {
    return new vt({
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
vt.create = (t, e) => new vt({
  valueType: t,
  minSize: null,
  maxSize: null,
  typeName: C.ZodSet,
  ...N(e)
});
class Ct extends O {
  constructor() {
    super(...arguments), this.validate = this.implement;
  }
  _parse(e) {
    const { ctx: r } = this._processInputParams(e);
    if (r.parsedType !== b.function)
      return v(r, {
        code: g.invalid_type,
        expected: b.function,
        received: r.parsedType
      }), $;
    function n(c, l) {
      return Nr({
        data: c,
        path: r.path,
        errorMaps: [
          r.common.contextualErrorMap,
          r.schemaErrorMap,
          $r(),
          Mt
        ].filter((u) => !!u),
        issueData: {
          code: g.invalid_arguments,
          argumentsError: l
        }
      });
    }
    function o(c, l) {
      return Nr({
        data: c,
        path: r.path,
        errorMaps: [
          r.common.contextualErrorMap,
          r.schemaErrorMap,
          $r(),
          Mt
        ].filter((u) => !!u),
        issueData: {
          code: g.invalid_return_type,
          returnTypeError: l
        }
      });
    }
    const s = { errorMap: r.common.contextualErrorMap }, i = r.data;
    if (this._def.returns instanceof Ft) {
      const c = this;
      return fe(async function(...l) {
        const u = new ye([]), d = await c._def.args.parseAsync(l, s).catch((p) => {
          throw u.addIssue(n(l, p)), u;
        }), f = await Reflect.apply(i, this, d);
        return await c._def.returns._def.type.parseAsync(f, s).catch((p) => {
          throw u.addIssue(o(f, p)), u;
        });
      });
    } else {
      const c = this;
      return fe(function(...l) {
        const u = c._def.args.safeParse(l, s);
        if (!u.success)
          throw new ye([n(l, u.error)]);
        const d = Reflect.apply(i, this, u.data), f = c._def.returns.safeParse(d, s);
        if (!f.success)
          throw new ye([o(d, f.error)]);
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
    return new Ct({
      ...this._def,
      args: Ze.create(e).rest(mt.create())
    });
  }
  returns(e) {
    return new Ct({
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
    return new Ct({
      args: e || Ze.create([]).rest(mt.create()),
      returns: r || mt.create(),
      typeName: C.ZodFunction,
      ...N(n)
    });
  }
}
class tr extends O {
  get schema() {
    return this._def.getter();
  }
  _parse(e) {
    const { ctx: r } = this._processInputParams(e);
    return this._def.getter()._parse({ data: r.data, path: r.path, parent: r });
  }
}
tr.create = (t, e) => new tr({
  getter: t,
  typeName: C.ZodLazy,
  ...N(e)
});
class rr extends O {
  _parse(e) {
    if (e.data !== this._def.value) {
      const r = this._getOrReturnCtx(e);
      return v(r, {
        received: r.data,
        code: g.invalid_literal,
        expected: this._def.value
      }), $;
    }
    return { status: "valid", value: e.data };
  }
  get value() {
    return this._def.value;
  }
}
rr.create = (t, e) => new rr({
  value: t,
  typeName: C.ZodLiteral,
  ...N(e)
});
function qs(t, e) {
  return new nt({
    values: t,
    typeName: C.ZodEnum,
    ...N(e)
  });
}
class nt extends O {
  constructor() {
    super(...arguments), Vt.set(this, void 0);
  }
  _parse(e) {
    if (typeof e.data != "string") {
      const r = this._getOrReturnCtx(e), n = this._def.values;
      return v(r, {
        expected: F.joinValues(n),
        received: r.parsedType,
        code: g.invalid_type
      }), $;
    }
    if (Or(this, Vt) || Gs(this, Vt, new Set(this._def.values)), !Or(this, Vt).has(e.data)) {
      const r = this._getOrReturnCtx(e), n = this._def.values;
      return v(r, {
        received: r.data,
        code: g.invalid_enum_value,
        options: n
      }), $;
    }
    return fe(e.data);
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
    return nt.create(e, {
      ...this._def,
      ...r
    });
  }
  exclude(e, r = this._def) {
    return nt.create(this.options.filter((n) => !e.includes(n)), {
      ...this._def,
      ...r
    });
  }
}
Vt = /* @__PURE__ */ new WeakMap();
nt.create = qs;
class nr extends O {
  constructor() {
    super(...arguments), Ht.set(this, void 0);
  }
  _parse(e) {
    const r = F.getValidEnumValues(this._def.values), n = this._getOrReturnCtx(e);
    if (n.parsedType !== b.string && n.parsedType !== b.number) {
      const o = F.objectValues(r);
      return v(n, {
        expected: F.joinValues(o),
        received: n.parsedType,
        code: g.invalid_type
      }), $;
    }
    if (Or(this, Ht) || Gs(this, Ht, new Set(F.getValidEnumValues(this._def.values))), !Or(this, Ht).has(e.data)) {
      const o = F.objectValues(r);
      return v(n, {
        received: n.data,
        code: g.invalid_enum_value,
        options: o
      }), $;
    }
    return fe(e.data);
  }
  get enum() {
    return this._def.values;
  }
}
Ht = /* @__PURE__ */ new WeakMap();
nr.create = (t, e) => new nr({
  values: t,
  typeName: C.ZodNativeEnum,
  ...N(e)
});
class Ft extends O {
  unwrap() {
    return this._def.type;
  }
  _parse(e) {
    const { ctx: r } = this._processInputParams(e);
    if (r.parsedType !== b.promise && r.common.async === !1)
      return v(r, {
        code: g.invalid_type,
        expected: b.promise,
        received: r.parsedType
      }), $;
    const n = r.parsedType === b.promise ? r.data : Promise.resolve(r.data);
    return fe(n.then((o) => this._def.type.parseAsync(o, {
      path: r.path,
      errorMap: r.common.contextualErrorMap
    })));
  }
}
Ft.create = (t, e) => new Ft({
  type: t,
  typeName: C.ZodPromise,
  ...N(e)
});
class Oe extends O {
  innerType() {
    return this._def.schema;
  }
  sourceType() {
    return this._def.schema._def.typeName === C.ZodEffects ? this._def.schema.sourceType() : this._def.schema;
  }
  _parse(e) {
    const { status: r, ctx: n } = this._processInputParams(e), o = this._def.effect || null, s = {
      addIssue: (i) => {
        v(n, i), i.fatal ? r.abort() : r.dirty();
      },
      get path() {
        return n.path;
      }
    };
    if (s.addIssue = s.addIssue.bind(s), o.type === "preprocess") {
      const i = o.transform(n.data, s);
      if (n.common.async)
        return Promise.resolve(i).then(async (c) => {
          if (r.value === "aborted")
            return $;
          const l = await this._def.schema._parseAsync({
            data: c,
            path: n.path,
            parent: n
          });
          return l.status === "aborted" ? $ : l.status === "dirty" || r.value === "dirty" ? Pt(l.value) : l;
        });
      {
        if (r.value === "aborted")
          return $;
        const c = this._def.schema._parseSync({
          data: i,
          path: n.path,
          parent: n
        });
        return c.status === "aborted" ? $ : c.status === "dirty" || r.value === "dirty" ? Pt(c.value) : c;
      }
    }
    if (o.type === "refinement") {
      const i = (c) => {
        const l = o.refinement(c, s);
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
        return c.status === "aborted" ? $ : (c.status === "dirty" && r.dirty(), i(c.value), { status: r.value, value: c.value });
      } else
        return this._def.schema._parseAsync({ data: n.data, path: n.path, parent: n }).then((c) => c.status === "aborted" ? $ : (c.status === "dirty" && r.dirty(), i(c.value).then(() => ({ status: r.value, value: c.value }))));
    }
    if (o.type === "transform")
      if (n.common.async === !1) {
        const i = this._def.schema._parseSync({
          data: n.data,
          path: n.path,
          parent: n
        });
        if (!yt(i))
          return i;
        const c = o.transform(i.value, s);
        if (c instanceof Promise)
          throw new Error("Asynchronous transform encountered during synchronous parse operation. Use .parseAsync instead.");
        return { status: r.value, value: c };
      } else
        return this._def.schema._parseAsync({ data: n.data, path: n.path, parent: n }).then((i) => yt(i) ? Promise.resolve(o.transform(i.value, s)).then((c) => ({ status: r.value, value: c })) : i);
    F.assertNever(o);
  }
}
Oe.create = (t, e, r) => new Oe({
  schema: t,
  typeName: C.ZodEffects,
  effect: e,
  ...N(r)
});
Oe.createWithPreprocess = (t, e, r) => new Oe({
  schema: e,
  effect: { type: "preprocess", transform: t },
  typeName: C.ZodEffects,
  ...N(r)
});
class Ue extends O {
  _parse(e) {
    return this._getType(e) === b.undefined ? fe(void 0) : this._def.innerType._parse(e);
  }
  unwrap() {
    return this._def.innerType;
  }
}
Ue.create = (t, e) => new Ue({
  innerType: t,
  typeName: C.ZodOptional,
  ...N(e)
});
class ot extends O {
  _parse(e) {
    return this._getType(e) === b.null ? fe(null) : this._def.innerType._parse(e);
  }
  unwrap() {
    return this._def.innerType;
  }
}
ot.create = (t, e) => new ot({
  innerType: t,
  typeName: C.ZodNullable,
  ...N(e)
});
class or extends O {
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
or.create = (t, e) => new or({
  innerType: t,
  typeName: C.ZodDefault,
  defaultValue: typeof e.default == "function" ? e.default : () => e.default,
  ...N(e)
});
class sr extends O {
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
    return qt(o) ? o.then((s) => ({
      status: "valid",
      value: s.status === "valid" ? s.value : this._def.catchValue({
        get error() {
          return new ye(n.common.issues);
        },
        input: n.data
      })
    })) : {
      status: "valid",
      value: o.status === "valid" ? o.value : this._def.catchValue({
        get error() {
          return new ye(n.common.issues);
        },
        input: n.data
      })
    };
  }
  removeCatch() {
    return this._def.innerType;
  }
}
sr.create = (t, e) => new sr({
  innerType: t,
  typeName: C.ZodCatch,
  catchValue: typeof e.catch == "function" ? e.catch : () => e.catch,
  ...N(e)
});
class Dr extends O {
  _parse(e) {
    if (this._getType(e) !== b.nan) {
      const n = this._getOrReturnCtx(e);
      return v(n, {
        code: g.invalid_type,
        expected: b.nan,
        received: n.parsedType
      }), $;
    }
    return { status: "valid", value: e.data };
  }
}
Dr.create = (t) => new Dr({
  typeName: C.ZodNaN,
  ...N(t)
});
const pl = Symbol("zod_brand");
class Hn extends O {
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
class ur extends O {
  _parse(e) {
    const { status: r, ctx: n } = this._processInputParams(e);
    if (n.common.async)
      return (async () => {
        const s = await this._def.in._parseAsync({
          data: n.data,
          path: n.path,
          parent: n
        });
        return s.status === "aborted" ? $ : s.status === "dirty" ? (r.dirty(), Pt(s.value)) : this._def.out._parseAsync({
          data: s.value,
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
      return o.status === "aborted" ? $ : o.status === "dirty" ? (r.dirty(), {
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
    return new ur({
      in: e,
      out: r,
      typeName: C.ZodPipeline
    });
  }
}
class ar extends O {
  _parse(e) {
    const r = this._def.innerType._parse(e), n = (o) => (yt(o) && (o.value = Object.freeze(o.value)), o);
    return qt(r) ? r.then((o) => n(o)) : n(r);
  }
  unwrap() {
    return this._def.innerType;
  }
}
ar.create = (t, e) => new ar({
  innerType: t,
  typeName: C.ZodReadonly,
  ...N(e)
});
function Ks(t, e = {}, r) {
  return t ? Lt.create().superRefine((n, o) => {
    var s, i;
    if (!t(n)) {
      const c = typeof e == "function" ? e(n) : typeof e == "string" ? { message: e } : e, l = (i = (s = c.fatal) !== null && s !== void 0 ? s : r) !== null && i !== void 0 ? i : !0, u = typeof c == "string" ? { message: c } : c;
      o.addIssue({ code: "custom", ...u, fatal: l });
    }
  }) : Lt.create();
}
const hl = {
  object: V.lazycreate
};
var C;
(function(t) {
  t.ZodString = "ZodString", t.ZodNumber = "ZodNumber", t.ZodNaN = "ZodNaN", t.ZodBigInt = "ZodBigInt", t.ZodBoolean = "ZodBoolean", t.ZodDate = "ZodDate", t.ZodSymbol = "ZodSymbol", t.ZodUndefined = "ZodUndefined", t.ZodNull = "ZodNull", t.ZodAny = "ZodAny", t.ZodUnknown = "ZodUnknown", t.ZodNever = "ZodNever", t.ZodVoid = "ZodVoid", t.ZodArray = "ZodArray", t.ZodObject = "ZodObject", t.ZodUnion = "ZodUnion", t.ZodDiscriminatedUnion = "ZodDiscriminatedUnion", t.ZodIntersection = "ZodIntersection", t.ZodTuple = "ZodTuple", t.ZodRecord = "ZodRecord", t.ZodMap = "ZodMap", t.ZodSet = "ZodSet", t.ZodFunction = "ZodFunction", t.ZodLazy = "ZodLazy", t.ZodLiteral = "ZodLiteral", t.ZodEnum = "ZodEnum", t.ZodEffects = "ZodEffects", t.ZodNativeEnum = "ZodNativeEnum", t.ZodOptional = "ZodOptional", t.ZodNullable = "ZodNullable", t.ZodDefault = "ZodDefault", t.ZodCatch = "ZodCatch", t.ZodPromise = "ZodPromise", t.ZodBranded = "ZodBranded", t.ZodPipeline = "ZodPipeline", t.ZodReadonly = "ZodReadonly";
})(C || (C = {}));
const ml = (t, e = {
  message: `Input not instance of ${t.name}`
}) => Ks((r) => r instanceof t, e), Ys = Re.create, Js = tt.create, gl = Dr.create, yl = rt.create, Xs = Kt.create, _l = _t.create, vl = Mr.create, bl = Yt.create, wl = Jt.create, Sl = Lt.create, xl = mt.create, El = Ye.create, kl = Lr.create, Pl = Ne.create, Al = V.create, Tl = V.strictCreate, Il = Xt.create, Cl = Qr.create, Rl = Qt.create, $l = Ze.create, Nl = er.create, Ol = Fr.create, Ml = vt.create, Ll = Ct.create, Fl = tr.create, Dl = rr.create, Ul = nt.create, jl = nr.create, Zl = Ft.create, xo = Oe.create, zl = Ue.create, Bl = ot.create, Gl = Oe.createWithPreprocess, Vl = ur.create, Hl = () => Ys().optional(), Wl = () => Js().optional(), ql = () => Xs().optional(), Kl = {
  string: (t) => Re.create({ ...t, coerce: !0 }),
  number: (t) => tt.create({ ...t, coerce: !0 }),
  boolean: (t) => Kt.create({
    ...t,
    coerce: !0
  }),
  bigint: (t) => rt.create({ ...t, coerce: !0 }),
  date: (t) => _t.create({ ...t, coerce: !0 })
}, Yl = $;
var K = /* @__PURE__ */ Object.freeze({
  __proto__: null,
  defaultErrorMap: Mt,
  setErrorMap: Gc,
  getErrorMap: $r,
  makeIssue: Nr,
  EMPTY_PATH: Vc,
  addIssueToContext: v,
  ParseStatus: oe,
  INVALID: $,
  DIRTY: Pt,
  OK: fe,
  isAborted: En,
  isDirty: kn,
  isValid: yt,
  isAsync: qt,
  get util() {
    return F;
  },
  get objectUtil() {
    return xn;
  },
  ZodParsedType: b,
  getParsedType: He,
  ZodType: O,
  datetimeRegex: Ws,
  ZodString: Re,
  ZodNumber: tt,
  ZodBigInt: rt,
  ZodBoolean: Kt,
  ZodDate: _t,
  ZodSymbol: Mr,
  ZodUndefined: Yt,
  ZodNull: Jt,
  ZodAny: Lt,
  ZodUnknown: mt,
  ZodNever: Ye,
  ZodVoid: Lr,
  ZodArray: Ne,
  ZodObject: V,
  ZodUnion: Xt,
  ZodDiscriminatedUnion: Qr,
  ZodIntersection: Qt,
  ZodTuple: Ze,
  ZodRecord: er,
  ZodMap: Fr,
  ZodSet: vt,
  ZodFunction: Ct,
  ZodLazy: tr,
  ZodLiteral: rr,
  ZodEnum: nt,
  ZodNativeEnum: nr,
  ZodPromise: Ft,
  ZodEffects: Oe,
  ZodTransformer: Oe,
  ZodOptional: Ue,
  ZodNullable: ot,
  ZodDefault: or,
  ZodCatch: sr,
  ZodNaN: Dr,
  BRAND: pl,
  ZodBranded: Hn,
  ZodPipeline: ur,
  ZodReadonly: ar,
  custom: Ks,
  Schema: O,
  ZodSchema: O,
  late: hl,
  get ZodFirstPartyTypeKind() {
    return C;
  },
  coerce: Kl,
  any: Sl,
  array: Pl,
  bigint: yl,
  boolean: Xs,
  date: _l,
  discriminatedUnion: Cl,
  effect: xo,
  enum: Ul,
  function: Ll,
  instanceof: ml,
  intersection: Rl,
  lazy: Fl,
  literal: Dl,
  map: Ol,
  nan: gl,
  nativeEnum: jl,
  never: El,
  null: wl,
  nullable: Bl,
  number: Js,
  object: Al,
  oboolean: ql,
  onumber: Wl,
  optional: zl,
  ostring: Hl,
  pipeline: Vl,
  preprocess: Gl,
  promise: Zl,
  record: Nl,
  set: Ml,
  strictObject: Tl,
  string: Ys,
  symbol: vl,
  transformer: xo,
  tuple: $l,
  undefined: bl,
  union: Il,
  unknown: xl,
  void: kl,
  NEVER: Yl,
  ZodIssueCode: g,
  quotelessJson: Bc,
  ZodError: ye
});
const Jl = K.object({
  pluginId: K.string(),
  name: K.string(),
  host: K.string().url(),
  code: K.string(),
  icon: K.string().optional(),
  description: K.string().max(200).optional(),
  permissions: K.array(
    K.enum([
      "content:read",
      "content:write",
      "library:read",
      "library:write",
      "user:read",
      "comment:read",
      "comment:write",
      "allow:downloads"
    ])
  )
});
function Qs(t, e) {
  return new URL(e, t).toString();
}
function Xl(t) {
  return fetch(t).then((e) => e.json()).then((e) => {
    if (!Jl.safeParse(e).success)
      throw new Error("Invalid plugin manifest");
    return e;
  }).catch((e) => {
    throw console.error(e), e;
  });
}
function Eo(t) {
  return !t.host && !t.code.startsWith("http") ? Promise.resolve(t.code) : fetch(Qs(t.host, t.code)).then((e) => {
    if (e.ok)
      return e.text();
    throw new Error("Failed to load plugin code");
  });
}
const ea = K.object({
  width: K.number().positive(),
  height: K.number().positive()
}), Ql = K.function().args(
  K.string(),
  K.string(),
  K.enum(["dark", "light"]),
  ea.optional(),
  K.boolean().optional()
).implement((t, e, r, n, o) => jc(t, e, r, n, o));
async function eu(t, e, r, n) {
  let o = await Eo(e), s = !1, i = !1, c = null, l = [];
  const u = /* @__PURE__ */ new Set(), d = !!e.permissions.find(
    (I) => I === "allow:downloads"
  ), f = t.addListener("themechange", (I) => {
    c == null || c.setTheme(I);
  }), h = t.addListener("finish", () => {
    A(), t == null || t.removeListener(h);
  });
  let p = [];
  const m = () => {
    j(f), p.forEach((I) => {
      j(I);
    }), l = [], p = [];
  }, A = () => {
    m(), u.forEach(clearTimeout), u.clear(), c && (c.removeEventListener("close", A), c.remove(), c = null), i = !0, r();
  }, S = async () => {
    if (!s) {
      s = !0;
      return;
    }
    m(), o = await Eo(e), n(o);
  }, w = (I, L, Z) => {
    const se = t.theme, J = Qs(e.host, L);
    (c == null ? void 0 : c.getAttribute("iframe-src")) !== J && (c = Ql(I, J, se, Z, d), c.setTheme(se), c.addEventListener("close", A, {
      once: !0
    }), c.addEventListener("load", S));
  }, R = (I) => {
    l.push(I);
  }, T = (I, L, Z) => {
    const se = t.addListener(
      I,
      (...J) => {
        i || L(...J);
      },
      Z
    );
    return p.push(se), se;
  }, j = (I) => {
    t.removeListener(I);
  };
  return {
    close: A,
    destroyListener: j,
    openModal: w,
    resizeModal: (I, L) => {
      ea.parse({ width: I, height: L }), c && c.resize(I, L);
    },
    getModal: () => c,
    registerListener: T,
    registerMessageCallback: R,
    sendMessage: (I) => {
      l.forEach((L) => L(I));
    },
    get manifest() {
      return e;
    },
    get context() {
      return t;
    },
    get timeouts() {
      return u;
    },
    get code() {
      return o;
    }
  };
}
const tu = [
  "finish",
  "pagechange",
  "filechange",
  "selectionchange",
  "themechange",
  "shapechange",
  "contentsave"
];
function ru(t) {
  const e = (n) => {
    if (!t.manifest.permissions.includes(n))
      throw new Error(`Permission ${n} is not granted`);
  };
  return {
    penpot: {
      ui: {
        open: (n, o, s) => {
          t.openModal(n, o, s);
        },
        get size() {
          var n;
          return ((n = t.getModal()) == null ? void 0 : n.size()) || null;
        },
        resize: (n, o) => t.resizeModal(n, o),
        sendMessage(n) {
          var s;
          const o = new CustomEvent("message", {
            detail: n
          });
          (s = t.getModal()) == null || s.dispatchEvent(o);
        },
        onMessage: (n) => {
          K.function().parse(n), t.registerMessageCallback(n);
        }
      },
      utils: {
        geometry: {
          center(n) {
            return window.app.plugins.public_utils.centerShapes(n);
          }
        },
        types: {
          isBoard(n) {
            return n.type === "board";
          },
          isGroup(n) {
            return n.type === "group";
          },
          isMask(n) {
            return n.type === "group" && n.isMask();
          },
          isBool(n) {
            return n.type === "boolean";
          },
          isRectangle(n) {
            return n.type === "rectangle";
          },
          isPath(n) {
            return n.type === "path";
          },
          isText(n) {
            return n.type === "text";
          },
          isEllipse(n) {
            return n.type === "ellipse";
          },
          isSVG(n) {
            return n.type === "svg-raw";
          }
        }
      },
      closePlugin: () => {
        t.close();
      },
      on(n, o, s) {
        return K.enum(tu).parse(n), K.function().parse(o), e("content:read"), t.registerListener(n, o, s);
      },
      off(n) {
        t.destroyListener(n);
      },
      // Penpot State API
      get root() {
        return e("content:read"), t.context.root;
      },
      get currentFile() {
        return e("content:read"), t.context.currentFile;
      },
      get currentPage() {
        return e("content:read"), t.context.currentPage;
      },
      get selection() {
        return e("content:read"), t.context.selection;
      },
      set selection(n) {
        e("content:read"), t.context.selection = n;
      },
      get viewport() {
        return t.context.viewport;
      },
      get history() {
        return t.context.history;
      },
      get library() {
        return e("library:read"), t.context.library;
      },
      get fonts() {
        return e("content:read"), t.context.fonts;
      },
      get currentUser() {
        return e("user:read"), t.context.currentUser;
      },
      get activeUsers() {
        return e("user:read"), t.context.activeUsers;
      },
      shapesColors(n) {
        return e("content:read"), t.context.shapesColors(n);
      },
      replaceColor(n, o, s) {
        return e("content:write"), t.context.replaceColor(n, o, s);
      },
      get theme() {
        return t.context.theme;
      },
      createBoard() {
        return e("content:write"), t.context.createBoard();
      },
      createRectangle() {
        return e("content:write"), t.context.createRectangle();
      },
      createEllipse() {
        return e("content:write"), t.context.createEllipse();
      },
      createText(n) {
        return e("content:write"), t.context.createText(n);
      },
      createPath() {
        return e("content:write"), t.context.createPath();
      },
      createBoolean(n, o) {
        return e("content:write"), t.context.createBoolean(n, o);
      },
      createShapeFromSvg(n) {
        return e("content:write"), t.context.createShapeFromSvg(n);
      },
      createShapeFromSvgWithImages(n) {
        return e("content:write"), t.context.createShapeFromSvgWithImages(n);
      },
      group(n) {
        return e("content:write"), t.context.group(n);
      },
      ungroup(n, ...o) {
        e("content:write"), t.context.ungroup(n, ...o);
      },
      uploadMediaUrl(n, o) {
        return e("content:write"), t.context.uploadMediaUrl(n, o);
      },
      uploadMediaData(n, o, s) {
        return e("content:write"), t.context.uploadMediaData(n, o, s);
      },
      generateMarkup(n, o) {
        return e("content:read"), t.context.generateMarkup(n, o);
      },
      generateStyle(n, o) {
        return e("content:read"), t.context.generateStyle(n, o);
      },
      openViewer() {
        e("content:read"), t.context.openViewer();
      },
      createPage() {
        return e("content:write"), t.context.createPage();
      },
      openPage(n) {
        e("content:read"), t.context.openPage(n);
      },
      alignHorizontal(n, o) {
        e("content:write"), t.context.alignHorizontal(n, o);
      },
      alignVertical(n, o) {
        e("content:write"), t.context.alignVertical(n, o);
      },
      distributeHorizontal(n) {
        e("content:write"), t.context.distributeHorizontal(n);
      },
      distributeVertical(n) {
        e("content:write"), t.context.distributeVertical(n);
      },
      flatten(n) {
        return e("content:write"), t.context.flatten(n);
      }
    }
  };
}
let ko = !1;
const k = {
  hardenIntrinsics: () => {
    ko || (ko = !0, hardenIntrinsics());
  },
  createCompartment: (t) => new Compartment(t),
  harden: (t) => harden(t),
  safeReturn(t) {
    return t == null ? t : harden(t);
  }
};
function nu(t) {
  k.hardenIntrinsics();
  const e = ru(t), r = {
    get(c, l, u) {
      const d = Reflect.get(c, l, u);
      return typeof d == "function" ? function(...f) {
        const h = d.apply(c, f);
        return k.safeReturn(h);
      } : k.safeReturn(d);
    }
  }, n = new Proxy(e.penpot, r), o = (c, l) => {
    const u = {
      ...l,
      credentials: "omit",
      headers: {
        ...l == null ? void 0 : l.headers,
        Authorization: ""
      }
    };
    return fetch(c, u).then((d) => {
      const f = {
        ok: d.ok,
        status: d.status,
        statusText: d.statusText,
        url: d.url,
        text: d.text.bind(d),
        json: d.json.bind(d)
      };
      return k.safeReturn(f);
    });
  }, s = {
    penpot: n,
    fetch: k.harden(o),
    setTimeout: k.harden(
      (...[c, l]) => {
        const u = setTimeout(() => {
          c();
        }, l);
        return t.timeouts.add(u), k.safeReturn(u);
      }
    ),
    clearTimeout: k.harden((c) => {
      clearTimeout(c), t.timeouts.delete(c);
    }),
    /**
     * GLOBAL FUNCTIONS ACCESIBLE TO PLUGINS
     **/
    isFinite: k.harden(isFinite),
    isNaN: k.harden(isNaN),
    parseFloat: k.harden(parseFloat),
    parseInt: k.harden(parseInt),
    decodeURI: k.harden(decodeURI),
    decodeURIComponent: k.harden(decodeURIComponent),
    encodeURI: k.harden(encodeURI),
    encodeURIComponent: k.harden(encodeURIComponent),
    Object: k.harden(Object),
    Boolean: k.harden(Boolean),
    Symbol: k.harden(Symbol),
    Number: k.harden(Number),
    BigInt: k.harden(BigInt),
    Math: k.harden(Math),
    Date: k.harden(Date),
    String: k.harden(String),
    RegExp: k.harden(RegExp),
    Array: k.harden(Array),
    Int8Array: k.harden(Int8Array),
    Uint8Array: k.harden(Uint8Array),
    Uint8ClampedArray: k.harden(Uint8ClampedArray),
    Int16Array: k.harden(Int16Array),
    Uint16Array: k.harden(Uint16Array),
    Int32Array: k.harden(Int32Array),
    Uint32Array: k.harden(Uint32Array),
    BigInt64Array: k.harden(BigInt64Array),
    BigUint64Array: k.harden(BigUint64Array),
    Float32Array: k.harden(Float32Array),
    Float64Array: k.harden(Float64Array),
    Map: k.harden(Map),
    Set: k.harden(Set),
    WeakMap: k.harden(WeakMap),
    WeakSet: k.harden(WeakSet),
    ArrayBuffer: k.harden(ArrayBuffer),
    DataView: k.harden(DataView),
    Atomics: k.harden(Atomics),
    JSON: k.harden(JSON),
    Promise: k.harden(Promise),
    Proxy: k.harden(Proxy),
    Intl: k.harden(Intl),
    // Window properties
    console: k.harden(window.console),
    devicePixelRatio: k.harden(window.devicePixelRatio),
    atob: k.harden(window.atob),
    btoa: k.harden(window.btoa),
    structuredClone: k.harden(window.structuredClone)
  }, i = k.createCompartment(s);
  return {
    evaluate: () => {
      i.evaluate(t.code);
    },
    cleanGlobalThis: () => {
      Object.keys(s).forEach((c) => {
        delete i.globalThis[c];
      });
    },
    compartment: i
  };
}
async function ou(t, e, r) {
  const n = async () => {
    try {
      s.evaluate();
    } catch (i) {
      console.error(i), o.close();
    }
  }, o = await eu(
    t,
    e,
    function() {
      s.cleanGlobalThis(), r();
    },
    function() {
      n();
    }
  ), s = nu(o);
  return n(), {
    plugin: o,
    manifest: e,
    compartment: s
  };
}
let gt = [], An = null;
function su(t) {
  An = t;
}
const Po = () => {
  gt.forEach((t) => {
    t.plugin.close();
  }), gt = [];
};
window.addEventListener("message", (t) => {
  try {
    for (const e of gt)
      e.plugin.sendMessage(t.data);
  } catch (e) {
    console.error(e);
  }
});
const au = async function(t, e) {
  try {
    const r = An && An(t.pluginId);
    if (!r)
      return;
    Po();
    const n = await ou(
      k.harden(r),
      t,
      () => {
        gt = gt.filter((o) => o !== n), e && e();
      }
    );
    gt.push(n);
  } catch (r) {
    Po(), console.error(r);
  }
}, ta = async function(t, e) {
  au(t, e);
}, iu = async function(t) {
  const e = await Xl(t);
  ta(e);
}, cu = function(t) {
  const e = gt.find((r) => r.manifest.pluginId === t);
  e && e.plugin.close();
};
console.log("%c[PLUGINS] Loading plugin system", "color: #008d7c");
repairIntrinsics({
  evalTaming: "unsafeEval",
  stackFiltering: "verbose",
  errorTaming: "unsafe",
  consoleTaming: "unsafe",
  errorTrapping: "none",
  unhandledRejectionTrapping: "none"
});
const Ao = globalThis;
Ao.initPluginsRuntime = (t) => {
  try {
    console.log("%c[PLUGINS] Initialize runtime", "color: #008d7c"), su(t), Ao.ɵcontext = t("TEST"), globalThis.ɵloadPlugin = ta, globalThis.ɵloadPluginByUrl = iu, globalThis.ɵunloadPlugin = cu;
  } catch (e) {
    console.error(e);
  }
};
//# sourceMappingURL=index.js.map
