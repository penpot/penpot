;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.services.mutations.project-pages
  (:require
   [clojure.spec.alpha :as s]
   [promesa.core :as p]
   [uxbox.common.pages :as cp]
   [uxbox.db :as db]
   [uxbox.services.mutations :as sm]
   [uxbox.services.mutations.project-files :as files]
   [uxbox.services.queries.project-pages :refer [decode-row]]
   [uxbox.services.util :as su]
   [uxbox.util.blob :as blob]
   [uxbox.util.exceptions :as ex]
   [uxbox.util.spec :as us]
   [uxbox.util.sql :as sql]
   [uxbox.util.uuid :as uuid]
   [vertx.eventbus :as ve]))

;; --- Helpers & Specs

(s/def ::id ::us/uuid)
(s/def ::name ::us/string)
(s/def ::data ::cp/data)
(s/def ::user ::us/uuid)
(s/def ::project-id ::us/uuid)
(s/def ::metadata ::cp/metadata)
(s/def ::ordering ::us/number)

;; --- Mutation: Create Page

(declare create-page)

(s/def ::create-project-page
  (s/keys :req-un [::user ::file-id ::name ::ordering ::metadata ::data]
          :opt-un [::id]))

(sm/defmutation ::create-project-page
  [{:keys [user file-id] :as params}]
  (db/with-atomic [conn db/pool]
    (files/check-edition-permissions! conn user file-id)
    (create-page conn params)))

(defn create-page
  [conn {:keys [id user file-id name ordering data metadata] :as params}]
  (let [sql "insert into project_pages (id, user_id, file_id, name,
                                        ordering, data, metadata, version)
             values ($1, $2, $3, $4, $5, $6, $7, 0)
             returning *"
        id   (or id (uuid/next))
        data (blob/encode data)
        mdata (blob/encode metadata)]
    (-> (db/query-one conn [sql id user file-id name ordering data mdata])
        (p/then' decode-row))))

;; --- Mutation: Update Page Data

(declare select-page-for-update)
(declare update-page-data)
(declare insert-page-snapshot)

(s/def ::update-project-page-data
  (s/keys :req-un [::id ::user ::data]))

(sm/defmutation ::update-project-page-data
  [{:keys [id user data] :as params}]
  (db/with-atomic [conn db/pool]
    (p/let [{:keys [version file-id]} (select-page-for-update conn id)]
      (files/check-edition-permissions! conn user file-id)
      (let [data (blob/encode data)
            version (inc version)
            params (assoc params :id id :data data :version version)]
        (p/do! (update-page-data conn params)
               (insert-page-snapshot conn params)
               (select-keys params [:id :version]))))))

(defn- select-page-for-update
  [conn id]
  (let [sql "select p.id, p.version, p.file_id, p.data
               from project_pages as p
              where p.id = $1
                and deleted_at is null
                 for update;"]
    (-> (db/query-one conn [sql id])
        (p/then' su/raise-not-found-if-nil))))

(defn- update-page-data
  [conn {:keys [id name version data]}]
  (let [sql "update project_pages
                set version = $1,
                    data = $2
              where id = $3"]
    (-> (db/query-one conn [sql version data id])
        (p/then' su/constantly-nil))))

(defn- insert-page-snapshot
  [conn {:keys [user-id id version data operations]}]
  (let [sql "insert into project_page_snapshots (user_id, page_id, version, data, operations)
             values ($1, $2, $3, $4, $5)
             returning id, page_id, user_id, version, operations"]
    (db/query-one conn [sql user-id id version data operations])))

;; --- Mutation: Rename Page

(declare rename-page)

(s/def ::rename-project-page
  (s/keys :req-un [::id ::name ::user]))

(sm/defmutation ::rename-project-page
  [{:keys [id name user]}]
  (db/with-atomic [conn db/pool]
    (p/let [page (select-page-for-update conn id)]
      (files/check-edition-permissions! conn user (:file-id page))
      (rename-page conn (assoc page :name name)))))

(defn- rename-page
  [conn {:keys [id name] :as params}]
  (let [sql "update project_pages
                set name = $2
              where id = $1
                and deleted_at is null"]
    (-> (db/query-one db/pool [sql id name])
        (p/then su/constantly-nil))))

;; --- Mutation: Update Page

;; A generic, Ops based (granular) page update method.

(s/def ::operations
  (s/coll-of vector? :kind vector?))

(s/def ::update-project-page
  (s/keys :opt-un [::id ::user ::version ::operations]))

(declare update-project-page)
(declare retrieve-lagged-operations)

(sm/defmutation ::update-project-page
  [{:keys [id user] :as params}]
  (db/with-atomic [conn db/pool]
    (p/let [{:keys [file-id] :as page} (select-page-for-update conn id)]
      (files/check-edition-permissions! conn user file-id)
      (update-project-page conn page params))))

(defn- update-project-page
  [conn page params]
  (when (> (:version params)
           (:version page))
    (ex/raise :type :validation
              :code :version-conflict
              :hint "The incoming version is greater that stored version."
              :context {:incoming-version (:version params)
                        :stored-version (:version page)}))
  (let [ops  (:operations params)
        data (-> (:data page)
                  (blob/decode)
                  (cp/process-ops ops)
                  (blob/encode))

        page (assoc page
                    :user-id (:user params)
                    :data data
                    :version (inc (:version page))
                    :operations (blob/encode ops))]

    (-> (update-page-data conn page)
        (p/then (fn [_] (insert-page-snapshot conn page)))
        (p/then (fn [s]
                  (let [topic (str "internal.uxbox.file." (:file-id page))]
                    (p/do! (ve/publish! uxbox.core/system topic {:type :page-snapshot
                                                                 :user-id (:user-id s)
                                                                 :page-id (:page-id s)
                                                                 :version (:version s)
                                                                 :operations ops})
                           (retrieve-lagged-operations conn s params))))))))

(su/defstr sql:lagged-snapshots
  "select s.id, s.operations
     from project_page_snapshots as s
    where s.page_id = $1
      and s.version > $2")

(defn- retrieve-lagged-operations
  [conn snapshot params]
  (let [sql sql:lagged-snapshots]
    (-> (db/query conn [sql (:id params) (:version params) #_(:id snapshot)])
        (p/then (fn [rows]
                  {:page-id (:id params)
                   :version (:version snapshot)
                   :operations (into [] (comp (map decode-row)
                                              (map :operations)
                                              (mapcat identity))
                                     rows)})))))

;; --- Mutation: Delete Page

(declare delete-page)

(s/def ::delete-project-page
  (s/keys :req-un [::user ::id]))

(sm/defmutation ::delete-project-page
  [{:keys [id user]}]
  (db/with-atomic [conn db/pool]
    (p/let [page (select-page-for-update conn id)]
      (files/check-edition-permissions! conn user (:file-id page))
      (delete-page conn id))))

(su/defstr sql:delete-page
  "update project_pages
      set deleted_at = clock_timestamp()
    where id = $1
      and deleted_at is null")

(defn- delete-page
  [conn id]
  (let [sql sql:delete-page]
    (-> (db/query-one conn [sql id])
        (p/then su/constantly-nil))))

;; --- Update Page History

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
