;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.services.queries.profile
  (:require
   [clojure.spec.alpha :as s]
   [uxbox.common.exceptions :as ex]
   [uxbox.common.spec :as us]
   [uxbox.db :as db]
   [uxbox.media :as media]
   [uxbox.services.queries :as sq]
   [uxbox.common.uuid :as uuid]
   [uxbox.util.blob :as blob]))

;; --- Helpers & Specs

(declare strip-private-attrs)

(s/def ::email ::us/email)
(s/def ::fullname ::us/string)
(s/def ::metadata any?)
(s/def ::old-password ::us/string)
(s/def ::password ::us/string)
(s/def ::path ::us/string)
(s/def ::user ::us/uuid)
(s/def ::profile-id ::us/uuid)
(s/def ::theme ::us/string)

;; --- Query: Profile (own)

(declare retrieve-profile)
(declare retrieve-additional-data)

(s/def ::profile
  (s/keys :opt-un [::profile-id]))

(sq/defquery ::profile
  [{:keys [profile-id] :as params}]
  (if profile-id
    (with-open [conn (db/open)]
      (retrieve-profile conn profile-id))
    {:id uuid/zero
     :fullname "Anonymous User"}))

;; NOTE: this query make the assumption that union all preserves the
;; order so the first id will always be the team id and the second the
;; project_id; this is a postgresql behavior because UNION ALL works
;; like APPEND operation.

(def ^:private sql:default-team-and-project
  "select t.id
     from team as t
    inner join team_profile_rel as tpr on (tpr.team_id = t.id)
    where tpr.profile_id = ?
      and tpr.is_owner is true
      and t.is_default is true
    union all
   select p.id
     from project as p
    inner join project_profile_rel as tpr on (tpr.project_id = p.id)
    where tpr.profile_id = ?
      and tpr.is_owner is true
      and p.is_default is true")

(defn retrieve-additional-data
  [conn id]
  (let [[team project] (db/exec! conn [sql:default-team-and-project id id])]
    {:default-team-id (:id team)
     :default-project-id (:id project)}))

(defn retrieve-profile-data
  [conn id]
  (db/get-by-id conn :profile id))

(defn retrieve-profile
  [conn id]
  (let [profile (some-> (retrieve-profile-data conn id)
                        (media/resolve-urls :photo :photo-uri)
                        (strip-private-attrs)
                        (merge (retrieve-additional-data conn id)))]
    (when (nil? profile)
      (ex/raise :type :not-found
                :hint "Object doest not exists."))

    profile))

;; --- Attrs Helpers

(defn strip-private-attrs
  "Only selects a publicy visible profile attrs."
  [row]
  (dissoc row :password :deleted-at))
