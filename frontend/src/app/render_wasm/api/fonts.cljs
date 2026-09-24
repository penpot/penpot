;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.render-wasm.api.fonts
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.fonts :as cfnt]
   [app.common.logging :as log]
   [app.common.render-wasm.helpers :as h]
   [app.common.render-wasm.wasm :as wasm]
   [app.common.types.text :as txt]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.fonts :as fonts]
   [app.main.store :as st]
   [app.util.http :as http]
   [app.util.timers :as tm]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [goog.object :as gobj]
   [lambdaisland.uri :as u]
   [okulary.core :as l]))

;; Custom fonts uploaded to the current team, keyed by id (`fonts` is taken by
;; the `app.main.fonts` alias).
(def ^:private custom-fonts
  (l/derived :fonts st/state))

;; Emits every font face that WASM can measure.
(defonce font-stored-stream (rx/subject))

;; Emits failed font faces so layout can fall back.
(defonce font-storage-failed-stream (rx/subject))

;; Stores faces that currently use WASM fallbacks.
(defonce ^:private failed-font-data-keys (atom #{}))

(defn font-data-key
  "Returns the identity WASM uses to distinguish stored faces in one family."
  [font-data]
  (select-keys font-data [:font-id :weight :style :emoji?]))

(defn- clear-font-storage-failure!
  [font-data]
  (swap! failed-font-data-keys disj (font-data-key font-data)))

(defn- report-font-storage-failed!
  [font-data]
  (let [key (font-data-key font-data)]
    (swap! failed-font-data-keys conj key)
    (rx/push! font-storage-failed-stream key)))

(def ^:private default-font-size 14)
(def ^:private default-line-height 1.2)
(def ^:private default-letter-spacing 0.0)

(defn- font-db-data
  [font-id font-variant-id font-weight-fallback font-style-fallback]
  (let [font (fonts/get-font-data font-id)
        closest-variant (fonts/find-closest-variant font font-weight-fallback font-style-fallback)
        variant (fonts/get-variant font font-variant-id)]
    (if (or (nil? closest-variant) (= closest-variant variant))
      variant
      closest-variant)))

(defn uuid->font-id
  [font-uuid]
  (if (= font-uuid uuid/zero)
    "sourcesanspro"
    (or (:id (fonts/find-font-data {:uuid font-uuid}))
        (dm/str "custom-" font-uuid))))

(defn uuid->font-variant-id
  [font-id font-variant-uuid]
  (if (= font-variant-uuid uuid/zero)
    "regular"
    (or (:id (d/seek #(= (:uuid %) font-variant-uuid)
                     (:variants (fonts/get-font-data font-id))))
        "regular")))

(defn ^:private font-id->asset-id [font-id font-variant-id font-weight font-style]
  (case (cfnt/font-id->backend font-id)
    :google
    font-id
    :custom
    (let [font-uuid (cfnt/font-id->uuid font-id)
          matching-font (some (fn [[_ font]]
                                (and (= (:font-id font) font-uuid)
                                     (= (str (:font-weight font)) (str font-weight))
                                     (= (:font-style font) font-style)
                                     font))
                              (seq @custom-fonts))]
      (when matching-font
        (:ttf-file-id matching-font)))
    :builtin
    (let [variant (font-db-data font-id font-variant-id font-weight font-style)]
      (:ttf-url variant))))

(defn update-text-layout
  [id]
  (when (wasm/live?)
    (let [shape-id-buffer (uuid/get-u32 id)]
      (h/call wasm/internal-module "_update_shape_text_layout_for"
              (aget shape-id-buffer 0)
              (aget shape-id-buffer 1)
              (aget shape-id-buffer 2)
              (aget shape-id-buffer 3)))))

(defn force-update-text-layout
  [id]
  (when (wasm/live?)
    (let [shape-id-buffer (uuid/get-u32 id)]
      (h/call wasm/internal-module "_force_update_shape_text_layout_for"
              (aget shape-id-buffer 0)
              (aget shape-id-buffer 1)
              (aget shape-id-buffer 2)
              (aget shape-id-buffer 3)))))

;; IMPORTANT: Only TTF fonts can be stored.
(defn- store-font-url
  [font-data font-url]
  (when (and (wasm/live?) (some? font-url) (not (str/blank? font-url)))
    (let [font-id-buffer (:family-id-buffer font-data)
          encoder (js/TextEncoder.)
          encoded (.encode encoder font-url)
          size (.-byteLength encoded)
          ptr  (h/call wasm/internal-module "_alloc_bytes" size)
          heap (gobj/get ^js wasm/internal-module "HEAPU8")
          mem  (js/Uint8Array. (.-buffer heap) ptr size)]
      (.set mem encoded)
      (h/call wasm/internal-module "_store_font_url"
              (aget font-id-buffer 0)
              (aget font-id-buffer 1)
              (aget font-id-buffer 2)
              (aget font-id-buffer 3)
              (:weight font-data)
              (:style font-data))
      true)))

(defn- store-font-buffer
  [font-data font-array-buffer font-url emoji? fallback?]
  (when (wasm/live?)
    (let [font-id-buffer  (:family-id-buffer font-data)
          size (.-byteLength font-array-buffer)
          ptr  (h/call wasm/internal-module "_alloc_bytes" size)
          heap (gobj/get ^js wasm/internal-module "HEAPU8")
          mem  (js/Uint8Array. (.-buffer heap) ptr size)]

      (.set mem (js/Uint8Array. font-array-buffer))
      (h/call wasm/internal-module "_store_font"
              (aget font-id-buffer 0)
              (aget font-id-buffer 1)
              (aget font-id-buffer 2)
              (aget font-id-buffer 3)
              (:weight font-data)
              (:style font-data)
              emoji?
              fallback?)
      (store-font-url font-data font-url)
      (clear-font-storage-failure! font-data)
      ;; Reported after the store call: subscribers react by measuring text.
      (rx/push! font-stored-stream (font-data-key font-data))
      true)))

;; Tracks every font face waiting on each shared request.
(def fetching (atom {}))

(defn- register-font-fetch!
  [font-url font-data emoji? fallback?]
  (let [key (font-data-key font-data)]
    (clear-font-storage-failure! font-data)
    (swap! fetching
           update-in
           [font-url key]
           (fn [request]
             {:font-data font-data
              :emoji? emoji?
              :fallback? (or fallback? (:fallback? request))
              :font-url font-url}))))

(defn- take-font-fetches!
  [font-url]
  (let [requests (vals (get @fetching font-url))]
    (swap! fetching dissoc font-url)
    requests))

(defn- fail-font-fetches!
  [font-url cause]
  (let [requests (take-font-fetches! font-url)]
    (log/error :hint "Could not fetch font"
               :font-url font-url
               :cause cause)
    (doseq [{:keys [font-data]} requests]
      (report-font-storage-failed! font-data))))

(defn- store-font-fetch!
  [body {:keys [font-data emoji? fallback? font-url]}]
  (try
    (let [stored? (store-font-buffer font-data body font-url emoji? fallback?)]
      (when-not stored?
        (report-font-storage-failed! font-data))
      stored?)
    (catch :default cause
      (log/error :hint "Could not store font"
                 :font-id (:font-id font-data)
                 :cause cause)
      (report-font-storage-failed! font-data)
      false)))

(defn- fetch-font
  [font-data font-url emoji? fallback?]
  (cond
    (nil? font-url)
    ;; Fail missing font assets without sharing a nil request.
    (do
      (clear-font-storage-failure! font-data)
      (tm/schedule #(report-font-storage-failed! font-data))
      nil)

    (contains? @fetching font-url)
    (do
      (register-font-fetch! font-url font-data emoji? fallback?)
      nil)

    :else
    (do
      (register-font-fetch! font-url font-data emoji? fallback?)
      {:key font-url
       :callback
       (fn []
         (try
           (->> (http/send! {:method :get
                             :uri font-url
                             :response-type :buffer})
                (rx/map
                 (fn [{:keys [body]}]
                   (let [requests (take-font-fetches! font-url)]
                     (mapv (partial store-font-fetch! body) requests))))
                (rx/catch
                 (fn [cause]
                   (fail-font-fetches! font-url cause)
                   (rx/empty))))
           (catch :default cause
             (fail-font-fetches! font-url cause)
             (rx/empty))))})))

(defn- google-font-ttf-url
  [font-id font-variant-id font-weight font-style]
  (let [variant (font-db-data font-id font-variant-id font-weight font-style)]
    (when-let [ttf-url (:ttf-url variant)]
      (cfnt/gstatic->proxy-url ttf-url (u/join cf/public-uri "internal/gfonts/font")))))

(defn- font-id->ttf-url
  [font-id asset-id font-variant-id font-weight font-style]
  (case (cfnt/font-id->backend font-id)
    :google
    (google-font-ttf-url font-id font-variant-id font-weight font-style)
    :custom
    (dm/str (u/join cf/public-uri "assets/by-id/" asset-id))
    :builtin
    (dm/str (u/join cf/public-uri "fonts/" asset-id))))

(defn font-stored?
  [font-data emoji?]
  (when-let [id-buffer (uuid/get-u32 (:wasm-id font-data))]
    (not= 0 (h/call wasm/internal-module "_is_font_uploaded"
                    (aget id-buffer 0)
                    (aget id-buffer 1)
                    (aget id-buffer 2)
                    (aget id-buffer 3)
                    (:weight font-data)
                    (:style font-data)
                    emoji?))))

(defn font-ready?
  "Returns true when WASM can lay out with the requested face or its fallback."
  [font-data]
  (or (contains? @failed-font-data-keys (font-data-key font-data))
      (font-stored? font-data (:emoji? font-data))))

(defn- store-font-id
  [font-data asset-id emoji? fallback?]
  (if asset-id
    (let [uri (font-id->ttf-url
               (:font-id font-data) asset-id
               (:font-variant-id font-data)
               (:weight font-data)
               (:style-name font-data))
          id-buffer (uuid/get-u32 (:wasm-id font-data))
          font-data (assoc font-data :family-id-buffer id-buffer)
          font-stored? (font-stored? font-data emoji?)]
      (if font-stored?
        ;; Deferred so consumers, which subscribe after dispatching the sync
        ;; that lands here, are listening when an already-stored font reports.
        (do
          (store-font-url font-data uri)
          (clear-font-storage-failure! font-data)
          (tm/schedule #(rx/push! font-stored-stream (font-data-key font-data))))
        (fetch-font font-data uri emoji? fallback?)))
    ;; Report missing font assets asynchronously.
    (do
      (clear-font-storage-failure! font-data)
      (tm/schedule
       #(report-font-storage-failed! font-data))
      nil)))

(defn serialize-font-style
  [font-style]
  (case font-style
    "normal" 0
    "regular" 0
    "italic" 1
    0))

(defn normalize-span-font
  [span paragraph]
  (let [font-id (:font-id span)
        font-variant-id (:font-variant-id span)
        font-weight-fallback (or (:font-weight span) (:font-weight paragraph))
        font-style-fallback (or (:font-style span) (:font-style paragraph))
        font-data (font-db-data font-id font-variant-id font-weight-fallback font-style-fallback)]
    (-> span
        (assoc :font-variant-id (or (:id font-data) (:id font-data) font-variant-id)
               :font-weight (or (:weight font-data) font-weight-fallback)
               :font-style (or (:style font-data) font-style-fallback)))))

(defn normalize-paragraph-font
  [paragraph]
  (let [font-id (:font-id paragraph)
        font-variant-id (:font-variant-id paragraph)
        font-weight-fallback (:font-weight paragraph)
        font-style-fallback (:font-style paragraph)
        font-data (font-db-data font-id font-variant-id font-weight-fallback font-style-fallback)]
    (-> paragraph
        (assoc :font-variant-id (or (:id font-data) (:id font-data) font-variant-id)
               :font-weight (or (:weight font-data) font-weight-fallback)
               :font-style (or (:style font-data) font-style-fallback)))))

(defn serialize-font-size
  [font-size]
  (cond
    (number? font-size)
    font-size

    (string? font-size)
    (or (d/parse-double font-size) default-font-size)))

(defn serialize-font-weight
  [font-weight]
  (if (number? font-weight)
    font-weight
    (let [font-weight-str (str font-weight)]
      (cond
        (re-matches #"\d+" font-weight-str)
        (js/Number font-weight-str)

        (str/includes? font-weight-str "bold")
        700
        (str/includes? font-weight-str "black")
        900
        (str/includes? font-weight-str "extrabold")
        800
        (str/includes? font-weight-str "extralight")
        200
        (str/includes? font-weight-str "light")
        300
        (str/includes? font-weight-str "medium")
        500
        (str/includes? font-weight-str "semibold")
        600
        (str/includes? font-weight-str "thin")
        100
        :else
        400))))

(defn serialize-line-height
  ([line-height]
   (serialize-line-height line-height default-line-height))
  ([line-height default-value]
   (cond
     (number? line-height)
     line-height

     (string? line-height)
     (or (d/parse-double line-height) default-value))))

(defn serialize-letter-spacing
  [letter-spacing]
  (cond
    (number? letter-spacing)
    letter-spacing

    (string? letter-spacing)
    (or (d/parse-double letter-spacing) default-letter-spacing)))


(defn normalize-font-variant
  [font-variant-id]
  (if (or (nil? font-variant-id) (str/blank? font-variant-id))
    "regular"
    font-variant-id))

(defn make-font-data
  [font]
  (let [font-id (get font :font-id)
        font-variant-id (get font :font-variant-id)
        normalized-variant-id (when font-variant-id
                                (-> font-variant-id
                                    (str/lower)
                                    (str/replace #"\s+" "")))
        font-weight-fallback (or (get font :font-weight) 400)
        font-style-fallback (or (get font :font-style) "normal")
        emoji? (get font :is-emoji false)
        fallback? (get font :is-fallback false)
        font-data (font-db-data font-id normalized-variant-id font-weight-fallback font-style-fallback)
        wasm-id (cfnt/font-id->uuid font-id)
        raw-weight (or (:weight font-data) font-weight-fallback)
        weight (serialize-font-weight raw-weight)
        style (cond
                (str/includes? (or normalized-variant-id "") "italic") "italic"
                (str/includes? raw-weight "italic") "italic"
                :else font-style-fallback)
        variant-id (or (:id font-data) normalized-variant-id)
        asset-id (font-id->asset-id font-id variant-id raw-weight style)]
    {:wasm-id wasm-id
     :font-id font-id
     :font-variant-id variant-id
     :style (serialize-font-style style)
     :style-name style
     :weight weight
     :emoji? emoji?
     :fallback? fallback?
     :asset-id asset-id}))

(defn store-font
  [font]
  (let [{:keys [asset-id emoji? fallback?] :as font-data} (make-font-data font)]
    (store-font-id font-data asset-id emoji? fallback?)))

;; FIXME: This is a temporary function to load the fallback fonts for the editor.
;; Once we render the editor content within wasm, we can remove this function.
(defn load-fallback-fonts-for-editor!
  [fonts]
  (doseq [font fonts]
    (fonts/ensure-loaded! (:font-id font) (:font-variant-id font))))


(defn get-content-fonts
  "Extends from app.main.fonts/get-content-fonts. Extracts the fonts used by the content of a text shape, resolving the correct font variant info."
  [content]
  (let [paragraph-set (first (get content :children))
        paragraphs (get paragraph-set :children)]
    (->> paragraphs
         (mapcat #(get % :children))
         (filter txt/is-text-node?)
         (reduce
          (fn [result {:keys [font-id font-variant-id font-weight font-style] :as node}]
            (let [resolved-font-id (or font-id (:font-id txt/default-typography))
                  resolved-variant-id (or font-variant-id (:font-variant-id txt/default-typography))
                  font-weight-fallback (or font-weight (:font-weight txt/default-typography) 400)
                  font-style-fallback (or font-style (:font-style txt/default-typography) "normal")
                  font-data (font-db-data resolved-font-id resolved-variant-id font-weight-fallback font-style-fallback)
                  font-ref {:font-id resolved-font-id
                            :font-variant-id (or (:id font-data) (:name font-data) resolved-variant-id)
                            :font-weight (or (:weight font-data) font-weight-fallback)
                            :font-style (or (:style font-data) font-style-fallback)}]
              (conj result font-ref)))
          #{}))))

(defn store-fonts
  [fonts]
  (keep (fn [font] (store-font font)) fonts))
