;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.services.queries.profile
  (:require
   [clojure.spec.alpha :as s]
   [promesa.core :as p]
   [promesa.exec :as px]
   [uxbox.common.exceptions :as ex]
   [uxbox.common.spec :as us]
   [uxbox.db :as db]
   [uxbox.images :as images]
   [uxbox.services.queries :as sq]
   [uxbox.services.util :as su]
   [uxbox.util.uuid :as uuid]
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
    (db/with-atomic [conn db/pool]
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
    where tpr.profile_id = $1
      and tpr.is_owner is true
      and t.is_default is true
    union all
   select p.id
     from project as p
    inner join project_profile_rel as tpr on (tpr.project_id = p.id)
    where tpr.profile_id = $1
      and tpr.is_owner is true
      and p.is_default is true")

(defn retrieve-additional-data
  [conn id]
  (-> (db/query conn [sql:default-team-and-project id])
      (p/then' (fn [[team project]]
                 {:default-team-id (:id team)
                  :default-project-id (:id project)}))))

(defn retrieve-profile-data
  [conn id]
  (let [sql "select * from profile where id=$1 and deleted_at is null"]
    (db/query-one conn [sql id])))

(defn retrieve-profile
  [conn id]
  (p/let [prof (-> (retrieve-profile-data conn id)
                   (p/then' su/raise-not-found-if-nil)
                   (p/then' strip-private-attrs)
                   (p/then' #(images/resolve-media-uris % [:photo :photo-uri])))
          addt (retrieve-additional-data conn id)]
    (merge prof addt)))

;; --- Attrs Helpers

(defn strip-private-attrs
  "Only selects a publicy visible profile attrs."
  [profile]
  (select-keys profile [:id :fullname :lang :email :created-at :photo :theme]))
