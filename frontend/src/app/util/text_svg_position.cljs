;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.text-svg-position
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.transit :as transit]
   [app.main.fonts :as fonts]
   [app.util.dom :as dom]
   [app.util.text-position-data :as tpd]
   [promesa.core :as p]))

(defn parse-text-nodes
  "Given a text node retrieves the rectangles for everyone of its paragraphs and its text."
  [parent-node direction text-node text-align]

  (letfn [(parse-entry [^js entry]
            (when (some? (.-position entry))
              {:node      (.-node entry)
               :position  (dom/bounding-rect->rect (.-position entry))
               :text      (.-text entry)
               :direction direction}))]
    (into
     []
     (keep parse-entry)
     (tpd/parse-text-nodes parent-node text-node text-align))))

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
                   (load-font font)))
        (p/catch #(.error js/console (dm/str "Cannot load font " font-id) %)))))

(defn- calc-text-node-positions
  [shape-id]

  (when (some? shape-id)
    (let [text-nodes (-> (dom/query (dm/fmt "#html-text-node-%" shape-id))
                         (dom/query-all ".text-node"))
          load-fonts (->> text-nodes (map resolve-font))

          process-text-node
          (fn [parent-node]
            (let [root (dom/get-parent-with-selector parent-node ".text-node-html")
                  paragraph (dom/get-parent-with-selector parent-node ".paragraph")
                  shape-x (-> (dom/get-attribute root "data-x") d/parse-double)
                  shape-y (-> (dom/get-attribute root "data-y") d/parse-double)
                  direction (.-direction (js/getComputedStyle parent-node))
                  text-align (.-textAlign (js/getComputedStyle paragraph))]

              (->> (.-childNodes parent-node)
                   (mapcat #(parse-text-nodes parent-node direction % text-align))
                   (mapv #(-> %
                              (update-in [:position :x] + shape-x)
                              (update-in [:position :y] + shape-y))))))]
      (-> (p/all load-fonts)
          (p/then
           (fn []
             (->> text-nodes (mapcat process-text-node))))))))

(defn calc-position-data
  [shape-id]
  (letfn [(get-prop [styles prop]
            (let [value (.getPropertyValue styles prop)]
              (when (and (some? value) (not= value ""))
                value)))

          (transform-data [{:keys [node position text direction]}]
            (let [styles   (js/getComputedStyle ^js node)
                  position (assoc position :y (+ (dm/get-prop position :y)
                                                 (dm/get-prop position :height)))]
              (into position (filter val)
                    {:direction       direction
                     :font-family     (dm/str (get-prop styles "font-family"))
                     :font-size       (dm/str (get-prop styles "font-size"))
                     :font-weight     (dm/str (get-prop styles "font-weight"))
                     :text-transform  (dm/str (get-prop styles "text-transform"))
                     :text-decoration (dm/str (get-prop styles "text-decoration"))
                     :letter-spacing  (dm/str (get-prop styles "letter-spacing"))
                     :font-style      (dm/str (get-prop styles "font-style"))
                     :fills           (transit/decode-str (get-prop styles "--fills"))
                     :text            text})))]

    (when (some? shape-id)
      (->> (calc-text-node-positions shape-id)
           (p/fmap (fn [text-data]
                     (mapv transform-data text-data)))))))
