;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.util.text-svg-position
  (:require
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.common.transit :as transit]
   [app.main.store :as st]
   [app.util.dom :as dom]
   [app.util.globals :as global]))

(defn get-range-rects
  "Retrieve the rectangles that cover the selection given by a `node` adn
  the start and end index `start-i`, `end-i`"
  [^js node start-i end-i]
  (let [^js range (.createRange global/document)]
    (.setStart range node start-i)
    (.setEnd range node end-i)
    (.getClientRects range)))

(defn parse-text-nodes
  "Given a text node retrieves the rectangles for everyone of its paragraphs and its text."
  [parent-node rtl? text-node]

  (let [content (.-textContent text-node)
        text-size (.-length content)]

    (loop [from-i  0
           to-i    0
           current ""
           result  []]
      (if (>= to-i text-size)
        (let [rects (get-range-rects text-node from-i to-i)
              entry {:node parent-node
                     :position (dom/bounding-rect->rect (first rects))
                     :text current}]
          ;; We need to add the last element not closed yet
          (conj result entry))
        
        (let [rects (get-range-rects text-node from-i (inc to-i))]
          ;; If the rects increase means we're in a new paragraph
          (if (> (.-length rects) 1)
            (let [entry {:node parent-node
                         :position (dom/bounding-rect->rect (if rtl? (second rects) (first rects)))
                         :text current}]
              (recur to-i to-i "" (conj result entry)))
            (recur from-i (inc to-i) (str current (nth content to-i)) result)))))))


(defn calc-text-node-positions
  [base-node viewport zoom]

  (when (some? viewport)
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

          text-nodes (dom/query-all base-node ".text-node, span[data-text]")]

      (->> text-nodes
           (mapcat
            (fn [parent-node]
              (let [rtl? (= "rtl" (.-dir (.-parentElement parent-node)))]
                (->> (.-childNodes parent-node)
                     (mapcat #(parse-text-nodes parent-node rtl? %))))))
           (map #(update % :position translate-rect))))))



(defn calc-position-data
  [base-node]
  (let [viewport      (dom/get-element "render")
        zoom          (get-in @st/state [:workspace-local :zoom])
        text-data     (calc-text-node-positions base-node viewport zoom)]
    (->> text-data
         (map (fn [{:keys [node position text]}]
                (let [{:keys [x y width height]} position
                      rtl?   (= "rtl" (.-dir (.-parentElement ^js node)))
                      styles (js/getComputedStyle ^js node)
                      get    (fn [prop]
                               (let [value (.getPropertyValue styles prop)]
                                 (when (and value (not= value ""))
                                   value)))]
                  (d/without-nils
                   {:rtl?                rtl?
                    :x                   (if rtl? (+ x width) x)
                    :y                   (+ y height)
                    :width               width
                    :height              height
                    :font-family         (str (get "font-family"))
                    :font-size           (str (get "font-size"))
                    :font-weight         (str (get "font-weight"))
                    :text-transform      (str (get "text-transform"))
                    :text-decoration     (str (get "text-decoration"))
                    :font-style          (str (get "font-style"))
                    :fill-color          (or (get "--fill-color") "#000000")
                    :fill-color-gradient (transit/decode-str (get "--fill-color-gradient"))
                    :fill-opacity        (d/parse-double (or (get "--fill-opacity") "1"))
                    :text                text})))))))
