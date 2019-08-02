;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>
;; Copyright (c) 2015-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.workspace.ruler
  (:require
   [rumext.alpha :as mf]
   [uxbox.main.constants :as c]
   [uxbox.main.data.workspace :as udw]
   [uxbox.main.store :as st]
   [uxbox.main.user-events :as uev]
   [uxbox.util.dom :as dom]
   [uxbox.util.geom.point :as gpt]
   [uxbox.util.math :as mth]))

(mf/defc ruler-text
  [{:keys [zoom ruler] :as props}]
  (let [{:keys [start end]} ruler
        distance (-> (gpt/distance (gpt/divide end zoom)
                                   (gpt/divide start zoom))
                     (mth/precision 2))
        angle (-> (gpt/angle end start)
                  (mth/precision 2))
        transform1 (str "translate(" (+ (:x end) 35) "," (- (:y end) 10) ")")
        transform2 (str "translate(" (+ (:x end) 25) "," (- (:y end) 30) ")")]
    [:g
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

(mf/defc ruler-line
  [{:keys [zoom ruler] :as props}]
  (let [{:keys [start end]} ruler]
    [:line {:x1 (:x start)
            :y1 (:y start)
            :x2 (:x end)
            :y2 (:y end)
            :style {:cursor "cell"}
            :stroke-width "1"
            :stroke "red"}]))

(mf/defc ruler
  [{:keys [ruler zoom] :as props}]
  (letfn [(on-mouse-down [event]
            (dom/stop-propagation event)
            (st/emit! ::uev/interrupt
                      (udw/set-tooltip nil)
                      (udw/start-ruler)))
          (on-mouse-up [event]
            (dom/stop-propagation event)
            (st/emit! ::uev/interrupt))
          (on-unmount []
            (st/emit! ::uev/interrupt
                      (udw/clear-ruler)))]
    (mf/use-effect {:end on-unmount})
    [:svg {:on-mouse-down on-mouse-down
           :on-mouse-up on-mouse-up}
     [:rect {:style {:fill "transparent"
                     :stroke "transparent"
                     :cursor "cell"}
             :width c/viewport-width
             :height c/viewport-height}]
     (when ruler
       [:g
        [:& ruler-line {:ruler ruler}]
        [:& ruler-text {:ruler ruler :zoom zoom}]])]))

