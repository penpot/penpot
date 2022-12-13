;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.queries.teams
  (:require
   [app.db :as db]
   [app.rpc.commands.teams :as cmd.teams]
   [app.rpc.doc :as-alias doc]
   [app.util.services :as sv]
   [clojure.spec.alpha :as s]))

;; --- Query: Teams

(s/def ::teams ::cmd.teams/get-teams)

(sv/defmethod ::teams
  {::doc/added "1.0"
   ::doc/deprecated "1.17"}
  [{:keys [pool] :as cfg} {:keys [profile-id]}]
  (with-open [conn (db/open pool)]
    (cmd.teams/retrieve-teams conn profile-id)))

;; --- Query: Team (by ID)

(s/def ::team ::cmd.teams/get-team)

(sv/defmethod ::team
  {::doc/added "1.0"
   ::doc/deprecated "1.17"}
  [{:keys [pool] :as cfg} {:keys [profile-id id]}]
  (with-open [conn (db/open pool)]
    (cmd.teams/retrieve-team conn profile-id id)))

;; --- Query: Team Members

(s/def ::team-members ::cmd.teams/get-team-members)

(sv/defmethod ::team-members
  {::doc/added "1.0"
   ::doc/deprecated "1.17"}
  [{:keys [pool] :as cfg} {:keys [profile-id team-id]}]
  (with-open [conn (db/open pool)]
    (cmd.teams/check-read-permissions! conn profile-id team-id)
    (cmd.teams/retrieve-team-members conn team-id)))

;; --- Query: Team Users
(s/def ::team-users ::cmd.teams/get-team-users)

(sv/defmethod ::team-users
  {::doc/added "1.0"
   ::doc/deprecated "1.17"}
  [{:keys [pool] :as cfg} {:keys [profile-id team-id file-id]}]
  (with-open [conn (db/open pool)]
    (if team-id
      (do
        (cmd.teams/check-read-permissions! conn profile-id team-id)
        (cmd.teams/retrieve-users conn team-id))
      (let [{team-id :id} (cmd.teams/retrieve-team-for-file conn file-id)]
        (cmd.teams/check-read-permissions! conn profile-id team-id)
        (cmd.teams/retrieve-users conn team-id)))))

;; --- Query: Team Stats

(s/def ::team-stats ::cmd.teams/get-team-stats)

(sv/defmethod ::team-stats
  {::doc/added "1.0"
   ::doc/deprecated "1.17"}
  [{:keys [pool] :as cfg} {:keys [profile-id team-id]}]
  (with-open [conn (db/open pool)]
    (cmd.teams/check-read-permissions! conn profile-id team-id)
    (cmd.teams/retrieve-team-stats conn team-id)))

;; --- Query: Team invitations

(s/def ::team-invitations ::cmd.teams/get-team-invitations)

(sv/defmethod ::team-invitations
  {::doc/added "1.0"
   ::doc/deprecated "1.17"}
  [{:keys [pool] :as cfg} {:keys [profile-id team-id]}]
  (with-open [conn (db/open pool)]
    (cmd.teams/check-read-permissions! conn profile-id team-id)
    (cmd.teams/get-team-invitations conn team-id)))
