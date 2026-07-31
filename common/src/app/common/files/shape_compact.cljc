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
   [app.common.math :as mth]
   [app.common.types.shape :as cts]))

#?(:clj (set! *warn-on-reflection* true))

;; --- Identity transform detection (handles both Matrix instances and plain maps)

(defn- identity-transform?
  [transform]
  (if (nil? transform)
    true
    (if (gmt/matrix? transform)
      (gmt/unit? transform)
      (and (mth/close? (d/nilv (:a transform) 1) 1)
           (mth/close? (d/nilv (:b transform) 0) 0)
           (mth/close? (d/nilv (:c transform) 0) 0)
           (mth/close? (d/nilv (:d transform) 1) 1)
           (mth/close? (d/nilv (:e transform) 0) 0)
           (mth/close? (d/nilv (:f transform) 0) 0)))))

;; --- Default values that can be omitted

(def ^:private default-attrs
  {:rotation         0
   :proportion-lock  false
   :hide-fill-on-export false
   :hide-in-viewer   false
   :show-content     false
   :blocked          false
   :collapsed        false
   :locked           false
   :hidden           false
   :masked-group     false
   :fixed-scroll     false
   :grow-type        :fixed})

;; --- Empty collections that can be omitted these are optional attrs with
;;     default behavior when absent

(def ^:private empty-collections
  [:fills :strokes :shadow :exports :interactions :grids :shapes])

;; --- Compaction

(defn compact-shape
  "Prune redundant and derivable fields from a shape for compact serialization.
   Omits:
     - nil values
     - identity transform and transform-inverse
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
        id-xf?  (identity-transform? (:transform shape))
        type    (dm/get-prop shape :type)
        path?   (or (= type :path) (= type :bool))
        rot     (dm/get-prop shape :rotation)
        safe?   (and id-xf? (or (nil? rot) (zero? rot)))]
    (-> shape
        (dissoc :page-id)
        (cond-> id-xf?
          (dissoc :transform :transform-inverse))
        (cond-> (and safe? (not path?))
          (dissoc :selrect :points))
        (cond-> (and id-xf? path?)
          (dissoc :points))
        (as-> s
          (reduce-kv (fn [s k v]
                       (let [d (get default-attrs k ::nf)]
                         (if (and (not= d ::nf) (= v d))
                           (dissoc s k)
                           s)))
                     s s))
        (as-> s
          (reduce (fn [s k]
                    (let [v (get s k)]
                      (if (and (coll? v) (empty? v))
                        (dissoc s k)
                        s)))
                  s empty-collections)))))

;; --- Expansion

(defn expand-shape
  "Restore fields omitted by compact-shape. Restores identity
   transform/transform-inverse, and recomputes selrect and points using
   the standard shape setup functions. Returns a Shape record."
  [shape]
  (let [type   (dm/get-prop shape :type)
        path?  (or (= type :path) (= type :bool))]
    (-> shape
        (cond-> (nil? (:transform shape))
          (assoc :transform (gmt/matrix)))
        (cond-> (nil? (:transform-inverse shape))
          (assoc :transform-inverse (gmt/matrix)))
        (as-> s
          (if path?
            (cts/setup-path s)
            (cts/setup-rect s)))
        (cts/create-shape))))

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
