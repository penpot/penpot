;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.services.mutations.pages
  (:require
   [clojure.spec.alpha :as s]
   [promesa.core :as p]
   [uxbox.db :as db]
   [uxbox.util.spec :as us]
   [uxbox.services.mutations :as sm]
   [uxbox.services.util :as su]
   [uxbox.services.queries.pages :refer [decode-row]]
   [uxbox.util.sql :as sql]
   [uxbox.util.blob :as blob]
   [uxbox.util.uuid :as uuid]))

;; --- Helpers & Specs

;; TODO: validate `:data` and `:metadata`

(s/def ::id ::us/uuid)
(s/def ::name ::us/string)
(s/def ::data any?)
(s/def ::user ::us/uuid)
(s/def ::project-id ::us/uuid)
(s/def ::metadata any?)
(s/def ::ordering ::us/number)

;; --- Mutation: Create Page

(declare create-page)

(s/def ::create-page
  (s/keys :req-un [::data ::user ::project-id ::name ::metadata]
          :opt-un [::id]))

(sm/defmutation ::create-page
  [params]
  (create-page db/pool params))

(defn create-page
  [conn {:keys [id user project-id name ordering data metadata] :as params}]
  (let [sql "insert into pages (id, user_id, project_id, name,
                                ordering, data, metadata, version)
             values ($1, $2, $3, $4, $5, $6, $7, 0)
             returning *"
        id   (or id (uuid/next))
        data (blob/encode data)
        mdata (blob/encode metadata)]
    (-> (db/query-one db/pool [sql id user project-id name ordering data mdata])
        (p/then' decode-row))))

;; --- Mutation: Update Page

(s/def ::update-page
  (s/keys :req-un [::data ::user ::project-id ::name ::data ::metadata ::id]))

(letfn [(select-for-update [conn id]
          (let [sql "select p.id, p.version
                       from pages as p
                      where p.id = $1
                        and deleted_at is null
                        for update;"]
            (-> (db/query-one conn [sql id])
                (p/then' su/raise-not-found-if-nil))))

        (update-page [conn {:keys [id name version data metadata user]}]
          (let [sql "update pages
                        set name = $1,
                            version = $2,
                            data = $3,
                            metadata = $4
                      where id = $5
                        and user_id = $6"]
            (-> (db/query-one conn [sql name version data metadata id user])
                (p/then' su/constantly-nil))))

        (update-history [conn {:keys [user id version data metadata]}]
          (let [sql "insert into pages_history (user_id, page_id, version, data, metadata)
                     values ($1, $2, $3, $4, $5)"]
            (-> (db/query-one conn [sql user id version data metadata])
                (p/then' su/constantly-nil))))]

  (sm/defmutation ::update-page
    [{:keys [id data metadata] :as params}]
    (db/with-atomic [conn db/pool]
      (-> (select-for-update conn id)
          (p/then (fn [{:keys [id version]}]
                    (let [data (blob/encode data)
                          mdata (blob/encode metadata)
                          version (inc version)
                          params (assoc params
                                        :id id
                                        :version version
                                        :data data
                                        :metadata mdata)]
                      (p/do! (update-page conn params)
                             (update-history conn params)
                             (select-keys params [:id :version])))))))))

;; --- Mutation: Rename Page

(s/def ::rename-page
  (s/keys :req-un [::id ::name ::user]))

(sm/defmutation ::rename-page
  [{:keys [id name user]}]
  (let [sql "update pages
                set name = $3
              where id = $1
                and user_id = $2
                and deleted_at is null"]
    (-> (db/query-one db/pool [sql id user name])
        (p/then su/constantly-nil))))

;; --- Mutation: Update Page Metadata

(s/def ::update-page-metadata
  (s/keys :req-un [::user ::project-id ::name ::metadata ::id]))

(sm/defmutation ::update-page-metadata
  [{:keys [id user project-id name metadata]}]
  (let [sql "update pages
                set name = $3,
                    metadata = $4
              where id = $1
                and user_id = $2
                and deleted_at is null
             returning *"
        mdata (blob/encode metadata)]
    (-> (db/query-one db/pool [sql id user name mdata])
        (p/then' decode-row))))

;; --- Mutation: Delete Page

(s/def ::delete-page
  (s/keys :req-un [::user ::id]))

(sm/defmutation ::delete-page
  [{:keys [id user]}]
  (let [sql "update pages
                set deleted_at = clock_timestamp()
              where id = $1
                and user_id = $2
                and deleted_at is null
             returning id"]
    (-> (db/query-one db/pool [sql id user])
        (p/then su/raise-not-found-if-nil)
        (p/then su/constantly-nil))))

;; ;; --- Update Page History

;; (defn update-page-history
;;   [conn {:keys [user id label pinned]}]
;;   (let [sqlv (sql/update-page-history {:user user
;;                                        :id id
;;                                        :label label
;;                                        :pinned pinned})]
;;     (some-> (db/fetch-one conn sqlv)
;;             (decode-row))))

;; (s/def ::label ::us/string)
;; (s/def ::update-page-history
;;   (s/keys :req-un [::user ::id ::pinned ::label]))

;; (sm/defmutation :update-page-history
;;   {:doc "Update page history"
;;    :spec ::update-page-history}
;;   [params]
;;   (with-open [conn (db/connection)]
;;     (update-page-history conn params)))
