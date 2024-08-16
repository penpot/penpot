;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.grid
  (:require
   [app.common.schema :as sm]
   [app.common.types.color :as ctc]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SCHEMA
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def schema:grid-color
  [:map {:title "PageGridColor"}
   [:color ::ctc/rgb-color]
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

(def schema:saved-grids
  [:map {:title "PageGrid"}
   [:square {:optional true} ::square-params]
   [:row {:optional true} ::column-params]
   [:column {:optional true} ::column-params]])

(sm/register! ::square-params schema:square-params)
(sm/register! ::column-params schema:column-params)
(sm/register! ::grid schema:grid)
(sm/register! ::saved-grids schema:saved-grids)
