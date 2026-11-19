;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.worker.trim
  "Keeps only the shape attributes the worker reads: its selection and
  snap indexes, and the change replay of `process-changes`."
  (:require
   [app.common.transit :as t]
   [app.common.types.objects-map :as omap]
   [app.common.types.shape :as cts]))

;; The attributes the worker keeps. Add one when the selection or snap index,
;; or a change type in `app.worker.index/index-change-types`, reads it.
(def ^:private shape-attrs
  [:id :type :parent-id :frame-id :shapes
   :x :y :width :height :rotation :selrect :points
   :transform :transform-inverse :flip-x :flip-y
   :hidden :blocked :show-content :masked-group
   ;; The replay refuses to edit copies or to nest a component in itself.
   :component-id :shape-ref
   ;; The replay rebuilds bool paths from rounded rects.
   :r1 :r2 :r3 :r4
   :stroke-width :bool-type :grids :layout])

(def ^:private stroke-attrs [:stroke-width :stroke-alignment])

(def ^:private position-data-attrs [:x :y :width :height])

;; Selection only asks whether a shape has fills.
(def ^:private some-fills [{}])

(defn- trim-svg-attrs
  [svg-attrs]
  (let [style (get svg-attrs :style)]
    (cond-> {}
      (contains? svg-attrs :fill)
      (assoc :fill (get svg-attrs :fill))

      (contains? style :fill)
      (assoc :style {:fill (get style :fill)}))))

(defn trim-shape
  [shape]
  (let [type      (get shape :type)
        fills     (get shape :fills)
        strokes   (get shape :strokes)
        svg-attrs (some-> (get shape :svg-attrs) trim-svg-attrs not-empty)
        content   (get shape :content)
        pos-data  (get shape :position-data)]
    (cts/map->Shape
     (cond-> (select-keys shape shape-attrs)
       (seq fills)
       (assoc :fills some-fills)

       (seq strokes)
       (assoc :strokes (mapv #(select-keys % stroke-attrs) strokes))

       (some? svg-attrs)
       (assoc :svg-attrs svg-attrs)

       (and (some? content) (or (= type :path) (= type :bool)))
       (assoc :content content)

       (and (some? pos-data) (= type :text))
       (assoc :position-data (mapv #(select-keys % position-data-attrs) pos-data))))))

(defn trim-objects
  "Trims every object. An `ObjectsMap` is read from its encoded data, so
  the decoded shapes are not kept in its cache."
  [objects]
  (if (omap/objects-map? objects)
    (persistent!
     (reduce-kv (fn [result id data]
                  (assoc! result id (trim-shape (t/decode-str data))))
                (transient {})
                (omap/get-data objects)))
    (persistent!
     (reduce-kv (fn [result id shape]
                  (assoc! result id (trim-shape shape)))
                (transient {})
                objects))))

(defn retrim-objects
  "Trims again the objects at `ids` that differ from `old-objects`: the
  replay may have written attributes the worker does not keep."
  [old-objects new-objects ids]
  (reduce (fn [objects id]
            (let [shape (get objects id)]
              (cond-> objects
                (and (some? shape) (not (identical? shape (get old-objects id))))
                (assoc id (trim-shape shape)))))
          new-objects
          ids))

(defn trim-page
  [page]
  (cond-> {:id (get page :id)
           :objects (trim-objects (get page :objects))}
    (contains? page :guides)
    (assoc :guides (get page :guides))))
