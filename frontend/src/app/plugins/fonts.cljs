;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.fonts
  (:require
   [app.common.data :as d]
   [app.main.data.workspace.texts :as dwt]
   [app.main.fonts :as fonts]
   [app.main.store :as st]
   [app.plugins.format :as format]
   [app.plugins.register :as r]
   [app.plugins.shape :as shape]
   [app.plugins.text :as text]
   [app.plugins.utils :as u]
   [app.util.object :as obj]
   [cuerdas.core :as str]))

(defn font-variant-proxy? [p]
  (obj/type-of? p "FontVariantProxy"))

(defn font-variant-proxy [name id weight style]
  (obj/reify {:name "FontVariantProxy"}
    :name {:get (fn [] name)}
    :fontVariantId {:get (fn [] id)}
    :fontWeight {:get (fn [] weight)}
    :fontStyle {:get (fn [] style)}))

(defn font-proxy? [p]
  (obj/type-of? p "FontProxy"))

(defn font-proxy
  [{:keys [id family name variants] :as font}]
  (when (some? font)
    (let [default-variant (fonts/get-default-variant font)]
      (obj/reify {:name "FontProxy"}
        :name {:get (fn [] name)}
        :fontId {:get (fn [] id)}
        :fontFamily {:get (fn [] family)}
        :fontStyle {:get (fn [] (:style default-variant))}
        :fontVariantId {:get (fn [] (:id default-variant))}
        :fontWeight {:get (fn [] (:weight default-variant))}

        :variants
        {:get
         (fn []
           (format/format-array
            (fn [{:keys [id name style weight]}]
              (font-variant-proxy name id weight style))
            variants))}

        :applyToText
        (fn [text variant]
          (cond
            (not (shape/shape-proxy? text))
            (u/display-not-valid :applyToText text)

            (not (r/check-permission (obj/get text "$plugin") "content:write"))
            (u/display-not-valid :applyToText "Plugin doesn't have 'content:write' permission")

            :else
            (let [id (obj/get text "$id")
                  values {:font-id id
                          :font-family family
                          :font-style (d/nilv (obj/get variant "fontStyle") (:style default-variant))
                          :font-variant-id (d/nilv (obj/get variant "fontVariantId") (:id default-variant))
                          :font-weight (d/nilv (obj/get variant "fontWeight") (:weight default-variant))}]
              (st/emit! (dwt/update-attrs id values)))))

        :applyToRange
        (fn [range variant]
          (cond
            (not (text/text-range-proxy? range))
            (u/display-not-valid :applyToRange range)

            (not (r/check-permission (obj/get range "$plugin") "content:write"))
            (u/display-not-valid :applyToRange "Plugin doesn't have 'content:write' permission")

            :else
            (let [id    (obj/get range "$id")
                  start (obj/get range "start")
                  end   (obj/get range "end")
                  values {:font-id id
                          :font-family family
                          :font-style (d/nilv (obj/get variant "fontStyle") (:style default-variant))
                          :font-variant-id (d/nilv (obj/get variant "fontVariantId") (:id default-variant))
                          :font-weight (d/nilv (obj/get variant "fontWeight") (:weight default-variant))}]
              (st/emit! (dwt/update-text-range id start end values)))))))))

(defn fonts-subcontext
  [plugin-id]
  (obj/reify {:name "PenpotFontsSubcontext"}
    :$plugin {:name "" :enumerable false :get (constantly plugin-id)}

    :all
    {:get
     (fn []
       (format/format-array
        font-proxy
        (vals @fonts/fontsdb)))}

    :findById
    (fn [id]
      (cond
        (not (string? id))
        (u/display-not-valid :findbyId id)

        :else
        (->> (vals @fonts/fontsdb)
             (d/seek #(str/includes? (str/lower (:id %)) (str/lower id)))
             (font-proxy))))

    :findByName
    (fn [name]
      (cond
        (not (string? name))
        (u/display-not-valid :findByName name)

        :else
        (->> (vals @fonts/fontsdb)
             (d/seek #(str/includes? (str/lower (:name %)) (str/lower name)))
             (font-proxy))))

    :findAllById
    (fn [id]
      (cond
        (not (string? id))
        (u/display-not-valid :findAllById name)

        :else
        (format/format-array
         (fn [font]
           (when (str/includes? (str/lower (:id font)) (str/lower id))
             (font-proxy font)))
         (vals @fonts/fontsdb))))

    :findAllByName
    (fn [name]
      (cond
        (not (string? name))
        (u/display-not-valid :findAllByName name)

        :else
        (format/format-array
         (fn [font]
           (when (str/includes? (str/lower (:name font)) (str/lower name))
             (font-proxy font)))
         (vals @fonts/fontsdb))))))
