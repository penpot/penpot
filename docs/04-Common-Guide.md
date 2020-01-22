# Common's guide #

This section intends to have articles that related to both frontend
and backend, such as: code style hints, architecture dicisions, etc...


## Assertions ##

UXBOX source code has 3 types of assertions that can be used: simple,
spec, and dev-spec.

The simple assertion consists in using the clojure builting `assert`
macro. This asserts are only executed on development mode. On
production environment all assets like this will be ignored by
runtime.

Example:

```clojure
(assert (number? 3) "optional message")
```

Also, if you are using clojure.spec, you have the spec based
`clojure.spec.alpha/assert` macro. In the same way as the
`clojure.core/assert`, on production environment this asserts will be
removed by the compiler/runtime.

Example:

````clojure
(require '[clojure.spec.alpha :as s])

(s/def ::number number?)

(s/assert ::number 3)
```

And finally, for cases when you want a permanent assert (including in
production code), you need to use `uxbox.common.spec/assert` macro. It
has the same call signature as `clojure.spec.alpha/assert`.

Example:

```clojure
(require '[uxbox.common.spec :as us])

(us/assert ::number 3)
```

This macro enables you have assetions on production code.


