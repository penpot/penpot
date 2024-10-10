var zn = (t) => {
  throw TypeError(t);
};
var Bn = (t, e, r) => e.has(t) || zn("Cannot " + r);
var Y = (t, e, r) => (Bn(t, e, "read from private field"), r ? r.call(t) : e.get(t)), dr = (t, e, r) => e.has(t) ? zn("Cannot add the same private member more than once") : e instanceof WeakSet ? e.add(t) : e.set(t, r), fr = (t, e, r, n) => (Bn(t, e, "write to private field"), n ? n.call(t, r) : e.set(t, r), r);
const T = globalThis, {
  Array: Gs,
  Date: Vs,
  FinalizationRegistry: At,
  Float32Array: Hs,
  JSON: Ws,
  Map: Re,
  Math: qs,
  Number: xo,
  Object: vn,
  Promise: Ks,
  Proxy: Lr,
  Reflect: Ys,
  RegExp: Xe,
  Set: Ot,
  String: be,
  Symbol: St,
  WeakMap: je,
  WeakSet: Mt
} = globalThis, {
  // The feral Error constructor is safe for internal use, but must not be
  // revealed to post-lockdown code in any compartment including the start
  // compartment since in V8 at least it bears stack inspection capabilities.
  Error: ce,
  RangeError: Js,
  ReferenceError: Bt,
  SyntaxError: sr,
  TypeError: v,
  AggregateError: Yr
} = globalThis, {
  assign: Fr,
  create: H,
  defineProperties: B,
  entries: ge,
  freeze: y,
  getOwnPropertyDescriptor: ne,
  getOwnPropertyDescriptors: Ze,
  getOwnPropertyNames: It,
  getPrototypeOf: V,
  is: Dr,
  isFrozen: zl,
  isSealed: Bl,
  isExtensible: Gl,
  keys: So,
  prototype: _n,
  seal: Vl,
  preventExtensions: Xs,
  setPrototypeOf: Eo,
  values: ko,
  fromEntries: yt
} = vn, {
  species: Jr,
  toStringTag: Qe,
  iterator: ar,
  matchAll: Po,
  unscopables: Qs,
  keyFor: ea,
  for: ta
} = St, { isInteger: ra } = xo, { stringify: To } = Ws, { defineProperty: na } = vn, U = (t, e, r) => {
  const n = na(t, e, r);
  if (n !== t)
    throw v(
      `Please report that the original defineProperty silently failed to set ${To(
        be(e)
      )}. (SES_DEFINE_PROPERTY_FAILED_SILENTLY)`
    );
  return n;
}, {
  apply: ue,
  construct: br,
  get: oa,
  getOwnPropertyDescriptor: sa,
  has: Ao,
  isExtensible: aa,
  ownKeys: Ve,
  preventExtensions: ia,
  set: Io
} = Ys, { isArray: Et, prototype: Pe } = Gs, { prototype: Lt } = Re, { prototype: Ur } = RegExp, { prototype: ir } = Ot, { prototype: ze } = be, { prototype: jr } = je, { prototype: Co } = Mt, { prototype: bn } = Function, { prototype: Ro } = Ks, { prototype: $o } = V(
  // eslint-disable-next-line no-empty-function, func-names
  function* () {
  }
), ca = V(Uint8Array.prototype), { bind: sn } = bn, A = sn.bind(sn.call), de = A(_n.hasOwnProperty), et = A(Pe.filter), ft = A(Pe.forEach), Zr = A(Pe.includes), Ft = A(Pe.join), fe = (
  /** @type {any} */
  A(Pe.map)
), No = (
  /** @type {any} */
  A(Pe.flatMap)
), wr = A(Pe.pop), oe = A(Pe.push), la = A(Pe.slice), ua = A(Pe.some), Oo = A(Pe.sort), da = A(Pe[ar]), he = A(Lt.set), He = A(Lt.get), zr = A(Lt.has), fa = A(Lt.delete), pa = A(Lt.entries), ha = A(Lt[ar]), wn = A(ir.add);
A(ir.delete);
const Gn = A(ir.forEach), xn = A(ir.has), ma = A(ir[ar]), Sn = A(Ur.test), En = A(Ur.exec), ga = A(Ur[Po]), Mo = A(ze.endsWith), Lo = A(ze.includes), ya = A(ze.indexOf);
A(ze.match);
const xr = A($o.next), Fo = A($o.throw), Sr = (
  /** @type {any} */
  A(ze.replace)
), va = A(ze.search), kn = A(ze.slice), Pn = A(ze.split), Do = A(ze.startsWith), _a = A(ze[ar]), ba = A(jr.delete), z = A(jr.get), kt = A(jr.has), me = A(jr.set), Br = A(Co.add), cr = A(Co.has), wa = A(bn.toString), xa = A(sn);
A(Ro.catch);
const Uo = (
  /** @type {any} */
  A(Ro.then)
), Sa = At && A(At.prototype.register);
At && A(At.prototype.unregister);
const Tn = y(H(null)), ke = (t) => vn(t) === t, Gr = (t) => t instanceof ce, jo = eval, Ee = Function, Ea = () => {
  throw v('Cannot eval with evalTaming set to "noEval" (SES_NO_EVAL)');
}, Ye = ne(Error("er1"), "stack"), Xr = ne(v("er2"), "stack");
let Zo, zo;
if (Ye && Xr && Ye.get)
  if (
    // In the v8 case as we understand it, all errors have an own stack
    // accessor property, but within the same realm, all these accessor
    // properties have the same getter and have the same setter.
    // This is therefore the case that we repair.
    typeof Ye.get == "function" && Ye.get === Xr.get && typeof Ye.set == "function" && Ye.set === Xr.set
  )
    Zo = y(Ye.get), zo = y(Ye.set);
  else
    throw v(
      "Unexpected Error own stack accessor functions (SES_UNEXPECTED_ERROR_OWN_STACK_ACCESSOR)"
    );
const Qr = Zo, ka = zo;
function Pa() {
  return this;
}
if (Pa())
  throw v("SES failed to initialize, sloppy mode (SES_NO_SLOPPY)");
const { freeze: lt } = Object, { apply: Ta } = Reflect, An = (t) => (e, ...r) => Ta(t, e, r), Aa = An(Array.prototype.push), Vn = An(Array.prototype.includes), Ia = An(String.prototype.split), at = JSON.stringify, pr = (t, ...e) => {
  let r = t[0];
  for (let n = 0; n < e.length; n += 1)
    r = `${r}${e[n]}${t[n + 1]}`;
  throw Error(r);
}, Bo = (t, e = !1) => {
  const r = [], n = (c, l, u = void 0) => {
    typeof c == "string" || pr`Environment option name ${at(c)} must be a string.`, typeof l == "string" || pr`Environment option default setting ${at(
      l
    )} must be a string.`;
    let d = l;
    const f = t.process || void 0, h = typeof f == "object" && f.env || void 0;
    if (typeof h == "object" && c in h) {
      e || Aa(r, c);
      const p = h[c];
      typeof p == "string" || pr`Environment option named ${at(
        c
      )}, if present, must have a corresponding string value, got ${at(
        p
      )}`, d = p;
    }
    return u === void 0 || d === l || Vn(u, d) || pr`Unrecognized ${at(c)} value ${at(
      d
    )}. Expected one of ${at([l, ...u])}`, d;
  };
  lt(n);
  const o = (c) => {
    const l = n(c, "");
    return lt(l === "" ? [] : Ia(l, ","));
  };
  lt(o);
  const s = (c, l) => Vn(o(c), l), i = () => lt([...r]);
  return lt(i), lt({
    getEnvironmentOption: n,
    getEnvironmentOptionsList: o,
    environmentOptionsListHas: s,
    getCapturedEnvironmentOptionNames: i
  });
};
lt(Bo);
const {
  getEnvironmentOption: ve,
  getEnvironmentOptionsList: Hl,
  environmentOptionsListHas: Wl
} = Bo(globalThis, !0), Er = (t) => (t = `${t}`, t.length >= 1 && Lo("aeiouAEIOU", t[0]) ? `an ${t}` : `a ${t}`);
y(Er);
const Go = (t, e = void 0) => {
  const r = new Ot(), n = (o, s) => {
    switch (typeof s) {
      case "object": {
        if (s === null)
          return null;
        if (xn(r, s))
          return "[Seen]";
        if (wn(r, s), Gr(s))
          return `[${s.name}: ${s.message}]`;
        if (Qe in s)
          return `[${s[Qe]}]`;
        if (Et(s))
          return s;
        const i = So(s);
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
        Oo(i);
        const l = fe(i, (u) => [u, s[u]]);
        return yt(l);
      }
      case "function":
        return `[Function ${s.name || "<anon>"}]`;
      case "string":
        return Do(s, "[") ? `[${s}]` : s;
      case "undefined":
      case "symbol":
        return `[${be(s)}]`;
      case "bigint":
        return `[${s}n]`;
      case "number":
        return Dr(s, NaN) ? "[NaN]" : s === 1 / 0 ? "[Infinity]" : s === -1 / 0 ? "[-Infinity]" : s;
      default:
        return s;
    }
  };
  try {
    return To(t, n, e);
  } catch {
    return "[Something that failed to stringify]";
  }
};
y(Go);
const { isSafeInteger: Ca } = Number, { freeze: bt } = Object, { toStringTag: Ra } = Symbol, Hn = (t) => {
  const r = {
    next: void 0,
    prev: void 0,
    data: t
  };
  return r.next = r, r.prev = r, r;
}, Wn = (t, e) => {
  if (t === e)
    throw TypeError("Cannot splice a cell into itself");
  if (e.next !== e || e.prev !== e)
    throw TypeError("Expected self-linked cell");
  const r = e, n = t.next;
  return r.prev = t, r.next = n, t.next = r, n.prev = r, r;
}, en = (t) => {
  const { prev: e, next: r } = t;
  e.next = r, r.prev = e, t.prev = t, t.next = t;
}, Vo = (t) => {
  if (!Ca(t) || t < 0)
    throw TypeError("keysBudget must be a safe non-negative integer number");
  const e = /* @__PURE__ */ new WeakMap();
  let r = 0;
  const n = Hn(void 0), o = (d) => {
    const f = e.get(d);
    if (!(f === void 0 || f.data === void 0))
      return en(f), Wn(n, f), f;
  }, s = (d) => o(d) !== void 0;
  bt(s);
  const i = (d) => {
    const f = o(d);
    return f && f.data && f.data.get(d);
  };
  bt(i);
  const c = (d, f) => {
    if (t < 1)
      return u;
    let h = o(d);
    if (h === void 0 && (h = Hn(void 0), Wn(n, h)), !h.data)
      for (r += 1, h.data = /* @__PURE__ */ new WeakMap(), e.set(d, h); r > t; ) {
        const p = n.prev;
        en(p), p.data = void 0, r -= 1;
      }
    return h.data.set(d, f), u;
  };
  bt(c);
  const l = (d) => {
    const f = e.get(d);
    return f === void 0 || (en(f), e.delete(d), f.data === void 0) ? !1 : (f.data = void 0, r -= 1, !0);
  };
  bt(l);
  const u = bt({
    has: s,
    get: i,
    set: c,
    delete: l,
    // eslint-disable-next-line jsdoc/check-types
    [
      /** @type {typeof Symbol.toStringTag} */
      Ra
    ]: "LRUCacheMap"
  });
  return u;
};
bt(Vo);
const { freeze: vr } = Object, { isSafeInteger: $a } = Number, Na = 1e3, Oa = 100, Ho = (t = Na, e = Oa) => {
  if (!$a(e) || e < 1)
    throw TypeError(
      "argsPerErrorBudget must be a safe positive integer number"
    );
  const r = Vo(t), n = (s, i) => {
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
vr(Ho);
const Ct = new je(), Z = (t, e = void 0) => {
  const r = y({
    toString: y(() => Go(t, e))
  });
  return me(Ct, r, t), r;
};
y(Z);
const Ma = y(/^[\w:-]( ?[\w:-])*$/), kr = (t, e = void 0) => {
  if (typeof t != "string" || !Sn(Ma, t))
    return Z(t, e);
  const r = y({
    toString: y(() => t)
  });
  return me(Ct, r, t), r;
};
y(kr);
const Vr = new je(), Wo = ({ template: t, args: e }) => {
  const r = [t[0]];
  for (let n = 0; n < e.length; n += 1) {
    const o = e[n];
    let s;
    kt(Ct, o) ? s = `${o}` : Gr(o) ? s = `(${Er(o.name)})` : s = `(${Er(typeof o)})`, oe(r, s, t[n + 1]);
  }
  return Ft(r, "");
}, qo = y({
  toString() {
    const t = z(Vr, this);
    return t === void 0 ? "[Not a DetailsToken]" : Wo(t);
  }
});
y(qo.toString);
const le = (t, ...e) => {
  const r = y({ __proto__: qo });
  return me(Vr, r, { template: t, args: e }), /** @type {DetailsToken} */
  /** @type {unknown} */
  r;
};
y(le);
const Ko = (t, ...e) => (e = fe(
  e,
  (r) => kt(Ct, r) ? r : Z(r)
), le(t, ...e));
y(Ko);
const Yo = ({ template: t, args: e }) => {
  const r = [t[0]];
  for (let n = 0; n < e.length; n += 1) {
    let o = e[n];
    kt(Ct, o) && (o = z(Ct, o));
    const s = Sr(wr(r) || "", / $/, "");
    s !== "" && oe(r, s);
    const i = Sr(t[n + 1], /^ /, "");
    oe(r, o, i);
  }
  return r[r.length - 1] === "" && wr(r), r;
}, _r = new je();
let an = 0;
const qn = new je(), Jo = (t, e = t.name) => {
  let r = z(qn, t);
  return r !== void 0 || (an += 1, r = `${e}#${an}`, me(qn, t, r)), r;
}, La = (t) => {
  const e = Ze(t), {
    name: r,
    message: n,
    errors: o = void 0,
    cause: s = void 0,
    stack: i = void 0,
    ...c
  } = e, l = Ve(c);
  if (l.length >= 1) {
    for (const d of l)
      delete t[d];
    const u = H(_n, c);
    Hr(
      t,
      le`originally with properties ${Z(u)}`
    );
  }
  for (const u of Ve(t)) {
    const d = e[u];
    d && de(d, "get") && U(t, u, {
      value: t[u]
      // invoke the getter to convert to data property
    });
  }
  y(t);
}, Le = (t = le`Assert failed`, e = T.Error, {
  errorName: r = void 0,
  cause: n = void 0,
  errors: o = void 0,
  sanitize: s = !0
} = {}) => {
  typeof t == "string" && (t = le([t]));
  const i = z(Vr, t);
  if (i === void 0)
    throw v(`unrecognized details ${Z(t)}`);
  const c = Wo(i), l = n && { cause: n };
  let u;
  return typeof Yr < "u" && e === Yr ? u = Yr(o || [], c, l) : (u = /** @type {ErrorConstructor} */
  e(
    c,
    l
  ), o !== void 0 && U(u, "errors", {
    value: o,
    writable: !0,
    enumerable: !1,
    configurable: !0
  })), me(_r, u, Yo(i)), r !== void 0 && Jo(u, r), s && La(u), u;
};
y(Le);
const { addLogArgs: Fa, takeLogArgsArray: Da } = Ho(), cn = new je(), Hr = (t, e) => {
  typeof e == "string" && (e = le([e]));
  const r = z(Vr, e);
  if (r === void 0)
    throw v(`unrecognized details ${Z(e)}`);
  const n = Yo(r), o = z(cn, t);
  if (o !== void 0)
    for (const s of o)
      s(t, n);
  else
    Fa(t, n);
};
y(Hr);
const Ua = (t) => {
  if (!("stack" in t))
    return "";
  const e = `${t.stack}`, r = ya(e, `
`);
  return Do(e, " ") || r === -1 ? e : kn(e, r + 1);
}, Pr = {
  getStackString: T.getStackString || Ua,
  tagError: (t) => Jo(t),
  resetErrorTagNum: () => {
    an = 0;
  },
  getMessageLogArgs: (t) => z(_r, t),
  takeMessageLogArgs: (t) => {
    const e = z(_r, t);
    return ba(_r, t), e;
  },
  takeNoteLogArgsArray: (t, e) => {
    const r = Da(t);
    if (e !== void 0) {
      const n = z(cn, t);
      n ? oe(n, e) : me(cn, t, [e]);
    }
    return r || [];
  }
};
y(Pr);
const Wr = (t = void 0, e = !1) => {
  const r = e ? Ko : le, n = r`Check failed`, o = (f = n, h = void 0, p = void 0) => {
    const m = Le(f, h, p);
    throw t !== void 0 && t(m), m;
  };
  y(o);
  const s = (f, ...h) => o(r(f, ...h));
  function i(f, h = void 0, p = void 0, m = void 0) {
    f || o(h, p, m);
  }
  const c = (f, h, p = void 0, m = void 0, _ = void 0) => {
    Dr(f, h) || o(
      p || r`Expected ${f} is same as ${h}`,
      m || Js,
      _
    );
  };
  y(c);
  const l = (f, h, p) => {
    if (typeof f !== h) {
      if (typeof h == "string" || s`${Z(h)} must be a string`, p === void 0) {
        const m = Er(h);
        p = r`${f} must be ${kr(m)}`;
      }
      o(p, v);
    }
  };
  y(l);
  const d = Fr(i, {
    error: Le,
    fail: o,
    equal: c,
    typeof: l,
    string: (f, h = void 0) => l(f, "string", h),
    note: Hr,
    details: r,
    Fail: s,
    quote: Z,
    bare: kr,
    makeAssert: Wr
  });
  return y(d);
};
y(Wr);
const ee = Wr(), Kn = ee.equal, Xo = ne(
  ca,
  Qe
);
ee(Xo);
const Qo = Xo.get;
ee(Qo);
const ja = (t) => ue(Qo, t, []) !== void 0, Za = (t) => {
  const e = +be(t);
  return ra(e) && be(e) === t;
}, za = (t) => {
  Xs(t), ft(Ve(t), (e) => {
    const r = ne(t, e);
    ee(r), Za(e) || U(t, e, {
      ...r,
      writable: !1,
      configurable: !1
    });
  });
}, Ba = () => {
  if (typeof T.harden == "function")
    return T.harden;
  const t = new Mt(), { harden: e } = {
    /**
     * @template T
     * @param {T} root
     * @returns {T}
     */
    harden(r) {
      const n = new Ot();
      function o(d) {
        if (!ke(d))
          return;
        const f = typeof d;
        if (f !== "object" && f !== "function")
          throw v(`Unexpected typeof: ${f}`);
        cr(t, d) || xn(n, d) || wn(n, d);
      }
      const s = (d) => {
        ja(d) ? za(d) : y(d);
        const f = Ze(d), h = V(d);
        o(h), ft(Ve(f), (p) => {
          const m = f[
            /** @type {string} */
            p
          ];
          de(m, "value") ? o(m.value) : (o(m.get), o(m.set));
        });
      }, i = Qr === void 0 && ka === void 0 ? (
        // On platforms without v8's error own stack accessor problem,
        // don't pay for any extra overhead.
        s
      ) : (d) => {
        if (Gr(d)) {
          const f = ne(d, "stack");
          f && f.get === Qr && f.configurable && U(d, "stack", {
            // NOTE: Calls getter during harden, which seems dangerous.
            // But we're only calling the problematic getter whose
            // hazards we think we understand.
            // @ts-expect-error TS should know FERAL_STACK_GETTER
            // cannot be `undefined` here.
            // See https://github.com/endojs/endo/pull/2232#discussion_r1575179471
            value: ue(Qr, d, [])
          });
        }
        return s(d);
      }, c = () => {
        Gn(n, i);
      }, l = (d) => {
        Br(t, d);
      }, u = () => {
        Gn(n, l);
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
}, Yn = {
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
typeof AggregateError < "u" && oe(ns, AggregateError);
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
}, a = ln, Jn = Ga, M = {
  get: a,
  set: "undefined"
}, Oe = {
  get: a,
  set: a
}, Xn = (t) => t === M || t === Oe;
function it(t) {
  return {
    // Properties of the NativeError Constructors
    "[[Proto]]": "%SharedError%",
    // NativeError.prototype
    prototype: t
  };
}
function ct(t) {
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
function xe(t) {
  return {
    // Properties of the TypedArray Constructors
    "[[Proto]]": "%TypedArray%",
    BYTES_PER_ELEMENT: "number",
    prototype: t
  };
}
function Se(t) {
  return {
    // Properties of the TypedArray Prototype Objects
    "[[Proto]]": "%TypedArrayPrototype%",
    BYTES_PER_ELEMENT: "number",
    constructor: t
  };
}
const Qn = {
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
}, Tr = {
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
  EvalError: it("%EvalErrorPrototype%"),
  RangeError: it("%RangeErrorPrototype%"),
  ReferenceError: it("%ReferenceErrorPrototype%"),
  SyntaxError: it("%SyntaxErrorPrototype%"),
  TypeError: it("%TypeErrorPrototype%"),
  URIError: it("%URIErrorPrototype%"),
  // https://github.com/endojs/endo/issues/550
  AggregateError: it("%AggregateErrorPrototype%"),
  "%EvalErrorPrototype%": ct("EvalError"),
  "%RangeErrorPrototype%": ct("RangeError"),
  "%ReferenceErrorPrototype%": ct("ReferenceError"),
  "%SyntaxErrorPrototype%": ct("SyntaxError"),
  "%TypeErrorPrototype%": ct("TypeError"),
  "%URIErrorPrototype%": ct("URIError"),
  // https://github.com/endojs/endo/issues/550
  "%AggregateErrorPrototype%": ct("AggregateError"),
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
    ...Qn,
    // `%InitialMath%.random()` has the standard unsafe behavior
    random: a
  },
  "%SharedMath%": {
    ...Qn,
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
  BigInt64Array: xe("%BigInt64ArrayPrototype%"),
  BigUint64Array: xe("%BigUint64ArrayPrototype%"),
  // https://github.com/tc39/proposal-float16array
  Float16Array: xe("%Float16ArrayPrototype%"),
  Float32Array: xe("%Float32ArrayPrototype%"),
  Float64Array: xe("%Float64ArrayPrototype%"),
  Int16Array: xe("%Int16ArrayPrototype%"),
  Int32Array: xe("%Int32ArrayPrototype%"),
  Int8Array: xe("%Int8ArrayPrototype%"),
  Uint16Array: xe("%Uint16ArrayPrototype%"),
  Uint32Array: xe("%Uint32ArrayPrototype%"),
  Uint8ClampedArray: xe("%Uint8ClampedArrayPrototype%"),
  Uint8Array: {
    ...xe("%Uint8ArrayPrototype%"),
    // https://github.com/tc39/proposal-arraybuffer-base64
    fromBase64: a,
    // https://github.com/tc39/proposal-arraybuffer-base64
    fromHex: a
  },
  "%BigInt64ArrayPrototype%": Se("BigInt64Array"),
  "%BigUint64ArrayPrototype%": Se("BigUint64Array"),
  // https://github.com/tc39/proposal-float16array
  "%Float16ArrayPrototype%": Se("Float16Array"),
  "%Float32ArrayPrototype%": Se("Float32Array"),
  "%Float64ArrayPrototype%": Se("Float64Array"),
  "%Int16ArrayPrototype%": Se("Int16Array"),
  "%Int32ArrayPrototype%": Se("Int32Array"),
  "%Int8ArrayPrototype%": Se("Int8Array"),
  "%Uint16ArrayPrototype%": Se("Uint16Array"),
  "%Uint32ArrayPrototype%": Se("Uint32Array"),
  "%Uint8ClampedArrayPrototype%": Se("Uint8ClampedArray"),
  "%Uint8ArrayPrototype%": {
    ...Se("Uint8Array"),
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
    import: Jn,
    load: Jn,
    importNow: a,
    module: a,
    "@@toStringTag": "string"
  },
  lockdown: a,
  harden: { ...a, isFake: "boolean" },
  "%InitialGetStackString%": a
}, Va = (t) => typeof t == "function";
function Ha(t, e, r) {
  if (de(t, e)) {
    const n = ne(t, e);
    if (!n || !Dr(n.value, r.value) || n.get !== r.get || n.set !== r.set || n.writable !== r.writable || n.enumerable !== r.enumerable || n.configurable !== r.configurable)
      throw v(`Conflicting definitions of ${e}`);
  }
  U(t, e, r);
}
function Wa(t, e) {
  for (const [r, n] of ge(e))
    Ha(t, r, n);
}
function os(t, e) {
  const r = { __proto__: null };
  for (const [n, o] of ge(e))
    de(t, n) && (r[o] = t[n]);
  return r;
}
const ss = () => {
  const t = H(null);
  let e;
  const r = (c) => {
    Wa(t, Ze(c));
  };
  y(r);
  const n = () => {
    for (const [c, l] of ge(t)) {
      if (!ke(l) || !de(l, "prototype"))
        continue;
      const u = Tr[c];
      if (typeof u != "object")
        throw v(`Expected permit object at whitelist.${c}`);
      const d = u.prototype;
      if (!d)
        throw v(`${c}.prototype property not whitelisted`);
      if (typeof d != "string" || !de(Tr, d))
        throw v(`Unrecognized ${c}.prototype whitelist entry`);
      const f = l.prototype;
      if (de(t, d)) {
        if (t[d] !== f)
          throw v(`Conflicting bindings of ${d}`);
        continue;
      }
      t[d] = f;
    }
  };
  y(n);
  const o = () => (y(t), e = new Mt(et(ko(t), Va)), t);
  y(o);
  const s = (c) => {
    if (!e)
      throw v(
        "isPseudoNative can only be called after finalIntrinsics"
      );
    return cr(e, c);
  };
  y(s);
  const i = {
    addIntrinsics: r,
    completePrototypes: n,
    finalIntrinsics: o,
    isPseudoNative: s
  };
  return y(i), r(es), r(os(T, ts)), i;
}, qa = (t) => {
  const { addIntrinsics: e, finalIntrinsics: r } = ss();
  return e(os(t, rs)), r();
};
function Ka(t, e) {
  let r = !1;
  const n = (h, ...p) => (r || (console.groupCollapsed("Removing unpermitted intrinsics"), r = !0), console[h](...p)), o = ["undefined", "boolean", "number", "string", "symbol"], s = new Re(
    St ? fe(
      et(
        ge(Tr["%SharedSymbol%"]),
        ([h, p]) => p === "symbol" && typeof St[h] == "symbol"
      ),
      ([h]) => [St[h], `@@${h}`]
    ) : []
  );
  function i(h, p) {
    if (typeof p == "string")
      return p;
    const m = He(s, p);
    if (typeof p == "symbol") {
      if (m)
        return m;
      {
        const _ = ea(p);
        return _ !== void 0 ? `RegisteredSymbol(${_})` : `Unique${be(p)}`;
      }
    }
    throw v(`Unexpected property name type ${h} ${p}`);
  }
  function c(h, p, m) {
    if (!ke(p))
      throw v(`Object expected: ${h}, ${p}, ${m}`);
    const _ = V(p);
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
        if (de(t, _)) {
          if (p !== t[_])
            throw v(`Does not match whitelist ${h}`);
          return !0;
        }
      } else if (Zr(o, _)) {
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
    const S = ne(p, m);
    if (!S)
      throw v(`Property ${m} not found at ${h}`);
    if (de(S, "value")) {
      if (Xn(_))
        throw v(`Accessor expected at ${h}`);
      return l(h, S.value, m, _);
    }
    if (!Xn(_))
      throw v(`Accessor not expected at ${h}`);
    return l(`${h}<get>`, S.get, m, _.get) && l(`${h}<set>`, S.set, m, _.set);
  }
  function d(h, p, m) {
    const _ = m === "__proto__" ? "--proto--" : m;
    if (de(p, _))
      return p[_];
    if (typeof h == "function" && de(ln, _))
      return ln[_];
  }
  function f(h, p, m) {
    if (p == null)
      return;
    const _ = m["[[Proto]]"];
    c(h, p, _), typeof p == "function" && e(p);
    for (const S of Ve(p)) {
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
    f("intrinsics", t, Tr);
  } finally {
    r && console.groupEnd();
  }
}
function Ya() {
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
      if (l instanceof sr)
        return;
      throw l;
    }
    const i = V(s), c = function() {
      throw v(
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
    }), c !== Ee.prototype.constructor && Eo(c, Ee.prototype.constructor), t[n] = c;
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
  const e = Vs, r = e.prototype, n = {
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
      return new.target === void 0 ? ue(e, void 0, d) : br(e, d, new.target);
    } : l = function(...d) {
      if (new.target === void 0)
        throw v(
          "secure mode Calling %SharedDate% constructor as a function throws"
        );
      if (d.length === 0)
        throw v(
          "secure mode Calling new %SharedDate%() with no arguments throws"
        );
      return br(e, d, new.target);
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
function Xa(t = "safe") {
  if (t !== "safe" && t !== "unsafe")
    throw v(`unrecognized mathTaming ${t}`);
  const e = qs, r = e, { random: n, ...o } = Ze(e), i = H(_n, {
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
  const e = Xe.prototype, r = (s = {}) => {
    const i = function(...l) {
      return new.target === void 0 ? Xe(...l) : br(Xe, l, new.target);
    };
    if (B(i, {
      length: { value: 2 },
      prototype: {
        value: e,
        writable: !1,
        enumerable: !1,
        configurable: !1
      }
    }), Jr) {
      const c = ne(
        Xe,
        Jr
      );
      if (!c)
        throw v("no RegExp[Symbol.species] descriptor");
      B(i, {
        [Jr]: c
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
    [Qe]: !0
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
    [ar]: !0
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
  const n = new Ot(r);
  function o(u, d, f, h) {
    if ("value" in h && h.configurable) {
      const { value: p } = h, m = xn(n, f), { get: _, set: S } = ne(
        {
          get [f]() {
            return p;
          },
          set [f](x) {
            if (d === this)
              throw v(
                `Cannot assign to read only property '${be(
                  f
                )}' of '${u}'`
              );
            de(this, f) ? this[f] = x : (m && console.error(v(`Override property ${f}`)), U(this, f, {
              value: x,
              writable: !0,
              enumerable: !0,
              configurable: !0
            }));
          }
        },
        f
      );
      U(_, "originalValue", {
        value: p,
        writable: !1,
        enumerable: !1,
        configurable: !1
      }), U(d, f, {
        get: _,
        set: S,
        enumerable: h.enumerable,
        configurable: h.configurable
      });
    }
  }
  function s(u, d, f) {
    const h = ne(d, f);
    h && o(u, d, f, h);
  }
  function i(u, d) {
    const f = Ze(d);
    f && ft(Ve(f), (h) => o(u, d, h, f[h]));
  }
  function c(u, d, f) {
    for (const h of Ve(f)) {
      const p = ne(d, h);
      if (!p || p.get || p.set)
        continue;
      const m = `${u}.${be(h)}`, _ = f[h];
      if (_ === !0)
        s(m, d, h);
      else if (_ === "*")
        i(m, p.value);
      else if (ke(_))
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
const { Fail: un, quote: Ar } = ee, ni = /^(\w*[a-z])Locale([A-Z]\w*)$/, is = {
  // See https://tc39.es/ecma262/#sec-string.prototype.localecompare
  localeCompare(t) {
    if (this === null || this === void 0)
      throw v(
        'Cannot localeCompare with null or undefined "this" value'
      );
    const e = `${this}`, r = `${t}`;
    return e < r ? -1 : e > r ? 1 : (e === r || un`expected ${Ar(e)} and ${Ar(r)} to compare`, 0);
  },
  toString() {
    return `${this}`;
  }
}, oi = is.localeCompare, si = is.toString;
function ai(t, e = "safe") {
  if (e !== "safe" && e !== "unsafe")
    throw v(`unrecognized localeTaming ${e}`);
  if (e !== "unsafe") {
    U(be.prototype, "localeCompare", {
      value: oi
    });
    for (const r of It(t)) {
      const n = t[r];
      if (ke(n))
        for (const o of It(n)) {
          const s = En(ni, o);
          if (s) {
            typeof n[o] == "function" || un`expected ${Ar(o)} to be a function`;
            const i = `${s[1]}${s[2]}`, c = n[i];
            typeof c == "function" || un`function ${Ar(i)} not found`, U(n, o, { value: c });
          }
        }
    }
    U(xo.prototype, "toLocaleString", {
      value: si
    });
  }
}
const ii = (t) => ({
  eval(r) {
    return typeof r != "string" ? r : t(r);
  }
}).eval, { Fail: eo } = ee, ci = (t) => {
  const e = function(n) {
    const o = `${wr(arguments) || ""}`, s = `${Ft(arguments, ",")}`;
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
  }), V(Ee) === Ee.prototype || eo`Function prototype is the same accross compartments`, V(e) === Ee.prototype || eo`Function constructor prototype is the same accross compartments`, e;
}, li = (t) => {
  U(
    t,
    Qs,
    y(
      Fr(H(null), {
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
  for (const [e, r] of ge(es))
    U(t, e, {
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
  parentCompartment: s
}) => {
  for (const [c, l] of ge(ts))
    de(e, l) && U(t, c, {
      value: e[l],
      writable: !0,
      enumerable: !1,
      configurable: !0
    });
  for (const [c, l] of ge(r))
    de(e, l) && U(t, c, {
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
  for (const [c, l] of ge(i))
    U(t, c, {
      value: l,
      writable: !0,
      enumerable: !1,
      configurable: !0
    }), typeof l == "function" && o(l);
}, dn = (t, e, r) => {
  {
    const n = y(ii(e));
    r(n), U(t, "eval", {
      value: n,
      writable: !0,
      enumerable: !1,
      configurable: !0
    });
  }
  {
    const n = y(ci(e));
    r(n), U(t, "Function", {
      value: n,
      writable: !0,
      enumerable: !1,
      configurable: !0
    });
  }
}, { Fail: ui, quote: us } = ee, ds = new Lr(
  Tn,
  y({
    get(t, e) {
      ui`Please report unexpected scope handler trap: ${us(be(e))}`;
    }
  })
), di = {
  get(t, e) {
  },
  set(t, e, r) {
    throw Bt(`${be(e)} is not defined`);
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
    const r = us(be(e));
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
  H(
    ds,
    Ze(di)
  )
), fi = new Lr(
  Tn,
  fs
), ps = (t) => {
  const e = {
    // inherit scopeTerminator behavior
    ...fs,
    // Redirect set properties to the globalObject.
    set(o, s, i) {
      return Io(t, s, i);
    },
    // Always claim to have a potential property in order to be the recipient of a set
    has(o, s) {
      return !0;
    }
  }, r = y(
    H(
      ds,
      Ze(e)
    )
  );
  return new Lr(
    Tn,
    r
  );
};
y(ps);
const { Fail: pi } = ee, hi = () => {
  const t = H(null), e = y({
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
      n !== null && pi`a handler did not reset allowNextEvalToBeUnsafe ${n.err}`, B(t, e);
    },
    /** @type {null | { err: any }} */
    revoked: null
  };
  return r;
}, to = "\\s*[@#]\\s*([a-zA-Z][a-zA-Z0-9]*)\\s*=\\s*([^\\s\\*]*)", mi = new Xe(
  `(?:\\s*//${to}|/\\*${to}\\s*\\*/)\\s*$`
), In = (t) => {
  let e = "<unknown>";
  for (; t.length > 0; ) {
    const r = En(mi, t);
    if (r === null)
      break;
    t = kn(t, 0, t.length - r[0].length), r[3] === "sourceURL" ? e = r[4] : r[1] === "sourceURL" && (e = r[2]);
  }
  return e;
};
function Cn(t, e) {
  const r = va(t, e);
  if (r < 0)
    return -1;
  const n = t[r] === `
` ? 1 : 0;
  return Pn(kn(t, 0, r), `
`).length + n;
}
const hs = new Xe("(?:<!--|-->)", "g"), ms = (t) => {
  const e = Cn(t, hs);
  if (e < 0)
    return t;
  const r = In(t);
  throw sr(
    `Possible HTML comment rejected at ${r}:${e}. (SES_HTML_COMMENT_REJECTED)`
  );
}, gs = (t) => Sr(t, hs, (r) => r[0] === "<" ? "< ! --" : "-- >"), ys = new Xe(
  "(^|[^.]|\\.\\.\\.)\\bimport(\\s*(?:\\(|/[/*]))",
  "g"
), vs = (t) => {
  const e = Cn(t, ys);
  if (e < 0)
    return t;
  const r = In(t);
  throw sr(
    `Possible import expression rejected at ${r}:${e}. (SES_IMPORT_REJECTED)`
  );
}, _s = (t) => Sr(t, ys, (r, n, o) => `${n}__import__${o}`), gi = new Xe(
  "(^|[^.])\\beval(\\s*\\()",
  "g"
), bs = (t) => {
  const e = Cn(t, gi);
  if (e < 0)
    return t;
  const r = In(t);
  throw sr(
    `Possible direct eval expression rejected at ${r}:${e}. (SES_EVAL_REJECTED)`
  );
}, ws = (t) => (t = ms(t), t = vs(t), t), xs = (t, e) => {
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
  applyTransforms: y(xs)
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
], vi = /^[a-zA-Z_$][\w$]*$/, ro = (t) => t !== "eval" && !Zr(yi, t) && Sn(vi, t);
function no(t, e) {
  const r = ne(t, e);
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
  de(r, "value");
}
const _i = (t, e = {}) => {
  const r = It(t), n = It(e), o = et(
    n,
    (i) => ro(i) && no(e, i)
  );
  return {
    globalObjectConstants: et(
      r,
      (i) => (
        // Can't define a constant: it would prevent a
        // lookup on the endowments.
        !Zr(n, i) && ro(i) && no(t, i)
      )
    ),
    moduleLexicalConstants: o
  };
};
function oo(t, e) {
  return t.length === 0 ? "" : `const {${Ft(t, ",")}} = this.${e};`;
}
const bi = (t) => {
  const { globalObjectConstants: e, moduleLexicalConstants: r } = _i(
    t.globalObject,
    t.moduleLexicals
  ), n = oo(
    e,
    "globalObject"
  ), o = oo(
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
}, { Fail: wi } = ee, Rn = ({
  globalObject: t,
  moduleLexicals: e = {},
  globalTransforms: r = [],
  sloppyGlobalsMode: n = !1
}) => {
  const o = n ? ps(t) : fi, s = hi(), { evalScope: i } = s, c = y({
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
    u(), f = xs(f, [
      ...p,
      ...r,
      ws
    ]);
    let m;
    try {
      return s.allowNextEvalToBeUnsafe(), ue(l, t, [f]);
    } catch (_) {
      throw m = _, _;
    } finally {
      const _ = "eval" in i;
      delete i.eval, _ && (s.revoked = { err: m }, wi`handler did not reset allowNextEvalToBeUnsafe ${m}`);
    }
  } };
}, xi = ") { [native code] }";
let tn;
const Ss = () => {
  if (tn === void 0) {
    const t = new Mt();
    U(bn, "toString", {
      value: {
        toString() {
          const r = wa(this);
          return Mo(r, xi) || !cr(t, this) ? r : `function ${this.name}() { [native code] }`;
        }
      }.toString
    }), tn = y(
      (r) => Br(t, r)
    );
  }
  return tn;
};
function Si(t = "safe") {
  if (t !== "safe" && t !== "unsafe")
    throw v(`unrecognized domainTaming ${t}`);
  if (t === "unsafe")
    return;
  const e = T.process || void 0;
  if (typeof e == "object") {
    const r = ne(e, "domain");
    if (r !== void 0 && r.get !== void 0)
      throw v(
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
]), Nn = y([
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
]), Es = y([
  ...$n,
  ...Nn
]), Ei = (t, { shouldResetForDebugging: e = !1 } = {}) => {
  e && t.resetErrorTagNum();
  let r = [];
  const n = yt(
    fe(Es, ([i, c]) => {
      const l = (...u) => {
        oe(r, [i, ...u]);
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
y(Ei);
const ut = {
  NOTE: "ERROR_NOTE:",
  MESSAGE: "ERROR_MESSAGE:",
  CAUSE: "cause:",
  ERRORS: "errors:"
};
y(ut);
const On = (t, e) => {
  if (!t)
    return;
  const { getStackString: r, tagError: n, takeMessageLogArgs: o, takeNoteLogArgsArray: s } = e, i = (S, x) => fe(S, (E) => Gr(E) ? (oe(x, E), `(${n(E)})`) : E), c = (S, x, I, E, L) => {
    const $ = n(x), j = I === ut.MESSAGE ? `${$}:` : `${$} ${I}`, F = i(E, L);
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
  }, u = new Mt(), d = (S) => (x, I) => {
    const E = [];
    c(S, x, ut.NOTE, I, E), l(S, E, n(x));
  }, f = (S, x) => {
    if (cr(u, x))
      return;
    const I = n(x);
    Br(u, x);
    const E = [], L = o(x), $ = s(
      x,
      d(S)
    );
    L === void 0 ? t[S](`${I}:`, x.message) : c(
      S,
      x,
      ut.MESSAGE,
      L,
      E
    );
    let j = r(x);
    typeof j == "string" && j.length >= 1 && !Mo(j, `
`) && (j += `
`), t[S](j), x.cause && c(S, x, ut.CAUSE, [x.cause], E), x.errors && c(S, x, ut.ERRORS, x.errors, E);
    for (const F of $)
      c(S, x, ut.NOTE, F, E);
    l(S, E, I);
  }, h = fe($n, ([S, x]) => {
    const I = (...E) => {
      const L = [], $ = i(E, L);
      t[S](...$), l(S, L);
    };
    return U(I, "name", { value: S }), [S, y(I)];
  }), p = et(
    Nn,
    ([S, x]) => S in t
  ), m = fe(p, ([S, x]) => {
    const I = (...E) => {
      t[S](...E);
    };
    return U(I, "name", { value: S }), [S, y(I)];
  }), _ = yt([...h, ...m]);
  return (
    /** @type {VirtualConsole} */
    y(_)
  );
};
y(On);
const ki = (t, e, r) => {
  const [n, ...o] = Pn(t, e), s = No(o, (i) => [e, ...r, i]);
  return ["", n, ...s];
}, ks = (t) => y((r) => {
  const n = [], o = (...l) => (n.length > 0 && (l = No(
    l,
    (u) => typeof u == "string" && Lo(u, `
`) ? ki(u, `
`, n) : [u]
  ), l = [...n, ...l]), r(...l)), s = (l, u) => ({ [l]: (...d) => u(...d) })[l], i = yt([
    ...fe($n, ([l]) => [
      l,
      s(l, o)
    ]),
    ...fe(Nn, ([l]) => [
      l,
      s(l, (...u) => o(l, ...u))
    ])
  ]);
  for (const l of ["group", "groupCollapsed"])
    i[l] && (i[l] = s(l, (...u) => {
      u.length >= 1 && o(...u), oe(n, " ");
    }));
  return i.groupEnd && (i.groupEnd = s("groupEnd", (...l) => {
    wr(n);
  })), harden(i), On(
    /** @type {VirtualConsole} */
    i,
    t
  );
});
y(ks);
const Pi = (t, e, r = void 0) => {
  const n = et(
    Es,
    ([i, c]) => i in t
  ), o = fe(n, ([i, c]) => [i, y((...u) => {
    (c === void 0 || e.canLog(c)) && t[i](...u);
  })]), s = yt(o);
  return (
    /** @type {VirtualConsole} */
    y(s)
  );
};
y(Pi);
const so = (t) => {
  if (At === void 0)
    return;
  let e = 0;
  const r = new Re(), n = (d) => {
    fa(r, d);
  }, o = new je(), s = (d) => {
    if (zr(r, d)) {
      const f = He(r, d);
      n(d), t(f);
    }
  }, i = new At(s);
  return {
    rejectionHandledHandler: (d) => {
      const f = z(o, d);
      n(f);
    },
    unhandledRejectionHandler: (d, f) => {
      e += 1;
      const h = e;
      he(r, h, d), me(o, f, h), Sa(i, f, h, f);
    },
    processTerminationHandler: () => {
      for (const [d, f] of pa(r))
        n(d), t(f);
    }
  };
}, rn = (t) => {
  throw v(t);
}, ao = (t, e) => y((...r) => ue(t, e, r)), Ti = (t = "safe", e = "platform", r = "report", n = void 0) => {
  t === "safe" || t === "unsafe" || rn(`unrecognized consoleTaming ${t}`);
  let o;
  n === void 0 ? o = Pr : o = {
    ...Pr,
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
        ao(T.print)
      )
    ) : void 0
  );
  if (s && s.log)
    for (const u of ["warn", "error"])
      s[u] || U(s, u, {
        value: ao(s.log, s)
      });
  const i = (
    /** @type {VirtualConsole} */
    t === "unsafe" ? s : On(s, o)
  ), c = T.process || void 0;
  if (e !== "none" && typeof c == "object" && typeof c.on == "function") {
    let u;
    if (e === "platform" || e === "exit") {
      const { exit: d } = c;
      typeof d == "function" || rn("missing process.exit"), u = () => d(c.exitCode || -1);
    } else e === "abort" && (u = c.abort, typeof u == "function" || rn("missing process.abort"));
    c.on("uncaughtException", (d) => {
      i.error(d), u && u();
    });
  }
  if (r !== "none" && typeof c == "object" && typeof c.on == "function") {
    const d = so((f) => {
      i.error("SES_UNHANDLED_REJECTION:", f);
    });
    d && (c.on("unhandledRejection", d.unhandledRejectionHandler), c.on("rejectionHandled", d.rejectionHandledHandler), c.on("exit", d.processTerminationHandler));
  }
  const l = T.window || void 0;
  if (e !== "none" && typeof l == "object" && typeof l.addEventListener == "function" && l.addEventListener("error", (u) => {
    u.preventDefault(), i.error(u.error), (e === "exit" || e === "abort") && (l.location.href = "about:blank");
  }), r !== "none" && typeof l == "object" && typeof l.addEventListener == "function") {
    const d = so((f) => {
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
  const r = yt(fe(Ai, (n) => {
    const o = t[n];
    return [n, () => ue(o, t, [])];
  }));
  return H(r, {});
}, Ci = (t) => fe(t, Ii), Ri = /\/node_modules\//, $i = /^(?:node:)?internal\//, Ni = /\/packages\/ses\/src\/error\/assert.js$/, Oi = /\/packages\/eventual-send\/src\//, Mi = [
  Ri,
  $i,
  Ni,
  Oi
], Li = (t) => {
  if (!t)
    return !0;
  for (const e of Mi)
    if (Sn(e, t))
      return !1;
  return !0;
}, Fi = /^((?:.*[( ])?)[:/\w_-]*\/\.\.\.\/(.+)$/, Di = /^((?:.*[( ])?)[:/\w_-]*\/(packages\/.+)$/, Ui = [
  Fi,
  Di
], ji = (t) => {
  for (const e of Ui) {
    const r = En(e, t);
    if (r)
      return Ft(la(r, 1), "");
  }
  return t;
}, Zi = (t, e, r, n) => {
  if (r === "unsafe-debug")
    throw v(
      "internal: v8+unsafe-debug special case should already be done"
    );
  const o = t.captureStackTrace, s = (p) => n === "verbose" ? !0 : Li(p.getFileName()), i = (p) => {
    let m = `${p}`;
    return n === "concise" && (m = ji(m)), `
  at ${m}`;
  }, c = (p, m) => Ft(
    fe(et(m, s), i),
    ""
  ), l = new je(), u = {
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
      Io(p, "stack", "");
    },
    // Shim of proposed special power, to reside by default only
    // in the start compartment, for getting the stack traceback
    // string associated with an error.
    // See https://tc39.es/proposal-error-stacks/
    getStackString(p) {
      let m = z(l, p);
      if (m === void 0 && (p.stack, m = z(l, p), m || (m = { stackString: "" }, me(l, p, m))), m.stackString !== void 0)
        return m.stackString;
      const _ = c(p, m.callSites);
      return me(l, p, { stackString: _ }), _;
    },
    prepareStackTrace(p, m) {
      if (r === "unsafe") {
        const _ = c(p, m);
        return me(l, p, { stackString: _ }), `${p}${_}`;
      } else
        return me(l, p, { callSites: m }), "";
    }
  }, d = u.prepareStackTrace;
  t.prepareStackTrace = d;
  const f = new Mt([d]), h = (p) => {
    if (cr(f, p))
      return p;
    const m = {
      prepareStackTrace(_, S) {
        return me(l, _, { callSites: S }), p(_, Ci(S));
      }
    };
    return Br(f, m.prepareStackTrace), m.prepareStackTrace;
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
}, io = ne(ce.prototype, "stack"), co = io && io.get, zi = {
  getStackString(t) {
    return typeof co == "function" ? ue(co, t, []) : "stack" in t ? `${t.stack}` : "";
  }
};
let hr = zi.getStackString;
function Bi(t = "safe", e = "concise") {
  if (t !== "safe" && t !== "unsafe" && t !== "unsafe-debug")
    throw v(`unrecognized errorTaming ${t}`);
  if (e !== "concise" && e !== "verbose")
    throw v(`unrecognized stackFiltering ${e}`);
  const r = ce.prototype, { captureStackTrace: n } = ce, o = typeof n == "function" ? "v8" : "unknown", s = (l = {}) => {
    const u = function(...f) {
      let h;
      return new.target === void 0 ? h = ue(ce, this, f) : h = br(ce, f, new.target), o === "v8" && ue(n, ce, [h, u]), h;
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
  for (const l of ns)
    Eo(l, c);
  if (B(i, {
    stackTraceLimit: {
      get() {
        if (typeof ce.stackTraceLimit == "number")
          return ce.stackTraceLimit;
      },
      set(l) {
        if (typeof l == "number" && typeof ce.stackTraceLimit == "number") {
          ce.stackTraceLimit = l;
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
          return ce.prepareStackTrace;
        },
        set(u) {
          ce.prepareStackTrace = u;
        },
        enumerable: !1,
        configurable: !0
      },
      captureStackTrace: {
        value: ce.captureStackTrace,
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
        U(l, "stack", {
          value: ""
        });
      },
      writable: !1,
      enumerable: !1,
      configurable: !0
    }
  }), o === "v8" ? hr = Zi(
    ce,
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
const Gi = () => {
}, Vi = async (t, e, r) => {
  await null;
  const n = t(...e);
  let o = xr(n);
  for (; !o.done; )
    try {
      const s = await o.value;
      o = xr(n, s);
    } catch (s) {
      o = Fo(n, r(s));
    }
  return o.value;
}, Hi = (t, e) => {
  const r = t(...e);
  let n = xr(r);
  for (; !n.done; )
    try {
      n = xr(r, n.value);
    } catch (o) {
      n = Fo(r, o);
    }
  return n.value;
}, Wi = (t, e) => y({ compartment: t, specifier: e }), qi = (t, e, r) => {
  const n = H(null);
  for (const o of t) {
    const s = e(o, r);
    n[o] = s;
  }
  return y(n);
}, Ut = (t, e, r, n, o, s, i, c, l) => {
  const { resolveHook: u } = z(t, r), d = qi(
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
  for (const h of ko(d))
    s(Pt, [
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
function* Ki(t, e, r, n, o, s, i) {
  const {
    importHook: c,
    importNowHook: l,
    moduleMap: u,
    moduleMapHook: d,
    moduleRecords: f,
    parentCompartment: h
  } = z(t, r);
  if (zr(f, n))
    return He(f, n);
  let p = u[n];
  if (p === void 0 && d !== void 0 && (p = d(n)), p === void 0) {
    const m = s(c, l);
    if (m === void 0) {
      const _ = s(
        "importHook",
        "importNowHook"
      );
      throw Le(
        le`${kr(_)} needed to load module ${Z(
          n
        )} in compartment ${Z(r.name)}`
      );
    }
    p = m(n), kt(e, p) || (p = yield p);
  }
  if (typeof p == "string")
    throw Le(
      le`Cannot map module ${Z(n)} to ${Z(
        p
      )} in parent compartment, use {source} module descriptor`,
      v
    );
  if (ke(p)) {
    let m = z(e, p);
    if (m !== void 0 && (p = m), p.namespace !== void 0) {
      if (typeof p.namespace == "string") {
        const {
          compartment: x = h,
          namespace: I
        } = p;
        if (!ke(x) || !kt(t, x))
          throw Le(
            le`Invalid compartment in module descriptor for specifier ${Z(n)} in compartment ${Z(r.name)}`
          );
        const E = yield Pt(
          t,
          e,
          x,
          I,
          o,
          s,
          i
        );
        return he(f, n, E), E;
      }
      if (ke(p.namespace)) {
        const { namespace: x } = p;
        if (m = z(e, x), m !== void 0)
          p = m;
        else {
          const I = It(x), $ = Ut(
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
          return he(f, n, $), $;
        }
      } else
        throw Le(
          le`Invalid compartment in module descriptor for specifier ${Z(n)} in compartment ${Z(r.name)}`
        );
    }
    if (p.source !== void 0)
      if (typeof p.source == "string") {
        const {
          source: x,
          specifier: I = n,
          compartment: E = h,
          importMeta: L = void 0
        } = p, $ = yield Pt(
          t,
          e,
          E,
          x,
          o,
          s,
          i
        ), { moduleSource: j } = $, F = Ut(
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
        return he(f, n, F), F;
      } else {
        const {
          source: x,
          specifier: I = n,
          importMeta: E
        } = p, L = Ut(
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
        return he(f, n, L), L;
      }
    if (p.archive !== void 0)
      throw Le(
        le`Unsupported archive module descriptor for specifier ${Z(n)} in compartment ${Z(r.name)}`
      );
    if (p.record !== void 0) {
      const {
        compartment: x = r,
        specifier: I = n,
        record: E,
        importMeta: L
      } = p, $ = Ut(
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
      return he(f, n, $), he(f, I, $), $;
    }
    if (p.compartment !== void 0 && p.specifier !== void 0) {
      if (!ke(p.compartment) || !kt(t, p.compartment) || typeof p.specifier != "string")
        throw Le(
          le`Invalid compartment in module descriptor for specifier ${Z(n)} in compartment ${Z(r.name)}`
        );
      const x = yield Pt(
        t,
        e,
        p.compartment,
        p.specifier,
        o,
        s,
        i
      );
      return he(f, n, x), x;
    }
    const S = Ut(
      t,
      e,
      r,
      n,
      p,
      o,
      s,
      i
    );
    return he(f, n, S), S;
  } else
    throw Le(
      le`module descriptor must be a string or object for specifier ${Z(
        n
      )} in compartment ${Z(r.name)}`
    );
}
const Pt = (t, e, r, n, o, s, i) => {
  const { name: c } = z(
    t,
    r
  );
  let l = He(i, r);
  l === void 0 && (l = new Re(), he(i, r, l));
  let u = He(l, n);
  return u !== void 0 || (u = s(Vi, Hi)(
    Ki,
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
      throw Hr(
        d,
        le`${d.message}, loading ${Z(n)} in compartment ${Z(
          c
        )}`
      ), d;
    }
  ), he(l, n, u)), u;
}, Yi = () => {
  const t = new Ot(), e = [];
  return { enqueueJob: (o, s) => {
    wn(
      t,
      Uo(o(...s), Gi, (i) => {
        oe(e, i);
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
    const r = ve("COMPARTMENT_LOAD_ERRORS", "", ["verbose"]) === "verbose";
    throw v(
      `${e} (${t.length} underlying failures: ${Ft(
        fe(t, (n) => n.message + (r ? n.stack : "")),
        ", "
      )}`
    );
  }
}, Ji = (t, e) => e, Xi = (t, e) => t, lo = async (t, e, r, n) => {
  const { name: o } = z(
    t,
    r
  ), s = new Re(), { enqueueJob: i, drainQueue: c } = Yi();
  i(Pt, [
    t,
    e,
    r,
    n,
    i,
    Xi,
    s
  ]);
  const l = await c();
  Ps({
    errors: l,
    errorPrefix: `Failed to load module ${Z(n)} in package ${Z(
      o
    )}`
  });
}, Qi = (t, e, r, n) => {
  const { name: o } = z(
    t,
    r
  ), s = new Re(), i = [], c = (l, u) => {
    try {
      l(...u);
    } catch (d) {
      oe(i, d);
    }
  };
  c(Pt, [
    t,
    e,
    r,
    n,
    c,
    Ji,
    s
  ]), Ps({
    errors: i,
    errorPrefix: `Failed to load module ${Z(n)} in package ${Z(
      o
    )}`
  });
}, { quote: _t } = ee, ec = () => {
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
    exportsProxy: new Lr(e, {
      get(r, n, o) {
        if (!t)
          throw v(
            `Cannot get property ${_t(
              n
            )} of module exports namespace, the module has not yet begun to execute`
          );
        return oa(e, n, o);
      },
      set(r, n, o) {
        throw v(
          `Cannot set property ${_t(n)} of module exports namespace`
        );
      },
      has(r, n) {
        if (!t)
          throw v(
            `Cannot check property ${_t(
              n
            )}, the module has not yet begun to execute`
          );
        return Ao(e, n);
      },
      deleteProperty(r, n) {
        throw v(
          `Cannot delete property ${_t(n)}s of module exports namespace`
        );
      },
      ownKeys(r) {
        if (!t)
          throw v(
            "Cannot enumerate keys, the module has not yet begun to execute"
          );
        return Ve(e);
      },
      getOwnPropertyDescriptor(r, n) {
        if (!t)
          throw v(
            `Cannot get own property descriptor ${_t(
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
          `Cannot define property ${_t(n)} of module exports namespace`
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
}, Mn = (t, e, r, n) => {
  const { deferredExports: o } = e;
  if (!zr(o, n)) {
    const s = ec();
    me(
      r,
      s.exportsProxy,
      Wi(t, n)
    ), he(o, n, s);
  }
  return He(o, n);
}, tc = (t, e) => {
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
    )), { safeEvaluate: o } = Rn({
      globalObject: i,
      moduleLexicals: c,
      globalTransforms: s,
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
    __evadeImportExpressionTest__: s = !1,
    __rejectSomeDirectEvalExpressions__: i = !0
    // Note default on
  } = r, c = [...n];
  o === !0 && oe(c, gs), s === !0 && oe(c, _s), i === !0 && oe(c, bs);
  const { safeEvaluate: l } = tc(
    t,
    r
  );
  return l(e, {
    localTransforms: c
  });
}, { quote: mr } = ee, rc = (t, e, r, n, o, s) => {
  const { exportsProxy: i, exportsTarget: c, activate: l } = Mn(
    r,
    z(t, r),
    n,
    o
  ), u = H(null);
  if (e.exports) {
    if (!Et(e.exports) || ua(e.exports, (f) => typeof f != "string"))
      throw v(
        `SES virtual module source "exports" property must be an array of strings for module ${o}`
      );
    ft(e.exports, (f) => {
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
        oe(p, S), S(h);
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
          e.execute(c, r, s);
        } catch (f) {
          throw d.errorFromExecute = f, f;
        }
      }
    }
  });
}, nc = (t, e, r, n) => {
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
  } = i, _ = z(t, o), { __shimTransforms__: S, importMetaHook: x } = _, { exportsProxy: I, exportsTarget: E, activate: L } = Mn(
    o,
    _,
    e,
    s
  ), $ = H(null), j = H(null), F = H(null), J = H(null), X = H(null);
  c && Fr(X, c), p && x && x(s, X);
  const qe = H(null), st = H(null);
  ft(ge(d), ([we, [W]]) => {
    let q = qe[W];
    if (!q) {
      let ae, ie = !0, ye = [];
      const te = () => {
        if (ie)
          throw Bt(`binding ${mr(W)} not yet initialized`);
        return ae;
      }, Te = y((Ae) => {
        if (!ie)
          throw v(
            `Internal: binding ${mr(W)} already initialized`
          );
        ae = Ae;
        const Zn = ye;
        ye = null, ie = !1;
        for (const Ie of Zn || [])
          Ie(Ae);
        return Ae;
      });
      q = {
        get: te,
        notify: (Ae) => {
          Ae !== Te && (ie ? oe(ye || [], Ae) : Ae(ae));
        }
      }, qe[W] = q, F[W] = Te;
    }
    $[we] = {
      get: q.get,
      set: void 0,
      enumerable: !0,
      configurable: !1
    }, st[we] = q.notify;
  }), ft(
    ge(f),
    ([we, [W, q]]) => {
      let ae = qe[W];
      if (!ae) {
        let ie, ye = !0;
        const te = [], Te = () => {
          if (ye)
            throw Bt(
              `binding ${mr(we)} not yet initialized`
            );
          return ie;
        }, vt = y((Ie) => {
          ie = Ie, ye = !1;
          for (const Kr of te)
            Kr(Ie);
        }), Ae = (Ie) => {
          if (ye)
            throw Bt(`binding ${mr(W)} not yet initialized`);
          ie = Ie;
          for (const Kr of te)
            Kr(Ie);
        };
        ae = {
          get: Te,
          notify: (Ie) => {
            Ie !== vt && (oe(te, Ie), ye || Ie(ie));
          }
        }, qe[W] = ae, q && U(j, W, {
          get: Te,
          set: Ae,
          enumerable: !0,
          configurable: !1
        }), J[W] = vt;
      }
      $[we] = {
        get: ae.get,
        set: void 0,
        enumerable: !0,
        configurable: !1
      }, st[we] = ae.notify;
    }
  );
  const Ke = (we) => {
    we(E);
  };
  st["*"] = Ke;
  function ur(we) {
    const W = H(null);
    W.default = !1;
    for (const [q, ae] of we) {
      const ie = He(n, q);
      ie.execute();
      const { notifiers: ye } = ie;
      for (const [te, Te] of ae) {
        const vt = ye[te];
        if (!vt)
          throw sr(
            `The requested module '${q}' does not provide an export named '${te}'`
          );
        for (const Ae of Te)
          vt(Ae);
      }
      if (Zr(l, q))
        for (const [te, Te] of ge(
          ye
        ))
          W[te] === void 0 ? W[te] = Te : W[te] = !1;
      if (h[q])
        for (const [te, Te] of h[q])
          W[Te] = ye[te];
    }
    for (const [q, ae] of ge(W))
      if (!st[q] && ae !== !1) {
        st[q] = ae;
        let ie;
        ae((te) => ie = te), $[q] = {
          get() {
            return ie;
          },
          set: void 0,
          enumerable: !0,
          configurable: !1
        };
      }
    ft(
      Oo(So($)),
      (q) => U(E, q, $[q])
    ), y(E), L();
  }
  let Dt;
  m !== void 0 ? Dt = m : Dt = Ts(_, u, {
    globalObject: o.globalThis,
    transforms: S,
    __moduleShimLexicals__: j
  });
  let Un = !1, jn;
  function Bs() {
    if (Dt) {
      const we = Dt;
      Dt = null;
      try {
        we(
          y({
            imports: y(ur),
            onceVar: y(F),
            liveVar: y(J),
            importMeta: X
          })
        );
      } catch (W) {
        Un = !0, jn = W;
      }
    }
    if (Un)
      throw jn;
  }
  return y({
    notifiers: st,
    exportsProxy: I,
    execute: Bs
  });
}, { Fail: dt, quote: Q } = ee, As = (t, e, r, n) => {
  const { name: o, moduleRecords: s } = z(
    t,
    r
  ), i = He(s, n);
  if (i === void 0)
    throw Bt(
      `Missing link to module ${Q(n)} from compartment ${Q(
        o
      )}`
    );
  return lc(t, e, i);
};
function oc(t) {
  return typeof t.__syncModuleProgram__ == "string";
}
function sc(t, e) {
  const { __fixedExportMap__: r, __liveExportMap__: n } = t;
  ke(r) || dt`Property '__fixedExportMap__' of a precompiled module source must be an object, got ${Q(
    r
  )}, for module ${Q(e)}`, ke(n) || dt`Property '__liveExportMap__' of a precompiled module source must be an object, got ${Q(
    n
  )}, for module ${Q(e)}`;
}
function ac(t) {
  return typeof t.execute == "function";
}
function ic(t, e) {
  const { exports: r } = t;
  Et(r) || dt`Property 'exports' of a third-party module source must be an array, got ${Q(
    r
  )}, for module ${Q(e)}`;
}
function cc(t, e) {
  ke(t) || dt`Module sources must be of type object, got ${Q(
    t
  )}, for module ${Q(e)}`;
  const { imports: r, exports: n, reexports: o = [] } = t;
  Et(r) || dt`Property 'imports' of a module source must be an array, got ${Q(
    r
  )}, for module ${Q(e)}`, Et(n) || dt`Property 'exports' of a precompiled module source must be an array, got ${Q(
    n
  )}, for module ${Q(e)}`, Et(o) || dt`Property 'reexports' of a precompiled module source must be an array if present, got ${Q(
    o
  )}, for module ${Q(e)}`;
}
const lc = (t, e, r) => {
  const { compartment: n, moduleSpecifier: o, resolvedImports: s, moduleSource: i } = r, { instances: c } = z(t, n);
  if (zr(c, o))
    return He(c, o);
  cc(i, o);
  const l = new Re();
  let u;
  if (oc(i))
    sc(i, o), u = nc(
      t,
      e,
      r,
      l
    );
  else if (ac(i))
    ic(i, o), u = rc(
      t,
      i,
      n,
      e,
      o,
      s
    );
  else
    throw v(
      `importHook must provide a module source, got ${Q(i)}`
    );
  he(c, o, u);
  for (const [d, f] of ge(s)) {
    const h = As(
      t,
      e,
      n,
      f
    );
    he(l, d, h);
  }
  return u;
}, jt = new je(), Me = new je(), Ln = function(e = {}, r = {}, n = {}) {
  throw v(
    "Compartment.prototype.constructor is not a valid constructor."
  );
}, uo = (t, e) => {
  const { execute: r, exportsProxy: n } = As(
    Me,
    jt,
    t,
    e
  );
  return r(), n;
}, Fn = {
  constructor: Ln,
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
    return Ts(r, t, e);
  },
  module(t) {
    if (typeof t != "string")
      throw v("first argument of module() must be a string");
    const { exportsProxy: e } = Mn(
      this,
      z(Me, this),
      jt,
      t
    );
    return e;
  },
  async import(t) {
    const { noNamespaceBox: e } = z(Me, this);
    if (typeof t != "string")
      throw v("first argument of import() must be a string");
    return Uo(
      lo(Me, jt, this, t),
      () => {
        const r = uo(
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
      throw v("first argument of load() must be a string");
    return lo(Me, jt, this, t);
  },
  importNow(t) {
    if (typeof t != "string")
      throw v("first argument of importNow() must be a string");
    return Qi(Me, jt, this, t), uo(
      /** @type {Compartment} */
      this,
      t
    );
  }
};
B(Fn, {
  [Qe]: {
    value: "Compartment",
    writable: !1,
    enumerable: !1,
    configurable: !0
  }
});
B(Ln, {
  prototype: { value: Fn }
});
const uc = (...t) => {
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
    return Kn(
      n.modules,
      void 0,
      "Compartment constructor must receive either a module map argument or modules option, not both"
    ), Kn(
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
  function o(...s) {
    if (new.target === void 0)
      throw v(
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
      importMetaHook: _,
      __noNamespaceBox__: S = !1
    } = uc(...s), x = [...c, ...l], I = { __proto__: null, ...u }, E = { __proto__: null, ...d }, L = new Re(), $ = new Re(), j = new Re(), F = {};
    li(F), cs(F);
    const { safeEvaluate: J } = Rn({
      globalObject: F,
      globalTransforms: x,
      sloppyGlobalsMode: !1
    });
    ls(F, {
      intrinsics: e,
      newGlobalPropertyNames: rs,
      makeCompartmentConstructor: t,
      parentCompartment: this,
      markVirtualizedNativeFunction: r
    }), dn(
      F,
      J,
      r
    ), Fr(F, I), me(Me, this, {
      name: `${i}`,
      globalTransforms: x,
      globalObject: F,
      safeEvaluate: J,
      resolveHook: f,
      importHook: h,
      importNowHook: p,
      moduleMap: E,
      moduleMapHook: m,
      importMetaHook: _,
      moduleRecords: L,
      __shimTransforms__: l,
      deferredExports: j,
      instances: $,
      parentCompartment: n,
      noNamespaceBox: S
    });
  }
  return o.prototype = Fn, o;
};
function nn(t) {
  return V(t).constructor;
}
function dc() {
  return arguments;
}
const fc = () => {
  const t = Ee.prototype.constructor, e = ne(dc(), "callee"), r = e && e.get, n = _a(new be()), o = V(n), s = Ur[Po] && ga(/./), i = s && V(s), c = da([]), l = V(c), u = V(Hs), d = ha(new Re()), f = V(d), h = ma(new Ot()), p = V(h), m = V(l);
  function* _() {
  }
  const S = nn(_), x = S.prototype;
  async function* I() {
  }
  const E = nn(
    I
  ), L = E.prototype, $ = L.prototype, j = V($);
  async function F() {
  }
  const J = nn(F), X = {
    "%InertFunction%": t,
    "%ArrayIteratorPrototype%": l,
    "%InertAsyncFunction%": J,
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
    "%InertCompartment%": Ln
  };
  return T.Iterator && (X["%IteratorHelperPrototype%"] = V(
    // eslint-disable-next-line @endo/no-polymorphic-call
    T.Iterator.from([]).take(0)
  ), X["%WrapForValidIteratorPrototype%"] = V(
    // eslint-disable-next-line @endo/no-polymorphic-call
    T.Iterator.from({ next() {
    } })
  )), T.AsyncIterator && (X["%AsyncIteratorHelperPrototype%"] = V(
    // eslint-disable-next-line @endo/no-polymorphic-call
    T.AsyncIterator.from([]).take(0)
  ), X["%WrapForValidAsyncIteratorPrototype%"] = V(
    // eslint-disable-next-line @endo/no-polymorphic-call
    T.AsyncIterator.from({ next() {
    } })
  )), X;
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
  const t = St, e = t.prototype, r = xa(St, void 0);
  B(e, {
    constructor: {
      value: r
      // leave other `constructor` attributes as is
    }
  });
  const n = ge(
    Ze(t)
  ), o = yt(
    fe(n, ([s, i]) => [
      s,
      { ...i, configurable: !0 }
    ])
  );
  return B(r, o), { "%SharedSymbol%": r };
}, hc = (t) => {
  try {
    return t(), !1;
  } catch {
    return !0;
  }
}, fo = (t, e, r) => {
  if (t === void 0)
    return !1;
  const n = ne(t, e);
  if (!n || "value" in n)
    return !1;
  const { get: o, set: s } = n;
  if (typeof o != "function" || typeof s != "function" || o() !== r || ue(o, t, []) !== r)
    return !1;
  const i = "Seems to be a setter", c = { __proto__: null };
  if (ue(s, c, [i]), c[e] !== i)
    return !1;
  const l = { __proto__: t };
  return ue(s, l, [i]), l[e] !== i || !hc(() => ue(s, t, [r])) || "originalValue" in o || n.configurable === !1 ? !1 : (U(t, e, {
    value: r,
    writable: !0,
    enumerable: n.enumerable,
    configurable: !0
  }), !0);
}, mc = (t) => {
  fo(
    t["%IteratorPrototype%"],
    "constructor",
    t.Iterator
  ), fo(
    t["%IteratorPrototype%"],
    Qe,
    "Iterator"
  );
}, { Fail: po, details: ho, quote: mo } = ee;
let gr, yr;
const gc = Ba(), yc = () => {
  let t = !1;
  try {
    t = Ee(
      "eval",
      "SES_changed",
      `        eval("SES_changed = true");
        return SES_changed;
      `
    )(jo, !1), t || delete T.SES_changed;
  } catch {
    t = !0;
  }
  if (!t)
    throw v(
      "SES cannot initialize unless 'eval' is the original intrinsic 'eval', suitable for direct-eval (dynamically scoped eval) (SES_DIRECT_EVAL)"
    );
}, Cs = (t = {}) => {
  const {
    errorTaming: e = ve("LOCKDOWN_ERROR_TAMING", "safe"),
    errorTrapping: r = (
      /** @type {"platform" | "none" | "report" | "abort" | "exit" | undefined} */
      ve("LOCKDOWN_ERROR_TRAPPING", "platform")
    ),
    unhandledRejectionTrapping: n = (
      /** @type {"none" | "report" | undefined} */
      ve("LOCKDOWN_UNHANDLED_REJECTION_TRAPPING", "report")
    ),
    regExpTaming: o = ve("LOCKDOWN_REGEXP_TAMING", "safe"),
    localeTaming: s = ve("LOCKDOWN_LOCALE_TAMING", "safe"),
    consoleTaming: i = (
      /** @type {'unsafe' | 'safe' | undefined} */
      ve("LOCKDOWN_CONSOLE_TAMING", "safe")
    ),
    overrideTaming: c = ve("LOCKDOWN_OVERRIDE_TAMING", "moderate"),
    stackFiltering: l = ve("LOCKDOWN_STACK_FILTERING", "concise"),
    domainTaming: u = ve("LOCKDOWN_DOMAIN_TAMING", "safe"),
    evalTaming: d = ve("LOCKDOWN_EVAL_TAMING", "safeEval"),
    overrideDebug: f = et(
      Pn(ve("LOCKDOWN_OVERRIDE_DEBUG", ""), ","),
      /** @param {string} debugName */
      (Ke) => Ke !== ""
    ),
    __hardenTaming__: h = ve("LOCKDOWN_HARDEN_TAMING", "safe"),
    dateTaming: p = "safe",
    // deprecated
    mathTaming: m = "safe",
    // deprecated
    ..._
  } = t;
  d === "unsafeEval" || d === "safeEval" || d === "noEval" || po`lockdown(): non supported option evalTaming: ${mo(d)}`;
  const S = Ve(_);
  if (S.length === 0 || po`lockdown(): non supported option ${mo(S)}`, gr === void 0 || // eslint-disable-next-line @endo/no-polymorphic-call
  ee.fail(
    ho`Already locked down at ${gr} (SES_ALREADY_LOCKED_DOWN)`,
    v
  ), gr = v("Prior lockdown (SES_ALREADY_LOCKED_DOWN)"), gr.stack, yc(), T.Function.prototype.constructor !== T.Function && // @ts-ignore harden is absent on globalThis type def.
  typeof T.harden == "function" && // @ts-ignore lockdown is absent on globalThis type def.
  typeof T.lockdown == "function" && T.Date.prototype.constructor !== T.Date && typeof T.Date.now == "function" && // @ts-ignore does not recognize that Date constructor is a special
  // Function.
  // eslint-disable-next-line @endo/no-polymorphic-call
  Dr(T.Date.prototype.constructor.now(), NaN))
    throw v(
      "Already locked down but not by this SES instance (SES_MULTIPLE_INSTANCES)"
    );
  Si(u);
  const I = Ss(), { addIntrinsics: E, completePrototypes: L, finalIntrinsics: $ } = ss(), j = Is(gc, h);
  E({ harden: j }), E(Ya()), E(Ja(p)), E(Bi(e, l)), E(Xa(m)), E(Qa(o)), E(pc()), E(fc()), L();
  const F = $(), J = { __proto__: null };
  typeof T.Buffer == "function" && (J.Buffer = T.Buffer);
  let X;
  e === "safe" && (X = F["%InitialGetStackString%"]);
  const qe = Ti(
    i,
    r,
    n,
    X
  );
  if (T.console = /** @type {Console} */
  qe.console, typeof /** @type {any} */
  qe.console._times == "object" && (J.SafeMap = V(
    // eslint-disable-next-line no-underscore-dangle
    /** @type {any} */
    qe.console._times
  )), (e === "unsafe" || e === "unsafe-debug") && T.assert === ee && (T.assert = Wr(void 0, !0)), ai(F, s), mc(F), Ka(F, I), cs(T), ls(T, {
    intrinsics: F,
    newGlobalPropertyNames: Yn,
    makeCompartmentConstructor: fn,
    markVirtualizedNativeFunction: I
  }), d === "noEval")
    dn(
      T,
      Ea,
      I
    );
  else if (d === "safeEval") {
    const { safeEvaluate: Ke } = Rn({ globalObject: T });
    dn(
      T,
      Ke,
      I
    );
  }
  return () => {
    yr === void 0 || // eslint-disable-next-line @endo/no-polymorphic-call
    ee.fail(
      ho`Already locked down at ${yr} (SES_ALREADY_LOCKED_DOWN)`,
      v
    ), yr = v(
      "Prior lockdown (SES_ALREADY_LOCKED_DOWN)"
    ), yr.stack, ri(F, c, f);
    const Ke = {
      intrinsics: F,
      hostIntrinsics: J,
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
    for (const ur of It(Yn))
      Ke.globals[ur] = T[ur];
    return j(Ke), j;
  };
};
T.lockdown = (t) => {
  const e = Cs(t);
  T.harden = e();
};
T.repairIntrinsics = (t) => {
  const e = Cs(t);
  T.hardenIntrinsics = () => {
    T.harden = e();
  };
};
const vc = Ss();
T.Compartment = fn(
  fn,
  qa(T),
  vc
);
T.assert = ee;
const _c = ks(Pr), bc = ta(
  "MAKE_CAUSAL_CONSOLE_FROM_LOGGER_KEY_FOR_SES_AVA"
);
T[bc] = _c;
const wc = (t, e = t, r) => {
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
}, xc = `:host{--spacing-4: .25rem;--spacing-8: calc(var(--spacing-4) * 2);--spacing-12: calc(var(--spacing-4) * 3);--spacing-16: calc(var(--spacing-4) * 4);--spacing-20: calc(var(--spacing-4) * 5);--spacing-24: calc(var(--spacing-4) * 6);--spacing-28: calc(var(--spacing-4) * 7);--spacing-32: calc(var(--spacing-4) * 8);--spacing-36: calc(var(--spacing-4) * 9);--spacing-40: calc(var(--spacing-4) * 10);--font-weight-regular: 400;--font-weight-bold: 500;--font-line-height-s: 1.2;--font-line-height-m: 1.4;--font-line-height-l: 1.5;--font-size-s: 12px;--font-size-m: 14px;--font-size-l: 16px}[data-theme]{background-color:var(--color-background-primary);color:var(--color-foreground-secondary)}::-webkit-resizer{display:none}.wrapper{position:absolute;inset-block-start:var(--modal-block-start);inset-inline-start:var(--modal-inline-start);z-index:1000;padding:10px;border-radius:15px;border:2px solid var(--color-background-quaternary);box-shadow:0 0 10px #0000004d;overflow:hidden;min-inline-size:25px;min-block-size:200px;resize:both}.wrapper:after{content:"";cursor:se-resize;inline-size:1rem;block-size:1rem;background-image:url("data:image/svg+xml,%3csvg%20width='16.022'%20xmlns='http://www.w3.org/2000/svg'%20height='16.022'%20viewBox='-0.011%20-0.011%2016.022%2016.022'%20fill='none'%3e%3cg%20data-testid='Group'%3e%3cg%20data-testid='Path'%3e%3cpath%20d='M.011%2015.917%2015.937-.011'%20class='fills'/%3e%3cg%20class='strokes'%3e%3cpath%20d='M.011%2015.917%2015.937-.011'%20style='fill:%20none;%20stroke-width:%201;%20stroke:%20rgb(111,%20111,%20111);%20stroke-opacity:%201;%20stroke-linecap:%20round;'%20class='stroke-shape'/%3e%3c/g%3e%3c/g%3e%3cg%20data-testid='Path'%3e%3cpath%20d='m11.207%2014.601%203.361-3.401'%20class='fills'/%3e%3cg%20class='strokes'%3e%3cpath%20d='m11.207%2014.601%203.361-3.401'%20style='fill:%20none;%20stroke-width:%201;%20stroke:%20rgb(111,%20111,%20111);%20stroke-opacity:%201;%20stroke-linecap:%20round;'%20class='stroke-shape'/%3e%3c/g%3e%3c/g%3e%3cg%20data-testid='Path'%3e%3cpath%20d='m4.884%2016.004%2011.112-11.17'%20class='fills'/%3e%3cg%20class='strokes'%3e%3cpath%20d='m4.884%2016.004%2011.112-11.17'%20style='fill:%20none;%20stroke-width:%201;%20stroke:%20rgb(111,%20111,%20111);%20stroke-opacity:%201;%20stroke-linecap:%20round;'%20class='stroke-shape'/%3e%3c/g%3e%3c/g%3e%3c/g%3e%3c/svg%3e");background-position:center;right:5px;bottom:5px;pointer-events:none;position:absolute}.inner{padding:10px;cursor:grab;box-sizing:border-box;display:flex;flex-direction:column;overflow:hidden;block-size:100%}.inner>*{flex:1}.inner>.header{flex:0}.header{align-items:center;display:flex;justify-content:space-between;border-block-end:2px solid var(--color-background-quaternary);padding-block-end:var(--spacing-4)}button{background:transparent;border:0;cursor:pointer;padding:0}h1{font-size:var(--font-size-s);font-weight:var(--font-weight-bold);margin:0;margin-inline-end:var(--spacing-4);-webkit-user-select:none;user-select:none}iframe{border:none;inline-size:100%;block-size:100%}`, Sc = `
<svg width="16"  height="16"xmlns="http://www.w3.org/2000/svg" fill="none"><g class="fills"><rect rx="0" ry="0" width="16" height="16" class="frame-background"/></g><g class="frame-children"><path d="M11.997 3.997 8 8l-3.997 4.003m-.006-8L8 8l4.003 3.997" class="fills"/><g class="strokes"><path d="M11.997 3.997 8 8l-3.997 4.003m-.006-8L8 8l4.003 3.997" style="fill: none; stroke-width: 1; stroke: rgb(143, 157, 163); stroke-opacity: 1; stroke-linecap: round;" class="stroke-shape"/></g></g></svg>`;
var re, Ge, or;
class Ec extends HTMLElement {
  constructor() {
    super();
    dr(this, re, null);
    dr(this, Ge, null);
    dr(this, or, null);
    this.attachShadow({ mode: "open" });
  }
  setTheme(r) {
    Y(this, re) && Y(this, re).setAttribute("data-theme", r);
  }
  disconnectedCallback() {
    var r;
    (r = Y(this, or)) == null || r.call(this);
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
    fr(this, re, document.createElement("div")), fr(this, Ge, document.createElement("div")), Y(this, Ge).classList.add("inner"), Y(this, re).classList.add("wrapper"), Y(this, re).style.inlineSize = `${o}px`, Y(this, re).style.minInlineSize = `${o}px`, Y(this, re).style.blockSize = `${s}px`, Y(this, re).style.minBlockSize = `${s}px`, Y(this, re).style.maxInlineSize = "90vw", Y(this, re).style.maxBlockSize = "90vh", fr(this, or, wc(Y(this, Ge), Y(this, re), () => {
      this.calculateZIndex();
    }));
    const c = document.createElement("div");
    c.classList.add("header");
    const l = document.createElement("h1");
    l.textContent = r, c.appendChild(l);
    const u = document.createElement("button");
    u.setAttribute("type", "button"), u.innerHTML = `<div class="close">${Sc}</div>`, u.addEventListener("click", () => {
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
    }), this.shadowRoot.appendChild(Y(this, re)), Y(this, re).appendChild(Y(this, Ge)), Y(this, Ge).appendChild(c), Y(this, Ge).appendChild(d);
    const f = document.createElement("style");
    f.textContent = xc, this.shadowRoot.appendChild(f), this.calculateZIndex();
  }
}
re = new WeakMap(), Ge = new WeakMap(), or = new WeakMap();
customElements.define("plugin-modal", Ec);
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
var pn;
(function(t) {
  t.mergeShapes = (e, r) => ({
    ...e,
    ...r
    // second overwrites first
  });
})(pn || (pn = {}));
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
]), Je = (t) => {
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
]), kc = (t) => JSON.stringify(t, null, 2).replace(/"([^"]+)":/g, "$1:");
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
const Rt = (t, e) => {
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
let Rs = Rt;
function Pc(t) {
  Rs = t;
}
function Ir() {
  return Rs;
}
const Cr = (t) => {
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
}, Tc = [];
function b(t, e) {
  const r = Ir(), n = Cr({
    issueData: e,
    data: t.data,
    path: t.path,
    errorMaps: [
      t.common.contextualErrorMap,
      t.schemaErrorMap,
      r,
      r === Rt ? void 0 : Rt
      // then global default map
    ].filter((o) => !!o)
  });
  t.common.issues.push(n);
}
class se {
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
    return se.mergeObjectSync(e, n);
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
}), xt = (t) => ({ status: "dirty", value: t }), pe = (t) => ({ status: "valid", value: t }), hn = (t) => t.status === "aborted", mn = (t) => t.status === "dirty", Gt = (t) => t.status === "valid", Vt = (t) => typeof Promise < "u" && t instanceof Promise;
function Rr(t, e, r, n) {
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
var Zt, zt;
class De {
  constructor(e, r, n, o) {
    this._cachedPath = [], this.parent = e, this.data = r, this._path = n, this._key = o;
  }
  get path() {
    return this._cachedPath.length || (this._key instanceof Array ? this._cachedPath.push(...this._path, ...this._key) : this._cachedPath.push(...this._path, this._key)), this._cachedPath;
  }
}
const go = (t, e) => {
  if (Gt(e))
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
    return Je(e.data);
  }
  _getOrReturnCtx(e, r) {
    return r || {
      common: e.parent.common,
      data: e.data,
      parsedType: Je(e.data),
      schemaErrorMap: this._def.errorMap,
      path: e.path,
      parent: e.parent
    };
  }
  _processInputParams(e) {
    return {
      status: new se(),
      ctx: {
        common: e.parent.common,
        data: e.data,
        parsedType: Je(e.data),
        schemaErrorMap: this._def.errorMap,
        path: e.path,
        parent: e.parent
      }
    };
  }
  _parseSync(e) {
    const r = this._parse(e);
    if (Vt(r))
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
      parsedType: Je(e)
    }, s = this._parseSync({ data: e, path: o.path, parent: o });
    return go(o, s);
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
      parsedType: Je(e)
    }, o = this._parse({ data: e, path: n.path, parent: n }), s = await (Vt(o) ? o : Promise.resolve(o));
    return go(n, s);
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
    return ot.create(this, this._def);
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
    return Kt.create([this, e], this._def);
  }
  and(e) {
    return Yt.create(this, e, this._def);
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
    return new tr({
      ...N(this._def),
      innerType: this,
      defaultValue: r,
      typeName: C.ZodDefault
    });
  }
  brand() {
    return new Dn({
      typeName: C.ZodBranded,
      type: this,
      ...N(this._def)
    });
  }
  catch(e) {
    const r = typeof e == "function" ? e : () => e;
    return new rr({
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
    return lr.create(this, e);
  }
  readonly() {
    return nr.create(this);
  }
  isOptional() {
    return this.safeParse(void 0).success;
  }
  isNullable() {
    return this.safeParse(null).success;
  }
}
const Ac = /^c[^\s-]{8,}$/i, Ic = /^[0-9a-z]+$/, Cc = /^[0-9A-HJKMNP-TV-Z]{26}$/, Rc = /^[0-9a-fA-F]{8}\b-[0-9a-fA-F]{4}\b-[0-9a-fA-F]{4}\b-[0-9a-fA-F]{4}\b-[0-9a-fA-F]{12}$/i, $c = /^[a-z0-9_-]{21}$/i, Nc = /^[-+]?P(?!$)(?:(?:[-+]?\d+Y)|(?:[-+]?\d+[.,]\d+Y$))?(?:(?:[-+]?\d+M)|(?:[-+]?\d+[.,]\d+M$))?(?:(?:[-+]?\d+W)|(?:[-+]?\d+[.,]\d+W$))?(?:(?:[-+]?\d+D)|(?:[-+]?\d+[.,]\d+D$))?(?:T(?=[\d+-])(?:(?:[-+]?\d+H)|(?:[-+]?\d+[.,]\d+H$))?(?:(?:[-+]?\d+M)|(?:[-+]?\d+[.,]\d+M$))?(?:[-+]?\d+(?:[.,]\d+)?S)?)??$/, Oc = /^(?!\.)(?!.*\.\.)([A-Z0-9_'+\-\.]*)[A-Z0-9_+-]@([A-Z0-9][A-Z0-9\-]*\.)+[A-Z]{2,}$/i, Mc = "^(\\p{Extended_Pictographic}|\\p{Emoji_Component})+$";
let on;
const Lc = /^(?:(?:25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9][0-9]|[0-9])\.){3}(?:25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9][0-9]|[0-9])$/, Fc = /^(([a-f0-9]{1,4}:){7}|::([a-f0-9]{1,4}:){0,6}|([a-f0-9]{1,4}:){1}:([a-f0-9]{1,4}:){0,5}|([a-f0-9]{1,4}:){2}:([a-f0-9]{1,4}:){0,4}|([a-f0-9]{1,4}:){3}:([a-f0-9]{1,4}:){0,3}|([a-f0-9]{1,4}:){4}:([a-f0-9]{1,4}:){0,2}|([a-f0-9]{1,4}:){5}:([a-f0-9]{1,4}:){0,1})([a-f0-9]{1,4}|(((25[0-5])|(2[0-4][0-9])|(1[0-9]{2})|([0-9]{1,2}))\.){3}((25[0-5])|(2[0-4][0-9])|(1[0-9]{2})|([0-9]{1,2})))$/, Dc = /^([0-9a-zA-Z+/]{4})*(([0-9a-zA-Z+/]{2}==)|([0-9a-zA-Z+/]{3}=))?$/, Ns = "((\\d\\d[2468][048]|\\d\\d[13579][26]|\\d\\d0[48]|[02468][048]00|[13579][26]00)-02-29|\\d{4}-((0[13578]|1[02])-(0[1-9]|[12]\\d|3[01])|(0[469]|11)-(0[1-9]|[12]\\d|30)|(02)-(0[1-9]|1\\d|2[0-8])))", Uc = new RegExp(`^${Ns}$`);
function Os(t) {
  let e = "([01]\\d|2[0-3]):[0-5]\\d:[0-5]\\d";
  return t.precision ? e = `${e}\\.\\d{${t.precision}}` : t.precision == null && (e = `${e}(\\.\\d+)?`), e;
}
function jc(t) {
  return new RegExp(`^${Os(t)}$`);
}
function Ms(t) {
  let e = `${Ns}T${Os(t)}`;
  const r = [];
  return r.push(t.local ? "Z?" : "Z"), t.offset && r.push("([+-]\\d{2}:?\\d{2})"), e = `${e}(${r.join("|")})`, new RegExp(`^${e}$`);
}
function Zc(t, e) {
  return !!((e === "v4" || !e) && Lc.test(t) || (e === "v6" || !e) && Fc.test(t));
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
    const n = new se();
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
        Oc.test(e.data) || (o = this._getOrReturnCtx(e, o), b(o, {
          validation: "email",
          code: g.invalid_string,
          message: s.message
        }), n.dirty());
      else if (s.kind === "emoji")
        on || (on = new RegExp(Mc, "u")), on.test(e.data) || (o = this._getOrReturnCtx(e, o), b(o, {
          validation: "emoji",
          code: g.invalid_string,
          message: s.message
        }), n.dirty());
      else if (s.kind === "uuid")
        Rc.test(e.data) || (o = this._getOrReturnCtx(e, o), b(o, {
          validation: "uuid",
          code: g.invalid_string,
          message: s.message
        }), n.dirty());
      else if (s.kind === "nanoid")
        $c.test(e.data) || (o = this._getOrReturnCtx(e, o), b(o, {
          validation: "nanoid",
          code: g.invalid_string,
          message: s.message
        }), n.dirty());
      else if (s.kind === "cuid")
        Ac.test(e.data) || (o = this._getOrReturnCtx(e, o), b(o, {
          validation: "cuid",
          code: g.invalid_string,
          message: s.message
        }), n.dirty());
      else if (s.kind === "cuid2")
        Ic.test(e.data) || (o = this._getOrReturnCtx(e, o), b(o, {
          validation: "cuid2",
          code: g.invalid_string,
          message: s.message
        }), n.dirty());
      else if (s.kind === "ulid")
        Cc.test(e.data) || (o = this._getOrReturnCtx(e, o), b(o, {
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
      }), n.dirty()) : s.kind === "datetime" ? Ms(s).test(e.data) || (o = this._getOrReturnCtx(e, o), b(o, {
        code: g.invalid_string,
        validation: "datetime",
        message: s.message
      }), n.dirty()) : s.kind === "date" ? Uc.test(e.data) || (o = this._getOrReturnCtx(e, o), b(o, {
        code: g.invalid_string,
        validation: "date",
        message: s.message
      }), n.dirty()) : s.kind === "time" ? jc(s).test(e.data) || (o = this._getOrReturnCtx(e, o), b(o, {
        code: g.invalid_string,
        validation: "time",
        message: s.message
      }), n.dirty()) : s.kind === "duration" ? Nc.test(e.data) || (o = this._getOrReturnCtx(e, o), b(o, {
        validation: "duration",
        code: g.invalid_string,
        message: s.message
      }), n.dirty()) : s.kind === "ip" ? Zc(e.data, s.version) || (o = this._getOrReturnCtx(e, o), b(o, {
        validation: "ip",
        code: g.invalid_string,
        message: s.message
      }), n.dirty()) : s.kind === "base64" ? Dc.test(e.data) || (o = this._getOrReturnCtx(e, o), b(o, {
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
function zc(t, e) {
  const r = (t.toString().split(".")[1] || "").length, n = (e.toString().split(".")[1] || "").length, o = r > n ? r : n, s = parseInt(t.toFixed(o).replace(".", "")), i = parseInt(e.toFixed(o).replace(".", ""));
  return s % i / Math.pow(10, o);
}
class tt extends O {
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
    const o = new se();
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
      }), o.dirty()) : s.kind === "multipleOf" ? zc(e.data, s.value) !== 0 && (n = this._getOrReturnCtx(e, n), b(n, {
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
    if (this._def.coerce && (e.data = BigInt(e.data)), this._getType(e) !== w.bigint) {
      const s = this._getOrReturnCtx(e);
      return b(s, {
        code: g.invalid_type,
        expected: w.bigint,
        received: s.parsedType
      }), R;
    }
    let n;
    const o = new se();
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
    return new rt({
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
rt.create = (t) => {
  var e;
  return new rt({
    checks: [],
    typeName: C.ZodBigInt,
    coerce: (e = t == null ? void 0 : t.coerce) !== null && e !== void 0 ? e : !1,
    ...N(t)
  });
};
class Ht extends O {
  _parse(e) {
    if (this._def.coerce && (e.data = !!e.data), this._getType(e) !== w.boolean) {
      const n = this._getOrReturnCtx(e);
      return b(n, {
        code: g.invalid_type,
        expected: w.boolean,
        received: n.parsedType
      }), R;
    }
    return pe(e.data);
  }
}
Ht.create = (t) => new Ht({
  typeName: C.ZodBoolean,
  coerce: (t == null ? void 0 : t.coerce) || !1,
  ...N(t)
});
class mt extends O {
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
    const n = new se();
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
    return new mt({
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
mt.create = (t) => new mt({
  checks: [],
  coerce: (t == null ? void 0 : t.coerce) || !1,
  typeName: C.ZodDate,
  ...N(t)
});
class $r extends O {
  _parse(e) {
    if (this._getType(e) !== w.symbol) {
      const n = this._getOrReturnCtx(e);
      return b(n, {
        code: g.invalid_type,
        expected: w.symbol,
        received: n.parsedType
      }), R;
    }
    return pe(e.data);
  }
}
$r.create = (t) => new $r({
  typeName: C.ZodSymbol,
  ...N(t)
});
class Wt extends O {
  _parse(e) {
    if (this._getType(e) !== w.undefined) {
      const n = this._getOrReturnCtx(e);
      return b(n, {
        code: g.invalid_type,
        expected: w.undefined,
        received: n.parsedType
      }), R;
    }
    return pe(e.data);
  }
}
Wt.create = (t) => new Wt({
  typeName: C.ZodUndefined,
  ...N(t)
});
class qt extends O {
  _parse(e) {
    if (this._getType(e) !== w.null) {
      const n = this._getOrReturnCtx(e);
      return b(n, {
        code: g.invalid_type,
        expected: w.null,
        received: n.parsedType
      }), R;
    }
    return pe(e.data);
  }
}
qt.create = (t) => new qt({
  typeName: C.ZodNull,
  ...N(t)
});
class $t extends O {
  constructor() {
    super(...arguments), this._any = !0;
  }
  _parse(e) {
    return pe(e.data);
  }
}
$t.create = (t) => new $t({
  typeName: C.ZodAny,
  ...N(t)
});
class pt extends O {
  constructor() {
    super(...arguments), this._unknown = !0;
  }
  _parse(e) {
    return pe(e.data);
  }
}
pt.create = (t) => new pt({
  typeName: C.ZodUnknown,
  ...N(t)
});
class We extends O {
  _parse(e) {
    const r = this._getOrReturnCtx(e);
    return b(r, {
      code: g.invalid_type,
      expected: w.never,
      received: r.parsedType
    }), R;
  }
}
We.create = (t) => new We({
  typeName: C.ZodNever,
  ...N(t)
});
class Nr extends O {
  _parse(e) {
    if (this._getType(e) !== w.undefined) {
      const n = this._getOrReturnCtx(e);
      return b(n, {
        code: g.invalid_type,
        expected: w.void,
        received: n.parsedType
      }), R;
    }
    return pe(e.data);
  }
}
Nr.create = (t) => new Nr({
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
      return Promise.all([...r.data].map((i, c) => o.type._parseAsync(new De(r, i, r.path, c)))).then((i) => se.mergeArray(n, i));
    const s = [...r.data].map((i, c) => o.type._parseSync(new De(r, i, r.path, c)));
    return se.mergeArray(n, s);
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
function wt(t) {
  if (t instanceof G) {
    const e = {};
    for (const r in t.shape) {
      const n = t.shape[r];
      e[r] = Fe.create(wt(n));
    }
    return new G({
      ...t._def,
      shape: () => e
    });
  } else return t instanceof $e ? new $e({
    ...t._def,
    type: wt(t.element)
  }) : t instanceof Fe ? Fe.create(wt(t.unwrap())) : t instanceof ot ? ot.create(wt(t.unwrap())) : t instanceof Ue ? Ue.create(t.items.map((e) => wt(e))) : t;
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
    if (!(this._def.catchall instanceof We && this._def.unknownKeys === "strip"))
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
    if (this._def.catchall instanceof We) {
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
    }).then((u) => se.mergeObjectSync(n, u)) : se.mergeObjectSync(n, l);
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
    return wt(this);
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
    return Ls(D.objectKeys(this.shape));
  }
}
G.create = (t, e) => new G({
  shape: () => t,
  unknownKeys: "strip",
  catchall: We.create(),
  typeName: C.ZodObject,
  ...N(e)
});
G.strictCreate = (t, e) => new G({
  shape: () => t,
  unknownKeys: "strict",
  catchall: We.create(),
  typeName: C.ZodObject,
  ...N(e)
});
G.lazycreate = (t, e) => new G({
  shape: t,
  unknownKeys: "strip",
  catchall: We.create(),
  typeName: C.ZodObject,
  ...N(e)
});
class Kt extends O {
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
Kt.create = (t, e) => new Kt({
  options: t,
  typeName: C.ZodUnion,
  ...N(e)
});
const Be = (t) => t instanceof Xt ? Be(t.schema) : t instanceof Ne ? Be(t.innerType()) : t instanceof Qt ? [t.value] : t instanceof nt ? t.options : t instanceof er ? D.objectValues(t.enum) : t instanceof tr ? Be(t._def.innerType) : t instanceof Wt ? [void 0] : t instanceof qt ? [null] : t instanceof Fe ? [void 0, ...Be(t.unwrap())] : t instanceof ot ? [null, ...Be(t.unwrap())] : t instanceof Dn || t instanceof nr ? Be(t.unwrap()) : t instanceof rr ? Be(t._def.innerType) : [];
class qr extends O {
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
    return new qr({
      typeName: C.ZodDiscriminatedUnion,
      discriminator: e,
      options: r,
      optionsMap: o,
      ...N(n)
    });
  }
}
function gn(t, e) {
  const r = Je(t), n = Je(e);
  if (t === e)
    return { valid: !0, data: t };
  if (r === w.object && n === w.object) {
    const o = D.objectKeys(e), s = D.objectKeys(t).filter((c) => o.indexOf(c) !== -1), i = { ...t, ...e };
    for (const c of s) {
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
    for (let s = 0; s < t.length; s++) {
      const i = t[s], c = e[s], l = gn(i, c);
      if (!l.valid)
        return { valid: !1 };
      o.push(l.data);
    }
    return { valid: !0, data: o };
  } else return r === w.date && n === w.date && +t == +e ? { valid: !0, data: t } : { valid: !1 };
}
class Yt extends O {
  _parse(e) {
    const { status: r, ctx: n } = this._processInputParams(e), o = (s, i) => {
      if (hn(s) || hn(i))
        return R;
      const c = gn(s.value, i.value);
      return c.valid ? ((mn(s) || mn(i)) && r.dirty(), { status: r.value, value: c.data }) : (b(n, {
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
Yt.create = (t, e, r) => new Yt({
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
    return n.common.async ? Promise.all(s).then((i) => se.mergeArray(r, i)) : se.mergeArray(r, s);
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
class Jt extends O {
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
    return n.common.async ? se.mergeObjectAsync(r, o) : se.mergeObjectSync(r, o);
  }
  get element() {
    return this._def.valueType;
  }
  static create(e, r, n) {
    return r instanceof O ? new Jt({
      keyType: e,
      valueType: r,
      typeName: C.ZodRecord,
      ...N(n)
    }) : new Jt({
      keyType: Ce.create(),
      valueType: e,
      typeName: C.ZodRecord,
      ...N(r)
    });
  }
}
class Or extends O {
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
Or.create = (t, e, r) => new Or({
  valueType: e,
  keyType: t,
  typeName: C.ZodMap,
  ...N(r)
});
class gt extends O {
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
    return new gt({
      ...this._def,
      minSize: { value: e, message: k.toString(r) }
    });
  }
  max(e, r) {
    return new gt({
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
gt.create = (t, e) => new gt({
  valueType: t,
  minSize: null,
  maxSize: null,
  typeName: C.ZodSet,
  ...N(e)
});
class Tt extends O {
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
      return Cr({
        data: c,
        path: r.path,
        errorMaps: [
          r.common.contextualErrorMap,
          r.schemaErrorMap,
          Ir(),
          Rt
        ].filter((u) => !!u),
        issueData: {
          code: g.invalid_arguments,
          argumentsError: l
        }
      });
    }
    function o(c, l) {
      return Cr({
        data: c,
        path: r.path,
        errorMaps: [
          r.common.contextualErrorMap,
          r.schemaErrorMap,
          Ir(),
          Rt
        ].filter((u) => !!u),
        issueData: {
          code: g.invalid_return_type,
          returnTypeError: l
        }
      });
    }
    const s = { errorMap: r.common.contextualErrorMap }, i = r.data;
    if (this._def.returns instanceof Nt) {
      const c = this;
      return pe(async function(...l) {
        const u = new _e([]), d = await c._def.args.parseAsync(l, s).catch((p) => {
          throw u.addIssue(n(l, p)), u;
        }), f = await Reflect.apply(i, this, d);
        return await c._def.returns._def.type.parseAsync(f, s).catch((p) => {
          throw u.addIssue(o(f, p)), u;
        });
      });
    } else {
      const c = this;
      return pe(function(...l) {
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
    return new Tt({
      ...this._def,
      args: Ue.create(e).rest(pt.create())
    });
  }
  returns(e) {
    return new Tt({
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
    return new Tt({
      args: e || Ue.create([]).rest(pt.create()),
      returns: r || pt.create(),
      typeName: C.ZodFunction,
      ...N(n)
    });
  }
}
class Xt extends O {
  get schema() {
    return this._def.getter();
  }
  _parse(e) {
    const { ctx: r } = this._processInputParams(e);
    return this._def.getter()._parse({ data: r.data, path: r.path, parent: r });
  }
}
Xt.create = (t, e) => new Xt({
  getter: t,
  typeName: C.ZodLazy,
  ...N(e)
});
class Qt extends O {
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
Qt.create = (t, e) => new Qt({
  value: t,
  typeName: C.ZodLiteral,
  ...N(e)
});
function Ls(t, e) {
  return new nt({
    values: t,
    typeName: C.ZodEnum,
    ...N(e)
  });
}
class nt extends O {
  constructor() {
    super(...arguments), Zt.set(this, void 0);
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
    if (Rr(this, Zt) || $s(this, Zt, new Set(this._def.values)), !Rr(this, Zt).has(e.data)) {
      const r = this._getOrReturnCtx(e), n = this._def.values;
      return b(r, {
        received: r.data,
        code: g.invalid_enum_value,
        options: n
      }), R;
    }
    return pe(e.data);
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
Zt = /* @__PURE__ */ new WeakMap();
nt.create = Ls;
class er extends O {
  constructor() {
    super(...arguments), zt.set(this, void 0);
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
    if (Rr(this, zt) || $s(this, zt, new Set(D.getValidEnumValues(this._def.values))), !Rr(this, zt).has(e.data)) {
      const o = D.objectValues(r);
      return b(n, {
        received: n.data,
        code: g.invalid_enum_value,
        options: o
      }), R;
    }
    return pe(e.data);
  }
  get enum() {
    return this._def.values;
  }
}
zt = /* @__PURE__ */ new WeakMap();
er.create = (t, e) => new er({
  values: t,
  typeName: C.ZodNativeEnum,
  ...N(e)
});
class Nt extends O {
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
    return pe(n.then((o) => this._def.type.parseAsync(o, {
      path: r.path,
      errorMap: r.common.contextualErrorMap
    })));
  }
}
Nt.create = (t, e) => new Nt({
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
          return l.status === "aborted" ? R : l.status === "dirty" || r.value === "dirty" ? xt(l.value) : l;
        });
      {
        if (r.value === "aborted")
          return R;
        const c = this._def.schema._parseSync({
          data: i,
          path: n.path,
          parent: n
        });
        return c.status === "aborted" ? R : c.status === "dirty" || r.value === "dirty" ? xt(c.value) : c;
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
        if (!Gt(i))
          return i;
        const c = o.transform(i.value, s);
        if (c instanceof Promise)
          throw new Error("Asynchronous transform encountered during synchronous parse operation. Use .parseAsync instead.");
        return { status: r.value, value: c };
      } else
        return this._def.schema._parseAsync({ data: n.data, path: n.path, parent: n }).then((i) => Gt(i) ? Promise.resolve(o.transform(i.value, s)).then((c) => ({ status: r.value, value: c })) : i);
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
    return this._getType(e) === w.undefined ? pe(void 0) : this._def.innerType._parse(e);
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
class ot extends O {
  _parse(e) {
    return this._getType(e) === w.null ? pe(null) : this._def.innerType._parse(e);
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
class tr extends O {
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
tr.create = (t, e) => new tr({
  innerType: t,
  typeName: C.ZodDefault,
  defaultValue: typeof e.default == "function" ? e.default : () => e.default,
  ...N(e)
});
class rr extends O {
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
    return Vt(o) ? o.then((s) => ({
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
rr.create = (t, e) => new rr({
  innerType: t,
  typeName: C.ZodCatch,
  catchValue: typeof e.catch == "function" ? e.catch : () => e.catch,
  ...N(e)
});
class Mr extends O {
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
Mr.create = (t) => new Mr({
  typeName: C.ZodNaN,
  ...N(t)
});
const Bc = Symbol("zod_brand");
class Dn extends O {
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
class lr extends O {
  _parse(e) {
    const { status: r, ctx: n } = this._processInputParams(e);
    if (n.common.async)
      return (async () => {
        const s = await this._def.in._parseAsync({
          data: n.data,
          path: n.path,
          parent: n
        });
        return s.status === "aborted" ? R : s.status === "dirty" ? (r.dirty(), xt(s.value)) : this._def.out._parseAsync({
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
    return new lr({
      in: e,
      out: r,
      typeName: C.ZodPipeline
    });
  }
}
class nr extends O {
  _parse(e) {
    const r = this._def.innerType._parse(e), n = (o) => (Gt(o) && (o.value = Object.freeze(o.value)), o);
    return Vt(r) ? r.then((o) => n(o)) : n(r);
  }
  unwrap() {
    return this._def.innerType;
  }
}
nr.create = (t, e) => new nr({
  innerType: t,
  typeName: C.ZodReadonly,
  ...N(e)
});
function Fs(t, e = {}, r) {
  return t ? $t.create().superRefine((n, o) => {
    var s, i;
    if (!t(n)) {
      const c = typeof e == "function" ? e(n) : typeof e == "string" ? { message: e } : e, l = (i = (s = c.fatal) !== null && s !== void 0 ? s : r) !== null && i !== void 0 ? i : !0, u = typeof c == "string" ? { message: c } : c;
      o.addIssue({ code: "custom", ...u, fatal: l });
    }
  }) : $t.create();
}
const Gc = {
  object: G.lazycreate
};
var C;
(function(t) {
  t.ZodString = "ZodString", t.ZodNumber = "ZodNumber", t.ZodNaN = "ZodNaN", t.ZodBigInt = "ZodBigInt", t.ZodBoolean = "ZodBoolean", t.ZodDate = "ZodDate", t.ZodSymbol = "ZodSymbol", t.ZodUndefined = "ZodUndefined", t.ZodNull = "ZodNull", t.ZodAny = "ZodAny", t.ZodUnknown = "ZodUnknown", t.ZodNever = "ZodNever", t.ZodVoid = "ZodVoid", t.ZodArray = "ZodArray", t.ZodObject = "ZodObject", t.ZodUnion = "ZodUnion", t.ZodDiscriminatedUnion = "ZodDiscriminatedUnion", t.ZodIntersection = "ZodIntersection", t.ZodTuple = "ZodTuple", t.ZodRecord = "ZodRecord", t.ZodMap = "ZodMap", t.ZodSet = "ZodSet", t.ZodFunction = "ZodFunction", t.ZodLazy = "ZodLazy", t.ZodLiteral = "ZodLiteral", t.ZodEnum = "ZodEnum", t.ZodEffects = "ZodEffects", t.ZodNativeEnum = "ZodNativeEnum", t.ZodOptional = "ZodOptional", t.ZodNullable = "ZodNullable", t.ZodDefault = "ZodDefault", t.ZodCatch = "ZodCatch", t.ZodPromise = "ZodPromise", t.ZodBranded = "ZodBranded", t.ZodPipeline = "ZodPipeline", t.ZodReadonly = "ZodReadonly";
})(C || (C = {}));
const Vc = (t, e = {
  message: `Input not instance of ${t.name}`
}) => Fs((r) => r instanceof t, e), Ds = Ce.create, Us = tt.create, Hc = Mr.create, Wc = rt.create, js = Ht.create, qc = mt.create, Kc = $r.create, Yc = Wt.create, Jc = qt.create, Xc = $t.create, Qc = pt.create, el = We.create, tl = Nr.create, rl = $e.create, nl = G.create, ol = G.strictCreate, sl = Kt.create, al = qr.create, il = Yt.create, cl = Ue.create, ll = Jt.create, ul = Or.create, dl = gt.create, fl = Tt.create, pl = Xt.create, hl = Qt.create, ml = nt.create, gl = er.create, yl = Nt.create, yo = Ne.create, vl = Fe.create, _l = ot.create, bl = Ne.createWithPreprocess, wl = lr.create, xl = () => Ds().optional(), Sl = () => Us().optional(), El = () => js().optional(), kl = {
  string: (t) => Ce.create({ ...t, coerce: !0 }),
  number: (t) => tt.create({ ...t, coerce: !0 }),
  boolean: (t) => Ht.create({
    ...t,
    coerce: !0
  }),
  bigint: (t) => rt.create({ ...t, coerce: !0 }),
  date: (t) => mt.create({ ...t, coerce: !0 })
}, Pl = R;
var K = /* @__PURE__ */ Object.freeze({
  __proto__: null,
  defaultErrorMap: Rt,
  setErrorMap: Pc,
  getErrorMap: Ir,
  makeIssue: Cr,
  EMPTY_PATH: Tc,
  addIssueToContext: b,
  ParseStatus: se,
  INVALID: R,
  DIRTY: xt,
  OK: pe,
  isAborted: hn,
  isDirty: mn,
  isValid: Gt,
  isAsync: Vt,
  get util() {
    return D;
  },
  get objectUtil() {
    return pn;
  },
  ZodParsedType: w,
  getParsedType: Je,
  ZodType: O,
  datetimeRegex: Ms,
  ZodString: Ce,
  ZodNumber: tt,
  ZodBigInt: rt,
  ZodBoolean: Ht,
  ZodDate: mt,
  ZodSymbol: $r,
  ZodUndefined: Wt,
  ZodNull: qt,
  ZodAny: $t,
  ZodUnknown: pt,
  ZodNever: We,
  ZodVoid: Nr,
  ZodArray: $e,
  ZodObject: G,
  ZodUnion: Kt,
  ZodDiscriminatedUnion: qr,
  ZodIntersection: Yt,
  ZodTuple: Ue,
  ZodRecord: Jt,
  ZodMap: Or,
  ZodSet: gt,
  ZodFunction: Tt,
  ZodLazy: Xt,
  ZodLiteral: Qt,
  ZodEnum: nt,
  ZodNativeEnum: er,
  ZodPromise: Nt,
  ZodEffects: Ne,
  ZodTransformer: Ne,
  ZodOptional: Fe,
  ZodNullable: ot,
  ZodDefault: tr,
  ZodCatch: rr,
  ZodNaN: Mr,
  BRAND: Bc,
  ZodBranded: Dn,
  ZodPipeline: lr,
  ZodReadonly: nr,
  custom: Fs,
  Schema: O,
  ZodSchema: O,
  late: Gc,
  get ZodFirstPartyTypeKind() {
    return C;
  },
  coerce: kl,
  any: Xc,
  array: rl,
  bigint: Wc,
  boolean: js,
  date: qc,
  discriminatedUnion: al,
  effect: yo,
  enum: ml,
  function: fl,
  instanceof: Vc,
  intersection: il,
  lazy: pl,
  literal: hl,
  map: ul,
  nan: Hc,
  nativeEnum: gl,
  never: el,
  null: Jc,
  nullable: _l,
  number: Us,
  object: nl,
  oboolean: El,
  onumber: Sl,
  optional: vl,
  ostring: xl,
  pipeline: wl,
  preprocess: bl,
  promise: yl,
  record: ll,
  set: dl,
  strictObject: ol,
  string: Ds,
  symbol: Kc,
  transformer: yo,
  tuple: cl,
  undefined: Yc,
  union: sl,
  unknown: Qc,
  void: tl,
  NEVER: Pl,
  ZodIssueCode: g,
  quotelessJson: kc,
  ZodError: _e
});
const Tl = K.object({
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
function Zs(t, e) {
  return new URL(e, t).toString();
}
function Al(t) {
  return fetch(t).then((e) => e.json()).then((e) => {
    if (!Tl.safeParse(e).success)
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
const Il = K.object({
  width: K.number().positive(),
  height: K.number().positive()
});
function Cl(t, e, r, n, o) {
  const s = document.createElement("plugin-modal");
  s.setTheme(r);
  const i = 200, c = 200, l = 335, u = 590, d = ((n == null ? void 0 : n.width) ?? l) > window.innerWidth ? window.innerWidth - 290 : (n == null ? void 0 : n.width) ?? l, f = {
    blockStart: 40,
    // To be able to resize the element as expected the position must be absolute from the right.
    // This value is the length of the window minus the width of the element plus the width of the design tab.
    inlineStart: window.innerWidth - d - 290
  };
  s.style.setProperty(
    "--modal-block-start",
    `${f.blockStart}px`
  ), s.style.setProperty(
    "--modal-inline-start",
    `${f.inlineStart}px`
  );
  const h = window.innerHeight - f.blockStart;
  let p = Math.min((n == null ? void 0 : n.width) || l, d), m = Math.min((n == null ? void 0 : n.height) || u, h);
  return p = Math.max(p, i), m = Math.max(m, c), s.setAttribute("title", t), s.setAttribute("iframe-src", e), s.setAttribute("width", String(p)), s.setAttribute("height", String(m)), o && s.setAttribute("allow-downloads", "true"), document.body.appendChild(s), s;
}
const Rl = K.function().args(
  K.string(),
  K.string(),
  K.enum(["dark", "light"]),
  Il.optional(),
  K.boolean().optional()
).implement((t, e, r, n, o) => Cl(t, e, r, n, o));
async function $l(t, e, r, n) {
  let o = await vo(e), s = !1, i = !1, c = null, l = [];
  const u = /* @__PURE__ */ new Set(), d = !!e.permissions.find(
    ($) => $ === "allow:downloads"
  ), f = t.addListener("themechange", ($) => {
    c == null || c.setTheme($);
  }), h = t.addListener("finish", () => {
    _(), t == null || t.removeListener(h);
  });
  let p = [];
  const m = () => {
    L(f), p.forEach(($) => {
      L($);
    }), l = [], p = [];
  }, _ = () => {
    m(), u.forEach(clearTimeout), u.clear(), c && (c.removeEventListener("close", _), c.remove(), c = null), i = !0, r();
  }, S = async () => {
    if (!s) {
      s = !0;
      return;
    }
    m(), o = await vo(e), n(o);
  }, x = ($, j, F) => {
    const J = t.theme, X = Zs(e.host, j);
    (c == null ? void 0 : c.getAttribute("iframe-src")) !== X && (c = Rl($, X, J, F, d), c.setTheme(J), c.addEventListener("close", _, {
      once: !0
    }), c.addEventListener("load", S));
  }, I = ($) => {
    l.push($);
  }, E = ($, j, F) => {
    const J = t.addListener(
      $,
      (...X) => {
        i || j(...X);
      },
      F
    );
    return p.push(J), J;
  }, L = ($) => {
    t.removeListener($);
  };
  return {
    close: _,
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
const Nl = [
  "finish",
  "pagechange",
  "filechange",
  "selectionchange",
  "themechange",
  "shapechange",
  "contentsave"
];
function Ol(t) {
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
        return K.enum(Nl).parse(n), K.function().parse(o), e("content:read"), t.registerListener(n, o, s);
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
function Ml(t) {
  P.hardenIntrinsics();
  const e = Ol(t), r = {
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
async function Ll(t, e, r) {
  const n = async () => {
    try {
      s.evaluate();
    } catch (i) {
      console.error(i), o.close();
    }
  }, o = await $l(
    t,
    e,
    function() {
      s.cleanGlobalThis(), r();
    },
    function() {
      n();
    }
  ), s = Ml(o);
  return n(), {
    plugin: o,
    manifest: e,
    compartment: s
  };
}
let ht = [], yn = null;
function Fl(t) {
  yn = t;
}
const bo = () => {
  ht.forEach((t) => {
    t.plugin.close();
  }), ht = [];
};
window.addEventListener("message", (t) => {
  try {
    for (const e of ht)
      e.plugin.sendMessage(t.data);
  } catch (e) {
    console.error(e);
  }
});
const Dl = async function(t, e) {
  try {
    const r = yn && yn(t.pluginId);
    if (!r)
      return;
    bo();
    const n = await Ll(
      P.harden(r),
      t,
      () => {
        ht = ht.filter((o) => o !== n), e && e();
      }
    );
    ht.push(n);
  } catch (r) {
    bo(), console.error(r);
  }
}, zs = async function(t, e) {
  Dl(t, e);
}, Ul = async function(t) {
  const e = await Al(t);
  zs(e);
}, jl = function(t) {
  const e = ht.find((r) => r.manifest.pluginId === t);
  e && e.plugin.close();
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
    console.log("%c[PLUGINS] Initialize runtime", "color: #008d7c"), Fl(t), wo.ɵcontext = t("TEST"), globalThis.ɵloadPlugin = zs, globalThis.ɵloadPluginByUrl = Ul, globalThis.ɵunloadPlugin = jl;
  } catch (e) {
    console.error(e);
  }
};
//# sourceMappingURL=index.js.map
