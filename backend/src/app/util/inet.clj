;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.inet
  "INET addr parsing and validation helpers"
  (:require
   [cuerdas.core :as str]
   [ring.request :as rreq])
  (:import
   com.google.common.net.InetAddresses
   java.net.InetAddress))

(defn valid?
  [s]
  (InetAddresses/isInetAddress s))

(defn normalize
  [s]
  (try
    (let [addr (InetAddresses/forString s)]
      (.getHostAddress ^InetAddress addr))
    (catch Throwable _cause
      nil)))

(defn parse-request
  [request]
  (or (some-> (rreq/get-header request "x-real-ip")
              (normalize))
      (some-> (rreq/get-header request "x-forwarded-for")
              (str/split #"\s*,\s*")
              (first)
              (normalize))
      (some-> (rreq/remote-addr request)
              (normalize))))
