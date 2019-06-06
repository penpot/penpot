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
            [uxbox.util.http :as http]
            [uxbox.util.spec :as us]
            [uxbox.util.uuid :as uuid]
            [uxbox.util.exceptions :as ex]))

(defn list
  {:description "List projects"}
  [{:keys [user] :as req}]
  (let [message {:user user :type :list-projects}]
    (->> (sv/query message)
         (p/map #(http/ok %)))))

(defn create
  "Create project"
  {:parameters {:body {:name [st/required st/string]
                       :id   [st/uuid-str]}}}
  [{:keys [user parameters] :as req}]
  (let [data (get parameters :body)
        message (assoc data :type :create-project :user user)]
    (->> (sv/novelty message)
         (p/map (fn [result]
                  (let [loc (str "/api/projects/" (:id result))]
                    (http/created loc result)))))))

(defn update
  "Update project"
  {:parameters {:path {:id [st/required st/uuid-str]}
                :body {:name [st/required st/string]
                       :version [st/required st/integer]}}}
  [{:keys [user parameters] :as req}]
  (let [id (get-in parameters [:path :id])
        data (get parameters :body)
        message (assoc data :id id :type :update-project :user user)]
    (-> (sv/novelty message)
        (p/then #(http/ok %)))))

(defn delete
  "Delete project"
  {:parameters {:path {:id [st/required st/uuid-str]}}}
  [{:keys [user parameters] :as req}]
  (let [id (get-in parameters [:path :id])
        message {:id id :type :delete-project :user user}]
    (-> (sv/novelty message)
        (p/then (fn [v] (http/no-content))))))

(defn get-by-share-token
  "Get a project by shared token"
  {:parameters {:path {:token [st/required st/string]}}}
  [{:keys [user parameters] :as req}]
  (let [message {:token (get-in parameters [:path :token])
                 :type :retrieve-project-by-share-token}]
    (->> (sv/query message)
         (p/map #(http/ok %)))))
