;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.exports.wasm
  (:require
   [app.common.media :refer [format->mtype]]
   [app.render-wasm.api :as wasm.api]
   [app.util.dom :as dom]
   [app.util.webapi :as wapi]))

(defn export-image-uri
  [{:keys [type scale object-id]}]
  (let [bytes (wasm.api/render-shape-pixels object-id scale)
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
  (let [bytes (wasm.api/render-shape-pdf object-id (or scale 1))
        blob (wapi/create-blob bytes "application/pdf")]
    (wapi/create-uri blob)))

(defn export-pdf
  [{:keys [suffix name] :as params}]
  (let [url (export-pdf-uri params)
        filename (str name (or suffix "") ".pdf")]
    (dom/trigger-download-uri filename "application/pdf" url)
    (wapi/revoke-uri url)
    nil))

