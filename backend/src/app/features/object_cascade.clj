;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.features.object-cascade
  "One description of the deletion cascade, walked in both directions.

  Deleting stamps a single timestamp down the whole tree (profile →
  teams → projects → files → rows); restoring clears it back to
  nil. The shape of the tree is shared; only the leaf writes differ,
  plus two one-way steps kept from the original code: deleting
  unshares the file and absorbs its libraries (irreversible: a
  restored file does not become shared again), while restoring
  resets the media-trimmed flag."
  (:require
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

(def ^:private sql:owned-team-ids-live
  (str sql:owned-team-ids " AND t.deleted_at IS NULL"))

(defn- child-teams
  [conn profile-id include-deleted?]
  (db/exec! conn [(if include-deleted?
                    sql:owned-team-ids
                    sql:owned-team-ids-live)
                  profile-id]))

(defn- child-projects
  [conn team-id]
  (db/query conn :project {:team-id team-id} {::sql/columns [:id]}))

(defn- child-files
  [conn project-id]
  (db/query conn :file {:project-id project-id} {::sql/columns [:id]}))

(defn- stamp-file-rows
  [conn file-id deleted-at]
  (doseq [table file-row-tables]
    (db/update! conn table
                {:deleted-at deleted-at}
                {:file-id file-id}
                {::db/return-keys false})))

(declare run-cascade)

(defn- run-file
  [{:keys [::db/conn] :as cfg} id {:keys [deleted-at recursive? absorb-file!]}]
  (let [row (db/get* conn :file {:id id}
                     {::db/remove-deleted false
                      ::sql/columns [:id :is-shared]})]
    (when row
      (l/trc :obj "file" :id (str id)
             :deleted-at (some-> deleted-at ct/format-inst))
      (if (nil? deleted-at)
        (do
          (db/update! conn :file
                      (cond-> {:deleted-at nil}
                        recursive? (assoc :has-media-trimmed false))
                      {:id id}
                      {::db/return-keys false})
          (when recursive?
            (stamp-file-rows conn id nil)))
        (do
          (db/update! conn :file
                      {:deleted-at deleted-at
                       :is-shared false}
                      {:id id}
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
                        :file-id (str id)
                        :cause cause)))))))))

(defn- run-project
  [{:keys [::db/conn] :as cfg} id {:keys [deleted-at recursive?] :as opts}]
  (l/trc :obj "project" :id (str id)
         :deleted-at (some-> deleted-at ct/format-inst))
  (db/update! conn :project
              {:deleted-at deleted-at}
              {:id id}
              {::db/return-keys false})
  (when recursive?
    (doseq [file (child-files conn id)]
      (run-cascade cfg :file (:id file) opts))))

(defn- run-team
  [{:keys [::db/conn] :as cfg} id {:keys [deleted-at recursive?] :as opts}]
  (l/trc :obj "team" :id (str id)
         :deleted-at (some-> deleted-at ct/format-inst))
  (db/update! conn :team
              {:deleted-at deleted-at}
              {:id id}
              {::db/return-keys false})
  (db/update! conn :team-font-variant
              {:deleted-at deleted-at}
              {:team-id id}
              {::db/return-keys false})
  (when recursive?
    (binding [*team-deletion* true]
      (doseq [project (child-projects conn id)]
        (run-cascade cfg :project (:id project) opts)))))

(defn- run-profile
  [{:keys [::db/conn] :as cfg} id {:keys [deleted-at recursive?] :as opts}]
  (l/trc :obj "profile" :id (str id)
         :deleted-at (some-> deleted-at ct/format-inst))
  (db/update! conn :profile
              {:deleted-at deleted-at}
              {:id id}
              {::db/return-keys false})
  (when recursive?
    (doseq [team (child-teams conn id (nil? deleted-at))]
      (run-cascade cfg :team (:id team) opts))))

(defn run-cascade
  "Walk the object tree stamping `deleted-at`: a timestamp deletes,
  nil restores. With `recursive?` false only the object's own tables
  are stamped. The `:absorb-file!` hook `(fn [cfg id])` runs after a
  shared file is stamped on delete; failures never stop the walk.

  `cfg` must carry `::db/conn`: call inside a transaction, like the
  worker and the admin commands do."
  [cfg object id opts]
  ;; NOTE: normalize here, because `:or` defaults do not travel
  ;; inside the `:as`-bound map to the nested levels.
  (let [opts (merge {:recursive? true} opts)]
    (case object
      :file    (run-file cfg id opts)
      :project (run-project cfg id opts)
      :team    (run-team cfg id opts)
      :profile (run-profile cfg id opts)))
  nil)
