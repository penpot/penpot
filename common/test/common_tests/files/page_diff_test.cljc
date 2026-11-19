;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.files.page-diff-test
  (:require
   [app.common.files.page-diff :as diff]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(def ^:private check-attrs [:frame-id :x :y :width :height :hidden :selrect :grids])

(def ^:private rect-a-id (uuid/custom 50 1))
(def ^:private rect-b-id (uuid/custom 50 2))
(def ^:private rect-c-id (uuid/custom 50 3))
(def ^:private guide-id (uuid/custom 50 4))

(defn- make-rect
  [id x]
  (cts/setup-shape {:id id :type :rect :name "rect"
                    :frame-id uuid/zero :parent-id uuid/zero
                    :x x :y 0 :width 10 :height 10}))

(def ^:private old-page
  {:id (uuid/custom 50 10)
   :objects {rect-a-id (make-rect rect-a-id 0)
             rect-b-id (make-rect rect-b-id 0)}})

(def ^:private new-page
  {:id (uuid/custom 50 10)
   :objects {rect-a-id (make-rect rect-a-id 50)
             rect-b-id (make-rect rect-b-id 50)
             rect-c-id (make-rect rect-c-id 0)}
   :guides {guide-id {:id guide-id :axis :x :position 10}}})

(t/deftest diff-with-every-id-reports-every-change
  (let [result (diff/calculate-page-diff old-page new-page check-attrs
                                         #{rect-a-id rect-b-id rect-c-id})]
    (t/is (= #{rect-a-id rect-b-id}
             (into #{} (map (comp :id second)) (:updated-shapes result))))
    (t/is (= [rect-c-id] (mapv :id (:new-shapes result))))
    (t/is (= [guide-id] (mapv :id (:new-guides result))))))

(t/deftest diff-ignores-objects-outside-the-given-ids
  (let [result (diff/calculate-page-diff old-page new-page check-attrs #{rect-a-id})]
    (t/is (= [rect-a-id] (mapv (comp :id second) (:updated-shapes result))))
    (t/is (= [] (:new-shapes result)))))

(t/deftest diff-always-compares-every-guide
  (let [result (diff/calculate-page-diff old-page new-page check-attrs #{})]
    (t/is (= [guide-id] (mapv :id (:new-guides result))))))
