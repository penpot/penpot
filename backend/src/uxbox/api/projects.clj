;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.api.projects
  (:require [clojure.spec.alpha :as s]
            [clojure.pprint :refer [pprint]]
            [struct.core :as st]
            [promesa.core :as p]
            [uxbox.services :as sv]
            [uxbox.http.response :as rsp]
            [uxbox.util.spec :as us]
            [uxbox.util.uuid :as uuid]
            [uxbox.util.exceptions :as ex]))


;; --- Common Specs

(s/def ::id ::us/uuid)
(s/def ::name string?)
(s/def ::version int?)

;; --- List Projects

(defn list-projects
  {:description "List projects"}
  [{:keys [user] :as req}]
  (let [message {:user user :type :list-projects}]
    (->> (sv/query message)
         (p/map rsp/ok))))

;; --- Create Projects

(s/def ::create-project|body
  (s/keys :req-un [::name]
          :opt-un [::id]))

(defn create-project
  "Create project"
  {:parameters {:body ::create-project|body}
   :validation :spec}
  [{:keys [user parameters] :as req}]
  (let [data (get parameters :body)
        message (assoc data :type :create-project :user user)]
    (->> (sv/novelty message)
         (p/map (fn [result]
                  (let [loc (str "/api/projects/" (:id result))]
                    (rsp/created loc result)))))))

;; --- Update Project

(s/def ::update-project|path
  (s/keys :req-un [::id]))

(s/def ::update-project|body
  (s/keys :req-un [::name ::version]))

(defn update-project
  "Update project"
  {:parameters {:path ::update-project|path
                :body ::update-project|body}
   :validation :spec}
  [{:keys [user parameters] :as req}]
  (let [id (get-in parameters [:path :id])
        data (get parameters :body)
        message (assoc data :id id :type :update-project :user user)]
    (-> (sv/novelty message)
        (p/then rsp/ok))))

;; --- Delete Project

(s/def ::delete-project|path
  (s/keys :req-un [::id]))

(defn delete-project
  "Delete project"
  {:parameters {:path ::delete-project|path}
   :validation :spec}
  [{:keys [user parameters] :as req}]
  (let [id (get-in parameters [:path :id])
        message {:id id :type :delete-project :user user}]
    (-> (sv/novelty message)
        (p/then (constantly (rsp/no-content))))))

;; --- Get Project by Share Token

(s/def ::token string?)

(s/def ::get-project-by-share-token|path
  (s/keys :req-un [::token]))

(defn get-project-by-share-token
  "Get a project by shared token"
  {:parameters {:path ::get-project-by-share-token|path}
   :validation :spec}
  [{:keys [user parameters] :as req}]
  (let [message {:token (get-in parameters [:path :token])
                 :type :retrieve-project-by-share-token}]
    (->> (sv/query message)
         (p/map rsp/ok))))
