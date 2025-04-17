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
  (let [font (fonts/get-font-data font-id)]
    (:uuid font)))

(defn- custom-font-id->uuid
  [font-id]
  (uuid/uuid (subs font-id (inc (str/index-of font-id "-")))))

(defn- font-backend
  [font-id]
  (cond
    (str/starts-with? font-id "gfont-")
    :google
    (str/starts-with? font-id "custom-")
    :custom
    :else
    :builtin))

(defn- font-db-data
  [font-id font-variant-id]
  (let [font (fonts/get-font-data font-id)
        variant (fonts/get-variant font font-variant-id)]
    variant))

(defn- font-id->uuid [font-id]
  (case (font-backend font-id)
    :google
    (google-font-id->uuid font-id)
    :custom
    (custom-font-id->uuid font-id)
    :builtin
    uuid/zero))

(defn ^:private font-id->asset-id [font-id font-variant-id]
  (case (font-backend font-id)
    :google
    font-id
    :custom
    (let [font-uuid (custom-font-id->uuid font-id)
          matching-font (d/seek (fn [[_ font]]
                                  (and (= (:font-id font) font-uuid)
                                       (= (:font-variant-id font) font-variant-id)))
                                (seq @fonts))]
      (when matching-font
        (:ttf-file-id (second matching-font))))
    :builtin
    (let [variant (font-db-data font-id font-variant-id)]
      (:ttf-url variant))))

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
  (let [variant (font-db-data font-id font-variant-id)]
    (if-let [ttf-url (:ttf-url variant)]
      (str/replace ttf-url "http://fonts.gstatic.com/s/" (u/join cf/public-uri "/internal/gfonts/font/"))
      nil)))

(defn- font-id->ttf-url
  [font-id asset-id font-variant-id]
  (case (font-backend font-id)
    :google
    (google-font-ttf-url font-id font-variant-id)
    :custom
    (dm/str (u/join cf/public-uri "assets/by-id/" font-id))
    :builtin
    (dm/str (u/join cf/public-uri "fonts/" asset-id))))

(defn- store-font-id
  [font-data asset-id]
  (when asset-id
    (let [uri (font-id->ttf-url (:font-id font-data) asset-id (:font-variant-id font-data))
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
                font-variant-id (dm/get-prop font :font-variant-id)
                wasm-id (font-id->uuid font-id)
                raw-weight (or (:weight (font-db-data font-id font-variant-id)) 400)

                weight (serialize-font-weight raw-weight)

                style (serialize-font-style (cond
                                              (str/includes? font-variant-id "italic") "italic"
                                              :else "normal"))
                asset-id (font-id->asset-id font-id font-variant-id)
                font-data {:wasm-id wasm-id
                           :font-id font-id
                           :font-variant-id font-variant-id
                           :style style
                           :weight weight}]
            (store-font-id font-data asset-id))) fonts))

