;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.services.images
  "Images library related services."
  (:require [clojure.spec.alpha :as s]
            [promesa.core :as p]
            [suricatta.core :as sc]
            [datoteka.storages :as st]
            [datoteka.core :as fs]
            [uxbox.config :as ucfg]
            [uxbox.util.spec :as us]
            [uxbox.sql :as sql]
            [uxbox.db :as db]
            [uxbox.media :as media]
            [uxbox.services.core :as core]
            [uxbox.util.exceptions :as ex]
            [uxbox.util.transit :as t]
            [uxbox.util.uuid :as uuid]
            [uxbox.util.data :as data])
  (:import ratpack.form.UploadedFile
           org.apache.commons.io.FilenameUtils))

(s/def ::width integer?)
(s/def ::height integer?)
(s/def ::mimetype string?)
(s/def ::user uuid?)
(s/def ::path string?)
(s/def ::collection (s/nilable uuid?))

;; --- Create Collection

(defn create-collection
  [conn {:keys [id user name]}]
  (let [id (or id (uuid/random))
        params {:id id :user user :name name}
        sqlv (sql/create-image-collection params)]
    (-> (sc/fetch-one conn sqlv)
        (data/normalize-attrs))))

(s/def ::create-image-collection
  (s/keys :req-un [::user ::us/name]
          :opt-un [::us/id]))

(defmethod core/novelty :create-image-collection
  [params]
  (s/assert ::create-image-collection params)
  (with-open [conn (db/connection)]
    (create-collection conn params)))

;; --- Update Collection

(defn update-collection
  [conn {:keys [id user name version]}]
  (let [sqlv (sql/update-image-collection {:id id
                                           :user user
                                           :name name
                                           :version version})]
    (some-> (sc/fetch-one conn sqlv)
            (data/normalize-attrs))))

(s/def ::update-image-collection
  (s/keys :req-un [::user ::us/name ::us/version]
          :opt-un [::us/id]))

(defmethod core/novelty :update-image-collection
  [params]
  (s/assert ::update-image-collection params)
  (with-open [conn (db/connection)]
    (update-collection conn params)))

;; --- List Collections

(defn get-collections-by-user
  [conn user]
  (let [sqlv (sql/get-image-collections {:user user})]
    (->> (sc/fetch conn sqlv)
         (map data/normalize-attrs))))

(defmethod core/query :list-image-collections
  [{:keys [user] :as params}]
  (s/assert ::user user)
  (with-open [conn (db/connection)]
    (get-collections-by-user conn user)))

;; --- Delete Collection

(defn delete-collection
  [conn {:keys [id user]}]
  (let [sqlv (sql/delete-image-collection {:id id :user user})]
    (pos? (sc/execute conn sqlv))))

(s/def ::delete-image-collection
  (s/keys :req-un [::user]
          :opt-un [::us/id]))

(defmethod core/novelty :delete-image-collection
  [params]
  (s/assert ::delete-image-collection params)
  (with-open [conn (db/connection)]
    (delete-collection conn params)))

;; --- Retrieve Image

(defn retrieve-image
  [conn {:keys [id]}]
  (let [sqlv (sql/get-image {:id id})]
    (some-> (sc/fetch-one conn sqlv)
            (data/normalize-attrs))))

(s/def ::retrieve-image
  (s/keys :req-un [::user ::us/id]))

(defmethod core/query :retrieve-image
  [params]
  (s/assert ::retrieve-image params)
  (with-open [conn (db/connection)]
    (retrieve-image conn params)))

;; --- Create Image (Upload)

(defn create-image
  [conn {:keys [id user name path collection
                height width mimetype]}]
  (let [id (or id (uuid/random))
        sqlv (sql/create-image {:id id
                                :name name
                                :mimetype mimetype
                                :path path
                                :width width
                                :height height
                                :collection collection
                                :user user})]
    (some-> (sc/fetch-one conn sqlv)
            (data/normalize-attrs))))

(s/def ::create-image
  (s/keys :req-un [::user ::us/name ::path ::width ::height ::mimetype]
          :opt-un [::us/id]))

(defmethod core/novelty :create-image
  [params]
  (s/assert ::create-image params)
  (with-open [conn (db/connection)]
    (create-image conn params)))

;; --- Update Image

(defn update-image
  [conn {:keys [id name version user collection]}]
  (let [sqlv (sql/update-image {:id id
                                :collection collection
                                :name name
                                :user user
                                :version version})]
    (some-> (sc/fetch-one conn sqlv)
            (data/normalize-attrs))))

(s/def ::update-image
  (s/keys :req-un [::user ::us/name ::us/version ::collection]
          :opt-un [::us/id]))

(defmethod core/novelty :update-image
  [params]
  (s/assert ::update-image params)
  (with-open [conn (db/connection)]
    (update-image conn params)))

;; --- Copy Image

(s/def ::copy-image
  (s/keys :req-un [::us/id ::collection ::user]))

(declare retrieve-image)

(defn- copy-image
  [conn {:keys [user id collection]}]
  (let [image (retrieve-image conn {:id id :user user})
        storage media/images-storage]
    (when-not image
      (ex/raise :type :validation
                :code ::image-does-not-exists))
    (let [path @(st/lookup storage (:path image))
          filename (fs/name path)
          path @(st/save storage filename path)
          image (assoc image
                       :path (str path)
                       :collection collection)
          image (dissoc image :id)]
      (create-image conn image))))

(defmethod core/novelty :copy-image
  [params]
  (s/assert ::copy-image params)
  (with-open [conn (db/connection)]
    (sc/apply-atomic conn copy-image params)))

;; --- Delete Image

(defn delete-image
  [conn {:keys [user id]}]
  (let [sqlv (sql/delete-image {:id id :user user})]
    (pos? (sc/execute conn sqlv))))

(s/def ::delete-image
  (s/keys :req-un [::user]
          :opt-un [::us/id]))

(defmethod core/novelty :delete-image
  [params]
  (s/assert ::delete-image params)
  (with-open [conn (db/connection)]
    (delete-image conn params)))

;; --- List Images

(defn get-images-by-user
  [conn user collection]
  (let [sqlv (if collection
               (sql/get-images-by-collection {:user user :collection collection})
               (sql/get-images {:user user}))]
    (->> (sc/fetch conn sqlv)
         (map data/normalize-attrs))))

(s/def ::list-images
  (s/keys :req-un [::user ::collection]))

(defmethod core/query :list-images
  [{:keys [user collection] :as params}]
  (s/assert ::list-images params)
  (with-open [conn (db/connection)]
    (get-images-by-user conn user collection)))
