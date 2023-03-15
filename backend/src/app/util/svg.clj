;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.svg
  (:require
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [clojure.xml :as xml]
   [cuerdas.core :as str])
  (:import
   javax.xml.XMLConstants
   java.io.InputStream
   javax.xml.parsers.SAXParserFactory
   clojure.lang.XMLHandler
   org.apache.commons.io.IOUtils))

(defn- secure-parser-factory
  [^InputStream input ^XMLHandler handler]
  (.. (doto (SAXParserFactory/newInstance)
        (.setFeature XMLConstants/FEATURE_SECURE_PROCESSING true)
        (.setFeature "http://apache.org/xml/features/disallow-doctype-decl" true))
      (newSAXParser)
      (parse input handler)))

(defn parse
  [^String data]
  (try
    (dm/with-open [istream (IOUtils/toInputStream data "UTF-8")]
      (xml/parse istream secure-parser-factory))
    (catch Exception e
      (l/warn :hint "error on processing svg"
              :message (ex-message e))
      (ex/raise :type :validation
                :code :invalid-svg-file
                :hint "invalid svg file"
                :cause e))))


;; --- PROCESSORS

(defn strip-doctype
  [data]
  (cond-> data
    (str/includes? data "<!DOCTYPE")
    (str/replace #"<\!DOCTYPE[^>]*>" "")))

(def pre-process strip-doctype)
