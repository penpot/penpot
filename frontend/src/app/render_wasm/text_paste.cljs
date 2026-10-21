;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.render-wasm.text-paste
  "Restyles the text WASM just inserted from a paste fragment (see
  `app.util.text.clipboard`), with run overrides or full Penpot styles."
  (:require
   [app.common.types.text :as txt]
   [app.main.fonts :as fonts]
   [app.render-wasm.text-editor :as text-editor]
   [cuerdas.core :as str]))

(defn fragment->text
  [fragment]
  (->> fragment
       (map (fn [paragraph] (str/join (map :text (:children paragraph)))))
       (str/join "\n")))

(defn styled?
  [fragment]
  (some (fn [paragraph] (some (comp seq :attrs) (:children paragraph))) fragment))

(defn- styled-ranges
  "The content range of every run with overrides, for a fragment inserted at
  `start` (`{:para :offset}`, offsets in UTF-16 units like WASM's)."
  [fragment {:keys [para offset]}]
  (for [[idx paragraph] (map-indexed vector fragment)
        :let [para-idx (+ para idx)
              runs     (:children paragraph)
              starts   (reductions + (if (zero? idx) offset 0)
                                   (map (comp count :text) runs))]
        [run run-start] (map vector runs starts)
        :when (seq (:attrs run))]
    {:attrs (:attrs run)
     :range {:start-para para-idx
             :start-offset run-start
             :end-para para-idx
             :end-offset (+ run-start (count (:text run)))}}))

(defn- target-weight
  "The weight for `span` given a weight override. \"400\" means not bold, so it
  only unbolds: a span below bold keeps its own weight."
  [span font-weight]
  (let [current (or (:font-weight span) "400")]
    (if (and (= font-weight "400") (< (js/parseInt current 10) 600))
      current
      (or font-weight current))))

(defn- resolve-overrides
  "`span` with `overrides`, weight and italic resolved to a variant its font has.
  A changed span leaves its typography, which it no longer matches."
  [span {:keys [font-weight font-style] :as overrides}]
  (let [variant (when (or font-weight font-style)
                  (some-> (fonts/get-font-data (:font-id span))
                          (fonts/find-closest-variant
                           (target-weight span font-weight)
                           (or font-style (:font-style span) "normal"))))
        result  (cond-> (merge span (select-keys overrides [:text-decoration :text-transform]))
                  (some? variant)
                  (assoc :font-weight (:weight variant)
                         :font-style (:style variant)
                         :font-variant-id (:id variant)))]
    (if (= result span)
      span
      (dissoc result :typography-ref-id :typography-ref-file))))

(defn fragment->content
  "Penpot content for `fragment`, with `base` as the style its overrides go over."
  [fragment base]
  {:type "root"
   :children
   [{:type "paragraph-set"
     :children
     (mapv (fn [{:keys [children]}]
             (merge base
                    {:type "paragraph"
                     :children (if (seq children)
                                 (mapv (fn [{:keys [text attrs]}]
                                         (resolve-overrides (assoc base :text text) attrs))
                                       children)
                                 [(assoc base :text "")])}))
           fragment)}]})

(defn apply-fragment-styles
  "Restyles the `fragment` text WASM inserted at `start` in `content`."
  [content fragment start]
  (reduce (fn [content {:keys [range attrs]}]
            (text-editor/apply-styles-over-range content range #(resolve-overrides % attrs)))
          content
          (styled-ranges fragment start)))

;; --- Text copied in Penpot

(def ^:private style-attrs
  (into txt/paragraph-attrs txt/text-node-attrs))

(defn content->fragment
  "The paste fragment for Penpot `content`. Its attrs are whole paragraph and span
  styles, not overrides."
  [content]
  (->> (-> content :children first :children)
       (mapv (fn [paragraph]
               {:attrs (dissoc paragraph :type :children)
                :children (into []
                                (comp (filter (comp seq :text))
                                      (map (fn [span] {:text (:text span) :attrs (dissoc span :text)})))
                                (:children paragraph))}))))

(defn- restyle
  "`node` with its style replaced by `attrs`."
  [node attrs]
  (merge (apply dissoc node style-attrs) attrs))

(defn apply-content-styles
  "Restyles the Penpot `fragment` text WASM inserted at `start` in `content`. The
  first paragraph keeps the style of the one it went into; the others bring their own."
  [content fragment start]
  (let [content (reduce (fn [content {:keys [range attrs]}]
                          (text-editor/apply-styles-over-range content range #(restyle % attrs)))
                        content
                        (styled-ranges fragment start))]
    (reduce (fn [content [idx {:keys [attrs]}]]
              (update-in content [:children 0 :children (+ (:para start) idx)] restyle attrs))
            content
            (rest (map-indexed vector fragment)))))

;; --- Gradients and images of text copied in Penpot

(defn- move-gradient
  "`gradient`, in fractions of the `from` box, in fractions of the `to` box, with its
  points at the same pixel offset from the span origin."
  [gradient from to]
  (let [{x0 :x y0 :y {w0 :width h0 :height} :box} from
        {x1 :x y1 :y {w1 :width h1 :height} :box} to
        move-x                                    #(/ (+ x1 (- (* % w0) x0)) w1)
        move-y                                    #(/ (+ y1 (- (* % h0) y0)) h1)]
    (cond-> (-> gradient
                (update :start-x move-x)
                (update :start-y move-y)
                (update :end-x move-x)
                (update :end-y move-y))
      ;; A radial width is scaled by the box aspect ratio when drawn.
      (= :radial (:type gradient))
      (update :width #(/ (* % w0 h1) (* h0 w1))))))

(defn- move-image
  "`image`, laid over its transform in the `from` box (the whole box without one),
  with a transform in the `to` box at the same pixel offset from the span origin."
  [image from to]
  (let [{x0 :x y0 :y {w0 :width h0 :height} :box} from
        {x1 :x y1 :y {w1 :width h1 :height} :box} to
        {:keys [x y width height]}                (get image :transform {:x 0 :y 0 :width 1 :height 1})]
    (assoc image :transform {:x      (/ (+ x1 (- (* x w0) x0)) w1)
                             :y      (/ (+ y1 (- (* y h0) y0)) h1)
                             :width  (/ (* width w0) w1)
                             :height (/ (* height h0) h1)})))

(defn- move-fills
  [span from to]
  (update span :fills
          (fn [fills]
            (mapv #(cond-> %
                     (some? (:fill-color-gradient %))
                     (update :fill-color-gradient move-gradient from to)

                     (some? (:fill-image %))
                     (update :fill-image move-image from to))
                  fills))))

(defn- sized?
  [{{:keys [width height]} :box}]
  (and (pos? width) (pos? height)))

(defn move-span-fills
  "`content` with the gradients and images of `pasted`, inserted at `start`, moved from
  the `source` geometry to the `target` one (see `text-editor/content-geometry`)."
  [content pasted start source target]
  (let [entries (map vector
                     (flatten (text-editor/span-ranges pasted start))
                     (flatten (:origins source))
                     (flatten (:origins target)))]
    (reduce (fn [content [range from to]]
              (if (and (some? from) (some? to) (sized? from) (sized? to))
                (text-editor/apply-styles-over-range content range #(move-fills % from to))
                content))
            content
            entries)))
