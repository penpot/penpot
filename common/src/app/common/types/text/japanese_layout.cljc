;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.types.text.japanese-layout
  (:require
   [app.common.data.macros :as dm]))

;; Vertical writing (tategaki). Absent values behave as "horizontal-tb"
;; and "mixed", so plain horizontal text never stores these attrs.
(def text-writing-mode-attrs
  [:writing-mode])

(def text-orientation-attrs
  [:text-orientation])

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

(defn content-writing-mode
  "Writing mode of a text content. Stored per paragraph but treated as a
   whole-shape property: the first paragraph decides the flow."
  [content]
  (dm/get-in content [:children 0 :children 0 :writing-mode]))

(defn vertical-text-content?
  "True when the text content flows vertically (vertical-rl)."
  [content]
  (= "vertical-rl" (content-writing-mode content)))
