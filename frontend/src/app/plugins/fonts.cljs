;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.fonts
  (:require
   [app.common.data :as d]
   [app.common.record :as cr]
   [app.main.data.workspace.texts :as dwt]
   [app.main.fonts :as fonts]
   [app.main.store :as st]
   [app.plugins.shape :as shape]
   [app.plugins.text :as text]
   [app.plugins.utils :as u]
   [app.util.object :as obj]
   [cuerdas.core :as str]))

(deftype PenpotFontVariant [name fontVariantId fontWeight fontStyle])

(deftype PenpotFont [name fontId fontFamily fontStyle fontVariantId fontWeight variants]
  Object

  (applyToText [_ text variant]
    (cond
      (not (shape/shape-proxy? text))
      (u/display-not-valid :applyToText text)

      ;; TODO: Check variant inside font variants

      :else
      (let [id (obj/get text "$id")
            values {:font-id fontId
                    :font-family fontFamily
                    :font-style (d/nilv (obj/get variant "fontStyle") fontStyle)
                    :font-variant-id (d/nilv (obj/get variant "fontVariantId") fontVariantId)
                    :font-weight (d/nilv (obj/get variant "fontWeight") fontWeight)}]
        (st/emit! (dwt/update-attrs id values)))))

  (applyToRange [_ range variant]
    (cond
      (not (text/text-range? range))
      (u/display-not-valid :applyToRange range)

      ;; TODO: Check variant inside font variants

      :else
      (let [id    (obj/get range "$id")
            start (obj/get range "start")
            end   (obj/get range "end")
            values {:font-id fontId
                    :font-family fontFamily
                    :font-style (d/nilv (obj/get variant "fontStyle") fontStyle)
                    :font-variant-id (d/nilv (obj/get variant "fontVariantId") fontVariantId)
                    :font-weight (d/nilv (obj/get variant "fontWeight") fontWeight)}]
        (st/emit! (dwt/update-text-range id start end values))))))

(defn font-proxy? [p]
  (instance? PenpotFont p))

(defn font-proxy
  [{:keys [id name variants] :as font}]
  (when (some? font)
    (let [default-variant (fonts/get-default-variant font)]
      (PenpotFont.
       name
       id
       id
       (:style default-variant)
       (:id default-variant)
       (:weight default-variant)
       (apply
        array
        (->> variants
             (map (fn [{:keys [id name style weight]}]
                    (PenpotFontVariant. name id weight style)))))))))

(deftype PenpotFontsSubcontext [$plugin]
  Object
  (findById
    [_ id]
    (cond
      (not (string? id))
      (u/display-not-valid :findbyId id)

      :else
      (font-proxy (d/seek #(str/includes? (str/lower (:id %)) (str/lower id)) (vals @fonts/fontsdb)))))

  (findByName
    [_ name]
    (cond
      (not (string? name))
      (u/display-not-valid :findByName name)

      :else
      (font-proxy (d/seek #(str/includes? (str/lower (:name %)) (str/lower name)) (vals @fonts/fontsdb)))))

  (findAllById
    [_ id]
    (cond
      (not (string? id))
      (u/display-not-valid :findAllById name)

      :else
      (apply array (->> (vals @fonts/fontsdb)
                        (filter #(str/includes? (str/lower (:id %)) (str/lower id)))
                        (map font-proxy)))))

  (findAllByName
    [_ name]
    (cond
      (not (string? name))
      (u/display-not-valid :findAllByName name)

      :else
      (apply array (->> (vals @fonts/fontsdb)
                        (filter #(str/includes? (str/lower (:name %)) (str/lower name)))
                        (map font-proxy))))))

(defn fonts-subcontext
  [plugin-id]
  (cr/add-properties!
   (PenpotFontsSubcontext. plugin-id)
   {:name "$plugin" :enumerable false :get (constantly plugin-id)}
   {:name "all" :get
    (fn [_]
      (apply array (->> @fonts/fontsdb vals (map font-proxy))))}))
