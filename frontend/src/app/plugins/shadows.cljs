;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.shadows
  (:require
   [app.plugins.format :as format]
   [app.plugins.parser :as parser]
   [app.util.object :as obj]))

(defn shadow-proxy
  [shadow-data on-change!]
  (let [state (atom shadow-data)]
    (obj/reify {:name "ShadowProxy"}
      :id
      {:get (fn [] (format/format-id (:id @state)))
       :set (fn [v] (swap! state assoc :id (parser/parse-id v)) (on-change!))}

      :style
      {:get (fn [] (format/format-key (:style @state)))
       :set (fn [v] (swap! state assoc :style (parser/parse-keyword v)) (on-change!))}

      :offsetX
      {:get (fn [] (:offset-x @state))
       :set (fn [v] (swap! state assoc :offset-x v) (on-change!))}

      :offsetY
      {:get (fn [] (:offset-y @state))
       :set (fn [v] (swap! state assoc :offset-y v) (on-change!))}

      :blur
      {:get (fn [] (:blur @state))
       :set (fn [v] (swap! state assoc :blur v) (on-change!))}

      :spread
      {:get (fn [] (:spread @state))
       :set (fn [v] (swap! state assoc :spread v) (on-change!))}

      :hidden
      {:get (fn [] (:hidden @state))
       :set (fn [v] (swap! state assoc :hidden v) (on-change!))}

      ;; The color is returned as a plain snapshot; reconfiguring it means
      ;; reassigning the whole `color` object.
      :color
      {:get (fn [] (format/format-color (:color @state)))
       :set (fn [v] (swap! state assoc :color (parser/parse-color v)) (on-change!))})))

(defn format-shadows
  ([shadows] (format-shadows shadows nil))
  ([shadows commit-fn]
   (if (and (some? shadows) (fn? commit-fn))
     (let [arr-ref    (atom nil)
           on-change! (fn [] (commit-fn @arr-ref))
           arr        (apply array (mapv #(shadow-proxy % on-change!) shadows))]
       (reset! arr-ref arr)
       arr)
     (format/format-array format/format-shadow shadows))))
