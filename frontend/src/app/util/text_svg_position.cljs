;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.util.text-svg-position
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.point :as gpt]
   [app.common.transit :as transit]
   [app.main.fonts :as fonts]
   [app.main.store :as st]
   [app.util.dom :as dom]
   [app.util.text-position-data :as tpd]
   [promesa.core :as p]))

(defn parse-text-nodes
  "Given a text node retrieves the rectangles for everyone of its paragraphs and its text."
  [parent-node direction text-node]

  (letfn [(parse-entry [^js entry]
            {:node      (.-node entry)
             :position  (dom/bounding-rect->rect (.-position entry))
             :text      (.-text entry)
             :direction direction})]
    (into
     []
     (map parse-entry)
     (tpd/parse-text-nodes parent-node text-node))))

(def load-promises (atom {}))

(defn load-font
  [font]
  (if (contains? @load-promises font)
    (get @load-promises font)
    (let [load-promise (dom/load-font font)]
      (swap! load-promises assoc font load-promise)
      load-promise)))

(defn resolve-font
  [^js node]

  (let [styles (js/getComputedStyle node)
        font (.getPropertyValue styles "font")
        font (if (or (not font) (empty? font))
               ;; Firefox 95 won't return the font correctly.
               ;; We can get the font shorthand with the font-size + font-family
               (dm/str (.getPropertyValue styles "font-size")
                       " "
                       (.getPropertyValue styles "font-family"))
               font)

        font-id (.getPropertyValue styles "--font-id")]

    (-> (fonts/ensure-loaded! font-id)
        (p/then #(when (not (dom/check-font? font))
                   (load-font font))))))

(defn calc-text-node-positions
  [base-node viewport zoom]

  (when (and (some? base-node)(some? viewport))
    (let [translate-point
          (fn [pt]
            (let [vbox     (.. ^js viewport -viewBox -baseVal)
                  brect    (dom/get-bounding-rect viewport)
                  brect    (gpt/point (d/parse-integer (:left brect))
                                      (d/parse-integer (:top brect)))
                  box      (gpt/point (.-x vbox) (.-y vbox))
                  zoom     (gpt/point zoom)]
              
              (-> (gpt/subtract pt brect)
                  (gpt/divide zoom)
                  (gpt/add box))))

          translate-rect
          (fn [{:keys [x y width height] :as rect}]
            (let [p1 (-> (gpt/point x y)
                         (translate-point))

                  p2 (-> (gpt/point (+ x width) (+ y height))
                         (translate-point))]
              (assoc rect
                     :x (:x p1)
                     :y (:y p1)
                     :width (- (:x p2) (:x p1))
                     :height (- (:y p2) (:y p1)))))

          text-nodes (dom/query-all base-node ".text-node, span[data-text]")
          load-fonts (->> text-nodes (map resolve-font))]
      (-> (p/all load-fonts)
          (p/then
           (fn []
             (->> text-nodes
                  (mapcat
                   (fn [parent-node]
                     (let [direction (.-direction (js/getComputedStyle parent-node))]
                       (->> (.-childNodes parent-node)
                            (mapcat #(parse-text-nodes parent-node direction %))))))
                  (mapv #(update % :position translate-rect)))))))))

(defn calc-position-data
  [base-node]
  (let [viewport      (dom/get-element "render")
        zoom          (or (get-in @st/state [:workspace-local :zoom]) 1)]
    (when (and (some? base-node) (some? viewport))
      (p/let [text-data (calc-text-node-positions base-node viewport zoom)]
        (when (d/not-empty? text-data)
          (->> text-data
               (mapv (fn [{:keys [node position text direction]}]
                       (let [{:keys [x y width height]} position
                             styles (js/getComputedStyle ^js node)
                             get    (fn [prop]
                                      (let [value (.getPropertyValue styles prop)]
                                        (when (and value (not= value ""))
                                          value)))]
                         (d/without-nils
                          {:x                   x
                           :y                   (+ y height)
                           :width               width
                           :height              height
                           :direction           direction
                           :font-family         (str (get "font-family"))
                           :font-size           (str (get "font-size"))
                           :font-weight         (str (get "font-weight"))
                           :text-transform      (str (get "text-transform"))
                           :text-decoration     (str (get "text-decoration"))
                           :letter-spacing      (str (get "letter-spacing"))
                           :font-style          (str (get "font-style"))
                           :fills               (transit/decode-str (get "--fills"))
                           :text                text}))))))))))


