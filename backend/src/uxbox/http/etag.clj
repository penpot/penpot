;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.http.etag
  "ETag calculation helpers."
  (:require [clojure.java.io :as io]
            [buddy.core.hash :as hash]
            [buddy.core.codecs :as codecs]
            [buddy.core.codecs.base64 :as b64]))

(defn digest
  [^bytes data]
  (-> (hash/blake2b-256 data)
      (b64/encode true)
      (codecs/bytes->str)))

(defn- etag-match?
  [request new-tag]
  (when-let [etag (get-in request [:headers "if-none-match"])]
    (= etag new-tag)))

(defn stream-bytes
  [is]
  (let [baos (java.io.ByteArrayOutputStream.)]
    (io/copy is baos)
    (.toByteArray baos)))

(defn handle-response
  [request {:keys [body] :as response}]
  (when (instance? java.io.ByteArrayInputStream body)
    (let [data (stream-bytes body)
          etag (digest data)]
      (.reset body)
      (if-not (etag-match? request etag)
        (update response :headers assoc "etag" etag)
        (-> response
            (assoc :body "" :status 304)
            (update :headers assoc "etag" etag))))))

(defn wrap-etag
  [handler]
  (fn [request respond raise]
    (handler request
             (fn [response]
               (if (= (:request-method request) :get)
                 (respond (or (handle-response request response) response))
                 (respond response)))
             raise)))


