;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.services.projects
  (:require [clojure.spec :as s]
            [suricatta.core :as sc]
            [buddy.core.codecs :as codecs]
            [uxbox.config :as ucfg]
            [uxbox.sql :as sql]
            [uxbox.db :as db]
            [uxbox.util.spec :as us]
            [uxbox.services.core :as core]
            [uxbox.services.pages :as pages]
            [uxbox.util.data :as data]
            [uxbox.util.transit :as t]
            [uxbox.util.blob :as blob]
            [uxbox.util.uuid :as uuid]))

(s/def ::token string?)
(s/def ::data string?)
(s/def ::user uuid?)
(s/def ::project uuid?)

;; --- Create Project

(defn create-project
  [conn {:keys [id user name] :as data}]
  (let [id (or id (uuid/random))
        sqlv (sql/create-project {:id id :user user :name name})]
    (some-> (sc/fetch-one conn sqlv)
            (data/normalize))))

(s/def ::create-project
  (s/keys :req-un [::user ::us/name]
          :opt-un [::us/id]))

(defmethod core/novelty :create-project
  [params]
  (s/assert ::create-project params)
  (with-open [conn (db/connection)]
    (create-project conn params)))

;; --- Update Project

(defn- update-project
  [conn {:keys [name version id user] :as data}]
  (let [sqlv (sql/update-project {:name name
                                  :version version
                                  :id id
                                  :user user})]
    (some-> (sc/fetch-one conn sqlv)
            (data/normalize))))

(s/def ::update-project
  (s/merge ::create-project (s/keys :req-un [::us/version])))

(defmethod core/novelty :update-project
  [params]
  (s/assert ::update-project params)
  (with-open [conn (db/connection)]
    (update-project conn params)))

;; --- Delete Project

(defn- delete-project
  [conn {:keys [id user] :as data}]
  (let [sqlv (sql/delete-project {:id id :user user})]
    (pos? (sc/execute conn sqlv))))

(s/def ::delete-project
  (s/keys :req-un [::us/id ::user]))

(defmethod core/novelty :delete-project
  [params]
  (s/assert ::delete-project params)
  (with-open [conn (db/connection)]
    (delete-project conn params)))

;; --- List Projects

(declare decode-page-metadata)
(declare decode-page-data)

(defn get-projects
  [conn user]
  (let [sqlv (sql/get-projects {:user user})]
    (->> (sc/fetch conn sqlv)
         (map data/normalize)

         ;; This is because the project comes with
         ;; the first page preloaded and it need
         ;; to be decoded.
         (map decode-page-metadata)
         (map decode-page-data))))

(defmethod core/query :list-projects
  [{:keys [user] :as params}]
  (s/assert ::user user)
  (with-open [conn (db/connection)]
    (get-projects conn user)))

;; --- Retrieve Project by share token

(defn- get-project-by-share-token
  [conn token]
  (let [sqlv (sql/get-project-by-share-token {:token token})
        project (some-> (sc/fetch-one conn sqlv)
                        (data/normalize))]
    (when-let [id (:id project)]
      (let [pages (vec (pages/get-pages-for-project conn id))]
        (assoc project :pages pages)))))

(defmethod core/query :retrieve-project-by-share-token
  [{:keys [token]}]
  (s/assert ::token token)
  (with-open [conn (db/connection)]
    (get-project-by-share-token conn token)))

;; --- Retrieve share tokens

(defn get-share-tokens-for-project
  [conn project]
  (s/assert ::project project)
  (let [sqlv (sql/get-share-tokens-for-project {:project project})]
    (->> (sc/fetch conn sqlv)
         (map data/normalize))))

;; Helpers

(defn- decode-page-metadata
  [{:keys [page-metadata] :as result}]
  (merge result (when page-metadata
                  {:page-metadata (-> page-metadata blob/decode t/decode)})))

(defn- decode-page-data
  [{:keys [page-data] :as result}]
  (merge result (when page-data
                  {:page-data (-> page-data blob/decode t/decode)})))


