;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns common-tests.types-shape-tree-test
  (:require
   [app.common.geom.point :as gpt]
   [app.common.types.shape-tree :as ctt]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(defn- make-frame
  [id parent-id shapes x y width height show-content]
  {:id id
   :type :frame
   :parent-id parent-id
   :frame-id parent-id
   :shapes (vec shapes)
   :x x
   :y y
   :width width
   :height height
   :rotation nil
   :hidden false
   :blocked false
   :show-content show-content})

(t/deftest top-nested-frame-clip-content-test
  (t/testing "board A (clip) contains a wider board B; point inside both resolves to B"
    (let [a-id     (uuid/next)
          b-id     (uuid/next)
          objects  {a-id (make-frame a-id uuid/zero [b-id] 0 0 200 200 false)
                    b-id (make-frame b-id a-id [] 50 50 300 300 false)}
          position (gpt/point 150 150)
          result   (ctt/top-nested-frame objects position)]
      (t/is (= b-id result))))

  (t/testing "point inside B but outside A's clipped bounds is not reachable at all"
    (let [a-id     (uuid/next)
          b-id     (uuid/next)
          objects  {a-id (make-frame a-id uuid/zero [b-id] 0 0 200 200 false)
                    b-id (make-frame b-id a-id [] 50 50 300 300 false)}
          position (gpt/point 300 300)
          result   (ctt/top-nested-frame objects position)]
      ;; Outside A (the clip ancestor) and B's visible/clipped region there is
      ;; not visible either, so no frame should be resolved at that point.
      (t/is (= uuid/zero result))))

  (t/testing "with show-content true on A, the same point can resolve into B"
    (let [a-id     (uuid/next)
          b-id     (uuid/next)
          objects  {a-id (make-frame a-id uuid/zero [b-id] 0 0 200 200 true)
                    b-id (make-frame b-id a-id [] 50 50 300 300 false)}
          position (gpt/point 300 300)
          result   (ctt/top-nested-frame objects position)]
      (t/is (= b-id result)))))
