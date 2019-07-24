;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.api.users
  (:require [clojure.spec.alpha :as s]
            [struct.core :as st]
            [promesa.core :as p]
            [datoteka.storages :as ds]
            [datoteka.core :as fs]
            [uxbox.services :as sv]
            [uxbox.media :as media]
            [uxbox.images :as images]
            [uxbox.util.http :as http]
            [uxbox.util.spec :as us]
            [uxbox.util.uuid :as uuid]))

;; --- Helpers

(defn- resolve-thumbnail
  [user]
  (let [opts {:src :photo
              :dst :photo
              :size [100 100]
              :quality 90
              :format "jpg"}]
    (images/populate-thumbnails user opts)))

(defn retrieve-profile
  [{:keys [user]}]
  (let [message {:user user :type :retrieve-profile}]
    (->> (sv/query message)
         (p/map resolve-thumbnail)
         (p/map #(http/ok %)))))

(defn update-profile
  {:parameters {:body {:username [st/required st/string]
                       :email [st/required st/email]
                       :fullname [st/required st/string]
                       :metadata [st/required]}}}
  [{:keys [user parameters]}]
  (let [data (get parameters :body)
        message (assoc data
                       :type :update-profile
                       :user user)]
    (->> (sv/novelty message)
         (p/map resolve-thumbnail)
         (p/map #(http/ok %)))))


(defn update-password
  {:parameters {:body {:password [st/required st/string]
                       :old-password [st/required st/string]}}}
  [{:keys [user parameters]}]
  (let [data (get parameters :body)
        message (assoc data
                      :type :update-profile-password
                      :user user)]
    (-> (sv/novelty message)
        (p/then (fn [_] (http/no-content))))))

;; TODO: validate {:multipart {:file {:filename "sample.jpg", :content-type "application/octet-stream", :tempfile #file "/tmp/ring-multipart-7913603702731714635.tmp", :size 312043}}}

(defn update-photo
  {:parameters {:multipart {:file [st/required]}}}
  [{:keys [user parameters] :as ctx}]
  (letfn [(store-photo [{:keys [filename tempfile] :as upload}]
            (let [filename (fs/name filename)
                  storage media/images-storage]
              (ds/save storage filename tempfile)))
          (assign-photo [path]
            (sv/novelty {:user user
                         :path (str path)
                         :type :update-profile-photo}))]
    (->> (get-in parameters [:multipart :file])
         (store-photo)
         (p/mapcat assign-photo)
         (p/map (constantly (http/no-content))))))




