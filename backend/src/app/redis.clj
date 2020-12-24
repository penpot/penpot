;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns app.redis
  (:refer-clojure :exclude [run!])
  (:require
   [app.common.spec :as us]
   [app.config :as cfg]
   [app.util.redis :as redis]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig])
  (:import
   java.lang.AutoCloseable))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; State
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod ig/pre-init-spec ::redis [_]
  (s/keys :req-un [::uri]))

(defmethod ig/init-key ::redis
  [_ cfg]
  (let [client (redis/client (:uri cfg "redis://redis/0"))
        conn   (redis/connect client)]
    {::client client
     ::conn conn}))

(defmethod ig/halt-key! ::redis
  [_ {:keys [::client ::conn]}]
  (.close ^AutoCloseable conn)
  (.close ^AutoCloseable client))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::client some?)
(s/def ::conn some?)
(s/def ::redis (s/keys :req [::client ::conn]))

(defn subscribe
  [client opts]
  (us/assert ::redis client)
  (redis/subscribe (::client client) opts))

(defn run!
  [client cmd params]
  (us/assert ::redis client)
  (redis/run! (::conn client) cmd params))

(defn run
  [client cmd params]
  (us/assert ::redis client)
  (redis/run (::conn client) cmd params))

