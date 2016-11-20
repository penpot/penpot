;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.services.kvstore
  (:require [clojure.spec :as s]
            [suricatta.core :as sc]
            [buddy.core.codecs :as codecs]
            [uxbox.config :as ucfg]
            [uxbox.sql :as sql]
            [uxbox.db :as db]
            [uxbox.util.spec :as us]
            [uxbox.services.core :as core]
            [uxbox.util.time :as dt]
            [uxbox.util.data :as data]
            [uxbox.util.transit :as t]
            [uxbox.util.blob :as blob]
            [uxbox.util.uuid :as uuid]))

(s/def ::version integer?)
(s/def ::key string?)
(s/def ::value any?)
(s/def ::user uuid?)

(defn decode-value
  [{:keys [value] :as data}]
  (if value
    (assoc data :value (-> value blob/decode t/decode))
    data))

;; --- Update KVStore

(s/def ::update-kvstore
  (s/keys :req-un [::key ::value ::user ::version]))

(defn update-kvstore
  [conn {:keys [user key value version] :as data}]
  (let [opts {:user user
              :key key
              :version version
              :value (-> value t/encode blob/encode)}
        sqlv (sql/update-kvstore opts)]
    (some->> (sc/fetch-one conn sqlv)
             (data/normalize-attrs)
             (decode-value))))

(defmethod core/novelty :update-kvstore
  [params]
  (s/assert ::update-kvstore params)
  (with-open [conn (db/connection)]
    (sc/apply-atomic conn update-kvstore params)))

;; --- Retrieve KVStore

(s/def ::retrieve-kvstore
  (s/keys :req-un [::key ::user]))

(defn retrieve-kvstore
  [conn {:keys [user key] :as params}]
  (let [sqlv (sql/retrieve-kvstore params)]
    (some->> (sc/fetch-one conn sqlv)
             (data/normalize-attrs)
             (decode-value))))

(defmethod core/query :retrieve-kvstore
  [params]
  (s/assert ::retrieve-kvstore params)
  (with-open [conn (db/connection)]
    (retrieve-kvstore conn params)))

;; --- Delete KVStore

(s/def ::delete-kvstore
  (s/keys :req-un [::key ::user]))

(defn delete-kvstore
  [conn {:keys [user key] :as params}]
  (let [sqlv (sql/delete-kvstore params)]
    (pos? (sc/execute conn sqlv))))

(defmethod core/novelty :delete-kvstore
  [params]
  (s/assert ::delete-kvstore params)
  (with-open [conn (db/connection)]
    (sc/apply-atomic conn delete-kvstore params)))
