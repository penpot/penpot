;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.files.shape-compact
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.shapes.constraints :as gcos]
   [app.common.math :as mth]
   [app.common.types.shape :as cts]))

#?(:clj (set! *warn-on-reflection* true))

;; --- Identity transform detection (handles both Matrix instances and plain maps)

(defn- identity-transform?
  "True when `transform` is the identity. The six coefficients are compared
  with exact numeric equality, not with a tolerance, because the writer keeps
  any coefficient that the rounding of the format would keep: a shear of
  0.0008 is written as it is and moves a border by units on a big shape, so
  pruning it would change the geometry of the shape when it is read back. An
  absent coefficient takes its identity value, `-0.0` counts as zero and NaN
  is not the identity."
  [transform]
  (if (nil? transform)
    true
    (and (== 1 (d/nilv (dm/get-prop transform :a) 1))
         (== 0 (d/nilv (dm/get-prop transform :b) 0))
         (== 0 (d/nilv (dm/get-prop transform :c) 0))
         (== 1 (d/nilv (dm/get-prop transform :d) 1))
         (== 0 (d/nilv (dm/get-prop transform :e) 0))
         (== 0 (d/nilv (dm/get-prop transform :f) 0)))))

;; --- Default values that can be omitted

(def ^:private radii-keys
  #{:r1 :r2 :r3 :r4})

(def ^:private radii-types
  "The four radii belong to the types whose minimal shape carries them
  (`minimal-rect-attrs`, `minimal-image-attrs` and `minimal-frame-attrs` in
  `app.common.types.shape`). `editable-attrs` also lets the inspector set them
  on `:svg-raw` and `:bool`, but those do not get them at birth, so pruning or
  restoring them there would only invent keys: the keys stay as they are."
  #{:rect :image :frame})

(def ^:private type-scoped-defaults
  "The defaults that only some types carry: the keys the schema scopes to a
  type (`allowed-frame-attrs`) and the one that only means something on a
  group. A type outside the set does not carry the key at birth, so pruning or
  restoring it there would only invent keys, for the same reason as the radii."
  {:hide-fill-on-export #{:frame}
   :show-content        #{:frame}
   :hide-in-viewer      #{:frame}
   :masked-group        #{:group}
   ;; The schema requires the children list on groups, frames and bools, and
   ;; makes it optional on svg-raw. A container with no children stores no
   ;; `:shapes` (it is in `empty-collections`), so the reader puts the empty
   ;; vector back on the types that cannot be read without it.
   :shapes              #{:frame :group :bool}})

;; `:grow-type` is deliberately absent from this map: a text without the key is
;; re-measured after a style change and one carrying `:fixed` is not (see
;; `app.main.data.workspace.texts`), and the wasm uploader carries a presence
;; bit, so absence and `:fixed` are different states of the shape. The writer
;; keeps the key as it is instead of turning its absence into a value.
(def ^:private default-attrs
  {:rotation               0
   :shapes                 []
   :proportion-lock        false
   :hide-fill-on-export    false
   :hide-in-viewer         false
   :show-content           false
   :blocked                false
   :collapsed              false
   :locked                 false
   :hidden                 false
   :masked-group           false
   :fixed-scroll           false
   :opacity                1
   :blend-mode             :normal
   :layout-item-absolute   false
   :layout-item-z-index    0
   :layout-item-margin     {:m1 0 :m2 0 :m3 0 :m4 0}
   :r1                     0
   :r2                     0
   :r3                     0
   :r4                     0})

(defn- default-of
  "The default value of `k` for this shape type, or `::nf` when the key does
  not apply."
  [type k]
  (let [default (get default-attrs k ::nf)
        types   (get type-scoped-defaults k)]
    (if (or (= default ::nf)
            (and (contains? radii-keys k)
                 (not (contains? radii-types type)))
            (and (some? types) (not (contains? types type))))
      ::nf
      default)))

;; --- Empty collections that can be omitted these are optional attrs with
;;     default behavior when absent

(def ^:private empty-collections
  [:fills :strokes :shadow :exports :interactions :grids :shapes
   :touched :applied-tokens :component-property-references])

;; --- Compaction

(defn compact-shape
  "Prune redundant and derivable fields from a shape for compact serialization.
   Omits:
     - nil values
     - transform when it is identity, and transform-inverse when both are
       identity
     - selrect and points when derivable from x/y/width/height (non-rotated shapes)
     - page-id (redundant with the containing page)
     - default values (rotation 0, proportion-lock false, etc.)
     - empty collections (fills, strokes, shadow, etc.)
   The resulting shape can be restored via expand-shape."
  [shape]
  ;; Convert to plain map first (cr/defrecord does not fully remove
  ;; declared fields on dissoc; it sets them to nil instead).
  (let [shape   (reduce-kv (fn [m k v]
                             (if (nil? v) m (assoc m k v)))
                           {} shape)
        id-xf?         (identity-transform? (:transform shape))
        id-xf-inverse? (identity-transform? (:transform-inverse shape))
        type           (dm/get-prop shape :type)
        path?          (or (= type :path) (= type :bool))
        rot            (dm/get-prop shape :rotation)
        safe?          (and id-xf? (or (nil? rot) (zero? rot)))]
    (-> shape
        (dissoc :page-id)
        (cond-> id-xf?
          (dissoc :transform))
        (cond-> (and id-xf? id-xf-inverse?)
          (dissoc :transform-inverse))
        (cond-> (and safe? (not path?))
          (dissoc :selrect :points))
        (cond-> (and id-xf? path?)
          (dissoc :points))
        (as-> s
              (reduce-kv (fn [s k v]
                           (if (= v (default-of type k))
                             (dissoc s k)
                             s))
                         s s))
        (as-> s
              (reduce (fn [s k]
                        (let [v (get s k)]
                          (if (and (coll? v) (empty? v))
                            (dissoc s k)
                            s)))
                      s empty-collections)))))

;; --- Expansion

(defn- restore-defaults
  "Puts back the default values that `compact-shape` drops, but only when the
  key is absent. Absence is checked with `nil?` and not with `contains?`,
  because the decoded shape is a `Shape` record and a record answers `true`
  for every base field whatever it holds. A key the stored shape already
  carries is never overwritten."
  [shape type]
  (reduce-kv (fn [shape k _]
               (let [default (default-of type k)]
                 (if (or (= default ::nf) (some? (get shape k)))
                   shape
                   (assoc shape k default))))
             shape
             default-attrs))

(defn expand-shape
  "Restore fields omitted by compact-shape. Restores identity
   transform/transform-inverse, the default values that are absent, and
   recomputes selrect and points using the standard shape setup functions.
   Returns a Shape record without nil extmap keys. A base field of the record
   survives (a record cannot drop it, it keeps it as nil), which is what a path
   wants for its geometry: `:x`, `:y`, `:width` and `:height` stay nil."
  [shape]
  (let [type   (dm/get-prop shape :type)
        path?  (or (= type :path) (= type :bool))]
    (-> shape
        (cond-> (nil? (:transform shape))
          (assoc :transform (gmt/matrix)))
        (cond-> (nil? (:transform-inverse shape))
          (assoc :transform-inverse (gmt/matrix)))
        (as-> s (restore-defaults s type))
        (as-> s
              (if path?
                (cts/setup-path s)
                (cts/setup-rect s)))
        (cts/create-shape)
        (d/without-nils))))

;; --- Page compaction

(defn- prune-page-object
  "Drops from one object of a page the id that the key of the map rebuilds and
  the two constraints when they equal the default that the shape's own ids give
  them. Expects the shape already compacted with `compact-shape` and rounded,
  with a uuid id, because the comparison is by value."
  [id shape]
  (let [ch (gcos/default-constraints-h shape)
        cv (gcos/default-constraints-v shape)]
    (cond-> shape
      (= id (:id shape))
      (dissoc :id)

      (= ch (:constraints-h shape))
      (dissoc :constraints-h)

      (= cv (:constraints-v shape))
      (dissoc :constraints-v))))

(defn compact-page
  "Drops from every object of the page the id that the key of the map rebuilds,
  and the two constraints when they equal the default that the shape's own ids
  give them (`:left`/`:top` inside a frame, `:scale` inside a group). Expects
  the objects already compacted with `compact-shape` and rounded, with uuid keys
  and uuid ids, because the comparison is by value: a caller with string keys
  would simply keep everything. A value that does not match the derivation is
  kept."
  [page]
  (update page :objects
          (fn [objects]
            (reduce-kv (fn [result id shape]
                         (assoc result id (prune-page-object id shape)))
                       objects
                       objects))))

(defn expand-page
  "Rebuilds the id and the constraints that `compact-page` dropped and expands
  every shape with `expand-shape`. A field the stored shape already carries is
  never overwritten, so a page written before this pruning reads back
  unchanged.

  Expects the objects decoded from the page entry, with uuid keys: the id comes
  from the key. Absence is checked with `nil?` and not with `contains?`, because
  the decoded shape is a `Shape` record: a record answers `true` for every base
  field whatever it holds, and nil is how it says that a field is unset."
  [page]
  (update page :objects
          (fn [objects]
            (reduce-kv (fn [result id shape]
                         (let [shape (cond-> shape
                                       (nil? (get shape :id))
                                       (assoc :id id))
                               ch    (gcos/default-constraints-h shape)
                               cv    (gcos/default-constraints-v shape)
                               shape (cond-> shape
                                       (and (some? ch) (nil? (get shape :constraints-h)))
                                       (assoc :constraints-h ch)

                                       (and (some? cv) (nil? (get shape :constraints-v)))
                                       (assoc :constraints-v cv))]
                           (assoc result id (expand-shape shape))))
                       objects
                       objects))))

;; --- Float rounding

(defn round-values
  "Round all numeric values in data to 4 decimal places.
   Eliminates float32 artifacts like 0.6000000238418579."
  [data]
  (letfn [(round-n [n]
            (if (and (number? n) (mth/finite? n) (not (integer? n)))
              (mth/precision n 4)
              n))
          (walk [node]
            (cond
              (map? node)
              (reduce-kv (fn [m k v] (assoc m k (walk v))) node node)

              (vector? node)
              (mapv walk node)

              (number? node)
              (round-n node)

              :else
              node))]
    (walk data)))

(defn compact-object
  "Compacts one object of a page: `compact-shape`, `round-values` and the id
  and constraint pruning that `compact-page` does, without materializing the
  page's objects map. `id` is the key of the page's `:objects`."
  [id shape]
  (prune-page-object id (-> shape compact-shape round-values)))
