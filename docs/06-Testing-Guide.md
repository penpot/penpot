# Testing guide #

## Backend / Common

You can run the tests directly with:

```bash
~/penpot/backend$ clojure -M:dev:tests
```

Alternatively, you can run them from a REPL. First starting a REPL.

```bash
~/penpot/backend$ scripts/repl
```

And then:

```bash
user=> (run-tests)
user=> (run-tests 'namespace)
user=> (run-tests 'namespace/test)
```

## Frontend

Frontend tests have to be compiled first, and then run with node.

```bash
npx shadow-cljs compile tests && node target/tests.js
```

Or run the watch (that automatically runs the test):

```bash
npx shadow-cljs watch tests
```

## Linter

We can execute the linter for the whole codebase with the following command:

```bash
clj-kondo --lint common:backend/src:frontend/src
```
