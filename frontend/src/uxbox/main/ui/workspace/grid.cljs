;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>
;; Copyright (c) 2015-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.workspace.grid
  (:require
   [cuerdas.core :as str]
   [lentes.core :as l]
   [rumext.alpha :as mf]
   [uxbox.main.refs :as refs]
   [uxbox.main.constants :as c]))

;; --- Grid (Component)

(def options-iref
  (-> (l/key :options)
      (l/derive refs/workspace-data)))

(mf/defc grid
  {:wrap [mf/memo]}
  [props]
  (prn "grid$render")
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
