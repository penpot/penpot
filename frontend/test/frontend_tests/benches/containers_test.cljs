;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.benches.containers-test
  (:require
   [app.common.uuid :as uuid]
   [benches.render-wasm.scenes.builder :as builder :include-macros true]
   [benches.render-wasm.scenes.common :as common]
   [cljs.test :as t :include-macros true]))

(defn- failure-data
  [f]
  (try
    (f)
    nil
    (catch :default cause
      (ex-data cause))))

(defn- child-ids
  "Ids listed in the :shapes vector of `id`."
  [instance id]
  (get-in instance [:objects id :shapes]))

(defn- children-of
  "Child shapes of `id` in order."
  [instance id]
  (mapv (:objects instance) (child-ids instance id)))

(defn- run-scope
  "Runs `f` inside a raw scope and returns the scope state, so tests can
  inspect partial builds without calling finish!."
  [params f]
  (let [state (builder/start params)]
    (binding [builder/*state* state
              builder/*defaults* {}]
      (f))
    state))

(t/deftest frame-attaches-children-in-order
  (let [instance (builder/fixture {:seed 1}
                                  (builder/frame {:x 0 :y 0 :width 100 :height 100 :name "F"}
                                                 (builder/rect {:x 10 :y 10 :width 5 :height 5})
                                                 (builder/rect {:x 20 :y 20 :width 5 :height 5})))
        frame    (first (children-of instance uuid/zero))
        rects    (children-of instance (:id frame))]
    (t/is (= :frame (:type frame)))
    (t/is (= [(:id frame)] (child-ids instance uuid/zero)))
    (t/is (= 2 (count rects)))
    (t/is (every? #(= (:id frame) (:parent-id %)) rects))
    (t/is (every? #(= (:id frame) (:frame-id %)) rects))
    (t/is (= [10 20] (mapv :x rects)))
    (t/is (= [10 20] (mapv :y rects)))
    (t/is (= [100 100] [(:width frame) (:height frame)]))))

(t/deftest nested-frames-keep-frame-ids
  (let [instance (builder/fixture {:seed 1}
                                  (builder/frame {:x 0 :y 0 :width 100 :height 100 :name "Outer"}
                                                 (builder/frame {:x 10 :y 10 :width 50 :height 50 :name "Inner"}
                                                                (builder/rect {:x 20 :y 20 :width 5 :height 5}))))
        outer    (first (children-of instance uuid/zero))
        inner    (first (children-of instance (:id outer)))
        rect     (first (children-of instance (:id inner)))]
    (t/is (= uuid/zero (:frame-id outer)))
    (t/is (= (:id outer) (:parent-id inner)))
    (t/is (= (:id outer) (:frame-id inner)))
    (t/is (= (:id inner) (:parent-id rect)))
    (t/is (= (:id inner) (:frame-id rect)))
    (t/is (= [20 20] [(:x rect) (:y rect)]))))

(t/deftest group-derives-bounds-from-children
  (let [instance (builder/fixture {:seed 1}
                                  (builder/group {}
                                                 (builder/rect {:x 10 :y 20 :width 30 :height 40})
                                                 (builder/rect {:x 50 :y 80 :width 10 :height 10})))
        group    (first (children-of instance uuid/zero))
        rects    (children-of instance (:id group))]
    (t/is (= :group (:type group)))
    (t/is (= [10 20 50 70]
             [(:x group) (:y group) (:width group) (:height group)]))
    (t/is (= [10 20 50 70]
             [(:x (:selrect group)) (:y (:selrect group))
              (:width (:selrect group)) (:height (:selrect group))]))
    (t/is (every? #(= uuid/zero (:frame-id %)) rects))
    (t/is (every? #(= (:id group) (:parent-id %)) rects))))

(t/deftest nested-groups-derive-inside-out
  (let [instance (builder/fixture {:seed 1}
                                  (builder/group {:name "Outer"}
                                                 (builder/rect {:x 0 :y 0 :width 10 :height 10})
                                                 (builder/group {:name "Inner"}
                                                                (builder/rect {:x 100 :y 100 :width 10 :height 10}))))
        outer    (first (children-of instance uuid/zero))
        inner    (second (children-of instance (:id outer)))]
    (t/is (= :group (:type inner)))
    (t/is (= [100 100 10 10]
             [(:x inner) (:y inner) (:width inner) (:height inner)]))
    (t/is (= [0 0 110 110]
             [(:x outer) (:y outer) (:width outer) (:height outer)]))))

(t/deftest group-inside-frame-points-at-the-frame
  (let [instance (builder/fixture {:seed 1}
                                  (builder/frame {:x 0 :y 0 :width 200 :height 200}
                                                 (builder/group {}
                                                                (builder/rect {:x 10 :y 10 :width 10 :height 10}))))
        frame    (first (children-of instance uuid/zero))
        group    (first (children-of instance (:id frame)))
        rect     (first (children-of instance (:id group)))]
    (t/is (= (:id frame) (:frame-id group)))
    (t/is (= (:id frame) (:frame-id rect)))
    (t/is (= (:id group) (:parent-id rect)))))

(t/deftest masked-group-takes-the-first-child-geometry
  (let [instance (builder/fixture {:seed 1}
                                  (builder/group {:masked-group true}
                                                 (builder/rect {:x 10 :y 20 :width 30 :height 40})
                                                 (builder/rect {:x 0 :y 0 :width 500 :height 500})))
        group    (first (children-of instance uuid/zero))]
    (t/is (true? (:masked-group group)))
    (t/is (= [10 20 30 40]
             [(:x group) (:y group) (:width group) (:height group)]))))

(t/deftest container-labels-are-instance-local
  (let [instance (builder/fixture {:seed 1}
                                  (builder/frame :hero {:x 0 :y 0 :width 10 :height 10}
                                                 (builder/group [:g 1] {}
                                                                (builder/rect))))
        refs     (:refs instance)]
    (t/is (= #{:hero [:g 1]} (set (keys refs))))
    (t/is (= (get refs :hero) (first (child-ids instance uuid/zero))))
    (t/is (= (get refs [:g 1]) (first (child-ids instance (get refs :hero)))))
    (t/is (= 1 (count (child-ids instance uuid/zero))))))

(t/deftest nested-order-follows-construction
  (let [instance (builder/fixture {:seed 1}
                                  (builder/frame {:x 0 :y 0 :width 100 :height 100}
                                                 (builder/rect)
                                                 (builder/group {}
                                                                (builder/rect))))
        order    (mapv :type (common/upload-order instance))]
    (t/is (= [:frame :frame :rect :group :rect] order))))

(t/deftest empty-group-is-rejected
  (let [data (failure-data #(builder/fixture {:seed 1} (builder/group {})))]
    (t/is (= ::builder/empty-group (:type data)))))

(t/deftest group-geometry-attrs-are-rejected
  (let [data (failure-data
              #(builder/fixture {:seed 1}
                                (builder/group {:x 1}
                                               (builder/rect))))]
    (t/is (= ::builder/group-geometry (:type data)))
    (t/is (= :x (:key data)))))

(t/deftest frame-requires-bounds
  (let [data (failure-data
              #(builder/fixture {:seed 1}
                                (builder/frame {:name "F"}
                                               (builder/rect))))]
    (t/is (= ::builder/frame-bounds (:type data)))))

(t/deftest runtime-attrs-expressions-are-accepted
  (let [frame-attrs {:x 0 :y 0 :width 10 :height 10}
        instance    (builder/fixture {:seed 1}
                                     (builder/frame frame-attrs
                                                    (builder/rect)))
        frame       (first (children-of instance uuid/zero))]
    (t/is (= :frame (:type frame)))
    (t/is (= 10 (:width frame)))))

(t/deftest non-map-attrs-are-rejected
  (t/is (= ::builder/invalid-attrs
           (:type (failure-data
                   #(builder/fixture {:seed 1}
                                     (builder/frame :hero (builder/rect)))))))
  (let [state (run-scope {:seed 1}
                         (fn []
                           (try
                             (builder/frame :hero (builder/rect))
                             (catch :default _ nil))))]
    (t/is (= 1 (count (child-ids @state uuid/zero))))))

(t/deftest non-numeric-frame-bounds-are-rejected
  (let [data (failure-data
              #(builder/fixture {:seed 1}
                                (builder/frame {:x 0 :y 0 :width "100" :height 10}
                                               (builder/rect))))]
    (t/is (= ::builder/frame-bounds (:type data)))
    (t/is (= :width (:invalid data)))))

(t/deftest duplicate-container-labels-are-rejected
  (let [data (failure-data
              #(builder/fixture {:seed 1}
                                (builder/frame :dup {:x 0 :y 0 :width 10 :height 10}
                                               (builder/rect))
                                (builder/group :dup {}
                                               (builder/rect))))]
    (t/is (= ::builder/duplicate-label (:type data)))
    (t/is (= :dup (:label data)))))

(t/deftest frame-inside-group-keeps-frame-ids
  (let [instance (builder/fixture {:seed 1}
                                  (builder/group {}
                                                 (builder/frame {:x 0 :y 0 :width 50 :height 50}
                                                                (builder/rect))))
        group    (first (children-of instance uuid/zero))
        frame    (first (children-of instance (:id group)))
        rect     (first (children-of instance (:id frame)))]
    (t/is (= (:id group) (:parent-id frame)))
    (t/is (= uuid/zero (:frame-id frame)))
    (t/is (= (:id frame) (:parent-id rect)))
    (t/is (= (:id frame) (:frame-id rect)))))

(t/deftest group-of-container-derives-bounds
  (let [instance (builder/fixture {:seed 1}
                                  (builder/group {}
                                                 (builder/group {}
                                                                (builder/rect {:x 5 :y 5 :width 10 :height 10}))))
        outer    (first (children-of instance uuid/zero))
        inner    (first (children-of instance (:id outer)))]
    (t/is (= [5 5 10 10]
             [(:x outer) (:y outer) (:width outer) (:height outer)]))
    (t/is (= [5 5 10 10]
             [(:x inner) (:y inner) (:width inner) (:height inner)]))
    (t/is (= 1 (count (child-ids instance (:id outer)))))))

(t/deftest empty-frame-is-allowed
  (let [instance (builder/fixture {:seed 1}
                                  (builder/frame {:x 0 :y 0 :width 10 :height 10}))
        frame    (first (children-of instance uuid/zero))]
    (t/is (= [] (child-ids instance (:id frame))))))

(t/deftest caught-empty-group-still-fails-finish
  (let [state (run-scope {:seed 1}
                         (fn []
                           (try
                             (builder/group {})
                             (catch :default _ nil))))]
    (t/is (= ::builder/unfinished-container
             (:type (failure-data #(builder/finish! state)))))))

(t/deftest container-failure-restores-parent-and-marks-unfinished
  (let [state (run-scope {:seed 1}
                         (fn []
                           (builder/rect {:x 0})
                           (try
                             (builder/frame {:x 0 :y 0 :width 10 :height 10}
                                            (builder/rect :dup {:x 1})
                                            (builder/rect :dup {:x 2}))
                             (catch :default _ nil))
                           (builder/rect {:x 3})))
        root-children (child-ids @state uuid/zero)
        last-child    (get-in @state [:objects (last root-children)])]
    (t/is (= 3 (count root-children)))
    (t/is (= :rect (:type last-child)))
    (t/is (= 3 (:x last-child)))
    (t/is (= 1 (count (:unfinished @state))))
    (t/is (= ::builder/unfinished-container
             (:type (failure-data #(builder/finish! state)))))))
