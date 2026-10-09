;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.features.object-cascade
  "One description of the deletion cascade, walked in both directions.

  Deleting stamps the earliest date down the whole tree (profile →
  teams → projects → files → rows): live rows take the operation
  date, rows carrying a later date are brought forward, and rows
  carrying an earlier date keep it, so a later parent delete never
  postpones their purge. Restoring clears dates back to nil.

  The walk is always recursive. Restoring an object also restores
  its ancestor chain up to the team (chain rows only, never
  siblings, never profiles); a team without a live owner refuses
  the restore instead of leaving content nobody can manage. Two
  one-way steps kept from the original code: deleting unshares the
  file and absorbs its libraries (irreversible: a restored file
  does not become shared again), while restoring resets the
  media-trimmed flag."
  (:require
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.time :as ct]
   [app.db :as db]
   [app.db.sql :as-alias sql]))

(def ^:dynamic *team-deletion*
  "True while walking under a team deletion, so shared libraries
  absorbed at the team level are not absorbed file by file."
  false)

(def ^:private file-row-tables
  [:file-change
   :file-data
   :file-media-object
   :file-thumbnail
   :file-tagged-object-thumbnail])

(def ^:private sql:owned-team-ids
  "SELECT t.id
     FROM team AS t
     JOIN team_profile_rel AS tp ON (tp.team_id = t.id)
    WHERE tp.profile_id = ?
      AND tp.is_owner IS TRUE")

(defn- child-teams
  [conn profile-id]
  (db/exec! conn [sql:owned-team-ids profile-id]))

(defn- child-projects
  [conn team-id]
  (db/query conn :project {:team-id team-id} {::sql/columns [:id]}))

(defn- child-files
  [conn project-id]
  (db/query conn :file {:project-id project-id} {::sql/columns [:id]}))

(defn- stamp-file-rows
  [conn file-id deleted-at]
  (doseq [table file-row-tables]
    (if (nil? deleted-at)
      (db/update! conn table
                  {:deleted-at nil}
                  {:file-id file-id}
                  {::db/return-keys false})
      (db/update! conn table
                  {:deleted-at deleted-at}
                  ["file_id = ? AND (deleted_at IS NULL OR deleted_at > ?)"
                   file-id deleted-at]
                  {::db/return-keys false}))))

(declare update-cascade)

(defn- run-file
  [{:keys [::db/conn] :as cfg} id {:keys [deleted-at absorb-file!]}]
  (let [row (db/get* conn :file {:id id}
                     {::db/remove-deleted false
                      ::sql/columns [:id :is-shared]})]
    (when row
      (l/trc :obj "file" :id (str id)
             :deleted-at (some-> deleted-at ct/format-inst))
      (if (nil? deleted-at)
        (do
          (db/update! conn :file
                      {:deleted-at nil
                       :has-media-trimmed false}
                      {:id id}
                      {::db/return-keys false})
          (stamp-file-rows conn id nil))
        (let [stamped? (pos? (db/get-update-count
                              (db/update! conn :file
                                          {:deleted-at deleted-at
                                           :is-shared false}
                                          ["id = ? AND (deleted_at IS NULL OR deleted_at > ?)"
                                           id deleted-at]
                                          {::db/return-keys false})))]
          (stamp-file-rows conn id deleted-at)
          (when (and stamped?
                     (:is-shared row)
                     (not *team-deletion*)
                     (some? absorb-file!))
            ;; NOTE: we don't prevent file deletion on absorb
            ;; operation failure
            (try
              (absorb-file! cfg id)
              (catch Throwable cause
                (l/warn :hint "error on absorbing library"
                        :file-id (str id)
                        :cause cause)))))))))

(defn- run-project
  [{:keys [::db/conn] :as cfg} id {:keys [deleted-at] :as opts}]
  (l/trc :obj "project" :id (str id)
         :deleted-at (some-> deleted-at ct/format-inst))
  (if (nil? deleted-at)
    (db/update! conn :project
                {:deleted-at nil}
                {:id id}
                {::db/return-keys false})
    (db/update! conn :project
                {:deleted-at deleted-at}
                ["id = ? AND (deleted_at IS NULL OR deleted_at > ?)"
                 id deleted-at]
                {::db/return-keys false}))
  (doseq [file (child-files conn id)]
    (update-cascade cfg :file (:id file) opts)))

(defn- run-team
  [{:keys [::db/conn] :as cfg} id {:keys [deleted-at] :as opts}]
  (l/trc :obj "team" :id (str id)
         :deleted-at (some-> deleted-at ct/format-inst))
  (if (nil? deleted-at)
    (do
      (db/update! conn :team
                  {:deleted-at nil}
                  {:id id}
                  {::db/return-keys false})
      (db/update! conn :team-font-variant
                  {:deleted-at nil}
                  {:team-id id}
                  {::db/return-keys false}))
    (do
      (db/update! conn :team
                  {:deleted-at deleted-at}
                  ["id = ? AND (deleted_at IS NULL OR deleted_at > ?)"
                   id deleted-at]
                  {::db/return-keys false})
      (db/update! conn :team-font-variant
                  {:deleted-at deleted-at}
                  ["team_id = ? AND (deleted_at IS NULL OR deleted_at > ?)"
                   id deleted-at]
                  {::db/return-keys false})))
  (binding [*team-deletion* true]
    (doseq [project (child-projects conn id)]
      (update-cascade cfg :project (:id project) opts))))

(defn- run-profile
  [{:keys [::db/conn] :as cfg} id {:keys [deleted-at] :as opts}]
  (l/trc :obj "profile" :id (str id)
         :deleted-at (some-> deleted-at ct/format-inst))
  (if (nil? deleted-at)
    (db/update! conn :profile
                {:deleted-at nil}
                {:id id}
                {::db/return-keys false})
    (db/update! conn :profile
                {:deleted-at deleted-at}
                ["id = ? AND (deleted_at IS NULL OR deleted_at > ?)"
                 id deleted-at]
                {::db/return-keys false}))
  (doseq [team (child-teams conn id)]
    (update-cascade cfg :team (:id team) opts)))

(defn update-cascade
  "Walk the whole object tree stamping `deleted-at`: a timestamp
  deletes keeping the earliest date, nil restores. The `:absorb-file!`
  hook `(fn [cfg id])` runs when a shared file transitions from live
  to deleted; failures never stop the walk.

  `cfg` must carry `::db/conn`: call inside a transaction, like the
  worker and the admin commands do."
  [cfg object id opts]
  (case object
    :file    (run-file cfg id opts)
    :project (run-project cfg id opts)
    :team    (run-team cfg id opts)
    :profile (run-profile cfg id opts))
  nil)

;; ----------------------------------------------------------------
;; Ancestor chain restore
;; ----------------------------------------------------------------

(def ^:private sql:live-owner-exists
  "SELECT EXISTS(SELECT 1
                   FROM team_profile_rel AS tp
                   JOIN profile AS p ON (p.id = tp.profile_id)
                  WHERE tp.team_id = ?
                    AND tp.is_owner IS TRUE
                    AND p.deleted_at IS NULL) AS has_live_owner")

(defn check-live-owner
  "Raise unless `team-id` has at least one owner whose profile is
  not deleted. Restoring into an ownerless team would leave content
  nobody can manage; the caller must restore the owner profile
  first. Runs before any write, so a refusal leaves nothing behind."
  [conn team-id]
  (let [{:keys [has-live-owner]} (db/exec-one! conn [sql:live-owner-exists team-id])]
    (when-not has-live-owner
      (ex/raise :type :validation
                :code :team-has-no-live-owner
                :hint (str "team " team-id " has no live owner")))))

(defn- restore-team-rows
  [conn team-id]
  (db/update! conn :team
              {:deleted-at nil}
              {:id team-id}
              {::db/return-keys false})
  (db/update! conn :team-font-variant
              {:deleted-at nil}
              {:team-id team-id}
              {::db/return-keys false}))

(defn restore-chain
  "Restore the ancestor chain of `object`/`id` up to the team: chain
  rows only, never siblings, never profiles. Returns the team id
  (`:profile` needs no chain and returns nil). Raises
  `:team-has-no-live-owner` before writing anything when the team
  has no live owner."
  [conn object id]
  (case object
    :file
    (let [{:keys [project-id]} (db/get* conn :file {:id id}
                                        {::db/remove-deleted false
                                         ::sql/columns [:id :project-id]})
          {:keys [team-id]}   (db/get* conn :project {:id project-id}
                                       {::db/remove-deleted false
                                        ::sql/columns [:id :team-id]})]
      (check-live-owner conn team-id)
      (db/update! conn :project
                  {:deleted-at nil}
                  {:id project-id}
                  {::db/return-keys false})
      (restore-team-rows conn team-id)
      team-id)

    :project
    (let [{:keys [team-id]} (db/get* conn :project {:id id}
                                     {::db/remove-deleted false
                                      ::sql/columns [:id :team-id]})]
      (check-live-owner conn team-id)
      (restore-team-rows conn team-id)
      team-id)

    :team
    (do
      (check-live-owner conn id)
      (restore-team-rows conn id)
      id)

    :profile
    nil))
