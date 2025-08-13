;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.text
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.record :as crc]
   [app.common.schema :as sm]
   [app.common.types.shape :as cts]
   [app.common.types.text :as txt]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.texts :as dwt]
   [app.main.fonts :as fonts]
   [app.main.store :as st]
   [app.plugins.format :as format]
   [app.plugins.parser :as parser]
   [app.plugins.register :as r]
   [app.plugins.utils :as u]
   [app.util.object :as obj]
   [app.util.text-editor :as ted]
   [cuerdas.core :as str]))

;; This regex seems duplicated but probably in the future when we support diferent units
;; this will need to reflect changes for each property

(def ^:private font-size-re #"^\d*\.?\d*$")
(def ^:private line-height-re #"^\d*\.?\d*$")
(def ^:private letter-spacing-re #"^\d*\.?\d*$")
(def ^:private text-transform-re #"uppercase|capitalize|lowercase|none")
(def ^:private text-decoration-re #"underline|line-through|none")
(def ^:private text-direction-re #"ltr|rtl")
(def ^:private text-align-re #"left|center|right|justify")
(def ^:private vertical-align-re #"top|center|bottom")

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
                          (and taking? (d/not-empty? text))
                          (conj (assoc node-style :text text)))
              continue? (or (> from end) (>= end to))]
          (recur (when continue? (rest styles)) taking? to result))
        result))))

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

    :shape
    {:this true
     :get #(-> % u/proxy->shape)}

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
           (u/display-not-valid :fontId value)

           (not (r/check-permission plugin-id "content:write"))
           (u/display-not-valid :fontId "Plugin doesn't have 'content:write' permission")

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
           (not (string? value))
           (u/display-not-valid :fontFamily value)

           (not (r/check-permission plugin-id "content:write"))
           (u/display-not-valid :fontFamily "Plugin doesn't have 'content:write' permission")

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
             variant (fonts/get-variant font value)]
         (cond
           (not (string? value))
           (u/display-not-valid :fontVariantId value)

           (not (r/check-permission plugin-id "content:write"))
           (u/display-not-valid :fontVariantId "Plugin doesn't have 'content:write' permission")

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
           (or (empty? value) (not (re-matches font-size-re value)))
           (u/display-not-valid :fontSize value)

           (not (r/check-permission plugin-id "content:write"))
           (u/display-not-valid :fontSize "Plugin doesn't have 'content:write' permission")

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
           (u/display-not-valid :fontWeight (dm/str "Font weight '" value "' not supported for the current font"))

           (not (r/check-permission plugin-id "content:write"))
           (u/display-not-valid :fontWeight "Plugin doesn't have 'content:write' permission")

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
           (u/display-not-valid :fontStyle (dm/str "Font style '" value "' not supported for the current font"))

           (not (r/check-permission plugin-id "content:write"))
           (u/display-not-valid :fontStyle "Plugin doesn't have 'content:write' permission")

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
           (or (empty? value) (not (re-matches line-height-re value)))
           (u/display-not-valid :lineHeight value)

           (not (r/check-permission plugin-id "content:write"))
           (u/display-not-valid :lineHeight "Plugin doesn't have 'content:write' permission")

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
           (or (empty? value) (re-matches letter-spacing-re value))
           (u/display-not-valid :letterSpacing value)

           (not (r/check-permission plugin-id "content:write"))
           (u/display-not-valid :letterSpacing "Plugin doesn't have 'content:write' permission")

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
         (and (string? value) (not (re-matches text-transform-re value)))
         (u/display-not-valid :textTransform value)

         (not (r/check-permission plugin-id "content:write"))
         (u/display-not-valid :textTransform "Plugin doesn't have 'content:write' permission")

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
         (and (string? value) (re-matches text-decoration-re value))
         (u/display-not-valid :textDecoration value)

         (not (r/check-permission plugin-id "content:write"))
         (u/display-not-valid :textDecoration "Plugin doesn't have 'content:write' permission")

         :else
         (st/emit! (dwt/update-text-range id start end {:text-decoration value}))))}

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
         (and (string? value) (re-matches text-direction-re value))
         (u/display-not-valid :direction value)

         (not (r/check-permission plugin-id "content:write"))
         (u/display-not-valid :direction "Plugin doesn't have 'content:write' permission")

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
         (and (string? value) (re-matches text-align-re value))
         (u/display-not-valid :align value)

         (not (r/check-permission plugin-id "content:write"))
         (u/display-not-valid :align "Plugin doesn't have 'content:write' permission")

         :else
         (st/emit! (dwt/update-text-range id start end {:text-align value}))))}

    :fills
    {:this true
     :get
     (fn [self]
       (let [range-data
             (-> self u/proxy->shape :content (content-range->text+styles start end))]
         (->> range-data (map :fills) u/mixed-value format/format-fills)))
     :set
     (fn [_ value]
       (let [value (parser/parse-fills value)]
         (cond
           (not (sm/validate [:vector ::cts/fill] value))
           (u/display-not-valid :fills value)

           (not (r/check-permission plugin-id "content:write"))
           (u/display-not-valid :fills "Plugin doesn't have 'content:write' permission")

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
          (u/display-not-valid :characters value)

          (not (r/check-permission plugin-id "content:write"))
          (u/display-not-valid :characters "Plugin doesn't have 'content:write' permission")

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
          (st/emit! (dwsh/update-shapes [id]
                                        #(update % :content txt/change-text value))))))}

   {:name "growType"
    :get #(-> % u/proxy->shape :grow-type d/name)
    :set
    (fn [self value]
      (let [id (obj/get self "$id")
            value (keyword value)]
        (cond
          (not (contains? #{:auto-width :auto-height :fixed} value))
          (u/display-not-valid :growType value)

          (not (r/check-permission plugin-id "content:write"))
          (u/display-not-valid :growType "Plugin doesn't have 'content:write' permission")

          :else
          (st/emit! (dwsh/update-shapes [id] #(assoc % :grow-type value))))))}

   {:name "fontId"
    :get #(-> % u/proxy->shape text-props :font-id format/format-mixed)
    :set
    (fn [self value]
      (let [id (obj/get self "$id")
            font (when (string? value) (fonts/get-font-data value))
            variant (fonts/get-default-variant font)]
        (cond
          (not font)
          (u/display-not-valid :fontId value)

          (not (r/check-permission plugin-id "content:write"))
          (u/display-not-valid :fontId "Plugin doesn't have 'content:write' permission")

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
          (u/display-not-valid :fontFamily value)

          (not (r/check-permission plugin-id "content:write"))
          (u/display-not-valid :fontFamily "Plugin doesn't have 'content:write' permission")

          :else
          (st/emit! (dwt/update-attrs id (font-data font variant))))))}

   {:name "fontVariantId"
    :get #(-> % u/proxy->shape text-props :font-variant-id format/format-mixed)
    :set
    (fn [self value]
      (let [id      (obj/get self "$id")
            font    (fonts/get-font-data (obj/get self "fontId"))
            variant (fonts/get-variant font value)]
        (cond
          (not variant)
          (u/display-not-valid :fontVariantId value)

          (not (r/check-permission plugin-id "content:write"))
          (u/display-not-valid :fontVariantId "Plugin doesn't have 'content:write' permission")

          :else
          (st/emit! (dwt/update-attrs id (variant-data variant))))))}

   {:name "fontSize"
    :get #(-> % u/proxy->shape text-props :font-size format/format-mixed)
    :set
    (fn [self value]
      (let [id (obj/get self "$id")
            value (str/trim (dm/str value))]
        (cond
          (or (empty? value) (not (re-matches font-size-re value)))
          (u/display-not-valid :fontSize value)

          (not (r/check-permission plugin-id "content:write"))
          (u/display-not-valid :fontSize "Plugin doesn't have 'content:write' permission")

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
          (u/display-not-valid :fontWeight (dm/str "Font weight '" value "' not supported for the current font"))

          (not (r/check-permission plugin-id "content:write"))
          (u/display-not-valid :fontWeight "Plugin doesn't have 'content:write' permission")

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
          (u/display-not-valid :fontStyle (dm/str "Font style '" value "' not supported for the current font"))

          (not (r/check-permission plugin-id "content:write"))
          (u/display-not-valid :fontStyle "Plugin doesn't have 'content:write' permission")

          :else
          (st/emit! (dwt/update-attrs id (variant-data variant))))))}

   {:name "lineHeight"
    :get #(-> % u/proxy->shape text-props :line-height format/format-mixed)
    :set
    (fn [self value]
      (let [id (obj/get self "$id")
            value (str/trim (dm/str value))]
        (cond
          (or (empty? value) (not (re-matches line-height-re value)))
          (u/display-not-valid :lineHeight value)

          (not (r/check-permission plugin-id "content:write"))
          (u/display-not-valid :lineHeight "Plugin doesn't have 'content:write' permission")

          :else
          (st/emit! (dwt/update-attrs id {:line-height value})))))}

   {:name "letterSpacing"
    :get #(-> % u/proxy->shape text-props :letter-spacing format/format-mixed)
    :set
    (fn [self value]
      (let [id (obj/get self "$id")
            value (str/trim (dm/str value))]
        (cond
          (or (not (string? value)) (not (re-matches letter-spacing-re value)))
          (u/display-not-valid :letterSpacing value)

          (not (r/check-permission plugin-id "content:write"))
          (u/display-not-valid :letterSpacing "Plugin doesn't have 'content:write' permission")

          :else
          (st/emit! (dwt/update-attrs id {:letter-spacing value})))))}

   {:name "textTransform"
    :get #(-> % u/proxy->shape text-props :text-transform format/format-mixed)
    :set
    (fn [self value]
      (let [id (obj/get self "$id")]
        (cond
          (or (not (string? value)) (not (re-matches text-transform-re value)))
          (u/display-not-valid :textTransform value)

          (not (r/check-permission plugin-id "content:write"))
          (u/display-not-valid :textTransform "Plugin doesn't have 'content:write' permission")

          :else
          (st/emit! (dwt/update-attrs id {:text-transform value})))))}

   {:name "textDecoration"
    :get #(-> % u/proxy->shape text-props :text-decoration format/format-mixed)
    :set
    (fn [self value]
      (let [id (obj/get self "$id")]
        (cond
          (or (not (string? value)) (not (re-matches text-decoration-re value)))
          (u/display-not-valid :textDecoration value)

          (not (r/check-permission plugin-id "content:write"))
          (u/display-not-valid :textDecoration "Plugin doesn't have 'content:write' permission")

          :else
          (st/emit! (dwt/update-attrs id {:text-decoration value})))))}

   {:name "direction"
    :get #(-> % u/proxy->shape text-props :text-direction format/format-mixed)
    :set
    (fn [self value]
      (let [id (obj/get self "$id")]
        (cond
          (or (not (string? value)) (not (re-matches text-direction-re value)))
          (u/display-not-valid :textDirection value)

          (not (r/check-permission plugin-id "content:write"))
          (u/display-not-valid :textDirection "Plugin doesn't have 'content:write' permission")

          :else
          (st/emit! (dwt/update-attrs id {:text-direction value})))))}

   {:name "align"
    :get #(-> % u/proxy->shape text-props :text-align format/format-mixed)
    :set
    (fn [self value]
      (let [id (obj/get self "$id")]
        (cond
          (or (not (string? value)) (not (re-matches text-align-re value)))
          (u/display-not-valid :align value)

          (not (r/check-permission plugin-id "content:write"))
          (u/display-not-valid :align "Plugin doesn't have 'content:write' permission")

          :else
          (st/emit! (dwt/update-attrs id {:text-align value})))))}

   {:name "verticalAlign"
    :get #(-> % u/proxy->shape text-props :vertical-align)
    :set
    (fn [self value]
      (let [id (obj/get self "$id")]
        (cond
          (or (not (string? value)) (not (re-matches vertical-align-re value)))
          (u/display-not-valid :verticalAlign value)

          (not (r/check-permission plugin-id "content:write"))
          (u/display-not-valid :verticalAlign "Plugin doesn't have 'content:write' permission")

          :else
          (st/emit! (dwt/update-attrs id {:vertical-align value})))))}))
