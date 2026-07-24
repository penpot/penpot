;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.media
  "Media & Font postprocessing.

  This namespace is the dispatch layer only. Processing implementations
  live in two separate namespaces, each owning their own defmulti:

    app.media.local  — shell/ImageMagick/FontForge implementations
    app.media.remote — HTTP delegation to media-processor service

  Validation and schemas live in app.media.validation (leaf namespace,
  no circular dep). When adding a new :cmd type, add defmethods in
  BOTH local and remote."
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.config :as cf]
   [app.db :as-alias db]
   [app.http.client :as http]
   [app.media.local :as media.local]
   [app.media.remote :as media.remote]
   [app.media.sanitize :as sanitize]
   [app.media.validation :as validation]
   [app.storage :as-alias sto]
   [app.storage.tmp :as tmp]
   [cuerdas.core :as str]
   [datoteka.io :as io]))

(defn run
  [system params]
  (if (contains? cf/flags :remote-media-processing)
    (media.remote/process system params)
    (media.local/process system params)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; IMAGE HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn download-image
  "Download an image from the provided URI and return the media input object"
  [{:keys [::http/client]} uri]
  (letfn [(parse-and-validate [{:keys [status headers] :as response}]
            (let [size     (some-> (get headers "content-length") d/parse-integer)
                  mtype    (get headers "content-type")]

              (when-not (<= 200 status 299)
                (ex/raise :type :validation
                          :code :unable-to-download-image
                          :hint (str/ffmt "unable to download image from '%': unexpected status code %" uri status)))

              (when-not size
                (ex/raise :type :validation
                          :code :unknown-size
                          :hint "seems like the url points to resource with unknown size"))

              (-> {:size size :mtype mtype}
                  (validation/validate-media-type!)
                  (validation/validate-media-size!))))]

    (let [{:keys [body] :as response}
          (try
            (http/req-with-redirects
             client
             {:method :get :uri uri}
             {:response-type :input-stream
              :max-redirects 3})
            (catch java.net.ConnectException cause
              (ex/raise :type :validation
                        :code :unable-to-download-image
                        :hint (str/ffmt "unable to download image from '%': connection refused or host unreachable" uri)
                        :cause cause))
            (catch java.net.http.HttpConnectTimeoutException cause
              (ex/raise :type :validation
                        :code :unable-to-download-image
                        :hint (str/ffmt "unable to download image from '%': connection timeout" uri)
                        :cause cause))
            (catch java.net.http.HttpTimeoutException cause
              (ex/raise :type :validation
                        :code :unable-to-download-image
                        :hint (str/ffmt "unable to download image from '%': request timeout" uri)
                        :cause cause))
            (catch java.io.IOException cause
              (ex/raise :type :validation
                        :code :unable-to-download-image
                        :hint (str/ffmt "unable to download image from '%': I/O error" uri)
                        :cause cause)))

          {:keys [size mtype]} (parse-and-validate response)
          path    (tmp/tempfile :prefix "penpot.media.download.")
          written (io/write* path body :size size)]

      (when (not= written size)
        (ex/raise :type :internal
                  :code :mismatch-write-size
                  :hint "unexpected state: unable to write to file"))

      ;; Sanitize: strip trailing data after image EOF markers
      (let [new-size (sanitize/truncate-after-eof path mtype)]
        {:path  path
         :mtype mtype
         :size  new-size}))))
