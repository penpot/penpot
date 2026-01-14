;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.render-wasm.svg-filters
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.svg :as csvg]
   [app.common.uuid :as uuid]
   [app.render-wasm.svg-fills :as svg-fills]))

(def ^:private drop-shadow-tags
  #{:feOffset :feGaussianBlur :feColorMatrix})

(defn- find-filter-element
  "Finds a filter element by tag in filter content."
  [filter-content tag]
  (some #(when (= tag (:tag %)) %) filter-content))

(defn- find-filter-def
  [shape]
  (let [filter-attr (or (dm/get-in shape [:svg-attrs :filter])
                        (dm/get-in shape [:svg-attrs :style :filter]))
        svg-defs    (dm/get-prop shape :svg-defs)]
    (when (and filter-attr svg-defs)
      (let [filter-ids (csvg/extract-ids filter-attr)]
        (some #(get svg-defs %) filter-ids)))))

(defn- build-blur
  [gaussian-blur]
  (when gaussian-blur
    {:id (uuid/next)
     :type :layer-blur
     ;; For layer blur the value matches stdDeviation directly
     :value (-> (dm/get-in gaussian-blur [:attrs :stdDeviation])
                (d/parse-double 0))
     :hidden false}))

(defn- build-drop-shadow
  [filter-content drop-shadow-elements]
  (let [offset-elem (find-filter-element filter-content :feOffset)]
    (when (and offset-elem (seq drop-shadow-elements))
      (let [blur-elem  (find-filter-element drop-shadow-elements :feGaussianBlur)
            dx         (-> (dm/get-in offset-elem [:attrs :dx])
                           (d/parse-double 0))
            dy         (-> (dm/get-in offset-elem [:attrs :dy])
                           (d/parse-double 0))
            blur-value (if blur-elem
                         (-> (dm/get-in blur-elem [:attrs :stdDeviation])
                             (d/parse-double 0)
                             (* 2))
                         0)]
        [{:id (uuid/next)
          :style :drop-shadow
          :offset-x dx
          :offset-y dy
          :blur blur-value
          :spread 0
          :hidden false
          ;; TODO: parse feColorMatrix to extract color/opacity
          :color {:color "#000000" :opacity 1}}]))))

(defn apply-svg-filters
  "Derives native blur/shadow from SVG filter definitions when the shape does
  not already have them. The SVG attributes are left untouched so SVG fallback
  rendering keeps working the same way as gradient fills."
  [shape]
  (let [existing-blur   (:blur shape)
        existing-shadow (:shadow shape)]
    (if-let [filter-def (find-filter-def shape)]
      (let [content              (:content filter-def)
            gaussian-blur        (find-filter-element content :feGaussianBlur)
            drop-shadow-elements (filter #(contains? drop-shadow-tags (:tag %)) content)
            blur                 (or existing-blur (build-blur gaussian-blur))
            shadow               (if (seq existing-shadow)
                                   existing-shadow
                                   (build-drop-shadow content drop-shadow-elements))]
        (cond-> shape
          blur (assoc :blur blur)
          (seq shadow) (assoc :shadow shadow)))
      shape)))

(defn apply-svg-derived
  "Applies SVG-derived effects (fills, blur, shadows) uniformly.
  - Keeps user fills if present; otherwise derives from SVG.
  - Converts SVG filters into native blur/shadow when needed.
  - Always returns shape with :fills (possibly []) and blur/shadow keys."
  [shape]
  (let [shape' (apply-svg-filters shape)
        fills  (or (svg-fills/resolve-shape-fills shape') [])]
    (assoc shape'
           :fills fills
           :blur (:blur shape')
           :shadow (:shadow shape'))))

