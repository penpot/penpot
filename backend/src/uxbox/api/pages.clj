;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.api.pages
  (:require [clojure.spec.alpha :as s]
            [struct.core :as st]
            [promesa.core :as p]
            [uxbox.services :as sv]
            [uxbox.http.response :as rsp]
            [uxbox.util.spec :as us]
            [uxbox.util.uuid :as uuid]))

(defn list-pages
  {:parameters {:query {:project [st/required st/uuid-str]}}}
  [{:keys [user parameters]}]
  (let [project (get-in parameters [:query :project])
        message {:user user :project project :type :list-pages-by-project}]
    (-> (sv/query message)
        (p/then rsp/ok))))

(defn create-page
  {:parameters {:body {:data [st/required]
                       :metadata [st/required]
                       :project [st/required st/uuid]
                       :name [st/required st/string]
                       :id [st/uuid]}}}
  [{:keys [user parameters]}]
  (let [data (get parameters :body)
        message (assoc data :user user :type :create-page)]
    (->> (sv/novelty message)
         (p/map (fn [result]
                  (let [loc (str "/api/pages/" (:id result))]
                    (rsp/created loc result)))))))

(defn update-page
  {:parameters {:path {:id [st/required st/uuid-str]}
                :body {:data [st/required]
                       :metadata [st/required]
                       :project [st/required st/uuid]
                       :name [st/required st/string]
                       :version [st/required st/integer]
                       :id [st/uuid]}}}
  [{:keys [user parameters]}]
  (let [id (get-in parameters [:path :id])
        data (get parameters :body)
        message (assoc data :id id :type :update-page :user user)]
    (->> (sv/novelty message)
         (p/map #(rsp/ok %)))))

(defn update-page-metadata
  {:parameters {:path {:id [st/required st/uuid-str]}
                :body {:id [st/required st/uuid]
                       :metadata [st/required]
                       :project [st/required st/uuid]
                       :name [st/required st/string]}}}
  [{:keys [user parameters]}]
  (let [id (get-in parameters [:path :id])
        data (get parameters :body)
        message (assoc data :id id :type :update-page-metadata :user user)]
    (->> (sv/novelty message)
         (p/map rsp/ok))))

(defn delete-page
  {:parameters {:path {:id [st/required st/uuid-str]}}}
  [{:keys [user parameters]}]
  (let [id (get-in parameters [:path :id])
        message {:id id :type :delete-page :user user}]
    (-> (sv/novelty message)
        (p/then (constantly (rsp/no-content))))))

(defn retrieve-page-history
  "Retrieve the page history"
  {:parameters {:path {:id [st/required st/uuid-str]}
                :query {:max [st/integer-str]
                        :since [st/integer-str]
                        :pinned [st/boolean-str]}}}
  [{:keys [user parameters]}]
  (let [id (get-in parameters [:path :id])
        data (get parameters :query)
        message (assoc data :id id :type :list-page-history :user user)]
    (->> (sv/query message)
         (p/map rsp/ok))))

(defn update-page-history
  {:parameters {:path {:id [st/required st/uuid-str]
                       :hid [st/required st/uuid-str]}
                :body {:label [st/required st/string]
                       :pinned [st/required st/boolean]}}}
  [{:keys [user parameters]}]
  (let [{:keys [id hid]} (get parameters :path)
        message (assoc (get parameters :body)
                       :type :update-page-history
                       :id hid
                       :user user)]
    (->> (sv/novelty message)
         (p/map rsp/ok))))
