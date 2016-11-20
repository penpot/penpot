;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.frontend.kvstore
  (:refer-clojure :exclude [update])
  (:require [clojure.spec :as s]
            [promesa.core :as p]
            [catacumba.http :as http]
            [uxbox.media :as media]
            [uxbox.util.spec :as us]
            [uxbox.services :as sv]
            [uxbox.util.response :refer (rsp)]
            [uxbox.util.uuid :as uuid]))

(s/def ::version integer?)
(s/def ::key string?)
(s/def ::value any?)

;; --- Retrieve

(s/def ::retrieve (s/keys :req-un [::key]))

(defn retrieve
  [{user :identity params :route-params}]
  (let [data (us/conform ::retrieve params)
        params (assoc data
                      :type :retrieve-kvstore
                      :user user)]
    (->> (sv/query params)
         (p/map #(http/ok (rsp %))))))

;; --- Update (or Create)

(s/def ::update (s/keys :req-un [::key ::value]
                        :opt-un [::version]))

(defn update
  [{user :identity data :data}]
  (let [data (us/conform ::update data)
        params (assoc data
                      :type :update-kvstore
                      :user user)]
    (->> (sv/novelty params)
         (p/map #(http/ok (rsp %))))))

;; --- Delete

(s/def ::delete (s/keys :req-un [::key]))

(defn delete
  [{user :identity params :route-params}]
  (let [data (us/conform ::delete params)
        params (assoc data
                      :type :delete-kvstore
                      :user user)]
    (->> (sv/novelty params)
         (p/map (fn [_] (http/no-content))))))
