;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.services.mutations.colors
  (:require
   [clojure.spec.alpha :as s]
   [datoteka.core :as fs]
   [datoteka.storages :as ds]
   [promesa.core :as p]
   [promesa.exec :as px]
   [uxbox.common.exceptions :as ex]
   [uxbox.common.spec :as us]
   [uxbox.config :as cfg]
   [uxbox.db :as db]
   [uxbox.media :as media]
   [uxbox.images :as images]
   [uxbox.tasks :as tasks]
   [uxbox.services.queries.colors :refer [decode-row]]
   [uxbox.services.mutations :as sm]
   [uxbox.services.util :as su]
   [uxbox.util.blob :as blob]
   [uxbox.util.uuid :as uuid]
   [vertx.util :as vu]))

;; --- Helpers & Specs

(s/def ::id ::us/uuid)
(s/def ::name ::us/string)
(s/def ::profile-id ::us/uuid)
(s/def ::collection-id ::us/uuid)
(s/def ::content ::us/string)


;; --- Mutation: Create Collection

(declare create-color-collection)

(s/def ::create-color-collection
  (s/keys :req-un [::profile-id ::name]
          :opt-un [::id]))

(sm/defmutation ::create-color-collection
  [{:keys [id profile-id name] :as params}]
  (db/with-atomic [conn db/pool]
    (create-color-collection conn params)))

(def ^:private sql:create-color-collection
  "insert into color_collection (id, profile_id, name)
   values ($1, $2, $3)
   returning *;")

(defn- create-color-collection
  [conn {:keys [id profile-id name] :as params}]
  (let [id (or id (uuid/next))]
    (db/query-one conn [sql:create-color-collection id profile-id name])))



;; --- Collection Permissions Check

(def ^:private sql:select-collection
  "select id, profile_id
     from color_collection
    where id=$1 and deleted_at is null
      for update")

(defn- check-collection-edition-permissions!
  [conn profile-id coll-id]
  (p/let [coll (-> (db/query-one conn [sql:select-collection coll-id])
                   (p/then' su/raise-not-found-if-nil))]
    (when (not= (:profile-id coll) profile-id)
      (ex/raise :type :validation
                :code :not-authorized))))


;; --- Mutation: Update Collection

(def ^:private sql:rename-collection
  "update color_collection
      set name = $2
    where id = $1
   returning *")

(s/def ::rename-color-collection
  (s/keys :req-un [::profile-id ::name ::id]))

(sm/defmutation ::rename-color-collection
  [{:keys [id profile-id name] :as params}]
  (db/with-atomic [conn db/pool]
    (check-collection-edition-permissions! conn profile-id id)
    (db/query-one conn [sql:rename-collection id name])))


;; --- Copy Color

;; (declare create-color)

;; (defn- retrieve-color
;;   [conn {:keys [profile-id id]}]
;;   (let [sql "select * from color
;;               where id = $1
;;                 and deleted_at is null
;;                 and (profile_id = $2 or
;;                      profile_id = '00000000-0000-0000-0000-000000000000'::uuid)"]
;;   (-> (db/query-one conn [sql id profile-id])
;;       (p/then' su/raise-not-found-if-nil))))

;; (s/def ::copy-color
;;   (s/keys :req-un [:us/id ::collection-id ::profile-id]))

;; (sm/defmutation ::copy-color
;;   [{:keys [profile-id id collection-id] :as params}]
;;   (db/with-atomic [conn db/pool]
;;     (-> (retrieve-color conn {:profile-id profile-id :id id})
;;         (p/then (fn [color]
;;                   (let [color (-> (dissoc color :id)
;;                                  (assoc :collection-id collection-id))]
;;                     (create-color conn color)))))))

;; --- Delete Collection

(def ^:private sql:mark-collection-deleted
  "update color_collection
      set deleted_at = clock_timestamp()
    where id = $1
   returning id")

(s/def ::delete-color-collection
  (s/keys :req-un [::profile-id ::id]))

(sm/defmutation ::delete-color-collection
  [{:keys [profile-id id] :as params}]
  (db/with-atomic [conn db/pool]
    (check-collection-edition-permissions! conn profile-id id)
    (-> (db/query-one conn [sql:mark-collection-deleted id])
        (p/then' su/constantly-nil))))



;; --- Mutation: Create Color (Upload)

(declare create-color)

(s/def ::create-color
  (s/keys :req-un [::profile-id ::name ::content ::collection-id]
          :opt-un [::id]))

(sm/defmutation ::create-color
  [{:keys [profile-id collection-id] :as params}]
  (db/with-atomic [conn db/pool]
    (check-collection-edition-permissions! conn profile-id collection-id)
    (create-color conn params)))

(def ^:private sql:create-color
  "insert into color (id, profile_id, name, collection_id, content)
   values ($1, $2, $3, $4, $5) returning *")

(defn create-color
  [conn {:keys [id profile-id name collection-id content]}]
  (let [id (or id (uuid/next))]
    (-> (db/query-one conn [sql:create-color id profile-id name collection-id content])
        (p/then' decode-row))))



;; --- Mutation: Update Color

(def ^:private sql:update-color
  "update color
      set name = $3,
          collection_id = $4
    where id = $1
      and profile_id = $2
   returning *")

(s/def ::update-color
  (s/keys :req-un [::id ::profile-id ::name ::collection-id]))

(sm/defmutation ::update-color
  [{:keys [id name profile-id collection-id] :as params}]
  (db/with-atomic [conn db/pool]
    (check-collection-edition-permissions! conn profile-id collection-id)
    (-> (db/query-one db/pool [sql:update-color id profile-id  name collection-id])
        (p/then' su/raise-not-found-if-nil))))



;; --- Mutation: Delete Color

(def ^:private sql:mark-color-deleted
  "update color
      set deleted_at = clock_timestamp()
    where id = $1
      and profile_id = $2
   returning id")

(s/def ::delete-color
  (s/keys :req-un [::profile-id ::id]))

(sm/defmutation ::delete-color
  [{:keys [id profile-id] :as params}]
  (db/with-atomic [conn db/pool]
    (-> (db/query-one conn [sql:mark-color-deleted id profile-id])
        (p/then' su/raise-not-found-if-nil))

    ;; Schedule object deletion
    (tasks/schedule! conn {:name "delete-object"
                           :delay cfg/default-deletion-delay
                           :props {:id id :type :color}})

    nil))


