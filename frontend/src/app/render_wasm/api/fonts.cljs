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
                                       (or (nil? (:font-variant-id font))
                                           (= (:font-variant-id font) font-variant-id))))
                                (seq @fonts))]
      (when matching-font
        (:ttf-file-id (second matching-font))))
    :builtin
    (let [variant (font-db-data font-id font-variant-id)]
      (:ttf-url variant))))

;; IMPORTANT: It should be noted that only TTF fonts can be stored.
(defn- store-font-buffer
  [shape-id font-data font-array-buffer emoji? fallback?]
  (let [font-id-buffer  (:family-id-buffer font-data)
        shape-id-buffer (uuid/get-u32 shape-id)
        size (.-byteLength font-array-buffer)
        ptr  (h/call wasm/internal-module "_alloc_bytes" size)
        heap (gobj/get ^js wasm/internal-module "HEAPU8")
        mem  (js/Uint8Array. (.-buffer heap) ptr size)]
    (.set mem (js/Uint8Array. font-array-buffer))
    (h/call wasm/internal-module "_store_font"
            (aget shape-id-buffer 0)
            (aget shape-id-buffer 1)
            (aget shape-id-buffer 2)
            (aget shape-id-buffer 3)
            (aget font-id-buffer 0)
            (aget font-id-buffer 1)
            (aget font-id-buffer 2)
            (aget font-id-buffer 3)
            (:weight font-data)
            (:style font-data)
            emoji?
            fallback?)
    true))

(defn- fetch-font
  [shape-id font-data font-url emoji? fallback?]
  {:key font-url
   :callback #(->> (http/send! {:method :get
                                :uri font-url
                                :response-type :buffer})
                   (rx/map (fn [{:keys [body]}]
                             (store-font-buffer shape-id font-data body emoji? fallback?))))})

(defn- google-font-ttf-url
  [font-id font-variant-id]
  (let [variant (font-db-data font-id font-variant-id)]
    (if-let [ttf-url (:ttf-url variant)]
      (str/replace ttf-url "https://fonts.gstatic.com/s/" (u/join cf/public-uri "/internal/gfonts/font/"))
      nil)))

(defn- font-id->ttf-url
  [font-id asset-id font-variant-id]
  (case (font-backend font-id)
    :google
    (google-font-ttf-url font-id font-variant-id)
    :custom
    (dm/str (u/join cf/public-uri "assets/by-id/" asset-id))
    :builtin
    (dm/str (u/join cf/public-uri "fonts/" asset-id))))

(defn- store-font-id
  [shape-id font-data asset-id emoji? fallback?]
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
                                       (:style font-data)
                                       emoji?))]
      (when-not font-stored?
        (fetch-font shape-id font-data uri emoji? fallback?)))))

(defn serialize-font-style
  [font-style]
  (case font-style
    "normal" 0
    "regular" 0
    "italic" 1
    0))

(defn serialize-font-id
  [font-id]
  (try
    (if (nil? font-id)
      (do
        [uuid/zero])
      (let [google-font? (str/starts-with? font-id "gfont-")]
        (if google-font?
          (uuid/get-u32 (google-font-id->uuid font-id))
          (let [no-prefix (subs font-id (inc (str/index-of font-id "-")))]
            (if (or (nil? no-prefix) (not (string? no-prefix)) (str/blank? no-prefix))
              [uuid/zero]
              (uuid/get-u32 (uuid/uuid no-prefix)))))))
    (catch :default _e
      [uuid/zero])))

(defn serialize-font-weight
  [font-weight]
  (js/Number font-weight))

(defn store-font
  [shape-id font]
  (let [font-id (get font :font-id)
        font-variant-id (get font :font-variant-id)
        emoji? (get font :is-emoji false)
        fallback? (get font :is-fallback false)
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
    (store-font-id shape-id font-data asset-id emoji? fallback?)))

(defn store-fonts
  [shape-id fonts]
  (keep (fn [font] (store-font shape-id font)) fonts))


(defn add-emoji-font
  [fonts]
  (conj fonts {:font-id "gfont-noto-color-emoji"
               :font-variant-id "regular"
               :style 0
               :weight 400
               :is-emoji true}))

(def noto-fonts
  {:japanese    {:font-id "gfont-noto-sans-jp"      :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :chinese     {:font-id "gfont-noto-sans-sc"      :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :korean      {:font-id "gfont-noto-sans-kr"      :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :arabic      {:font-id "gfont-noto-sans-arabic"  :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :cyrillic    {:font-id "gfont-noto-sans-cyrillic" :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :greek       {:font-id "gfont-noto-sans-greek"   :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :hebrew      {:font-id "gfont-noto-sans-hebrew"  :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :thai        {:font-id "gfont-noto-sans-thai"    :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :devanagari  {:font-id "gfont-noto-sans-devanagari" :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :tamil       {:font-id "gfont-noto-sans-tamil"   :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :latin-ext   {:font-id "gfont-noto-sans"         :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :vietnamese  {:font-id "gfont-noto-sans"         :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :armenian    {:font-id "gfont-noto-sans-armenian" :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :bengali     {:font-id "gfont-noto-sans-bengali" :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :cherokee    {:font-id "gfont-noto-sans-cherokee" :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :ethiopic    {:font-id "gfont-noto-sans-ethiopic" :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :georgian    {:font-id "gfont-noto-sans-georgian" :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :gujarati    {:font-id "gfont-noto-sans-gujarati" :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :gurmukhi    {:font-id "gfont-noto-sans-gurmukhi" :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :khmer       {:font-id "gfont-noto-sans-khmer"   :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :lao         {:font-id "gfont-noto-sans-lao"     :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :malayalam   {:font-id "gfont-noto-sans-malayalam" :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :myanmar     {:font-id "gfont-noto-sans-myanmar" :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :sinhala     {:font-id "gfont-noto-sans-sinhala" :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :telugu      {:font-id "gfont-noto-sans-telugu"  :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :tibetan     {:font-id "gfont-noto-sans-tibetan" :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :javanese    {:font-id "noto-sans-javanese"      :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :kannada     {:font-id "noto-sans-kannada"       :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :oriya       {:font-id "noto-sans-oriya"         :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :mongolian   {:font-id "noto-sans-mongolian"     :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :syriac      {:font-id "noto-sans-syriac"        :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :tifinagh    {:font-id "noto-sans-tifinagh"      :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :coptic      {:font-id "noto-sans-coptic"        :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :ol-chiki    {:font-id "noto-sans-ol-chiki"      :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :vai         {:font-id "noto-sans-vai"           :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :shavian     {:font-id "noto-sans-shavian"       :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :osmanya     {:font-id "noto-sans-osmanya"       :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :runic       {:font-id "noto-sans-runic"         :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :old-italic  {:font-id "noto-sans-old-italic"    :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :brahmi      {:font-id "noto-sans-brahmi"        :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :modi        {:font-id "noto-sans-modi"          :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :sora-sompeng {:font-id "noto-sans-sora-sompeng" :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :bamum       {:font-id "noto-sans-bamum"         :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :meroitic    {:font-id "noto-sans-meroitic"      :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}})


(defn add-noto-fonts [fonts languages]
  (reduce (fn [acc lang]
            (if-let [font (get noto-fonts lang)]
              (conj acc font)
              acc))
          fonts
          languages))
