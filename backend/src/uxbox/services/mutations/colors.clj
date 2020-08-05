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


;; ;; --- Mutation: Create Library
;;
;; (declare create-library)
;;
;; (s/def ::create-color-library
;;   (s/keys :req-un [::profile-id ::team-id ::name]
;;           :opt-un [::id]))
;;
;; (sm/defmutation ::create-color-library
;;   [{:keys [profile-id team-id] :as params}]
;;   (db/with-atomic [conn db/pool]
;;     (teams/check-edition-permissions! conn profile-id team-id)
;;     (create-library conn params)))
;;
;; (defn create-library
;;   [conn {:keys [id team-id name]}]
;;   (let [id (or id (uuid/next))]
;;     (db/insert! conn :color-library
;;                 {:id id
;;                  :team-id team-id
;;                  :name name})))
;;

;; ;; --- Mutation: Rename Library
;;
;; (declare select-library-for-update)
;; (declare rename-library)
;;
;; (s/def ::rename-color-library
;;   (s/keys :req-un [::profile-id ::name ::id]))
;;
;; (sm/defmutation ::rename-color-library
;;   [{:keys [id profile-id name] :as params}]
;;   (db/with-atomic [conn db/pool]
;;     (let [lib (select-library-for-update conn id)]
;;       (teams/check-edition-permissions! conn profile-id (:team-id lib))
;;       (rename-library conn id name))))
;;
;; (def ^:private sql:select-library-for-update
;;   "select l.*
;;      from color_library as l
;;     where l.id = $1
;;       for update")
;;
;; (def ^:private sql:rename-library
;;   "update color_library
;;       set name = $2
;;     where id = $1")
;;
;; (defn- select-library-for-update
;;   [conn id]
;;   (db/get-by-id conn :color-library id {:for-update true}))
;;
;; (defn- rename-library
;;   [conn id name]
;;   (db/update! conn :color-library
;;               {:name name}
;;               {:id id}))


;; ;; --- Delete Library
;;
;; (declare delete-library)
;;
;; (s/def ::delete-color-library
;;   (s/keys :req-un [::profile-id ::id]))
;;
;; (sm/defmutation ::delete-color-library
;;   [{:keys [id profile-id] :as params}]
;;   (db/with-atomic [conn db/pool]
;;     (let [lib (select-library-for-update conn id)]
;;       (teams/check-edition-permissions! conn profile-id (:team-id lib))
;;
;;       ;; Schedule object deletion
;;       (tasks/submit! conn {:name "delete-object"
;;                            :delay cfg/default-deletion-delay
;;                            :props {:id id :type :color-library}})
;;
;;       (db/update! conn :color-library
;;                   {:deleted-at (dt/now)}
;;                   {:id id})
;;       nil)))


;; --- Mutation: Create Color

(declare select-file-for-update)
(declare create-color)

(s/def ::create-color
  (s/keys :req-un [::profile-id ::name ::content ::file-id]
          :opt-un [::id]))

(sm/defmutation ::create-color
  [{:keys [profile-id file-id] :as params}]
  (db/with-atomic [conn db/pool]
    (let [file (select-file-for-update conn file-id)]
      (teams/check-edition-permissions! conn profile-id (:team-id file))
      (create-color conn params))))

(def ^:private sql:create-color
  "insert into color (id, name, file_id, content)
   values ($1, $2, $3, $4) returning *")

(defn create-color
  [conn {:keys [id name file-id content]}]
  (let [id (or id (uuid/next))]
    (db/insert! conn :color {:id id
                             :name name
                             :file-id file-id
                             :content content})))

(def ^:private sql:select-file-for-update
  "select file.*,
          project.team_id as team_id
     from file
    inner join project on (project.id = file.project_id)
    where file.id = ?
      for update of file")

(defn- select-file-for-update
  [conn id]
  (let [row (db/exec-one! conn [sql:select-file-for-update id])]
    (when-not row
      (ex/raise :type :not-found))
    row))


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
          p.team_id as team_id
     from color as c
    inner join file as f on f.id = c.file_id
    inner join project as p on p.id = f.project_id
    where c.id = ?
      for update of c")

(defn- select-color-for-update
  [conn id]
  (let [row (db/exec-one! conn [sql:select-color-for-update id])]
    (when-not row
      (ex/raise :type :not-found))
    row))


;; --- Mutation: Update Color

(s/def ::update-color
  (s/keys :req-un [::profile-id ::id ::content]))

(sm/defmutation ::update-color
  [{:keys [profile-id id content] :as params}]
  (db/with-atomic [conn db/pool]
    (let [clr (select-color-for-update conn id)
          ;; IMPORTANT: if the previous name was equal to the hex content,
          ;; we must rename it in addition to changing the value.
          new-name (if (= (:name clr) (:content clr))
                     content
                     (:name clr))]
      (teams/check-edition-permissions! conn profile-id (:team-id clr))
      (db/update! conn :color
                  {:name new-name
                   :content content}
                  {:id id}))))

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
      (tasks/submit! conn {:name "delete-object"
                           :delay cfg/default-deletion-delay
                           :props {:id id :type :color}})

      (db/update! conn :color
                  {:deleted-at (dt/now)}
                  {:id id})
      nil)))
