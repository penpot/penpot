---
title: Tokenscript
desc: How Penpot resolves design token values with tokenscript, the expression language for design tokens, and where its Clojure implementation lives.
---

# Tokenscript

Design token values in Penpot are tokenscript expressions:
`{spacing.base} * 2`, `lighten({color.primary}, 0.2)`, `16px`. This page
maps the implementation; the pages below describe the language, the
schemas and the resolver.

The interpreter lives in
[common/src/app/common/tokenscript](https://github.com/penpot/penpot/tree/develop/common/src/app/common/tokenscript).
It is a Clojure port of the TypeScript reference implementation
(`@tokens-studio/tokenscript-interpreter`) and compiles to Clojure and
ClojureScript, so the frontend, the backend and the exporter resolve
tokens with the same code.

In the workspace it is enabled with the `enable-tokenscript` flag.
Without it StyleDictionary (`app.main.data.style-dictionary`) resolves
the tokens; both produce the same `:resolved-value` / `:errors` shape.

## Pages

- [Language](/technical-guide/developer/subsystems/tokenscript/language/) — values, operators, units, statements and methods
- [Built-in functions](/technical-guide/developer/subsystems/tokenscript/functions/) — the functions available without schemas
- [Schemas](/technical-guide/developer/subsystems/tokenscript/schemas/) — color spaces, functions, units and constants, and the schemas Penpot ships
- [Token validation](/technical-guide/developer/subsystems/tokenscript/validation/) — token type schemas and their validation scripts
- [Resolver](/technical-guide/developer/subsystems/tokenscript/resolver/) — resolving token sets, incremental updates and previews
- [Errors and issues](/technical-guide/developer/subsystems/tokenscript/errors/) — error maps, error codes and the issues of a token
- [Testing](/technical-guide/developer/subsystems/tokenscript/testing/) — unit tests, the compliance suite and its known divergences

## Usage

`app.common.tokenscript` resolves token sets with the bundled schemas:

```clojure
(require '[app.common.tokenscript :as ts]
         '[app.common.tokenscript.processor :as processor])

(def result
  (ts/build {"base"   {:value "8"}
             "double" {:value "{base} * 2px"}}))

(processor/tokens->strings result)
;; => {"base" "8", "double" "16px"}
```

| Function | Does |
|---|---|
| `default-config` | The config with the bundled schemas, built once |
| `build` | Resolves token data (`{name {:value .. :type ..}}`) or an ordered vector of token sets |
| `color->hex` | Converts any color to a hex color, keeping alpha |

Anything else is used from the implementation namespaces directly.

## Architecture

| Namespace | Role |
|---|---|
| `app.common.tokenscript` | Facade: resolves token sets with the bundled schemas. |
| `.lexer`, `.parser`, `.ast` | Source text to AST. The lexer is a pure function over a `LexerState` record; the parser is a recursive descent parser. |
| `.interpreter`, `.operations` | Tree walking evaluator. Also runs the scripts of the registered schemas (color initializers and conversions, unit conversions, schema functions). |
| `.symbols` | Runtime values. Scalars are immutable records; lists, dictionaries and tokens keep their content in a volatile because the language mutates them in place (`list.append(x)`). |
| `.functions` | Built-in functions and the registry of schema functions. |
| `.config`, `.registry`, `.colors`, `.units`, `.token-types` | Schema registries. A config is an immutable `Config` record holding one registry record per kind, each implementing `registry/ISchemaRegistry`. |
| `.schema-uri` | Parsing, normalizing and version resolution of schema URIs. |
| `.schemas` | The schemas shipped with Penpot, read from `common/resources`. |
| `.processor`, `.token-data` | The resolver: token sets, dependency resolution, structured tokens, incremental updates. |
| `.validation` | Validation of resolved values against token type schemas. |
| `.errors` | Error codes, messages and error maps. |
| `.util` | JavaScript compatible number formatting and string handling, and case insensitive name tables. |

### Frontend integration

`app.main.data.tokenscript/resolve-tokens` resolves the workspace tokens
and converts the results to the values shape attributes use: colors as
hex strings (`#rrggbbaa` with alpha), `rem` in pixels, `%` as fractions,
composites as maps with keyword keys. It also applies the Penpot level
checks per token type (opacity range, stroke width, text case, font
weight, composite shapes) and maps resolver issues to the token error
codes used by the UI (`:error.style-dictionary/missing-reference`,
`:error.token/circular-reference`, ...).
