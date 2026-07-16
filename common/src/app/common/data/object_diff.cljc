;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.data.object-diff
  "Compute and apply diffs between two values.

  A diff is a vector of elements of two kinds, applied in order:

    - An operation, a flat vector:
        [:set path value]   replaces the value at `path`.
        [:add path value]   inserts `value` where no key or index exists.
        [:del path]         removes the value at `path`.

    - A group, a map that collects several operations sharing one
      container path:
        {:path [segment ...]          ; path to the container, [] for the root
         :set  [[segment value] ...]  ; replace existing keys or indices
         :add  [[segment value] ...]  ; insert missing keys or indices
         :del  [segment ...]}         ; remove keys or indices

  A map that changes in exactly one place is emitted as an operation,
  which is the smaller object; a map with two or more changes is
  emitted as a group, which stores the shared path once. A vector never
  produces a group: it emits one operation per replaced element plus
  the insertions and deletions its size change needs, in the order
  `apply-diff` expects (replacements first, then removals from the
  highest index, then insertions from the lowest). Both kinds produce
  the same result, and appliers read either without a flag.

  A path walks the value segment by segment: a map is entered by a key
  (any value, including nil) and a vector by an integer index. The
  container found at each step decides how the next segment is read,
  so an integer is a key in a map and an index in a vector. Because the
  path segments are values, JSON keeps their types.

  Operations do not carry the previous value: applying a diff replaces
  or deletes whatever the path holds. Only the shape of the target is
  checked, because it is what gives each operation its meaning: `:set`
  and `:del` require the key or index to exist, `:add` requires it to
  be absent (on a map) or within `0..count` (on a vector). A shape that
  does not fit throws an `ex-info` of `:type :diff-mismatch` and no
  result is returned. A malformed element throws `:type :invalid-diff`.

  Because `:add` and `:del` state presence explicitly, a missing key
  and a key holding nil are different values: `{}` to `{:a nil}` is an
  `:add` and `{:a nil}` to `{}` is a `:del`.

  Only maps and vectors are compared segment by segment. Every other
  value (sets, lists, scalars) is compared and replaced as a whole.
  Records are compared segment by segment only against a record of the
  same type; against any other map they are replaced as a whole.

  Equality is clojure `=`, so NaN is always reported as a change. An
  empty diff is `[]`."
  (:require
   [app.common.data :as d]))

;; --- Diff

(declare emit-groups)

(defn- same-map-kind?
  "Records are only comparable against a record of the same type: their
  fields cannot be removed without turning them into a plain map."
  [a b]
  (if (or (record? a) (record? b))
    (= (type a) (type b))
    true))

(defn- containers?
  [a b]
  (or (and (map? a) (map? b) (same-map-kind? a b))
      (and (vector? a) (vector? b))))

(def ^:private promote-size
  "Number of operations at which a collector stops being a plain vector.
  A `conj` on a persistent vector copies its tail, so it beats an empty
  transient (344 bytes on the JVM) only while the vector stays short.
  Measured over 4, 6, 8 and 12 on the benchmark payloads: the curve is
  flat and 4 allocates the least."
  4)

(defn- add-op
  "Adds `op` to the collector held by `cell` (a volatile). Collectors
  start empty and switch from a plain vector to a transient when they
  grow, so a container that contributes one operation pays one small
  vector instead of a transient. A collector is a plain vector until it
  promotes, which `vector?` tells apart from a transient."
  [cell op]
  (let [ops @cell]
    (vreset! cell
             (cond
               (nil? ops) [op]
               (vector? ops) (if (< (count ops) promote-size)
                               (conj ops op)
                               (conj! (transient ops) op))
               :else (conj! ops op)))))

(defn- collected-ops
  "Returns the operations of a collector, or nil when the cell is nil or
  empty. Persists a transient in place, so call it once per collector."
  [cell]
  (when cell
    (let [ops @cell]
      (cond
        (nil? ops) nil
        (vector? ops) ops
        :else (persistent! ops)))))

(defn- emit-local-ops
  "Appends the operations local to a container, or returns `out`
  untouched when it has none. A single operation goes out as an
  operation, which is a flat vector; two or more share the path in a
  group. Unused collectors are passed as nil."
  [out path sets adds dels]
  (let [sets  (collected-ops sets)
        adds  (collected-ops adds)
        dels  (collected-ops dels)
        nset  (count sets)
        nadd  (count adds)
        ndel  (count dels)
        total (+ nset nadd ndel)]
    (cond
      (zero? total)
      out

      (= 1 total)
      (cond
        (pos? nset) (conj! out [:set (conj path (ffirst sets)) (second (first sets))])
        (pos? nadd) (conj! out [:add (conj path (ffirst adds)) (second (first adds))])
        :else       (conj! out [:del (conj path (first dels))]))

      :else
      (conj! out (cond-> {:path path}
                   (pos? nset) (assoc :set sets)
                   (pos? nadd) (assoc :add adds)
                   (pos? ndel) (assoc :del dels))))))

(defn- emit-map-groups
  [out old new path]
  (let [sets (volatile! nil)
        adds (volatile! nil)
        dels (volatile! nil)

        ;; One pass classifies every key and recurses into the ones that
        ;; hold a nested change. A local operation and a nested group never
        ;; target the same key, so their relative order does not matter.
        out  (reduce-kv
              (fn [out k old-val]
                (if-let [entry (find new k)]
                  (let [new-val (val entry)]
                    (cond
                      (= old-val new-val)
                      out

                      (containers? old-val new-val)
                      (emit-groups out old-val new-val (conj path k))

                      :else
                      (do (add-op sets [k new-val]) out)))
                  (do (add-op dels k) out)))
              out
              old)]

    (reduce-kv
     (fn [out k new-val]
       (if (contains? old k)
         out
         (do (add-op adds [k new-val]) out)))
     out
     new)

    (emit-local-ops out path sets adds dels)))

(defn- common-prefix-length
  [old new ^long limit]
  (loop [idx 0]
    (if (and (< idx limit)
             (= (nth old idx) (nth new idx)))
      (recur (inc idx))
      idx)))

(defn- common-suffix-length
  [old new ^long limit]
  (let [old-count (count old)
        new-count (count new)]
    (loop [idx 0]
      (if (and (< idx limit)
               (= (nth old (- old-count idx 1))
                  (nth new (- new-count idx 1))))
        (recur (inc idx))
        idx))))

(defn- emit-vector-groups
  [out old new path]
  (let [old-count    (count old)
        new-count    (count new)
        limit        (min old-count new-count)
        ^long prefix (common-prefix-length old new limit)
        ^long suffix (common-suffix-length old new (- limit prefix))

        old-size     (- old-count suffix prefix)
        new-size     (- new-count suffix prefix)
        shared       (min old-size new-size)

        sets         (volatile! nil)

        ;; One pass handles both the replacements and the nested changes:
        ;; they target different indices, so their relative order is free.
        out          (reduce
                      (fn [out idx]
                        (let [abs-idx (+ prefix idx)
                              old-val (nth old abs-idx)
                              new-val (nth new abs-idx)]
                          (cond
                            (= old-val new-val)
                            out

                            (containers? old-val new-val)
                            (emit-groups out old-val new-val (conj path abs-idx))

                            :else
                            (do (add-op sets [abs-idx new-val]) out))))
                      out
                      (range shared))

        ndels        (- old-size shared)
        nadds        (- new-size shared)

        dels         (when (pos? ndels)
                       (mapv #(+ prefix %)
                             (range (dec old-size) (dec shared) -1)))

        adds         (when (pos? nadds)
                       (mapv (fn [idx]
                               (let [abs-idx (+ prefix idx)]
                                 [abs-idx (nth new abs-idx)]))
                             (range shared new-size)))

        out          (emit-local-ops out path sets nil nil)]

    ;; Removals and additions go last: they shift every index after
    ;; them, so every other operation must already be applied.
    (emit-local-ops out path nil
                    (when adds (volatile! adds))
                    (when dels (volatile! dels)))))

(defn- emit-groups
  "Appends the elements that turn `old` into `new` under `path`.
  Callers guarantee `(not= old new)`: the public `diff` checks the root
  and every recursive site checks before descending, so the equality
  comparison never runs twice on the same pair."
  [out old new path]
  (cond
    (and (map? old) (map? new) (same-map-kind? old new))
    (emit-map-groups out old new path)

    (and (vector? old) (vector? new))
    (emit-vector-groups out old new path)

    (empty? path)
    (conj! out [:set [] new])

    :else
    (throw (ex-info "internal error: non-container diff outside the root"
                    {:path path}))))

;; --- Apply

(defn- raise-mismatch
  [op path reason]
  (throw (ex-info "diff does not match the value"
                  {:type   :diff-mismatch
                   :op     op
                   :path   path
                   :reason reason})))

(defn- raise-invalid
  [value hint]
  (throw (ex-info hint {:type  :invalid-diff
                        :value value})))

(defn- parent-path
  [path]
  (subvec path 0 (dec (count path))))

(defn- insert-at
  "Inserts `elem` at `idx` in a vector, without the intermediate lazy
  seqs that `split-at` creates inside `app.common.data/add-at-index`."
  [v idx elem]
  (into (conj (vec (subvec v 0 idx)) elem) (subvec v idx)))

(defn- edit-at
  "Applies `f` to the value at `path`. An empty path means the root
  value itself. `update-in` rebuilds the path in the same pass, so the
  tree is walked once."
  [value path f]
  (if (empty? path)
    (f value)
    (update-in value path f)))

;; --- Apply: one operation

(defn- apply-set
  [value [op path new]]
  (if (empty? path)
    new
    (edit-at
     value (parent-path path)
     (fn [container]
       (let [k (peek path)]
         (cond
           (vector? container)
           (if (and (integer? k) (<= 0 k) (< k (count container)))
             (assoc container k new)
             (raise-mismatch op path :absent))

           (map? container)
           (if (contains? container k)
             (assoc container k new)
             (raise-mismatch op path :absent))

           :else
           (raise-mismatch op path :absent)))))))

(defn- apply-add
  [value [op path new]]
  (when (empty? path)
    (raise-invalid [op path new] "add operation needs a non-empty path"))
  (edit-at
   value (parent-path path)
   (fn [container]
     (let [k (peek path)]
       (cond
         (vector? container)
         (if (and (integer? k) (<= 0 k) (<= k (count container)))
           (insert-at container k new)
           (raise-mismatch op path :index-out-of-range))

         (map? container)
         (if (contains? container k)
           (raise-mismatch op path :present)
           (assoc container k new))

         :else
         (raise-mismatch op path :absent))))))

(defn- apply-del
  [value [op path]]
  (when (empty? path)
    (raise-invalid [op path] "del operation needs a non-empty path"))
  (edit-at
   value (parent-path path)
   (fn [container]
     (let [k (peek path)]
       (cond
         (vector? container)
         (if (and (integer? k) (<= 0 k) (< k (count container)))
           (d/remove-at-index container k)
           (raise-mismatch op path :absent))

         (map? container)
         (if (contains? container k)
           (dissoc container k)
           (raise-mismatch op path :absent))

         :else
         (raise-mismatch op path :absent))))))

(defn- apply-op
  [value op]
  (cond
    (not (vector? op))
    (raise-invalid op "operation must be a vector")

    (not (vector? (second op)))
    (raise-invalid op "operation needs a vector path")

    :else
    (case (first op)
      :set (if (= 3 (count op))
             (apply-set value op)
             (raise-invalid op "set operation needs a path and a value"))

      :add (if (= 3 (count op))
             (apply-add value op)
             (raise-invalid op "add operation needs a path and a value"))

      :del (if (= 2 (count op))
             (apply-del value op)
             (raise-invalid op "del operation needs a path"))

      (raise-invalid op "unknown operation type"))))

;; --- Apply: one group

(defn- group-op
  "Returns the kind of the first operation in the group, or throws when
  the group holds none. Cheap enough to run on every group: it only
  reads."
  [{:keys [set add del] :as group}]
  (cond
    (seq set) :set
    (seq add) :add
    (seq del) :del
    :else     (raise-invalid group "group has no operation")))

(defn- group-error-path
  "Path of the first operation of a group, built only when reporting an
  error so that the happy path does not allocate it."
  [{:keys [path set add del]}]
  (cond
    (seq set) (conj path (ffirst set))
    (seq add) (conj path (first (first add)))
    (seq del) (conj path (first del))
    :else     path))

(defn- apply-map-ops
  [container group path]
  (let [container (reduce (fn [container [k v]]
                            (if (contains? container k)
                              (assoc container k v)
                              (raise-mismatch :set (conj path k) :absent)))
                          container
                          (:set group))
        container (reduce (fn [container k]
                            (if (contains? container k)
                              (dissoc container k)
                              (raise-mismatch :del (conj path k) :absent)))
                          container
                          (:del group))]
    (reduce (fn [container [k v]]
              (if (contains? container k)
                (raise-mismatch :add (conj path k) :present)
                (assoc container k v)))
            container
            (:add group))))

(defn- apply-vector-ops
  [container group path]
  (let [container (reduce (fn [container [idx v]]
                            (if (and (integer? idx) (<= 0 idx) (< idx (count container)))
                              (assoc container idx v)
                              (raise-mismatch :set (conj path idx) :absent)))
                          container
                          (:set group))

        dels (:del group)
        dels (if (< 1 (count dels)) (sort > dels) dels)

        container (reduce (fn [container idx]
                            (if (and (integer? idx) (<= 0 idx) (< idx (count container)))
                              (d/remove-at-index container idx)
                              (raise-mismatch :del (conj path idx) :absent)))
                          container
                          dels)

        adds (:add group)
        adds (if (< 1 (count adds)) (sort-by first adds) adds)]

    (reduce (fn [container [idx v]]
              (if (and (integer? idx) (<= 0 idx) (<= idx (count container)))
                (insert-at container idx v)
                (raise-mismatch :add (conj path idx) :index-out-of-range)))
            container
            adds)))

(defn- apply-local-ops
  "Applies the operations of one group to the container it targets."
  [container group path]
  (cond
    (map? container)
    (apply-map-ops container group path)

    (vector? container)
    (apply-vector-ops container group path)

    :else
    (raise-mismatch (group-op group)
                    (group-error-path group)
                    :absent)))

(defn- check-members
  "Fails when a `:set`/`:add` collection holds a member that is not a
  `[key value]` vector. A shorter vector would apply a value the diff never
  carried (`nil`), and a longer one would drop the rest in silence."
  [members label]
  (doseq [member members
          :when (not (and (vector? member) (= 2 (count member))))]
    (raise-invalid member (str "a " label " member must be a two-element vector"))))

(defn- check-group
  "Fails when a group cannot be applied as it is: a collection that is present
  (`contains?`, so an explicit nil counts) but is not sequential, or a
  `:set`/`:add` member that is not a two-element vector. Runs before the group
  is resolved, so a malformed element is reported as such whatever the target
  holds."
  [{:keys [set add del] :as group}]
  (when (contains? group :set)
    (when-not (sequential? set)
      (raise-invalid group "the set collection of a group must be sequential"))
    (check-members set "set"))
  (when (contains? group :add)
    (when-not (sequential? add)
      (raise-invalid group "the add collection of a group must be sequential"))
    (check-members add "add"))
  (when (contains? group :del)
    (when-not (sequential? del)
      (raise-invalid group "the del collection of a group must be sequential"))))

(defn- apply-group
  [value {:keys [path] :as group}]
  (when-not (vector? path)
    (raise-invalid group "group needs a vector path"))
  (check-group group)
  ;; Fails fast on an empty group, whatever the container holds.
  (group-op group)
  ;; One walk down to the container and one rebuild on the way back,
  ;; instead of `get-in` plus `assoc-in`, which walks the path twice.
  (edit-at value path
           (fn [container]
             (apply-local-ops container group path))))

;; --- Public

(defn diff
  "Returns a diff, a vector of operations and groups, that turns `old`
  into `new`, or `[]` when they are equal.

  Maps are diffed by key and vectors by index, so a single changed
  element inside a vector yields one operation that targets it instead
  of a replacement of the whole vector. Adding or removing an element
  in the middle of a vector yields one `:add` or `:del`, because the
  unchanged head and tail are skipped.

  Operations on different map keys are independent; the only order that
  matters is inside a vector, where insertions and deletions shift
  indices, and the emitted order is always safe to apply as given."
  [old new]
  ;; Checked here as well as in the recursive sites so that an equal
  ;; pair does not pay for the output transient and its persistence.
  (if (= old new)
    []
    (persistent! (emit-groups (transient []) old new []))))

(defn apply-diff
  "Applies `diff`, as returned by `diff`, to `value` and returns the
  result.

  Operations replace or delete whatever the path holds; they do not
  check a previous value. Only the shape of the target is checked: a
  `:set` or `:del` whose key or index does not exist, an `:add` whose
  key already exists or whose index is out of range, and a path that
  runs through a non-container throw an `ex-info` of
  `:type :diff-mismatch` with `:op`, `:path` and `:reason` in its data.
  Malformed elements throw with `:type :invalid-diff` instead: a group
  whose `:set`/`:add`/`:del` is present but is not a sequential
  collection, or whose `:set`/`:add` member is not a `[key value]`
  vector. The group is validated before its path is resolved, so a
  malformed element is reported as such whatever the target holds.

  The one-argument arity returns a function that applies the given diff,
  for use with `map` and friends."
  ([diff]
   #(apply-diff % diff))

  ([value diff]
   (if-not (vector? diff)
     (raise-invalid diff "a diff must be a vector of groups and operations")
     (reduce (fn [value element]
               (if (map? element)
                 (apply-group value element)
                 (apply-op value element)))
             value
             diff))))
