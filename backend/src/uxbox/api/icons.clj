;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.api.icons
  (:require [struct.core :as st]
            [promesa.core :as p]
            [uxbox.services :as sv]
            [uxbox.util.http :as http]
            [uxbox.util.spec :as us]
            [uxbox.util.uuid :as uuid]))

(defn create-collection
  {:parameters {:body {:name [st/required st/string]
                       :id [st/uuid]}}}
  [{:keys [user parameters]}]
  (let [data (get parameters :body)
        message (assoc data
                       :type :create-icon-collection
                       :user user)]
    (->> (sv/novelty message)
         (p/map (fn [result]
                  (let [loc (str "/api/library/icons/" (:id result))]
                    (http/created loc result)))))))

(defn update-collection
  {:parameters {:path {:id [st/required st/uuid-str]}
                :body {:name [st/required st/string]
                       :version [st/required st/integer]
                       :id [st/uuid]}}}
  [{:keys [user parameters]}]
  (let [data (get parameters :body)
        message (assoc data
                       :id (get-in parameters [:path :id])
                       :type :update-icon-collection
                       :user user)]
    (-> (sv/novelty message)
        (p/then #(http/ok %)))))


(defn delete-collection
  {:parameters {:path {:id [st/required st/uuid-str]}}}
  [{:keys [user parameters]}]
  (let [message {:id (get-in parameters [:path :id])
                 :type :delete-icon-collection
                 :user user}]
    (-> (sv/novelty message)
        (p/then (fn [v] (http/no-content))))))

(defn list-collections
  [{:keys [user]}]
  (let [params {:user user :type :list-icon-collections}]
    (-> (sv/query params)
        (p/then #(http/ok %)))))

;; (def metadata-spec
;;   {:width [st/number st/positive]
;;    :height [st/number st/positive]
;;    :view-box [st/coll [st/every number?]]
;;    :mimetype [st/string]})

;; (def metadata-validator
;;   {:message "must be a metadata"
;;    :optional true
;;    :validate #(st/valid? %1 metadata-spec)})

(defn create-icon
  {:parameters {:body {:id [st/uuid]
                       :collection [st/uuid]
                       :metadata [st/required] ;; TODO
                       :name [st/required st/string]
                       :content [st/required st/string]}}}
  [{:keys [user parameters]}]
  (let [id (or (get-in parameters [:body :id]) (uuid/random))
        data (get parameters :body)
        message (assoc data
                       :user user
                       :id id
                       :type :create-icon)]
    (->> (sv/novelty message)
         (p/map (fn [entry]
                  (let [loc (str "/api/library/icons/" (:id entry))]
                    (http/created loc entry)))))))

(defn update-icon
  {:parameters {:path {:id [st/required st/uuid-str]}
                :body {:name [st/required st/string]
                       :version [st/required st/number]
                       :collection [st/uuid]}}}
  [{:keys [user parameters]}]
  (let [data (get parameters :body)
        message (assoc data
                       :id (get-in parameters [:path :id])
                       :type :update-icon
                       :user user)]
    (->> (sv/novelty message)
         (p/map #(http/ok %)))))

(defn copy-icon
  {:parameters {:path {:id [st/required st/uuid-str]}
                :body {:collection [st/uuid]}}}
  [{:keys [user parameters]}]
  (let [data (get parameters :body)
        message {:collection (get-in parameters [:body :collection])
                 :id (get-in parameters [:path :id])
                 :user user
                 :type :copy-icon}]
    (->> (sv/novelty message)
         (p/map #(http/ok %)))))

(defn delete-icon
  {:parameters {:path {:id [st/required st/uuid-str]}}}
  [{:keys [user parameters]}]
  (let [message {:id (get-in parameters [:path :id])
                 :type :delete-icon
                 :user user}]
    (->> (sv/novelty message)
         (p/map (fn [v] (http/no-content))))))

(defn list-icons
  {:parameters {:query {:collection [st/uuid-str]}}}
  [{:keys [user parameters]}]
  (let [collection (get-in parameters [:query :collection])
        message {:collection collection
                 :type :list-icons
                 :user user}]
    (->> (sv/query message)
         (p/map http/ok))))

