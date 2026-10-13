---
title: Tokenscript
desc: How Penpot resolves design token values with the tokenscript interpreter written in Clojure, its architecture, public API, bundled schemas, tests and known divergences.
---

# Tokenscript

Design token values in Penpot are expressions: `{spacing.base} * 2`,
`lighten({color.primary}, 0.2)`, `16px`. They are evaluated by an
interpreter for the tokenscript language that lives in
[common/src/app/common/tokenscript](https://github.com/penpot/penpot/tree/develop/common/src/app/common/tokenscript).

The interpreter is a Clojure port of the TypeScript reference
implementation (`@tokens-studio/tokenscript-interpreter`), which it
replaced. It compiles to both Clojure and ClojureScript, so tokens can be
resolved in the frontend, the backend and the exporter with the same code.

In the workspace it is enabled with the `enable-tokenscript` feature
flag. StyleDictionary (`app.main.data.style-dictionary`) remains the
default resolver until the tokenscript path is considered stable; both
produce the same `:resolved-value` / `:errors` shape.

## Language overview

Token values are parsed in *inline mode*: a single expression, with
unquoted words allowed (`1px solid black`, `http://foo.bar`). Schema
scripts (color conversions, functions) are parsed in *script mode*, which
adds statements:

```
variable c: Color.HSL = {input}.to.hsl();
if (c.l > 0.5) [
  c.l = c.l - 0.1;
];
return c;
```

Values are typed: `Number`, `NumberWithUnit` (`16px`, `50%`), `String`,
`Boolean`, `Color` (with a color space sub type: `Color.Hex`,
`Color.Oklch`, ...), `List`, `Dictionary`, `Token` (a structured token such
as a typography or shadow) and `Null`. References to other tokens use
braces: `{color.primary}`. A reference to a group (`{color}`) yields a
dictionary of the tokens directly below it.

## Architecture

| Namespace | Role |
|---|---|
| `app.common.tokenscript` | Entry point: default config, `evaluate`, `process-tokens`, incremental operations. |
| `app.common.tokenscript.lexer`, `.parser`, `.ast` | Source text to AST. The lexer is a pure function over a state map; the parser is a recursive descent parser. |
| `app.common.tokenscript.interpreter`, `.operations` | Tree walking evaluator. Also executes the scripts registered in the config (color initializers and conversions, unit conversions, dynamic functions). |
| `app.common.tokenscript.symbols` | Runtime values. Scalars are immutable records; lists, dictionaries and tokens keep their content in a volatile because the language mutates them in place (`list.append(x)`). |
| `app.common.tokenscript.config`, `.colors`, `.units`, `.functions`, `.token-types`, `.registry` | Schema registries. A config is an immutable map; schemas are registered with `register-schemas`. Scripts are parsed once at registration. |
| `app.common.tokenscript.processor`, `.token-data` | Token set resolution: dependency graph, structured tokens, group references, cycle detection, incremental updates. |
| `app.common.tokenscript.validation` | Validation of token values against token type schemas. |
| `app.common.tokenscript.errors` | Error codes and messages. Errors are `ex-info` with a data map; the processor stores the same maps as issues. |
| `app.common.tokenscript.bundle` | Generated: the schemas shipped with Penpot. |

### Processor

`process-tokens` takes a map of token name to value (or `{:value :type}`
data) and returns:

```clojure
{:tokens   {"a" #Number 8, "b" #Number 16}   ; symbols, or the original value for failed tokens
 :resolved {"a" ... "b" ...}                   ; symbols or error maps
 :issues   {"c" [{:code :processor/dependency-error ...}]} ; nil when clean
 :graph    {"b" #{"a"} ...}                    ; dependency graph
 :state    ...}                                ; for incremental operations
```

Resolution is a memoized depth first walk: resolving a token first
resolves everything it references, so the evaluation order comes out of
the recursion and cycles are detected with the set of tokens being
resolved. Errors never throw; a token depending on a failed token gets a
`:processor/dependency-error` with the chain and the root cause.

`update-token`, `create-token`, `delete-token` and `resolve-value` work
on the returned `:state`. Updates re-resolve only the tokens that can
observe the change (dependents, tokens under a changed group prefix,
tokens that previously had issues) and keep the other values.

Caches that live across builds, all keyed by inputs that fully
determine the result:

- Parsed token values, keyed by the value text
  (`processor/cached-parse`, up to 50 000 entries). Every build re-reads
  all tokens, but on a set or theme switch most values were parsed before.
- URI resolution and conversion paths between schemas, per registry
  instance (`registry/memoized`). Without it every color conversion ran a
  breadth first search over the schema graph.
- Parsed schema URIs (`schema-uri/parse-uri`).
- The evaluated inline constants, per config.

`return` in schema scripts is a value (`interpreter/Return`) that
statement lists and loops pass up, not an exception, so script functions
do not pay for a stack trace on every call.

### Frontend integration

`app.main.data.tokenscript/resolve-tokens` resolves the workspace tokens
and converts the results to the values shape attributes use: colors as
hex strings (`#rrggbbaa` with alpha), `rem` in pixels, `%` as fractions,
composites as maps with keyword keys. It also applies the Penpot level
checks per token type (opacity range, stroke width, text case, font
weight, composite shapes) and maps resolver issues to the token error
codes used by the UI (`:error.style-dictionary/missing-reference`,
`:error.token/circular-reference`, ...).

## Bundled schemas

The schemas shipped with Penpot are JSON files with tokenscript scripts
in
[common/resources/tokenscript/schemas](https://github.com/penpot/penpot/tree/develop/common/resources/tokenscript/schemas):

| Directory | Contents | URI |
|---|---|---|
| `types/<name>/` | Color spaces: sRGB, HSL, OKLCH, P3, ... | `/api/v1/core/<name>/0/` |
| `functions/<name>/` | Functions: `lighten`, `darken`, `saturate`, `desaturate`, `mix`, `invert`, and the CSS color functions `rgb`, `rgba`, `hsl`, `hsla` | `/api/v1/function/<name>/0/` |
| `constants/<name>/` | CSS named colors | `/api/v1/constants/<name>/0/` |

Each directory holds a `schema.json` that references its scripts by
relative path (`"script": "./initializer.tokenscript"`). The color
spaces, the color functions and the named colors come from
`@tokens-studio/tokenscript-schemas` 0.4.0 (MPL-2.0) and are kept
unchanged, so they can be compared with that package; the `css-*`
functions are Penpot's.

`app.common.tokenscript.bundle` is generated from these files with the
bundler `app.common.tokenscript.bundler`, which inlines the
scripts and fails when a script does not parse or a schema references
one that is not bundled:

```bash
cd common
clojure -M:dev -m app.common.tokenscript.bundler          # regenerate
clojure -M:dev -m app.common.tokenscript.bundler --check  # fail when stale
```

`common-tests.tokenscript.bundle-test` also fails when the bundle is
stale.

Schemas are identified by path URIs and never fetched. URIs with a host,
as other tokenscript tools write them
(`https://<host>/api/v1/core/rgb-color/0/`), are normalized to the path
form when they are registered or looked up, so external schemas still
connect to the bundled ones.

Additional schemas (for example user defined functions in the future)
are plain maps registered with `app.common.tokenscript/register-schemas`.

### CSS color functions

`rgb`, `rgba`, `hsl` and `hsla` are schema functions written in
tokenscript. They take precedence over the `rgb`/`hsl` initializers of
the color spaces and accept the CSS syntaxes:

```
rgb(255, 0, 0)    rgb(255 0 0)    rgb(100%, 0%, 0%)    rgba(255, 0, 0, 50%)
hsl(0, 100%, 50%)    hsl(120deg 100% 50%)    hsla({color.primary}, 0.5)
```

RGB channels are clamped to 0-255 and rounded, alpha values are clamped
to 0-1 and an alpha of 1 is opaque. HSL saturation and lightness given
as plain numbers are fractions (`hsl(0, 1, 0.5)`, the tokenscript HSL
scale) unless one of them is above 1, in which case they are CSS
percentages (`hsl(0, 100, 50)`).

The slash alpha syntax (`rgb(255 0 0 / 50%)`) is not supported: in
tokenscript the slash is a division, evaluated before the function sees
its arguments.

## Tests

Unit tests live in `common/test/common_tests/tokenscript/`. The external
compliance suite (`tokens-studio/tokenscript-compliance-suite`, not
vendored) runs when `TOKENSCRIPT_COMPLIANCE_DIR` points at a checkout:

```bash
TOKENSCRIPT_COMPLIANCE_DIR=/path/to/compliance-suite clojure -M:dev:test --focus common-tests.tokenscript.compliance-test
```

The color specification files referenced by the suite are expected in
`../specifications` next to it (or `TOKENSCRIPT_SPECIFICATIONS_DIR`).

### Known divergences from the compliance suite

The implementation reproduces the TypeScript reference interpreter
exactly (0 differences over the 238 suite cases). The suite itself
expects a different behaviour in a few cases, listed in
`common-tests.tokenscript.compliance-test/known-divergences`:

- `4 + 2.5 + 3 - 2.5` renders as `7`, the suite expects `7.0`.
- `round(2.5)` is `3` (JavaScript rounding), the suite expects `2`.
- Strings inside lists render without quotes.
- `type(#333)` is `color.hex`, the suite expects `hex`.
- Null colors render as an empty string and null units as `nullpx`.
- Empty lists render as an empty string, the suite expects `[]`.
