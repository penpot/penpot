;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.gradients
  (:require
   [app.plugins.format :as format]
   [app.plugins.parser :as parser]
   [app.util.object :as obj]))

(defn stop-proxy
  [stop-data on-change!]
  (let [state (atom stop-data)]
    (obj/reify {:name "GradientStopProxy"}
      :color
      {:get (fn [] (:color @state))
       :set (fn [v] (swap! state assoc :color v) (on-change!))}

      :opacity
      {:get (fn [] (:opacity @state))
       :set (fn [v] (swap! state assoc :opacity v) (on-change!))}

      :offset
      {:get (fn [] (:offset @state))
       :set (fn [v] (swap! state assoc :offset v) (on-change!))})))

;; gradient-proxy takes an external atom `state` so the caller
;; (fill-proxy / stroke-proxy) can read @state after any change.
(defn gradient-proxy
  [state on-change!]
  (obj/reify {:name "GradientProxy"}
    :type
    {:get (fn [] (format/format-key (:type @state)))
     :set (fn [v] (swap! state assoc :type (parser/parse-keyword v)) (on-change!))}

    :startX
    {:get (fn [] (:start-x @state))
     :set (fn [v] (swap! state assoc :start-x v) (on-change!))}

    :startY
    {:get (fn [] (:start-y @state))
     :set (fn [v] (swap! state assoc :start-y v) (on-change!))}

    :endX
    {:get (fn [] (:end-x @state))
     :set (fn [v] (swap! state assoc :end-x v) (on-change!))}

    :endY
    {:get (fn [] (:end-y @state))
     :set (fn [v] (swap! state assoc :end-y v) (on-change!))}

    :width
    {:get (fn [] (:width @state))
     :set (fn [v] (swap! state assoc :width v) (on-change!))}

    :stops
    {:get (fn []
            (let [stops-ref    (atom nil)
                  stop-change!
                  (fn []
                    (let [new-stops (into [] (map parser/parse-gradient-stop) @stops-ref)]
                      (swap! state assoc :stops new-stops)
                      (on-change!)))
                  arr          (apply array (mapv #(stop-proxy % stop-change!) (:stops @state)))]
              (reset! stops-ref arr)
              arr))
     :set (fn [v]
            (swap! state assoc :stops (into [] (map parser/parse-gradient-stop) v))
            (on-change!))}))
