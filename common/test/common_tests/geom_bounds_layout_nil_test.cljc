;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.geom-bounds-layout-nil-test
  (:require
   [app.common.data :as d]
   [app.common.geom.bounds-map :as gbm]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes.flex-layout.bounds :as fb]
   [app.common.geom.shapes.grid-layout.bounds :as gb]
   [app.common.geom.shapes.min-size-layout :as msl]
   [app.common.types.shape :as cts]
   [app.common.types.shape.layout :as ctl]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

;; ---- Helpers ----

(defn- make-rect
  [id x y w h]
  (-> (cts/setup-shape {:id id
                        :type :rect
                        :name (str "rect-" id)
                        :x x :y y :width w :height h})
      (assoc :parent-id uuid/zero
             :frame-id uuid/zero)))

(defn- make-flex-frame
  [id child-ids & {:keys [x y w h dir]
                   :or {x 0 y 0 w 200 h 200 dir :row}}]
  (-> (cts/setup-shape {:id id
                        :type :frame
                        :name (str "flex-" id)
                        :layout :flex
                        :layout-flex-dir dir
                        :x x :y y :width w :height h})
      (assoc :parent-id uuid/zero
             :frame-id uuid/zero
             :shapes (vec child-ids))))

(defn- make-grid-frame
  [id child-ids & {:keys [x y w h dir]
                   :or {x 0 y 0 w 200 h 200 dir :row}}]
  (let [cell-id (uuid/next)]
    (-> (cts/setup-shape {:id id
                          :type :frame
                          :name (str "grid-" id)
                          :layout :grid
                          :layout-grid-dir dir
                          :layout-grid-columns [{:type :flex :value 1}]
                          :layout-grid-rows [{:type :flex :value 1}]
                          :layout-grid-cells
                          {cell-id {:id cell-id
                                    :row 1
                                    :row-span 1
                                    :column 1
                                    :column-span 1
                                    :shapes (vec child-ids)}}
                          :layout-padding-type :multiple
                          :layout-padding {:p1 0 :p2 0 :p3 0 :p4 0}
                          :layout-gap {:column-gap 0 :row-gap 0}
                          :x x :y y :width w :height h})
        (assoc :parent-id uuid/zero
               :frame-id uuid/zero
               :shapes (vec child-ids)))))

(defn- make-objects
  [shapes]
  (let [shape-map (into {} (map (fn [s] [(:id s) s]) shapes))]
    (reduce-kv (fn [m _id shape]
                 (if (contains? shape :shapes)
                   (reduce (fn [m' child-id]
                             (assoc-in m' [child-id :parent-id] (:id shape)))
                           m
                           (:shapes shape))
                   m))
               shape-map
               shape-map)))

(defn- bounds-map-from-objects
  "Build a bounds map from objects, optionally excluding some IDs."
  [objects & {:keys [exclude-ids]}]
  (let [full (gbm/objects->bounds-map objects)]
    (if (seq exclude-ids)
      (apply dissoc full exclude-ids)
      full)))

;; ---- Tests for flex layout bounds with nil bounds ----

(t/deftest layout-content-points-with-missing-parent-bounds
  (t/testing "layout-content-points returns nil when parent is not in bounds map"
    (let [child-id (uuid/next)
          parent-id (uuid/next)
          child (make-rect child-id 10 10 50 50)
          parent (make-flex-frame parent-id [child-id])
          objects (make-objects [parent child])
          bounds (bounds-map-from-objects objects :exclude-ids #{parent-id})]

      (t/is (nil? (fb/layout-content-points bounds parent [child] objects))))))

(t/deftest layout-content-points-with-missing-child-bounds
  (t/testing "layout-content-points skips children with missing bounds"
    (let [child1-id (uuid/next)
          child2-id (uuid/next)
          parent-id (uuid/next)
          child1 (make-rect child1-id 10 10 50 50)
          child2 (make-rect child2-id 70 10 50 50)
          parent (make-flex-frame parent-id [child1-id child2-id])
          objects (make-objects [parent child1 child2])
          bounds (bounds-map-from-objects objects :exclude-ids #{child1-id})]

      (let [result (fb/layout-content-points bounds parent [child1 child2] objects)]
        (t/is (some? result))
        ;; Only child2's bounds should be in the result
        (t/is (pos? (count result)))))))

(t/deftest layout-content-bounds-with-missing-parent-bounds
  (t/testing "layout-content-bounds returns nil when parent is not in bounds map"
    (let [child-id (uuid/next)
          parent-id (uuid/next)
          child (make-rect child-id 10 10 50 50)
          parent (make-flex-frame parent-id [child-id])
          objects (make-objects [parent child])
          bounds (bounds-map-from-objects objects :exclude-ids #{parent-id})]

      (t/is (nil? (fb/layout-content-bounds bounds parent [child] objects))))))

;; ---- Tests for grid layout bounds with nil bounds ----

(t/deftest grid-layout-content-points-with-missing-parent-bounds
  (t/testing "grid layout-content-points returns nil when parent is not in bounds map"
    (let [parent-id (uuid/next)
          parent (make-grid-frame parent-id [])
          objects (make-objects [parent])
          bounds (bounds-map-from-objects objects :exclude-ids #{parent-id})
          layout-data {:row-tracks [{:start-p (gpt/point 0 0) :size 100}]
                       :column-tracks [{:start-p (gpt/point 0 0) :size 100}]}]

      (t/is (nil? (gb/layout-content-points bounds parent layout-data))))))

(t/deftest grid-layout-content-bounds-with-missing-parent-bounds
  (t/testing "grid layout-content-bounds returns nil when parent is not in bounds map"
    (let [parent-id (uuid/next)
          parent (make-grid-frame parent-id [])
          objects (make-objects [parent])
          bounds (bounds-map-from-objects objects :exclude-ids #{parent-id})
          layout-data {:row-tracks [{:start-p (gpt/point 0 0) :size 100}]
                       :column-tracks [{:start-p (gpt/point 0 0) :size 100}]}]

      (t/is (nil? (gb/layout-content-bounds bounds parent layout-data))))))

;; ---- Tests for min-size-layout with nil bounds ----

(t/deftest child-min-width-grid-with-missing-child-bounds
  (t/testing "child-min-width falls back when grid layout child bounds are missing"
    (let [grandchild-id (uuid/next)
          child-id (uuid/next)
          grandchild (make-rect grandchild-id 0 0 30 30)
          child (-> (make-grid-frame child-id [grandchild-id] :w 100 :h 100)
                    (assoc :layout-grid-dir :row
                           :layout-item-h-sizing :fill))
          objects (make-objects [child grandchild])
          ;; Exclude grandchild from bounds to simulate missing entry
          bounds (bounds-map-from-objects objects :exclude-ids #{grandchild-id})
          child-bounds (grc/rect->points (grc/make-rect 0 0 100 100))]

      (let [result (msl/child-min-width child child-bounds bounds objects)]
        (t/is (= (ctl/child-min-width child) result))))))

(t/deftest child-min-height-grid-with-missing-child-bounds
  (t/testing "child-min-height falls back when grid layout child bounds are missing"
    (let [grandchild-id (uuid/next)
          child-id (uuid/next)
          grandchild (make-rect grandchild-id 0 0 30 30)
          child (-> (make-grid-frame child-id [grandchild-id] :w 100 :h 100)
                    (assoc :layout-grid-dir :column
                           :layout-item-v-sizing :fill))
          objects (make-objects [child grandchild])
          bounds (bounds-map-from-objects objects :exclude-ids #{grandchild-id})
          child-bounds (grc/rect->points (grc/make-rect 0 0 100 100))]

      (let [result (msl/child-min-height child child-bounds bounds objects)]
        (t/is (= (ctl/child-min-height child) result))))))

(t/deftest child-min-width-grid-with-present-child-bounds
  (t/testing "child-min-width handles bounded children in a fill-width grid"
    (let [grandchild-id (uuid/next)
          child-id (uuid/next)
          grandchild (make-rect grandchild-id 0 0 30 30)
          child (-> (make-grid-frame child-id [grandchild-id] :w 100 :h 100)
                    (assoc :layout-item-h-sizing :fill))
          objects (make-objects [child grandchild])
          bounds (bounds-map-from-objects objects)
          child-bounds (grc/rect->points (grc/make-rect 0 0 100 100))]

      (t/is (number? (msl/child-min-width child child-bounds bounds objects))))))

(t/deftest child-min-height-grid-with-present-child-bounds
  (t/testing "child-min-height handles bounded children in a fill-height grid"
    (let [grandchild-id (uuid/next)
          child-id (uuid/next)
          grandchild (make-rect grandchild-id 0 0 30 30)
          child (-> (make-grid-frame child-id [grandchild-id] :w 100 :h 100)
                    (assoc :layout-item-v-sizing :fill))
          objects (make-objects [child grandchild])
          bounds (bounds-map-from-objects objects)
          child-bounds (grc/rect->points (grc/make-rect 0 0 100 100))]

      (t/is (number? (msl/child-min-height child child-bounds bounds objects))))))
