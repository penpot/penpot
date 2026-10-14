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
