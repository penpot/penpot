;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.test-helpers.variants
  (:require
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]))

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
        (thc/update-component component2-label {:variant-id variant-id :variant-properties [{:name "Property1" :value "Value1"}]}))))
