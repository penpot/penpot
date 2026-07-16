;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns common-tests.files-shape-compact-test
  (:require
   [app.common.files.shape-compact :as sc]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.math :as mth]
   [app.common.schema :as sm]
   [app.common.schema.generators :as sg]
   [app.common.schema.test :as smt]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

;; --- Helpers

(defn- make-rect
  [props]
  (cts/setup-shape (merge {:type :rect :x 0 :y 0 :width 10 :height 10} props)))

(defn- make-path
  [props]
  (cts/setup-shape
   (merge {:type :path
           :content [{:command :move-to :params {:x 0 :y 0}}
                     {:command :line-to :params {:x 10 :y 10}}]}
          props)))

;; --- round-values

(t/deftest round-values-integers-unchanged
  (t/is (= 5 (sc/round-values 5)))
  (t/is (= 0 (sc/round-values 0))))

(t/deftest round-values-floats-rounded
  (t/is (= 0.6 (sc/round-values 0.6000000238418579)))
  (t/is (= 4213.6 (sc/round-values 4213.60009765625)))
  (t/is (= 0.1235 (sc/round-values 0.12345678))))

(t/deftest round-values-nested-maps
  (let [data {:x 100.1234567 :y 200.9876543
              :meta {:opacity 0.6000000238418579}}]
    (t/is (= {:x 100.1235 :y 200.9877
              :meta {:opacity 0.6}}
            (sc/round-values data)))))

(t/deftest round-values-vectors
  (let [data [1.2345678 2.00001 3]]
    (t/is (= [1.2346 2.0 3] (sc/round-values data)))))

(t/deftest round-values-non-numeric-unchanged
  (t/is (= "hello" (sc/round-values "hello")))
  (t/is (= :foo (sc/round-values :foo)))
  (t/is (= true (sc/round-values true)))
  (t/is (nil? (sc/round-values nil)))
  (let [id (uuid/next)]
    (t/is (= id (sc/round-values id)))))

;; --- compact-shape

(t/deftest compact-shape-removes-nils
  (let [shape {:id (uuid/next) :type :rect :name "test"
               :flip-x nil :flip-y nil
               :fills [] :strokes []}
        c (sc/compact-shape shape)]
    (t/is (not (contains? c :flip-x)))
    (t/is (not (contains? c :flip-y)))
    (t/is (not (contains? c :fills)))
    (t/is (not (contains? c :strokes)))))

(t/deftest compact-shape-removes-identity-transform
  (let [shape (make-rect {:x 100 :y 200 :width 50 :height 30})
        c     (sc/compact-shape shape)]
    (t/is (not (contains? c :transform)))
    (t/is (not (contains? c :transform-inverse)))))

(t/deftest compact-shape-keeps-non-identity-transform
  (let [shape (-> (make-rect {:x 100 :y 200 :width 50 :height 30})
                  (assoc :transform (gmt/matrix 1 0.5 0 1 0 0)))
        c     (sc/compact-shape shape)]
    (t/is (contains? c :transform))
    (t/is (contains? c :transform-inverse))))

(t/deftest compact-shape-removes-derivable-geometry-rect
  (let [shape (make-rect {:x 100 :y 200 :width 50 :height 30})
        c     (sc/compact-shape shape)]
    (t/is (not (contains? c :selrect)))
    (t/is (not (contains? c :points)))))

(t/deftest compact-shape-keeps-geometry-with-rotation
  (let [shape (-> (make-rect {:x 100 :y 200 :width 50 :height 30})
                  (assoc :rotation 45))
        c     (sc/compact-shape shape)]
    (t/is (contains? c :selrect))
    (t/is (contains? c :points))))

(t/deftest compact-shape-path-keeps-selrect
  (let [shape (make-path {})
        c     (sc/compact-shape shape)]
    (t/is (contains? c :selrect))
    (t/is (not (contains? c :points)))
    (t/is (contains? c :content))))

(t/deftest compact-shape-removes-page-id
  (let [shape (assoc (make-rect {}) :page-id (uuid/next))
        c     (sc/compact-shape shape)]
    (t/is (not (contains? c :page-id)))))

(t/deftest compact-shape-removes-defaults
  (let [shape (make-rect {:rotation 0 :proportion-lock false :blocked false})
        c     (sc/compact-shape shape)]
    (t/is (not (contains? c :rotation)))
    (t/is (not (contains? c :proportion-lock)))
    (t/is (not (contains? c :blocked)))))

(t/deftest compact-shape-keeps-non-defaults
  (let [shape (make-rect {:rotation 30 :blocked true :opacity 0.5})
        c     (sc/compact-shape shape)]
    (t/is (= 30 (:rotation c)))
    (t/is (true? (:blocked c)))
    (t/is (= 0.5 (:opacity c)))))

(t/deftest compact-shape-preserves-component-attrs
  (let [comp-id (uuid/next)
        ref-id  (uuid/next)
        shape   (-> (make-rect {})
                    (assoc :component-id comp-id
                           :component-file (uuid/next)
                           :shape-ref ref-id
                           :touched #{:geometry-group}))
        c       (sc/compact-shape shape)]
    (t/is (= comp-id (:component-id c)))
    (t/is (= ref-id (:shape-ref c)))
    (t/is (= #{:geometry-group} (:touched c)))))

;; --- expand-shape

(t/deftest expand-shape-restores-transform
  (let [compact  {:id (uuid/next) :type :rect :name "test"
                  :x 100 :y 200 :width 50 :height 30
                  :frame-id uuid/zero :parent-id uuid/zero}
        expanded (sc/expand-shape compact)]
    (t/is (gmt/matrix? (:transform expanded)))
    (t/is (gmt/matrix? (:transform-inverse expanded)))
    (t/is (true? (gmt/unit? (:transform expanded))))))

(t/deftest expand-shape-restores-selrect-rect
  (let [compact  {:id (uuid/next) :type :rect :name "test"
                  :x 100 :y 200 :width 50 :height 30
                  :frame-id uuid/zero :parent-id uuid/zero}
        expanded (sc/expand-shape compact)]
    (t/is (grc/rect? (:selrect expanded)))
    (t/is (= 100 (:x (:selrect expanded))))
    (t/is (= 200 (:y (:selrect expanded))))
    (t/is (= 50 (:width (:selrect expanded))))
    (t/is (= 30 (:height (:selrect expanded))))))

(t/deftest expand-shape-restores-points
  (let [compact  {:id (uuid/next) :type :rect :name "test"
                  :x 100 :y 200 :width 50 :height 30
                  :frame-id uuid/zero :parent-id uuid/zero}
        expanded (sc/expand-shape compact)
        points   (:points expanded)]
    (t/is (vector? points))
    (t/is (= 4 (count points)))
    (t/is (every? gpt/point? points))))

(t/deftest expand-shape-path
  (let [compact  {:id (uuid/next) :type :path :name "test"
                  :selrect (grc/make-rect 0 0 10 10)
                  :content [{:command :move-to :params {:x 0 :y 0}}
                            {:command :line-to :params {:x 10 :y 10}}]
                  :frame-id uuid/zero :parent-id uuid/zero}
        expanded (sc/expand-shape compact)]
    (t/is (grc/rect? (:selrect expanded)))
    (t/is (vector? (:points expanded)))
    (t/is (= 4 (count (:points expanded))))))

;; --- Round-trip

(t/deftest compact-expand-roundtrip-rect
  (let [shape    (make-rect {:x 123 :y 456 :width 78 :height 90})
        compact  (sc/compact-shape shape)
        result   (sc/expand-shape compact)]
    (t/is (= (:id shape) (:id result)))
    (t/is (= (:type shape) (:type result)))
    (t/is (= (:name shape) (:name result)))
    (t/is (= (:x shape) (:x result)))
    (t/is (= (:y shape) (:y result)))
    (t/is (= (:width shape) (:width result)))
    (t/is (= (:height shape) (:height result)))
    (t/is (grc/rect? (:selrect result)))
    (t/is (gmt/matrix? (:transform result)))
    (t/is (sm/validate cts/schema:shape result))))

(t/deftest compact-expand-roundtrip-preserves-ids
  (let [id       (uuid/next)
        frame-id (uuid/next)
        parent-id (uuid/next)
        shape    (-> (make-rect {:x 10 :y 20 :width 30 :height 40})
                     (assoc :id id :frame-id frame-id :parent-id parent-id))
        compact  (sc/compact-shape shape)
        result   (sc/expand-shape compact)]
    (t/is (= id (:id result)))
    (t/is (= frame-id (:frame-id result)))
    (t/is (= parent-id (:parent-id result)))))

;; --- Generative

(t/deftest compact-expand-schema-valid
  (smt/check!
   (smt/for [shape (sg/generator cts/schema:shape)]
     (let [compact  (sc/compact-shape shape)
           expanded (sc/expand-shape compact)]
       (sm/validate cts/schema:shape expanded)))
   {:num 200}))

(t/deftest compact-expand-preserves-geometry
  (smt/check!
   (smt/for [shape (sg/generator cts/schema:shape)]
     (let [compact  (sc/compact-shape shape)
           expanded (sc/expand-shape compact)]
       (and (= (:id shape) (:id expanded))
            (= (:type shape) (:type expanded))
            (= (:name shape) (:name expanded))
            (= (:frame-id shape) (:frame-id expanded))
            (= (:parent-id shape) (:parent-id expanded))
            (= (:component-id shape) (:component-id expanded))
            (= (:component-file shape) (:component-file expanded))
            (= (:shape-ref shape) (:shape-ref expanded))
            (= (:touched shape) (:touched expanded)))))
   {:num 200}))

(t/deftest compact-expand-preserves-content
  (smt/check!
   (smt/for [shape (sg/generator cts/schema:shape)]
     (let [compact  (sc/compact-shape shape)
           expanded (sc/expand-shape compact)]
       (or (not= :path (:type shape))
           (and (= (:id shape) (:id expanded))
                (= (:content shape) (:content expanded))
                (some? (:selrect expanded))
                (vector? (:points expanded))))))
   {:num 50}))
