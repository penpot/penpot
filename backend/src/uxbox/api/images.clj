;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.api.images
  (:require [struct.core :as st]
            [promesa.core :as p]
            [datoteka.storages :as ds]
            [datoteka.core :as fs]
            [uxbox.media :as media]
            [uxbox.images :as images]
            [uxbox.services :as sv]
            [uxbox.util.http :as http]
            [uxbox.util.spec :as us]
            [uxbox.util.uuid :as uuid]))

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

(defn create-collection
  {:parameters {:body {:name [st/required st/string]
                       :id [st/uuid]}}}
  [{:keys [user parameters]}]
  (let [data (get parameters :body)
        message (assoc data
                       :type :create-image-collection
                       :user user)]
    (->> (sv/novelty message)
         (p/map (fn [result]
                  (let [loc (str "/api/library/images/" (:id result))]
                    (http/created loc result)))))))

(defn update-collection
  {:parameters {:path {:id [st/required st/uuid-str]}
                :body {:name [st/required st/string]
                       :version [st/required st/number]
                       :id [st/uuid]}}}
  [{:keys [user parameters]}]
  (let [data (get parameters :body)
        message (assoc data
                       :id (get-in parameters [:path :id])
                       :type :update-image-collection
                       :user user)]
    (-> (sv/novelty message)
        (p/then http/ok))))

(defn delete-collection
  {:parameters {:path {:id [st/required st/uuid-str]}}}
  [{:keys [user parameters]}]
  (let [message {:id (get-in parameters [:path :id])
                 :type :delete-image-collection
                 :user user}]
    (-> (sv/novelty message)
        (p/then (constantly (http/no-content))))))

(defn list-collections
  [{:keys [user]}]
  (let [params {:user user :type :list-image-collections}]
    (-> (sv/query params)
        (p/then http/ok))))

(defn retrieve-image
  {:parameters {:path {:id [st/required st/uuid-str]}}}
  [{:keys [user parameters]}]
  (let [message {:user user
                 :type :retrieve-image
                 :id (get-in parameters [:path :id])}]
    (->> (sv/query message)
         (p/map (fn [result]
                  (if result
                    (-> (populate-thumbnails result)
                        (populate-urls)
                        (http/ok))
                    (http/not-found "")))))))

;; (s/def ::create-image
;;   (s/keys :req-un [::file ::width ::height ::mimetype]
;;           :opt-un [::us/id ::collection]))

(defn create-image
  [{user :identity data :data}]
  #_(let [{:keys [file id width height
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

(defn update-image
  {:parameters {:path {:id [st/required st/uuid-str]}
                :body {:name [st/required st/string]
                       :version [st/required st/number]
                       :collection [st/uuid]}}}
  [{:keys [user parameters]}]
  (let [data (get parameters :body)
        message (assoc data
                       :id (get-in parameters [:path :id])
                       :type :update-image
                       :user user)]
    (->> (sv/novelty message)
         (p/map populate-thumbnails)
         (p/map populate-urls)
         (p/map http/ok))))

(defn copy-image
  {:parameters {:path {:id [st/required st/uuid-str]}
                :body {:collection [st/uuid]}}}
  [{:keys [user parameters]}]
  (let [message {:id (get-in parameters [:path :id])
                 :type :copy-image
                 :collection (get-in parameters [:body :collection])}]
    (->> (sv/novelty message)
         (p/map populate-thumbnails)
         (p/map populate-urls)
         (p/map http/ok))))

(defn delete-image
  {:parameters {:path {:id [st/required st/uuid-str]}}}
  [{:keys [user parameters]}]
  (let [message {:id (get-in parameters [:path :id])
                 :type :delete-image
                 :user user}]
    (->> (sv/novelty message)
         (p/map (constantly (http/no-content))))))

;; --- List collections

(defn list-images
  {:parameters {:query {:collection [st/uuid-str]}}}
  [{:keys [user parameters]}]
  (let [collection (get-in parameters [:query :collection])
        message {:collection collection
                 :type :list-images
                 :user user}]
    (->> (sv/query message)
         (p/map (partial map populate-thumbnails))
         (p/map (partial map populate-urls))
         (p/map http/ok))))


