;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.frontend.pages
  (:require [clojure.spec.alpha :as s]
            [promesa.core :as p]
            [catacumba.http :as http]
            [uxbox.util.spec :as us]
            [uxbox.services :as sv]
            [uxbox.util.response :refer (rsp)]
            [uxbox.util.uuid :as uuid]))

;; --- List Pages

(defn list-pages-by-project
  [{user :identity params :route-params}]
  (let [params {:user user
                :project (uuid/from-string (:id params))
                :type :list-pages-by-project}]
    (-> (sv/query params)
        (p/then #(http/ok (rsp %))))))

;; --- Create Page

(s/def ::data any?)
(s/def ::metadata any?)
(s/def ::project ::us/id)
(s/def ::create-page
  (s/keys :req-un [::data ::metadata ::project ::us/name]
          :opt-un [::us/id]))

(defn create-page
  [{user :identity data :data}]
  (let [data (us/conform ::create-page data)
        message (assoc data
                       :type :create-page
                       :user user)]
    (->> (sv/novelty message)
         (p/map (fn [result]
                  (let [loc (str "/api/pages/" (:id result))]
                    (http/created loc (rsp result))))))))

;; --- Update Page

(s/def ::update-page
  (s/merge ::create-page (s/keys :req-un [::us/version])))

(defn update-page
  [{user :identity params :route-params data :data}]
  (let [data (us/conform ::update-page data)
        message (assoc data
                       :id (uuid/from-string (:id params))
                       :type :update-page
                       :user user)]
    (->> (sv/novelty message)
         (p/map #(http/ok (rsp %))))))

;; --- Update Page Metadata

(s/def ::update-page-metadata
  (s/keys :req-un [::us/id ::metadata ::project ::us/name]))

(defn update-page-metadata
  [{user :identity params :route-params data :data}]
  (let [data (us/conform ::update-page-metadata data)
        message (assoc data
                       :id (uuid/from-string (:id params))
                       :type :update-page-metadata
                       :user user)]
    (->> (sv/novelty message)
         (p/map #(http/ok (rsp %))))))

;; --- Delete Page

(defn delete-page
  [{user :identity params :route-params}]
  (let [message {:id (uuid/from-string (:id params))
                 :type :delete-page
                 :user user}]
    (-> (sv/novelty message)
        (p/then (fn [v] (http/no-content))))))

;; --- Retrieve Page History

(s/def ::max (s/and ::us/integer-string ::us/positive-integer))
(s/def ::since ::us/integer-string)
(s/def ::pinned ::us/boolean-string)

(s/def ::retrieve-page-history
  (s/keys :opt-un [::max ::since ::pinned]))

(defn retrieve-page-history
  [{user :identity params :route-params query :query-params}]
  (let [query (us/conform ::retrieve-page-history query)
        message (assoc query
                       :id (uuid/from-string (:id params))
                       :type :list-page-history
                       :user user)]
    (->> (sv/query message)
         (p/map #(http/ok (rsp %))))))

;; --- Update Page History

(s/def ::label string?)
(s/def ::update-page-history
  (s/keys :req-un [::label ::pinned]))

(defn update-page-history
  [{user :identity params :route-params data :data}]
  (let [data (us/conform ::update-page-history data)
        message (assoc data
                       :type :update-page-history
                       :id (uuid/from-string (:hid params))
                       :user user)]
    (->> (sv/novelty message)
         (p/map #(http/ok (rsp %))))))
