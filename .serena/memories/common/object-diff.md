# Object diffs (`app.common.data.object-diff`)

`diff` and `apply-diff` replace `app.common.data/map-diff` when a caller needs to reach a single element inside a vector or to tell a missing key from a key holding nil. `map-diff` stays as is for consumers that compare whole attribute values (e.g. test helpers), and its format is **not** accepted by `apply-diff`: the two formats are unrelated.

This is the only diff format in the codebase. History: it replaced a tuple-only encoding after a comparison against a trie and a flat grouped encoding; both alternatives, their tests and the benchmark that measured them were removed, so do not look for `object-diff-tree`, `object-diff-grouped` or `common/dev/bench.cljc`.

## Format

A diff is a vector of elements applied in order, of two kinds.

An **operation** replaces, inserts or deletes one value, and carries the full path:

```clojure
[[:set path new]    ; replace the value at path
 [:add path new]    ; insert where no key or index exists
 [:del path]]       ; remove the value at path
```

A **group** collects several operations that share one container path, and stores that path once:

```clojure
{:path [segment ...]          ; path to the container, [] for the root
 :set  [[segment value] ...]  ; replace existing keys or indices
 :add  [[segment value] ...]  ; insert missing keys or indices
 :del  [segment ...]}         ; remove keys or indices
```

- A **map** with exactly one change is emitted as an operation; a map with two or more, as a group. A group is a map plus two vectors, so emitting one for a single change would only add weight. A **vector** never produces a group: it emits one operation per replaced element plus the insertions and deletions its size change needs.
- `apply-diff` dispatches on `(map? element)` and reads either kind without a flag.
- There is no `:root` key: replacing the whole value is `[:set [] value]`, and an empty diff is `[]`.
- A path is resolved segment by segment against the container found at each step: a map is entered by key (any value, including nil), a vector by integer index. The container decides, not the segment type, so integer map keys are keys and a map keyed by a vector is fine. Because path segments are values, JSON keeps their types.
- Operations do **not** carry the previous value and do not check it; `apply-diff` replaces or deletes whatever the path holds. The arity is the contract: three elements for `:set`/`:add`, two for `:del`. A group must have at least one of `:set`/`:add`/`:del`, its three collections must be sequential when present (`contains?`, so an explicit `nil` is refused like any other wrong type) and every `:set`/`:add` member must be a `[key value]` vector. The whole group is validated before its path is resolved, so a malformed member is reported as such even when the target is not a container. Reject anything else with `:invalid-diff`: a short member would apply a `nil` the diff never carried and a long one would drop the rest in silence. The check runs once per group in `check-group`, so the apply helpers keep destructuring the members.
- Shape checks remain because they define each operation and catch a wrong base value at the structural level: `:set`/`:del` require the key or index to exist, `:add` requires the key to be absent on a map and an index in `0..count` on a vector. `:add`/`:del` need a non-empty path; `:add` on a vector index below `count` is an insertion, not a replacement.
- Presence encoding is what makes nil unambiguous: `{}` → `{:a nil}` is an add, `{:a nil}` → `{}` is a del, and `{:a 1}` → `{:a nil}` is a set.

## Ordering rules

- Sibling map operations are independent; their relative order is unspecified.
- Vector elements are emitted in an order that is safe to apply as given: replacements and nested groups first (they target indices that still exist), then removals from the highest index down, then insertions from the lowest index up. Do not reorder elements inside a vector.
- Inside a container, nested groups come before that container's own group. Local operations and nested groups never target the same key or index, so only the `:del`/`:add` position is load-bearing, and it must stay last.
- `diff` skips the unchanged common prefix and suffix of a vector, so a middle insert/remove is one add/del instead of a shift cascade. There is no LCS alignment: moved elements are diffed as a replacement region.

## Equality and coverage

- Comparison and the `=`-based no-op detection use `=`. NaN is never equal to itself, so it always diffs; applying a set with NaN is fine because there is no previous-value check.
- Only maps and vectors are recursed. Sets, lists and scalars are replaced whole with a set operation.
- Records recurse only against the same record type (`same-map-kind?`); against a plain map or another record type they are replaced whole, because removing a declared record field returns a plain map.
- A shape mismatch throws `ex-info` `:type :diff-mismatch` with `:op`, `:path` and `:reason` (`:absent`, `:present`, `:index-out-of-range`). Malformed elements throw `:type :invalid-diff` with `:value`.

## Implementation notes

- `emit-map-groups` runs one `reduce-kv` pass over the old map (using `find` on the new one, so the new value is read once) that both classifies the local operation of every key and recurses into the nested containers, then one pass over the new map for added keys. Never reintroduce the `keys`/`concat`/`distinct`/`mapcat` pipeline, and never compare values before they are needed.
- `emit-vector-groups` hoists the counts and keeps `^long` hints on the prefix/suffix scan, so the loop uses primitive arithmetic. Removing the hints re-boxes every subtraction.
- `emit-groups` assumes `(not= old new)`: `diff` checks the root and every recursive site checks before descending. Keep it that way; see the cost below.
- `apply-add` uses the private `insert-at` (`subvec` + `into`) instead of `app.common.data/add-at-index`, which builds two lazy seqs through `split-at`; `apply-del` and vector groups keep `d/remove-at-index`.
- `edit-at` + `update-in` resolves the container and rebuilds the path in one pass, for both an operation's parent path and a group's own path; do not add a separate `path-exists?`/`get-in` walk.
- Throwers are named `raise-mismatch` / `raise-invalid`, without `!` (`mem:clojure/design-rules`). Helpers are named without `!` even when they use transients.
- `apply-op` and every apply helper stay private: the only entry points are `diff` and `apply-diff`.

## Performance invariants (measured)

These were measured with `ThreadMXBean.getThreadAllocatedBytes` (deterministic, unlike wall clock) and criterium's fastest sample, which is the honest statistic on a shared machine. Breaking them shows up as a 1.5-2x regression:

- Apply walks the path once per group: `update-in` with a local-ops function. `get-in` + `assoc-in` walks it twice, which doubles the cost of the worst case (one operation per group) and adds ~0.1 µs per group at depth 11.
- Do not build error paths eagerly. `group-op` returns the operation kind only; the path in the error data comes from `group-error-path`, called from the error branch.
- Do not sort `:del`/`:add` unconditionally: `diff` emits removals high-to-low and additions low-to-high, so sorting is only needed above one element.
- `diff` uses one output transient for the whole value. Folding child vectors with `into` at every level copies each element once per ancestor and made a 20-level single change twice as slow.
- One pass per container. Separating "local operations" and "nested changes" into two passes doubles the `=` comparisons, which dominate on nested maps.
- Compare each pair once. Repeating the deep `=` on the root cost 335 kB of allocation on a 10k-entry map with a single change.
- An empty `(transient [])` allocates a 32-slot tail (344 B on the JVM), while `conj` on a short persistent vector costs about 64 B. `add-op` therefore keeps the collector as a short vector and promotes it at `promote-size` (4; between 4 and 12 the curve is flat within ~5%). Making the collectors uniform transients costs 1.5-2.9x the allocated bytes and 10-35% of the diff time.
- A container with a single change must be emitted as an operation, not as a group: the group shape (`{:path p :set [[k v]]}`) is a map plus two vectors where the operation (`[:set p+k v]`) is two vectors.
- `diff` returns `[]` without touching the output transient when both values are equal.

## Tests

`common/test/common_tests/data/object_diff_test.cljc` is the only test namespace, and `object_diff_generators.cljc` holds the shared value generators plus `valid-op?` / `valid-element?`. The suite covers the `map-diff` behavior this supersedes, the element shape (operations and groups), path resolution, nil presence, vector alignment, records, shape/invalid error data, and generative round-trip/idempotence/element-shape/no-crash properties (seeded, via `app.common.schema.test/check!`). `diff-hybrid-shape-test` pins the one-change-versus-group rule and mixed ordering; `apply-diff-group-test` and `apply-diff-group-errors-test` cover group application and its error data.
