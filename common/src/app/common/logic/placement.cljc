;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.logic.placement
  "The placement step of a transform: which shapes of a copy a transform
   leaves untouched. Moving, resizing or rotating a copy as a whole is free
   placement and touches none of its descendants; editing one shape of a copy
   touches it (see `mem:common/component-sync-contract`). The result, the
   ignore tree, goes to `cls/generate-update-shapes` (`:ignore-tree`), which
   leaves the geometry of the shapes it marks untouched."
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.common.types.component :as ctk]
   [app.common.types.container :as ctn]))

(def transform-attrs
  "The attributes a transform can change, so an update after it compares
   only these."
  #{:selrect
    :points
    :x
    :y
    :r1
    :r2
    :r3
    :r4
    :shadow
    :blur
    :background-blur
    :strokes
    :width
    :height
    :content
    :transform
    :transform-inverse
    :rotation
    :flip-x
    :flip-y
    :grow-type
    :position-data
    :layout-gap
    :layout-padding
    :layout-item-h-sizing
    :layout-item-max-h
    :layout-item-max-w
    :layout-item-min-h
    :layout-item-min-w
    :layout-item-v-sizing
    :layout-padding-type
    :layout-item-margin
    :layout-item-margin-type
    :layout-grid-cells
    :layout-grid-columns
    :layout-grid-rows
    :fills
    :fill-image})

(defn- check-delta
  "If the shape is a component instance, check whether the transformation is a
  free change that must not mark the shape as touched.

  For the instance ROOT, position is free placement, but its rotation and flips
  are inherited content: transforming the copy as a whole overrides them, so a
  change there marks the root as touched.

  For DESCENDANTS, position, size, rotation and flips are compared RELATIVE TO
  THE ROOT: a transformation of the whole instance preserves them all and does
  not mark the descendants as touched; editing an individual shape does."
  [shape root transformed-shape transformed-root]
  (let [is-root?     (and (some? root)
                          (= (dm/get-prop shape :id) (dm/get-prop root :id)))

        center       (fn [shape]
                       (grc/rect->center (:selrect shape)))

        rel-pos      (fn [shape root]
                       (when root
                         ;; vector from the root center to the shape center,
                         ;; expressed in the root's local (untransformed) axes
                         (-> (gpt/subtract (center shape) (center root))
                             (gpt/transform (:transform-inverse root (gmt/matrix))))))

        orientation  (fn [shape root]
                       ;; the shape's rotation/flip orientation taken from its
                       ;; transform matrix, so it is reliable regardless of how the
                       ;; transform was applied (the WASM apply-transform path does
                       ;; not refresh the :rotation attribute). For the ROOT the
                       ;; absolute orientation; for a DESCENDANT the orientation
                       ;; relative to the root — invariant when the whole instance
                       ;; is rotated or flipped as a unit.
                       (let [t (:transform shape (gmt/matrix))]
                         (if is-root?
                           t
                           (gmt/multiply (:transform-inverse root (gmt/matrix)) t))))

        pos-before   (rel-pos shape root)
        pos-after    (rel-pos transformed-shape transformed-root)

        distance
        (if (and pos-before pos-after)
          (gpt/distance-vector pos-before pos-after)
          (gpt/point 0 0))

        orientation-unchanged?
        (gmt/close? (orientation shape root)
                    (orientation transformed-shape transformed-root))

        selrect (:selrect shape)
        transformed-selrect (:selrect transformed-shape)]

    ;; There are cases in that the coordinates change slightly (e.g. when rounding
    ;; to pixel, or when recalculating text positions in different zoom levels).
    ;; To take this into account, we ignore movements smaller than 1 pixel.
    ;;
    ;; When the change is a resize, also has a transformation that may have the
    ;; shape position unchanged. But in this case we do not want to ignore it.
    (and (and (< (:x distance) 1) (< (:y distance) 1))
         (mth/close? (:width selrect) (:width transformed-selrect))
         (mth/close? (:height selrect) (:height transformed-selrect))
         orientation-unchanged?)))

(defn cached-transform
  "`transform` (shape -> transformed shape), computed once per shape value."
  [transform]
  (let [cache (volatile! {})]
    (fn [shape]
      (let [id               (dm/get-prop shape :id)
            [cached result]  (get @cache id)]
        (if (and (some? cached) (identical? shape cached))
          result
          (let [result (transform shape)]
            (vswap! cache assoc id [shape result])
            result))))))

(defn calculate-ignore-tree
  "A map of each shape of a copy under the transformed shapes `ids` to
   whether the transform leaves it untouched: true when its geometry
   relative to its copy root (or, for the root, its orientation) is the
   same after `transform` (shape -> transformed shape). Each shape is
   compared with the root of the copy of the transformed shape latest in
   `ids` among itself and its ancestors. With `user-ids`, the shapes the
   user transformed, every other shape changes only as a consequence of
   theirs (a child through its constraints, a board that hugs them), a
   derived change that leaves it untouched."
  ([objects ids transform]
   (calculate-ignore-tree objects ids transform nil))
  ([objects ids transform user-ids]
   (let [order (into {} (map-indexed (fn [i id] [id i])) ids)

         component-shape
         (fn [shape parent parent-component]
           (cond
             (cfh/root? shape) nil
             (ctk/instance-root? shape) shape
             (and (ctk/instance-head? shape) (not (ctk/in-component-copy? parent))) shape
             :else parent-component))

         walk
         (fn walk [ignore-tree shape component latest]
           (let [index  (get order (dm/get-prop shape :id))
                 latest (if (and (some? index) (or (nil? latest) (> index (nth latest 0))))
                          (if (some? component)
                            [index component (transform component)]
                            latest)
                          latest)
                 ignore-tree
                 (if (and (some? latest) (ctk/in-component-copy? shape))
                   (let [[_ root transformed-root] latest
                         id (dm/get-prop shape :id)]
                     (assoc ignore-tree id
                            (or (and (some? user-ids) (not (contains? user-ids id)))
                                (check-delta shape root (transform shape) transformed-root))))
                   ignore-tree)]
             (reduce (fn [ignore-tree child]
                       (walk ignore-tree child (component-shape child shape component) latest))
                     ignore-tree
                     (map (d/getf objects) (:shapes shape)))))

         under-transform (volatile! {})

         under-transform?
         (fn under-transform? [id]
           (let [parent-id (dm/get-in objects [id :parent-id])]
             (cond
               (or (nil? parent-id) (= parent-id id)) false
               (contains? order parent-id) true
               :else
               (let [cached (get @under-transform parent-id ::none)]
                 (if (= cached ::none)
                   (let [result (under-transform? parent-id)]
                     (vswap! under-transform assoc parent-id result)
                     result)
                   cached)))))]
     (->> ids
          (remove under-transform?)
          (keep #(get objects %))
          (reduce (fn [ignore-tree shape]
                    (let [parent (get objects (:parent-id shape))]
                      (walk ignore-tree
                            shape
                            (component-shape shape parent (ctn/get-component-shape objects parent {:allow-main? true}))
                            nil)))
                  nil)))))

(defn modifiers-transform
  "A cached transform (see `cached-transform`) that applies to each shape its
   modifiers in `modif-tree`."
  [modif-tree]
  (cached-transform #(gsh/transform-shape % (dm/get-in modif-tree [(dm/get-prop % :id) :modifiers]))))

(defn matrices-transform
  "A cached transform (see `cached-transform`) that applies to each shape its
   matrix in `transforms`, a map of ids to matrices."
  [transforms]
  (cached-transform #(gsh/apply-transform % (get transforms (dm/get-prop % :id)))))
