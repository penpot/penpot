;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.shape.path
  (:require
   [app.common.schema :as sm]))

(def schema:line-to-segment
  [:map
   [:command [:= :line-to]]
   [:params
    [:map
     [:x ::sm/safe-number]
     [:y ::sm/safe-number]]]])

(def schema:close-path-segment
  [:map
   [:command [:= :close-path]]])

(def schema:move-to-segment
  [:map
   [:command [:= :move-to]]
   [:params
    [:map
     [:x ::sm/safe-number]
     [:y ::sm/safe-number]]]])

(def schema:curve-to-segment
  [:map
   [:command [:= :curve-to]]
   [:params
    [:map
     [:x ::sm/safe-number]
     [:y ::sm/safe-number]
     [:c1x ::sm/safe-number]
     [:c1y ::sm/safe-number]
     [:c2x ::sm/safe-number]
     [:c2y ::sm/safe-number]]]])

(def schema:path-segment
  [:multi {:title "PathSegment"
           :dispatch :command
           :decode/json #(update % :command keyword)}
   [:line-to schema:line-to-segment]
   [:close-path schema:close-path-segment]
   [:move-to schema:move-to-segment]
   [:curve-to schema:curve-to-segment]])

(def schema:path-content
  [:vector schema:path-segment])

(sm/register! ::segment schema:path-segment)
(sm/register! ::content schema:path-content)
