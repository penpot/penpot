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
   [promesa.core :as p]
   [uxbox.common.spec :as us]
   [uxbox.config :as cfg]
   [uxbox.db :as db]
   [uxbox.tasks :as tasks]
   [uxbox.services.queries.teams :as teams]
   [uxbox.services.mutations :as sm]
   [uxbox.services.util :as su]
   [uxbox.util.uuid :as uuid]))

;; --- Helpers & Specs

(s/def ::id ::us/uuid)
(s/def ::name ::us/string)
(s/def ::profile-id ::us/uuid)
(s/def ::team-id ::us/uuid)
(s/def ::library-id ::us/uuid)
(s/def ::content ::us/string)


;; --- Mutation: Create Library

(declare create-library)

(s/def ::create-color-library
  (s/keys :req-un [::profile-id ::team-id ::name]
          :opt-un [::id]))

(sm/defmutation ::create-color-library
  [{:keys [profile-id team-id] :as params}]
  (db/with-atomic [conn db/pool]
    (teams/check-edition-permissions! conn profile-id team-id)
    (create-library conn params)))

(def ^:private sql:create-library
  "insert into color_library (id, team_id, name)
   values ($1, $2, $3)
   returning *;")

(defn- create-library
  [conn {:keys [id team-id name]}]
  (let [id (or id (uuid/next))]
    (db/query-one conn [sql:create-library id team-id name])))


;; --- Mutation: Rename Library

(declare select-library-for-update)
(declare rename-library)

(s/def ::rename-color-library
  (s/keys :req-un [::profile-id ::name ::id]))

(sm/defmutation ::rename-color-library
  [{:keys [id profile-id name] :as params}]
  (db/with-atomic [conn db/pool]
    (p/let [lib (select-library-for-update conn id)]
      (teams/check-edition-permissions! conn profile-id (:team-id lib))
      (rename-library conn id name))))

(def ^:private sql:select-library-for-update
  "select l.*
     from color_library as l
    where l.id = $1
      for update")

(def ^:private sql:rename-library
  "update color_library
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
;;   (s/keys :req-un [:us/id ::library-id ::profile-id]))

;; (sm/defmutation ::copy-color
;;   [{:keys [profile-id id library-id] :as params}]
;;   (db/with-atomic [conn db/pool]
;;     (-> (retrieve-color conn {:profile-id profile-id :id id})
;;         (p/then (fn [color]
;;                   (let [color (-> (dissoc color :id)
;;                                  (assoc :library-id library-id))]
;;                     (create-color conn color)))))))



;; --- Delete Library

(declare delete-library)

(s/def ::delete-color-library
  (s/keys :req-un [::profile-id ::id]))

(sm/defmutation ::delete-color-library
  [{:keys [id profile-id] :as params}]
  (db/with-atomic [conn db/pool]
    (p/let [lib (select-library-for-update conn id)]
      (teams/check-edition-permissions! conn profile-id (:team-id lib))

      ;; Schedule object deletion
      (tasks/schedule! conn {:name "delete-object"
                             :delay cfg/default-deletion-delay
                             :props {:id id :type :color-library}})

      (delete-library conn id))))

(def ^:private sql:mark-library-deleted
  "update color_library
      set deleted_at = clock_timestamp()
    where id = $1")

(defn- delete-library
  [conn id]
  (-> (db/query-one conn [sql:mark-library-deleted id])
      (p/then' su/constantly-nil)))



;; --- Mutation: Create Color (Upload)

(declare create-color)

(s/def ::create-color
  (s/keys :req-un [::profile-id ::name ::content ::library-id]
          :opt-un [::id]))

(sm/defmutation ::create-color
  [{:keys [profile-id library-id] :as params}]
  (db/with-atomic [conn db/pool]
    (p/let [lib (select-library-for-update conn library-id)]
      (teams/check-edition-permissions! conn profile-id (:team-id lib))
      (create-color conn params))))

(def ^:private sql:create-color
  "insert into color (id, name, library_id, content)
   values ($1, $2, $3, $4) returning *")

(defn create-color
  [conn {:keys [id name library-id content]}]
  (let [id (or id (uuid/next))]
    (db/query-one conn [sql:create-color id name library-id content])))



;; --- Mutation: Rename Color

(declare select-color-for-update)
(declare rename-color)

(s/def ::rename-color
  (s/keys :req-un [::id ::profile-id ::name]))

(sm/defmutation ::rename-color
  [{:keys [id profile-id name] :as params}]
  (db/with-atomic [conn db/pool]
    (p/let [clr (select-color-for-update conn id)]
      (teams/check-edition-permissions! conn profile-id (:team-id clr))
      (rename-color conn id name))))

(def ^:private sql:select-color-for-update
  "select c.*,
          lib.team_id as team_id
     from color as c
    inner join color_library as lib on (lib.id = c.library_id)
    where c.id = $1
      for update of c")

(def ^:private sql:rename-color
  "update color
      set name = $2
    where id = $1")

(defn- select-color-for-update
  [conn id]
  (-> (db/query-one conn [sql:select-color-for-update id])
      (p/then' su/raise-not-found-if-nil)))

(defn- rename-color
  [conn id name]
  (-> (db/query-one conn [sql:rename-color id name])
      (p/then' su/constantly-nil)))



;; --- Delete Color

(declare delete-color)

(s/def ::delete-color
  (s/keys :req-un [::id ::profile-id]))

(sm/defmutation ::delete-color
  [{:keys [profile-id id] :as params}]
  (db/with-atomic [conn db/pool]
    (p/let [clr (select-color-for-update conn id)]
      (teams/check-edition-permissions! conn profile-id (:team-id clr))

      ;; Schedule object deletion
      (tasks/schedule! conn {:name "delete-object"
                             :delay cfg/default-deletion-delay
                             :props {:id id :type :color}})

      (delete-color conn id))))

(def ^:private sql:mark-color-deleted
  "update color
      set deleted_at = clock_timestamp()
    where id = $1")

(defn- delete-color
  [conn id]
  (-> (db/query-one conn [sql:mark-color-deleted id])
      (p/then' su/constantly-nil)))
