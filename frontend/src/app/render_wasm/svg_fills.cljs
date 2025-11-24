;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.render-wasm.svg-fills
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.svg :as csvg]
   [app.common.types.color :as clr]
   [clojure.string :as str]))

(def ^:private url-fill-pattern
  #"url\(\s*['\"]?#([^)'\"]+)['\"]?\s*\)")

(defn- trim-fill-value
  [value]
  (let [string-value (cond
                       (string? value) value
                       (keyword? value) (name value)
                       (symbol? value) (name value)
                       (number? value) (str value)
                       (some? value) (str value)
                       :else nil)]
    (when (some? string-value)
      (let [trimmed (str/trim string-value)]
        (when (seq trimmed)
          trimmed)))))

(defn- parse-length
  [value]
  (cond
    (nil? value) nil
    (number? value) (double value)
    :else
    (let [value (trim-fill-value value)]
      (when (seq value)
        (let [percent? (str/ends-with? value "%")
              px? (str/ends-with? value "px")
              numeric (cond
                        percent? (subs value 0 (dec (count value)))
                        px? (subs value 0 (- (count value) 2))
                        :else value)
              parsed (d/parse-double numeric)]
          (when parsed
            (if percent?
              (/ parsed 100.0)
              parsed)))))))

(defn- parse-offset
  [value]
  (let [length (parse-length (or value 0))]
    (-> (or length 0.0)
        (max 0.0)
        (min 1.0))))

(defn- parse-opacity
  [value]
  (let [parsed (parse-length value)]
    (if (some? parsed) parsed 1.0)))

(defn- shape->selrect
  [shape]
  (let [selrect (dm/get-prop shape :selrect)]
    (cond
      (grc/rect? selrect) selrect
      (map? selrect) (grc/make-rect selrect)
      :else (grc/make-rect {:x (or (dm/get-prop shape :x) 0)
                            :y (or (dm/get-prop shape :y) 0)
                            :width (max 0.01 (or (dm/get-prop shape :width) 1))
                            :height (max 0.01 (or (dm/get-prop shape :height) 1))}))))

(defn- normalize-point
  [pt units shape]
  (if (= units "userspaceonuse")
    (let [rect (shape->selrect shape)
          width (max 0.01 (dm/get-prop rect :width))
          height (max 0.01 (dm/get-prop rect :height))
          origin-x (or (dm/get-prop rect :x) (dm/get-prop rect :x1) 0)
          origin-y (or (dm/get-prop rect :y) (dm/get-prop rect :y1) 0)]
      (gpt/point (/ (- (dm/get-prop pt :x) origin-x) width)
                 (/ (- (dm/get-prop pt :y) origin-y) height)))
    pt))

(defn- normalize-attrs
  [attrs]
  (into {}
        (map (fn [[k v]]
               (let [key (cond
                           (keyword? k) (name k)
                           (symbol? k) (name k)
                           :else (str k))]
                 [(keyword (str/lower-case key)) v])))
        (or attrs {})))

(defn- id-candidates
  [id]
  (let [base (cond
               (string? id) id
               (keyword? id) (name id)
               (symbol? id) (name id)
               (some? id) (str id)
               :else nil)
        lower (some-> base str/lower-case)
        kebab (some-> base
                      (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
                      str/lower-case)]
    (->> [(when (string? id) id)
          (when (keyword? id) id)
          (when (symbol? id) id)
          base
          (when base (keyword base))
          (when base (symbol base))
          lower
          (when lower (keyword lower))
          (when lower (symbol lower))
          kebab
          (when kebab (keyword kebab))
          (when kebab (symbol kebab))]
         (remove #(or (nil? %) (and (string? %) (str/blank? %))))
         distinct)))

(defn- svg-def-by-id
  [defs id]
  (some #(get defs %)
        (id-candidates id)))

(defn- normalize-gradient-id
  [value]
  (when-let [clean (trim-fill-value value)]
    (let [without (str/replace clean #"^#" "")]
      (when (seq without) without))))

(defn- attr
  "Returns the first matching value for the provided attribute keys."
  [attrs & keys]
  (some #(get attrs %) keys))

(defn- resolve-gradient-node
  [shape gradient-id]
  (let [defs (dm/get-prop shape :svg-defs)]
    (when (and defs gradient-id)
      (let [chain (loop [gid gradient-id
                         seen #{}
                         acc []]
                    (let [normalized (normalize-gradient-id gid)]
                      (if (or (nil? normalized)
                              (contains? seen normalized))
                        acc
                        (if-let [node (svg-def-by-id defs normalized)]
                          (let [attrs (normalize-attrs (:attrs node))
                                tag   (let [raw (:tag node)]
                                        (cond
                                          (keyword? raw) raw
                                          (string? raw) (keyword raw)
                                          :else raw))
                                content (:content node)
                                href (or (get attrs :xlinkhref)
                                         (get attrs :xlink-href)
                                         (get attrs :xlink:href)
                                         (get attrs :href))]
                            (recur href
                                   (conj seen normalized)
                                   (conj acc {:tag tag
                                              :attrs attrs
                                              :content content})))
                          acc))))]
        (when (seq chain)
          (let [combined
                (reduce
                 (fn [result node]
                   (let [tag (or (:tag node) (:tag result))
                         attrs (merge (:attrs result) (:attrs node))
                         content (let [own (:content node)]
                                   (if (seq own)
                                     own
                                     (:content result)))]
                     {:tag tag
                      :attrs attrs
                      :content content}))
                 {} (reverse chain))]
            (when (contains? #{:linearGradient :radialGradient} (:tag combined))
              (let [result (update combined :content #(or % []))]
                result))))))))

(defn- parse-gradient-stop
  [stop-node]
  (let [attrs (normalize-attrs (:attrs stop-node))
        style (some-> (get attrs :style) csvg/parse-style)
        color-value (or (get attrs :stop-color)
                        (get attrs :stopcolor)
                        (get style :stop-color)
                        (get style :stopColor))
        color-value (trim-fill-value color-value)
        color-value (if (= color-value "currentcolor") clr/black color-value)
        color (when (clr/color-string? color-value)
                (clr/parse color-value))
        opacity (or (get attrs :stop-opacity)
                    (get attrs :stopopacity)
                    (get style :stop-opacity)
                    (get style :stopOpacity))
        offset (or (get attrs :offset) "0")]
    (when color
      (d/without-nils {:color color
                       :opacity (some-> opacity parse-opacity)
                       :offset (parse-offset offset)}))))

(defn- apply-gradient-transform
  [points transform]
  (if transform
    (let [matrix (csvg/parse-transform transform)]
      (mapv #(gpt/transform % matrix) points))
    points))

(defn- build-linear-gradient
  [shape {:keys [attrs content]}]
  (let [units (-> (or (attr attrs :gradientunits :gradient-units) "objectBoundingBox")
                  (str/lower-case))
        transform (attr attrs :gradienttransform :gradient-transform)
        x1 (or (parse-length (or (get attrs :x1) (get attrs :x))) 0.0)
        y1 (or (parse-length (or (get attrs :y1) (get attrs :y))) 0.0)
        x2 (or (parse-length (or (get attrs :x2) (get attrs :x))) 1.0)
        y2 (or (parse-length (or (get attrs :y2) (get attrs :y))) 0.0)
        stops (->> content
                   (keep (fn [node]
                           (when (= (keyword (:tag node)) :stop)
                             (parse-gradient-stop node))))
                   vec)]
    (when (seq stops)
      (let [points (apply-gradient-transform [(gpt/point x1 y1)
                                              (gpt/point x2 y2)]
                                             transform)
            [start end] (map #(normalize-point % units shape) points)]
        {:type :linear
         :start-x (dm/get-prop start :x)
         :start-y (dm/get-prop start :y)
         :end-x (dm/get-prop end :x)
         :end-y (dm/get-prop end :y)
         :width 1
         :stops stops}))))

(defn- build-radial-gradient
  [shape {:keys [attrs content]}]
  (let [units (-> (or (attr attrs :gradientunits :gradient-units) "objectBoundingBox")
                  (str/lower-case))
        transform (attr attrs :gradienttransform :gradient-transform)
        cx (or (parse-length (or (get attrs :cx) (get attrs :fx))) 0.5)
        cy (or (parse-length (or (get attrs :cy) (get attrs :fy))) 0.5)
        r  (or (parse-length (get attrs :r)) 0.5)
        stops (->> content
                   (keep (fn [node]
                           (when (= (keyword (:tag node)) :stop)
                             (parse-gradient-stop node))))
                   vec)]
    (when (seq stops)
      (let [[center radius-point]
            (let [points (apply-gradient-transform [(gpt/point cx cy)
                                                    (gpt/point (+ cx r) cy)]
                                                   transform)]
              (map #(normalize-point % units shape) points))
            radius (gpt/distance center radius-point)]
        {:type :radial
         :start-x (dm/get-prop center :x)
         :start-y (dm/get-prop center :y)
         :end-x (dm/get-prop radius-point :x)
         :end-y (dm/get-prop radius-point :y)
         :width radius
         :stops stops}))))

(defn- svg-gradient->fill
  [shape value]
  (let [trimmed (trim-fill-value value)
        fill-str (cond
                   (string? trimmed) trimmed
                   (some? trimmed) (str trimmed)
                   :else nil)]
    (when-let [gradient-id
               (when (string? fill-str)
                 (some-> (re-matches url-fill-pattern fill-str)
                         (nth 1 nil)))]
      (when-let [node (resolve-gradient-node shape gradient-id)]
        (case (:tag node)
          :linearGradient (build-linear-gradient shape node)
          :radialGradient (build-radial-gradient shape node)
          nil)))))

(defn- parse-svg-fill
  [shape value]
  (when-let [trimmed (trim-fill-value value)]
    (let [normalized (if (= (str/lower-case trimmed) "currentcolor") clr/black trimmed)]
      (cond
        (= normalized "none") nil

        (or (clr/color-string? normalized)
            (keyword? value))
        {:type :color
         :value (clr/parse normalized)}

        (str/starts-with? normalized "url(")
        (when-let [gradient (svg-gradient->fill shape normalized)]
          {:type :gradient
           :value gradient})

        :else nil))))

(defn svg-fill->fills
  "Returns a sequence with a single fill derived from the shape SVG attrs/defs,
  or nil when no SVG fill information can be inferred."
  [shape]
  (let [style-fill (parse-svg-fill shape (dm/get-in shape [:svg-attrs :style :fill]))
        attr-fill  (parse-svg-fill shape (dm/get-in shape [:svg-attrs :fill]))
        {:keys [type value]} (or style-fill attr-fill)]
    (when (some? type)
      (let [opacity (or (some-> (dm/get-in shape [:svg-attrs :style :fillOpacity])
                                (d/parse-double 1))
                        (some-> (dm/get-in shape [:svg-attrs :fillOpacity])
                                (d/parse-double 1)))
            base-fill (case type
                        :color {:fill-color value}
                        :gradient {:fill-color-gradient value})]
        [(cond-> base-fill
           (some? opacity) (assoc :fill-opacity opacity))]))))

(defn resolve-shape-fills
  "Returns the fills that should be sent to WASM for the provided shape,
  reusing existing fills when present, falling back to SVG derived fills,
  and finally defaulting to the standard SVG black fill when needed."
  [shape]
  (let [base-fills (dm/get-prop shape :fills)
        fallback (svg-fill->fills shape)
        type (dm/get-prop shape :type)]
    (cond
      (seq base-fills) base-fills
      (seq fallback) fallback
      (and (contains? shape :svg-attrs)
           (or (= :svg-raw type)
               (= :group type)))
      [{:fill-color "#000000" :fill-opacity 1}]
      :else [])))

