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

;; --- Common Specs

(s/def ::id ::us/uuid)
(s/def ::name string?)
(s/def ::project ::us/uuid)
(s/def ::version int?)
(s/def ::data any?)
(s/def ::metadata any?)

;; --- List Pages

(s/def ::list-pages|query
  (s/keys :req-un [::project]))

(defn list-pages
  {:parameters {:query ::list-pages|query}
   :validation :spec}
  [{:keys [user parameters]}]
  (let [project (get-in parameters [:query :project])
        message {:user user :project project :type :list-pages-by-project}]
    (-> (sv/query message)
        (p/then rsp/ok))))

;; --- Create Page

(s/def ::create-page|body
  (s/keys :req-un [::data
                   ::metadata
                   ::project
                   ::name]
          :opt-un [::id]))

(defn create-page
  {:parameters {:body ::create-page|body}
   :validation :spec}
  [{:keys [user parameters]}]
  (let [data (get parameters :body)
        message (assoc data :user user :type :create-page)]
    (->> (sv/novelty message)
         (p/map (fn [result]
                  (let [loc (str "/api/pages/" (:id result))]
                    (rsp/created loc result)))))))

;; --- Update Page

(s/def ::update-page|path
  (s/keys :req-un [::id]))

(s/def ::update-page|body
  (s/keys :req-un [::data
                   ::metadata
                   ::project
                   ::name
                   ::version]
          :opt-un [::id]))

(defn update-page
  {:parameters {:path ::update-page|path
                :body ::update-page|body}
   :validation :spec}
  [{:keys [user parameters]}]
  (let [id (get-in parameters [:path :id])
        data (get parameters :body)
        message (assoc data :id id :type :update-page :user user)]
    (->> (sv/novelty message)
         (p/map #(rsp/ok %)))))

;; --- Update Page Metadata

(s/def ::update-page-metadata|path
  (s/keys :req-un [::id]))

(s/def ::update-page-metadata|body
  (s/keys :req-un [::id
                   ::metadata
                   ::project
                   ::name]))

(defn update-page-metadata
  {:parameters {:path ::update-page-metadata|path
                :body ::update-page-metadata|body}
   :validation :spec}
  [{:keys [user parameters]}]
  (let [id (get-in parameters [:path :id])
        data (get parameters :body)
        message (assoc data :id id :type :update-page-metadata :user user)]
    (->> (sv/novelty message)
         (p/map rsp/ok))))

;; --- Delete Page

(s/def ::delete-page|path
  (s/keys :req-un [::id]))

(defn delete-page
  {:parameters {:path ::delete-page|path}
   :validation :spec}
  [{:keys [user parameters]}]
  (let [id (get-in parameters [:path :id])
        message {:id id :type :delete-page :user user}]
    (-> (sv/novelty message)
        (p/then (constantly (rsp/no-content))))))

;; --- Retrieve Page History

(s/def ::max ::us/integer)
(s/def ::since ::us/integer)
(s/def ::pinned ::us/boolean)

(s/def ::retrieve-page-history|path
  (s/keys :req-un [::id]))

(s/def ::retrieve-page-history|query
  (s/keys :opt-un [::max
                   ::since
                   ::pinned]))

(defn retrieve-page-history
  "Retrieve the page history"
  {:parameters {:path ::retrieve-page-history|path
                :query ::retrieve-page-history|query}
   :validation :spec}
  [{:keys [user parameters]}]
  (let [id (get-in parameters [:path :id])
        data (get parameters :query)
        message (assoc data :id id :type :list-page-history :user user)]
    (->> (sv/query message)
         (p/map rsp/ok))))

;; --- Update page history

(s/def ::hid ::us/uuid)
(s/def ::label string?)

(s/def ::update-page-history|path
  (s/keys :req-un [::id ::hid]))

(s/def ::update-page-history|body
  (s/keys :req-un [::label ::pinned]))

(defn update-page-history
  {:parameters {:path ::update-page-history|path
                :body ::update-page-history|body}
   :validation :spec}
  [{:keys [user parameters]}]
  (let [{:keys [id hid]} (get parameters :path)
        message (assoc (get parameters :body)
                       :type :update-page-history
                       :id hid
                       :user user)]
    (->> (sv/novelty message)
         (p/map rsp/ok))))
