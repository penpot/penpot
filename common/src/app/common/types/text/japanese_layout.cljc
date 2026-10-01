;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.types.text.japanese-layout
  (:require
   [app.common.data.macros :as dm]
   [cuerdas.core :as str]))

;; Vertical writing (tategaki). Absent values behave as "horizontal-tb"
;; and "mixed", so plain horizontal text never stores these attrs.
(def text-writing-mode-attrs
  [:writing-mode])

(def text-orientation-attrs
  [:text-orientation])

;; Paragraph attrs that act as whole-shape properties: every paragraph must
;; carry the first paragraph's value, since that one decides the flow.
(def whole-shape-paragraph-attrs
  (into text-writing-mode-attrs text-orientation-attrs))

(def text-combine-upright-attrs
  [:text-combine-upright])

;; Emphasis mark (圏点 / bouten) applied per span; absent means no emphasis.
(def text-emphasis-attrs
  [:text-emphasis])

;; Ruby (furigana) annotation text and customization carried per span.
(def text-ruby-attrs
  [:ruby
   :ruby-hidden
   :ruby-size
   :ruby-align
   :ruby-overhang
   :ruby-side])

;; Warichu (割注): the span renders as two half-size lines stacked inline
;; within one column position. Values "warichu" / "none"; absent means off.
(def text-warichu-attrs
  [:warichu])

(def text-font-features-attrs
  [:font-features])

;; Annotation collision policy. "none" preserves the explicit line height;
;; "auto" reserves an additional half-em layer for ruby and emphasis.
(def text-annotation-clearance-attrs
  [:annotation-clearance])

;; Values of the span attrs above when a span does not store them.
(def span-attr-defaults
  {:text-combine-upright "none"
   :text-emphasis        "none"
   :ruby                 ""
   :ruby-hidden          false
   :ruby-size            "half"
   :ruby-align           "space-around"
   :ruby-overhang        "auto"
   :ruby-side            "over"
   :warichu              "none"
   :font-features        "none"
   :annotation-clearance "none"})

;; Annotation font sizes relative to the base font size.
(def emphasis-font-scale 0.5)
(def warichu-font-scale 0.5)

(defn ruby-font-scale
  [ruby-size]
  (case ruby-size
    "third"   (/ 1 3)
    "quarter" 0.25
    0.5))

(defn visible-ruby
  "Ruby annotation text of a text node, or nil when it has none or the
   annotation is hidden."
  [node]
  (let [ruby (:ruby node)]
    (when (and (string? ruby)
               (not (str/blank? ruby))
               (not (true? (:ruby-hidden node))))
      ruby)))

(defn warichu-text?
  "True when a text node renders as warichu: it needs two characters to fill
   its two sub-lines."
  [node]
  (let [text (:text node)]
    (and (= "warichu" (:warichu node))
         (string? text)
         (>= (count text) 2))))

(defn content-writing-mode
  "Writing mode of a text content. Stored per paragraph but treated as a
   whole-shape property: the first paragraph decides the flow."
  [content]
  (dm/get-in content [:children 0 :children 0 :writing-mode]))

(defn vertical-text-content?
  "True when the text content flows vertically (vertical-rl)."
  [content]
  (= "vertical-rl" (content-writing-mode content)))
