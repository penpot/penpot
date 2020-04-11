;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2019-2020 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.services.mutations.pages
  (:require
   [clojure.spec.alpha :as s]
   [promesa.core :as p]
   [uxbox.common.pages :as cp]
   [uxbox.common.exceptions :as ex]
   [uxbox.common.spec :as us]
   [uxbox.config :as cfg]
   [uxbox.db :as db]
   [uxbox.services.queries.files :as files]
   [uxbox.services.mutations :as sm]
   [uxbox.services.queries.pages :refer [decode-row]]
   [uxbox.services.util :as su]
   [uxbox.tasks :as tasks]
   [uxbox.util.blob :as blob]
   [uxbox.util.sql :as sql]
   [uxbox.common.uuid :as uuid]
   [vertx.eventbus :as ve]))

;; --- Helpers & Specs

(s/def ::id ::us/uuid)
(s/def ::name ::us/string)
(s/def ::data ::cp/data)
(s/def ::profile-id ::us/uuid)
(s/def ::project-id ::us/uuid)
(s/def ::ordering ::us/number)
(s/def ::file-id ::us/uuid)

;; --- Mutation: Create Page

(declare create-page)

(s/def ::create-page
  (s/keys :req-un [::profile-id ::file-id ::name ::ordering ::data]
          :opt-un [::id]))

(sm/defmutation ::create-page
  [{:keys [profile-id file-id] :as params}]
  (db/with-atomic [conn db/pool]
    (files/check-edition-permissions! conn profile-id file-id)
    (create-page conn params)))

(def ^:private sql:create-page
  "insert into page (id, file_id, name, ordering, data)
   values ($1, $2, $3, $4, $5)
   returning *")

(defn- create-page
  [conn {:keys [id file-id name ordering data] :as params}]
  (let [id   (or id (uuid/next))
        data (blob/encode data)]
    (-> (db/query-one conn [sql:create-page
                            id file-id name
                            ordering data])
        (p/then' decode-row))))



;; --- Mutation: Rename Page

(declare rename-page)
(declare select-page-for-update)

(s/def ::rename-page
  (s/keys :req-un [::id ::name ::profile-id]))

(sm/defmutation ::rename-page
  [{:keys [id name profile-id]}]
  (db/with-atomic [conn db/pool]
    (p/let [page (select-page-for-update conn id)]
      (files/check-edition-permissions! conn profile-id (:file-id page))
      (rename-page conn (assoc page :name name)))))

(def ^:private sql:select-page-for-update
  "select p.id, p.revn, p.file_id, p.data
     from page as p
    where p.id = $1
      and deleted_at is null
      for update;")

(defn- select-page-for-update
  [conn id]
  (-> (db/query-one conn [sql:select-page-for-update id])
      (p/then' su/raise-not-found-if-nil)))

(def ^:private sql:rename-page
  "update page
      set name = $2
    where id = $1
      and deleted_at is null")

(defn- rename-page
  [conn {:keys [id name] :as params}]
  (-> (db/query-one conn [sql:rename-page id name])
      (p/then su/constantly-nil)))



;; --- Mutation: Generate Share Token

(declare assign-page-share-token)

(s/def ::generate-page-share-token
  (s/keys :req-un [::id]))

(sm/defmutation ::generate-page-share-token
  [{:keys [id] :as params}]
  (let [token (-> (sodi.prng/random-bytes 16)
                  (sodi.util/bytes->b64s))]
    (db/with-atomic [conn db/pool]
      (assign-page-share-token conn id token))))

(def ^:private sql:update-page-share-token
  "update page set share_token = $2 where id = $1")

(defn- assign-page-share-token
  [conn id token]
  (-> (db/query-one conn [sql:update-page-share-token id token])
      (p/then (fn [_] {:id id :share-token token}))))



;; --- Mutation: Clear Share Token

(s/def ::clear-page-share-token
  (s/keys :req-un [::id]))

(sm/defmutation ::clear-page-share-token
  [{:keys [id] :as params}]
  (db/with-atomic [conn db/pool]
    (assign-page-share-token conn id nil)))



;; --- Mutation: Update Page

;; A generic, Changes based (granular) page update method.

(s/def ::changes
  (s/coll-of map? :kind vector?))

(s/def ::revn ::us/integer)
(s/def ::update-page
  (s/keys :req-un [::id ::profile-id ::revn ::changes]))

(declare update-page)
(declare retrieve-lagged-changes)
(declare update-page-data)
(declare insert-page-change)

(sm/defmutation ::update-page
  [{:keys [id profile-id] :as params}]
  (db/with-atomic [conn db/pool]
    (p/let [{:keys [file-id] :as page} (select-page-for-update conn id)]
      (files/check-edition-permissions! conn profile-id file-id)
      (update-page conn page params))))

(defn- update-page
  [conn page params]
  (when (> (:revn params)
           (:revn page))
    (ex/raise :type :validation
              :code :revn-conflict
              :hint "The incoming revision number is greater that stored version."
              :context {:incoming-revn (:revn params)
                        :stored-revn (:revn page)}))
  (let [changes  (:changes params)
        data (-> (:data page)
                 (blob/decode)
                 (cp/process-changes changes)
                 (blob/encode))

        page (assoc page
                    :data data
                    :revn (inc (:revn page))
                    :changes (blob/encode changes))]

    (-> (update-page-data conn page)
        (p/then (fn [_] (insert-page-change conn page)))
        (p/then (fn [s]
                  (let [topic (str "internal.uxbox.file." (:file-id page))]
                    (p/do! (ve/publish! uxbox.core/system topic
                                        {:type :page-change
                                         :profile-id (:profile-id params)
                                         :page-id (:page-id s)
                                         :revn (:revn s)
                                         :changes changes})
                           (retrieve-lagged-changes conn s params))))))))

(def ^:private sql:update-page-data
  "update page
      set revn = $1,
          data = $2
    where id = $3")

(defn- update-page-data
  [conn {:keys [id name revn data]}]
  (-> (db/query-one conn [sql:update-page-data revn data id])
      (p/then' su/constantly-nil)))

(def ^:private sql:insert-page-change
  "insert into page_change (id, page_id, revn, data, changes)
   values ($1, $2, $3, $4, $5)
   returning id, page_id, revn, changes")

(defn- insert-page-change
  [conn {:keys [revn data changes] :as page}]
  (let [id (uuid/next)
        page-id (:id page)]
    (db/query-one conn [sql:insert-page-change id
                        page-id revn data changes])))

(def ^:private sql:lagged-changes
  "select s.id, s.changes
     from page_change as s
    where s.page_id = $1
      and s.revn > $2
    order by s.created_at asc")

(defn- retrieve-lagged-changes
  [conn snapshot params]
  (-> (db/query conn [sql:lagged-changes (:id params) (:revn params)])
      (p/then (fn [rows]
                {:page-id (:id params)
                 :revn (:revn snapshot)
                 :changes (into [] (comp (map decode-row)
                                         (map :changes)
                                         (mapcat identity))
                                rows)}))))


;; --- Mutation: Delete Page

(declare mark-page-deleted)

(s/def ::delete-page
  (s/keys :req-un [::profile-id ::id]))

(sm/defmutation ::delete-page
  [{:keys [id profile-id]}]
  (db/with-atomic [conn db/pool]
    (p/let [page (select-page-for-update conn id)]
      (files/check-edition-permissions! conn profile-id (:file-id page))

      ;; Schedule object deletion
      (tasks/schedule! conn {:name "delete-object"
                             :delay cfg/default-deletion-delay
                             :props {:id id :type :page}})

      (mark-page-deleted conn id))))

(def ^:private sql:mark-page-deleted
  "update page
      set deleted_at = clock_timestamp()
    where id = $1
      and deleted_at is null")

(defn- mark-page-deleted
  [conn id]
  (-> (db/query-one conn [sql:mark-page-deleted id])
      (p/then su/constantly-nil)))
