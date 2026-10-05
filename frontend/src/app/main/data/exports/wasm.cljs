;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.exports.wasm
  (:require
   [app.common.media :refer [format->mtype]]
   [app.common.render-wasm.api.props :as props]
   [app.common.render-wasm.api.select :as wselect]
   [app.main.refs :as refs]
   [app.render-wasm.api :as wasm.api]
   [app.util.dom :as dom]
   [app.util.webapi :as wapi]))

(defn- render-export
  "Hide the exported root's fill only for the synchronous render. Restore it
  even when encoding fails, without changing the document or its undo history."
  [object-id render-fn]
  (let [shape (get @refs/workspace-page-objects object-id)]
    (if (and (:hide-fill-on-export shape) (wasm.api/initialized?))
      (try
        (wselect/use-shape object-id)
        (props/write-shape-fills! [])
        (render-fn)
        (finally
          (wselect/use-shape object-id)
          (props/write-shape-fills! (:fills shape))))
      (render-fn))))

(defn export-image-uri
  [{:keys [type scale object-id]}]
  ;; The export type doubles as the encoder format, so the bytes always match
  ;; the mtype we wrap them in. `:svg` never reaches here — it needs vector
  ;; markup and `exports.assets/wasm-export-types` keeps it off this path.
  (let [bytes (render-export object-id #(wasm.api/render-shape-pixels object-id scale type))
        mtype (format->mtype type)
        blob (wapi/create-blob bytes mtype)]
    (wapi/create-uri blob)))

(defn export-image
  [{:keys [type suffix name] :as params}]
  (let [url (export-image-uri params)
        mtype (format->mtype type)
        filename (str name (or suffix ""))]
    (dom/trigger-download-uri filename mtype url)
    (wapi/revoke-uri url)
    nil))

(defn export-pdf-uri
  [{:keys [scale object-id]}]
  (let [bytes (render-export object-id #(wasm.api/render-shape-pdf object-id (or scale 1)))
        blob (wapi/create-blob bytes "application/pdf")]
    (wapi/create-uri blob)))

(defn export-pdf
  [{:keys [suffix name] :as params}]
  (let [url (export-pdf-uri params)
        filename (str name (or suffix "") ".pdf")]
    (dom/trigger-download-uri filename "application/pdf" url)
    (js/queueMicrotask #(wapi/revoke-uri url))
    nil))

(defn export-svg-uri
  [{:keys [scale object-id]}]
  (let [bytes (render-export object-id #(wasm.api/render-shape-svg object-id (or scale 1)))
        blob (wapi/create-blob bytes "image/svg+xml")]
    (wapi/create-uri blob)))

(defn export-svg
  [{:keys [suffix name] :as params}]
  (let [url (export-svg-uri params)
        filename (str name (or suffix "") ".svg")]
    (dom/trigger-download-uri filename "image/svg+xml" url)
    (js/queueMicrotask #(wapi/revoke-uri url))
    nil))
