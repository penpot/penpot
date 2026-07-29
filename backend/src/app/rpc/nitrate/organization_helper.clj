;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.nitrate.organization-helper
  "Shared Nitrate organization query helpers."
  (:require
   [app.db :as db]
   [app.nitrate :as nitrate]))

(def ^:private sql:get-org-invitations
  "SELECT DISTINCT ON (email_to)
          ti.id,
          ti.org_id AS organization_id,
          ti.email_to AS email,
          ti.created_at AS sent_at,
          p.fullname AS name,
          p.id AS profile_id,
          p.photo_id
     FROM team_invitation AS ti
LEFT JOIN profile AS p
       ON p.email = ti.email_to
      AND p.deleted_at IS NULL
    WHERE ti.valid_until >= now()
      AND (ti.org_id = ? OR ti.team_id = ANY(?))
    ORDER BY ti.email_to, ti.valid_until DESC, ti.created_at DESC;")

(def ^:private sql:get-team-invitation-emails
  "SELECT DISTINCT ON (email_to)
          ti.email_to AS email
     FROM team_invitation AS ti
    WHERE ti.team_id = ?
      AND ti.valid_until >= now()
    ORDER BY ti.email_to, ti.valid_until DESC, ti.created_at DESC;")

(defn get-org-team-ids
  "Return team ids for an organization.

  Accepts either `cfg` and `organization-id` (fetches the org summary from
  Nitrate) or an already-resolved org summary map."
  ([cfg organization-id]
   (get-org-team-ids (nitrate/call cfg :get-org-summary {:organization-id organization-id})))
  ([org-summary]
   (->> (:teams org-summary)
        (map :id)
        (filter uuid?)
        (vec))))

(defn get-org-invitations
  "Fetch valid org-level and team-level invitations for an organization."
  [conn organization-id team-ids]
  (let [ids-array (db/create-array conn "uuid" team-ids)]
    (db/exec! conn [sql:get-org-invitations organization-id ids-array])))

(defn get-team-invitation-emails
  "Return distinct valid team invitation recipient emails."
  [conn team-id]
  (db/exec! conn [sql:get-team-invitation-emails team-id]))
