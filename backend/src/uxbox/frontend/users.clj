;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.frontend.users
  (:require [clojure.spec :as s]
            [promesa.core :as p]
            [catacumba.http :as http]
            [storages.core :as st]
            [storages.util :as path]
            [uxbox.media :as media]
            [uxbox.images :as images]
            [uxbox.util.spec :as us]
            [uxbox.services :as sv]
            [uxbox.services.users :as svu]
            [uxbox.util.response :refer (rsp)]
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

;; --- Retrieve Profile

(defn retrieve-profile
  [{user :identity}]
  (let [message {:user user
                 :type :retrieve-profile}]
    (->> (sv/query message)
         (p/map resolve-thumbnail)
         (p/map #(http/ok (rsp %))))))

;; --- Update Profile

(s/def ::fullname string?)
(s/def ::metadata any?)
(s/def ::update-profile
  (s/keys :req-un [::us/id ::us/username ::us/email
                   ::fullname ::metadata]))

(defn update-profile
  [{user :identity data :data}]
  (let [data (us/conform ::update-profile data)
        message (assoc data
                       :type :update-profile
                       :user user)]
    (->> (sv/novelty message)
         (p/map resolve-thumbnail)
         (p/map #(http/ok (rsp %))))))

;; --- Update Password

(s/def ::old-password ::us/password)
(s/def ::update-password
  (s/keys :req-un [::us/password ::old-password]))

(defn update-password
  [{user :identity data :data}]
  (let [data (us/conform ::update-password data)
        message (assoc data
                      :type :update-profile-password
                      :user user)]
    (-> (sv/novelty message)
        (p/then #(http/ok (rsp %))))))

;; --- Update Profile Photo

(s/def ::file ::us/uploaded-file)
(s/def ::update-photo (s/keys :req-un [::file]))

(defn update-photo
  [{user :identity data :data}]
  (letfn [(store-photo [file]
            (let [filename (path/base-name file)
                  storage media/images-storage]
              (st/save storage filename file)))
          (assign-photo [path]
            (sv/novelty {:user user
                         :path (str path)
                         :type :update-profile-photo}))
          (create-response [_]
            (http/no-content))]
    (let [{:keys [file]} (us/conform ::update-photo data)]
      (->> (store-photo file)
           (p/mapcat assign-photo)
           (p/map create-response)))))

;; --- Register User

(s/def ::register
  (s/keys :req-un [::us/username ::us/email ::us/password ::fullname]))

(defn register-user
  [{data :data}]
  (let [data (us/conform ::register data)
        message (assoc data :type :register-profile)]
    (->> (sv/novelty message)
         (p/map #(http/ok (rsp %))))))


;; --- Request Password Recovery

;; FIXME: rename for consistency

(s/def ::request-recovery
  (s/keys :req-un [::us/username]))

(defn request-recovery
  [{data :data}]
  (let [data (us/conform ::request-recovery data)
        message (assoc data :type :request-profile-password-recovery)]
    (->> (sv/novelty message)
         (p/map (fn [_] (http/no-content))))))

;; --- Password Recovery

;; FIXME: rename for consistency

(s/def ::token string?)
(s/def ::password-recovery
  (s/keys :req-un [::token ::us/password]))

(defn recover-password
  [{data :data}]
  (let [data (us/conform ::password-recovery data)
        message (assoc data :type :recover-profile-password)]
    (->> (sv/novelty message)
         (p/map (fn [_] (http/no-content))))))

;; --- Valiadate Recovery Token

(defn validate-recovery-token
  [{params :route-params}]
  (let [message {:type :validate-profile-password-recovery-token
                 :token (:token params)}]
    (->> (sv/query message)
         (p/map (fn [v]
                  (if v
                    (http/no-content)
                    (http/not-found "")))))))
