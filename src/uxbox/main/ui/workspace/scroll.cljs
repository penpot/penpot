;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.scroll
  (:require [beicon.core :as rx]
            [lentes.core :as l]
            [uxbox.main.constants :as c]
            [uxbox.common.rstore :as rs]
            [uxbox.main.state :as ust]
            [uxbox.main.data.shapes :as uds]
            [uxbox.main.ui.core :as uuc]
            [uxbox.main.ui.mixins :as mx]
            [uxbox.main.ui.workspace.base :as uuwb]
            [uxbox.common.geom.point :as gpt]))

(defn watch-scroll-interactions
  [own]
  (letfn [(handle-scroll-interaction []
            (let [stoper (->> uuc/actions-s
                              (rx/map :type)
                              (rx/filter #(empty? %))
                              (rx/take 1))
                  local (:rum/local own)
                  initial @uuwb/mouse-viewport-a]
              (swap! local assoc :scrolling true)
              (as-> uuwb/mouse-viewport-s $
                (rx/take-until stoper $)
                (rx/subscribe $ #(on-scroll % initial) nil on-scroll-end))))

          (on-scroll-end []
            (let [local (:rum/local own)]
              (swap! local assoc :scrolling false)))

          (on-scroll [pt initial]
            (let [{:keys [x y]} (gpt/subtract pt initial)
                  el (mx/get-ref-dom own "workspace-canvas")
                  cx (.-scrollLeft el)
                  cy (.-scrollTop el)]
              (set! (.-scrollLeft el) (- cx x))
              (set! (.-scrollTop el) (- cy y))))]
    (as-> uuc/actions-s $
      (rx/map :type $)
      (rx/dedupe $)
      (rx/filter #(= "ui.workspace.scroll" %) $)
      (rx/on-value $ handle-scroll-interaction))))
