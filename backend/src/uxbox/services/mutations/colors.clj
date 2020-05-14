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
   [uxbox.common.exceptions :as ex]
   [uxbox.common.spec :as us]
   [uxbox.common.uuid :as uuid]
   [uxbox.config :as cfg]
   [uxbox.db :as db]
   [uxbox.services.mutations :as sm]
   [uxbox.services.queries.teams :as teams]
   [uxbox.tasks :as tasks]
   [uxbox.util.time :as dt]))

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

(defn create-library
  [conn {:keys [id team-id name]}]
  (let [id (or id (uuid/next))]
    (db/insert! conn :color-library
                {:id id
                 :team-id team-id
                 :name name})))


;; --- Mutation: Rename Library

(declare select-library-for-update)
(declare rename-library)

(s/def ::rename-color-library
  (s/keys :req-un [::profile-id ::name ::id]))

(sm/defmutation ::rename-color-library
  [{:keys [id profile-id name] :as params}]
  (db/with-atomic [conn db/pool]
    (let [lib (select-library-for-update conn id)]
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
  (db/get-by-id conn :color-library id {:for-update true}))

(defn- rename-library
  [conn id name]
  (db/update! conn :color-library
              {:name name}
              {:id id}))


;; --- Delete Library

(declare delete-library)

(s/def ::delete-color-library
  (s/keys :req-un [::profile-id ::id]))

(sm/defmutation ::delete-color-library
  [{:keys [id profile-id] :as params}]
  (db/with-atomic [conn db/pool]
    (let [lib (select-library-for-update conn id)]
      (teams/check-edition-permissions! conn profile-id (:team-id lib))

      ;; Schedule object deletion
      (tasks/schedule! conn {:name "delete-object"
                             :delay cfg/default-deletion-delay
                             :props {:id id :type :color-library}})

      (db/update! conn :color-library
                  {:deleted-at (dt/now)}
                  {:id id})
      nil)))


;; --- Mutation: Create Color (Upload)

(declare create-color)

(s/def ::create-color
  (s/keys :req-un [::profile-id ::name ::content ::library-id]
          :opt-un [::id]))

(sm/defmutation ::create-color
  [{:keys [profile-id library-id] :as params}]
  (db/with-atomic [conn db/pool]
    (let [lib (select-library-for-update conn library-id)]
      (teams/check-edition-permissions! conn profile-id (:team-id lib))
      (create-color conn params))))

(def ^:private sql:create-color
  "insert into color (id, name, library_id, content)
   values ($1, $2, $3, $4) returning *")

(defn create-color
  [conn {:keys [id name library-id content]}]
  (let [id (or id (uuid/next))]
    (db/insert! conn :color {:id id
                             :name name
                             :library-id library-id
                             :content content})))


;; --- Mutation: Rename Color

(declare select-color-for-update)

(s/def ::rename-color
  (s/keys :req-un [::id ::profile-id ::name]))

(sm/defmutation ::rename-color
  [{:keys [id profile-id name] :as params}]
  (db/with-atomic [conn db/pool]
    (let [clr (select-color-for-update conn id)]
      (teams/check-edition-permissions! conn profile-id (:team-id clr))
      (db/update! conn :color
                  {:name name}
                  {:id id}))))

(def ^:private sql:select-color-for-update
  "select c.*,
          lib.team_id as team_id
     from color as c
    inner join color_library as lib on (lib.id = c.library_id)
    where c.id = ?
      for update of c")

(defn- select-color-for-update
  [conn id]
  (let [row (db/exec-one! conn [sql:select-color-for-update id])]
    (when-not row
      (ex/raise :type :not-found))
    row))


;; --- Delete Color

(declare delete-color)

(s/def ::delete-color
  (s/keys :req-un [::id ::profile-id]))

(sm/defmutation ::delete-color
  [{:keys [profile-id id] :as params}]
  (db/with-atomic [conn db/pool]
    (let [clr (select-color-for-update conn id)]
      (teams/check-edition-permissions! conn profile-id (:team-id clr))

      ;; Schedule object deletion
      (tasks/schedule! conn {:name "delete-object"
                             :delay cfg/default-deletion-delay
                             :props {:id id :type :color}})

      (db/update! conn :color
                  {:deleted-at (dt/now)}
                  {:id id})
      nil)))
