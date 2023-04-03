;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.queries.projects
  (:require
   [app.common.spec :as us]
   [app.db :as db]
   [app.rpc.commands.projects :as projects]
   [app.rpc.commands.teams :as teams]
   [app.rpc.doc :as-alias doc]
   [app.util.services :as sv]
   [clojure.spec.alpha :as s]))

;; --- Query: Projects

(s/def ::team-id ::us/uuid)
(s/def ::profile-id ::us/uuid)
(s/def ::projects
  (s/keys :req-un [::profile-id ::team-id]))

(sv/defmethod ::projects
  {::doc/added "1.0"
   ::doc/deprecated "1.18"}
  [{:keys [pool]} {:keys [profile-id team-id]}]
  (with-open [conn (db/open pool)]
    (teams/check-read-permissions! conn profile-id team-id)
    (projects/get-projects conn profile-id team-id)))

;; --- Query: All projects

(s/def ::profile-id ::us/uuid)
(s/def ::all-projects
  (s/keys :req-un [::profile-id]))

(sv/defmethod ::all-projects
  {::doc/added "1.0"
   ::doc/deprecated "1.18"}
  [{:keys [pool]} {:keys [profile-id]}]
  (with-open [conn (db/open pool)]
    (projects/get-all-projects conn profile-id)))

;; --- Query: Project

(s/def ::id ::us/uuid)
(s/def ::project
  (s/keys :req-un [::profile-id ::id]))

(sv/defmethod ::project
  {::doc/added "1.0"
   ::doc/deprecated "1.18"}
  [{:keys [pool]} {:keys [profile-id id]}]
  (with-open [conn (db/open pool)]
    (let [project (db/get-by-id conn :project id)]
      (projects/check-read-permissions! conn profile-id id)
      project)))

