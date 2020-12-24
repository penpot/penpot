;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.tests.test-common-geom-shapes
  (:require
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.pages :refer [make-minimal-shape]]
   [clojure.test :as t]))

(def default-path
  [{:command :move-to :params {:x 0 :y 0}}
   {:command :line-to :params {:x 20 :y 20}}
   {:command :line-to :params {:x 30 :y 30}}
   {:command :curve-to :params {:x 40 :y 40 :c1x 35 :c1y 35 :c2x 45 :c2y 45}}
   {:command :close-path}])

(defn add-path-data [shape]
  (let [content (:content shape default-path)
        selrect (gsh/content->selrect content)
        points (gsh/rect->points selrect)]
    (assoc shape
           :content content
           :selrect selrect
           :points points)))

(defn add-rect-data [shape]
  (let [selrect (gsh/rect->selrect shape)
        points (gsh/rect->points selrect)]
    (assoc shape
           :selrect selrect
           :points points)))

(defn create-test-shape
  ([type] (create-test-shape type {}))
  ([type params]
   (-> (make-minimal-shape type)
       (merge params)
       (cond->
           (= type :path)    (add-path-data)
           (not= type :path) (add-rect-data)))))


(t/deftest transform-shape-tests
  (t/testing "Shape without modifiers should stay the same"
    (t/are [type]
        (let [shape-before (create-test-shape type)
              shape-after  (gsh/transform-shape shape-before)]
          (= shape-before shape-after))

      :rect :path))

  (t/testing "Transform shape with translation modifiers"
    (t/are [type]
        (let [modifiers {:displacement (gmt/translate-matrix (gpt/point 10 -10))}]
          (let [shape-before (create-test-shape type {:modifiers modifiers})
                shape-after  (gsh/transform-shape shape-before)]
            (t/is (not= shape-before shape-after))

            (t/is (== (get-in shape-before [:selrect :x])
                      (- 10 (get-in shape-after  [:selrect :x]))))

            (t/is (== (get-in shape-before [:selrect :y])
                      (+ 10 (get-in shape-after  [:selrect :y]))))

            (t/is (== (get-in shape-before [:selrect :width])
                      (get-in shape-after  [:selrect :width])))

            (t/is (== (get-in shape-before [:selrect :height])
                      (get-in shape-after  [:selrect :height])))))

      :rect :path))

  (t/testing "Transform with empty translation"
    (t/are [type]
        (let [modifiers {:displacement (gmt/matrix)}
              shape-before (create-test-shape type {:modifiers modifiers})
              shape-after  (gsh/transform-shape shape-before)]
          (t/are [prop]
              (t/is (== (get-in shape-before [:selrect prop])
                        (get-in shape-after [:selrect prop])))
            :x :y :width :height :x1 :y1 :x2 :y2))
      :rect :path))

  (t/testing "Transform shape with resize modifiers"
    (t/are [type]
        (let [modifiers {:resize-origin (gpt/point 0 0)
                         :resize-vector (gpt/point 2 2)
                         :resize-transform (gmt/matrix)}
              shape-before (create-test-shape type {:modifiers modifiers})
              shape-after  (gsh/transform-shape shape-before)]
          (t/is (not= shape-before shape-after))

          (t/is (== (get-in shape-before [:selrect :x])
                    (get-in shape-after  [:selrect :x])))

          (t/is (== (get-in shape-before [:selrect :y])
                    (get-in shape-after  [:selrect :y])))

          (t/is (== (* 2 (get-in shape-before [:selrect :width]))
                    (get-in shape-after  [:selrect :width])))

          (t/is (== (* 2 (get-in shape-before [:selrect :height]))
                    (get-in shape-after  [:selrect :height]))))
      :rect :path))

  (t/testing "Transform with empty resize"
    (t/are [type]
        (let [modifiers {:resize-origin (gpt/point 0 0)
                         :resize-vector (gpt/point 1 1)
                         :resize-transform (gmt/matrix)}
              shape-before (create-test-shape type {:modifiers modifiers})
              shape-after  (gsh/transform-shape shape-before)]
          (t/are [prop]
              (t/is (== (get-in shape-before [:selrect prop])
                        (get-in shape-after [:selrect prop])))
            :x :y :width :height :x1 :y1 :x2 :y2))
      :rect :path))

  (t/testing "Transform with resize=0"
    (t/are [type]
        (let [modifiers {:resize-origin (gpt/point 0 0)
                         :resize-vector (gpt/point 0 0)
                         :resize-transform (gmt/matrix)}
              shape-before (create-test-shape type {:modifiers modifiers})
              shape-after  (gsh/transform-shape shape-before)]
          (t/is (> (get-in shape-before [:selrect :width])
                   (get-in shape-after  [:selrect :width])))
          (t/is (> (get-in shape-after  [:selrect :width]) 0))

          (t/is (> (get-in shape-before [:selrect :height])
                   (get-in shape-after  [:selrect :height])))
          (t/is (> (get-in shape-after  [:selrect :height]) 0)))
      :rect :path))

  (t/testing "Transform shape with rotation modifiers"
    (t/are [type]
        (let [modifiers {:rotation 30}
              shape-before (create-test-shape type {:modifiers modifiers})
              shape-after  (gsh/transform-shape shape-before)]
          (t/is (not= shape-before shape-after))

          (t/is (not (== (get-in shape-before [:selrect :x])
                         (get-in shape-after  [:selrect :x]))))

          (t/is (not (== (get-in shape-before [:selrect :y])
                         (get-in shape-after  [:selrect :y])))))
      :rect :path))

  (t/testing "Transform shape with rotation = 0 should leave equal selrect"
    (t/are [type]
        (let [modifiers {:rotation 0}
              shape-before (create-test-shape type {:modifiers modifiers})
              shape-after  (gsh/transform-shape shape-before)]
          (t/are [prop]
              (t/is (== (get-in shape-before [:selrect prop])
                        (get-in shape-after [:selrect prop])))
            :x :y :width :height :x1 :y1 :x2 :y2))
      :rect :path))

  (t/testing "Transform shape with invalid selrect fails gracefuly"
    (t/are [type selrect]
        (let [modifiers {:displacement (gmt/matrix)}
              shape-before (-> (create-test-shape type {:modifiers modifiers})
                               (assoc :selrect selrect))
              shape-after  (gsh/transform-shape shape-before)]
          (= (:selrect shape-before) (:selrect shape-after)))

      :rect {:x 0 :y 0 :width ##Inf :height ##Inf}
      :path {:x 0 :y 0 :width ##Inf :height ##Inf}
      :rect nil
      :path nil)))
