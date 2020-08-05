;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2019-2020 Andrey Antukh <niwi@niwi.nz>

;; (ns uxbox.services.mutations.icons
;;   (:require
;;    [clojure.spec.alpha :as s]
;;    [uxbox.common.exceptions :as ex]
;;    [uxbox.common.spec :as us]
;;    [uxbox.common.uuid :as uuid]
;;    [uxbox.config :as cfg]
;;    [uxbox.db :as db]
;;    [uxbox.services.mutations :as sm]
;;    [uxbox.services.queries.icons :refer [decode-row]]
;;    [uxbox.services.queries.teams :as teams]
;;    [uxbox.tasks :as tasks]
;;    [uxbox.util.blob :as blob]
;;    [uxbox.util.time :as dt]))
;;
;; ;; --- Helpers & Specs
;;
;; (s/def ::height ::us/integer)
;; (s/def ::id ::us/uuid)
;; (s/def ::library-id ::us/uuid)
;; (s/def ::name ::us/string)
;; (s/def ::profile-id ::us/uuid)
;; (s/def ::team-id ::us/uuid)
;; (s/def ::width ::us/integer)
;;
;; (s/def ::view-box
;;   (s/and (s/coll-of number?)
;;          #(= 4 (count %))
;;          vector?))
;;
;; (s/def ::content ::us/string)
;; (s/def ::mimetype ::us/string)
;;
;; (s/def ::metadata
;;   (s/keys :opt-un [::width ::height ::view-box ::mimetype]))
;;
;;
;; ;; --- Mutation: Create Library
;;
;; (declare create-library)
;;
;; (s/def ::create-icon-library
;;   (s/keys :req-un [::profile-id ::team-id ::name]
;;           :opt-un [::id]))
;;
;; (sm/defmutation ::create-icon-library
;;   [{:keys [profile-id team-id id name] :as params}]
;;   (db/with-atomic [conn db/pool]
;;     (teams/check-edition-permissions! conn profile-id team-id)
;;     (create-library conn params)))
;;
;; (def ^:private sql:create-library
;;   "insert into icon_library (id, team_id, name)
;;    values ($1, $2, $3)
;;    returning *;")
;;
;; (defn create-library
;;   [conn {:keys [team-id id name] :as params}]
;;   (let [id (or id (uuid/next))]
;;     (db/insert! conn :icon-library
;;                 {:id id
;;                  :team-id team-id
;;                  :name name})))
;;
;;
;; ;; --- Mutation: Rename Library
;;
;; (declare select-library-for-update)
;; (declare rename-library)
;;
;; (s/def ::rename-icon-library
;;   (s/keys :req-un [::profile-id ::name ::id]))
;;
;; (sm/defmutation ::rename-icon-library
;;   [{:keys [id profile-id name] :as params}]
;;   (db/with-atomic [conn db/pool]
;;     (let [lib (select-library-for-update conn id)]
;;       (teams/check-edition-permissions! conn profile-id (:team-id lib))
;;       (rename-library conn id name))))
;;
;; (defn- select-library-for-update
;;   [conn id]
;;   (db/get-by-id conn :icon-library id {:for-update true}))
;;
;; (defn- rename-library
;;   [conn id name]
;;   (db/update! conn :icon-library
;;               {:name name}
;;               {:id id}))
;;
;; ;; --- Mutation: Delete Library
;;
;; (declare delete-library)
;;
;; (s/def ::delete-icon-library
;;   (s/keys :req-un [::profile-id ::id]))
;;
;; (sm/defmutation ::delete-icon-library
;;   [{:keys [profile-id id] :as params}]
;;   (db/with-atomic [conn db/pool]
;;     (let [lib (select-library-for-update conn id)]
;;       (teams/check-edition-permissions! conn profile-id (:team-id lib))
;;
;;       ;; Schedule object deletion
;;       (tasks/submit! conn {:name "delete-object"
;;                            :delay cfg/default-deletion-delay
;;                            :props {:id id :type :icon-library}})
;;
;;       (db/update! conn :icon-library
;;                   {:deleted-at (dt/now)}
;;                   {:id id})
;;       nil)))
;;
;;
;; ;; --- Mutation: Create Icon (Upload)
;;
;; (declare create-icon)
;;
;; (s/def ::create-icon
;;   (s/keys :req-un [::profile-id ::name ::metadata ::content ::library-id]
;;           :opt-un [::id]))
;;
;; (sm/defmutation ::create-icon
;;   [{:keys [profile-id library-id] :as params}]
;;   (db/with-atomic [conn db/pool]
;;     (let [lib (select-library-for-update conn library-id)]
;;       (teams/check-edition-permissions! conn profile-id (:team-id lib))
;;       (create-icon conn params))))
;;
;; (defn create-icon
;;   [conn {:keys [id name library-id metadata content]}]
;;   (let [id (or id (uuid/next))]
;;     (-> (db/insert! conn :icon
;;                     {:id id
;;                      :name name
;;                      :library-id library-id
;;                      :content content
;;                      :metadata (blob/encode metadata)})
;;         (decode-row))))
;;
;;
;; ;; --- Mutation: Rename Icon
;;
;; (declare select-icon-for-update)
;; (declare rename-icon)
;;
;; (s/def ::rename-icon
;;   (s/keys :req-un [::id ::profile-id ::name]))
;;
;; (sm/defmutation ::rename-icon
;;   [{:keys [id profile-id name] :as params}]
;;   (db/with-atomic [conn db/pool]
;;     (let [icon (select-icon-for-update conn id)]
;;       (teams/check-edition-permissions! conn profile-id (:team-id icon))
;;       (db/update! conn :icon
;;                   {:name name}
;;                   {:id id}))))
;;
;; (def ^:private
;;   sql:select-icon-for-update
;;   "select i.*,
;;           lib.team_id as team_id
;;      from icon as i
;;     inner join icon_library as lib on (lib.id = i.library_id)
;;     where i.id = ?
;;       for update")
;;
;; (defn- select-icon-for-update
;;   [conn id]
;;   (let [row (db/exec-one! conn [sql:select-icon-for-update id])]
;;     (when-not row
;;       (ex/raise :type :not-found))
;;     row))
;;
;;
;; ;; --- Mutation: Delete Icon
;;
;; (declare delete-icon)
;;
;; (s/def ::delete-icon
;;   (s/keys :req-un [::profile-id ::id]))
;;
;; (sm/defmutation ::delete-icon
;;   [{:keys [id profile-id] :as params}]
;;   (db/with-atomic [conn db/pool]
;;     (let [icn (select-icon-for-update conn id)]
;;       (teams/check-edition-permissions! conn profile-id (:team-id icn))
;;
;;       ;; Schedule object deletion
;;       (tasks/submit! conn {:name "delete-object"
;;                            :delay cfg/default-deletion-delay
;;                            :props {:id id :type :icon}})
;;
;;       (db/update! conn :icon
;;                   {:deleted-at (dt/now)}
;;                   {:id id})
;;       nil)))
