;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.render-wasm.api.fonts
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.fonts :as fonts]
   [app.main.store :as st]
   [app.render-wasm.helpers :as h]
   [app.render-wasm.wasm :as wasm]
   [app.util.http :as http]
   [app.util.webapi :as wapi]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [goog.object :as gobj]
   [lambdaisland.uri :as u]
   [okulary.core :as l]))

(def ^:private fonts
  (l/derived :fonts st/state))

(defn- google-font-id->uuid
  [font-id]
  (let [font (get @fonts/fontsdb font-id)]
    (:uuid font)))


(defn ^:private font->ttf-id [font-uuid font-style font-weight]
  (if (str/starts-with? font-uuid "gfont-")
    font-uuid
    (let [matching-font (d/seek (fn [[_ font]]
                                  (and (= (:font-id font) font-uuid)
                                       (= (:font-style font) font-style)
                                       (= (:font-weight font) font-weight)))
                                (seq @fonts))]
      (when matching-font
        (:ttf-file-id (second matching-font))))))

;; IMPORTANT: It should be noted that only TTF fonts can be stored.
(defn- store-font-buffer
  [font-data font-array-buffer]
  (let [id-buffer (:family-id-buffer font-data)
        size (.-byteLength font-array-buffer)
        ptr  (h/call wasm/internal-module "_alloc_bytes" size)
        heap (gobj/get ^js wasm/internal-module "HEAPU8")
        mem  (js/Uint8Array. (.-buffer heap) ptr size)]
    (.set mem (js/Uint8Array. font-array-buffer))
    (h/call wasm/internal-module "_store_font"
            (aget id-buffer 0)
            (aget id-buffer 1)
            (aget id-buffer 2)
            (aget id-buffer 3)
            (:weight font-data)
            (:style font-data))
    true))

(defn- store-font-url
  [font-data font-url]
  (->> (http/send! {:method :get
                    :uri font-url
                    :response-type :blob})
       (rx/map :body)
       (rx/mapcat wapi/read-file-as-array-buffer)
       (rx/map (fn [array-buffer] (store-font-buffer font-data array-buffer)))))

(defn- google-font-ttf-url
  [font-id font-variant-id]
  (let [font (get @fonts/fontsdb font-id)
        variant (d/seek (fn [variant]
                          (= (:id variant) font-variant-id))
                        (:variants font))
        file (-> (:ttf-url variant)
                 (str/replace "http://fonts.gstatic.com/s/" (u/join cf/public-uri "/internal/gfonts/font/")))]
    file))

(defn- font-id->ttf-url
  [font-id font-variant-id]
  (if (str/starts-with? font-id "gfont-")
    ;; if font-id is a google font (starts with gfont-), we need to get the ttf url from Google Fonts API.
    (google-font-ttf-url font-id font-variant-id)
    ;; otherwise, we return the font from our public-uri
    (str (u/join cf/public-uri "assets/by-id/" font-id))))

(defn- store-font-id
  [font-data asset-id]
  (when asset-id
    (let [uri (font-id->ttf-url asset-id (:font-variant-id font-data))
          id-buffer (uuid/get-u32 (:wasm-id font-data))
          font-data (assoc font-data :family-id-buffer id-buffer)
          font-stored? (not= 0 (h/call wasm/internal-module "_is_font_uploaded"
                                       (aget id-buffer 0)
                                       (aget id-buffer 1)
                                       (aget id-buffer 2)
                                       (aget id-buffer 3)
                                       (:weight font-data)
                                       (:style font-data)))]
      (when-not font-stored? (store-font-url font-data uri)))))

(defn serialize-font-style
  [font-style]
  (case font-style
    "normal" 0
    "regular" 0
    "italic" 1
    0))

(defn serialize-font-id
  [font-id]
  (let [google-font? (str/starts-with? font-id "gfont-")]
    (if google-font?
      (uuid/get-u32 (google-font-id->uuid font-id))
      (let [no-prefix (subs font-id (inc (str/index-of font-id "-")))
            as-uuid (uuid/uuid no-prefix)]
        (uuid/get-u32 as-uuid)))))

(defn serialize-font-weight
  [font-weight]
  (js/Number font-weight))

(defn store-fonts
  [fonts]
  (keep (fn [font]
          (let [font-id (dm/get-prop font :font-id)
                google-font? (str/starts-with? font-id "gfont-")
                font-variant-id (dm/get-prop font :font-variant-id)
                variant-parts (str/split font-variant-id #"\-")
                variant-parts (if (= (count variant-parts) 1)
                                (conj variant-parts "400")
                                variant-parts)
                style (first variant-parts)
                weight (serialize-font-weight (last variant-parts))
                font-id (if google-font?
                          font-id
                          (uuid/uuid (subs font-id (inc (str/index-of font-id "-")))))
                asset-id (font->ttf-id font-id style weight)
                wasm-id (if google-font? (google-font-id->uuid font-id) font-id)
                font-data {:family-id font-id
                           :wasm-id wasm-id
                           :font-variant-id font-variant-id
                           :style (serialize-font-style style)
                           :weight weight}]
            (store-font-id font-data asset-id))) fonts))
