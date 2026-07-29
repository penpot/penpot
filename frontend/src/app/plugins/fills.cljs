;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.fills
  (:require
   [app.plugins.format :as format]
   [app.plugins.gradients :as gradients]
   [app.plugins.parser :as parser]
   [app.util.object :as obj]))

(defn fill-proxy
  [fill-data on-change!]
  (let [state (atom fill-data)]
    (obj/reify {:name "FillProxy"}
      :fillColor
      {:get (fn [] (:fill-color @state))
       :set (fn [v]
              (swap! state #(-> % (assoc :fill-color v) (dissoc :fill-color-gradient :fill-image)))
              (on-change!))}

      :fillOpacity
      {:get (fn [] (:fill-opacity @state))
       :set (fn [v] (swap! state assoc :fill-opacity v) (on-change!))}

      :fillColorGradient
      {:get (fn []
              (when-let [gradient (:fill-color-gradient @state)]
                (let [gradient-state (atom gradient)
                      gradient-change! (fn []
                                         (swap! state assoc :fill-color-gradient @gradient-state)
                                         (on-change!))]
                  (gradients/gradient-proxy gradient-state gradient-change!))))
       :set (fn [v]
              (swap! state #(-> % (assoc :fill-color-gradient (parser/parse-gradient v)) (dissoc :fill-color :fill-image)))
              (on-change!))}

      :fillColorRefFile
      {:get (fn [] (format/format-id (:fill-color-ref-file @state)))
       :set (fn [v] (swap! state assoc :fill-color-ref-file (parser/parse-id v)) (on-change!))}

      :fillColorRefId
      {:get (fn [] (format/format-id (:fill-color-ref-id @state)))
       :set (fn [v] (swap! state assoc :fill-color-ref-id (parser/parse-id v)) (on-change!))}

      :fillImage
      {:get (fn [] (format/format-image (:fill-image @state)))
       :set (fn [v]
              (swap! state #(-> % (assoc :fill-image (parser/parse-image-data v)) (dissoc :fill-color :fill-color-gradient)))
              (on-change!))})))

(defn format-fills
  ([fills] (format-fills fills nil))
  ([fills commit-fn]
   (cond
     (= fills :multiple) "mixed"
     (= fills "mixed")   "mixed"

     (and (some? fills) (fn? commit-fn))
     (let [arr-ref    (atom nil)
           on-change! (fn [] (commit-fn @arr-ref))
           arr        (apply array (mapv #(fill-proxy % on-change!) fills))]
       (reset! arr-ref arr)
       arr)

     :else
     (format/format-array format/format-fill fills))))
