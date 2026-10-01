;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.features.object-cascade
  "One description of the deletion cascade, walked in both directions.

  Deleting stamps the operation date down the whole tree (profile →
  teams → projects → files → rows): live rows take the operation
  date, rows carrying an earlier date keep it, so a later parent
  delete never postpones their purge. The file row always stops
  being shared, like the original task did. Restoring clears dates back to
  nil.

  `file_change` is the exception: a deletion date is assigned once
  and never moved. Deleting dates only the rows that carry no date
  yet, and restoring brings back only durable history (snapshots and
  legacy payload rows, told apart by a non-null `label` or `data`).
  Revisions are born already scheduled for deletion one hour later,
  and the collector only looks at rows that carry a date, so moving
  or clearing their date would leave them in the table for ever.

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
  [:file-data
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

(defn- stamp-file-change-rows
  "Date (or clear) the `file_change` rows of a file, keeping the
  once-only rule: deleting dates only the rows that carry no date
  yet, restoring brings back only durable history (a snapshot carries
  `label`, a legacy payload row carries `data`). Revisions have
  neither and are deliberately left alone in both directions: they
  are born already scheduled for deletion one hour later, and the
  collector only looks at rows that carry a date."
  [conn file-id deleted-at]
  (if (nil? deleted-at)
    (db/update! conn :file-change
                {:deleted-at nil}
                ["file_id = ? AND (label IS NOT NULL OR data IS NOT NULL)"
                 file-id]
                {::db/return-keys false})
    (db/update! conn :file-change
                {:deleted-at deleted-at}
                {:file-id file-id :deleted-at nil}
                {::db/return-keys false})))

(defn- stamp-file-rows
  [conn file-id deleted-at]
  (stamp-file-change-rows conn file-id deleted-at)
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
  [{:keys [::db/conn] :as cfg} id {:keys [deleted-at absorb-file! report!]}]
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
        (do
          ;; The row takes the operation date only when it carries no
          ;; earlier one, so a later parent delete never postpones its
          ;; purge; but it always stops being shared, like the
          ;; original task did.
          (db/update! conn :file
                      {:is-shared false}
                      {:id id}
                      {::db/return-keys false})
          (db/update! conn :file
                      {:deleted-at deleted-at}
                      ["id = ? AND (deleted_at IS NULL OR deleted_at > ?)"
                       id deleted-at]
                      {::db/return-keys false})
          (stamp-file-rows conn id deleted-at)
          (when (and (:is-shared row)
                     (not *team-deletion*)
                     (some? absorb-file!))
            ;; NOTE: we don't prevent file deletion on absorb
            ;; operation failure
            (try
              (absorb-file! cfg id)
              (catch Throwable cause
                (l/warn :hint "error on absorbing library"
                        :file-id id
                        :cause cause))))))
      (when (and (some? deleted-at) (some? report!))
        (report! :files id)))))

(defn- run-project
  [{:keys [::db/conn] :as cfg} id {:keys [deleted-at report!] :as opts}]
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
    (update-cascade cfg :file (:id file) opts))
  (when (and (some? deleted-at) (some? report!))
    (report! :projects id)))

(def ^:private sql:restore-font-file-objects
  "UPDATE storage_object
      SET deleted_at = null
    WHERE deleted_at IS NOT NULL
      AND id IN (SELECT unnest(array[woff1_file_id, woff2_file_id, otf_file_id, ttf_file_id])
                   FROM team_font_variant
                  WHERE team_id = ?::uuid
                    AND deleted_at IS NULL)")

(defn- run-team
  [{:keys [::db/conn] :as cfg} id {:keys [deleted-at report!] :as opts}]
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
                  {::db/return-keys false})
      ;; The font objects of the variants we just brought back must
      ;; stop being marked for deletion, or the storage GC will skip
      ;; them forever (those foreign keys are `NO ACTION`).
      (db/exec-one! conn [sql:restore-font-file-objects id]))
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
      (update-cascade cfg :project (:id project) opts)))
  (when (and (some? deleted-at) (some? report!))
    (report! :teams id)))

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
  deletes, nil restores. The file row takes the operation date
  only when it carries no earlier one (project, team and row
  tables too), so a later parent delete never postpones their
  purge; the file row always stops being shared, like the original
  task did. `file_change` follows the once-only rule
  instead: deleting dates only the rows that carry no date yet, and
  restoring brings back only durable history, so a revision keeps
  the schedule it was born with. The `:absorb-file!` hook `(fn [cfg id])` runs when a
  shared file is deleted outside a team deletion; failures never
  stop the walk. The `:report!` hook `(fn [kind id])` is called with
  `:files`, `:projects` or `:teams` for every row the walk marks in
  the delete direction; the `:delete-object` task uses it to report
  the cascade as an audit event.

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
              {::db/return-keys false})
  ;; Same as in `run-team`: the font objects of the variants we just
  ;; brought back must stop being marked for deletion, or the storage
  ;; GC will skip them forever (those foreign keys are `NO ACTION`).
  (db/exec-one! conn [sql:restore-font-file-objects team-id]))

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
