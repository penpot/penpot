;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.shape.path
  (:require
   [app.common.schema :as sm]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SCHEMA
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(sm/define! ::segment
  [:multi {:title "PathSegment" :dispatch :command}
   [:line-to
    [:map
     [:command [:= :line-to]]
     [:params
      [:map
       [:x ::sm/safe-number]
       [:y ::sm/safe-number]]]]]
   [:close-path
    [:map
     [:command [:= :close-path]]]]
   [:move-to
    [:map
     [:command [:= :move-to]]
     [:params
      [:map
       [:x ::sm/safe-number]
       [:y ::sm/safe-number]]]]]
   [:curve-to
    [:map
     [:command [:= :curve-to]]
     [:params
      [:map
       [:x ::sm/safe-number]
       [:y ::sm/safe-number]
       [:c1x ::sm/safe-number]
       [:c1y ::sm/safe-number]
       [:c2x ::sm/safe-number]
       [:c2y ::sm/safe-number]]]]]])

(sm/define! ::content
  [:vector ::segment])
