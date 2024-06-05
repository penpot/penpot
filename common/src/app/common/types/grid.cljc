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

(sm/register! ::grid-color
  [:map {:title "PageGridColor"}
   [:color ::ctc/rgb-color]
   [:opacity ::sm/safe-number]])

(sm/register! ::column-params
  [:map
   [:color ::grid-color]
   [:type {:optional true} [::sm/one-of #{:stretch :left :center :right}]]
   [:size {:optional true} [:maybe ::sm/safe-number]]
   [:margin {:optional true} [:maybe ::sm/safe-number]]
   [:item-length {:optional true} [:maybe ::sm/safe-number]]
   [:gutter {:optional true} [:maybe ::sm/safe-number]]])

(sm/register! ::square-params
  [:map
   [:size {:optional true} [:maybe ::sm/safe-number]]
   [:color ::grid-color]])

(sm/register! ::grid
  [:multi {:dispatch :type}
   [:column
    [:map
     [:type [:= :column]]
     [:display :boolean]
     [:params ::column-params]]]

   [:row
    [:map
     [:type [:= :row]]
     [:display :boolean]
     [:params ::column-params]]]

   [:square
    [:map
     [:type [:= :square]]
     [:display :boolean]
     [:params ::square-params]]]])

(sm/register! ::saved-grids
  [:map {:title "PageGrid"}
   [:square {:optional true} ::square-params]
   [:row {:optional true} ::column-params]
   [:column {:optional true} ::column-params]])
