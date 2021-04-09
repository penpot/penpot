;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.svgparse
  (:require
   [app.common.exceptions :as ex]
   [app.metrics :as mtx]
   [app.util.logging :as l]
   [clojure.spec.alpha :as s]
   [clojure.xml :as xml]
   [integrant.core :as ig])
  (:import
   org.apache.commons.io.IOUtils))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Handler
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare handler)
(declare process-request)

(defmethod ig/pre-init-spec ::handler [_]
  (s/keys :req-un [::mtx/metrics]))

(defmethod ig/init-key ::handler
  [_ {:keys [metrics] :as cfg}]
  (let [handler #(handler cfg %)]
    (->> {:registry (:registry metrics)
          :type :summary
          :name "http_handler_svgparse_timing"
          :help "svg parse timings"}
         (mtx/instrument handler))))

(defn- handler
  [_ {:keys [headers body] :as request}]
  (when (not= "image/svg+xml" (get headers "content-type"))
    (ex/raise :type :validation
              :code :unsupported-mime-type
              :mime (get headers "content-type")))
  {:status 200
   :body (process-request body)})

(defn secure-factory
  [s ch]
  (.. (doto (javax.xml.parsers.SAXParserFactory/newInstance)
        (.setFeature javax.xml.XMLConstants/FEATURE_SECURE_PROCESSING true)
        (.setFeature "http://apache.org/xml/features/disallow-doctype-decl" true))
      (newSAXParser)
      (parse s ch)))

(defn parse
  [data]
  (try
    (with-open [istream (IOUtils/toInputStream data "UTF-8")]
      (xml/parse istream secure-factory))
    (catch Exception e
      (l/warn :hint "error on processing svg"
              :message (ex-message e))
      (ex/raise :type :validation
                :code :invalid-svg-file
                :cause e))))

(defn process-request
  [body]
  (let [data (slurp body)]
    (parse data)))

