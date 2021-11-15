;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

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

(def ^:private sql:default-profile-team
  "select t.id, name
     from team as t
    inner join team_profile_rel as tp on (tp.team_id = t.id)
    where tp.profile_id = ?
      and tp.is_owner is true
      and t.is_default is true")

(def ^:private sql:default-profile-project
  "select p.id, name
     from project as p
    inner join project_profile_rel as tp on (tp.project_id = p.id)
    where tp.profile_id = ?
      and tp.is_owner is true
      and p.is_default is true
      and p.team_id = ?")

(defn retrieve-additional-data
  [conn id]
  (let [team    (db/exec-one! conn [sql:default-profile-team id])
        project (db/exec-one! conn [sql:default-profile-project id (:id team)])]
    {:default-team-id (:id team)
     :default-project-id (:id project)}))

(defn populate-additional-data
  [conn profile]
  (merge profile (retrieve-additional-data conn (:id profile))))

(defn- filter-profile-props
  [props]
  (into {} (filter (fn [[k _]] (simple-ident? k))) props))

(defn decode-profile-row
  [{:keys [props] :as row}]
  (cond-> row
    (db/pgobject? props "jsonb")
    (assoc :props (db/decode-transit-pgobject props))))

(defn retrieve-profile-data
  [conn id]
  (-> (db/get-by-id conn :profile id)
      (decode-profile-row)))

(defn retrieve-profile
  [conn id]
  (let [profile (->> (retrieve-profile-data conn id)
                     (strip-private-attrs)
                     (populate-additional-data conn))]
    (update profile :props filter-profile-props)))

(def ^:private sql:profile-by-email
  "select p.* from profile as p
    where p.email = ?
      and (p.deleted_at is null or
           p.deleted_at > now())")

(defn retrieve-profile-data-by-email
  [conn email]
  (ex/ignoring
   (db/exec-one! conn [sql:profile-by-email (str/lower email)])))

;; --- Attrs Helpers

(defn strip-private-attrs
  "Only selects a publicly visible profile attrs."
  [row]
  (dissoc row :password :deleted-at))
