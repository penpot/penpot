;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2019-2020 Andrey Antukh <niwi@niwi.nz>

(ns app.services.mutations.pages
  (:require
   [clojure.spec.alpha :as s]
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.pages :as cp]
   [app.common.pages-migrations :as pmg]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.config :as cfg]
   [app.db :as db]
   [app.services.mutations :as sm]
   [app.services.queries.files :as files]
   [app.services.queries.pages :refer [decode-row]]
   [app.tasks :as tasks]
   [app.redis :as redis]
   [app.util.blob :as blob]
   [app.util.time :as dt]
   [app.util.transit :as t]))

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

(defn- create-page
  [conn {:keys [id file-id name ordering data] :as params}]
  (let [id   (or id (uuid/next))
        data (blob/encode data)]
    (-> (db/insert! conn :page
                    {:id id
                     :file-id file-id
                     :name name
                     :ordering ordering
                     :data data})
        (decode-row))))


;; --- Mutation: Rename Page

(declare rename-page)
(declare select-page-for-update)

(s/def ::rename-page
  (s/keys :req-un [::id ::name ::profile-id]))

(sm/defmutation ::rename-page
  [{:keys [id name profile-id]}]
  (db/with-atomic [conn db/pool]
    (let [page (select-page-for-update conn id)]
      (files/check-edition-permissions! conn profile-id (:file-id page))
      (rename-page conn (assoc page :name name)))))

(defn- select-page-for-update
  [conn id]
  (db/get-by-id conn :page id {:for-update true}))

(defn- rename-page
  [conn {:keys [id name] :as params}]
  (db/update! conn :page
              {:name name}
              {:id id}))


;; --- Mutation: Sort Pages

(s/def ::page-ids (s/every ::us/uuid :kind vector?))
(s/def ::reorder-pages
  (s/keys :req-un [::profile-id ::file-id ::page-ids]))

(declare update-page-ordering)

(sm/defmutation ::reorder-pages
  [{:keys [profile-id file-id page-ids]}]
  (db/with-atomic [conn db/pool]
    (run! #(update-page-ordering conn file-id %)
          (d/enumerate page-ids))
    nil))

(defn- update-page-ordering
  [conn file-id [ordering page-id]]
  (db/update! conn :page
              {:ordering ordering}
              {:file-id file-id
               :id page-id}))


;; --- Mutation: Generate Share Token

(declare assign-page-share-token)

(s/def ::generate-page-share-token
  (s/keys :req-un [::id]))

(sm/defmutation ::generate-page-share-token
  [{:keys [id] :as params}]
  (let [token (-> (sodi.prng/random-bytes 16)
                  (sodi.util/bytes->b64s))]
    (db/with-atomic [conn db/pool]
      (db/update! conn :page
                  {:share-token token}
                  {:id id}))))


;; --- Mutation: Clear Share Token

(s/def ::clear-page-share-token
  (s/keys :req-un [::id]))

(sm/defmutation ::clear-page-share-token
  [{:keys [id] :as params}]
  (db/with-atomic [conn db/pool]
      (db/update! conn :page
                  {:share-token nil}
                  {:id id})))



;; --- Mutation: Update Page

;; A generic, Changes based (granular) page update method.

(s/def ::changes
  (s/coll-of map? :kind vector?))

(s/def ::session-id ::us/uuid)
(s/def ::revn ::us/integer)
(s/def ::update-page
  (s/keys :req-un [::id ::session-id ::profile-id ::revn ::changes]))

(declare update-page)
(declare retrieve-lagged-changes)
(declare insert-page-change!)

(sm/defmutation ::update-page
  [{:keys [id profile-id] :as params}]
  (db/with-atomic [conn db/pool]
    (let [{:keys [file-id] :as page} (select-page-for-update conn id)]
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
  (let [sid      (:session-id params)
        changes  (:changes params)
        page     (-> page
                     (update :data blob/decode)
                     (update :data pmg/migrate-data)
                     (update :data cp/process-changes changes)
                     (update :data blob/encode)
                     (update :revn inc)
                     (assoc :changes (blob/encode changes)
                            :session-id sid))

        chng     (insert-page-change! conn page)
        msg      {:type :page-change
                  :profile-id (:profile-id params)
                  :page-id (:id page)
                  :session-id sid
                  :revn (:revn page)
                  :changes changes}]

    @(redis/run! :publish {:channel (str (:file-id page))
                           :message (t/encode-str msg)})

    (db/update! conn :page
                {:revn (:revn page)
                 :data (:data page)}
                {:id (:id page)})

    (retrieve-lagged-changes conn chng params)))

(defn- insert-page-change!
  [conn {:keys [revn data changes session-id] :as page}]
  (let [id (uuid/next)
        page-id (:id page)]
    (db/insert! conn :page-change
                {:id id
                 :session-id session-id
                 :page-id page-id
                 :revn revn
                 :data data
                 :changes changes})))

(def ^:private
  sql:lagged-changes
  "select s.id, s.revn, s.page_id,
          s.session_id, s.changes
     from page_change as s
    where s.page_id = ?
      and s.revn > ?
    order by s.created_at asc")

(defn- retrieve-lagged-changes
  [conn snapshot params]
  (->> (db/exec! conn [sql:lagged-changes (:id params) (:revn params)])
       (mapv decode-row)))

;; --- Mutation: Delete Page

(declare mark-page-deleted)

(s/def ::delete-page
  (s/keys :req-un [::profile-id ::id]))

(sm/defmutation ::delete-page
  [{:keys [id profile-id]}]
  (db/with-atomic [conn db/pool]
    (let [page (select-page-for-update conn id)]
      (files/check-edition-permissions! conn profile-id (:file-id page))

      ;; Schedule object deletion
      (tasks/submit! conn {:name "delete-object"
                           :delay cfg/default-deletion-delay
                           :props {:id id :type :page}})

      (db/update! conn :page
                  {:deleted-at (dt/now)}
                  {:id id})
      nil)))
