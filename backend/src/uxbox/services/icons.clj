;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.services.icons
  "Icons library related services."
  (:require [clojure.spec.alpha :as s]
            [suricatta.core :as sc]
            [uxbox.util.spec :as us]
            [uxbox.sql :as sql]
            [uxbox.db :as db]
            [uxbox.services.core :as core]
            [uxbox.util.exceptions :as ex]
            [uxbox.util.transit :as t]
            [uxbox.util.uuid :as uuid]
            [uxbox.util.blob :as blob]
            [uxbox.util.data :as data])
  (:import ratpack.form.UploadedFile
           org.apache.commons.io.FilenameUtils))

;; --- Helpers & Specs

(s/def ::user uuid?)
(s/def ::collection (s/nilable uuid?))
(s/def ::width (s/and number? pos?))
(s/def ::height (s/and number? pos?))
(s/def ::view-box (s/and (s/coll-of number?)
                         #(= 4 (count %))
                         vector?))

(s/def ::content string?)
(s/def ::mimetype string?)
(s/def ::metadata
  (s/keys :opt-un [::width ::height ::view-box ::mimetype]))

(defn decode-metadata
  [{:keys [metadata] :as data}]
  (if metadata
    (assoc data :metadata (-> metadata blob/decode t/decode))
    data))

;; --- Create Collection

(defn create-collection
  [conn {:keys [id user name]}]
  (let [id (or id (uuid/random))
        params {:id id :user user :name name}
        sqlv (sql/create-icon-collection params)]
    (-> (sc/fetch-one conn sqlv)
        (data/normalize))))

(s/def ::create-icon-collection
  (s/keys :req-un [::user ::us/name]
          :opt-un [::us/id]))

(defmethod core/novelty :create-icon-collection
  [params]
  (s/assert ::create-icon-collection params)
  (with-open [conn (db/connection)]
    (create-collection conn params)))

;; --- Update Collection

(defn update-collection
  [conn {:keys [id user name version]}]
  (let [sqlv (sql/update-icon-collection {:id id
                                           :user user
                                           :name name
                                           :version version})]
    (some-> (sc/fetch-one conn sqlv)
            (data/normalize))))

(s/def ::update-icon-collection
  (s/keys :req-un [::user ::us/name ::us/version]
          :opt-un [::us/id]))

(defmethod core/novelty :update-icon-collection
  [params]
  (s/assert ::update-icon-collection params)
  (with-open [conn (db/connection)]
    (sc/apply-atomic conn update-collection params)))

;; --- Copy Icon

(s/def ::copy-icon
  (s/keys :req-un [:us/id ::collection ::user]))

(defn- retrieve-icon
  [conn {:keys [user id]}]
  (let [sqlv (sql/get-icon {:user user :id id})]
    (some->> (sc/fetch-one conn sqlv)
             (data/normalize-attrs))))

(declare create-icon)

(defn- copy-icon
  [conn {:keys [user id collection]}]
  (let [icon (retrieve-icon conn {:id id :user user})]
    (when-not icon
      (ex/raise :type :validation
                :code ::icon-does-not-exists))
    (let [params (dissoc icon :id)]
      (create-icon conn params))))

(defmethod core/novelty :copy-icon
  [params]
  (s/assert ::copy-icon params)
  (with-open [conn (db/connection)]
    (sc/apply-atomic conn copy-icon params)))

;; --- List Collections

(defn get-collections-by-user
  [conn user]
  (let [sqlv (sql/get-icon-collections {:user user})]
    (->> (sc/fetch conn sqlv)
         (map data/normalize))))

(defmethod core/query :list-icon-collections
  [{:keys [user] :as params}]
  (s/assert ::user user)
  (with-open [conn (db/connection)]
    (get-collections-by-user conn user)))

;; --- Delete Collection

(defn delete-collection
  [conn {:keys [id user]}]
  (let [sqlv (sql/delete-icon-collection {:id id :user user})]
    (pos? (sc/execute conn sqlv))))

(s/def ::delete-icon-collection
  (s/keys :req-un [::user]
          :opt-un [::us/id]))

(defmethod core/novelty :delete-icon-collection
  [params]
  (s/assert ::delete-icon-collection params)
  (with-open [conn (db/connection)]
    (delete-collection conn params)))

;; --- Create Icon (Upload)

(defn create-icon
  [conn {:keys [id user name collection
                metadata content] :as params}]
  (let [id (or id (uuid/random))
        params {:id id
                :name name
                :content content
                :metadata (-> metadata t/encode blob/encode)
                :collection collection
                :user user}
        sqlv (sql/create-icon params)]
    (some-> (sc/fetch-one conn sqlv)
            (data/normalize)
            (decode-metadata))))

(s/def ::create-icon
  (s/keys :req-un [::user ::us/name ::metadata ::content]
          :opt-un [::us/id ::collection]))

(defmethod core/novelty :create-icon
  [params]
  (s/assert ::create-icon params)
  (with-open [conn (db/connection)]
    (create-icon conn params)))

;; --- Update Icon

(defn update-icon
  [conn {:keys [id name version user collection]}]
  (let [sqlv (sql/update-icon {:id id
                               :collection collection
                               :name name
                               :user user
                               :version version})]
    (some-> (sc/fetch-one conn sqlv)
            (data/normalize)
            (decode-metadata))))

(s/def ::update-icon
  (s/keys :req-un [::us/id ::user ::us/name ::us/version ::collection]))

(defmethod core/novelty :update-icon
  [params]
  (s/assert ::update-icon params)
  (with-open [conn (db/connection)]
    (update-icon conn params)))

;; --- Delete Icon

(defn delete-icon
  [conn {:keys [user id]}]
  (let [sqlv (sql/delete-icon {:id id :user user})]
    (pos? (sc/execute conn sqlv))))

(s/def ::delete-icon
  (s/keys :req-un [::user]
          :opt-un [::us/id]))

(defmethod core/novelty :delete-icon
  [params]
  (s/assert ::delete-icon params)
  (with-open [conn (db/connection)]
    (delete-icon conn params)))

;; --- List Icons

(defn get-icons-by-user
  [conn user collection]
  (let [sqlv (if collection
               (sql/get-icons-by-collection {:user user :collection collection})
               (sql/get-icons {:user user}))]
    (->> (sc/fetch conn sqlv)
         (map data/normalize)
         (map decode-metadata))))

(s/def ::list-icons
  (s/keys :req-un [::user ::collection]))

(defmethod core/query :list-icons
  [{:keys [user collection] :as params}]
  (s/assert ::list-icons params)
  (with-open [conn (db/connection)]
    (get-icons-by-user conn user collection)))
