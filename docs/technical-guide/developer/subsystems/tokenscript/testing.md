---
title: Testing
desc: The unit tests of Penpot's tokenscript interpreter, how to run the external compliance suite, and where the implementation knowingly differs from it.
---

# Testing tokenscript

The unit tests are in `common/test/common_tests/tokenscript/`. Run them
on the JVM from `common/`:

```bash
clojure -M:dev:test --focus common-tests.tokenscript.interpreter-test
```

and in ClojureScript, which runs all the common tests:

```bash
clojure -M:dev -m shadow.cljs.devtools.cli compile test
node target/tests/test.js
```

## Compliance suite

The cross-runtime compliance suite
(`tokens-studio/tokenscript-compliance-suite`) is not vendored.
`common-tests.tokenscript.compliance-test` runs it when
`TOKENSCRIPT_COMPLIANCE_DIR` points at a checkout, and skips it
otherwise:

```bash
TOKENSCRIPT_COMPLIANCE_DIR=/path/to/compliance-suite \
  clojure -M:dev:test --focus common-tests.tokenscript.compliance-test
```

The color specification files the suite references are read from
`../specifications` next to the suite, or from
`TOKENSCRIPT_SPECIFICATIONS_DIR`.

## Known divergences

The implementation reproduces the TypeScript reference interpreter.
The suite expects a different behaviour in a few cases, listed in
`common-tests.tokenscript.compliance-test/known-divergences`:

- `4 + 2.5 + 3 - 2.5` renders as `7`, the suite expects `7.0`.
- `round(2.5)` is `3` (JavaScript rounding), the suite expects `2`.
- Strings inside lists render without quotes.
- `type(#333)` is `color.hex`, the suite expects `hex`.
- Null colors render as an empty string and null units as `nullpx`.
- Empty lists render as an empty string, the suite expects `[]`.
