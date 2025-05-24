;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.grid
  (:require
   [app.common.colors :as clr]
   [app.common.schema :as sm]
   [app.common.types.color :refer [schema:hex-color]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SCHEMA
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def schema:grid-color
  [:map {:title "PageGridColor"}
   [:color schema:hex-color]
   [:opacity ::sm/safe-number]])

(def schema:column-params
  [:map
   [:color schema:grid-color]
   [:type {:optional true} [::sm/one-of #{:stretch :left :center :right}]]
   [:size {:optional true} [:maybe ::sm/safe-number]]
   [:margin {:optional true} [:maybe ::sm/safe-number]]
   [:item-length {:optional true} [:maybe ::sm/safe-number]]
   [:gutter {:optional true} [:maybe ::sm/safe-number]]])

(def schema:square-params
  [:map
   [:size {:optional true} [:maybe ::sm/safe-number]]
   [:color schema:grid-color]])

(def schema:grid
  [:multi {:title "Grid"
           :dispatch :type
           :decode/json #(update % :type keyword)}
   [:column
    [:map
     [:type [:= :column]]
     [:display :boolean]
     [:params schema:column-params]]]

   [:row
    [:map
     [:type [:= :row]]
     [:display :boolean]
     [:params schema:column-params]]]

   [:square
    [:map
     [:type [:= :square]]
     [:display :boolean]
     [:params schema:square-params]]]])

(def schema:default-grids
  [:map {:title "PageGrid"}
   [:square {:optional true} ::square-params]
   [:row {:optional true} ::column-params]
   [:column {:optional true} ::column-params]])

(sm/register! ::square-params schema:square-params)
(sm/register! ::column-params schema:column-params)
(sm/register! ::grid schema:grid)
(sm/register! ::default-grids schema:default-grids)

(def ^:private default-square-params
  {:size 16
   :color {:color clr/info
           :opacity 0.4}})

(def ^:private default-layout-params
  {:size 12
   :type :stretch
   :item-length nil
   :gutter 8
   :margin 0
   :color {:color clr/default-layout
           :opacity 0.1}})

(def default-grid-params
  {:square default-square-params
   :column default-layout-params
   :row    default-layout-params})

