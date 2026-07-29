;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.render-dimensions-test
  (:require
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes.bounds :as gsb]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.main.render :as render]
   [cljs.test :as t :include-macros true]))

(defn- make-objects
  "Create a proper objects map with a root frame and the given shapes."
  [& shapes]
  (let [root-frame (cts/setup-shape {:id uuid/zero
                                     :type :frame
                                     :parent-id uuid/zero
                                     :frame-id uuid/zero
                                     :name "Root Frame"
                                     :shapes (mapv :id shapes)})
        objects {uuid/zero root-frame}]
    (reduce (fn [objs shape]
              (assoc objs (:id shape) (assoc shape :frame-id uuid/zero)))
            objects
            shapes)))

(t/deftest calculate-dimensions-normal-bounds
  (t/testing "Normal bounding box should pass"
    (let [shape1 (cts/setup-shape {:type :rect :x 100 :y 100 :width 200 :height 150})
          shape2 (cts/setup-shape {:type :rect :x 400 :y 300 :width 100 :height 100})
          objects (make-objects shape1 shape2)
          result (render/calculate-dimensions objects nil)]
      (t/is (some? result))
      (t/is (<= (:width result) render/max-export-dimension))
      (t/is (<= (:height result) render/max-export-dimension)))))

(t/deftest calculate-dimensions-extreme-width
  (t/testing "Extreme width should throw export-area-too-large"
    (let [shape (cts/setup-shape {:type :rect :x 0 :y 0 :width 200000 :height 100})
          objects (make-objects shape)]
      (t/is (thrown-with-msg?
             js/Error
             #"export area exceeds maximum allowed dimensions"
             (render/calculate-dimensions objects nil))))))

(t/deftest calculate-dimensions-extreme-height
  (t/testing "Extreme height should throw export-area-too-large"
    (let [shape (cts/setup-shape {:type :rect :x 0 :y 0 :width 100 :height 200000})
          objects (make-objects shape)]
      (t/is (thrown-with-msg?
             js/Error
             #"export area exceeds maximum allowed dimensions"
             (render/calculate-dimensions objects nil))))))

(t/deftest calculate-dimensions-extreme-position
  (t/testing "Shape at extreme position should throw export-area-too-large"
    (let [shape (cts/setup-shape {:type :rect :x 500000 :y 500000 :width 100 :height 100})
          objects (make-objects shape)]
      (t/is (thrown-with-msg?
             js/Error
             #"export area exceeds maximum allowed dimensions"
             (render/calculate-dimensions objects nil))))))

(t/deftest calculate-dimensions-exactly-at-limit
  (t/testing "Bounding box exactly at limit should pass"
    (let [shape (cts/setup-shape {:type :rect :x 0 :y 0 :width render/max-export-dimension :height render/max-export-dimension})
          objects (make-objects shape)
          result (render/calculate-dimensions objects nil)]
      (t/is (some? result))
      (t/is (<= (:width result) render/max-export-dimension))
      (t/is (<= (:height result) render/max-export-dimension)))))
