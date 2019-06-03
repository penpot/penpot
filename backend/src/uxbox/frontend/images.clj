;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.frontend.images
  (:require [clojure.spec.alpha :as s]
            [promesa.core :as p]
            [catacumba.http :as http]
            [datoteka.storages :as st]
            [datoteka.core :as fs]
            [uxbox.media :as media]
            [uxbox.images :as images]
            [uxbox.util.spec :as us]
            [uxbox.services :as sv]
            [uxbox.util.response :refer (rsp)]
            [uxbox.util.uuid :as uuid]))

;; --- Constants & Config

(s/def ::file ::us/uploaded-file)
(s/def ::width ::us/integer-string)
(s/def ::height ::us/integer-string)
(s/def ::collection (s/nilable ::us/uuid-string))
(s/def ::mimetype string?)

(def +thumbnail-options+ {:src :path
                          :dst :thumbnail
                          :width 300
                          :height 100
                          :quality 92
                          :format "webp"})

(def populate-thumbnails
  #(images/populate-thumbnails % +thumbnail-options+))

(def populate-urls
  #(images/populate-urls % media/images-storage :path :url))

;; --- Create Collection

(s/def ::create-collection
  (s/keys :req-un [::us/name] :opt-un [::us/id]))

(defn create-collection
  [{user :identity data :data}]
  (let [data (us/conform ::create-collection data)
        message (assoc data
                       :type :create-image-collection
                       :user user)]
    (->> (sv/novelty message)
         (p/map (fn [result]
                  (let [loc (str "/api/library/images/" (:id result))]
                    (http/created loc (rsp result))))))))

;; --- Update Collection

(s/def ::update-collection
  (s/merge ::create-collection (s/keys :req-un [::us/version])))

(defn update-collection
  [{user :identity params :route-params data :data}]
  (let [data (us/conform ::update-collection data)
        message (assoc data
                       :id (uuid/from-string (:id params))
                       :type :update-image-collection
                       :user user)]
    (-> (sv/novelty message)
        (p/then #(http/ok (rsp %))))))

;; --- Delete Collection

(defn delete-collection
  [{user :identity params :route-params}]
  (let [message {:id (uuid/from-string (:id params))
                 :type :delete-image-collection
                 :user user}]
    (-> (sv/novelty message)
        (p/then (fn [v] (http/no-content))))))

;; --- List collections

(defn list-collections
  [{user :identity}]
  (let [params {:user user
                :type :list-image-collections}]
    (-> (sv/query params)
        (p/then #(http/ok (rsp %))))))

;; --- Retrieve Image

(s/def ::retrieve-image
  (s/keys :req-un [::us/id]))

(defn retrieve-image
  [{user :identity params :route-params}]
  (let [params (us/conform ::retrieve-image params)
        params (assoc params :user user :type :retrieve-image)]
    (->> (sv/query params)
         (p/map (fn [result]
                  (if result
                    (-> (populate-thumbnails result)
                        (populate-urls)
                        (rsp)
                        (http/ok))
                    (http/not-found "")))))))

;; --- Create Image

(s/def ::create-image
  (s/keys :req-un [::file ::width ::height ::mimetype]
          :opt-un [::us/id ::collection]))

(defn create-image
  [{user :identity data :data}]
  (let [{:keys [file id width height
                mimetype collection]} (us/conform ::create-image data)
        id (or id (uuid/random))
        filename (fs/name file)
        storage media/images-storage]
    (letfn [(persist-image-entry [path]
              (sv/novelty {:id id
                           :type :create-image
                           :user user
                           :width width
                           :height height
                           :mimetype mimetype
                           :collection collection
                           :name filename
                           :path (str path)}))
            (create-response [entry]
              (let [loc (str "/api/library/images/" (:id entry))]
                (http/created loc (rsp entry))))]
      (->> (st/save storage filename file)
           (p/mapcat persist-image-entry)
           (p/map populate-thumbnails)
           (p/map populate-urls)
           (p/map create-response)))))

;; --- Update Image

(s/def ::update-image
  (s/keys :req-un [::us/name ::us/version ::collection] :opt-un [::us/id]))

(defn update-image
  [{user :identity params :route-params data :data}]
  (let [data (us/conform ::update-image data)
        message (assoc data
                       :id (uuid/from-string (:id params))
                       :type :update-image
                       :user user)]
    (->> (sv/novelty message)
         (p/map populate-thumbnails)
         (p/map populate-urls)
         (p/map #(http/ok (rsp %))))))

;; --- Copy Image

(s/def ::copy-image
  (s/keys :req-un [::us/id ::collection]))

(defn copy-image
  [{user :identity data :data}]
  (let [data (us/conform ::copy-image data)
        params (assoc data :user user :type :copy-image)]
    (->> (sv/novelty params)
         (p/map populate-thumbnails)
         (p/map populate-urls)
         (p/map #(http/ok (rsp %))))))

;; --- Delete Image

(defn delete-image
  [{user :identity params :route-params}]
  (let [message {:id (uuid/from-string (:id params))
                 :type :delete-image
                 :user user}]
    (->> (sv/novelty message)
         (p/map (fn [v] (http/no-content))))))

;; --- List collections

(s/def ::list-images
  (s/keys :opt-un [::us/id]))

(defn list-images
  [{user :identity route-params :route-params}]
  (let [{:keys [id]} (us/conform ::list-images route-params)
        params {:collection id
                :type :list-images
                :user user}]
    (->> (sv/query params)
         (p/map (partial map populate-thumbnails))
         (p/map (partial map populate-urls))
         (p/map rsp)
         (p/map http/ok))))
