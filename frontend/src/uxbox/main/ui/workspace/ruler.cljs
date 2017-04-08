;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.ruler
  (:require [lentes.core :as l]
            [potok.core :as ptk]
            [beicon.core :as rx]
            [uxbox.main.constants :as c]
            [uxbox.main.data.workspace :as udw]
            [uxbox.main.refs :as refs]
            [uxbox.main.streams :as streams]
            [uxbox.main.store :as st]
            [uxbox.main.user-events :as uev]
            [uxbox.util.math :as mth]
            [rumext.core :as mx :include-macros true]
            [uxbox.util.geom.point :as gpt]
            [uxbox.util.dom :as dom]))

(def ruler-points-ref
  (-> (l/key :ruler)
      (l/derive refs/workspace)))

(mx/defc ruler-text
  {:mixins [mx/static]}
  [zoom [center pt]]
  (let [distance (-> (gpt/distance (gpt/divide pt zoom)
                                   (gpt/divide center zoom))
                     (mth/precision 2))
        angle (-> (gpt/angle pt center)
                  (mth/precision 2))
        transform1 (str "translate(" (+ (:x pt) 35) "," (- (:y pt) 10) ")")
        transform2 (str "translate(" (+ (:x pt) 25) "," (- (:y pt) 30) ")")]
    [:g {}
     [:rect {:fill "black"
             :fill-opacity "0.4"
             :rx "3"
             :ry "3"
             :width "90"
             :height "50"
             :transform transform2}]
     [:text {:transform transform1
             :fill "white"}
      [:tspan {:x "0"}
       (str distance " px")]
      [:tspan {:x "0" :y "20"}
       (str angle "°")]]]))

(mx/defc ruler-line
  {:mixins [mx/static]}
  [zoom [center pt]]
  [:line {:x1 (:x center)
          :y1 (:y center)
          :x2 (:x pt)
          :y2 (:y pt)
          :style {:cursor "cell"}
          :stroke-width "1"
          :stroke "red"}])

(mx/defc ruler
  {:mixins [mx/static mx/reactive]
   :will-unmount (fn [own]
                   (st/emit! ::uev/interrupt
                             (udw/clear-ruler))
                   own)}
  [zoom]
  (letfn [(on-mouse-down [event]
            (dom/stop-propagation event)
            (st/emit! ::uev/interrupt
                      (udw/set-tooltip nil)
                      (udw/start-ruler)))
          (on-mouse-up [event]
            (dom/stop-propagation event)
            (st/emit! ::uev/interrupt))]
    [:svg {:on-mouse-down on-mouse-down
           :on-mouse-up on-mouse-up}
     [:rect {:style {:fill "transparent"
                     :stroke "transparent"
                     :cursor "cell"}
             :width c/viewport-width
             :height c/viewport-height}]
     (when-let [points (mx/react ruler-points-ref)]
       [:g {}
        (ruler-line zoom points)
        (ruler-text zoom points)])]))

