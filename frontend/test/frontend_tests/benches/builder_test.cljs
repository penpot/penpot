;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.benches.builder-test
  (:require
   [app.common.uuid :as uuid]
   [benches.render-wasm.scenes.builder :as b :include-macros true]
   [benches.render-wasm.scenes.common :as common]
   [benches.render-wasm.scenes.rects :as rects]
   [cljs.test :as t :include-macros true]))

(defn- build
  ([] (build {}))
  ([params] (rects/build (merge {:seed 42 :count 5} params))))

(defn- rect-shapes
  "Rectangles of a fixture instance in creation order."
  [instance]
  (mapv (:objects instance)
        (get-in instance [:objects uuid/zero :shapes])))

(defn- failure-data
  [f]
  (try
    (f)
    nil
    (catch :default cause
      (ex-data cause))))

(defn- add-tile
  "Helper defined outside a fixture scope, called from inside one."
  [index]
  (b/rect [:tile index] {:x (* 10 index)}))

(t/deftest repeatable-builds-are-identical
  (t/is (= (build) (build)))
  (t/is (not= (build) (build {:seed 43}))))

(t/deftest rectangle-workload-keeps-default-count
  (let [instance (rects/build {:seed 42})
        rects    (rect-shapes instance)]
    (t/is (= 1000 (:count rects/default-params)))
    (t/is (= 1000 (count rects)))
    (t/is (< 1 (count (distinct (map :x rects)))))))

(t/deftest default-workload-is-canonical
  (let [instance (build {:count 25})
        rects    (rect-shapes instance)]
    (t/is (= 25 (count rects)))
    (t/is (every? #(= :rect (:type %)) rects))
    (t/is (every? (fn [rect]
                    (and (= 1 (count (:fills rect)))
                         (= 1 (count (:strokes rect)))
                         (= 10 (:stroke-width (first (:strokes rect))))
                         (= :center (:stroke-alignment (first (:strokes rect))))))
                  rects))
    (t/is (every? (fn [rect]
                    (and (re-matches #"^#[0-9a-f]{6}$" (:fill-color (first (:fills rect))))
                         (<= 0.1 (:fill-opacity (first (:fills rect))) 0.999)
                         (every? #(<= 0 % 24) [(:r1 rect) (:r2 rect) (:r3 rect) (:r4 rect)])))
                  rects))))

(t/deftest geometry-is-page-relative-and-in-range
  (let [params   {:seed 7 :count 40 :width 800 :height 600 :min-size 5 :max-size 15}
        instance (build params)
        rects    (rect-shapes instance)]
    (t/is (every? #(<= 0 (:x %) 800) rects))
    (t/is (every? #(<= 0 (:y %) 600) rects))
    (t/is (every? #(<= 5 (:width %) 15) rects))
    (t/is (every? #(<= 5 (:height %) 15) rects))
    (t/is (= 800 (get-in instance [:objects uuid/zero :width])))
    (t/is (= 600 (get-in instance [:objects uuid/zero :height])))))

(t/deftest labels-are-instance-local
  (let [instance (b/fixture {:seed 1}
                            (b/rect)
                            (b/rect :hero {})
                            (b/rect [:tile 1] {}))
        refs     (:refs instance)
        children (get-in instance [:objects uuid/zero :shapes])]
    (t/is (= #{:hero [:tile 1]} (set (keys refs))))
    (t/is (= (get refs :hero) (second children)))
    (t/is (= (get refs [:tile 1]) (nth children 2)))))

(t/deftest attrs-override-generated-values
  (let [instance (b/fixture {:seed 1
                             :defaults {:rect {:x (b/gen-int 0 100)}}}
                            (b/rect {:x 7 :fills []}))
        rect     (first (rect-shapes instance))]
    (t/is (= 7 (:x rect)))
    (t/is (= [] (:fills rect)))))

(t/deftest ordinary-forms-work-inside-the-scope
  (let [instance (b/fixture {:seed 1}
                            (let [first-id (b/rect)]
                              (t/is (uuid? first-id)))
                            (doseq [index (range 3)]
                              (add-tile index))
                            (b/rect :last {:x 99}))
        refs     (:refs instance)
        rects    (rect-shapes instance)]
    (t/is (= 5 (count rects)))
    (t/is (= [0 10 20]
             (mapv #(:x (get-in instance [:objects (get refs [:tile %])]))
                   (range 3))))
    (t/is (= 99 (:x (get-in instance [:objects (get refs :last)]))))))

(t/deftest duplicate-labels-are-rejected
  (let [data (failure-data
              #(b/fixture {:seed 1}
                          (b/rect :dup {})
                          (b/rect :dup {})))]
    (t/is (= ::b/duplicate-label (:type data)))
    (t/is (= :dup (:label data)))))

(t/deftest failed-builds-leak-no-state
  (let [clean (build)]
    (failure-data #(b/fixture {:seed 99}
                              (b/rect :dup {})
                              (b/rect :dup {})))
    (t/is (= clean (build)))))

(t/deftest rect-outside-scope-is-rejected
  (let [data (failure-data #(b/rect))]
    (t/is (= ::b/outside-scope (:type data)))))

(t/deftest nested-scopes-are-rejected
  (let [data (failure-data
              #(b/fixture {:seed 1}
                          (b/fixture {:seed 2}
                                     (b/rect))))]
    (t/is (= ::b/nested-scope (:type data)))))

(t/deftest invalid-seeds-are-rejected
  (t/is (= ::b/invalid-seed
           (:type (failure-data #(b/fixture {} (b/rect))))))
  (t/is (= ::b/invalid-seed
           (:type (failure-data #(b/fixture {:seed -1} (b/rect))))))
  (t/is (= ::b/invalid-seed
           (:type (failure-data #(b/fixture {:seed 4294967296} (b/rect)))))))

(t/deftest runtime-attrs-maps-are-resolved
  (let [attrs    {:x 11}
        instance (b/fixture {:seed 1}
                            (b/rect attrs))
        rect     (first (rect-shapes instance))]
    (t/is (= 11 (:x rect)))))

(t/deftest single-label-form-is-supported
  (let [instance (b/fixture {:seed 1}
                            (b/rect :solo))
        id       (get (:refs instance) :solo)]
    (t/is (uuid? id))
    (t/is (= [id] (get-in instance [:objects uuid/zero :shapes])))))

(t/deftest built-instance-feeds-the-snapshot-api
  (let [instance (b/fixture {:seed 1}
                            (b/rect :hero {}))
        order    (common/upload-order instance)
        hero-id  (common/ref-id instance :hero)]
    (t/is (= [uuid/zero hero-id] (mapv :id order)))))

(t/deftest invalid-labels-are-rejected
  (let [data (failure-data #(b/fixture {:seed 1} (b/rect "hero" {})))]
    (t/is (= ::b/invalid-label (:type data)))))

(t/deftest protected-keys-cannot-be-overridden
  (let [other    (uuid/custom 9 9)
        instance (b/fixture {:seed 1}
                            (b/rect {:type :circle :id other :parent-id other}))
        rect     (first (rect-shapes instance))]
    (t/is (= :rect (:type rect)))
    (t/is (not= other (:id rect)))
    (t/is (= uuid/zero (:parent-id rect)))))

(t/deftest root-protected-keys-cannot-be-overridden
  (let [other    (uuid/custom 9 8)
        instance (b/fixture {:seed 1
                             :root {:id other :type :rect :width 10}}
                            (b/rect))
        root     (get-in instance [:objects uuid/zero])]
    (t/is (= uuid/zero (:id root)))
    (t/is (= :frame (:type root)))
    (t/is (= 10 (:width root)))))

(t/deftest attrs-override-generated-fills
  (let [fill     {:fill-color "#ffffff" :fill-opacity 1}
        instance (b/fixture {:seed 1}
                            (b/rect {:fills [fill]}))
        rect     (first (rect-shapes instance))]
    (t/is (= [fill] (:fills rect)))))

(t/deftest generator-helpers-produce-expected-values
  (let [instance (b/fixture {:seed 3
                             :defaults {:rect {:opacity (b/gen-float 0.5 0.6)
                                               :x (b/gen-one-of [4 5])
                                               :r1 (b/gen-int 3 4)
                                               :fills (b/gen-vector (b/gen-fill))}}}
                            (b/rect))
        rect     (first (rect-shapes instance))]
    (t/is (<= 0.5 (:opacity rect) 0.6))
    (t/is (contains? #{4 5} (:x rect)))
    (t/is (= 3 (:r1 rect)))
    (t/is (re-matches #"^#[0-9a-f]{6}$" (:fill-color (first (:fills rect)))))))

(t/deftest empty-one-of-is-rejected
  (let [data (failure-data #(b/gen-one-of []))]
    (t/is (= ::b/no-values (:type data)))))
