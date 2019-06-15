;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.api.kvstore
  (:refer-clojure :exclude [update])
  (:require [struct.core :as st]
            [promesa.core :as p]
            [uxbox.services :as sv]
            [uxbox.media :as media]
            [uxbox.util.http :as http]
            [uxbox.util.spec :as us]
            [uxbox.util.uuid :as uuid]))

(defn retrieve
  {:parameters {:path {:key [st/required st/string]}}}
  [{:keys [user parameters] }]
  (let [key (get-in parameters [:path :key])
        message {:key key
                 :type :retrieve-kvstore
                 :user user}]
    (->> (sv/query message)
         (p/map http/ok))))

(defn upsert
  {:parameters {:path {:key [st/required st/string]}
                :body {:value [st/required]
                       :version [st/number]}}}
  [{:keys [user parameters]}]
  (let [value (get-in parameters [:body :value])
        key (get-in parameters [:path :key])
        version (get-in parameters [:body :version])
        message {:key key
                 :version version
                 :value value
                 :type :update-kvstore
                 :user user}]
    (->> (sv/novelty message)
         (p/map http/ok))))

(defn delete
  {:parameters {:path {:key [st/required st/string]}}}
  [{:keys [user parameters]}]
  (let [key (get-in parameters [:path :key])
        message {:key key
                 :type :delete-kvstore
                 :user user}]
    (->> (sv/novelty message)
         (p/map (constantly (http/no-content))))))


