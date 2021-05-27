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
   [cuerdas.core :as str])
  (:import
   javax.xml.XMLConstants
   javax.xml.parsers.SAXParserFactory
   org.apache.commons.io.IOUtils))

(defn- secure-parser-factory
  [s ch]
  (.. (doto (SAXParserFactory/newInstance)
        (.setFeature XMLConstants/FEATURE_SECURE_PROCESSING true)
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
                :hint "invalid svg file"
                :cause e))))

(declare pre-process)

(s/def ::data ::us/string)
(s/def ::parsed-svg (s/keys :req-un [::data]))

(sv/defmethod ::parsed-svg
  [_ {:keys [data] :as params}]
  (->> data pre-process parse))

;; --- PROCESSORS

(defn strip-doctype
  [data]
  (cond-> data
    (str/includes? data "<!DOCTYPE")
    (str/replace #"<\!DOCTYPE[^>]*>" "")))

(def pre-process strip-doctype)
