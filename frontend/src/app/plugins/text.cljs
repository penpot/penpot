;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.plugins.text
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.shapes.text :as gst]
   [app.common.record :as crc]
   [app.common.schema :as sm]
   [app.common.types.fills :as types.fills]
   [app.common.types.text :as txt]
   [app.common.types.text.japanese-layout :as jl]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.texts :as dwt]
   [app.main.data.workspace.wasm-text :as dwwt]
   [app.main.features :as features]
   [app.main.fonts :as fonts]
   [app.main.store :as st]
   [app.plugins.fills :as fills]
   [app.plugins.format :as format]
   [app.plugins.parser :as parser]
   [app.plugins.register :as r]
   [app.plugins.utils :as u]
   [app.util.object :as obj]
   [app.util.text-editor :as ted]
   [cuerdas.core :as str]))

(def ^:private text-decoration-re #"underline|line-through|none")
(def ^:private text-direction-re #"ltr|rtl")
(def ^:private text-align-re #"left|center|right|justify")
(def ^:private vertical-align-re #"top|center|bottom")
(def ^:private writing-mode-re #"horizontal-tb|vertical-rl")
(def ^:private text-orientation-re #"mixed|upright")
(def ^:private text-combine-upright-re #"none|all|digits2|digits3|digits")
(def ^:private text-emphasis-re #"none|filled-dot|open-dot|filled-circle|open-circle|filled-sesame|open-sesame")
(def ^:private warichu-re #"none|warichu")
(def ^:private font-features-re #"none|palt|vpal")
(def ^:private annotation-clearance-re #"none|auto")
(def ^:private ruby-size-re #"half|third|quarter")
(def ^:private ruby-align-re #"space-around|center|start|space-between")
(def ^:private ruby-overhang-re #"auto|none")
(def ^:private ruby-side-re #"over|under")

(defn- font-data
  [font variant]
  (d/without-nils
   {:font-id (:id font)
    :font-family (:family font)
    :font-variant-id (:id variant)
    :font-style (:style variant)
    :font-weight (:weight variant)}))

(defn- variant-data
  [variant]
  (d/without-nils
   {:font-variant-id (:id variant)
    :font-style (:style variant)
    :font-weight (:weight variant)}))

(defn- unsupported-weight-message
  "Validation message for a font weight the current font has no variant for,
  listing the weights the font supports."
  [font value]
  (let [weights (->> (:variants font)
                     (map :weight)
                     (distinct)
                     (sort-by #(or (d/parse-integer %) 0))
                     (str/join ", "))]
    (cond-> (dm/str "Font weight '" value "' not supported for the current font")
      (seq weights)
      (str ". Supported weights: " weights))))

(defn- text-props
  [shape]
  (d/merge
   (dwt/current-root-values {:shape shape :attrs txt/root-attrs})
   (dwt/current-paragraph-values {:shape shape :attrs txt/paragraph-attrs})
   (dwt/current-text-values {:shape shape :attrs txt/text-node-attrs})))

(defn- content-range->text+styles
  "Given a root node of a text content extracts the texts with its associated styles"
  [node start end]
  (let [sss (txt/content->text+styles node)]
    (loop [styles  (seq sss)
           taking? false
           acc      0
           result   []]
      (if styles
        (let [[node-style text] (first styles)
              from      acc
              to        (+ acc (count text))
              taking?   (or taking? (and (<= from start) (< start to)))
              text      (subs text (max 0 (- start acc)) (- end acc))
              result    (cond-> result
                          (and taking? (seq text))
                          (conj (assoc node-style :text text)))
              continue? (or (> from end) (>= end to))]
          (recur (when continue? (rest styles)) taking? to result))
        result))))

(def ^:private japanese-range-defaults
  ;; A range without ruby reports null to plugins.
  (assoc jl/span-attr-defaults :ruby nil))

(defn- range-japanese-value
  "Value of a Japanese span `attr` over a text range proxy's characters."
  [range-proxy start end attr]
  (let [default (get japanese-range-defaults attr)]
    (->> (-> range-proxy u/proxy->shape :content (content-range->text+styles start end))
         (map #(get % attr default))
         (u/mixed-value))))

(defn- enum-value?
  "Validator accepting the strings fully matched by `re`."
  [re]
  (fn [value]
    (and (string? value) (some? (re-matches re value)))))

(defn- optional-string?
  [value]
  (or (nil? value) (string? value)))

(defn- attr-setter
  "Property setter that checks `valid?` and the plugin's write access before
   emitting `(update-event self attrs)` with `{attr value}`."
  [plugin-id page-id prop attr valid? update-event]
  (fn [self value]
    (cond
      (not (valid? value))
      (u/not-valid plugin-id prop value)

      (not (r/check-permission plugin-id "content:write"))
      (u/not-valid plugin-id prop "Plugin doesn't have 'content:write' permission")

      (not (u/page-active? page-id))
      (u/not-valid plugin-id prop "Cannot modify a page that is not currently active")

      :else
      (st/emit! (update-event self {attr value})))))

(defn- range-attr-setter
  "Setter of a span attribute over the characters [start, end) of shape `id`."
  [plugin-id page-id id start end prop attr valid?]
  (attr-setter plugin-id page-id prop attr valid?
               (fn [_ attrs] (dwt/update-text-range id start end attrs))))

(defn- shape-attr-setter
  "Setter of a text attribute over the whole text shape."
  [plugin-id page-id prop attr valid?]
  (attr-setter plugin-id page-id prop attr valid?
               (fn [self attrs] (dwt/update-attrs (obj/get self "$id") attrs))))

(defn text-range-proxy?
  [range]
  (obj/type-of? range "TextRange"))

(defn text-range-proxy
  [plugin-id file-id page-id id start end]
  (obj/reify {:name "TextRange"}
    :$plugin {:enumerable false :get (constantly plugin-id)}
    :$id {:enumerable false :get (constantly id)}
    :$file {:enumerable false :get (constantly file-id)}
    :$page {:enumerable false :get (constantly page-id)}
    :$start {:enumerable false :get (constantly start)}
    :$end {:enumerable false :get (constantly end)}

    :shape
    {:get (fn [] (format/shape-proxy plugin-id file-id page-id id))}

    :characters
    {:this true
     :get
     (fn [self]
       (let [range-data
             (-> self u/proxy->shape :content (content-range->text+styles start end))]
         (->> range-data (map :text) (str/join ""))))}

    :fontId
    {:this true
     :get
     (fn [self]
       (let [range-data
             (-> self u/proxy->shape :content (content-range->text+styles start end))]
         (->> range-data (map :font-id) u/mixed-value)))

     :set
     (fn [_ value]
       (let [font (when (string? value) (fonts/get-font-data value))
             variant (fonts/get-default-variant font)]
         (cond
           (not font)
           (u/not-valid plugin-id :fontId value)

           (not (r/check-permission plugin-id "content:write"))
           (u/not-valid plugin-id :fontId "Plugin doesn't have 'content:write' permission")

           (not (u/page-active? page-id))
           (u/not-valid plugin-id :fontId "Cannot modify a page that is not currently active")

           :else
           (st/emit! (dwt/update-text-range id start end (font-data font variant))))))}

    :fontFamily
    {:this true
     :get
     (fn [self]
       (let [range-data
             (-> self u/proxy->shape :content (content-range->text+styles start end))]
         (->> range-data (map :font-family) u/mixed-value)))

     :set
     (fn [_ value]
       (let [font (fonts/find-font-data {:family value})
             variant (fonts/get-default-variant font)]
         (cond
           (nil? font)
           (u/not-valid plugin-id :fontFamily value)

           (not (r/check-permission plugin-id "content:write"))
           (u/not-valid plugin-id :fontFamily "Plugin doesn't have 'content:write' permission")

           (not (u/page-active? page-id))
           (u/not-valid plugin-id :fontFamily "Cannot modify a page that is not currently active")

           :else
           (st/emit! (dwt/update-text-range id start end (font-data font variant))))))}

    :fontVariantId
    {:this true
     :get
     (fn [self]
       (let [range-data
             (-> self u/proxy->shape :content (content-range->text+styles start end))]
         (->> range-data (map :font-variant-id) u/mixed-value)))
     :set
     (fn [self value]
       (let [font    (fonts/get-font-data (obj/get self "fontId"))
             variant (when (string? value)
                       (fonts/find-variant font {:id value}))]
         (cond
           (nil? variant)
           (u/not-valid plugin-id :fontVariantId value)

           (not (r/check-permission plugin-id "content:write"))
           (u/not-valid plugin-id :fontVariantId "Plugin doesn't have 'content:write' permission")

           (not (u/page-active? page-id))
           (u/not-valid plugin-id :fontVariantId "Cannot modify a page that is not currently active")

           :else
           (st/emit! (dwt/update-text-range id start end (variant-data variant))))))}

    :fontSize
    {:this true
     :get
     (fn [self]
       (let [range-data
             (-> self u/proxy->shape :content (content-range->text+styles start end))]
         (->> range-data (map :font-size) u/mixed-value)))
     :set
     (fn [_ value]
       (let [value (str/trim (dm/str value))]
         (cond
           (not (txt/valid-font-size? value))
           (u/not-valid plugin-id :fontSize value)

           (not (r/check-permission plugin-id "content:write"))
           (u/not-valid plugin-id :fontSize "Plugin doesn't have 'content:write' permission")

           (not (u/page-active? page-id))
           (u/not-valid plugin-id :fontSize "Cannot modify a page that is not currently active")

           :else
           (st/emit! (dwt/update-text-range id start end {:font-size value})))))}

    :fontWeight
    {:this true
     :get
     (fn [self]
       (let [range-data
             (-> self u/proxy->shape :content (content-range->text+styles start end))]
         (->> range-data (map :font-weight) u/mixed-value)))

     :set
     (fn [self value]
       (let [font    (fonts/get-font-data (obj/get self "fontId"))
             weight  (dm/str value)
             style   (obj/get self "fontStyle")
             variant
             (or
              (fonts/find-variant font {:style style :weight weight})
              (fonts/find-variant font {:weight weight}))]
         (cond
           (nil? variant)
           (u/not-valid plugin-id :fontWeight (unsupported-weight-message font value))

           (not (r/check-permission plugin-id "content:write"))
           (u/not-valid plugin-id :fontWeight "Plugin doesn't have 'content:write' permission")

           (not (u/page-active? page-id))
           (u/not-valid plugin-id :fontWeight "Cannot modify a page that is not currently active")

           :else
           (st/emit! (dwt/update-text-range id start end (variant-data variant))))))}

    :fontStyle
    {:this true
     :get
     (fn [self]
       (let [range-data
             (-> self u/proxy->shape :content (content-range->text+styles start end))]
         (->> range-data (map :font-style) u/mixed-value)))
     :set
     (fn [self value]
       (let [font    (fonts/get-font-data (obj/get self "fontId"))
             style   (dm/str value)
             weight  (obj/get self "fontWeight")
             variant
             (or
              (fonts/find-variant font {:weight weight :style style})
              (fonts/find-variant font {:style style}))]
         (cond
           (nil? variant)
           (u/not-valid plugin-id :fontStyle (dm/str "Font style '" value "' not supported for the current font"))

           (not (r/check-permission plugin-id "content:write"))
           (u/not-valid plugin-id :fontStyle "Plugin doesn't have 'content:write' permission")

           (not (u/page-active? page-id))
           (u/not-valid plugin-id :fontStyle "Cannot modify a page that is not currently active")

           :else
           (st/emit! (dwt/update-text-range id start end (variant-data variant))))))}

    :lineHeight
    {:this true
     :get
     (fn [self]
       (let [range-data
             (-> self u/proxy->shape :content (content-range->text+styles start end))]
         (->> range-data (map :line-height) u/mixed-value)))
     :set
     (fn [_ value]
       (let [value (str/trim (dm/str value))]
         (cond
           (not (txt/valid-line-height? value))
           (u/not-valid plugin-id :lineHeight value)

           (not (r/check-permission plugin-id "content:write"))
           (u/not-valid plugin-id :lineHeight "Plugin doesn't have 'content:write' permission")

           (not (u/page-active? page-id))
           (u/not-valid plugin-id :lineHeight "Cannot modify a page that is not currently active")

           :else
           (st/emit! (dwt/update-text-range id start end {:line-height value})))))}

    :letterSpacing
    {:this true
     :get
     (fn [self]
       (let [range-data
             (-> self u/proxy->shape :content (content-range->text+styles start end))]
         (->> range-data (map :letter-spacing) u/mixed-value)))
     :set
     (fn [_ value]
       (let [value (str/trim (dm/str value))]
         (cond
           (not (txt/valid-letter-spacing? value))
           (u/not-valid plugin-id :letterSpacing value)

           (not (r/check-permission plugin-id "content:write"))
           (u/not-valid plugin-id :letterSpacing "Plugin doesn't have 'content:write' permission")

           (not (u/page-active? page-id))
           (u/not-valid plugin-id :letterSpacing "Cannot modify a page that is not currently active")

           :else
           (st/emit! (dwt/update-text-range id start end {:letter-spacing value})))))}

    :textTransform
    {:this true
     :get
     (fn [self]
       (let [range-data
             (-> self u/proxy->shape :content (content-range->text+styles start end))]
         (->> range-data (map :text-transform) u/mixed-value)))
     :set
     (fn [_ value]
       (cond
         (not (txt/valid-text-transform? value))
         (u/not-valid plugin-id :textTransform value)

         (not (r/check-permission plugin-id "content:write"))
         (u/not-valid plugin-id :textTransform "Plugin doesn't have 'content:write' permission")

         (not (u/page-active? page-id))
         (u/not-valid plugin-id :textTransform "Cannot modify a page that is not currently active")

         :else
         (st/emit! (dwt/update-text-range id start end {:text-transform value}))))}

    :textDecoration
    {:this true
     :get
     (fn [self]
       (let [range-data
             (-> self u/proxy->shape :content (content-range->text+styles start end))]
         (->> range-data (map :text-decoration) u/mixed-value)))
     :set
     (fn [_ value]
       (cond
         (or (not (string? value)) (not (re-matches text-decoration-re value)))
         (u/not-valid plugin-id :textDecoration value)

         (not (r/check-permission plugin-id "content:write"))
         (u/not-valid plugin-id :textDecoration "Plugin doesn't have 'content:write' permission")

         (not (u/page-active? page-id))
         (u/not-valid plugin-id :textDecoration "Cannot modify a page that is not currently active")

         :else
         (st/emit! (dwt/update-text-range id start end {:text-decoration value}))))}

    :fontFeatures
    {:this true
     :get (fn [self] (range-japanese-value self start end :font-features))
     :set (range-attr-setter plugin-id page-id id start end :fontFeatures :font-features (enum-value? font-features-re))}

    :textCombineUpright
    {:this true
     :get (fn [self] (range-japanese-value self start end :text-combine-upright))
     :set (range-attr-setter plugin-id page-id id start end :textCombineUpright :text-combine-upright (enum-value? text-combine-upright-re))}

    :textEmphasis
    {:this true
     :get (fn [self] (range-japanese-value self start end :text-emphasis))
     :set (range-attr-setter plugin-id page-id id start end :textEmphasis :text-emphasis (enum-value? text-emphasis-re))}

    :warichu
    {:this true
     :get (fn [self] (range-japanese-value self start end :warichu))
     :set (range-attr-setter plugin-id page-id id start end :warichu :warichu (enum-value? warichu-re))}

    :annotationClearance
    {:this true
     :get (fn [self] (range-japanese-value self start end :annotation-clearance))
     :set (range-attr-setter plugin-id page-id id start end :annotationClearance :annotation-clearance (enum-value? annotation-clearance-re))}

    :ruby
    {:this true
     :get (fn [self] (range-japanese-value self start end :ruby))
     :set (range-attr-setter plugin-id page-id id start end :ruby :ruby optional-string?)}

    :rubySize
    {:this true
     :get (fn [self] (range-japanese-value self start end :ruby-size))
     :set (range-attr-setter plugin-id page-id id start end :rubySize :ruby-size (enum-value? ruby-size-re))}

    :rubyAlign
    {:this true
     :get (fn [self] (range-japanese-value self start end :ruby-align))
     :set (range-attr-setter plugin-id page-id id start end :rubyAlign :ruby-align (enum-value? ruby-align-re))}

    :rubyOverhang
    {:this true
     :get (fn [self] (range-japanese-value self start end :ruby-overhang))
     :set (range-attr-setter plugin-id page-id id start end :rubyOverhang :ruby-overhang (enum-value? ruby-overhang-re))}

    :rubySide
    {:this true
     :get (fn [self] (range-japanese-value self start end :ruby-side))
     :set (range-attr-setter plugin-id page-id id start end :rubySide :ruby-side (enum-value? ruby-side-re))}

    :direction
    {:this true
     :get
     (fn [self]
       (let [range-data
             (-> self u/proxy->shape :content (content-range->text+styles start end))]
         (->> range-data (map :direction) u/mixed-value)))
     :set
     (fn [_ value]
       (cond
         (or (not (string? value)) (not (re-matches text-direction-re value)))
         (u/not-valid plugin-id :direction value)

         (not (r/check-permission plugin-id "content:write"))
         (u/not-valid plugin-id :direction "Plugin doesn't have 'content:write' permission")

         (not (u/page-active? page-id))
         (u/not-valid plugin-id :direction "Cannot modify a page that is not currently active")

         :else
         (st/emit! (dwt/update-text-range id start end {:direction value}))))}

    :align
    {:this true
     :get
     (fn [self]
       (let [range-data
             (-> self u/proxy->shape :content (content-range->text+styles start end))]
         (->> range-data (map :text-align) u/mixed-value)))
     :set
     (fn [_ value]
       (cond
         (or (not (string? value)) (not (re-matches text-align-re value)))
         (u/not-valid plugin-id :align value)

         (not (r/check-permission plugin-id "content:write"))
         (u/not-valid plugin-id :align "Plugin doesn't have 'content:write' permission")

         (not (u/page-active? page-id))
         (u/not-valid plugin-id :align "Cannot modify a page that is not currently active")

         :else
         (st/emit! (dwt/update-text-range id start end {:text-align value}))))}

    :fills
    {:this true
     :get
     (fn [self]
       (let [range-data
             (-> self u/proxy->shape :content (content-range->text+styles start end))]
         (->> range-data (map :fills) u/mixed-value fills/format-fills)))
     :set
     (fn [_ value]
       (let [value (parser/parse-fills value)]
         (cond
           (not (sm/validate [:vector types.fills/schema:fill] value))
           (u/not-valid plugin-id :fills value)

           (not (r/check-permission plugin-id "content:write"))
           (u/not-valid plugin-id :fills "Plugin doesn't have 'content:write' permission")

           (not (u/page-active? page-id))
           (u/not-valid plugin-id :fills "Cannot modify a page that is not currently active")

           :else
           (st/emit! (dwt/update-text-range id start end {:fills value})))))}

    :applyTypography
    (fn [typography]
      (let [typography (u/proxy->library-typography typography)
            attrs (-> typography
                      (assoc :typography-ref-file file-id)
                      (assoc :typography-ref-id (:id typography))
                      (dissoc :id :name))]
        (st/emit! (dwt/update-text-range id start end attrs))))))

(defn add-text-props
  [shape-proxy plugin-id]
  (let [page-id (obj/get shape-proxy "$page")]
    (crc/add-properties!
     shape-proxy
     {:name "characters"
      :get #(-> % u/proxy->shape :content txt/content->text)
      :set
      (fn [self value]
        (let [id (obj/get self "$id")]
          ;; The user is currently editing the text. We need to update the
          ;; editor as well
          (cond
            (or (not (string? value)) (empty? value))
            (u/not-valid plugin-id :characters value)

            (not (r/check-permission plugin-id "content:write"))
            (u/not-valid plugin-id :characters "Plugin doesn't have 'content:write' permission")

            (not (u/page-active? page-id))
            (u/not-valid plugin-id :characters "Cannot modify a page that is not currently active")

            (contains? (:workspace-editor-state @st/state) id)
            (let [shape (u/proxy->shape self)
                  editor
                  (-> shape
                      (get :content)
                      (txt/change-text value)
                      ted/import-content
                      ted/create-editor-state)]
              (st/emit! (dwt/update-editor-state shape editor)))

            :else
            (do
              (st/emit! (dwsh/update-shapes [id] #(update % :content txt/change-text value)))
              (when (features/active-feature? @st/state "render-wasm/v1")
                (st/emit! (dwwt/resize-wasm-text-debounce id)))))))}

     {:name "growType"
      :get #(-> % u/proxy->shape :grow-type d/name)
      :set
      (fn [self value]
        (let [id (obj/get self "$id")
              value (keyword value)]
          (cond
            (not (contains? #{:auto-width :auto-height :fixed} value))
            (u/not-valid plugin-id :growType value)

            (not (r/check-permission plugin-id "content:write"))
            (u/not-valid plugin-id :growType "Plugin doesn't have 'content:write' permission")

            (not (u/page-active? page-id))
            (u/not-valid plugin-id :growType "Cannot modify a page that is not currently active")

            :else
            (do
              (st/emit! (dwsh/update-shapes [id] #(assoc % :grow-type value)))
              (when (features/active-feature? @st/state "render-wasm/v1")
                (st/emit! (dwwt/resize-wasm-text-debounce id)))))))}

     {:name "fontId"
      :get #(-> % u/proxy->shape text-props :font-id format/format-mixed)
      :set
      (fn [self value]
        (let [id (obj/get self "$id")
              font (when (string? value) (fonts/get-font-data value))
              variant (fonts/get-default-variant font)]
          (cond
            (not font)
            (u/not-valid plugin-id :fontId value)

            (not (r/check-permission plugin-id "content:write"))
            (u/not-valid plugin-id :fontId "Plugin doesn't have 'content:write' permission")

            (not (u/page-active? page-id))
            (u/not-valid plugin-id :fontId "Cannot modify a page that is not currently active")

            :else
            (st/emit! (dwt/update-attrs id (font-data font variant))))))}

     {:name "fontFamily"
      :get #(-> % u/proxy->shape text-props :font-family format/format-mixed)
      :set
      (fn [self value]
        (let [id (obj/get self "$id")
              font (fonts/find-font-data {:family value})
              variant (fonts/get-default-variant font)]
          (cond
            (not font)
            (u/not-valid plugin-id :fontFamily value)

            (not (r/check-permission plugin-id "content:write"))
            (u/not-valid plugin-id :fontFamily "Plugin doesn't have 'content:write' permission")

            (not (u/page-active? page-id))
            (u/not-valid plugin-id :fontFamily "Cannot modify a page that is not currently active")

            :else
            (st/emit! (dwt/update-attrs id (font-data font variant))))))}

     {:name "fontVariantId"
      :get #(-> % u/proxy->shape text-props :font-variant-id format/format-mixed)
      :set
      (fn [self value]
        (let [id      (obj/get self "$id")
              font    (fonts/get-font-data (obj/get self "fontId"))
              variant (when (string? value)
                        (fonts/find-variant font {:id value}))]
          (cond
            (not variant)
            (u/not-valid plugin-id :fontVariantId value)

            (not (r/check-permission plugin-id "content:write"))
            (u/not-valid plugin-id :fontVariantId "Plugin doesn't have 'content:write' permission")

            (not (u/page-active? page-id))
            (u/not-valid plugin-id :fontVariantId "Cannot modify a page that is not currently active")

            :else
            (st/emit! (dwt/update-attrs id (variant-data variant))))))}

     {:name "fontSize"
      :get #(-> % u/proxy->shape text-props :font-size format/format-mixed)
      :set
      (fn [self value]
        (let [id (obj/get self "$id")
              value (str/trim (dm/str value))]
          (cond
            (not (txt/valid-font-size? value))
            (u/not-valid plugin-id :fontSize value)

            (not (r/check-permission plugin-id "content:write"))
            (u/not-valid plugin-id :fontSize "Plugin doesn't have 'content:write' permission")

            (not (u/page-active? page-id))
            (u/not-valid plugin-id :fontSize "Cannot modify a page that is not currently active")

            :else
            (st/emit! (dwt/update-attrs id {:font-size value})))))}

     {:name "fontWeight"
      :get #(-> % u/proxy->shape text-props :font-weight format/format-mixed)
      :set
      (fn [self value]
        (let [id (obj/get self "$id")
              font    (fonts/get-font-data (obj/get self "fontId"))
              weight  (dm/str value)
              style   (obj/get self "fontStyle")
              variant
              (or
               (fonts/find-variant font {:style style :weight weight})
               (fonts/find-variant font {:weight weight}))]
          (cond
            (nil? variant)
            (u/not-valid plugin-id :fontWeight (unsupported-weight-message font value))

            (not (r/check-permission plugin-id "content:write"))
            (u/not-valid plugin-id :fontWeight "Plugin doesn't have 'content:write' permission")

            (not (u/page-active? page-id))
            (u/not-valid plugin-id :fontWeight "Cannot modify a page that is not currently active")

            :else
            (st/emit! (dwt/update-attrs id (variant-data variant))))))}

     {:name "fontStyle"
      :get #(-> % u/proxy->shape text-props :font-style format/format-mixed)
      :set
      (fn [self value]
        (let [id (obj/get self "$id")
              font    (fonts/get-font-data (obj/get self "fontId"))
              style   (dm/str value)
              weight  (obj/get self "fontWeight")
              variant
              (or
               (fonts/find-variant font {:weight weight :style style})
               (fonts/find-variant font {:style style}))]
          (cond
            (nil? variant)
            (u/not-valid plugin-id :fontStyle (dm/str "Font style '" value "' not supported for the current font"))

            (not (r/check-permission plugin-id "content:write"))
            (u/not-valid plugin-id :fontStyle "Plugin doesn't have 'content:write' permission")

            (not (u/page-active? page-id))
            (u/not-valid plugin-id :fontStyle "Cannot modify a page that is not currently active")

            :else
            (st/emit! (dwt/update-attrs id (variant-data variant))))))}

     {:name "lineHeight"
      :get #(-> % u/proxy->shape text-props :line-height format/format-mixed)
      :set
      (fn [self value]
        (let [id (obj/get self "$id")
              value (str/trim (dm/str value))]
          (cond
            (not (txt/valid-line-height? value))
            (u/not-valid plugin-id :lineHeight value)

            (not (r/check-permission plugin-id "content:write"))
            (u/not-valid plugin-id :lineHeight "Plugin doesn't have 'content:write' permission")

            (not (u/page-active? page-id))
            (u/not-valid plugin-id :lineHeight "Cannot modify a page that is not currently active")

            :else
            (st/emit! (dwt/update-attrs id {:line-height value})))))}

     {:name "letterSpacing"
      :get #(-> % u/proxy->shape text-props :letter-spacing format/format-mixed)
      :set
      (fn [self value]
        (let [id (obj/get self "$id")
              value (str/trim (dm/str value))]
          (cond
            (not (txt/valid-letter-spacing? value))
            (u/not-valid plugin-id :letterSpacing value)

            (not (r/check-permission plugin-id "content:write"))
            (u/not-valid plugin-id :letterSpacing "Plugin doesn't have 'content:write' permission")

            (not (u/page-active? page-id))
            (u/not-valid plugin-id :letterSpacing "Cannot modify a page that is not currently active")

            :else
            (st/emit! (dwt/update-attrs id {:letter-spacing value})))))}

     {:name "textTransform"
      :get #(-> % u/proxy->shape text-props :text-transform format/format-mixed)
      :set
      (fn [self value]
        (let [id (obj/get self "$id")]
          (cond
            (not (txt/valid-text-transform? value))
            (u/not-valid plugin-id :textTransform value)

            (not (r/check-permission plugin-id "content:write"))
            (u/not-valid plugin-id :textTransform "Plugin doesn't have 'content:write' permission")

            (not (u/page-active? page-id))
            (u/not-valid plugin-id :textTransform "Cannot modify a page that is not currently active")

            :else
            (st/emit! (dwt/update-attrs id {:text-transform value})))))}

     {:name "textDecoration"
      :get #(-> % u/proxy->shape text-props :text-decoration format/format-mixed)
      :set
      (fn [self value]
        (let [id (obj/get self "$id")]
          (cond
            (or (not (string? value)) (not (re-matches text-decoration-re value)))
            (u/not-valid plugin-id :textDecoration value)

            (not (r/check-permission plugin-id "content:write"))
            (u/not-valid plugin-id :textDecoration "Plugin doesn't have 'content:write' permission")

            (not (u/page-active? page-id))
            (u/not-valid plugin-id :textDecoration "Cannot modify a page that is not currently active")

            :else
            (st/emit! (dwt/update-attrs id {:text-decoration value})))))}

     {:name "direction"
      :get #(-> % u/proxy->shape text-props :text-direction format/format-mixed)
      :set
      (fn [self value]
        (let [id (obj/get self "$id")]
          (cond
            (or (not (string? value)) (not (re-matches text-direction-re value)))
            (u/not-valid plugin-id :textDirection value)

            (not (r/check-permission plugin-id "content:write"))
            (u/not-valid plugin-id :textDirection "Plugin doesn't have 'content:write' permission")

            (not (u/page-active? page-id))
            (u/not-valid plugin-id :textDirection "Cannot modify a page that is not currently active")

            :else
            (st/emit! (dwt/update-attrs id {:text-direction value})))))}

     {:name "align"
      :get #(-> % u/proxy->shape text-props :text-align format/format-mixed)
      :set
      (fn [self value]
        (let [id (obj/get self "$id")]
          (cond
            (or (not (string? value)) (not (re-matches text-align-re value)))
            (u/not-valid plugin-id :align value)

            (not (r/check-permission plugin-id "content:write"))
            (u/not-valid plugin-id :align "Plugin doesn't have 'content:write' permission")

            (not (u/page-active? page-id))
            (u/not-valid plugin-id :align "Cannot modify a page that is not currently active")

            :else
            (st/emit! (dwt/update-attrs id {:text-align value})))))}

     {:name "verticalAlign"
      :get #(-> % u/proxy->shape text-props :vertical-align)
      :set
      (fn [self value]
        (let [id (obj/get self "$id")]
          (cond
            (or (not (string? value)) (not (re-matches vertical-align-re value)))
            (u/not-valid plugin-id :verticalAlign value)

            (not (r/check-permission plugin-id "content:write"))
            (u/not-valid plugin-id :verticalAlign "Plugin doesn't have 'content:write' permission")

            (not (u/page-active? page-id))
            (u/not-valid plugin-id :verticalAlign "Cannot modify a page that is not currently active")

            :else
            (st/emit! (dwt/update-attrs id {:vertical-align value})))))}

     {:name "writingMode"
      :get #(-> % u/proxy->shape text-props :writing-mode format/format-mixed)
      :set (shape-attr-setter plugin-id page-id :writingMode :writing-mode (enum-value? writing-mode-re))}

     {:name "textOrientation"
      :get #(-> % u/proxy->shape text-props :text-orientation format/format-mixed)
      :set (shape-attr-setter plugin-id page-id :textOrientation :text-orientation (enum-value? text-orientation-re))}

     {:name "textCombineUpright"
      :get #(-> % u/proxy->shape text-props :text-combine-upright format/format-mixed)
      :set (shape-attr-setter plugin-id page-id :textCombineUpright :text-combine-upright (enum-value? text-combine-upright-re))}

     {:name "textEmphasis"
      :get #(-> % u/proxy->shape text-props :text-emphasis format/format-mixed)
      :set (shape-attr-setter plugin-id page-id :textEmphasis :text-emphasis (enum-value? text-emphasis-re))}

     {:name "warichu"
      :get #(-> % u/proxy->shape text-props :warichu format/format-mixed)
      :set (shape-attr-setter plugin-id page-id :warichu :warichu (enum-value? warichu-re))}

     {:name "fontFeatures"
      :get #(-> % u/proxy->shape text-props :font-features format/format-mixed)
      :set (shape-attr-setter plugin-id page-id :fontFeatures :font-features (enum-value? font-features-re))}

     {:name "annotationClearance"
      :get #(-> % u/proxy->shape text-props :annotation-clearance format/format-mixed)
      :set (shape-attr-setter plugin-id page-id :annotationClearance :annotation-clearance (enum-value? annotation-clearance-re))}

     {:name "ruby"
      :get #(-> % u/proxy->shape text-props :ruby format/format-mixed)
      :set (shape-attr-setter plugin-id page-id :ruby :ruby optional-string?)}

     {:name "rubySize"
      :get #(-> % u/proxy->shape text-props :ruby-size format/format-mixed)
      :set (shape-attr-setter plugin-id page-id :rubySize :ruby-size (enum-value? ruby-size-re))}

     {:name "rubyAlign"
      :get #(-> % u/proxy->shape text-props :ruby-align format/format-mixed)
      :set (shape-attr-setter plugin-id page-id :rubyAlign :ruby-align (enum-value? ruby-align-re))}

     {:name "rubyOverhang"
      :get #(-> % u/proxy->shape text-props :ruby-overhang format/format-mixed)
      :set (shape-attr-setter plugin-id page-id :rubyOverhang :ruby-overhang (enum-value? ruby-overhang-re))}

     {:name "rubySide"
      :get #(-> % u/proxy->shape text-props :ruby-side format/format-mixed)
      :set (shape-attr-setter plugin-id page-id :rubySide :ruby-side (enum-value? ruby-side-re))}

     {:name "textBounds"
      :get #(-> % u/proxy->shape gst/shape->bounds format/format-geom-rect)})))
