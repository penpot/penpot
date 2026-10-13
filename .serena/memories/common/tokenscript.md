# Tokenscript (token value interpreter)

`app.common.tokenscript.*`: Clojure/ClojureScript port of the TypeScript `@tokens-studio/tokenscript-interpreter`. Resolves token value expressions (`{a} * 2px`, `lighten({c}, 0.2)`). Replaced the npm TS interpreter (`@penpot/tokenscript` package removed). Used in the workspace behind the `enable-tokenscript` flag (`(contains? cf/flags :tokenscript)`); StyleDictionary (`app.main.data.style-dictionary`) stays the default until stable, both return the same `:resolved-value`/`:errors` shape.

## Map

- Entry point `app.common.tokenscript`: `default-config` (bundled schemas), `evaluate`, `process-tokens`, `build`, `update-token`/`create-token`/`delete-token`/`resolve-value` on the result `:state`.
- `lexer` (pure `LexerState` record, `next-token` -> `[token state]`), `parser` (volatile `ParserState` with a lookahead buffer, `parse text {:mode :inline|:script}`), `ast` (maps with `:node`). Parsing is hot: keep lexer/parser changes allocation free and check JVM/node parity with `tokenscript-bench parity`.
- `interpreter` (eval + execution of schema scripts: color conversions, unit conversions, dynamic functions), `operations` (math with unit conversion passed as a fn to avoid the cycle).
- `symbols`: records implementing protocols: `ISymbol` (type, `value`, string/data conversion, copy, equality), `IScriptable` (method table, attributes), `ISized`, `IListSymbol`, `IDictionarySymbol`. Add behaviour by implementing the protocol in the record, not with `cond` on type predicates. Scalars immutable; `ListSymbol`/`DictionarySymbol`/`TokenSymbol` hold a volatile (language mutates containers in place). `TokenSymbol` implements both container protocols and checks its content (list or record). Script methods are per type tables of name -> `{:fn protocol-fn :args n|[min max]}` used by `call-method`. Read with `sym/value`, convert with `sym/->data`, render with `sym/to-string sym config` (dynamic colors need config for channel order). Dictionaries use `linked.map` (insertion order matters).
- Config is an immutable map of registries (`:colors :units :functions :tokens :inline-constants`); `registry` has version resolution and BFS conversion paths. Schemas are keywordized maps; scripts parsed once at registration.
- `processor`: memoized DFS resolution; cycle detection via `:visiting`; every cycle member gets `:processor/circular-dependency`, dependents get `:processor/dependency-error` with `:chain`/`:root-cause`. Structured tokens expand string leaves to virtual sub-field tokens (`shadow[0].blur`); group refs `{color}` build a dictionary of direct expression children; `{dict.key}` reaches one level into dictionary tokens.
- `errors`: `ex-info` with data `{:type :tokenscript/error :kind :code :hint :message :data}`; same maps are the issue values. `err/code->compliance-code` maps `:interpreter/unknown-reference` -> `INT_UNKNOWN_REFERENCE`.
- `bundle.cljc` is GENERATED from `common/resources/tokenscript/schemas/{types,functions,constants}/<name>/schema.json` + `.tokenscript` files by `clojure -M:dev -m tokenscript-bundle` (`common/dev/tokenscript_bundle.clj`); do not hand edit, `bundle-test` fails when stale.
- Schema URIs are path URIs (`/api/v1/core/rgb-color/0/`), never fetched; `schema-uri/normalize` strips hosts and the registries normalize on register and lookup.
- CSS color syntax (`rgb(255 0 0)`, `hsl(0, 100%, 50%)`, `rgba({c}, 0.5)`) is handled by the Penpot schema functions `functions/css-rgb|css-rgba|css-hsl|css-hsla`, which shadow the color initializers. The CSS slash alpha (`rgb(0 0 128 / 50%)`) is parsed as a division and gives wrong channels.

## Invariants / gotchas

- Numbers are doubles everywhere; format with `util/num->str` (JS `String(n)` semantics). Compare numbers in tests with `==` or via strings.
- Behaviour must match the TS reference, not intuition: `1rem + 1px` -> `17px` (largest magnitude unit wins), `(1500ms + 500ms)s` -> `2000s` (relabel, no conversion), `round(2.5)` -> `3`, `type(#333)` -> `color.hex`, methods with no declared args accept any args.
- Inline mode lexes `a.b` as one greedy string: `{c}.to.hsl()` only works in script mode. `//` starts a comment in script mode.
- Inline constants (CSS color names) are injected by the processor only, not by `evaluate`.
- Frontend adapter `app.main.data.tokenscript/resolve-tokens` returns `:resolved-value` as plain data (hex strings, px numbers, keyword maps) and `:errors` as `wte` error maps; it also applies the per type checks (opacity range, stroke width, text case, font weight, composite shapes, number-too-large) that StyleDictionary used to do. `nil` values get `:error.token/empty-input`.

## Tests

- `common/test/common_tests/tokenscript/*` (lexer, parser, interpreter, symbols, processor). Compliance suite: `TOKENSCRIPT_COMPLIANCE_DIR=<checkout> clojure -M:dev:test --focus common-tests.tokenscript.compliance-test`; known divergences listed in that test.
- Docs: `docs/technical-guide/developer/subsystems/tokenscript.md`.
