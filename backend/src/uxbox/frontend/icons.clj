;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.frontend.icons
  (:require [clojure.spec.alpha :as s]
            [promesa.core :as p]
            [catacumba.http :as http]
            [uxbox.util.spec :as us]
            [uxbox.services :as sv]
            [uxbox.util.response :refer (rsp)]
            [uxbox.util.uuid :as uuid]))

;; --- Constants & Config

(s/def ::collection (s/nilable ::us/uuid-string))

(s/def ::width (s/and number? pos?))
(s/def ::height (s/and number? pos?))
(s/def ::view-box (s/and (s/coll-of number?)
                         #(= 4 (count %))
                         vector?))

(s/def ::mimetype string?)
(s/def ::metadata
  (s/keys :opt-un [::width ::height ::view-box ::mimetype]))

(s/def ::content string?)

;; --- Create Collection

(s/def ::create-collection
  (s/keys :req-un [::us/name] :opt-un [::us/id]))

(defn create-collection
  [{user :identity data :data}]
  (let [data (us/conform ::create-collection data)
        message (assoc data
                       :type :create-icon-collection
                       :user user)]
    (->> (sv/novelty message)
         (p/map (fn [result]
                  (let [loc (str "/api/library/icons/" (:id result))]
                    (http/created loc (rsp result))))))))

;; --- Update Collection

(s/def ::update-collection
  (s/merge ::create-collection (s/keys :req-un [::us/version])))

(defn update-collection
  [{user :identity params :route-params data :data}]
  (let [data (us/conform ::update-collection data)
        message (assoc data
                       :id (uuid/from-string (:id params))
                       :type :update-icon-collection
                       :user user)]
    (-> (sv/novelty message)
        (p/then #(http/ok (rsp %))))))

;; --- Delete Collection

(defn delete-collection
  [{user :identity params :route-params}]
  (let [message {:id (uuid/from-string (:id params))
                 :type :delete-icon-collection
                 :user user}]
    (-> (sv/novelty message)
        (p/then (fn [v] (http/no-content))))))

;; --- List collections

(defn list-collections
  [{user :identity}]
  (let [params {:user user
                :type :list-icon-collections}]
    (-> (sv/query params)
        (p/then #(http/ok (rsp %))))))

;; --- Create Icon

(s/def ::create-icon
  (s/keys :req-un [::metadata ::us/name ::metadata ::content]
          :opt-un [::us/id ::collection]))

(defn create-icon
  [{user :identity data :data :as request}]
  (let [{:keys [id name content metadata collection]} (us/conform ::create-icon data)
        id (or id (uuid/random))]
    (->> (sv/novelty {:id id
                      :type :create-icon
                      :user user
                      :name name
                      :collection collection
                      :metadata metadata
                      :content content})
         (p/map (fn [entry]
                  (let [loc (str "/api/library/icons/" (:id entry))]
                    (http/created loc (rsp entry))))))))

;; --- Update Icon

(s/def ::update-icon
  (s/keys :req-un [::us/name ::us/version ::collection] :opt-un [::us/id]))

(defn update-icon
  [{user :identity params :route-params data :data}]
  (let [data (us/conform ::update-icon data)
        message (assoc data
                       :id (uuid/from-string (:id params))
                       :type :update-icon
                       :user user)]
    (->> (sv/novelty message)
         (p/map #(http/ok (rsp %))))))

;; --- Copy Icon

(s/def ::copy-icon
  (s/keys :req-un [:us/id ::collection]))

(defn copy-icon
  [{user :identity data :data}]
  (let [data (us/conform ::copy-icon data)
        message (assoc data
                       :user user
                       :type :copy-icon)]
    (->> (sv/novelty message)
         (p/map #(http/ok (rsp %))))))

;; --- Delete Icon

(defn delete-icon
  [{user :identity params :route-params}]
  (let [message {:id (uuid/from-string (:id params))
                 :type :delete-icon
                 :user user}]
    (->> (sv/novelty message)
         (p/map (fn [v] (http/no-content))))))

;; --- List collections

(s/def ::list-icons
  (s/keys :opt-un [::us/id]))

(defn list-icons
  [{user :identity route-params :route-params}]
  (let [{:keys [id]} (us/conform ::list-icons route-params)
        params {:collection id
                :type :list-icons
                :user user}]
    (->> (sv/query params)
         (p/map rsp)
         (p/map http/ok))))
