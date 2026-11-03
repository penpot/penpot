;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.exports.wasm
  (:require
   [app.common.media :refer [format->mtype]]
   [app.render-wasm.api :as wasm.api]
   [app.util.dom :as dom]
   [app.util.webapi :as wapi]
   [cuerdas.core :as str]))

;; Regex shared with the exporter so ZIP entry names stay compatible.
(def ^:private sanitize-file-regex #"[\\/:*?\"<>|]")

(defn export-extension
  [type]
  (case type
    :png  ".png"
    :jpeg ".jpg"
    :webp ".webp"
    :svg  ".svg"
    :pdf  ".pdf"
    :zip  ".zip"
    ""))

(defn sanitize-filename
  "Exporter-compatible filename: strip reserved chars, then name+suffix+ext
  with numeric de-dupe against `used`."
  [{:keys [name suffix type]} used]
  (let [base (-> (str (or name "") (or suffix ""))
                 (str/replace sanitize-file-regex "_"))]
    (loop [index 0]
      (let [candidate (str base
                           (when (pos? index)
                             (str "-" (inc index)))
                           (export-extension type))]
        (if (contains? used candidate)
          (recur (inc index))
          candidate)))))

(defn export-bytes
  "Encoded bytes for one export entry. Returns nil when WASM is not ready.
  Used by the worker local export path (single / ZIP) and by main-thread
  helpers (plugins, clipboard) that still need a sync URI.

  Raster formats use the GPU surface when it fits; otherwise the CPU
  raster path (same as the headless exporter) so oversized shapes still
  export locally without calling the server API."
  [{:keys [type scale object-id] :as _export}]
  (let [scale (or scale 1)]
    (case type
      :pdf (wasm.api/render-shape-pdf object-id)
      :svg (wasm.api/render-shape-svg object-id scale)
      (if (= :client (wasm.api/export-target object-id scale type))
        (wasm.api/render-shape-pixels object-id scale type)
        (wasm.api/render-shape-raster object-id scale type)))))

(defn export-image-uri
  [{:keys [type] :as params}]
  ;; The export type doubles as the encoder format, so the bytes always match
  ;; the mtype we wrap them in. `:svg` never reaches here — it needs vector
  ;; markup and `exports.assets/wasm-export-types` keeps it off this path.
  (let [bytes (export-bytes params)
        mtype (format->mtype type)
        blob  (wapi/create-blob bytes mtype)]
    (wapi/create-uri blob)))

(defn export-image
  [{:keys [type name] :as params}]
  (let [url   (export-image-uri params)
        mtype (format->mtype type)]
    (dom/trigger-download-uri name mtype url)
    (wapi/revoke-uri url)
    nil))

(defn export-pdf-uri
  [params]
  (let [bytes (export-bytes (assoc params :type :pdf))
        blob  (wapi/create-blob bytes "application/pdf")]
    (wapi/create-uri blob)))

(defn export-pdf
  [{:keys [name] :as params}]
  (let [url      (export-pdf-uri params)
        filename (str name ".pdf")]
    (dom/trigger-download-uri filename "application/pdf" url)
    (js/queueMicrotask #(wapi/revoke-uri url))
    nil))

(defn export-svg-uri
  [params]
  (let [bytes (export-bytes (assoc params :type :svg))
        blob  (wapi/create-blob bytes "image/svg+xml")]
    (wapi/create-uri blob)))

(defn export-svg
  [{:keys [name] :as params}]
  (let [url      (export-svg-uri params)
        filename (str name ".svg")]
    (dom/trigger-download-uri filename "image/svg+xml" url)
    (js/queueMicrotask #(wapi/revoke-uri url))
    nil))

(defn export-frames-pdf-uri
  "One multi-page PDF for the given object ids (page order = id order).
  Synchronous main-thread helper; multi-export prefers the worker session."
  [{:keys [object-ids]}]
  (let [bytes (wasm.api/render-shapes-pdf object-ids)
        blob  (wapi/create-blob bytes "application/pdf")]
    (wapi/create-uri blob)))

(defn export-frames-pdf
  [{:keys [name] :as params}]
  (let [url      (export-frames-pdf-uri params)
        filename (str (or name "export") ".pdf")]
    (dom/trigger-download-uri filename "application/pdf" url)
    (js/queueMicrotask #(wapi/revoke-uri url))
    nil))
