;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2019-2020 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.services.mutations.files
  (:require
   [clojure.spec.alpha :as s]
   [datoteka.core :as fs]
   [promesa.core :as p]
   [uxbox.common.exceptions :as ex]
   [uxbox.common.pages :as cp]
   [uxbox.common.spec :as us]
   [uxbox.common.uuid :as uuid]
   [uxbox.config :as cfg]
   [uxbox.db :as db]
   [uxbox.services.mutations :as sm]
   [uxbox.services.mutations.projects :as proj]
   [uxbox.services.queries.files :as files]
   [uxbox.tasks :as tasks]
   [uxbox.util.blob :as blob]
   [uxbox.util.storage :as ust]
   [uxbox.util.time :as dt]))

;; --- Helpers & Specs

(s/def ::id ::us/uuid)
(s/def ::name ::us/string)
(s/def ::profile-id ::us/uuid)
(s/def ::project-id ::us/uuid)
(s/def ::url ::us/url)

;; --- Mutation: Create File

(declare create-file)
(declare create-page)

(s/def ::is-shared ::us/boolean)
(s/def ::create-file
  (s/keys :req-un [::profile-id ::name ::project-id ::is-shared]
          :opt-un [::id]))

(sm/defmutation ::create-file
  [{:keys [profile-id project-id] :as params}]
  (db/with-atomic [conn db/pool]
    (let [file (create-file conn params)
          page (create-page conn (assoc params :file-id (:id file)))]
      (assoc file :pages [(:id page)]))))

(defn- create-file-profile
  [conn {:keys [profile-id file-id] :as params}]
  (db/insert! conn :file-profile-rel
              {:profile-id profile-id
               :file-id file-id
               :is-owner true
               :is-admin true
               :can-edit true}))

(defn create-file
  [conn {:keys [id profile-id name project-id is-shared] :as params}]
  (let [id      (or id (uuid/next))
        file (db/insert! conn :file
                         {:id id
                          :project-id project-id
                          :name name
                          :is-shared is-shared})]
    (->> (assoc params :file-id id)
         (create-file-profile conn))
    file))

(defn create-page
  [conn {:keys [file-id] :as params}]
  (let [id  (uuid/next)]
    (db/insert! conn :page
                {:id id
                 :file-id file-id
                 :name "Page 1"
                 :ordering 1
                 :data (blob/encode cp/default-page-data)})))


;; --- Mutation: Rename File

(declare rename-file)

(s/def ::rename-file
  (s/keys :req-un [::profile-id ::name ::id]))

(sm/defmutation ::rename-file
  [{:keys [id profile-id] :as params}]
  (db/with-atomic [conn db/pool]
    (files/check-edition-permissions! conn profile-id id)
    (rename-file conn params)))

(defn- rename-file
  [conn {:keys [id name] :as params}]
  (db/update! conn :file
              {:name name}
              {:id id}))


;; --- Mutation: Set File shared

(declare set-file-shared)

(s/def ::set-file-shared
  (s/keys :req-un [::profile-id ::id ::is-shared]))

(sm/defmutation ::set-file-shared
  [{:keys [id profile-id] :as params}]
  (db/with-atomic [conn db/pool]
    (files/check-edition-permissions! conn profile-id id)
    (set-file-shared conn params)))

(defn- set-file-shared
  [conn {:keys [id is-shared] :as params}]
  (db/update! conn :file
              {:is-shared is-shared}
              {:id id}))


;; --- Mutation: Delete Project File

(declare mark-file-deleted)

(s/def ::delete-file
  (s/keys :req-un [::id ::profile-id]))

(sm/defmutation ::delete-file
  [{:keys [id profile-id] :as params}]
  (db/with-atomic [conn db/pool]
    (files/check-edition-permissions! conn profile-id id)

    ;; Schedule object deletion
    (tasks/submit! conn {:name "delete-object"
                         :delay cfg/default-deletion-delay
                         :props {:id id :type :file}})

    (mark-file-deleted conn params)))

(defn mark-file-deleted
  [conn {:keys [id] :as params}]
  (db/update! conn :file
              {:deleted-at (dt/now)}
              {:id id})
  nil)

