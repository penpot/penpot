# Clojure Idioms (verified)

Behaviors confirmed against the language/stdlib — do not re-derive from
assumption; a wrong assumption here already cost a review round.

- `int?` is NOT 32-bit-only: true for `Long`, `Integer`, `Short`,
  `Byte` (fixed-precision integers). Clojure integer literals are
  `Long`, so `(int? 5000)` is true.
- `integer?` is the general integer predicate; prefer it when any
  integer kind must match, `int?` only when fixed precision is meant.
