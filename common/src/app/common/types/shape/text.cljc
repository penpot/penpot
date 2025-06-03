;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.shape.text
  (:require
   [app.common.schema :as sm]
   [app.common.types.shape :as-alias shape]
   [app.common.types.shape.text.position-data :as-alias position-data]))

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
           [:maybe
            [:vector {:gen/max 2} ::shape/fill]]]
          [:font-family {:optional true} :string]
          [:font-size {:optional true} :string]
          [:font-style {:optional true} :string]
          [:font-weight {:optional true} :string]
          [:direction {:optional true} :string]
          [:text-decoration {:optional true} :string]
          [:text-transform {:optional true} :string]
          [:typography-ref-id {:optional true} [:maybe ::sm/uuid]]
          [:typography-ref-file {:optional true} [:maybe ::sm/uuid]]
          [:children
           [:vector {:min 1 :gen/max 2 :gen/min 1}
            [:map
             [:text :string]
             [:key {:optional true} :string]
             [:fills {:optional true}
              [:maybe
               [:vector {:gen/max 2} ::shape/fill]]]
             [:font-family {:optional true} :string]
             [:font-size {:optional true} :string]
             [:font-style {:optional true} :string]
             [:font-weight {:optional true} :string]
             [:direction {:optional true} :string]
             [:text-decoration {:optional true} :string]
             [:text-transform {:optional true} :string]
             [:typography-ref-id {:optional true} [:maybe ::sm/uuid]]
             [:typography-ref-file {:optional true} [:maybe ::sm/uuid]]]]]]]]]]]]])

(sm/register! ::content schema:content)

(def valid-content?
  (sm/lazy-validator schema:content))

(sm/register!
 ^{::sm/type ::position-data}
 [:vector {:min 1 :gen/max 2}
  [:map
   [:x ::sm/safe-number]
   [:y ::sm/safe-number]
   [:width ::sm/safe-number]
   [:height ::sm/safe-number]
   [:fills [:vector {:gen/max 2} ::shape/fill]]
   [:font-family {:optional true} :string]
   [:font-size {:optional true} :string]
   [:font-style {:optional true} :string]
   [:font-weight {:optional true} :string]
   [:rtl {:optional true} :boolean]
   [:text {:optional true} :string]
   [:text-decoration {:optional true} :string]
   [:text-transform {:optional true} :string]]])

