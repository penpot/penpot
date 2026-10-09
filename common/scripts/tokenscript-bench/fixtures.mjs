// Synthetic token sets for the tokenscript benchmarks.
//
// Every fixture is a flat map of token name -> {type, value}, the shape
// penpot hands to the resolvers. Names never collide with group prefixes
// (no `a` next to `a.b`) so the same data can be fed to StyleDictionary as
// a nested tree.

// Deterministic PRNG (mulberry32), so every run benchmarks the same data.
function rng(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const hex = (rand) =>
  `#${Math.floor(rand() * 0xffffff)
    .toString(16)
    .padStart(6, "0")}`;

const pick = (rand, xs) => xs[Math.floor(rand() * xs.length)];

// Tokens are grouped 50 per group so names look like real token paths.
const group = (i) => Math.floor(i / 50);

function makeTokens(size, seed, fill) {
  const rand = rng(seed);
  const tokens = {};
  const add = (name, type, value) => {
    tokens[name] = { type, value };
    return name;
  };
  fill({ size, rand, add });
  return tokens;
}

// Profiles ------------------------------------------------------------------

export const profiles = {
  // Literal values only: half hex colors, half spacing numbers.
  flat: {
    description: "literal values only (hex colors and numbers)",
    generate: (size) =>
      makeTokens(size, 1, ({ size, rand, add }) => {
        for (let i = 0; i < size; i++) {
          if (i % 2 === 0)
            add(`palette.c${group(i)}.t${i}`, "color", hex(rand));
          else
            add(
              `space.s${group(i)}.t${i}`,
              "spacing",
              String(Math.floor(rand() * 64)),
            );
        }
      }),
  },

  // 20% primitives, the rest four levels of aliases on top of them.
  aliases: {
    description: "20% hex primitives, 80% reference chains up to 4 levels deep",
    generate: (size) =>
      makeTokens(size, 2, ({ size, rand, add }) => {
        const primitives = Math.max(1, Math.ceil(size * 0.2));
        let previous = [];
        for (let i = 0; i < primitives; i++)
          previous.push(add(`base.c${group(i)}.t${i}`, "color", hex(rand)));
        const rest = size - primitives;
        const perLevel = Math.ceil(rest / 4);
        let made = 0;
        for (let level = 1; level <= 4 && made < rest; level++) {
          const current = [];
          for (let i = 0; i < perLevel && made < rest; i++, made++) {
            current.push(
              add(
                `alias.l${level}.g${group(i)}.t${i}`,
                "color",
                `{${pick(rand, previous)}}`,
              ),
            );
          }
          previous = current;
        }
      }),
  },

  // Unitless dimension primitives plus arithmetic on references.
  math: {
    description: "10% number primitives, 90% arithmetic over references",
    generate: (size) =>
      makeTokens(size, 3, ({ size, rand, add }) => {
        const primitives = Math.max(2, Math.ceil(size * 0.1));
        const base = [];
        for (let i = 0; i < primitives; i++)
          base.push(
            add(
              `dim.base.g${group(i)}.t${i}`,
              "dimensions",
              String(4 * (i + 1)),
            ),
          );
        const forms = [
          (a) => `{${a}} * 2`,
          (a, b) => `({${a}} + {${b}}) / 2`,
          (a) => `{${a}} * 1.5 + 4`,
          (a, b) => `{${a}} - {${b}} / 4`,
        ];
        for (let i = 0; i < size - primitives; i++) {
          const f = pick(rand, forms);
          add(
            `dim.calc.g${group(i)}.t${i}`,
            "dimensions",
            f(pick(rand, base), pick(rand, base)),
          );
        }
      }),
  },

  // Tokenscript color functions. StyleDictionary has no equivalent.
  "color-fns": {
    description:
      "20% hex primitives, 80% lighten/darken/saturate/desaturate/invert/mix",
    engines: { sd: false },
    generate: (size) =>
      makeTokens(size, 4, ({ size, rand, add }) => {
        const primitives = Math.max(2, Math.ceil(size * 0.2));
        const base = [];
        for (let i = 0; i < primitives; i++)
          base.push(add(`brand.c${group(i)}.t${i}`, "color", hex(rand)));
        const forms = [
          (a) => `lighten({${a}}, 0.2)`,
          (a) => `darken({${a}}, 0.3)`,
          (a) => `saturate({${a}}, 0.1)`,
          (a) => `desaturate({${a}}, 0.1)`,
          (a) => `invert({${a}})`,
          (a, b) => `mix({${a}}, {${b}}, 0.5)`,
        ];
        for (let i = 0; i < size - primitives; i++) {
          const f = pick(rand, forms);
          add(
            `ui.tone.g${group(i)}.t${i}`,
            "color",
            f(pick(rand, base), pick(rand, base)),
          );
        }
      }),
  },

  // Typography and shadow composites referencing primitives.
  composite: {
    description:
      "40% primitives, 60% typography and shadow composites referencing them",
    generate: (size) =>
      makeTokens(size, 5, ({ size, rand, add }) => {
        const primitives = Math.max(10, Math.ceil(size * 0.4));
        const per = Math.ceil(primitives / 5);
        const families = [];
        const sizes = [];
        const weights = [];
        const colors = [];
        const dims = [];
        const fontNames = [
          "Inter",
          "Roboto",
          "Source Sans Pro",
          "Work Sans",
          "IBM Plex Sans",
        ];
        for (let i = 0; i < per; i++) {
          families.push(
            add(`font.family.t${i}`, "font-family", pick(rand, fontNames)),
          );
          sizes.push(
            add(`font.size.t${i}`, "font-size", String(10 + (i % 40))),
          );
          weights.push(
            add(
              `font.weight.t${i}`,
              "font-weight",
              String(100 * (1 + (i % 9))),
            ),
          );
          colors.push(add(`shadow.color.t${i}`, "color", hex(rand)));
          dims.push(add(`shadow.dim.t${i}`, "dimensions", String(i % 24)));
        }
        const composites = Math.max(0, size - per * 5);
        for (let i = 0; i < composites; i++) {
          if (i % 2 === 0) {
            add(`type.style.g${group(i)}.t${i}`, "typography", {
              "font-family": `{${pick(rand, families)}}`,
              "font-size": `{${pick(rand, sizes)}}`,
              "font-weight": `{${pick(rand, weights)}}`,
              "line-height": "1.5",
              "letter-spacing": "0",
            });
          } else {
            add(`elevation.e${group(i)}.t${i}`, "shadow", [
              {
                "offset-x": "0",
                "offset-y": `{${pick(rand, dims)}}`,
                blur: `{${pick(rand, dims)}}`,
                spread: "0",
                color: `{${pick(rand, colors)}}`,
                inset: false,
              },
            ]);
          }
        }
      }),
  },

  // Proportions of a real design system (penpot's Tokens starter kit):
  // half hex colors, ~40% references, ~8% numbers, ~2% color functions.
  mixed: {
    description:
      "starter-kit proportions: 50% hex, 40% refs, 8% numbers, 2% color functions",
    generate: (size) =>
      makeTokens(size, 6, ({ size, rand, add }) => {
        const colors = [];
        const numbers = [];
        for (let i = 0; i < size; i++) {
          const r = i < 4 ? 0 : rand();
          if (r < 0.5 || colors.length === 0) {
            colors.push(
              add(`color.base.g${group(i)}.t${i}`, "color", hex(rand)),
            );
          } else if (r < 0.58 || numbers.length === 0) {
            numbers.push(
              add(
                `size.base.g${group(i)}.t${i}`,
                "spacing",
                String(Math.floor(rand() * 96)),
              ),
            );
          } else if (r < 0.98) {
            if (rand() < 0.75)
              add(
                `color.alias.g${group(i)}.t${i}`,
                "color",
                `{${pick(rand, colors)}}`,
              );
            else
              add(
                `size.alias.g${group(i)}.t${i}`,
                "spacing",
                `{${pick(rand, numbers)}}`,
              );
          } else {
            add(
              `color.fn.g${group(i)}.t${i}`,
              "color",
              `lighten({${pick(rand, colors)}}, 0.1)`,
            );
          }
        }
      }),
  },
};

// Update target ---------------------------------------------------------------

const refPattern = /\{([^{}]+)\}/g;

function referencesOf(value) {
  const text = typeof value === "string" ? value : JSON.stringify(value);
  return [...text.matchAll(refPattern)].map((m) => m[1]);
}

// Number of tokens that (transitively) depend on each token.
export function dependentCounts(tokens) {
  const reverse = new Map();
  for (const [name, { value }] of Object.entries(tokens)) {
    for (const ref of referencesOf(value)) {
      if (!reverse.has(ref)) reverse.set(ref, []);
      reverse.get(ref).push(name);
    }
  }
  const counts = new Map();
  const count = (name) => {
    if (counts.has(name)) return counts.get(name);
    counts.set(name, 0);
    const seen = new Set();
    const stack = [...(reverse.get(name) ?? [])];
    while (stack.length) {
      const n = stack.pop();
      if (seen.has(n)) continue;
      seen.add(n);
      for (const d of reverse.get(n) ?? []) stack.push(d);
    }
    counts.set(name, seen.size);
    return seen.size;
  };
  for (const name of Object.keys(tokens)) count(name);
  return counts;
}

function alternateValue(value) {
  if (/^#[0-9a-f]{6}$/i.test(value)) {
    const n = (parseInt(value.slice(1), 16) + 0x101010) % 0x1000000;
    return `#${n.toString(16).padStart(6, "0")}`;
  }
  if (/^-?\d+(\.\d+)?$/.test(value)) return String(Number(value) + 1);
  return null;
}

// The literal token with the most dependents: updating it exercises the
// incremental re-resolution of the largest part of the graph.
export function pickUpdate(tokens) {
  const counts = dependentCounts(tokens);
  let best = null;
  for (const [name, token] of Object.entries(tokens)) {
    if (typeof token.value !== "string" || referencesOf(token.value).length)
      continue;
    const alt = alternateValue(token.value);
    if (alt === null) continue;
    const c = counts.get(name) ?? 0;
    if (!best || c > best.dependents)
      best = {
        name,
        type: token.type,
        values: [token.value, alt],
        dependents: c,
      };
  }
  return best;
}

// Fixtures ------------------------------------------------------------------

export function makeFixture(id, profile, size, tokens, extra = {}) {
  return {
    id,
    profile,
    size: Object.keys(tokens).length,
    requestedSize: size,
    tokens,
    update: pickUpdate(tokens),
    engines: extra.engines ?? {},
    description: extra.description ?? "",
  };
}

export function syntheticFixtures(profileNames, sizes) {
  const out = [];
  for (const p of profileNames) {
    const profile = profiles[p];
    if (!profile)
      throw new Error(
        `unknown profile ${p} (known: ${Object.keys(profiles).join(", ")})`,
      );
    for (const size of sizes) {
      out.push(
        makeFixture(`${p}-${size}`, p, size, profile.generate(size), profile),
      );
    }
  }
  return out;
}

// Real token data: a JSON object of name -> {type, value} (or {$type, $value}).
export function inputFixture(id, json) {
  const tokens = {};
  for (const [name, t] of Object.entries(json)) {
    const value = t.value ?? t.$value;
    const type = t.type ?? t.$type;
    if (value === undefined || value === null) continue;
    tokens[name] = { type, value };
  }
  return makeFixture(id, id, Object.keys(tokens).length, tokens, {
    description: "real token data",
  });
}
