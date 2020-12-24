;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.rpc.queries.profile
  (:require
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.util.services :as sv]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]))

;; --- Helpers & Specs

(declare strip-private-attrs)

(s/def ::email ::us/email)
(s/def ::fullname ::us/string)
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

(sv/defmethod ::profile {:auth false}
  [{:keys [pool] :as cfg} {:keys [profile-id] :as params}]
  (if profile-id
    (retrieve-profile pool profile-id)
    {:id uuid/zero
     :fullname "Anonymous User"}))

;; NOTE: this query make the assumption that union all preserves the
;; order so the first id will always be the team id and the second the
;; project_id; this is a postgresql behavior because UNION ALL works
;; like APPEND operation.

(def ^:private sql:default-team-and-project
  "select t.id
     from team as t
    inner join team_profile_rel as tp on (tp.team_id = t.id)
    where tp.profile_id = ?
      and tp.is_owner is true
      and t.is_default is true
    union all
   select p.id
     from project as p
    inner join project_profile_rel as tp on (tp.project_id = p.id)
    where tp.profile_id = ?
      and tp.is_owner is true
      and p.is_default is true")

(defn retrieve-additional-data
  [conn id]
  (let [[team project] (db/exec! conn [sql:default-team-and-project id id])]
    {:default-team-id (:id team)
     :default-project-id (:id project)}))

(defn decode-profile-row
  [{:keys [props] :as row}]
  (cond-> row
    (db/pgobject? props) (assoc :props (db/decode-transit-pgobject props))))

(defn retrieve-profile-data
  [conn id]
  (-> (db/get-by-id conn :profile id)
      (decode-profile-row)))

(defn retrieve-profile
  [conn id]
  (let [profile (some-> (retrieve-profile-data conn id)
                        (strip-private-attrs)
                        (merge (retrieve-additional-data conn id)))]
    (when (nil? profile)
      (ex/raise :type :not-found
                :hint "Object doest not exists."))

    profile))


(def sql:profile-by-email
  "select * from profile
    where email=?
      and deleted_at is null")

(defn retrieve-profile-data-by-email
  [conn email]
  (let [email (str/lower email)]
    (-> (db/exec-one! conn [sql:profile-by-email email])
        (decode-profile-row))))


;; --- Attrs Helpers

(defn strip-private-attrs
  "Only selects a publicy visible profile attrs."
  [row]
  (dissoc row :password :deleted-at))
