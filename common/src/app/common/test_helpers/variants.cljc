;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.test-helpers.variants
  (:require
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.text :as txt]
   [app.common.types.shape :as cts]))

(defn add-variant
  [file variant-label component1-label root1-label component2-label root2-label
   & {:keys []}]
  (let [file (ths/add-sample-shape file variant-label :type :frame :is-variant-container true)
        variant-id (thi/id variant-label)]

    (-> file
        (ths/add-sample-shape root2-label :type :frame :parent-label variant-label :variant-id variant-id :variant-name "Value2")
        (ths/add-sample-shape root1-label :type :frame :parent-label variant-label :variant-id variant-id :variant-name "Value1")
        (thc/make-component component1-label root1-label)
        (thc/update-component component1-label {:variant-id variant-id :variant-properties [{:name "Property1" :value "Value1"}]})
        (thc/make-component component2-label root2-label)
        (thc/update-component component2-label {:variant-id variant-id :variant-properties [{:name "Property1" :value "Value2"}]}))))

(defn add-variant-two-properties
  [file variant-label component1-label root1-label component2-label root2-label
   & {:keys []}]
  (let [file (ths/add-sample-shape file variant-label :type :frame :is-variant-container true)
        variant-id (thi/id variant-label)]

    (-> file
        (ths/add-sample-shape root2-label :type :frame :parent-label variant-label :variant-id variant-id :variant-name "p1v2, p2v2")
        (ths/add-sample-shape root1-label :type :frame :parent-label variant-label :variant-id variant-id :variant-name "p1v1, p2v1")
        (thc/make-component component1-label root1-label)
        (thc/update-component component1-label {:variant-id variant-id :variant-properties [{:name "Property1" :value "p1v1"} {:name "Property2" :value "p2v1"}]})
        (thc/make-component component2-label root2-label)
        (thc/update-component component2-label {:variant-id variant-id :variant-properties [{:name "Property1" :value "p1v2"} {:name "Property2" :value "p2v2"}]}))))

(defn add-variant-with-child
  [file variant-label component1-label root1-label component2-label root2-label child1-label child2-label
   & {:keys [child1-params child2-params]}]
  (let [file (ths/add-sample-shape file variant-label :type :frame :is-variant-container true)
        variant-id (thi/id variant-label)]
    (-> file
        (ths/add-sample-shape root2-label :type :frame :parent-label variant-label :variant-id variant-id :variant-name "Value2")
        (ths/add-sample-shape root1-label :type :frame :parent-label variant-label :variant-id variant-id :variant-name "Value1")
        (ths/add-sample-shape child1-label (assoc child1-params :parent-label root1-label))
        (ths/add-sample-shape child2-label (assoc child2-params :parent-label root2-label))
        (thc/make-component component1-label root1-label)
        (thc/update-component component1-label {:variant-id variant-id :variant-properties [{:name "Property1" :value "Value1"}]})
        (thc/make-component component2-label root2-label)
        (thc/update-component component2-label {:variant-id variant-id :variant-properties [{:name "Property1" :value "Value2"}]}))))


(defn add-variant-with-text
  [file variant-label component1-label root1-label component2-label root2-label child1-label child2-label text1 text2
   & {:keys [text1-params text2-params]}]
  (let [text1 (-> (cts/setup-shape {:type :text :x 0 :y 0 :grow-type :auto-width})
                  (txt/change-text text1)
                  (assoc :position-data nil
                         :parent-label root1-label))
        text2 (-> (cts/setup-shape {:type :text :x 0 :y 0 :grow-type :auto-width})
                  (txt/change-text text2)
                  (assoc :position-data nil
                         :parent-label root2-label))

        file (ths/add-sample-shape file variant-label :type :frame :is-variant-container true)
        variant-id (thi/id variant-label)]
    (-> file

        (ths/add-sample-shape root2-label :type :frame :parent-label variant-label :variant-id variant-id :variant-name "Value2")
        (ths/add-sample-shape root1-label :type :frame :parent-label variant-label :variant-id variant-id :variant-name "Value1")
        (ths/add-sample-shape child1-label
                              (merge text1
                                     text1-params))
        (ths/add-sample-shape child2-label
                              (merge text2
                                     text2-params))
        (thc/make-component component1-label root1-label)
        (thc/update-component component1-label {:variant-id variant-id :variant-properties [{:name "Property1" :value "Value1"}]})
        (thc/make-component component2-label root2-label)
        (thc/update-component component2-label {:variant-id variant-id :variant-properties [{:name "Property1" :value "Value2"}]}))))
