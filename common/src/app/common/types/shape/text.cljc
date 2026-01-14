;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.shape.text
  (:require
   [app.common.schema :as sm]
   [app.common.types.fills :refer [schema:fills]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SCHEMA
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def node-types #{"root" "paragraph-set" "paragraph"})

(def schema:content
  [:map
   [:type [:= "root"]]
   [:key {:optional true} :string]
   [:children
    {:optional true}
    [:maybe
     [:vector {:min 1 :gen/max 2 :gen/min 1}
      [:map
       [:type [:= "paragraph-set"]]
       [:key {:optional true} :string]
       [:children
        [:vector {:min 1 :gen/max 2 :gen/min 1}
         [:map
          [:type [:= "paragraph"]]
          [:key {:optional true} :string]
          [:fills {:optional true}
           [:maybe schema:fills]]
          [:font-family {:optional true} ::sm/text]
          [:font-size {:optional true} ::sm/text]
          [:font-style {:optional true} ::sm/text]
          [:font-weight {:optional true} ::sm/text]
          [:direction {:optional true} ::sm/text]
          [:text-decoration {:optional true} ::sm/text]
          [:text-transform {:optional true} ::sm/text]
          [:typography-ref-id {:optional true} [:maybe ::sm/uuid]]
          [:typography-ref-file {:optional true} [:maybe ::sm/uuid]]
          [:children
           [:vector {:min 1 :gen/max 2 :gen/min 1}
            [:map
             [:text :string]
             [:key {:optional true} :string]
             [:fills {:optional true}
              [:maybe schema:fills]]
             [:font-family {:optional true} ::sm/text]
             [:font-size {:optional true} ::sm/text]
             [:font-style {:optional true} ::sm/text]
             [:font-weight {:optional true} ::sm/text]
             [:direction {:optional true} ::sm/text]
             [:text-decoration {:optional true} ::sm/text]
             [:text-transform {:optional true} ::sm/text]
             [:typography-ref-id {:optional true} [:maybe ::sm/uuid]]
             [:typography-ref-file {:optional true} [:maybe ::sm/uuid]]]]]]]]]]]]])

(def valid-content?
  (sm/lazy-validator schema:content))

(def schema:position-data
  [:vector {:min 0 :gen/max 2}
   [:map
    [:x ::sm/safe-number]
    [:y ::sm/safe-number]
    [:width ::sm/safe-number]
    [:height ::sm/safe-number]
    [:fills schema:fills]
    [:font-family {:optional true} ::sm/text]
    [:font-size {:optional true} ::sm/text]
    [:font-style {:optional true} ::sm/text]
    [:font-weight {:optional true} ::sm/text]
    [:rtl {:optional true} :boolean]
    [:text {:optional true} :string]
    [:text-decoration {:optional true} ::sm/text]
    [:text-transform {:optional true} ::sm/text]]])
