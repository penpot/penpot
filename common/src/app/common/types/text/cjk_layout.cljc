;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.types.text.cjk-layout
  (:require
   [app.common.data :as d]
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
   :text-emphasis-position ["auto" "over-right" "over-left" "under-right" "under-left"]
   :text-underline-position ["auto" "under" "left" "right" "under-left" "under-right"]
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
   :line-adjustment      ["push-in-first" "push-out-first" "push-out-only"]
   ;; Korean line breaking: between syllables, or between words only.
   ;; "spaces-only" is the former name of "keep-words", kept on reload.
   :korean-wrap          ["syllable" "spaces-only" "keep-words"]})

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

;; Vertical writing paragraph attrs. They apply to the whole shape: every
;; paragraph carries the first one's value.
(def vertical-writing-attrs
  [:writing-mode :text-orientation])

;; Paragraph attrs the text options write to every paragraph, whatever the
;; editor selection: a text has one writing mode and one language.
(def whole-text-paragraph-attrs
  (conj vertical-writing-attrs :text-language))

;; Paragraph attrs of the language. A text has one language, but Korean
;; wrapping can change per paragraph.
(def language-paragraph-attrs
  [:text-language :korean-wrap])

;; Ruby attrs that change how a reading looks, not the reading itself.
(def ruby-presentation-attrs
  [:ruby-hidden :ruby-zhuyin :ruby-size :ruby-align :ruby-overhang :ruby-side])

;; Span attrs; `:ruby` holds the reading (furigana) of the span.
(def span-attrs
  (into [:text-combine-upright :text-emphasis :text-emphasis-position :text-underline-position :ruby]
        (concat ruby-presentation-attrs
                [:warichu :font-features :annotation-clearance])))

;; Span attrs owned by the span's characters, not its style: a reading or a
;; warichu note annotates one span and is never copied to other text.
(def text-content-attrs
  [:ruby :warichu])

;; Values of the span attrs above when a span does not store them.
(def span-attr-defaults
  (into {:ruby "" :ruby-hidden false :ruby-zhuyin false}
        (comp (filter #(contains? enum-values %))
              (map (juxt identity enum-default)))
        span-attrs))

;; Values of the paragraph attrs when a paragraph does not store them.
(def paragraph-attr-defaults
  (into {} (map (juxt identity enum-default)) vertical-writing-attrs))

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

(def ^:private zhuyin-base-pattern
  #?(:clj (re-pattern "[\\u3400-\\u9FFF\\uF900-\\uFAFF\\x{20000}-\\x{323AF}][\\uFE00-\\uFE0F\\x{E0100}-\\x{E01EF}]?")
     :cljs (js/RegExp. "[\\u3400-\\u9FFF\\uF900-\\uFAFF\\u{20000}-\\u{323AF}][\\uFE00-\\uFE0F\\u{E0100}-\\u{E01EF}]?" "u")))

(defn zhuyin-base?
  "One Han character, optionally followed by a variation selector."
  [text]
  (boolean (and (string? text) (re-matches zhuyin-base-pattern text))))

(defn zhuyin-reading?
  "One Mandarin syllable: one to three symbols and an optional tone."
  [reading]
  (boolean (and (string? reading)
                (re-matches #"(?:[\u3105-\u3129]{1,3}[ˉˊˇˋ˙]?|˙[\u3105-\u3129]{1,3})" (str/trim reading)))))

(defn zhuyin-slots
  "Upright symbol ink centres in base em for HTML handoff. Input is one valid
   Mandarin syllable. The canvas positions font ink around the same centres."
  [reading]
  (let [chars (vec (str/trim reading))
        symbols (vec (keep-indexed (fn [idx ch] (when (re-matches #"[\u3105-\u3129]" (str ch)) idx)) chars))
        neutral? (some #(= "˙" (str %)) chars)
        lead (if neutral? (/ 1 15) 0)
        top (/ (- 1 (* (count symbols) 0.3) lead) 2)]
    (mapv (fn [idx ch]
            (if-let [symbol (first (keep-indexed #(when (= idx %2) %1) symbols))]
              {:text (str ch) :x 0.15 :y (+ top lead (* (+ symbol 0.5) 0.3))}
              {:text (str ch) :x (if neutral? 0.15 0.45)
               :y (+ top (if neutral? (/ lead 2) (* (dec (count symbols)) 0.3)))}))
          (range (count chars)) chars)))

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
   where runs of up to the value's maximum ASCII or full-width digits
   combine, a lone digit included as in CSS `digits`, and longer runs stay
   ordinary text. Nil for other values."
  [text value]
  (when-let [max-len (get digit-combine-max-lengths value)]
    (->> (partition-by digit? (seq text))
         (mapv (fn [chars]
                 (let [run (apply str chars)]
                   [run (and (digit? (first chars)) (<= (count chars) max-len))]))))))

(defn warichu-text?
  "True when a text node renders as warichu, which needs at least two
   characters for its two sub-lines."
  [node]
  (let [text (:text node)]
    (and (= "warichu" (:warichu node))
         (string? text)
         (>= (count text) 2))))

(defn whole-shape-attr
  "Value of a whole-shape paragraph `attr` (see `whole-text-paragraph-attrs`)
   in a text content: stored per paragraph, the first paragraph's value
   decides. Nil when unset."
  [content attr]
  (dm/get-in content [:children 0 :children 0 attr]))

(defn stored-writing-mode
  "Writing mode stored in a text content, whatever its language, or nil
  when unset. `active-writing-mode` is the one the renderer uses."
  [content]
  (whole-shape-attr content :writing-mode))

(defn stored-vertical?
  "True when the text content stores vertical writing (vertical-rl),
  whatever its language."
  [content]
  (= "vertical-rl" (stored-writing-mode content)))

;; --- Active language

(defn language-family
  "CJK language of a BCP 47 `tag` (`:japanese`, `:chinese` or `:korean`),
  or nil for any other value."
  [tag]
  (when (string? tag)
    (case (-> tag (str/split "-") first str/lower)
      "ja" :japanese
      "zh" :chinese
      "ko" :korean
      nil)))

(def ^:private ruby-attrs
  (into #{:ruby} ruby-presentation-attrs))

;; CJK attrs each language renders; the others stay in the content and come
;; back when the text takes that language again.
(def ^:private language-attrs
  {:japanese {:root      #{:line-adjustment}
              :paragraph (set vertical-writing-attrs)
              :span      (into #{:text-combine-upright :warichu :text-emphasis
                                 :annotation-clearance :font-features}
                               (disj ruby-attrs :ruby-zhuyin))}
   :chinese  {:root      #{}
              :paragraph (set vertical-writing-attrs)
              :span      (into #{:text-emphasis :text-emphasis-position
                                 :text-underline-position :annotation-clearance}
                               ruby-attrs)}
   :korean   {:root      #{}
              :paragraph (conj (set vertical-writing-attrs) :korean-wrap)
              :span      #{}}})

;; Attrs some language renders, per level: the ones without language drop.
(def ^:private language-dependent-attrs
  (reduce-kv (fn [result _ levels] (merge-with into result levels))
             {:root #{} :paragraph #{} :span #{}}
             language-attrs))

(defn content-language
  "Language of a text content: the first paragraph's `:text-language`, as a
  text has one language. Nil when unset."
  [content]
  (whole-shape-attr content :text-language))

(defn- keep-language-attrs
  "`node` without the CJK attrs that its language `family` does not render
  at `level` (`:root`, `:paragraph` or `:span`)."
  [node family level]
  (let [kept (get-in language-attrs [family level] #{})]
    (reduce (fn [node attr]
              (if (contains? kept attr) node (dissoc node attr)))
            node
            (get language-dependent-attrs level))))

(defn- active-paragraph
  [paragraph default-language]
  (let [language (or (:text-language paragraph) default-language)
        family   (language-family language)]
    (cond-> (-> paragraph
                (d/update-when :children
                               (partial mapv #(keep-language-attrs % family :span)))
                (keep-language-attrs family :paragraph))
      (some? language)
      (assoc :text-language language)

      (and (nil? family) (= "distribute" (:text-align paragraph)))
      (assoc :text-align "left"))))

(defn active-content
  "Text `content` as the renderer lays it out. A paragraph without language
  takes `default-language` (the file language), and the CJK attrs its
  language does not use are left out: without a CJK language a text is
  horizontal and not distributed. The stored content keeps every attr."
  [content default-language]
  (let [family (language-family (or (content-language content) default-language))]
    (-> content
        (d/update-when :children
                       (partial mapv
                                (fn [paragraph-set]
                                  (d/update-when paragraph-set :children
                                                 (partial mapv #(active-paragraph % default-language))))))
        (keep-language-attrs family :root))))

(defn active-writing-mode
  "Writing mode the renderer lays `content` out with (see `active-content`):
  nil when unset or when the text has no CJK language."
  [content default-language]
  (when (some? (language-family (or (content-language content) default-language)))
    (stored-writing-mode content)))
