;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.workspace.grid
  (:require
   [cuerdas.core :as str]
   [okulary.core :as l]
   [rumext.alpha :as mf]
   [uxbox.main.constants :as c]
   [uxbox.main.refs :as refs]))

;; --- Grid (Component)

(def options-iref
  (l/derived :options refs/workspace-data))

(mf/defc grid
  {:wrap [mf/memo]}
  [props]
  (let [options (mf/deref options-iref)
        width (:grid-x options 10)
        height (:grid-y options 10)
        color (:grid-color options "#cccccc")]
    [:g.grid
     [:defs
      [:pattern {:id "grid-pattern"
                 :x "0" :y "0"
                 :width width :height height
                 :patternUnits "userSpaceOnUse"}
       [:path {:d (str/format "M 0 %s L %s %s L %s 0" height width height width)
               :fill "transparent"
               :stroke color}]]]
     [:rect {:style {:pointer-events "none"}
             :x 0 :y 0
             :width "100%"
             :height "100%"
             :fill "url(#grid-pattern)"}]]))
