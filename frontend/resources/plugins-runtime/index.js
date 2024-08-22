var Gn = (t) => {
  throw TypeError(t);
};
var Vn = (t, e, r) => e.has(t) || Gn("Cannot " + r);
var Ae = (t, e, r) => (Vn(t, e, "read from private field"), r ? r.call(t) : e.get(t)), Wr = (t, e, r) => e.has(t) ? Gn("Cannot add the same private member more than once") : e instanceof WeakSet ? e.add(t) : e.set(t, r), qr = (t, e, r, n) => (Vn(t, e, "write to private field"), n ? n.call(t, r) : e.set(t, r), r);
const P = globalThis, {
  Array: Gs,
  Date: Vs,
  FinalizationRegistry: Tt,
  Float32Array: Hs,
  JSON: Ws,
  Map: Ce,
  Math: qs,
  Number: So,
  Object: bn,
  Promise: Ks,
  Proxy: Rr,
  Reflect: Ys,
  RegExp: Je,
  Set: Rt,
  String: ge,
  Symbol: St,
  WeakMap: Ue,
  WeakSet: Ot
} = globalThis, {
  // The feral Error constructor is safe for internal use, but must not be
  // revealed to post-lockdown code in any compartment including the start
  // compartment since in V8 at least it bears stack inspection capabilities.
  Error: ne,
  RangeError: Js,
  ReferenceError: zt,
  SyntaxError: or,
  TypeError: _,
  AggregateError: Kr
} = globalThis, {
  assign: Or,
  create: V,
  defineProperties: Z,
  entries: de,
  freeze: y,
  getOwnPropertyDescriptor: X,
  getOwnPropertyDescriptors: je,
  getOwnPropertyNames: At,
  getPrototypeOf: G,
  is: Mr,
  isFrozen: Zl,
  isSealed: zl,
  isExtensible: Bl,
  keys: Eo,
  prototype: wn,
  seal: Gl,
  preventExtensions: Xs,
  setPrototypeOf: xo,
  values: ko,
  fromEntries: ht
} = bn, {
  species: Yr,
  toStringTag: Xe,
  iterator: sr,
  matchAll: Po,
  unscopables: Qs,
  keyFor: ea,
  for: ta
} = St, { isInteger: ra } = So, { stringify: To } = Ws, { defineProperty: na } = bn, F = (t, e, r) => {
  const n = na(t, e, r);
  if (n !== t)
    throw _(
      `Please report that the original defineProperty silently failed to set ${To(
        ge(e)
      )}. (SES_DEFINE_PROPERTY_FAILED_SILENTLY)`
    );
  return n;
}, {
  apply: se,
  construct: yr,
  get: oa,
  getOwnPropertyDescriptor: sa,
  has: Ao,
  isExtensible: aa,
  ownKeys: Be,
  preventExtensions: ia,
  set: Io
} = Ys, { isArray: Et, prototype: Ee } = Gs, { prototype: Mt } = Ce, { prototype: Lr } = RegExp, { prototype: ar } = Rt, { prototype: Ze } = ge, { prototype: Fr } = Ue, { prototype: Co } = Ot, { prototype: Sn } = Function, { prototype: $o } = Ks, { prototype: No } = G(
  // eslint-disable-next-line no-empty-function, func-names
  function* () {
  }
), ca = G(Uint8Array.prototype), { bind: sn } = Sn, T = sn.bind(sn.call), ae = T(wn.hasOwnProperty), Qe = T(Ee.filter), dt = T(Ee.forEach), Dr = T(Ee.includes), Lt = T(Ee.join), ie = (
  /** @type {any} */
  T(Ee.map)
), Ro = (
  /** @type {any} */
  T(Ee.flatMap)
), _r = T(Ee.pop), Q = T(Ee.push), la = T(Ee.slice), ua = T(Ee.some), Oo = T(Ee.sort), da = T(Ee[sr]), le = T(Mt.set), Ge = T(Mt.get), Ur = T(Mt.has), fa = T(Mt.delete), pa = T(Mt.entries), ma = T(Mt[sr]), En = T(ar.add);
T(ar.delete);
const Hn = T(ar.forEach), xn = T(ar.has), ha = T(ar[sr]), kn = T(Lr.test), Pn = T(Lr.exec), ga = T(Lr[Po]), Mo = T(Ze.endsWith), Lo = T(Ze.includes), ya = T(Ze.indexOf);
T(Ze.match);
const vr = T(No.next), Fo = T(No.throw), br = (
  /** @type {any} */
  T(Ze.replace)
), _a = T(Ze.search), Tn = T(Ze.slice), An = T(Ze.split), Do = T(Ze.startsWith), va = T(Ze[sr]), ba = T(Fr.delete), j = T(Fr.get), xt = T(Fr.has), ue = T(Fr.set), jr = T(Co.add), ir = T(Co.has), wa = T(Sn.toString), Sa = T(sn);
T($o.catch);
const Uo = (
  /** @type {any} */
  T($o.then)
), Ea = Tt && T(Tt.prototype.register);
Tt && T(Tt.prototype.unregister);
const In = y(V(null)), Se = (t) => bn(t) === t, Zr = (t) => t instanceof ne, jo = eval, we = Function, xa = () => {
  throw _('Cannot eval with evalTaming set to "noEval" (SES_NO_EVAL)');
}, qe = X(Error("er1"), "stack"), Jr = X(_("er2"), "stack");
let Zo, zo;
if (qe && Jr && qe.get)
  if (
    // In the v8 case as we understand it, all errors have an own stack
    // accessor property, but within the same realm, all these accessor
    // properties have the same getter and have the same setter.
    // This is therefore the case that we repair.
    typeof qe.get == "function" && qe.get === Jr.get && typeof qe.set == "function" && qe.set === Jr.set
  )
    Zo = y(qe.get), zo = y(qe.set);
  else
    throw _(
      "Unexpected Error own stack accessor functions (SES_UNEXPECTED_ERROR_OWN_STACK_ACCESSOR)"
    );
const Xr = Zo, ka = zo;
function Pa() {
  return this;
}
if (Pa())
  throw _("SES failed to initialize, sloppy mode (SES_NO_SLOPPY)");
const { freeze: ct } = Object, { apply: Ta } = Reflect, Cn = (t) => (e, ...r) => Ta(t, e, r), Aa = Cn(Array.prototype.push), Wn = Cn(Array.prototype.includes), Ia = Cn(String.prototype.split), st = JSON.stringify, ur = (t, ...e) => {
  let r = t[0];
  for (let n = 0; n < e.length; n += 1)
    r = `${r}${e[n]}${t[n + 1]}`;
  throw Error(r);
}, Bo = (t, e = !1) => {
  const r = [], n = (c, l, d = void 0) => {
    typeof c == "string" || ur`Environment option name ${st(c)} must be a string.`, typeof l == "string" || ur`Environment option default setting ${st(
      l
    )} must be a string.`;
    let u = l;
    const f = t.process || void 0, m = typeof f == "object" && f.env || void 0;
    if (typeof m == "object" && c in m) {
      e || Aa(r, c);
      const p = m[c];
      typeof p == "string" || ur`Environment option named ${st(
        c
      )}, if present, must have a corresponding string value, got ${st(
        p
      )}`, u = p;
    }
    return d === void 0 || u === l || Wn(d, u) || ur`Unrecognized ${st(c)} value ${st(
      u
    )}. Expected one of ${st([l, ...d])}`, u;
  };
  ct(n);
  const o = (c) => {
    const l = n(c, "");
    return ct(l === "" ? [] : Ia(l, ","));
  };
  ct(o);
  const a = (c, l) => Wn(o(c), l), i = () => ct([...r]);
  return ct(i), ct({
    getEnvironmentOption: n,
    getEnvironmentOptionsList: o,
    environmentOptionsListHas: a,
    getCapturedEnvironmentOptionNames: i
  });
};
ct(Bo);
const {
  getEnvironmentOption: pe,
  getEnvironmentOptionsList: Vl,
  environmentOptionsListHas: Hl
} = Bo(globalThis, !0), wr = (t) => (t = `${t}`, t.length >= 1 && Lo("aeiouAEIOU", t[0]) ? `an ${t}` : `a ${t}`);
y(wr);
const Go = (t, e = void 0) => {
  const r = new Rt(), n = (o, a) => {
    switch (typeof a) {
      case "object": {
        if (a === null)
          return null;
        if (xn(r, a))
          return "[Seen]";
        if (En(r, a), Zr(a))
          return `[${a.name}: ${a.message}]`;
        if (Xe in a)
          return `[${a[Xe]}]`;
        if (Et(a))
          return a;
        const i = Eo(a);
        if (i.length < 2)
          return a;
        let c = !0;
        for (let d = 1; d < i.length; d += 1)
          if (i[d - 1] >= i[d]) {
            c = !1;
            break;
          }
        if (c)
          return a;
        Oo(i);
        const l = ie(i, (d) => [d, a[d]]);
        return ht(l);
      }
      case "function":
        return `[Function ${a.name || "<anon>"}]`;
      case "string":
        return Do(a, "[") ? `[${a}]` : a;
      case "undefined":
      case "symbol":
        return `[${ge(a)}]`;
      case "bigint":
        return `[${a}n]`;
      case "number":
        return Mr(a, NaN) ? "[NaN]" : a === 1 / 0 ? "[Infinity]" : a === -1 / 0 ? "[-Infinity]" : a;
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
y(Go);
const { isSafeInteger: Ca } = Number, { freeze: _t } = Object, { toStringTag: $a } = Symbol, qn = (t) => {
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
}, Qr = (t) => {
  const { prev: e, next: r } = t;
  e.next = r, r.prev = e, t.prev = t, t.next = t;
}, Vo = (t) => {
  if (!Ca(t) || t < 0)
    throw TypeError("keysBudget must be a safe non-negative integer number");
  const e = /* @__PURE__ */ new WeakMap();
  let r = 0;
  const n = qn(void 0), o = (u) => {
    const f = e.get(u);
    if (!(f === void 0 || f.data === void 0))
      return Qr(f), Kn(n, f), f;
  }, a = (u) => o(u) !== void 0;
  _t(a);
  const i = (u) => {
    const f = o(u);
    return f && f.data && f.data.get(u);
  };
  _t(i);
  const c = (u, f) => {
    if (t < 1)
      return d;
    let m = o(u);
    if (m === void 0 && (m = qn(void 0), Kn(n, m)), !m.data)
      for (r += 1, m.data = /* @__PURE__ */ new WeakMap(), e.set(u, m); r > t; ) {
        const p = n.prev;
        Qr(p), p.data = void 0, r -= 1;
      }
    return m.data.set(u, f), d;
  };
  _t(c);
  const l = (u) => {
    const f = e.get(u);
    return f === void 0 || (Qr(f), e.delete(u), f.data === void 0) ? !1 : (f.data = void 0, r -= 1, !0);
  };
  _t(l);
  const d = _t({
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
  return d;
};
_t(Vo);
const { freeze: hr } = Object, { isSafeInteger: Na } = Number, Ra = 1e3, Oa = 100, Ho = (t = Ra, e = Oa) => {
  if (!Na(e) || e < 1)
    throw TypeError(
      "argsPerErrorBudget must be a safe positive integer number"
    );
  const r = Vo(t), n = (a, i) => {
    const c = r.get(a);
    c !== void 0 ? (c.length >= e && c.shift(), c.push(i)) : r.set(a, [i]);
  };
  hr(n);
  const o = (a) => {
    const i = r.get(a);
    return r.delete(a), i;
  };
  return hr(o), hr({
    addLogArgs: n,
    takeLogArgsArray: o
  });
};
hr(Ho);
const It = new Ue(), U = (t, e = void 0) => {
  const r = y({
    toString: y(() => Go(t, e))
  });
  return ue(It, r, t), r;
};
y(U);
const Ma = y(/^[\w:-]( ?[\w:-])*$/), Sr = (t, e = void 0) => {
  if (typeof t != "string" || !kn(Ma, t))
    return U(t, e);
  const r = y({
    toString: y(() => t)
  });
  return ue(It, r, t), r;
};
y(Sr);
const zr = new Ue(), Wo = ({ template: t, args: e }) => {
  const r = [t[0]];
  for (let n = 0; n < e.length; n += 1) {
    const o = e[n];
    let a;
    xt(It, o) ? a = `${o}` : Zr(o) ? a = `(${wr(o.name)})` : a = `(${wr(typeof o)})`, Q(r, a, t[n + 1]);
  }
  return Lt(r, "");
}, qo = y({
  toString() {
    const t = j(zr, this);
    return t === void 0 ? "[Not a DetailsToken]" : Wo(t);
  }
});
y(qo.toString);
const oe = (t, ...e) => {
  const r = y({ __proto__: qo });
  return ue(zr, r, { template: t, args: e }), /** @type {DetailsToken} */
  /** @type {unknown} */
  r;
};
y(oe);
const Ko = (t, ...e) => (e = ie(
  e,
  (r) => xt(It, r) ? r : U(r)
), oe(t, ...e));
y(Ko);
const Yo = ({ template: t, args: e }) => {
  const r = [t[0]];
  for (let n = 0; n < e.length; n += 1) {
    let o = e[n];
    xt(It, o) && (o = j(It, o));
    const a = br(_r(r) || "", / $/, "");
    a !== "" && Q(r, a);
    const i = br(t[n + 1], /^ /, "");
    Q(r, o, i);
  }
  return r[r.length - 1] === "" && _r(r), r;
}, gr = new Ue();
let an = 0;
const Yn = new Ue(), Jo = (t, e = t.name) => {
  let r = j(Yn, t);
  return r !== void 0 || (an += 1, r = `${e}#${an}`, ue(Yn, t, r)), r;
}, La = (t) => {
  const e = je(t), {
    name: r,
    message: n,
    errors: o = void 0,
    cause: a = void 0,
    stack: i = void 0,
    ...c
  } = e, l = Be(c);
  if (l.length >= 1) {
    for (const u of l)
      delete t[u];
    const d = V(wn, c);
    Br(
      t,
      oe`originally with properties ${U(d)}`
    );
  }
  for (const d of Be(t)) {
    const u = e[d];
    u && ae(u, "get") && F(t, d, {
      value: t[d]
      // invoke the getter to convert to data property
    });
  }
  y(t);
}, Me = (t = oe`Assert failed`, e = P.Error, {
  errorName: r = void 0,
  cause: n = void 0,
  errors: o = void 0,
  sanitize: a = !0
} = {}) => {
  typeof t == "string" && (t = oe([t]));
  const i = j(zr, t);
  if (i === void 0)
    throw _(`unrecognized details ${U(t)}`);
  const c = Wo(i), l = n && { cause: n };
  let d;
  return typeof Kr < "u" && e === Kr ? d = Kr(o || [], c, l) : (d = /** @type {ErrorConstructor} */
  e(
    c,
    l
  ), o !== void 0 && F(d, "errors", {
    value: o,
    writable: !0,
    enumerable: !1,
    configurable: !0
  })), ue(gr, d, Yo(i)), r !== void 0 && Jo(d, r), a && La(d), d;
};
y(Me);
const { addLogArgs: Fa, takeLogArgsArray: Da } = Ho(), cn = new Ue(), Br = (t, e) => {
  typeof e == "string" && (e = oe([e]));
  const r = j(zr, e);
  if (r === void 0)
    throw _(`unrecognized details ${U(e)}`);
  const n = Yo(r), o = j(cn, t);
  if (o !== void 0)
    for (const a of o)
      a(t, n);
  else
    Fa(t, n);
};
y(Br);
const Ua = (t) => {
  if (!("stack" in t))
    return "";
  const e = `${t.stack}`, r = ya(e, `
`);
  return Do(e, " ") || r === -1 ? e : Tn(e, r + 1);
}, Er = {
  getStackString: P.getStackString || Ua,
  tagError: (t) => Jo(t),
  resetErrorTagNum: () => {
    an = 0;
  },
  getMessageLogArgs: (t) => j(gr, t),
  takeMessageLogArgs: (t) => {
    const e = j(gr, t);
    return ba(gr, t), e;
  },
  takeNoteLogArgsArray: (t, e) => {
    const r = Da(t);
    if (e !== void 0) {
      const n = j(cn, t);
      n ? Q(n, e) : ue(cn, t, [e]);
    }
    return r || [];
  }
};
y(Er);
const Gr = (t = void 0, e = !1) => {
  const r = e ? Ko : oe, n = r`Check failed`, o = (f = n, m = void 0, p = void 0) => {
    const h = Me(f, m, p);
    throw t !== void 0 && t(h), h;
  };
  y(o);
  const a = (f, ...m) => o(r(f, ...m));
  function i(f, m = void 0, p = void 0, h = void 0) {
    f || o(m, p, h);
  }
  const c = (f, m, p = void 0, h = void 0, b = void 0) => {
    Mr(f, m) || o(
      p || r`Expected ${f} is same as ${m}`,
      h || Js,
      b
    );
  };
  y(c);
  const l = (f, m, p) => {
    if (typeof f !== m) {
      if (typeof m == "string" || a`${U(m)} must be a string`, p === void 0) {
        const h = wr(m);
        p = r`${f} must be ${Sr(h)}`;
      }
      o(p, _);
    }
  };
  y(l);
  const u = Or(i, {
    error: Me,
    fail: o,
    equal: c,
    typeof: l,
    string: (f, m = void 0) => l(f, "string", m),
    note: Br,
    details: r,
    Fail: a,
    quote: U,
    bare: Sr,
    makeAssert: Gr
  });
  return y(u);
};
y(Gr);
const Y = Gr(), Jn = Y.equal, Xo = X(
  ca,
  Xe
);
Y(Xo);
const Qo = Xo.get;
Y(Qo);
const ja = (t) => se(Qo, t, []) !== void 0, Za = (t) => {
  const e = +ge(t);
  return ra(e) && ge(e) === t;
}, za = (t) => {
  Xs(t), dt(Be(t), (e) => {
    const r = X(t, e);
    Y(r), Za(e) || F(t, e, {
      ...r,
      writable: !1,
      configurable: !1
    });
  });
}, Ba = () => {
  if (typeof P.harden == "function")
    return P.harden;
  const t = new Ot(), { harden: e } = {
    /**
     * @template T
     * @param {T} root
     * @returns {T}
     */
    harden(r) {
      const n = new Rt();
      function o(u) {
        if (!Se(u))
          return;
        const f = typeof u;
        if (f !== "object" && f !== "function")
          throw _(`Unexpected typeof: ${f}`);
        ir(t, u) || xn(n, u) || En(n, u);
      }
      const a = (u) => {
        ja(u) ? za(u) : y(u);
        const f = je(u), m = G(u);
        o(m), dt(Be(f), (p) => {
          const h = f[
            /** @type {string} */
            p
          ];
          ae(h, "value") ? o(h.value) : (o(h.get), o(h.set));
        });
      }, i = Xr === void 0 && ka === void 0 ? (
        // On platforms without v8's error own stack accessor problem,
        // don't pay for any extra overhead.
        a
      ) : (u) => {
        if (Zr(u)) {
          const f = X(u, "stack");
          f && f.get === Xr && f.configurable && F(u, "stack", {
            // NOTE: Calls getter during harden, which seems dangerous.
            // But we're only calling the problematic getter whose
            // hazards we think we understand.
            // @ts-expect-error TS should know FERAL_STACK_GETTER
            // cannot be `undefined` here.
            // See https://github.com/endojs/endo/pull/2232#discussion_r1575179471
            value: se(Xr, u, [])
          });
        }
        return a(u);
      }, c = () => {
        Hn(n, i);
      }, l = (u) => {
        jr(t, u);
      }, d = () => {
        Hn(n, l);
      };
      return o(r), c(), d(), r;
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
}, Xn = {
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
typeof AggregateError < "u" && Q(ns, AggregateError);
const ln = {
  "[[Proto]]": "%FunctionPrototype%",
  length: "number",
  name: "string"
  // Do not specify "prototype" here, since only Function instances that can
  // be used as a constructor have a prototype property. For constructors,
  // since prototype properties are instance-specific, we define it there.
}, Ga = {
  // This property is not mentioned in ECMA 262, but is present in V8 and
  // necessary for lockdown to succeed.
  "[[Proto]]": "%AsyncFunctionPrototype%"
}, s = ln, Qn = Ga, R = {
  get: s,
  set: "undefined"
}, Re = {
  get: s,
  set: s
}, eo = (t) => t === R || t === Re;
function at(t) {
  return {
    // Properties of the NativeError Constructors
    "[[Proto]]": "%SharedError%",
    // NativeError.prototype
    prototype: t
  };
}
function it(t) {
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
function ve(t) {
  return {
    // Properties of the TypedArray Constructors
    "[[Proto]]": "%TypedArray%",
    BYTES_PER_ELEMENT: "number",
    prototype: t
  };
}
function be(t) {
  return {
    // Properties of the TypedArray Prototype Objects
    "[[Proto]]": "%TypedArrayPrototype%",
    BYTES_PER_ELEMENT: "number",
    constructor: t
  };
}
const to = {
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
}, xr = {
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
    "--proto--": Re,
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
    stackTraceLimit: Re,
    // Non standard, v8 only, used by several, tamed to accessor
    prepareStackTrace: Re
  },
  "%SharedError%": {
    // Properties of the Error Constructor
    "[[Proto]]": "%FunctionPrototype%",
    prototype: "%ErrorPrototype%",
    // Non standard, v8 only, used by tap
    captureStackTrace: s,
    // Non standard, v8 only, used by tap, tamed to accessor
    stackTraceLimit: Re,
    // Non standard, v8 only, used by several, tamed to accessor
    prepareStackTrace: Re
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
    stack: Re,
    // Superfluously present in some versions of V8.
    // https://github.com/tc39/notes/blob/master/meetings/2021-10/oct-26.md#:~:text=However%2C%20Chrome%2093,and%20node%2016.11.
    cause: !1
  },
  // NativeError
  EvalError: at("%EvalErrorPrototype%"),
  RangeError: at("%RangeErrorPrototype%"),
  ReferenceError: at("%ReferenceErrorPrototype%"),
  SyntaxError: at("%SyntaxErrorPrototype%"),
  TypeError: at("%TypeErrorPrototype%"),
  URIError: at("%URIErrorPrototype%"),
  // https://github.com/endojs/endo/issues/550
  AggregateError: at("%AggregateErrorPrototype%"),
  "%EvalErrorPrototype%": it("EvalError"),
  "%RangeErrorPrototype%": it("RangeError"),
  "%ReferenceErrorPrototype%": it("ReferenceError"),
  "%SyntaxErrorPrototype%": it("SyntaxError"),
  "%TypeErrorPrototype%": it("TypeError"),
  "%URIErrorPrototype%": it("URIError"),
  // https://github.com/endojs/endo/issues/550
  "%AggregateErrorPrototype%": it("AggregateError"),
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
    ...to,
    // `%InitialMath%.random()` has the standard unsafe behavior
    random: s
  },
  "%SharedMath%": {
    ...to,
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
  BigInt64Array: ve("%BigInt64ArrayPrototype%"),
  BigUint64Array: ve("%BigUint64ArrayPrototype%"),
  // https://github.com/tc39/proposal-float16array
  Float16Array: ve("%Float16ArrayPrototype%"),
  Float32Array: ve("%Float32ArrayPrototype%"),
  Float64Array: ve("%Float64ArrayPrototype%"),
  Int16Array: ve("%Int16ArrayPrototype%"),
  Int32Array: ve("%Int32ArrayPrototype%"),
  Int8Array: ve("%Int8ArrayPrototype%"),
  Uint16Array: ve("%Uint16ArrayPrototype%"),
  Uint32Array: ve("%Uint32ArrayPrototype%"),
  Uint8ClampedArray: ve("%Uint8ClampedArrayPrototype%"),
  Uint8Array: {
    ...ve("%Uint8ArrayPrototype%"),
    // https://github.com/tc39/proposal-arraybuffer-base64
    fromBase64: s,
    // https://github.com/tc39/proposal-arraybuffer-base64
    fromHex: s
  },
  "%BigInt64ArrayPrototype%": be("BigInt64Array"),
  "%BigUint64ArrayPrototype%": be("BigUint64Array"),
  // https://github.com/tc39/proposal-float16array
  "%Float16ArrayPrototype%": be("Float16Array"),
  "%Float32ArrayPrototype%": be("Float32Array"),
  "%Float64ArrayPrototype%": be("Float64Array"),
  "%Int16ArrayPrototype%": be("Int16Array"),
  "%Int32ArrayPrototype%": be("Int32Array"),
  "%Int8ArrayPrototype%": be("Int8Array"),
  "%Uint16ArrayPrototype%": be("Uint16Array"),
  "%Uint32ArrayPrototype%": be("Uint32Array"),
  "%Uint8ClampedArrayPrototype%": be("Uint8ClampedArray"),
  "%Uint8ArrayPrototype%": {
    ...be("Uint8Array"),
    // https://github.com/tc39/proposal-arraybuffer-base64
    setFromBase64: s,
    // https://github.com/tc39/proposal-arraybuffer-base64
    setFromHex: s,
    // https://github.com/tc39/proposal-arraybuffer-base64
    toBase64: s,
    // https://github.com/tc39/proposal-arraybuffer-base64
    toHex: s
  },
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
    "UniqueSymbol(async_id_symbol)": Re,
    "UniqueSymbol(trigger_async_id_symbol)": Re,
    "UniqueSymbol(destroyed)": Re
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
    import: Qn,
    load: Qn,
    importNow: s,
    module: s,
    "@@toStringTag": "string"
  },
  lockdown: s,
  harden: { ...s, isFake: "boolean" },
  "%InitialGetStackString%": s
}, Va = (t) => typeof t == "function";
function Ha(t, e, r) {
  if (ae(t, e)) {
    const n = X(t, e);
    if (!n || !Mr(n.value, r.value) || n.get !== r.get || n.set !== r.set || n.writable !== r.writable || n.enumerable !== r.enumerable || n.configurable !== r.configurable)
      throw _(`Conflicting definitions of ${e}`);
  }
  F(t, e, r);
}
function Wa(t, e) {
  for (const [r, n] of de(e))
    Ha(t, r, n);
}
function os(t, e) {
  const r = { __proto__: null };
  for (const [n, o] of de(e))
    ae(t, n) && (r[o] = t[n]);
  return r;
}
const ss = () => {
  const t = V(null);
  let e;
  const r = (c) => {
    Wa(t, je(c));
  };
  y(r);
  const n = () => {
    for (const [c, l] of de(t)) {
      if (!Se(l) || !ae(l, "prototype"))
        continue;
      const d = xr[c];
      if (typeof d != "object")
        throw _(`Expected permit object at whitelist.${c}`);
      const u = d.prototype;
      if (!u)
        throw _(`${c}.prototype property not whitelisted`);
      if (typeof u != "string" || !ae(xr, u))
        throw _(`Unrecognized ${c}.prototype whitelist entry`);
      const f = l.prototype;
      if (ae(t, u)) {
        if (t[u] !== f)
          throw _(`Conflicting bindings of ${u}`);
        continue;
      }
      t[u] = f;
    }
  };
  y(n);
  const o = () => (y(t), e = new Ot(Qe(ko(t), Va)), t);
  y(o);
  const a = (c) => {
    if (!e)
      throw _(
        "isPseudoNative can only be called after finalIntrinsics"
      );
    return ir(e, c);
  };
  y(a);
  const i = {
    addIntrinsics: r,
    completePrototypes: n,
    finalIntrinsics: o,
    isPseudoNative: a
  };
  return y(i), r(es), r(os(P, ts)), i;
}, qa = (t) => {
  const { addIntrinsics: e, finalIntrinsics: r } = ss();
  return e(os(t, rs)), r();
};
function Ka(t, e) {
  let r = !1;
  const n = (m, ...p) => (r || (console.groupCollapsed("Removing unpermitted intrinsics"), r = !0), console[m](...p)), o = ["undefined", "boolean", "number", "string", "symbol"], a = new Ce(
    St ? ie(
      Qe(
        de(xr["%SharedSymbol%"]),
        ([m, p]) => p === "symbol" && typeof St[m] == "symbol"
      ),
      ([m]) => [St[m], `@@${m}`]
    ) : []
  );
  function i(m, p) {
    if (typeof p == "string")
      return p;
    const h = Ge(a, p);
    if (typeof p == "symbol") {
      if (h)
        return h;
      {
        const b = ea(p);
        return b !== void 0 ? `RegisteredSymbol(${b})` : `Unique${ge(p)}`;
      }
    }
    throw _(`Unexpected property name type ${m} ${p}`);
  }
  function c(m, p, h) {
    if (!Se(p))
      throw _(`Object expected: ${m}, ${p}, ${h}`);
    const b = G(p);
    if (!(b === null && h === null)) {
      if (h !== void 0 && typeof h != "string")
        throw _(`Malformed whitelist permit ${m}.__proto__`);
      if (b !== t[h || "%ObjectPrototype%"])
        throw _(`Unexpected intrinsic ${m}.__proto__ at ${h}`);
    }
  }
  function l(m, p, h, b) {
    if (typeof b == "object")
      return f(m, p, b), !0;
    if (b === !1)
      return !1;
    if (typeof b == "string") {
      if (h === "prototype" || h === "constructor") {
        if (ae(t, b)) {
          if (p !== t[b])
            throw _(`Does not match whitelist ${m}`);
          return !0;
        }
      } else if (Dr(o, b)) {
        if (typeof p !== b)
          throw _(
            `At ${m} expected ${b} not ${typeof p}`
          );
        return !0;
      }
    }
    throw _(`Unexpected whitelist permit ${b} at ${m}`);
  }
  function d(m, p, h, b) {
    const E = X(p, h);
    if (!E)
      throw _(`Property ${h} not found at ${m}`);
    if (ae(E, "value")) {
      if (eo(b))
        throw _(`Accessor expected at ${m}`);
      return l(m, E.value, h, b);
    }
    if (!eo(b))
      throw _(`Accessor not expected at ${m}`);
    return l(`${m}<get>`, E.get, h, b.get) && l(`${m}<set>`, E.set, h, b.set);
  }
  function u(m, p, h) {
    const b = h === "__proto__" ? "--proto--" : h;
    if (ae(p, b))
      return p[b];
    if (typeof m == "function" && ae(ln, b))
      return ln[b];
  }
  function f(m, p, h) {
    if (p == null)
      return;
    const b = h["[[Proto]]"];
    c(m, p, b), typeof p == "function" && e(p);
    for (const E of Be(p)) {
      const S = i(m, E), C = `${m}.${S}`, x = u(p, h, S);
      if (!x || !d(C, p, E, x)) {
        x !== !1 && n("warn", `Removing ${C}`);
        try {
          delete p[E];
        } catch (M) {
          if (E in p) {
            if (typeof p == "function" && E === "prototype" && (p.prototype = void 0, p.prototype === void 0)) {
              n(
                "warn",
                `Tolerating undeletable ${C} === undefined`
              );
              continue;
            }
            n("error", `failed to delete ${C}`, M);
          } else
            n("error", `deleting ${C} threw`, M);
          throw M;
        }
      }
    }
  }
  try {
    f("intrinsics", t, xr);
  } finally {
    r && console.groupEnd();
  }
}
function Ya() {
  try {
    we.prototype.constructor("return 1");
  } catch {
    return y({});
  }
  const t = {};
  function e(r, n, o) {
    let a;
    try {
      a = (0, eval)(o);
    } catch (l) {
      if (l instanceof or)
        return;
      throw l;
    }
    const i = G(a), c = function() {
      throw _(
        "Function.prototype.constructor is not a valid constructor."
      );
    };
    Z(c, {
      prototype: { value: i },
      name: {
        value: r,
        writable: !1,
        enumerable: !1,
        configurable: !0
      }
    }), Z(i, {
      constructor: { value: c }
    }), c !== we.prototype.constructor && xo(c, we.prototype.constructor), t[n] = c;
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
    throw _(`unrecognized dateTaming ${t}`);
  const e = Vs, r = e.prototype, n = {
    /**
     * `%SharedDate%.now()` throw a `TypeError` starting with "secure mode".
     * See https://github.com/endojs/endo/issues/910#issuecomment-1581855420
     */
    now() {
      throw _("secure mode Calling %SharedDate%.now() throws");
    }
  }, o = ({ powers: c = "none" } = {}) => {
    let l;
    return c === "original" ? l = function(...u) {
      return new.target === void 0 ? se(e, void 0, u) : yr(e, u, new.target);
    } : l = function(...u) {
      if (new.target === void 0)
        throw _(
          "secure mode Calling %SharedDate% constructor as a function throws"
        );
      if (u.length === 0)
        throw _(
          "secure mode Calling new %SharedDate%() with no arguments throws"
        );
      return yr(e, u, new.target);
    }, Z(l, {
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
  return Z(a, {
    now: {
      value: e.now,
      writable: !0,
      enumerable: !1,
      configurable: !0
    }
  }), Z(i, {
    now: {
      value: n.now,
      writable: !0,
      enumerable: !1,
      configurable: !0
    }
  }), Z(r, {
    constructor: { value: i }
  }), {
    "%InitialDate%": a,
    "%SharedDate%": i
  };
}
function Xa(t = "safe") {
  if (t !== "safe" && t !== "unsafe")
    throw _(`unrecognized mathTaming ${t}`);
  const e = qs, r = e, { random: n, ...o } = je(e), i = V(wn, {
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
function Qa(t = "safe") {
  if (t !== "safe" && t !== "unsafe")
    throw _(`unrecognized regExpTaming ${t}`);
  const e = Je.prototype, r = (a = {}) => {
    const i = function(...l) {
      return new.target === void 0 ? Je(...l) : yr(Je, l, new.target);
    };
    if (Z(i, {
      length: { value: 2 },
      prototype: {
        value: e,
        writable: !1,
        enumerable: !1,
        configurable: !1
      }
    }), Yr) {
      const c = X(
        Je,
        Yr
      );
      if (!c)
        throw _("no RegExp[Symbol.species] descriptor");
      Z(i, {
        [Yr]: c
      });
    }
    return i;
  }, n = r(), o = r();
  return t !== "unsafe" && delete e.compile, Z(e, {
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
    [Xe]: !0
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
    [sr]: !0
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
    [Xe]: !0
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
  const n = new Rt(r);
  function o(d, u, f, m) {
    if ("value" in m && m.configurable) {
      const { value: p } = m, h = xn(n, f), { get: b, set: E } = X(
        {
          get [f]() {
            return p;
          },
          set [f](S) {
            if (u === this)
              throw _(
                `Cannot assign to read only property '${ge(
                  f
                )}' of '${d}'`
              );
            ae(this, f) ? this[f] = S : (h && console.error(_(`Override property ${f}`)), F(this, f, {
              value: S,
              writable: !0,
              enumerable: !0,
              configurable: !0
            }));
          }
        },
        f
      );
      F(b, "originalValue", {
        value: p,
        writable: !1,
        enumerable: !1,
        configurable: !1
      }), F(u, f, {
        get: b,
        set: E,
        enumerable: m.enumerable,
        configurable: m.configurable
      });
    }
  }
  function a(d, u, f) {
    const m = X(u, f);
    m && o(d, u, f, m);
  }
  function i(d, u) {
    const f = je(u);
    f && dt(Be(f), (m) => o(d, u, m, f[m]));
  }
  function c(d, u, f) {
    for (const m of Be(f)) {
      const p = X(u, m);
      if (!p || p.get || p.set)
        continue;
      const h = `${d}.${ge(m)}`, b = f[m];
      if (b === !0)
        a(h, u, m);
      else if (b === "*")
        i(h, p.value);
      else if (Se(b))
        c(h, p.value, b);
      else
        throw _(`Unexpected override enablement plan ${h}`);
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
      throw _(`unrecognized overrideTaming ${e}`);
  }
  c("root", t, l);
}
const { Fail: un, quote: kr } = Y, ni = /^(\w*[a-z])Locale([A-Z]\w*)$/, is = {
  // See https://tc39.es/ecma262/#sec-string.prototype.localecompare
  localeCompare(t) {
    if (this === null || this === void 0)
      throw _(
        'Cannot localeCompare with null or undefined "this" value'
      );
    const e = `${this}`, r = `${t}`;
    return e < r ? -1 : e > r ? 1 : (e === r || un`expected ${kr(e)} and ${kr(r)} to compare`, 0);
  },
  toString() {
    return `${this}`;
  }
}, oi = is.localeCompare, si = is.toString;
function ai(t, e = "safe") {
  if (e !== "safe" && e !== "unsafe")
    throw _(`unrecognized localeTaming ${e}`);
  if (e !== "unsafe") {
    F(ge.prototype, "localeCompare", {
      value: oi
    });
    for (const r of At(t)) {
      const n = t[r];
      if (Se(n))
        for (const o of At(n)) {
          const a = Pn(ni, o);
          if (a) {
            typeof n[o] == "function" || un`expected ${kr(o)} to be a function`;
            const i = `${a[1]}${a[2]}`, c = n[i];
            typeof c == "function" || un`function ${kr(i)} not found`, F(n, o, { value: c });
          }
        }
    }
    F(So.prototype, "toLocaleString", {
      value: si
    });
  }
}
const ii = (t) => ({
  eval(r) {
    return typeof r != "string" ? r : t(r);
  }
}).eval, { Fail: ro } = Y, ci = (t) => {
  const e = function(n) {
    const o = `${_r(arguments) || ""}`, a = `${Lt(arguments, ",")}`;
    new we(a, ""), new we(o);
    const i = `(function anonymous(${a}
) {
${o}
})`;
    return t(i);
  };
  return Z(e, {
    // Ensure that any function created in any evaluator in a realm is an
    // instance of Function in any evaluator of the same realm.
    prototype: {
      value: we.prototype,
      writable: !1,
      enumerable: !1,
      configurable: !1
    }
  }), G(we) === we.prototype || ro`Function prototype is the same accross compartments`, G(e) === we.prototype || ro`Function constructor prototype is the same accross compartments`, e;
}, li = (t) => {
  F(
    t,
    Qs,
    y(
      Or(V(null), {
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
}, cs = (t) => {
  for (const [e, r] of de(es))
    F(t, e, {
      value: r,
      writable: !1,
      enumerable: !1,
      configurable: !1
    });
}, ls = (t, {
  intrinsics: e,
  newGlobalPropertyNames: r,
  makeCompartmentConstructor: n,
  markVirtualizedNativeFunction: o,
  parentCompartment: a
}) => {
  for (const [c, l] of de(ts))
    ae(e, l) && F(t, c, {
      value: e[l],
      writable: !0,
      enumerable: !1,
      configurable: !0
    });
  for (const [c, l] of de(r))
    ae(e, l) && F(t, c, {
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
      a
    )
  );
  for (const [c, l] of de(i))
    F(t, c, {
      value: l,
      writable: !0,
      enumerable: !1,
      configurable: !0
    }), typeof l == "function" && o(l);
}, dn = (t, e, r) => {
  {
    const n = y(ii(e));
    r(n), F(t, "eval", {
      value: n,
      writable: !0,
      enumerable: !1,
      configurable: !0
    });
  }
  {
    const n = y(ci(e));
    r(n), F(t, "Function", {
      value: n,
      writable: !0,
      enumerable: !1,
      configurable: !0
    });
  }
}, { Fail: ui, quote: us } = Y, ds = new Rr(
  In,
  y({
    get(t, e) {
      ui`Please report unexpected scope handler trap: ${us(ge(e))}`;
    }
  })
), di = {
  get(t, e) {
  },
  set(t, e, r) {
    throw zt(`${ge(e)} is not defined`);
  },
  has(t, e) {
    return e in P;
  },
  // note: this is likely a bug of safari
  // https://bugs.webkit.org/show_bug.cgi?id=195534
  getPrototypeOf(t) {
    return null;
  },
  // See https://github.com/endojs/endo/issues/1510
  // TODO: report as bug to v8 or Chrome, and record issue link here.
  getOwnPropertyDescriptor(t, e) {
    const r = us(ge(e));
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
}, fs = y(
  V(
    ds,
    je(di)
  )
), fi = new Rr(
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
    V(
      ds,
      je(e)
    )
  );
  return new Rr(
    In,
    r
  );
};
y(ps);
const { Fail: pi } = Y, mi = () => {
  const t = V(null), e = y({
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
      n !== null && pi`a handler did not reset allowNextEvalToBeUnsafe ${n.err}`, Z(t, e);
    },
    /** @type {null | { err: any }} */
    revoked: null
  };
  return r;
}, no = "\\s*[@#]\\s*([a-zA-Z][a-zA-Z0-9]*)\\s*=\\s*([^\\s\\*]*)", hi = new Je(
  `(?:\\s*//${no}|/\\*${no}\\s*\\*/)\\s*$`
), $n = (t) => {
  let e = "<unknown>";
  for (; t.length > 0; ) {
    const r = Pn(hi, t);
    if (r === null)
      break;
    t = Tn(t, 0, t.length - r[0].length), r[3] === "sourceURL" ? e = r[4] : r[1] === "sourceURL" && (e = r[2]);
  }
  return e;
};
function Nn(t, e) {
  const r = _a(t, e);
  if (r < 0)
    return -1;
  const n = t[r] === `
` ? 1 : 0;
  return An(Tn(t, 0, r), `
`).length + n;
}
const ms = new Je("(?:<!--|-->)", "g"), hs = (t) => {
  const e = Nn(t, ms);
  if (e < 0)
    return t;
  const r = $n(t);
  throw or(
    `Possible HTML comment rejected at ${r}:${e}. (SES_HTML_COMMENT_REJECTED)`
  );
}, gs = (t) => br(t, ms, (r) => r[0] === "<" ? "< ! --" : "-- >"), ys = new Je(
  "(^|[^.]|\\.\\.\\.)\\bimport(\\s*(?:\\(|/[/*]))",
  "g"
), _s = (t) => {
  const e = Nn(t, ys);
  if (e < 0)
    return t;
  const r = $n(t);
  throw or(
    `Possible import expression rejected at ${r}:${e}. (SES_IMPORT_REJECTED)`
  );
}, vs = (t) => br(t, ys, (r, n, o) => `${n}__import__${o}`), gi = new Je(
  "(^|[^.])\\beval(\\s*\\()",
  "g"
), bs = (t) => {
  const e = Nn(t, gi);
  if (e < 0)
    return t;
  const r = $n(t);
  throw or(
    `Possible direct eval expression rejected at ${r}:${e}. (SES_EVAL_REJECTED)`
  );
}, ws = (t) => (t = hs(t), t = _s(t), t), Ss = (t, e) => {
  for (const r of e)
    t = r(t);
  return t;
};
y({
  rejectHtmlComments: y(hs),
  evadeHtmlCommentTest: y(gs),
  rejectImportExpressions: y(_s),
  evadeImportExpressionTest: y(vs),
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
], _i = /^[a-zA-Z_$][\w$]*$/, oo = (t) => t !== "eval" && !Dr(yi, t) && kn(_i, t);
function so(t, e) {
  const r = X(t, e);
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
  ae(r, "value");
}
const vi = (t, e = {}) => {
  const r = At(t), n = At(e), o = Qe(
    n,
    (i) => oo(i) && so(e, i)
  );
  return {
    globalObjectConstants: Qe(
      r,
      (i) => (
        // Can't define a constant: it would prevent a
        // lookup on the endowments.
        !Dr(n, i) && oo(i) && so(t, i)
      )
    ),
    moduleLexicalConstants: o
  };
};
function ao(t, e) {
  return t.length === 0 ? "" : `const {${Lt(t, ",")}} = this.${e};`;
}
const bi = (t) => {
  const { globalObjectConstants: e, moduleLexicalConstants: r } = vi(
    t.globalObject,
    t.moduleLexicals
  ), n = ao(
    e,
    "globalObject"
  ), o = ao(
    r,
    "moduleLexicals"
  ), a = we(`
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
  return se(a, t, []);
}, { Fail: wi } = Y, Rn = ({
  globalObject: t,
  moduleLexicals: e = {},
  globalTransforms: r = [],
  sloppyGlobalsMode: n = !1
}) => {
  const o = n ? ps(t) : fi, a = mi(), { evalScope: i } = a, c = y({
    evalScope: i,
    moduleLexicals: e,
    globalObject: t,
    scopeTerminator: o
  });
  let l;
  const d = () => {
    l || (l = bi(c));
  };
  return { safeEvaluate: (f, m) => {
    const { localTransforms: p = [] } = m || {};
    d(), f = Ss(f, [
      ...p,
      ...r,
      ws
    ]);
    let h;
    try {
      return a.allowNextEvalToBeUnsafe(), se(l, t, [f]);
    } catch (b) {
      throw h = b, b;
    } finally {
      const b = "eval" in i;
      delete i.eval, b && (a.revoked = { err: h }, wi`handler did not reset allowNextEvalToBeUnsafe ${h}`);
    }
  } };
}, Si = ") { [native code] }";
let en;
const Es = () => {
  if (en === void 0) {
    const t = new Ot();
    F(Sn, "toString", {
      value: {
        toString() {
          const r = wa(this);
          return Mo(r, Si) || !ir(t, this) ? r : `function ${this.name}() { [native code] }`;
        }
      }.toString
    }), en = y(
      (r) => jr(t, r)
    );
  }
  return en;
};
function Ei(t = "safe") {
  if (t !== "safe" && t !== "unsafe")
    throw _(`unrecognized domainTaming ${t}`);
  if (t === "unsafe")
    return;
  const e = P.process || void 0;
  if (typeof e == "object") {
    const r = X(e, "domain");
    if (r !== void 0 && r.get !== void 0)
      throw _(
        "SES failed to lockdown, Node.js domains have been initialized (SES_NO_DOMAINS)"
      );
    F(e, "domain", {
      value: null,
      configurable: !1,
      writable: !1,
      enumerable: !1
    });
  }
}
const On = y([
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
]), Mn = y([
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
  ...On,
  ...Mn
]), xi = (t, { shouldResetForDebugging: e = !1 } = {}) => {
  e && t.resetErrorTagNum();
  let r = [];
  const n = ht(
    ie(xs, ([i, c]) => {
      const l = (...d) => {
        Q(r, [i, ...d]);
      };
      return F(l, "name", { value: i }), [i, y(l)];
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
const lt = {
  NOTE: "ERROR_NOTE:",
  MESSAGE: "ERROR_MESSAGE:",
  CAUSE: "cause:",
  ERRORS: "errors:"
};
y(lt);
const Ln = (t, e) => {
  if (!t)
    return;
  const { getStackString: r, tagError: n, takeMessageLogArgs: o, takeNoteLogArgsArray: a } = e, i = (E, S) => ie(E, (x) => Zr(x) ? (Q(S, x), `(${n(x)})`) : x), c = (E, S, C, x, M) => {
    const D = n(S), B = C === lt.MESSAGE ? `${D}:` : `${D} ${C}`, L = i(x, M);
    t[E](B, ...L);
  }, l = (E, S, C = void 0) => {
    if (S.length === 0)
      return;
    if (S.length === 1 && C === void 0) {
      f(E, S[0]);
      return;
    }
    let x;
    S.length === 1 ? x = "Nested error" : x = `Nested ${S.length} errors`, C !== void 0 && (x = `${x} under ${C}`), t.group(x);
    try {
      for (const M of S)
        f(E, M);
    } finally {
      t.groupEnd();
    }
  }, d = new Ot(), u = (E) => (S, C) => {
    const x = [];
    c(E, S, lt.NOTE, C, x), l(E, x, n(S));
  }, f = (E, S) => {
    if (ir(d, S))
      return;
    const C = n(S);
    jr(d, S);
    const x = [], M = o(S), D = a(
      S,
      u(E)
    );
    M === void 0 ? t[E](`${C}:`, S.message) : c(
      E,
      S,
      lt.MESSAGE,
      M,
      x
    );
    let B = r(S);
    typeof B == "string" && B.length >= 1 && !Mo(B, `
`) && (B += `
`), t[E](B), S.cause && c(E, S, lt.CAUSE, [S.cause], x), S.errors && c(E, S, lt.ERRORS, S.errors, x);
    for (const L of D)
      c(E, S, lt.NOTE, L, x);
    l(E, x, C);
  }, m = ie(On, ([E, S]) => {
    const C = (...x) => {
      const M = [], D = i(x, M);
      t[E](...D), l(E, M);
    };
    return F(C, "name", { value: E }), [E, y(C)];
  }), p = Qe(
    Mn,
    ([E, S]) => E in t
  ), h = ie(p, ([E, S]) => {
    const C = (...x) => {
      t[E](...x);
    };
    return F(C, "name", { value: E }), [E, y(C)];
  }), b = ht([...m, ...h]);
  return (
    /** @type {VirtualConsole} */
    y(b)
  );
};
y(Ln);
const ki = (t, e, r) => {
  const [n, ...o] = An(t, e), a = Ro(o, (i) => [e, ...r, i]);
  return ["", n, ...a];
}, ks = (t) => y((r) => {
  const n = [], o = (...l) => (n.length > 0 && (l = Ro(
    l,
    (d) => typeof d == "string" && Lo(d, `
`) ? ki(d, `
`, n) : [d]
  ), l = [...n, ...l]), r(...l)), a = (l, d) => ({ [l]: (...u) => d(...u) })[l], i = ht([
    ...ie(On, ([l]) => [
      l,
      a(l, o)
    ]),
    ...ie(Mn, ([l]) => [
      l,
      a(l, (...d) => o(l, ...d))
    ])
  ]);
  for (const l of ["group", "groupCollapsed"])
    i[l] && (i[l] = a(l, (...d) => {
      d.length >= 1 && o(...d), Q(n, " ");
    }));
  return i.groupEnd && (i.groupEnd = a("groupEnd", (...l) => {
    _r(n);
  })), harden(i), Ln(
    /** @type {VirtualConsole} */
    i,
    t
  );
});
y(ks);
const Pi = (t, e, r = void 0) => {
  const n = Qe(
    xs,
    ([i, c]) => i in t
  ), o = ie(n, ([i, c]) => [i, y((...d) => {
    (c === void 0 || e.canLog(c)) && t[i](...d);
  })]), a = ht(o);
  return (
    /** @type {VirtualConsole} */
    y(a)
  );
};
y(Pi);
const Ti = (t) => {
  if (Tt === void 0)
    return;
  let e = 0;
  const r = new Ce(), n = (u) => {
    fa(r, u);
  }, o = new Ue(), a = (u) => {
    if (Ur(r, u)) {
      const f = Ge(r, u);
      n(u), t(f);
    }
  }, i = new Tt(a);
  return {
    rejectionHandledHandler: (u) => {
      const f = j(o, u);
      n(f);
    },
    unhandledRejectionHandler: (u, f) => {
      e += 1;
      const m = e;
      le(r, m, u), ue(o, f, m), Ea(i, f, m, f);
    },
    processTerminationHandler: () => {
      for (const [u, f] of pa(r))
        n(u), t(f);
    }
  };
}, tn = (t) => {
  throw _(t);
}, io = (t, e) => y((...r) => se(t, e, r)), Ai = (t = "safe", e = "platform", r = "report", n = void 0) => {
  console.log("tameConsole", t, e, r), t === "safe" || t === "unsafe" || tn(`unrecognized consoleTaming ${t}`);
  let o;
  n === void 0 ? o = Er : o = {
    ...Er,
    getStackString: n
  };
  const a = (
    /** @type {VirtualConsole} */
    // eslint-disable-next-line no-nested-ternary
    typeof P.console < "u" ? P.console : typeof P.print == "function" ? (
      // Make a good-enough console for eshost (including only functions that
      // log at a specific level with no special argument interpretation).
      // https://console.spec.whatwg.org/#logging
      ((d) => y({ debug: d, log: d, info: d, warn: d, error: d }))(
        // eslint-disable-next-line no-undef
        io(P.print)
      )
    ) : void 0
  );
  if (a && a.log)
    for (const d of ["warn", "error"])
      a[d] || F(a, d, {
        value: io(a.log, a)
      });
  const i = (
    /** @type {VirtualConsole} */
    t === "unsafe" ? a : Ln(a, o)
  ), c = P.process || void 0;
  if (e !== "none" && typeof c == "object" && typeof c.on == "function") {
    let d;
    if (e === "platform" || e === "exit") {
      const { exit: u } = c;
      typeof u == "function" || tn("missing process.exit"), d = () => u(c.exitCode || -1);
    } else e === "abort" && (d = c.abort, typeof d == "function" || tn("missing process.abort"));
    c.on("uncaughtException", (u) => {
      i.error(u), d && d();
    });
  }
  if (r !== "none" && typeof c == "object" && typeof c.on == "function") {
    const u = Ti((f) => {
      i.error("SES_UNHANDLED_REJECTION:", f);
    });
    u && (c.on("unhandledRejection", u.unhandledRejectionHandler), c.on("rejectionHandled", u.rejectionHandledHandler), c.on("exit", u.processTerminationHandler));
  }
  const l = P.window || void 0;
  return e !== "none" && typeof l == "object" && typeof l.addEventListener == "function" && (console.log("eeeeeeeeeee"), l.addEventListener("error", (d) => {
    d.preventDefault(), i.error(d.error), (e === "exit" || e === "abort") && (l.location.href = "about:blank");
  })), { console: i };
}, Ii = [
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
], Ci = (t) => {
  const r = ht(ie(Ii, (n) => {
    const o = t[n];
    return [n, () => se(o, t, [])];
  }));
  return V(r, {});
}, $i = (t) => ie(t, Ci), Ni = /\/node_modules\//, Ri = /^(?:node:)?internal\//, Oi = /\/packages\/ses\/src\/error\/assert.js$/, Mi = /\/packages\/eventual-send\/src\//, Li = [
  Ni,
  Ri,
  Oi,
  Mi
], Fi = (t) => {
  if (!t)
    return !0;
  for (const e of Li)
    if (kn(e, t))
      return !1;
  return !0;
}, Di = /^((?:.*[( ])?)[:/\w_-]*\/\.\.\.\/(.+)$/, Ui = /^((?:.*[( ])?)[:/\w_-]*\/(packages\/.+)$/, ji = [
  Di,
  Ui
], Zi = (t) => {
  for (const e of ji) {
    const r = Pn(e, t);
    if (r)
      return Lt(la(r, 1), "");
  }
  return t;
}, zi = (t, e, r, n) => {
  if (r === "unsafe-debug")
    throw _(
      "internal: v8+unsafe-debug special case should already be done"
    );
  const o = t.captureStackTrace, a = (p) => n === "verbose" ? !0 : Fi(p.getFileName()), i = (p) => {
    let h = `${p}`;
    return n === "concise" && (h = Zi(h)), `
  at ${h}`;
  }, c = (p, h) => Lt(
    ie(Qe(h, a), i),
    ""
  ), l = new Ue(), d = {
    // The optional `optFn` argument is for cutting off the bottom of
    // the stack --- for capturing the stack only above the topmost
    // call to that function. Since this isn't the "real" captureStackTrace
    // but instead calls the real one, if no other cutoff is provided,
    // we cut this one off.
    captureStackTrace(p, h = d.captureStackTrace) {
      if (typeof o == "function") {
        se(o, t, [p, h]);
        return;
      }
      Io(p, "stack", "");
    },
    // Shim of proposed special power, to reside by default only
    // in the start compartment, for getting the stack traceback
    // string associated with an error.
    // See https://tc39.es/proposal-error-stacks/
    getStackString(p) {
      let h = j(l, p);
      if (h === void 0 && (p.stack, h = j(l, p), h || (h = { stackString: "" }, ue(l, p, h))), h.stackString !== void 0)
        return h.stackString;
      const b = c(p, h.callSites);
      return ue(l, p, { stackString: b }), b;
    },
    prepareStackTrace(p, h) {
      if (r === "unsafe") {
        const b = c(p, h);
        return ue(l, p, { stackString: b }), `${p}${b}`;
      } else
        return ue(l, p, { callSites: h }), "";
    }
  }, u = d.prepareStackTrace;
  t.prepareStackTrace = u;
  const f = new Ot([u]), m = (p) => {
    if (ir(f, p))
      return p;
    const h = {
      prepareStackTrace(b, E) {
        return ue(l, b, { callSites: E }), p(b, $i(E));
      }
    };
    return jr(f, h.prepareStackTrace), h.prepareStackTrace;
  };
  return Z(e, {
    captureStackTrace: {
      value: d.captureStackTrace,
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
          t.prepareStackTrace = u;
      },
      enumerable: !1,
      configurable: !0
    }
  }), d.getStackString;
}, co = X(ne.prototype, "stack"), lo = co && co.get, Bi = {
  getStackString(t) {
    return typeof lo == "function" ? se(lo, t, []) : "stack" in t ? `${t.stack}` : "";
  }
};
let dr = Bi.getStackString;
function Gi(t = "safe", e = "concise") {
  if (t !== "safe" && t !== "unsafe" && t !== "unsafe-debug")
    throw _(`unrecognized errorTaming ${t}`);
  if (e !== "concise" && e !== "verbose")
    throw _(`unrecognized stackFiltering ${e}`);
  const r = ne.prototype, { captureStackTrace: n } = ne, o = typeof n == "function" ? "v8" : "unknown", a = (l = {}) => {
    const d = function(...f) {
      let m;
      return new.target === void 0 ? m = se(ne, this, f) : m = yr(ne, f, new.target), o === "v8" && se(n, ne, [m, d]), m;
    };
    return Z(d, {
      length: { value: 1 },
      prototype: {
        value: r,
        writable: !1,
        enumerable: !1,
        configurable: !1
      }
    }), d;
  }, i = a({ powers: "original" }), c = a({ powers: "none" });
  Z(r, {
    constructor: { value: c }
  });
  for (const l of ns)
    xo(l, c);
  if (Z(i, {
    stackTraceLimit: {
      get() {
        if (typeof ne.stackTraceLimit == "number")
          return ne.stackTraceLimit;
      },
      set(l) {
        if (typeof l == "number" && typeof ne.stackTraceLimit == "number") {
          ne.stackTraceLimit = l;
          return;
        }
      },
      // WTF on v8 stackTraceLimit is enumerable
      enumerable: !1,
      configurable: !0
    }
  }), t === "unsafe-debug" && o === "v8") {
    Z(i, {
      prepareStackTrace: {
        get() {
          return ne.prepareStackTrace;
        },
        set(d) {
          ne.prepareStackTrace = d;
        },
        enumerable: !1,
        configurable: !0
      },
      captureStackTrace: {
        value: ne.captureStackTrace,
        writable: !0,
        enumerable: !1,
        configurable: !0
      }
    });
    const l = je(i);
    return Z(c, {
      stackTraceLimit: l.stackTraceLimit,
      prepareStackTrace: l.prepareStackTrace,
      captureStackTrace: l.captureStackTrace
    }), {
      "%InitialGetStackString%": dr,
      "%InitialError%": i,
      "%SharedError%": c
    };
  }
  return Z(c, {
    stackTraceLimit: {
      get() {
      },
      set(l) {
      },
      enumerable: !1,
      configurable: !0
    }
  }), o === "v8" && Z(c, {
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
        F(l, "stack", {
          value: ""
        });
      },
      writable: !1,
      enumerable: !1,
      configurable: !0
    }
  }), o === "v8" ? dr = zi(
    ne,
    i,
    t,
    e
  ) : t === "unsafe" || t === "unsafe-debug" ? Z(r, {
    stack: {
      get() {
        return dr(this);
      },
      set(l) {
        Z(this, {
          stack: {
            value: l,
            writable: !0,
            enumerable: !0,
            configurable: !0
          }
        });
      }
    }
  }) : Z(r, {
    stack: {
      get() {
        return `${this}`;
      },
      set(l) {
        Z(this, {
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
    "%InitialGetStackString%": dr,
    "%InitialError%": i,
    "%SharedError%": c
  };
}
const Vi = () => {
}, Hi = async (t, e, r) => {
  await null;
  const n = t(...e);
  let o = vr(n);
  for (; !o.done; )
    try {
      const a = await o.value;
      o = vr(n, a);
    } catch (a) {
      o = Fo(n, r(a));
    }
  return o.value;
}, Wi = (t, e) => {
  const r = t(...e);
  let n = vr(r);
  for (; !n.done; )
    try {
      n = vr(r, n.value);
    } catch (o) {
      n = Fo(r, o);
    }
  return n.value;
}, qi = (t, e) => y({ compartment: t, specifier: e }), Ki = (t, e, r) => {
  const n = V(null);
  for (const o of t) {
    const a = e(o, r);
    n[o] = a;
  }
  return y(n);
}, Dt = (t, e, r, n, o, a, i, c, l) => {
  const { resolveHook: d } = j(t, r), u = Ki(
    o.imports,
    d,
    n
  ), f = y({
    compartment: r,
    moduleSource: o,
    moduleSpecifier: n,
    resolvedImports: u,
    importMeta: l
  });
  for (const m of ko(u))
    a(kt, [
      t,
      e,
      r,
      m,
      a,
      i,
      c
    ]);
  return f;
};
function* Yi(t, e, r, n, o, a, i) {
  const {
    importHook: c,
    importNowHook: l,
    moduleMap: d,
    moduleMapHook: u,
    moduleRecords: f,
    parentCompartment: m
  } = j(t, r);
  if (Ur(f, n))
    return Ge(f, n);
  let p = d[n];
  if (p === void 0 && u !== void 0 && (p = u(n)), p === void 0) {
    const h = a(c, l);
    if (h === void 0) {
      const b = a(
        "importHook",
        "importNowHook"
      );
      throw Me(
        oe`${Sr(b)} needed to load module ${U(
          n
        )} in compartment ${U(r.name)}`
      );
    }
    p = h(n), xt(e, p) || (p = yield p);
  }
  if (typeof p == "string")
    throw Me(
      oe`Cannot map module ${U(n)} to ${U(
        p
      )} in parent compartment, use {source} module descriptor`,
      _
    );
  if (Se(p)) {
    let h = j(e, p);
    if (h !== void 0 && (p = h), p.namespace !== void 0) {
      if (typeof p.namespace == "string") {
        const {
          compartment: S = m,
          namespace: C
        } = p;
        if (!Se(S) || !xt(t, S))
          throw Me(
            oe`Invalid compartment in module descriptor for specifier ${U(n)} in compartment ${U(r.name)}`
          );
        const x = yield kt(
          t,
          e,
          S,
          C,
          o,
          a,
          i
        );
        return le(f, n, x), x;
      }
      if (Se(p.namespace)) {
        const { namespace: S } = p;
        if (h = j(e, S), h !== void 0)
          p = h;
        else {
          const C = At(S), D = Dt(
            t,
            e,
            r,
            n,
            {
              imports: [],
              exports: C,
              execute(B) {
                for (const L of C)
                  B[L] = S[L];
              }
            },
            o,
            a,
            i,
            void 0
          );
          return le(f, n, D), D;
        }
      } else
        throw Me(
          oe`Invalid compartment in module descriptor for specifier ${U(n)} in compartment ${U(r.name)}`
        );
    }
    if (p.source !== void 0)
      if (typeof p.source == "string") {
        const {
          source: S,
          specifier: C = n,
          compartment: x = m,
          importMeta: M = void 0
        } = p, D = yield kt(
          t,
          e,
          x,
          S,
          o,
          a,
          i
        ), { moduleSource: B } = D, L = Dt(
          t,
          e,
          r,
          C,
          B,
          o,
          a,
          i,
          M
        );
        return le(f, n, L), L;
      } else {
        const {
          source: S,
          specifier: C = n,
          importMeta: x
        } = p, M = Dt(
          t,
          e,
          r,
          C,
          S,
          o,
          a,
          i,
          x
        );
        return le(f, n, M), M;
      }
    if (p.archive !== void 0)
      throw Me(
        oe`Unsupported archive module descriptor for specifier ${U(n)} in compartment ${U(r.name)}`
      );
    if (p.record !== void 0) {
      const {
        compartment: S = r,
        specifier: C = n,
        record: x,
        importMeta: M
      } = p, D = Dt(
        t,
        e,
        S,
        C,
        x,
        o,
        a,
        i,
        M
      );
      return le(f, n, D), le(f, C, D), D;
    }
    if (p.compartment !== void 0 && p.specifier !== void 0) {
      if (!Se(p.compartment) || !xt(t, p.compartment) || typeof p.specifier != "string")
        throw Me(
          oe`Invalid compartment in module descriptor for specifier ${U(n)} in compartment ${U(r.name)}`
        );
      const S = yield kt(
        t,
        e,
        p.compartment,
        p.specifier,
        o,
        a,
        i
      );
      return le(f, n, S), S;
    }
    const E = Dt(
      t,
      e,
      r,
      n,
      p,
      o,
      a,
      i
    );
    return le(f, n, E), E;
  } else
    throw Me(
      oe`module descriptor must be a string or object for specifier ${U(
        n
      )} in compartment ${U(r.name)}`
    );
}
const kt = (t, e, r, n, o, a, i) => {
  const { name: c } = j(
    t,
    r
  );
  let l = Ge(i, r);
  l === void 0 && (l = new Ce(), le(i, r, l));
  let d = Ge(l, n);
  return d !== void 0 || (d = a(Hi, Wi)(
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
    (u) => {
      throw Br(
        u,
        oe`${u.message}, loading ${U(n)} in compartment ${U(
          c
        )}`
      ), u;
    }
  ), le(l, n, d)), d;
}, Ji = () => {
  const t = new Rt(), e = [];
  return { enqueueJob: (o, a) => {
    En(
      t,
      Uo(o(...a), Vi, (i) => {
        Q(e, i);
      })
    );
  }, drainQueue: async () => {
    await null;
    for (const o of t)
      await o;
    return e;
  } };
}, Ps = ({ errors: t, errorPrefix: e }) => {
  if (t.length > 0) {
    const r = pe("COMPARTMENT_LOAD_ERRORS", "", ["verbose"]) === "verbose";
    throw _(
      `${e} (${t.length} underlying failures: ${Lt(
        ie(t, (n) => n.message + (r ? n.stack : "")),
        ", "
      )}`
    );
  }
}, Xi = (t, e) => e, Qi = (t, e) => t, uo = async (t, e, r, n) => {
  const { name: o } = j(
    t,
    r
  ), a = new Ce(), { enqueueJob: i, drainQueue: c } = Ji();
  i(kt, [
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
    errorPrefix: `Failed to load module ${U(n)} in package ${U(
      o
    )}`
  });
}, ec = (t, e, r, n) => {
  const { name: o } = j(
    t,
    r
  ), a = new Ce(), i = [], c = (l, d) => {
    try {
      l(...d);
    } catch (u) {
      Q(i, u);
    }
  };
  c(kt, [
    t,
    e,
    r,
    n,
    c,
    Xi,
    a
  ]), Ps({
    errors: i,
    errorPrefix: `Failed to load module ${U(n)} in package ${U(
      o
    )}`
  });
}, { quote: yt } = Y, tc = () => {
  let t = !1;
  const e = V(null, {
    // Make this appear like an ESM module namespace object.
    [Xe]: {
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
    exportsProxy: new Rr(e, {
      get(r, n, o) {
        if (!t)
          throw _(
            `Cannot get property ${yt(
              n
            )} of module exports namespace, the module has not yet begun to execute`
          );
        return oa(e, n, o);
      },
      set(r, n, o) {
        throw _(
          `Cannot set property ${yt(n)} of module exports namespace`
        );
      },
      has(r, n) {
        if (!t)
          throw _(
            `Cannot check property ${yt(
              n
            )}, the module has not yet begun to execute`
          );
        return Ao(e, n);
      },
      deleteProperty(r, n) {
        throw _(
          `Cannot delete property ${yt(n)}s of module exports namespace`
        );
      },
      ownKeys(r) {
        if (!t)
          throw _(
            "Cannot enumerate keys, the module has not yet begun to execute"
          );
        return Be(e);
      },
      getOwnPropertyDescriptor(r, n) {
        if (!t)
          throw _(
            `Cannot get own property descriptor ${yt(
              n
            )}, the module has not yet begun to execute`
          );
        return sa(e, n);
      },
      preventExtensions(r) {
        if (!t)
          throw _(
            "Cannot prevent extensions of module exports namespace, the module has not yet begun to execute"
          );
        return ia(e);
      },
      isExtensible() {
        if (!t)
          throw _(
            "Cannot check extensibility of module exports namespace, the module has not yet begun to execute"
          );
        return aa(e);
      },
      getPrototypeOf(r) {
        return null;
      },
      setPrototypeOf(r, n) {
        throw _("Cannot set prototype of module exports namespace");
      },
      defineProperty(r, n, o) {
        throw _(
          `Cannot define property ${yt(n)} of module exports namespace`
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
}, Fn = (t, e, r, n) => {
  const { deferredExports: o } = e;
  if (!Ur(o, n)) {
    const a = tc();
    ue(
      r,
      a.exportsProxy,
      qi(t, n)
    ), le(o, n, a);
  }
  return Ge(o, n);
}, rc = (t, e) => {
  const { sloppyGlobalsMode: r = !1, __moduleShimLexicals__: n = void 0 } = e;
  let o;
  if (n === void 0 && !r)
    ({ safeEvaluate: o } = t);
  else {
    let { globalTransforms: a } = t;
    const { globalObject: i } = t;
    let c;
    n !== void 0 && (a = void 0, c = V(
      null,
      je(n)
    )), { safeEvaluate: o } = Rn({
      globalObject: i,
      moduleLexicals: c,
      globalTransforms: a,
      sloppyGlobalsMode: r
    });
  }
  return { safeEvaluate: o };
}, Ts = (t, e, r) => {
  if (typeof e != "string")
    throw _("first argument of evaluate() must be a string");
  const {
    transforms: n = [],
    __evadeHtmlCommentTest__: o = !1,
    __evadeImportExpressionTest__: a = !1,
    __rejectSomeDirectEvalExpressions__: i = !0
    // Note default on
  } = r, c = [...n];
  o === !0 && Q(c, gs), a === !0 && Q(c, vs), i === !0 && Q(c, bs);
  const { safeEvaluate: l } = rc(
    t,
    r
  );
  return l(e, {
    localTransforms: c
  });
}, { quote: fr } = Y, nc = (t, e, r, n, o, a) => {
  const { exportsProxy: i, exportsTarget: c, activate: l } = Fn(
    r,
    j(t, r),
    n,
    o
  ), d = V(null);
  if (e.exports) {
    if (!Et(e.exports) || ua(e.exports, (f) => typeof f != "string"))
      throw _(
        `SES virtual module source "exports" property must be an array of strings for module ${o}`
      );
    dt(e.exports, (f) => {
      let m = c[f];
      const p = [];
      F(c, f, {
        get: () => m,
        set: (E) => {
          m = E;
          for (const S of p)
            S(E);
        },
        enumerable: !0,
        configurable: !1
      }), d[f] = (E) => {
        Q(p, E), E(m);
      };
    }), d["*"] = (f) => {
      f(c);
    };
  }
  const u = {
    activated: !1
  };
  return y({
    notifiers: d,
    exportsProxy: i,
    execute() {
      if (Ao(u, "errorFromExecute"))
        throw u.errorFromExecute;
      if (!u.activated) {
        l(), u.activated = !0;
        try {
          e.execute(c, r, a);
        } catch (f) {
          throw u.errorFromExecute = f, f;
        }
      }
    }
  });
}, oc = (t, e, r, n) => {
  const {
    compartment: o,
    moduleSpecifier: a,
    moduleSource: i,
    importMeta: c
  } = r, {
    reexports: l = [],
    __syncModuleProgram__: d,
    __fixedExportMap__: u = {},
    __liveExportMap__: f = {},
    __reexportMap__: m = {},
    __needsImportMeta__: p = !1,
    __syncModuleFunctor__: h
  } = i, b = j(t, o), { __shimTransforms__: E, importMetaHook: S } = b, { exportsProxy: C, exportsTarget: x, activate: M } = Fn(
    o,
    b,
    e,
    a
  ), D = V(null), B = V(null), L = V(null), xe = V(null), ye = V(null);
  c && Or(ye, c), p && S && S(a, ye);
  const He = V(null), ot = V(null);
  dt(de(u), ([_e, [H]]) => {
    let W = He[H];
    if (!W) {
      let te, re = !0, fe = [];
      const J = () => {
        if (re)
          throw zt(`binding ${fr(H)} not yet initialized`);
        return te;
      }, ke = y((Pe) => {
        if (!re)
          throw _(
            `Internal: binding ${fr(H)} already initialized`
          );
        te = Pe;
        const Bn = fe;
        fe = null, re = !1;
        for (const Te of Bn || [])
          Te(Pe);
        return Pe;
      });
      W = {
        get: J,
        notify: (Pe) => {
          Pe !== ke && (re ? Q(fe || [], Pe) : Pe(te));
        }
      }, He[H] = W, L[H] = ke;
    }
    D[_e] = {
      get: W.get,
      set: void 0,
      enumerable: !0,
      configurable: !1
    }, ot[_e] = W.notify;
  }), dt(
    de(f),
    ([_e, [H, W]]) => {
      let te = He[H];
      if (!te) {
        let re, fe = !0;
        const J = [], ke = () => {
          if (fe)
            throw zt(
              `binding ${fr(_e)} not yet initialized`
            );
          return re;
        }, gt = y((Te) => {
          re = Te, fe = !1;
          for (const Hr of J)
            Hr(Te);
        }), Pe = (Te) => {
          if (fe)
            throw zt(`binding ${fr(H)} not yet initialized`);
          re = Te;
          for (const Hr of J)
            Hr(Te);
        };
        te = {
          get: ke,
          notify: (Te) => {
            Te !== gt && (Q(J, Te), fe || Te(re));
          }
        }, He[H] = te, W && F(B, H, {
          get: ke,
          set: Pe,
          enumerable: !0,
          configurable: !1
        }), xe[H] = gt;
      }
      D[_e] = {
        get: te.get,
        set: void 0,
        enumerable: !0,
        configurable: !1
      }, ot[_e] = te.notify;
    }
  );
  const We = (_e) => {
    _e(x);
  };
  ot["*"] = We;
  function lr(_e) {
    const H = V(null);
    H.default = !1;
    for (const [W, te] of _e) {
      const re = Ge(n, W);
      re.execute();
      const { notifiers: fe } = re;
      for (const [J, ke] of te) {
        const gt = fe[J];
        if (!gt)
          throw or(
            `The requested module '${W}' does not provide an export named '${J}'`
          );
        for (const Pe of ke)
          gt(Pe);
      }
      if (Dr(l, W))
        for (const [J, ke] of de(
          fe
        ))
          H[J] === void 0 ? H[J] = ke : H[J] = !1;
      if (m[W])
        for (const [J, ke] of m[W])
          H[ke] = fe[J];
    }
    for (const [W, te] of de(H))
      if (!ot[W] && te !== !1) {
        ot[W] = te;
        let re;
        te((J) => re = J), D[W] = {
          get() {
            return re;
          },
          set: void 0,
          enumerable: !0,
          configurable: !1
        };
      }
    dt(
      Oo(Eo(D)),
      (W) => F(x, W, D[W])
    ), y(x), M();
  }
  let Ft;
  h !== void 0 ? Ft = h : Ft = Ts(b, d, {
    globalObject: o.globalThis,
    transforms: E,
    __moduleShimLexicals__: B
  });
  let Zn = !1, zn;
  function Bs() {
    if (Ft) {
      const _e = Ft;
      Ft = null;
      try {
        _e(
          y({
            imports: y(lr),
            onceVar: y(L),
            liveVar: y(xe),
            importMeta: ye
          })
        );
      } catch (H) {
        Zn = !0, zn = H;
      }
    }
    if (Zn)
      throw zn;
  }
  return y({
    notifiers: ot,
    exportsProxy: C,
    execute: Bs
  });
}, { Fail: ut, quote: K } = Y, As = (t, e, r, n) => {
  const { name: o, moduleRecords: a } = j(
    t,
    r
  ), i = Ge(a, n);
  if (i === void 0)
    throw zt(
      `Missing link to module ${K(n)} from compartment ${K(
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
  Se(r) || ut`Property '__fixedExportMap__' of a precompiled module source must be an object, got ${K(
    r
  )}, for module ${K(e)}`, Se(n) || ut`Property '__liveExportMap__' of a precompiled module source must be an object, got ${K(
    n
  )}, for module ${K(e)}`;
}
function ic(t) {
  return typeof t.execute == "function";
}
function cc(t, e) {
  const { exports: r } = t;
  Et(r) || ut`Property 'exports' of a third-party module source must be an array, got ${K(
    r
  )}, for module ${K(e)}`;
}
function lc(t, e) {
  Se(t) || ut`Module sources must be of type object, got ${K(
    t
  )}, for module ${K(e)}`;
  const { imports: r, exports: n, reexports: o = [] } = t;
  Et(r) || ut`Property 'imports' of a module source must be an array, got ${K(
    r
  )}, for module ${K(e)}`, Et(n) || ut`Property 'exports' of a precompiled module source must be an array, got ${K(
    n
  )}, for module ${K(e)}`, Et(o) || ut`Property 'reexports' of a precompiled module source must be an array if present, got ${K(
    o
  )}, for module ${K(e)}`;
}
const uc = (t, e, r) => {
  const { compartment: n, moduleSpecifier: o, resolvedImports: a, moduleSource: i } = r, { instances: c } = j(t, n);
  if (Ur(c, o))
    return Ge(c, o);
  lc(i, o);
  const l = new Ce();
  let d;
  if (sc(i))
    ac(i, o), d = oc(
      t,
      e,
      r,
      l
    );
  else if (ic(i))
    cc(i, o), d = nc(
      t,
      i,
      n,
      e,
      o,
      a
    );
  else
    throw _(
      `importHook must provide a module source, got ${K(i)}`
    );
  le(c, o, d);
  for (const [u, f] of de(a)) {
    const m = As(
      t,
      e,
      n,
      f
    );
    le(l, u, m);
  }
  return d;
}, Ut = new Ue(), Oe = new Ue(), Dn = function(e = {}, r = {}, n = {}) {
  throw _(
    "Compartment.prototype.constructor is not a valid constructor."
  );
}, fo = (t, e) => {
  const { execute: r, exportsProxy: n } = As(
    Oe,
    Ut,
    t,
    e
  );
  return r(), n;
}, Un = {
  constructor: Dn,
  get globalThis() {
    return j(Oe, this).globalObject;
  },
  get name() {
    return j(Oe, this).name;
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
    const r = j(Oe, this);
    return Ts(r, t, e);
  },
  module(t) {
    if (typeof t != "string")
      throw _("first argument of module() must be a string");
    const { exportsProxy: e } = Fn(
      this,
      j(Oe, this),
      Ut,
      t
    );
    return e;
  },
  async import(t) {
    const { noNamespaceBox: e } = j(Oe, this);
    if (typeof t != "string")
      throw _("first argument of import() must be a string");
    return Uo(
      uo(Oe, Ut, this, t),
      () => {
        const r = fo(
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
    return uo(Oe, Ut, this, t);
  },
  importNow(t) {
    if (typeof t != "string")
      throw _("first argument of importNow() must be a string");
    return ec(Oe, Ut, this, t), fo(
      /** @type {Compartment} */
      this,
      t
    );
  }
};
Z(Un, {
  [Xe]: {
    value: "Compartment",
    writable: !1,
    enumerable: !1,
    configurable: !0
  }
});
Z(Dn, {
  prototype: { value: Un }
});
const dc = (...t) => {
  if (t.length === 0)
    return {};
  if (t.length === 1 && typeof t[0] == "object" && t[0] !== null && "__options__" in t[0]) {
    const { __options__: e, ...r } = t[0];
    return assert(
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
    return Jn(
      n.modules,
      void 0,
      "Compartment constructor must receive either a module map argument or modules option, not both"
    ), Jn(
      n.globals,
      void 0,
      "Compartment constructor must receive either globals argument or option, not both"
    ), {
      ...n,
      globals: e,
      modules: r
    };
  }
}, fn = (t, e, r, n = void 0) => {
  function o(...a) {
    if (new.target === void 0)
      throw _(
        "Class constructor Compartment cannot be invoked without 'new'"
      );
    const {
      name: i = "<unknown>",
      transforms: c = [],
      __shimTransforms__: l = [],
      globals: d = {},
      modules: u = {},
      resolveHook: f,
      importHook: m,
      importNowHook: p,
      moduleMapHook: h,
      importMetaHook: b,
      __noNamespaceBox__: E = !1
    } = dc(...a), S = [...c, ...l], C = { __proto__: null, ...d }, x = { __proto__: null, ...u }, M = new Ce(), D = new Ce(), B = new Ce(), L = {};
    li(L), cs(L);
    const { safeEvaluate: xe } = Rn({
      globalObject: L,
      globalTransforms: S,
      sloppyGlobalsMode: !1
    });
    ls(L, {
      intrinsics: e,
      newGlobalPropertyNames: rs,
      makeCompartmentConstructor: t,
      parentCompartment: this,
      markVirtualizedNativeFunction: r
    }), dn(
      L,
      xe,
      r
    ), Or(L, C), ue(Oe, this, {
      name: `${i}`,
      globalTransforms: S,
      globalObject: L,
      safeEvaluate: xe,
      resolveHook: f,
      importHook: m,
      importNowHook: p,
      moduleMap: x,
      moduleMapHook: h,
      importMetaHook: b,
      moduleRecords: M,
      __shimTransforms__: l,
      deferredExports: B,
      instances: D,
      parentCompartment: n,
      noNamespaceBox: E
    });
  }
  return o.prototype = Un, o;
};
function rn(t) {
  return G(t).constructor;
}
function fc() {
  return arguments;
}
const pc = () => {
  const t = we.prototype.constructor, e = X(fc(), "callee"), r = e && e.get, n = va(new ge()), o = G(n), a = Lr[Po] && ga(/./), i = a && G(a), c = da([]), l = G(c), d = G(Hs), u = ma(new Ce()), f = G(u), m = ha(new Rt()), p = G(m), h = G(l);
  function* b() {
  }
  const E = rn(b), S = E.prototype;
  async function* C() {
  }
  const x = rn(
    C
  ), M = x.prototype, D = M.prototype, B = G(D);
  async function L() {
  }
  const xe = rn(L), ye = {
    "%InertFunction%": t,
    "%ArrayIteratorPrototype%": l,
    "%InertAsyncFunction%": xe,
    "%AsyncGenerator%": M,
    "%InertAsyncGeneratorFunction%": x,
    "%AsyncGeneratorPrototype%": D,
    "%AsyncIteratorPrototype%": B,
    "%Generator%": S,
    "%InertGeneratorFunction%": E,
    "%IteratorPrototype%": h,
    "%MapIteratorPrototype%": f,
    "%RegExpStringIteratorPrototype%": i,
    "%SetIteratorPrototype%": p,
    "%StringIteratorPrototype%": o,
    "%ThrowTypeError%": r,
    "%TypedArray%": d,
    "%InertCompartment%": Dn
  };
  return P.Iterator && (ye["%IteratorHelperPrototype%"] = G(
    // eslint-disable-next-line @endo/no-polymorphic-call
    P.Iterator.from([]).take(0)
  ), ye["%WrapForValidIteratorPrototype%"] = G(
    // eslint-disable-next-line @endo/no-polymorphic-call
    P.Iterator.from({ next() {
    } })
  )), P.AsyncIterator && (ye["%AsyncIteratorHelperPrototype%"] = G(
    // eslint-disable-next-line @endo/no-polymorphic-call
    P.AsyncIterator.from([]).take(0)
  ), ye["%WrapForValidAsyncIteratorPrototype%"] = G(
    // eslint-disable-next-line @endo/no-polymorphic-call
    P.AsyncIterator.from({ next() {
    } })
  )), ye;
}, Is = (t, e) => {
  if (e !== "safe" && e !== "unsafe")
    throw _(`unrecognized fakeHardenOption ${e}`);
  if (e === "safe" || (Object.isExtensible = () => !1, Object.isFrozen = () => !0, Object.isSealed = () => !0, Reflect.isExtensible = () => !1, t.isFake))
    return t;
  const r = (n) => n;
  return r.isFake = !0, y(r);
};
y(Is);
const mc = () => {
  const t = St, e = t.prototype, r = Sa(St, void 0);
  Z(e, {
    constructor: {
      value: r
      // leave other `constructor` attributes as is
    }
  });
  const n = de(
    je(t)
  ), o = ht(
    ie(n, ([a, i]) => [
      a,
      { ...i, configurable: !0 }
    ])
  );
  return Z(r, o), { "%SharedSymbol%": r };
}, hc = (t) => {
  try {
    return t(), !1;
  } catch {
    return !0;
  }
}, po = (t, e, r) => {
  if (t === void 0)
    return !1;
  const n = X(t, e);
  if (!n || "value" in n)
    return !1;
  const { get: o, set: a } = n;
  if (typeof o != "function" || typeof a != "function" || o() !== r || se(o, t, []) !== r)
    return !1;
  const i = "Seems to be a setter", c = { __proto__: null };
  if (se(a, c, [i]), c[e] !== i)
    return !1;
  const l = { __proto__: t };
  return se(a, l, [i]), l[e] !== i || !hc(() => se(a, t, [r])) || "originalValue" in o || n.configurable === !1 ? !1 : (F(t, e, {
    value: r,
    writable: !0,
    enumerable: n.enumerable,
    configurable: !0
  }), !0);
}, gc = (t) => {
  po(
    t["%IteratorPrototype%"],
    "constructor",
    t.Iterator
  ), po(
    t["%IteratorPrototype%"],
    Xe,
    "Iterator"
  );
}, { Fail: mo, details: ho, quote: go } = Y;
let pr, mr;
const yc = Ba(), _c = () => {
  let t = !1;
  try {
    t = we(
      "eval",
      "SES_changed",
      `        eval("SES_changed = true");
        return SES_changed;
      `
    )(jo, !1), t || delete P.SES_changed;
  } catch {
    t = !0;
  }
  if (!t)
    throw _(
      "SES cannot initialize unless 'eval' is the original intrinsic 'eval', suitable for direct-eval (dynamically scoped eval) (SES_DIRECT_EVAL)"
    );
}, Cs = (t = {}) => {
  const {
    errorTaming: e = pe("LOCKDOWN_ERROR_TAMING", "safe"),
    errorTrapping: r = (
      /** @type {"platform" | "none" | "report" | "abort" | "exit" | undefined} */
      pe("LOCKDOWN_ERROR_TRAPPING", "platform")
    ),
    unhandledRejectionTrapping: n = (
      /** @type {"none" | "report" | undefined} */
      pe("LOCKDOWN_UNHANDLED_REJECTION_TRAPPING", "report")
    ),
    regExpTaming: o = pe("LOCKDOWN_REGEXP_TAMING", "safe"),
    localeTaming: a = pe("LOCKDOWN_LOCALE_TAMING", "safe"),
    consoleTaming: i = (
      /** @type {'unsafe' | 'safe' | undefined} */
      pe("LOCKDOWN_CONSOLE_TAMING", "safe")
    ),
    overrideTaming: c = pe("LOCKDOWN_OVERRIDE_TAMING", "moderate"),
    stackFiltering: l = pe("LOCKDOWN_STACK_FILTERING", "concise"),
    domainTaming: d = pe("LOCKDOWN_DOMAIN_TAMING", "safe"),
    evalTaming: u = pe("LOCKDOWN_EVAL_TAMING", "safeEval"),
    overrideDebug: f = Qe(
      An(pe("LOCKDOWN_OVERRIDE_DEBUG", ""), ","),
      /** @param {string} debugName */
      (We) => We !== ""
    ),
    __hardenTaming__: m = pe("LOCKDOWN_HARDEN_TAMING", "safe"),
    dateTaming: p = "safe",
    // deprecated
    mathTaming: h = "safe",
    // deprecated
    ...b
  } = t;
  u === "unsafeEval" || u === "safeEval" || u === "noEval" || mo`lockdown(): non supported option evalTaming: ${go(u)}`;
  const E = Be(b);
  if (E.length === 0 || mo`lockdown(): non supported option ${go(E)}`, pr === void 0 || // eslint-disable-next-line @endo/no-polymorphic-call
  Y.fail(
    ho`Already locked down at ${pr} (SES_ALREADY_LOCKED_DOWN)`,
    _
  ), pr = _("Prior lockdown (SES_ALREADY_LOCKED_DOWN)"), pr.stack, _c(), P.Function.prototype.constructor !== P.Function && // @ts-ignore harden is absent on globalThis type def.
  typeof P.harden == "function" && // @ts-ignore lockdown is absent on globalThis type def.
  typeof P.lockdown == "function" && P.Date.prototype.constructor !== P.Date && typeof P.Date.now == "function" && // @ts-ignore does not recognize that Date constructor is a special
  // Function.
  // eslint-disable-next-line @endo/no-polymorphic-call
  Mr(P.Date.prototype.constructor.now(), NaN))
    throw _(
      "Already locked down but not by this SES instance (SES_MULTIPLE_INSTANCES)"
    );
  Ei(d);
  const C = Es(), { addIntrinsics: x, completePrototypes: M, finalIntrinsics: D } = ss(), B = Is(yc, m);
  x({ harden: B }), x(Ya()), x(Ja(p)), x(Gi(e, l)), x(Xa(h)), x(Qa(o)), x(mc()), x(pc()), M();
  const L = D(), xe = { __proto__: null };
  typeof P.Buffer == "function" && (xe.Buffer = P.Buffer);
  let ye;
  e === "safe" && (ye = L["%InitialGetStackString%"]);
  const He = Ai(
    i,
    r,
    n,
    ye
  );
  if (P.console = /** @type {Console} */
  He.console, typeof /** @type {any} */
  He.console._times == "object" && (xe.SafeMap = G(
    // eslint-disable-next-line no-underscore-dangle
    /** @type {any} */
    He.console._times
  )), (e === "unsafe" || e === "unsafe-debug") && P.assert === Y && (P.assert = Gr(void 0, !0)), ai(L, a), gc(L), Ka(L, C), cs(P), ls(P, {
    intrinsics: L,
    newGlobalPropertyNames: Xn,
    makeCompartmentConstructor: fn,
    markVirtualizedNativeFunction: C
  }), u === "noEval")
    dn(
      P,
      xa,
      C
    );
  else if (u === "safeEval") {
    const { safeEvaluate: We } = Rn({ globalObject: P });
    dn(
      P,
      We,
      C
    );
  }
  return () => {
    mr === void 0 || // eslint-disable-next-line @endo/no-polymorphic-call
    Y.fail(
      ho`Already locked down at ${mr} (SES_ALREADY_LOCKED_DOWN)`,
      _
    ), mr = _(
      "Prior lockdown (SES_ALREADY_LOCKED_DOWN)"
    ), mr.stack, ri(L, c, f);
    const We = {
      intrinsics: L,
      hostIntrinsics: xe,
      globals: {
        // Harden evaluators
        Function: P.Function,
        eval: P.eval,
        // @ts-ignore Compartment does exist on globalThis
        Compartment: P.Compartment,
        // Harden Symbol
        Symbol: P.Symbol
      }
    };
    for (const lr of At(Xn))
      We.globals[lr] = P[lr];
    return B(We), B;
  };
};
P.lockdown = (t) => {
  const e = Cs(t);
  P.harden = e();
};
P.repairIntrinsics = (t) => {
  const e = Cs(t);
  P.hardenIntrinsics = () => {
    P.harden = e();
  };
};
const vc = Es();
P.Compartment = fn(
  fn,
  qa(P),
  vc
);
P.assert = Y;
const bc = ks(Er), wc = ta(
  "MAKE_CAUSAL_CONSOLE_FROM_LOGGER_KEY_FOR_SES_AVA"
);
P[wc] = bc;
const Sc = (t, e) => {
  let r = { x: 0, y: 0 }, n = { x: 0, y: 0 }, o = { x: 0, y: 0 };
  const a = (l) => {
    const { clientX: d, clientY: u } = l, f = d - o.x + n.x, m = u - o.y + n.y;
    r = { x: f, y: m }, t.style.transform = `translate(${f}px, ${m}px)`, e == null || e();
  }, i = () => {
    document.removeEventListener("mousemove", a), document.removeEventListener("mouseup", i);
  }, c = (l) => {
    o = { x: l.clientX, y: l.clientY }, n = { x: r.x, y: r.y }, document.addEventListener("mousemove", a), document.addEventListener("mouseup", i);
  };
  return t.addEventListener("mousedown", c), i;
}, Ec = ":host{--spacing-4: .25rem;--spacing-8: calc(var(--spacing-4) * 2);--spacing-12: calc(var(--spacing-4) * 3);--spacing-16: calc(var(--spacing-4) * 4);--spacing-20: calc(var(--spacing-4) * 5);--spacing-24: calc(var(--spacing-4) * 6);--spacing-28: calc(var(--spacing-4) * 7);--spacing-32: calc(var(--spacing-4) * 8);--spacing-36: calc(var(--spacing-4) * 9);--spacing-40: calc(var(--spacing-4) * 10);--font-weight-regular: 400;--font-weight-bold: 500;--font-line-height-s: 1.2;--font-line-height-m: 1.4;--font-line-height-l: 1.5;--font-size-s: 12px;--font-size-m: 14px;--font-size-l: 16px}[data-theme]{background-color:var(--color-background-primary);color:var(--color-foreground-secondary)}.wrapper{box-sizing:border-box;display:flex;flex-direction:column;position:fixed;inset-block-start:var(--modal-block-start);inset-inline-end:var(--modal-inline-end);z-index:1000;padding:25px;border-radius:15px;border:2px solid var(--color-background-quaternary);box-shadow:0 0 10px #0000004d}.header{align-items:center;display:flex;justify-content:space-between;border-block-end:2px solid var(--color-background-quaternary);padding-block-end:var(--spacing-4)}button{background:transparent;border:0;cursor:pointer;padding:0}h1{font-size:var(--font-size-s);font-weight:var(--font-weight-bold);margin:0;margin-inline-end:var(--spacing-4);-webkit-user-select:none;user-select:none}iframe{border:none;inline-size:100%;block-size:100%}", xc = `
<svg width="16"  height="16"xmlns="http://www.w3.org/2000/svg" fill="none"><g class="fills"><rect rx="0" ry="0" width="16" height="16" class="frame-background"/></g><g class="frame-children"><path d="M11.997 3.997 8 8l-3.997 4.003m-.006-8L8 8l4.003 3.997" class="fills"/><g class="strokes"><path d="M11.997 3.997 8 8l-3.997 4.003m-.006-8L8 8l4.003 3.997" style="fill: none; stroke-width: 1; stroke: rgb(143, 157, 163); stroke-opacity: 1; stroke-linecap: round;" class="stroke-shape"/></g></g></svg>`;
var me, nr;
class kc extends HTMLElement {
  constructor() {
    super();
    Wr(this, me, null);
    Wr(this, nr, null);
    this.attachShadow({ mode: "open" });
  }
  setTheme(r) {
    Ae(this, me) && Ae(this, me).setAttribute("data-theme", r);
  }
  disconnectedCallback() {
    var r;
    (r = Ae(this, nr)) == null || r.call(this);
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
    qr(this, me, document.createElement("div")), Ae(this, me).classList.add("wrapper"), Ae(this, me).style.inlineSize = `${o}px`, Ae(this, me).style.blockSize = `${a}px`, qr(this, nr, Sc(Ae(this, me), () => {
      this.calculateZIndex();
    }));
    const i = document.createElement("div");
    i.classList.add("header");
    const c = document.createElement("h1");
    c.textContent = r, i.appendChild(c);
    const l = document.createElement("button");
    l.setAttribute("type", "button"), l.innerHTML = `<div class="close">${xc}</div>`, l.addEventListener("click", () => {
      this.shadowRoot && this.shadowRoot.dispatchEvent(
        new CustomEvent("close", {
          composed: !0,
          bubbles: !0
        })
      );
    }), i.appendChild(l);
    const d = document.createElement("iframe");
    d.src = n, d.allow = "", d.sandbox.add(
      "allow-scripts",
      "allow-forms",
      "allow-modals",
      "allow-popups",
      "allow-popups-to-escape-sandbox",
      "allow-storage-access-by-user-activation"
    ), d.addEventListener("load", () => {
      var f;
      (f = this.shadowRoot) == null || f.dispatchEvent(
        new CustomEvent("load", {
          composed: !0,
          bubbles: !0
        })
      );
    }), this.addEventListener("message", (f) => {
      d.contentWindow && d.contentWindow.postMessage(f.detail, "*");
    }), this.shadowRoot.appendChild(Ae(this, me)), Ae(this, me).appendChild(i), Ae(this, me).appendChild(d);
    const u = document.createElement("style");
    u.textContent = Ec, this.shadowRoot.appendChild(u), this.calculateZIndex();
  }
}
me = new WeakMap(), nr = new WeakMap();
customElements.define("plugin-modal", kc);
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
var pn;
(function(t) {
  t.mergeShapes = (e, r) => ({
    ...e,
    ...r
    // second overwrites first
  });
})(pn || (pn = {}));
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
]), Ye = (t) => {
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
]), Pc = (t) => JSON.stringify(t, null, 2).replace(/"([^"]+)":/g, "$1:");
class he extends Error {
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
            const d = i.path[l];
            l === i.path.length - 1 ? (c[d] = c[d] || { _errors: [] }, c[d]._errors.push(r(i))) : c[d] = c[d] || { _errors: [] }, c = c[d], l++;
          }
        }
    };
    return o(this), n;
  }
  static assert(e) {
    if (!(e instanceof he))
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
he.create = (t) => new he(t);
const Ct = (t, e) => {
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
let $s = Ct;
function Tc(t) {
  $s = t;
}
function Pr() {
  return $s;
}
const Tr = (t) => {
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
  const l = n.filter((d) => !!d).slice().reverse();
  for (const d of l)
    c = d(i, { data: e, defaultError: c }).message;
  return {
    ...o,
    path: a,
    message: c
  };
}, Ac = [];
function v(t, e) {
  const r = Pr(), n = Tr({
    issueData: e,
    data: t.data,
    path: t.path,
    errorMaps: [
      t.common.contextualErrorMap,
      t.schemaErrorMap,
      r,
      r === Ct ? void 0 : Ct
      // then global default map
    ].filter((o) => !!o)
  });
  t.common.issues.push(n);
}
class ee {
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
    return ee.mergeObjectSync(e, n);
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
}), bt = (t) => ({ status: "dirty", value: t }), ce = (t) => ({ status: "valid", value: t }), mn = (t) => t.status === "aborted", hn = (t) => t.status === "dirty", Bt = (t) => t.status === "valid", Gt = (t) => typeof Promise < "u" && t instanceof Promise;
function Ar(t, e, r, n) {
  if (typeof e == "function" ? t !== e || !n : !e.has(t)) throw new TypeError("Cannot read private member from an object whose class did not declare it");
  return e.get(t);
}
function Ns(t, e, r, n, o) {
  if (typeof e == "function" ? t !== e || !o : !e.has(t)) throw new TypeError("Cannot write private member to an object whose class did not declare it");
  return e.set(t, r), r;
}
var k;
(function(t) {
  t.errToObj = (e) => typeof e == "string" ? { message: e } : e || {}, t.toString = (e) => typeof e == "string" ? e : e == null ? void 0 : e.message;
})(k || (k = {}));
var jt, Zt;
class Fe {
  constructor(e, r, n, o) {
    this._cachedPath = [], this.parent = e, this.data = r, this._path = n, this._key = o;
  }
  get path() {
    return this._cachedPath.length || (this._key instanceof Array ? this._cachedPath.push(...this._path, ...this._key) : this._cachedPath.push(...this._path, this._key)), this._cachedPath;
  }
}
const yo = (t, e) => {
  if (Bt(e))
    return { success: !0, data: e.value };
  if (!t.common.issues.length)
    throw new Error("Validation failed but no issues detected.");
  return {
    success: !1,
    get error() {
      if (this._error)
        return this._error;
      const r = new he(t.common.issues);
      return this._error = r, this._error;
    }
  };
};
function $(t) {
  if (!t)
    return {};
  const { errorMap: e, invalid_type_error: r, required_error: n, description: o } = t;
  if (e && (r || n))
    throw new Error(`Can't use "invalid_type_error" or "required_error" in conjunction with custom error map.`);
  return e ? { errorMap: e, description: o } : { errorMap: (i, c) => {
    var l, d;
    const { message: u } = t;
    return i.code === "invalid_enum_value" ? { message: u ?? c.defaultError } : typeof c.data > "u" ? { message: (l = u ?? n) !== null && l !== void 0 ? l : c.defaultError } : i.code !== "invalid_type" ? { message: c.defaultError } : { message: (d = u ?? r) !== null && d !== void 0 ? d : c.defaultError };
  }, description: o };
}
class N {
  constructor(e) {
    this.spa = this.safeParseAsync, this._def = e, this.parse = this.parse.bind(this), this.safeParse = this.safeParse.bind(this), this.parseAsync = this.parseAsync.bind(this), this.safeParseAsync = this.safeParseAsync.bind(this), this.spa = this.spa.bind(this), this.refine = this.refine.bind(this), this.refinement = this.refinement.bind(this), this.superRefine = this.superRefine.bind(this), this.optional = this.optional.bind(this), this.nullable = this.nullable.bind(this), this.nullish = this.nullish.bind(this), this.array = this.array.bind(this), this.promise = this.promise.bind(this), this.or = this.or.bind(this), this.and = this.and.bind(this), this.transform = this.transform.bind(this), this.brand = this.brand.bind(this), this.default = this.default.bind(this), this.catch = this.catch.bind(this), this.describe = this.describe.bind(this), this.pipe = this.pipe.bind(this), this.readonly = this.readonly.bind(this), this.isNullable = this.isNullable.bind(this), this.isOptional = this.isOptional.bind(this);
  }
  get description() {
    return this._def.description;
  }
  _getType(e) {
    return Ye(e.data);
  }
  _getOrReturnCtx(e, r) {
    return r || {
      common: e.parent.common,
      data: e.data,
      parsedType: Ye(e.data),
      schemaErrorMap: this._def.errorMap,
      path: e.path,
      parent: e.parent
    };
  }
  _processInputParams(e) {
    return {
      status: new ee(),
      ctx: {
        common: e.parent.common,
        data: e.data,
        parsedType: Ye(e.data),
        schemaErrorMap: this._def.errorMap,
        path: e.path,
        parent: e.parent
      }
    };
  }
  _parseSync(e) {
    const r = this._parse(e);
    if (Gt(r))
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
      parsedType: Ye(e)
    }, a = this._parseSync({ data: e, path: o.path, parent: o });
    return yo(o, a);
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
      parsedType: Ye(e)
    }, o = this._parse({ data: e, path: n.path, parent: n }), a = await (Gt(o) ? o : Promise.resolve(o));
    return yo(n, a);
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
    return new Ne({
      schema: this,
      typeName: A.ZodEffects,
      effect: { type: "refinement", refinement: e }
    });
  }
  superRefine(e) {
    return this._refinement(e);
  }
  optional() {
    return Le.create(this, this._def);
  }
  nullable() {
    return nt.create(this, this._def);
  }
  nullish() {
    return this.nullable().optional();
  }
  array() {
    return $e.create(this, this._def);
  }
  promise() {
    return Nt.create(this, this._def);
  }
  or(e) {
    return qt.create([this, e], this._def);
  }
  and(e) {
    return Kt.create(this, e, this._def);
  }
  transform(e) {
    return new Ne({
      ...$(this._def),
      schema: this,
      typeName: A.ZodEffects,
      effect: { type: "transform", transform: e }
    });
  }
  default(e) {
    const r = typeof e == "function" ? e : () => e;
    return new er({
      ...$(this._def),
      innerType: this,
      defaultValue: r,
      typeName: A.ZodDefault
    });
  }
  brand() {
    return new jn({
      typeName: A.ZodBranded,
      type: this,
      ...$(this._def)
    });
  }
  catch(e) {
    const r = typeof e == "function" ? e : () => e;
    return new tr({
      ...$(this._def),
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
    return cr.create(this, e);
  }
  readonly() {
    return rr.create(this);
  }
  isOptional() {
    return this.safeParse(void 0).success;
  }
  isNullable() {
    return this.safeParse(null).success;
  }
}
const Ic = /^c[^\s-]{8,}$/i, Cc = /^[0-9a-z]+$/, $c = /^[0-9A-HJKMNP-TV-Z]{26}$/, Nc = /^[0-9a-fA-F]{8}\b-[0-9a-fA-F]{4}\b-[0-9a-fA-F]{4}\b-[0-9a-fA-F]{4}\b-[0-9a-fA-F]{12}$/i, Rc = /^[a-z0-9_-]{21}$/i, Oc = /^[-+]?P(?!$)(?:(?:[-+]?\d+Y)|(?:[-+]?\d+[.,]\d+Y$))?(?:(?:[-+]?\d+M)|(?:[-+]?\d+[.,]\d+M$))?(?:(?:[-+]?\d+W)|(?:[-+]?\d+[.,]\d+W$))?(?:(?:[-+]?\d+D)|(?:[-+]?\d+[.,]\d+D$))?(?:T(?=[\d+-])(?:(?:[-+]?\d+H)|(?:[-+]?\d+[.,]\d+H$))?(?:(?:[-+]?\d+M)|(?:[-+]?\d+[.,]\d+M$))?(?:[-+]?\d+(?:[.,]\d+)?S)?)??$/, Mc = /^(?!\.)(?!.*\.\.)([A-Z0-9_'+\-\.]*)[A-Z0-9_+-]@([A-Z0-9][A-Z0-9\-]*\.)+[A-Z]{2,}$/i, Lc = "^(\\p{Extended_Pictographic}|\\p{Emoji_Component})+$";
let nn;
const Fc = /^(?:(?:25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9][0-9]|[0-9])\.){3}(?:25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9][0-9]|[0-9])$/, Dc = /^(([a-f0-9]{1,4}:){7}|::([a-f0-9]{1,4}:){0,6}|([a-f0-9]{1,4}:){1}:([a-f0-9]{1,4}:){0,5}|([a-f0-9]{1,4}:){2}:([a-f0-9]{1,4}:){0,4}|([a-f0-9]{1,4}:){3}:([a-f0-9]{1,4}:){0,3}|([a-f0-9]{1,4}:){4}:([a-f0-9]{1,4}:){0,2}|([a-f0-9]{1,4}:){5}:([a-f0-9]{1,4}:){0,1})([a-f0-9]{1,4}|(((25[0-5])|(2[0-4][0-9])|(1[0-9]{2})|([0-9]{1,2}))\.){3}((25[0-5])|(2[0-4][0-9])|(1[0-9]{2})|([0-9]{1,2})))$/, Uc = /^([0-9a-zA-Z+/]{4})*(([0-9a-zA-Z+/]{2}==)|([0-9a-zA-Z+/]{3}=))?$/, Rs = "((\\d\\d[2468][048]|\\d\\d[13579][26]|\\d\\d0[48]|[02468][048]00|[13579][26]00)-02-29|\\d{4}-((0[13578]|1[02])-(0[1-9]|[12]\\d|3[01])|(0[469]|11)-(0[1-9]|[12]\\d|30)|(02)-(0[1-9]|1\\d|2[0-8])))", jc = new RegExp(`^${Rs}$`);
function Os(t) {
  let e = "([01]\\d|2[0-3]):[0-5]\\d:[0-5]\\d";
  return t.precision ? e = `${e}\\.\\d{${t.precision}}` : t.precision == null && (e = `${e}(\\.\\d+)?`), e;
}
function Zc(t) {
  return new RegExp(`^${Os(t)}$`);
}
function Ms(t) {
  let e = `${Rs}T${Os(t)}`;
  const r = [];
  return r.push(t.local ? "Z?" : "Z"), t.offset && r.push("([+-]\\d{2}:?\\d{2})"), e = `${e}(${r.join("|")})`, new RegExp(`^${e}$`);
}
function zc(t, e) {
  return !!((e === "v4" || !e) && Fc.test(t) || (e === "v6" || !e) && Dc.test(t));
}
class Ie extends N {
  _parse(e) {
    if (this._def.coerce && (e.data = String(e.data)), this._getType(e) !== w.string) {
      const a = this._getOrReturnCtx(e);
      return v(a, {
        code: g.invalid_type,
        expected: w.string,
        received: a.parsedType
      }), I;
    }
    const n = new ee();
    let o;
    for (const a of this._def.checks)
      if (a.kind === "min")
        e.data.length < a.value && (o = this._getOrReturnCtx(e, o), v(o, {
          code: g.too_small,
          minimum: a.value,
          type: "string",
          inclusive: !0,
          exact: !1,
          message: a.message
        }), n.dirty());
      else if (a.kind === "max")
        e.data.length > a.value && (o = this._getOrReturnCtx(e, o), v(o, {
          code: g.too_big,
          maximum: a.value,
          type: "string",
          inclusive: !0,
          exact: !1,
          message: a.message
        }), n.dirty());
      else if (a.kind === "length") {
        const i = e.data.length > a.value, c = e.data.length < a.value;
        (i || c) && (o = this._getOrReturnCtx(e, o), i ? v(o, {
          code: g.too_big,
          maximum: a.value,
          type: "string",
          inclusive: !0,
          exact: !0,
          message: a.message
        }) : c && v(o, {
          code: g.too_small,
          minimum: a.value,
          type: "string",
          inclusive: !0,
          exact: !0,
          message: a.message
        }), n.dirty());
      } else if (a.kind === "email")
        Mc.test(e.data) || (o = this._getOrReturnCtx(e, o), v(o, {
          validation: "email",
          code: g.invalid_string,
          message: a.message
        }), n.dirty());
      else if (a.kind === "emoji")
        nn || (nn = new RegExp(Lc, "u")), nn.test(e.data) || (o = this._getOrReturnCtx(e, o), v(o, {
          validation: "emoji",
          code: g.invalid_string,
          message: a.message
        }), n.dirty());
      else if (a.kind === "uuid")
        Nc.test(e.data) || (o = this._getOrReturnCtx(e, o), v(o, {
          validation: "uuid",
          code: g.invalid_string,
          message: a.message
        }), n.dirty());
      else if (a.kind === "nanoid")
        Rc.test(e.data) || (o = this._getOrReturnCtx(e, o), v(o, {
          validation: "nanoid",
          code: g.invalid_string,
          message: a.message
        }), n.dirty());
      else if (a.kind === "cuid")
        Ic.test(e.data) || (o = this._getOrReturnCtx(e, o), v(o, {
          validation: "cuid",
          code: g.invalid_string,
          message: a.message
        }), n.dirty());
      else if (a.kind === "cuid2")
        Cc.test(e.data) || (o = this._getOrReturnCtx(e, o), v(o, {
          validation: "cuid2",
          code: g.invalid_string,
          message: a.message
        }), n.dirty());
      else if (a.kind === "ulid")
        $c.test(e.data) || (o = this._getOrReturnCtx(e, o), v(o, {
          validation: "ulid",
          code: g.invalid_string,
          message: a.message
        }), n.dirty());
      else if (a.kind === "url")
        try {
          new URL(e.data);
        } catch {
          o = this._getOrReturnCtx(e, o), v(o, {
            validation: "url",
            code: g.invalid_string,
            message: a.message
          }), n.dirty();
        }
      else a.kind === "regex" ? (a.regex.lastIndex = 0, a.regex.test(e.data) || (o = this._getOrReturnCtx(e, o), v(o, {
        validation: "regex",
        code: g.invalid_string,
        message: a.message
      }), n.dirty())) : a.kind === "trim" ? e.data = e.data.trim() : a.kind === "includes" ? e.data.includes(a.value, a.position) || (o = this._getOrReturnCtx(e, o), v(o, {
        code: g.invalid_string,
        validation: { includes: a.value, position: a.position },
        message: a.message
      }), n.dirty()) : a.kind === "toLowerCase" ? e.data = e.data.toLowerCase() : a.kind === "toUpperCase" ? e.data = e.data.toUpperCase() : a.kind === "startsWith" ? e.data.startsWith(a.value) || (o = this._getOrReturnCtx(e, o), v(o, {
        code: g.invalid_string,
        validation: { startsWith: a.value },
        message: a.message
      }), n.dirty()) : a.kind === "endsWith" ? e.data.endsWith(a.value) || (o = this._getOrReturnCtx(e, o), v(o, {
        code: g.invalid_string,
        validation: { endsWith: a.value },
        message: a.message
      }), n.dirty()) : a.kind === "datetime" ? Ms(a).test(e.data) || (o = this._getOrReturnCtx(e, o), v(o, {
        code: g.invalid_string,
        validation: "datetime",
        message: a.message
      }), n.dirty()) : a.kind === "date" ? jc.test(e.data) || (o = this._getOrReturnCtx(e, o), v(o, {
        code: g.invalid_string,
        validation: "date",
        message: a.message
      }), n.dirty()) : a.kind === "time" ? Zc(a).test(e.data) || (o = this._getOrReturnCtx(e, o), v(o, {
        code: g.invalid_string,
        validation: "time",
        message: a.message
      }), n.dirty()) : a.kind === "duration" ? Oc.test(e.data) || (o = this._getOrReturnCtx(e, o), v(o, {
        validation: "duration",
        code: g.invalid_string,
        message: a.message
      }), n.dirty()) : a.kind === "ip" ? zc(e.data, a.version) || (o = this._getOrReturnCtx(e, o), v(o, {
        validation: "ip",
        code: g.invalid_string,
        message: a.message
      }), n.dirty()) : a.kind === "base64" ? Uc.test(e.data) || (o = this._getOrReturnCtx(e, o), v(o, {
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
      ...k.errToObj(n)
    });
  }
  _addCheck(e) {
    return new Ie({
      ...this._def,
      checks: [...this._def.checks, e]
    });
  }
  email(e) {
    return this._addCheck({ kind: "email", ...k.errToObj(e) });
  }
  url(e) {
    return this._addCheck({ kind: "url", ...k.errToObj(e) });
  }
  emoji(e) {
    return this._addCheck({ kind: "emoji", ...k.errToObj(e) });
  }
  uuid(e) {
    return this._addCheck({ kind: "uuid", ...k.errToObj(e) });
  }
  nanoid(e) {
    return this._addCheck({ kind: "nanoid", ...k.errToObj(e) });
  }
  cuid(e) {
    return this._addCheck({ kind: "cuid", ...k.errToObj(e) });
  }
  cuid2(e) {
    return this._addCheck({ kind: "cuid2", ...k.errToObj(e) });
  }
  ulid(e) {
    return this._addCheck({ kind: "ulid", ...k.errToObj(e) });
  }
  base64(e) {
    return this._addCheck({ kind: "base64", ...k.errToObj(e) });
  }
  ip(e) {
    return this._addCheck({ kind: "ip", ...k.errToObj(e) });
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
      ...k.errToObj(e == null ? void 0 : e.message)
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
      ...k.errToObj(e == null ? void 0 : e.message)
    });
  }
  duration(e) {
    return this._addCheck({ kind: "duration", ...k.errToObj(e) });
  }
  regex(e, r) {
    return this._addCheck({
      kind: "regex",
      regex: e,
      ...k.errToObj(r)
    });
  }
  includes(e, r) {
    return this._addCheck({
      kind: "includes",
      value: e,
      position: r == null ? void 0 : r.position,
      ...k.errToObj(r == null ? void 0 : r.message)
    });
  }
  startsWith(e, r) {
    return this._addCheck({
      kind: "startsWith",
      value: e,
      ...k.errToObj(r)
    });
  }
  endsWith(e, r) {
    return this._addCheck({
      kind: "endsWith",
      value: e,
      ...k.errToObj(r)
    });
  }
  min(e, r) {
    return this._addCheck({
      kind: "min",
      value: e,
      ...k.errToObj(r)
    });
  }
  max(e, r) {
    return this._addCheck({
      kind: "max",
      value: e,
      ...k.errToObj(r)
    });
  }
  length(e, r) {
    return this._addCheck({
      kind: "length",
      value: e,
      ...k.errToObj(r)
    });
  }
  /**
   * @deprecated Use z.string().min(1) instead.
   * @see {@link ZodString.min}
   */
  nonempty(e) {
    return this.min(1, k.errToObj(e));
  }
  trim() {
    return new Ie({
      ...this._def,
      checks: [...this._def.checks, { kind: "trim" }]
    });
  }
  toLowerCase() {
    return new Ie({
      ...this._def,
      checks: [...this._def.checks, { kind: "toLowerCase" }]
    });
  }
  toUpperCase() {
    return new Ie({
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
Ie.create = (t) => {
  var e;
  return new Ie({
    checks: [],
    typeName: A.ZodString,
    coerce: (e = t == null ? void 0 : t.coerce) !== null && e !== void 0 ? e : !1,
    ...$(t)
  });
};
function Bc(t, e) {
  const r = (t.toString().split(".")[1] || "").length, n = (e.toString().split(".")[1] || "").length, o = r > n ? r : n, a = parseInt(t.toFixed(o).replace(".", "")), i = parseInt(e.toFixed(o).replace(".", ""));
  return a % i / Math.pow(10, o);
}
class et extends N {
  constructor() {
    super(...arguments), this.min = this.gte, this.max = this.lte, this.step = this.multipleOf;
  }
  _parse(e) {
    if (this._def.coerce && (e.data = Number(e.data)), this._getType(e) !== w.number) {
      const a = this._getOrReturnCtx(e);
      return v(a, {
        code: g.invalid_type,
        expected: w.number,
        received: a.parsedType
      }), I;
    }
    let n;
    const o = new ee();
    for (const a of this._def.checks)
      a.kind === "int" ? O.isInteger(e.data) || (n = this._getOrReturnCtx(e, n), v(n, {
        code: g.invalid_type,
        expected: "integer",
        received: "float",
        message: a.message
      }), o.dirty()) : a.kind === "min" ? (a.inclusive ? e.data < a.value : e.data <= a.value) && (n = this._getOrReturnCtx(e, n), v(n, {
        code: g.too_small,
        minimum: a.value,
        type: "number",
        inclusive: a.inclusive,
        exact: !1,
        message: a.message
      }), o.dirty()) : a.kind === "max" ? (a.inclusive ? e.data > a.value : e.data >= a.value) && (n = this._getOrReturnCtx(e, n), v(n, {
        code: g.too_big,
        maximum: a.value,
        type: "number",
        inclusive: a.inclusive,
        exact: !1,
        message: a.message
      }), o.dirty()) : a.kind === "multipleOf" ? Bc(e.data, a.value) !== 0 && (n = this._getOrReturnCtx(e, n), v(n, {
        code: g.not_multiple_of,
        multipleOf: a.value,
        message: a.message
      }), o.dirty()) : a.kind === "finite" ? Number.isFinite(e.data) || (n = this._getOrReturnCtx(e, n), v(n, {
        code: g.not_finite,
        message: a.message
      }), o.dirty()) : O.assertNever(a);
    return { status: o.value, value: e.data };
  }
  gte(e, r) {
    return this.setLimit("min", e, !0, k.toString(r));
  }
  gt(e, r) {
    return this.setLimit("min", e, !1, k.toString(r));
  }
  lte(e, r) {
    return this.setLimit("max", e, !0, k.toString(r));
  }
  lt(e, r) {
    return this.setLimit("max", e, !1, k.toString(r));
  }
  setLimit(e, r, n, o) {
    return new et({
      ...this._def,
      checks: [
        ...this._def.checks,
        {
          kind: e,
          value: r,
          inclusive: n,
          message: k.toString(o)
        }
      ]
    });
  }
  _addCheck(e) {
    return new et({
      ...this._def,
      checks: [...this._def.checks, e]
    });
  }
  int(e) {
    return this._addCheck({
      kind: "int",
      message: k.toString(e)
    });
  }
  positive(e) {
    return this._addCheck({
      kind: "min",
      value: 0,
      inclusive: !1,
      message: k.toString(e)
    });
  }
  negative(e) {
    return this._addCheck({
      kind: "max",
      value: 0,
      inclusive: !1,
      message: k.toString(e)
    });
  }
  nonpositive(e) {
    return this._addCheck({
      kind: "max",
      value: 0,
      inclusive: !0,
      message: k.toString(e)
    });
  }
  nonnegative(e) {
    return this._addCheck({
      kind: "min",
      value: 0,
      inclusive: !0,
      message: k.toString(e)
    });
  }
  multipleOf(e, r) {
    return this._addCheck({
      kind: "multipleOf",
      value: e,
      message: k.toString(r)
    });
  }
  finite(e) {
    return this._addCheck({
      kind: "finite",
      message: k.toString(e)
    });
  }
  safe(e) {
    return this._addCheck({
      kind: "min",
      inclusive: !0,
      value: Number.MIN_SAFE_INTEGER,
      message: k.toString(e)
    })._addCheck({
      kind: "max",
      inclusive: !0,
      value: Number.MAX_SAFE_INTEGER,
      message: k.toString(e)
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
et.create = (t) => new et({
  checks: [],
  typeName: A.ZodNumber,
  coerce: (t == null ? void 0 : t.coerce) || !1,
  ...$(t)
});
class tt extends N {
  constructor() {
    super(...arguments), this.min = this.gte, this.max = this.lte;
  }
  _parse(e) {
    if (this._def.coerce && (e.data = BigInt(e.data)), this._getType(e) !== w.bigint) {
      const a = this._getOrReturnCtx(e);
      return v(a, {
        code: g.invalid_type,
        expected: w.bigint,
        received: a.parsedType
      }), I;
    }
    let n;
    const o = new ee();
    for (const a of this._def.checks)
      a.kind === "min" ? (a.inclusive ? e.data < a.value : e.data <= a.value) && (n = this._getOrReturnCtx(e, n), v(n, {
        code: g.too_small,
        type: "bigint",
        minimum: a.value,
        inclusive: a.inclusive,
        message: a.message
      }), o.dirty()) : a.kind === "max" ? (a.inclusive ? e.data > a.value : e.data >= a.value) && (n = this._getOrReturnCtx(e, n), v(n, {
        code: g.too_big,
        type: "bigint",
        maximum: a.value,
        inclusive: a.inclusive,
        message: a.message
      }), o.dirty()) : a.kind === "multipleOf" ? e.data % a.value !== BigInt(0) && (n = this._getOrReturnCtx(e, n), v(n, {
        code: g.not_multiple_of,
        multipleOf: a.value,
        message: a.message
      }), o.dirty()) : O.assertNever(a);
    return { status: o.value, value: e.data };
  }
  gte(e, r) {
    return this.setLimit("min", e, !0, k.toString(r));
  }
  gt(e, r) {
    return this.setLimit("min", e, !1, k.toString(r));
  }
  lte(e, r) {
    return this.setLimit("max", e, !0, k.toString(r));
  }
  lt(e, r) {
    return this.setLimit("max", e, !1, k.toString(r));
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
          message: k.toString(o)
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
  positive(e) {
    return this._addCheck({
      kind: "min",
      value: BigInt(0),
      inclusive: !1,
      message: k.toString(e)
    });
  }
  negative(e) {
    return this._addCheck({
      kind: "max",
      value: BigInt(0),
      inclusive: !1,
      message: k.toString(e)
    });
  }
  nonpositive(e) {
    return this._addCheck({
      kind: "max",
      value: BigInt(0),
      inclusive: !0,
      message: k.toString(e)
    });
  }
  nonnegative(e) {
    return this._addCheck({
      kind: "min",
      value: BigInt(0),
      inclusive: !0,
      message: k.toString(e)
    });
  }
  multipleOf(e, r) {
    return this._addCheck({
      kind: "multipleOf",
      value: e,
      message: k.toString(r)
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
tt.create = (t) => {
  var e;
  return new tt({
    checks: [],
    typeName: A.ZodBigInt,
    coerce: (e = t == null ? void 0 : t.coerce) !== null && e !== void 0 ? e : !1,
    ...$(t)
  });
};
class Vt extends N {
  _parse(e) {
    if (this._def.coerce && (e.data = !!e.data), this._getType(e) !== w.boolean) {
      const n = this._getOrReturnCtx(e);
      return v(n, {
        code: g.invalid_type,
        expected: w.boolean,
        received: n.parsedType
      }), I;
    }
    return ce(e.data);
  }
}
Vt.create = (t) => new Vt({
  typeName: A.ZodBoolean,
  coerce: (t == null ? void 0 : t.coerce) || !1,
  ...$(t)
});
class pt extends N {
  _parse(e) {
    if (this._def.coerce && (e.data = new Date(e.data)), this._getType(e) !== w.date) {
      const a = this._getOrReturnCtx(e);
      return v(a, {
        code: g.invalid_type,
        expected: w.date,
        received: a.parsedType
      }), I;
    }
    if (isNaN(e.data.getTime())) {
      const a = this._getOrReturnCtx(e);
      return v(a, {
        code: g.invalid_date
      }), I;
    }
    const n = new ee();
    let o;
    for (const a of this._def.checks)
      a.kind === "min" ? e.data.getTime() < a.value && (o = this._getOrReturnCtx(e, o), v(o, {
        code: g.too_small,
        message: a.message,
        inclusive: !0,
        exact: !1,
        minimum: a.value,
        type: "date"
      }), n.dirty()) : a.kind === "max" ? e.data.getTime() > a.value && (o = this._getOrReturnCtx(e, o), v(o, {
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
      message: k.toString(r)
    });
  }
  max(e, r) {
    return this._addCheck({
      kind: "max",
      value: e.getTime(),
      message: k.toString(r)
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
  ...$(t)
});
class Ir extends N {
  _parse(e) {
    if (this._getType(e) !== w.symbol) {
      const n = this._getOrReturnCtx(e);
      return v(n, {
        code: g.invalid_type,
        expected: w.symbol,
        received: n.parsedType
      }), I;
    }
    return ce(e.data);
  }
}
Ir.create = (t) => new Ir({
  typeName: A.ZodSymbol,
  ...$(t)
});
class Ht extends N {
  _parse(e) {
    if (this._getType(e) !== w.undefined) {
      const n = this._getOrReturnCtx(e);
      return v(n, {
        code: g.invalid_type,
        expected: w.undefined,
        received: n.parsedType
      }), I;
    }
    return ce(e.data);
  }
}
Ht.create = (t) => new Ht({
  typeName: A.ZodUndefined,
  ...$(t)
});
class Wt extends N {
  _parse(e) {
    if (this._getType(e) !== w.null) {
      const n = this._getOrReturnCtx(e);
      return v(n, {
        code: g.invalid_type,
        expected: w.null,
        received: n.parsedType
      }), I;
    }
    return ce(e.data);
  }
}
Wt.create = (t) => new Wt({
  typeName: A.ZodNull,
  ...$(t)
});
class $t extends N {
  constructor() {
    super(...arguments), this._any = !0;
  }
  _parse(e) {
    return ce(e.data);
  }
}
$t.create = (t) => new $t({
  typeName: A.ZodAny,
  ...$(t)
});
class ft extends N {
  constructor() {
    super(...arguments), this._unknown = !0;
  }
  _parse(e) {
    return ce(e.data);
  }
}
ft.create = (t) => new ft({
  typeName: A.ZodUnknown,
  ...$(t)
});
class Ve extends N {
  _parse(e) {
    const r = this._getOrReturnCtx(e);
    return v(r, {
      code: g.invalid_type,
      expected: w.never,
      received: r.parsedType
    }), I;
  }
}
Ve.create = (t) => new Ve({
  typeName: A.ZodNever,
  ...$(t)
});
class Cr extends N {
  _parse(e) {
    if (this._getType(e) !== w.undefined) {
      const n = this._getOrReturnCtx(e);
      return v(n, {
        code: g.invalid_type,
        expected: w.void,
        received: n.parsedType
      }), I;
    }
    return ce(e.data);
  }
}
Cr.create = (t) => new Cr({
  typeName: A.ZodVoid,
  ...$(t)
});
class $e extends N {
  _parse(e) {
    const { ctx: r, status: n } = this._processInputParams(e), o = this._def;
    if (r.parsedType !== w.array)
      return v(r, {
        code: g.invalid_type,
        expected: w.array,
        received: r.parsedType
      }), I;
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
      return Promise.all([...r.data].map((i, c) => o.type._parseAsync(new Fe(r, i, r.path, c)))).then((i) => ee.mergeArray(n, i));
    const a = [...r.data].map((i, c) => o.type._parseSync(new Fe(r, i, r.path, c)));
    return ee.mergeArray(n, a);
  }
  get element() {
    return this._def.type;
  }
  min(e, r) {
    return new $e({
      ...this._def,
      minLength: { value: e, message: k.toString(r) }
    });
  }
  max(e, r) {
    return new $e({
      ...this._def,
      maxLength: { value: e, message: k.toString(r) }
    });
  }
  length(e, r) {
    return new $e({
      ...this._def,
      exactLength: { value: e, message: k.toString(r) }
    });
  }
  nonempty(e) {
    return this.min(1, e);
  }
}
$e.create = (t, e) => new $e({
  type: t,
  minLength: null,
  maxLength: null,
  exactLength: null,
  typeName: A.ZodArray,
  ...$(e)
});
function vt(t) {
  if (t instanceof z) {
    const e = {};
    for (const r in t.shape) {
      const n = t.shape[r];
      e[r] = Le.create(vt(n));
    }
    return new z({
      ...t._def,
      shape: () => e
    });
  } else return t instanceof $e ? new $e({
    ...t._def,
    type: vt(t.element)
  }) : t instanceof Le ? Le.create(vt(t.unwrap())) : t instanceof nt ? nt.create(vt(t.unwrap())) : t instanceof De ? De.create(t.items.map((e) => vt(e))) : t;
}
class z extends N {
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
      const d = this._getOrReturnCtx(e);
      return v(d, {
        code: g.invalid_type,
        expected: w.object,
        received: d.parsedType
      }), I;
    }
    const { status: n, ctx: o } = this._processInputParams(e), { shape: a, keys: i } = this._getCached(), c = [];
    if (!(this._def.catchall instanceof Ve && this._def.unknownKeys === "strip"))
      for (const d in o.data)
        i.includes(d) || c.push(d);
    const l = [];
    for (const d of i) {
      const u = a[d], f = o.data[d];
      l.push({
        key: { status: "valid", value: d },
        value: u._parse(new Fe(o, f, o.path, d)),
        alwaysSet: d in o.data
      });
    }
    if (this._def.catchall instanceof Ve) {
      const d = this._def.unknownKeys;
      if (d === "passthrough")
        for (const u of c)
          l.push({
            key: { status: "valid", value: u },
            value: { status: "valid", value: o.data[u] }
          });
      else if (d === "strict")
        c.length > 0 && (v(o, {
          code: g.unrecognized_keys,
          keys: c
        }), n.dirty());
      else if (d !== "strip") throw new Error("Internal ZodObject error: invalid unknownKeys value.");
    } else {
      const d = this._def.catchall;
      for (const u of c) {
        const f = o.data[u];
        l.push({
          key: { status: "valid", value: u },
          value: d._parse(
            new Fe(o, f, o.path, u)
            //, ctx.child(key), value, getParsedType(value)
          ),
          alwaysSet: u in o.data
        });
      }
    }
    return o.common.async ? Promise.resolve().then(async () => {
      const d = [];
      for (const u of l) {
        const f = await u.key, m = await u.value;
        d.push({
          key: f,
          value: m,
          alwaysSet: u.alwaysSet
        });
      }
      return d;
    }).then((d) => ee.mergeObjectSync(n, d)) : ee.mergeObjectSync(n, l);
  }
  get shape() {
    return this._def.shape();
  }
  strict(e) {
    return k.errToObj, new z({
      ...this._def,
      unknownKeys: "strict",
      ...e !== void 0 ? {
        errorMap: (r, n) => {
          var o, a, i, c;
          const l = (i = (a = (o = this._def).errorMap) === null || a === void 0 ? void 0 : a.call(o, r, n).message) !== null && i !== void 0 ? i : n.defaultError;
          return r.code === "unrecognized_keys" ? {
            message: (c = k.errToObj(e).message) !== null && c !== void 0 ? c : l
          } : {
            message: l
          };
        }
      } : {}
    });
  }
  strip() {
    return new z({
      ...this._def,
      unknownKeys: "strip"
    });
  }
  passthrough() {
    return new z({
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
    return new z({
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
    return new z({
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
    return new z({
      ...this._def,
      catchall: e
    });
  }
  pick(e) {
    const r = {};
    return O.objectKeys(e).forEach((n) => {
      e[n] && this.shape[n] && (r[n] = this.shape[n]);
    }), new z({
      ...this._def,
      shape: () => r
    });
  }
  omit(e) {
    const r = {};
    return O.objectKeys(this.shape).forEach((n) => {
      e[n] || (r[n] = this.shape[n]);
    }), new z({
      ...this._def,
      shape: () => r
    });
  }
  /**
   * @deprecated
   */
  deepPartial() {
    return vt(this);
  }
  partial(e) {
    const r = {};
    return O.objectKeys(this.shape).forEach((n) => {
      const o = this.shape[n];
      e && !e[n] ? r[n] = o : r[n] = o.optional();
    }), new z({
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
        for (; a instanceof Le; )
          a = a._def.innerType;
        r[n] = a;
      }
    }), new z({
      ...this._def,
      shape: () => r
    });
  }
  keyof() {
    return Ls(O.objectKeys(this.shape));
  }
}
z.create = (t, e) => new z({
  shape: () => t,
  unknownKeys: "strip",
  catchall: Ve.create(),
  typeName: A.ZodObject,
  ...$(e)
});
z.strictCreate = (t, e) => new z({
  shape: () => t,
  unknownKeys: "strict",
  catchall: Ve.create(),
  typeName: A.ZodObject,
  ...$(e)
});
z.lazycreate = (t, e) => new z({
  shape: t,
  unknownKeys: "strip",
  catchall: Ve.create(),
  typeName: A.ZodObject,
  ...$(e)
});
class qt extends N {
  _parse(e) {
    const { ctx: r } = this._processInputParams(e), n = this._def.options;
    function o(a) {
      for (const c of a)
        if (c.result.status === "valid")
          return c.result;
      for (const c of a)
        if (c.result.status === "dirty")
          return r.common.issues.push(...c.ctx.common.issues), c.result;
      const i = a.map((c) => new he(c.ctx.common.issues));
      return v(r, {
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
        const d = {
          ...r,
          common: {
            ...r.common,
            issues: []
          },
          parent: null
        }, u = l._parseSync({
          data: r.data,
          path: r.path,
          parent: d
        });
        if (u.status === "valid")
          return u;
        u.status === "dirty" && !a && (a = { result: u, ctx: d }), d.common.issues.length && i.push(d.common.issues);
      }
      if (a)
        return r.common.issues.push(...a.ctx.common.issues), a.result;
      const c = i.map((l) => new he(l));
      return v(r, {
        code: g.invalid_union,
        unionErrors: c
      }), I;
    }
  }
  get options() {
    return this._def.options;
  }
}
qt.create = (t, e) => new qt({
  options: t,
  typeName: A.ZodUnion,
  ...$(e)
});
const ze = (t) => t instanceof Jt ? ze(t.schema) : t instanceof Ne ? ze(t.innerType()) : t instanceof Xt ? [t.value] : t instanceof rt ? t.options : t instanceof Qt ? O.objectValues(t.enum) : t instanceof er ? ze(t._def.innerType) : t instanceof Ht ? [void 0] : t instanceof Wt ? [null] : t instanceof Le ? [void 0, ...ze(t.unwrap())] : t instanceof nt ? [null, ...ze(t.unwrap())] : t instanceof jn || t instanceof rr ? ze(t.unwrap()) : t instanceof tr ? ze(t._def.innerType) : [];
class Vr extends N {
  _parse(e) {
    const { ctx: r } = this._processInputParams(e);
    if (r.parsedType !== w.object)
      return v(r, {
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
    }) : (v(r, {
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
      const i = ze(a.shape[e]);
      if (!i.length)
        throw new Error(`A discriminator value for key \`${e}\` could not be extracted from all schema options`);
      for (const c of i) {
        if (o.has(c))
          throw new Error(`Discriminator property ${String(e)} has duplicate value ${String(c)}`);
        o.set(c, a);
      }
    }
    return new Vr({
      typeName: A.ZodDiscriminatedUnion,
      discriminator: e,
      options: r,
      optionsMap: o,
      ...$(n)
    });
  }
}
function gn(t, e) {
  const r = Ye(t), n = Ye(e);
  if (t === e)
    return { valid: !0, data: t };
  if (r === w.object && n === w.object) {
    const o = O.objectKeys(e), a = O.objectKeys(t).filter((c) => o.indexOf(c) !== -1), i = { ...t, ...e };
    for (const c of a) {
      const l = gn(t[c], e[c]);
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
      const i = t[a], c = e[a], l = gn(i, c);
      if (!l.valid)
        return { valid: !1 };
      o.push(l.data);
    }
    return { valid: !0, data: o };
  } else return r === w.date && n === w.date && +t == +e ? { valid: !0, data: t } : { valid: !1 };
}
class Kt extends N {
  _parse(e) {
    const { status: r, ctx: n } = this._processInputParams(e), o = (a, i) => {
      if (mn(a) || mn(i))
        return I;
      const c = gn(a.value, i.value);
      return c.valid ? ((hn(a) || hn(i)) && r.dirty(), { status: r.value, value: c.data }) : (v(n, {
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
Kt.create = (t, e, r) => new Kt({
  left: t,
  right: e,
  typeName: A.ZodIntersection,
  ...$(r)
});
class De extends N {
  _parse(e) {
    const { status: r, ctx: n } = this._processInputParams(e);
    if (n.parsedType !== w.array)
      return v(n, {
        code: g.invalid_type,
        expected: w.array,
        received: n.parsedType
      }), I;
    if (n.data.length < this._def.items.length)
      return v(n, {
        code: g.too_small,
        minimum: this._def.items.length,
        inclusive: !0,
        exact: !1,
        type: "array"
      }), I;
    !this._def.rest && n.data.length > this._def.items.length && (v(n, {
      code: g.too_big,
      maximum: this._def.items.length,
      inclusive: !0,
      exact: !1,
      type: "array"
    }), r.dirty());
    const a = [...n.data].map((i, c) => {
      const l = this._def.items[c] || this._def.rest;
      return l ? l._parse(new Fe(n, i, n.path, c)) : null;
    }).filter((i) => !!i);
    return n.common.async ? Promise.all(a).then((i) => ee.mergeArray(r, i)) : ee.mergeArray(r, a);
  }
  get items() {
    return this._def.items;
  }
  rest(e) {
    return new De({
      ...this._def,
      rest: e
    });
  }
}
De.create = (t, e) => {
  if (!Array.isArray(t))
    throw new Error("You must pass an array of schemas to z.tuple([ ... ])");
  return new De({
    items: t,
    typeName: A.ZodTuple,
    rest: null,
    ...$(e)
  });
};
class Yt extends N {
  get keySchema() {
    return this._def.keyType;
  }
  get valueSchema() {
    return this._def.valueType;
  }
  _parse(e) {
    const { status: r, ctx: n } = this._processInputParams(e);
    if (n.parsedType !== w.object)
      return v(n, {
        code: g.invalid_type,
        expected: w.object,
        received: n.parsedType
      }), I;
    const o = [], a = this._def.keyType, i = this._def.valueType;
    for (const c in n.data)
      o.push({
        key: a._parse(new Fe(n, c, n.path, c)),
        value: i._parse(new Fe(n, n.data[c], n.path, c)),
        alwaysSet: c in n.data
      });
    return n.common.async ? ee.mergeObjectAsync(r, o) : ee.mergeObjectSync(r, o);
  }
  get element() {
    return this._def.valueType;
  }
  static create(e, r, n) {
    return r instanceof N ? new Yt({
      keyType: e,
      valueType: r,
      typeName: A.ZodRecord,
      ...$(n)
    }) : new Yt({
      keyType: Ie.create(),
      valueType: e,
      typeName: A.ZodRecord,
      ...$(r)
    });
  }
}
class $r extends N {
  get keySchema() {
    return this._def.keyType;
  }
  get valueSchema() {
    return this._def.valueType;
  }
  _parse(e) {
    const { status: r, ctx: n } = this._processInputParams(e);
    if (n.parsedType !== w.map)
      return v(n, {
        code: g.invalid_type,
        expected: w.map,
        received: n.parsedType
      }), I;
    const o = this._def.keyType, a = this._def.valueType, i = [...n.data.entries()].map(([c, l], d) => ({
      key: o._parse(new Fe(n, c, n.path, [d, "key"])),
      value: a._parse(new Fe(n, l, n.path, [d, "value"]))
    }));
    if (n.common.async) {
      const c = /* @__PURE__ */ new Map();
      return Promise.resolve().then(async () => {
        for (const l of i) {
          const d = await l.key, u = await l.value;
          if (d.status === "aborted" || u.status === "aborted")
            return I;
          (d.status === "dirty" || u.status === "dirty") && r.dirty(), c.set(d.value, u.value);
        }
        return { status: r.value, value: c };
      });
    } else {
      const c = /* @__PURE__ */ new Map();
      for (const l of i) {
        const d = l.key, u = l.value;
        if (d.status === "aborted" || u.status === "aborted")
          return I;
        (d.status === "dirty" || u.status === "dirty") && r.dirty(), c.set(d.value, u.value);
      }
      return { status: r.value, value: c };
    }
  }
}
$r.create = (t, e, r) => new $r({
  valueType: e,
  keyType: t,
  typeName: A.ZodMap,
  ...$(r)
});
class mt extends N {
  _parse(e) {
    const { status: r, ctx: n } = this._processInputParams(e);
    if (n.parsedType !== w.set)
      return v(n, {
        code: g.invalid_type,
        expected: w.set,
        received: n.parsedType
      }), I;
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
    const a = this._def.valueType;
    function i(l) {
      const d = /* @__PURE__ */ new Set();
      for (const u of l) {
        if (u.status === "aborted")
          return I;
        u.status === "dirty" && r.dirty(), d.add(u.value);
      }
      return { status: r.value, value: d };
    }
    const c = [...n.data.values()].map((l, d) => a._parse(new Fe(n, l, n.path, d)));
    return n.common.async ? Promise.all(c).then((l) => i(l)) : i(c);
  }
  min(e, r) {
    return new mt({
      ...this._def,
      minSize: { value: e, message: k.toString(r) }
    });
  }
  max(e, r) {
    return new mt({
      ...this._def,
      maxSize: { value: e, message: k.toString(r) }
    });
  }
  size(e, r) {
    return this.min(e, r).max(e, r);
  }
  nonempty(e) {
    return this.min(1, e);
  }
}
mt.create = (t, e) => new mt({
  valueType: t,
  minSize: null,
  maxSize: null,
  typeName: A.ZodSet,
  ...$(e)
});
class Pt extends N {
  constructor() {
    super(...arguments), this.validate = this.implement;
  }
  _parse(e) {
    const { ctx: r } = this._processInputParams(e);
    if (r.parsedType !== w.function)
      return v(r, {
        code: g.invalid_type,
        expected: w.function,
        received: r.parsedType
      }), I;
    function n(c, l) {
      return Tr({
        data: c,
        path: r.path,
        errorMaps: [
          r.common.contextualErrorMap,
          r.schemaErrorMap,
          Pr(),
          Ct
        ].filter((d) => !!d),
        issueData: {
          code: g.invalid_arguments,
          argumentsError: l
        }
      });
    }
    function o(c, l) {
      return Tr({
        data: c,
        path: r.path,
        errorMaps: [
          r.common.contextualErrorMap,
          r.schemaErrorMap,
          Pr(),
          Ct
        ].filter((d) => !!d),
        issueData: {
          code: g.invalid_return_type,
          returnTypeError: l
        }
      });
    }
    const a = { errorMap: r.common.contextualErrorMap }, i = r.data;
    if (this._def.returns instanceof Nt) {
      const c = this;
      return ce(async function(...l) {
        const d = new he([]), u = await c._def.args.parseAsync(l, a).catch((p) => {
          throw d.addIssue(n(l, p)), d;
        }), f = await Reflect.apply(i, this, u);
        return await c._def.returns._def.type.parseAsync(f, a).catch((p) => {
          throw d.addIssue(o(f, p)), d;
        });
      });
    } else {
      const c = this;
      return ce(function(...l) {
        const d = c._def.args.safeParse(l, a);
        if (!d.success)
          throw new he([n(l, d.error)]);
        const u = Reflect.apply(i, this, d.data), f = c._def.returns.safeParse(u, a);
        if (!f.success)
          throw new he([o(u, f.error)]);
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
    return new Pt({
      ...this._def,
      args: De.create(e).rest(ft.create())
    });
  }
  returns(e) {
    return new Pt({
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
    return new Pt({
      args: e || De.create([]).rest(ft.create()),
      returns: r || ft.create(),
      typeName: A.ZodFunction,
      ...$(n)
    });
  }
}
class Jt extends N {
  get schema() {
    return this._def.getter();
  }
  _parse(e) {
    const { ctx: r } = this._processInputParams(e);
    return this._def.getter()._parse({ data: r.data, path: r.path, parent: r });
  }
}
Jt.create = (t, e) => new Jt({
  getter: t,
  typeName: A.ZodLazy,
  ...$(e)
});
class Xt extends N {
  _parse(e) {
    if (e.data !== this._def.value) {
      const r = this._getOrReturnCtx(e);
      return v(r, {
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
Xt.create = (t, e) => new Xt({
  value: t,
  typeName: A.ZodLiteral,
  ...$(e)
});
function Ls(t, e) {
  return new rt({
    values: t,
    typeName: A.ZodEnum,
    ...$(e)
  });
}
class rt extends N {
  constructor() {
    super(...arguments), jt.set(this, void 0);
  }
  _parse(e) {
    if (typeof e.data != "string") {
      const r = this._getOrReturnCtx(e), n = this._def.values;
      return v(r, {
        expected: O.joinValues(n),
        received: r.parsedType,
        code: g.invalid_type
      }), I;
    }
    if (Ar(this, jt) || Ns(this, jt, new Set(this._def.values)), !Ar(this, jt).has(e.data)) {
      const r = this._getOrReturnCtx(e), n = this._def.values;
      return v(r, {
        received: r.data,
        code: g.invalid_enum_value,
        options: n
      }), I;
    }
    return ce(e.data);
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
    return rt.create(e, {
      ...this._def,
      ...r
    });
  }
  exclude(e, r = this._def) {
    return rt.create(this.options.filter((n) => !e.includes(n)), {
      ...this._def,
      ...r
    });
  }
}
jt = /* @__PURE__ */ new WeakMap();
rt.create = Ls;
class Qt extends N {
  constructor() {
    super(...arguments), Zt.set(this, void 0);
  }
  _parse(e) {
    const r = O.getValidEnumValues(this._def.values), n = this._getOrReturnCtx(e);
    if (n.parsedType !== w.string && n.parsedType !== w.number) {
      const o = O.objectValues(r);
      return v(n, {
        expected: O.joinValues(o),
        received: n.parsedType,
        code: g.invalid_type
      }), I;
    }
    if (Ar(this, Zt) || Ns(this, Zt, new Set(O.getValidEnumValues(this._def.values))), !Ar(this, Zt).has(e.data)) {
      const o = O.objectValues(r);
      return v(n, {
        received: n.data,
        code: g.invalid_enum_value,
        options: o
      }), I;
    }
    return ce(e.data);
  }
  get enum() {
    return this._def.values;
  }
}
Zt = /* @__PURE__ */ new WeakMap();
Qt.create = (t, e) => new Qt({
  values: t,
  typeName: A.ZodNativeEnum,
  ...$(e)
});
class Nt extends N {
  unwrap() {
    return this._def.type;
  }
  _parse(e) {
    const { ctx: r } = this._processInputParams(e);
    if (r.parsedType !== w.promise && r.common.async === !1)
      return v(r, {
        code: g.invalid_type,
        expected: w.promise,
        received: r.parsedType
      }), I;
    const n = r.parsedType === w.promise ? r.data : Promise.resolve(r.data);
    return ce(n.then((o) => this._def.type.parseAsync(o, {
      path: r.path,
      errorMap: r.common.contextualErrorMap
    })));
  }
}
Nt.create = (t, e) => new Nt({
  type: t,
  typeName: A.ZodPromise,
  ...$(e)
});
class Ne extends N {
  innerType() {
    return this._def.schema;
  }
  sourceType() {
    return this._def.schema._def.typeName === A.ZodEffects ? this._def.schema.sourceType() : this._def.schema;
  }
  _parse(e) {
    const { status: r, ctx: n } = this._processInputParams(e), o = this._def.effect || null, a = {
      addIssue: (i) => {
        v(n, i), i.fatal ? r.abort() : r.dirty();
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
          return l.status === "aborted" ? I : l.status === "dirty" || r.value === "dirty" ? bt(l.value) : l;
        });
      {
        if (r.value === "aborted")
          return I;
        const c = this._def.schema._parseSync({
          data: i,
          path: n.path,
          parent: n
        });
        return c.status === "aborted" ? I : c.status === "dirty" || r.value === "dirty" ? bt(c.value) : c;
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
        if (!Bt(i))
          return i;
        const c = o.transform(i.value, a);
        if (c instanceof Promise)
          throw new Error("Asynchronous transform encountered during synchronous parse operation. Use .parseAsync instead.");
        return { status: r.value, value: c };
      } else
        return this._def.schema._parseAsync({ data: n.data, path: n.path, parent: n }).then((i) => Bt(i) ? Promise.resolve(o.transform(i.value, a)).then((c) => ({ status: r.value, value: c })) : i);
    O.assertNever(o);
  }
}
Ne.create = (t, e, r) => new Ne({
  schema: t,
  typeName: A.ZodEffects,
  effect: e,
  ...$(r)
});
Ne.createWithPreprocess = (t, e, r) => new Ne({
  schema: e,
  effect: { type: "preprocess", transform: t },
  typeName: A.ZodEffects,
  ...$(r)
});
class Le extends N {
  _parse(e) {
    return this._getType(e) === w.undefined ? ce(void 0) : this._def.innerType._parse(e);
  }
  unwrap() {
    return this._def.innerType;
  }
}
Le.create = (t, e) => new Le({
  innerType: t,
  typeName: A.ZodOptional,
  ...$(e)
});
class nt extends N {
  _parse(e) {
    return this._getType(e) === w.null ? ce(null) : this._def.innerType._parse(e);
  }
  unwrap() {
    return this._def.innerType;
  }
}
nt.create = (t, e) => new nt({
  innerType: t,
  typeName: A.ZodNullable,
  ...$(e)
});
class er extends N {
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
er.create = (t, e) => new er({
  innerType: t,
  typeName: A.ZodDefault,
  defaultValue: typeof e.default == "function" ? e.default : () => e.default,
  ...$(e)
});
class tr extends N {
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
    return Gt(o) ? o.then((a) => ({
      status: "valid",
      value: a.status === "valid" ? a.value : this._def.catchValue({
        get error() {
          return new he(n.common.issues);
        },
        input: n.data
      })
    })) : {
      status: "valid",
      value: o.status === "valid" ? o.value : this._def.catchValue({
        get error() {
          return new he(n.common.issues);
        },
        input: n.data
      })
    };
  }
  removeCatch() {
    return this._def.innerType;
  }
}
tr.create = (t, e) => new tr({
  innerType: t,
  typeName: A.ZodCatch,
  catchValue: typeof e.catch == "function" ? e.catch : () => e.catch,
  ...$(e)
});
class Nr extends N {
  _parse(e) {
    if (this._getType(e) !== w.nan) {
      const n = this._getOrReturnCtx(e);
      return v(n, {
        code: g.invalid_type,
        expected: w.nan,
        received: n.parsedType
      }), I;
    }
    return { status: "valid", value: e.data };
  }
}
Nr.create = (t) => new Nr({
  typeName: A.ZodNaN,
  ...$(t)
});
const Gc = Symbol("zod_brand");
class jn extends N {
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
class cr extends N {
  _parse(e) {
    const { status: r, ctx: n } = this._processInputParams(e);
    if (n.common.async)
      return (async () => {
        const a = await this._def.in._parseAsync({
          data: n.data,
          path: n.path,
          parent: n
        });
        return a.status === "aborted" ? I : a.status === "dirty" ? (r.dirty(), bt(a.value)) : this._def.out._parseAsync({
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
    return new cr({
      in: e,
      out: r,
      typeName: A.ZodPipeline
    });
  }
}
class rr extends N {
  _parse(e) {
    const r = this._def.innerType._parse(e), n = (o) => (Bt(o) && (o.value = Object.freeze(o.value)), o);
    return Gt(r) ? r.then((o) => n(o)) : n(r);
  }
  unwrap() {
    return this._def.innerType;
  }
}
rr.create = (t, e) => new rr({
  innerType: t,
  typeName: A.ZodReadonly,
  ...$(e)
});
function Fs(t, e = {}, r) {
  return t ? $t.create().superRefine((n, o) => {
    var a, i;
    if (!t(n)) {
      const c = typeof e == "function" ? e(n) : typeof e == "string" ? { message: e } : e, l = (i = (a = c.fatal) !== null && a !== void 0 ? a : r) !== null && i !== void 0 ? i : !0, d = typeof c == "string" ? { message: c } : c;
      o.addIssue({ code: "custom", ...d, fatal: l });
    }
  }) : $t.create();
}
const Vc = {
  object: z.lazycreate
};
var A;
(function(t) {
  t.ZodString = "ZodString", t.ZodNumber = "ZodNumber", t.ZodNaN = "ZodNaN", t.ZodBigInt = "ZodBigInt", t.ZodBoolean = "ZodBoolean", t.ZodDate = "ZodDate", t.ZodSymbol = "ZodSymbol", t.ZodUndefined = "ZodUndefined", t.ZodNull = "ZodNull", t.ZodAny = "ZodAny", t.ZodUnknown = "ZodUnknown", t.ZodNever = "ZodNever", t.ZodVoid = "ZodVoid", t.ZodArray = "ZodArray", t.ZodObject = "ZodObject", t.ZodUnion = "ZodUnion", t.ZodDiscriminatedUnion = "ZodDiscriminatedUnion", t.ZodIntersection = "ZodIntersection", t.ZodTuple = "ZodTuple", t.ZodRecord = "ZodRecord", t.ZodMap = "ZodMap", t.ZodSet = "ZodSet", t.ZodFunction = "ZodFunction", t.ZodLazy = "ZodLazy", t.ZodLiteral = "ZodLiteral", t.ZodEnum = "ZodEnum", t.ZodEffects = "ZodEffects", t.ZodNativeEnum = "ZodNativeEnum", t.ZodOptional = "ZodOptional", t.ZodNullable = "ZodNullable", t.ZodDefault = "ZodDefault", t.ZodCatch = "ZodCatch", t.ZodPromise = "ZodPromise", t.ZodBranded = "ZodBranded", t.ZodPipeline = "ZodPipeline", t.ZodReadonly = "ZodReadonly";
})(A || (A = {}));
const Hc = (t, e = {
  message: `Input not instance of ${t.name}`
}) => Fs((r) => r instanceof t, e), Ds = Ie.create, Us = et.create, Wc = Nr.create, qc = tt.create, js = Vt.create, Kc = pt.create, Yc = Ir.create, Jc = Ht.create, Xc = Wt.create, Qc = $t.create, el = ft.create, tl = Ve.create, rl = Cr.create, nl = $e.create, ol = z.create, sl = z.strictCreate, al = qt.create, il = Vr.create, cl = Kt.create, ll = De.create, ul = Yt.create, dl = $r.create, fl = mt.create, pl = Pt.create, ml = Jt.create, hl = Xt.create, gl = rt.create, yl = Qt.create, _l = Nt.create, _o = Ne.create, vl = Le.create, bl = nt.create, wl = Ne.createWithPreprocess, Sl = cr.create, El = () => Ds().optional(), xl = () => Us().optional(), kl = () => js().optional(), Pl = {
  string: (t) => Ie.create({ ...t, coerce: !0 }),
  number: (t) => et.create({ ...t, coerce: !0 }),
  boolean: (t) => Vt.create({
    ...t,
    coerce: !0
  }),
  bigint: (t) => tt.create({ ...t, coerce: !0 }),
  date: (t) => pt.create({ ...t, coerce: !0 })
}, Tl = I;
var q = /* @__PURE__ */ Object.freeze({
  __proto__: null,
  defaultErrorMap: Ct,
  setErrorMap: Tc,
  getErrorMap: Pr,
  makeIssue: Tr,
  EMPTY_PATH: Ac,
  addIssueToContext: v,
  ParseStatus: ee,
  INVALID: I,
  DIRTY: bt,
  OK: ce,
  isAborted: mn,
  isDirty: hn,
  isValid: Bt,
  isAsync: Gt,
  get util() {
    return O;
  },
  get objectUtil() {
    return pn;
  },
  ZodParsedType: w,
  getParsedType: Ye,
  ZodType: N,
  datetimeRegex: Ms,
  ZodString: Ie,
  ZodNumber: et,
  ZodBigInt: tt,
  ZodBoolean: Vt,
  ZodDate: pt,
  ZodSymbol: Ir,
  ZodUndefined: Ht,
  ZodNull: Wt,
  ZodAny: $t,
  ZodUnknown: ft,
  ZodNever: Ve,
  ZodVoid: Cr,
  ZodArray: $e,
  ZodObject: z,
  ZodUnion: qt,
  ZodDiscriminatedUnion: Vr,
  ZodIntersection: Kt,
  ZodTuple: De,
  ZodRecord: Yt,
  ZodMap: $r,
  ZodSet: mt,
  ZodFunction: Pt,
  ZodLazy: Jt,
  ZodLiteral: Xt,
  ZodEnum: rt,
  ZodNativeEnum: Qt,
  ZodPromise: Nt,
  ZodEffects: Ne,
  ZodTransformer: Ne,
  ZodOptional: Le,
  ZodNullable: nt,
  ZodDefault: er,
  ZodCatch: tr,
  ZodNaN: Nr,
  BRAND: Gc,
  ZodBranded: jn,
  ZodPipeline: cr,
  ZodReadonly: rr,
  custom: Fs,
  Schema: N,
  ZodSchema: N,
  late: Vc,
  get ZodFirstPartyTypeKind() {
    return A;
  },
  coerce: Pl,
  any: Qc,
  array: nl,
  bigint: qc,
  boolean: js,
  date: Kc,
  discriminatedUnion: il,
  effect: _o,
  enum: gl,
  function: pl,
  instanceof: Hc,
  intersection: cl,
  lazy: ml,
  literal: hl,
  map: dl,
  nan: Wc,
  nativeEnum: yl,
  never: tl,
  null: Xc,
  nullable: bl,
  number: Us,
  object: ol,
  oboolean: kl,
  onumber: xl,
  optional: vl,
  ostring: El,
  pipeline: Sl,
  preprocess: wl,
  promise: _l,
  record: ul,
  set: fl,
  strictObject: sl,
  string: Ds,
  symbol: Yc,
  transformer: _o,
  tuple: ll,
  undefined: Jc,
  union: al,
  unknown: el,
  void: rl,
  NEVER: Tl,
  ZodIssueCode: g,
  quotelessJson: Pc,
  ZodError: he
});
const Al = q.object({
  width: q.number().positive(),
  height: q.number().positive()
});
function Il(t, e, r, n) {
  const o = document.createElement("plugin-modal");
  o.setTheme(r);
  const a = 200, i = 200, c = 335, l = 590, d = {
    blockStart: 40,
    inlineEnd: 320
  };
  o.style.setProperty(
    "--modal-block-start",
    `${d.blockStart}px`
  ), o.style.setProperty(
    "--modal-inline-end",
    `${d.inlineEnd}px`
  );
  const u = window.innerWidth - d.inlineEnd, f = window.innerHeight - d.blockStart;
  let m = Math.min((n == null ? void 0 : n.width) || c, u), p = Math.min((n == null ? void 0 : n.height) || l, f);
  return m = Math.max(m, a), p = Math.max(p, i), o.setAttribute("title", t), o.setAttribute("iframe-src", e), o.setAttribute("width", String(m)), o.setAttribute("height", String(p)), document.body.appendChild(o), o;
}
const Cl = q.function().args(
  q.string(),
  q.string(),
  q.enum(["dark", "light"]),
  Al.optional()
).implement((t, e, r, n) => Il(t, e, r, n)), $l = q.object({
  pluginId: q.string(),
  name: q.string(),
  host: q.string().url(),
  code: q.string(),
  icon: q.string().optional(),
  description: q.string().max(200).optional(),
  permissions: q.array(
    q.enum([
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
function Nl(t) {
  return fetch(t).then((e) => e.json()).then((e) => {
    if (!$l.safeParse(e).success)
      throw new Error("Invalid plugin manifest");
    return e;
  }).catch((e) => {
    throw console.error(e), e;
  });
}
function vo(t) {
  return !t.host && !t.code.startsWith("http") ? Promise.resolve(t.code) : fetch(Zs(t.host, t.code)).then((e) => {
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
let yn = [], _n = /* @__PURE__ */ new Set([]);
window.addEventListener("message", (t) => {
  try {
    for (const e of yn)
      e(t.data);
  } catch (e) {
    console.error(e);
  }
});
function Ol(t) {
  _n.forEach((e) => {
    e.setTheme(t);
  });
}
function Ml(t, e, r, n) {
  let o = null, a = {};
  const i = () => {
    Object.entries(a).forEach(([, u]) => {
      u.forEach((f) => {
        t.removeListener(f);
      });
    }), yn = [];
  }, c = () => {
    i(), o && (_n.delete(o), o.removeEventListener("close", c), o.remove()), o = null, r();
  }, l = (u) => {
    if (!e.permissions.includes(u))
      throw new Error(`Permission ${u} is not granted`);
  };
  return {
    penpot: {
      ui: {
        open: (u, f, m) => {
          const p = t.getTheme(), h = Zs(e.host, f);
          (o == null ? void 0 : o.getAttribute("iframe-src")) !== h && (o = Cl(u, h, p, m), o.setTheme(p), o.addEventListener("close", c, {
            once: !0
          }), o.addEventListener("load", n), _n.add(o));
        },
        sendMessage(u) {
          const f = new CustomEvent("message", {
            detail: u
          });
          o == null || o.dispatchEvent(f);
        },
        onMessage: (u) => {
          q.function().parse(u), yn.push(u);
        }
      },
      utils: {
        geometry: {
          center(u) {
            return window.app.plugins.public_utils.centerShapes(u);
          }
        },
        types: {
          isFrame(u) {
            return u.type === "frame";
          },
          isGroup(u) {
            return u.type === "group";
          },
          isMask(u) {
            return u.type === "group" && u.isMask();
          },
          isBool(u) {
            return u.type === "bool";
          },
          isRectangle(u) {
            return u.type === "rect";
          },
          isPath(u) {
            return u.type === "path";
          },
          isText(u) {
            return u.type === "text";
          },
          isEllipse(u) {
            return u.type === "circle";
          },
          isSVG(u) {
            return u.type === "svg-raw";
          }
        }
      },
      closePlugin: c,
      on(u, f, m) {
        q.enum(Rl).parse(u), q.function().parse(f), l("content:read");
        const p = t.addListener(u, f, m);
        return a[u] || (a[u] = /* @__PURE__ */ new Map()), a[u].set(f, p), p;
      },
      off(u, f) {
        let m;
        typeof u == "symbol" ? m = u : f && (m = a[u].get(f)), m && t.removeListener(m);
      },
      // Penpot State API
      get root() {
        return l("content:read"), t.root;
      },
      get currentPage() {
        return l("content:read"), t.currentPage;
      },
      get selection() {
        return l("content:read"), t.selection;
      },
      set selection(u) {
        l("content:read"), t.selection = u;
      },
      get viewport() {
        return t.viewport;
      },
      get history() {
        return t.history;
      },
      get library() {
        return l("library:read"), t.library;
      },
      get fonts() {
        return l("content:read"), t.fonts;
      },
      get currentUser() {
        return l("user:read"), t.currentUser;
      },
      get activeUsers() {
        return l("user:read"), t.activeUsers;
      },
      getFile() {
        return l("content:read"), t.getFile();
      },
      getPage() {
        return l("content:read"), t.getPage();
      },
      getSelected() {
        return l("content:read"), t.getSelected();
      },
      getSelectedShapes() {
        return l("content:read"), t.getSelectedShapes();
      },
      shapesColors(u) {
        return l("content:read"), t.shapesColors(u);
      },
      replaceColor(u, f, m) {
        return l("content:write"), t.replaceColor(u, f, m);
      },
      getTheme() {
        return t.getTheme();
      },
      createFrame() {
        return l("content:write"), t.createFrame();
      },
      createRectangle() {
        return l("content:write"), t.createRectangle();
      },
      createEllipse() {
        return l("content:write"), t.createEllipse();
      },
      createText(u) {
        return l("content:write"), t.createText(u);
      },
      createPath() {
        return l("content:write"), t.createPath();
      },
      createBoolean(u, f) {
        return l("content:write"), t.createBoolean(u, f);
      },
      createShapeFromSvg(u) {
        return l("content:write"), t.createShapeFromSvg(u);
      },
      group(u) {
        return l("content:write"), t.group(u);
      },
      ungroup(u, ...f) {
        l("content:write"), t.ungroup(u, ...f);
      },
      uploadMediaUrl(u, f) {
        return l("content:write"), t.uploadMediaUrl(u, f);
      },
      uploadMediaData(u, f, m) {
        return l("content:write"), t.uploadMediaData(u, f, m);
      },
      generateMarkup(u, f) {
        return l("content:read"), t.generateMarkup(u, f);
      },
      generateStyle(u, f) {
        return l("content:read"), t.generateStyle(u, f);
      },
      openViewer() {
        l("content:read"), t.openViewer();
      },
      createPage() {
        return l("content:write"), t.createPage();
      },
      openPage(u) {
        l("content:read"), t.openPage(u);
      }
    },
    removeAllEventListeners: i
  };
}
let bo = !1;
const Ke = {
  hardenIntrinsics: () => {
    bo || (bo = !0, hardenIntrinsics());
  },
  createCompartment: (t) => new Compartment(t),
  harden: (t) => harden(t)
};
let wt = [];
const Ll = !1;
let vn = null;
function Fl(t) {
  vn = t;
}
const on = () => {
  wt.forEach((t) => {
    t.penpot.closePlugin();
  }), wt = [];
}, Dl = async function(t) {
  try {
    const e = vn && vn(t.pluginId);
    if (!e)
      return;
    e.addListener("themechange", (f) => Ol(f));
    const r = e.addListener("finish", () => {
      on(), e == null || e.removeListener(r);
    }), n = await vo(t);
    Ke.hardenIntrinsics(), wt && !Ll && on();
    const o = () => {
      wt = wt.filter((f) => f !== c), l.forEach(clearTimeout), l.clear(), Object.keys(d).forEach((f) => {
        delete u.globalThis[f];
      });
    };
    let a = !1;
    const c = Ml(e, t, o, async () => {
      if (!a) {
        a = !0;
        return;
      }
      c.removeAllEventListeners();
      const f = await vo(t);
      u.evaluate(f);
    });
    wt.push(c);
    const l = /* @__PURE__ */ new Set(), d = {
      penpot: Ke.harden(c.penpot),
      fetch: Ke.harden((...f) => {
        const m = {
          ...f[1],
          credentials: "omit"
        };
        return fetch(f[0], m);
      }),
      console: Ke.harden(window.console),
      Math: Ke.harden(Math),
      setTimeout: Ke.harden(
        (...[f, m]) => {
          const p = setTimeout(() => {
            f();
          }, m);
          return l.add(p), p;
        }
      ),
      clearTimeout: Ke.harden((f) => {
        clearTimeout(f), l.delete(f);
      })
    }, u = Ke.createCompartment(d);
    return u.evaluate(n), {
      compartment: u,
      publicPluginApi: d,
      timeouts: l,
      context: e
    };
  } catch (e) {
    on(), console.error(e);
  }
}, zs = async function(t) {
  Dl(t);
}, Ul = async function(t) {
  const e = await Nl(t);
  zs(e);
};
console.log("%c[PLUGINS] Loading plugin system", "color: #008d7c");
repairIntrinsics({
  evalTaming: "unsafeEval",
  stackFiltering: "verbose",
  errorTaming: "unsafe",
  consoleTaming: "unsafe",
  errorTrapping: "none"
});
const wo = globalThis;
wo.initPluginsRuntime = (t) => {
  try {
    console.log("%c[PLUGINS] Initialize runtime", "color: #008d7c"), Fl(t), wo.ɵcontext = t("TEST"), globalThis.ɵloadPlugin = zs, globalThis.ɵloadPluginByUrl = Ul;
  } catch (e) {
    console.error(e);
  }
};
//# sourceMappingURL=index.js.map
