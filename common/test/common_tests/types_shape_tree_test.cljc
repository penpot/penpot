;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

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

(t/deftest drag-frame-query-matches-rotated-and-clipped-frames
  (let [parent (uuid/next)
        child  (uuid/next)
        points [(gpt/point 75 10) (gpt/point 125 60)
                (gpt/point 75 110) (gpt/point 25 60)]
        objects {parent (make-frame parent uuid/zero [child] 0 0 100 100 false)
                 child  (assoc (make-frame child parent [] 40 25 70 70 false)
                               :rotation 45 :points points)}
        query   (ctt/make-drag-frame-query objects)]
    (doseq [point (concat points [(gpt/point 75 60) (gpt/point 32 32)
                                  (gpt/point 108 60) (gpt/point 300 300)])
            excluded [nil #{} #{child} #{parent}]]
      (t/is (= (ctt/top-nested-frame objects point excluded)
               (query point excluded))))
    (t/is (= child (query (gpt/point 75 60) nil)))
    (t/is (= uuid/zero (query (gpt/point 108 60) nil)))))

(t/deftest drag-frame-query-crosses-cell-boundaries
  (let [id      (uuid/next)
        objects {id (make-frame id uuid/zero [] -512 -256 768 512 false)}
        query   (ctt/make-drag-frame-query objects)]
    (doseq [x [-513 -512 -256.01 -256 -0.01 0 255.99 256 256.01 -512]
            y [-256 0 256 257]
            excluded [nil #{id}]]
      (let [pt (gpt/point x y)]
        (t/is (= (ctt/top-nested-frame objects pt excluded)
                 (query pt excluded)))))))
