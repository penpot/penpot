;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns performance-tests.render-wasm.boolean-test
  (:require
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.intersect :as gint]
   [app.common.types.path :as path]
   [app.common.types.path.segment :as segm]
   [app.common.uuid :as uuid]
   [benches.render-wasm.builder :as b :include-macros true]
   [benches.render-wasm.snapshot :as snapshot]
   [cljs.test :as t :include-macros true]
   [performance-tests.render-wasm.test-helpers :as helpers]))

(t/deftest difference-of-overlapping-rects-keeps-hierarchy-and-bounds
  (let [red      {:fill-color "#ff0000" :fill-opacity 1}
        blue     {:fill-color "#0000ff" :fill-opacity 1}
        instance (b/scene {:seed 1}
                          (b/bool :l-shape {:bool-type :difference :name "L"}
                                  (b/rect {:x 0 :y 0 :width 100 :height 100 :fills [red]})
                                  (b/rect {:x 50 :y 50 :width 100 :height 100 :fills [blue]})))
        bool     (first (helpers/children-of instance uuid/zero))
        children (helpers/children-of instance (:id bool))]
    (t/is (= :bool (:type bool)))
    (t/is (= :difference (:bool-type bool)))
    (t/is (= (:id bool) (snapshot/ref-id instance :l-shape)))
    (t/is (= 2 (count children)))
    (t/is (= [(:id (first children)) (:id (second children))] (:shapes bool)))
    (t/is (= [(:id bool) (:id bool)]
             [(:parent-id (first children)) (:parent-id (second children))]))
    (t/is (= [uuid/zero uuid/zero]
             [(:frame-id (first children)) (:frame-id (second children))]))
    (t/is (= [0 0 100 100] (helpers/selrect bool)))))

(t/deftest difference-excludes-points-in-removed-overlap
  (let [instance (b/scene {:seed 1}
                          (b/bool {:bool-type :difference}
                                  (b/rect {:x 0 :y 0 :width 100 :height 100})
                                  (b/rect {:x 50 :y 50 :width 100 :height 100})))
        bool     (first (helpers/children-of instance uuid/zero))
        lines    (segm/path->lines bool)]
    (t/is (true? (gint/is-point-inside-nonzero? (gpt/point 25 25) lines))
          "a point in the kept region stays inside the content")
    (t/is (false? (gint/is-point-inside-nonzero? (gpt/point 75 75) lines))
          "a point in the subtracted overlap is excluded from the content")))

(t/deftest union-derives-canonical-bounds
  (let [instance (b/scene {:seed 1}
                          (b/bool {:bool-type :union}
                                  (b/rect {:x 0 :y 0 :width 100 :height 100})
                                  (b/rect {:x 50 :y 50 :width 100 :height 100})))
        bool     (first (helpers/children-of instance uuid/zero))]
    (t/is (= :union (:bool-type bool)))
    (t/is (= [0 0 150 150] (helpers/selrect bool)))))

(t/deftest difference-picks-the-first-child-as-style-head
  (let [red        {:fill-color "#ff0000" :fill-opacity 1}
        blue       {:fill-color "#0000ff" :fill-opacity 1}
        blur       {:id (uuid/custom 3 3) :type :layer-blur :value 4 :hidden false}
        difference (b/scene {:seed 1}
                            (b/bool {:bool-type :difference}
                                    (b/rect {:x 0 :y 0 :width 10 :height 10
                                             :fills [red] :blur blur})
                                    (b/rect {:x 5 :y 0 :width 10 :height 10
                                             :fills [blue]})))
        diff-bool  (first (helpers/children-of difference uuid/zero))]
    (t/is (= [red] (:fills diff-bool)))
    (t/is (= blur (:blur diff-bool)))))

(t/deftest supplied-styles-override-inherited
  (let [instance (b/scene {:seed 1}
                          (b/bool {:bool-type :union :fills []}
                                  (b/rect {:x 0 :y 0 :width 10 :height 10
                                           :fills [{:fill-color "#ff0000" :fill-opacity 1}]})
                                  (b/rect {:x 5 :y 0 :width 10 :height 10
                                           :fills [{:fill-color "#0000ff" :fill-opacity 1}]})))
        bool     (first (helpers/children-of instance uuid/zero))]
    (t/is (= [] (:fills bool)))))

(t/deftest nil-styles-do-not-count-as-supplied
  (let [red      {:fill-color "#ff0000" :fill-opacity 1}
        blue     {:fill-color "#0000ff" :fill-opacity 1}
        instance (b/scene {:seed 1}
                          (b/bool {:bool-type :union :fills nil}
                                  (b/rect {:x 0 :y 0 :width 10 :height 10 :fills [red]})
                                  (b/rect {:x 5 :y 0 :width 10 :height 10 :fills [blue]})))
        bool     (first (helpers/children-of instance uuid/zero))]
    (t/is (= [blue] (:fills bool)))))

(t/deftest single-child-bool-uses-the-child-path
  (let [instance (b/scene {:seed 1}
                          (b/bool {:bool-type :difference}
                                  (b/rect {:x 10 :y 20 :width 30 :height 40})))
        bool     (first (helpers/children-of instance uuid/zero))]
    (t/is (= 1 (count (:shapes bool))))
    (t/is (= [10 20 30 40] (helpers/selrect bool)))
    (t/is (= (:selrect bool) (path/calc-selrect (:content bool))))))

(t/deftest nested-bools-finalize-inside-out
  (let [instance (b/scene {:seed 1}
                          (b/bool {:bool-type :union}
                                  (b/bool {:bool-type :difference}
                                          (b/rect {:x 0 :y 0 :width 100 :height 100})
                                          (b/rect {:x 50 :y 50 :width 100 :height 100}))
                                  (b/rect {:x 200 :y 0 :width 50 :height 50})))
        outer    (first (helpers/children-of instance uuid/zero))
        inner    (first (helpers/children-of instance (:id outer)))]
    (t/is (= :bool (:type inner)))
    (t/is (= [0 0 100 100] (helpers/selrect inner)))
    (t/is (= [0 0 250 100] (helpers/selrect outer)))
    (t/is (= (:selrect outer) (path/calc-selrect (:content outer))))))

(t/deftest bool-inside-frame-keeps-frame-ids
  (let [instance (b/scene {:seed 1}
                          (b/frame {:x 0 :y 0 :width 200 :height 200}
                                   (b/bool {:bool-type :union}
                                           (b/rect {:x 10 :y 10 :width 10 :height 10})
                                           (b/rect {:x 30 :y 10 :width 10 :height 10}))))
        frame    (first (helpers/children-of instance uuid/zero))
        bool     (first (helpers/children-of instance (:id frame)))
        rects    (helpers/children-of instance (:id bool))]
    (t/is (= (:id frame) (:parent-id bool)))
    (t/is (= (:id frame) (:frame-id bool)))
    (t/is (= [(:id frame) (:id frame)] (mapv :frame-id rects)))
    (t/is (= [(:id bool) (:id bool)] (mapv :parent-id rects)))))

(t/deftest bool-inside-group-derives-group-bounds
  (let [instance (b/scene {:seed 1}
                          (b/group {}
                                   (b/bool {:bool-type :union}
                                           (b/rect {:x 10 :y 20 :width 30 :height 40})
                                           (b/rect {:x 50 :y 80 :width 10 :height 10}))))
        group    (first (helpers/children-of instance uuid/zero))
        bool     (first (helpers/children-of instance (:id group)))]
    (t/is (= :bool (:type bool)))
    (t/is (= [10 20 50 70] (helpers/selrect group)))
    (t/is (= uuid/zero (:frame-id bool)))))

(t/deftest empty-bool-is-rejected
  (let [data (helpers/failure-data #(b/scene {:seed 1} (b/bool {:bool-type :union})))]
    (t/is (= ::b/empty-bool (:type data)))))

(t/deftest caught-empty-bool-still-fails-finish
  (let [state (helpers/run-scope {:seed 1}
                                 (fn []
                                   (try
                                     (b/bool {:bool-type :union})
                                     (catch :default _ nil))))]
    (t/is (= ::b/unfinished-container
             (:type (helpers/failure-data #(b/finish! state)))))))

(t/deftest bool-geometry-attrs-are-rejected
  (doseq [attrs [{:bool-type :union :x 1}
                 {:bool-type :union :selrect {:x 0 :y 0 :width 1 :height 1}}
                 {:bool-type :union :content []}]]
    (let [data (helpers/failure-data #(b/scene {:seed 1} (b/bool attrs (b/rect))))]
      (t/is (= ::b/bool-geometry (:type data)) (str attrs))
      (t/is (contains? #{:x :selrect :content} (:key data)) (str attrs)))))

(t/deftest bool-transform-attrs-are-rejected
  (doseq [attrs [{:bool-type :union :rotation 45}
                 {:bool-type :union :transform "m"}
                 {:bool-type :union :transform-inverse "m"}
                 {:bool-type :union :flip-x true}
                 {:bool-type :union :flip-y true}]]
    (let [data (helpers/failure-data #(b/scene {:seed 1} (b/bool attrs (b/rect))))]
      (t/is (= ::b/bool-transform (:type data)) (str attrs)))))

(t/deftest bool-type-is-required-and-validated
  (t/is (= ::b/bool-type
           (:type (helpers/failure-data #(b/scene {:seed 1} (b/bool {:name "B"} (b/rect)))))))
  (let [data (helpers/failure-data #(b/scene {:seed 1} (b/bool {:bool-type :merge} (b/rect))))]
    (t/is (= ::b/bool-type (:type data)))
    (t/is (= :merge (:bool-type data)))
    (t/is (= #{:union :difference :intersection :exclude} (set (:supported data))))))

(t/deftest frame-child-is-rejected
  (let [data (helpers/failure-data
              #(b/scene {:seed 1}
                        (b/bool {:bool-type :union}
                                (b/frame {:x 0 :y 0 :width 10 :height 10}
                                         (b/rect)))))]
    (t/is (= ::b/frame-in-bool (:type data)))
    (t/is (uuid? (:child data)))))

(t/deftest bool-generation-ignores-the-renderer-override
  (let [original path/wasm:calc-bool-content]
    (try
      (set! path/wasm:calc-bool-content
            (fn [& _]
              (throw (ex-info "renderer override ran during generation"
                              {:type ::unexpected-renderer-call}))))
      (let [instance (b/scene {:seed 1}
                              (b/bool {:bool-type :difference}
                                      (b/rect {:x 0 :y 0 :width 100 :height 100})
                                      (b/rect {:x 50 :y 50 :width 100 :height 100})))
            bool     (first (helpers/children-of instance uuid/zero))]
        (t/is (= [0 0 100 100] (helpers/selrect bool))))
      (finally
        (set! path/wasm:calc-bool-content original)))))

(t/deftest non-map-bool-attrs-are-rejected
  (t/is (= ::b/invalid-attrs
           (:type (helpers/failure-data #(b/scene {:seed 1} (b/bool :hero (b/rect)))))))
  (let [state (helpers/run-scope {:seed 1}
                                 (fn []
                                   (try
                                     (b/bool :hero (b/rect))
                                     (catch :default _ nil))))]
    (t/is (= 1 (count (get-in @state [:objects uuid/zero :shapes]))))))
