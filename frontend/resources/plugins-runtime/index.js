var Zn = (t) => {
  throw TypeError(t);
};
var zn = (t, e, r) => e.has(t) || Zn("Cannot " + r);
var Ie = (t, e, r) => (zn(t, e, "read from private field"), r ? r.call(t) : e.get(t)), Wr = (t, e, r) => e.has(t) ? Zn("Cannot add the same private member more than once") : e instanceof WeakSet ? e.add(t) : e.set(t, r), qr = (t, e, r, n) => (zn(t, e, "write to private field"), n ? n.call(t, r) : e.set(t, r), r);
const T = globalThis, {
  Array: Bs,
  Date: Gs,
  FinalizationRegistry: Tt,
  Float32Array: Vs,
  JSON: Hs,
  Map: Re,
  Math: Ws,
  Number: wo,
  Object: yn,
  Promise: qs,
  Proxy: Nr,
  Reflect: Ks,
  RegExp: Je,
  Set: Nt,
  String: ve,
  Symbol: wt,
  WeakMap: je,
  WeakSet: Ot
} = globalThis, {
  // The feral Error constructor is safe for internal use, but must not be
  // revealed to post-lockdown code in any compartment including the start
  // compartment since in V8 at least it bears stack inspection capabilities.
  Error: ae,
  RangeError: Ys,
  ReferenceError: zt,
  SyntaxError: or,
  TypeError: _,
  AggregateError: Kr
} = globalThis, {
  assign: Or,
  create: H,
  defineProperties: B,
  entries: he,
  freeze: y,
  getOwnPropertyDescriptor: te,
  getOwnPropertyDescriptors: Ze,
  getOwnPropertyNames: At,
  getPrototypeOf: V,
  is: Mr,
  isFrozen: jl,
  isSealed: Zl,
  isExtensible: zl,
  keys: xo,
  prototype: _n,
  seal: Bl,
  preventExtensions: Js,
  setPrototypeOf: So,
  values: Eo,
  fromEntries: mt
} = yn, {
  species: Yr,
  toStringTag: Xe,
  iterator: sr,
  matchAll: ko,
  unscopables: Xs,
  keyFor: Qs,
  for: ea
} = wt, { isInteger: ta } = wo, { stringify: Po } = Hs, { defineProperty: ra } = yn, U = (t, e, r) => {
  const n = ra(t, e, r);
  if (n !== t)
    throw _(
      `Please report that the original defineProperty silently failed to set ${Po(
        ve(e)
      )}. (SES_DEFINE_PROPERTY_FAILED_SILENTLY)`
    );
  return n;
}, {
  apply: ce,
  construct: yr,
  get: na,
  getOwnPropertyDescriptor: oa,
  has: To,
  isExtensible: sa,
  ownKeys: Ge,
  preventExtensions: aa,
  set: Ao
} = Ks, { isArray: xt, prototype: ke } = Bs, { prototype: Mt } = Re, { prototype: Lr } = RegExp, { prototype: ar } = Nt, { prototype: ze } = ve, { prototype: Fr } = je, { prototype: Io } = Ot, { prototype: vn } = Function, { prototype: Co } = qs, { prototype: Ro } = V(
  // eslint-disable-next-line no-empty-function, func-names
  function* () {
  }
), ia = V(Uint8Array.prototype), { bind: on } = vn, A = on.bind(on.call), le = A(_n.hasOwnProperty), Qe = A(ke.filter), dt = A(ke.forEach), Dr = A(ke.includes), Lt = A(ke.join), ue = (
  /** @type {any} */
  A(ke.map)
), $o = (
  /** @type {any} */
  A(ke.flatMap)
), _r = A(ke.pop), re = A(ke.push), ca = A(ke.slice), la = A(ke.some), No = A(ke.sort), ua = A(ke[sr]), fe = A(Mt.set), Ve = A(Mt.get), Ur = A(Mt.has), da = A(Mt.delete), fa = A(Mt.entries), pa = A(Mt[sr]), bn = A(ar.add);
A(ar.delete);
const Bn = A(ar.forEach), wn = A(ar.has), ha = A(ar[sr]), xn = A(Lr.test), Sn = A(Lr.exec), ma = A(Lr[ko]), Oo = A(ze.endsWith), Mo = A(ze.includes), ga = A(ze.indexOf);
A(ze.match);
const vr = A(Ro.next), Lo = A(Ro.throw), br = (
  /** @type {any} */
  A(ze.replace)
), ya = A(ze.search), En = A(ze.slice), kn = A(ze.split), Fo = A(ze.startsWith), _a = A(ze[sr]), va = A(Fr.delete), z = A(Fr.get), St = A(Fr.has), pe = A(Fr.set), jr = A(Io.add), ir = A(Io.has), ba = A(vn.toString), wa = A(on);
A(Co.catch);
const Do = (
  /** @type {any} */
  A(Co.then)
), xa = Tt && A(Tt.prototype.register);
Tt && A(Tt.prototype.unregister);
const Pn = y(H(null)), Ee = (t) => yn(t) === t, Zr = (t) => t instanceof ae, Uo = eval, Se = Function, Sa = () => {
  throw _('Cannot eval with evalTaming set to "noEval" (SES_NO_EVAL)');
}, Ke = te(Error("er1"), "stack"), Jr = te(_("er2"), "stack");
let jo, Zo;
if (Ke && Jr && Ke.get)
  if (
    // In the v8 case as we understand it, all errors have an own stack
    // accessor property, but within the same realm, all these accessor
    // properties have the same getter and have the same setter.
    // This is therefore the case that we repair.
    typeof Ke.get == "function" && Ke.get === Jr.get && typeof Ke.set == "function" && Ke.set === Jr.set
  )
    jo = y(Ke.get), Zo = y(Ke.set);
  else
    throw _(
      "Unexpected Error own stack accessor functions (SES_UNEXPECTED_ERROR_OWN_STACK_ACCESSOR)"
    );
const Xr = jo, Ea = Zo;
function ka() {
  return this;
}
if (ka())
  throw _("SES failed to initialize, sloppy mode (SES_NO_SLOPPY)");
const { freeze: ct } = Object, { apply: Pa } = Reflect, Tn = (t) => (e, ...r) => Pa(t, e, r), Ta = Tn(Array.prototype.push), Gn = Tn(Array.prototype.includes), Aa = Tn(String.prototype.split), st = JSON.stringify, ur = (t, ...e) => {
  let r = t[0];
  for (let n = 0; n < e.length; n += 1)
    r = `${r}${e[n]}${t[n + 1]}`;
  throw Error(r);
}, zo = (t, e = !1) => {
  const r = [], n = (c, l, u = void 0) => {
    typeof c == "string" || ur`Environment option name ${st(c)} must be a string.`, typeof l == "string" || ur`Environment option default setting ${st(
      l
    )} must be a string.`;
    let d = l;
    const f = t.process || void 0, h = typeof f == "object" && f.env || void 0;
    if (typeof h == "object" && c in h) {
      e || Ta(r, c);
      const p = h[c];
      typeof p == "string" || ur`Environment option named ${st(
        c
      )}, if present, must have a corresponding string value, got ${st(
        p
      )}`, d = p;
    }
    return u === void 0 || d === l || Gn(u, d) || ur`Unrecognized ${st(c)} value ${st(
      d
    )}. Expected one of ${st([l, ...u])}`, d;
  };
  ct(n);
  const o = (c) => {
    const l = n(c, "");
    return ct(l === "" ? [] : Aa(l, ","));
  };
  ct(o);
  const s = (c, l) => Gn(o(c), l), i = () => ct([...r]);
  return ct(i), ct({
    getEnvironmentOption: n,
    getEnvironmentOptionsList: o,
    environmentOptionsListHas: s,
    getCapturedEnvironmentOptionNames: i
  });
};
ct(zo);
const {
  getEnvironmentOption: ge,
  getEnvironmentOptionsList: Gl,
  environmentOptionsListHas: Vl
} = zo(globalThis, !0), wr = (t) => (t = `${t}`, t.length >= 1 && Mo("aeiouAEIOU", t[0]) ? `an ${t}` : `a ${t}`);
y(wr);
const Bo = (t, e = void 0) => {
  const r = new Nt(), n = (o, s) => {
    switch (typeof s) {
      case "object": {
        if (s === null)
          return null;
        if (wn(r, s))
          return "[Seen]";
        if (bn(r, s), Zr(s))
          return `[${s.name}: ${s.message}]`;
        if (Xe in s)
          return `[${s[Xe]}]`;
        if (xt(s))
          return s;
        const i = xo(s);
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
        No(i);
        const l = ue(i, (u) => [u, s[u]]);
        return mt(l);
      }
      case "function":
        return `[Function ${s.name || "<anon>"}]`;
      case "string":
        return Fo(s, "[") ? `[${s}]` : s;
      case "undefined":
      case "symbol":
        return `[${ve(s)}]`;
      case "bigint":
        return `[${s}n]`;
      case "number":
        return Mr(s, NaN) ? "[NaN]" : s === 1 / 0 ? "[Infinity]" : s === -1 / 0 ? "[-Infinity]" : s;
      default:
        return s;
    }
  };
  try {
    return Po(t, n, e);
  } catch {
    return "[Something that failed to stringify]";
  }
};
y(Bo);
const { isSafeInteger: Ia } = Number, { freeze: _t } = Object, { toStringTag: Ca } = Symbol, Vn = (t) => {
  const r = {
    next: void 0,
    prev: void 0,
    data: t
  };
  return r.next = r, r.prev = r, r;
}, Hn = (t, e) => {
  if (t === e)
    throw TypeError("Cannot splice a cell into itself");
  if (e.next !== e || e.prev !== e)
    throw TypeError("Expected self-linked cell");
  const r = e, n = t.next;
  return r.prev = t, r.next = n, t.next = r, n.prev = r, r;
}, Qr = (t) => {
  const { prev: e, next: r } = t;
  e.next = r, r.prev = e, t.prev = t, t.next = t;
}, Go = (t) => {
  if (!Ia(t) || t < 0)
    throw TypeError("keysBudget must be a safe non-negative integer number");
  const e = /* @__PURE__ */ new WeakMap();
  let r = 0;
  const n = Vn(void 0), o = (d) => {
    const f = e.get(d);
    if (!(f === void 0 || f.data === void 0))
      return Qr(f), Hn(n, f), f;
  }, s = (d) => o(d) !== void 0;
  _t(s);
  const i = (d) => {
    const f = o(d);
    return f && f.data && f.data.get(d);
  };
  _t(i);
  const c = (d, f) => {
    if (t < 1)
      return u;
    let h = o(d);
    if (h === void 0 && (h = Vn(void 0), Hn(n, h)), !h.data)
      for (r += 1, h.data = /* @__PURE__ */ new WeakMap(), e.set(d, h); r > t; ) {
        const p = n.prev;
        Qr(p), p.data = void 0, r -= 1;
      }
    return h.data.set(d, f), u;
  };
  _t(c);
  const l = (d) => {
    const f = e.get(d);
    return f === void 0 || (Qr(f), e.delete(d), f.data === void 0) ? !1 : (f.data = void 0, r -= 1, !0);
  };
  _t(l);
  const u = _t({
    has: s,
    get: i,
    set: c,
    delete: l,
    // eslint-disable-next-line jsdoc/check-types
    [
      /** @type {typeof Symbol.toStringTag} */
      Ca
    ]: "LRUCacheMap"
  });
  return u;
};
_t(Go);
const { freeze: mr } = Object, { isSafeInteger: Ra } = Number, $a = 1e3, Na = 100, Vo = (t = $a, e = Na) => {
  if (!Ra(e) || e < 1)
    throw TypeError(
      "argsPerErrorBudget must be a safe positive integer number"
    );
  const r = Go(t), n = (s, i) => {
    const c = r.get(s);
    c !== void 0 ? (c.length >= e && c.shift(), c.push(i)) : r.set(s, [i]);
  };
  mr(n);
  const o = (s) => {
    const i = r.get(s);
    return r.delete(s), i;
  };
  return mr(o), mr({
    addLogArgs: n,
    takeLogArgsArray: o
  });
};
mr(Vo);
const It = new je(), Z = (t, e = void 0) => {
  const r = y({
    toString: y(() => Bo(t, e))
  });
  return pe(It, r, t), r;
};
y(Z);
const Oa = y(/^[\w:-]( ?[\w:-])*$/), xr = (t, e = void 0) => {
  if (typeof t != "string" || !xn(Oa, t))
    return Z(t, e);
  const r = y({
    toString: y(() => t)
  });
  return pe(It, r, t), r;
};
y(xr);
const zr = new je(), Ho = ({ template: t, args: e }) => {
  const r = [t[0]];
  for (let n = 0; n < e.length; n += 1) {
    const o = e[n];
    let s;
    St(It, o) ? s = `${o}` : Zr(o) ? s = `(${wr(o.name)})` : s = `(${wr(typeof o)})`, re(r, s, t[n + 1]);
  }
  return Lt(r, "");
}, Wo = y({
  toString() {
    const t = z(zr, this);
    return t === void 0 ? "[Not a DetailsToken]" : Ho(t);
  }
});
y(Wo.toString);
const ie = (t, ...e) => {
  const r = y({ __proto__: Wo });
  return pe(zr, r, { template: t, args: e }), /** @type {DetailsToken} */
  /** @type {unknown} */
  r;
};
y(ie);
const qo = (t, ...e) => (e = ue(
  e,
  (r) => St(It, r) ? r : Z(r)
), ie(t, ...e));
y(qo);
const Ko = ({ template: t, args: e }) => {
  const r = [t[0]];
  for (let n = 0; n < e.length; n += 1) {
    let o = e[n];
    St(It, o) && (o = z(It, o));
    const s = br(_r(r) || "", / $/, "");
    s !== "" && re(r, s);
    const i = br(t[n + 1], /^ /, "");
    re(r, o, i);
  }
  return r[r.length - 1] === "" && _r(r), r;
}, gr = new je();
let sn = 0;
const Wn = new je(), Yo = (t, e = t.name) => {
  let r = z(Wn, t);
  return r !== void 0 || (sn += 1, r = `${e}#${sn}`, pe(Wn, t, r)), r;
}, Ma = (t) => {
  const e = Ze(t), {
    name: r,
    message: n,
    errors: o = void 0,
    cause: s = void 0,
    stack: i = void 0,
    ...c
  } = e, l = Ge(c);
  if (l.length >= 1) {
    for (const d of l)
      delete t[d];
    const u = H(_n, c);
    Br(
      t,
      ie`originally with properties ${Z(u)}`
    );
  }
  for (const u of Ge(t)) {
    const d = e[u];
    d && le(d, "get") && U(t, u, {
      value: t[u]
      // invoke the getter to convert to data property
    });
  }
  y(t);
}, Le = (t = ie`Assert failed`, e = T.Error, {
  errorName: r = void 0,
  cause: n = void 0,
  errors: o = void 0,
  sanitize: s = !0
} = {}) => {
  typeof t == "string" && (t = ie([t]));
  const i = z(zr, t);
  if (i === void 0)
    throw _(`unrecognized details ${Z(t)}`);
  const c = Ho(i), l = n && { cause: n };
  let u;
  return typeof Kr < "u" && e === Kr ? u = Kr(o || [], c, l) : (u = /** @type {ErrorConstructor} */
  e(
    c,
    l
  ), o !== void 0 && U(u, "errors", {
    value: o,
    writable: !0,
    enumerable: !1,
    configurable: !0
  })), pe(gr, u, Ko(i)), r !== void 0 && Yo(u, r), s && Ma(u), u;
};
y(Le);
const { addLogArgs: La, takeLogArgsArray: Fa } = Vo(), an = new je(), Br = (t, e) => {
  typeof e == "string" && (e = ie([e]));
  const r = z(zr, e);
  if (r === void 0)
    throw _(`unrecognized details ${Z(e)}`);
  const n = Ko(r), o = z(an, t);
  if (o !== void 0)
    for (const s of o)
      s(t, n);
  else
    La(t, n);
};
y(Br);
const Da = (t) => {
  if (!("stack" in t))
    return "";
  const e = `${t.stack}`, r = ga(e, `
`);
  return Fo(e, " ") || r === -1 ? e : En(e, r + 1);
}, Sr = {
  getStackString: T.getStackString || Da,
  tagError: (t) => Yo(t),
  resetErrorTagNum: () => {
    sn = 0;
  },
  getMessageLogArgs: (t) => z(gr, t),
  takeMessageLogArgs: (t) => {
    const e = z(gr, t);
    return va(gr, t), e;
  },
  takeNoteLogArgsArray: (t, e) => {
    const r = Fa(t);
    if (e !== void 0) {
      const n = z(an, t);
      n ? re(n, e) : pe(an, t, [e]);
    }
    return r || [];
  }
};
y(Sr);
const Gr = (t = void 0, e = !1) => {
  const r = e ? qo : ie, n = r`Check failed`, o = (f = n, h = void 0, p = void 0) => {
    const m = Le(f, h, p);
    throw t !== void 0 && t(m), m;
  };
  y(o);
  const s = (f, ...h) => o(r(f, ...h));
  function i(f, h = void 0, p = void 0, m = void 0) {
    f || o(h, p, m);
  }
  const c = (f, h, p = void 0, m = void 0, v = void 0) => {
    Mr(f, h) || o(
      p || r`Expected ${f} is same as ${h}`,
      m || Ys,
      v
    );
  };
  y(c);
  const l = (f, h, p) => {
    if (typeof f !== h) {
      if (typeof h == "string" || s`${Z(h)} must be a string`, p === void 0) {
        const m = wr(h);
        p = r`${f} must be ${xr(m)}`;
      }
      o(p, _);
    }
  };
  y(l);
  const d = Or(i, {
    error: Le,
    fail: o,
    equal: c,
    typeof: l,
    string: (f, h = void 0) => l(f, "string", h),
    note: Br,
    details: r,
    Fail: s,
    quote: Z,
    bare: xr,
    makeAssert: Gr
  });
  return y(d);
};
y(Gr);
const Q = Gr(), qn = Q.equal, Jo = te(
  ia,
  Xe
);
Q(Jo);
const Xo = Jo.get;
Q(Xo);
const Ua = (t) => ce(Xo, t, []) !== void 0, ja = (t) => {
  const e = +ve(t);
  return ta(e) && ve(e) === t;
}, Za = (t) => {
  Js(t), dt(Ge(t), (e) => {
    const r = te(t, e);
    Q(r), ja(e) || U(t, e, {
      ...r,
      writable: !1,
      configurable: !1
    });
  });
}, za = () => {
  if (typeof T.harden == "function")
    return T.harden;
  const t = new Ot(), { harden: e } = {
    /**
     * @template T
     * @param {T} root
     * @returns {T}
     */
    harden(r) {
      const n = new Nt();
      function o(d) {
        if (!Ee(d))
          return;
        const f = typeof d;
        if (f !== "object" && f !== "function")
          throw _(`Unexpected typeof: ${f}`);
        ir(t, d) || wn(n, d) || bn(n, d);
      }
      const s = (d) => {
        Ua(d) ? Za(d) : y(d);
        const f = Ze(d), h = V(d);
        o(h), dt(Ge(f), (p) => {
          const m = f[
            /** @type {string} */
            p
          ];
          le(m, "value") ? o(m.value) : (o(m.get), o(m.set));
        });
      }, i = Xr === void 0 && Ea === void 0 ? (
        // On platforms without v8's error own stack accessor problem,
        // don't pay for any extra overhead.
        s
      ) : (d) => {
        if (Zr(d)) {
          const f = te(d, "stack");
          f && f.get === Xr && f.configurable && U(d, "stack", {
            // NOTE: Calls getter during harden, which seems dangerous.
            // But we're only calling the problematic getter whose
            // hazards we think we understand.
            // @ts-expect-error TS should know FERAL_STACK_GETTER
            // cannot be `undefined` here.
            // See https://github.com/endojs/endo/pull/2232#discussion_r1575179471
            value: ce(Xr, d, [])
          });
        }
        return s(d);
      }, c = () => {
        Bn(n, i);
      }, l = (d) => {
        jr(t, d);
      }, u = () => {
        Bn(n, l);
      };
      return o(r), c(), u(), r;
    }
  };
  return e;
}, Qo = {
  // *** Value Properties of the Global Object
  Infinity: 1 / 0,
  NaN: NaN,
  undefined: void 0
}, es = {
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
}, Kn = {
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
}, ts = {
  // *** Constructor Properties of the Global Object
  Date: "%SharedDate%",
  Error: "%SharedError%",
  RegExp: "%SharedRegExp%",
  Symbol: "%SharedSymbol%",
  // *** Other Properties of the Global Object
  Math: "%SharedMath%"
}, rs = [
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
typeof AggregateError < "u" && re(rs, AggregateError);
const cn = {
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
}, a = cn, Yn = Ba, M = {
  get: a,
  set: "undefined"
}, Oe = {
  get: a,
  set: a
}, Jn = (t) => t === M || t === Oe;
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
function we(t) {
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
const Xn = {
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
}, Er = {
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
    "--proto--": Oe,
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
    stackTraceLimit: Oe,
    // Non standard, v8 only, used by several, tamed to accessor
    prepareStackTrace: Oe
  },
  "%SharedError%": {
    // Properties of the Error Constructor
    "[[Proto]]": "%FunctionPrototype%",
    prototype: "%ErrorPrototype%",
    // Non standard, v8 only, used by tap
    captureStackTrace: a,
    // Non standard, v8 only, used by tap, tamed to accessor
    stackTraceLimit: Oe,
    // Non standard, v8 only, used by several, tamed to accessor
    prepareStackTrace: Oe
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
    stack: Oe,
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
    ...Xn,
    // `%InitialMath%.random()` has the standard unsafe behavior
    random: a
  },
  "%SharedMath%": {
    ...Xn,
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
  BigInt64Array: we("%BigInt64ArrayPrototype%"),
  BigUint64Array: we("%BigUint64ArrayPrototype%"),
  // https://github.com/tc39/proposal-float16array
  Float16Array: we("%Float16ArrayPrototype%"),
  Float32Array: we("%Float32ArrayPrototype%"),
  Float64Array: we("%Float64ArrayPrototype%"),
  Int16Array: we("%Int16ArrayPrototype%"),
  Int32Array: we("%Int32ArrayPrototype%"),
  Int8Array: we("%Int8ArrayPrototype%"),
  Uint16Array: we("%Uint16ArrayPrototype%"),
  Uint32Array: we("%Uint32ArrayPrototype%"),
  Uint8ClampedArray: we("%Uint8ClampedArrayPrototype%"),
  Uint8Array: {
    ...we("%Uint8ArrayPrototype%"),
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
  // another whitelist change to update to the current proposed standard.
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
    "@@species": M
  },
  "%PromisePrototype%": {
    // Properties of the Promise Prototype Object
    catch: a,
    constructor: "Promise",
    finally: a,
    then: a,
    "@@toStringTag": "string",
    // Non-standard, used in node to prevent async_hooks from breaking
    "UniqueSymbol(async_id_symbol)": Oe,
    "UniqueSymbol(trigger_async_id_symbol)": Oe,
    "UniqueSymbol(destroyed)": Oe
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
    import: Yn,
    load: Yn,
    importNow: a,
    module: a,
    "@@toStringTag": "string"
  },
  lockdown: a,
  harden: { ...a, isFake: "boolean" },
  "%InitialGetStackString%": a
}, Ga = (t) => typeof t == "function";
function Va(t, e, r) {
  if (le(t, e)) {
    const n = te(t, e);
    if (!n || !Mr(n.value, r.value) || n.get !== r.get || n.set !== r.set || n.writable !== r.writable || n.enumerable !== r.enumerable || n.configurable !== r.configurable)
      throw _(`Conflicting definitions of ${e}`);
  }
  U(t, e, r);
}
function Ha(t, e) {
  for (const [r, n] of he(e))
    Va(t, r, n);
}
function ns(t, e) {
  const r = { __proto__: null };
  for (const [n, o] of he(e))
    le(t, n) && (r[o] = t[n]);
  return r;
}
const os = () => {
  const t = H(null);
  let e;
  const r = (c) => {
    Ha(t, Ze(c));
  };
  y(r);
  const n = () => {
    for (const [c, l] of he(t)) {
      if (!Ee(l) || !le(l, "prototype"))
        continue;
      const u = Er[c];
      if (typeof u != "object")
        throw _(`Expected permit object at whitelist.${c}`);
      const d = u.prototype;
      if (!d)
        throw _(`${c}.prototype property not whitelisted`);
      if (typeof d != "string" || !le(Er, d))
        throw _(`Unrecognized ${c}.prototype whitelist entry`);
      const f = l.prototype;
      if (le(t, d)) {
        if (t[d] !== f)
          throw _(`Conflicting bindings of ${d}`);
        continue;
      }
      t[d] = f;
    }
  };
  y(n);
  const o = () => (y(t), e = new Ot(Qe(Eo(t), Ga)), t);
  y(o);
  const s = (c) => {
    if (!e)
      throw _(
        "isPseudoNative can only be called after finalIntrinsics"
      );
    return ir(e, c);
  };
  y(s);
  const i = {
    addIntrinsics: r,
    completePrototypes: n,
    finalIntrinsics: o,
    isPseudoNative: s
  };
  return y(i), r(Qo), r(ns(T, es)), i;
}, Wa = (t) => {
  const { addIntrinsics: e, finalIntrinsics: r } = os();
  return e(ns(t, ts)), r();
};
function qa(t, e) {
  let r = !1;
  const n = (h, ...p) => (r || (console.groupCollapsed("Removing unpermitted intrinsics"), r = !0), console[h](...p)), o = ["undefined", "boolean", "number", "string", "symbol"], s = new Re(
    wt ? ue(
      Qe(
        he(Er["%SharedSymbol%"]),
        ([h, p]) => p === "symbol" && typeof wt[h] == "symbol"
      ),
      ([h]) => [wt[h], `@@${h}`]
    ) : []
  );
  function i(h, p) {
    if (typeof p == "string")
      return p;
    const m = Ve(s, p);
    if (typeof p == "symbol") {
      if (m)
        return m;
      {
        const v = Qs(p);
        return v !== void 0 ? `RegisteredSymbol(${v})` : `Unique${ve(p)}`;
      }
    }
    throw _(`Unexpected property name type ${h} ${p}`);
  }
  function c(h, p, m) {
    if (!Ee(p))
      throw _(`Object expected: ${h}, ${p}, ${m}`);
    const v = V(p);
    if (!(v === null && m === null)) {
      if (m !== void 0 && typeof m != "string")
        throw _(`Malformed whitelist permit ${h}.__proto__`);
      if (v !== t[m || "%ObjectPrototype%"])
        throw _(`Unexpected intrinsic ${h}.__proto__ at ${m}`);
    }
  }
  function l(h, p, m, v) {
    if (typeof v == "object")
      return f(h, p, v), !0;
    if (v === !1)
      return !1;
    if (typeof v == "string") {
      if (m === "prototype" || m === "constructor") {
        if (le(t, v)) {
          if (p !== t[v])
            throw _(`Does not match whitelist ${h}`);
          return !0;
        }
      } else if (Dr(o, v)) {
        if (typeof p !== v)
          throw _(
            `At ${h} expected ${v} not ${typeof p}`
          );
        return !0;
      }
    }
    throw _(`Unexpected whitelist permit ${v} at ${h}`);
  }
  function u(h, p, m, v) {
    const S = te(p, m);
    if (!S)
      throw _(`Property ${m} not found at ${h}`);
    if (le(S, "value")) {
      if (Jn(v))
        throw _(`Accessor expected at ${h}`);
      return l(h, S.value, m, v);
    }
    if (!Jn(v))
      throw _(`Accessor not expected at ${h}`);
    return l(`${h}<get>`, S.get, m, v.get) && l(`${h}<set>`, S.set, m, v.set);
  }
  function d(h, p, m) {
    const v = m === "__proto__" ? "--proto--" : m;
    if (le(p, v))
      return p[v];
    if (typeof h == "function" && le(cn, v))
      return cn[v];
  }
  function f(h, p, m) {
    if (p == null)
      return;
    const v = m["[[Proto]]"];
    c(h, p, v), typeof p == "function" && e(p);
    for (const S of Ge(p)) {
      const x = i(h, S), I = `${h}.${x}`, E = d(p, m, x);
      if (!E || !u(I, p, S, E)) {
        E !== !1 && n("warn", `Removing ${I}`);
        try {
          delete p[S];
        } catch (L) {
          if (S in p) {
            if (typeof p == "function" && S === "prototype" && (p.prototype = void 0, p.prototype === void 0)) {
              n(
                "warn",
                `Tolerating undeletable ${I} === undefined`
              );
              continue;
            }
            n("error", `failed to delete ${I}`, L);
          } else
            n("error", `deleting ${I} threw`, L);
          throw L;
        }
      }
    }
  }
  try {
    f("intrinsics", t, Er);
  } finally {
    r && console.groupEnd();
  }
}
function Ka() {
  try {
    Se.prototype.constructor("return 1");
  } catch {
    return y({});
  }
  const t = {};
  function e(r, n, o) {
    let s;
    try {
      s = (0, eval)(o);
    } catch (l) {
      if (l instanceof or)
        return;
      throw l;
    }
    const i = V(s), c = function() {
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
    }), c !== Se.prototype.constructor && So(c, Se.prototype.constructor), t[n] = c;
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
function Ya(t = "safe") {
  if (t !== "safe" && t !== "unsafe")
    throw _(`unrecognized dateTaming ${t}`);
  const e = Gs, r = e.prototype, n = {
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
      return new.target === void 0 ? ce(e, void 0, d) : yr(e, d, new.target);
    } : l = function(...d) {
      if (new.target === void 0)
        throw _(
          "secure mode Calling %SharedDate% constructor as a function throws"
        );
      if (d.length === 0)
        throw _(
          "secure mode Calling new %SharedDate%() with no arguments throws"
        );
      return yr(e, d, new.target);
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
function Ja(t = "safe") {
  if (t !== "safe" && t !== "unsafe")
    throw _(`unrecognized mathTaming ${t}`);
  const e = Ws, r = e, { random: n, ...o } = Ze(e), i = H(_n, {
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
function Xa(t = "safe") {
  if (t !== "safe" && t !== "unsafe")
    throw _(`unrecognized regExpTaming ${t}`);
  const e = Je.prototype, r = (s = {}) => {
    const i = function(...l) {
      return new.target === void 0 ? Je(...l) : yr(Je, l, new.target);
    };
    if (B(i, {
      length: { value: 2 },
      prototype: {
        value: e,
        writable: !1,
        enumerable: !1,
        configurable: !1
      }
    }), Yr) {
      const c = te(
        Je,
        Yr
      );
      if (!c)
        throw _("no RegExp[Symbol.species] descriptor");
      B(i, {
        [Yr]: c
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
const Qa = {
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
}, ss = {
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
}, ei = {
  ...ss,
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
function ti(t, e, r = []) {
  const n = new Nt(r);
  function o(u, d, f, h) {
    if ("value" in h && h.configurable) {
      const { value: p } = h, m = wn(n, f), { get: v, set: S } = te(
        {
          get [f]() {
            return p;
          },
          set [f](x) {
            if (d === this)
              throw _(
                `Cannot assign to read only property '${ve(
                  f
                )}' of '${u}'`
              );
            le(this, f) ? this[f] = x : (m && console.error(_(`Override property ${f}`)), U(this, f, {
              value: x,
              writable: !0,
              enumerable: !0,
              configurable: !0
            }));
          }
        },
        f
      );
      U(v, "originalValue", {
        value: p,
        writable: !1,
        enumerable: !1,
        configurable: !1
      }), U(d, f, {
        get: v,
        set: S,
        enumerable: h.enumerable,
        configurable: h.configurable
      });
    }
  }
  function s(u, d, f) {
    const h = te(d, f);
    h && o(u, d, f, h);
  }
  function i(u, d) {
    const f = Ze(d);
    f && dt(Ge(f), (h) => o(u, d, h, f[h]));
  }
  function c(u, d, f) {
    for (const h of Ge(f)) {
      const p = te(d, h);
      if (!p || p.get || p.set)
        continue;
      const m = `${u}.${ve(h)}`, v = f[h];
      if (v === !0)
        s(m, d, h);
      else if (v === "*")
        i(m, p.value);
      else if (Ee(v))
        c(m, p.value, v);
      else
        throw _(`Unexpected override enablement plan ${m}`);
    }
  }
  let l;
  switch (e) {
    case "min": {
      l = Qa;
      break;
    }
    case "moderate": {
      l = ss;
      break;
    }
    case "severe": {
      l = ei;
      break;
    }
    default:
      throw _(`unrecognized overrideTaming ${e}`);
  }
  c("root", t, l);
}
const { Fail: ln, quote: kr } = Q, ri = /^(\w*[a-z])Locale([A-Z]\w*)$/, as = {
  // See https://tc39.es/ecma262/#sec-string.prototype.localecompare
  localeCompare(t) {
    if (this === null || this === void 0)
      throw _(
        'Cannot localeCompare with null or undefined "this" value'
      );
    const e = `${this}`, r = `${t}`;
    return e < r ? -1 : e > r ? 1 : (e === r || ln`expected ${kr(e)} and ${kr(r)} to compare`, 0);
  },
  toString() {
    return `${this}`;
  }
}, ni = as.localeCompare, oi = as.toString;
function si(t, e = "safe") {
  if (e !== "safe" && e !== "unsafe")
    throw _(`unrecognized localeTaming ${e}`);
  if (e !== "unsafe") {
    U(ve.prototype, "localeCompare", {
      value: ni
    });
    for (const r of At(t)) {
      const n = t[r];
      if (Ee(n))
        for (const o of At(n)) {
          const s = Sn(ri, o);
          if (s) {
            typeof n[o] == "function" || ln`expected ${kr(o)} to be a function`;
            const i = `${s[1]}${s[2]}`, c = n[i];
            typeof c == "function" || ln`function ${kr(i)} not found`, U(n, o, { value: c });
          }
        }
    }
    U(wo.prototype, "toLocaleString", {
      value: oi
    });
  }
}
const ai = (t) => ({
  eval(r) {
    return typeof r != "string" ? r : t(r);
  }
}).eval, { Fail: Qn } = Q, ii = (t) => {
  const e = function(n) {
    const o = `${_r(arguments) || ""}`, s = `${Lt(arguments, ",")}`;
    new Se(s, ""), new Se(o);
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
      value: Se.prototype,
      writable: !1,
      enumerable: !1,
      configurable: !1
    }
  }), V(Se) === Se.prototype || Qn`Function prototype is the same accross compartments`, V(e) === Se.prototype || Qn`Function constructor prototype is the same accross compartments`, e;
}, ci = (t) => {
  U(
    t,
    Xs,
    y(
      Or(H(null), {
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
}, is = (t) => {
  for (const [e, r] of he(Qo))
    U(t, e, {
      value: r,
      writable: !1,
      enumerable: !1,
      configurable: !1
    });
}, cs = (t, {
  intrinsics: e,
  newGlobalPropertyNames: r,
  makeCompartmentConstructor: n,
  markVirtualizedNativeFunction: o,
  parentCompartment: s
}) => {
  for (const [c, l] of he(es))
    le(e, l) && U(t, c, {
      value: e[l],
      writable: !0,
      enumerable: !1,
      configurable: !0
    });
  for (const [c, l] of he(r))
    le(e, l) && U(t, c, {
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
  for (const [c, l] of he(i))
    U(t, c, {
      value: l,
      writable: !0,
      enumerable: !1,
      configurable: !0
    }), typeof l == "function" && o(l);
}, un = (t, e, r) => {
  {
    const n = y(ai(e));
    r(n), U(t, "eval", {
      value: n,
      writable: !0,
      enumerable: !1,
      configurable: !0
    });
  }
  {
    const n = y(ii(e));
    r(n), U(t, "Function", {
      value: n,
      writable: !0,
      enumerable: !1,
      configurable: !0
    });
  }
}, { Fail: li, quote: ls } = Q, us = new Nr(
  Pn,
  y({
    get(t, e) {
      li`Please report unexpected scope handler trap: ${ls(ve(e))}`;
    }
  })
), ui = {
  get(t, e) {
  },
  set(t, e, r) {
    throw zt(`${ve(e)} is not defined`);
  },
  has(t, e) {
    return e in T;
  },
  // note: this is likely a bug of safari
  // https://bugs.webkit.org/show_bug.cgi?id=195534
  getPrototypeOf(t) {
    return null;
  },
  // See https://github.com/endojs/endo/issues/1510
  // TODO: report as bug to v8 or Chrome, and record issue link here.
  getOwnPropertyDescriptor(t, e) {
    const r = ls(ve(e));
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
}, ds = y(
  H(
    us,
    Ze(ui)
  )
), di = new Nr(
  Pn,
  ds
), fs = (t) => {
  const e = {
    // inherit scopeTerminator behavior
    ...ds,
    // Redirect set properties to the globalObject.
    set(o, s, i) {
      return Ao(t, s, i);
    },
    // Always claim to have a potential property in order to be the recipient of a set
    has(o, s) {
      return !0;
    }
  }, r = y(
    H(
      us,
      Ze(e)
    )
  );
  return new Nr(
    Pn,
    r
  );
};
y(fs);
const { Fail: fi } = Q, pi = () => {
  const t = H(null), e = y({
    eval: {
      get() {
        return delete t.eval, Uo;
      },
      enumerable: !1,
      configurable: !0
    }
  }), r = {
    evalScope: t,
    allowNextEvalToBeUnsafe() {
      const { revoked: n } = r;
      n !== null && fi`a handler did not reset allowNextEvalToBeUnsafe ${n.err}`, B(t, e);
    },
    /** @type {null | { err: any }} */
    revoked: null
  };
  return r;
}, eo = "\\s*[@#]\\s*([a-zA-Z][a-zA-Z0-9]*)\\s*=\\s*([^\\s\\*]*)", hi = new Je(
  `(?:\\s*//${eo}|/\\*${eo}\\s*\\*/)\\s*$`
), An = (t) => {
  let e = "<unknown>";
  for (; t.length > 0; ) {
    const r = Sn(hi, t);
    if (r === null)
      break;
    t = En(t, 0, t.length - r[0].length), r[3] === "sourceURL" ? e = r[4] : r[1] === "sourceURL" && (e = r[2]);
  }
  return e;
};
function In(t, e) {
  const r = ya(t, e);
  if (r < 0)
    return -1;
  const n = t[r] === `
` ? 1 : 0;
  return kn(En(t, 0, r), `
`).length + n;
}
const ps = new Je("(?:<!--|-->)", "g"), hs = (t) => {
  const e = In(t, ps);
  if (e < 0)
    return t;
  const r = An(t);
  throw or(
    `Possible HTML comment rejected at ${r}:${e}. (SES_HTML_COMMENT_REJECTED)`
  );
}, ms = (t) => br(t, ps, (r) => r[0] === "<" ? "< ! --" : "-- >"), gs = new Je(
  "(^|[^.]|\\.\\.\\.)\\bimport(\\s*(?:\\(|/[/*]))",
  "g"
), ys = (t) => {
  const e = In(t, gs);
  if (e < 0)
    return t;
  const r = An(t);
  throw or(
    `Possible import expression rejected at ${r}:${e}. (SES_IMPORT_REJECTED)`
  );
}, _s = (t) => br(t, gs, (r, n, o) => `${n}__import__${o}`), mi = new Je(
  "(^|[^.])\\beval(\\s*\\()",
  "g"
), vs = (t) => {
  const e = In(t, mi);
  if (e < 0)
    return t;
  const r = An(t);
  throw or(
    `Possible direct eval expression rejected at ${r}:${e}. (SES_EVAL_REJECTED)`
  );
}, bs = (t) => (t = hs(t), t = ys(t), t), ws = (t, e) => {
  for (const r of e)
    t = r(t);
  return t;
};
y({
  rejectHtmlComments: y(hs),
  evadeHtmlCommentTest: y(ms),
  rejectImportExpressions: y(ys),
  evadeImportExpressionTest: y(_s),
  rejectSomeDirectEvalExpressions: y(vs),
  mandatoryTransforms: y(bs),
  applyTransforms: y(ws)
});
const gi = [
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
], yi = /^[a-zA-Z_$][\w$]*$/, to = (t) => t !== "eval" && !Dr(gi, t) && xn(yi, t);
function ro(t, e) {
  const r = te(t, e);
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
  le(r, "value");
}
const _i = (t, e = {}) => {
  const r = At(t), n = At(e), o = Qe(
    n,
    (i) => to(i) && ro(e, i)
  );
  return {
    globalObjectConstants: Qe(
      r,
      (i) => (
        // Can't define a constant: it would prevent a
        // lookup on the endowments.
        !Dr(n, i) && to(i) && ro(t, i)
      )
    ),
    moduleLexicalConstants: o
  };
};
function no(t, e) {
  return t.length === 0 ? "" : `const {${Lt(t, ",")}} = this.${e};`;
}
const vi = (t) => {
  const { globalObjectConstants: e, moduleLexicalConstants: r } = _i(
    t.globalObject,
    t.moduleLexicals
  ), n = no(
    e,
    "globalObject"
  ), o = no(
    r,
    "moduleLexicals"
  ), s = Se(`
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
  return ce(s, t, []);
}, { Fail: bi } = Q, Cn = ({
  globalObject: t,
  moduleLexicals: e = {},
  globalTransforms: r = [],
  sloppyGlobalsMode: n = !1
}) => {
  const o = n ? fs(t) : di, s = pi(), { evalScope: i } = s, c = y({
    evalScope: i,
    moduleLexicals: e,
    globalObject: t,
    scopeTerminator: o
  });
  let l;
  const u = () => {
    l || (l = vi(c));
  };
  return { safeEvaluate: (f, h) => {
    const { localTransforms: p = [] } = h || {};
    u(), f = ws(f, [
      ...p,
      ...r,
      bs
    ]);
    let m;
    try {
      return s.allowNextEvalToBeUnsafe(), ce(l, t, [f]);
    } catch (v) {
      throw m = v, v;
    } finally {
      const v = "eval" in i;
      delete i.eval, v && (s.revoked = { err: m }, bi`handler did not reset allowNextEvalToBeUnsafe ${m}`);
    }
  } };
}, wi = ") { [native code] }";
let en;
const xs = () => {
  if (en === void 0) {
    const t = new Ot();
    U(vn, "toString", {
      value: {
        toString() {
          const r = ba(this);
          return Oo(r, wi) || !ir(t, this) ? r : `function ${this.name}() { [native code] }`;
        }
      }.toString
    }), en = y(
      (r) => jr(t, r)
    );
  }
  return en;
};
function xi(t = "safe") {
  if (t !== "safe" && t !== "unsafe")
    throw _(`unrecognized domainTaming ${t}`);
  if (t === "unsafe")
    return;
  const e = T.process || void 0;
  if (typeof e == "object") {
    const r = te(e, "domain");
    if (r !== void 0 && r.get !== void 0)
      throw _(
        "SES failed to lockdown, Node.js domains have been initialized (SES_NO_DOMAINS)"
      );
    U(e, "domain", {
      value: null,
      configurable: !1,
      writable: !1,
      enumerable: !1
    });
  }
}
const Rn = y([
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
]), $n = y([
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
]), Ss = y([
  ...Rn,
  ...$n
]), Si = (t, { shouldResetForDebugging: e = !1 } = {}) => {
  e && t.resetErrorTagNum();
  let r = [];
  const n = mt(
    ue(Ss, ([i, c]) => {
      const l = (...u) => {
        re(r, [i, ...u]);
      };
      return U(l, "name", { value: i }), [i, y(l)];
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
y(Si);
const lt = {
  NOTE: "ERROR_NOTE:",
  MESSAGE: "ERROR_MESSAGE:",
  CAUSE: "cause:",
  ERRORS: "errors:"
};
y(lt);
const Nn = (t, e) => {
  if (!t)
    return;
  const { getStackString: r, tagError: n, takeMessageLogArgs: o, takeNoteLogArgsArray: s } = e, i = (S, x) => ue(S, (E) => Zr(E) ? (re(x, E), `(${n(E)})`) : E), c = (S, x, I, E, L) => {
    const $ = n(x), j = I === lt.MESSAGE ? `${$}:` : `${$} ${I}`, F = i(E, L);
    t[S](j, ...F);
  }, l = (S, x, I = void 0) => {
    if (x.length === 0)
      return;
    if (x.length === 1 && I === void 0) {
      f(S, x[0]);
      return;
    }
    let E;
    x.length === 1 ? E = "Nested error" : E = `Nested ${x.length} errors`, I !== void 0 && (E = `${E} under ${I}`), t.group(E);
    try {
      for (const L of x)
        f(S, L);
    } finally {
      t.groupEnd();
    }
  }, u = new Ot(), d = (S) => (x, I) => {
    const E = [];
    c(S, x, lt.NOTE, I, E), l(S, E, n(x));
  }, f = (S, x) => {
    if (ir(u, x))
      return;
    const I = n(x);
    jr(u, x);
    const E = [], L = o(x), $ = s(
      x,
      d(S)
    );
    L === void 0 ? t[S](`${I}:`, x.message) : c(
      S,
      x,
      lt.MESSAGE,
      L,
      E
    );
    let j = r(x);
    typeof j == "string" && j.length >= 1 && !Oo(j, `
`) && (j += `
`), t[S](j), x.cause && c(S, x, lt.CAUSE, [x.cause], E), x.errors && c(S, x, lt.ERRORS, x.errors, E);
    for (const F of $)
      c(S, x, lt.NOTE, F, E);
    l(S, E, I);
  }, h = ue(Rn, ([S, x]) => {
    const I = (...E) => {
      const L = [], $ = i(E, L);
      t[S](...$), l(S, L);
    };
    return U(I, "name", { value: S }), [S, y(I)];
  }), p = Qe(
    $n,
    ([S, x]) => S in t
  ), m = ue(p, ([S, x]) => {
    const I = (...E) => {
      t[S](...E);
    };
    return U(I, "name", { value: S }), [S, y(I)];
  }), v = mt([...h, ...m]);
  return (
    /** @type {VirtualConsole} */
    y(v)
  );
};
y(Nn);
const Ei = (t, e, r) => {
  const [n, ...o] = kn(t, e), s = $o(o, (i) => [e, ...r, i]);
  return ["", n, ...s];
}, Es = (t) => y((r) => {
  const n = [], o = (...l) => (n.length > 0 && (l = $o(
    l,
    (u) => typeof u == "string" && Mo(u, `
`) ? Ei(u, `
`, n) : [u]
  ), l = [...n, ...l]), r(...l)), s = (l, u) => ({ [l]: (...d) => u(...d) })[l], i = mt([
    ...ue(Rn, ([l]) => [
      l,
      s(l, o)
    ]),
    ...ue($n, ([l]) => [
      l,
      s(l, (...u) => o(l, ...u))
    ])
  ]);
  for (const l of ["group", "groupCollapsed"])
    i[l] && (i[l] = s(l, (...u) => {
      u.length >= 1 && o(...u), re(n, " ");
    }));
  return i.groupEnd && (i.groupEnd = s("groupEnd", (...l) => {
    _r(n);
  })), harden(i), Nn(
    /** @type {VirtualConsole} */
    i,
    t
  );
});
y(Es);
const ki = (t, e, r = void 0) => {
  const n = Qe(
    Ss,
    ([i, c]) => i in t
  ), o = ue(n, ([i, c]) => [i, y((...u) => {
    (c === void 0 || e.canLog(c)) && t[i](...u);
  })]), s = mt(o);
  return (
    /** @type {VirtualConsole} */
    y(s)
  );
};
y(ki);
const oo = (t) => {
  if (Tt === void 0)
    return;
  let e = 0;
  const r = new Re(), n = (d) => {
    da(r, d);
  }, o = new je(), s = (d) => {
    if (Ur(r, d)) {
      const f = Ve(r, d);
      n(d), t(f);
    }
  }, i = new Tt(s);
  return {
    rejectionHandledHandler: (d) => {
      const f = z(o, d);
      n(f);
    },
    unhandledRejectionHandler: (d, f) => {
      e += 1;
      const h = e;
      fe(r, h, d), pe(o, f, h), xa(i, f, h, f);
    },
    processTerminationHandler: () => {
      for (const [d, f] of fa(r))
        n(d), t(f);
    }
  };
}, tn = (t) => {
  throw _(t);
}, so = (t, e) => y((...r) => ce(t, e, r)), Pi = (t = "safe", e = "platform", r = "report", n = void 0) => {
  t === "safe" || t === "unsafe" || tn(`unrecognized consoleTaming ${t}`);
  let o;
  n === void 0 ? o = Sr : o = {
    ...Sr,
    getStackString: n
  };
  const s = (
    /** @type {VirtualConsole} */
    // eslint-disable-next-line no-nested-ternary
    typeof T.console < "u" ? T.console : typeof T.print == "function" ? (
      // Make a good-enough console for eshost (including only functions that
      // log at a specific level with no special argument interpretation).
      // https://console.spec.whatwg.org/#logging
      ((u) => y({ debug: u, log: u, info: u, warn: u, error: u }))(
        // eslint-disable-next-line no-undef
        so(T.print)
      )
    ) : void 0
  );
  if (s && s.log)
    for (const u of ["warn", "error"])
      s[u] || U(s, u, {
        value: so(s.log, s)
      });
  const i = (
    /** @type {VirtualConsole} */
    t === "unsafe" ? s : Nn(s, o)
  ), c = T.process || void 0;
  if (e !== "none" && typeof c == "object" && typeof c.on == "function") {
    let u;
    if (e === "platform" || e === "exit") {
      const { exit: d } = c;
      typeof d == "function" || tn("missing process.exit"), u = () => d(c.exitCode || -1);
    } else e === "abort" && (u = c.abort, typeof u == "function" || tn("missing process.abort"));
    c.on("uncaughtException", (d) => {
      i.error(d), u && u();
    });
  }
  if (r !== "none" && typeof c == "object" && typeof c.on == "function") {
    const d = oo((f) => {
      i.error("SES_UNHANDLED_REJECTION:", f);
    });
    d && (c.on("unhandledRejection", d.unhandledRejectionHandler), c.on("rejectionHandled", d.rejectionHandledHandler), c.on("exit", d.processTerminationHandler));
  }
  const l = T.window || void 0;
  if (e !== "none" && typeof l == "object" && typeof l.addEventListener == "function" && l.addEventListener("error", (u) => {
    u.preventDefault(), i.error(u.error), (e === "exit" || e === "abort") && (l.location.href = "about:blank");
  }), r !== "none" && typeof l == "object" && typeof l.addEventListener == "function") {
    const d = oo((f) => {
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
}, Ti = [
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
], Ai = (t) => {
  const r = mt(ue(Ti, (n) => {
    const o = t[n];
    return [n, () => ce(o, t, [])];
  }));
  return H(r, {});
}, Ii = (t) => ue(t, Ai), Ci = /\/node_modules\//, Ri = /^(?:node:)?internal\//, $i = /\/packages\/ses\/src\/error\/assert.js$/, Ni = /\/packages\/eventual-send\/src\//, Oi = [
  Ci,
  Ri,
  $i,
  Ni
], Mi = (t) => {
  if (!t)
    return !0;
  for (const e of Oi)
    if (xn(e, t))
      return !1;
  return !0;
}, Li = /^((?:.*[( ])?)[:/\w_-]*\/\.\.\.\/(.+)$/, Fi = /^((?:.*[( ])?)[:/\w_-]*\/(packages\/.+)$/, Di = [
  Li,
  Fi
], Ui = (t) => {
  for (const e of Di) {
    const r = Sn(e, t);
    if (r)
      return Lt(ca(r, 1), "");
  }
  return t;
}, ji = (t, e, r, n) => {
  if (r === "unsafe-debug")
    throw _(
      "internal: v8+unsafe-debug special case should already be done"
    );
  const o = t.captureStackTrace, s = (p) => n === "verbose" ? !0 : Mi(p.getFileName()), i = (p) => {
    let m = `${p}`;
    return n === "concise" && (m = Ui(m)), `
  at ${m}`;
  }, c = (p, m) => Lt(
    ue(Qe(m, s), i),
    ""
  ), l = new je(), u = {
    // The optional `optFn` argument is for cutting off the bottom of
    // the stack --- for capturing the stack only above the topmost
    // call to that function. Since this isn't the "real" captureStackTrace
    // but instead calls the real one, if no other cutoff is provided,
    // we cut this one off.
    captureStackTrace(p, m = u.captureStackTrace) {
      if (typeof o == "function") {
        ce(o, t, [p, m]);
        return;
      }
      Ao(p, "stack", "");
    },
    // Shim of proposed special power, to reside by default only
    // in the start compartment, for getting the stack traceback
    // string associated with an error.
    // See https://tc39.es/proposal-error-stacks/
    getStackString(p) {
      let m = z(l, p);
      if (m === void 0 && (p.stack, m = z(l, p), m || (m = { stackString: "" }, pe(l, p, m))), m.stackString !== void 0)
        return m.stackString;
      const v = c(p, m.callSites);
      return pe(l, p, { stackString: v }), v;
    },
    prepareStackTrace(p, m) {
      if (r === "unsafe") {
        const v = c(p, m);
        return pe(l, p, { stackString: v }), `${p}${v}`;
      } else
        return pe(l, p, { callSites: m }), "";
    }
  }, d = u.prepareStackTrace;
  t.prepareStackTrace = d;
  const f = new Ot([d]), h = (p) => {
    if (ir(f, p))
      return p;
    const m = {
      prepareStackTrace(v, S) {
        return pe(l, v, { callSites: S }), p(v, Ii(S));
      }
    };
    return jr(f, m.prepareStackTrace), m.prepareStackTrace;
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
}, ao = te(ae.prototype, "stack"), io = ao && ao.get, Zi = {
  getStackString(t) {
    return typeof io == "function" ? ce(io, t, []) : "stack" in t ? `${t.stack}` : "";
  }
};
let dr = Zi.getStackString;
function zi(t = "safe", e = "concise") {
  if (t !== "safe" && t !== "unsafe" && t !== "unsafe-debug")
    throw _(`unrecognized errorTaming ${t}`);
  if (e !== "concise" && e !== "verbose")
    throw _(`unrecognized stackFiltering ${e}`);
  const r = ae.prototype, { captureStackTrace: n } = ae, o = typeof n == "function" ? "v8" : "unknown", s = (l = {}) => {
    const u = function(...f) {
      let h;
      return new.target === void 0 ? h = ce(ae, this, f) : h = yr(ae, f, new.target), o === "v8" && ce(n, ae, [h, u]), h;
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
  for (const l of rs)
    So(l, c);
  if (B(i, {
    stackTraceLimit: {
      get() {
        if (typeof ae.stackTraceLimit == "number")
          return ae.stackTraceLimit;
      },
      set(l) {
        if (typeof l == "number" && typeof ae.stackTraceLimit == "number") {
          ae.stackTraceLimit = l;
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
          return ae.prepareStackTrace;
        },
        set(u) {
          ae.prepareStackTrace = u;
        },
        enumerable: !1,
        configurable: !0
      },
      captureStackTrace: {
        value: ae.captureStackTrace,
        writable: !0,
        enumerable: !1,
        configurable: !0
      }
    });
    const l = Ze(i);
    return B(c, {
      stackTraceLimit: l.stackTraceLimit,
      prepareStackTrace: l.prepareStackTrace,
      captureStackTrace: l.captureStackTrace
    }), {
      "%InitialGetStackString%": dr,
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
        U(l, "stack", {
          value: ""
        });
      },
      writable: !1,
      enumerable: !1,
      configurable: !0
    }
  }), o === "v8" ? dr = ji(
    ae,
    i,
    t,
    e
  ) : t === "unsafe" || t === "unsafe-debug" ? B(r, {
    stack: {
      get() {
        return dr(this);
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
    "%InitialGetStackString%": dr,
    "%InitialError%": i,
    "%SharedError%": c
  };
}
const Bi = () => {
}, Gi = async (t, e, r) => {
  await null;
  const n = t(...e);
  let o = vr(n);
  for (; !o.done; )
    try {
      const s = await o.value;
      o = vr(n, s);
    } catch (s) {
      o = Lo(n, r(s));
    }
  return o.value;
}, Vi = (t, e) => {
  const r = t(...e);
  let n = vr(r);
  for (; !n.done; )
    try {
      n = vr(r, n.value);
    } catch (o) {
      n = Lo(r, o);
    }
  return n.value;
}, Hi = (t, e) => y({ compartment: t, specifier: e }), Wi = (t, e, r) => {
  const n = H(null);
  for (const o of t) {
    const s = e(o, r);
    n[o] = s;
  }
  return y(n);
}, Dt = (t, e, r, n, o, s, i, c, l) => {
  const { resolveHook: u } = z(t, r), d = Wi(
    o.imports,
    u,
    n
  ), f = y({
    compartment: r,
    moduleSource: o,
    moduleSpecifier: n,
    resolvedImports: d,
    importMeta: l
  });
  for (const h of Eo(d))
    s(Et, [
      t,
      e,
      r,
      h,
      s,
      i,
      c
    ]);
  return f;
};
function* qi(t, e, r, n, o, s, i) {
  const {
    importHook: c,
    importNowHook: l,
    moduleMap: u,
    moduleMapHook: d,
    moduleRecords: f,
    parentCompartment: h
  } = z(t, r);
  if (Ur(f, n))
    return Ve(f, n);
  let p = u[n];
  if (p === void 0 && d !== void 0 && (p = d(n)), p === void 0) {
    const m = s(c, l);
    if (m === void 0) {
      const v = s(
        "importHook",
        "importNowHook"
      );
      throw Le(
        ie`${xr(v)} needed to load module ${Z(
          n
        )} in compartment ${Z(r.name)}`
      );
    }
    p = m(n), St(e, p) || (p = yield p);
  }
  if (typeof p == "string")
    throw Le(
      ie`Cannot map module ${Z(n)} to ${Z(
        p
      )} in parent compartment, use {source} module descriptor`,
      _
    );
  if (Ee(p)) {
    let m = z(e, p);
    if (m !== void 0 && (p = m), p.namespace !== void 0) {
      if (typeof p.namespace == "string") {
        const {
          compartment: x = h,
          namespace: I
        } = p;
        if (!Ee(x) || !St(t, x))
          throw Le(
            ie`Invalid compartment in module descriptor for specifier ${Z(n)} in compartment ${Z(r.name)}`
          );
        const E = yield Et(
          t,
          e,
          x,
          I,
          o,
          s,
          i
        );
        return fe(f, n, E), E;
      }
      if (Ee(p.namespace)) {
        const { namespace: x } = p;
        if (m = z(e, x), m !== void 0)
          p = m;
        else {
          const I = At(x), $ = Dt(
            t,
            e,
            r,
            n,
            {
              imports: [],
              exports: I,
              execute(j) {
                for (const F of I)
                  j[F] = x[F];
              }
            },
            o,
            s,
            i,
            void 0
          );
          return fe(f, n, $), $;
        }
      } else
        throw Le(
          ie`Invalid compartment in module descriptor for specifier ${Z(n)} in compartment ${Z(r.name)}`
        );
    }
    if (p.source !== void 0)
      if (typeof p.source == "string") {
        const {
          source: x,
          specifier: I = n,
          compartment: E = h,
          importMeta: L = void 0
        } = p, $ = yield Et(
          t,
          e,
          E,
          x,
          o,
          s,
          i
        ), { moduleSource: j } = $, F = Dt(
          t,
          e,
          r,
          I,
          j,
          o,
          s,
          i,
          L
        );
        return fe(f, n, F), F;
      } else {
        const {
          source: x,
          specifier: I = n,
          importMeta: E
        } = p, L = Dt(
          t,
          e,
          r,
          I,
          x,
          o,
          s,
          i,
          E
        );
        return fe(f, n, L), L;
      }
    if (p.archive !== void 0)
      throw Le(
        ie`Unsupported archive module descriptor for specifier ${Z(n)} in compartment ${Z(r.name)}`
      );
    if (p.record !== void 0) {
      const {
        compartment: x = r,
        specifier: I = n,
        record: E,
        importMeta: L
      } = p, $ = Dt(
        t,
        e,
        x,
        I,
        E,
        o,
        s,
        i,
        L
      );
      return fe(f, n, $), fe(f, I, $), $;
    }
    if (p.compartment !== void 0 && p.specifier !== void 0) {
      if (!Ee(p.compartment) || !St(t, p.compartment) || typeof p.specifier != "string")
        throw Le(
          ie`Invalid compartment in module descriptor for specifier ${Z(n)} in compartment ${Z(r.name)}`
        );
      const x = yield Et(
        t,
        e,
        p.compartment,
        p.specifier,
        o,
        s,
        i
      );
      return fe(f, n, x), x;
    }
    const S = Dt(
      t,
      e,
      r,
      n,
      p,
      o,
      s,
      i
    );
    return fe(f, n, S), S;
  } else
    throw Le(
      ie`module descriptor must be a string or object for specifier ${Z(
        n
      )} in compartment ${Z(r.name)}`
    );
}
const Et = (t, e, r, n, o, s, i) => {
  const { name: c } = z(
    t,
    r
  );
  let l = Ve(i, r);
  l === void 0 && (l = new Re(), fe(i, r, l));
  let u = Ve(l, n);
  return u !== void 0 || (u = s(Gi, Vi)(
    qi,
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
      throw Br(
        d,
        ie`${d.message}, loading ${Z(n)} in compartment ${Z(
          c
        )}`
      ), d;
    }
  ), fe(l, n, u)), u;
}, Ki = () => {
  const t = new Nt(), e = [];
  return { enqueueJob: (o, s) => {
    bn(
      t,
      Do(o(...s), Bi, (i) => {
        re(e, i);
      })
    );
  }, drainQueue: async () => {
    await null;
    for (const o of t)
      await o;
    return e;
  } };
}, ks = ({ errors: t, errorPrefix: e }) => {
  if (t.length > 0) {
    const r = ge("COMPARTMENT_LOAD_ERRORS", "", ["verbose"]) === "verbose";
    throw _(
      `${e} (${t.length} underlying failures: ${Lt(
        ue(t, (n) => n.message + (r ? n.stack : "")),
        ", "
      )}`
    );
  }
}, Yi = (t, e) => e, Ji = (t, e) => t, co = async (t, e, r, n) => {
  const { name: o } = z(
    t,
    r
  ), s = new Re(), { enqueueJob: i, drainQueue: c } = Ki();
  i(Et, [
    t,
    e,
    r,
    n,
    i,
    Ji,
    s
  ]);
  const l = await c();
  ks({
    errors: l,
    errorPrefix: `Failed to load module ${Z(n)} in package ${Z(
      o
    )}`
  });
}, Xi = (t, e, r, n) => {
  const { name: o } = z(
    t,
    r
  ), s = new Re(), i = [], c = (l, u) => {
    try {
      l(...u);
    } catch (d) {
      re(i, d);
    }
  };
  c(Et, [
    t,
    e,
    r,
    n,
    c,
    Yi,
    s
  ]), ks({
    errors: i,
    errorPrefix: `Failed to load module ${Z(n)} in package ${Z(
      o
    )}`
  });
}, { quote: yt } = Q, Qi = () => {
  let t = !1;
  const e = H(null, {
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
    exportsProxy: new Nr(e, {
      get(r, n, o) {
        if (!t)
          throw _(
            `Cannot get property ${yt(
              n
            )} of module exports namespace, the module has not yet begun to execute`
          );
        return na(e, n, o);
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
        return To(e, n);
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
        return Ge(e);
      },
      getOwnPropertyDescriptor(r, n) {
        if (!t)
          throw _(
            `Cannot get own property descriptor ${yt(
              n
            )}, the module has not yet begun to execute`
          );
        return oa(e, n);
      },
      preventExtensions(r) {
        if (!t)
          throw _(
            "Cannot prevent extensions of module exports namespace, the module has not yet begun to execute"
          );
        return aa(e);
      },
      isExtensible() {
        if (!t)
          throw _(
            "Cannot check extensibility of module exports namespace, the module has not yet begun to execute"
          );
        return sa(e);
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
}, On = (t, e, r, n) => {
  const { deferredExports: o } = e;
  if (!Ur(o, n)) {
    const s = Qi();
    pe(
      r,
      s.exportsProxy,
      Hi(t, n)
    ), fe(o, n, s);
  }
  return Ve(o, n);
}, ec = (t, e) => {
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
      Ze(n)
    )), { safeEvaluate: o } = Cn({
      globalObject: i,
      moduleLexicals: c,
      globalTransforms: s,
      sloppyGlobalsMode: r
    });
  }
  return { safeEvaluate: o };
}, Ps = (t, e, r) => {
  if (typeof e != "string")
    throw _("first argument of evaluate() must be a string");
  const {
    transforms: n = [],
    __evadeHtmlCommentTest__: o = !1,
    __evadeImportExpressionTest__: s = !1,
    __rejectSomeDirectEvalExpressions__: i = !0
    // Note default on
  } = r, c = [...n];
  o === !0 && re(c, ms), s === !0 && re(c, _s), i === !0 && re(c, vs);
  const { safeEvaluate: l } = ec(
    t,
    r
  );
  return l(e, {
    localTransforms: c
  });
}, { quote: fr } = Q, tc = (t, e, r, n, o, s) => {
  const { exportsProxy: i, exportsTarget: c, activate: l } = On(
    r,
    z(t, r),
    n,
    o
  ), u = H(null);
  if (e.exports) {
    if (!xt(e.exports) || la(e.exports, (f) => typeof f != "string"))
      throw _(
        `SES virtual module source "exports" property must be an array of strings for module ${o}`
      );
    dt(e.exports, (f) => {
      let h = c[f];
      const p = [];
      U(c, f, {
        get: () => h,
        set: (S) => {
          h = S;
          for (const x of p)
            x(S);
        },
        enumerable: !0,
        configurable: !1
      }), u[f] = (S) => {
        re(p, S), S(h);
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
      if (To(d, "errorFromExecute"))
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
}, rc = (t, e, r, n) => {
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
  } = i, v = z(t, o), { __shimTransforms__: S, importMetaHook: x } = v, { exportsProxy: I, exportsTarget: E, activate: L } = On(
    o,
    v,
    e,
    s
  ), $ = H(null), j = H(null), F = H(null), Y = H(null), J = H(null);
  c && Or(J, c), p && x && x(s, J);
  const We = H(null), ot = H(null);
  dt(he(d), ([be, [W]]) => {
    let q = We[W];
    if (!q) {
      let oe, se = !0, me = [];
      const ee = () => {
        if (se)
          throw zt(`binding ${fr(W)} not yet initialized`);
        return oe;
      }, Pe = y((Te) => {
        if (!se)
          throw _(
            `Internal: binding ${fr(W)} already initialized`
          );
        oe = Te;
        const jn = me;
        me = null, se = !1;
        for (const Ae of jn || [])
          Ae(Te);
        return Te;
      });
      q = {
        get: ee,
        notify: (Te) => {
          Te !== Pe && (se ? re(me || [], Te) : Te(oe));
        }
      }, We[W] = q, F[W] = Pe;
    }
    $[be] = {
      get: q.get,
      set: void 0,
      enumerable: !0,
      configurable: !1
    }, ot[be] = q.notify;
  }), dt(
    he(f),
    ([be, [W, q]]) => {
      let oe = We[W];
      if (!oe) {
        let se, me = !0;
        const ee = [], Pe = () => {
          if (me)
            throw zt(
              `binding ${fr(be)} not yet initialized`
            );
          return se;
        }, gt = y((Ae) => {
          se = Ae, me = !1;
          for (const Hr of ee)
            Hr(Ae);
        }), Te = (Ae) => {
          if (me)
            throw zt(`binding ${fr(W)} not yet initialized`);
          se = Ae;
          for (const Hr of ee)
            Hr(Ae);
        };
        oe = {
          get: Pe,
          notify: (Ae) => {
            Ae !== gt && (re(ee, Ae), me || Ae(se));
          }
        }, We[W] = oe, q && U(j, W, {
          get: Pe,
          set: Te,
          enumerable: !0,
          configurable: !1
        }), Y[W] = gt;
      }
      $[be] = {
        get: oe.get,
        set: void 0,
        enumerable: !0,
        configurable: !1
      }, ot[be] = oe.notify;
    }
  );
  const qe = (be) => {
    be(E);
  };
  ot["*"] = qe;
  function lr(be) {
    const W = H(null);
    W.default = !1;
    for (const [q, oe] of be) {
      const se = Ve(n, q);
      se.execute();
      const { notifiers: me } = se;
      for (const [ee, Pe] of oe) {
        const gt = me[ee];
        if (!gt)
          throw or(
            `The requested module '${q}' does not provide an export named '${ee}'`
          );
        for (const Te of Pe)
          gt(Te);
      }
      if (Dr(l, q))
        for (const [ee, Pe] of he(
          me
        ))
          W[ee] === void 0 ? W[ee] = Pe : W[ee] = !1;
      if (h[q])
        for (const [ee, Pe] of h[q])
          W[Pe] = me[ee];
    }
    for (const [q, oe] of he(W))
      if (!ot[q] && oe !== !1) {
        ot[q] = oe;
        let se;
        oe((ee) => se = ee), $[q] = {
          get() {
            return se;
          },
          set: void 0,
          enumerable: !0,
          configurable: !1
        };
      }
    dt(
      No(xo($)),
      (q) => U(E, q, $[q])
    ), y(E), L();
  }
  let Ft;
  m !== void 0 ? Ft = m : Ft = Ps(v, u, {
    globalObject: o.globalThis,
    transforms: S,
    __moduleShimLexicals__: j
  });
  let Dn = !1, Un;
  function zs() {
    if (Ft) {
      const be = Ft;
      Ft = null;
      try {
        be(
          y({
            imports: y(lr),
            onceVar: y(F),
            liveVar: y(Y),
            importMeta: J
          })
        );
      } catch (W) {
        Dn = !0, Un = W;
      }
    }
    if (Dn)
      throw Un;
  }
  return y({
    notifiers: ot,
    exportsProxy: I,
    execute: zs
  });
}, { Fail: ut, quote: X } = Q, Ts = (t, e, r, n) => {
  const { name: o, moduleRecords: s } = z(
    t,
    r
  ), i = Ve(s, n);
  if (i === void 0)
    throw zt(
      `Missing link to module ${X(n)} from compartment ${X(
        o
      )}`
    );
  return cc(t, e, i);
};
function nc(t) {
  return typeof t.__syncModuleProgram__ == "string";
}
function oc(t, e) {
  const { __fixedExportMap__: r, __liveExportMap__: n } = t;
  Ee(r) || ut`Property '__fixedExportMap__' of a precompiled module source must be an object, got ${X(
    r
  )}, for module ${X(e)}`, Ee(n) || ut`Property '__liveExportMap__' of a precompiled module source must be an object, got ${X(
    n
  )}, for module ${X(e)}`;
}
function sc(t) {
  return typeof t.execute == "function";
}
function ac(t, e) {
  const { exports: r } = t;
  xt(r) || ut`Property 'exports' of a third-party module source must be an array, got ${X(
    r
  )}, for module ${X(e)}`;
}
function ic(t, e) {
  Ee(t) || ut`Module sources must be of type object, got ${X(
    t
  )}, for module ${X(e)}`;
  const { imports: r, exports: n, reexports: o = [] } = t;
  xt(r) || ut`Property 'imports' of a module source must be an array, got ${X(
    r
  )}, for module ${X(e)}`, xt(n) || ut`Property 'exports' of a precompiled module source must be an array, got ${X(
    n
  )}, for module ${X(e)}`, xt(o) || ut`Property 'reexports' of a precompiled module source must be an array if present, got ${X(
    o
  )}, for module ${X(e)}`;
}
const cc = (t, e, r) => {
  const { compartment: n, moduleSpecifier: o, resolvedImports: s, moduleSource: i } = r, { instances: c } = z(t, n);
  if (Ur(c, o))
    return Ve(c, o);
  ic(i, o);
  const l = new Re();
  let u;
  if (nc(i))
    oc(i, o), u = rc(
      t,
      e,
      r,
      l
    );
  else if (sc(i))
    ac(i, o), u = tc(
      t,
      i,
      n,
      e,
      o,
      s
    );
  else
    throw _(
      `importHook must provide a module source, got ${X(i)}`
    );
  fe(c, o, u);
  for (const [d, f] of he(s)) {
    const h = Ts(
      t,
      e,
      n,
      f
    );
    fe(l, d, h);
  }
  return u;
}, Ut = new je(), Me = new je(), Mn = function(e = {}, r = {}, n = {}) {
  throw _(
    "Compartment.prototype.constructor is not a valid constructor."
  );
}, lo = (t, e) => {
  const { execute: r, exportsProxy: n } = Ts(
    Me,
    Ut,
    t,
    e
  );
  return r(), n;
}, Ln = {
  constructor: Mn,
  get globalThis() {
    return z(Me, this).globalObject;
  },
  get name() {
    return z(Me, this).name;
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
    const r = z(Me, this);
    return Ps(r, t, e);
  },
  module(t) {
    if (typeof t != "string")
      throw _("first argument of module() must be a string");
    const { exportsProxy: e } = On(
      this,
      z(Me, this),
      Ut,
      t
    );
    return e;
  },
  async import(t) {
    const { noNamespaceBox: e } = z(Me, this);
    if (typeof t != "string")
      throw _("first argument of import() must be a string");
    return Do(
      co(Me, Ut, this, t),
      () => {
        const r = lo(
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
    return co(Me, Ut, this, t);
  },
  importNow(t) {
    if (typeof t != "string")
      throw _("first argument of importNow() must be a string");
    return Xi(Me, Ut, this, t), lo(
      /** @type {Compartment} */
      this,
      t
    );
  }
};
B(Ln, {
  [Xe]: {
    value: "Compartment",
    writable: !1,
    enumerable: !1,
    configurable: !0
  }
});
B(Mn, {
  prototype: { value: Ln }
});
const lc = (...t) => {
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
    return qn(
      n.modules,
      void 0,
      "Compartment constructor must receive either a module map argument or modules option, not both"
    ), qn(
      n.globals,
      void 0,
      "Compartment constructor must receive either globals argument or option, not both"
    ), {
      ...n,
      globals: e,
      modules: r
    };
  }
}, dn = (t, e, r, n = void 0) => {
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
      importMetaHook: v,
      __noNamespaceBox__: S = !1
    } = lc(...s), x = [...c, ...l], I = { __proto__: null, ...u }, E = { __proto__: null, ...d }, L = new Re(), $ = new Re(), j = new Re(), F = {};
    ci(F), is(F);
    const { safeEvaluate: Y } = Cn({
      globalObject: F,
      globalTransforms: x,
      sloppyGlobalsMode: !1
    });
    cs(F, {
      intrinsics: e,
      newGlobalPropertyNames: ts,
      makeCompartmentConstructor: t,
      parentCompartment: this,
      markVirtualizedNativeFunction: r
    }), un(
      F,
      Y,
      r
    ), Or(F, I), pe(Me, this, {
      name: `${i}`,
      globalTransforms: x,
      globalObject: F,
      safeEvaluate: Y,
      resolveHook: f,
      importHook: h,
      importNowHook: p,
      moduleMap: E,
      moduleMapHook: m,
      importMetaHook: v,
      moduleRecords: L,
      __shimTransforms__: l,
      deferredExports: j,
      instances: $,
      parentCompartment: n,
      noNamespaceBox: S
    });
  }
  return o.prototype = Ln, o;
};
function rn(t) {
  return V(t).constructor;
}
function uc() {
  return arguments;
}
const dc = () => {
  const t = Se.prototype.constructor, e = te(uc(), "callee"), r = e && e.get, n = _a(new ve()), o = V(n), s = Lr[ko] && ma(/./), i = s && V(s), c = ua([]), l = V(c), u = V(Vs), d = pa(new Re()), f = V(d), h = ha(new Nt()), p = V(h), m = V(l);
  function* v() {
  }
  const S = rn(v), x = S.prototype;
  async function* I() {
  }
  const E = rn(
    I
  ), L = E.prototype, $ = L.prototype, j = V($);
  async function F() {
  }
  const Y = rn(F), J = {
    "%InertFunction%": t,
    "%ArrayIteratorPrototype%": l,
    "%InertAsyncFunction%": Y,
    "%AsyncGenerator%": L,
    "%InertAsyncGeneratorFunction%": E,
    "%AsyncGeneratorPrototype%": $,
    "%AsyncIteratorPrototype%": j,
    "%Generator%": x,
    "%InertGeneratorFunction%": S,
    "%IteratorPrototype%": m,
    "%MapIteratorPrototype%": f,
    "%RegExpStringIteratorPrototype%": i,
    "%SetIteratorPrototype%": p,
    "%StringIteratorPrototype%": o,
    "%ThrowTypeError%": r,
    "%TypedArray%": u,
    "%InertCompartment%": Mn
  };
  return T.Iterator && (J["%IteratorHelperPrototype%"] = V(
    // eslint-disable-next-line @endo/no-polymorphic-call
    T.Iterator.from([]).take(0)
  ), J["%WrapForValidIteratorPrototype%"] = V(
    // eslint-disable-next-line @endo/no-polymorphic-call
    T.Iterator.from({ next() {
    } })
  )), T.AsyncIterator && (J["%AsyncIteratorHelperPrototype%"] = V(
    // eslint-disable-next-line @endo/no-polymorphic-call
    T.AsyncIterator.from([]).take(0)
  ), J["%WrapForValidAsyncIteratorPrototype%"] = V(
    // eslint-disable-next-line @endo/no-polymorphic-call
    T.AsyncIterator.from({ next() {
    } })
  )), J;
}, As = (t, e) => {
  if (e !== "safe" && e !== "unsafe")
    throw _(`unrecognized fakeHardenOption ${e}`);
  if (e === "safe" || (Object.isExtensible = () => !1, Object.isFrozen = () => !0, Object.isSealed = () => !0, Reflect.isExtensible = () => !1, t.isFake))
    return t;
  const r = (n) => n;
  return r.isFake = !0, y(r);
};
y(As);
const fc = () => {
  const t = wt, e = t.prototype, r = wa(wt, void 0);
  B(e, {
    constructor: {
      value: r
      // leave other `constructor` attributes as is
    }
  });
  const n = he(
    Ze(t)
  ), o = mt(
    ue(n, ([s, i]) => [
      s,
      { ...i, configurable: !0 }
    ])
  );
  return B(r, o), { "%SharedSymbol%": r };
}, pc = (t) => {
  try {
    return t(), !1;
  } catch {
    return !0;
  }
}, uo = (t, e, r) => {
  if (t === void 0)
    return !1;
  const n = te(t, e);
  if (!n || "value" in n)
    return !1;
  const { get: o, set: s } = n;
  if (typeof o != "function" || typeof s != "function" || o() !== r || ce(o, t, []) !== r)
    return !1;
  const i = "Seems to be a setter", c = { __proto__: null };
  if (ce(s, c, [i]), c[e] !== i)
    return !1;
  const l = { __proto__: t };
  return ce(s, l, [i]), l[e] !== i || !pc(() => ce(s, t, [r])) || "originalValue" in o || n.configurable === !1 ? !1 : (U(t, e, {
    value: r,
    writable: !0,
    enumerable: n.enumerable,
    configurable: !0
  }), !0);
}, hc = (t) => {
  uo(
    t["%IteratorPrototype%"],
    "constructor",
    t.Iterator
  ), uo(
    t["%IteratorPrototype%"],
    Xe,
    "Iterator"
  );
}, { Fail: fo, details: po, quote: ho } = Q;
let pr, hr;
const mc = za(), gc = () => {
  let t = !1;
  try {
    t = Se(
      "eval",
      "SES_changed",
      `        eval("SES_changed = true");
        return SES_changed;
      `
    )(Uo, !1), t || delete T.SES_changed;
  } catch {
    t = !0;
  }
  if (!t)
    throw _(
      "SES cannot initialize unless 'eval' is the original intrinsic 'eval', suitable for direct-eval (dynamically scoped eval) (SES_DIRECT_EVAL)"
    );
}, Is = (t = {}) => {
  const {
    errorTaming: e = ge("LOCKDOWN_ERROR_TAMING", "safe"),
    errorTrapping: r = (
      /** @type {"platform" | "none" | "report" | "abort" | "exit" | undefined} */
      ge("LOCKDOWN_ERROR_TRAPPING", "platform")
    ),
    unhandledRejectionTrapping: n = (
      /** @type {"none" | "report" | undefined} */
      ge("LOCKDOWN_UNHANDLED_REJECTION_TRAPPING", "report")
    ),
    regExpTaming: o = ge("LOCKDOWN_REGEXP_TAMING", "safe"),
    localeTaming: s = ge("LOCKDOWN_LOCALE_TAMING", "safe"),
    consoleTaming: i = (
      /** @type {'unsafe' | 'safe' | undefined} */
      ge("LOCKDOWN_CONSOLE_TAMING", "safe")
    ),
    overrideTaming: c = ge("LOCKDOWN_OVERRIDE_TAMING", "moderate"),
    stackFiltering: l = ge("LOCKDOWN_STACK_FILTERING", "concise"),
    domainTaming: u = ge("LOCKDOWN_DOMAIN_TAMING", "safe"),
    evalTaming: d = ge("LOCKDOWN_EVAL_TAMING", "safeEval"),
    overrideDebug: f = Qe(
      kn(ge("LOCKDOWN_OVERRIDE_DEBUG", ""), ","),
      /** @param {string} debugName */
      (qe) => qe !== ""
    ),
    __hardenTaming__: h = ge("LOCKDOWN_HARDEN_TAMING", "safe"),
    dateTaming: p = "safe",
    // deprecated
    mathTaming: m = "safe",
    // deprecated
    ...v
  } = t;
  d === "unsafeEval" || d === "safeEval" || d === "noEval" || fo`lockdown(): non supported option evalTaming: ${ho(d)}`;
  const S = Ge(v);
  if (S.length === 0 || fo`lockdown(): non supported option ${ho(S)}`, pr === void 0 || // eslint-disable-next-line @endo/no-polymorphic-call
  Q.fail(
    po`Already locked down at ${pr} (SES_ALREADY_LOCKED_DOWN)`,
    _
  ), pr = _("Prior lockdown (SES_ALREADY_LOCKED_DOWN)"), pr.stack, gc(), T.Function.prototype.constructor !== T.Function && // @ts-ignore harden is absent on globalThis type def.
  typeof T.harden == "function" && // @ts-ignore lockdown is absent on globalThis type def.
  typeof T.lockdown == "function" && T.Date.prototype.constructor !== T.Date && typeof T.Date.now == "function" && // @ts-ignore does not recognize that Date constructor is a special
  // Function.
  // eslint-disable-next-line @endo/no-polymorphic-call
  Mr(T.Date.prototype.constructor.now(), NaN))
    throw _(
      "Already locked down but not by this SES instance (SES_MULTIPLE_INSTANCES)"
    );
  xi(u);
  const I = xs(), { addIntrinsics: E, completePrototypes: L, finalIntrinsics: $ } = os(), j = As(mc, h);
  E({ harden: j }), E(Ka()), E(Ya(p)), E(zi(e, l)), E(Ja(m)), E(Xa(o)), E(fc()), E(dc()), L();
  const F = $(), Y = { __proto__: null };
  typeof T.Buffer == "function" && (Y.Buffer = T.Buffer);
  let J;
  e === "safe" && (J = F["%InitialGetStackString%"]);
  const We = Pi(
    i,
    r,
    n,
    J
  );
  if (T.console = /** @type {Console} */
  We.console, typeof /** @type {any} */
  We.console._times == "object" && (Y.SafeMap = V(
    // eslint-disable-next-line no-underscore-dangle
    /** @type {any} */
    We.console._times
  )), (e === "unsafe" || e === "unsafe-debug") && T.assert === Q && (T.assert = Gr(void 0, !0)), si(F, s), hc(F), qa(F, I), is(T), cs(T, {
    intrinsics: F,
    newGlobalPropertyNames: Kn,
    makeCompartmentConstructor: dn,
    markVirtualizedNativeFunction: I
  }), d === "noEval")
    un(
      T,
      Sa,
      I
    );
  else if (d === "safeEval") {
    const { safeEvaluate: qe } = Cn({ globalObject: T });
    un(
      T,
      qe,
      I
    );
  }
  return () => {
    hr === void 0 || // eslint-disable-next-line @endo/no-polymorphic-call
    Q.fail(
      po`Already locked down at ${hr} (SES_ALREADY_LOCKED_DOWN)`,
      _
    ), hr = _(
      "Prior lockdown (SES_ALREADY_LOCKED_DOWN)"
    ), hr.stack, ti(F, c, f);
    const qe = {
      intrinsics: F,
      hostIntrinsics: Y,
      globals: {
        // Harden evaluators
        Function: T.Function,
        eval: T.eval,
        // @ts-ignore Compartment does exist on globalThis
        Compartment: T.Compartment,
        // Harden Symbol
        Symbol: T.Symbol
      }
    };
    for (const lr of At(Kn))
      qe.globals[lr] = T[lr];
    return j(qe), j;
  };
};
T.lockdown = (t) => {
  const e = Is(t);
  T.harden = e();
};
T.repairIntrinsics = (t) => {
  const e = Is(t);
  T.hardenIntrinsics = () => {
    T.harden = e();
  };
};
const yc = xs();
T.Compartment = dn(
  dn,
  Wa(T),
  yc
);
T.assert = Q;
const _c = Es(Sr), vc = ea(
  "MAKE_CAUSAL_CONSOLE_FROM_LOGGER_KEY_FOR_SES_AVA"
);
T[vc] = _c;
const bc = (t, e) => {
  let r = { x: 0, y: 0 }, n = { x: 0, y: 0 }, o = { x: 0, y: 0 };
  const s = (l) => {
    const { clientX: u, clientY: d } = l, f = u - o.x + n.x, h = d - o.y + n.y;
    r = { x: f, y: h }, t.style.transform = `translate(${f}px, ${h}px)`, e == null || e();
  }, i = () => {
    document.removeEventListener("mousemove", s), document.removeEventListener("mouseup", i);
  }, c = (l) => {
    o = { x: l.clientX, y: l.clientY }, n = { x: r.x, y: r.y }, document.addEventListener("mousemove", s), document.addEventListener("mouseup", i);
  };
  return t.addEventListener("mousedown", c), i;
}, wc = ":host{--spacing-4: .25rem;--spacing-8: calc(var(--spacing-4) * 2);--spacing-12: calc(var(--spacing-4) * 3);--spacing-16: calc(var(--spacing-4) * 4);--spacing-20: calc(var(--spacing-4) * 5);--spacing-24: calc(var(--spacing-4) * 6);--spacing-28: calc(var(--spacing-4) * 7);--spacing-32: calc(var(--spacing-4) * 8);--spacing-36: calc(var(--spacing-4) * 9);--spacing-40: calc(var(--spacing-4) * 10);--font-weight-regular: 400;--font-weight-bold: 500;--font-line-height-s: 1.2;--font-line-height-m: 1.4;--font-line-height-l: 1.5;--font-size-s: 12px;--font-size-m: 14px;--font-size-l: 16px}[data-theme]{background-color:var(--color-background-primary);color:var(--color-foreground-secondary)}.wrapper{box-sizing:border-box;display:flex;flex-direction:column;position:fixed;inset-block-start:var(--modal-block-start);inset-inline-end:var(--modal-inline-end);z-index:1000;padding:25px;border-radius:15px;border:2px solid var(--color-background-quaternary);box-shadow:0 0 10px #0000004d}.header{align-items:center;display:flex;justify-content:space-between;border-block-end:2px solid var(--color-background-quaternary);padding-block-end:var(--spacing-4)}button{background:transparent;border:0;cursor:pointer;padding:0}h1{font-size:var(--font-size-s);font-weight:var(--font-weight-bold);margin:0;margin-inline-end:var(--spacing-4);-webkit-user-select:none;user-select:none}iframe{border:none;inline-size:100%;block-size:100%}", xc = `
<svg width="16"  height="16"xmlns="http://www.w3.org/2000/svg" fill="none"><g class="fills"><rect rx="0" ry="0" width="16" height="16" class="frame-background"/></g><g class="frame-children"><path d="M11.997 3.997 8 8l-3.997 4.003m-.006-8L8 8l4.003 3.997" class="fills"/><g class="strokes"><path d="M11.997 3.997 8 8l-3.997 4.003m-.006-8L8 8l4.003 3.997" style="fill: none; stroke-width: 1; stroke: rgb(143, 157, 163); stroke-opacity: 1; stroke-linecap: round;" class="stroke-shape"/></g></g></svg>`;
var ye, nr;
class Sc extends HTMLElement {
  constructor() {
    super();
    Wr(this, ye, null);
    Wr(this, nr, null);
    this.attachShadow({ mode: "open" });
  }
  setTheme(r) {
    Ie(this, ye) && Ie(this, ye).setAttribute("data-theme", r);
  }
  disconnectedCallback() {
    var r;
    (r = Ie(this, nr)) == null || r.call(this);
  }
  calculateZIndex() {
    const r = document.querySelectorAll("plugin-modal"), n = Array.from(r).filter((s) => s !== this).map((s) => Number(s.style.zIndex)), o = Math.max(...n, 0);
    this.style.zIndex = (o + 1).toString();
  }
  connectedCallback() {
    const r = this.getAttribute("title"), n = this.getAttribute("iframe-src"), o = Number(this.getAttribute("width") || "300"), s = Number(this.getAttribute("height") || "400"), i = this.getAttribute("allow-downloads") || !1;
    if (!r || !n)
      throw new Error("title and iframe-src attributes are required");
    if (!this.shadowRoot)
      throw new Error("Error creating shadow root");
    qr(this, ye, document.createElement("div")), Ie(this, ye).classList.add("wrapper"), Ie(this, ye).style.inlineSize = `${o}px`, Ie(this, ye).style.blockSize = `${s}px`, qr(this, nr, bc(Ie(this, ye), () => {
      this.calculateZIndex();
    }));
    const c = document.createElement("div");
    c.classList.add("header");
    const l = document.createElement("h1");
    l.textContent = r, c.appendChild(l);
    const u = document.createElement("button");
    u.setAttribute("type", "button"), u.innerHTML = `<div class="close">${xc}</div>`, u.addEventListener("click", () => {
      this.shadowRoot && this.shadowRoot.dispatchEvent(
        new CustomEvent("close", {
          composed: !0,
          bubbles: !0
        })
      );
    }), c.appendChild(u);
    const d = document.createElement("iframe");
    d.src = n, d.allow = "", d.sandbox.add(
      "allow-scripts",
      "allow-forms",
      "allow-modals",
      "allow-popups",
      "allow-popups-to-escape-sandbox",
      "allow-storage-access-by-user-activation"
    ), i && d.sandbox.add("allow-downloads"), d.addEventListener("load", () => {
      var h;
      (h = this.shadowRoot) == null || h.dispatchEvent(
        new CustomEvent("load", {
          composed: !0,
          bubbles: !0
        })
      );
    }), this.addEventListener("message", (h) => {
      d.contentWindow && d.contentWindow.postMessage(h.detail, "*");
    }), this.shadowRoot.appendChild(Ie(this, ye)), Ie(this, ye).appendChild(c), Ie(this, ye).appendChild(d);
    const f = document.createElement("style");
    f.textContent = wc, this.shadowRoot.appendChild(f), this.calculateZIndex();
  }
}
ye = new WeakMap(), nr = new WeakMap();
customElements.define("plugin-modal", Sc);
var D;
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
})(D || (D = {}));
var fn;
(function(t) {
  t.mergeShapes = (e, r) => ({
    ...e,
    ...r
    // second overwrites first
  });
})(fn || (fn = {}));
const w = D.arrayToEnum([
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
}, g = D.arrayToEnum([
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
]), Ec = (t) => JSON.stringify(t, null, 2).replace(/"([^"]+)":/g, "$1:");
class _e extends Error {
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
    if (!(e instanceof _e))
      throw new Error(`Not a ZodError: ${e}`);
  }
  toString() {
    return this.message;
  }
  get message() {
    return JSON.stringify(this.issues, D.jsonStringifyReplacer, 2);
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
_e.create = (t) => new _e(t);
const Ct = (t, e) => {
  let r;
  switch (t.code) {
    case g.invalid_type:
      t.received === w.undefined ? r = "Required" : r = `Expected ${t.expected}, received ${t.received}`;
      break;
    case g.invalid_literal:
      r = `Invalid literal value, expected ${JSON.stringify(t.expected, D.jsonStringifyReplacer)}`;
      break;
    case g.unrecognized_keys:
      r = `Unrecognized key(s) in object: ${D.joinValues(t.keys, ", ")}`;
      break;
    case g.invalid_union:
      r = "Invalid input";
      break;
    case g.invalid_union_discriminator:
      r = `Invalid discriminator value. Expected ${D.joinValues(t.options)}`;
      break;
    case g.invalid_enum_value:
      r = `Invalid enum value. Expected ${D.joinValues(t.options)}, received '${t.received}'`;
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
      typeof t.validation == "object" ? "includes" in t.validation ? (r = `Invalid input: must include "${t.validation.includes}"`, typeof t.validation.position == "number" && (r = `${r} at one or more positions greater than or equal to ${t.validation.position}`)) : "startsWith" in t.validation ? r = `Invalid input: must start with "${t.validation.startsWith}"` : "endsWith" in t.validation ? r = `Invalid input: must end with "${t.validation.endsWith}"` : D.assertNever(t.validation) : t.validation !== "regex" ? r = `Invalid ${t.validation}` : r = "Invalid";
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
      r = e.defaultError, D.assertNever(t);
  }
  return { message: r };
};
let Cs = Ct;
function kc(t) {
  Cs = t;
}
function Pr() {
  return Cs;
}
const Tr = (t) => {
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
}, Pc = [];
function b(t, e) {
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
class ne {
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
        return R;
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
    return ne.mergeObjectSync(e, n);
  }
  static mergeObjectSync(e, r) {
    const n = {};
    for (const o of r) {
      const { key: s, value: i } = o;
      if (s.status === "aborted" || i.status === "aborted")
        return R;
      s.status === "dirty" && e.dirty(), i.status === "dirty" && e.dirty(), s.value !== "__proto__" && (typeof i.value < "u" || o.alwaysSet) && (n[s.value] = i.value);
    }
    return { status: e.value, value: n };
  }
}
const R = Object.freeze({
  status: "aborted"
}), bt = (t) => ({ status: "dirty", value: t }), de = (t) => ({ status: "valid", value: t }), pn = (t) => t.status === "aborted", hn = (t) => t.status === "dirty", Bt = (t) => t.status === "valid", Gt = (t) => typeof Promise < "u" && t instanceof Promise;
function Ar(t, e, r, n) {
  if (typeof e == "function" ? t !== e || !n : !e.has(t)) throw new TypeError("Cannot read private member from an object whose class did not declare it");
  return e.get(t);
}
function Rs(t, e, r, n, o) {
  if (typeof e == "function" ? t !== e || !o : !e.has(t)) throw new TypeError("Cannot write private member to an object whose class did not declare it");
  return e.set(t, r), r;
}
var k;
(function(t) {
  t.errToObj = (e) => typeof e == "string" ? { message: e } : e || {}, t.toString = (e) => typeof e == "string" ? e : e == null ? void 0 : e.message;
})(k || (k = {}));
var jt, Zt;
class De {
  constructor(e, r, n, o) {
    this._cachedPath = [], this.parent = e, this.data = r, this._path = n, this._key = o;
  }
  get path() {
    return this._cachedPath.length || (this._key instanceof Array ? this._cachedPath.push(...this._path, ...this._key) : this._cachedPath.push(...this._path, this._key)), this._cachedPath;
  }
}
const mo = (t, e) => {
  if (Bt(e))
    return { success: !0, data: e.value };
  if (!t.common.issues.length)
    throw new Error("Validation failed but no issues detected.");
  return {
    success: !1,
    get error() {
      if (this._error)
        return this._error;
      const r = new _e(t.common.issues);
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
      status: new ne(),
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
    }, s = this._parseSync({ data: e, path: o.path, parent: o });
    return mo(o, s);
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
    }, o = this._parse({ data: e, path: n.path, parent: n }), s = await (Gt(o) ? o : Promise.resolve(o));
    return mo(n, s);
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
    return new Ne({
      schema: this,
      typeName: C.ZodEffects,
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
    return nt.create(this, this._def);
  }
  nullish() {
    return this.nullable().optional();
  }
  array() {
    return $e.create(this, this._def);
  }
  promise() {
    return $t.create(this, this._def);
  }
  or(e) {
    return qt.create([this, e], this._def);
  }
  and(e) {
    return Kt.create(this, e, this._def);
  }
  transform(e) {
    return new Ne({
      ...N(this._def),
      schema: this,
      typeName: C.ZodEffects,
      effect: { type: "transform", transform: e }
    });
  }
  default(e) {
    const r = typeof e == "function" ? e : () => e;
    return new er({
      ...N(this._def),
      innerType: this,
      defaultValue: r,
      typeName: C.ZodDefault
    });
  }
  brand() {
    return new Fn({
      typeName: C.ZodBranded,
      type: this,
      ...N(this._def)
    });
  }
  catch(e) {
    const r = typeof e == "function" ? e : () => e;
    return new tr({
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
const Tc = /^c[^\s-]{8,}$/i, Ac = /^[0-9a-z]+$/, Ic = /^[0-9A-HJKMNP-TV-Z]{26}$/, Cc = /^[0-9a-fA-F]{8}\b-[0-9a-fA-F]{4}\b-[0-9a-fA-F]{4}\b-[0-9a-fA-F]{4}\b-[0-9a-fA-F]{12}$/i, Rc = /^[a-z0-9_-]{21}$/i, $c = /^[-+]?P(?!$)(?:(?:[-+]?\d+Y)|(?:[-+]?\d+[.,]\d+Y$))?(?:(?:[-+]?\d+M)|(?:[-+]?\d+[.,]\d+M$))?(?:(?:[-+]?\d+W)|(?:[-+]?\d+[.,]\d+W$))?(?:(?:[-+]?\d+D)|(?:[-+]?\d+[.,]\d+D$))?(?:T(?=[\d+-])(?:(?:[-+]?\d+H)|(?:[-+]?\d+[.,]\d+H$))?(?:(?:[-+]?\d+M)|(?:[-+]?\d+[.,]\d+M$))?(?:[-+]?\d+(?:[.,]\d+)?S)?)??$/, Nc = /^(?!\.)(?!.*\.\.)([A-Z0-9_'+\-\.]*)[A-Z0-9_+-]@([A-Z0-9][A-Z0-9\-]*\.)+[A-Z]{2,}$/i, Oc = "^(\\p{Extended_Pictographic}|\\p{Emoji_Component})+$";
let nn;
const Mc = /^(?:(?:25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9][0-9]|[0-9])\.){3}(?:25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9][0-9]|[0-9])$/, Lc = /^(([a-f0-9]{1,4}:){7}|::([a-f0-9]{1,4}:){0,6}|([a-f0-9]{1,4}:){1}:([a-f0-9]{1,4}:){0,5}|([a-f0-9]{1,4}:){2}:([a-f0-9]{1,4}:){0,4}|([a-f0-9]{1,4}:){3}:([a-f0-9]{1,4}:){0,3}|([a-f0-9]{1,4}:){4}:([a-f0-9]{1,4}:){0,2}|([a-f0-9]{1,4}:){5}:([a-f0-9]{1,4}:){0,1})([a-f0-9]{1,4}|(((25[0-5])|(2[0-4][0-9])|(1[0-9]{2})|([0-9]{1,2}))\.){3}((25[0-5])|(2[0-4][0-9])|(1[0-9]{2})|([0-9]{1,2})))$/, Fc = /^([0-9a-zA-Z+/]{4})*(([0-9a-zA-Z+/]{2}==)|([0-9a-zA-Z+/]{3}=))?$/, $s = "((\\d\\d[2468][048]|\\d\\d[13579][26]|\\d\\d0[48]|[02468][048]00|[13579][26]00)-02-29|\\d{4}-((0[13578]|1[02])-(0[1-9]|[12]\\d|3[01])|(0[469]|11)-(0[1-9]|[12]\\d|30)|(02)-(0[1-9]|1\\d|2[0-8])))", Dc = new RegExp(`^${$s}$`);
function Ns(t) {
  let e = "([01]\\d|2[0-3]):[0-5]\\d:[0-5]\\d";
  return t.precision ? e = `${e}\\.\\d{${t.precision}}` : t.precision == null && (e = `${e}(\\.\\d+)?`), e;
}
function Uc(t) {
  return new RegExp(`^${Ns(t)}$`);
}
function Os(t) {
  let e = `${$s}T${Ns(t)}`;
  const r = [];
  return r.push(t.local ? "Z?" : "Z"), t.offset && r.push("([+-]\\d{2}:?\\d{2})"), e = `${e}(${r.join("|")})`, new RegExp(`^${e}$`);
}
function jc(t, e) {
  return !!((e === "v4" || !e) && Mc.test(t) || (e === "v6" || !e) && Lc.test(t));
}
class Ce extends O {
  _parse(e) {
    if (this._def.coerce && (e.data = String(e.data)), this._getType(e) !== w.string) {
      const s = this._getOrReturnCtx(e);
      return b(s, {
        code: g.invalid_type,
        expected: w.string,
        received: s.parsedType
      }), R;
    }
    const n = new ne();
    let o;
    for (const s of this._def.checks)
      if (s.kind === "min")
        e.data.length < s.value && (o = this._getOrReturnCtx(e, o), b(o, {
          code: g.too_small,
          minimum: s.value,
          type: "string",
          inclusive: !0,
          exact: !1,
          message: s.message
        }), n.dirty());
      else if (s.kind === "max")
        e.data.length > s.value && (o = this._getOrReturnCtx(e, o), b(o, {
          code: g.too_big,
          maximum: s.value,
          type: "string",
          inclusive: !0,
          exact: !1,
          message: s.message
        }), n.dirty());
      else if (s.kind === "length") {
        const i = e.data.length > s.value, c = e.data.length < s.value;
        (i || c) && (o = this._getOrReturnCtx(e, o), i ? b(o, {
          code: g.too_big,
          maximum: s.value,
          type: "string",
          inclusive: !0,
          exact: !0,
          message: s.message
        }) : c && b(o, {
          code: g.too_small,
          minimum: s.value,
          type: "string",
          inclusive: !0,
          exact: !0,
          message: s.message
        }), n.dirty());
      } else if (s.kind === "email")
        Nc.test(e.data) || (o = this._getOrReturnCtx(e, o), b(o, {
          validation: "email",
          code: g.invalid_string,
          message: s.message
        }), n.dirty());
      else if (s.kind === "emoji")
        nn || (nn = new RegExp(Oc, "u")), nn.test(e.data) || (o = this._getOrReturnCtx(e, o), b(o, {
          validation: "emoji",
          code: g.invalid_string,
          message: s.message
        }), n.dirty());
      else if (s.kind === "uuid")
        Cc.test(e.data) || (o = this._getOrReturnCtx(e, o), b(o, {
          validation: "uuid",
          code: g.invalid_string,
          message: s.message
        }), n.dirty());
      else if (s.kind === "nanoid")
        Rc.test(e.data) || (o = this._getOrReturnCtx(e, o), b(o, {
          validation: "nanoid",
          code: g.invalid_string,
          message: s.message
        }), n.dirty());
      else if (s.kind === "cuid")
        Tc.test(e.data) || (o = this._getOrReturnCtx(e, o), b(o, {
          validation: "cuid",
          code: g.invalid_string,
          message: s.message
        }), n.dirty());
      else if (s.kind === "cuid2")
        Ac.test(e.data) || (o = this._getOrReturnCtx(e, o), b(o, {
          validation: "cuid2",
          code: g.invalid_string,
          message: s.message
        }), n.dirty());
      else if (s.kind === "ulid")
        Ic.test(e.data) || (o = this._getOrReturnCtx(e, o), b(o, {
          validation: "ulid",
          code: g.invalid_string,
          message: s.message
        }), n.dirty());
      else if (s.kind === "url")
        try {
          new URL(e.data);
        } catch {
          o = this._getOrReturnCtx(e, o), b(o, {
            validation: "url",
            code: g.invalid_string,
            message: s.message
          }), n.dirty();
        }
      else s.kind === "regex" ? (s.regex.lastIndex = 0, s.regex.test(e.data) || (o = this._getOrReturnCtx(e, o), b(o, {
        validation: "regex",
        code: g.invalid_string,
        message: s.message
      }), n.dirty())) : s.kind === "trim" ? e.data = e.data.trim() : s.kind === "includes" ? e.data.includes(s.value, s.position) || (o = this._getOrReturnCtx(e, o), b(o, {
        code: g.invalid_string,
        validation: { includes: s.value, position: s.position },
        message: s.message
      }), n.dirty()) : s.kind === "toLowerCase" ? e.data = e.data.toLowerCase() : s.kind === "toUpperCase" ? e.data = e.data.toUpperCase() : s.kind === "startsWith" ? e.data.startsWith(s.value) || (o = this._getOrReturnCtx(e, o), b(o, {
        code: g.invalid_string,
        validation: { startsWith: s.value },
        message: s.message
      }), n.dirty()) : s.kind === "endsWith" ? e.data.endsWith(s.value) || (o = this._getOrReturnCtx(e, o), b(o, {
        code: g.invalid_string,
        validation: { endsWith: s.value },
        message: s.message
      }), n.dirty()) : s.kind === "datetime" ? Os(s).test(e.data) || (o = this._getOrReturnCtx(e, o), b(o, {
        code: g.invalid_string,
        validation: "datetime",
        message: s.message
      }), n.dirty()) : s.kind === "date" ? Dc.test(e.data) || (o = this._getOrReturnCtx(e, o), b(o, {
        code: g.invalid_string,
        validation: "date",
        message: s.message
      }), n.dirty()) : s.kind === "time" ? Uc(s).test(e.data) || (o = this._getOrReturnCtx(e, o), b(o, {
        code: g.invalid_string,
        validation: "time",
        message: s.message
      }), n.dirty()) : s.kind === "duration" ? $c.test(e.data) || (o = this._getOrReturnCtx(e, o), b(o, {
        validation: "duration",
        code: g.invalid_string,
        message: s.message
      }), n.dirty()) : s.kind === "ip" ? jc(e.data, s.version) || (o = this._getOrReturnCtx(e, o), b(o, {
        validation: "ip",
        code: g.invalid_string,
        message: s.message
      }), n.dirty()) : s.kind === "base64" ? Fc.test(e.data) || (o = this._getOrReturnCtx(e, o), b(o, {
        validation: "base64",
        code: g.invalid_string,
        message: s.message
      }), n.dirty()) : D.assertNever(s);
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
    return new Ce({
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
    return new Ce({
      ...this._def,
      checks: [...this._def.checks, { kind: "trim" }]
    });
  }
  toLowerCase() {
    return new Ce({
      ...this._def,
      checks: [...this._def.checks, { kind: "toLowerCase" }]
    });
  }
  toUpperCase() {
    return new Ce({
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
Ce.create = (t) => {
  var e;
  return new Ce({
    checks: [],
    typeName: C.ZodString,
    coerce: (e = t == null ? void 0 : t.coerce) !== null && e !== void 0 ? e : !1,
    ...N(t)
  });
};
function Zc(t, e) {
  const r = (t.toString().split(".")[1] || "").length, n = (e.toString().split(".")[1] || "").length, o = r > n ? r : n, s = parseInt(t.toFixed(o).replace(".", "")), i = parseInt(e.toFixed(o).replace(".", ""));
  return s % i / Math.pow(10, o);
}
class et extends O {
  constructor() {
    super(...arguments), this.min = this.gte, this.max = this.lte, this.step = this.multipleOf;
  }
  _parse(e) {
    if (this._def.coerce && (e.data = Number(e.data)), this._getType(e) !== w.number) {
      const s = this._getOrReturnCtx(e);
      return b(s, {
        code: g.invalid_type,
        expected: w.number,
        received: s.parsedType
      }), R;
    }
    let n;
    const o = new ne();
    for (const s of this._def.checks)
      s.kind === "int" ? D.isInteger(e.data) || (n = this._getOrReturnCtx(e, n), b(n, {
        code: g.invalid_type,
        expected: "integer",
        received: "float",
        message: s.message
      }), o.dirty()) : s.kind === "min" ? (s.inclusive ? e.data < s.value : e.data <= s.value) && (n = this._getOrReturnCtx(e, n), b(n, {
        code: g.too_small,
        minimum: s.value,
        type: "number",
        inclusive: s.inclusive,
        exact: !1,
        message: s.message
      }), o.dirty()) : s.kind === "max" ? (s.inclusive ? e.data > s.value : e.data >= s.value) && (n = this._getOrReturnCtx(e, n), b(n, {
        code: g.too_big,
        maximum: s.value,
        type: "number",
        inclusive: s.inclusive,
        exact: !1,
        message: s.message
      }), o.dirty()) : s.kind === "multipleOf" ? Zc(e.data, s.value) !== 0 && (n = this._getOrReturnCtx(e, n), b(n, {
        code: g.not_multiple_of,
        multipleOf: s.value,
        message: s.message
      }), o.dirty()) : s.kind === "finite" ? Number.isFinite(e.data) || (n = this._getOrReturnCtx(e, n), b(n, {
        code: g.not_finite,
        message: s.message
      }), o.dirty()) : D.assertNever(s);
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
    return !!this._def.checks.find((e) => e.kind === "int" || e.kind === "multipleOf" && D.isInteger(e.value));
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
  typeName: C.ZodNumber,
  coerce: (t == null ? void 0 : t.coerce) || !1,
  ...N(t)
});
class tt extends O {
  constructor() {
    super(...arguments), this.min = this.gte, this.max = this.lte;
  }
  _parse(e) {
    if (this._def.coerce && (e.data = BigInt(e.data)), this._getType(e) !== w.bigint) {
      const s = this._getOrReturnCtx(e);
      return b(s, {
        code: g.invalid_type,
        expected: w.bigint,
        received: s.parsedType
      }), R;
    }
    let n;
    const o = new ne();
    for (const s of this._def.checks)
      s.kind === "min" ? (s.inclusive ? e.data < s.value : e.data <= s.value) && (n = this._getOrReturnCtx(e, n), b(n, {
        code: g.too_small,
        type: "bigint",
        minimum: s.value,
        inclusive: s.inclusive,
        message: s.message
      }), o.dirty()) : s.kind === "max" ? (s.inclusive ? e.data > s.value : e.data >= s.value) && (n = this._getOrReturnCtx(e, n), b(n, {
        code: g.too_big,
        type: "bigint",
        maximum: s.value,
        inclusive: s.inclusive,
        message: s.message
      }), o.dirty()) : s.kind === "multipleOf" ? e.data % s.value !== BigInt(0) && (n = this._getOrReturnCtx(e, n), b(n, {
        code: g.not_multiple_of,
        multipleOf: s.value,
        message: s.message
      }), o.dirty()) : D.assertNever(s);
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
    typeName: C.ZodBigInt,
    coerce: (e = t == null ? void 0 : t.coerce) !== null && e !== void 0 ? e : !1,
    ...N(t)
  });
};
class Vt extends O {
  _parse(e) {
    if (this._def.coerce && (e.data = !!e.data), this._getType(e) !== w.boolean) {
      const n = this._getOrReturnCtx(e);
      return b(n, {
        code: g.invalid_type,
        expected: w.boolean,
        received: n.parsedType
      }), R;
    }
    return de(e.data);
  }
}
Vt.create = (t) => new Vt({
  typeName: C.ZodBoolean,
  coerce: (t == null ? void 0 : t.coerce) || !1,
  ...N(t)
});
class pt extends O {
  _parse(e) {
    if (this._def.coerce && (e.data = new Date(e.data)), this._getType(e) !== w.date) {
      const s = this._getOrReturnCtx(e);
      return b(s, {
        code: g.invalid_type,
        expected: w.date,
        received: s.parsedType
      }), R;
    }
    if (isNaN(e.data.getTime())) {
      const s = this._getOrReturnCtx(e);
      return b(s, {
        code: g.invalid_date
      }), R;
    }
    const n = new ne();
    let o;
    for (const s of this._def.checks)
      s.kind === "min" ? e.data.getTime() < s.value && (o = this._getOrReturnCtx(e, o), b(o, {
        code: g.too_small,
        message: s.message,
        inclusive: !0,
        exact: !1,
        minimum: s.value,
        type: "date"
      }), n.dirty()) : s.kind === "max" ? e.data.getTime() > s.value && (o = this._getOrReturnCtx(e, o), b(o, {
        code: g.too_big,
        message: s.message,
        inclusive: !0,
        exact: !1,
        maximum: s.value,
        type: "date"
      }), n.dirty()) : D.assertNever(s);
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
  typeName: C.ZodDate,
  ...N(t)
});
class Ir extends O {
  _parse(e) {
    if (this._getType(e) !== w.symbol) {
      const n = this._getOrReturnCtx(e);
      return b(n, {
        code: g.invalid_type,
        expected: w.symbol,
        received: n.parsedType
      }), R;
    }
    return de(e.data);
  }
}
Ir.create = (t) => new Ir({
  typeName: C.ZodSymbol,
  ...N(t)
});
class Ht extends O {
  _parse(e) {
    if (this._getType(e) !== w.undefined) {
      const n = this._getOrReturnCtx(e);
      return b(n, {
        code: g.invalid_type,
        expected: w.undefined,
        received: n.parsedType
      }), R;
    }
    return de(e.data);
  }
}
Ht.create = (t) => new Ht({
  typeName: C.ZodUndefined,
  ...N(t)
});
class Wt extends O {
  _parse(e) {
    if (this._getType(e) !== w.null) {
      const n = this._getOrReturnCtx(e);
      return b(n, {
        code: g.invalid_type,
        expected: w.null,
        received: n.parsedType
      }), R;
    }
    return de(e.data);
  }
}
Wt.create = (t) => new Wt({
  typeName: C.ZodNull,
  ...N(t)
});
class Rt extends O {
  constructor() {
    super(...arguments), this._any = !0;
  }
  _parse(e) {
    return de(e.data);
  }
}
Rt.create = (t) => new Rt({
  typeName: C.ZodAny,
  ...N(t)
});
class ft extends O {
  constructor() {
    super(...arguments), this._unknown = !0;
  }
  _parse(e) {
    return de(e.data);
  }
}
ft.create = (t) => new ft({
  typeName: C.ZodUnknown,
  ...N(t)
});
class He extends O {
  _parse(e) {
    const r = this._getOrReturnCtx(e);
    return b(r, {
      code: g.invalid_type,
      expected: w.never,
      received: r.parsedType
    }), R;
  }
}
He.create = (t) => new He({
  typeName: C.ZodNever,
  ...N(t)
});
class Cr extends O {
  _parse(e) {
    if (this._getType(e) !== w.undefined) {
      const n = this._getOrReturnCtx(e);
      return b(n, {
        code: g.invalid_type,
        expected: w.void,
        received: n.parsedType
      }), R;
    }
    return de(e.data);
  }
}
Cr.create = (t) => new Cr({
  typeName: C.ZodVoid,
  ...N(t)
});
class $e extends O {
  _parse(e) {
    const { ctx: r, status: n } = this._processInputParams(e), o = this._def;
    if (r.parsedType !== w.array)
      return b(r, {
        code: g.invalid_type,
        expected: w.array,
        received: r.parsedType
      }), R;
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
      return Promise.all([...r.data].map((i, c) => o.type._parseAsync(new De(r, i, r.path, c)))).then((i) => ne.mergeArray(n, i));
    const s = [...r.data].map((i, c) => o.type._parseSync(new De(r, i, r.path, c)));
    return ne.mergeArray(n, s);
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
  typeName: C.ZodArray,
  ...N(e)
});
function vt(t) {
  if (t instanceof G) {
    const e = {};
    for (const r in t.shape) {
      const n = t.shape[r];
      e[r] = Fe.create(vt(n));
    }
    return new G({
      ...t._def,
      shape: () => e
    });
  } else return t instanceof $e ? new $e({
    ...t._def,
    type: vt(t.element)
  }) : t instanceof Fe ? Fe.create(vt(t.unwrap())) : t instanceof nt ? nt.create(vt(t.unwrap())) : t instanceof Ue ? Ue.create(t.items.map((e) => vt(e))) : t;
}
class G extends O {
  constructor() {
    super(...arguments), this._cached = null, this.nonstrict = this.passthrough, this.augment = this.extend;
  }
  _getCached() {
    if (this._cached !== null)
      return this._cached;
    const e = this._def.shape(), r = D.objectKeys(e);
    return this._cached = { shape: e, keys: r };
  }
  _parse(e) {
    if (this._getType(e) !== w.object) {
      const u = this._getOrReturnCtx(e);
      return b(u, {
        code: g.invalid_type,
        expected: w.object,
        received: u.parsedType
      }), R;
    }
    const { status: n, ctx: o } = this._processInputParams(e), { shape: s, keys: i } = this._getCached(), c = [];
    if (!(this._def.catchall instanceof He && this._def.unknownKeys === "strip"))
      for (const u in o.data)
        i.includes(u) || c.push(u);
    const l = [];
    for (const u of i) {
      const d = s[u], f = o.data[u];
      l.push({
        key: { status: "valid", value: u },
        value: d._parse(new De(o, f, o.path, u)),
        alwaysSet: u in o.data
      });
    }
    if (this._def.catchall instanceof He) {
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
      else if (u !== "strip") throw new Error("Internal ZodObject error: invalid unknownKeys value.");
    } else {
      const u = this._def.catchall;
      for (const d of c) {
        const f = o.data[d];
        l.push({
          key: { status: "valid", value: d },
          value: u._parse(
            new De(o, f, o.path, d)
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
    }).then((u) => ne.mergeObjectSync(n, u)) : ne.mergeObjectSync(n, l);
  }
  get shape() {
    return this._def.shape();
  }
  strict(e) {
    return k.errToObj, new G({
      ...this._def,
      unknownKeys: "strict",
      ...e !== void 0 ? {
        errorMap: (r, n) => {
          var o, s, i, c;
          const l = (i = (s = (o = this._def).errorMap) === null || s === void 0 ? void 0 : s.call(o, r, n).message) !== null && i !== void 0 ? i : n.defaultError;
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
    return new G({
      ...this._def,
      unknownKeys: "strip"
    });
  }
  passthrough() {
    return new G({
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
    return new G({
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
    return new G({
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
    return new G({
      ...this._def,
      catchall: e
    });
  }
  pick(e) {
    const r = {};
    return D.objectKeys(e).forEach((n) => {
      e[n] && this.shape[n] && (r[n] = this.shape[n]);
    }), new G({
      ...this._def,
      shape: () => r
    });
  }
  omit(e) {
    const r = {};
    return D.objectKeys(this.shape).forEach((n) => {
      e[n] || (r[n] = this.shape[n]);
    }), new G({
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
    return D.objectKeys(this.shape).forEach((n) => {
      const o = this.shape[n];
      e && !e[n] ? r[n] = o : r[n] = o.optional();
    }), new G({
      ...this._def,
      shape: () => r
    });
  }
  required(e) {
    const r = {};
    return D.objectKeys(this.shape).forEach((n) => {
      if (e && !e[n])
        r[n] = this.shape[n];
      else {
        let s = this.shape[n];
        for (; s instanceof Fe; )
          s = s._def.innerType;
        r[n] = s;
      }
    }), new G({
      ...this._def,
      shape: () => r
    });
  }
  keyof() {
    return Ms(D.objectKeys(this.shape));
  }
}
G.create = (t, e) => new G({
  shape: () => t,
  unknownKeys: "strip",
  catchall: He.create(),
  typeName: C.ZodObject,
  ...N(e)
});
G.strictCreate = (t, e) => new G({
  shape: () => t,
  unknownKeys: "strict",
  catchall: He.create(),
  typeName: C.ZodObject,
  ...N(e)
});
G.lazycreate = (t, e) => new G({
  shape: t,
  unknownKeys: "strip",
  catchall: He.create(),
  typeName: C.ZodObject,
  ...N(e)
});
class qt extends O {
  _parse(e) {
    const { ctx: r } = this._processInputParams(e), n = this._def.options;
    function o(s) {
      for (const c of s)
        if (c.result.status === "valid")
          return c.result;
      for (const c of s)
        if (c.result.status === "dirty")
          return r.common.issues.push(...c.ctx.common.issues), c.result;
      const i = s.map((c) => new _e(c.ctx.common.issues));
      return b(r, {
        code: g.invalid_union,
        unionErrors: i
      }), R;
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
      const c = i.map((l) => new _e(l));
      return b(r, {
        code: g.invalid_union,
        unionErrors: c
      }), R;
    }
  }
  get options() {
    return this._def.options;
  }
}
qt.create = (t, e) => new qt({
  options: t,
  typeName: C.ZodUnion,
  ...N(e)
});
const Be = (t) => t instanceof Jt ? Be(t.schema) : t instanceof Ne ? Be(t.innerType()) : t instanceof Xt ? [t.value] : t instanceof rt ? t.options : t instanceof Qt ? D.objectValues(t.enum) : t instanceof er ? Be(t._def.innerType) : t instanceof Ht ? [void 0] : t instanceof Wt ? [null] : t instanceof Fe ? [void 0, ...Be(t.unwrap())] : t instanceof nt ? [null, ...Be(t.unwrap())] : t instanceof Fn || t instanceof rr ? Be(t.unwrap()) : t instanceof tr ? Be(t._def.innerType) : [];
class Vr extends O {
  _parse(e) {
    const { ctx: r } = this._processInputParams(e);
    if (r.parsedType !== w.object)
      return b(r, {
        code: g.invalid_type,
        expected: w.object,
        received: r.parsedType
      }), R;
    const n = this.discriminator, o = r.data[n], s = this.optionsMap.get(o);
    return s ? r.common.async ? s._parseAsync({
      data: r.data,
      path: r.path,
      parent: r
    }) : s._parseSync({
      data: r.data,
      path: r.path,
      parent: r
    }) : (b(r, {
      code: g.invalid_union_discriminator,
      options: Array.from(this.optionsMap.keys()),
      path: [n]
    }), R);
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
      const i = Be(s.shape[e]);
      if (!i.length)
        throw new Error(`A discriminator value for key \`${e}\` could not be extracted from all schema options`);
      for (const c of i) {
        if (o.has(c))
          throw new Error(`Discriminator property ${String(e)} has duplicate value ${String(c)}`);
        o.set(c, s);
      }
    }
    return new Vr({
      typeName: C.ZodDiscriminatedUnion,
      discriminator: e,
      options: r,
      optionsMap: o,
      ...N(n)
    });
  }
}
function mn(t, e) {
  const r = Ye(t), n = Ye(e);
  if (t === e)
    return { valid: !0, data: t };
  if (r === w.object && n === w.object) {
    const o = D.objectKeys(e), s = D.objectKeys(t).filter((c) => o.indexOf(c) !== -1), i = { ...t, ...e };
    for (const c of s) {
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
    for (let s = 0; s < t.length; s++) {
      const i = t[s], c = e[s], l = mn(i, c);
      if (!l.valid)
        return { valid: !1 };
      o.push(l.data);
    }
    return { valid: !0, data: o };
  } else return r === w.date && n === w.date && +t == +e ? { valid: !0, data: t } : { valid: !1 };
}
class Kt extends O {
  _parse(e) {
    const { status: r, ctx: n } = this._processInputParams(e), o = (s, i) => {
      if (pn(s) || pn(i))
        return R;
      const c = mn(s.value, i.value);
      return c.valid ? ((hn(s) || hn(i)) && r.dirty(), { status: r.value, value: c.data }) : (b(n, {
        code: g.invalid_intersection_types
      }), R);
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
Kt.create = (t, e, r) => new Kt({
  left: t,
  right: e,
  typeName: C.ZodIntersection,
  ...N(r)
});
class Ue extends O {
  _parse(e) {
    const { status: r, ctx: n } = this._processInputParams(e);
    if (n.parsedType !== w.array)
      return b(n, {
        code: g.invalid_type,
        expected: w.array,
        received: n.parsedType
      }), R;
    if (n.data.length < this._def.items.length)
      return b(n, {
        code: g.too_small,
        minimum: this._def.items.length,
        inclusive: !0,
        exact: !1,
        type: "array"
      }), R;
    !this._def.rest && n.data.length > this._def.items.length && (b(n, {
      code: g.too_big,
      maximum: this._def.items.length,
      inclusive: !0,
      exact: !1,
      type: "array"
    }), r.dirty());
    const s = [...n.data].map((i, c) => {
      const l = this._def.items[c] || this._def.rest;
      return l ? l._parse(new De(n, i, n.path, c)) : null;
    }).filter((i) => !!i);
    return n.common.async ? Promise.all(s).then((i) => ne.mergeArray(r, i)) : ne.mergeArray(r, s);
  }
  get items() {
    return this._def.items;
  }
  rest(e) {
    return new Ue({
      ...this._def,
      rest: e
    });
  }
}
Ue.create = (t, e) => {
  if (!Array.isArray(t))
    throw new Error("You must pass an array of schemas to z.tuple([ ... ])");
  return new Ue({
    items: t,
    typeName: C.ZodTuple,
    rest: null,
    ...N(e)
  });
};
class Yt extends O {
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
      }), R;
    const o = [], s = this._def.keyType, i = this._def.valueType;
    for (const c in n.data)
      o.push({
        key: s._parse(new De(n, c, n.path, c)),
        value: i._parse(new De(n, n.data[c], n.path, c)),
        alwaysSet: c in n.data
      });
    return n.common.async ? ne.mergeObjectAsync(r, o) : ne.mergeObjectSync(r, o);
  }
  get element() {
    return this._def.valueType;
  }
  static create(e, r, n) {
    return r instanceof O ? new Yt({
      keyType: e,
      valueType: r,
      typeName: C.ZodRecord,
      ...N(n)
    }) : new Yt({
      keyType: Ce.create(),
      valueType: e,
      typeName: C.ZodRecord,
      ...N(r)
    });
  }
}
class Rr extends O {
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
      }), R;
    const o = this._def.keyType, s = this._def.valueType, i = [...n.data.entries()].map(([c, l], u) => ({
      key: o._parse(new De(n, c, n.path, [u, "key"])),
      value: s._parse(new De(n, l, n.path, [u, "value"]))
    }));
    if (n.common.async) {
      const c = /* @__PURE__ */ new Map();
      return Promise.resolve().then(async () => {
        for (const l of i) {
          const u = await l.key, d = await l.value;
          if (u.status === "aborted" || d.status === "aborted")
            return R;
          (u.status === "dirty" || d.status === "dirty") && r.dirty(), c.set(u.value, d.value);
        }
        return { status: r.value, value: c };
      });
    } else {
      const c = /* @__PURE__ */ new Map();
      for (const l of i) {
        const u = l.key, d = l.value;
        if (u.status === "aborted" || d.status === "aborted")
          return R;
        (u.status === "dirty" || d.status === "dirty") && r.dirty(), c.set(u.value, d.value);
      }
      return { status: r.value, value: c };
    }
  }
}
Rr.create = (t, e, r) => new Rr({
  valueType: e,
  keyType: t,
  typeName: C.ZodMap,
  ...N(r)
});
class ht extends O {
  _parse(e) {
    const { status: r, ctx: n } = this._processInputParams(e);
    if (n.parsedType !== w.set)
      return b(n, {
        code: g.invalid_type,
        expected: w.set,
        received: n.parsedType
      }), R;
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
    const s = this._def.valueType;
    function i(l) {
      const u = /* @__PURE__ */ new Set();
      for (const d of l) {
        if (d.status === "aborted")
          return R;
        d.status === "dirty" && r.dirty(), u.add(d.value);
      }
      return { status: r.value, value: u };
    }
    const c = [...n.data.values()].map((l, u) => s._parse(new De(n, l, n.path, u)));
    return n.common.async ? Promise.all(c).then((l) => i(l)) : i(c);
  }
  min(e, r) {
    return new ht({
      ...this._def,
      minSize: { value: e, message: k.toString(r) }
    });
  }
  max(e, r) {
    return new ht({
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
ht.create = (t, e) => new ht({
  valueType: t,
  minSize: null,
  maxSize: null,
  typeName: C.ZodSet,
  ...N(e)
});
class kt extends O {
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
      }), R;
    function n(c, l) {
      return Tr({
        data: c,
        path: r.path,
        errorMaps: [
          r.common.contextualErrorMap,
          r.schemaErrorMap,
          Pr(),
          Ct
        ].filter((u) => !!u),
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
        ].filter((u) => !!u),
        issueData: {
          code: g.invalid_return_type,
          returnTypeError: l
        }
      });
    }
    const s = { errorMap: r.common.contextualErrorMap }, i = r.data;
    if (this._def.returns instanceof $t) {
      const c = this;
      return de(async function(...l) {
        const u = new _e([]), d = await c._def.args.parseAsync(l, s).catch((p) => {
          throw u.addIssue(n(l, p)), u;
        }), f = await Reflect.apply(i, this, d);
        return await c._def.returns._def.type.parseAsync(f, s).catch((p) => {
          throw u.addIssue(o(f, p)), u;
        });
      });
    } else {
      const c = this;
      return de(function(...l) {
        const u = c._def.args.safeParse(l, s);
        if (!u.success)
          throw new _e([n(l, u.error)]);
        const d = Reflect.apply(i, this, u.data), f = c._def.returns.safeParse(d, s);
        if (!f.success)
          throw new _e([o(d, f.error)]);
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
    return new kt({
      ...this._def,
      args: Ue.create(e).rest(ft.create())
    });
  }
  returns(e) {
    return new kt({
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
    return new kt({
      args: e || Ue.create([]).rest(ft.create()),
      returns: r || ft.create(),
      typeName: C.ZodFunction,
      ...N(n)
    });
  }
}
class Jt extends O {
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
  typeName: C.ZodLazy,
  ...N(e)
});
class Xt extends O {
  _parse(e) {
    if (e.data !== this._def.value) {
      const r = this._getOrReturnCtx(e);
      return b(r, {
        received: r.data,
        code: g.invalid_literal,
        expected: this._def.value
      }), R;
    }
    return { status: "valid", value: e.data };
  }
  get value() {
    return this._def.value;
  }
}
Xt.create = (t, e) => new Xt({
  value: t,
  typeName: C.ZodLiteral,
  ...N(e)
});
function Ms(t, e) {
  return new rt({
    values: t,
    typeName: C.ZodEnum,
    ...N(e)
  });
}
class rt extends O {
  constructor() {
    super(...arguments), jt.set(this, void 0);
  }
  _parse(e) {
    if (typeof e.data != "string") {
      const r = this._getOrReturnCtx(e), n = this._def.values;
      return b(r, {
        expected: D.joinValues(n),
        received: r.parsedType,
        code: g.invalid_type
      }), R;
    }
    if (Ar(this, jt) || Rs(this, jt, new Set(this._def.values)), !Ar(this, jt).has(e.data)) {
      const r = this._getOrReturnCtx(e), n = this._def.values;
      return b(r, {
        received: r.data,
        code: g.invalid_enum_value,
        options: n
      }), R;
    }
    return de(e.data);
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
rt.create = Ms;
class Qt extends O {
  constructor() {
    super(...arguments), Zt.set(this, void 0);
  }
  _parse(e) {
    const r = D.getValidEnumValues(this._def.values), n = this._getOrReturnCtx(e);
    if (n.parsedType !== w.string && n.parsedType !== w.number) {
      const o = D.objectValues(r);
      return b(n, {
        expected: D.joinValues(o),
        received: n.parsedType,
        code: g.invalid_type
      }), R;
    }
    if (Ar(this, Zt) || Rs(this, Zt, new Set(D.getValidEnumValues(this._def.values))), !Ar(this, Zt).has(e.data)) {
      const o = D.objectValues(r);
      return b(n, {
        received: n.data,
        code: g.invalid_enum_value,
        options: o
      }), R;
    }
    return de(e.data);
  }
  get enum() {
    return this._def.values;
  }
}
Zt = /* @__PURE__ */ new WeakMap();
Qt.create = (t, e) => new Qt({
  values: t,
  typeName: C.ZodNativeEnum,
  ...N(e)
});
class $t extends O {
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
      }), R;
    const n = r.parsedType === w.promise ? r.data : Promise.resolve(r.data);
    return de(n.then((o) => this._def.type.parseAsync(o, {
      path: r.path,
      errorMap: r.common.contextualErrorMap
    })));
  }
}
$t.create = (t, e) => new $t({
  type: t,
  typeName: C.ZodPromise,
  ...N(e)
});
class Ne extends O {
  innerType() {
    return this._def.schema;
  }
  sourceType() {
    return this._def.schema._def.typeName === C.ZodEffects ? this._def.schema.sourceType() : this._def.schema;
  }
  _parse(e) {
    const { status: r, ctx: n } = this._processInputParams(e), o = this._def.effect || null, s = {
      addIssue: (i) => {
        b(n, i), i.fatal ? r.abort() : r.dirty();
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
            return R;
          const l = await this._def.schema._parseAsync({
            data: c,
            path: n.path,
            parent: n
          });
          return l.status === "aborted" ? R : l.status === "dirty" || r.value === "dirty" ? bt(l.value) : l;
        });
      {
        if (r.value === "aborted")
          return R;
        const c = this._def.schema._parseSync({
          data: i,
          path: n.path,
          parent: n
        });
        return c.status === "aborted" ? R : c.status === "dirty" || r.value === "dirty" ? bt(c.value) : c;
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
        return c.status === "aborted" ? R : (c.status === "dirty" && r.dirty(), i(c.value), { status: r.value, value: c.value });
      } else
        return this._def.schema._parseAsync({ data: n.data, path: n.path, parent: n }).then((c) => c.status === "aborted" ? R : (c.status === "dirty" && r.dirty(), i(c.value).then(() => ({ status: r.value, value: c.value }))));
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
        const c = o.transform(i.value, s);
        if (c instanceof Promise)
          throw new Error("Asynchronous transform encountered during synchronous parse operation. Use .parseAsync instead.");
        return { status: r.value, value: c };
      } else
        return this._def.schema._parseAsync({ data: n.data, path: n.path, parent: n }).then((i) => Bt(i) ? Promise.resolve(o.transform(i.value, s)).then((c) => ({ status: r.value, value: c })) : i);
    D.assertNever(o);
  }
}
Ne.create = (t, e, r) => new Ne({
  schema: t,
  typeName: C.ZodEffects,
  effect: e,
  ...N(r)
});
Ne.createWithPreprocess = (t, e, r) => new Ne({
  schema: e,
  effect: { type: "preprocess", transform: t },
  typeName: C.ZodEffects,
  ...N(r)
});
class Fe extends O {
  _parse(e) {
    return this._getType(e) === w.undefined ? de(void 0) : this._def.innerType._parse(e);
  }
  unwrap() {
    return this._def.innerType;
  }
}
Fe.create = (t, e) => new Fe({
  innerType: t,
  typeName: C.ZodOptional,
  ...N(e)
});
class nt extends O {
  _parse(e) {
    return this._getType(e) === w.null ? de(null) : this._def.innerType._parse(e);
  }
  unwrap() {
    return this._def.innerType;
  }
}
nt.create = (t, e) => new nt({
  innerType: t,
  typeName: C.ZodNullable,
  ...N(e)
});
class er extends O {
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
  typeName: C.ZodDefault,
  defaultValue: typeof e.default == "function" ? e.default : () => e.default,
  ...N(e)
});
class tr extends O {
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
    return Gt(o) ? o.then((s) => ({
      status: "valid",
      value: s.status === "valid" ? s.value : this._def.catchValue({
        get error() {
          return new _e(n.common.issues);
        },
        input: n.data
      })
    })) : {
      status: "valid",
      value: o.status === "valid" ? o.value : this._def.catchValue({
        get error() {
          return new _e(n.common.issues);
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
  typeName: C.ZodCatch,
  catchValue: typeof e.catch == "function" ? e.catch : () => e.catch,
  ...N(e)
});
class $r extends O {
  _parse(e) {
    if (this._getType(e) !== w.nan) {
      const n = this._getOrReturnCtx(e);
      return b(n, {
        code: g.invalid_type,
        expected: w.nan,
        received: n.parsedType
      }), R;
    }
    return { status: "valid", value: e.data };
  }
}
$r.create = (t) => new $r({
  typeName: C.ZodNaN,
  ...N(t)
});
const zc = Symbol("zod_brand");
class Fn extends O {
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
class cr extends O {
  _parse(e) {
    const { status: r, ctx: n } = this._processInputParams(e);
    if (n.common.async)
      return (async () => {
        const s = await this._def.in._parseAsync({
          data: n.data,
          path: n.path,
          parent: n
        });
        return s.status === "aborted" ? R : s.status === "dirty" ? (r.dirty(), bt(s.value)) : this._def.out._parseAsync({
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
      return o.status === "aborted" ? R : o.status === "dirty" ? (r.dirty(), {
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
      typeName: C.ZodPipeline
    });
  }
}
class rr extends O {
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
  typeName: C.ZodReadonly,
  ...N(e)
});
function Ls(t, e = {}, r) {
  return t ? Rt.create().superRefine((n, o) => {
    var s, i;
    if (!t(n)) {
      const c = typeof e == "function" ? e(n) : typeof e == "string" ? { message: e } : e, l = (i = (s = c.fatal) !== null && s !== void 0 ? s : r) !== null && i !== void 0 ? i : !0, u = typeof c == "string" ? { message: c } : c;
      o.addIssue({ code: "custom", ...u, fatal: l });
    }
  }) : Rt.create();
}
const Bc = {
  object: G.lazycreate
};
var C;
(function(t) {
  t.ZodString = "ZodString", t.ZodNumber = "ZodNumber", t.ZodNaN = "ZodNaN", t.ZodBigInt = "ZodBigInt", t.ZodBoolean = "ZodBoolean", t.ZodDate = "ZodDate", t.ZodSymbol = "ZodSymbol", t.ZodUndefined = "ZodUndefined", t.ZodNull = "ZodNull", t.ZodAny = "ZodAny", t.ZodUnknown = "ZodUnknown", t.ZodNever = "ZodNever", t.ZodVoid = "ZodVoid", t.ZodArray = "ZodArray", t.ZodObject = "ZodObject", t.ZodUnion = "ZodUnion", t.ZodDiscriminatedUnion = "ZodDiscriminatedUnion", t.ZodIntersection = "ZodIntersection", t.ZodTuple = "ZodTuple", t.ZodRecord = "ZodRecord", t.ZodMap = "ZodMap", t.ZodSet = "ZodSet", t.ZodFunction = "ZodFunction", t.ZodLazy = "ZodLazy", t.ZodLiteral = "ZodLiteral", t.ZodEnum = "ZodEnum", t.ZodEffects = "ZodEffects", t.ZodNativeEnum = "ZodNativeEnum", t.ZodOptional = "ZodOptional", t.ZodNullable = "ZodNullable", t.ZodDefault = "ZodDefault", t.ZodCatch = "ZodCatch", t.ZodPromise = "ZodPromise", t.ZodBranded = "ZodBranded", t.ZodPipeline = "ZodPipeline", t.ZodReadonly = "ZodReadonly";
})(C || (C = {}));
const Gc = (t, e = {
  message: `Input not instance of ${t.name}`
}) => Ls((r) => r instanceof t, e), Fs = Ce.create, Ds = et.create, Vc = $r.create, Hc = tt.create, Us = Vt.create, Wc = pt.create, qc = Ir.create, Kc = Ht.create, Yc = Wt.create, Jc = Rt.create, Xc = ft.create, Qc = He.create, el = Cr.create, tl = $e.create, rl = G.create, nl = G.strictCreate, ol = qt.create, sl = Vr.create, al = Kt.create, il = Ue.create, cl = Yt.create, ll = Rr.create, ul = ht.create, dl = kt.create, fl = Jt.create, pl = Xt.create, hl = rt.create, ml = Qt.create, gl = $t.create, go = Ne.create, yl = Fe.create, _l = nt.create, vl = Ne.createWithPreprocess, bl = cr.create, wl = () => Fs().optional(), xl = () => Ds().optional(), Sl = () => Us().optional(), El = {
  string: (t) => Ce.create({ ...t, coerce: !0 }),
  number: (t) => et.create({ ...t, coerce: !0 }),
  boolean: (t) => Vt.create({
    ...t,
    coerce: !0
  }),
  bigint: (t) => tt.create({ ...t, coerce: !0 }),
  date: (t) => pt.create({ ...t, coerce: !0 })
}, kl = R;
var K = /* @__PURE__ */ Object.freeze({
  __proto__: null,
  defaultErrorMap: Ct,
  setErrorMap: kc,
  getErrorMap: Pr,
  makeIssue: Tr,
  EMPTY_PATH: Pc,
  addIssueToContext: b,
  ParseStatus: ne,
  INVALID: R,
  DIRTY: bt,
  OK: de,
  isAborted: pn,
  isDirty: hn,
  isValid: Bt,
  isAsync: Gt,
  get util() {
    return D;
  },
  get objectUtil() {
    return fn;
  },
  ZodParsedType: w,
  getParsedType: Ye,
  ZodType: O,
  datetimeRegex: Os,
  ZodString: Ce,
  ZodNumber: et,
  ZodBigInt: tt,
  ZodBoolean: Vt,
  ZodDate: pt,
  ZodSymbol: Ir,
  ZodUndefined: Ht,
  ZodNull: Wt,
  ZodAny: Rt,
  ZodUnknown: ft,
  ZodNever: He,
  ZodVoid: Cr,
  ZodArray: $e,
  ZodObject: G,
  ZodUnion: qt,
  ZodDiscriminatedUnion: Vr,
  ZodIntersection: Kt,
  ZodTuple: Ue,
  ZodRecord: Yt,
  ZodMap: Rr,
  ZodSet: ht,
  ZodFunction: kt,
  ZodLazy: Jt,
  ZodLiteral: Xt,
  ZodEnum: rt,
  ZodNativeEnum: Qt,
  ZodPromise: $t,
  ZodEffects: Ne,
  ZodTransformer: Ne,
  ZodOptional: Fe,
  ZodNullable: nt,
  ZodDefault: er,
  ZodCatch: tr,
  ZodNaN: $r,
  BRAND: zc,
  ZodBranded: Fn,
  ZodPipeline: cr,
  ZodReadonly: rr,
  custom: Ls,
  Schema: O,
  ZodSchema: O,
  late: Bc,
  get ZodFirstPartyTypeKind() {
    return C;
  },
  coerce: El,
  any: Jc,
  array: tl,
  bigint: Hc,
  boolean: Us,
  date: Wc,
  discriminatedUnion: sl,
  effect: go,
  enum: hl,
  function: dl,
  instanceof: Gc,
  intersection: al,
  lazy: fl,
  literal: pl,
  map: ll,
  nan: Vc,
  nativeEnum: ml,
  never: Qc,
  null: Yc,
  nullable: _l,
  number: Ds,
  object: rl,
  oboolean: Sl,
  onumber: xl,
  optional: yl,
  ostring: wl,
  pipeline: bl,
  preprocess: vl,
  promise: gl,
  record: cl,
  set: ul,
  strictObject: nl,
  string: Fs,
  symbol: qc,
  transformer: go,
  tuple: il,
  undefined: Kc,
  union: ol,
  unknown: Xc,
  void: el,
  NEVER: kl,
  ZodIssueCode: g,
  quotelessJson: Ec,
  ZodError: _e
});
const Pl = K.object({
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
function js(t, e) {
  return new URL(e, t).toString();
}
function Tl(t) {
  return fetch(t).then((e) => e.json()).then((e) => {
    if (!Pl.safeParse(e).success)
      throw new Error("Invalid plugin manifest");
    return e;
  }).catch((e) => {
    throw console.error(e), e;
  });
}
function yo(t) {
  return !t.host && !t.code.startsWith("http") ? Promise.resolve(t.code) : fetch(js(t.host, t.code)).then((e) => {
    if (e.ok)
      return e.text();
    throw new Error("Failed to load plugin code");
  });
}
const Al = K.object({
  width: K.number().positive(),
  height: K.number().positive()
});
function Il(t, e, r, n, o) {
  const s = document.createElement("plugin-modal");
  s.setTheme(r);
  const i = 200, c = 200, l = 335, u = 590, d = {
    blockStart: 40,
    inlineEnd: 320
  };
  s.style.setProperty(
    "--modal-block-start",
    `${d.blockStart}px`
  ), s.style.setProperty(
    "--modal-inline-end",
    `${d.inlineEnd}px`
  );
  const f = window.innerWidth - d.inlineEnd, h = window.innerHeight - d.blockStart;
  let p = Math.min((n == null ? void 0 : n.width) || l, f), m = Math.min((n == null ? void 0 : n.height) || u, h);
  return p = Math.max(p, i), m = Math.max(m, c), s.setAttribute("title", t), s.setAttribute("iframe-src", e), s.setAttribute("width", String(p)), s.setAttribute("height", String(m)), o && s.setAttribute("allow-downloads", "true"), document.body.appendChild(s), s;
}
const Cl = K.function().args(
  K.string(),
  K.string(),
  K.enum(["dark", "light"]),
  Al.optional(),
  K.boolean().optional()
).implement((t, e, r, n, o) => Il(t, e, r, n, o));
async function Rl(t, e, r, n) {
  let o = await yo(e), s = !1, i = !1, c = null, l = [];
  const u = /* @__PURE__ */ new Set(), d = !!e.permissions.find(
    ($) => $ === "allow:downloads"
  ), f = t.addListener("themechange", ($) => {
    c == null || c.setTheme($);
  }), h = t.addListener("finish", () => {
    v(), t == null || t.removeListener(h);
  });
  let p = [];
  const m = () => {
    L(f), p.forEach(($) => {
      L($);
    }), l = [], p = [];
  }, v = () => {
    m(), u.forEach(clearTimeout), u.clear(), c && (c.removeEventListener("close", v), c.remove(), c = null), i = !0, r();
  }, S = async () => {
    if (!s) {
      s = !0;
      return;
    }
    m(), o = await yo(e), n(o);
  }, x = ($, j, F) => {
    const Y = t.theme, J = js(e.host, j);
    (c == null ? void 0 : c.getAttribute("iframe-src")) !== J && (c = Cl($, J, Y, F, d), c.setTheme(Y), c.addEventListener("close", v, {
      once: !0
    }), c.addEventListener("load", S));
  }, I = ($) => {
    l.push($);
  }, E = ($, j, F) => {
    const Y = t.addListener(
      $,
      (...J) => {
        i || j(...J);
      },
      F
    );
    return p.push(Y), Y;
  }, L = ($) => {
    t.removeListener($);
  };
  return {
    close: v,
    destroyListener: L,
    openModal: x,
    getModal: () => c,
    registerListener: E,
    registerMessageCallback: I,
    sendMessage: ($) => {
      l.forEach((j) => j($));
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
const $l = [
  "finish",
  "pagechange",
  "filechange",
  "selectionchange",
  "themechange",
  "shapechange",
  "contentsave"
];
function Nl(t) {
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
        return K.enum($l).parse(n), K.function().parse(o), e("content:read"), t.registerListener(n, o, s);
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
let _o = !1;
const P = {
  hardenIntrinsics: () => {
    _o || (_o = !0, hardenIntrinsics());
  },
  createCompartment: (t) => new Compartment(t),
  harden: (t) => harden(t),
  safeReturn(t) {
    return t == null ? t : harden(t);
  }
};
function Ol(t) {
  P.hardenIntrinsics();
  const e = Nl(t), r = {
    get(c, l, u) {
      const d = Reflect.get(c, l, u);
      return typeof d == "function" ? function(...f) {
        const h = d.apply(c, f);
        return P.safeReturn(h);
      } : P.safeReturn(d);
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
      return P.safeReturn(f);
    });
  }, s = {
    penpot: n,
    fetch: P.harden(o),
    setTimeout: P.harden(
      (...[c, l]) => {
        const u = setTimeout(() => {
          c();
        }, l);
        return t.timeouts.add(u), P.safeReturn(u);
      }
    ),
    clearTimeout: P.harden((c) => {
      clearTimeout(c), t.timeouts.delete(c);
    }),
    /**
     * GLOBAL FUNCTIONS ACCESIBLE TO PLUGINS
     **/
    isFinite: P.harden(isFinite),
    isNaN: P.harden(isNaN),
    parseFloat: P.harden(parseFloat),
    parseInt: P.harden(parseInt),
    decodeURI: P.harden(decodeURI),
    decodeURIComponent: P.harden(decodeURIComponent),
    encodeURI: P.harden(encodeURI),
    encodeURIComponent: P.harden(encodeURIComponent),
    Object: P.harden(Object),
    Boolean: P.harden(Boolean),
    Symbol: P.harden(Symbol),
    Number: P.harden(Number),
    BigInt: P.harden(BigInt),
    Math: P.harden(Math),
    Date: P.harden(Date),
    String: P.harden(String),
    RegExp: P.harden(RegExp),
    Array: P.harden(Array),
    Int8Array: P.harden(Int8Array),
    Uint8Array: P.harden(Uint8Array),
    Uint8ClampedArray: P.harden(Uint8ClampedArray),
    Int16Array: P.harden(Int16Array),
    Uint16Array: P.harden(Uint16Array),
    Int32Array: P.harden(Int32Array),
    Uint32Array: P.harden(Uint32Array),
    BigInt64Array: P.harden(BigInt64Array),
    BigUint64Array: P.harden(BigUint64Array),
    Float32Array: P.harden(Float32Array),
    Float64Array: P.harden(Float64Array),
    Map: P.harden(Map),
    Set: P.harden(Set),
    WeakMap: P.harden(WeakMap),
    WeakSet: P.harden(WeakSet),
    ArrayBuffer: P.harden(ArrayBuffer),
    DataView: P.harden(DataView),
    Atomics: P.harden(Atomics),
    JSON: P.harden(JSON),
    Promise: P.harden(Promise),
    Proxy: P.harden(Proxy),
    Intl: P.harden(Intl),
    // Window properties
    console: P.harden(window.console),
    devicePixelRatio: P.harden(window.devicePixelRatio),
    atob: P.harden(window.atob),
    btoa: P.harden(window.btoa),
    structuredClone: P.harden(window.structuredClone)
  }, i = P.createCompartment(s);
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
async function Ml(t, e, r) {
  const n = async () => {
    try {
      s.evaluate();
    } catch (i) {
      console.error(i), o.close();
    }
  }, o = await Rl(
    t,
    e,
    function() {
      s.cleanGlobalThis(), r();
    },
    function() {
      n();
    }
  ), s = Ol(o);
  return n(), {
    plugin: o,
    compartment: s
  };
}
let Pt = [], gn = null;
function Ll(t) {
  gn = t;
}
const vo = () => {
  Pt.forEach((t) => {
    t.plugin.close();
  }), Pt = [];
};
window.addEventListener("message", (t) => {
  try {
    for (const e of Pt)
      e.plugin.sendMessage(t.data);
  } catch (e) {
    console.error(e);
  }
});
const Fl = async function(t) {
  try {
    const e = gn && gn(t.pluginId);
    if (!e)
      return;
    vo();
    const r = await Ml(
      P.harden(e),
      t,
      () => {
        Pt = Pt.filter((n) => n !== r);
      }
    );
    Pt.push(r);
  } catch (e) {
    vo(), console.error(e);
  }
}, Zs = async function(t) {
  Fl(t);
}, Dl = async function(t) {
  const e = await Tl(t);
  Zs(e);
};
console.log("%c[PLUGINS] Loading plugin system", "color: #008d7c");
repairIntrinsics({
  evalTaming: "unsafeEval",
  stackFiltering: "verbose",
  errorTaming: "unsafe",
  consoleTaming: "unsafe",
  errorTrapping: "none"
});
const bo = globalThis;
bo.initPluginsRuntime = (t) => {
  try {
    console.log("%c[PLUGINS] Initialize runtime", "color: #008d7c"), Ll(t), bo.ɵcontext = t("TEST"), globalThis.ɵloadPlugin = Zs, globalThis.ɵloadPluginByUrl = Dl;
  } catch (e) {
    console.error(e);
  }
};
//# sourceMappingURL=index.js.map
