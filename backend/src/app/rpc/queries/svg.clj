;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.rpc.queries.svg
  (:require
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.util.logging :as l]
   [app.util.services :as sv]
   [clojure.spec.alpha :as s]
   [clojure.xml :as xml]
   [integrant.core :as ig])
  (:import
   javax.xml.XMLConstants
   javax.xml.parsers.SAXParserFactory
   org.apache.commons.io.IOUtils))

(defn- secure-parser-factory
  [s ch]
  (.. (doto (SAXParserFactory/newInstance)
        (.setFeature javax.xml.XMLConstants/FEATURE_SECURE_PROCESSING true)
        (.setFeature "http://apache.org/xml/features/disallow-doctype-decl" true))
      (newSAXParser)
      (parse s ch)))

(defn parse
  [data]
  (try
    (with-open [istream (IOUtils/toInputStream data "UTF-8")]
      (xml/parse istream secure-parser-factory))
    (catch Exception e
      (l/warn :hint "error on processing svg"
              :message (ex-message e))
      (ex/raise :type :validation
                :code :invalid-svg-file
                :cause e))))

(s/def ::data ::us/string)
(s/def ::parsed-svg (s/keys :req-un [::data]))

(sv/defmethod ::parsed-svg
  [_ {:keys [data] :as params}]
  (parse data))


