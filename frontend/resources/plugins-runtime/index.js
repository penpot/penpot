var Zn = (t) => {
  throw TypeError(t);
};
var zn = (t, e, r) => e.has(t) || Zn("Cannot " + r);
var Ie = (t, e, r) => (zn(t, e, "read from private field"), r ? r.call(t) : e.get(t)), Wr = (t, e, r) => e.has(t) ? Zn("Cannot add the same private member more than once") : e instanceof WeakSet ? e.add(t) : e.set(t, r), qr = (t, e, r, n) => (zn(t, e, "write to private field"), n ? n.call(t, r) : e.set(t, r), r);
const P = globalThis, {
  Array: Bs,
  Date: Gs,
  FinalizationRegistry: Tt,
  Float32Array: Vs,
  JSON: Hs,
  Map: Ce,
  Math: Ws,
  Number: wo,
  Object: yn,
  Promise: qs,
  Proxy: Nr,
  Reflect: Ks,
  RegExp: Je,
  Set: Nt,
  String: ye,
  Symbol: wt,
  WeakMap: Ue,
  WeakSet: Ot
} = globalThis, {
  // The feral Error constructor is safe for internal use, but must not be
  // revealed to post-lockdown code in any compartment including the start
  // compartment since in V8 at least it bears stack inspection capabilities.
  Error: oe,
  RangeError: Ys,
  ReferenceError: zt,
  SyntaxError: or,
  TypeError: _,
  AggregateError: Kr
} = globalThis, {
  assign: Or,
  create: V,
  defineProperties: z,
  entries: fe,
  freeze: y,
  getOwnPropertyDescriptor: Q,
  getOwnPropertyDescriptors: je,
  getOwnPropertyNames: It,
  getPrototypeOf: G,
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
        ye(e)
      )}. (SES_DEFINE_PROPERTY_FAILED_SILENTLY)`
    );
  return n;
}, {
  apply: ae,
  construct: yr,
  get: na,
  getOwnPropertyDescriptor: oa,
  has: To,
  isExtensible: sa,
  ownKeys: Be,
  preventExtensions: aa,
  set: Io
} = Ks, { isArray: xt, prototype: Ee } = Bs, { prototype: Mt } = Ce, { prototype: Lr } = RegExp, { prototype: ar } = Nt, { prototype: Ze } = ye, { prototype: Fr } = Ue, { prototype: Ao } = Ot, { prototype: vn } = Function, { prototype: Co } = qs, { prototype: $o } = G(
  // eslint-disable-next-line no-empty-function, func-names
  function* () {
  }
), ia = G(Uint8Array.prototype), { bind: on } = vn, T = on.bind(on.call), ie = T(_n.hasOwnProperty), Qe = T(Ee.filter), dt = T(Ee.forEach), Dr = T(Ee.includes), Lt = T(Ee.join), ce = (
  /** @type {any} */
  T(Ee.map)
), Ro = (
  /** @type {any} */
  T(Ee.flatMap)
), _r = T(Ee.pop), ee = T(Ee.push), ca = T(Ee.slice), la = T(Ee.some), No = T(Ee.sort), ua = T(Ee[sr]), ue = T(Mt.set), Ge = T(Mt.get), Ur = T(Mt.has), da = T(Mt.delete), fa = T(Mt.entries), pa = T(Mt[sr]), bn = T(ar.add);
T(ar.delete);
const Bn = T(ar.forEach), wn = T(ar.has), ha = T(ar[sr]), xn = T(Lr.test), Sn = T(Lr.exec), ma = T(Lr[ko]), Oo = T(Ze.endsWith), Mo = T(Ze.includes), ga = T(Ze.indexOf);
T(Ze.match);
const vr = T($o.next), Lo = T($o.throw), br = (
  /** @type {any} */
  T(Ze.replace)
), ya = T(Ze.search), En = T(Ze.slice), kn = T(Ze.split), Fo = T(Ze.startsWith), _a = T(Ze[sr]), va = T(Fr.delete), Z = T(Fr.get), St = T(Fr.has), de = T(Fr.set), jr = T(Ao.add), ir = T(Ao.has), ba = T(vn.toString), wa = T(on);
T(Co.catch);
const Do = (
  /** @type {any} */
  T(Co.then)
), xa = Tt && T(Tt.prototype.register);
Tt && T(Tt.prototype.unregister);
const Pn = y(V(null)), Se = (t) => yn(t) === t, Zr = (t) => t instanceof oe, Uo = eval, xe = Function, Sa = () => {
  throw _('Cannot eval with evalTaming set to "noEval" (SES_NO_EVAL)');
}, qe = Q(Error("er1"), "stack"), Jr = Q(_("er2"), "stack");
let jo, Zo;
if (qe && Jr && qe.get)
  if (
    // In the v8 case as we understand it, all errors have an own stack
    // accessor property, but within the same realm, all these accessor
    // properties have the same getter and have the same setter.
    // This is therefore the case that we repair.
    typeof qe.get == "function" && qe.get === Jr.get && typeof qe.set == "function" && qe.set === Jr.set
  )
    jo = y(qe.get), Zo = y(qe.set);
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
const { freeze: ct } = Object, { apply: Pa } = Reflect, Tn = (t) => (e, ...r) => Pa(t, e, r), Ta = Tn(Array.prototype.push), Gn = Tn(Array.prototype.includes), Ia = Tn(String.prototype.split), st = JSON.stringify, ur = (t, ...e) => {
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
    return ct(l === "" ? [] : Ia(l, ","));
  };
  ct(o);
  const a = (c, l) => Gn(o(c), l), i = () => ct([...r]);
  return ct(i), ct({
    getEnvironmentOption: n,
    getEnvironmentOptionsList: o,
    environmentOptionsListHas: a,
    getCapturedEnvironmentOptionNames: i
  });
};
ct(zo);
const {
  getEnvironmentOption: he,
  getEnvironmentOptionsList: Gl,
  environmentOptionsListHas: Vl
} = zo(globalThis, !0), wr = (t) => (t = `${t}`, t.length >= 1 && Mo("aeiouAEIOU", t[0]) ? `an ${t}` : `a ${t}`);
y(wr);
const Bo = (t, e = void 0) => {
  const r = new Nt(), n = (o, a) => {
    switch (typeof a) {
      case "object": {
        if (a === null)
          return null;
        if (wn(r, a))
          return "[Seen]";
        if (bn(r, a), Zr(a))
          return `[${a.name}: ${a.message}]`;
        if (Xe in a)
          return `[${a[Xe]}]`;
        if (xt(a))
          return a;
        const i = xo(a);
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
        No(i);
        const l = ce(i, (u) => [u, a[u]]);
        return mt(l);
      }
      case "function":
        return `[Function ${a.name || "<anon>"}]`;
      case "string":
        return Fo(a, "[") ? `[${a}]` : a;
      case "undefined":
      case "symbol":
        return `[${ye(a)}]`;
      case "bigint":
        return `[${a}n]`;
      case "number":
        return Mr(a, NaN) ? "[NaN]" : a === 1 / 0 ? "[Infinity]" : a === -1 / 0 ? "[-Infinity]" : a;
      default:
        return a;
    }
  };
  try {
    return Po(t, n, e);
  } catch {
    return "[Something that failed to stringify]";
  }
};
y(Bo);
const { isSafeInteger: Aa } = Number, { freeze: _t } = Object, { toStringTag: Ca } = Symbol, Vn = (t) => {
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
  if (!Aa(t) || t < 0)
    throw TypeError("keysBudget must be a safe non-negative integer number");
  const e = /* @__PURE__ */ new WeakMap();
  let r = 0;
  const n = Vn(void 0), o = (d) => {
    const f = e.get(d);
    if (!(f === void 0 || f.data === void 0))
      return Qr(f), Hn(n, f), f;
  }, a = (d) => o(d) !== void 0;
  _t(a);
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
    has: a,
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
const { freeze: mr } = Object, { isSafeInteger: $a } = Number, Ra = 1e3, Na = 100, Vo = (t = Ra, e = Na) => {
  if (!$a(e) || e < 1)
    throw TypeError(
      "argsPerErrorBudget must be a safe positive integer number"
    );
  const r = Go(t), n = (a, i) => {
    const c = r.get(a);
    c !== void 0 ? (c.length >= e && c.shift(), c.push(i)) : r.set(a, [i]);
  };
  mr(n);
  const o = (a) => {
    const i = r.get(a);
    return r.delete(a), i;
  };
  return mr(o), mr({
    addLogArgs: n,
    takeLogArgsArray: o
  });
};
mr(Vo);
const At = new Ue(), j = (t, e = void 0) => {
  const r = y({
    toString: y(() => Bo(t, e))
  });
  return de(At, r, t), r;
};
y(j);
const Oa = y(/^[\w:-]( ?[\w:-])*$/), xr = (t, e = void 0) => {
  if (typeof t != "string" || !xn(Oa, t))
    return j(t, e);
  const r = y({
    toString: y(() => t)
  });
  return de(At, r, t), r;
};
y(xr);
const zr = new Ue(), Ho = ({ template: t, args: e }) => {
  const r = [t[0]];
  for (let n = 0; n < e.length; n += 1) {
    const o = e[n];
    let a;
    St(At, o) ? a = `${o}` : Zr(o) ? a = `(${wr(o.name)})` : a = `(${wr(typeof o)})`, ee(r, a, t[n + 1]);
  }
  return Lt(r, "");
}, Wo = y({
  toString() {
    const t = Z(zr, this);
    return t === void 0 ? "[Not a DetailsToken]" : Ho(t);
  }
});
y(Wo.toString);
const se = (t, ...e) => {
  const r = y({ __proto__: Wo });
  return de(zr, r, { template: t, args: e }), /** @type {DetailsToken} */
  /** @type {unknown} */
  r;
};
y(se);
const qo = (t, ...e) => (e = ce(
  e,
  (r) => St(At, r) ? r : j(r)
), se(t, ...e));
y(qo);
const Ko = ({ template: t, args: e }) => {
  const r = [t[0]];
  for (let n = 0; n < e.length; n += 1) {
    let o = e[n];
    St(At, o) && (o = Z(At, o));
    const a = br(_r(r) || "", / $/, "");
    a !== "" && ee(r, a);
    const i = br(t[n + 1], /^ /, "");
    ee(r, o, i);
  }
  return r[r.length - 1] === "" && _r(r), r;
}, gr = new Ue();
let sn = 0;
const Wn = new Ue(), Yo = (t, e = t.name) => {
  let r = Z(Wn, t);
  return r !== void 0 || (sn += 1, r = `${e}#${sn}`, de(Wn, t, r)), r;
}, Ma = (t) => {
  const e = je(t), {
    name: r,
    message: n,
    errors: o = void 0,
    cause: a = void 0,
    stack: i = void 0,
    ...c
  } = e, l = Be(c);
  if (l.length >= 1) {
    for (const d of l)
      delete t[d];
    const u = V(_n, c);
    Br(
      t,
      se`originally with properties ${j(u)}`
    );
  }
  for (const u of Be(t)) {
    const d = e[u];
    d && ie(d, "get") && U(t, u, {
      value: t[u]
      // invoke the getter to convert to data property
    });
  }
  y(t);
}, Me = (t = se`Assert failed`, e = P.Error, {
  errorName: r = void 0,
  cause: n = void 0,
  errors: o = void 0,
  sanitize: a = !0
} = {}) => {
  typeof t == "string" && (t = se([t]));
  const i = Z(zr, t);
  if (i === void 0)
    throw _(`unrecognized details ${j(t)}`);
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
  })), de(gr, u, Ko(i)), r !== void 0 && Yo(u, r), a && Ma(u), u;
};
y(Me);
const { addLogArgs: La, takeLogArgsArray: Fa } = Vo(), an = new Ue(), Br = (t, e) => {
  typeof e == "string" && (e = se([e]));
  const r = Z(zr, e);
  if (r === void 0)
    throw _(`unrecognized details ${j(e)}`);
  const n = Ko(r), o = Z(an, t);
  if (o !== void 0)
    for (const a of o)
      a(t, n);
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
  getStackString: P.getStackString || Da,
  tagError: (t) => Yo(t),
  resetErrorTagNum: () => {
    sn = 0;
  },
  getMessageLogArgs: (t) => Z(gr, t),
  takeMessageLogArgs: (t) => {
    const e = Z(gr, t);
    return va(gr, t), e;
  },
  takeNoteLogArgsArray: (t, e) => {
    const r = Fa(t);
    if (e !== void 0) {
      const n = Z(an, t);
      n ? ee(n, e) : de(an, t, [e]);
    }
    return r || [];
  }
};
y(Sr);
const Gr = (t = void 0, e = !1) => {
  const r = e ? qo : se, n = r`Check failed`, o = (f = n, h = void 0, p = void 0) => {
    const m = Me(f, h, p);
    throw t !== void 0 && t(m), m;
  };
  y(o);
  const a = (f, ...h) => o(r(f, ...h));
  function i(f, h = void 0, p = void 0, m = void 0) {
    f || o(h, p, m);
  }
  const c = (f, h, p = void 0, m = void 0, b = void 0) => {
    Mr(f, h) || o(
      p || r`Expected ${f} is same as ${h}`,
      m || Ys,
      b
    );
  };
  y(c);
  const l = (f, h, p) => {
    if (typeof f !== h) {
      if (typeof h == "string" || a`${j(h)} must be a string`, p === void 0) {
        const m = wr(h);
        p = r`${f} must be ${xr(m)}`;
      }
      o(p, _);
    }
  };
  y(l);
  const d = Or(i, {
    error: Me,
    fail: o,
    equal: c,
    typeof: l,
    string: (f, h = void 0) => l(f, "string", h),
    note: Br,
    details: r,
    Fail: a,
    quote: j,
    bare: xr,
    makeAssert: Gr
  });
  return y(d);
};
y(Gr);
const Y = Gr(), qn = Y.equal, Jo = Q(
  ia,
  Xe
);
Y(Jo);
const Xo = Jo.get;
Y(Xo);
const Ua = (t) => ae(Xo, t, []) !== void 0, ja = (t) => {
  const e = +ye(t);
  return ta(e) && ye(e) === t;
}, Za = (t) => {
  Js(t), dt(Be(t), (e) => {
    const r = Q(t, e);
    Y(r), ja(e) || U(t, e, {
      ...r,
      writable: !1,
      configurable: !1
    });
  });
}, za = () => {
  if (typeof P.harden == "function")
    return P.harden;
  const t = new Ot(), { harden: e } = {
    /**
     * @template T
     * @param {T} root
     * @returns {T}
     */
    harden(r) {
      const n = new Nt();
      function o(d) {
        if (!Se(d))
          return;
        const f = typeof d;
        if (f !== "object" && f !== "function")
          throw _(`Unexpected typeof: ${f}`);
        ir(t, d) || wn(n, d) || bn(n, d);
      }
      const a = (d) => {
        Ua(d) ? Za(d) : y(d);
        const f = je(d), h = G(d);
        o(h), dt(Be(f), (p) => {
          const m = f[
            /** @type {string} */
            p
          ];
          ie(m, "value") ? o(m.value) : (o(m.get), o(m.set));
        });
      }, i = Xr === void 0 && Ea === void 0 ? (
        // On platforms without v8's error own stack accessor problem,
        // don't pay for any extra overhead.
        a
      ) : (d) => {
        if (Zr(d)) {
          const f = Q(d, "stack");
          f && f.get === Xr && f.configurable && U(d, "stack", {
            // NOTE: Calls getter during harden, which seems dangerous.
            // But we're only calling the problematic getter whose
            // hazards we think we understand.
            // @ts-expect-error TS should know FERAL_STACK_GETTER
            // cannot be `undefined` here.
            // See https://github.com/endojs/endo/pull/2232#discussion_r1575179471
            value: ae(Xr, d, [])
          });
        }
        return a(d);
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
typeof AggregateError < "u" && ee(rs, AggregateError);
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
}, s = cn, Yn = Ba, L = {
  get: s,
  set: "undefined"
}, Ne = {
  get: s,
  set: s
}, Jn = (t) => t === L || t === Ne;
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
function be(t) {
  return {
    // Properties of the TypedArray Constructors
    "[[Proto]]": "%TypedArray%",
    BYTES_PER_ELEMENT: "number",
    prototype: t
  };
}
function we(t) {
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
}, Er = {
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
    "--proto--": Ne,
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
    description: L,
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
    stackTraceLimit: Ne,
    // Non standard, v8 only, used by several, tamed to accessor
    prepareStackTrace: Ne
  },
  "%SharedError%": {
    // Properties of the Error Constructor
    "[[Proto]]": "%FunctionPrototype%",
    prototype: "%ErrorPrototype%",
    // Non standard, v8 only, used by tap
    captureStackTrace: s,
    // Non standard, v8 only, used by tap, tamed to accessor
    stackTraceLimit: Ne,
    // Non standard, v8 only, used by several, tamed to accessor
    prepareStackTrace: Ne
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
    stack: Ne,
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
    ...Xn,
    // `%InitialMath%.random()` has the standard unsafe behavior
    random: s
  },
  "%SharedMath%": {
    ...Xn,
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
    "@@species": L,
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
    "@@species": L
  },
  "%RegExpPrototype%": {
    // Properties of the RegExp Prototype Object
    constructor: "%SharedRegExp%",
    exec: s,
    dotAll: L,
    flags: L,
    global: L,
    hasIndices: L,
    ignoreCase: L,
    "@@match": s,
    "@@matchAll": s,
    multiline: L,
    "@@replace": s,
    "@@search": s,
    source: L,
    "@@split": s,
    sticky: L,
    test: s,
    toString: s,
    unicode: L,
    unicodeSets: L,
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
    "@@species": L,
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
    "@@species": L
  },
  "%TypedArrayPrototype%": {
    at: s,
    buffer: L,
    byteLength: L,
    byteOffset: L,
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
    length: L,
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
    "@@toStringTag": L,
    // See https://github.com/tc39/proposal-array-find-from-last
    findLast: s,
    findLastIndex: s,
    // https://github.com/tc39/proposal-change-array-by-copy
    toReversed: s,
    toSorted: s,
    with: s
  },
  // The TypedArray Constructors
  BigInt64Array: be("%BigInt64ArrayPrototype%"),
  BigUint64Array: be("%BigUint64ArrayPrototype%"),
  // https://github.com/tc39/proposal-float16array
  Float16Array: be("%Float16ArrayPrototype%"),
  Float32Array: be("%Float32ArrayPrototype%"),
  Float64Array: be("%Float64ArrayPrototype%"),
  Int16Array: be("%Int16ArrayPrototype%"),
  Int32Array: be("%Int32ArrayPrototype%"),
  Int8Array: be("%Int8ArrayPrototype%"),
  Uint16Array: be("%Uint16ArrayPrototype%"),
  Uint32Array: be("%Uint32ArrayPrototype%"),
  Uint8ClampedArray: be("%Uint8ClampedArrayPrototype%"),
  Uint8Array: {
    ...be("%Uint8ArrayPrototype%"),
    // https://github.com/tc39/proposal-arraybuffer-base64
    fromBase64: s,
    // https://github.com/tc39/proposal-arraybuffer-base64
    fromHex: s
  },
  "%BigInt64ArrayPrototype%": we("BigInt64Array"),
  "%BigUint64ArrayPrototype%": we("BigUint64Array"),
  // https://github.com/tc39/proposal-float16array
  "%Float16ArrayPrototype%": we("Float16Array"),
  "%Float32ArrayPrototype%": we("Float32Array"),
  "%Float64ArrayPrototype%": we("Float64Array"),
  "%Int16ArrayPrototype%": we("Int16Array"),
  "%Int32ArrayPrototype%": we("Int32Array"),
  "%Int8ArrayPrototype%": we("Int8Array"),
  "%Uint16ArrayPrototype%": we("Uint16Array"),
  "%Uint32ArrayPrototype%": we("Uint32Array"),
  "%Uint8ClampedArrayPrototype%": we("Uint8ClampedArray"),
  "%Uint8ArrayPrototype%": {
    ...we("Uint8Array"),
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
    "@@species": L,
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
    size: L,
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
    "@@species": L,
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
    size: L,
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
    "@@species": L,
    // See https://github.com/Moddable-OpenSource/moddable/issues/523
    fromString: !1,
    // See https://github.com/Moddable-OpenSource/moddable/issues/523
    fromBigInt: !1
  },
  "%ArrayBufferPrototype%": {
    byteLength: L,
    constructor: "ArrayBuffer",
    slice: s,
    "@@toStringTag": "string",
    // See https://github.com/Moddable-OpenSource/moddable/issues/523
    concat: !1,
    // See https://github.com/tc39/proposal-resizablearraybuffer
    transfer: s,
    resize: s,
    resizable: L,
    maxByteLength: L,
    // https://github.com/tc39/proposal-arraybuffer-transfer
    transferToFixedLength: s,
    detached: L
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
    buffer: L,
    byteLength: L,
    byteOffset: L,
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
    "@@species": L
  },
  "%PromisePrototype%": {
    // Properties of the Promise Prototype Object
    catch: s,
    constructor: "Promise",
    finally: s,
    then: s,
    "@@toStringTag": "string",
    // Non-standard, used in node to prevent async_hooks from breaking
    "UniqueSymbol(async_id_symbol)": Ne,
    "UniqueSymbol(trigger_async_id_symbol)": Ne,
    "UniqueSymbol(destroyed)": Ne
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
    globalThis: L,
    name: L,
    import: Yn,
    load: Yn,
    importNow: s,
    module: s,
    "@@toStringTag": "string"
  },
  lockdown: s,
  harden: { ...s, isFake: "boolean" },
  "%InitialGetStackString%": s
}, Ga = (t) => typeof t == "function";
function Va(t, e, r) {
  if (ie(t, e)) {
    const n = Q(t, e);
    if (!n || !Mr(n.value, r.value) || n.get !== r.get || n.set !== r.set || n.writable !== r.writable || n.enumerable !== r.enumerable || n.configurable !== r.configurable)
      throw _(`Conflicting definitions of ${e}`);
  }
  U(t, e, r);
}
function Ha(t, e) {
  for (const [r, n] of fe(e))
    Va(t, r, n);
}
function ns(t, e) {
  const r = { __proto__: null };
  for (const [n, o] of fe(e))
    ie(t, n) && (r[o] = t[n]);
  return r;
}
const os = () => {
  const t = V(null);
  let e;
  const r = (c) => {
    Ha(t, je(c));
  };
  y(r);
  const n = () => {
    for (const [c, l] of fe(t)) {
      if (!Se(l) || !ie(l, "prototype"))
        continue;
      const u = Er[c];
      if (typeof u != "object")
        throw _(`Expected permit object at whitelist.${c}`);
      const d = u.prototype;
      if (!d)
        throw _(`${c}.prototype property not whitelisted`);
      if (typeof d != "string" || !ie(Er, d))
        throw _(`Unrecognized ${c}.prototype whitelist entry`);
      const f = l.prototype;
      if (ie(t, d)) {
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
  return y(i), r(Qo), r(ns(P, es)), i;
}, Wa = (t) => {
  const { addIntrinsics: e, finalIntrinsics: r } = os();
  return e(ns(t, ts)), r();
};
function qa(t, e) {
  let r = !1;
  const n = (h, ...p) => (r || (console.groupCollapsed("Removing unpermitted intrinsics"), r = !0), console[h](...p)), o = ["undefined", "boolean", "number", "string", "symbol"], a = new Ce(
    wt ? ce(
      Qe(
        fe(Er["%SharedSymbol%"]),
        ([h, p]) => p === "symbol" && typeof wt[h] == "symbol"
      ),
      ([h]) => [wt[h], `@@${h}`]
    ) : []
  );
  function i(h, p) {
    if (typeof p == "string")
      return p;
    const m = Ge(a, p);
    if (typeof p == "symbol") {
      if (m)
        return m;
      {
        const b = Qs(p);
        return b !== void 0 ? `RegisteredSymbol(${b})` : `Unique${ye(p)}`;
      }
    }
    throw _(`Unexpected property name type ${h} ${p}`);
  }
  function c(h, p, m) {
    if (!Se(p))
      throw _(`Object expected: ${h}, ${p}, ${m}`);
    const b = G(p);
    if (!(b === null && m === null)) {
      if (m !== void 0 && typeof m != "string")
        throw _(`Malformed whitelist permit ${h}.__proto__`);
      if (b !== t[m || "%ObjectPrototype%"])
        throw _(`Unexpected intrinsic ${h}.__proto__ at ${m}`);
    }
  }
  function l(h, p, m, b) {
    if (typeof b == "object")
      return f(h, p, b), !0;
    if (b === !1)
      return !1;
    if (typeof b == "string") {
      if (m === "prototype" || m === "constructor") {
        if (ie(t, b)) {
          if (p !== t[b])
            throw _(`Does not match whitelist ${h}`);
          return !0;
        }
      } else if (Dr(o, b)) {
        if (typeof p !== b)
          throw _(
            `At ${h} expected ${b} not ${typeof p}`
          );
        return !0;
      }
    }
    throw _(`Unexpected whitelist permit ${b} at ${h}`);
  }
  function u(h, p, m, b) {
    const S = Q(p, m);
    if (!S)
      throw _(`Property ${m} not found at ${h}`);
    if (ie(S, "value")) {
      if (Jn(b))
        throw _(`Accessor expected at ${h}`);
      return l(h, S.value, m, b);
    }
    if (!Jn(b))
      throw _(`Accessor not expected at ${h}`);
    return l(`${h}<get>`, S.get, m, b.get) && l(`${h}<set>`, S.set, m, b.set);
  }
  function d(h, p, m) {
    const b = m === "__proto__" ? "--proto--" : m;
    if (ie(p, b))
      return p[b];
    if (typeof h == "function" && ie(cn, b))
      return cn[b];
  }
  function f(h, p, m) {
    if (p == null)
      return;
    const b = m["[[Proto]]"];
    c(h, p, b), typeof p == "function" && e(p);
    for (const S of Be(p)) {
      const x = i(h, S), I = `${h}.${x}`, E = d(p, m, x);
      if (!E || !u(I, p, S, E)) {
        E !== !1 && n("warn", `Removing ${I}`);
        try {
          delete p[S];
        } catch (A) {
          if (S in p) {
            if (typeof p == "function" && S === "prototype" && (p.prototype = void 0, p.prototype === void 0)) {
              n(
                "warn",
                `Tolerating undeletable ${I} === undefined`
              );
              continue;
            }
            n("error", `failed to delete ${I}`, A);
          } else
            n("error", `deleting ${I} threw`, A);
          throw A;
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
    xe.prototype.constructor("return 1");
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
    z(c, {
      prototype: { value: i },
      name: {
        value: r,
        writable: !1,
        enumerable: !1,
        configurable: !0
      }
    }), z(i, {
      constructor: { value: c }
    }), c !== xe.prototype.constructor && So(c, xe.prototype.constructor), t[n] = c;
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
      return new.target === void 0 ? ae(e, void 0, d) : yr(e, d, new.target);
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
    }, z(l, {
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
  return z(a, {
    now: {
      value: e.now,
      writable: !0,
      enumerable: !1,
      configurable: !0
    }
  }), z(i, {
    now: {
      value: n.now,
      writable: !0,
      enumerable: !1,
      configurable: !0
    }
  }), z(r, {
    constructor: { value: i }
  }), {
    "%InitialDate%": a,
    "%SharedDate%": i
  };
}
function Ja(t = "safe") {
  if (t !== "safe" && t !== "unsafe")
    throw _(`unrecognized mathTaming ${t}`);
  const e = Ws, r = e, { random: n, ...o } = je(e), i = V(_n, {
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
  const e = Je.prototype, r = (a = {}) => {
    const i = function(...l) {
      return new.target === void 0 ? Je(...l) : yr(Je, l, new.target);
    };
    if (z(i, {
      length: { value: 2 },
      prototype: {
        value: e,
        writable: !1,
        enumerable: !1,
        configurable: !1
      }
    }), Yr) {
      const c = Q(
        Je,
        Yr
      );
      if (!c)
        throw _("no RegExp[Symbol.species] descriptor");
      z(i, {
        [Yr]: c
      });
    }
    return i;
  }, n = r(), o = r();
  return t !== "unsafe" && delete e.compile, z(e, {
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
      const { value: p } = h, m = wn(n, f), { get: b, set: S } = Q(
        {
          get [f]() {
            return p;
          },
          set [f](x) {
            if (d === this)
              throw _(
                `Cannot assign to read only property '${ye(
                  f
                )}' of '${u}'`
              );
            ie(this, f) ? this[f] = x : (m && console.error(_(`Override property ${f}`)), U(this, f, {
              value: x,
              writable: !0,
              enumerable: !0,
              configurable: !0
            }));
          }
        },
        f
      );
      U(b, "originalValue", {
        value: p,
        writable: !1,
        enumerable: !1,
        configurable: !1
      }), U(d, f, {
        get: b,
        set: S,
        enumerable: h.enumerable,
        configurable: h.configurable
      });
    }
  }
  function a(u, d, f) {
    const h = Q(d, f);
    h && o(u, d, f, h);
  }
  function i(u, d) {
    const f = je(d);
    f && dt(Be(f), (h) => o(u, d, h, f[h]));
  }
  function c(u, d, f) {
    for (const h of Be(f)) {
      const p = Q(d, h);
      if (!p || p.get || p.set)
        continue;
      const m = `${u}.${ye(h)}`, b = f[h];
      if (b === !0)
        a(m, d, h);
      else if (b === "*")
        i(m, p.value);
      else if (Se(b))
        c(m, p.value, b);
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
const { Fail: ln, quote: kr } = Y, ri = /^(\w*[a-z])Locale([A-Z]\w*)$/, as = {
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
    U(ye.prototype, "localeCompare", {
      value: ni
    });
    for (const r of It(t)) {
      const n = t[r];
      if (Se(n))
        for (const o of It(n)) {
          const a = Sn(ri, o);
          if (a) {
            typeof n[o] == "function" || ln`expected ${kr(o)} to be a function`;
            const i = `${a[1]}${a[2]}`, c = n[i];
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
}).eval, { Fail: Qn } = Y, ii = (t) => {
  const e = function(n) {
    const o = `${_r(arguments) || ""}`, a = `${Lt(arguments, ",")}`;
    new xe(a, ""), new xe(o);
    const i = `(function anonymous(${a}
) {
${o}
})`;
    return t(i);
  };
  return z(e, {
    // Ensure that any function created in any evaluator in a realm is an
    // instance of Function in any evaluator of the same realm.
    prototype: {
      value: xe.prototype,
      writable: !1,
      enumerable: !1,
      configurable: !1
    }
  }), G(xe) === xe.prototype || Qn`Function prototype is the same accross compartments`, G(e) === xe.prototype || Qn`Function constructor prototype is the same accross compartments`, e;
}, ci = (t) => {
  U(
    t,
    Xs,
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
}, is = (t) => {
  for (const [e, r] of fe(Qo))
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
  parentCompartment: a
}) => {
  for (const [c, l] of fe(es))
    ie(e, l) && U(t, c, {
      value: e[l],
      writable: !0,
      enumerable: !1,
      configurable: !0
    });
  for (const [c, l] of fe(r))
    ie(e, l) && U(t, c, {
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
  for (const [c, l] of fe(i))
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
}, { Fail: li, quote: ls } = Y, us = new Nr(
  Pn,
  y({
    get(t, e) {
      li`Please report unexpected scope handler trap: ${ls(ye(e))}`;
    }
  })
), ui = {
  get(t, e) {
  },
  set(t, e, r) {
    throw zt(`${ye(e)} is not defined`);
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
    const r = ls(ye(e));
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
  V(
    us,
    je(ui)
  )
), di = new Nr(
  Pn,
  ds
), fs = (t) => {
  const e = {
    // inherit scopeTerminator behavior
    ...ds,
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
      us,
      je(e)
    )
  );
  return new Nr(
    Pn,
    r
  );
};
y(fs);
const { Fail: fi } = Y, pi = () => {
  const t = V(null), e = y({
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
      n !== null && fi`a handler did not reset allowNextEvalToBeUnsafe ${n.err}`, z(t, e);
    },
    /** @type {null | { err: any }} */
    revoked: null
  };
  return r;
}, eo = "\\s*[@#]\\s*([a-zA-Z][a-zA-Z0-9]*)\\s*=\\s*([^\\s\\*]*)", hi = new Je(
  `(?:\\s*//${eo}|/\\*${eo}\\s*\\*/)\\s*$`
), In = (t) => {
  let e = "<unknown>";
  for (; t.length > 0; ) {
    const r = Sn(hi, t);
    if (r === null)
      break;
    t = En(t, 0, t.length - r[0].length), r[3] === "sourceURL" ? e = r[4] : r[1] === "sourceURL" && (e = r[2]);
  }
  return e;
};
function An(t, e) {
  const r = ya(t, e);
  if (r < 0)
    return -1;
  const n = t[r] === `
` ? 1 : 0;
  return kn(En(t, 0, r), `
`).length + n;
}
const ps = new Je("(?:<!--|-->)", "g"), hs = (t) => {
  const e = An(t, ps);
  if (e < 0)
    return t;
  const r = In(t);
  throw or(
    `Possible HTML comment rejected at ${r}:${e}. (SES_HTML_COMMENT_REJECTED)`
  );
}, ms = (t) => br(t, ps, (r) => r[0] === "<" ? "< ! --" : "-- >"), gs = new Je(
  "(^|[^.]|\\.\\.\\.)\\bimport(\\s*(?:\\(|/[/*]))",
  "g"
), ys = (t) => {
  const e = An(t, gs);
  if (e < 0)
    return t;
  const r = In(t);
  throw or(
    `Possible import expression rejected at ${r}:${e}. (SES_IMPORT_REJECTED)`
  );
}, _s = (t) => br(t, gs, (r, n, o) => `${n}__import__${o}`), mi = new Je(
  "(^|[^.])\\beval(\\s*\\()",
  "g"
), vs = (t) => {
  const e = An(t, mi);
  if (e < 0)
    return t;
  const r = In(t);
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
  const r = Q(t, e);
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
  ie(r, "value");
}
const _i = (t, e = {}) => {
  const r = It(t), n = It(e), o = Qe(
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
  ), a = xe(`
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
  return ae(a, t, []);
}, { Fail: bi } = Y, Cn = ({
  globalObject: t,
  moduleLexicals: e = {},
  globalTransforms: r = [],
  sloppyGlobalsMode: n = !1
}) => {
  const o = n ? fs(t) : di, a = pi(), { evalScope: i } = a, c = y({
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
      return a.allowNextEvalToBeUnsafe(), ae(l, t, [f]);
    } catch (b) {
      throw m = b, b;
    } finally {
      const b = "eval" in i;
      delete i.eval, b && (a.revoked = { err: m }, bi`handler did not reset allowNextEvalToBeUnsafe ${m}`);
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
  const e = P.process || void 0;
  if (typeof e == "object") {
    const r = Q(e, "domain");
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
const $n = y([
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
]), Rn = y([
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
  ...$n,
  ...Rn
]), Si = (t, { shouldResetForDebugging: e = !1 } = {}) => {
  e && t.resetErrorTagNum();
  let r = [];
  const n = mt(
    ce(Ss, ([i, c]) => {
      const l = (...u) => {
        ee(r, [i, ...u]);
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
  const { getStackString: r, tagError: n, takeMessageLogArgs: o, takeNoteLogArgsArray: a } = e, i = (S, x) => ce(S, (E) => Zr(E) ? (ee(x, E), `(${n(E)})`) : E), c = (S, x, I, E, A) => {
    const N = n(x), D = I === lt.MESSAGE ? `${N}:` : `${N} ${I}`, M = i(E, A);
    t[S](D, ...M);
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
      for (const A of x)
        f(S, A);
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
    const E = [], A = o(x), N = a(
      x,
      d(S)
    );
    A === void 0 ? t[S](`${I}:`, x.message) : c(
      S,
      x,
      lt.MESSAGE,
      A,
      E
    );
    let D = r(x);
    typeof D == "string" && D.length >= 1 && !Oo(D, `
`) && (D += `
`), t[S](D), x.cause && c(S, x, lt.CAUSE, [x.cause], E), x.errors && c(S, x, lt.ERRORS, x.errors, E);
    for (const M of N)
      c(S, x, lt.NOTE, M, E);
    l(S, E, I);
  }, h = ce($n, ([S, x]) => {
    const I = (...E) => {
      const A = [], N = i(E, A);
      t[S](...N), l(S, A);
    };
    return U(I, "name", { value: S }), [S, y(I)];
  }), p = Qe(
    Rn,
    ([S, x]) => S in t
  ), m = ce(p, ([S, x]) => {
    const I = (...E) => {
      t[S](...E);
    };
    return U(I, "name", { value: S }), [S, y(I)];
  }), b = mt([...h, ...m]);
  return (
    /** @type {VirtualConsole} */
    y(b)
  );
};
y(Nn);
const Ei = (t, e, r) => {
  const [n, ...o] = kn(t, e), a = Ro(o, (i) => [e, ...r, i]);
  return ["", n, ...a];
}, Es = (t) => y((r) => {
  const n = [], o = (...l) => (n.length > 0 && (l = Ro(
    l,
    (u) => typeof u == "string" && Mo(u, `
`) ? Ei(u, `
`, n) : [u]
  ), l = [...n, ...l]), r(...l)), a = (l, u) => ({ [l]: (...d) => u(...d) })[l], i = mt([
    ...ce($n, ([l]) => [
      l,
      a(l, o)
    ]),
    ...ce(Rn, ([l]) => [
      l,
      a(l, (...u) => o(l, ...u))
    ])
  ]);
  for (const l of ["group", "groupCollapsed"])
    i[l] && (i[l] = a(l, (...u) => {
      u.length >= 1 && o(...u), ee(n, " ");
    }));
  return i.groupEnd && (i.groupEnd = a("groupEnd", (...l) => {
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
  ), o = ce(n, ([i, c]) => [i, y((...u) => {
    (c === void 0 || e.canLog(c)) && t[i](...u);
  })]), a = mt(o);
  return (
    /** @type {VirtualConsole} */
    y(a)
  );
};
y(ki);
const oo = (t) => {
  if (Tt === void 0)
    return;
  let e = 0;
  const r = new Ce(), n = (d) => {
    da(r, d);
  }, o = new Ue(), a = (d) => {
    if (Ur(r, d)) {
      const f = Ge(r, d);
      n(d), t(f);
    }
  }, i = new Tt(a);
  return {
    rejectionHandledHandler: (d) => {
      const f = Z(o, d);
      n(f);
    },
    unhandledRejectionHandler: (d, f) => {
      e += 1;
      const h = e;
      ue(r, h, d), de(o, f, h), xa(i, f, h, f);
    },
    processTerminationHandler: () => {
      for (const [d, f] of fa(r))
        n(d), t(f);
    }
  };
}, tn = (t) => {
  throw _(t);
}, so = (t, e) => y((...r) => ae(t, e, r)), Pi = (t = "safe", e = "platform", r = "report", n = void 0) => {
  t === "safe" || t === "unsafe" || tn(`unrecognized consoleTaming ${t}`);
  let o;
  n === void 0 ? o = Sr : o = {
    ...Sr,
    getStackString: n
  };
  const a = (
    /** @type {VirtualConsole} */
    // eslint-disable-next-line no-nested-ternary
    typeof P.console < "u" ? P.console : typeof P.print == "function" ? (
      // Make a good-enough console for eshost (including only functions that
      // log at a specific level with no special argument interpretation).
      // https://console.spec.whatwg.org/#logging
      ((u) => y({ debug: u, log: u, info: u, warn: u, error: u }))(
        // eslint-disable-next-line no-undef
        so(P.print)
      )
    ) : void 0
  );
  if (a && a.log)
    for (const u of ["warn", "error"])
      a[u] || U(a, u, {
        value: so(a.log, a)
      });
  const i = (
    /** @type {VirtualConsole} */
    t === "unsafe" ? a : Nn(a, o)
  ), c = P.process || void 0;
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
  const l = P.window || void 0;
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
], Ii = (t) => {
  const r = mt(ce(Ti, (n) => {
    const o = t[n];
    return [n, () => ae(o, t, [])];
  }));
  return V(r, {});
}, Ai = (t) => ce(t, Ii), Ci = /\/node_modules\//, $i = /^(?:node:)?internal\//, Ri = /\/packages\/ses\/src\/error\/assert.js$/, Ni = /\/packages\/eventual-send\/src\//, Oi = [
  Ci,
  $i,
  Ri,
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
  const o = t.captureStackTrace, a = (p) => n === "verbose" ? !0 : Mi(p.getFileName()), i = (p) => {
    let m = `${p}`;
    return n === "concise" && (m = Ui(m)), `
  at ${m}`;
  }, c = (p, m) => Lt(
    ce(Qe(m, a), i),
    ""
  ), l = new Ue(), u = {
    // The optional `optFn` argument is for cutting off the bottom of
    // the stack --- for capturing the stack only above the topmost
    // call to that function. Since this isn't the "real" captureStackTrace
    // but instead calls the real one, if no other cutoff is provided,
    // we cut this one off.
    captureStackTrace(p, m = u.captureStackTrace) {
      if (typeof o == "function") {
        ae(o, t, [p, m]);
        return;
      }
      Io(p, "stack", "");
    },
    // Shim of proposed special power, to reside by default only
    // in the start compartment, for getting the stack traceback
    // string associated with an error.
    // See https://tc39.es/proposal-error-stacks/
    getStackString(p) {
      let m = Z(l, p);
      if (m === void 0 && (p.stack, m = Z(l, p), m || (m = { stackString: "" }, de(l, p, m))), m.stackString !== void 0)
        return m.stackString;
      const b = c(p, m.callSites);
      return de(l, p, { stackString: b }), b;
    },
    prepareStackTrace(p, m) {
      if (r === "unsafe") {
        const b = c(p, m);
        return de(l, p, { stackString: b }), `${p}${b}`;
      } else
        return de(l, p, { callSites: m }), "";
    }
  }, d = u.prepareStackTrace;
  t.prepareStackTrace = d;
  const f = new Ot([d]), h = (p) => {
    if (ir(f, p))
      return p;
    const m = {
      prepareStackTrace(b, S) {
        return de(l, b, { callSites: S }), p(b, Ai(S));
      }
    };
    return jr(f, m.prepareStackTrace), m.prepareStackTrace;
  };
  return z(e, {
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
}, ao = Q(oe.prototype, "stack"), io = ao && ao.get, Zi = {
  getStackString(t) {
    return typeof io == "function" ? ae(io, t, []) : "stack" in t ? `${t.stack}` : "";
  }
};
let dr = Zi.getStackString;
function zi(t = "safe", e = "concise") {
  if (t !== "safe" && t !== "unsafe" && t !== "unsafe-debug")
    throw _(`unrecognized errorTaming ${t}`);
  if (e !== "concise" && e !== "verbose")
    throw _(`unrecognized stackFiltering ${e}`);
  const r = oe.prototype, { captureStackTrace: n } = oe, o = typeof n == "function" ? "v8" : "unknown", a = (l = {}) => {
    const u = function(...f) {
      let h;
      return new.target === void 0 ? h = ae(oe, this, f) : h = yr(oe, f, new.target), o === "v8" && ae(n, oe, [h, u]), h;
    };
    return z(u, {
      length: { value: 1 },
      prototype: {
        value: r,
        writable: !1,
        enumerable: !1,
        configurable: !1
      }
    }), u;
  }, i = a({ powers: "original" }), c = a({ powers: "none" });
  z(r, {
    constructor: { value: c }
  });
  for (const l of rs)
    So(l, c);
  if (z(i, {
    stackTraceLimit: {
      get() {
        if (typeof oe.stackTraceLimit == "number")
          return oe.stackTraceLimit;
      },
      set(l) {
        if (typeof l == "number" && typeof oe.stackTraceLimit == "number") {
          oe.stackTraceLimit = l;
          return;
        }
      },
      // WTF on v8 stackTraceLimit is enumerable
      enumerable: !1,
      configurable: !0
    }
  }), t === "unsafe-debug" && o === "v8") {
    z(i, {
      prepareStackTrace: {
        get() {
          return oe.prepareStackTrace;
        },
        set(u) {
          oe.prepareStackTrace = u;
        },
        enumerable: !1,
        configurable: !0
      },
      captureStackTrace: {
        value: oe.captureStackTrace,
        writable: !0,
        enumerable: !1,
        configurable: !0
      }
    });
    const l = je(i);
    return z(c, {
      stackTraceLimit: l.stackTraceLimit,
      prepareStackTrace: l.prepareStackTrace,
      captureStackTrace: l.captureStackTrace
    }), {
      "%InitialGetStackString%": dr,
      "%InitialError%": i,
      "%SharedError%": c
    };
  }
  return z(c, {
    stackTraceLimit: {
      get() {
      },
      set(l) {
      },
      enumerable: !1,
      configurable: !0
    }
  }), o === "v8" && z(c, {
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
    oe,
    i,
    t,
    e
  ) : t === "unsafe" || t === "unsafe-debug" ? z(r, {
    stack: {
      get() {
        return dr(this);
      },
      set(l) {
        z(this, {
          stack: {
            value: l,
            writable: !0,
            enumerable: !0,
            configurable: !0
          }
        });
      }
    }
  }) : z(r, {
    stack: {
      get() {
        return `${this}`;
      },
      set(l) {
        z(this, {
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
      const a = await o.value;
      o = vr(n, a);
    } catch (a) {
      o = Lo(n, r(a));
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
  const n = V(null);
  for (const o of t) {
    const a = e(o, r);
    n[o] = a;
  }
  return y(n);
}, Dt = (t, e, r, n, o, a, i, c, l) => {
  const { resolveHook: u } = Z(t, r), d = Wi(
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
    a(Et, [
      t,
      e,
      r,
      h,
      a,
      i,
      c
    ]);
  return f;
};
function* qi(t, e, r, n, o, a, i) {
  const {
    importHook: c,
    importNowHook: l,
    moduleMap: u,
    moduleMapHook: d,
    moduleRecords: f,
    parentCompartment: h
  } = Z(t, r);
  if (Ur(f, n))
    return Ge(f, n);
  let p = u[n];
  if (p === void 0 && d !== void 0 && (p = d(n)), p === void 0) {
    const m = a(c, l);
    if (m === void 0) {
      const b = a(
        "importHook",
        "importNowHook"
      );
      throw Me(
        se`${xr(b)} needed to load module ${j(
          n
        )} in compartment ${j(r.name)}`
      );
    }
    p = m(n), St(e, p) || (p = yield p);
  }
  if (typeof p == "string")
    throw Me(
      se`Cannot map module ${j(n)} to ${j(
        p
      )} in parent compartment, use {source} module descriptor`,
      _
    );
  if (Se(p)) {
    let m = Z(e, p);
    if (m !== void 0 && (p = m), p.namespace !== void 0) {
      if (typeof p.namespace == "string") {
        const {
          compartment: x = h,
          namespace: I
        } = p;
        if (!Se(x) || !St(t, x))
          throw Me(
            se`Invalid compartment in module descriptor for specifier ${j(n)} in compartment ${j(r.name)}`
          );
        const E = yield Et(
          t,
          e,
          x,
          I,
          o,
          a,
          i
        );
        return ue(f, n, E), E;
      }
      if (Se(p.namespace)) {
        const { namespace: x } = p;
        if (m = Z(e, x), m !== void 0)
          p = m;
        else {
          const I = It(x), N = Dt(
            t,
            e,
            r,
            n,
            {
              imports: [],
              exports: I,
              execute(D) {
                for (const M of I)
                  D[M] = x[M];
              }
            },
            o,
            a,
            i,
            void 0
          );
          return ue(f, n, N), N;
        }
      } else
        throw Me(
          se`Invalid compartment in module descriptor for specifier ${j(n)} in compartment ${j(r.name)}`
        );
    }
    if (p.source !== void 0)
      if (typeof p.source == "string") {
        const {
          source: x,
          specifier: I = n,
          compartment: E = h,
          importMeta: A = void 0
        } = p, N = yield Et(
          t,
          e,
          E,
          x,
          o,
          a,
          i
        ), { moduleSource: D } = N, M = Dt(
          t,
          e,
          r,
          I,
          D,
          o,
          a,
          i,
          A
        );
        return ue(f, n, M), M;
      } else {
        const {
          source: x,
          specifier: I = n,
          importMeta: E
        } = p, A = Dt(
          t,
          e,
          r,
          I,
          x,
          o,
          a,
          i,
          E
        );
        return ue(f, n, A), A;
      }
    if (p.archive !== void 0)
      throw Me(
        se`Unsupported archive module descriptor for specifier ${j(n)} in compartment ${j(r.name)}`
      );
    if (p.record !== void 0) {
      const {
        compartment: x = r,
        specifier: I = n,
        record: E,
        importMeta: A
      } = p, N = Dt(
        t,
        e,
        x,
        I,
        E,
        o,
        a,
        i,
        A
      );
      return ue(f, n, N), ue(f, I, N), N;
    }
    if (p.compartment !== void 0 && p.specifier !== void 0) {
      if (!Se(p.compartment) || !St(t, p.compartment) || typeof p.specifier != "string")
        throw Me(
          se`Invalid compartment in module descriptor for specifier ${j(n)} in compartment ${j(r.name)}`
        );
      const x = yield Et(
        t,
        e,
        p.compartment,
        p.specifier,
        o,
        a,
        i
      );
      return ue(f, n, x), x;
    }
    const S = Dt(
      t,
      e,
      r,
      n,
      p,
      o,
      a,
      i
    );
    return ue(f, n, S), S;
  } else
    throw Me(
      se`module descriptor must be a string or object for specifier ${j(
        n
      )} in compartment ${j(r.name)}`
    );
}
const Et = (t, e, r, n, o, a, i) => {
  const { name: c } = Z(
    t,
    r
  );
  let l = Ge(i, r);
  l === void 0 && (l = new Ce(), ue(i, r, l));
  let u = Ge(l, n);
  return u !== void 0 || (u = a(Gi, Vi)(
    qi,
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
      throw Br(
        d,
        se`${d.message}, loading ${j(n)} in compartment ${j(
          c
        )}`
      ), d;
    }
  ), ue(l, n, u)), u;
}, Ki = () => {
  const t = new Nt(), e = [];
  return { enqueueJob: (o, a) => {
    bn(
      t,
      Do(o(...a), Bi, (i) => {
        ee(e, i);
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
    const r = he("COMPARTMENT_LOAD_ERRORS", "", ["verbose"]) === "verbose";
    throw _(
      `${e} (${t.length} underlying failures: ${Lt(
        ce(t, (n) => n.message + (r ? n.stack : "")),
        ", "
      )}`
    );
  }
}, Yi = (t, e) => e, Ji = (t, e) => t, co = async (t, e, r, n) => {
  const { name: o } = Z(
    t,
    r
  ), a = new Ce(), { enqueueJob: i, drainQueue: c } = Ki();
  i(Et, [
    t,
    e,
    r,
    n,
    i,
    Ji,
    a
  ]);
  const l = await c();
  ks({
    errors: l,
    errorPrefix: `Failed to load module ${j(n)} in package ${j(
      o
    )}`
  });
}, Xi = (t, e, r, n) => {
  const { name: o } = Z(
    t,
    r
  ), a = new Ce(), i = [], c = (l, u) => {
    try {
      l(...u);
    } catch (d) {
      ee(i, d);
    }
  };
  c(Et, [
    t,
    e,
    r,
    n,
    c,
    Yi,
    a
  ]), ks({
    errors: i,
    errorPrefix: `Failed to load module ${j(n)} in package ${j(
      o
    )}`
  });
}, { quote: yt } = Y, Qi = () => {
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
        return Be(e);
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
    const a = Qi();
    de(
      r,
      a.exportsProxy,
      Hi(t, n)
    ), ue(o, n, a);
  }
  return Ge(o, n);
}, ec = (t, e) => {
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
    )), { safeEvaluate: o } = Cn({
      globalObject: i,
      moduleLexicals: c,
      globalTransforms: a,
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
    __evadeImportExpressionTest__: a = !1,
    __rejectSomeDirectEvalExpressions__: i = !0
    // Note default on
  } = r, c = [...n];
  o === !0 && ee(c, ms), a === !0 && ee(c, _s), i === !0 && ee(c, vs);
  const { safeEvaluate: l } = ec(
    t,
    r
  );
  return l(e, {
    localTransforms: c
  });
}, { quote: fr } = Y, tc = (t, e, r, n, o, a) => {
  const { exportsProxy: i, exportsTarget: c, activate: l } = On(
    r,
    Z(t, r),
    n,
    o
  ), u = V(null);
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
        ee(p, S), S(h);
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
          e.execute(c, r, a);
        } catch (f) {
          throw d.errorFromExecute = f, f;
        }
      }
    }
  });
}, rc = (t, e, r, n) => {
  const {
    compartment: o,
    moduleSpecifier: a,
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
  } = i, b = Z(t, o), { __shimTransforms__: S, importMetaHook: x } = b, { exportsProxy: I, exportsTarget: E, activate: A } = On(
    o,
    b,
    e,
    a
  ), N = V(null), D = V(null), M = V(null), J = V(null), _e = V(null);
  c && Or(_e, c), p && x && x(a, _e);
  const He = V(null), ot = V(null);
  dt(fe(d), ([ve, [H]]) => {
    let W = He[H];
    if (!W) {
      let re, ne = !0, pe = [];
      const X = () => {
        if (ne)
          throw zt(`binding ${fr(H)} not yet initialized`);
        return re;
      }, ke = y((Pe) => {
        if (!ne)
          throw _(
            `Internal: binding ${fr(H)} already initialized`
          );
        re = Pe;
        const jn = pe;
        pe = null, ne = !1;
        for (const Te of jn || [])
          Te(Pe);
        return Pe;
      });
      W = {
        get: X,
        notify: (Pe) => {
          Pe !== ke && (ne ? ee(pe || [], Pe) : Pe(re));
        }
      }, He[H] = W, M[H] = ke;
    }
    N[ve] = {
      get: W.get,
      set: void 0,
      enumerable: !0,
      configurable: !1
    }, ot[ve] = W.notify;
  }), dt(
    fe(f),
    ([ve, [H, W]]) => {
      let re = He[H];
      if (!re) {
        let ne, pe = !0;
        const X = [], ke = () => {
          if (pe)
            throw zt(
              `binding ${fr(ve)} not yet initialized`
            );
          return ne;
        }, gt = y((Te) => {
          ne = Te, pe = !1;
          for (const Hr of X)
            Hr(Te);
        }), Pe = (Te) => {
          if (pe)
            throw zt(`binding ${fr(H)} not yet initialized`);
          ne = Te;
          for (const Hr of X)
            Hr(Te);
        };
        re = {
          get: ke,
          notify: (Te) => {
            Te !== gt && (ee(X, Te), pe || Te(ne));
          }
        }, He[H] = re, W && U(D, H, {
          get: ke,
          set: Pe,
          enumerable: !0,
          configurable: !1
        }), J[H] = gt;
      }
      N[ve] = {
        get: re.get,
        set: void 0,
        enumerable: !0,
        configurable: !1
      }, ot[ve] = re.notify;
    }
  );
  const We = (ve) => {
    ve(E);
  };
  ot["*"] = We;
  function lr(ve) {
    const H = V(null);
    H.default = !1;
    for (const [W, re] of ve) {
      const ne = Ge(n, W);
      ne.execute();
      const { notifiers: pe } = ne;
      for (const [X, ke] of re) {
        const gt = pe[X];
        if (!gt)
          throw or(
            `The requested module '${W}' does not provide an export named '${X}'`
          );
        for (const Pe of ke)
          gt(Pe);
      }
      if (Dr(l, W))
        for (const [X, ke] of fe(
          pe
        ))
          H[X] === void 0 ? H[X] = ke : H[X] = !1;
      if (h[W])
        for (const [X, ke] of h[W])
          H[ke] = pe[X];
    }
    for (const [W, re] of fe(H))
      if (!ot[W] && re !== !1) {
        ot[W] = re;
        let ne;
        re((X) => ne = X), N[W] = {
          get() {
            return ne;
          },
          set: void 0,
          enumerable: !0,
          configurable: !1
        };
      }
    dt(
      No(xo(N)),
      (W) => U(E, W, N[W])
    ), y(E), A();
  }
  let Ft;
  m !== void 0 ? Ft = m : Ft = Ps(b, u, {
    globalObject: o.globalThis,
    transforms: S,
    __moduleShimLexicals__: D
  });
  let Dn = !1, Un;
  function zs() {
    if (Ft) {
      const ve = Ft;
      Ft = null;
      try {
        ve(
          y({
            imports: y(lr),
            onceVar: y(M),
            liveVar: y(J),
            importMeta: _e
          })
        );
      } catch (H) {
        Dn = !0, Un = H;
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
}, { Fail: ut, quote: K } = Y, Ts = (t, e, r, n) => {
  const { name: o, moduleRecords: a } = Z(
    t,
    r
  ), i = Ge(a, n);
  if (i === void 0)
    throw zt(
      `Missing link to module ${K(n)} from compartment ${K(
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
  Se(r) || ut`Property '__fixedExportMap__' of a precompiled module source must be an object, got ${K(
    r
  )}, for module ${K(e)}`, Se(n) || ut`Property '__liveExportMap__' of a precompiled module source must be an object, got ${K(
    n
  )}, for module ${K(e)}`;
}
function sc(t) {
  return typeof t.execute == "function";
}
function ac(t, e) {
  const { exports: r } = t;
  xt(r) || ut`Property 'exports' of a third-party module source must be an array, got ${K(
    r
  )}, for module ${K(e)}`;
}
function ic(t, e) {
  Se(t) || ut`Module sources must be of type object, got ${K(
    t
  )}, for module ${K(e)}`;
  const { imports: r, exports: n, reexports: o = [] } = t;
  xt(r) || ut`Property 'imports' of a module source must be an array, got ${K(
    r
  )}, for module ${K(e)}`, xt(n) || ut`Property 'exports' of a precompiled module source must be an array, got ${K(
    n
  )}, for module ${K(e)}`, xt(o) || ut`Property 'reexports' of a precompiled module source must be an array if present, got ${K(
    o
  )}, for module ${K(e)}`;
}
const cc = (t, e, r) => {
  const { compartment: n, moduleSpecifier: o, resolvedImports: a, moduleSource: i } = r, { instances: c } = Z(t, n);
  if (Ur(c, o))
    return Ge(c, o);
  ic(i, o);
  const l = new Ce();
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
      a
    );
  else
    throw _(
      `importHook must provide a module source, got ${K(i)}`
    );
  ue(c, o, u);
  for (const [d, f] of fe(a)) {
    const h = Ts(
      t,
      e,
      n,
      f
    );
    ue(l, d, h);
  }
  return u;
}, Ut = new Ue(), Oe = new Ue(), Mn = function(e = {}, r = {}, n = {}) {
  throw _(
    "Compartment.prototype.constructor is not a valid constructor."
  );
}, lo = (t, e) => {
  const { execute: r, exportsProxy: n } = Ts(
    Oe,
    Ut,
    t,
    e
  );
  return r(), n;
}, Ln = {
  constructor: Mn,
  get globalThis() {
    return Z(Oe, this).globalObject;
  },
  get name() {
    return Z(Oe, this).name;
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
    const r = Z(Oe, this);
    return Ps(r, t, e);
  },
  module(t) {
    if (typeof t != "string")
      throw _("first argument of module() must be a string");
    const { exportsProxy: e } = On(
      this,
      Z(Oe, this),
      Ut,
      t
    );
    return e;
  },
  async import(t) {
    const { noNamespaceBox: e } = Z(Oe, this);
    if (typeof t != "string")
      throw _("first argument of import() must be a string");
    return Do(
      co(Oe, Ut, this, t),
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
    return co(Oe, Ut, this, t);
  },
  importNow(t) {
    if (typeof t != "string")
      throw _("first argument of importNow() must be a string");
    return Xi(Oe, Ut, this, t), lo(
      /** @type {Compartment} */
      this,
      t
    );
  }
};
z(Ln, {
  [Xe]: {
    value: "Compartment",
    writable: !1,
    enumerable: !1,
    configurable: !0
  }
});
z(Mn, {
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
  function o(...a) {
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
      importMetaHook: b,
      __noNamespaceBox__: S = !1
    } = lc(...a), x = [...c, ...l], I = { __proto__: null, ...u }, E = { __proto__: null, ...d }, A = new Ce(), N = new Ce(), D = new Ce(), M = {};
    ci(M), is(M);
    const { safeEvaluate: J } = Cn({
      globalObject: M,
      globalTransforms: x,
      sloppyGlobalsMode: !1
    });
    cs(M, {
      intrinsics: e,
      newGlobalPropertyNames: ts,
      makeCompartmentConstructor: t,
      parentCompartment: this,
      markVirtualizedNativeFunction: r
    }), un(
      M,
      J,
      r
    ), Or(M, I), de(Oe, this, {
      name: `${i}`,
      globalTransforms: x,
      globalObject: M,
      safeEvaluate: J,
      resolveHook: f,
      importHook: h,
      importNowHook: p,
      moduleMap: E,
      moduleMapHook: m,
      importMetaHook: b,
      moduleRecords: A,
      __shimTransforms__: l,
      deferredExports: D,
      instances: N,
      parentCompartment: n,
      noNamespaceBox: S
    });
  }
  return o.prototype = Ln, o;
};
function rn(t) {
  return G(t).constructor;
}
function uc() {
  return arguments;
}
const dc = () => {
  const t = xe.prototype.constructor, e = Q(uc(), "callee"), r = e && e.get, n = _a(new ye()), o = G(n), a = Lr[ko] && ma(/./), i = a && G(a), c = ua([]), l = G(c), u = G(Vs), d = pa(new Ce()), f = G(d), h = ha(new Nt()), p = G(h), m = G(l);
  function* b() {
  }
  const S = rn(b), x = S.prototype;
  async function* I() {
  }
  const E = rn(
    I
  ), A = E.prototype, N = A.prototype, D = G(N);
  async function M() {
  }
  const J = rn(M), _e = {
    "%InertFunction%": t,
    "%ArrayIteratorPrototype%": l,
    "%InertAsyncFunction%": J,
    "%AsyncGenerator%": A,
    "%InertAsyncGeneratorFunction%": E,
    "%AsyncGeneratorPrototype%": N,
    "%AsyncIteratorPrototype%": D,
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
  return P.Iterator && (_e["%IteratorHelperPrototype%"] = G(
    // eslint-disable-next-line @endo/no-polymorphic-call
    P.Iterator.from([]).take(0)
  ), _e["%WrapForValidIteratorPrototype%"] = G(
    // eslint-disable-next-line @endo/no-polymorphic-call
    P.Iterator.from({ next() {
    } })
  )), P.AsyncIterator && (_e["%AsyncIteratorHelperPrototype%"] = G(
    // eslint-disable-next-line @endo/no-polymorphic-call
    P.AsyncIterator.from([]).take(0)
  ), _e["%WrapForValidAsyncIteratorPrototype%"] = G(
    // eslint-disable-next-line @endo/no-polymorphic-call
    P.AsyncIterator.from({ next() {
    } })
  )), _e;
}, Is = (t, e) => {
  if (e !== "safe" && e !== "unsafe")
    throw _(`unrecognized fakeHardenOption ${e}`);
  if (e === "safe" || (Object.isExtensible = () => !1, Object.isFrozen = () => !0, Object.isSealed = () => !0, Reflect.isExtensible = () => !1, t.isFake))
    return t;
  const r = (n) => n;
  return r.isFake = !0, y(r);
};
y(Is);
const fc = () => {
  const t = wt, e = t.prototype, r = wa(wt, void 0);
  z(e, {
    constructor: {
      value: r
      // leave other `constructor` attributes as is
    }
  });
  const n = fe(
    je(t)
  ), o = mt(
    ce(n, ([a, i]) => [
      a,
      { ...i, configurable: !0 }
    ])
  );
  return z(r, o), { "%SharedSymbol%": r };
}, pc = (t) => {
  try {
    return t(), !1;
  } catch {
    return !0;
  }
}, uo = (t, e, r) => {
  if (t === void 0)
    return !1;
  const n = Q(t, e);
  if (!n || "value" in n)
    return !1;
  const { get: o, set: a } = n;
  if (typeof o != "function" || typeof a != "function" || o() !== r || ae(o, t, []) !== r)
    return !1;
  const i = "Seems to be a setter", c = { __proto__: null };
  if (ae(a, c, [i]), c[e] !== i)
    return !1;
  const l = { __proto__: t };
  return ae(a, l, [i]), l[e] !== i || !pc(() => ae(a, t, [r])) || "originalValue" in o || n.configurable === !1 ? !1 : (U(t, e, {
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
}, { Fail: fo, details: po, quote: ho } = Y;
let pr, hr;
const mc = za(), gc = () => {
  let t = !1;
  try {
    t = xe(
      "eval",
      "SES_changed",
      `        eval("SES_changed = true");
        return SES_changed;
      `
    )(Uo, !1), t || delete P.SES_changed;
  } catch {
    t = !0;
  }
  if (!t)
    throw _(
      "SES cannot initialize unless 'eval' is the original intrinsic 'eval', suitable for direct-eval (dynamically scoped eval) (SES_DIRECT_EVAL)"
    );
}, As = (t = {}) => {
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
    regExpTaming: o = he("LOCKDOWN_REGEXP_TAMING", "safe"),
    localeTaming: a = he("LOCKDOWN_LOCALE_TAMING", "safe"),
    consoleTaming: i = (
      /** @type {'unsafe' | 'safe' | undefined} */
      he("LOCKDOWN_CONSOLE_TAMING", "safe")
    ),
    overrideTaming: c = he("LOCKDOWN_OVERRIDE_TAMING", "moderate"),
    stackFiltering: l = he("LOCKDOWN_STACK_FILTERING", "concise"),
    domainTaming: u = he("LOCKDOWN_DOMAIN_TAMING", "safe"),
    evalTaming: d = he("LOCKDOWN_EVAL_TAMING", "safeEval"),
    overrideDebug: f = Qe(
      kn(he("LOCKDOWN_OVERRIDE_DEBUG", ""), ","),
      /** @param {string} debugName */
      (We) => We !== ""
    ),
    __hardenTaming__: h = he("LOCKDOWN_HARDEN_TAMING", "safe"),
    dateTaming: p = "safe",
    // deprecated
    mathTaming: m = "safe",
    // deprecated
    ...b
  } = t;
  d === "unsafeEval" || d === "safeEval" || d === "noEval" || fo`lockdown(): non supported option evalTaming: ${ho(d)}`;
  const S = Be(b);
  if (S.length === 0 || fo`lockdown(): non supported option ${ho(S)}`, pr === void 0 || // eslint-disable-next-line @endo/no-polymorphic-call
  Y.fail(
    po`Already locked down at ${pr} (SES_ALREADY_LOCKED_DOWN)`,
    _
  ), pr = _("Prior lockdown (SES_ALREADY_LOCKED_DOWN)"), pr.stack, gc(), P.Function.prototype.constructor !== P.Function && // @ts-ignore harden is absent on globalThis type def.
  typeof P.harden == "function" && // @ts-ignore lockdown is absent on globalThis type def.
  typeof P.lockdown == "function" && P.Date.prototype.constructor !== P.Date && typeof P.Date.now == "function" && // @ts-ignore does not recognize that Date constructor is a special
  // Function.
  // eslint-disable-next-line @endo/no-polymorphic-call
  Mr(P.Date.prototype.constructor.now(), NaN))
    throw _(
      "Already locked down but not by this SES instance (SES_MULTIPLE_INSTANCES)"
    );
  xi(u);
  const I = xs(), { addIntrinsics: E, completePrototypes: A, finalIntrinsics: N } = os(), D = Is(mc, h);
  E({ harden: D }), E(Ka()), E(Ya(p)), E(zi(e, l)), E(Ja(m)), E(Xa(o)), E(fc()), E(dc()), A();
  const M = N(), J = { __proto__: null };
  typeof P.Buffer == "function" && (J.Buffer = P.Buffer);
  let _e;
  e === "safe" && (_e = M["%InitialGetStackString%"]);
  const He = Pi(
    i,
    r,
    n,
    _e
  );
  if (P.console = /** @type {Console} */
  He.console, typeof /** @type {any} */
  He.console._times == "object" && (J.SafeMap = G(
    // eslint-disable-next-line no-underscore-dangle
    /** @type {any} */
    He.console._times
  )), (e === "unsafe" || e === "unsafe-debug") && P.assert === Y && (P.assert = Gr(void 0, !0)), si(M, a), hc(M), qa(M, I), is(P), cs(P, {
    intrinsics: M,
    newGlobalPropertyNames: Kn,
    makeCompartmentConstructor: dn,
    markVirtualizedNativeFunction: I
  }), d === "noEval")
    un(
      P,
      Sa,
      I
    );
  else if (d === "safeEval") {
    const { safeEvaluate: We } = Cn({ globalObject: P });
    un(
      P,
      We,
      I
    );
  }
  return () => {
    hr === void 0 || // eslint-disable-next-line @endo/no-polymorphic-call
    Y.fail(
      po`Already locked down at ${hr} (SES_ALREADY_LOCKED_DOWN)`,
      _
    ), hr = _(
      "Prior lockdown (SES_ALREADY_LOCKED_DOWN)"
    ), hr.stack, ti(M, c, f);
    const We = {
      intrinsics: M,
      hostIntrinsics: J,
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
    for (const lr of It(Kn))
      We.globals[lr] = P[lr];
    return D(We), D;
  };
};
P.lockdown = (t) => {
  const e = As(t);
  P.harden = e();
};
P.repairIntrinsics = (t) => {
  const e = As(t);
  P.hardenIntrinsics = () => {
    P.harden = e();
  };
};
const yc = xs();
P.Compartment = dn(
  dn,
  Wa(P),
  yc
);
P.assert = Y;
const _c = Es(Sr), vc = ea(
  "MAKE_CAUSAL_CONSOLE_FROM_LOGGER_KEY_FOR_SES_AVA"
);
P[vc] = _c;
const bc = (t, e) => {
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
}, wc = ":host{--spacing-4: .25rem;--spacing-8: calc(var(--spacing-4) * 2);--spacing-12: calc(var(--spacing-4) * 3);--spacing-16: calc(var(--spacing-4) * 4);--spacing-20: calc(var(--spacing-4) * 5);--spacing-24: calc(var(--spacing-4) * 6);--spacing-28: calc(var(--spacing-4) * 7);--spacing-32: calc(var(--spacing-4) * 8);--spacing-36: calc(var(--spacing-4) * 9);--spacing-40: calc(var(--spacing-4) * 10);--font-weight-regular: 400;--font-weight-bold: 500;--font-line-height-s: 1.2;--font-line-height-m: 1.4;--font-line-height-l: 1.5;--font-size-s: 12px;--font-size-m: 14px;--font-size-l: 16px}[data-theme]{background-color:var(--color-background-primary);color:var(--color-foreground-secondary)}.wrapper{box-sizing:border-box;display:flex;flex-direction:column;position:fixed;inset-block-start:var(--modal-block-start);inset-inline-end:var(--modal-inline-end);z-index:1000;padding:25px;border-radius:15px;border:2px solid var(--color-background-quaternary);box-shadow:0 0 10px #0000004d}.header{align-items:center;display:flex;justify-content:space-between;border-block-end:2px solid var(--color-background-quaternary);padding-block-end:var(--spacing-4)}button{background:transparent;border:0;cursor:pointer;padding:0}h1{font-size:var(--font-size-s);font-weight:var(--font-weight-bold);margin:0;margin-inline-end:var(--spacing-4);-webkit-user-select:none;user-select:none}iframe{border:none;inline-size:100%;block-size:100%}", xc = `
<svg width="16"  height="16"xmlns="http://www.w3.org/2000/svg" fill="none"><g class="fills"><rect rx="0" ry="0" width="16" height="16" class="frame-background"/></g><g class="frame-children"><path d="M11.997 3.997 8 8l-3.997 4.003m-.006-8L8 8l4.003 3.997" class="fills"/><g class="strokes"><path d="M11.997 3.997 8 8l-3.997 4.003m-.006-8L8 8l4.003 3.997" style="fill: none; stroke-width: 1; stroke: rgb(143, 157, 163); stroke-opacity: 1; stroke-linecap: round;" class="stroke-shape"/></g></g></svg>`;
var me, nr;
class Sc extends HTMLElement {
  constructor() {
    super();
    Wr(this, me, null);
    Wr(this, nr, null);
    this.attachShadow({ mode: "open" });
  }
  setTheme(r) {
    Ie(this, me) && Ie(this, me).setAttribute("data-theme", r);
  }
  disconnectedCallback() {
    var r;
    (r = Ie(this, nr)) == null || r.call(this);
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
    qr(this, me, document.createElement("div")), Ie(this, me).classList.add("wrapper"), Ie(this, me).style.inlineSize = `${o}px`, Ie(this, me).style.blockSize = `${a}px`, qr(this, nr, bc(Ie(this, me), () => {
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
    const u = document.createElement("iframe");
    u.src = n, u.allow = "", u.sandbox.add(
      "allow-scripts",
      "allow-forms",
      "allow-modals",
      "allow-popups",
      "allow-popups-to-escape-sandbox",
      "allow-storage-access-by-user-activation"
    ), u.addEventListener("load", () => {
      var f;
      (f = this.shadowRoot) == null || f.dispatchEvent(
        new CustomEvent("load", {
          composed: !0,
          bubbles: !0
        })
      );
    }), this.addEventListener("message", (f) => {
      u.contentWindow && u.contentWindow.postMessage(f.detail, "*");
    }), this.shadowRoot.appendChild(Ie(this, me)), Ie(this, me).appendChild(i), Ie(this, me).appendChild(u);
    const d = document.createElement("style");
    d.textContent = wc, this.shadowRoot.appendChild(d), this.calculateZIndex();
  }
}
me = new WeakMap(), nr = new WeakMap();
customElements.define("plugin-modal", Sc);
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
})(F || (F = {}));
var fn;
(function(t) {
  t.mergeShapes = (e, r) => ({
    ...e,
    ...r
    // second overwrites first
  });
})(fn || (fn = {}));
const w = F.arrayToEnum([
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
]), Ec = (t) => JSON.stringify(t, null, 2).replace(/"([^"]+)":/g, "$1:");
class ge extends Error {
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
    if (!(e instanceof ge))
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
ge.create = (t) => new ge(t);
const Ct = (t, e) => {
  let r;
  switch (t.code) {
    case g.invalid_type:
      t.received === w.undefined ? r = "Required" : r = `Expected ${t.expected}, received ${t.received}`;
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
let Cs = Ct;
function kc(t) {
  Cs = t;
}
function Pr() {
  return Cs;
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
  const l = n.filter((u) => !!u).slice().reverse();
  for (const u of l)
    c = u(i, { data: e, defaultError: c }).message;
  return {
    ...o,
    path: a,
    message: c
  };
}, Pc = [];
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
class te {
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
      const a = await o.key, i = await o.value;
      n.push({
        key: a,
        value: i
      });
    }
    return te.mergeObjectSync(e, n);
  }
  static mergeObjectSync(e, r) {
    const n = {};
    for (const o of r) {
      const { key: a, value: i } = o;
      if (a.status === "aborted" || i.status === "aborted")
        return $;
      a.status === "dirty" && e.dirty(), i.status === "dirty" && e.dirty(), a.value !== "__proto__" && (typeof i.value < "u" || o.alwaysSet) && (n[a.value] = i.value);
    }
    return { status: e.value, value: n };
  }
}
const $ = Object.freeze({
  status: "aborted"
}), bt = (t) => ({ status: "dirty", value: t }), le = (t) => ({ status: "valid", value: t }), pn = (t) => t.status === "aborted", hn = (t) => t.status === "dirty", Bt = (t) => t.status === "valid", Gt = (t) => typeof Promise < "u" && t instanceof Promise;
function Ir(t, e, r, n) {
  if (typeof e == "function" ? t !== e || !n : !e.has(t)) throw new TypeError("Cannot read private member from an object whose class did not declare it");
  return e.get(t);
}
function $s(t, e, r, n, o) {
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
      const r = new ge(t.common.issues);
      return this._error = r, this._error;
    }
  };
};
function R(t) {
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
      status: new te(),
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
    return mo(o, a);
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
    return mo(n, a);
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
    return new Re({
      schema: this,
      typeName: C.ZodEffects,
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
    return Rt.create(this, this._def);
  }
  or(e) {
    return qt.create([this, e], this._def);
  }
  and(e) {
    return Kt.create(this, e, this._def);
  }
  transform(e) {
    return new Re({
      ...R(this._def),
      schema: this,
      typeName: C.ZodEffects,
      effect: { type: "transform", transform: e }
    });
  }
  default(e) {
    const r = typeof e == "function" ? e : () => e;
    return new er({
      ...R(this._def),
      innerType: this,
      defaultValue: r,
      typeName: C.ZodDefault
    });
  }
  brand() {
    return new Fn({
      typeName: C.ZodBranded,
      type: this,
      ...R(this._def)
    });
  }
  catch(e) {
    const r = typeof e == "function" ? e : () => e;
    return new tr({
      ...R(this._def),
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
const Tc = /^c[^\s-]{8,}$/i, Ic = /^[0-9a-z]+$/, Ac = /^[0-9A-HJKMNP-TV-Z]{26}$/, Cc = /^[0-9a-fA-F]{8}\b-[0-9a-fA-F]{4}\b-[0-9a-fA-F]{4}\b-[0-9a-fA-F]{4}\b-[0-9a-fA-F]{12}$/i, $c = /^[a-z0-9_-]{21}$/i, Rc = /^[-+]?P(?!$)(?:(?:[-+]?\d+Y)|(?:[-+]?\d+[.,]\d+Y$))?(?:(?:[-+]?\d+M)|(?:[-+]?\d+[.,]\d+M$))?(?:(?:[-+]?\d+W)|(?:[-+]?\d+[.,]\d+W$))?(?:(?:[-+]?\d+D)|(?:[-+]?\d+[.,]\d+D$))?(?:T(?=[\d+-])(?:(?:[-+]?\d+H)|(?:[-+]?\d+[.,]\d+H$))?(?:(?:[-+]?\d+M)|(?:[-+]?\d+[.,]\d+M$))?(?:[-+]?\d+(?:[.,]\d+)?S)?)??$/, Nc = /^(?!\.)(?!.*\.\.)([A-Z0-9_'+\-\.]*)[A-Z0-9_+-]@([A-Z0-9][A-Z0-9\-]*\.)+[A-Z]{2,}$/i, Oc = "^(\\p{Extended_Pictographic}|\\p{Emoji_Component})+$";
let nn;
const Mc = /^(?:(?:25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9][0-9]|[0-9])\.){3}(?:25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9][0-9]|[0-9])$/, Lc = /^(([a-f0-9]{1,4}:){7}|::([a-f0-9]{1,4}:){0,6}|([a-f0-9]{1,4}:){1}:([a-f0-9]{1,4}:){0,5}|([a-f0-9]{1,4}:){2}:([a-f0-9]{1,4}:){0,4}|([a-f0-9]{1,4}:){3}:([a-f0-9]{1,4}:){0,3}|([a-f0-9]{1,4}:){4}:([a-f0-9]{1,4}:){0,2}|([a-f0-9]{1,4}:){5}:([a-f0-9]{1,4}:){0,1})([a-f0-9]{1,4}|(((25[0-5])|(2[0-4][0-9])|(1[0-9]{2})|([0-9]{1,2}))\.){3}((25[0-5])|(2[0-4][0-9])|(1[0-9]{2})|([0-9]{1,2})))$/, Fc = /^([0-9a-zA-Z+/]{4})*(([0-9a-zA-Z+/]{2}==)|([0-9a-zA-Z+/]{3}=))?$/, Rs = "((\\d\\d[2468][048]|\\d\\d[13579][26]|\\d\\d0[48]|[02468][048]00|[13579][26]00)-02-29|\\d{4}-((0[13578]|1[02])-(0[1-9]|[12]\\d|3[01])|(0[469]|11)-(0[1-9]|[12]\\d|30)|(02)-(0[1-9]|1\\d|2[0-8])))", Dc = new RegExp(`^${Rs}$`);
function Ns(t) {
  let e = "([01]\\d|2[0-3]):[0-5]\\d:[0-5]\\d";
  return t.precision ? e = `${e}\\.\\d{${t.precision}}` : t.precision == null && (e = `${e}(\\.\\d+)?`), e;
}
function Uc(t) {
  return new RegExp(`^${Ns(t)}$`);
}
function Os(t) {
  let e = `${Rs}T${Ns(t)}`;
  const r = [];
  return r.push(t.local ? "Z?" : "Z"), t.offset && r.push("([+-]\\d{2}:?\\d{2})"), e = `${e}(${r.join("|")})`, new RegExp(`^${e}$`);
}
function jc(t, e) {
  return !!((e === "v4" || !e) && Mc.test(t) || (e === "v6" || !e) && Lc.test(t));
}
class Ae extends O {
  _parse(e) {
    if (this._def.coerce && (e.data = String(e.data)), this._getType(e) !== w.string) {
      const a = this._getOrReturnCtx(e);
      return v(a, {
        code: g.invalid_type,
        expected: w.string,
        received: a.parsedType
      }), $;
    }
    const n = new te();
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
        Nc.test(e.data) || (o = this._getOrReturnCtx(e, o), v(o, {
          validation: "email",
          code: g.invalid_string,
          message: a.message
        }), n.dirty());
      else if (a.kind === "emoji")
        nn || (nn = new RegExp(Oc, "u")), nn.test(e.data) || (o = this._getOrReturnCtx(e, o), v(o, {
          validation: "emoji",
          code: g.invalid_string,
          message: a.message
        }), n.dirty());
      else if (a.kind === "uuid")
        Cc.test(e.data) || (o = this._getOrReturnCtx(e, o), v(o, {
          validation: "uuid",
          code: g.invalid_string,
          message: a.message
        }), n.dirty());
      else if (a.kind === "nanoid")
        $c.test(e.data) || (o = this._getOrReturnCtx(e, o), v(o, {
          validation: "nanoid",
          code: g.invalid_string,
          message: a.message
        }), n.dirty());
      else if (a.kind === "cuid")
        Tc.test(e.data) || (o = this._getOrReturnCtx(e, o), v(o, {
          validation: "cuid",
          code: g.invalid_string,
          message: a.message
        }), n.dirty());
      else if (a.kind === "cuid2")
        Ic.test(e.data) || (o = this._getOrReturnCtx(e, o), v(o, {
          validation: "cuid2",
          code: g.invalid_string,
          message: a.message
        }), n.dirty());
      else if (a.kind === "ulid")
        Ac.test(e.data) || (o = this._getOrReturnCtx(e, o), v(o, {
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
      }), n.dirty()) : a.kind === "datetime" ? Os(a).test(e.data) || (o = this._getOrReturnCtx(e, o), v(o, {
        code: g.invalid_string,
        validation: "datetime",
        message: a.message
      }), n.dirty()) : a.kind === "date" ? Dc.test(e.data) || (o = this._getOrReturnCtx(e, o), v(o, {
        code: g.invalid_string,
        validation: "date",
        message: a.message
      }), n.dirty()) : a.kind === "time" ? Uc(a).test(e.data) || (o = this._getOrReturnCtx(e, o), v(o, {
        code: g.invalid_string,
        validation: "time",
        message: a.message
      }), n.dirty()) : a.kind === "duration" ? Rc.test(e.data) || (o = this._getOrReturnCtx(e, o), v(o, {
        validation: "duration",
        code: g.invalid_string,
        message: a.message
      }), n.dirty()) : a.kind === "ip" ? jc(e.data, a.version) || (o = this._getOrReturnCtx(e, o), v(o, {
        validation: "ip",
        code: g.invalid_string,
        message: a.message
      }), n.dirty()) : a.kind === "base64" ? Fc.test(e.data) || (o = this._getOrReturnCtx(e, o), v(o, {
        validation: "base64",
        code: g.invalid_string,
        message: a.message
      }), n.dirty()) : F.assertNever(a);
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
    return new Ae({
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
    return new Ae({
      ...this._def,
      checks: [...this._def.checks, { kind: "trim" }]
    });
  }
  toLowerCase() {
    return new Ae({
      ...this._def,
      checks: [...this._def.checks, { kind: "toLowerCase" }]
    });
  }
  toUpperCase() {
    return new Ae({
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
Ae.create = (t) => {
  var e;
  return new Ae({
    checks: [],
    typeName: C.ZodString,
    coerce: (e = t == null ? void 0 : t.coerce) !== null && e !== void 0 ? e : !1,
    ...R(t)
  });
};
function Zc(t, e) {
  const r = (t.toString().split(".")[1] || "").length, n = (e.toString().split(".")[1] || "").length, o = r > n ? r : n, a = parseInt(t.toFixed(o).replace(".", "")), i = parseInt(e.toFixed(o).replace(".", ""));
  return a % i / Math.pow(10, o);
}
class et extends O {
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
      }), $;
    }
    let n;
    const o = new te();
    for (const a of this._def.checks)
      a.kind === "int" ? F.isInteger(e.data) || (n = this._getOrReturnCtx(e, n), v(n, {
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
      }), o.dirty()) : a.kind === "multipleOf" ? Zc(e.data, a.value) !== 0 && (n = this._getOrReturnCtx(e, n), v(n, {
        code: g.not_multiple_of,
        multipleOf: a.value,
        message: a.message
      }), o.dirty()) : a.kind === "finite" ? Number.isFinite(e.data) || (n = this._getOrReturnCtx(e, n), v(n, {
        code: g.not_finite,
        message: a.message
      }), o.dirty()) : F.assertNever(a);
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
et.create = (t) => new et({
  checks: [],
  typeName: C.ZodNumber,
  coerce: (t == null ? void 0 : t.coerce) || !1,
  ...R(t)
});
class tt extends O {
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
      }), $;
    }
    let n;
    const o = new te();
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
      }), o.dirty()) : F.assertNever(a);
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
    ...R(t)
  });
};
class Vt extends O {
  _parse(e) {
    if (this._def.coerce && (e.data = !!e.data), this._getType(e) !== w.boolean) {
      const n = this._getOrReturnCtx(e);
      return v(n, {
        code: g.invalid_type,
        expected: w.boolean,
        received: n.parsedType
      }), $;
    }
    return le(e.data);
  }
}
Vt.create = (t) => new Vt({
  typeName: C.ZodBoolean,
  coerce: (t == null ? void 0 : t.coerce) || !1,
  ...R(t)
});
class pt extends O {
  _parse(e) {
    if (this._def.coerce && (e.data = new Date(e.data)), this._getType(e) !== w.date) {
      const a = this._getOrReturnCtx(e);
      return v(a, {
        code: g.invalid_type,
        expected: w.date,
        received: a.parsedType
      }), $;
    }
    if (isNaN(e.data.getTime())) {
      const a = this._getOrReturnCtx(e);
      return v(a, {
        code: g.invalid_date
      }), $;
    }
    const n = new te();
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
      }), n.dirty()) : F.assertNever(a);
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
  ...R(t)
});
class Ar extends O {
  _parse(e) {
    if (this._getType(e) !== w.symbol) {
      const n = this._getOrReturnCtx(e);
      return v(n, {
        code: g.invalid_type,
        expected: w.symbol,
        received: n.parsedType
      }), $;
    }
    return le(e.data);
  }
}
Ar.create = (t) => new Ar({
  typeName: C.ZodSymbol,
  ...R(t)
});
class Ht extends O {
  _parse(e) {
    if (this._getType(e) !== w.undefined) {
      const n = this._getOrReturnCtx(e);
      return v(n, {
        code: g.invalid_type,
        expected: w.undefined,
        received: n.parsedType
      }), $;
    }
    return le(e.data);
  }
}
Ht.create = (t) => new Ht({
  typeName: C.ZodUndefined,
  ...R(t)
});
class Wt extends O {
  _parse(e) {
    if (this._getType(e) !== w.null) {
      const n = this._getOrReturnCtx(e);
      return v(n, {
        code: g.invalid_type,
        expected: w.null,
        received: n.parsedType
      }), $;
    }
    return le(e.data);
  }
}
Wt.create = (t) => new Wt({
  typeName: C.ZodNull,
  ...R(t)
});
class $t extends O {
  constructor() {
    super(...arguments), this._any = !0;
  }
  _parse(e) {
    return le(e.data);
  }
}
$t.create = (t) => new $t({
  typeName: C.ZodAny,
  ...R(t)
});
class ft extends O {
  constructor() {
    super(...arguments), this._unknown = !0;
  }
  _parse(e) {
    return le(e.data);
  }
}
ft.create = (t) => new ft({
  typeName: C.ZodUnknown,
  ...R(t)
});
class Ve extends O {
  _parse(e) {
    const r = this._getOrReturnCtx(e);
    return v(r, {
      code: g.invalid_type,
      expected: w.never,
      received: r.parsedType
    }), $;
  }
}
Ve.create = (t) => new Ve({
  typeName: C.ZodNever,
  ...R(t)
});
class Cr extends O {
  _parse(e) {
    if (this._getType(e) !== w.undefined) {
      const n = this._getOrReturnCtx(e);
      return v(n, {
        code: g.invalid_type,
        expected: w.void,
        received: n.parsedType
      }), $;
    }
    return le(e.data);
  }
}
Cr.create = (t) => new Cr({
  typeName: C.ZodVoid,
  ...R(t)
});
class $e extends O {
  _parse(e) {
    const { ctx: r, status: n } = this._processInputParams(e), o = this._def;
    if (r.parsedType !== w.array)
      return v(r, {
        code: g.invalid_type,
        expected: w.array,
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
      return Promise.all([...r.data].map((i, c) => o.type._parseAsync(new Fe(r, i, r.path, c)))).then((i) => te.mergeArray(n, i));
    const a = [...r.data].map((i, c) => o.type._parseSync(new Fe(r, i, r.path, c)));
    return te.mergeArray(n, a);
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
  ...R(e)
});
function vt(t) {
  if (t instanceof B) {
    const e = {};
    for (const r in t.shape) {
      const n = t.shape[r];
      e[r] = Le.create(vt(n));
    }
    return new B({
      ...t._def,
      shape: () => e
    });
  } else return t instanceof $e ? new $e({
    ...t._def,
    type: vt(t.element)
  }) : t instanceof Le ? Le.create(vt(t.unwrap())) : t instanceof nt ? nt.create(vt(t.unwrap())) : t instanceof De ? De.create(t.items.map((e) => vt(e))) : t;
}
class B extends O {
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
    if (this._getType(e) !== w.object) {
      const u = this._getOrReturnCtx(e);
      return v(u, {
        code: g.invalid_type,
        expected: w.object,
        received: u.parsedType
      }), $;
    }
    const { status: n, ctx: o } = this._processInputParams(e), { shape: a, keys: i } = this._getCached(), c = [];
    if (!(this._def.catchall instanceof Ve && this._def.unknownKeys === "strip"))
      for (const u in o.data)
        i.includes(u) || c.push(u);
    const l = [];
    for (const u of i) {
      const d = a[u], f = o.data[u];
      l.push({
        key: { status: "valid", value: u },
        value: d._parse(new Fe(o, f, o.path, u)),
        alwaysSet: u in o.data
      });
    }
    if (this._def.catchall instanceof Ve) {
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
            new Fe(o, f, o.path, d)
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
    }).then((u) => te.mergeObjectSync(n, u)) : te.mergeObjectSync(n, l);
  }
  get shape() {
    return this._def.shape();
  }
  strict(e) {
    return k.errToObj, new B({
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
    return new B({
      ...this._def,
      unknownKeys: "strip"
    });
  }
  passthrough() {
    return new B({
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
    return new B({
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
    return new B({
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
    return new B({
      ...this._def,
      catchall: e
    });
  }
  pick(e) {
    const r = {};
    return F.objectKeys(e).forEach((n) => {
      e[n] && this.shape[n] && (r[n] = this.shape[n]);
    }), new B({
      ...this._def,
      shape: () => r
    });
  }
  omit(e) {
    const r = {};
    return F.objectKeys(this.shape).forEach((n) => {
      e[n] || (r[n] = this.shape[n]);
    }), new B({
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
    return F.objectKeys(this.shape).forEach((n) => {
      const o = this.shape[n];
      e && !e[n] ? r[n] = o : r[n] = o.optional();
    }), new B({
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
        let a = this.shape[n];
        for (; a instanceof Le; )
          a = a._def.innerType;
        r[n] = a;
      }
    }), new B({
      ...this._def,
      shape: () => r
    });
  }
  keyof() {
    return Ms(F.objectKeys(this.shape));
  }
}
B.create = (t, e) => new B({
  shape: () => t,
  unknownKeys: "strip",
  catchall: Ve.create(),
  typeName: C.ZodObject,
  ...R(e)
});
B.strictCreate = (t, e) => new B({
  shape: () => t,
  unknownKeys: "strict",
  catchall: Ve.create(),
  typeName: C.ZodObject,
  ...R(e)
});
B.lazycreate = (t, e) => new B({
  shape: t,
  unknownKeys: "strip",
  catchall: Ve.create(),
  typeName: C.ZodObject,
  ...R(e)
});
class qt extends O {
  _parse(e) {
    const { ctx: r } = this._processInputParams(e), n = this._def.options;
    function o(a) {
      for (const c of a)
        if (c.result.status === "valid")
          return c.result;
      for (const c of a)
        if (c.result.status === "dirty")
          return r.common.issues.push(...c.ctx.common.issues), c.result;
      const i = a.map((c) => new ge(c.ctx.common.issues));
      return v(r, {
        code: g.invalid_union,
        unionErrors: i
      }), $;
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
      const c = i.map((l) => new ge(l));
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
qt.create = (t, e) => new qt({
  options: t,
  typeName: C.ZodUnion,
  ...R(e)
});
const ze = (t) => t instanceof Jt ? ze(t.schema) : t instanceof Re ? ze(t.innerType()) : t instanceof Xt ? [t.value] : t instanceof rt ? t.options : t instanceof Qt ? F.objectValues(t.enum) : t instanceof er ? ze(t._def.innerType) : t instanceof Ht ? [void 0] : t instanceof Wt ? [null] : t instanceof Le ? [void 0, ...ze(t.unwrap())] : t instanceof nt ? [null, ...ze(t.unwrap())] : t instanceof Fn || t instanceof rr ? ze(t.unwrap()) : t instanceof tr ? ze(t._def.innerType) : [];
class Vr extends O {
  _parse(e) {
    const { ctx: r } = this._processInputParams(e);
    if (r.parsedType !== w.object)
      return v(r, {
        code: g.invalid_type,
        expected: w.object,
        received: r.parsedType
      }), $;
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
      typeName: C.ZodDiscriminatedUnion,
      discriminator: e,
      options: r,
      optionsMap: o,
      ...R(n)
    });
  }
}
function mn(t, e) {
  const r = Ye(t), n = Ye(e);
  if (t === e)
    return { valid: !0, data: t };
  if (r === w.object && n === w.object) {
    const o = F.objectKeys(e), a = F.objectKeys(t).filter((c) => o.indexOf(c) !== -1), i = { ...t, ...e };
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
  } else return r === w.date && n === w.date && +t == +e ? { valid: !0, data: t } : { valid: !1 };
}
class Kt extends O {
  _parse(e) {
    const { status: r, ctx: n } = this._processInputParams(e), o = (a, i) => {
      if (pn(a) || pn(i))
        return $;
      const c = mn(a.value, i.value);
      return c.valid ? ((hn(a) || hn(i)) && r.dirty(), { status: r.value, value: c.data }) : (v(n, {
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
  typeName: C.ZodIntersection,
  ...R(r)
});
class De extends O {
  _parse(e) {
    const { status: r, ctx: n } = this._processInputParams(e);
    if (n.parsedType !== w.array)
      return v(n, {
        code: g.invalid_type,
        expected: w.array,
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
    const a = [...n.data].map((i, c) => {
      const l = this._def.items[c] || this._def.rest;
      return l ? l._parse(new Fe(n, i, n.path, c)) : null;
    }).filter((i) => !!i);
    return n.common.async ? Promise.all(a).then((i) => te.mergeArray(r, i)) : te.mergeArray(r, a);
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
    typeName: C.ZodTuple,
    rest: null,
    ...R(e)
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
      return v(n, {
        code: g.invalid_type,
        expected: w.object,
        received: n.parsedType
      }), $;
    const o = [], a = this._def.keyType, i = this._def.valueType;
    for (const c in n.data)
      o.push({
        key: a._parse(new Fe(n, c, n.path, c)),
        value: i._parse(new Fe(n, n.data[c], n.path, c)),
        alwaysSet: c in n.data
      });
    return n.common.async ? te.mergeObjectAsync(r, o) : te.mergeObjectSync(r, o);
  }
  get element() {
    return this._def.valueType;
  }
  static create(e, r, n) {
    return r instanceof O ? new Yt({
      keyType: e,
      valueType: r,
      typeName: C.ZodRecord,
      ...R(n)
    }) : new Yt({
      keyType: Ae.create(),
      valueType: e,
      typeName: C.ZodRecord,
      ...R(r)
    });
  }
}
class $r extends O {
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
      }), $;
    const o = this._def.keyType, a = this._def.valueType, i = [...n.data.entries()].map(([c, l], u) => ({
      key: o._parse(new Fe(n, c, n.path, [u, "key"])),
      value: a._parse(new Fe(n, l, n.path, [u, "value"]))
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
$r.create = (t, e, r) => new $r({
  valueType: e,
  keyType: t,
  typeName: C.ZodMap,
  ...R(r)
});
class ht extends O {
  _parse(e) {
    const { status: r, ctx: n } = this._processInputParams(e);
    if (n.parsedType !== w.set)
      return v(n, {
        code: g.invalid_type,
        expected: w.set,
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
    const a = this._def.valueType;
    function i(l) {
      const u = /* @__PURE__ */ new Set();
      for (const d of l) {
        if (d.status === "aborted")
          return $;
        d.status === "dirty" && r.dirty(), u.add(d.value);
      }
      return { status: r.value, value: u };
    }
    const c = [...n.data.values()].map((l, u) => a._parse(new Fe(n, l, n.path, u)));
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
  ...R(e)
});
class kt extends O {
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
      }), $;
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
    const a = { errorMap: r.common.contextualErrorMap }, i = r.data;
    if (this._def.returns instanceof Rt) {
      const c = this;
      return le(async function(...l) {
        const u = new ge([]), d = await c._def.args.parseAsync(l, a).catch((p) => {
          throw u.addIssue(n(l, p)), u;
        }), f = await Reflect.apply(i, this, d);
        return await c._def.returns._def.type.parseAsync(f, a).catch((p) => {
          throw u.addIssue(o(f, p)), u;
        });
      });
    } else {
      const c = this;
      return le(function(...l) {
        const u = c._def.args.safeParse(l, a);
        if (!u.success)
          throw new ge([n(l, u.error)]);
        const d = Reflect.apply(i, this, u.data), f = c._def.returns.safeParse(d, a);
        if (!f.success)
          throw new ge([o(d, f.error)]);
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
      args: De.create(e).rest(ft.create())
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
      args: e || De.create([]).rest(ft.create()),
      returns: r || ft.create(),
      typeName: C.ZodFunction,
      ...R(n)
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
  ...R(e)
});
class Xt extends O {
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
Xt.create = (t, e) => new Xt({
  value: t,
  typeName: C.ZodLiteral,
  ...R(e)
});
function Ms(t, e) {
  return new rt({
    values: t,
    typeName: C.ZodEnum,
    ...R(e)
  });
}
class rt extends O {
  constructor() {
    super(...arguments), jt.set(this, void 0);
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
    if (Ir(this, jt) || $s(this, jt, new Set(this._def.values)), !Ir(this, jt).has(e.data)) {
      const r = this._getOrReturnCtx(e), n = this._def.values;
      return v(r, {
        received: r.data,
        code: g.invalid_enum_value,
        options: n
      }), $;
    }
    return le(e.data);
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
    const r = F.getValidEnumValues(this._def.values), n = this._getOrReturnCtx(e);
    if (n.parsedType !== w.string && n.parsedType !== w.number) {
      const o = F.objectValues(r);
      return v(n, {
        expected: F.joinValues(o),
        received: n.parsedType,
        code: g.invalid_type
      }), $;
    }
    if (Ir(this, Zt) || $s(this, Zt, new Set(F.getValidEnumValues(this._def.values))), !Ir(this, Zt).has(e.data)) {
      const o = F.objectValues(r);
      return v(n, {
        received: n.data,
        code: g.invalid_enum_value,
        options: o
      }), $;
    }
    return le(e.data);
  }
  get enum() {
    return this._def.values;
  }
}
Zt = /* @__PURE__ */ new WeakMap();
Qt.create = (t, e) => new Qt({
  values: t,
  typeName: C.ZodNativeEnum,
  ...R(e)
});
class Rt extends O {
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
      }), $;
    const n = r.parsedType === w.promise ? r.data : Promise.resolve(r.data);
    return le(n.then((o) => this._def.type.parseAsync(o, {
      path: r.path,
      errorMap: r.common.contextualErrorMap
    })));
  }
}
Rt.create = (t, e) => new Rt({
  type: t,
  typeName: C.ZodPromise,
  ...R(e)
});
class Re extends O {
  innerType() {
    return this._def.schema;
  }
  sourceType() {
    return this._def.schema._def.typeName === C.ZodEffects ? this._def.schema.sourceType() : this._def.schema;
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
            return $;
          const l = await this._def.schema._parseAsync({
            data: c,
            path: n.path,
            parent: n
          });
          return l.status === "aborted" ? $ : l.status === "dirty" || r.value === "dirty" ? bt(l.value) : l;
        });
      {
        if (r.value === "aborted")
          return $;
        const c = this._def.schema._parseSync({
          data: i,
          path: n.path,
          parent: n
        });
        return c.status === "aborted" ? $ : c.status === "dirty" || r.value === "dirty" ? bt(c.value) : c;
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
        if (!Bt(i))
          return i;
        const c = o.transform(i.value, a);
        if (c instanceof Promise)
          throw new Error("Asynchronous transform encountered during synchronous parse operation. Use .parseAsync instead.");
        return { status: r.value, value: c };
      } else
        return this._def.schema._parseAsync({ data: n.data, path: n.path, parent: n }).then((i) => Bt(i) ? Promise.resolve(o.transform(i.value, a)).then((c) => ({ status: r.value, value: c })) : i);
    F.assertNever(o);
  }
}
Re.create = (t, e, r) => new Re({
  schema: t,
  typeName: C.ZodEffects,
  effect: e,
  ...R(r)
});
Re.createWithPreprocess = (t, e, r) => new Re({
  schema: e,
  effect: { type: "preprocess", transform: t },
  typeName: C.ZodEffects,
  ...R(r)
});
class Le extends O {
  _parse(e) {
    return this._getType(e) === w.undefined ? le(void 0) : this._def.innerType._parse(e);
  }
  unwrap() {
    return this._def.innerType;
  }
}
Le.create = (t, e) => new Le({
  innerType: t,
  typeName: C.ZodOptional,
  ...R(e)
});
class nt extends O {
  _parse(e) {
    return this._getType(e) === w.null ? le(null) : this._def.innerType._parse(e);
  }
  unwrap() {
    return this._def.innerType;
  }
}
nt.create = (t, e) => new nt({
  innerType: t,
  typeName: C.ZodNullable,
  ...R(e)
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
  ...R(e)
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
    return Gt(o) ? o.then((a) => ({
      status: "valid",
      value: a.status === "valid" ? a.value : this._def.catchValue({
        get error() {
          return new ge(n.common.issues);
        },
        input: n.data
      })
    })) : {
      status: "valid",
      value: o.status === "valid" ? o.value : this._def.catchValue({
        get error() {
          return new ge(n.common.issues);
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
  ...R(e)
});
class Rr extends O {
  _parse(e) {
    if (this._getType(e) !== w.nan) {
      const n = this._getOrReturnCtx(e);
      return v(n, {
        code: g.invalid_type,
        expected: w.nan,
        received: n.parsedType
      }), $;
    }
    return { status: "valid", value: e.data };
  }
}
Rr.create = (t) => new Rr({
  typeName: C.ZodNaN,
  ...R(t)
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
        const a = await this._def.in._parseAsync({
          data: n.data,
          path: n.path,
          parent: n
        });
        return a.status === "aborted" ? $ : a.status === "dirty" ? (r.dirty(), bt(a.value)) : this._def.out._parseAsync({
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
  ...R(e)
});
function Ls(t, e = {}, r) {
  return t ? $t.create().superRefine((n, o) => {
    var a, i;
    if (!t(n)) {
      const c = typeof e == "function" ? e(n) : typeof e == "string" ? { message: e } : e, l = (i = (a = c.fatal) !== null && a !== void 0 ? a : r) !== null && i !== void 0 ? i : !0, u = typeof c == "string" ? { message: c } : c;
      o.addIssue({ code: "custom", ...u, fatal: l });
    }
  }) : $t.create();
}
const Bc = {
  object: B.lazycreate
};
var C;
(function(t) {
  t.ZodString = "ZodString", t.ZodNumber = "ZodNumber", t.ZodNaN = "ZodNaN", t.ZodBigInt = "ZodBigInt", t.ZodBoolean = "ZodBoolean", t.ZodDate = "ZodDate", t.ZodSymbol = "ZodSymbol", t.ZodUndefined = "ZodUndefined", t.ZodNull = "ZodNull", t.ZodAny = "ZodAny", t.ZodUnknown = "ZodUnknown", t.ZodNever = "ZodNever", t.ZodVoid = "ZodVoid", t.ZodArray = "ZodArray", t.ZodObject = "ZodObject", t.ZodUnion = "ZodUnion", t.ZodDiscriminatedUnion = "ZodDiscriminatedUnion", t.ZodIntersection = "ZodIntersection", t.ZodTuple = "ZodTuple", t.ZodRecord = "ZodRecord", t.ZodMap = "ZodMap", t.ZodSet = "ZodSet", t.ZodFunction = "ZodFunction", t.ZodLazy = "ZodLazy", t.ZodLiteral = "ZodLiteral", t.ZodEnum = "ZodEnum", t.ZodEffects = "ZodEffects", t.ZodNativeEnum = "ZodNativeEnum", t.ZodOptional = "ZodOptional", t.ZodNullable = "ZodNullable", t.ZodDefault = "ZodDefault", t.ZodCatch = "ZodCatch", t.ZodPromise = "ZodPromise", t.ZodBranded = "ZodBranded", t.ZodPipeline = "ZodPipeline", t.ZodReadonly = "ZodReadonly";
})(C || (C = {}));
const Gc = (t, e = {
  message: `Input not instance of ${t.name}`
}) => Ls((r) => r instanceof t, e), Fs = Ae.create, Ds = et.create, Vc = Rr.create, Hc = tt.create, Us = Vt.create, Wc = pt.create, qc = Ar.create, Kc = Ht.create, Yc = Wt.create, Jc = $t.create, Xc = ft.create, Qc = Ve.create, el = Cr.create, tl = $e.create, rl = B.create, nl = B.strictCreate, ol = qt.create, sl = Vr.create, al = Kt.create, il = De.create, cl = Yt.create, ll = $r.create, ul = ht.create, dl = kt.create, fl = Jt.create, pl = Xt.create, hl = rt.create, ml = Qt.create, gl = Rt.create, go = Re.create, yl = Le.create, _l = nt.create, vl = Re.createWithPreprocess, bl = cr.create, wl = () => Fs().optional(), xl = () => Ds().optional(), Sl = () => Us().optional(), El = {
  string: (t) => Ae.create({ ...t, coerce: !0 }),
  number: (t) => et.create({ ...t, coerce: !0 }),
  boolean: (t) => Vt.create({
    ...t,
    coerce: !0
  }),
  bigint: (t) => tt.create({ ...t, coerce: !0 }),
  date: (t) => pt.create({ ...t, coerce: !0 })
}, kl = $;
var q = /* @__PURE__ */ Object.freeze({
  __proto__: null,
  defaultErrorMap: Ct,
  setErrorMap: kc,
  getErrorMap: Pr,
  makeIssue: Tr,
  EMPTY_PATH: Pc,
  addIssueToContext: v,
  ParseStatus: te,
  INVALID: $,
  DIRTY: bt,
  OK: le,
  isAborted: pn,
  isDirty: hn,
  isValid: Bt,
  isAsync: Gt,
  get util() {
    return F;
  },
  get objectUtil() {
    return fn;
  },
  ZodParsedType: w,
  getParsedType: Ye,
  ZodType: O,
  datetimeRegex: Os,
  ZodString: Ae,
  ZodNumber: et,
  ZodBigInt: tt,
  ZodBoolean: Vt,
  ZodDate: pt,
  ZodSymbol: Ar,
  ZodUndefined: Ht,
  ZodNull: Wt,
  ZodAny: $t,
  ZodUnknown: ft,
  ZodNever: Ve,
  ZodVoid: Cr,
  ZodArray: $e,
  ZodObject: B,
  ZodUnion: qt,
  ZodDiscriminatedUnion: Vr,
  ZodIntersection: Kt,
  ZodTuple: De,
  ZodRecord: Yt,
  ZodMap: $r,
  ZodSet: ht,
  ZodFunction: kt,
  ZodLazy: Jt,
  ZodLiteral: Xt,
  ZodEnum: rt,
  ZodNativeEnum: Qt,
  ZodPromise: Rt,
  ZodEffects: Re,
  ZodTransformer: Re,
  ZodOptional: Le,
  ZodNullable: nt,
  ZodDefault: er,
  ZodCatch: tr,
  ZodNaN: Rr,
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
  ZodError: ge
});
const Pl = q.object({
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
const Il = q.object({
  width: q.number().positive(),
  height: q.number().positive()
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
const Cl = q.function().args(
  q.string(),
  q.string(),
  q.enum(["dark", "light"]),
  Il.optional()
).implement((t, e, r, n) => Al(t, e, r, n));
async function $l(t, e, r, n) {
  let o = await yo(e), a = !1, i = !1, c = null, l = [];
  const u = /* @__PURE__ */ new Set(), d = t.addListener("themechange", (A) => {
    c == null || c.setTheme(A);
  }), f = t.addListener("finish", () => {
    m(), t == null || t.removeListener(f);
  });
  let h = {};
  const p = () => {
    t.removeListener(d), Object.entries(h).forEach(([, A]) => {
      A.forEach((N) => {
        E(N);
      });
    }), l = [], h = {};
  }, m = () => {
    p(), u.forEach(clearTimeout), u.clear(), c && (c.removeEventListener("close", m), c.remove(), c = null), i = !0, r();
  }, b = async () => {
    if (!a) {
      a = !0;
      return;
    }
    p(), o = await yo(e), n(o);
  }, S = (A, N, D) => {
    const M = t.getTheme(), J = js(e.host, N);
    (c == null ? void 0 : c.getAttribute("iframe-src")) !== J && (c = Cl(A, J, M, D), c.setTheme(M), c.addEventListener("close", m, {
      once: !0
    }), c.addEventListener("load", b));
  }, x = (A) => {
    l.push(A);
  }, I = (A, N, D) => {
    const M = t.addListener(
      A,
      (...J) => {
        i || N(...J);
      },
      D
    );
    return h[A] || (h[A] = /* @__PURE__ */ new Map()), h[A].set(N, M), M;
  }, E = (A, N) => {
    let D;
    typeof A == "symbol" ? D = A : N && (D = h[A].get(N)), D && t.removeListener(D);
  };
  return {
    close: m,
    destroyListener: E,
    openModal: S,
    getModal: () => c,
    registerListener: I,
    registerMessageCallback: x,
    sendMessage: (A) => {
      l.forEach((N) => N(A));
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
const Rl = [
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
        open: (n, o, a) => {
          t.openModal(n, o, a);
        },
        sendMessage(n) {
          var a;
          const o = new CustomEvent("message", {
            detail: n
          });
          (a = t.getModal()) == null || a.dispatchEvent(o);
        },
        onMessage: (n) => {
          q.function().parse(n), t.registerMessageCallback(n);
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
      on(n, o, a) {
        return q.enum(Rl).parse(n), q.function().parse(o), e("content:read"), t.registerListener(n, o, a);
      },
      off(n, o) {
        t.destroyListener(n, o);
      },
      // Penpot State API
      get root() {
        return e("content:read"), t.context.root;
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
      getFile() {
        return e("content:read"), t.context.getFile();
      },
      getPage() {
        return e("content:read"), t.context.getPage();
      },
      getSelected() {
        return e("content:read"), t.context.getSelected();
      },
      getSelectedShapes() {
        return e("content:read"), t.context.getSelectedShapes();
      },
      shapesColors(n) {
        return e("content:read"), t.context.shapesColors(n);
      },
      replaceColor(n, o, a) {
        return e("content:write"), t.context.replaceColor(n, o, a);
      },
      getTheme() {
        return t.context.getTheme();
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
      uploadMediaData(n, o, a) {
        return e("content:write"), t.context.uploadMediaData(n, o, a);
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
      }
    }
  };
}
let _o = !1;
const Ke = {
  hardenIntrinsics: () => {
    _o || (_o = !0, hardenIntrinsics());
  },
  createCompartment: (t) => new Compartment(t),
  harden: (t) => harden(t)
};
function Ol(t) {
  Ke.hardenIntrinsics();
  const e = Nl(t), r = {
    penpot: Ke.harden(e.penpot),
    fetch: Ke.harden((...o) => {
      const a = {
        ...o[1],
        credentials: "omit"
      };
      return fetch(o[0], a);
    }),
    console: Ke.harden(window.console),
    Math: Ke.harden(Math),
    setTimeout: Ke.harden(
      (...[o, a]) => {
        const i = setTimeout(() => {
          o();
        }, a);
        return t.timeouts.add(i), i;
      }
    ),
    clearTimeout: Ke.harden((o) => {
      clearTimeout(o), t.timeouts.delete(o);
    })
  }, n = Ke.createCompartment(r);
  return {
    evaluate: () => {
      n.evaluate(t.code);
    },
    cleanGlobalThis: () => {
      Object.keys(r).forEach((o) => {
        delete n.globalThis[o];
      });
    },
    compartment: n
  };
}
async function Ml(t, e, r) {
  const n = await $l(
    t,
    e,
    function() {
      o.cleanGlobalThis(), r();
    },
    function() {
      o.evaluate();
    }
  ), o = Ol(n);
  return o.evaluate(), {
    plugin: n,
    compartment: o
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
    const r = await Ml(e, t, () => {
      Pt = Pt.filter((n) => n !== r);
    });
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
