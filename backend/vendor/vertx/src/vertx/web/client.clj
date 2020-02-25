;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns vertx.web.client
  "High level http client."
  (:refer-clojure :exclude [get])
  (:require
   [clojure.spec.alpha :as s]
   [promesa.core :as p]
   [reitit.core :as rt]
   [vertx.http :as http]
   [vertx.impl :as impl])
  (:import
   clojure.lang.IPersistentMap
   clojure.lang.Keyword
   io.vertx.core.Future
   io.vertx.core.Handler
   io.vertx.core.Vertx
   io.vertx.core.buffer.Buffer
   io.vertx.core.http.HttpMethod
   io.vertx.ext.web.client.HttpRequest
   io.vertx.ext.web.client.HttpResponse
   io.vertx.ext.web.client.WebClientSession
   io.vertx.ext.web.client.WebClient))

;; TODO: accept options

(defn create
  ([vsm] (create vsm {}))
  ([vsm opts]
   (let [^Vertx system (impl/resolve-system vsm)]
     (WebClient/create system))))

(defn session
  [client]
  (WebClientSession/create client))

(defn get
  ([session url] (get session url {}))
  ([session url opts]
   (let [^HttpRequest req (.getAbs ^WebClientSession session url)
         d (p/deferred)]
     (.send req (impl/deferred->handler d))
     (p/then d (fn [^HttpResponse res]
                 {:body (.bodyAsBuffer res)
                  :status (.statusCode res)
                  :headers (http/->headers (.headers res))})))))

