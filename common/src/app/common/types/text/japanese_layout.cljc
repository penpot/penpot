;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.types.text.japanese-layout
  (:require
   [app.common.data.macros :as dm]
   [cuerdas.core :as str]))

;; Values of the enum attrs, in the order of the renderer's discriminants
;; (`Raw*` enums in render-wasm). The first value is the default.
(def enum-values
  {;; Vertical writing (tategaki).
   :writing-mode         ["horizontal-tb" "vertical-rl"]
   :text-orientation     ["mixed" "upright"]
   :text-combine-upright ["none" "all" "digits" "digits2" "digits3"]
   ;; Emphasis mark (圏点 / bouten) applied per span.
   :text-emphasis        ["none" "filled-dot" "open-dot" "filled-circle"
                          "open-circle" "filled-sesame" "open-sesame"]
   ;; Warichu (割注): two half-size lines in one line position.
   :warichu              ["none" "warichu"]
   :font-features        ["none" "palt" "vpal"]
   ;; "auto" adds the ruby (at its size) and emphasis marks to the line
   ;; height; "none" keeps it, so annotations sit in the line gap.
   :annotation-clearance ["none" "auto"]
   :ruby-size            ["half" "third" "quarter"]
   :ruby-align           ["space-around" "center" "start" "space-between"]
   :ruby-overhang        ["auto" "none"]
   :ruby-side            ["over" "under"]
   ;; Root attr: how lines slightly too long are fitted (Adobe's Kinsoku
   ;; Type). It decides squeezing; alignment decides the space left over.
   :line-adjustment      ["push-in-first" "push-out-first" "push-out-only"]})

(def ^:private enum-value-sets
  (update-vals enum-values set))

(defn enum-default
  "Default value of the enum `attr`."
  [attr]
  (first (get enum-values attr)))

(defn valid-enum-value?
  "True when `value` is one of the values of the enum `attr`."
  [attr value]
  (contains? (get enum-value-sets attr) value))

;; Paragraph attrs. They apply to the whole shape: every paragraph carries
;; the first one's value.
(def whole-shape-paragraph-attrs
  [:writing-mode :text-orientation])

;; Ruby attrs that change how a reading looks, not the reading itself.
(def ruby-presentation-attrs
  [:ruby-hidden :ruby-size :ruby-align :ruby-overhang :ruby-side])

;; Span attrs; `:ruby` holds the reading (furigana) of the span.
(def span-attrs
  (into [:text-combine-upright :text-emphasis :ruby]
        (concat ruby-presentation-attrs
                [:warichu :font-features :annotation-clearance])))

;; Span attrs owned by the span's characters, not its style: a reading or a
;; warichu note annotates one span and is never copied to other text.
(def text-content-attrs
  [:ruby :warichu])

;; Values of the span attrs above when a span does not store them.
(def span-attr-defaults
  (into {:ruby "" :ruby-hidden false}
        (comp (filter #(contains? enum-values %))
              (map (juxt identity enum-default)))
        span-attrs))

;; Values of the paragraph attrs when a paragraph does not store them.
(def paragraph-attr-defaults
  (into {} (map (juxt identity enum-default)) whole-shape-paragraph-attrs))

;; Glyph of each emphasis style, per CSS `text-emphasis-style`.
(def ^:private emphasis-mark-chars
  {"filled-dot"    "•"
   "open-dot"      "◦"
   "filled-circle" "●"
   "open-circle"   "○"
   "filled-sesame" "﹅"
   "open-sesame"   "﹆"})

(defn emphasis-mark-char
  "Mark glyph of a text-emphasis value, or nil for none."
  [text-emphasis]
  (get emphasis-mark-chars text-emphasis))

;; Emphasis mark font size relative to the base font size.
(def emphasis-font-scale 0.5)

;; Warichu sub-line font size relative to the base font size.
(def warichu-font-scale 0.5)

(defn ruby-font-scale
  [ruby-size]
  (case ruby-size
    "third"   (/ 1 3)
    "quarter" 0.25
    0.5))

(defn visible-ruby
  "Ruby annotation text of a text node, or nil when absent, hidden or on a
   warichu note, which shows no ruby."
  [node]
  (let [ruby (:ruby node)]
    (when (and (string? ruby)
               (not (str/blank? ruby))
               (not (true? (:ruby-hidden node)))
               (not= "warichu" (:warichu node)))
      ruby)))

(defn western-reading?
  "True when a ruby reading holds no CJK, kana or full-width character: it is
   set as words, sideways in vertical text, and never spread letter by
   letter."
  [ruby]
  (and (string? ruby)
       (not (str/blank? ruby))
       (not (re-find #"[\u2E80-\u9FFF\uF900-\uFAFF\uFF00-\uFFEF]" ruby))))

(defn ruby-span?
  "True when a text node carries a ruby reading, hidden or not."
  [node]
  (not (str/blank? (:ruby node))))

(defn annotated-span?
  "True when a text node carries a ruby reading or is a warichu note."
  [node]
  (or (ruby-span? node)
      (= "warichu" (:warichu node))))

(defn- digit?
  [c]
  (let [code #?(:clj (int c) :cljs (.charCodeAt c 0))]
    (or (<= 48 code 57) (<= 0xFF10 code 0xFF19))))

;; Longest digit run each `digits` tate-chu-yoko value combines.
(def ^:private digit-combine-max-lengths
  {"digits" 4 "digits2" 2 "digits3" 3})

(defn digit-combine?
  "True for the tate-chu-yoko values that combine digit runs only."
  [value]
  (contains? digit-combine-max-lengths value))

(defn digit-combine-segments
  "Text split for a `digits` tate-chu-yoko value: `[text combine?]` pairs,
   where runs of two up to the value's maximum ASCII or full-width digits
   combine and longer runs stay ordinary text. Nil for other values."
  [text value]
  (when-let [max-len (get digit-combine-max-lengths value)]
    (->> (partition-by digit? (seq text))
         (mapv (fn [chars]
                 (let [run (apply str chars)]
                   [run (and (digit? (first chars)) (<= 2 (count chars) max-len))]))))))

(defn warichu-text?
  "True when a text node renders as warichu, which needs at least two
   characters for its two sub-lines."
  [node]
  (let [text (:text node)]
    (and (= "warichu" (:warichu node))
         (string? text)
         (>= (count text) 2))))

(defn whole-shape-attr
  "Value of a whole-shape paragraph `attr` (see `whole-shape-paragraph-attrs`)
   in a text content: stored per paragraph, the first paragraph's value
   decides. Nil when unset."
  [content attr]
  (dm/get-in content [:children 0 :children 0 attr]))

(defn content-writing-mode
  "Writing mode of a text content, or nil when unset."
  [content]
  (whole-shape-attr content :writing-mode))

(defn vertical-text-content?
  "True when the text content flows vertically (vertical-rl)."
  [content]
  (= "vertical-rl" (content-writing-mode content)))
