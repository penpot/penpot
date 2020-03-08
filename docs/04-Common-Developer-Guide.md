# Common's guide #

This section intends to have articles that related to both frontend
and backend, such as: code style hints, architecture dicisions, etc...


## Assertions ##

UXBOX source code has this types of assertions:

**assert**: just using the clojure builtin `assert` macro.

Example:

```clojure
(assert (number? 3) "optional message")
```

This asserts are only executed on development mode. On production
environment all assets like this will be ignored by runtime.

**spec/assert**: using the `uxbox.common.spec/assert` macro.

Also, if you are using clojure.spec, you have the spec based
`clojure.spec.alpha/assert` macro. In the same way as the
`clojure.core/assert`, on production environment this asserts will be
removed by the compiler/runtime.

Example:

````clojure
(require '[clojure.spec.alpha :as s]
         '[uxbox.common.spec :as us])

(s/def ::number number?)

(us/assert ::number 3)
```

In the same way as the `assert` macro, this performs the spec
assertion only on development build. On production this code will
completely removed.

**spec/verify**: An assertion type that is executed always.

Example:

```clojure
(require '[uxbox.common.spec :as us])

(us/verify ::number 3)
```

This macro enables you have assetions on production code.

**Why don't use the `clojure.spec.alpha/assert` instead of the `uxbox.common.spec/assert`?**

The uxbox variant does not peforms additional runtime checks for know
if asserts are disabled in "runtime". As a result it generates much
simplier code at development and production builds.

