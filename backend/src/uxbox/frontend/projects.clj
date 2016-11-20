;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.frontend.projects
  (:require [clojure.spec :as s]
            [promesa.core :as p]
            [catacumba.http :as http]
            [uxbox.util.spec :as us]
            [uxbox.services :as sv]
            [uxbox.util.response :refer (rsp)]
            [uxbox.util.uuid :as uuid]))

;; --- List Projects

(defn list-projects
  [{user :identity}]
  (let [message {:user user :type :list-projects}]
    (->> (sv/query message)
         (p/map #(http/ok (rsp %))))))

;; --- Create Projects

(s/def ::create-project
  (s/keys :req-un [::us/name] :opt-un [::us/id]))

(defn create-project
  [{user :identity data :data}]
  (let [data (us/conform ::create-project data)
        message (assoc data
                       :type :create-project
                       :user user)]
    (->> (sv/novelty message)
         (p/map (fn [result]
                  (let [loc (str "/api/projects/" (:id result))]
                    (http/created loc (rsp result))))))))

;; --- Update Project

(s/def ::update-project
  (s/keys :req-un [::us/name ::us/version]))

(defn update-project
  [{user :identity params :route-params data :data}]
  (let [data (us/conform ::update-project data)
        message (assoc data
                       :id (uuid/from-string (:id params))
                       :type :update-project
                       :user user)]
    (-> (sv/novelty message)
        (p/then #(http/ok (rsp %))))))

;; --- Delete Project

(defn delete-project
  [{user :identity params :route-params}]
  (let [message {:id (uuid/from-string (:id params))
                 :type :delete-project
                 :user user}]
    (-> (sv/novelty message)
        (p/then (fn [v] (http/no-content))))))


;; --- Retrieve project

(defn retrieve-project-by-share-token
  [{params :route-params}]
  (let [message {:token (:token params)
                 :type :retrieve-project-by-share-token}]
    (->> (sv/query message)
         (p/map #(http/ok (rsp %))))))
