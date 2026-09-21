;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.benches.boolean-fixture-test
  (:require
   [app.common.types.path :as path]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [benches.render-wasm.scenes.builder :as b :include-macros true]
   [benches.render-wasm.scenes.common :as common]
   [cljs.test :as t :include-macros true]))

(defn- failure-data
  [f]
  (try
    (f)
    nil
    (catch :default cause
      (ex-data cause))))

(defn- selrect
  [shape]
  [(:x (:selrect shape)) (:y (:selrect shape))
   (:width (:selrect shape)) (:height (:selrect shape))])

(defn- children-of
  [instance id]
  (mapv (:objects instance) (get-in instance [:objects id :shapes])))

(defn- run-scope
  "Runs `f` inside a raw scope and returns the scope state, so tests can
  inspect partial builds without calling finish!."
  [params f]
  (let [state (b/start params)]
    (binding [b/*state* state
              b/*defaults* {}]
      (f))
    state))

(t/deftest difference-of-overlapping-rects-derives-the-l-shape
  (let [red      {:fill-color "#ff0000" :fill-opacity 1}
        blue     {:fill-color "#0000ff" :fill-opacity 1}
        instance (b/fixture {:seed 1}
                            (b/bool :l-shape {:bool-type :difference :name "L"}
                                    (b/rect {:x 0 :y 0 :width 100 :height 100 :fills [red]})
                                    (b/rect {:x 50 :y 50 :width 100 :height 100 :fills [blue]})))
        bool     (first (children-of instance uuid/zero))
        children (children-of instance (:id bool))]
    (t/is (= :bool (:type bool)))
    (t/is (= :difference (:bool-type bool)))
    (t/is (= (:id bool) (common/ref-id instance :l-shape)))
    (t/is (= 2 (count children)))
    (t/is (= [(:id (first children)) (:id (second children))] (:shapes bool)))
    (t/is (= [(:id bool) (:id bool)]
             [(:parent-id (first children)) (:parent-id (second children))]))
    (t/is (= [uuid/zero uuid/zero]
             [(:frame-id (first children)) (:frame-id (second children))]))
    (t/is (= [0 0 100 100] (selrect bool)))
    (t/is (= (:selrect bool) (path/calc-selrect (:content bool))))
    (t/is (= 4 (count (:points bool))))
    (t/is (= #{[0 0] [100 0] [100 50] [50 50] [50 100] [0 100]}
             (set (map (juxt :x :y) (path/get-points (:content bool))))))))

(t/deftest union-intersection-and-exclude-derive-canonical-bounds
  (doseq [[bool-type expected] [[:union [0 0 150 150]]
                                [:intersection [50 50 50 50]]
                                [:exclude [0 0 150 150]]]]
    (let [instance (b/fixture {:seed 1}
                              (b/bool {:bool-type bool-type}
                                      (b/rect {:x 0 :y 0 :width 100 :height 100})
                                      (b/rect {:x 50 :y 50 :width 100 :height 100})))
          bool     (first (children-of instance uuid/zero))]
      (t/is (= bool-type (:bool-type bool)))
      (t/is (= expected (selrect bool)) (str bool-type)))))

(t/deftest operation-picks-the-canonical-style-head
  (let [red        {:fill-color "#ff0000" :fill-opacity 1}
        blue       {:fill-color "#0000ff" :fill-opacity 1}
        blur       {:id (uuid/custom 3 3) :type :layer-blur :value 4 :hidden false}
        difference (b/fixture {:seed 1}
                              (b/bool {:bool-type :difference}
                                      (b/rect {:x 0 :y 0 :width 10 :height 10
                                               :fills [red] :blur blur})
                                      (b/rect {:x 5 :y 0 :width 10 :height 10
                                               :fills [blue]})))
        union      (b/fixture {:seed 1}
                              (b/bool {:bool-type :union}
                                      (b/rect {:x 0 :y 0 :width 10 :height 10
                                               :fills [red] :blur blur})
                                      (b/rect {:x 5 :y 0 :width 10 :height 10
                                               :fills [blue]})))
        diff-bool  (first (children-of difference uuid/zero))
        union-bool (first (children-of union uuid/zero))]
    (t/is (= [red] (:fills diff-bool)))
    (t/is (= blur (:blur diff-bool)))
    (t/is (= [blue] (:fills union-bool)))
    (t/is (nil? (:blur union-bool)))))

(t/deftest supplied-styles-override-inherited
  (let [instance (b/fixture {:seed 1}
                            (b/bool {:bool-type :union :fills []}
                                    (b/rect {:x 0 :y 0 :width 10 :height 10
                                             :fills [{:fill-color "#ff0000" :fill-opacity 1}]})
                                    (b/rect {:x 5 :y 0 :width 10 :height 10
                                             :fills [{:fill-color "#0000ff" :fill-opacity 1}]})))
        bool     (first (children-of instance uuid/zero))]
    (t/is (= [] (:fills bool)))))

(t/deftest nil-styles-do-not-count-as-supplied
  (let [red      {:fill-color "#ff0000" :fill-opacity 1}
        blue     {:fill-color "#0000ff" :fill-opacity 1}
        instance (b/fixture {:seed 1}
                            (b/bool {:bool-type :union :fills nil}
                                    (b/rect {:x 0 :y 0 :width 10 :height 10 :fills [red]})
                                    (b/rect {:x 5 :y 0 :width 10 :height 10 :fills [blue]})))
        bool     (first (children-of instance uuid/zero))]
    (t/is (= [blue] (:fills bool)))))

(t/deftest single-child-bool-uses-the-child-path
  (let [instance (b/fixture {:seed 1}
                            (b/bool {:bool-type :difference}
                                    (b/rect {:x 10 :y 20 :width 30 :height 40})))
        bool     (first (children-of instance uuid/zero))]
    (t/is (= 1 (count (:shapes bool))))
    (t/is (= [10 20 30 40] (selrect bool)))
    (t/is (= (:selrect bool) (path/calc-selrect (:content bool))))))

(t/deftest nested-bools-finalize-inside-out
  (let [instance (b/fixture {:seed 1}
                            (b/bool {:bool-type :union}
                                    (b/bool {:bool-type :difference}
                                            (b/rect {:x 0 :y 0 :width 100 :height 100})
                                            (b/rect {:x 50 :y 50 :width 100 :height 100}))
                                    (b/rect {:x 200 :y 0 :width 50 :height 50})))
        outer    (first (children-of instance uuid/zero))
        inner    (first (children-of instance (:id outer)))]
    (t/is (= :bool (:type inner)))
    (t/is (= [0 0 100 100] (selrect inner)))
    (t/is (= [0 0 250 100] (selrect outer)))
    (t/is (= (:selrect outer) (path/calc-selrect (:content outer))))))

(t/deftest bool-inside-frame-keeps-frame-ids
  (let [instance (b/fixture {:seed 1}
                            (b/frame {:x 0 :y 0 :width 200 :height 200}
                                     (b/bool {:bool-type :union}
                                             (b/rect {:x 10 :y 10 :width 10 :height 10})
                                             (b/rect {:x 30 :y 10 :width 10 :height 10}))))
        frame    (first (children-of instance uuid/zero))
        bool     (first (children-of instance (:id frame)))
        rects    (children-of instance (:id bool))]
    (t/is (= (:id frame) (:parent-id bool)))
    (t/is (= (:id frame) (:frame-id bool)))
    (t/is (= [(:id frame) (:id frame)] (mapv :frame-id rects)))
    (t/is (= [(:id bool) (:id bool)] (mapv :parent-id rects)))))

(t/deftest bool-inside-group-derives-group-bounds
  (let [instance (b/fixture {:seed 1}
                            (b/group {}
                                     (b/bool {:bool-type :union}
                                             (b/rect {:x 10 :y 20 :width 30 :height 40})
                                             (b/rect {:x 50 :y 80 :width 10 :height 10}))))
        group    (first (children-of instance uuid/zero))
        bool     (first (children-of instance (:id group)))]
    (t/is (= :bool (:type bool)))
    (t/is (= [10 20 50 70] (selrect group)))
    (t/is (= uuid/zero (:frame-id bool)))))

(t/deftest empty-bool-is-rejected
  (let [data (failure-data #(b/fixture {:seed 1} (b/bool {:bool-type :union})))]
    (t/is (= ::b/empty-bool (:type data)))))

(t/deftest caught-empty-bool-still-fails-finish
  (let [state (run-scope {:seed 1}
                         (fn []
                           (try
                             (b/bool {:bool-type :union})
                             (catch :default _ nil))))]
    (t/is (= ::b/unfinished-container
             (:type (failure-data #(b/finish! state)))))))

(t/deftest bool-geometry-attrs-are-rejected
  (doseq [attrs [{:bool-type :union :x 1}
                 {:bool-type :union :selrect {:x 0 :y 0 :width 1 :height 1}}
                 {:bool-type :union :content []}]]
    (let [data (failure-data #(b/fixture {:seed 1} (b/bool attrs (b/rect))))]
      (t/is (= ::b/bool-geometry (:type data)) (str attrs))
      (t/is (contains? #{:x :selrect :content} (:key data)) (str attrs)))))

(t/deftest bool-transform-attrs-are-rejected
  (doseq [attrs [{:bool-type :union :rotation 45}
                 {:bool-type :union :transform "m"}
                 {:bool-type :union :transform-inverse "m"}
                 {:bool-type :union :flip-x true}
                 {:bool-type :union :flip-y true}]]
    (let [data (failure-data #(b/fixture {:seed 1} (b/bool attrs (b/rect))))]
      (t/is (= ::b/bool-transform (:type data)) (str attrs)))))

(t/deftest bool-type-is-required-and-validated
  (t/is (= ::b/bool-type
           (:type (failure-data #(b/fixture {:seed 1} (b/bool {:name "B"} (b/rect)))))))
  (let [data (failure-data #(b/fixture {:seed 1} (b/bool {:bool-type :merge} (b/rect))))]
    (t/is (= ::b/bool-type (:type data)))
    (t/is (= :merge (:bool-type data)))
    (t/is (= cts/bool-types (:supported data)))))

(t/deftest frame-child-is-rejected
  (let [data (failure-data
              #(b/fixture {:seed 1}
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
      (let [instance (b/fixture {:seed 1}
                                (b/bool {:bool-type :difference}
                                        (b/rect {:x 0 :y 0 :width 100 :height 100})
                                        (b/rect {:x 50 :y 50 :width 100 :height 100})))
            bool     (first (children-of instance uuid/zero))]
        (t/is (= [0 0 100 100] (selrect bool))))
      (finally
        (set! path/wasm:calc-bool-content original)))))

(t/deftest non-map-bool-attrs-are-rejected
  (t/is (= ::b/invalid-attrs
           (:type (failure-data #(b/fixture {:seed 1} (b/bool :hero (b/rect)))))))
  (let [state (run-scope {:seed 1}
                         (fn []
                           (try
                             (b/bool :hero (b/rect))
                             (catch :default _ nil))))]
    (t/is (= 1 (count (get-in @state [:objects uuid/zero :shapes]))))))
