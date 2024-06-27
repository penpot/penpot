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
   [app.common.text :as txt]
   [app.common.types.shape :as cts]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.texts :as dwt]
   [app.main.fonts :as fonts]
   [app.main.store :as st]
   [app.plugins.format :as format]
   [app.plugins.parser :as parser]
   [app.plugins.utils :as u]
   [app.util.object :as obj]
   [app.util.text-editor :as ted]
   [cuerdas.core :as str]))

;; This regex seems duplicated but probably in the future when we support diferent units
;; this will need to reflect changes for each property

(def font-size-re #"^\d*\.?\d*$")
(def line-height-re #"^\d*\.?\d*$")
(def letter-spacing-re #"^\d*\.?\d*$")
(def text-transform-re #"uppercase|capitalize|lowercase|none")
(def text-decoration-re #"underline|line-through|none")
(def text-direction-re #"ltr|rtl")
(def text-align-re #"left|center|right|justify")
(def vertical-align-re #"top|center|bottom")

(defn mixed-value
  [values]
  (let [s (set values)]
    (if (= (count s) 1) (first s) "mixed")))

(defn font-data
  [font variant]
  (d/without-nils
   {:font-id (:id font)
    :font-family (:family font)
    :font-variant-id (:id variant)
    :font-style (:style variant)
    :font-weight (:weight variant)}))

(defn variant-data
  [variant]
  (d/without-nils
   {:font-variant-id (:id variant)
    :font-style (:style variant)
    :font-weight (:weight variant)}))

(deftype TextRange [$plugin $file $page $id start end]
  Object
  (applyTypography [_ typography]
    (let [typography (u/proxy->library-typography typography)
          attrs (-> typography
                    (assoc :typography-ref-file $file)
                    (assoc :typography-ref-id (:id typography))
                    (dissoc :id :name))]
      (st/emit! (dwt/update-text-range $id start end attrs)))))

(defn text-range?
  [range]
  (instance? TextRange range))

(defn text-props
  [shape]
  (d/merge
   (dwt/current-root-values {:shape shape :attrs txt/root-attrs})
   (dwt/current-paragraph-values {:shape shape :attrs txt/paragraph-attrs})
   (dwt/current-text-values {:shape shape :attrs txt/text-node-attrs})))

(defn text-range
  [plugin-id file-id page-id id start end]
  (-> (TextRange. plugin-id file-id page-id id start end)
      (crc/add-properties!
       {:name "$plugin" :enumerable false :get (constantly plugin-id)}
       {:name "$id" :enumerable false :get (constantly id)}
       {:name "$file" :enumerable false :get (constantly file-id)}
       {:name "$page" :enumerable false :get (constantly page-id)}

       {:name "shape"
        :get #(-> % u/proxy->shape)}

       {:name "characters"
        :get #(let [range-data
                    (-> % u/proxy->shape :content (txt/content-range->text+styles start end))]
                (->> range-data (map :text) (str/join "")))}

       {:name "fontId"
        :get #(let [range-data
                    (-> % u/proxy->shape :content (txt/content-range->text+styles start end))]
                (->> range-data (map :font-id) mixed-value))

        :set
        (fn [_ value]
          (let [font (when (string? value) (fonts/get-font-data value))
                variant (fonts/get-default-variant font)]
            (cond
              (not (some? font))
              (u/display-not-valid :fontId value)

              :else
              (st/emit! (dwt/update-text-range id start end (font-data font variant))))))}

       {:name "fontFamily"
        :get #(let [range-data
                    (-> % u/proxy->shape :content (txt/content-range->text+styles start end))]
                (->> range-data (map :font-family) mixed-value))

        :set
        (fn [_ value]
          (let [font (fonts/find-font-data {:font-family value})
                variant (fonts/get-default-variant font)]
            (cond
              (not (string? value))
              (u/display-not-valid :fontFamily value)

              :else
              (st/emit! (dwt/update-text-range id start end (font-data font variant))))))}

       {:name "fontVariantId"
        :get #(let [range-data
                    (-> % u/proxy->shape :content (txt/content-range->text+styles start end))]
                (->> range-data (map :font-variant-id) mixed-value))
        :set
        (fn [self value]
          (let [font    (fonts/get-font-data (obj/get self "fontId"))
                variant (fonts/get-variant font value)]
            (cond
              (not (string? value))
              (u/display-not-valid :fontVariantId value)

              :else
              (st/emit! (dwt/update-text-range id start end (variant-data variant))))))}

       {:name "fontSize"
        :get #(let [range-data
                    (-> % u/proxy->shape :content (txt/content-range->text+styles start end))]
                (->> range-data (map :font-size) mixed-value))
        :set
        (fn [_ value]
          (let [value (str/trim (dm/str value))]
            (cond
              (or (empty? value) (not (re-matches font-size-re value)))
              (u/display-not-valid :fontSize value)

              :else
              (st/emit! (dwt/update-text-range id start end {:font-size value})))))}

       {:name "fontWeight"
        :get #(let [range-data
                    (-> % u/proxy->shape :content (txt/content-range->text+styles start end))]
                (->> range-data (map :font-weight) mixed-value))
        :set
        (fn [self value]
          (let [font    (fonts/get-font-data (obj/get self "fontId"))
                variant (fonts/find-variant font {:weight (dm/str value)})]
            (cond
              (nil? variant)
              (u/display-not-valid :fontWeight (dm/str "Font weight '" value "' not supported for the current font"))

              :else
              (st/emit! (dwt/update-text-range id start end (variant-data variant))))))}

       {:name "fontStyle"
        :get #(let [range-data
                    (-> % u/proxy->shape :content (txt/content-range->text+styles start end))]
                (->> range-data (map :font-style) mixed-value))
        :set
        (fn [self value]
          (let [font    (fonts/get-font-data (obj/get self "fontId"))
                variant (fonts/find-variant font {:weight (dm/str value)})]
            (cond
              (nil? variant)
              (u/display-not-valid :fontStyle (dm/str "Font style '" value "' not supported for the current font"))

              :else
              (st/emit! (dwt/update-text-range id start end (variant-data variant))))))}

       {:name "lineHeight"
        :get #(let [range-data
                    (-> % u/proxy->shape :content (txt/content-range->text+styles start end))]
                (->> range-data (map :line-height) mixed-value))
        :set
        (fn [_ value]
          (let [value (str/trim (dm/str value))]
            (cond
              (or (empty? value) (not (re-matches line-height-re value)))
              (u/display-not-valid :lineHeight value)

              :else
              (st/emit! (dwt/update-text-range id start end {:line-height value})))))}

       {:name "letterSpacing"
        :get #(let [range-data
                    (-> % u/proxy->shape :content (txt/content-range->text+styles start end))]
                (->> range-data (map :letter-spacing) mixed-value))
        :set
        (fn [_ value]
          (let [value (str/trim (dm/str value))]
            (cond
              (or (empty? value) (re-matches letter-spacing-re value))
              (u/display-not-valid :letterSpacing value)

              :else
              (st/emit! (dwt/update-text-range id start end {:letter-spacing value})))))}

       {:name "textTransform"
        :get #(let [range-data
                    (-> % u/proxy->shape :content (txt/content-range->text+styles start end))]
                (->> range-data (map :text-transform) mixed-value))
        :set
        (fn [_ value]
          (cond
            (and (string? value) (re-matches text-transform-re value))
            (u/display-not-valid :textTransform value)

            :else
            (st/emit! (dwt/update-text-range id start end {:text-transform value}))))}

       {:name "textDecoration"
        :get #(let [range-data
                    (-> % u/proxy->shape :content (txt/content-range->text+styles start end))]
                (->> range-data (map :text-decoration) mixed-value))
        :set
        (fn [_ value]
          (cond
            (and (string? value) (re-matches text-decoration-re value))
            (u/display-not-valid :textDecoration value)

            :else
            (st/emit! (dwt/update-text-range id start end {:text-decoration value}))))}

       {:name "direction"
        :get #(let [range-data
                    (-> % u/proxy->shape :content (txt/content-range->text+styles start end))]
                (->> range-data (map :direction) mixed-value))
        :set
        (fn [_ value]
          (cond
            (and (string? value) (re-matches text-direction-re value))
            (u/display-not-valid :direction value)

            :else
            (st/emit! (dwt/update-text-range id start end {:direction value}))))}

       {:name "align"
        :get #(let [range-data
                    (-> % u/proxy->shape :content (txt/content-range->text+styles start end))]
                (->> range-data (map :text-align) mixed-value))
        :set
        (fn [_ value]
          (cond
            (and (string? value) (re-matches text-align-re value))
            (u/display-not-valid :text-align value)

            :else
            (st/emit! (dwt/update-text-range id start end {:text-align value}))))}

       {:name "fills"
        :get #(let [range-data
                    (-> % u/proxy->shape :content (txt/content-range->text+styles start end))]
                (->> range-data (map :fills) mixed-value format/format-fills))
        :set
        (fn [_ value]
          (let [value (parser/parse-fills value)]
            (cond
              (not (sm/validate [:vector ::cts/fill] value))
              (u/display-not-valid :fills value)

              :else
              (st/emit! (dwt/update-text-range id start end {:fills value})))))})))

(defn add-text-props
  [shape-proxy]
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

          (contains? (:workspace-editor-state @st/state) id)
          (let [shape (u/proxy->shape self)
                editor
                (-> shape
                    (txt/change-text value)
                    :content
                    ted/import-content
                    ted/create-editor-state)]
            (st/emit! (dwt/update-editor-state shape editor)))

          :else
          (st/emit! (dwsh/update-shapes [id] #(txt/change-text % value))))))}

   {:name "growType"
    :get #(-> % u/proxy->shape :grow-type d/name)
    :set
    (fn [self value]
      (let [id (obj/get self "$id")
            value (keyword value)]
        (cond
          (not (contains? #{:auto-width :auto-height :fixed} value))
          (u/display-not-valid :growType value)

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
          (not (some? font))
          (u/display-not-valid :fontId value)

          :else
          (st/emit! (dwt/update-attrs id (font-data font variant))))))}

   {:name "fontFamily"
    :get #(-> % u/proxy->shape text-props :font-family format/format-mixed)
    :set
    (fn [self value]
      (let [id (obj/get self "$id")
            font (fonts/find-font-data {:font-family value})
            variant (fonts/get-default-variant font)]
        (cond
          (not (some? font))
          (u/display-not-valid :fontFamily value)

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
          (not (some? variant))
          (u/display-not-valid :fontVariantId value)

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

          :else
          (st/emit! (dwt/update-attrs id {:font-size value})))))}

   {:name "fontWeight"
    :get #(-> % u/proxy->shape text-props :font-weight format/format-mixed)
    :set
    (fn [self value]
      (let [id (obj/get self "$id")
            font    (fonts/get-font-data (obj/get self "fontId"))
            variant (fonts/find-variant font {:weight (dm/str value)})]
        (cond
          (nil? variant)
          (u/display-not-valid :fontWeight (dm/str "Font weight '" value "' not supported for the current font"))

          :else
          (st/emit! (dwt/update-attrs id (variant-data variant))))))}

   {:name "fontStyle"
    :get #(-> % u/proxy->shape text-props :font-style format/format-mixed)
    :set
    (fn [self value]
      (let [id (obj/get self "$id")
            font    (fonts/get-font-data (obj/get self "fontId"))
            variant (fonts/find-variant font {:weight (dm/str value)})]
        (cond
          (nil? variant)
          (u/display-not-valid :fontStyle (dm/str "Font style '" value "' not supported for the current font"))

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

          :else
          (st/emit! (dwt/update-attrs id {:line-height value})))))}

   {:name "letterSpacing"
    :get #(-> % u/proxy->shape text-props :letter-spacing format/format-mixed)
    :set
    (fn [self value]
      (let [id (obj/get self "$id")
            value (str/trim (dm/str value))]
        (cond
          (or (empty? value) (re-matches letter-spacing-re value))
          (u/display-not-valid :letterSpacing value)

          :else
          (st/emit! (dwt/update-attrs id {:letter-spacing value})))))}

   {:name "textTransform"
    :get #(-> % u/proxy->shape text-props :text-transform format/format-mixed)
    :set
    (fn [self value]
      (let [id (obj/get self "$id")]
        (cond
          (and (string? value) (re-matches text-transform-re value))
          (u/display-not-valid :textTransform value)

          :else
          (st/emit! (dwt/update-attrs id {:text-transform value})))))}

   {:name "textDecoration"
    :get #(-> % u/proxy->shape text-props :text-decoration format/format-mixed)
    :set
    (fn [self value]
      (let [id (obj/get self "$id")]
        (cond
          (and (string? value) (re-matches text-decoration-re value))
          (u/display-not-valid :textDecoration value)

          :else
          (st/emit! (dwt/update-attrs id {:text-decoration value})))))}

   {:name "direction"
    :get #(-> % u/proxy->shape text-props :text-direction format/format-mixed)
    :set
    (fn [self value]
      (let [id (obj/get self "$id")]
        (cond
          (and (string? value) (re-matches text-direction-re value))
          (u/display-not-valid :textDecoration value)

          :else
          (st/emit! (dwt/update-attrs id {:text-decoration value})))))}

   {:name "align"
    :get #(-> % u/proxy->shape text-props :text-align format/format-mixed)
    :set
    (fn [self value]
      (let [id (obj/get self "$id")]
        (cond
          (and (string? value) (re-matches text-align-re value))
          (u/display-not-valid :align value)

          :else
          (st/emit! (dwt/update-attrs id {:text-align value})))))}

   {:name "verticalAlign"
    :get #(-> % u/proxy->shape text-props :vertical-align)
    :set
    (fn [self value]
      (let [id (obj/get self "$id")]
        (cond
          (and (string? value) (re-matches vertical-align-re value))
          (u/display-not-valid :verticalAlign value)

          :else
          (st/emit! (dwt/update-attrs id {:vertical-align value})))))}))
