;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.response
  "A lightweigt reponse type definition.

  At first instance it allows set the appropriate
  content-type headers and encode the body using
  the builtin transit abstraction.

  In future it will allow easy adapt for the content
  negotiation that is coming to catacumba."
  (:require [catacumba.impl.handlers :as ch]
            [catacumba.impl.context :as ctx]
            [buddy.core.hash :as hash]
            [buddy.core.codecs :as codecs]
            [buddy.core.codecs.base64 :as b64]
            [uxbox.util.transit :as t])
  (:import ratpack.handling.Context
           ratpack.http.Response
           ratpack.http.Request
           ratpack.http.Headers
           ratpack.http.MutableHeaders))

(defn digest
  [^bytes data]
  (-> (hash/blake2b-512 data)
      (b64/encode true)
      (codecs/bytes->str)))

(defn- etag-match?
  [^Request request ^String new-tag]
  (let [^Headers headers (.getHeaders request)]
    (when-let [etag (.get headers "if-none-match")]
      (= etag new-tag))))

(deftype Rsp [data]
  clojure.lang.IDeref
  (deref [_] data)

  ch/ISend
  (-send [_ ctx]
    (let [^Response response (ctx/get-response* ctx)
          ^Request  request (ctx/get-request* ctx)
          ^MutableHeaders headers (.getHeaders response)
          ^String method (.. request getMethod getName toLowerCase)
          data (t/encode data)]
      (if (= method "get")
        (let [etag (digest data)]
          (if (etag-match? request etag)
            (do
              (.set headers "etag" etag)
              (.status response 304)
              (.send response))
            (do
              (.set headers "content-type" "application/transit+json")
              (.set headers "etag" etag)
              (ch/-send data ctx))))
        (do
          (.set headers "content-type" "application/transit+json")
          (ch/-send data ctx))))))

(defn rsp
  "A shortcut for create a response instance."
  [data]
  (Rsp. data))

(defn rsp?
  [v]
  (instance? Rsp v))
