;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2019-2020 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.services.mutations.icons
  (:require
   [clojure.spec.alpha :as s]
   [promesa.core :as p]
   [uxbox.common.spec :as us]
   [uxbox.config :as cfg]
   [uxbox.db :as db]
   [uxbox.services.mutations :as sm]
   [uxbox.services.queries.icons :refer [decode-row]]
   [uxbox.services.queries.teams :as teams]
   [uxbox.services.util :as su]
   [uxbox.tasks :as tasks]
   [uxbox.util.blob :as blob]
   [uxbox.common.uuid :as uuid]))

;; --- Helpers & Specs

(s/def ::height ::us/integer)
(s/def ::id ::us/uuid)
(s/def ::library-id ::us/uuid)
(s/def ::name ::us/string)
(s/def ::profile-id ::us/uuid)
(s/def ::team-id ::us/uuid)
(s/def ::width ::us/integer)

(s/def ::view-box
  (s/and (s/coll-of number?)
         #(= 4 (count %))
         vector?))

(s/def ::content ::us/string)
(s/def ::mimetype ::us/string)

(s/def ::metadata
  (s/keys :opt-un [::width ::height ::view-box ::mimetype]))



;; --- Mutation: Create Library

(declare create-library)

(s/def ::create-icon-library
  (s/keys :req-un [::profile-id ::team-id ::name]
          :opt-un [::id]))

(sm/defmutation ::create-icon-library
  [{:keys [profile-id team-id id name] :as params}]
  (db/with-atomic [conn db/pool]
    (teams/check-edition-permissions! conn profile-id team-id)
    (create-library conn params)))

(def ^:private sql:create-library
  "insert into icon_library (id, team_id, name)
   values ($1, $2, $3)
   returning *;")

(defn create-library
  [conn {:keys [team-id id name] :as params}]
  (let [id (or id (uuid/next))]
    (db/query-one conn [sql:create-library id team-id name])))



;; --- Mutation: Rename Library

(declare select-library-for-update)
(declare rename-library)

(s/def ::rename-icon-library
  (s/keys :req-un [::profile-id ::name ::id]))

(sm/defmutation ::rename-icon-library
  [{:keys [id profile-id name] :as params}]
  (db/with-atomic [conn db/pool]
    (p/let [lib (select-library-for-update conn id)]
      (teams/check-edition-permissions! conn profile-id (:team-id lib))
      (rename-library conn id name))))

(def ^:private sql:select-library-for-update
  "select l.*
     from icon_library as l
    where l.id = $1
      for update")

(def ^:private sql:rename-library
  "update icon_library
      set name = $2
    where id = $1")

(defn- select-library-for-update
  [conn id]
  (-> (db/query-one conn [sql:select-library-for-update id])
      (p/then' su/raise-not-found-if-nil)))

(defn- rename-library
  [conn id name]
  (-> (db/query-one conn [sql:rename-library id name])
      (p/then' su/constantly-nil)))



;; ;; --- Copy Icon

;; (declare create-icon)

;; (defn- retrieve-icon
;;   [conn {:keys [profile-id id]}]
;;   (let [sql "select * from icon
;;               where id = $1
;;                 and deleted_at is null
;;                 and (profile_id = $2 or
;;                      profile_id = '00000000-0000-0000-0000-000000000000'::uuid)"]
;;   (-> (db/query-one conn [sql id profile-id])
;;       (p/then' su/raise-not-found-if-nil))))

;; (s/def ::copy-icon
;;   (s/keys :req-un [:us/id ::library-id ::profile-id]))

;; (sm/defmutation ::copy-icon
;;   [{:keys [profile-id id library-id] :as params}]
;;   (db/with-atomic [conn db/pool]
;;     (-> (retrieve-icon conn {:profile-id profile-id :id id})
;;         (p/then (fn [icon]
;;                   (let [icon (-> (dissoc icon :id)
;;                                  (assoc :library-id library-id))]
;;                     (create-icon conn icon)))))))


;; --- Mutation: Delete Library

(declare delete-library)

(s/def ::delete-icon-library
  (s/keys :req-un [::profile-id ::id]))

(sm/defmutation ::delete-icon-library
  [{:keys [profile-id id] :as params}]
  (db/with-atomic [conn db/pool]
    (p/let [lib (select-library-for-update conn id)]
      (teams/check-edition-permissions! conn profile-id (:team-id lib))

      ;; Schedule object deletion
      (tasks/schedule! conn {:name "delete-object"
                             :delay cfg/default-deletion-delay
                             :props {:id id :type :icon-library}})

      (delete-library conn id))))

(def ^:private sql:mark-library-deleted
  "update icon_library
      set deleted_at = clock_timestamp()
    where id = $1
   returning id")

(defn- delete-library
  [conn id]
  (-> (db/query-one conn [sql:mark-library-deleted id])
      (p/then' su/constantly-nil)))



;; --- Mutation: Create Icon (Upload)

(declare create-icon)

(s/def ::create-icon
  (s/keys :req-un [::profile-id ::name ::metadata ::content ::library-id]
          :opt-un [::id]))

(sm/defmutation ::create-icon
  [{:keys [profile-id library-id] :as params}]
  (db/with-atomic [conn db/pool]
    (p/let [lib (select-library-for-update conn library-id)]
      (teams/check-edition-permissions! conn profile-id (:team-id lib))
      (create-icon conn params))))

(def ^:private sql:create-icon
  "insert into icon (id, name, library_id, content, metadata)
   values ($1, $2, $3, $4, $5) returning *")

(defn create-icon
  [conn {:keys [id name library-id metadata content]}]
  (let [id (or id (uuid/next))]
    (-> (db/query-one conn [sql:create-icon id name library-id
                            content (blob/encode metadata)])
        (p/then' decode-row))))



;; --- Mutation: Rename Icon

(declare select-icon-for-update)
(declare rename-icon)

(s/def ::rename-icon
  (s/keys :req-un [::id ::profile-id ::name]))

(sm/defmutation ::rename-icon
  [{:keys [id profile-id name] :as params}]
  (db/with-atomic [conn db/pool]
    (p/let [clr (select-icon-for-update conn id)]
      (teams/check-edition-permissions! conn profile-id (:team-id clr))
      (rename-icon conn id name))))

(def ^:private sql:select-icon-for-update
  "select i.*,
          lib.team_id as team_id
     from icon as i
    inner join icon_library as lib on (lib.id = i.library_id)
    where i.id = $1
      for update")

(def ^:private sql:rename-icon
  "update icon
      set name = $2
    where id = $1")

(defn- select-icon-for-update
  [conn id]
  (-> (db/query-one conn [sql:select-icon-for-update id])
      (p/then' su/raise-not-found-if-nil)))

(defn- rename-icon
  [conn id name]
  (-> (db/query-one conn [sql:rename-icon id name])
      (p/then' su/constantly-nil)))



;; --- Mutation: Delete Icon

(declare delete-icon)

(s/def ::delete-icon
  (s/keys :req-un [::profile-id ::id]))

(sm/defmutation ::delete-icon
  [{:keys [id profile-id] :as params}]
  (db/with-atomic [conn db/pool]
    (p/let [icn (select-icon-for-update conn id)]
      (teams/check-edition-permissions! conn profile-id (:team-id icn))

      ;; Schedule object deletion
      (tasks/schedule! conn {:name "delete-object"
                             :delay cfg/default-deletion-delay
                             :props {:id id :type :icon}})

      (delete-icon conn id))))

(def ^:private sql:mark-icon-deleted
  "update icon
      set deleted_at = clock_timestamp()
    where id = $1")

(defn- delete-icon
  [conn id]
  (-> (db/query-one conn [sql:mark-icon-deleted id])
      (p/then' su/constantly-nil)))
