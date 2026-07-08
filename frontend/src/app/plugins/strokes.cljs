;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.strokes
  (:require
   [app.plugins.format :as format]
   [app.plugins.gradients :as gradients]
   [app.plugins.parser :as parser]
   [app.util.object :as obj]))

(defn stroke-proxy
  [stroke-data on-change!]
  (let [state (atom stroke-data)]
    (obj/reify {:name "StrokeProxy"}
      :strokeColor
      {:get (fn [] (:stroke-color @state))
       :set (fn [v]
              (swap! state #(-> % (assoc :stroke-color v) (dissoc :stroke-color-gradient)))
              (on-change!))}

      :strokeColorRefFile
      {:get (fn [] (format/format-id (:stroke-color-ref-file @state)))
       :set (fn [v] (swap! state assoc :stroke-color-ref-file (parser/parse-id v)) (on-change!))}

      :strokeColorRefId
      {:get (fn [] (format/format-id (:stroke-color-ref-id @state)))
       :set (fn [v] (swap! state assoc :stroke-color-ref-id (parser/parse-id v)) (on-change!))}

      :strokeOpacity
      {:get (fn [] (:stroke-opacity @state))
       :set (fn [v] (swap! state assoc :stroke-opacity v) (on-change!))}

      :strokeStyle
      {:get (fn [] (format/format-key (:stroke-style @state)))
       :set (fn [v] (swap! state assoc :stroke-style (parser/parse-keyword v)) (on-change!))}

      :strokeWidth
      {:get (fn [] (:stroke-width @state))
       :set (fn [v] (swap! state assoc :stroke-width v) (on-change!))}

      :strokeAlignment
      {:get (fn [] (format/format-key (:stroke-alignment @state)))
       :set (fn [v] (swap! state assoc :stroke-alignment (parser/parse-keyword v)) (on-change!))}

      :strokeCapStart
      {:get (fn [] (format/format-key (:stroke-cap-start @state)))
       :set (fn [v] (swap! state assoc :stroke-cap-start (parser/parse-keyword v)) (on-change!))}

      :strokeCapEnd
      {:get (fn [] (format/format-key (:stroke-cap-end @state)))
       :set (fn [v] (swap! state assoc :stroke-cap-end (parser/parse-keyword v)) (on-change!))}

      :strokeColorGradient
      {:get (fn []
              (when-let [gradient (:stroke-color-gradient @state)]
                (let [gradient-state (atom gradient)
                      gradient-change! (fn []
                                         (swap! state assoc :stroke-color-gradient @gradient-state)
                                         (on-change!))]
                  (gradients/gradient-proxy gradient-state gradient-change!))))
       :set (fn [v]
              (swap! state #(-> % (assoc :stroke-color-gradient (parser/parse-gradient v)) (dissoc :stroke-color)))
              (on-change!))})))

(defn format-strokes
  ([strokes] (format-strokes strokes nil))
  ([strokes commit-fn]
   (if (and (some? strokes) (fn? commit-fn))
     (let [arr-ref    (atom nil)
           on-change! (fn [] (commit-fn @arr-ref))
           arr        (apply array (mapv #(stroke-proxy % on-change!) strokes))]
       (reset! arr-ref arr)
       arr)
     (format/format-array format/format-stroke strokes))))
